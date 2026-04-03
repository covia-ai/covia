package covia.adapter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import covia.grid.Asset;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Hash;
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
 * <p>All agent state mutations use atomic lattice updates via
 * {@link AgentState} named methods — no per-agent locks. The only
 * coordination primitive is a per-agent {@link CompletableFuture} that
 * fires when a run cycle completes, enabling callers to wait for results.</p>
 */
public class AgentAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(AgentAdapter.class);

	private static final AString K_START = Strings.intern("start");
	private static final AString K_END   = Strings.intern("end");
	private static final AString PENDING = Strings.intern("PENDING");

	/** Maximum run loop iterations before forced exit (safety net) */
	private static final int MAX_LOOP_ITERATIONS = 20;

	/** Per-agent completion future — fires when the current run cycle finishes */
	private final ConcurrentHashMap<String, CompletableFuture<ACell>> runCompletions = new ConcurrentHashMap<>();

	/** Counter for task ID generation */
	private long taskIdCounter = 0;

	@Override public String getName() { return "agent"; }

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
		installAsset(BASE + "info.json");
		installAsset(BASE + "list.json");
		installAsset(BASE + "delete.json");
		installAsset(BASE + "suspend.json");
		installAsset(BASE + "resume.json");
		installAsset(BASE + "update.json");
		installAsset(BASE + "cancelTask.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		throw new UnsupportedOperationException("AgentAdapter requires caller DID — use invoke(Job, ...) path");
	}

	@Override
	public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		if (ctx.getCallerDID() == null) {
			job.fail("Agent operations require an authenticated caller");
			return;
		}
		try {
			switch (getSubOperation(meta)) {
				case "create"  -> handleCreate(job, input, ctx);
				case "request" -> handleRequest(job, input, ctx);
				case "message" -> handleMessage(job, input, ctx);
				case "trigger" -> handleTrigger(job, input, ctx);
				case "info"    -> handleQuery(job, input, ctx);
				case "list"    -> handleList(job, input, ctx);
				case "delete"  -> handleDelete(job, input, ctx);
				case "suspend" -> handleSuspend(job, input, ctx);
				case "resume"  -> handleResume(job, input, ctx);
				case "update"     -> handleUpdate(job, input, ctx);
				case "cancelTask" -> handleCancelTask(job, input, ctx);
				default           -> job.fail("Unknown agent operation: " + getSubOperation(meta));
			}
		} catch (Exception e) {
			job.fail(e.getMessage());
		}
	}

	// ========== Operation handlers ==========

	@SuppressWarnings("unchecked")
	private void handleCreate(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		AMap<AString, ACell> config = (AMap<AString, ACell>) RT.getIn(input, Fields.CONFIG);
		ACell initialState = RT.getIn(input, AgentState.KEY_STATE);

		// Resolve agent definition asset if provided
		AString definitionRef = RT.ensureString(RT.getIn(input, Fields.DEFINITION));
		if (definitionRef != null) {
			Asset defAsset = engine.resolveAsset(definitionRef, ctx);
			if (defAsset == null) { job.fail("Definition asset not found: " + definitionRef); return; }

			AMap<AString, ACell> defMeta = defAsset.meta();

			// Extract agent config from definition metadata
			AString defOp = RT.ensureString(RT.getIn(defMeta, Strings.intern("agent"), Fields.OPERATION));
			AMap<AString, ACell> defConfig = RT.ensureMap(RT.getIn(defMeta, Strings.intern("agent"), Fields.CONFIG));

			// Definition provides defaults; explicit params override
			if (config == null && defOp != null) {
				config = Maps.of(Fields.OPERATION, defOp);
			}
			if (initialState == null && defConfig != null) {
				initialState = Maps.of(Strings.intern("config"), defConfig);
			}

			// Store resolved asset ID in config for provenance
			if (config != null) {
				AString defID = Strings.create(defAsset.getID().toHexString());
				config = config.assoc(Fields.DEFINITION, defID);
			}
		}

		boolean overwrite = CVMBool.TRUE.equals(RT.getIn(input, Fields.OVERWRITE));

		Users users = engine.getVenueState().users();
		User user = users.ensure(ctx.getCallerDID());
		AgentState agent = user.agent(agentId);
		boolean alreadyExists = (agent != null && agent.exists());

		// If overwrite requested, only allow on TERMINATED agents
		if (alreadyExists && overwrite) {
			if (AgentState.TERMINATED.equals(agent.getStatus())) {
				user.removeAgent(agentId);
				alreadyExists = false;
			} else {
				job.fail("Cannot overwrite agent that is not TERMINATED (status: " + agent.getStatus() + ")");
				return;
			}
		}

		agent = user.ensureAgent(agentId, config, initialState);

		AMap<AString, ACell> result = Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.STATUS, agent.getStatus(),
			Fields.CREATED, CVMBool.of(!alreadyExists));

		job.setStatus(Status.STARTED);
		job.completeWith(result);
	}

	/**
	 * agent:request — submit a task to an agent.
	 *
	 * <p>The task is purely lattice data — no Job in JobManager. If {@code wait}
	 * is set, the virtual thread blocks on the run cycle's completion future
	 * and extracts the task result.</p>
	 */
	private void handleRequest(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		AgentState agent = lookupAgent(job, ctx.getCallerDID(), agentId);
		if (agent == null) return;

		// Parse wait: false/absent = async, true = indefinite, integer = timeout ms
		// Accept string "true" for LLM compatibility (agents pass strings, not booleans)
		ACell waitCell = RT.getIn(input, Fields.WAIT);
		long waitMs = 0;
		if (CVMBool.TRUE.equals(waitCell) || Strings.create("true").equals(waitCell)) waitMs = -1;
		else if (waitCell instanceof CVMLong l && l.longValue() > 0) waitMs = l.longValue();

		// Add task to agent's lattice (atomic)
		Blob taskId = generateTaskId();
		ACell taskInput = RT.getIn(input, Fields.INPUT);
		agent.addTask(taskId, taskInput);

		// Wake agent — returns the run cycle's completion future
		CompletableFuture<ACell> completion = wakeAgent(agentId, ctx);

		job.setStatus(Status.STARTED);
		AString taskIdHex = taskIdHex(taskId);

		if (waitMs != 0 && completion != null) {
			try {
				ACell cycleResult = (waitMs < 0)
					? completion.join()
					: completion.get(waitMs, TimeUnit.MILLISECONDS);
				ACell taskResult = RT.getIn(cycleResult, Fields.TASK_RESULTS, taskIdHex);
				if (taskResult != null) {
					job.completeWith(Maps.of(
						Fields.ID, taskIdHex,
						Fields.STATUS, RT.getIn(taskResult, Fields.STATUS),
						Fields.OUTPUT, RT.getIn(taskResult, Fields.OUTPUT)));
				} else {
					job.completeWith(Maps.of(Fields.ID, taskIdHex, Fields.STATUS, PENDING));
				}
			} catch (TimeoutException e) {
				job.completeWith(Maps.of(Fields.ID, taskIdHex, Fields.STATUS, PENDING));
			} catch (Exception e) {
				job.completeWith(Maps.of(Fields.ID, taskIdHex, Fields.STATUS, PENDING));
			}
		} else {
			job.completeWith(Maps.of(Fields.ID, taskIdHex, Fields.STATUS, PENDING));
		}
	}

	private void handleMessage(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		AgentState agent = lookupAgent(job, ctx.getCallerDID(), agentId);
		if (agent == null) return;

		ACell messageContent = RT.getIn(input, Fields.MESSAGE);
		// Wrap message with caller provenance
		ACell envelope = Maps.of(
			Fields.CALLER, ctx.getCallerDID(),
			Fields.MESSAGE, messageContent);
		agent.deliverMessage(envelope);
		wakeAgent(agentId, ctx);

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(Fields.AGENT_ID, agentId, Fields.DELIVERED, CVMBool.TRUE));
	}

	private void handleTrigger(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		AgentState agent = lookupAgent(job, ctx.getCallerDID(), agentId);
		if (agent == null) return;

		if (AgentState.SUSPENDED.equals(agent.getStatus())) {
			job.fail("Agent is suspended: " + agentId);
			return;
		}

		CompletableFuture<ACell> completion = tryStartRun(agentId, ctx, true);
		if (completion == null) {
			job.fail("Cannot start agent: " + agentId);
			return;
		}

		job.setStatus(Status.STARTED);
		completion.whenComplete((result, ex) -> {
			if (ex != null) job.fail(ex.getMessage());
			else job.completeWith(result);
		});
	}

	private void handleQuery(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		Users users = engine.getVenueState().users();
		User user = users.get(ctx.getCallerDID());
		if (user == null) { job.fail("User not found: " + ctx.getCallerDID()); return; }

		AgentState agent = user.agent(agentId);
		if (agent == null) { job.fail("Agent not found: " + agentId); return; }

		AMap<AString, ACell> record = agent.getRecord();
		if (record == null) { job.fail("Agent not found: " + agentId); return; }

		// Return a lightweight summary. Full state, history, and timeline
		// are accessible via covia:read path=g/<agentId>/state etc.
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> state = RT.ensureMap(record.get(AgentState.KEY_STATE));
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> stateConfig = (state != null) ? RT.ensureMap(RT.getIn(state, Strings.intern("config"))) : null;
		AVector<?> timeline = agent.getTimeline();
		Index<Blob, ACell> tasks = agent.getTasks();

		AMap<AString, ACell> summary = Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.STATUS, record.get(AgentState.KEY_STATUS),
			Fields.CONFIG, record.get(AgentState.KEY_CONFIG));

		// Include state.config (caps, context, model, prompt) but not history
		if (stateConfig != null) summary = summary.assoc(Strings.intern("stateConfig"), stateConfig);
		if (timeline != null) summary = summary.assoc(Strings.intern("timelineLength"), CVMLong.create(timeline.count()));
		if (tasks != null) summary = summary.assoc(Strings.intern("tasks"), CVMLong.create(tasks.count()));
		ACell error = record.get(AgentState.KEY_ERROR);
		if (error != null) summary = summary.assoc(AgentState.KEY_ERROR, error);

		job.setStatus(Status.STARTED);
		job.completeWith(summary);
	}

	@SuppressWarnings("unchecked")
	private void handleList(Job job, ACell input, RequestContext ctx) {
		boolean includeTerminated = CVMBool.TRUE.equals(RT.getIn(input, Fields.INCLUDE_TERMINATED));

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

					ACell status = record.get(AgentState.KEY_STATUS);
					if (!includeTerminated && AgentState.TERMINATED.equals(status)) continue;

					long taskCount = 0;
					ACell tasksCell = record.get(AgentState.KEY_TASKS);
					if (tasksCell instanceof Index) taskCount = ((Index<?, ?>) tasksCell).count();

					AMap<AString, ACell> summary = Maps.of(
						Fields.AGENT_ID, agentId,
						Fields.STATUS, status,
						Fields.TASKS, CVMLong.create(taskCount));
					ACell error = record.get(AgentState.KEY_ERROR);
					if (error != null) summary = summary.assoc(Fields.ERROR, error);
					agents = agents.conj(summary);
				}
			}
		}

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(Strings.intern("agents"), agents));
	}

	private void handleDelete(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		AgentState agent = lookupAgent(job, ctx.getCallerDID(), agentId);
		if (agent == null) return;

		boolean remove = CVMBool.TRUE.equals(RT.getIn(input, Fields.REMOVE));
		if (remove) {
			Users users = engine.getVenueState().users();
			User user = users.get(ctx.getCallerDID());
			user.removeAgent(agentId);
			job.setStatus(Status.STARTED);
			job.completeWith(Maps.of(Fields.AGENT_ID, agentId, Fields.REMOVED, CVMBool.TRUE));
		} else {
			agent.setStatus(AgentState.TERMINATED);
			job.setStatus(Status.STARTED);
			job.completeWith(Maps.of(Fields.AGENT_ID, agentId, Fields.STATUS, AgentState.TERMINATED));
		}
	}

	private void handleSuspend(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		AgentState agent = lookupAgent(job, ctx.getCallerDID(), agentId);
		if (agent == null) return;

		agent.setStatus(AgentState.SUSPENDED);

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(Fields.AGENT_ID, agentId, Fields.STATUS, AgentState.SUSPENDED));
	}

	private void handleResume(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		AgentState agent = lookupAgent(job, ctx.getCallerDID(), agentId);
		if (agent == null) return;

		// Default autoWake to true
		ACell autoWakeCell = RT.getIn(input, Fields.AUTO_WAKE);
		boolean autoWake = !(CVMBool.FALSE.equals(autoWakeCell));

		// Atomic CAS: SUSPENDED → SLEEPING, clear error
		if (!agent.tryResume()) {
			job.fail("Agent is not suspended (status: " + agent.getStatus() + ")");
			return;
		}

		if (autoWake) tryStartRun(agentId, ctx, false);

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(Fields.AGENT_ID, agentId, Fields.STATUS, AgentState.SLEEPING));
	}

	@SuppressWarnings("unchecked")
	private void handleUpdate(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		AgentState agent = lookupAgent(job, ctx.getCallerDID(), agentId);
		if (agent == null) return;

		AMap<AString, ACell> newConfig = (AMap<AString, ACell>) RT.getIn(input, Fields.CONFIG);
		ACell newState = RT.getIn(input, AgentState.KEY_STATE);
		if (newConfig == null && newState == null) {
			job.fail("At least one of 'config' or 'state' must be provided");
			return;
		}

		agent.updateConfigAndState(newConfig, newState);

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(Fields.AGENT_ID, agentId, Fields.STATUS, agent.getStatus()));
	}

	private void handleCancelTask(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		AString taskIdHex = RT.ensureString(RT.getIn(input, Fields.TASK_ID));
		if (taskIdHex == null) { job.fail("taskId is required"); return; }

		AgentState agent = lookupAgent(job, ctx.getCallerDID(), agentId);
		if (agent == null) return;

		// Parse hex task ID to Blob
		Blob taskId;
		try {
			taskId = Blob.fromHex(taskIdHex.toString());
		} catch (Exception e) {
			job.fail("Invalid taskId format: " + taskIdHex);
			return;
		}

		// Check task exists
		Index<Blob, ACell> tasks = agent.getTasks();
		if (tasks.get(taskId) == null) {
			job.fail("Task not found: " + taskIdHex);
			return;
		}

		agent.removeTask(taskId);

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.TASK_ID, taskIdHex,
			Fields.CANCELLED, CVMBool.TRUE));
	}

	// ========== Wake and run management ==========

	/**
	 * Wakes an agent — sets wake time to now, then tries to start the run loop.
	 * Returns the completion future for the run cycle (may be null if the agent
	 * can't run). Package-private so LLMAgentAdapter can call it.
	 */
	CompletableFuture<ACell> wakeAgent(AString agentId, RequestContext ctx) {
		AgentState agent = getAgent(ctx.getCallerDID(), agentId);
		if (agent == null) return null;
		agent.setWakeTime(Utils.getCurrentTimestamp());
		return tryStartRun(agentId, ctx, false);
	}

	/**
	 * Tries to start the run loop. Returns the completion future.
	 *
	 * <p>If the agent is already RUNNING, returns the existing future.
	 * If SLEEPING, performs an atomic CAS to RUNNING and starts the loop.
	 * The {@code force} flag skips the shouldWake/hasWork check (for triggers).</p>
	 */
	private CompletableFuture<ACell> tryStartRun(AString agentId, RequestContext ctx, boolean force) {
		AString callerDID = ctx.getCallerDID();

		// If already running, return existing future
		CompletableFuture<ACell> existing = runCompletions.get(agentId.toString());
		if (existing != null && !existing.isDone()) return existing;

		AgentState agent = getAgent(callerDID, agentId);
		if (agent == null) return null;
		if (!force && !agent.shouldWake() && !hasWork(agent)) return null;

		AString transitionOp = resolveTransitionOp(callerDID, agentId);
		if (transitionOp == null) return null;

		// Atomic CAS: SLEEPING → RUNNING
		if (!agent.tryStartRunning()) {
			// Was already RUNNING — return existing future
			return runCompletions.get(agentId.toString());
		}

		// We won the CAS — create future and start loop
		CompletableFuture<ACell> completion = new CompletableFuture<>();
		runCompletions.put(agentId.toString(), completion);

		final AString finalOp = transitionOp;
		CompletableFuture.runAsync(() -> {
			executeRunLoop(agentId, callerDID, finalOp, ctx, completion);
		}, VIRTUAL_EXECUTOR);

		return completion;
	}

	private static boolean hasWork(AgentState agent) {
		AVector<ACell> inbox = agent.getInbox();
		if (inbox != null && inbox.count() > 0) return true;
		Index<Blob, ACell> tasks = agent.getTasks();
		return tasks != null && tasks.count() > 0;
	}

	// ========== Run loop ==========

	@SuppressWarnings("unchecked")
	private void executeRunLoop(AString agentId, AString callerDID,
			AString transitionOp, RequestContext ctx,
			CompletableFuture<ACell> completion) {
		ACell lastResult = null;
		boolean firstIteration = true;
		int iteration = 0;
		AMap<AString, ACell> allTaskResults = Maps.empty();

		try {
		while (true) {
			if (++iteration > MAX_LOOP_ITERATIONS) {
				log.warn("Agent {} hit max loop iterations ({}), forcing sleep", agentId, MAX_LOOP_ITERATIONS);
				AgentState agent = getAgent(callerDID, agentId);
				if (agent != null) agent.setStatus(AgentState.SLEEPING);
				completeRun(completion, agentId, lastResult != null ? lastResult : Maps.of(
					Fields.AGENT_ID, agentId, Fields.STATUS, AgentState.SLEEPING,
					Fields.TASK_RESULTS, allTaskResults));
				return;
			}

			// Snapshot current state (reads are non-blocking)
			AgentState agent = getAgent(callerDID, agentId);
			if (agent == null) {
				completeRunExceptionally(completion, agentId, "Agent not found: " + agentId);
				return;
			}

			AMap<AString, ACell> record = agent.getRecord();
			AVector<ACell> inbox = ensureVector(agent.getInbox());
			Index<Blob, ACell> tasks = agent.getTasks();
			Index<Blob, ACell> pending = agent.getPending();
			ACell currentState = agent.getState();

			// On subsequent iterations, exit if no new work
			if (!firstIteration && inbox.count() == 0 && tasks.count() == 0) {
				agent.sleep();
				completeRun(completion, agentId, lastResult != null ? lastResult : Maps.of(
					Fields.AGENT_ID, agentId, Fields.STATUS, AgentState.SLEEPING,
					Fields.TASK_RESULTS, allTaskResults));
				return;
			}
			firstIteration = false;

			long startTs = Utils.getCurrentTimestamp();

			// Format tasks for the transition (tasks are raw lattice data)
			AVector<ACell> formattedTasks = formatTasks(tasks);
			AVector<ACell> resolvedPending = resolveJobIds(pending, Fields.OUTPUT);

			// Invoke transition — no lock, no coordination during this call
			AMap<AString, ACell> transitionInput = Maps.of(
				Fields.AGENT_ID, agentId,
				AgentState.KEY_STATE, currentState,
				Fields.TASKS, formattedTasks,
				Fields.PENDING, resolvedPending,
				Fields.MESSAGES, inbox);

			Job transitionJob = engine.jobs().invokeOperation(transitionOp, transitionInput, ctx);
			ACell transitionResult = transitionJob.awaitResult();

			// Process results
			long endTs = Utils.getCurrentTimestamp();
			ACell newState = RT.getIn(transitionResult, AgentState.KEY_STATE);
			ACell result = RT.getIn(transitionResult, Fields.RESULT);

			AMap<AString, ACell> taskResults = null;
			ACell taskResultsCell = RT.getIn(transitionResult, Fields.TASK_RESULTS);
			if (taskResultsCell instanceof AMap) taskResults = (AMap<AString, ACell>) taskResultsCell;

			AMap<AString, ACell> timelineEntry = Maps.of(
				K_START, CVMLong.create(startTs),
				K_END, CVMLong.create(endTs),
				Fields.OP, transitionOp,
				AgentState.KEY_STATE, record.get(AgentState.KEY_STATE),
				Fields.TASKS, formattedTasks,
				Fields.MESSAGES, inbox,
				Fields.RESULT, result);
			if (taskResults != null) timelineEntry = timelineEntry.assoc(Fields.TASK_RESULTS, taskResults);

			// Accumulate task results across iterations
			if (taskResults != null) {
				for (var entry : taskResults.entrySet()) {
					allTaskResults = allTaskResults.assoc(entry.getKey(), entry.getValue());
				}
			}

			// Merge results atomically
			AMap<AString, ACell> merged = agent.mergeRunResult(
				newState, inbox.count(), tasks, taskResults, timelineEntry);

			lastResult = Maps.of(
				Fields.AGENT_ID, agentId,
				Fields.STATUS, merged.get(AgentState.KEY_STATUS),
				Fields.RESULT, result,
				Fields.TASK_RESULTS, allTaskResults);

			boolean continueLoop = AgentState.RUNNING.equals(
				RT.ensureString(merged.get(AgentState.KEY_STATUS)));
			if (!continueLoop) {
				completeRun(completion, agentId, lastResult);
				return;
			}
		}
		} catch (Exception e) {
			suspendOnError(callerDID, agentId, e);
			completeRunExceptionally(completion, agentId, e.getMessage());
		}
	}

	// ========== Helpers ==========

	private static AVector<ACell> ensureVector(AVector<ACell> v) {
		return (v != null) ? v : Vectors.empty();
	}

	/**
	 * Formats task Index entries as a vector for the transition function.
	 * Tasks are raw lattice data: {@code Blob taskId → ACell input}.
	 */
	private AVector<ACell> formatTasks(Index<Blob, ACell> tasks) {
		if (tasks == null || tasks.count() == 0) return Vectors.empty();
		AVector<ACell> result = Vectors.empty();
		for (var entry : tasks.entrySet()) {
			Blob jobId = entry.getKey();
			AMap<AString, ACell> jobData = engine.jobs().getJobData(jobId);
			ACell caller = (jobData != null) ? jobData.get(Fields.CALLER) : null;
			AMap<AString, ACell> task = Maps.of(
				Fields.JOB_ID, taskIdHex(jobId),
				Fields.INPUT, entry.getValue());
			if (caller != null) task = task.assoc(Fields.CALLER, caller);
			result = result.conj(task);
		}
		return result;
	}

	/**
	 * Resolves pending Job IDs to a vector of maps with status and output.
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
				Fields.JOB_ID, taskIdHex(jobId),
				Fields.STATUS, (jobData != null) ? jobData.get(Fields.STATUS) : null,
				payloadField, payload);
			if (snapshot != null) info = info.assoc(Fields.SNAPSHOT, snapshot);
			resolved = resolved.conj(info);
		}
		return resolved;
	}

	// ========== Run completion ==========

	private void completeRun(CompletableFuture<ACell> completion, AString agentId, ACell result) {
		runCompletions.remove(agentId.toString(), completion);
		completion.complete(result);
	}

	private void completeRunExceptionally(CompletableFuture<ACell> completion,
			AString agentId, String message) {
		runCompletions.remove(agentId.toString(), completion);
		completion.completeExceptionally(new RuntimeException(message));
	}

	private void suspendOnError(AString callerDID, AString agentId, Exception e) {
		try {
			AgentState agent = getAgent(callerDID, agentId);
			if (agent != null) agent.suspend(Strings.create(e.getMessage()));
		} catch (Exception inner) {
			log.warn("Failed to set agent error state", inner);
		}
	}

	// ========== Agent lookup ==========

	private AgentState getAgent(AString callerDID, AString agentId) {
		Users users = engine.getVenueState().users();
		User user = users.get(callerDID);
		if (user == null) return null;
		AgentState agent = user.agent(agentId);
		if (agent == null) return null;
		if (AgentState.TERMINATED.equals(agent.getStatus())) return null;
		return agent;
	}

	private AgentState lookupAgent(Job job, AString callerDID, AString agentId) {
		AgentState agent = getAgent(callerDID, agentId);
		if (agent == null) job.fail("Agent not found or terminated: " + agentId);
		return agent;
	}

	private AString resolveTransitionOp(AString callerDID, AString agentId) {
		AgentState agent = getAgent(callerDID, agentId);
		if (agent == null) return null;
		AMap<AString, ACell> config = agent.getConfig();
		if (config == null) return null;
		return RT.ensureString(config.get(Fields.OPERATION));
	}

	// ========== ID generation ==========

	private synchronized Blob generateTaskId() {
		long ts = Utils.getCurrentTimestamp();
		byte[] bs = new byte[16];
		Utils.writeLong(bs, 0, ts);
		Utils.writeLong(bs, 8, taskIdCounter++);
		return Blob.wrap(bs);
	}

	private static AString taskIdHex(Blob id) {
		return Strings.create(id.toHexString());
	}
}
