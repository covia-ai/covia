package covia.adapter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
import covia.venue.AgentScheduler;
import covia.venue.AgentState;
import covia.venue.RequestContext;
import covia.venue.User;
import covia.venue.Users;

/**
 * Adapter for agent lifecycle management.
 *
 * <p><b>Run-loop concurrency model.</b> One virtual thread per agent at any
 * time. Launch is serialised by an atomic CAS on {@link #runningLoops}: the
 * first {@code wakeAgent} caller that finds an empty slot installs a
 * completion future and starts the loop; subsequent concurrent wakes see the
 * live slot and return that future unchanged. The running loop drains work
 * from the lattice ({@code session.pending}, {@code tasks}) on each
 * iteration — wakes that arrive during a cycle write to the lattice and are
 * picked up by {@code hasWork()} at the top of the next iteration, not via
 * thread signalling.</p>
 *
 * <p>Transitions are invoked with a blocking {@code .join()} on the virtual
 * thread — cheap (vthreads park without consuming an OS thread), no yield
 * plumbing needed. Long-running external ops (HTTP, slow LLM, HITL) simply
 * park the vthread until the future completes. Self-chat and async resume
 * work without races because work arrives on the lattice and is naturally
 * drained by the same loop.</p>
 *
 * <p>The lattice {@code K_STATUS} field mirrors the runtime state for
 * restart recovery and observability — it is not the concurrency
 * primitive.</p>
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
	 * Per-agent launcher slot. Presence of a non-done entry means a virtual
	 * thread is currently running the agent's loop; the value is that
	 * thread's completion future (callers of {@code wakeAgent} that are
	 * waiting can join on it). {@link ConcurrentHashMap#compute} provides the
	 * atomic "install if absent or done" primitive that serialises launch
	 * without any lock.
	 */
	private final ConcurrentHashMap<AString, CompletableFuture<ACell>> runningLoops
		= new ConcurrentHashMap<>();

	/** Active transition job per agent — allows suspend to cancel running transitions */
	private final ConcurrentHashMap<AString, CompletableFuture<ACell>> activeTransitions = new ConcurrentHashMap<>();

	/**
	 * Per-agent deferred task completions written by {@code agent:complete-task}
	 * and {@code agent:fail-task} during a transition cycle. The framework
	 * drains these AFTER {@code mergeRunResult} has written the timeline, so
	 * the caller's {@code awaitResult} only returns once the cycle is fully
	 * persisted. Inner key is the task (== caller Job) ID.
	 */
	private final ConcurrentHashMap<AString, ConcurrentHashMap<Blob, AMap<AString, ACell>>> deferredCompletions
		= new ConcurrentHashMap<>();

	/**
	 * Per-agent in-flight chat Jobs keyed by session ID. An entry reserves
	 * the chat slot for its session — a subsequent {@code agent:chat} on the
	 * same session fails fast while the entry is live. The slot is released
	 * when the run loop completes the Job, when the caller's Job is cancelled
	 * (via a cancel hook registered in {@link #handleChat}), or when
	 * {@link #failAllPendingForAgent} sweeps on technical failure. Keeping
	 * the reservation in memory (not on the lattice) lets {@code Job.isFinished()}
	 * act as the truth — no separate CAS required, and a cancelled caller
	 * Job naturally frees the slot.
	 */
	private final ConcurrentHashMap<AString, ConcurrentHashMap<Blob, Job>> activeChats = new ConcurrentHashMap<>();

	/**
	 * Test-only: injects a chat reservation so a follow-up {@code agent:chat}
	 * on the same session hits the busy-slot path deterministically without
	 * needing a real long-running transition to hold the slot.
	 */
	public void reserveChatSlotForTest(AString agentId, Blob sid, Job job) {
		activeChats.computeIfAbsent(agentId, k -> new ConcurrentHashMap<>())
			.put(sid, job);
	}

	/**
	 * Test-only: returns the live chat Job for the given session (the one
	 * reserving the in-memory slot), or {@code null} if no reservation
	 * is held. Returns null if the previous holder's Job has since finished
	 * — matches the semantics used by the run loop.
	 */
	public Job getActiveChatForTest(AString agentId, Blob sid) {
		ConcurrentHashMap<Blob, Job> agentChats = activeChats.get(agentId);
		if (agentChats == null) return null;
		Job j = agentChats.get(sid);
		return (j != null && !j.isFinished()) ? j : null;
	}

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
		// Only the two task-completion venue ops are reachable via
		// invokeInternal (the framework invokes them from adapter transitions
		// without creating a sub-Job). All other agent ops require the
		// Job-aware invoke(Job, ...) path — they are external, user-facing,
		// and produce observable Jobs.
		String subOp = getSubOperation(meta);
		try {
			switch (subOp) {
				case "completeTask" -> {
					return CompletableFuture.completedFuture(doCompleteTask(input, ctx));
				}
				case "failTask" -> {
					return CompletableFuture.completedFuture(doFailTask(input, ctx));
				}
				default -> {
					return CompletableFuture.failedFuture(new UnsupportedOperationException(
						"agent:" + subOp + " requires Job-aware invocation"));
				}
			}
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
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

		// Reserve the per-session chat slot (in-memory). Fails fast if a
		// live chat is already in flight — but a previous caller whose Job
		// has since finished (completed or cancelled) no longer holds the
		// slot. Register a cancel hook so the caller cancelling their own
		// Job immediately frees the slot for a retry.
		ConcurrentHashMap<Blob, Job> agentChats = activeChats
			.computeIfAbsent(agentId, k -> new ConcurrentHashMap<>());
		Job existing = agentChats.get(sid);
		if (existing != null && !existing.isFinished()) {
			job.fail("Session " + sidHex + " already has an in-flight chat");
			return;
		}
		agentChats.put(sid, job);
		final ConcurrentHashMap<Blob, Job> chatsRef = agentChats;
		final Blob sidRef = sid;
		final Job jobRef = job;
		job.setCancelHook(() -> chatsRef.remove(sidRef, jobRef));

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

	/**
	 * Fallback kick to nudge the agent's run loop. <b>Not a result-getter.</b>
	 *
	 * <p>Trigger carries no payload and makes no guarantee about what the
	 * agent produces — it only guarantees the run loop gets a cycle (subject
	 * to the usual gates). Callers who want a response should submit work
	 * via {@code agent:request} / {@code agent:chat} and wait on the
	 * returned Job. Use trigger only when normal intake isn't enough — e.g.
	 * after a manual state edit, for diagnostics, or to resume a stuck agent.
	 *
	 * <p>The {@code wait} param controls how long this call blocks, not what
	 * is awaited. With the non-blocking run loop, a "completed" wait means
	 * the current cycle has either quiesced (SLEEPING) or yielded on an
	 * async transition op (still RUNNING). It does not mean the agent's
	 * task/chat work is done.
	 */
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

		// Default wait=true: block until the loop drains all work and the
		// completion future resolves. This is a blocking wait on the run
		// loop, NOT a result-await — the caller gets a status snapshot,
		// not agent output. For output, wait on the task/chat Job returned
		// by agent:request / agent:chat.
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
		CompletableFuture<ACell> activeTransition = activeTransitions.get(agentId);
		if (activeTransition != null) activeTransition.cancel(true);

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
		try {
			job.setStatus(Status.STARTED);
			job.completeWith(doCompleteTask(input, ctx));
		} catch (Exception e) {
			job.fail(e.getMessage());
		}
	}

	private ACell doCompleteTask(ACell input, RequestContext ctx) {
		AString agentId = ctx.getAgentId();
		Blob taskId = ctx.getTaskId();
		if (agentId == null || taskId == null) {
			throw new IllegalArgumentException(
				"agent:completeTask requires task scope (agentId + taskId in RequestContext)");
		}

		AgentState agent = requireAgent(ctx.getCallerDID(), agentId);
		Index<Blob, ACell> tasks = agent.getTasks();
		if (tasks == null || tasks.get(taskId) == null) {
			throw new IllegalArgumentException("Task not found: " + taskId.toHexString());
		}

		ACell result = RT.getIn(input, Fields.RESULT);
		parkCompletion(agentId, tasks, taskId, Status.COMPLETE, Fields.OUTPUT, result);
		agent.removeTask(taskId);

		return Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.TASK_ID,  taskIdHex(taskId),
			Fields.STATUS,   Status.COMPLETE);
	}

	/**
	 * Fails the in-scope task with an error. Mirror of {@link #handleCompleteTask}
	 * for the failure path: the pending task Job is failed with the supplied
	 * error, the task entry is removed, and the call returns
	 * {@code {agentId, taskId, status: "FAILED"}}.
	 */
	private void handleFailTask(Job job, ACell input, RequestContext ctx) {
		try {
			job.setStatus(Status.STARTED);
			job.completeWith(doFailTask(input, ctx));
		} catch (Exception e) {
			job.fail(e.getMessage());
		}
	}

	private ACell doFailTask(ACell input, RequestContext ctx) {
		AString agentId = ctx.getAgentId();
		Blob taskId = ctx.getTaskId();
		if (agentId == null || taskId == null) {
			throw new IllegalArgumentException(
				"agent:failTask requires task scope (agentId + taskId in RequestContext)");
		}

		AgentState agent = requireAgent(ctx.getCallerDID(), agentId);
		Index<Blob, ACell> tasks = agent.getTasks();
		if (tasks == null || tasks.get(taskId) == null) {
			throw new IllegalArgumentException("Task not found: " + taskId.toHexString());
		}

		ACell errorCell = RT.getIn(input, Fields.ERROR);
		AString errorStr = (errorCell == null) ? Strings.create("Task failed") : Strings.create(errorCell.toString());
		parkCompletion(agentId, tasks, taskId, Status.FAILED, Fields.ERROR, errorStr);
		agent.removeTask(taskId);

		return Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.TASK_ID,  taskIdHex(taskId),
			Fields.STATUS,   Status.FAILED);
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
			.computeIfAbsent(agentId, k -> new ConcurrentHashMap<>())
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
	 * Wakes the agent: persists the wake flag and launches a fresh run loop
	 * if none is currently running. Returns the live loop's completion
	 * future, or null if the agent doesn't exist / has no work and wake
	 * isn't forced.
	 *
	 * <p>All run-loop concurrency flows through this single entry point.
	 * Launch is serialised by {@link ConcurrentHashMap#compute} on
	 * {@link #runningLoops} — at most one virtual thread per agent. Wakes
	 * that arrive during a live cycle write to the lattice (session.pending,
	 * tasks) and are picked up by the running loop's {@code hasWork()} check
	 * at the top of the next iteration; they do not need to be signalled
	 * across threads. The lattice {@code K_STATUS} mirrors runtime state
	 * for observability, not concurrency control.</p>
	 *
	 * @param force if true, skips the {@code hasWork} gate — used by
	 *              explicit triggers and scheduler fires that always want
	 *              to try running
	 */
	public CompletableFuture<ACell> wakeAgent(AString agentId, RequestContext ctx, boolean force) {
		AString callerDID = ctx.getCallerDID();

		AgentState agent = getAgent(callerDID, agentId);
		if (agent == null) return null;

		// Fast path: live loop exists, attach to it. A done future in the
		// slot is treated as "no live loop" — the exiting loop removes it
		// in its finally block, but we may observe it briefly.
		CompletableFuture<ACell> existing = runningLoops.get(agentId);
		if (existing != null && !existing.isDone()) return existing;

		// Phantom RUNNING recovery (#64): if lattice shows RUNNING but no
		// live loop in our map, it's a crash remnant or stale write. Correct
		// to SLEEPING so a fresh loop can start; otherwise the agent would
		// be locked out indefinitely.
		AString status = agent.getStatus();
		if (AgentState.RUNNING.equals(status)) {
			log.warn("Agent {} in phantom RUNNING state with no live run; "
				+ "correcting to SLEEPING", agentId);
			agent.setStatus(AgentState.SLEEPING);
			status = AgentState.SLEEPING;
		}

		// Only start a loop from SLEEPING. Suspended or terminated agents
		// keep their wake flag for later resume.
		if (!AgentState.SLEEPING.equals(status)) return null;

		// Gate: only start if there's work (or forced).
		if (!force && !hasWork(agent)) return null;

		AString transitionOp = resolveTransitionOp(callerDID, agentId);
		if (transitionOp == null) return null;

		// Atomic CAS: install our completion only if no other loop is live.
		// compute's function runs atomically under CHM's bucket lock, so the
		// check+install is a single operation — no lost-launch race with
		// concurrent wakeAgent calls.
		CompletableFuture<ACell> mine = new CompletableFuture<>();
		CompletableFuture<ACell> installed = runningLoops.compute(agentId, (k, cur) -> {
			if (cur != null && !cur.isDone()) return cur;
			return mine;
		});
		if (installed != mine) {
			// Someone else won the launch race — use their future.
			return installed;
		}

		agent.setStatus(AgentState.RUNNING);
		final AString finalOp = transitionOp;
		final CompletableFuture<ACell> finalCompletion = mine;
		Thread.ofVirtual().start(
			() -> executeRunLoop(agentId, callerDID, finalOp, ctx, finalCompletion));
		return mine;
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

	/**
	 * Carries per-iteration outputs back to {@link #executeRunLoop}. The
	 * decision "continue looping" lives in the loop itself — it just checks
	 * {@code hasWork()} at the top of the next iteration, same criterion
	 * used at launch time.
	 */
	private record IterResult(ACell lastResult, AMap<AString, ACell> allTaskResults) {}

	/**
	 * Runs an agent's cycle loop on a single virtual thread. Each iteration:
	 * <ol>
	 *   <li>Checks for work on the lattice (session.pending, tasks). No work
	 *       (and no explicit wake) → exit the loop.</li>
	 *   <li>Picks one task / one session and builds transition input.</li>
	 *   <li>Invokes the transition and blocks on its future via
	 *       {@code .join()}. Virtual thread parking is cheap; a slow or
	 *       long-running op (HTTP, HITL, slow LLM) does not consume an OS
	 *       thread. Self-chat and other in-transition ops that enqueue more
	 *       work on the lattice are picked up by the same loop on the next
	 *       iteration — no cross-thread wake needed.</li>
	 *   <li>Merges the transition result (timeline, history, state, task
	 *       cleanup) and drains deferred task-completion envelopes.</li>
	 * </ol>
	 *
	 * <p>Clean exit is a three-step dance that closes the exit/wake race:
	 * loop exits → {@code finally} removes the loop from
	 * {@link #runningLoops} → re-checks {@code hasWork}. Any wake whose
	 * lattice write landed before that re-check is picked up and a fresh
	 * loop is launched via {@link #wakeAgent}. Any wake whose write lands
	 * after the re-check sees the empty launcher slot and launches its own
	 * loop — the CAS in {@code wakeAgent} guarantees at most one survives.</p>
	 */
	@SuppressWarnings("unchecked")
	private void executeRunLoop(AString agentId, AString callerDID,
			AString transitionOp, RequestContext ctx,
			CompletableFuture<ACell> completion) {
		ACell lastResult = null;
		int iteration = 0;
		AMap<AString, ACell> allTaskResults = Maps.empty();
		boolean firstIteration = true;

		try {
			while (true) {
				if (++iteration > MAX_LOOP_ITERATIONS) {
					log.warn("Agent {} hit max loop iterations ({}), forcing sleep",
						agentId, MAX_LOOP_ITERATIONS);
					break;
				}

				AgentState agent = getAgent(callerDID, agentId);
				if (agent == null) {
					completion.completeExceptionally(
						new RuntimeException("Agent not found: " + agentId));
					return;
				}

				Index<Blob, ACell> tasks = agent.getTasks();
				Index<Blob, ACell> pending = agent.getPending();
				ACell currentState = agent.getState();

				// Honour external SUSPENDED/TERMINATED — if someone (e.g.
				// handleSuspend) flipped our status while we were between
				// iterations, exit promptly. The merge step preserves the
				// status via the same rule, so we won't clobber it here.
				AString curStatus = agent.getStatus();
				if (AgentState.SUSPENDED.equals(curStatus)
						|| AgentState.TERMINATED.equals(curStatus)) {
					break;
				}

				// On subsequent iterations, exit cleanly if no work remains.
				// The finally block performs a post-exit re-check that closes
				// the exit/wake race without a lock.
				if (!firstIteration && !agent.hasSessionPending() && tasks.count() == 0) {
					break;
				}
				firstIteration = false;

				long startTs = Utils.getCurrentTimestamp();

				// Pick at most one task per cycle (oldest by created timestamp).
				// Multi-task agents fan out across cycles.
				Map.Entry<Blob, ACell> pickedTask = pickOldestTask(tasks);
				AVector<ACell> formattedTasks = formatPickedTask(pickedTask);
				AVector<ACell> resolvedPending = resolveJobIds(pending, Fields.OUTPUT);

				// Pick at most one session per cycle. Priority:
				//   1. picked task's sessionId (so the task's session controls)
				//   2. else the first session with non-empty pending
				//   3. else null (no session traffic)
				AString pickedSession = pickSessionForCycle(pickedTask, agent);
				Blob pickedSessionBlob = (pickedSession != null)
					? Blob.parse(pickedSession.toString()) : null;
				AVector<ACell> filteredInbox = (pickedSessionBlob != null)
					? agent.getSessionPending(pickedSessionBlob) : Vectors.empty();

				Job pickedChatJob = null;
				AMap<AString, ACell> pickedSessionRecord = null;
				long presentedSessionPendingCount = filteredInbox.count();
				if (pickedSessionBlob != null) {
					ConcurrentHashMap<Blob, Job> agentChats = activeChats.get(agentId);
					if (agentChats != null) {
						Job candidate = agentChats.get(pickedSessionBlob);
						if (candidate != null && !candidate.isFinished()) {
							pickedChatJob = candidate;
						}
					}
					pickedSessionRecord = agent.getSession(pickedSessionBlob);
				}

				// Per-cycle ctx: scope to the agent, picked task, and session so
				// path resolvers (n/, t/, c/) can address the right slot, and
				// agent:completeTask / agent:failTask can identify which
				// agent + task they're acting on.
				RequestContext cycleCtx = ctx.withAgentId(agentId);
				if (pickedTask != null) cycleCtx = cycleCtx.withTaskId(pickedTask.getKey());
				if (pickedSessionBlob != null) cycleCtx = cycleCtx.withSessionId(pickedSessionBlob);

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
				if (pickedSessionRecord != null) {
					AMap<AString, ACell> sessionMap = pickedSessionRecord
						.assoc(Fields.SESSION_ID, pickedSession);
					transitionInput = transitionInput.assoc(Fields.SESSION, sessionMap);
				}
				AMap<AString, ACell> agentConfig = agent.getConfig();
				if (agentConfig != null) {
					transitionInput = transitionInput.assoc(AgentState.KEY_CONFIG, agentConfig);
				}

				// Invoke transition. Blocks on the vthread — cheap, and any
				// work the transition enqueues on the lattice (e.g. a nested
				// agent:chat that wakes this same agent) is naturally visible
				// to the next iteration via hasWork(). A cancelled or errored
				// future surfaces as an error-shaped transitionResult so the
				// merge path handles it normally.
				CompletableFuture<ACell> transitionFuture =
					engine.jobs().invokeInternal(transitionOp, transitionInput, cycleCtx);
				activeTransitions.put(agentId, transitionFuture);

				ACell transitionResult;
				try {
					transitionResult = transitionFuture.join();
				} catch (java.util.concurrent.CancellationException ce) {
					transitionResult = Maps.of(Fields.ERROR,
						Strings.create("Transition cancelled"));
				} catch (java.util.concurrent.CompletionException e) {
					Throwable cause = (e.getCause() != null) ? e.getCause() : e;
					transitionResult = Maps.of(Fields.ERROR,
						Strings.create("Transition failed: " + cause.getMessage()));
				} finally {
					activeTransitions.remove(agentId, transitionFuture);
				}

				IterResult merged = mergeAndPostProcess(
					agent, agentId, callerDID, transitionOp, transitionResult, pickedTask,
					pickedTaskInput, formattedTasks, pickedSession,
					pickedSessionBlob, pickedChatJob, filteredInbox,
					presentedSessionPendingCount, tasks, startTs, allTaskResults);
				lastResult = merged.lastResult();
				allTaskResults = merged.allTaskResults();
			}

			// Clean exit: mark SLEEPING (atomic — preserves SUSPENDED /
			// TERMINATED if set externally), complete the completion with
			// the last cycle's result. Report whatever status we ended up
			// on so callers of the completion see the true final state.
			AgentState agent = getAgent(callerDID, agentId);
			AString finalStatus = AgentState.SLEEPING;
			if (agent != null) {
				agent.sleep();
				AString observed = agent.getStatus();
				if (observed != null) finalStatus = observed;
			}
			completion.complete(Maps.of(
				Fields.AGENT_ID, agentId,
				Fields.STATUS, finalStatus,
				Fields.RESULT, lastResult != null ? RT.getIn(lastResult, Fields.RESULT) : null,
				Fields.TASK_RESULTS, allTaskResults));
		} catch (Exception e) {
			suspendOnError(callerDID, agentId, e);
			failAllPendingForAgent(callerDID, agentId, e.getMessage());
			completion.completeExceptionally(new RuntimeException(e.getMessage()));
		} finally {
			// Release the launcher slot, then re-check for wakes that may
			// have arrived while this loop was exiting. remove(key, value)
			// only clears the slot if it still holds OUR completion — a
			// concurrent wakeAgent that already took over won't be disturbed.
			runningLoops.remove(agentId, completion);
			AgentState agent = getAgent(callerDID, agentId);
			if (agent != null && AgentState.SLEEPING.equals(agent.getStatus())
					&& hasWork(agent)) {
				wakeAgent(agentId, ctx, false);
			}
		}
	}

	/**
	 * Merges a transition result into the agent record and handles all the
	 * post-transition bookkeeping: timeline entry, history turns, deferred
	 * task-completion envelopes, in-flight chat completion.
	 */
	@SuppressWarnings("unchecked")
	private IterResult mergeAndPostProcess(
			AgentState agent, AString agentId, AString callerDID,
			AString transitionOp,
			ACell transitionResult, Map.Entry<Blob, ACell> pickedTask,
			ACell pickedTaskInput, AVector<ACell> formattedTasks,
			AString pickedSession, Blob pickedSessionBlob, Job pickedChatJob,
			AVector<ACell> filteredInbox, long presentedSessionPendingCount,
			Index<Blob, ACell> tasks, long startTs,
			AMap<AString, ACell> allTaskResults) {
		long endTs = Utils.getCurrentTimestamp();
		ACell newState = RT.getIn(transitionResult, AgentState.KEY_STATE);
		ACell leanResponse = RT.getIn(transitionResult, Fields.RESPONSE);
		ACell leanError = RT.getIn(transitionResult, Fields.ERROR);

		// Peek the parked envelopes (don't remove yet) so an exception
		// before completeDeferredJobs leaves them visible to the outer
		// catch sweeper — the slot is only cleared after merge succeeds.
		ConcurrentHashMap<Blob, AMap<AString, ACell>> deferred =
			deferredCompletions.get(agentId);
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
		AVector<ACell> turnsToAppend = Vectors.empty();
		if (pickedSessionBlob != null && leanError == null) {
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
		// session pending drain). History append lands in the same CAS as
		// the timeline, so external readers never see a cycle that wrote
		// one but not the other.
		AMap<AString, ACell> merged = agent.mergeRunResult(
			newState, pickedSession, tasks, taskResults,
			timelineEntry, pickedSessionBlob, turnsToAppend,
			presentedSessionPendingCount);

		// Per-thread scheduled wake (B8.8). Transition result may carry a
		// `wakeTime` (absolute wall-clock millis) requesting a future fire on
		// the picked thread. If present, install it via setThreadWakeTime
		// (lattice-first, then scheduler). If absent but the picked record
		// still carries a stale `wakeTime` from a just-consumed scheduler
		// fire, clear it so the scheduler rebuild on restart doesn't re-fire
		// a wake that's already been serviced. Lattice is authoritative; the
		// scheduler index is rebuilt from it on boot.
		AgentScheduler scheduler = engine.scheduler();
		if (scheduler != null && leanError == null) {
			Blob pickedThreadId = null;
			AgentScheduler.ThreadKind pickedKind = null;
			AMap<AString, ACell> pickedRecord = null;
			if (pickedTask != null) {
				pickedThreadId = pickedTask.getKey();
				pickedKind = AgentScheduler.ThreadKind.TASK;
				Index<Blob, ACell> currentTasks = agent.getTasks();
				ACell tRec = (currentTasks != null) ? currentTasks.get(pickedThreadId) : null;
				if (tRec instanceof AMap) pickedRecord = (AMap<AString, ACell>) tRec;
			} else if (pickedSessionBlob != null) {
				pickedThreadId = pickedSessionBlob;
				pickedKind = AgentScheduler.ThreadKind.SESSION;
				pickedRecord = agent.getSession(pickedSessionBlob);
			}
			if (pickedThreadId != null) {
				ACell wtCell = RT.getIn(transitionResult, Fields.WAKE_TIME);
				long requestedWake = (wtCell instanceof CVMLong cl) ? cl.longValue() : 0L;
				boolean hasExisting = pickedRecord != null
					&& pickedRecord.get(Fields.WAKE_TIME) instanceof CVMLong;
				if (requestedWake > 0 || hasExisting) {
					agent.setThreadWakeTime(scheduler, callerDID,
						pickedKind, pickedThreadId, requestedWake);
				}
			}
		}

		// Now that the timeline + state are persisted, claim the parked
		// envelopes (atomic remove) and complete the caller's pending
		// task Jobs. Doing this AFTER the merge guarantees that an
		// awaitResult caller sees the completed cycle's writes.
		completeDeferredJobs(deferredCompletions.remove(agentId));

		// Complete any in-flight chat for the picked session. Same
		// post-merge ordering invariant as task completion.
		if (pickedChatJob != null && (leanError != null || leanResponse != null)) {
			ConcurrentHashMap<Blob, Job> agentChats = activeChats.get(agentId);
			if (agentChats != null) agentChats.remove(pickedSessionBlob, pickedChatJob);
			if (leanError != null) {
				if (!pickedChatJob.isFinished()) pickedChatJob.fail(leanError.toString());
			} else {
				if (!pickedChatJob.isFinished()) {
					pickedChatJob.completeWith(Maps.of(
						Fields.AGENT_ID,   agentId,
						Fields.SESSION_ID, pickedSession,
						Fields.RESPONSE,   leanResponse));
				}
			}
		}
		// else: yield — keep slot reserved for the next wake

		ACell lastResult = Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.STATUS, merged.get(AgentState.KEY_STATUS),
			Fields.RESULT, result,
			Fields.TASK_RESULTS, allTaskResults);

		return new IterResult(lastResult, allTaskResults);
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
		}
		ConcurrentHashMap<Blob, Job> agentChats = activeChats.remove(agentId);
		if (agentChats != null) {
			for (Job chatJob : agentChats.values()) {
				if (chatJob != null && !chatJob.isFinished()) {
					chatJob.fail(error);
				}
			}
		}
		ConcurrentHashMap<Blob, AMap<AString, ACell>> deferred =
			deferredCompletions.remove(agentId);
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

	private AgentState requireAgent(AString callerDID, AString agentId) {
		AgentState agent = getAgent(callerDID, agentId);
		if (agent == null) throw new IllegalArgumentException("Agent not found or terminated: " + agentId);
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
