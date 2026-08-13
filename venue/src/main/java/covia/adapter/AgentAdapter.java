package covia.adapter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import covia.adapter.agent.AbstractLLMAdapter;
import covia.adapter.agent.ContextBuilder;
import covia.adapter.agent.ContextInspectable;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.AgentState;
import covia.api.Abilities;
import covia.venue.RequestContext;
import covia.venue.Scheduler;
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
 * <p>{@code RUNNING} is persisted for lattice/remote observability, but the
 * live launcher slot remains authoritative for concurrency and mutation
 * safety. Startup clears stale RUNNING markers before scheduling work.</p>
 */
public class AgentAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(AgentAdapter.class);

	private static final AString K_START = Strings.intern("start");
	private static final AString K_END   = Strings.intern("end");
	private static final AString K_SOURCE_ID        = Strings.intern("sourceId");
	private static final AString K_INCLUDE_TIMELINE = Strings.intern("includeTimeline");
	private static final AString K_FORKED_FROM      = Strings.intern("forkedFrom");
	private static final AString K_AGENT_IDS        = Strings.intern("agentIds");
	private static final AString K_AGENTS           = Strings.intern("agents");
	private static final AString K_SYSTEM_PROMPT    = Strings.intern("systemPrompt");
	private static final AString K_LLM_OPERATION    = Strings.intern("llmOperation");
	private static final AString K_MODEL            = Strings.intern("model");
	private static final AString K_TOOLS            = Strings.intern("tools");
	private static final AString K_DEFAULT_TOOLS    = Strings.intern("defaultTools");
	private static final AString K_CAPS             = Strings.intern("caps");
	private static final AString K_CONTEXT          = Strings.intern("context");
	private static final AString K_OUTPUTS          = Strings.intern("outputs");
	private static final AString K_RESPONSE_FORMAT  = Strings.intern("responseFormat");
	private static final AString K_API_KEY          = Strings.intern("apiKey");
	private static final AString K_PROVIDER_OPTIONS = Strings.intern("providerOptions");
	private static final AString K_AGENT_FACET      = Strings.intern("agent");
	private static final int MAX_CONFIG_LAYER_DEPTH = 32;
	/** Persisted, non-secret requester scope used to reconstruct an output
	 * handoff after a venue restart. Live requests use the complete immutable
	 * RequestContext held in {@link #outputContexts}; raw bearer UCANs are never
	 * written into agent state. */
	private static final AString K_OUTPUT_CONTEXT   = Strings.intern("outputContext");
	private static final AString K_CONTEXT_CAPS     = Strings.intern("caps");
	private static final AString K_CONTEXT_AGENT_ID = Strings.intern("agentId");
	private static final AString K_CONTEXT_JOB_ID   = Strings.intern("jobId");
	private static final AString K_CONTEXT_SESSION_ID = Strings.intern("sessionId");
	private static final AString K_CONTEXT_TASK_ID  = Strings.intern("taskId");
	private static final AString DEFAULT_AGENT_TEMPLATE = Strings.intern("v/agents/templates/skilled");

	/** Maximum run loop iterations before forced exit (safety net) */
	private static final int MAX_LOOP_ITERATIONS = 20;
	/** Administrative shutdown must never wait forever on a wedged run loop. */
	static final long HALT_TIMEOUT_MS = 5_000;

	/**
	 * Per-agent launcher slot. Presence of a non-done entry means a virtual
	 * thread is currently running the agent's loop; the value is that
	 * thread's completion future (callers of {@code wakeAgent} that are
	 * waiting can join on it). {@link ConcurrentHashMap#compute} provides the
	 * atomic "install if absent or done" primitive that serialises launch
	 * without any lock.
	 */
	private final ConcurrentHashMap<AgentKey, CompletableFuture<ACell>> runningLoops
		= new ConcurrentHashMap<>();

	/** Active transition job per agent — allows suspend to cancel running transitions */
	private final ConcurrentHashMap<AgentKey, CompletableFuture<ACell>> activeTransitions = new ConcurrentHashMap<>();

	/** Cancellation token per active transition — flipped alongside
	 *  {@code transitionFuture.cancel(true)}, which does NOT stop the running
	 *  transition thread. Long transitions (goaltree) poll it via the cycle
	 *  ctx to stop work and lattice writes promptly. */
	private final ConcurrentHashMap<AgentKey, java.util.concurrent.atomic.AtomicBoolean> activeCancellations = new ConcurrentHashMap<>();

	/**
	 * Per-agent deferred task completions written by {@code agent:complete-task}
	 * and {@code agent:fail-task} during a transition cycle. The framework
	 * drains these AFTER {@code mergeRunResult} has written the timeline, so
	 * the caller's {@code awaitResult} only returns once the cycle is fully
	 * persisted. Inner key is the task (== caller Job) ID.
	 */
	private final ConcurrentHashMap<AgentKey, ConcurrentHashMap<Blob, AMap<AString, ACell>>> deferredCompletions
		= new ConcurrentHashMap<>();

	/**
	 * Complete requester contexts for live {@code outputPath} handoffs, keyed by
	 * the request Job (= task) ID. This preserves execution scopes and verified
	 * proof/raw-UCAN material without persisting bearer credentials in lattice
	 * state. The task row carries a non-secret identity/caps/scope snapshot as a
	 * restart-safe fallback for own-resource writes.
	 */
	private final ConcurrentHashMap<Blob, RequestContext> outputContexts
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
	private final ConcurrentHashMap<AgentKey, ConcurrentHashMap<Blob, Job>> activeChats = new ConcurrentHashMap<>();

	/**
	 * Identity of an agent on this venue: its owner's DID plus the local agent
	 * id. The in-memory run-loop registries above key on this — never the bare
	 * {@code agentId} — because agents are per-user (stored at
	 * {@code <ownerDID>/g/<id>}), so two users' agents that share a name must
	 * not collide on a single venue (see {@code AgentAdapterTest.testUserIsolation}).
	 */
	private record AgentKey(AString owner, AString id) {}

	/** True only while this venue process owns a live run-loop attempt. */
	private boolean isRunning(AgentKey key) {
		CompletableFuture<ACell> loop = runningLoops.get(key);
		return loop != null && !loop.isDone();
	}

	/**
	 * Observable status: administrative terminal/stop states take precedence.
	 * A live slot reports RUNNING; a persisted RUNNING without one is stale and
	 * observed as SLEEPING until startup cleanup lands in the lattice.
	 */
	private AString observableStatus(AString ownerDID, AString agentId, AgentState agent) {
		if (agent == null) return AgentState.TERMINATED;
		AString stable = agent.getStatus();
		if (AgentState.SUSPENDED.equals(stable) || AgentState.TERMINATED.equals(stable)) {
			return stable;
		}
		return isRunning(new AgentKey(ownerDID, agentId))
			? AgentState.RUNNING : AgentState.SLEEPING;
	}

	/**
	 * Test-only: injects a chat reservation so a follow-up {@code agent:chat}
	 * on the same session hits the busy-slot path deterministically without
	 * needing a real long-running transition to hold the slot.
	 */
	public void reserveChatSlotForTest(AString ownerDID, AString agentId, Blob sid, Job job) {
		activeChats.computeIfAbsent(new AgentKey(ownerDID, agentId), k -> new ConcurrentHashMap<>())
			.put(sid, job);
	}

	/**
	 * Test-only: returns the live chat Job for the given session (the one
	 * reserving the in-memory slot), or {@code null} if no reservation
	 * is held. Returns null if the previous holder's Job has since finished
	 * — matches the semantics used by the run loop.
	 */
	public Job getActiveChatForTest(AString ownerDID, AString agentId, Blob sid) {
		ConcurrentHashMap<Blob, Job> agentChats = activeChats.get(new AgentKey(ownerDID, agentId));
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
		installAsset("agent/delete-session", BASE + "deleteSession.json");
		installAsset("agent/rename-session", BASE + "renameSession.json");

		// Install standard agent templates at v/agents/templates/<name>.
		// Discoverable via covia_list path=v/agents/templates and usable in
		// agent:create via config="v/agents/templates/<name>".
		installAgentTemplate("minimal",  "/agent-templates/minimal.json");
		installAgentTemplate("skilled",  "/agent-templates/skilled.json");
		installAgentTemplate("reader",   "/agent-templates/reader.json");
		installAgentTemplate("worker",   "/agent-templates/worker.json");
		installAgentTemplate("manager",  "/agent-templates/manager.json");
		installAgentTemplate("analyst",  "/agent-templates/analyst.json");
		installAgentTemplate("full",     "/agent-templates/full.json");
		installAgentTemplate("goaltree", "/agent-templates/goaltree.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		// completeTask/failTask use transient Jobs (framework invokes from transitions).
		// request is reachable here from the LLM tool loop — delegates to the
		// Job-aware path to create a task Job, then races completion against
		// an optional timeout.
		String subOp = getSubOperation(meta);
		// The task-lifecycle ops are self-scoped — agentId/taskId come from the
		// RequestContext, never the input — and must stay callable under a
		// restricted transition scope (the requireAgentCap contract): a scoped
		// agent that cannot complete/fail its own task is trapped in the tool
		// loop until the iteration limit. The invoke gate covers everything else.
		if (!"completeTask".equals(subOp) && !"failTask".equals(subOp)) requireInvoke(ctx);
		try {
			switch (subOp) {
				case "completeTask" -> {
					return CompletableFuture.completedFuture(doCompleteTask(input, ctx));
				}
				case "failTask" -> {
					return CompletableFuture.completedFuture(doFailTask(input, ctx));
				}
				case "request" -> {
					return invokeRequestInternal(ctx, meta, input);
				}
				case "trigger" -> {
					// Zero-Job kick (the scheduler's deferred-wake path and the
					// LLM tool loop). Fire-and-forget: start the loop per `force`
					// and return a status snapshot immediately — `wait` does not
					// apply here. Mints no session.
					return CompletableFuture.completedFuture(doKick(ctx, input));
				}
				case "list" -> {
					// Job-free read (#180): shares the /agents route accessor.
					boolean includeTerminated = CVMBool.TRUE.equals(RT.getIn(input, Fields.INCLUDE_TERMINATED));
					return CompletableFuture.completedFuture(listAgents(ctx, includeTerminated, true));
				}
				case "info" -> {
					// Job-free read (#180): shares the /agents/{id} route accessor.
					AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
					if (agentId == null) {
						return CompletableFuture.failedFuture(
							new IllegalArgumentException("agentId is required"));
					}
					AMap<AString, ACell> summary = agentInfo(ctx, agentId);
					if (summary == null) {
						return CompletableFuture.failedFuture(
							new IllegalArgumentException("Agent '" + agentId
								+ "' was not found or is terminated; use agent:list or agent:create"));
					}
					return CompletableFuture.completedFuture(summary);
				}
				default -> {
					// Everything else — create, update, fork, chat, message,
					// delete, suspend, resume, cancelTask, deleteSession,
					// context — is JOB-WORTHY by design: an agent creating,
					// reconfiguring or conversing with another agent is a
					// system-of-record action, so the internal path (LLM tool
					// loop, context assemble ops) delegates to the Job-aware
					// dispatch and gets a real, owner-attributed Job — same
					// RequestContext, same grant scope. This is the
					// delegation pattern `request` established above; the
					// internal path previously rejected these outright (#85
					// fall-out), breaking agent ops as LLM tools.
					Job job = engine.jobs().invokeOperation(meta, input, ctx);
					return job.future().thenApply(x -> x);
				}
			}
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/**
	 * Invokes {@code agent:request} from a transient-Job context (LLM tool loop).
	 *
	 * <p>Creates a task Job via {@link #invoke(Job, RequestContext, AMap, ACell)}
	 * and races its completion against {@code input.timeout} (ms). If the task
	 * completes within the timeout, its result is returned. If not, a snapshot
	 * {@code {id, status, agentId, sessionId}} is returned — this is a success
	 * path, enabling the caller to poll via {@code grid:jobResult}. The task
	 * continues to run in the background.</p>
	 *
	 * <p>Default timeout is 5000ms — short best-effort wait so fast helpers
	 * return inline while long work falls through to the async-poll pattern.
	 * Timeout {@code <= 0} returns the snapshot immediately (pure async).</p>
	 */
	private CompletableFuture<ACell> invokeRequestInternal(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		Job taskJob = engine.jobs().invokeOperation(meta, input, ctx);
		return requestResultFuture(taskJob, input);
	}

	/** Preserve agent:request's best-effort wait/snapshot result contract when
	 * JobManager supplies the Job wrapper for run/invokeInternal. */
	@Override
	public CompletableFuture<ACell> resultFuture(Job job,
			AMap<AString, ACell> meta, ACell input) {
		if ("request".equals(getSubOperation(meta))) {
			return requestResultFuture(job, input);
		}
		return super.resultFuture(job, meta, input);
	}

	private CompletableFuture<ACell> requestResultFuture(Job taskJob, ACell input) {
		long timeoutMs = parseRequestTimeoutMs(input);
		AString sessionId = RT.ensureString(RT.getIn(input, Fields.SESSION_ID));
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));

		if (timeoutMs <= 0) {
			return CompletableFuture.completedFuture(buildRequestSnapshot(taskJob, agentId, sessionId));
		}

		// Derive a view future so orTimeout only affects this returned future,
		// not the task Job's internal completion.
		return taskJob.future().thenApply(x -> x)
				.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
				.exceptionally(ex -> {
					Throwable cause = (ex instanceof java.util.concurrent.CompletionException) ? ex.getCause() : ex;
					if (cause instanceof TimeoutException) {
						return buildRequestSnapshot(taskJob, agentId, sessionId);
					}
					throw (ex instanceof RuntimeException re) ? re : new RuntimeException(cause);
				});
	}

	private static final long DEFAULT_REQUEST_TIMEOUT_MS = 5000L;

	private static long parseRequestTimeoutMs(ACell input) {
		ACell v = RT.getIn(input, Fields.TIMEOUT);
		if (v instanceof CVMLong l) return l.longValue();
		return DEFAULT_REQUEST_TIMEOUT_MS;
	}

	private static AMap<AString, ACell> buildRequestSnapshot(Job taskJob, AString agentId, AString sessionId) {
		AMap<AString, ACell> snap = Maps.of(
			Fields.ID,       Strings.create(taskJob.getID().toHexString()),
			Fields.STATUS,   taskJob.getStatus(),
			Fields.AGENT_ID, agentId);
		if (sessionId != null) snap = snap.assoc(Fields.SESSION_ID, sessionId);
		return snap;
	}

	/**
	 * {@code force} policy for {@code agent:trigger}: {@code true} (default)
	 * runs a cycle even when the agent is idle — the historical trigger
	 * behaviour; {@code false} runs only if there is work (the scheduler's
	 * deferred-wake path). The flag is additive — absent means {@code true},
	 * so existing callers are unchanged.
	 */
	private static boolean parseForce(ACell input) {
		ACell v = RT.getIn(input, Fields.FORCE);
		return (v instanceof CVMBool b) ? b.booleanValue() : true;
	}

	/**
	 * Zero-Job trigger kick: start the agent's run loop per {@code force} and
	 * return a status snapshot immediately. Mints no session; ignores
	 * {@code wait}. Shared by the scheduler's deferred wake and the LLM tool
	 * loop. The loop, if started, runs on its own virtual thread.
	 */
	private ACell doKick(RequestContext ctx, ACell input) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) throw new IllegalArgumentException("agentId is required");
		wakeAgent(ctx.getUserDID(), agentId, parseForce(input));
		AgentState agent = getAgent(ctx.getUserDID(), agentId);
		return Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.STATUS, observableStatus(ctx.getUserDID(), agentId, agent));
	}

	/**
	 * Capability enforcement co-located with the agent op dispatch: each
	 * user-facing op pins the exact ability it needs on the agent resource
	 * ({@code g/<agentId>}). A null grant scope (authenticated/internal) is
	 * unrestricted (no-op). The internal task-lifecycle ops
	 * ({@code completeTask}/{@code failTask}) and {@code trigger}/reads are not
	 * gated here — they fall to the boundary net — so an agent with a restricted
	 * config scope can still complete its own tasks during a transition.
	 */
	private void requireAgentCap(RequestContext ctx, ACell input, String subOp) {
		AString ability = switch (subOp) {
			case "create", "fork" -> Abilities.AGENT_CREATE;
			case "request"        -> Abilities.AGENT_REQUEST;
			case "message", "chat" -> Abilities.AGENT_MESSAGE;
			case "delete", "suspend", "resume", "update", "cancelTask", "deleteSession", "renameSession" -> Abilities.AGENT_WRITE;
			default -> null; // info/list/context (reads), trigger, completeTask/failTask
		};
		if (ability == null) return;
		if ("delete".equals(subOp) && RT.getIn(input, K_AGENT_IDS) != null) {
			DeleteRequest request = parseDeleteRequest(input);
			for (long i = 0; i < request.agentIds().count(); i++) {
				engine.requireAuthority(ctx,
					Strings.create("g/" + request.agentIds().get(i)), ability);
			}
			return;
		}
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		engine.requireAuthority(ctx, agentId != null ? Strings.create("g/" + agentId) : null, ability);
	}

	@Override
	public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		if (ctx.getCallerDID() == null) {
			job.fail("Agent operations require an authenticated caller");
			return;
		}
		try {
			String subOp = getSubOperation(meta);
			// Keep the Job-aware path identical to invokeFuture: task completion
			// is context-bound framework plumbing; every other user-facing
			// operation requires invocation of its exact definition.
			if (!"completeTask".equals(subOp) && !"failTask".equals(subOp)) {
				requireInvoke(ctx);
			}
			requireAgentCap(ctx, input, subOp);
			switch (subOp) {
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
				case "deleteSession" -> handleDeleteSession(job, input, ctx);
				case "renameSession" -> handleRenameSession(job, input, ctx);
				case "completeTask" -> handleCompleteTask(job, input, ctx);
				case "failTask"     -> handleFailTask(job, input, ctx);
				default             -> job.fail("Unknown agent operation: " + getSubOperation(meta));
			}
		} catch (Exception e) {
			job.fail(describeFailure(e));
		}
	}

	// ========== Operation handlers ==========

	private void handleCreate(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }
		if (RT.getIn(input, Fields.OVERWRITE) != null) {
			job.fail("agent:create no longer supports overwrite; delete the existing agent "
				+ "with remove=true, then create it again");
			return;
		}
		Users users = engine.getVenueState().users();
		User user = users.ensure(ctx.getUserDID());
		AgentState existing = user.agent(agentId);
		if (existing != null && existing.exists()) {
			job.fail("Agent already exists: " + agentId
				+ "; update it, or delete it with remove=true before creating it again");
			return;
		}

		ACell configArg = RT.getIn(input, Fields.CONFIG);
		AMap<AString, ACell> config;
		try {
			config = parseConfigArg(configArg, ctx);
		} catch (IllegalArgumentException e) {
			job.fail(describeFailure(e)); return;
		}
		// A genuinely omitted config means "give me the useful platform
		// default", not a tool-less shell. Reuse the provider-neutral installed
		// skilled template for its prompt/read-list/skills policy, then apply this
		// venue's transition and LLM provider defaults explicitly.
		if (configArg == null) {
			config = resolveConfigRef(DEFAULT_AGENT_TEMPLATE, ctx);
			if (config == null) {
				job.fail("Default agent template is unavailable: " + DEFAULT_AGENT_TEMPLATE);
				return;
			}
			config = config
				.assoc(Fields.OPERATION, engine.config().getDefaultTransitionOp())
				.assoc(K_LLM_OPERATION, engine.config().getDefaultLlmOperation())
				.dissoc(K_MODEL);
		}

		ACell initialState = RT.getIn(input, AgentState.KEY_STATE);

		// Resolve agent definition asset if provided
		AString definitionRef = RT.ensureString(RT.getIn(input, Fields.DEFINITION));
		if (definitionRef != null) {
			Asset defAsset = engine.resolveAsset(definitionRef, ctx);
			// resolveAsset is intentionally operation-oriented for local catalog
			// paths. An agent definition is a non-operation functional asset, so
			// accept its literal metadata through the general path/CAS resolver.
			if (defAsset == null) {
				ACell definitionValue = engine.resolvePath(definitionRef, ctx);
				if (definitionValue instanceof AMap<?,?> dm) {
					@SuppressWarnings("unchecked")
					AMap<AString, ACell> definitionMeta = (AMap<AString, ACell>) dm;
					defAsset = Asset.fromMeta(definitionMeta);
				}
			}
			if (defAsset == null) { job.fail("Definition asset not found: " + definitionRef); return; }

			AMap<AString, ACell> defMeta = defAsset.meta();

			// Agent definitions and templates use the same functional asset facet.
			// Resolve its config through the ordinary ordered-layer machinery so a
			// definition may itself compose reusable config assets.
			AMap<AString, ACell> defConfig;
			try {
				defConfig = resolveConfigValue(defMeta, ctx, new ArrayList<>(), 0,
					"definition '" + definitionRef + "'");
			} catch (IllegalArgumentException e) {
				job.fail("Invalid agent definition " + definitionRef + ": " + e.getMessage());
				return;
			}

			// Definition provides defaults; explicit params override. Everything
			// goes into record.config — the single canonical config slot (#144).
			if (defConfig != null) {
				config = (config == null) ? defConfig : mergeConfigMaps(defConfig, config);
			}

			// Store resolved asset ID in config for provenance (full DID URL)
			if (config != null) {
				// The asset lives in the user's /a/, so the DID URL must name the
				// user — an agent-scoped URL would not resolve.
				AString defID = ctx.getUserDID().append("/a/" + defAsset.getID().toHexString());
				config = config.assoc(Fields.DEFINITION, defID);
			}
		}

		// Config assets may embed initial state. Explicit operation input wins,
		// but the construction-only field is never retained in runtime config.
		// This runs after the compatibility definition layer has been composed.
		if (config != null && config.containsKey(AgentState.KEY_STATE)) {
			ACell embeddedState = config.get(AgentState.KEY_STATE);
			if (initialState == null) initialState = embeddedState;
			config = config.dissoc(AgentState.KEY_STATE);
		}

		// Config has exactly one home: record.config, written by the principal;
		// state is written by the runtime (#144). A config map smuggled inside
		// state would be silently inert — reject loudly. Likewise loads (#142):
		// they live on the context scope chain (config.loads / session loads),
		// never in agent-level state.
		if (RT.getIn(initialState, AgentState.KEY_CONFIG) != null) {
			job.fail("state.config is not supported — pass agent configuration via the 'config' parameter");
			return;
		}
		if (RT.getIn(initialState, Fields.LOADS) != null) {
			job.fail("state.loads is not supported — loads are per-session (config.loads for operator pins, #142)");
			return;
		}

		// Apply sensible defaults for LLM agents — record.config is the single
		// config slot, read by all runtimes at transition time.
		if (config == null) config = Maps.empty();
		if (!config.containsKey(Fields.OPERATION)) {
			config = config.assoc(Fields.OPERATION, engine.config().getDefaultTransitionOp());
		}
		// systemPrompt present implies an LLM agent — ensure llmOperation is set
		if (config.containsKey(K_SYSTEM_PROMPT) && !config.containsKey(K_LLM_OPERATION)) {
			config = config.assoc(K_LLM_OPERATION, engine.config().getDefaultLlmOperation());
		}
		try {
			validateComposedConfig(config);
		} catch (IllegalArgumentException e) {
			job.fail(describeFailure(e));
			return;
		}

		AgentState agent = user.ensureAgent(agentId, config, initialState);

		AMap<AString, ACell> result = Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.STATUS, agent.getStatus());

		// Advisory only: surface anything that looks misconfigured but doesn't
		// warrant failing create (#205). Emitted as a vector so several checks can
		// contribute; omitted entirely when clean.
		AVector<ACell> warnings = collectCreateWarnings(config, ctx);
		if (!warnings.isEmpty()) {
			result = result.assoc(Fields.WARNINGS, warnings);
			for (long i = 0; i < warnings.count(); i++) {
				log.info("agent:create {} — {}", agentId, warnings.get(i));
			}
		}

		job.setStatus(Status.STARTED);
		job.completeWith(result);
	}

	/**
	 * Collects non-fatal create-time advisories for a resolved config. Each entry
	 * is a human-readable message string; the vector is empty when nothing is
	 * flagged. New sanity checks append here — the result field ({@code warnings})
	 * is already a list, so adding one is additive.
	 */
	private AVector<ACell> collectCreateWarnings(AMap<AString, ACell> config, RequestContext ctx) {
		AVector<ACell> warnings = Vectors.empty();
		AString toolWarn = toolCapabilityWarning(config, ctx);
		if (toolWarn != null) warnings = warnings.conj(toolWarn);
		AString unavailableWarn = unavailableToolsWarning(config, ctx);
		if (unavailableWarn != null) warnings = warnings.conj(unavailableWarn);
		AString skillsWarn = skillSourcesWarning(config, ctx);
		if (skillsWarn != null) warnings = warnings.conj(skillsWarn);
		AString keyWarn = rawApiKeyWarning(config);
		if (keyWarn != null) warnings = warnings.conj(keyWarn);
		return warnings;
	}

	/**
	 * Advisory for {@code config.skills} (see venue/docs/SKILLS.md). Two cases:
	 * a malformed shape (non-array, non-string entry) — which will THROW at
	 * transition time, so it's flagged here at the moment it's fixable — and
	 * sources that resolve to nothing right now. The latter is only a warning:
	 * sources resolve live each turn, so a source created later simply starts
	 * appearing in the skills index — no recreate needed.
	 */
	private AString skillSourcesWarning(AMap<AString, ACell> config, RequestContext ctx) {
		if (config == null) return null;
		ACell raw = config.get(Strings.intern("skills"));
		if (raw == null) return null;
		AVector<ACell> sources;
		try {
			sources = ContextBuilder.skillSources(raw);
		} catch (RuntimeException e) {
			return Strings.create(describeFailure(e)
				+ " (the agent will fail at transition time until this is fixed)");
		}
		java.util.List<String> unresolved = new java.util.ArrayList<>();
		for (long i = 0; i < sources.count(); i++) {
			AString source = RT.ensureString(sources.get(i));
			try {
				if (engine.resolvePath(source, ctx) == null) unresolved.add(source.toString());
			} catch (RuntimeException e) {
				// Transient resolution errors aren't config errors — don't over-warn.
			}
		}
		if (unresolved.isEmpty()) return null;
		return Strings.create("agent declares skills source(s) that resolve to nothing right now: "
			+ String.join(", ", unresolved) + ". Sources resolve live each turn, so a source"
			+ " created later starts appearing in the skills index automatically — this is"
			+ " only a warning.");
	}

	/**
	 * Advisory for a raw credential in agent config. Job records redact
	 * {@code apiKey} inputs, but an agent CONFIG persists on the lattice
	 * verbatim — a raw key in {@code config.apiKey} is durably stored
	 * unredacted and visible to anything that can read the agent record.
	 * The supported pattern is the secret store: store the key via
	 * {@code v/ops/secret/set}, then reference it as {@code s/<name>}
	 * (resolved at invocation time, never persisted in the record). Warning
	 * only — inlined keys on a throwaway dev venue are legitimate.
	 */
	static AString rawApiKeyWarning(AMap<AString, ACell> config) {
		if (config == null) return null;
		AString apiKey = RT.ensureString(config.get(Strings.intern("apiKey")));
		if (apiKey == null) return null;
		String v = apiKey.toString();
		if (v.startsWith("s/") || v.startsWith("/s/")) return null; // secret reference
		return Strings.intern("config.apiKey holds a raw credential — agent config"
			+ " persists unredacted on the lattice. Store the key with the"
			+ " v/ops/secret/set operation and reference it as s/<name> instead;"
			+ " secret references are resolved at invocation time and never"
			+ " persisted in the agent record.");
	}

	/**
	 * Advisory for configured operation tools whose metadata is missing,
	 * unreachable, or unreadable under the agent's own {@code config.caps}
	 * (#317). Resolution is live every turn, so fixing the path, remote venue, or
	 * read grant makes the tool available without recreating the agent. Harness
	 * pseudo-tools ({@code subgoal}, {@code complete}, …) are not operations and
	 * are never flagged.
	 */
	private AString unavailableToolsWarning(AMap<AString, ACell> config, RequestContext ctx) {
		if (config == null) return null;
		AVector<ACell> unavailable = ContextBuilder.unavailableConfigTools(
			engine, ctx, config, AbstractLLMAdapter.allHarnessToolNames());
		if (unavailable.isEmpty()) return null;
		java.util.List<String> details = new java.util.ArrayList<>();
		for (long i = 0; i < unavailable.count(); i++) {
			ACell entry = unavailable.get(i);
			details.add(RT.getIn(entry, Fields.OPERATION) + " ("
				+ RT.getIn(entry, Fields.REASON) + ")");
		}
		return Strings.create("agent declares unavailable tool operation(s): "
			+ String.join(", ", details) + ". Tools must be resolvable operation paths"
			+ " (e.g. 'v/ops/covia/read'), not adapter shorthand (e.g. 'covia:read')."
			+ " Private/user-scoped definitions also require metadata read authority in"
			+ " addition to invoke authority. Until each resolves it is left out of the"
			+ " agent's toolset; install it, fix the path, or grant metadata read access"
			+ " and it becomes available on the next cycle — this is only a warning.");
	}

	/**
	 * Best-effort tool-capability advisory for a freshly-configured agent (#205).
	 * When the agent declares {@code tools} on an Ollama-backed model, probes the
	 * model's advertised capabilities and returns a warning when tool-calling
	 * can't be confirmed — either the model reports no {@code tools} capability,
	 * or it couldn't be reached/verified (not installed yet). Warning only:
	 * create never fails on this, since the model can be switched or installed
	 * later. Returns {@code null} when there's nothing to warn about — no tools
	 * declared, a non-Ollama provider (whose tool support isn't discoverable in
	 * advance, so silence beats a guess), or a confirmed tool-capable model.
	 */
	private AString toolCapabilityWarning(AMap<AString, ACell> config, RequestContext ctx) {
		if (config == null) return null;
		AVector<ACell> tools = RT.ensureVector(config.get(Strings.intern("tools")));
		if (tools == null || tools.isEmpty()) return null;   // no tools → nothing to check

		// Only Ollama exposes model capabilities; skip other providers entirely.
		AString llmOp = RT.ensureString(config.get(K_LLM_OPERATION));
		if (llmOp == null) return null;
		Asset opAsset;
		try { opAsset = engine.resolveAsset(llmOp, ctx); }
		catch (RuntimeException e) { return null; }
		if (opAsset == null) return null;
		String adapterOp = getAdapterOperation(opAsset.meta());   // e.g. "langchain:ollama"
		if (adapterOp == null || !adapterOp.startsWith("langchain:ollama")) return null;

		AString modelCell = RT.ensureString(config.get(Strings.intern("model")));
		String model = (modelCell != null) ? modelCell.toString() : "qwen";  // langchain Ollama default
		AString urlCell = RT.ensureString(config.get(Strings.intern("url")));
		String baseUrl = (urlCell != null) ? urlCell.toString() : "http://localhost:11434";

		return toolWarningFor(model, baseUrl,
			LangChainAdapter.ollamaModelCapabilities(baseUrl, model));
	}

	/**
	 * Pure decision half of {@link #toolCapabilityWarning} — given a model, its
	 * base URL, and the capabilities the probe resolved ({@code null} = couldn't
	 * determine), returns the advisory string or {@code null} for "no warning".
	 * Split out so the wording is unit-testable without a live Ollama server.
	 */
	static AString toolWarningFor(String model, String baseUrl, java.util.List<String> caps) {
		if (caps == null) {
			return Strings.create("agent declares tools but its Ollama model '" + model
				+ "' could not be confirmed to support tool-calling at " + baseUrl
				+ " (it may not be installed yet). Install or switch to a tool-capable model"
				+ " such as qwen2.5 before running — this is only a warning.");
		}
		if (!caps.contains("tools")) {
			return Strings.create("agent declares tools but its Ollama model '" + model
				+ "' does not advertise tool-calling (capabilities: " + caps
				+ ") — the model will likely ignore tool calls. Use a tool-capable model"
				+ " such as qwen2.5, or switch the model later — this is only a warning.");
		}
		return null;
	}

	/** Stops an agent and waits for its run loop before its record may be reused. */
	private boolean haltAgent(Job job, User user, AgentState agent, AString agentId,
			RequestContext ctx, String pendingError, String action) {
		AString ownerDID = user.getDID();
		AgentKey key = new AgentKey(ownerDID, agentId);
		CompletableFuture<ACell> oldLoop = runningLoops.get(key);
		if (agentId.equals(ctx.getAgentId()) && oldLoop != null && !oldLoop.isDone()) {
			job.fail("Agent cannot " + action + " itself while RUNNING: " + agentId);
			return false;
		}

		failAllPendingForAgent(ownerDID, agentId, pendingError);
		agent.setStatus(AgentState.TERMINATED);
		cancelActiveTransition(key);
		if (oldLoop != null && !oldLoop.isDone()) {
			try {
				awaitLoopExit(oldLoop, HALT_TIMEOUT_MS);
			} catch (TimeoutException e) {
				job.fail("Agent " + agentId + " did not stop within "
					+ HALT_TIMEOUT_MS + " ms");
				return false;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				job.fail("Interrupted while waiting for agent " + agentId + " to stop");
				return false;
			} catch (java.util.concurrent.CancellationException ignored) {
				// The requested terminal state is already set; only loop exit matters.
			}
		}
		return true;
	}

	/** Bounded wait seam kept package-visible for deterministic regression tests. */
	static void awaitLoopExit(CompletableFuture<?> loop, long timeoutMs)
			throws TimeoutException, InterruptedException {
		try {
			loop.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.ExecutionException ignored) {
			// Exceptional completion still means the run loop exited.
		}
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
		if (RT.getIn(input, Fields.OVERWRITE) != null) {
			job.fail("agent:fork no longer supports overwrite; delete the target agent "
				+ "with remove=true, then fork it again");
			return;
		}

		Users users = engine.getVenueState().users();
		User user = users.ensure(ctx.getUserDID());

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
			job.fail(describeFailure(e)); return;
		}
		AMap<AString, ACell> sourceConfig = source.getConfig();
		AMap<AString, ACell> forkConfig = (overrideConfig == null) ? sourceConfig
			: (sourceConfig != null ? mergeConfigMaps(sourceConfig, overrideConfig) : overrideConfig);
		try {
			validateComposedConfig(forkConfig);
		} catch (IllegalArgumentException e) {
			job.fail(describeFailure(e));
			return;
		}

		ACell sourceState = source.getState();
		AVector<ACell> sourceTimeline = CVMBool.TRUE.equals(RT.getIn(input, K_INCLUDE_TIMELINE))
			? source.getTimeline() : null;

		// Fork is an exclusive create: replacement is delete(remove=true) + fork.
		AgentState existing = user.agent(agentId);
		if (existing != null && existing.exists()) {
			job.fail("Target agent already exists: " + agentId
				+ "; delete it with remove=true before forking into this name");
			return;
		}

		AgentState target = user.forkAgent(agentId, forkConfig, sourceState, sourceTimeline);

		AMap<AString, ACell> result = Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.STATUS, target.getStatus(),
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
		ACell outputPathCell = RT.getIn(input, Fields.OUTPUT_PATH);
		AString outputPath = RT.ensureString(outputPathCell);
		if (outputPathCell != null
				&& (outputPath == null || outputPath.toString().trim().isEmpty())) {
			job.fail("outputPath must be a non-empty string");
			return;
		}

		AgentState agent = lookupAgent(job, ctx.getUserDID(), agentId);
		if (agent == null) return;
		if (failIfSuspended(job, agent, agentId)) return;

		// Mint or reuse a session for this request. Stage 1 scaffold — the
		// sid is recorded on the task row and returned in the response
		// envelope, but the transition function does not yet consume it.
		Blob sid = resolveOrMintSession(job, agent, input, ctx.getCallerDID());
		if (sid == null) return;

		// Build canonical taskdata map: {input, caller, created, sessionId, responseSchema?}
		// Task rows are transient — status/result/error live on the Job record
		// and in the agent timeline's taskResults snapshot. Per-task t/ scratch
		// lives on that same Job's persistent temp field, not this queue row.
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
			Fields.SESSION_ID, Strings.create(sid.toHexString()));
		if (responseSchema instanceof AMap) {
			taskData = taskData.assoc(Fields.RESPONSE_SCHEMA, responseSchema);
		}
		if (outputPath != null) {
			taskData = taskData
				.assoc(Fields.OUTPUT_PATH, outputPath)
				.assoc(K_OUTPUT_CONTEXT, snapshotOutputContext(ctx));
			outputContexts.put(taskId, ctx);
		}
		agent.addTask(taskId, taskData);

		// Record the session on the task Job so consumers can recover it for the
		// task's whole lifecycle (the A2A layer maps A2A contextId = session).
		// Job.completeWith preserves existing fields, so this survives completion.
		job.updateData(job.getData().assoc(Fields.SESSION_ID, Strings.create(sid.toHexString())));

		// Each accepted request guarantees a processing attempt, so bypass the
		// optional-work launch gate.
		wakeAgent(ctx.getUserDID(), agentId, true);
	}

	private void handleMessage(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		AgentState agent = lookupAgent(job, ctx.getUserDID(), agentId);
		if (agent == null) return;

		ACell messageContent = RT.getIn(input, Fields.MESSAGE);
		if (messageContent == null) { job.fail("message is required"); return; }

		AString taskIdHex = RT.ensureString(RT.getIn(input, Fields.TASK_ID));
		Blob taskId = null;
		Blob sid;
		if (taskIdHex != null) {
			taskId = Blob.fromHex(taskIdHex.toString());
			if (taskId == null) { job.fail("Invalid taskId format: " + taskIdHex); return; }
			ACell taskData = agent.getTasks().get(taskId);
			AString taskSid = (taskData instanceof AMap<?, ?> map)
				? RT.ensureString(map.get(Fields.SESSION_ID)) : null;
			sid = (taskSid != null) ? Blob.fromHex(taskSid.toString()) : null;
			if (sid == null) { job.fail("Task not found: " + taskIdHex); return; }
			AString suppliedSid = RT.ensureString(RT.getIn(input, Fields.SESSION_ID));
			if (suppliedSid != null && !taskSid.equals(suppliedSid)) {
				job.fail("contextId does not match task session");
				return;
			}
		} else {
			sid = resolveOrMintSession(job, agent, input, ctx.getCallerDID());
			if (sid == null) return;
		}

		AString sidHex = Strings.create(sid.toHexString());
		// Wrap message with caller provenance and the session it belongs to
		AMap<AString, ACell> envelope = Maps.of(
			Fields.CALLER,     ctx.getCallerDID(),
			Fields.SESSION_ID, sidHex,
			Fields.MESSAGE,    messageContent);
		if (taskId != null) {
			envelope = envelope.assoc(Fields.TASK_ID, taskIdHex);
			AString messageId = RT.ensureString(RT.getIn(messageContent, Fields.MESSAGE_ID));
			if (!agent.appendTaskContinuation(taskId, sid, envelope, messageId)) {
				job.fail("Task not found or no longer accepts messages: " + taskIdHex);
				return;
			}
		} else {
			agent.appendSessionPending(sid, envelope);
		}
		wakeAgent(ctx.getUserDID(), agentId, true);

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(
			Fields.AGENT_ID,   agentId,
			Fields.SESSION_ID, sidHex,
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

		AgentState agent = lookupAgent(job, ctx.getUserDID(), agentId);
		if (agent == null) return;
		if (failIfSuspended(job, agent, agentId)) return;

		Blob sid = resolveSessionForChat(job, agent, input, ctx.getCallerDID());
		if (sid == null) return;
		AString sidHex = Strings.create(sid.toHexString());

		// Reserve the per-session chat slot (in-memory). Fails fast if a
		// live chat is already in flight — but a previous caller whose Job
		// has since finished (completed or cancelled) no longer holds the
		// slot. Register a cancel hook so the caller cancelling their own
		// Job immediately frees the slot for a retry.
		ConcurrentHashMap<Blob, Job> agentChats = activeChats
			.computeIfAbsent(new AgentKey(ctx.getUserDID(), agentId), k -> new ConcurrentHashMap<>());
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

		// The envelope carries the chat Job id for provenance — turns minted
		// from it keep the id, tying conversation content back to the job.
		AString jobIdHex = Strings.create(job.getID().toHexString());
		ACell envelope = Maps.of(
			Fields.CALLER,     ctx.getCallerDID(),
			Fields.SESSION_ID, sidHex,
			Fields.MESSAGE,    messageContent,
			Fields.JOB_ID,     jobIdHex);
		agent.appendSessionPending(sid, envelope);

		// Record the (possibly just-minted) sessionId on the job so a caller
		// finding this job failed after a venue restart knows which session to
		// re-engage (recovery never re-executes intake, #214). Mirrors
		// handleRequest.
		job.updateData(job.getData().assoc(Fields.SESSION_ID, sidHex));

		// Each accepted chat guarantees a processing attempt, so bypass the
		// optional-work launch gate.
		wakeAgent(ctx.getUserDID(), agentId, true);
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

		AgentState agent = lookupAgent(job, ctx.getUserDID(), agentId);
		if (agent == null) return;
		if (failIfSuspended(job, agent, agentId)) return;

		// Trigger never creates a session. A supplied sessionId is resolved
		// and echoed back; if none is supplied the trigger runs unsessioned.
		Blob sid = null;
		ACell sidCell = RT.getIn(input, Fields.SESSION_ID);
		if (sidCell != null) {
			AString s = RT.ensureString(sidCell);
			if (s == null) { job.fail("sessionId must be a hex string"); return; }
			sid = Blob.fromHex(s.toString());
			if (sid == null) { job.fail("Invalid sessionId format: " + s); return; }
		}
		AString sidHex = (sid != null) ? Strings.create(sid.toHexString()) : null;

		boolean force = parseForce(input);
		CompletableFuture<ACell> completion = wakeAgent(ctx.getUserDID(), agentId, force);
		if (completion == null) {
			// force=true (the default) keeps the historical "must start" contract.
			if (force) {
				AString transitionOp = resolveTransitionOp(ctx.getUserDID(), agentId);
				if (transitionOp == null) {
					job.fail("Cannot start agent '" + agentId
						+ "': config.operation is missing or invalid; fix it with agent:update");
				} else {
					job.fail("Cannot start agent '" + agentId + "': status is " + agent.getStatus()
						+ "; inspect with agent:info");
				}
				return;
			}
			// force=false: an idle agent with no work is not an error — return a snapshot.
			AMap<AString, ACell> snap = Maps.of(
				Fields.AGENT_ID, agentId,
				Fields.STATUS, observableStatus(ctx.getUserDID(), agentId, agent));
			job.completeWith((sidHex != null) ? snap.assoc(Fields.SESSION_ID, sidHex) : snap);
			return;
		}

		// Default wait=true: block until the loop drains all work and the
		// completion future resolves. This is a blocking wait on the run
		// loop, NOT a result-await — the caller gets a status snapshot,
		// not agent output. For output, wait on the task/chat Job returned
		// by agent:request / agent:chat.
		long waitMs = parseWaitMs(input);
		if (waitMs == 0 && RT.getIn(input, Fields.WAIT) == null) waitMs = -1;

		AMap<AString, ACell> running = Maps.of(
			Fields.AGENT_ID, agentId, Fields.STATUS, AgentState.RUNNING);
		if (sidHex != null) running = running.assoc(Fields.SESSION_ID, sidHex);
		final AString fSidHex = sidHex;
		awaitRunCompletion(job, completion, waitMs, running,
			result -> (fSidHex != null) ? annotateWithSession(result, fSidHex) : result);
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
		User user = users.get(ctx.getUserDID());
		if (user == null) { job.fail("User not found: " + ctx.getUserDID()); return; }

		AgentState agent = user.agent(agentId);
		if (agent == null || agent.getRecord() == null) { job.fail("Agent not found: " + agentId); return; }

		job.setStatus(Status.STARTED);
		job.completeWith(buildAgentSummary(agentId, agent, ctx));
	}

	/**
	 * Job-free read: the caller's agent summary (the {@code agent:info} payload),
	 * or {@code null} if the caller has no such agent. Shared by {@code agent:info}
	 * and the job-free {@code GET /api/v1/agents/{id}} route (#180).
	 */
	public AMap<AString, ACell> agentInfo(RequestContext ctx, AString agentId) {
		User user = engine.getVenueState().users().get(ctx.getUserDID());
		if (user == null) return null;
		AgentState agent = user.agent(agentId);
		if (agent == null || agent.getRecord() == null) return null;
		return buildAgentSummary(agentId, agent, ctx);
	}

	/**
	 * Builds the {@code agent:info} summary from a resolved agent — a lightweight
	 * view; full state, history, and timeline are read via
	 * {@code covia:read path=g/<agentId>/state} etc.
	 */
	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> buildAgentSummary(
			AString agentId, AgentState agent, RequestContext ctx) {
		AMap<AString, ACell> record = agent.getRecord();
		AVector<?> timeline = agent.getTimeline();
		Index<Blob, ACell> tasks = agent.getTasks();

		AMap<AString, ACell> summary = Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.STATUS, observableStatus(ctx.getUserDID(), agentId, agent),
			Fields.CONFIG, record.get(AgentState.KEY_CONFIG));

		if (timeline != null) summary = summary.assoc(Strings.intern("timelineLength"), CVMLong.create(timeline.count()));
		if (tasks != null) summary = summary.assoc(Strings.intern("tasks"), CVMLong.create(tasks.count()));
		ACell error = record.get(AgentState.KEY_ERROR);
		if (error != null) summary = summary.assoc(AgentState.KEY_ERROR, error);
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> config = (record.get(AgentState.KEY_CONFIG) instanceof AMap<?, ?> m)
			? (AMap<AString, ACell>) m : null;
		AVector<ACell> unavailable = ContextBuilder.unavailableConfigTools(
			engine, ctx, config, AbstractLLMAdapter.allHarnessToolNames());
		if (!unavailable.isEmpty()) {
			summary = summary.assoc(Fields.UNAVAILABLE_TOOLS, unavailable);
		}
		return summary;
	}

	/**
	 * Dispatches {@code agent:context} to the configured transition adapter.
	 *
	 * <p>Looks up the agent's transition operation, resolves its adapter, and
	 * if the adapter implements {@link ContextInspectable}, asks it to render
	 * its context as JSON. Adapters that do not declare context inspection
	 * support cause the call to fail with a clear message — AgentAdapter
	 * holds no opinion on what a context "looks like".</p>
	 */
	@SuppressWarnings("unchecked")
	private void handleContext(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		Users users = engine.getVenueState().users();
		User user = users.get(ctx.getUserDID());
		if (user == null) { job.fail("User not found"); return; }
		AgentState agent = user.agent(agentId);
		if (agent == null) { job.fail("Agent not found: " + agentId); return; }

		AMap<AString, ACell> record = agent.getRecord();
		AMap<AString, ACell> recordConfig = (record.get(AgentState.KEY_CONFIG) instanceof AMap m)
			? (AMap<AString, ACell>) m : null;
		ACell state = record.get(AgentState.KEY_STATE);

		AString operation = (recordConfig != null)
			? RT.ensureString(recordConfig.get(Fields.OPERATION))
			: null;
		if (operation == null) {
			job.fail("Agent has no transition operation configured");
			return;
		}

		// Resolve the adapter that handles the agent's transition operation.
		covia.grid.Asset asset = engine.resolveAsset(operation, ctx);
		if (asset == null) {
			job.fail("Could not resolve agent's operation: " + operation);
			return;
		}
		AString adapterRef = RT.ensureString(RT.getIn(asset.meta(), Fields.OPERATION, Fields.ADAPTER));
		if (adapterRef == null) {
			job.fail("Agent's operation has no adapter: " + operation);
			return;
		}
		String adapterName = adapterRef.toString();
		int colon = adapterName.indexOf(':');
		if (colon >= 0) adapterName = adapterName.substring(0, colon);
		AAdapter target = engine.getAdapter(adapterName);
		if (!(target instanceof ContextInspectable inspectable)) {
			job.fail("Adapter '" + adapterName + "' does not support context inspection");
			return;
		}

		// Optional session scope (#211): with a sessionId the rendered context
		// includes that session's conversation — prior turns and tool-failure
		// diagnostics — exactly as a live transition would see it. Without one
		// the render is the synthetic fresh-transition context, as before.
		AMap<AString, ACell> session = null;
		AString sidHex = RT.ensureString(RT.getIn(input, Fields.SESSION_ID));
		if (sidHex != null) {
			Blob sid = Blob.fromHex(sidHex.toString());
			if (sid == null) { job.fail("Invalid sessionId format: " + sidHex); return; }
			session = agent.getSession(sid);
			if (session == null) { job.fail("Unknown session: " + sidHex); return; }
		}

		ACell taskInput = RT.getIn(input, Strings.intern("task"));
		// Inspection must resolve n/ paths and capability-scoped loads exactly as
		// the live transition does. The caller identity remains the owner; the
		// agent id selects the same private namespace/cursor view as execution.
		AString rendered = inspectable.inspectContext(
			recordConfig, state, taskInput, session, ctx.withAgentId(agentId));

		// Session token totals (#217): measured usage accumulated on
		// meta.tokens, appended so an inspector sees real counts instead of
		// estimating from characters. Output stays a rendered string —
		// structured reads go job-free via values API on the session record.
		ACell sessionTokens = RT.getIn(session, Strings.intern("meta"), Fields.TOKENS);
		if (sessionTokens != null) {
			rendered = Strings.create(rendered + "\n[Session token usage (measured): "
				+ convex.core.util.JSON.print(sessionTokens) + "]");
		}

		job.setStatus(Status.STARTED);
		job.completeWith(rendered);
	}

	private void handleList(Job job, ACell input, RequestContext ctx) {
		boolean includeTerminated = CVMBool.TRUE.equals(RT.getIn(input, Fields.INCLUDE_TERMINATED));
		job.setStatus(Status.STARTED);
		job.completeWith(listAgents(ctx, includeTerminated, true));
	}

	/**
	 * Job-free read: the caller's agents. Shared by {@code agent:list} and the
	 * job-free {@code GET /api/v1/agents} route (#180). {@code annotated} controls
	 * the entry shape: {@code true} → the status-annotated summary maps
	 * {@code agent:list} returns ({@code {agentId, status, tasks, error?}});
	 * {@code false} → bare agent ids.
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> listAgents(RequestContext ctx, boolean includeTerminated, boolean annotated) {
		User user = engine.getVenueState().users().get(ctx.getUserDID());

		AVector<ACell> agents = Vectors.empty();
		if (user != null) {
			AMap<AString, ACell> agentMap = user.getAgents();
			if (agentMap != null) {
				for (var entry : agentMap.entrySet()) {
					AString agentId = entry.getKey();
					ACell value = entry.getValue();
					if (!(value instanceof AMap)) continue;
					AMap<AString, ACell> record = (AMap<AString, ACell>) value;

					AString stableStatus = RT.ensureString(record.get(AgentState.KEY_STATUS));
					if (!includeTerminated && AgentState.TERMINATED.equals(stableStatus)) continue;

					if (!annotated) {
						agents = agents.conj(agentId);
						continue;
					}

					long taskCount = 0;
					ACell tasksCell = record.get(AgentState.KEY_TASKS);
					if (tasksCell instanceof Index) taskCount = ((Index<?, ?>) tasksCell).count();

					AString status = observableStatus(ctx.getUserDID(), agentId,
						user.agent(agentId));
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

		return Maps.of(Strings.intern("agents"), agents);
	}

	private void handleDelete(Job job, ACell input, RequestContext ctx) {
		DeleteRequest request;
		try {
			request = parseDeleteRequest(input);
		} catch (IllegalArgumentException e) {
			job.fail(e.getMessage());
			return;
		}
		boolean remove = CVMBool.TRUE.equals(RT.getIn(input, Fields.REMOVE));
		Users users = engine.getVenueState().users();
		User user = users.get(ctx.getUserDID());
		if (user == null) {
			job.fail("No agents found for caller " + ctx.getUserDID());
			return;
		}

		// Validate the full exact-ID set before mutating anything. Runtime races
		// can still change a slot after this preflight, but ordinary bad input
		// (including one missing ID in a batch) never causes a partial delete.
		for (long i = 0; i < request.agentIds().count(); i++) {
			AString agentId = request.agentIds().get(i);
			AgentState agent = user.agent(agentId);
			if (agent == null || !agent.exists()) {
				job.fail("Agent not found: " + agentId + " (no agents were deleted)");
				return;
			}
			CompletableFuture<ACell> loop = runningLoops.get(
				new AgentKey(ctx.getUserDID(), agentId));
			if (agentId.equals(ctx.getAgentId()) && loop != null && !loop.isDone()) {
				job.fail("Agent cannot delete itself while RUNNING: " + agentId
					+ " (no agents were deleted; schedule deletion for after the current run)");
				return;
			}
		}

		AVector<ACell> results = Vectors.empty();
		for (long i = 0; i < request.agentIds().count(); i++) {
			AString agentId = request.agentIds().get(i);
			AMap<AString, ACell> result = deleteOneAgent(job, user, agentId, ctx, remove);
			if (result == null) return;
			results = results.conj(result);
		}

		job.setStatus(Status.STARTED);
		if (request.batch()) {
			job.completeWith(Maps.of(
				K_AGENTS, results,
				Fields.TOTAL, CVMLong.create(results.count())));
		} else {
			job.completeWith(results.get(0));
		}
	}

	private static final int MAX_DELETE_BATCH = 100;

	private record DeleteRequest(AVector<AString> agentIds, boolean batch) {}

	/** Parse the backward-compatible single-id form or the exact-id batch form. */
	@SuppressWarnings("unchecked")
	private static DeleteRequest parseDeleteRequest(ACell input) {
		ACell singleCell = RT.getIn(input, Fields.AGENT_ID);
		ACell batchCell = RT.getIn(input, K_AGENT_IDS);
		if (singleCell != null && batchCell != null) {
			throw new IllegalArgumentException("Specify exactly one of agentId or agentIds");
		}
		if (singleCell != null) {
			if (!(singleCell instanceof AString agentId)) {
				throw new IllegalArgumentException("agentId must be a string");
			}
			return new DeleteRequest(Vectors.of(agentId), false);
		}
		if (!(batchCell instanceof AVector<?> raw)) {
			throw new IllegalArgumentException("Specify agentId or a non-empty agentIds array");
		}
		if (raw.count() == 0) {
			throw new IllegalArgumentException("agentIds must not be empty");
		}
		if (raw.count() > MAX_DELETE_BATCH) {
			throw new IllegalArgumentException("agentIds supports at most " + MAX_DELETE_BATCH + " agents per call");
		}
		AVector<AString> ids = Vectors.empty();
		HashSet<String> seen = new HashSet<>();
		for (long i = 0; i < raw.count(); i++) {
			if (!(raw.get(i) instanceof AString id)) {
				throw new IllegalArgumentException("agentIds[" + i + "] must be a string");
			}
			if (!seen.add(id.toString())) {
				throw new IllegalArgumentException("agentIds contains duplicate: " + id);
			}
			ids = ids.conj(id);
		}
		return new DeleteRequest(ids, true);
	}

	/** Shared single-agent deletion semantics used by both wire shapes. */
	private AMap<AString, ACell> deleteOneAgent(Job job, User user, AString agentId,
			RequestContext ctx, boolean remove) {
		AgentState agent = user.agent(agentId);
		if (agent == null || !agent.exists()) {
			job.fail("Agent disappeared during deletion: " + agentId);
			return null;
		}

		// Logical deletion is idempotent. Physical deletion also accepts an
		// already-TERMINATED record, allowing a later cleanup pass to make its
		// old lattice values unreachable and eligible for Etch collection.
		if (!AgentState.TERMINATED.equals(agent.getStatus())) {
			if (!haltAgent(job, user, agent, agentId, ctx,
					"Agent deleted: " + agentId, "delete")) return null;
		}
		if (remove) {
			user.removeAgent(agentId);
			return Maps.of(Fields.AGENT_ID, agentId, Fields.REMOVED, CVMBool.TRUE);
		}
		return Maps.of(Fields.AGENT_ID, agentId, Fields.STATUS, AgentState.TERMINATED);
	}

	private void handleSuspend(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		AgentState agent = lookupAgent(job, ctx.getUserDID(), agentId);
		if (agent == null) return;

		agent.setStatus(AgentState.SUSPENDED);

		// Cancel any active transition so the agent stops promptly (the token
		// stops the transition thread itself; cancel unblocks the run loop)
		cancelActiveTransition(new AgentKey(ctx.getUserDID(), agentId));

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(Fields.AGENT_ID, agentId, Fields.STATUS, AgentState.SUSPENDED));
	}

	private void handleResume(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		AgentState agent = lookupAgent(job, ctx.getUserDID(), agentId);
		if (agent == null) return;

		// Default autoWake to true
		ACell autoWakeCell = RT.getIn(input, Fields.AUTO_WAKE);
		boolean autoWake = !(CVMBool.FALSE.equals(autoWakeCell));

		// Atomic CAS: SUSPENDED → SLEEPING, clear error
		if (!agent.tryResume()) {
			job.fail("Cannot resume agent '" + agentId + "': status is " + agent.getStatus()
				+ "; agent:resume requires SUSPENDED");
			return;
		}

		if (autoWake) wakeAgent(ctx.getUserDID(), agentId, false);

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(Fields.AGENT_ID, agentId, Fields.STATUS, AgentState.SLEEPING));
	}

	@SuppressWarnings("unchecked")
	private void handleUpdate(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		AgentState agent = lookupAgent(job, ctx.getUserDID(), agentId);
		if (agent == null) return;
		if (isRunning(new AgentKey(ctx.getUserDID(), agentId))) {
			// A running transition has already captured its config (including caps)
			// for the duration of its tool loop, so mutating the record mid-run
			// would not affect it and is refused by design. To revoke authority or
			// otherwise reconfigure a running agent, HALT it first: agent:suspend
			// cancels the in-flight transition promptly (no further tool call
			// runs), the update then applies to the stopped agent, and agent:resume
			// restarts it under the new config. This is the kill-switch pattern —
			// the caller halts, then updates.
			job.fail("Cannot update agent " + agentId + ": currently RUNNING. "
				+ "Suspend it first (agent:suspend) to halt the run, then update and resume; "
				+ "or delete it with remove=true before creating a replacement.");
			return;
		}

		ACell configInput = RT.getIn(input, Fields.CONFIG);
		AMap<AString, ACell> newConfig = null;
		if (configInput != null) {
			try {
				AMap<AString, ACell> resolved = parseConfigArg(configInput, ctx);
				newConfig = mergeConfigMaps(agent.getConfig(), resolved);
			} catch (IllegalArgumentException e) {
				job.fail(describeFailure(e));
				return;
			}
		}
		ACell newState = RT.getIn(input, AgentState.KEY_STATE);
		if (newConfig == null && newState == null) {
			job.fail("At least one of 'config' or 'state' must be provided");
			return;
		}
		if (newConfig != null) {
			try {
				validateComposedConfig(newConfig);
			} catch (IllegalArgumentException e) {
				job.fail(describeFailure(e));
				return;
			}
		}
		// Config's single home is record.config (#144), and loads live on the
		// context scope chain (#142) — see handleCreate.
		if (RT.getIn(newState, AgentState.KEY_CONFIG) != null) {
			job.fail("state.config is not supported — pass agent configuration via the 'config' parameter");
			return;
		}
		if (RT.getIn(newState, Fields.LOADS) != null) {
			job.fail("state.loads is not supported — loads are per-session (config.loads for operator pins, #142)");
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

		AgentState agent = lookupAgent(job, ctx.getUserDID(), agentId);
		if (agent == null) return;

		// Parse hex task ID to Blob
		Blob taskId;
		try {
			taskId = Blob.fromHex(taskIdHex.toString());
		} catch (Exception e) {
			job.fail("Invalid taskId format: " + taskIdHex);
			return;
		}

		// Claim atomically so cancel cannot race completion or another cancel.
		if (agent.takeTask(taskId) == null) {
			job.fail("Task not found: " + taskIdHex);
			return;
		}
		outputContexts.remove(taskId);
		Job pending = engine.jobs().getJob(taskId);
		if (pending != null && !pending.isFinished()) pending.cancel();

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.TASK_ID, taskIdHex,
			Fields.CANCELLED, CVMBool.TRUE));
	}

	/** Adapter config key: set {@code {"adapters": {"agent": {"sessionDelete":
	 *  false}}}} in venue config to disable {@code agent:deleteSession}. */
	private static final AString CONFIG_SESSION_DELETE = Strings.intern("sessionDelete");

	/** Whether agent:deleteSession is enabled on this venue (default true). */
	private boolean isSessionDeleteEnabled() {
		ACell v = engine.config().getAdapterConfig(getName()).get(CONFIG_SESSION_DELETE);
		return (v == null) || RT.bool(v);
	}

	/**
	 * agent:deleteSession — deletes a session on an agent, removing the
	 * session record (pending, frames/history, meta). Lets a user hold a
	 * private conversation and delete it afterwards.
	 *
	 * <p>Job records are NOT touched: they belong to their callers, who hold
	 * the Job IDs from intake and can delete them via the jobs API — or avoid
	 * persisting conversation content in the first place (see #192, private
	 * job variants).</p>
	 *
	 * <p>Enabled by default; operators disable via venue config
	 * {@code {"adapters": {"agent": {"sessionDelete": false}}}}.</p>
	 *
	 * <p>An in-flight {@code agent:chat} on the session is failed
	 * ("Session deleted") and its slot cleared, so a blocked caller gets a
	 * clean error.</p>
	 */
	private void handleDeleteSession(Job job, ACell input, RequestContext ctx) {
		if (!isSessionDeleteEnabled()) {
			job.fail("agent:deleteSession is disabled on this venue");
			return;
		}

		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		AString sidHex = RT.ensureString(RT.getIn(input, Fields.SESSION_ID));
		if (sidHex == null) { job.fail("sessionId is required"); return; }

		AgentState agent = lookupAgent(job, ctx.getUserDID(), agentId);
		if (agent == null) return;

		Blob sid;
		try {
			sid = Blob.fromHex(sidHex.toString());
		} catch (Exception e) {
			sid = null;
		}
		if (sid == null) { job.fail("Invalid sessionId format: " + sidHex); return; }

		if (agent.getSession(sid) == null) {
			job.fail("Session not found: " + sidHex);
			return;
		}

		// Fail any in-flight chat awaiting on this session so its caller
		// unblocks with a clean error (scoped analogue of failAllPendingForAgent)
		ConcurrentHashMap<Blob, Job> agentChats =
			activeChats.get(new AgentKey(ctx.getUserDID(), agentId));
		if (agentChats != null) {
			Job chatJob = agentChats.remove(sid);
			if (chatJob != null && !chatJob.isFinished()) {
				chatJob.fail("Session deleted");
			}
		}

		agent.removeSession(sid);

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(
			Fields.AGENT_ID,   agentId,
			Fields.SESSION_ID, sidHex,
			Fields.DELETED,    CVMBool.TRUE));
	}

	/**
	 * Sets or clears a session's free-form {@code title} (docs/AGENT_SESSIONS.md
	 * §4.3 — the field the "suggested" session meta shape always documented
	 * but the framework never implemented). An absent or blank {@code title}
	 * clears it back to unset — callers wanting the auto-derived
	 * first-message label (client-side) just don't set one.
	 */
	private void handleRenameSession(Job job, ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) { job.fail("agentId is required"); return; }

		AString sidHex = RT.ensureString(RT.getIn(input, Fields.SESSION_ID));
		if (sidHex == null) { job.fail("sessionId is required"); return; }

		AgentState agent = lookupAgent(job, ctx.getUserDID(), agentId);
		if (agent == null) return;

		Blob sid;
		try {
			sid = Blob.fromHex(sidHex.toString());
		} catch (Exception e) {
			sid = null;
		}
		if (sid == null) { job.fail("Invalid sessionId format: " + sidHex); return; }

		ACell titleValue = RT.getIn(input, Fields.TITLE);
		if (titleValue != null && !(titleValue instanceof AString)) {
			job.fail("title must be a string");
			return;
		}
		AString title = (AString) titleValue;
		if (title != null && title.toString().isBlank()) title = null;

		if (!agent.setSessionTitle(sid, title)) {
			job.fail("Session not found: " + sidHex);
			return;
		}

		job.setStatus(Status.STARTED);
		AMap<AString, ACell> result = Maps.of(
			Fields.AGENT_ID,   agentId,
			Fields.SESSION_ID, sidHex);
		if (title != null) result = result.assoc(Fields.TITLE, title);
		job.completeWith(result);
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
			job.fail(describeFailure(e));
		}
	}

	private ACell doCompleteTask(ACell input, RequestContext ctx) {
		AString agentId = ctx.getAgentId();
		Blob taskId = ctx.getTaskId();
		if (agentId == null || taskId == null) {
			throw new IllegalArgumentException(
				"agent:completeTask requires task scope (agentId + taskId in RequestContext)");
		}

		AgentState agent = requireAgent(ctx.getUserDID(), agentId);
		long expectedRevision = ctx.getTaskRevision();
		ACell task = (expectedRevision >= 0)
			? agent.takeTask(taskId, expectedRevision) : agent.takeTask(taskId);
		if (task == null) {
			ACell current = agent.getTasks().get(taskId);
			if (current != null && expectedRevision >= 0
					&& (AgentState.taskRevision(current) != expectedRevision
						|| AgentState.hasUnpresentedTaskInputs(current))) {
				return Maps.of(
					Fields.AGENT_ID, agentId,
					Fields.TASK_ID, taskIdHex(taskId),
					Fields.STATUS, Status.STARTED,
					Fields.REASON, Strings.create("Task received continuation input; process the updated task before completing"));
			}
			throw new IllegalArgumentException("Task not found: " + taskId.toHexString());
		}

		ACell result = RT.getIn(input, Fields.RESULT);
		parkCompletion(ctx.getUserDID(), agentId, task, taskId, Status.COMPLETE, Fields.OUTPUT, result);

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
			job.fail(describeFailure(e));
		}
	}

	private ACell doFailTask(ACell input, RequestContext ctx) {
		AString agentId = ctx.getAgentId();
		Blob taskId = ctx.getTaskId();
		if (agentId == null || taskId == null) {
			throw new IllegalArgumentException(
				"agent:failTask requires task scope (agentId + taskId in RequestContext)");
		}

		AgentState agent = requireAgent(ctx.getUserDID(), agentId);
		long expectedRevision = ctx.getTaskRevision();
		ACell task = (expectedRevision >= 0)
			? agent.takeTask(taskId, expectedRevision) : agent.takeTask(taskId);
		if (task == null) {
			ACell current = agent.getTasks().get(taskId);
			if (current != null && expectedRevision >= 0
					&& (AgentState.taskRevision(current) != expectedRevision
						|| AgentState.hasUnpresentedTaskInputs(current))) {
					return Maps.of(
						Fields.AGENT_ID, agentId,
						Fields.TASK_ID, taskIdHex(taskId),
						Fields.STATUS, Status.STARTED,
						Fields.REASON, Strings.create("Task received continuation input; process the updated task before failing"));
			}
			throw new IllegalArgumentException("Task not found: " + taskId.toHexString());
		}

		ACell errorCell = RT.getIn(input, Fields.ERROR);
		AString errorStr = (errorCell == null) ? Strings.create("Task failed") : Strings.create(errorCell.toString());
		parkCompletion(ctx.getUserDID(), agentId, task, taskId, Status.FAILED, Fields.ERROR, errorStr);

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
	private void parkCompletion(AString ownerDID, AString agentId, ACell task, Blob taskId,
			AString status, AString valueField, ACell value) {
		AMap<AString, ACell> envelope = Maps.of(
			Fields.ID,     taskId,
			Fields.STATUS, status,
			valueField,    value);
		ACell sid = extractTaskSessionId(task);
		if (sid != null) envelope = envelope.assoc(Fields.SESSION_ID, sid);
		if (task instanceof AMap<?, ?> taskMap) {
			ACell outputPath = taskMap.get(Fields.OUTPUT_PATH);
			if (outputPath != null) envelope = envelope.assoc(Fields.OUTPUT_PATH, outputPath);
			ACell outputContext = taskMap.get(K_OUTPUT_CONTEXT);
			if (outputContext != null) envelope = envelope.assoc(K_OUTPUT_CONTEXT, outputContext);
		}
		deferredCompletions
			.computeIfAbsent(new AgentKey(ownerDID, agentId), k -> new ConcurrentHashMap<>())
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
			Blob taskId = e.getKey();
			Job pendingJob = engine.jobs().getJob(taskId);
			if (pendingJob == null || pendingJob.isFinished()) {
				outputContexts.remove(taskId);
				continue;
			}
			AMap<AString, ACell> envelope = e.getValue();
			AString status = RT.ensureString(envelope.get(Fields.STATUS));
			if (Status.FAILED.equals(status)) {
				outputContexts.remove(taskId);
				ACell err = envelope.get(Fields.ERROR);
				pendingJob.fail(err == null ? "Task failed" : err.toString());
			} else {
				AString outputPath = RT.ensureString(envelope.get(Fields.OUTPUT_PATH));
				if (outputPath == null) {
					// Compatibility contract: without outputPath the exact
					// historical envelope (including the full output) survives.
					pendingJob.completeWith(envelope);
					continue;
				}

				RequestContext outputCtx = outputContexts.remove(taskId);
				if (outputCtx == null) {
					outputCtx = restoreOutputContext(envelope.get(K_OUTPUT_CONTEXT));
				}
				if (outputCtx == null) {
					pendingJob.fail("Output handoff failed for '" + outputPath
						+ "': requester authority is unavailable");
					continue;
				}

				ACell output = envelope.get(Fields.OUTPUT);
				CompletableFuture<ACell> write;
				try {
					CoviaAdapter covia = (CoviaAdapter) engine.getAdapter("covia");
					if (covia == null) throw new IllegalStateException("covia adapter is unavailable");
					write = covia.writeResolvedPath(outputCtx, outputPath, output);
				} catch (Exception ex) {
					write = CompletableFuture.failedFuture(ex);
				}
				write.whenComplete((ignored, failure) -> {
					// A cancellation that won before the handoff completed stays
					// terminal and never exposes a success receipt.
					if (pendingJob.isFinished()) return;
					if (failure != null) {
						Throwable cause = failure;
						while ((cause instanceof java.util.concurrent.CompletionException
								|| cause instanceof java.util.concurrent.ExecutionException)
								&& cause.getCause() != null) {
							cause = cause.getCause();
						}
						pendingJob.fail("Output handoff failed for '" + outputPath
							+ "': " + describeFailure(cause));
						return;
					}
					pendingJob.completeWith(buildOutputReceipt(envelope, outputPath, output));
				});
			}
		}
	}

	/** Captures identity, cap ceiling and execution scopes without bearer UCANs. */
	private static AMap<AString, ACell> snapshotOutputContext(RequestContext ctx) {
		AMap<AString, ACell> snapshot = Maps.of(Fields.CALLER, ctx.getCallerDID());
		if (ctx.getCaps() != null) snapshot = snapshot.assoc(K_CONTEXT_CAPS, ctx.getCaps());
		if (ctx.getAgentId() != null) snapshot = snapshot.assoc(K_CONTEXT_AGENT_ID, ctx.getAgentId());
		if (ctx.getJobId() != null) snapshot = snapshot.assoc(K_CONTEXT_JOB_ID, ctx.getJobId());
		if (ctx.getSessionId() != null) snapshot = snapshot.assoc(K_CONTEXT_SESSION_ID, ctx.getSessionId());
		if (ctx.getTaskId() != null) snapshot = snapshot.assoc(K_CONTEXT_TASK_ID, ctx.getTaskId());
		return snapshot;
	}

	/**
	 * Restores the non-secret portion of a captured context after restart.
	 * Proof-backed cross-user/federated authority deliberately cannot be
	 * reconstructed: raw bearer credentials are never persisted.
	 */
	@SuppressWarnings("unchecked")
	private static RequestContext restoreOutputContext(ACell snapshotCell) {
		if (!(snapshotCell instanceof AMap<?, ?> raw)) return null;
		AMap<AString, ACell> snapshot = (AMap<AString, ACell>) raw;
		AString caller = RT.ensureString(snapshot.get(Fields.CALLER));
		if (caller == null) return null;
		RequestContext ctx = RequestContext.of(caller);
		if (snapshot.containsKey(K_CONTEXT_CAPS)) {
			AVector<ACell> caps = RT.ensureVector(snapshot.get(K_CONTEXT_CAPS));
			if (caps == null) return null;
			ctx = ctx.withCaps(caps);
		}
		ACell agentId = snapshot.get(K_CONTEXT_AGENT_ID);
		if (agentId != null) ctx = ctx.withAgentId(RT.ensureString(agentId));
		ACell jobId = snapshot.get(K_CONTEXT_JOB_ID);
		if (jobId instanceof Blob b) ctx = ctx.withJobId(b);
		ACell sessionId = snapshot.get(K_CONTEXT_SESSION_ID);
		if (sessionId instanceof Blob b) ctx = ctx.withSessionId(b);
		ACell taskId = snapshot.get(K_CONTEXT_TASK_ID);
		if (taskId instanceof Blob b) ctx = ctx.withTaskId(b);
		return ctx;
	}

	/** Small completion envelope used only when outputPath was requested. */
	private static AMap<AString, ACell> buildOutputReceipt(
			AMap<AString, ACell> envelope, AString outputPath, ACell output) {
		AMap<AString, ACell> receipt = Maps.of(
			Fields.ID, envelope.get(Fields.ID),
			Fields.STATUS, Status.COMPLETE,
			Fields.OUTPUT_PATH, outputPath,
			Fields.BYTES, CVMLong.create(Cells.storageSize(output)));
		ACell sid = envelope.get(Fields.SESSION_ID);
		return (sid != null) ? receipt.assoc(Fields.SESSION_ID, sid) : receipt;
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
		// Make the blocking wait cancellable. A per-caller signal races against
		// the shared run-loop future: job.cancel() completes the signal, which
		// unblocks THIS wait without cancelling the loop — other waiters attached
		// to `completion` are unaffected, and we never touch `completion` itself.
		CompletableFuture<ACell> cancelSignal = new CompletableFuture<>();
		job.setCancelHook(() -> cancelSignal.complete(null));
		// A cancel may have landed between STARTED and hook registration.
		if (job.isFinished()) { job.setCancelHook(null); return; }
		CompletableFuture<ACell> raced = completion.applyToEither(cancelSignal, x -> x);
		try {
			ACell cycleResult = (waitMs < 0)
				? raced.join()
				: raced.get(waitMs, TimeUnit.MILLISECONDS);
			// A cancel during the wait already set CANCELLED — don't overwrite it
			// with a stale completion result.
			if (!job.isFinished()) job.completeWith(resultMapper.apply(cycleResult));
		} catch (Exception e) {
			// Bounded wait elapsed, or the loop future failed: RUNNING snapshot,
			// unless a cancel already finished the job.
			if (!job.isFinished()) job.completeWith(immediateResult);
		} finally {
			job.setCancelHook(null);
			// Detach `raced` from `completion` on the timeout path so a
			// never-draining loop doesn't accumulate dependents.
			cancelSignal.complete(null);
		}
	}

	/**
	 * Waits for the agent's in-flight run (if any) to reach a rest state
	 * (SLEEPING, SUSPENDED or TERMINATED) and returns that state.
	 *
	 * <p><b>Pure observer — it never wakes or starts the agent.</b> It reads the
	 * run loop's existing completion future from {@link #runningLoops} (the same
	 * future {@code agent:trigger} awaits): if a run is in flight it attaches and
	 * waits; otherwise the agent is already at rest and the current status is
	 * returned at once. No status polling, no thread to leak, and — unlike a
	 * {@code wakeAgent} call — no side effect on the shared engine. A run that
	 * ends via error (the loop suspends the agent and completes the future
	 * exceptionally) still counts as finished; only a run still RUNNING past
	 * {@code timeoutMs} times out.</p>
	 *
	 * <p>Intended for callers observing a run they already triggered; it does not
	 * wait for a run that has not started yet.</p>
	 *
	 * @return the rest state reached (SLEEPING / SUSPENDED / TERMINATED)
	 * @throws TimeoutException if the run is still RUNNING after {@code timeoutMs}
	 */
	AString awaitRunFinished(AString agentId, RequestContext ctx, long timeoutMs)
			throws TimeoutException {
		CompletableFuture<ACell> f = runningLoops.get(new AgentKey(ctx.getUserDID(), agentId)); // observe only — do NOT wake
		if (f != null && !f.isDone()) {
			try {
				f.get(timeoutMs, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				throw e; // genuinely still running — caller's bound exceeded
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (java.util.concurrent.ExecutionException e) {
				// Run finished via error: the loop suspended the agent and
				// completed the future exceptionally. That is still a finish.
			}
		}
		AgentState agent = getAgent(ctx.getUserDID(), agentId);
		return agent == null ? AgentState.TERMINATED : agent.getStatus();
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
	 * that arrive during a live attempt write to the lattice (session.pending,
	 * tasks) and are picked up by the running loop's {@code hasWork()} check
	 * at the top of the next iteration; they do not need to be signalled
	 * across threads. RUNNING is persisted for observers, while this map remains
	 * the authoritative concurrency primitive.</p>
	 *
	 * <p>This is a pure mechanism keyed on the agent's address
	 * ({@code ownerDID} + {@code agentId}): it runs the agent as its owner,
	 * regardless of who triggered the wake. Authorization (may this caller
	 * wake this agent?) and provenance (who sent the message) are the
	 * responsibility of the entry handler, which knows the request and
	 * records the caller before calling here. The waking caller's identity
	 * never reaches the run loop. (#91)</p>
	 *
	 * @param ownerDID the DID whose namespace the agent lives in — the identity
	 *              the run loop executes as
	 * @param force if true, skips the {@code hasWork} gate — used by
	 *              explicit triggers and scheduler fires that always want
	 *              to try running
	 */
	public CompletableFuture<ACell> wakeAgent(AString ownerDID, AString agentId, boolean force) {
		AgentState agent = getAgent(ownerDID, agentId);
		if (agent == null) return null;

		final AgentKey key = new AgentKey(ownerDID, agentId);

		// Fast path: live loop exists, attach to it. A done future in the
		// slot is treated as "no live loop" — the exiting loop removes it
		// in its finally block, but we may observe it briefly.
		CompletableFuture<ACell> existing = runningLoops.get(key);
		if (existing != null && !existing.isDone()) return existing;

		AString status = agent.getStatus();

		// SLEEPING is normal. RUNNING without a live slot is a stale marker
		// (normally cleared by startup) and must not lock the agent forever.
		if (!AgentState.SLEEPING.equals(status)
				&& !AgentState.RUNNING.equals(status)) return null;

		// Gate: only start if there's work (or forced).
		if (!force && !hasWork(agent)) return null;

		AString transitionOp = resolveTransitionOp(ownerDID, agentId);
		if (transitionOp == null) return null;

		// Atomic CAS: install our completion only if no other loop is live.
		// compute's function runs atomically under CHM's bucket lock, so the
		// check+install is a single operation — no lost-launch race with
		// concurrent wakeAgent calls.
		CompletableFuture<ACell> mine = new CompletableFuture<>();
		CompletableFuture<ACell> installed = runningLoops.compute(key, (k, cur) -> {
			if (cur != null && !cur.isDone()) return cur;
			return mine;
		});
		if (installed != mine) {
			// Someone else won the launch race — use their future.
			return installed;
		}

		// The run loop executes as the AGENT, within its OWNER's namespace — a
		// fresh context carrying none of the waking caller's proofs, caps, job or
		// session. Which caller won the launch CAS above must never leak into
		// the agent's execution: every namespace-scoped access during the run
		// (secrets /s/, workspace w/, job ownership, per-user cursors) resolves
		// in the owner's namespace, deterministically (#91), while the caller
		// identity is the agent's own sub-principal DID so what the agent did is
		// attributable to the agent rather than to the human who owns it.
		final RequestContext agentCtx = RequestContext.ofAgent(ownerDID, agentId);
		try {
			agent.setStatus(AgentState.RUNNING);
			final CompletableFuture<ACell> finalCompletion = mine;
			Thread.ofVirtual().start(
				() -> executeRunLoop(agentId, ownerDID, agentCtx, finalCompletion));
		} catch (RuntimeException | Error t) {
			// Do not leave an authoritative live slot behind when launch itself
			// fails before the run loop can enter its own finally block.
			runningLoops.remove(key, mine);
			agent.sleep();
			mine.completeExceptionally(t);
			throw t;
		}
		return mine;
	}

	/** Overload: non-forced wake (used by message delivery / resume auto-wake). */
	CompletableFuture<ACell> wakeAgent(AString ownerDID, AString agentId) {
		return wakeAgent(ownerDID, agentId, false);
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
	private void executeRunLoop(AString agentId, AString ownerDID,
			RequestContext ctx, CompletableFuture<ACell> completion) {
		// ctx is the agent's OWN context (see wakeAgent) — never a waking
		// caller's. Throughout the run ownerDID == ctx.getUserDID(), while
		// ctx.getCallerDID() is the agent's own sub-principal DID.
		final AgentKey key = new AgentKey(ownerDID, agentId);
		ACell lastResult = null;
		int iteration = 0;
		AMap<AString, ACell> allTaskResults = Maps.empty();
		boolean firstIteration = true;
		// Stuck-task detection (#215): the oldest task is re-presented every
		// cycle until it resolves, so the same task id recurring for the whole
		// iteration budget means the agent burned every cycle without progress.
		Blob stuckTaskId = null;
		int stuckCount = 0;

		try {
			while (true) {
				if (++iteration > MAX_LOOP_ITERATIONS) {
					// The cap must be terminal for a stuck task, not a nap: the
					// task would survive the sleep, the post-exit re-check would
					// relaunch a fresh loop against it, and the wake/cap cycle
					// burns LLM spend forever while the caller's job pins
					// STARTED (#215). A cap hit on genuinely varied work (long
					// task queue, chat traffic) stays a benign sleep as before.
					if (stuckTaskId != null && stuckCount >= MAX_LOOP_ITERATIONS) {
						failStuckTask(ownerDID, agentId, stuckTaskId);
					}
					log.warn("Agent {} hit max loop iterations ({}), forcing sleep",
						agentId, MAX_LOOP_ITERATIONS);
					break;
				}

				AgentState agent = getAgent(ownerDID, agentId);
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

				// Re-resolve the transition op every cycle: config is read at
				// transition time, and the record under this agentId may have
				// been replaced entirely (delete + recreate, #202) while this
				// loop was blocked — an op captured at launch must not process
				// the replacement agent's work.
				AString transitionOp = resolveTransitionOp(ownerDID, agentId);
				if (transitionOp == null) break;

				long startTs = Utils.getCurrentTimestamp();

				// Pick at most one task per cycle (oldest by created timestamp).
				// Multi-task agents fan out across cycles.
				Map.Entry<Blob, ACell> pickedTask = pickOldestTask(tasks);
				if (pickedTask != null) {
					if (stuckTaskId != null && stuckTaskId.equals(pickedTask.getKey())) {
						stuckCount++;
					} else {
						stuckTaskId = pickedTask.getKey();
						stuckCount = 1;
					}
				} else {
					stuckTaskId = null;
					stuckCount = 0;
				}
				AVector<ACell> formattedTasks = formatPickedTask(pickedTask);
				AVector<ACell> resolvedPending = resolveJobIds(pending, Fields.OUTPUT);

				// Pick at most one session per cycle. Priority:
				//   1. picked task's sessionId (so the task's session controls)
				//   2. else the first session with non-empty pending
				//   3. else null (no session traffic)
				AString pickedSession = pickSessionForCycle(pickedTask, agent);
				Blob pickedSessionBlob = (pickedSession != null)
					? Blob.parse(pickedSession.toString()) : null;
				// Read the session record ONCE and derive the presented inbox
				// from it — a second lattice read would let a message land in
				// between, making the drain count, the adapter's view and the
				// timeline snapshot disagree about what was presented.
				Job pickedChatJob = null;
				AMap<AString, ACell> pickedSessionRecord = null;
				AVector<ACell> filteredInbox = Vectors.empty();
				if (pickedSessionBlob != null) {
					ConcurrentHashMap<Blob, Job> agentChats = activeChats.get(key);
					if (agentChats != null) {
						Job candidate = agentChats.get(pickedSessionBlob);
						if (candidate != null && !candidate.isFinished()) {
							pickedChatJob = candidate;
						}
					}
					pickedSessionRecord = agent.getSession(pickedSessionBlob);
					if (pickedSessionRecord != null
							&& pickedSessionRecord.get(AgentState.KEY_PENDING) instanceof AVector<?> pv) {
						filteredInbox = (AVector<ACell>) pv;
					}
				}
				long presentedSessionPendingCount = filteredInbox.count();

				// Per-cycle ctx: scope to the agent, picked task, and session so
				// path resolvers (n/, t/, c/) can address the right slot, and
				// agent:completeTask / agent:failTask can identify which
				// agent + task they're acting on.
				RequestContext cycleCtx = ctx.withAgentId(agentId);
				long pickedTaskRevision = -1L;
				ACell pickedTaskInput = null;
				if (pickedTask != null) {
					pickedTaskRevision = AgentState.taskRevision(pickedTask.getValue());
					pickedTaskInput = agent.claimTaskInput(
						pickedTask.getKey(), pickedTaskRevision);
					ACell currentTask = agent.getTasks().get(pickedTask.getKey());
					if (currentTask == null) continue;
					if (AgentState.taskRevision(currentTask) != pickedTaskRevision) {
						// Continuation won the claim race. Re-snapshot so the fresh
						// revision — and only its next unpresented input — is used.
						continue;
					}
					cycleCtx = cycleCtx.withTaskId(pickedTask.getKey(),
						pickedTaskRevision);
				}
				if (pickedSessionBlob != null) cycleCtx = cycleCtx.withSessionId(pickedSessionBlob);

				// PENDING means accepted and queued. STARTED begins only when this
				// live executor attempt actually picks the external request/chat.
				if (pickedTask != null) {
					Job taskJob = engine.jobs().getJob(pickedTask.getKey());
					// Tasks are also a public lattice primitive and need not have a
					// corresponding Job. Only a present terminal Job invalidates one.
					if (taskJob != null && taskJob.isFinished()) {
						agent.removeTask(pickedTask.getKey());
						continue;
					}
					if (taskJob != null && Status.PENDING.equals(taskJob.getStatus())) {
						taskJob.setStatus(Status.STARTED);
					}
				}
				if (pickedChatJob != null && Status.PENDING.equals(pickedChatJob.getStatus())) {
					pickedChatJob.setStatus(Status.STARTED);
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

				// Does the transition adapter own the session's frames + drain
				// itself (lattice-resident frames)? Resolved from the ADAPTER,
				// not the transition output — an errored/cancelled transition
				// produces no output, and gating on output shape would
				// double-drain pending and re-append turns the adapter already
				// wrote live.
				boolean framesOwned = isFramesOwningOp(transitionOp, ctx);

				// Invoke transition. Blocks on the vthread — cheap, and any
				// work the transition enqueues on the lattice (e.g. a nested
				// agent:chat that wakes this same agent) is naturally visible
				// to the next iteration via hasWork(). A cancelled or errored
				// future surfaces as an error-shaped transitionResult so the
				// merge path handles it normally. The cancellation token rides
				// the cycle ctx: cancel(true) does not stop the transition
				// thread, so long transitions poll the token to stop promptly.
				java.util.concurrent.atomic.AtomicBoolean cancelToken =
					new java.util.concurrent.atomic.AtomicBoolean(false);
				activeCancellations.put(key, cancelToken);
				CompletableFuture<ACell> transitionFuture =
					engine.jobs().invokeInternal(transitionOp, transitionInput,
						cycleCtx.withCancellation(cancelToken));
				activeTransitions.put(key, transitionFuture);

				// Close the suspend/delete race: if the record was suspended,
				// terminated or removed between the iteration-top status check
				// and the put above, the suspender/deleter read an empty
				// activeTransitions slot and could not cancel — re-check here
				// (after the put, so one side always sees the other's write)
				// and cancel locally rather than blocking on a doomed cycle.
				AgentState recheck = getAgent(ownerDID, agentId);
				if (shouldCancelRegisteredTransition(recheck)) {
					cancelToken.set(true);
					transitionFuture.cancel(true);
				}

				ACell transitionResult;
				boolean transitionCancelled = false;
				try {
					transitionResult = transitionFuture.join();
				} catch (java.util.concurrent.CancellationException ce) {
					transitionCancelled = true;
					transitionResult = Maps.of(Fields.ERROR,
						Strings.create("Transition cancelled"));
				} catch (java.util.concurrent.CompletionException e) {
					Throwable cause = (e.getCause() != null) ? e.getCause() : e;
					transitionResult = Maps.of(Fields.ERROR,
						Strings.create("Transition failed: " + describeFailure(cause)));
				} finally {
					activeTransitions.remove(key, transitionFuture);
					activeCancellations.remove(key, cancelToken);
				}

				// A cancelled transition is an administrative stop (agent:suspend
				// or agent:delete cancelled it), not an agent failure — it must
				// NOT flow into the merge + fail-fast path, which would stamp
				// SUSPENDED over a TERMINATED record or poison a freshly
				// recreated agent under the same id (#202). Settle per initiator
				// and exit; the finally-block re-check handles any new work.
				if (transitionCancelled) {
					AString statusNow = agent.getStatus();
					if (AgentState.SUSPENDED.equals(statusNow)) {
						// agent:suspend stopped this cycle — record the cause,
						// drop the queue and fail queued callers, exactly as the
						// error path settled a suspend-cancel before (#88).
						AString errStr = Strings.create("Transition cancelled");
						Index<Blob, ACell> tasksAtCancel = agent.getTasks();
						agent.suspendAndDrain(errStr);
						failQueuedTasks(tasksAtCancel, errStr.toString());
						failAllPendingForAgent(ownerDID, agentId, errStr.toString());
						// Settle the session's cycle claim: an administrative
						// stop is not a crash — the session must not register
						// as interrupted work (no auto-resume), and the claim
						// release fences any zombie writes from the stopped
						// transition thread.
						if (pickedSessionBlob != null) {
							agent.clearSessionCycle(pickedSessionBlob);
						}
					}
					// TERMINATED / removed / replaced: agent:delete has already
					// settled the record and notified the queued callers.
					break;
				}

				IterResult merged = mergeAndPostProcess(
					agent, agentId, ownerDID, transitionOp, framesOwned, transitionResult, pickedTask,
					pickedTaskInput, formattedTasks, pickedSession,
					pickedSessionBlob, pickedChatJob, filteredInbox,
					presentedSessionPendingCount, startTs, allTaskResults);
				lastResult = merged.lastResult();
				allTaskResults = merged.allTaskResults();
			}

			// Clean exit clears the persisted executor marker. The atomic sleep
			// preserves an administrative stop/terminal state set while running.
			AgentState agent = getAgent(ownerDID, agentId);
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
			String failure = describeFailure(e);
			suspendOnError(ownerDID, agentId, e);
			failAllPendingForAgent(ownerDID, agentId, failure);
			completion.completeExceptionally(new RuntimeException(failure, e));
		} finally {
			// Release the launcher slot, then re-check for wakes that may
			// have arrived while this loop was exiting. remove(key, value)
			// only clears the slot if it still holds OUR completion — a
			// concurrent wakeAgent that already took over won't be disturbed.
			runningLoops.remove(key, completion);
			AgentState agent = getAgent(ownerDID, agentId);
			if (agent != null && AgentState.SLEEPING.equals(agent.getStatus())
					&& hasWork(agent)) {
				wakeAgent(ownerDID, agentId, false);
			}
		}
	}

	/** Close the administrative-stop/transition-registration race. */
	static boolean shouldCancelRegisteredTransition(AgentState agent) {
		if (agent == null) return true;
		AString status = agent.getStatus();
		return AgentState.SUSPENDED.equals(status) || AgentState.TERMINATED.equals(status);
	}

	/**
	 * Merges a transition result into the agent record and handles all the
	 * post-transition bookkeeping: timeline entry, history turns, deferred
	 * task-completion envelopes, in-flight chat completion.
	 */
	@SuppressWarnings("unchecked")
	private IterResult mergeAndPostProcess(
			AgentState agent, AString agentId, AString callerDID,
			AString transitionOp, boolean framesOwned,
			ACell transitionResult, Map.Entry<Blob, ACell> pickedTask,
			ACell pickedTaskInput, AVector<ACell> formattedTasks,
			AString pickedSession, Blob pickedSessionBlob, Job pickedChatJob,
			AVector<ACell> filteredInbox, long presentedSessionPendingCount,
			long startTs, AMap<AString, ACell> allTaskResults) {
		final AgentKey key = new AgentKey(callerDID, agentId);
		long endTs = Utils.getCurrentTimestamp();
		ACell newState = RT.getIn(transitionResult, AgentState.KEY_STATE);
		ACell leanResponse = RT.getIn(transitionResult, Fields.RESPONSE);
		ACell leanError = RT.getIn(transitionResult, Fields.ERROR);
		// Adapter-owned frame stack: when the transition emits `frames`, the
		// adapter has recorded its own assistant/tool turns in the appropriate
		// frame conversations. Framework skips appending the assistant response
		// to frames[0] in that case (would duplicate). User turns from drained
		// pending still append at root (concurrent-intake path).
		ACell framesCell = RT.getIn(transitionResult, Fields.FRAMES);
		@SuppressWarnings("unchecked")
		AVector<ACell> adapterFrames = (framesCell instanceof AVector)
			? (AVector<ACell>) framesCell : null;

		// Peek the parked envelopes (don't remove yet) so an exception
		// before completeDeferredJobs leaves them visible to the outer
		// catch sweeper — the slot is only cleared after merge succeeds.
		ConcurrentHashMap<Blob, AMap<AString, ACell>> deferred =
			deferredCompletions.get(key);
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

		// Tool-failure diagnostics ([{name, error}], #211): persisted on the
		// timeline entry so a denied/failed tool call is queryable after the
		// cycle. The adapter's provider-shaped role:tool result in Fields.TURNS
		// is the single session record of the same failure (#290).
		AVector<ACell> toolFailures = RT.ensureVector(
			RT.getIn(transitionResult, Fields.TOOL_FAILURES));
		if (toolFailures != null && toolFailures.count() > 0) {
			timelineEntry = timelineEntry.assoc(Fields.TOOL_FAILURES, toolFailures);
		}
		// Adapter-emitted non-terminal turns (currently llmagent tool-call and
		// tool-result messages). These slot between framework-authored user input
		// and the final response; unlike Fields.FRAMES they do not transfer frame
		// ownership to the adapter.
		AVector<ACell> transitionTurns = RT.ensureVector(
			RT.getIn(transitionResult, Fields.TURNS));

		// Cycle token usage ({input, output, total}, #217): persisted on the
		// timeline entry; mergeRunResult mirrors it into the picked session's
		// meta.tokens running totals in the same CAS. Measured only — absent
		// means the LLM op reported nothing, never zero.
		AMap<AString, ACell> cycleTokens =
			(RT.getIn(transitionResult, Fields.TOKENS) instanceof AMap<?, ?> tm)
				? (AMap<AString, ACell>) tm : null;
		if (cycleTokens != null) {
			timelineEntry = timelineEntry.assoc(Fields.TOKENS, cycleTokens);
		}

		// Accumulate task results across iterations
		if (taskResults != null) {
			for (var entry : taskResults.entrySet()) {
				allTaskResults = allTaskResults.assoc(entry.getKey(), entry.getValue());
			}
		}

		// Build turns to append to frames[0].conversation (only when a session
		// was picked this cycle, the transition didn't error, and the adapter
		// did NOT emit its own frames stack). When adapterFrames is non-null
		// the adapter owns every conversation write — framework stays out.
		// Order: inbox messages → picked task input → assistant response.
		//
		// Per-turn caller attribution (#84): opt-in via config.recordCaller.
		// Off by default (single-party sessions: caller == owner, redundant);
		// on for multi-party sessions (e.g. A2A-exposed agents) where you need
		// who-sent-which, not just meta.parties' who's-present.
		boolean recordCaller = CVMBool.TRUE.equals(
			RT.getIn(agent.getConfig(), Strings.intern("recordCaller")));
		AVector<ACell> turnsToAppend = Vectors.empty();
		if (pickedSessionBlob != null && leanError == null && adapterFrames == null && !framesOwned) {
			if (filteredInbox != null) {
				for (long i = 0; i < filteredInbox.count(); i++) {
					ACell envelope = filteredInbox.get(i);
					ACell msgContent = RT.getIn(envelope, Fields.MESSAGE);
					if (msgContent != null) {
						AMap<AString, ACell> turn = Maps.of(
							AgentState.K_ROLE,    AgentState.ROLE_USER,
							AgentState.K_CONTENT, msgContent,
							AgentState.K_TURN_TS, CVMLong.create(startTs),
							AgentState.K_SOURCE,  AgentState.SOURCE_CHAT);
						// Preserve the originating Job id as durable provenance for
						// audit and result correlation.
						ACell envJobId = RT.getIn(envelope, Fields.JOB_ID);
						if (envJobId != null) turn = turn.assoc(Fields.JOB_ID, envJobId);
						turnsToAppend = turnsToAppend.conj(
							withCaller(turn, recordCaller, RT.getIn(envelope, Fields.CALLER)));
					}
				}
			}
			if (pickedTaskInput != null) {
				AMap<AString, ACell> turn = Maps.of(
					AgentState.K_ROLE,    AgentState.ROLE_USER,
					AgentState.K_CONTENT, pickedTaskInput,
					AgentState.K_TURN_TS, CVMLong.create(startTs),
					AgentState.K_SOURCE,  AgentState.SOURCE_REQUEST);
				ACell taskCaller = (pickedTask != null && pickedTask.getValue() instanceof AMap)
					? ((AMap<AString, ACell>) pickedTask.getValue()).get(Fields.CALLER) : null;
				turnsToAppend = turnsToAppend.conj(withCaller(turn, recordCaller, taskCaller));
			}
			if (transitionTurns != null) {
				for (long i = 0; i < transitionTurns.count(); i++) {
					AMap<AString, ACell> turn = normaliseTransitionTurn(
						transitionTurns.get(i), endTs);
					if (turn != null) turnsToAppend = turnsToAppend.conj(turn);
				}
			}
			if (leanResponse != null) {
				AMap<AString, ACell> assistantTurn = Maps.of(
					AgentState.K_ROLE,    AgentState.ROLE_ASSISTANT,
					AgentState.K_CONTENT, leanResponse,
					AgentState.K_TURN_TS, CVMLong.create(endTs),
					AgentState.K_SOURCE,  AgentState.SOURCE_TRANSITION);
				// Per-turn usage where known (#217): one assistant turn per
				// cycle on this path, so the cycle totals ARE its usage.
				// (Frames-owning adapters record per-call usage on each L3
				// message they append themselves.)
				if (cycleTokens != null) {
					assistantTurn = assistantTurn.assoc(Fields.TOKENS, cycleTokens);
				}
				turnsToAppend = turnsToAppend.conj(assistantTurn);
			}
		}

		// Session-tier loads (#142): the transition's final working set for the
		// picked session (tombstones included), written in the same CAS below.
		AMap<AString, ACell> sessionLoads =
			(RT.getIn(transitionResult, Fields.LOADS) instanceof AMap<?, ?> lm)
				? (AMap<AString, ACell>) lm : null;

		// Merge results atomically (timeline, state, task cleanup, history,
		// session pending drain, session loads). History append lands in the
		// same CAS as the timeline, so external readers never see a cycle
		// that wrote one but not the other.
		//
		// FramesOwning transitions (lattice-resident frames) already wrote
		// their frames live and drained the presented pending at cycle start
		// (beginSessionCycle) — the merge must not re-drain (it would drop
		// mid-transition arrivals) and must not rewrite frames (the live
		// stack is authoritative; a merge rewrite would also mask any missed
		// live-write in testing). The merge still clears the session's
		// inCycle claim, appends the timeline entry and removes completed tasks.
		AMap<AString, ACell> merged = agent.mergeRunResult(
			newState, taskResults,
			timelineEntry, pickedSessionBlob, turnsToAppend,
			framesOwned ? 0 : presentedSessionPendingCount,
			framesOwned ? null : adapterFrames,
			sessionLoads);

		// Per-thread scheduled wake (B8.8). Transition result may carry a
		// `wakeTime` (absolute wall-clock millis) requesting a future fire on
		// the picked thread. If present, install it via setThreadWakeTime
		// (lattice-first, then scheduler). If absent but the picked record
		// still carries a stale `wakeTime` from a just-consumed scheduler
		// fire, clear it so the scheduler rebuild on restart doesn't re-fire
		// a wake that's already been serviced. Lattice is authoritative; the
		// scheduler index is rebuilt from it on boot.
		Scheduler scheduler = engine.gridScheduler();
		if (scheduler != null && leanError == null) {
			Blob pickedThreadId = null;
			AgentState.ThreadKind pickedKind = null;
			AMap<AString, ACell> pickedRecord = null;
			if (pickedTask != null) {
				pickedThreadId = pickedTask.getKey();
				pickedKind = AgentState.ThreadKind.TASK;
				Index<Blob, ACell> currentTasks = agent.getTasks();
				ACell tRec = (currentTasks != null) ? currentTasks.get(pickedThreadId) : null;
				if (tRec instanceof AMap) pickedRecord = (AMap<AString, ACell>) tRec;
			} else if (pickedSessionBlob != null) {
				pickedThreadId = pickedSessionBlob;
				pickedKind = AgentState.ThreadKind.SESSION;
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

		// Cost attribution (#217): stamp the cycle's token usage onto the
		// caller-facing job records BEFORE completing them, so the tokens
		// field rides the same write-through persist as the completion.
		// Job records accrete (completeWith builds on getData()), so this
		// survives into the final persisted record.
		if (cycleTokens != null) {
			if (pickedTask != null) {
				attachTokens(engine.jobs().getJob(pickedTask.getKey()), cycleTokens);
			}
			attachTokens(pickedChatJob, cycleTokens);
		}

		// Now that the timeline + state are persisted, claim the parked
		// envelopes (atomic remove) and complete the caller's pending
		// task Jobs. Doing this AFTER the merge guarantees that an
		// awaitResult caller sees the completed cycle's writes.
		completeDeferredJobs(deferredCompletions.remove(key));

		// Complete any in-flight chat for the picked session. Same
		// post-merge ordering invariant as task completion.
		if (pickedChatJob != null && (leanError != null || leanResponse != null)) {
			ConcurrentHashMap<Blob, Job> agentChats = activeChats.get(key);
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

		// Fail-fast on transition error. Framework does not classify or retry —
		// the caller (operator, or whichever submitter is awaiting a queued
		// task) decides how to respond to a failure. Suspend the agent and
		// drop the task queue first so the lattice state is settled before
		// any caller's awaitResult unblocks; then fail every queued task's
		// caller Job. The run loop exits at the top of the next iteration
		// via the SUSPENDED check; the finally re-wake only fires on
		// SLEEPING, so the agent stays down until an operator resumes it.
		// See issue #88.
		if (leanError != null) {
			AString errStr = Strings.create(leanError.toString());
			Index<Blob, ACell> tasksAtError = agent.getTasks();
			agent.suspendAndDrain(errStr);
			failQueuedTasks(tasksAtError, errStr.toString());
			failAllPendingForAgent(callerDID, agentId, errStr.toString());
		}

		ACell lastResult = Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.STATUS, (leanError != null) ? AgentState.SUSPENDED
				: merged.get(AgentState.KEY_STATUS),
			Fields.RESULT, result,
			Fields.TASK_RESULTS, allTaskResults);

		return new IterResult(lastResult, allTaskResults);
	}

	/**
	 * Stamps a user turn with its sender's DID (#84) when {@code recordCaller}
	 * is on and a caller is known. Off by default and a no-op when the caller
	 * is absent, so single-party sessions carry no redundant per-turn DID.
	 */
	private static AMap<AString, ACell> withCaller(AMap<AString, ACell> turn,
			boolean recordCaller, ACell caller) {
		if (!recordCaller || caller == null) return turn;
		return turn.assoc(Fields.CALLER, caller);
	}

	/** Adds framework audit metadata to an adapter-emitted LLM/tool message
	 * while preserving provider-significant fields such as toolCalls, id, name,
	 * and structuredContent. Invalid/non-message entries are ignored. */
	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> normaliseTransitionTurn(ACell value, long ts) {
		if (!(value instanceof AMap<?, ?> raw)) return null;
		AMap<AString, ACell> turn = (AMap<AString, ACell>) raw;
		AString role = RT.ensureString(turn.get(AgentState.K_ROLE));
		if (role == null) return null;
		if (turn.get(AgentState.K_TURN_TS) == null) {
			turn = turn.assoc(AgentState.K_TURN_TS, CVMLong.create(ts));
		}
		if (turn.get(AgentState.K_SOURCE) == null) {
			turn = turn.assoc(AgentState.K_SOURCE,
				Strings.intern("tool").equals(role)
					? AgentState.SOURCE_TOOL : AgentState.SOURCE_TRANSITION);
		}
		return turn;
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
	 * Returns the full frame stack from the session map.
	 *
	 * <p>Reads {@code input.session.frames} — a vector of frame records. The
	 * first entry is the root frame; subsequent entries are pushed by
	 * {@code subgoal}. Each frame is {@code {description, conversation, …}}.
	 * See {@code venue/docs/GOAL_TREE.md}.</p>
	 *
	 * <p>Returns {@code null} if no session is in scope, or if the session
	 * has no frames. Adapters use the null sentinel to fall back to their
	 * own state-held transcript for unsessioned callers.</p>
	 *
	 * @param input the transition input map
	 * @return frame-stack vector, or null if no session/frames present
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> sessionFrames(ACell input) {
		ACell session = RT.getIn(input, Fields.SESSION);
		if (session == null) return null;
		ACell frames = RT.getIn(session, AgentState.KEY_FRAMES);
		if (!(frames instanceof AVector)) return null;
		AVector<ACell> fv = (AVector<ACell>) frames;
		return (fv.count() > 0) ? fv : null;
	}

	/**
	 * Reads the sessionId recorded on a claimed task row, or returns null if
	 * absent or malformed.
	 */
	@SuppressWarnings("unchecked")
	private static ACell extractTaskSessionId(ACell task) {
		if (!(task instanceof AMap)) return null;
		return ((AMap<AString, ACell>) task).get(Fields.SESSION_ID);
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
	 * Fails the caller Jobs for a set of queued tasks. Used by the fail-fast
	 * path on transition errors, where the task queue is drained before
	 * notification so callers see a settled (SUSPENDED) lattice state. Pass
	 * the tasks snapshot captured before the drain.
	 */
	/**
	 * Terminal settlement for a task that consumed an entire loop's iteration
	 * budget without resolving (#215): fail the caller's job with a structured
	 * error and remove the task, so the loop-cap sleep is an ending rather
	 * than a pause before the next full-budget burn. Younger queued tasks are
	 * untouched — the post-exit re-check gives them their own loop.
	 */
	private void failStuckTask(AString ownerDID, AString agentId, Blob taskId) {
		String err = "Agent exceeded " + MAX_LOOP_ITERATIONS
			+ " loop iterations without resolving the task"
			+ " — inspect the agent timeline/session for what the model did;"
			+ " re-submit if appropriate";
		log.warn("Agent {} stuck on task {} for the whole iteration budget — failing the task job",
			agentId, taskId);
		// Drain before notification (the settled-state rule, S2.7c-2): the
		// task leaves the agent's Index BEFORE the caller's job fails, so an
		// awaitResult caller never observes the stuck task still queued.
		AgentState agent = getAgent(ownerDID, agentId);
		if (agent != null) agent.removeTask(taskId);
		outputContexts.remove(taskId);
		Job pending = engine.jobs().getJob(taskId);
		if (pending != null && !pending.isFinished()) {
			pending.fail(err);
		}
	}

	/** Stamps token usage onto a job's record (#217) — best-effort: an
	 *  already-finished or racing job just keeps its record as-is. */
	private static void attachTokens(Job job, AMap<AString, ACell> tokens) {
		if (job == null || job.isFinished()) return;
		try {
			job.updateData(job.getData().assoc(Fields.TOKENS, tokens));
		} catch (Exception e) {
			log.debug("Could not attach token usage to job {}: {}", job.getID(), e.toString());
		}
	}

	private void failQueuedTasks(Index<Blob, ACell> tasks, String error) {
		if (tasks == null) return;
		for (var entry : tasks.entrySet()) {
			outputContexts.remove(entry.getKey());
			Job pending = engine.jobs().getJob(entry.getKey());
			if (pending != null && !pending.isFinished()) {
				pending.fail(error);
			}
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
		final AgentKey key = new AgentKey(callerDID, agentId);
		AgentState agent = getAgent(callerDID, agentId);
		if (agent != null) {
			failQueuedTasks(agent.getTasks(), error);
		}
		ConcurrentHashMap<Blob, Job> agentChats = activeChats.remove(key);
		if (agentChats != null) {
			for (Job chatJob : agentChats.values()) {
				if (chatJob != null && !chatJob.isFinished()) {
					chatJob.fail(error);
				}
			}
		}
		ConcurrentHashMap<Blob, AMap<AString, ACell>> deferred =
			deferredCompletions.remove(key);
		if (deferred != null) {
			for (var e : deferred.entrySet()) {
				outputContexts.remove(e.getKey());
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
			if (agent != null) agent.suspend(Strings.create(describeFailure(e)));
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
		if (agent == null) job.fail("Agent '" + agentId
			+ "' was not found or is terminated; use agent:list or agent:create");
		return agent;
	}

	/**
	 * Whether the transition op's adapter owns the session's frames and
	 * pending drain itself ({@link covia.adapter.agent.FramesOwning} —
	 * lattice-resident frames). Resolved from the adapter, per cycle, so the
	 * answer survives error/cancel paths where the transition emits no output.
	 */
	private boolean isFramesOwningOp(AString opRef, RequestContext ctx) {
		try {
			covia.grid.Asset asset = engine.resolveAsset(opRef, ctx);
			if (asset == null) return false;
			AString adapterRef = RT.ensureString(RT.getIn(asset.meta(), Fields.OPERATION, Fields.ADAPTER));
			if (adapterRef == null) return false;
			String name = adapterRef.toString();
			int colon = name.indexOf(':');
			if (colon >= 0) name = name.substring(0, colon);
			return engine.getAdapter(name) instanceof covia.adapter.agent.FramesOwning;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Boot scan: wakes every agent with durable queued work — pending session
	 * envelopes or queued tasks. A stale {@code inCycle} claim is abandoned
	 * executor state, not work and not a resume checkpoint. Called once after
	 * {@code recoverJobs()} stabilises job records.
	 *
	 * @return the number of agents woken
	 */
	public int wakeAgentsWithWork() {
		int woken = 0;
		int staleRuns = 0;
		AMap<AString, ACell> all = engine.getVenueState().users().getAll();
		if (all == null) return 0;
		for (var userEntry : all.entrySet()) {
			AString did = RT.ensureString(userEntry.getKey());
			if (did == null) continue;
			covia.venue.User user = engine.getVenueState().users().get(did);
			if (user == null) continue;
			AMap<AString, ACell> agents = user.getAgents();
			if (agents == null) continue;
			for (var agentEntry : agents.entrySet()) {
				AString agentId = agentEntry.getKey();
				AgentState agent = getAgent(did, agentId);
				if (agent == null) continue;   // terminated / missing
				AgentKey key = new AgentKey(did, agentId);
				// This method is a startup hook, but keep it safe if an embedding
				// invokes it after launch: never reconcile beneath a live executor.
				if (isRunning(key)) continue;
				// No executor survives venue startup. Clear the durable dirty
				// marker before deciding whether remaining queued work merits a
				// fresh attempt; recoverJobs has already failed interrupted Jobs.
				if (AgentState.RUNNING.equals(agent.getStatus())) {
					agent.sleep();
					staleRuns++;
				}
				reconcileAgentAfterRestart(user, agent);
				if (hasWork(agent)) {
					log.info("Waking agent {} — durable work found on boot", agentId);
					wakeAgent(did, agentId, true);
					woken++;
				}
			}
		}
		if (staleRuns > 0) {
			log.info("Agent startup recovery: cleared {} stale RUNNING marker(s)", staleRuns);
		}
		return woken;
	}

	/**
	 * Removes agent-owned intake whose external Job was made terminal by the
	 * generic Job recovery pass. Stable waiting Jobs and jobless lattice tasks
	 * remain queued. Session epochs are executor fences, so none survives
	 * process startup.
	 */
	@SuppressWarnings("unchecked")
	private void reconcileAgentAfterRestart(User user, AgentState agent) {
		Index<Blob, ACell> tasks = agent.getTasks();
		if (tasks != null) {
			for (var entry : tasks.entrySet()) {
				ACell rawJob = user.getJob(entry.getKey());
				if (rawJob instanceof AMap<?, ?> map
						&& Job.isFinished((AMap<AString, ACell>) map)) {
					agent.removeTask(entry.getKey());
				}
			}
		}

		Index<Blob, ACell> sessions = agent.getSessions();
		if (sessions == null) return;
		for (var entry : sessions.entrySet()) {
			Blob sid = entry.getKey();
			agent.clearSessionCycle(sid);
			AVector<ACell> pending = agent.getSessionPending(sid);
			if (pending == null) continue;
			for (long i = 0; i < pending.count(); i++) {
				AString jobIdHex = RT.ensureString(RT.getIn(pending.get(i), Fields.JOB_ID));
				Blob jobId = (jobIdHex != null) ? Blob.fromHex(jobIdHex.toString()) : null;
				if (jobId == null) continue; // fire-and-forget message, not a chat Job
				ACell rawJob = user.getJob(jobId);
				if (!(rawJob instanceof AMap<?, ?> map)
						|| Job.isFinished((AMap<AString, ACell>) map)) {
					agent.removeSessionPendingJob(sid, jobId);
				}
			}
		}
	}

	/** Flips the agent's active-transition cancellation token (if any) and
	 *  cancels the transition future — both are needed: cancel unblocks the
	 *  run loop's join, the token stops the still-running transition thread. */
	private void cancelActiveTransition(AgentKey key) {
		java.util.concurrent.atomic.AtomicBoolean token = activeCancellations.get(key);
		if (token != null) token.set(true);
		CompletableFuture<ACell> activeTransition = activeTransitions.get(key);
		if (activeTransition != null) activeTransition.cancel(true);
	}

	/**
	 * Fails the caller's Job if the agent is SUSPENDED, naming the suspension
	 * cause and the remedy. A suspended agent accepts no new work — without
	 * this gate a request/chat Job would sit STARTED indefinitely with no
	 * signal to the caller (#201). Note {@code agent:message} deliberately
	 * bypasses this: it completes immediately and its envelope queues on the
	 * session for processing after resume.
	 *
	 * @return true if the agent was suspended and the Job has been failed
	 */
	private boolean failIfSuspended(Job job, AgentState agent, AString agentId) {
		if (!AgentState.SUSPENDED.equals(agent.getStatus())) return false;
		AString error = agent.getError();
		StringBuilder sb = new StringBuilder("Agent '").append(agentId).append("' is suspended");
		if (error != null) sb.append(": ").append(conciseDetail(error, 512));
		sb.append("; fix the cause, then use agent:resume");
		job.fail(sb.toString());
		return true;
	}

	private AgentState requireAgent(AString callerDID, AString agentId) {
		AgentState agent = getAgent(callerDID, agentId);
		if (agent == null) throw new IllegalArgumentException("Agent '" + agentId
			+ "' was not found or is terminated; use agent:list or agent:create");
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
	 * Resolves agent configuration from one layer or an ordered vector of layers.
	 * A layer is an inline config map or any lattice reference resolving to one.
	 * Layers merge left-to-right: maps merge recursively, while every other value
	 * (including vectors and {@code null}) is replaced by the later layer.
	 *
	 * <p>A referenced asset may expose config canonically under
	 * {@code agent.config}; flat config maps remain valid for compatibility.</p>
	 */
	private AMap<AString, ACell> parseConfigArg(ACell configArg, RequestContext ctx) {
		if (configArg == null) return null;
		return resolveConfigValue(configArg, ctx, new ArrayList<>(), 0, "config");
	}

	/**
	 * Resolves a string reference to a config map via standard lattice path
	 * resolution. Accepts any resolvable form: venue paths
	 * ({@code v/agents/templates/manager}), workspace paths ({@code w/configs/my}),
	 * pinned operations ({@code o/my-config}), asset hashes, DID URLs, etc.
	 * Returns the resolved value if it's a map, or {@code null} otherwise.
	 */
	private AMap<AString, ACell> resolveConfigRef(AString ref, RequestContext ctx) {
		return resolveConfigReference(ref, ctx, new ArrayList<>(), 0, "default config");
	}

	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> resolveConfigValue(ACell value, RequestContext ctx,
			List<String> resolving, int depth, String location) {
		if (depth > MAX_CONFIG_LAYER_DEPTH) {
			throw new IllegalArgumentException(
				location + " exceeds the maximum config composition depth of "
					+ MAX_CONFIG_LAYER_DEPTH + "; simplify the layer/reference chain");
		}
		if (value == null) {
			throw new IllegalArgumentException(location + " is null; remove this layer. "
				+ "To clear an inherited field, set that field to null inside a config map");
		}

		if (value instanceof AVector<?> layers) {
			AMap<AString, ACell> merged = Maps.empty();
			for (long i = 0; i < layers.count(); i++) {
				AMap<AString, ACell> layer = resolveConfigValue(
					(ACell) layers.get(i), ctx, resolving, depth + 1,
					location + "[" + i + "]");
				merged = mergeConfigMaps(merged, layer);
			}
			return merged;
		}

		if (value instanceof AString ref) {
			String text = ref.toString().trim();
			// MCP transports sometimes stringify an inline map/vector.
			if (text.startsWith("{") || text.startsWith("[")) {
				ACell parsed;
				try {
					parsed = JSON.parse(text);
				} catch (RuntimeException e) {
					throw new IllegalArgumentException(location
						+ " looks like inline JSON but could not be parsed: "
						+ describeFailure(e));
				}
				return resolveConfigValue(parsed, ctx, resolving, depth + 1, location);
			}
			return resolveConfigReference(ref, ctx, resolving, depth + 1, location);
		}

		if (!(value instanceof AMap<?,?> rawMap)) {
			throw invalidConfigShape(location, value);
		}
		AMap<AString, ACell> map = (AMap<AString, ACell>) rawMap;

		// Canonical functional asset form: ordinary metadata at the top level,
		// with reusable agent construction data under the agent facet.
		ACell facetCell = map.get(K_AGENT_FACET);
		if (map.containsKey(K_AGENT_FACET)) {
			if (!(facetCell instanceof AMap<?,?> rawFacet)) {
				throw new IllegalArgumentException(location
					+ ".agent must be a map containing config and optionally operation/state; got "
					+ configValueType(facetCell));
			}
			AMap<AString, ACell> facet = (AMap<AString, ACell>) rawFacet;
			if (!facet.containsKey(Fields.CONFIG) && !facet.containsKey(Fields.OPERATION)
					&& !facet.containsKey(AgentState.KEY_STATE)) {
				throw new IllegalArgumentException(location
					+ ".agent must contain at least one of config, operation, or state");
			}
			AMap<AString, ACell> config = facet.containsKey(Fields.CONFIG)
				? resolveConfigValue(facet.get(Fields.CONFIG), ctx, resolving, depth + 1,
					location + ".agent.config")
				: Maps.empty();
			ACell operationCell = facet.get(Fields.OPERATION);
			if (operationCell != null && !(operationCell instanceof AString)) {
				throw new IllegalArgumentException(location
					+ ".agent.operation must be a string operation path; got "
					+ configValueType(operationCell));
			}
			AString operation = (AString) operationCell;
			if (operation != null && !config.containsKey(Fields.OPERATION)) {
				config = config.assoc(Fields.OPERATION, operation);
			}
			if (facet.containsKey(AgentState.KEY_STATE)) {
				ACell incomingState = facet.get(AgentState.KEY_STATE);
				ACell existingState = config.get(AgentState.KEY_STATE);
				if (existingState instanceof AMap<?,?> em && incomingState instanceof AMap<?,?> im) {
					config = config.assoc(AgentState.KEY_STATE,
						mergeConfigMaps((AMap<AString, ACell>) em, (AMap<AString, ACell>) im));
				} else {
					config = config.assoc(AgentState.KEY_STATE, incomingState);
				}
			}
			return config;
		}
		return map;
	}

	private AMap<AString, ACell> resolveConfigReference(AString ref, RequestContext ctx,
			List<String> resolving, int depth, String location) {
		String key = ref.toString();
		int cycleStart = resolving.indexOf(key);
		if (cycleStart >= 0) {
			List<String> cycle = new ArrayList<>(resolving.subList(cycleStart, resolving.size()));
			cycle.add(key);
			throw new IllegalArgumentException(location + " has a cyclic config reference: "
				+ String.join(" -> ", cycle));
		}
		resolving.add(key);
		try {
			ACell resolved;
			try {
				resolved = engine.resolvePath(ref, ctx);
			} catch (RuntimeException e) {
				throw new IllegalArgumentException(location + " could not resolve config reference '"
					+ key + "': " + describeFailure(e));
			}
			// Pure path resolution intentionally does not fetch remote DID assets.
			// Config assets are definitions, so a remote content-addressed or named
			// DID reference uses the standard verified asset-fetch path.
			if (resolved == null && ref.startsWith(Strings.intern("did:"))) {
				Asset remote;
				try {
					remote = engine.resolveAsset(ref, ctx);
				} catch (RuntimeException e) {
					throw new IllegalArgumentException(location + " could not fetch remote config asset '"
						+ key + "': " + describeFailure(e));
				}
				if (remote != null) resolved = remote.meta();
			}
			if (resolved == null) {
				throw new IllegalArgumentException(location + " references '" + key
					+ "', but it was not found; check the path and the caller's read capability. "
					+ "String config layers are references; put prompt text inside {systemPrompt: ...}");
			}
			return resolveConfigValue(resolved, ctx, resolving, depth + 1,
				location + " (from '" + key + "')");
		} finally {
			resolving.remove(resolving.size() - 1);
		}
	}

	private static IllegalArgumentException invalidConfigShape(String location, ACell value) {
		return new IllegalArgumentException(location
			+ " must be a config map or a string reference (for example "
			+ "'v/agents/templates/worker'); got " + configValueType(value));
	}

	private static String configValueType(ACell value) {
		if (value == null) return "null";
		if (value instanceof AMap<?,?>) return "map";
		if (value instanceof AVector<?>) return "array";
		if (value instanceof AString) return "string";
		return value.getClass().getSimpleName();
	}

	/**
	 * Validates the provider-neutral agent config surface after all layers have
	 * merged. Provider-specific keys remain intentionally open-ended, but common
	 * selector mistakes must fail at author time instead of being silently ignored
	 * on the agent's first turn.
	 */
	@SuppressWarnings("unchecked")
	private static void validateComposedConfig(AMap<AString, ACell> config) {
		if (config == null) return;
		requireConfigType(config, Fields.OPERATION, AString.class, "a string transition operation path");
		requireConfigType(config, K_LLM_OPERATION, AString.class, "a string LLM operation path");
		requireConfigType(config, K_SYSTEM_PROMPT, AString.class, "a string");
		requireConfigType(config, K_MODEL, AString.class, "a string model name");
		requireConfigType(config, K_API_KEY, AString.class, "a string secret reference");
		requireConfigType(config, K_DEFAULT_TOOLS, CVMBool.class, "a boolean");
		requireConfigType(config, K_CAPS, AVector.class, "an array of capability objects");
		requireConfigType(config, K_CONTEXT, AVector.class, "an array of context entries");
		requireConfigType(config, Fields.LOADS, AMap.class, "a map of path to load options");
		requireConfigType(config, K_OUTPUTS, AMap.class, "a map of output declarations");
		requireConfigType(config, K_PROVIDER_OPTIONS, AMap.class, "a map");

		ACell responseFormat = config.get(K_RESPONSE_FORMAT);
		if (responseFormat != null && !(responseFormat instanceof AString)
				&& !(responseFormat instanceof AMap<?,?>)) {
			throw new IllegalArgumentException("config.responseFormat must be a string or map; got "
				+ configValueType(responseFormat));
		}

		ACell toolsCell = config.get(K_TOOLS);
		if (toolsCell == null) return;
		if (!(toolsCell instanceof AVector<?> tools)) {
			throw new IllegalArgumentException("config.tools must be an array of string operation paths "
				+ "or {operation, name?, description?} maps; got " + configValueType(toolsCell));
		}
		for (long i = 0; i < tools.count(); i++) {
			ACell entry = (ACell) tools.get(i);
			if (entry instanceof AString) continue;
			if (!(entry instanceof AMap<?,?> rawTool)) {
				throw new IllegalArgumentException("config.tools[" + i + "] must be a string operation "
					+ "path/harness tool name or an {operation, name?, description?} map; got "
					+ configValueType(entry));
			}
			AMap<AString, ACell> tool = (AMap<AString, ACell>) rawTool;
			ACell operation = tool.get(Fields.OPERATION);
			if (!(operation instanceof AString)) {
				throw new IllegalArgumentException("config.tools[" + i
					+ "].operation is required and must be a string operation path; got "
					+ configValueType(operation));
			}
			requireToolText(tool, Strings.intern("name"), i);
			requireToolText(tool, Strings.intern("description"), i);
		}
	}

	private static void requireConfigType(AMap<AString, ACell> config, AString key,
			Class<?> expected, String expectedDescription) {
		ACell value = config.get(key);
		// Null is the merge model's explicit "clear inherited optional value".
		if (value == null || expected.isInstance(value)) return;
		throw new IllegalArgumentException("config." + key + " must be "
			+ expectedDescription + "; got " + configValueType(value));
	}

	private static void requireToolText(AMap<AString, ACell> tool, AString key, long index) {
		ACell value = tool.get(key);
		if (value != null && !(value instanceof AString)) {
			throw new IllegalArgumentException("config.tools[" + index + "]." + key
				+ " must be a string when present; got " + configValueType(value));
		}
	}

	/** Recursive later-wins merge used only for ordered config composition. */
	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> mergeConfigMaps(
			AMap<AString, ACell> earlier, AMap<AString, ACell> later) {
		if (earlier == null || earlier.isEmpty()) return (later != null) ? later : Maps.empty();
		if (later == null || later.isEmpty()) return earlier;
		AMap<AString, ACell> merged = earlier;
		for (var entry : later.entrySet()) {
			AString key = (AString) entry.getKey();
			ACell incoming = entry.getValue();
			ACell existing = merged.get(key);
			if (existing instanceof AMap<?,?> em && incoming instanceof AMap<?,?> im) {
				incoming = mergeConfigMaps(
					(AMap<AString, ACell>) em, (AMap<AString, ACell>) im);
			}
			merged = merged.assoc(key, incoming);
		}
		return merged;
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
	 *
	 * <p>An optional {@code loads} input seeds the new session's tier of the
	 * context scope chain (#142). It applies only when the session is minted
	 * here; passing it against an existing session is an error, never a
	 * silent ignore.</p>
	 */
	private Blob resolveOrMintSession(Job job, AgentState agent, ACell input, AString caller) {
		AMap<AString, ACell> initialLoads;
		try {
			initialLoads = mintLoads(input);
		} catch (IllegalArgumentException e) {
			job.fail(describeFailure(e));
			return null;
		}
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
			if (initialLoads != null && agent.getSession(sid) != null) {
				job.fail("loads can only be passed when a session is created — this session already exists");
				return null;
			}
		} else {
			sid = generateSessionId();
		}
		agent.ensureSession(sid, caller, initialLoads);
		return sid;
	}

	/**
	 * Parses the optional mint-time {@code loads} input ({@code {path: {budget?}}},
	 * the session tier's declared loads, #142). Returns null when absent;
	 * throws {@link IllegalArgumentException} on a malformed value.
	 */
	private static AMap<AString, ACell> mintLoads(ACell input) {
		ACell raw = RT.getIn(input, Fields.LOADS);
		if (raw == null) return null;
		return covia.adapter.agent.ContextChain.declaredLoads(raw, "loads", true);
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
		AMap<AString, ACell> initialLoads;
		try {
			initialLoads = mintLoads(input);
		} catch (IllegalArgumentException e) {
			job.fail(describeFailure(e));
			return null;
		}
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
			if (initialLoads != null) {
				job.fail("loads can only be passed when a session is created — this session already exists");
				return null;
			}
			return sid;
		}
		Blob sid = generateSessionId();
		agent.ensureSession(sid, caller, initialLoads);
		return sid;
	}
}
