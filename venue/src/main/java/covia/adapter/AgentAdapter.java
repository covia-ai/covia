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
	private static final AString K_SOURCE_ID        = Strings.intern("sourceId");
	private static final AString K_INCLUDE_TIMELINE = Strings.intern("includeTimeline");
	private static final AString K_FORKED_FROM      = Strings.intern("forkedFrom");
	private static final AString K_SYSTEM_PROMPT    = Strings.intern("systemPrompt");
	private static final AString K_LLM_OPERATION    = Strings.intern("llmOperation");

	/** Maximum run loop iterations before forced exit (safety net) */
	private static final int MAX_LOOP_ITERATIONS = 20;

	/** Per-agent completion future — fires when the current run cycle finishes */
	private final ConcurrentHashMap<String, CompletableFuture<ACell>> runCompletions = new ConcurrentHashMap<>();

	/** Active transition job per agent — allows suspend to cancel running transitions */
	private final ConcurrentHashMap<String, Job> activeTransitions = new ConcurrentHashMap<>();

	/** Pending task Jobs — taskIdHex → Job. Completed by the run loop when taskResults arrive. */
	private final ConcurrentHashMap<String, Job> pendingTaskJobs = new ConcurrentHashMap<>();

	/** Counter for task ID generation */
	private long taskIdCounter = 0;

	/** Registry of pre-installed template names → venue asset hashes */
	private Index<AString, Hash> templates = Index.none();

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
		installAsset("agent/message",     BASE + "message.json");
		installAsset("agent/trigger",     BASE + "trigger.json");
		installAsset("agent/info",        BASE + "info.json");
		installAsset("agent/context",     BASE + "context.json");
		installAsset("agent/list",        BASE + "list.json");
		installAsset("agent/delete",      BASE + "delete.json");
		installAsset("agent/suspend",     BASE + "suspend.json");
		installAsset("agent/resume",      BASE + "resume.json");
		installAsset("agent/update",      BASE + "update.json");
		installAsset("agent/cancel-task", BASE + "cancelTask.json");

		// Install standard agent templates — discoverable via config="template:<name>"
		installTemplate("template:minimal", "/agent-templates/minimal.json");
		installTemplate("template:reader",  "/agent-templates/reader.json");
		installTemplate("template:worker",  "/agent-templates/worker.json");
		installTemplate("template:manager", "/agent-templates/manager.json");
		installTemplate("template:analyst", "/agent-templates/analyst.json");
		installTemplate("template:full",    "/agent-templates/full.json");
		installTemplate("template:goaltree","/agent-templates/goaltree.json");
	}

	/**
	 * Installs an agent template as a venue-level asset and records its
	 * short name in the template registry. Templates are flat config maps
	 * (systemPrompt, tools, etc.) that can be referenced via
	 * {@code config="template:<name>"} in {@code agent:create}.
	 */
	private void installTemplate(String name, String resourcePath) {
		Hash hash = installAsset(resourcePath);
		if (hash != null) {
			templates = templates.assoc(Strings.create(name), hash);
		}
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
				case "message" -> handleMessage(job, input, ctx);
				case "trigger" -> handleTrigger(job, input, ctx);
				case "info"    -> handleQuery(job, input, ctx);
				case "context" -> handleContext(job, input, ctx);
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

		// Add task to agent's lattice
		Blob taskId = generateTaskId();
		ACell taskInput = RT.getIn(input, Fields.INPUT);
		agent.addTask(taskId, taskInput);

		// Register this Job to be completed by the run loop when the task result arrives.
		// The Job stays in STARTED state — the standard Job lifecycle (REST ?wait, SDK
		// job.result()) handles sync vs async from the caller's perspective.
		AString taskIdHex = taskIdHex(taskId);
		job.setStatus(Status.STARTED);
		pendingTaskJobs.put(taskIdHex.toString(), job);

		// Wake agent to process the task — force=true because we just added a task
		// that may not yet be visible via cursor.get() (lattice write race)
		wakeAgent(agentId, ctx, true);
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

		// Default wait=true for backward compat (trigger traditionally blocks)
		long waitMs = parseWaitMs(input);
		if (waitMs == 0 && RT.getIn(input, Fields.WAIT) == null) waitMs = -1;

		ACell running = Maps.of(Fields.AGENT_ID, agentId, Fields.STATUS, AgentState.RUNNING);
		awaitRunCompletion(job, completion, waitMs, running, result -> result);
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
	 * Wakes an agent — sets wake time to now, then tries to start the run loop.
	 * Returns the completion future for the run cycle (may be null if the agent
	 * can't run). Package-private so LLMAgentAdapter can call it.
	 */
	CompletableFuture<ACell> wakeAgent(AString agentId, RequestContext ctx) {
		return wakeAgent(agentId, ctx, false);
	}

	CompletableFuture<ACell> wakeAgent(AString agentId, RequestContext ctx, boolean force) {
		AgentState agent = getAgent(ctx.getCallerDID(), agentId);
		if (agent == null) return null;
		agent.setWakeTime(Utils.getCurrentTimestamp());
		return tryStartRun(agentId, ctx, force);
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

		// Atomic CAS: SLEEPING → RUNNING. If it fails, the agent is already
		// running elsewhere — return that runner's future.
		if (!agent.tryStartRunning()) {
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
			// Pass framework config separately so the transition can read caps, tools, etc.
			AMap<AString, ACell> agentConfig = agent.getConfig();
			if (agentConfig != null) {
				transitionInput = transitionInput.assoc(AgentState.KEY_CONFIG, agentConfig);
			}

			Job transitionJob = engine.jobs().invokeOperation(transitionOp, transitionInput, ctx);
			activeTransitions.put(agentId.toString(), transitionJob);
			ACell transitionResult;
			try {
				transitionResult = transitionJob.awaitResult();
			} finally {
				activeTransitions.remove(agentId.toString());
			}

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
				Fields.RESULT, result);
			// Only include non-empty collections to avoid bloat
			if (formattedTasks != null && formattedTasks.count() > 0) {
				timelineEntry = timelineEntry.assoc(Fields.TASKS, formattedTasks);
			}
			if (inbox != null && inbox.count() > 0) {
				timelineEntry = timelineEntry.assoc(Fields.MESSAGES, inbox);
			}
			if (taskResults != null) timelineEntry = timelineEntry.assoc(Fields.TASK_RESULTS, taskResults);

			// Accumulate task results across iterations
			if (taskResults != null) {
				for (var entry : taskResults.entrySet()) {
					allTaskResults = allTaskResults.assoc(entry.getKey(), entry.getValue());
				}
			}

			// Merge results atomically (timeline, state, task cleanup)
			AMap<AString, ACell> merged = agent.mergeRunResult(
				newState, inbox.count(), tasks, taskResults, timelineEntry);

			// Complete pending request Jobs AFTER the merge so timeline and state
			// are visible when the caller's awaitResult returns
			if (taskResults != null) {
				for (var entry : taskResults.entrySet()) {
					String taskKey = entry.getKey().toString();
					Job pendingJob = pendingTaskJobs.remove(taskKey);
					if (pendingJob != null) {
						ACell taskResult = entry.getValue();
						pendingJob.completeWith(Maps.of(
							Fields.ID, entry.getKey(),
							Fields.STATUS, RT.getIn(taskResult, Fields.STATUS),
							Fields.OUTPUT, RT.getIn(taskResult, Fields.OUTPUT)));
					}
				}
			}

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
			failPendingTaskJobs(callerDID, agentId, e.getMessage());
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

	/**
	 * Fails pending task Jobs for the given agent. Called when the agent
	 * suspends — the agent can't process tasks in this state, so callers
	 * waiting on results should be unblocked with a failure.
	 */
	private void failPendingTaskJobs(AString callerDID, AString agentId, String error) {
		AgentState agent = getAgent(callerDID, agentId);
		if (agent == null) return;
		Index<Blob, ACell> tasks = agent.getTasks();
		if (tasks == null) return;
		for (var entry : tasks.entrySet()) {
			String taskKey = taskIdHex(entry.getKey()).toString();
			Job pending = pendingTaskJobs.remove(taskKey);
			if (pending != null) {
				pending.fail(error);
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
	 * Resolves a string reference to a config map. Tries, in order:
	 * <ol>
	 *   <li>Pre-installed template registry (e.g. {@code template:worker})</li>
	 *   <li>Asset resolution (bare hash, {@code /a/}, {@code /o/}, DID URL,
	 *       venue operation name) — the asset metadata is used as the config map</li>
	 *   <li>Workspace path resolution (e.g. {@code w/templates/reader}) within
	 *       the caller's own lattice namespace</li>
	 * </ol>
	 * Returns {@code null} if none resolves to a map.
	 */
	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> resolveConfigRef(AString ref, RequestContext ctx) {
		// Check pre-installed template registry first (e.g. "template:worker")
		Hash templateHash = templates.get(ref);
		if (templateHash != null) {
			Asset tAsset = engine.getAsset(templateHash);
			if (tAsset != null) {
				AMap<AString, ACell> tMeta = tAsset.meta();
				if (tMeta != null) return tMeta;
			}
		}

		// Try asset resolution
		Asset asset = engine.resolveAsset(ref, ctx);
		if (asset != null) {
			AMap<AString, ACell> meta = asset.meta();
			if (meta != null) return meta;
		}

		// Fall back to workspace path resolution in caller's own namespace
		if (ctx.getCallerDID() != null) {
			Users users = engine.getVenueState().users();
			User user = users.get(ctx.getCallerDID());
			if (user != null) {
				ACell[] pathKeys = CoviaAdapter.parsePath(ref);
				if (pathKeys.length > 0) {
					ACell value = CoviaAdapter.readPath(user.cursor(), pathKeys);
					if (value instanceof AMap<?,?> vm) return (AMap<AString, ACell>) vm;
				}
			}
		}
		return null;
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
