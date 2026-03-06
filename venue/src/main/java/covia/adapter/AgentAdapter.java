package covia.adapter;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
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
 *   <li>{@code agent:run} — run the agent update loop (internal / testing)</li>
 * </ul>
 *
 * <p>All operations atomically replace the agent record with a new {@code ts}.
 * See {@code AGENT_LOOP.md} for design.</p>
 */
public class AgentAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(AgentAdapter.class);

	// Timeline entry timestamp keys
	private static final AString K_START = Strings.intern("start");
	private static final AString K_END   = Strings.intern("end");

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
		installAsset(BASE + "run.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		throw new UnsupportedOperationException(
			"AgentAdapter requires caller DID — use invoke(Job, ...) path");
	}

	@Override
	public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		AString callerDID = ctx.getCallerDID();
		if (callerDID == null) {
			job.fail("Agent operations require an authenticated caller");
			return;
		}

		String op = getSubOperation(meta);

		try {
			switch (op) {
				case "create":
					handleCreate(job, input, callerDID);
					break;
				case "request":
					handleRequest(job, input, ctx);
					break;
				case "message":
					handleMessage(job, input, callerDID);
					break;
				case "run":
					handleRun(job, input, ctx);
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
	private void handleCreate(Job job, ACell input, AString callerDID) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) {
			job.fail("agentId is required");
			return;
		}

		AMap<AString, ACell> config = (AMap<AString, ACell>) RT.getIn(input, Fields.CONFIG);
		ACell initialState = RT.getIn(input, AgentState.KEY_STATE);

		Users users = engine.getVenueState().users();
		User user = users.ensure(callerDID);
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
	 * reject it during a future run. An async run is scheduled immediately.</p>
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

		// Add this job's ID to the agent's task queue
		agent.addTask(job.getID());

		// Schedule an async run
		scheduleRun(agentId, ctx);
	}

	/**
	 * agent:message — deliver an ephemeral message to an agent's inbox.
	 */
	private void handleMessage(Job job, ACell input, AString callerDID) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) {
			job.fail("agentId is required");
			return;
		}

		AgentState agent = lookupAgent(job, callerDID, agentId);
		if (agent == null) return;

		agent.deliverMessage(RT.getIn(input, Fields.MESSAGE));

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.DELIVERED, CVMBool.TRUE
		));
	}

	/**
	 * agent:run — run the agent update loop.
	 *
	 * <p>Internal operation, triggered by the scheduler after agent:request or
	 * agent:message. Also callable directly for testing.</p>
	 *
	 * <p>Follows the agent update sequence from AGENT_LOOP.md §4.4.</p>
	 */
	@SuppressWarnings("unchecked")
	private void handleRun(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) {
			job.fail("agentId is required");
			return;
		}

		AString callerDID = ctx.getCallerDID();

		// Resolve transition operation — explicit input overrides agent config default
		AString transitionOp = RT.ensureString(RT.getIn(input, Fields.OPERATION));
		if (transitionOp == null) {
			AgentState agent = lookupAgent(job, callerDID, agentId);
			if (agent == null) return;
			AMap<AString, ACell> config = agent.getConfig();
			if (config != null) {
				transitionOp = RT.ensureString(config.get(Fields.OPERATION));
			}
		}
		if (transitionOp == null) {
			job.fail("operation is required (not provided and no default in agent config)");
			return;
		}
		final AString finalTransitionOp = transitionOp;

		job.setStatus(Status.STARTED);

		CompletableFuture.runAsync(() -> {
			try {
				executeRun(job, agentId, callerDID, finalTransitionOp, ctx);
			} catch (Exception e) {
				suspendOnError(callerDID, agentId, e);
				job.fail(e.getMessage());
			}
		}, VIRTUAL_EXECUTOR);
	}

	/**
	 * Core agent run logic — reads state, invokes transition, applies results.
	 */
	@SuppressWarnings("unchecked")
	private void executeRun(Job job, AString agentId, AString callerDID,
			AString transitionOp, RequestContext ctx) {
		AgentState agent = lookupAgent(job, callerDID, agentId);
		if (agent == null) return;

		// Step 1: Read current agent record
		AMap<AString, ACell> record = agent.getRecord();
		AVector<ACell> inbox = ensureVector(agent.getInbox());
		AVector<ACell> tasks = agent.getTasks();
		AVector<ACell> pending = agent.getPending();

		// Step 2: Check if there's work to do
		if (inbox.count() == 0 && tasks.count() == 0) {
			job.completeWith(Maps.of(
				Fields.AGENT_ID, agentId,
				Fields.STATUS, agent.getStatus()
			));
			return;
		}

		// Resolve job data from JobManager
		AVector<ACell> resolvedTasks = resolveJobIds(tasks, Fields.INPUT);
		AVector<ACell> resolvedPending = resolveJobIds(pending, Fields.OUTPUT);

		// Step 3: Set status to RUNNING
		long startTs = Utils.getCurrentTimestamp();
		agent.setStatus(AgentState.RUNNING);

		// Step 4: Invoke transition function
		AMap<AString, ACell> transitionInput = Maps.of(
			Fields.AGENT_ID, agentId,
			AgentState.KEY_STATE, agent.getState(),
			Fields.TASKS, resolvedTasks,
			Fields.PENDING, resolvedPending,
			Fields.MESSAGES, inbox
		);

		Job transitionJob = engine.jobs().invokeOperation(
			transitionOp, transitionInput, ctx);
		ACell transitionResult = transitionJob.awaitResult();

		// Step 5: On success — process results and update record
		long endTs = Utils.getCurrentTimestamp();
		ACell newState = RT.getIn(transitionResult, AgentState.KEY_STATE);
		ACell result = RT.getIn(transitionResult, Fields.RESULT);

		// Collect taskResults from transition output
		AMap<AString, ACell> taskResults = null;
		ACell taskResultsCell = RT.getIn(transitionResult, Fields.TASK_RESULTS);
		if (taskResultsCell instanceof AMap) {
			taskResults = (AMap<AString, ACell>) taskResultsCell;
		}

		// Remove completed tasks from the queue
		AVector<ACell> updatedTasks = removeCompletedTasks(tasks, taskResults);

		// Build timeline entry
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

		AVector<ACell> timeline = ensureVector(agent.getTimeline());

		// Write complete agent record BEFORE completing task Jobs.
		// This ensures callers who awaitResult() on task Jobs see
		// consistent agent state (tasks removed, timeline updated).
		AMap<AString, ACell> newRecord = record
			.assoc(AgentState.KEY_STATE, newState)
			.assoc(AgentState.KEY_TASKS, updatedTasks)
			.assoc(AgentState.KEY_INBOX, Vectors.empty())
			.assoc(AgentState.KEY_TIMELINE, timeline.conj(timelineEntry))
			.assoc(AgentState.KEY_STATUS, AgentState.SLEEPING)
			.dissoc(AgentState.KEY_ERROR);
		agent.putRecord(newRecord);

		// Now complete the task Jobs — unblocks callers waiting on them
		if (taskResults != null) {
			applyTaskResults(taskResults);
		}

		job.completeWith(Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.STATUS, AgentState.SLEEPING,
			Fields.RESULT, result
		));
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
	 * Looks up an agent by ID for the given user, failing the job on error.
	 *
	 * @return the AgentState, or null if the job was failed
	 */
	private AgentState lookupAgent(Job job, AString callerDID, AString agentId) {
		Users users = engine.getVenueState().users();
		User user = users.get(callerDID);
		if (user == null) {
			job.fail("User not found: " + callerDID);
			return null;
		}
		AgentState agent = user.agent(agentId);
		if (agent == null) {
			job.fail("Agent not found: " + agentId);
			return null;
		}
		if (AgentState.TERMINATED.equals(agent.getStatus())) {
			job.fail("Agent is terminated: " + agentId);
			return null;
		}
		return agent;
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
	 * Resolves a vector of Job IDs to maps containing jobId, status, and a
	 * payload field (e.g. {@code Fields.INPUT} for tasks, {@code Fields.OUTPUT}
	 * for pending).
	 */
	private AVector<ACell> resolveJobIds(AVector<ACell> ids, AString payloadField) {
		if (ids == null || ids.count() == 0) return Vectors.empty();
		AVector<ACell> resolved = Vectors.empty();
		for (long i = 0; i < ids.count(); i++) {
			Blob jobId = tryParseID(ids.get(i));
			if (jobId == null) continue;
			AMap<AString, ACell> jobData = engine.jobs().getJobData(jobId);
			AMap<AString, ACell> info = Maps.of(
				Fields.JOB_ID, jobIdHex(jobId),
				Fields.STATUS, (jobData != null) ? jobData.get(Fields.STATUS) : null,
				payloadField, (jobData != null) ? jobData.get(payloadField) : null
			);
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
	 * Removes completed/rejected task IDs from the tasks vector.
	 */
	private AVector<ACell> removeCompletedTasks(AVector<ACell> tasks, AMap<AString, ACell> taskResults) {
		if (taskResults == null || tasks == null) return ensureVector(tasks);
		AVector<ACell> remaining = Vectors.empty();
		for (long i = 0; i < tasks.count(); i++) {
			ACell taskId = tasks.get(i);
			Blob jobId = tryParseID(taskId);
			if (jobId == null) continue;
			// Keep the task if it wasn't in taskResults (key is hex AString)
			if (taskResults.get(jobIdHex(jobId)) == null) {
				remaining = remaining.conj(taskId);
			}
		}
		return remaining;
	}

	/**
	 * Schedules an async agent run after a request or message delivery.
	 */
	private void scheduleRun(AString agentId, RequestContext ctx) {
		CompletableFuture.runAsync(() -> {
			try {
				engine.jobs().invokeOperation(
					"agent:run", Maps.of(Fields.AGENT_ID, agentId), ctx);
			} catch (Exception e) {
				log.warn("Scheduled run failed for agent {}: {}", agentId, e.getMessage());
			}
		}, VIRTUAL_EXECUTOR);
	}
}
