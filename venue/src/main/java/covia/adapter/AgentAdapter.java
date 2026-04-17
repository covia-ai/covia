package covia.adapter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.util.JSON;
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
 * <p>Run-loop concurrency is mediated by a single per-agent
 * {@link RunCoordinator}: its lock serialises the "start new run vs. attach
 * to live run vs. exit cleanly" decisions, and its {@link CompletableFuture}
 * is the completion signal. The lattice {@code K_STATUS} field is a
 * persisted mirror of coord state for restart recovery and user visibility,
 * not the concurrency primitive.</p>
 */
public class AgentAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(AgentAdapter.class);

	private static final AString K_START = Strings.intern("start");
	private static final AString K_END   = Strings.intern("end");
	private static final AString PENDING = Strings.intern("PENDING");
	private static final AString K_SOURCE_ID        = Strings.intern("sourceId");
	private static final AString K_INCLUDE_TIMELINE = Strings.intern("includeTimeline");
	private static final AString K_FORKED_FROM      = Strings.intern("forkedFrom");
	private static final AString K_SYSTEM_PROMPT    = Strings.intern("systemPrompt");
	private static final AString K_LLM_OPERATION    = Strings.intern("llmOperation");

	/** Maximum run loop iterations before forced exit (safety net) */
	private static final int MAX_LOOP_ITERATIONS = 20;

	/**
	 * Per-agent run coordinator — the single source of truth for "is this
	 * agent running". The lock serialises start/exit decisions; the future
	 * slot holds the live run's completion (or null if idle).
	 *
	 * <p>Coordinators are created lazily and retained for the lifetime of
	 * the process — the per-agent memory is a ReentrantLock plus a nullable
	 * reference, negligible even for large agent populations.</p>
	 */
	private static final class RunCoordinator {
		final ReentrantLock lock = new ReentrantLock();
		CompletableFuture<ACell> completion;
	}

	/** One coordinator per agent, keyed by agentId. */
	private final ConcurrentHashMap<String, RunCoordinator> runs = new ConcurrentHashMap<>();

	/** Active transition job per agent — allows suspend to cancel running transitions */
	private final ConcurrentHashMap<String, Job> activeTransitions = new ConcurrentHashMap<>();

	/**
	 * Per-agent deferred task completions written by {@code agent:complete-task}
	 * and {@code agent:fail-task} during a transition cycle. The framework
	 * drains these AFTER {@code mergeRunResult} has written the timeline, so
	 * the caller's {@code awaitResult} only returns once the cycle is fully
	 * persisted. Inner key is the task (== caller Job) ID.
	 */
	private final ConcurrentHashMap<String, ConcurrentHashMap<Blob, AMap<AString, ACell>>> deferredCompletions
		= new ConcurrentHashMap<>();

	/** Counter for session ID generation */
	private long sessionIdCounter = 0;

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
		installAsset("agent/create",      BASE + "create.json");
		installAsset("agent/fork",        BASE + "fork.json");
		installAsset("agent/request",     BASE + "request.json");
		installAsset("agent/chat",        BASE + "chat.json");
		installAsset("agent/message",     BASE + "message.json");
		installAsset("agent/trigger",     BASE + "trigger.json");
		installAsset("agent/info",        BASE + "info.json");
		installAsset("agent/context",     BASE + "context.json");
		installAsset("agent/list",        BASE + "list.json");
		installAsset("agent/delete",      BASE + "delete.json");
		installAsset("agent/suspend",     BASE + "suspend.json");
		installAsset("agent/resume",      BASE + "resume.json");
		installAsset("agent/update",      BASE + "update.json");
		installAsset("agent/cancel-task",   BASE + "cancelTask.json");
		installAsset("agent/complete-task", BASE + "completeTask.json");
		installAsset("agent/fail-task",     BASE + "failTask.json");

		// Install standard agent templates at v/agents/templates/<name>.
		// Discoverable via covia_list path=v/agents/templates and usable in
		// agent:create via config="v/agents/templates/<name>".
		installAgentTemplate("minimal",  "/agent-templates/minimal.json");
		installAgentTemplate("reader",   "/agent-templates/reader.json");
		installAgentTemplate("worker",   "/agent-templates/worker.json");
		installAgentTemplate("manager",  "/agent-templates/manager.json");
		installAgentTemplate("analyst",  "/agent-templates/analyst.json");
		installAgentTemplate("full",     "/agent-templates/full.json");
		installAgentTemplate("goaltree", "/agent-templates/goaltree.json");
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
				case "fork"    -> handleFork(job, input, ctx);
				case "request" -> handleRequest(job, input, ctx);
				case "chat"    -> handleChat(job, input, ctx);
				case "message" -> handleMessage(job, input, ctx);
				case "trigger" -> handleTrigger(job, input, ctx);
				case "info"    -> handleQuery(job, input, ctx);
				case "context" -> handleContext(job, input, ctx);
				case "list"    -> handleList(job, input, ctx);
				case "delete"  -> handleDelete(job, input, ctx);
				case "suspend" -> handleSuspend(job, input, ctx);
				case "resume"  -> handleResume(job, input, ctx);
				case "update"       -> handleUpdate(job, input, ctx);
				case "cancelTask"   -> handleCancelTask(job, input, ctx);
				case "completeTask" -> handleCompleteTask(job, input, ctx);
				case "failTask"     -> handleFailTask(job, input, ctx);
				default             -> job.fail("Unknown agent operation: " + getSubOperation(meta));
			}
		} catch (Exception e) {
			job.fail(e.getMessage());
		}
	}

	// ========== Operation handlers ==========

	private void handleCreate(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		AMap<AString, ACell> config;
		try {
			config = parseConfigArg(RT.getIn(input, Fields.CONFIG), ctx);
		} catch (IllegalArgumentException e) {
			job.fail(e.getMessage()); return;
		}

		ACell initialState = RT.getIn(input, AgentState.KEY_STATE);

		// Templates may embed initial state — extract if caller didn't provide one
		if (config != null && initialState == null) {
			ACell embeddedState = config.get(AgentState.KEY_STATE);
			if (embeddedState != null) {
				initialState = embeddedState;
				config = config.dissoc(AgentState.KEY_STATE);
			}
		}

		// Resolve agent definition asset if provided
		AString definitionRef = RT.ensureString(RT.getIn(input, Fields.DEFINITION));
		if (definitionRef != null) {
			Asset defAsset = engine.resolveAsset(definitionRef, ctx);
			if (defAsset == null) { job.fail("Definition asset not found: " + definitionRef); return; }

			AMap<AString, ACell> defMeta = defAsset.meta();

			// Extract agent config from definition metadata.
			// NB: use instanceof (not RT.ensureMap) because RT.ensureMap(null) returns
			// an empty map, which would wrap an empty state.config even when the
			// definition has no nested agent.config.
			AString defOp = RT.ensureString(RT.getIn(defMeta, Strings.intern("agent"), Fields.OPERATION));
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> defConfig =
				(RT.getIn(defMeta, Strings.intern("agent"), Fields.CONFIG) instanceof AMap<?,?> dm)
					? (AMap<AString, ACell>) dm : null;

			// Definition provides defaults; explicit params override
			if (config == null && defOp != null) {
				config = Maps.of(Fields.OPERATION, defOp);
			}
			if (initialState == null && defConfig != null) {
				initialState = Maps.of(Strings.intern("config"), defConfig);
			}

			// Store resolved asset ID in config for provenance (full DID URL)
			if (config != null) {
				AString defID = ctx.getCallerDID().append("/a/" + defAsset.getID().toHexString());
				config = config.assoc(Fields.DEFINITION, defID);
			}
		}

		// Apply sensible defaults for LLM agents. LLMAgentAdapter.processChat merges
		// record.config with state.config at chat time (state wins), so we write LLM
		// defaults directly into record.config instead of duplicating into state.
		if (config == null) config = Maps.empty();
		if (!config.containsKey(Fields.OPERATION)) {
			config = config.assoc(Fields.OPERATION, Strings.create("v/ops/llmagent/chat"));
		}
		// systemPrompt present implies an LLM agent — ensure llmOperation is set
		if (config.containsKey(K_SYSTEM_PROMPT) && !config.containsKey(K_LLM_OPERATION)) {
			config = config.assoc(K_LLM_OPERATION, Strings.create("v/ops/langchain/openai"));
		}

		boolean overwrite = CVMBool.TRUE.equals(RT.getIn(input, Fields.OVERWRITE));
		Users users = engine.getVenueState().users();
		User user = users.ensure(ctx.getCallerDID());

		// Resolve what to do with the target slot. See resolveCreateSlot for the
		// full state machine — empty slots create, occupied slots update or no-op
		// based on overwrite flag and current status.
		SlotResult slot = resolveCreateSlot(job, user, agentId, overwrite, config, initialState);
		if (slot == SlotResult.FAILED) return;

		// ensureAgent is a no-op when the agent already exists; for UPDATED slots
		// the in-place update has already happened inside resolveCreateSlot.
		AgentState agent = user.ensureAgent(agentId, config, initialState);

		AMap<AString, ACell> result = Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.STATUS, agent.getStatus(),
			Fields.CREATED, CVMBool.of(slot == SlotResult.CREATED),
			Fields.UPDATED, CVMBool.of(slot == SlotResult.UPDATED));

		job.setStatus(Status.STARTED);
		job.completeWith(result);
	}

	/** Outcome of resolving the target slot for {@code agent:create}. */
	private enum SlotResult { CREATED, UPDATED, NOOP, FAILED }

	/**
	 * State machine for {@code agent:create}'s target slot. Determines whether
	 * to create a fresh record, update an existing one in place, no-op, or fail.
	 *
	 * <table>
	 *   <caption>Slot resolution matrix</caption>
	 *   <tr><th>{@code overwrite}</th><th>Existing status</th><th>Result</th><th>Side effect</th></tr>
	 *   <tr><td>any</td>            <td>(empty)</td>      <td>CREATED</td><td>none — caller initialises</td></tr>
	 *   <tr><td>false</td>          <td>any</td>          <td>NOOP</td>   <td>none — idempotent</td></tr>
	 *   <tr><td>true</td>           <td>TERMINATED</td>   <td>CREATED</td><td>removeAgent — fresh start, timeline wiped</td></tr>
	 *   <tr><td>true</td>           <td>SLEEPING</td>     <td>UPDATED</td><td>updateConfigAndState — timeline preserved</td></tr>
	 *   <tr><td>true</td>           <td>SUSPENDED</td>    <td>UPDATED</td><td>updateConfigAndState — timeline + error preserved</td></tr>
	 *   <tr><td>true</td>           <td>RUNNING</td>      <td>FAILED</td> <td>job.fail — racy, unsafe to mutate mid-run</td></tr>
	 * </table>
	 *
	 * <p>The RUNNING rejection is the only loud failure: a transition currently
	 * in flight has already captured the OLD config at its start, so a mid-run
	 * config swap would surface as a "why is the agent using the old prompt"
	 * mystery on the next run. Callers should wait for the agent to return to
	 * SLEEPING (e.g. via {@code agent:trigger}'s wait semantics) or cancel the
	 * active task with {@code agent:cancelTask}.</p>
	 */
	private SlotResult resolveCreateSlot(Job job, User user, AString agentId, boolean overwrite,
			AMap<AString, ACell> newConfig, ACell newState) {
		AgentState existing = user.agent(agentId);
		if (existing == null || !existing.exists()) return SlotResult.CREATED;

		if (!overwrite) return SlotResult.NOOP;

		AString status = existing.getStatus();
		if (AgentState.TERMINATED.equals(status)) {
			// Fresh start — wipe timeline, tasks, inbox, the lot
			user.removeAgent(agentId);
			return SlotResult.CREATED;
		}
		if (AgentState.RUNNING.equals(status)) {
			job.fail("Cannot update agent " + agentId + ": currently RUNNING. "
				+ "Wait for the active transition to finish, or call agent:cancelTask first.");
			return SlotResult.FAILED;
		}
		// SLEEPING / SUSPENDED — in-place update preserves timeline, inbox, tasks, pending, status
		existing.updateConfigAndState(newConfig, newState);
		return SlotResult.UPDATED;
	}

	/**
	 * agent:fork — create a new agent from an existing agent's config + state.
	 *
	 * <p>Copies config and state; timeline is copied only if {@code includeTimeline}
	 * is true. Tasks, pending, and inbox are fresh; status is SLEEPING. Optional
	 * inline or reference config is merged on top of the source config.</p>
	 */
	private void handleFork(Job job, ACell input, RequestContext ctx) {
		AString sourceId = RT.ensureString(RT.getIn(input, K_SOURCE_ID));
		if (sourceId == null) { job.fail("sourceId is required"); return; }

		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		Users users = engine.getVenueState().users();
		User user = users.ensure(ctx.getCallerDID());

		// Resolve source agent
		AgentState source = user.agent(sourceId);
		if (source == null || !source.exists()) {
			job.fail("Source agent not found: " + sourceId); return;
		}
		if (AgentState.TERMINATED.equals(source.getStatus())) {
			job.fail("Cannot fork TERMINATED agent: " + sourceId); return;
		}

		// Resolve optional config override and merge on top of source config
		AMap<AString, ACell> overrideConfig;
		try {
			overrideConfig = parseConfigArg(RT.getIn(input, Fields.CONFIG), ctx);
		} catch (IllegalArgumentException e) {
			job.fail(e.getMessage()); return;
		}
		AMap<AString, ACell> sourceConfig = source.getConfig();
		AMap<AString, ACell> forkConfig = (overrideConfig == null) ? sourceConfig
			: (sourceConfig != null ? sourceConfig.merge(overrideConfig) : overrideConfig);

		ACell sourceState = source.getState();
		AVector<ACell> sourceTimeline = CVMBool.TRUE.equals(RT.getIn(input, K_INCLUDE_TIMELINE))
			? source.getTimeline() : null;

		// Fork must write into an empty slot — fail if the target still exists
		boolean overwrite = CVMBool.TRUE.equals(RT.getIn(input, Fields.OVERWRITE));
		Boolean stillOccupied = applyOverwrite(job, user, agentId, overwrite);
		if (stillOccupied == null) return;
		if (stillOccupied) { job.fail("Target agent already exists: " + agentId); return; }

		AgentState target = user.forkAgent(agentId, forkConfig, sourceState, sourceTimeline);

		AMap<AString, ACell> result = Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.STATUS, target.getStatus(),
			Fields.CREATED, CVMBool.TRUE,
			K_FORKED_FROM, sourceId);

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

		// Mint or reuse a session for this request. Stage 1 scaffold — the
		// sid is recorded on the task row and returned in the response
		// envelope, but the transition function does not yet consume it.
		Blob sid = resolveOrMintSession(job, agent, input, ctx.getCallerDID());
		if (sid == null) return;

		// Build canonical taskdata map: {input, caller, created, sessionId, responseSchema?, t: {}}
		// Task rows are transient — status/result/error live on the Job record
		// and in the agent timeline's taskResults snapshot. The 't' slot is
		// reserved for per-task scratch.
		//
		// Task ID == caller's Job ID. There is no separate task identifier:
		// the request Job is the system of record, and the task entry in the
		// agent's tasks Index is the in-flight instruction keyed by that same
		// ID. The run loop completes the task by completing the Job retrieved
		// via {@code engine.jobs().getJob(taskId)} — no parallel pending-Jobs
		// map is maintained.
		Blob taskId = job.getID();
		ACell taskInput = RT.getIn(input, Fields.INPUT);
		ACell responseSchema = RT.getIn(input, Fields.RESPONSE_SCHEMA);
		AMap<AString, ACell> taskData = Maps.of(
			Fields.INPUT,      taskInput,
			Fields.CALLER,     ctx.getCallerDID(),
			Fields.CREATED,    CVMLong.create(Utils.getCurrentTimestamp()),
			Fields.SESSION_ID, Strings.create(sid.toHexString()),
			Fields.T,          Maps.empty());
		if (responseSchema instanceof AMap) {
			taskData = taskData.assoc(Fields.RESPONSE_SCHEMA, responseSchema);
		}
		agent.addTask(taskId, taskData);

		// The Job stays in STARTED state — the standard Job lifecycle (REST ?wait, SDK
		// job.result()) handles sync vs async from the caller's perspective. The run
		// loop will retrieve the Job by its ID (== taskId) and complete it.
		job.setStatus(Status.STARTED);

		// Wake agent to process the task — force=true because we just added a task
		// that may not yet be visible via cursor.get() (lattice write race)
		wakeAgent(agentId, ctx, true);
	}

	private void handleMessage(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		AgentState agent = lookupAgent(job, ctx.getCallerDID(), agentId);
		if (agent == null) return;

		Blob sid = resolveOrMintSession(job, agent, input, ctx.getCallerDID());
		if (sid == null) return;

		ACell messageContent = RT.getIn(input, Fields.MESSAGE);
		// Wrap message with caller provenance and the session it belongs to
		ACell envelope = Maps.of(
			Fields.CALLER,     ctx.getCallerDID(),
			Fields.SESSION_ID, Strings.create(sid.toHexString()),
			Fields.MESSAGE,    messageContent);
		agent.appendSessionPending(sid, envelope);
		wakeAgent(agentId, ctx);

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(
			Fields.AGENT_ID,   agentId,
			Fields.SESSION_ID, Strings.create(sid.toHexString()),
			Fields.DELIVERED,  CVMBool.TRUE));
	}

	/**
	 * agent:chat — synchronous request for the agent's next response on a
	 * session. Reserves a per-session chat slot, appends the message to the
	 * session's pending vector, wakes the agent, and leaves the Job in
	 * STARTED state. The framework's run loop completes the Job from the
	 * transition's {@code response} value once the agent runs (see
	 * {@link #executeRunLoop}).
	 *
	 * <p>A2A {@code message/send} analogue. Unlike {@code agent:request},
	 * which puts work in the {@code tasks} Index and requires explicit
	 * {@code agent:complete-task}, the chat path is naturally completed by
	 * whatever the agent next emits as its response on the session.</p>
	 *
	 * <p>Session resolution rules (chat-strict, §5.5):</p>
	 * <ul>
	 *   <li>{@code sessionId} omitted → mint a new session</li>
	 *   <li>{@code sessionId} present + known → continue that session</li>
	 *   <li>{@code sessionId} present + unknown → fail</li>
	 * </ul>
	 *
	 * <p>Only one chat may be in flight per session at a time. A second
	 * {@code agent:chat} on a session whose slot is already reserved fails
	 * fast — callers should wait for the first to complete or use
	 * {@code agent:message} for queued conversational sends.</p>
	 */
	private void handleChat(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		ACell messageContent = RT.getIn(input, Fields.MESSAGE);
		if (messageContent == null) { job.fail("message is required"); return; }

		AgentState agent = lookupAgent(job, ctx.getCallerDID(), agentId);
		if (agent == null) return;

		Blob sid = resolveSessionForChat(job, agent, input, ctx.getCallerDID());
		if (sid == null) return;
		AString sidHex = Strings.create(sid.toHexString());

		// Reserve the per-session chat slot. Atomic — fails fast if another
		// chat is already in flight for this session.
		if (!agent.tryReserveChatSlot(sid, job.getID())) {
			job.fail("Session " + sidHex + " already has an in-flight chat");
			return;
		}

		ACell envelope = Maps.of(
			Fields.CALLER,     ctx.getCallerDID(),
			Fields.SESSION_ID, sidHex,
			Fields.MESSAGE,    messageContent);
		agent.appendSessionPending(sid, envelope);

		// Stay in STARTED — the run loop will completeWith the agent's
		// response (or fail) once the next cycle for this session runs.
		job.setStatus(Status.STARTED);

		// Force the wake — we just reserved a slot and added a message,
		// either of which may not yet be visible via cursor.get().
		wakeAgent(agentId, ctx, true);
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

		Blob sid = resolveOrMintSession(job, agent, input, ctx.getCallerDID());
		if (sid == null) return;
		AString sidHex = Strings.create(sid.toHexString());

		CompletableFuture<ACell> completion = wakeAgent(agentId, ctx, true);
		if (completion == null) {
			job.fail("Cannot start agent: " + agentId);
			return;
		}

		// Default wait=true for backward compat (trigger traditionally blocks)
		long waitMs = parseWaitMs(input);
		if (waitMs == 0 && RT.getIn(input, Fields.WAIT) == null) waitMs = -1;

		ACell running = Maps.of(
			Fields.AGENT_ID,   agentId,
			Fields.SESSION_ID, sidHex,
			Fields.STATUS,     AgentState.RUNNING);
		awaitRunCompletion(job, completion, waitMs, running,
			result -> annotateWithSession(result, sidHex));
	}

	/**
	 * Adds a {@code sessionId} entry to a map-typed run result; returns the
	 * cell unchanged if it is not a map (e.g. null or unexpected shape).
	 */
	@SuppressWarnings("unchecked")
	private static ACell annotateWithSession(ACell result, AString sidHex) {
		if (!(result instanceof AMap)) return result;
		AMap<AString, ACell> m = (AMap<AString, ACell>) result;
		if (m.containsKey(Fields.SESSION_ID)) return m;
		return m.assoc(Fields.SESSION_ID, sidHex);
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
		// NB: stateConfig is only populated by legacy definition-created agents.
		// Use instanceof (not RT.ensureMap) because RT.ensureMap(null) returns
		// an empty map — callers would see a spurious empty stateConfig otherwise.
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> stateConfig =
			(RT.getIn(record, AgentState.KEY_STATE, Strings.intern("config")) instanceof AMap<?,?> sc)
				? (AMap<AString, ACell>) sc : null;
		AVector<?> timeline = agent.getTimeline();
		Index<Blob, ACell> tasks = agent.getTasks();

		AMap<AString, ACell> summary = Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.STATUS, record.get(AgentState.KEY_STATUS),
			Fields.CONFIG, record.get(AgentState.KEY_CONFIG));

		// Include state.config (legacy path — only populated by definition-created agents)
		if (stateConfig != null) summary = summary.assoc(Strings.intern("stateConfig"), stateConfig);
		if (timeline != null) summary = summary.assoc(Strings.intern("timelineLength"), CVMLong.create(timeline.count()));
		if (tasks != null) summary = summary.assoc(Strings.intern("tasks"), CVMLong.create(tasks.count()));
		ACell error = record.get(AgentState.KEY_ERROR);
		if (error != null) summary = summary.assoc(AgentState.KEY_ERROR, error);

		job.setStatus(Status.STARTED);
		job.completeWith(summary);
	}

	/**
	 * Renders the assembled LLM context an agent would see on a fresh
	 * transition — same ContextBuilder pipeline as the real adapter, but
	 * without invoking the LLM. Returns the system prompt text, context
	 * entry labels, tool names, caps, and outputs config as a single map.
	 * Designed for live debugging: call via {@code agent:context} to see
	 * exactly what the LLM receives.
	 */
	@SuppressWarnings("unchecked")
	private void handleContext(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		Users users = engine.getVenueState().users();
		User user = users.get(ctx.getCallerDID());
		if (user == null) { job.fail("User not found"); return; }
		AgentState agent = user.agent(agentId);
		if (agent == null) { job.fail("Agent not found: " + agentId); return; }

		AMap<AString, ACell> record = agent.getRecord();
		AMap<AString, ACell> recordConfig = (record.get(AgentState.KEY_CONFIG) instanceof AMap m)
			? (AMap<AString, ACell>) m : null;
		ACell state = record.get(AgentState.KEY_STATE);

		// Delegate to the actual adapter's code path. For goaltree agents
		// this calls GoalTreeAdapter.buildFirstIterationL3Input which uses
		// the same ContextBuilder pipeline, harness tool assembly, output
		// typing, and L3 input construction as the real transition — the
		// only difference is we return the L3 input instead of dispatching.
		ACell taskInput = RT.getIn(input, Strings.intern("task"));
		AString operation = (recordConfig != null)
			? RT.ensureString(recordConfig.get(Strings.intern("operation")))
			: null;

		AMap<AString, ACell> l3Input;
		if (operation != null && operation.toString().contains("goaltree")) {
			covia.adapter.agent.GoalTreeAdapter gta =
				(covia.adapter.agent.GoalTreeAdapter) engine.getAdapter("goaltree");
			l3Input = gta.buildFirstIterationL3Input(recordConfig, state, taskInput, ctx);
		} else {
			// Fallback for non-goaltree agents — basic ContextBuilder only
			ContextBuilder builder = new ContextBuilder(engine, ctx);
			ContextBuilder.ContextResult context = builder
				.withConfig(recordConfig, state)
				.withSystemPrompt(Vectors.empty())
				.withContextEntries(state)
				.withTools()
				.build();
			l3Input = covia.adapter.agent.AbstractLLMAdapter.buildL3Input(
				ContextBuilder.extractConfig(state),
				context.history(),
				context.tools());
		}

		// Serialize as ordered JSON matching the wire format to the LLM.
		// Convex maps hash-order keys, so we build the JSON directly.
		AVector<ACell> messages = RT.ensureVector(l3Input.get(Strings.create("messages")));
		AVector<ACell> tools = RT.ensureVector(l3Input.get(Strings.create("tools")));
		StringBuilder sb = new StringBuilder("{\n");
		ACell model = l3Input.get(Strings.create("model"));
		if (model != null) sb.append("  \"model\": ").append(JSON.toString(model)).append(",\n");
		ACell rf = l3Input.get(Strings.create("responseFormat"));
		if (rf != null) sb.append("  \"responseFormat\": ").append(JSON.toString(rf)).append(",\n");
		sb.append("  \"messages\": [\n");
		for (long i = 0; i < messages.count(); i++) {
			if (i > 0) sb.append(",\n");
			sb.append("    ").append(JSON.toString(messages.get(i)));
		}
		sb.append("\n  ],\n  \"tools\": [\n");
		for (long i = 0; i < tools.count(); i++) {
			if (i > 0) sb.append(",\n");
			sb.append("    ").append(JSON.toString(tools.get(i)));
		}
		sb.append("\n  ]\n}");

		job.setStatus(Status.STARTED);
		job.completeWith(Strings.create(sb.toString()));
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

		// Cancel any active transition so the agent stops promptly
		Job activeJob = activeTransitions.get(agentId.toString());
		if (activeJob != null) activeJob.cancel();

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

		if (autoWake) wakeAgent(agentId, ctx, false);

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

	/**
	 * Completes the in-scope task with a successful result. Invoked by an
	 * agent transition (typically as an LLM tool call) to explicitly mark
	 * the current task done. Reads {@code agentId} and {@code taskId} from
	 * the {@link RequestContext} — these are populated by the framework
	 * when dispatching a task transition. Without a task in scope the call
	 * fails.
	 *
	 * <p>Side effects: completes the pending task Job with the provided
	 * {@code result} and removes the task entry from the agent's task
	 * Index. Returns {@code {agentId, taskId, status: "COMPLETE"}}.</p>
	 */
	private void handleCompleteTask(Job job, ACell input, RequestContext ctx) {
		AString agentId = ctx.getAgentId();
		Blob taskId = ctx.getTaskId();
		if (agentId == null || taskId == null) {
			job.fail("agent:completeTask requires task scope (agentId + taskId in RequestContext)");
			return;
		}

		AgentState agent = lookupAgent(job, ctx.getCallerDID(), agentId);
		if (agent == null) return;

		Index<Blob, ACell> tasks = agent.getTasks();
		if (tasks == null || tasks.get(taskId) == null) {
			job.fail("Task not found: " + taskId.toHexString());
			return;
		}

		ACell result = RT.getIn(input, Fields.RESULT);
		parkCompletion(agentId, tasks, taskId, Status.COMPLETE, Fields.OUTPUT, result);
		agent.removeTask(taskId);

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.TASK_ID,  taskIdHex(taskId),
			Fields.STATUS,   Status.COMPLETE));
	}

	/**
	 * Fails the in-scope task with an error. Mirror of {@link #handleCompleteTask}
	 * for the failure path: the pending task Job is failed with the supplied
	 * error, the task entry is removed, and the call returns
	 * {@code {agentId, taskId, status: "FAILED"}}.
	 */
	private void handleFailTask(Job job, ACell input, RequestContext ctx) {
		AString agentId = ctx.getAgentId();
		Blob taskId = ctx.getTaskId();
		if (agentId == null || taskId == null) {
			job.fail("agent:failTask requires task scope (agentId + taskId in RequestContext)");
			return;
		}

		AgentState agent = lookupAgent(job, ctx.getCallerDID(), agentId);
		if (agent == null) return;

		Index<Blob, ACell> tasks = agent.getTasks();
		if (tasks == null || tasks.get(taskId) == null) {
			job.fail("Task not found: " + taskId.toHexString());
			return;
		}

		ACell errorCell = RT.getIn(input, Fields.ERROR);
		AString errorStr = (errorCell == null) ? Strings.create("Task failed") : Strings.create(errorCell.toString());
		parkCompletion(agentId, tasks, taskId, Status.FAILED, Fields.ERROR, errorStr);
		agent.removeTask(taskId);

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.TASK_ID,  taskIdHex(taskId),
			Fields.STATUS,   Status.FAILED));
	}

	/**
	 * Parks a completion envelope into {@link #deferredCompletions} for the
	 * given agent. The framework drains these AFTER {@code mergeRunResult}
	 * so the caller's {@code awaitResult} only returns once the cycle's
	 * timeline / state writes are visible. Shared by
	 * {@link #handleCompleteTask} and {@link #handleFailTask}.
	 */
	private void parkCompletion(AString agentId, Index<Blob, ACell> tasks, Blob taskId,
			AString status, AString valueField, ACell value) {
		AMap<AString, ACell> envelope = Maps.of(
			Fields.ID,     taskId,
			Fields.STATUS, status,
			valueField,    value);
		ACell sid = extractTaskSessionId(tasks, taskId);
		if (sid != null) envelope = envelope.assoc(Fields.SESSION_ID, sid);
		deferredCompletions
			.computeIfAbsent(agentId.toString(), k -> new ConcurrentHashMap<>())
			.put(taskId, envelope);
	}

	/**
	 * Builds the per-cycle {@code taskResults} map from drained completion
	 * envelopes, or returns {@code null} if there are none. The returned
	 * map is consumed by {@code mergeRunResult} for timeline + task cleanup.
	 */
	private static AMap<AString, ACell> buildTaskResultsFromDeferred(
			ConcurrentHashMap<Blob, AMap<AString, ACell>> deferred) {
		if (deferred == null || deferred.isEmpty()) return null;
		AMap<AString, ACell> taskResults = Maps.empty();
		for (var e : deferred.entrySet()) {
			AMap<AString, ACell> envelope = e.getValue();
			AString status = RT.ensureString(envelope.get(Fields.STATUS));
			ACell taskEntry = Status.FAILED.equals(status)
				? Maps.of(Fields.STATUS, Status.FAILED, Fields.ERROR,  envelope.get(Fields.ERROR))
				: Maps.of(Fields.STATUS, Status.COMPLETE, Fields.OUTPUT, envelope.get(Fields.OUTPUT));
			taskResults = taskResults.assoc(taskIdHex(e.getKey()), taskEntry);
		}
		return taskResults;
	}

	/**
	 * Completes the caller's pending task Jobs from drained completion
	 * envelopes. MUST be called AFTER {@code mergeRunResult} so a caller
	 * blocked on {@link Job#awaitResult} only unblocks once the cycle's
	 * timeline + state writes are visible. Idempotent and null-safe.
	 */
	private void completeDeferredJobs(ConcurrentHashMap<Blob, AMap<AString, ACell>> deferred) {
		if (deferred == null) return;
		for (var e : deferred.entrySet()) {
			Job pendingJob = engine.jobs().getJob(e.getKey());
			if (pendingJob == null || pendingJob.isFinished()) continue;
			AMap<AString, ACell> envelope = e.getValue();
			AString status = RT.ensureString(envelope.get(Fields.STATUS));
			if (Status.FAILED.equals(status)) {
				ACell err = envelope.get(Fields.ERROR);
				pendingJob.fail(err == null ? "Task failed" : err.toString());
			} else {
				pendingJob.completeWith(envelope);
			}
		}
	}

	// ========== Wake and run management ==========

	/**
	 * Parses the {@code wait} parameter from tool input.
	 * <ul>
	 *   <li>{@code false} or absent → 0 (async, return immediately)</li>
	 *   <li>{@code true} or {@code "true"} → -1 (block indefinitely)</li>
	 *   <li>positive integer → timeout in milliseconds</li>
	 * </ul>
	 */
	static long parseWaitMs(ACell input) {
		ACell waitCell = RT.getIn(input, Fields.WAIT);
		if (CVMBool.TRUE.equals(waitCell) || Strings.create("true").equals(waitCell)) return -1;
		if (waitCell instanceof CVMLong l && l.longValue() > 0) return l.longValue();
		return 0;
	}

	/**
	 * Awaits a run cycle completion future according to the wait policy, then
	 * completes the job. If {@code waitMs == 0} or {@code completion == null},
	 * completes immediately with the {@code immediateResult}. Otherwise blocks
	 * for the specified duration and calls {@code resultMapper} on the cycle result.
	 *
	 * @param job The job to complete
	 * @param completion Run cycle future (may be null)
	 * @param waitMs Wait policy: 0=async, -1=indefinite, >0=timeout ms
	 * @param immediateResult Result to return when not waiting
	 * @param resultMapper Maps the cycle result to a job result when waiting completes
	 */
	void awaitRunCompletion(Job job, CompletableFuture<ACell> completion, long waitMs,
			ACell immediateResult, java.util.function.Function<ACell, ACell> resultMapper) {
		job.setStatus(Status.STARTED);
		if (waitMs == 0 || completion == null) {
			job.completeWith(immediateResult);
			return;
		}
		try {
			ACell cycleResult = (waitMs < 0)
				? completion.join()
				: completion.get(waitMs, TimeUnit.MILLISECONDS);
			job.completeWith(resultMapper.apply(cycleResult));
		} catch (TimeoutException e) {
			job.completeWith(immediateResult);
		} catch (Exception e) {
			job.completeWith(immediateResult);
		}
	}

	/**
	 * Wakes the agent: persists the wake flag, then either attaches to the
	 * live run loop or starts a fresh one. Returns the completion future,
	 * or null if the agent doesn't exist / has no work and wake isn't forced.
	 *
	 * <p>All run-loop concurrency flows through this single entry point.
	 * The per-agent {@link RunCoordinator} lock serialises the
	 * start/attach/exit decision; callers never observe an inconsistency
	 * between "loop is live" (future present) and "agent can accept a new
	 * loop" (future absent). The lattice {@code K_STATUS} is written for
	 * observability and restart recovery, not as a CAS primitive.</p>
	 *
	 * @param force if true, skips the {@code shouldWake || hasWork} gate —
	 *              used by explicit triggers that always want to try running
	 */
	CompletableFuture<ACell> wakeAgent(AString agentId, RequestContext ctx, boolean force) {
		String key = agentId.toString();
		AString callerDID = ctx.getCallerDID();

		AgentState agent = getAgent(callerDID, agentId);
		if (agent == null) return null;

		RunCoordinator coord = runs.computeIfAbsent(key, k -> new RunCoordinator());
		coord.lock.lock();
		try {
			// Persist wake flag — any in-flight loop will observe it either
			// via mergeRunResult's CAS-retried lambda or via tryCleanExit's
			// re-check under this same lock. Set unconditionally so that
			// resume of a suspended agent sees pending work.
			agent.setWakeTime(Utils.getCurrentTimestamp());

			// Live run? Attach to it. Our wake flag is already visible to
			// the loop's merge or will be caught at its clean-exit check.
			if (coord.completion != null && !coord.completion.isDone()) {
				return coord.completion;
			}

			// Only start a loop from SLEEPING. Suspended or terminated
			// agents keep their wake flag for later resume; running is
			// handled by the live-run branch above.
			if (!AgentState.SLEEPING.equals(agent.getStatus())) return null;

			// No live run. Decide whether to start one.
			if (!force && !agent.shouldWake() && !hasWork(agent)) return null;

			AString transitionOp = resolveTransitionOp(callerDID, agentId);
			if (transitionOp == null) return null;

			CompletableFuture<ACell> completion = new CompletableFuture<>();
			coord.completion = completion;
			agent.setStatus(AgentState.RUNNING);

			final AString finalOp = transitionOp;
			CompletableFuture.runAsync(
				() -> executeRunLoop(agentId, callerDID, finalOp, ctx, coord),
				VIRTUAL_EXECUTOR);
			return completion;
		} finally {
			coord.lock.unlock();
		}
	}

	/** Overload: non-forced wake (used by message delivery / resume auto-wake). */
	CompletableFuture<ACell> wakeAgent(AString agentId, RequestContext ctx) {
		return wakeAgent(agentId, ctx, false);
	}

	private static boolean hasWork(AgentState agent) {
		if (agent.hasSessionPending()) return true;
		Index<Blob, ACell> tasks = agent.getTasks();
		return tasks != null && tasks.count() > 0;
	}

	// ========== Run loop ==========

	@SuppressWarnings("unchecked")
	private void executeRunLoop(AString agentId, AString callerDID,
			AString transitionOp, RequestContext ctx,
			RunCoordinator coord) {
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
				// Safety-net exit — bypass the clean-exit handshake so we
				// cannot loop forever even if wake flags keep arriving.
				forceExit(coord, lastResult != null ? lastResult : Maps.of(
					Fields.AGENT_ID, agentId, Fields.STATUS, AgentState.SLEEPING,
					Fields.TASK_RESULTS, allTaskResults));
				return;
			}

			// Snapshot current state (reads are non-blocking)
			AgentState agent = getAgent(callerDID, agentId);
			if (agent == null) {
				completeExceptionally(coord, "Agent not found: " + agentId);
				return;
			}

			AMap<AString, ACell> record = agent.getRecord();
			Index<Blob, ACell> tasks = agent.getTasks();
			Index<Blob, ACell> pending = agent.getPending();
			ACell currentState = agent.getState();

			// On subsequent iterations, attempt clean exit if no new work.
			// tryCleanExit re-checks wake/hasWork under the coord lock, so a
			// trigger arriving between the last merge and here is still seen.
			if (!firstIteration && !agent.hasSessionPending() && tasks.count() == 0) {
				if (tryCleanExit(coord, agent, lastResult, allTaskResults, agentId)) return;
				continue;  // late wake — loop again
			}
			firstIteration = false;

			long startTs = Utils.getCurrentTimestamp();

			// Pick at most one task per cycle (oldest by created timestamp).
			// Multi-task agents fan out across cycles. The transition still
			// receives a vector — current shape is length 0 or 1.
			Map.Entry<Blob, ACell> pickedTask = pickOldestTask(tasks);
			AVector<ACell> formattedTasks = formatPickedTask(pickedTask);
			AVector<ACell> resolvedPending = resolveJobIds(pending, Fields.OUTPUT);

			// Pick at most one session per cycle. Priority:
			//   1. picked task's sessionId (so the task's session controls)
			//   2. else the first session with non-empty pending
			//   3. else null (no session traffic)
			AString pickedSession = pickSessionForCycle(pickedTask, agent);
			Blob pickedSessionBlob = (pickedSession != null) ? Blob.parse(pickedSession.toString()) : null;
			AVector<ACell> filteredInbox = (pickedSessionBlob != null)
				? agent.getSessionPending(pickedSessionBlob) : Vectors.empty();

			// Snapshot the picked session's chat slot and pending count.
			// Captured here so that a chat that arrives during the transition
			// isn't picked up by this cycle's completion logic — it'll be
			// handled next pass.
			Blob pickedChatJobId = null;
			AMap<AString, ACell> pickedSessionRecord = null;
			long presentedSessionPendingCount = filteredInbox.count();
			if (pickedSessionBlob != null) {
				pickedChatJobId = agent.getChatJob(pickedSessionBlob);
				pickedSessionRecord = agent.getSession(pickedSessionBlob);
			}

			// Per-cycle ctx: scope to the agent, picked task, and session so
			// path resolvers (n/, t/, c/) can address the right slot, and
			// agent:completeTask / agent:failTask invoked during the
			// transition can identify which agent + task they're acting on.
			// Since taskId == caller's Job ID, no separate jobId scope is needed.
			RequestContext cycleCtx = ctx.withAgentId(agentId);
			if (pickedTask != null) {
				cycleCtx = cycleCtx.withTaskId(pickedTask.getKey());
			}
			if (pickedSessionBlob != null) {
				cycleCtx = cycleCtx.withSessionId(pickedSessionBlob);
			}

			// Invoke transition — no lock, no coordination during this call.
			// Lean contract surface: also pass `newInput` (the picked task's
			// input). Old transitions ignore it; new transitions read it
			// directly without iterating the tasks vector.
			// Extract picked task input once — used both as transition input
			// and (S3a) as the user-turn content if appended to history.
			ACell pickedTaskInput = null;
			if (pickedTask != null) {
				pickedTaskInput = (pickedTask.getValue() instanceof AMap)
					? ((AMap<AString, ACell>) pickedTask.getValue()).get(Fields.INPUT)
					: pickedTask.getValue();
			}

			AMap<AString, ACell> transitionInput = Maps.of(
				Fields.AGENT_ID, agentId,
				AgentState.KEY_STATE, currentState,
				Fields.TASKS, formattedTasks,
				Fields.PENDING, resolvedPending);
			if (pickedTaskInput != null) {
				transitionInput = transitionInput.assoc(Fields.NEW_INPUT, pickedTaskInput);
			}
			// Surface the picked session's full record under
			// `Fields.SESSION` — transitions read {history, pending, c,
			// meta, parties} from here. Includes `id` (sid hex) for
			// convenience. Adapters call effectiveMessages(input) to read
			// the session.pending vector.
			if (pickedSessionRecord != null) {
				AMap<AString, ACell> sessionMap = pickedSessionRecord
					.assoc(Fields.SESSION_ID, pickedSession);
				transitionInput = transitionInput.assoc(Fields.SESSION, sessionMap);
			}
			// Pass framework config separately so the transition can read caps, tools, etc.
			AMap<AString, ACell> agentConfig = agent.getConfig();
			if (agentConfig != null) {
				transitionInput = transitionInput.assoc(AgentState.KEY_CONFIG, agentConfig);
			}

			Job transitionJob = engine.jobs().invokeOperation(transitionOp, transitionInput, cycleCtx);
			activeTransitions.put(agentId.toString(), transitionJob);
			ACell transitionResult;
			try {
				transitionResult = transitionJob.awaitResult();
			} finally {
				activeTransitions.remove(agentId.toString());
			}

			// Process results — the transition contract is now lean only:
			//   {state?, response?, error?}
			// `error` takes precedence over `response` and signals failure.
			// Task completion is signalled by the transition invoking
			// agent:complete-task / agent:fail-task via the venue op, which
			// removes the task entry and parks a completion envelope in
			// `deferredCompletions[agentId][taskId]`. We drain that here to
			// build the per-cycle taskResults entry. Pending Jobs are
			// completed AFTER mergeRunResult so the caller's awaitResult only
			// returns once the timeline / state writes are visible.
			// The bare run result surfaced in the timeline is the error
			// (if any) or response, regardless of whether a task was picked,
			// so inbox-only and message-only transitions still get a non-null
			// timeline result.
			long endTs = Utils.getCurrentTimestamp();
			ACell newState = RT.getIn(transitionResult, AgentState.KEY_STATE);
			ACell leanResponse = RT.getIn(transitionResult, Fields.RESPONSE);
			ACell leanError = RT.getIn(transitionResult, Fields.ERROR);

			// Peek the parked envelopes (don't remove yet) so an exception
			// before completeDeferredJobs leaves them visible to the outer
			// catch sweeper — the slot is only cleared after merge succeeds.
			ConcurrentHashMap<Blob, AMap<AString, ACell>> deferred =
				deferredCompletions.get(agentId.toString());
			AMap<AString, ACell> taskResults = buildTaskResultsFromDeferred(deferred);
			ACell result = (leanError != null) ? leanError : leanResponse;

			AMap<AString, ACell> timelineEntry = Maps.of(
				K_START, CVMLong.create(startTs),
				K_END, CVMLong.create(endTs),
				Fields.OP, transitionOp,
				Fields.RESULT, result);
			// Only include non-empty collections to avoid bloat
			if (formattedTasks != null && formattedTasks.count() > 0) {
				timelineEntry = timelineEntry.assoc(Fields.TASKS, formattedTasks);
			}
			if (filteredInbox != null && filteredInbox.count() > 0) {
				timelineEntry = timelineEntry.assoc(Fields.MESSAGES, filteredInbox);
			}
			if (taskResults != null) timelineEntry = timelineEntry.assoc(Fields.TASK_RESULTS, taskResults);

			// Accumulate task results across iterations
			if (taskResults != null) {
				for (var entry : taskResults.entrySet()) {
					allTaskResults = allTaskResults.assoc(entry.getKey(), entry.getValue());
				}
			}

			// Build turns to append to session.history (only when a session
			// was picked this cycle, and the transition didn't error).
			// Order: inbox messages → picked task input → assistant response.
			// Errors and yields contribute no turns. See venue/CLAUDE.local.md
			// "Sessions S3 — Per-session history (turn shape contract)".
			AVector<ACell> turnsToAppend = Vectors.empty();
			if (pickedSessionBlob != null && leanError == null) {
				// S3f: record each inbox/chat message as a user turn so
				// session.history captures the full conversation (previously
				// only the adapter's transcript recorded these).
				if (filteredInbox != null) {
					for (long i = 0; i < filteredInbox.count(); i++) {
						ACell msgContent = RT.getIn(filteredInbox.get(i), Fields.MESSAGE);
						if (msgContent != null) {
							turnsToAppend = turnsToAppend.conj(Maps.of(
								AgentState.K_ROLE,    AgentState.ROLE_USER,
								AgentState.K_CONTENT, msgContent,
								AgentState.K_TURN_TS, CVMLong.create(startTs),
								AgentState.K_SOURCE,  AgentState.SOURCE_CHAT));
						}
					}
				}
				if (pickedTaskInput != null) {
					turnsToAppend = turnsToAppend.conj(Maps.of(
						AgentState.K_ROLE,    AgentState.ROLE_USER,
						AgentState.K_CONTENT, pickedTaskInput,
						AgentState.K_TURN_TS, CVMLong.create(startTs),
						AgentState.K_SOURCE,  AgentState.SOURCE_REQUEST));
				}
				if (leanResponse != null) {
					turnsToAppend = turnsToAppend.conj(Maps.of(
						AgentState.K_ROLE,    AgentState.ROLE_ASSISTANT,
						AgentState.K_CONTENT, leanResponse,
						AgentState.K_TURN_TS, CVMLong.create(endTs),
						AgentState.K_SOURCE,  AgentState.SOURCE_TRANSITION));
				}
			}

			// Merge results atomically (timeline, state, task cleanup, history,
			// session pending drain). Task cleanup is idempotent — the venue op
			// may have already removed completed entries from agent.tasks during
			// the transition, in which case removeCompletedTasks is a no-op.
			// History append lands in the same CAS as the timeline, so external
			// readers never see a cycle that wrote one but not the other.
			AMap<AString, ACell> merged = agent.mergeRunResult(
				newState, pickedSession, tasks, taskResults,
				timelineEntry, pickedSessionBlob, turnsToAppend,
				presentedSessionPendingCount);

			// Now that the timeline + state are persisted, claim the parked
			// envelopes (atomic remove) and complete the caller's pending
			// task Jobs. Doing this AFTER the merge guarantees that an
			// awaitResult caller sees the completed cycle's writes.
			completeDeferredJobs(deferredCompletions.remove(agentId.toString()));

			// Complete any in-flight chat for the picked session. Same
			// post-merge ordering invariant as task completion: the caller's
			// awaitResult only unblocks once the cycle's writes are visible.
			//   - leanError non-null → fail the chat Job (technical fail)
			//   - leanResponse non-null → complete with the response
			//   - both null → yield, slot stays for next cycle
			if (pickedChatJobId != null) {
				if (leanError != null) {
					// Clear the slot BEFORE completing the Job so a caller that
					// awakens on awaitResult and immediately submits a follow-up
					// chat on the same session never races a stale reservation.
					if (pickedSessionBlob != null) agent.clearChatSlot(pickedSessionBlob);
					Job chatJob = engine.jobs().getJob(pickedChatJobId);
					if (chatJob != null && !chatJob.isFinished()) {
						chatJob.fail(leanError.toString());
					}
				} else if (leanResponse != null) {
					if (pickedSessionBlob != null) agent.clearChatSlot(pickedSessionBlob);
					Job chatJob = engine.jobs().getJob(pickedChatJobId);
					if (chatJob != null && !chatJob.isFinished()) {
						chatJob.completeWith(Maps.of(
							Fields.AGENT_ID,   agentId,
							Fields.SESSION_ID, pickedSession,
							Fields.RESPONSE,   leanResponse));
					}
				}
				// else: yield — keep slot reserved for the next wake
			}

			lastResult = Maps.of(
				Fields.AGENT_ID, agentId,
				Fields.STATUS, merged.get(AgentState.KEY_STATUS),
				Fields.RESULT, result,
				Fields.TASK_RESULTS, allTaskResults);

			boolean continueLoop = AgentState.RUNNING.equals(
				RT.ensureString(merged.get(AgentState.KEY_STATUS)));
			if (!continueLoop) {
				if (tryCleanExit(coord, agent, lastResult, allTaskResults, agentId)) return;
				// Late wake arrived between merge and lock — loop again.
			}
		}
		} catch (Exception e) {
			suspendOnError(callerDID, agentId, e);
			failAllPendingForAgent(callerDID, agentId, e.getMessage());
			completeExceptionally(coord, e.getMessage());
		}
	}

	// ========== Helpers ==========

	/**
	 * Returns the effective inbox for a transition input.
	 *
	 * <p>Reads {@code input.session.pending} — the session-scoped envelope
	 * vector populated by the framework before each transition. This is the
	 * sole production path; the framework no longer puts messages under
	 * {@code Fields.MESSAGES}.</p>
	 *
	 * <p>A {@code Fields.MESSAGES} fallback is retained for unit tests that
	 * build transition inputs directly without a session map.</p>
	 *
	 * <p>Returns an empty vector (never null) for ergonomic iteration.</p>
	 *
	 * @param input the transition input map
	 * @return effective inbox vector — never null
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> effectiveMessages(ACell input) {
		ACell session = RT.getIn(input, Fields.SESSION);
		if (session != null) {
			ACell pending = RT.getIn(session, AgentState.KEY_PENDING);
			if (pending instanceof AVector) {
				return (AVector<ACell>) pending;
			}
		}
		// Fallback for unit tests that construct transition inputs directly.
		ACell messages = RT.getIn(input, Fields.MESSAGES);
		return (messages instanceof AVector) ? (AVector<ACell>) messages : Vectors.empty();
	}

	/**
	 * Returns the effective session.history for a transition input (S3c).
	 *
	 * <p>Returns the {@code input.session.history} vector if present, else
	 * {@code null}. Adapters use the null sentinel to fall back to their
	 * own state-held transcript (e.g. {@code state.transcript} in
	 * LLMAgentAdapter). Returning null (not empty) preserves "no session"
	 * vs "session with empty history" distinction.</p>
	 *
	 * @param input the transition input map
	 * @return turn-envelope vector, or null if no session present
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> sessionHistory(ACell input) {
		ACell session = RT.getIn(input, Fields.SESSION);
		if (session == null) return null;
		ACell history = RT.getIn(session, AgentState.KEY_HISTORY);
		return (history instanceof AVector) ? (AVector<ACell>) history : null;
	}

	/**
	 * Looks up the sessionId recorded on the task row keyed by the given
	 * task id, or returns null if absent or malformed.
	 */
	@SuppressWarnings("unchecked")
	private static ACell extractTaskSessionId(Index<Blob, ACell> tasks, Blob taskId) {
		if (tasks == null || taskId == null) return null;
		ACell row = tasks.get(taskId);
		if (!(row instanceof AMap)) return null;
		return ((AMap<AString, ACell>) row).get(Fields.SESSION_ID);
	}

	/**
	 * Picks the oldest task from the Index by {@code created} timestamp.
	 * Index iteration order is hash-based, so we must scan to find FIFO.
	 * Returns null if the Index is empty.
	 */
	@SuppressWarnings("unchecked")
	private static Map.Entry<Blob, ACell> pickOldestTask(Index<Blob, ACell> tasks) {
		if (tasks == null || tasks.count() == 0) return null;
		Map.Entry<Blob, ACell> oldest = null;
		long oldestTs = Long.MAX_VALUE;
		for (var entry : tasks.entrySet()) {
			ACell value = entry.getValue();
			long ts = Long.MAX_VALUE;
			if (value instanceof AMap) {
				ACell created = ((AMap<AString, ACell>) value).get(Fields.CREATED);
				if (created instanceof CVMLong) ts = ((CVMLong) created).longValue();
			}
			if (oldest == null || ts < oldestTs) {
				oldest = entry;
				oldestTs = ts;
			}
		}
		return oldest;
	}

	/**
	 * Picks the session this cycle will handle. Priority:
	 *   1. Picked task's sessionId (so the active task's session controls)
	 *   2. First session with non-empty pending
	 *   3. null (no session traffic)
	 *
	 * <p>Returned value is the AString hex sessionId, or null if no
	 * session has pending work. The transition will only see traffic for
	 * this single session per cycle.</p>
	 */
	@SuppressWarnings("unchecked")
	private static AString pickSessionForCycle(
			Map.Entry<Blob, ACell> pickedTask, AgentState agent) {
		if (pickedTask != null) {
			ACell tv = pickedTask.getValue();
			if (tv instanceof AMap) {
				ACell sid = ((AMap<AString, ACell>) tv).get(Fields.SESSION_ID);
				if (sid instanceof AString) return (AString) sid;
			}
			return null;
		}
		Blob sid = agent.pickSessionWithPending();
		return (sid != null) ? Strings.create(sid.toHexString()) : null;
	}

	/**
	 * Formats a single picked task entry as a single-element vector. Empty
	 * vector if no task was picked. Wire shape matches {@link #formatTask}.
	 */
	private static AVector<ACell> formatPickedTask(Map.Entry<Blob, ACell> picked) {
		if (picked == null) return Vectors.empty();
		return Vectors.of(formatTask(picked.getKey(), picked.getValue()));
	}

	/**
	 * Formats one canonical taskdata entry — {input, caller, created,
	 * responseSchema?, t, sessionId?, goals?} — into the transition wire
	 * shape {jobId, input, caller?, responseSchema?}.
	 */
	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> formatTask(Blob jobId, ACell raw) {
		AMap<AString, ACell> taskMap = (raw instanceof AMap) ? (AMap<AString, ACell>) raw : null;
		ACell taskInput = (taskMap != null) ? taskMap.get(Fields.INPUT) : raw;
		ACell caller = (taskMap != null) ? taskMap.get(Fields.CALLER) : null;
		ACell responseSchema = (taskMap != null) ? taskMap.get(Fields.RESPONSE_SCHEMA) : null;
		AMap<AString, ACell> task = Maps.of(
			Fields.JOB_ID, taskIdHex(jobId),
			Fields.INPUT, taskInput);
		if (caller != null) task = task.assoc(Fields.CALLER, caller);
		if (responseSchema != null) task = task.assoc(Fields.RESPONSE_SCHEMA, responseSchema);
		return task;
	}

	/**
	 * Extracts the sessionId blob from a canonical taskdata map. Returns
	 * null if the value isn't a map or carries no parseable sessionId.
	 */
	@SuppressWarnings("unchecked")
	private static Blob extractSessionIdFromTask(ACell taskValue) {
		if (!(taskValue instanceof AMap)) return null;
		ACell sid = ((AMap<AString, ACell>) taskValue).get(Fields.SESSION_ID);
		if (sid == null) return null;
		AString sidStr = RT.ensureString(sid);
		if (sidStr == null) return null;
		try { return Blob.fromHex(sidStr.toString()); }
		catch (Exception e) { return null; }
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

	/**
	 * Attempts a clean exit from the run loop. Called when the loop has
	 * observed empty queues or a non-RUNNING status.
	 *
	 * <p>Acquires the coord lock and re-checks under it whether new work has
	 * arrived (wake flag set, or inbox/tasks non-empty) since the last merge.
	 * If so, marks the agent RUNNING again and returns false so the caller
	 * loops. Otherwise completes the run future, clears the coord slot, and
	 * returns true.</p>
	 *
	 * <p>This is the only place that transitions a live run to "done" under
	 * normal flow — exactly symmetric with {@link #wakeAgent} which is the
	 * only place that transitions idle to "running". The shared coord lock
	 * guarantees no wake is lost between the loop's final merge and exit.</p>
	 *
	 * @return true if exit happened (caller must return), false if a late
	 *         wake was observed and the loop should continue
	 */
	private boolean tryCleanExit(RunCoordinator coord, AgentState agent,
			ACell lastResult, AMap<AString, ACell> allTaskResults, AString agentId) {
		coord.lock.lock();
		try {
			// Re-read agent under the lock — wake flag or queues may have
			// changed since the loop started this iteration.
			if (agent.shouldWake() || hasWork(agent)) {
				agent.setStatus(AgentState.RUNNING);
				return false;
			}
			agent.setStatus(AgentState.SLEEPING);
			CompletableFuture<ACell> f = coord.completion;
			coord.completion = null;
			if (f != null) {
				// Use the last iteration's transition result (if any) but
				// force status to SLEEPING — the last merge may have written
				// RUNNING because the initial wake flag was still set when
				// mergeRunResult read the record, but we're exiting now.
				ACell result = Maps.of(
					Fields.AGENT_ID, agentId,
					Fields.STATUS, AgentState.SLEEPING,
					Fields.RESULT, lastResult != null ? RT.getIn(lastResult, Fields.RESULT) : null,
					Fields.TASK_RESULTS, allTaskResults);
				f.complete(result);
			}
			return true;
		} finally {
			coord.lock.unlock();
		}
	}

	/**
	 * Safety-net exit used when the loop hits {@link #MAX_LOOP_ITERATIONS}.
	 * Bypasses the wake/hasWork re-check — if we've looped this many times,
	 * something is wrong and we must release the slot regardless.
	 */
	private void forceExit(RunCoordinator coord, ACell result) {
		coord.lock.lock();
		try {
			CompletableFuture<ACell> f = coord.completion;
			coord.completion = null;
			if (f != null) f.complete(result);
		} finally {
			coord.lock.unlock();
		}
	}

	/** Exceptional exit — used when the loop catches an exception or the agent vanishes. */
	private void completeExceptionally(RunCoordinator coord, String message) {
		coord.lock.lock();
		try {
			CompletableFuture<ACell> f = coord.completion;
			coord.completion = null;
			if (f != null) f.completeExceptionally(new RuntimeException(message));
		} finally {
			coord.lock.unlock();
		}
	}

	/**
	 * Fails all pending work for an agent that is being abandoned (e.g.
	 * suspended on a run-loop exception). Sweeps three sources:
	 * <ul>
	 *   <li>Tasks still listed in {@code agent.getTasks()} — venue op was
	 *       never called; the caller's Job is still waiting in PENDING/STARTED.</li>
	 *   <li>Envelopes parked in {@link #deferredCompletions} — venue op was
	 *       called but the framework didn't reach {@code completeDeferredJobs}
	 *       (e.g. exception fired between the inner peek and the post-merge
	 *       remove). These would otherwise leak indefinitely.</li>
	 *   <li>Per-session chat slots — {@code agent:chat} reserved a slot
	 *       awaiting the next response. Any agent error must surface as a
	 *       chat Job failure rather than leaving the caller blocked forever.</li>
	 * </ul>
	 * Each surviving Job is failed with {@code error}.
	 */
	@SuppressWarnings("unchecked")
	private void failAllPendingForAgent(AString callerDID, AString agentId, String error) {
		AgentState agent = getAgent(callerDID, agentId);
		if (agent != null) {
			Index<Blob, ACell> tasks = agent.getTasks();
			if (tasks != null) {
				for (var entry : tasks.entrySet()) {
					Job pending = engine.jobs().getJob(entry.getKey());
					if (pending != null && !pending.isFinished()) {
						pending.fail(error);
					}
				}
			}
			Index<Blob, ACell> sessions = agent.getSessions();
			if (sessions != null) {
				for (var entry : sessions.entrySet()) {
					Blob sid = entry.getKey();
					Blob chatJobId = agent.getChatJob(sid);
					if (chatJobId == null) continue;
					Job chatJob = engine.jobs().getJob(chatJobId);
					if (chatJob != null && !chatJob.isFinished()) {
						chatJob.fail(error);
					}
					agent.clearChatSlot(sid);
				}
			}
		}
		ConcurrentHashMap<Blob, AMap<AString, ACell>> deferred =
			deferredCompletions.remove(agentId.toString());
		if (deferred != null) {
			for (var e : deferred.entrySet()) {
				Job pending = engine.jobs().getJob(e.getKey());
				if (pending != null && !pending.isFinished()) {
					pending.fail(error);
				}
			}
		}
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

	/**
	 * Parses the {@code config} input argument to an {@link AMap}, accepting
	 * either an inline map or a string reference (workspace path, asset ref,
	 * DID URL, or standard template name).
	 *
	 * @throws IllegalArgumentException if a string reference cannot be resolved
	 * @return the resolved config map, or {@code null} if no config was provided
	 */
	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> parseConfigArg(ACell configArg, RequestContext ctx) {
		if (configArg == null) return null;
		if (configArg instanceof AMap<?,?> m) return (AMap<AString, ACell>) m;
		AString ref = RT.ensureString(configArg);
		if (ref == null) return null;
		// MCP oneOf may deliver a JSON object as a string — parse it
		String s = ref.toString();
		if (s.startsWith("{")) {
			ACell parsed = JSON.parse(s);
			if (parsed instanceof AMap<?,?> pm) return (AMap<AString, ACell>) pm;
		}
		AMap<AString, ACell> resolved = resolveConfigRef(ref, ctx);
		if (resolved == null) {
			throw new IllegalArgumentException("Could not resolve config reference: " + ref);
		}
		return resolved;
	}

	/**
	 * Processes the {@code overwrite} flag for a target agent slot. If the slot
	 * is occupied by a TERMINATED agent and {@code overwrite} is true, the
	 * existing agent is removed. If the slot is occupied by a non-TERMINATED
	 * agent and {@code overwrite} is true, the job is failed.
	 *
	 * <p>When {@code overwrite} is false and the slot is occupied, this method
	 * does nothing — callers decide whether that's an error (fork) or idempotent
	 * (create).</p>
	 *
	 * @return the slot occupancy AFTER this call — {@link Boolean#TRUE} if still
	 *         occupied, {@link Boolean#FALSE} if free, or {@code null} if the
	 *         job was failed by this method
	 */
	private Boolean applyOverwrite(Job job, User user, AString agentId, boolean overwrite) {
		AgentState existing = user.agent(agentId);
		if (existing == null || !existing.exists()) return Boolean.FALSE;

		if (!overwrite) return Boolean.TRUE;

		if (AgentState.TERMINATED.equals(existing.getStatus())) {
			user.removeAgent(agentId);
			return Boolean.FALSE;
		}
		job.fail("Cannot overwrite agent that is not TERMINATED (status: " + existing.getStatus() + ")");
		return null;
	}

	/**
	 * Resolves a string reference to a config map via standard lattice path
	 * resolution. Accepts any resolvable form: venue paths
	 * ({@code v/agents/templates/manager}), workspace paths ({@code w/configs/my}),
	 * pinned operations ({@code o/my-config}), asset hashes, DID URLs, etc.
	 * Returns the resolved value if it's a map, or {@code null} otherwise.
	 */
	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> resolveConfigRef(AString ref, RequestContext ctx) {
		ACell value = engine.resolvePath(ref, ctx);
		if (value instanceof AMap<?,?> m) return (AMap<AString, ACell>) m;
		return null;
	}

	// ========== ID generation ==========

	private synchronized Blob generateSessionId() {
		long ts = Utils.getCurrentTimestamp();
		byte[] bs = new byte[16];
		Utils.writeLong(bs, 0, ts);
		Utils.writeLong(bs, 8, sessionIdCounter++);
		return Blob.wrap(bs);
	}

	private static AString taskIdHex(Blob id) {
		return Strings.create(id.toHexString());
	}

	/**
	 * Resolves the sessionId from input, minting a new one if absent, and
	 * ensures a session record exists on the agent. Returns the sid, or
	 * {@code null} if the input's sessionId is malformed (job is failed).
	 */
	private Blob resolveOrMintSession(Job job, AgentState agent, ACell input, AString caller) {
		ACell sidCell = RT.getIn(input, Fields.SESSION_ID);
		Blob sid;
		if (sidCell != null) {
			AString s = RT.ensureString(sidCell);
			if (s == null) { job.fail("sessionId must be a hex string"); return null; }
			sid = Blob.fromHex(s.toString());
			if (sid == null) {
				job.fail("Invalid sessionId format: " + s);
				return null;
			}
		} else {
			sid = generateSessionId();
		}
		agent.ensureSession(sid, caller);
		return sid;
	}

	/**
	 * Chat-specific session resolution. Differs from {@link #resolveOrMintSession}
	 * in one critical way: a {@code sessionId} that is provided but does not
	 * exist on the agent is rejected — we do not silently create a session
	 * for the caller. This matches §5.5 (agent_chat row): {@code sessionId
	 * present and unknown → Error}. Mint-on-missing only happens when the
	 * caller supplied no {@code sessionId} at all.
	 */
	private Blob resolveSessionForChat(Job job, AgentState agent, ACell input, AString caller) {
		ACell sidCell = RT.getIn(input, Fields.SESSION_ID);
		if (sidCell != null) {
			AString s = RT.ensureString(sidCell);
			if (s == null) { job.fail("sessionId must be a hex string"); return null; }
			Blob sid = Blob.fromHex(s.toString());
			if (sid == null) { job.fail("Invalid sessionId format: " + s); return null; }
			if (agent.getSession(sid) == null) {
				job.fail("Unknown sessionId: " + s + " — omit sessionId to start a new session");
				return null;
			}
			return sid;
		}
		Blob sid = generateSessionId();
		agent.ensureSession(sid, caller);
		return sid;
	}
}
