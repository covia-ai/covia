package covia.adapter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.AgentState;
import covia.venue.RequestContext;
import covia.venue.User;
import covia.venue.Users;

/**
 * Adapter for agent lifecycle management.
 *
 * <p>Provides operations:</p>
 * <ul>
 *   <li>{@code agent:create} — create and initialise an agent</li>
 *   <li>{@code agent:request} — submit a persistent task to an agent</li>
 *   <li>{@code agent:message} — deliver an ephemeral message to an agent's inbox</li>
 *   <li>{@code agent:trigger} — trigger the agent update loop and wait for completion</li>
 * </ul>
 *
 * <p>Run scheduling is lattice-native: the agent's {@code status} field
 * (RUNNING / SLEEPING) is the source of truth for run exclusion. A per-agent
 * lock serialises all record mutations (addTask, deliverMessage, run loop
 * writes) so there are no lost updates. No separate Java-side flags.</p>
 *
 * <p>See {@code AGENT_LOOP.md} for design.</p>
 */
public class AgentAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(AgentAdapter.class);

	// Timeline entry timestamp keys
	private static final AString K_START = Strings.intern("start");
	private static final AString K_END   = Strings.intern("end");

	// Per-agent lock for serialising all record mutations
	private final ConcurrentHashMap<String, Object> agentLocks = new ConcurrentHashMap<>();

	// Per-agent completion futures — triggers park on these to wait for the current run
	private final ConcurrentHashMap<String, CompletableFuture<ACell>> runCompletions = new ConcurrentHashMap<>();

	Object agentLock(AString agentId) {
		return agentLocks.computeIfAbsent(agentId.toString(), k -> new Object());
	}

	@Override
	public String getName() {
		return "agent";
	}

	@Override
	public String getDescription() {
		return "Manages agent lifecycle: create agents, submit requests, deliver messages, "
			+ "and run their transition loop. Agents are per-user, identified by "
			+ "human-readable names, with persistent state in the lattice.";
	}

	@Override
	protected void installAssets() {
		String BASE = "/adapters/agent/";
		installAsset(BASE + "create.json");
		installAsset(BASE + "request.json");
		installAsset(BASE + "message.json");
		installAsset(BASE + "trigger.json");
		installAsset(BASE + "query.json");
		installAsset(BASE + "list.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		throw new UnsupportedOperationException(
			"AgentAdapter requires caller DID — use invoke(Job, ...) path");
	}

	@Override
	public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		if (ctx.getCallerDID() == null) {
			job.fail("Agent operations require an authenticated caller");
			return;
		}

		String op = getSubOperation(meta);

		try {
			switch (op) {
				case "create":
					handleCreate(job, input, ctx);
					break;
				case "request":
					handleRequest(job, input, ctx);
					break;
				case "message":
					handleMessage(job, input, ctx);
					break;
				case "trigger":
					handleTrigger(job, input, ctx);
					break;
				case "query":
					handleQuery(job, input, ctx);
					break;
				case "list":
					handleList(job, ctx);
					break;
				default:
					job.fail("Unknown agent operation: " + op);
			}
		} catch (Exception e) {
			job.fail(e.getMessage());
		}
	}

	/**
	 * agent:create — create and initialise an agent.
	 */
	@SuppressWarnings("unchecked")
	private void handleCreate(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) {
			job.fail("agentId is required");
			return;
		}

		AMap<AString, ACell> config = (AMap<AString, ACell>) RT.getIn(input, Fields.CONFIG);
		ACell initialState = RT.getIn(input, AgentState.KEY_STATE);

		Users users = engine.getVenueState().users();
		User user = users.ensure(ctx.getCallerDID());
		user.ensureAgent(agentId, config, initialState);

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.STATUS, AgentState.SLEEPING
		));
	}

	/**
	 * agent:request — submit a persistent task to an agent.
	 *
	 * <p>The Job created for this invocation IS the task. It is left in STARTED
	 * state and added to the agent's tasks queue. The agent will complete or
	 * reject it during a future run.</p>
	 */
	private void handleRequest(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) {
			job.fail("agentId is required");
			return;
		}

		AgentState agent = lookupAgent(job, ctx.getCallerDID(), agentId);
		if (agent == null) return;

		// The request job stays in STARTED — agent will complete it
		job.setStatus(Status.STARTED);

		// Add task inside the lock to serialise with run loop writes
		AMap<AString, ACell> snapshot = engine.jobs().getJobData(job.getID());
		synchronized (agentLock(agentId)) {
			agent.addTask(job.getID(), snapshot);
		}

		// Wake the agent
		wakeAgent(agentId, ctx);
	}

	/**
	 * agent:message — deliver an ephemeral message to an agent's inbox.
	 */
	private void handleMessage(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) {
			job.fail("agentId is required");
			return;
		}

		AgentState agent = lookupAgent(job, ctx.getCallerDID(), agentId);
		if (agent == null) return;

		// Deliver inside the lock to serialise with run loop writes
		synchronized (agentLock(agentId)) {
			agent.deliverMessage(RT.getIn(input, Fields.MESSAGE));
		}

		// Wake the agent
		wakeAgent(agentId, ctx);

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.DELIVERED, CVMBool.TRUE
		));
	}

	/**
	 * agent:query — read an agent's full record.
	 */
	private void handleQuery(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) {
			job.fail("agentId is required");
			return;
		}

		Users users = engine.getVenueState().users();
		User user = users.get(ctx.getCallerDID());
		if (user == null) {
			job.fail("User not found: " + ctx.getCallerDID());
			return;
		}

		AgentState agent = user.agent(agentId);
		if (agent == null) {
			job.fail("Agent not found: " + agentId);
			return;
		}

		AMap<AString, ACell> record = agent.getRecord();
		if (record == null) {
			job.fail("Agent not found: " + agentId);
			return;
		}

		// Return the full record with agentId included
		job.setStatus(Status.STARTED);
		job.completeWith(record.assoc(Fields.AGENT_ID, agentId));
	}

	/**
	 * agent:list — list all agents for the authenticated user.
	 */
	@SuppressWarnings("unchecked")
	private void handleList(Job job, RequestContext ctx) {
		Users users = engine.getVenueState().users();
		User user = users.get(ctx.getCallerDID());

		AVector<ACell> agents = Vectors.empty();
		if (user != null) {
			AMap<AString, ACell> agentMap = user.getAgents();
			if (agentMap != null) {
				for (var entry : agentMap.entrySet()) {
					AString agentId = entry.getKey();
					ACell value = entry.getValue();
					if (!(value instanceof AMap)) continue;
					AMap<AString, ACell> record = (AMap<AString, ACell>) value;

					long taskCount = 0;
					ACell tasksCell = record.get(AgentState.KEY_TASKS);
					if (tasksCell instanceof Index) {
						taskCount = ((Index<?, ?>) tasksCell).count();
					}

					AMap<AString, ACell> summary = Maps.of(
						Fields.AGENT_ID, agentId,
						Fields.STATUS, record.get(AgentState.KEY_STATUS),
						Fields.TASKS, CVMLong.create(taskCount)
					);
					ACell error = record.get(AgentState.KEY_ERROR);
					if (error != null) {
						summary = summary.assoc(Fields.ERROR, error);
					}
					agents = agents.conj(summary);
				}
			}
		}

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(Strings.intern("agents"), agents));
	}

	/**
	 * agent:trigger — trigger the agent update loop and wait for completion.
	 *
	 * <p>If the agent is SLEEPING, starts the run loop and the trigger Job
	 * completes when the loop finishes — even if there is no pending work
	 * (the transition function decides what to do). If the agent is already
	 * RUNNING, the trigger parks on the existing completion future and
	 * completes when the current run finishes.</p>
	 *
	 * <p>The transition operation always comes from the agent's
	 * {@code config.operation} — callers cannot override it.</p>
	 */
	private void handleTrigger(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) {
			job.fail("agentId is required");
			return;
		}

		CompletableFuture<ACell> completion0 = null;
		boolean startRun = false;
		AString transitionOp = null;

		synchronized (agentLock(agentId)) {
			AgentState agent = getAgent(ctx.getCallerDID(), agentId);
			if (agent == null) {
				job.fail("Agent not found: " + agentId);
				return;
			}

			AString status = agent.getStatus();
			if (AgentState.SUSPENDED.equals(status)) {
				job.fail("Agent is suspended: " + agentId);
				return;
			}

			if (AgentState.RUNNING.equals(status)) {
				// Park on existing completion future
				completion0 = runCompletions.get(agentId.toString());
				if (completion0 == null) {
					// Defensive — shouldn't happen if RUNNING was set properly
					completion0 = new CompletableFuture<>();
					runCompletions.put(agentId.toString(), completion0);
				}
			} else {
				// SLEEPING — always start the loop, even with no work.
				// The transition function decides what to do (may act proactively).
				transitionOp = resolveTransitionOp(ctx.getCallerDID(), agentId);
				if (transitionOp == null) {
					job.fail("No transition operation configured for agent: " + agentId);
					return;
				}

				completion0 = new CompletableFuture<>();
				runCompletions.put(agentId.toString(), completion0);
				agent.setStatus(AgentState.RUNNING);
				startRun = true;
			}
		}

		// Effectively final for lambda capture
		final CompletableFuture<ACell> completion = completion0;
		final AString finalOp = transitionOp;

		job.setStatus(Status.STARTED);

		// Subscribe to completion — fires when the run loop finishes
		completion.whenComplete((result, ex) -> {
			if (ex != null) {
				job.fail(ex.getMessage());
			} else {
				job.completeWith(result);
			}
		});

		if (startRun) {
			CompletableFuture.runAsync(() -> {
				executeRunLoop(agentId, ctx.getCallerDID(), finalOp, ctx, completion);
			}, VIRTUAL_EXECUTOR);
		}
	}

	// ========== Wake and run loop ==========

	/**
	 * Wakes an agent — checks the lattice status and starts the run loop if
	 * the agent is SLEEPING and has work.
	 *
	 * <p>This is the single entry point for all agent wakes: request, message,
	 * async job completion. The per-agent lock serialises the status check with
	 * record mutations, eliminating lost wakeups.</p>
	 *
	 * <p>If the agent is already RUNNING, the new work is already in the lattice
	 * (inbox/tasks) and the running loop will pick it up on its next iteration
	 * check. No separate wake flag needed.</p>
	 *
	 * <p>A completion future is created so that any {@code agent:trigger} that
	 * arrives while the agent is running can park on it.</p>
	 *
	 * <p>Package-private so that {@link LLMAgentAdapter} can call it from async
	 * completion callbacks.</p>
	 */
	void wakeAgent(AString agentId, RequestContext ctx) {
		AString callerDID = ctx.getCallerDID();
		AString transitionOp;
		CompletableFuture<ACell> completion;

		synchronized (agentLock(agentId)) {
			AgentState agent = getAgent(callerDID, agentId);
			if (agent == null) return;
			if (AgentState.RUNNING.equals(agent.getStatus())) return;
			if (!hasWork(agent)) return;

			transitionOp = resolveTransitionOp(callerDID, agentId);
			if (transitionOp == null) return;

			agent.setStatus(AgentState.RUNNING);
			completion = new CompletableFuture<>();
			runCompletions.put(agentId.toString(), completion);
		}

		final AString finalTransitionOp = transitionOp;
		CompletableFuture.runAsync(() -> {
			executeRunLoop(agentId, callerDID, finalTransitionOp, ctx, completion);
		}, VIRTUAL_EXECUTOR);
	}

	/**
	 * Checks whether an agent has work to do (non-empty inbox or tasks).
	 */
	private static boolean hasWork(AgentState agent) {
		AVector<ACell> inbox = agent.getInbox();
		if (inbox != null && inbox.count() > 0) return true;
		Index<Blob, ACell> tasks = agent.getTasks();
		return tasks != null && tasks.count() > 0;
	}

	/**
	 * Core agent run loop — reads state, invokes transition, merges results.
	 *
	 * <p>The loop re-reads the current record at write time and merges changes
	 * to avoid overwriting tasks or messages added concurrently. Only the
	 * specific messages processed in this iteration are removed; new messages
	 * appended during the transition are preserved.</p>
	 *
	 * <p>Status transitions use the lattice: the caller sets RUNNING before
	 * entry; the loop sets SLEEPING on exit (inside the lock). If new work
	 * arrived during the run, the loop continues instead of exiting.</p>
	 *
	 * <p>On completion (success or failure), notifies waiting triggers via the
	 * provided {@code completion} future.</p>
	 */
	@SuppressWarnings("unchecked")
	private void executeRunLoop(AString agentId, AString callerDID,
			AString transitionOp, RequestContext ctx,
			CompletableFuture<ACell> completion) {
		ACell lastResult = null;
		boolean firstIteration = true;

		try {
		while (true) {
			// Snapshot inputs inside the lock for a consistent read
			AMap<AString, ACell> record;
			AVector<ACell> inbox;
			Index<Blob, ACell> tasks;
			Index<Blob, ACell> pending;
			ACell currentState;

			synchronized (agentLock(agentId)) {
				AgentState agent = getAgent(callerDID, agentId);
				if (agent == null) {
					completeRunExceptionally(completion, agentId, "Agent not found: " + agentId);
					return;
				}

				record = agent.getRecord();
				inbox = ensureVector(agent.getInbox());
				tasks = agent.getTasks();
				pending = agent.getPending();
				currentState = agent.getState();

				// On subsequent iterations, exit if no more work arrived.
				// First iteration always runs — the transition decides what to do.
				if (!firstIteration && inbox.count() == 0 && tasks.count() == 0) {
					agent.setStatus(AgentState.SLEEPING);
					ACell finalResult = (lastResult != null) ? lastResult : Maps.of(
						Fields.AGENT_ID, agentId,
						Fields.STATUS, AgentState.SLEEPING
					);
					completeRun(completion, agentId, finalResult);
					return;
				}
			}
			firstIteration = false;

			long startTs = Utils.getCurrentTimestamp();

			// Resolve job data (no lock needed — read-only from JobManager)
			AVector<ACell> resolvedTasks = resolveJobIds(tasks, Fields.INPUT);
			AVector<ACell> resolvedPending = resolveJobIds(pending, Fields.OUTPUT);

			// Invoke transition function — NO LOCK held during this call
			AMap<AString, ACell> transitionInput = Maps.of(
				Fields.AGENT_ID, agentId,
				AgentState.KEY_STATE, currentState,
				Fields.TASKS, resolvedTasks,
				Fields.PENDING, resolvedPending,
				Fields.MESSAGES, inbox
			);

			Job transitionJob = engine.jobs().invokeOperation(
				transitionOp, transitionInput, ctx);
			ACell transitionResult = transitionJob.awaitResult();

			// Process results
			long endTs = Utils.getCurrentTimestamp();
			ACell newState = RT.getIn(transitionResult, AgentState.KEY_STATE);
			ACell result = RT.getIn(transitionResult, Fields.RESULT);

			AMap<AString, ACell> taskResults = null;
			ACell taskResultsCell = RT.getIn(transitionResult, Fields.TASK_RESULTS);
			if (taskResultsCell instanceof AMap) {
				taskResults = (AMap<AString, ACell>) taskResultsCell;
			}

			AMap<AString, ACell> timelineEntry = Maps.of(
				K_START, CVMLong.create(startTs),
				K_END, CVMLong.create(endTs),
				Fields.OP, transitionOp,
				AgentState.KEY_STATE, record.get(AgentState.KEY_STATE),
				Fields.TASKS, resolvedTasks,
				Fields.MESSAGES, inbox,
				Fields.RESULT, result
			);
			if (taskResults != null) {
				timelineEntry = timelineEntry.assoc(Fields.TASK_RESULTS, taskResults);
			}

			// Merge with current state inside the lock
			boolean hasMoreWork;
			synchronized (agentLock(agentId)) {
				AgentState agent = getAgent(callerDID, agentId);
				if (agent == null) {
					completeRunExceptionally(completion, agentId, "Agent not found: " + agentId);
					return;
				}

				// Remove completed tasks from CURRENT tasks (not stale snapshot)
				Index<Blob, ACell> currentTasks = agent.getTasks();
				Index<Blob, ACell> updatedTasks = removeCompletedTasks(currentTasks, taskResults);

				// Preserve messages appended during the transition
				AVector<ACell> currentInbox = ensureVector(agent.getInbox());
				long processedCount = inbox.count();
				AVector<ACell> remainingInbox = Vectors.empty();
				for (long i = processedCount; i < currentInbox.count(); i++) {
					remainingInbox = remainingInbox.conj(currentInbox.get(i));
				}

				AVector<ACell> timeline = ensureVector(agent.getTimeline());
				hasMoreWork = (updatedTasks.count() > 0 || remainingInbox.count() > 0);

				AMap<AString, ACell> newRecord = agent.getRecord()
					.assoc(AgentState.KEY_STATE, newState)
					.assoc(AgentState.KEY_TASKS, updatedTasks)
					.assoc(AgentState.KEY_INBOX, remainingInbox)
					.assoc(AgentState.KEY_TIMELINE, timeline.conj(timelineEntry))
					.assoc(AgentState.KEY_STATUS, hasMoreWork ? AgentState.RUNNING : AgentState.SLEEPING)
					.dissoc(AgentState.KEY_ERROR);
				agent.putRecord(newRecord);
			}

			// Complete task Jobs AFTER releasing the lock
			if (taskResults != null) {
				applyTaskResults(taskResults);
			}

			lastResult = Maps.of(
				Fields.AGENT_ID, agentId,
				Fields.STATUS, AgentState.SLEEPING,
				Fields.RESULT, result
			);

			if (!hasMoreWork) {
				completeRun(completion, agentId, lastResult);
				return;
			}
		}
		} catch (Exception e) {
			synchronized (agentLock(agentId)) {
				suspendOnError(callerDID, agentId, e);
			}
			completeRunExceptionally(completion, agentId, e.getMessage());
		}
	}

	/**
	 * Sets the agent to SUSPENDED with the given error, preserving all queues.
	 */
	private void suspendOnError(AString callerDID, AString agentId, Exception e) {
		try {
			Users users = engine.getVenueState().users();
			User user = users.get(callerDID);
			if (user != null) {
				AgentState agent = user.agent(agentId);
				if (agent != null) {
					agent.setError(Strings.create(e.getMessage()));
					agent.setStatus(AgentState.SUSPENDED);
				}
			}
		} catch (Exception inner) {
			log.warn("Failed to set agent error state", inner);
		}
	}

	// ========== Agent lookup ==========

	/**
	 * Looks up an agent, returning null on any failure. No side effects.
	 */
	private AgentState getAgent(AString callerDID, AString agentId) {
		Users users = engine.getVenueState().users();
		User user = users.get(callerDID);
		if (user == null) return null;
		AgentState agent = user.agent(agentId);
		if (agent == null) return null;
		if (AgentState.TERMINATED.equals(agent.getStatus())) return null;
		return agent;
	}

	/**
	 * Looks up an agent by ID for the given user, failing the job on error.
	 *
	 * @return the AgentState, or null if the job was failed
	 */
	private AgentState lookupAgent(Job job, AString callerDID, AString agentId) {
		AgentState agent = getAgent(callerDID, agentId);
		if (agent == null) {
			job.fail("Agent not found or terminated: " + agentId);
		}
		return agent;
	}

	/**
	 * Resolves the default transition operation from the agent's config.
	 */
	private AString resolveTransitionOp(AString callerDID, AString agentId) {
		AgentState agent = getAgent(callerDID, agentId);
		if (agent == null) return null;
		AMap<AString, ACell> config = agent.getConfig();
		if (config == null) return null;
		return RT.ensureString(config.get(Fields.OPERATION));
	}

	/**
	 * Parses a cell as a Job ID blob, returning null on failure.
	 */
	private static Blob tryParseID(ACell cell) {
		try {
			return Job.parseID(cell);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Converts a Job ID blob to its hex AString representation.
	 */
	private static AString jobIdHex(Blob jobId) {
		return Strings.create(jobId.toHexString());
	}

	// ========== Internal helpers ==========

	/**
	 * Resolves an Index of Job IDs to a vector of maps containing jobId, status,
	 * and a payload field (e.g. {@code Fields.INPUT} for tasks,
	 * {@code Fields.OUTPUT} for pending).
	 *
	 * <p>Each resolved entry includes the Index snapshot value. If JobManager
	 * data is unavailable (catastrophic failure), the snapshot provides a
	 * durable fallback so the agent can observe what was requested/invoked.</p>
	 */
	private AVector<ACell> resolveJobIds(Index<Blob, ACell> ids, AString payloadField) {
		if (ids == null || ids.count() == 0) return Vectors.empty();
		AVector<ACell> resolved = Vectors.empty();
		for (var entry : ids.entrySet()) {
			Blob jobId = entry.getKey();
			ACell snapshot = entry.getValue();
			AMap<AString, ACell> jobData = engine.jobs().getJobData(jobId);
			ACell payload = (jobData != null) ? jobData.get(payloadField) : snapshot;
			AMap<AString, ACell> info = Maps.of(
				Fields.JOB_ID, jobIdHex(jobId),
				Fields.STATUS, (jobData != null) ? jobData.get(Fields.STATUS) : null,
				payloadField, payload
			);
			if (snapshot != null) {
				info = info.assoc(Fields.SNAPSHOT, snapshot);
			}
			resolved = resolved.conj(info);
		}
		return resolved;
	}

	/**
	 * Returns the given vector, or an empty vector if null.
	 */
	private static AVector<ACell> ensureVector(AVector<ACell> v) {
		return (v != null) ? v : Vectors.empty();
	}

	/**
	 * Applies task results from transition output to the JobManager.
	 */
	@SuppressWarnings("unchecked")
	private void applyTaskResults(AMap<AString, ACell> taskResults) {
		for (var entry : taskResults.entrySet()) {
			Blob jobId = tryParseID(entry.getKey());
			if (jobId == null) continue;

			Job taskJob = engine.jobs().getJob(jobId);
			if (taskJob == null || taskJob.isFinished()) continue;

			ACell resultData = entry.getValue();
			AString status = RT.ensureString(RT.getIn(resultData, Fields.STATUS));
			if (status != null && Status.FAILED.equals(status)) {
				AString reason = RT.ensureString(RT.getIn(resultData, Fields.ERROR));
				taskJob.fail((reason != null) ? reason.toString() : "Rejected by agent");
			} else {
				taskJob.completeWith(RT.getIn(resultData, Fields.OUTPUT));
			}
		}
	}

	/**
	 * Removes completed/rejected task IDs from the tasks index.
	 */
	private Index<Blob, ACell> removeCompletedTasks(Index<Blob, ACell> tasks, AMap<AString, ACell> taskResults) {
		if (taskResults == null || tasks == null) return (tasks != null) ? tasks : Index.none();
		Index<Blob, ACell> remaining = tasks;
		for (var entry : tasks.entrySet()) {
			Blob jobId = entry.getKey();
			// Remove the task if it was in taskResults (key is hex AString)
			if (taskResults.get(jobIdHex(jobId)) != null) {
				remaining = remaining.dissoc(jobId);
			}
		}
		return remaining;
	}

	// ========== Run completion ==========

	/**
	 * Notifies waiting triggers that a run completed successfully.
	 *
	 * <p>Uses {@code ConcurrentHashMap.remove(key, value)} so that if a new
	 * completion future was installed by a concurrent wake, only the caller's
	 * own future is removed.</p>
	 */
	private void completeRun(CompletableFuture<ACell> completion, AString agentId, ACell result) {
		runCompletions.remove(agentId.toString(), completion);
		completion.complete(result);
	}

	/**
	 * Notifies waiting triggers that a run failed.
	 */
	private void completeRunExceptionally(CompletableFuture<ACell> completion,
			AString agentId, String message) {
		runCompletions.remove(agentId.toString(), completion);
		completion.completeExceptionally(new RuntimeException(message));
	}
}
