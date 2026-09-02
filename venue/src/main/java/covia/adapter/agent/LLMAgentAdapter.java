package covia.adapter.agent;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.AgentAdapter;
import covia.adapter.agent.ContextInspectable.Inspection;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * LLM-backed transition function for agents (level 2).
 *
 * <p>Invoked by the agent run loop as a transition operation ({@code llmagent:chat}).
 * Uses the same durable frame store as the goal-tree runtime, with one root
 * frame, and delegates LLM calls to a level 3 grid operation (for example
 * {@code langchain:openai}).</p>
 *
 * <p>This adapter has no LLM library dependencies — it works entirely with
 * structured message maps and invokes level 3 via the grid operation dispatch.</p>
 *
 * <h3>Tool palette</h3>
 * <p>Agents are advertised exactly the tools they declare in config
 * {@code tools} (strict allowlist, #92). {@code defaultTools: true} adds the
 * deliberately minimal read-only pack in
 * {@link ToolPalette#DEFAULT_TOOL_OPS}; capability tools arrive via skills
 * ({@code skill_load}). Task tools ({@code complete_task}, {@code fail_task})
 * are added dynamically when tasks are pending.</p>
 *
 * <p>Additional tools can be configured via {@code tools} in the agent's config.
 * Each entry is a string (operation name) or map with {@code operation} plus optional
 * {@code name} and {@code description} overrides. Config tools are resolved from
 * adapter functions or grid operations and flattened as direct tools for the LLM.</p>
 *
 * <h3>Message format</h3>
 * <p>All messages in history use a common map format:</p>
 * <ul>
 *   <li>{@code {role: "system"|"user", content: "..."}}</li>
 *   <li>{@code {role: "assistant", content: "...", toolCalls?: [{id, name, arguments: {...}}]}}</li>
 *   <li>{@code {role: "tool", id: "...", name: "...", content: "..."}}</li>
 * </ul>
 *
 * <h3>Tool call loop</h3>
 * <p>When level 3 returns an assistant message with {@code toolCalls}, level 2
 * executes each tool as a grid operation, appends tool result messages, and
 * calls level 3 again. This loops until the LLM returns a text response
 * (no tool calls) or the tool-call iteration limit is reached (venue
 * config {@code maxToolIterations}, default 30, overridable per agent via
 * {@code config.maxToolIterations}). The shared {@code compact} harness tool
 * replaces the visible prefix with assistant memory while retaining the exact
 * old vector beneath the archive record.</p>
 *
 * <h3>Conversation structure</h3>
 * <pre>
 * { "frames": [{
 *   "description": "Process incoming work",
 *   "conversation": [
 *     { "role": "user",      "content": "Hello" },
 *     { "role": "assistant", "content": "Hi there!" }
 *   ]
 * }]}
 * </pre>
 */
public class LLMAgentAdapter extends AbstractLLMAdapter implements FramesOwning {

	private static final Logger log = LoggerFactory.getLogger(LLMAgentAdapter.class);

	// State field keys

	// Config keys specific to this adapter (parent provides K_CONFIG, K_LLM_OPERATION,
	// K_MODEL, K_SYSTEM_PROMPT, K_URL, K_API_KEY, K_TOOLS, K_RESPONSE_FORMAT,
	// K_CONTEXT, K_CAPS, K_TOOL_CALL_TIMEOUT_MS).
	private static final AString K_DEFAULT_TOOLS   = Strings.intern("defaultTools");

	// ========== Default tool definitions ==========
	// MCP-style: {name, description, parameters: {type: "object", properties: {...}, required: [...]}}

	/** Harness pseudo-tools this runtime provides — intercepted by the adapter,
	 *  never dispatched as operations (see {@link AbstractLLMAdapter#dispatchTool}):
	 *  the shared registry plus the task tools. */
	static final java.util.Set<String> HARNESS_TOOL_NAMES;
	static {
		java.util.Set<String> names = new java.util.HashSet<>(HarnessTools.SHARED.keySet());
		names.addAll(TaskTools.NAMES);
		HARNESS_TOOL_NAMES = java.util.Set.copyOf(names);
	}

	@Override
	public String getName() {
		return "llmagent";
	}

	@Override
	public String getDescription() {
		return "LLM-backed transition function for agents. Maintains conversation "
			+ "history in the shared durable frame format, processes inbox messages as user turns, and "
			+ "invokes a level 3 grid operation for LLM calls. Supports tool call "
			+ "loops: when the LLM requests tool calls, executes them as grid "
			+ "operations and feeds results back until a text response is produced. "
			+ "Built-in tools: complete_task, fail_task (added dynamically when "
			+ "tasks are pending). All other tools dispatch via the grid.";
	}

	@Override
	protected void installAssets() {
		installAsset("llmagent/chat", "/adapters/llmagent/chat.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		return CompletableFuture.supplyAsync(() -> processChat(ctx, input), VIRTUAL_EXECUTOR);
	}

	/** What inspection and step share with a live cycle: the Spec, the tool
	 *  context the harness tools run against, and the cycle's fixed tools. */
	private record Preview(ContextAssembler.Spec spec, ToolContext toolCtx, AVector<ACell> fixedTools,
			ContextAssembler.Diagnostics diagnostics) {}

	/**
	 * The transition a call with these inputs would start: the session's
	 * conversation, the inbox, pending results, and a task rendered exactly as
	 * the tool loop renders it — task tools included.
	 */
	@SuppressWarnings("unchecked")
	private Preview preview(Inspection in, RequestContext ctx) {
		AMap<AString, ACell> sourceConfig = in.config();
		AMap<AString, ACell> config = effectiveModelConfig(sourceConfig, ctx);
		// Same scope-chain view as processChat (agent tier + session tier), so
		// the inspected skills index carries the right (loaded) markers.
		AMap<AString, ACell> configLoads = ContextChain.operatorLoads(
			RT.getIn(config, Fields.LOADS), "config.loads");
		ACell sessLoads = RT.getIn(in.session(), Fields.LOADS);
		AMap<AString, ACell> sessionTier = (sessLoads instanceof AMap)
			? (AMap<AString, ACell>) sessLoads : null;

		RequestContext capsCtx = capsContext(config, ctx);
		ModelProfile profile = modelProfileFor(config, ctx);
		boolean cachePrefix = promptCaching(profile, config);
		AVector<ACell> sessionFrames = sessionFramesOf(in.session());
		FixedPalette fixed = fixedPalette(sourceConfig, ctx, capsCtx,
			sessionFrames, cachePrefix);
		AVector<ACell> harness = fixed.harness();
		AVector<ACell> fixedTools = fixed.tools();

		// The task renders through the tool loop's own renderer — a preview
		// job id stands in for the one a real task would carry.
		AVector<ACell> tasks = (in.task() != null)
			? Vectors.of((ACell) Maps.of(Fields.JOB_ID, TaskTools.PREVIEW_JOB_ID, Fields.INPUT, in.task()))
			: null;
		ToolContext toolCtx = toolContext(config, sourceConfig, capsCtx, tasks, in.pending(),
			configLoads, sessionTier, in.session() != null, fixed, true);
		toolCtx.labels = profile.labels();
		toolCtx.cachePrefix = cachePrefix;
		AVector<ACell> previewFrames = (sessionFrames != null) ? sessionFrames : Vectors.empty();
		if (previewFrames.isEmpty()) {
			previewFrames = Vectors.of((ACell) GoalTreeContext.createFrame(""));
		}
		previewFrames = FrameStore.appendCycleInputTurns(previewFrames,
			in.messages(), null, convex.core.util.Utils.getCurrentTimestamp());
		toolCtx.frames = previewFrames;
		toolCtx.store = new FrameStore.LocalFrameStore(previewFrames);
		ACell task = toolCtx.tasks.message();
		Loads.Snapshot loads = toolCtx.refreshLoadSnapshot(engine, profile.labels());
		boolean hasInput = (in.messages() != null && in.messages().count() > 0)
			|| (in.pending() != null && in.pending().count() > 0)
			|| task != null;

		// The loads ride in exactly as the tool loop sets them — stable elements
		// through withLoads and watched values through the frame observation — so an inspected
		// context matches a live inference by construction. The pre-split
		// elements dropped every loads-derived exchange from inspection (#418).
		AVector<ACell> offered = concatTools(fixedTools, loads.tools());
		AMap<AString, ACell> effectiveLoads = ContextChain.effective(configLoads, sessionTier);
		ContextAssembler.Spec spec = new ContextAssembler.Spec(
			engine, ctx, capsCtx, config,
			ContextAssembler.sessionHex(RT.getIn(in.session(), Fields.ID)), null,
			profile.budget(), profile.labels(), profile.toolCalling(),
			offered, null, effectiveLoads,
			previewFrames, in.pending(), null, hasInput, null, task,
			fixed.unavailable(), null, null)
			.withLoads(loads, offered, effectiveLoads)
			.withSourceConfig(sourceConfig)
			.withCachePrefix(cachePrefix);
		if (!toolCtx.store.observe(0, ContextAssembler.observations(spec, loads),
				convex.core.util.Utils.getCurrentTimestamp())) {
			throw new IllegalStateException("Could not preview watched context observations");
		}
		toolCtx.frames = toolCtx.store.frames();
		spec = spec.withFrames(toolCtx.frames);
		AVector<ACell> entries = Vectors.empty();
		if (profile.toolCalling()) {
			entries = (AVector<ACell>) ToolPalette.provenance(TaskTools.DEFINITIONS, "harness")
				.concat(ToolPalette.provenance(harness, "harness"))
				.concat(fixed.provenance())
				.concat(loads.toolProvenance());
		}
		ContextAssembler.Diagnostics diagnostics = new ContextAssembler.Diagnostics(
			entries, loads.diagnostics(), fixed.unavailable());
		return new Preview(spec, toolCtx, fixedTools, diagnostics);
	}

	@Override
	protected InspectionContext inspectionContext(Inspection in, RequestContext ctx) {
		Preview p = preview(in, ctx);
		return new InspectionContext(p.spec(), p.diagnostics());
	}

	/**
	 * One iteration of the tool loop on the supplied reply: text-as-control
	 * recognised as live, the batch dispatched through the live registry —
	 * a task resolution judged and recorded but never reaching a job — and the next
	 * prompt rebuilt as the loop rebuilds it: appended loads reused, watched loads observed, a resolved task
	 * gone from the tail, this iteration's turns in band.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> stepContext(Inspection in, AMap<AString, ACell> assistant, RequestContext ctx) {
		Preview p = preview(in, ctx);
		ToolContext toolCtx = p.toolCtx();
		AMap<AString, ACell> reply = assistant;
		AVector<ACell> calls = RT.ensureVector(reply.get(K_TOOL_CALLS));
		if (calls == null && p.spec().task() != null) {
			AMap<AString, ACell> rewritten = recogniseTextualControlCall(reply, 0);
			if (rewritten != null) {
				reply = rewritten;
				calls = RT.ensureVector(reply.get(K_TOOL_CALLS));
			}
		}
		if (calls == null) return Step.done(reply, reply.get(K_CONTENT)).report();

		toolCtx.turnText = RT.ensureString(reply.get(K_CONTENT));
		StepSink sink = new StepSink();
		ToolCycleEngine.BatchResult batch = ToolCycleEngine.executeBatch(
			calls, 0, toolRegistry(), toolCtx, sink, log);
		AVector<ACell> turns = Vectors.of((ACell) reply).concat(sink.turns());

		Loads.Snapshot loads = toolCtx.refreshLoadSnapshot(engine, p.spec().labels());
		ACell task = toolCtx.tasks.message();
		AVector<ACell> tools = concatTools(p.fixedTools(), loads.tools());
		ContextAssembler.Spec next;
		boolean compacted = toolCtx.pendingCompactSummary != null;
		if (compacted) {
			AVector<ACell> frames = FrameStore.appendCycleInputTurns(
				toolCtx.frames, p.spec().input(), null, convex.core.util.Utils.getCurrentTimestamp());
			AMap<AString, ACell> root = RT.ensureMap(frames.get(0));
			root = GoalTreeContext.appendTurn(root, reply);
			for (long i = 0; i < sink.turns().count(); i++) {
				root = GoalTreeContext.appendTurn(root, sink.turns().get(i));
			}
			frames = frames.assoc(0,
				GoalTreeContext.compactFrame(root, toolCtx.pendingCompactSummary));
			next = p.spec().afterCompaction(frames);
		} else {
			next = p.spec().withToolLoop(turns);
		}
		next = next
			.withLoads(loads, tools, ContextChain.effective(toolCtx.outerLoads, toolCtx.loads))
			.withTask(task);
		next = observeStep(next, loads, compacted);
		return new Step(reply, turns, sink, batch.terminalStatus(), batch.terminalValue(), null, next).report();
	}

	/**
	 * The inspection-only step path has no durable CAS. Reuse the same pure
	 * observation transform, then place newly appended observation messages
	 * after this step's tool results. A compacted preview already moved those
	 * results into its frame, so its observations stay there as live runtime
	 * observations do.
	 */
	@SuppressWarnings("unchecked")
	private static ContextAssembler.Spec observeStep(ContextAssembler.Spec spec,
			Loads.Snapshot loads, boolean compacted) {
		AVector<ACell> frames = spec.frames();
		if (frames.isEmpty()) return spec;
		AMap<AString, ACell> frame = (AMap<AString, ACell>) frames.get(0);
		AVector<ACell> before = RT.ensureVector(frame.get(GoalTreeContext.K_CONVERSATION));
		if (before == null) before = Vectors.empty();
		AMap<AString, ACell> observed = GoalTreeContext.applyObservations(frame,
			ContextAssembler.observations(spec, loads),
			convex.core.util.Utils.getCurrentTimestamp());
		if (compacted) return spec.withFrames(frames.assoc(0, observed));

		AVector<ACell> after = RT.ensureVector(observed.get(GoalTreeContext.K_CONVERSATION));
		AVector<ACell> appended = (after != null && after.count() > before.count())
			? (AVector<ACell>) after.slice(before.count(), after.count()) : Vectors.empty();
		AMap<AString, ACell> stateOnly = observed.assoc(GoalTreeContext.K_CONVERSATION, before);
		return spec.withFrames(frames.assoc(0, stateOnly))
			.withToolLoop((AVector<ACell>) spec.toolLoop().concat(appended));
	}

	/**
	 * Core transition function logic.
	 *
	 * <p>Builds conversation history, invokes level 3 (with tool call loop),
	 * and returns the updated state.</p>
	 *
	 * @param ctx Request context (caller identity for level 3 invocation)
	 * @param input Transition input: { agentId, state, tasks, pending, messages, config, newInput, session? }
	 * @return Transition output: { state, response | error }
	 */
	ACell processChat(RequestContext ctx, ACell input) {
		// The cycle record (#392) collects every inference and tool call made
		// below — thread-confined, nothing threaded through the loop — and
		// rides out on the output, or on the failure that ends the cycle.
		CycleRecord.begin(ctx.getCycle());
		try {
			return chat(ctx, input);
		} catch (RuntimeException e) {
			throw CycleRecord.Failure.of(e);
		}
	}

	@SuppressWarnings("unchecked")
	private ACell chat(RequestContext ctx, ACell input) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		ACell state = RT.getIn(input, AgentState.KEY_STATE);
		// S3c: prefer session.pending over agent-level messages when a session
		// is in scope. Both carry the same envelopes (S3b dual-write); reading
		// both would duplicate. effectiveMessages picks the right one.
		AVector<ACell> messages = AgentAdapter.effectiveMessages(input);
		AVector<ACell> tasks = (AVector<ACell>) RT.getIn(input, Fields.TASKS);
		AVector<ACell> pending = (AVector<ACell>) RT.getIn(input, Fields.PENDING);

		@SuppressWarnings("unchecked")
		AMap<AString, ACell> recordConfig = (RT.getIn(input, AgentState.KEY_CONFIG) instanceof AMap m) ? m : null;

		// Determine if there is real input for the agent
		boolean hasInput = (messages != null && messages.count() > 0)
			|| (tasks != null && tasks.count() > 0)
			|| (pending != null && pending.count() > 0);

		// LLMAgent is the one-frame form of the shared frame runtime. Claim the
		// session, repair any interrupted tool exchange, and persist this cycle's
		// presented input before the first inference, exactly as GoalTree does.
		FrameStore.Opened opened = FrameStore.open(engine, ctx, agentId, input, messages,
			GoalTreeContext.describeTransitionInput(messages, tasks, pending),
			convex.core.util.Utils.getCurrentTimestamp(),
			convex.core.data.prim.CVMBool.TRUE.equals(
				RT.getIn(recordConfig, Strings.intern("recordCaller"))), log);
		if (opened.failed()) {
			CycleRecord.Result cycle = CycleRecord.end();
			AMap<AString, ACell> failed = Maps.of(
				AgentState.KEY_STATE, Maps.empty(), Fields.ERROR, opened.error(),
				Fields.CYCLE, cycle.cycle());
			if (cycle.tokens() != null) failed = failed.assoc(Fields.TOKENS, cycle.tokens());
			return failed;
		}
		FrameStore store = opened.store();

		// Context scope chain (#142): agent tier (config.loads, operator-pinned)
		// → session tier (sessions.<sid>.loads, runtime-managed). The session is
		// the innermost tier for this runtime; a cycle with no session in scope
		// has no writable tier and context_load/unload fail diagnosably.
		boolean sessionInScope = RT.getIn(input, Fields.SESSION) != null;
		AMap<AString, ACell> configLoads = ContextChain.operatorLoads(
			RT.getIn(recordConfig, Fields.LOADS), "config.loads");
		AMap<AString, ACell> sessionTier = ContextChain.sessionLoads(input);
		AMap<AString, ACell> effectiveLoads = ContextChain.effective(configLoads, sessionTier);

		AVector<ACell> sessionFrames = store.frames();
		RequestContext capsCtx = capsContext(recordConfig, ctx).withAgentId(agentId);
		AMap<AString, ACell> config = effectiveModelConfig(recordConfig, ctx);
		AString llmOperation = getLLMOperation(config);
		ModelProfile profile = modelProfileFor(config, ctx);
		boolean cachePrefix = promptCaching(profile, config);
		FixedPalette fixed = fixedPalette(recordConfig, ctx, capsCtx,
			sessionFrames, cachePrefix);
		AVector<ACell> fixedTools = fixed.tools();

		ToolContext toolCtx = toolContext(config, recordConfig, capsCtx, tasks, pending,
			configLoads, sessionTier, sessionInScope, fixed, false);
		toolCtx.labels = profile.labels();
		toolCtx.cachePrefix = cachePrefix;
		toolCtx.frames = sessionFrames;
		toolCtx.store = store;

		// Everything the assembler needs for this cycle; the loop supplies what
		// changes per inference — loads, this cycle's turns, the outstanding task.
		ContextAssembler.Spec spec = new ContextAssembler.Spec(
			engine, ctx, capsCtx, config,
			ContextAssembler.sessionHex(RT.getIn(input, Fields.SESSION, Fields.ID)), null,
			profile.budget(), profile.labels(), profile.toolCalling(),
			fixedTools, null, null, sessionFrames, pending, null, hasInput, null, null,
			fixed.unavailable(), null, null)
			.withSourceConfig(recordConfig)
			.withCachePrefix(cachePrefix);

		// ctx (uncapped) makes the provider call; capsCtx flows through toolCtx
		// for tool dispatch.
		AVector<ACell> newMessages = invokeWithToolLoop(llmOperation, spec, fixedTools, ctx, toolCtx);

		// Filter out empty assistant messages (e.g. when LLM produces only <think> tags)
		// to avoid polluting the transcript with useless entries
		AVector<ACell> newMessagesFiltered = Vectors.empty();
		for (long i = 0; i < newMessages.count(); i++) {
			ACell msg = newMessages.get(i);
			if (ROLE_ASSISTANT.equals(RT.getIn(msg, K_ROLE))) {
				AString content = RT.ensureString(RT.getIn(msg, K_CONTENT));
				boolean hasContent = content != null && content.count() > 0;
				boolean hasToolCalls = RT.getIn(msg, K_TOOL_CALLS) instanceof AVector<?> v && v.count() > 0;
				if (!hasContent && !hasToolCalls) continue;
			}
			newMessagesFiltered = newMessagesFiltered.conj(msg);
		}

		// Extract text content from the final assistant message
		ACell lastMsg = newMessages.get(newMessages.count() - 1);
		AString contentText = RT.ensureString(RT.getIn(lastMsg, K_CONTENT));
		String responseText = (contentText != null) ? contentText.toString() : "";

		// The session frame is the sole conversation record and loads live on the
		// session tier (#142); agent-level state carries nothing for this
		// runtime (config's single home is record.config, #144).
		AMap<AString, ACell> newState = Maps.empty();
		// Lean transition output: emit {state, response | error}. Task
		// completion (if any) is signalled to the framework by the venue op
		// invoked from the complete_task / fail_task tool wrappers, which
		// parks an envelope in deferredCompletions; the run loop drains
		// that map AFTER mergeRunResult to build the cycle's TASK_RESULTS.
		//
		// Default response is the assistant's chat text. If the LLM called
		// complete_task with structured output, that output overrides the
		// chat text in the timeline result (it's the authoritative task
		// answer). If the LLM called fail_task, the error replaces the
		// response entirely.
		AMap<AString, ACell> output = Maps.of(
			AgentState.KEY_STATE, newState,
			Fields.RESPONSE, Strings.create(responseText));
		// Preserve the conventional transition output for direct callers. Session
		// runs have already written these turns live through FrameStore, and the
		// FramesOwning marker prevents the framework from appending them again.
		if (newMessagesFiltered.count() > 1) {
			output = output.assoc(Fields.TURNS,
				newMessagesFiltered.slice(0, newMessagesFiltered.count() - 1));
		}
		if (store instanceof FrameStore.LocalFrameStore) {
			output = output.assoc(Fields.FRAMES, store.frames());
		}
		// Session-tier loads (post valve + this cycle's context_load/unload
		// mutations (including legacy tombstones) — the framework writes them back to
		// sessions.<sid>.loads inside mergeRunResult's CAS (#142).
		if (sessionInScope) {
			output = output.assoc(Fields.LOADS, toolCtx.getLoads());
		}

		output = toolCtx.tasks.promote(output);

		// The cycle record and its token totals (#217: measured only; absent
		// means the provider reported nothing, never zero).
		CycleRecord.Result cycle = CycleRecord.end();
		output = output.assoc(Fields.CYCLE, cycle.cycle());
		if (cycle.tokens() != null) output = output.assoc(Fields.TOKENS, cycle.tokens());
		return output;
	}

	/**
	 * The context harness tools run against for one cycle. Its loads slot is
	 * the SESSION tier (the innermost writable tier for this runtime); the
	 * agent tier rides along read-only so pinned ownership can be enforced.
	 */
	private ToolContext toolContext(AMap<AString, ACell> config,
			AMap<AString, ACell> sourceConfig, RequestContext capsCtx,
			AVector<ACell> tasks, AVector<ACell> pending,
			AMap<AString, ACell> configLoads, AMap<AString, ACell> sessionTier,
			boolean sessionInScope, FixedPalette fixed, boolean preview) {
		long timeoutMs = resolveToolCallTimeoutMs(config);
		ToolContext toolCtx = new ToolContext(capsCtx.getAgentId(), capsCtx,
			new TaskTools.Tasks(engine, capsCtx, tasks, timeoutMs, preview), pending,
			sessionTier, timeoutMs, fixed.toolIndex());
		toolCtx.outerLoads = configLoads;
		toolCtx.sourceConfig = sourceConfig;
		toolCtx.sessionInScope = sessionInScope;
		toolCtx.skillSources = Skills.sourcesOf(config);
		toolCtx.baseToolStart = Math.toIntExact(TaskTools.DEFINITIONS.count() + fixed.harness().count());
		toolCtx.baseToolEnd = Math.toIntExact(toolCtx.baseToolStart + fixed.baseTools().count());
		return toolCtx;
	}

	private record FixedPalette(AVector<ACell> baseTools, AVector<ACell> harness,
			AMap<AString, ACell> toolIndex, AVector<ACell> unavailable) {
		@SuppressWarnings("unchecked")
		AVector<ACell> tools() {
			return (AVector<ACell>) TaskTools.DEFINITIONS.concat(harness).concat(baseTools);
		}

		AVector<ACell> provenance() {
			return new ToolPalette.Palette(null, toolIndex, null).provenance();
		}
	}

	/** The immutable tools for a session: task controls, declared harness tools,
	 * configured operations, then schemas from the initial skills catalog. */
	@SuppressWarnings("unchecked")
	private FixedPalette fixedPalette(AMap<AString, ACell> config, RequestContext catalogCtx,
			RequestContext capsCtx, AVector<ACell> frames,
			boolean cachePrefix) {
		AVector<ACell> harness = HarnessTools.offered(config, HarnessTools.SHARED);
		ContextAssembler.Rendered rendered = ContextAssembler.rendered(
			frames, config, cachePrefix);
		if (rendered != null) {
			return new FixedPalette(rendered.baseTools(), harness,
				rendered.toolIndex(), Vectors.empty());
		}
		ToolPalette.Palette configured = ToolPalette.resolve(
			engine, catalogCtx, config, HARNESS_TOOL_NAMES);
		ToolPalette.Palette declared = ToolPalette.declaredSkillTools(
			engine, catalogCtx, capsCtx, Skills.sourcesOf(config),
			name -> HARNESS_TOOL_NAMES.contains(name) || configured.contains(name));
		ToolPalette.Palette base = configured.merge(declared);
		return new FixedPalette(base.tools(), harness,
			base.toolIndex(), configured.unavailable());
	}

	/**
	 * Invokes level 3 with a tool call loop.
	 *
	 * <p>Calls the LLM operation. If the response contains {@code toolCalls},
	 * executes each tool, appends tool result messages, and calls the LLM again.
	 * Repeats until a text-only response or the iteration limit.</p>
	 *
	 * <p>Built-in tools (complete_task, fail_task, grid_run, grid_invoke,
	 * message_agent) are intercepted and handled locally. All other tool names
	 * are dispatched as grid operations.</p>
	 *
	 * @return Vector of new messages to append to history (includes any tool call
	 *         assistant messages, tool result messages, and the final text response)
	 */
	@SuppressWarnings("unchecked")
	private AVector<ACell> invokeWithToolLoop(
			AString llmOperation, ContextAssembler.Spec spec,
			AVector<ACell> fixedTools, RequestContext ctx, ToolContext toolCtx) {
		AMap<AString, ACell> config = spec.config();
		AVector<ACell> messages = Vectors.empty();
		int maxToolIterations = resolveMaxToolIterations(config);
		ToolCycleEngine.Registry<ToolContext> registry = toolRegistry();
		@SuppressWarnings("unchecked")
		final AVector<ACell>[] sinkMessages = new AVector[] { messages };
		ToolCycleEngine.BatchSink sink = new ToolCycleEngine.BatchSink() {
			@Override
			public void append(AMap<AString, ACell> message) {
				sinkMessages[0] = sinkMessages[0].conj(message);
				appendRootTurn(toolCtx, message);
			}
		};
		boolean retriedAfterTruncation = false;

		for (int iteration = 0; iteration < maxToolIterations; iteration++) {
			if (toolCtx.pendingCompactSummary != null) {
				compactRoot(toolCtx, toolCtx.pendingCompactSummary);
				toolCtx.pendingCompactSummary = null;
				spec = spec.afterCompaction(toolCtx.frames);
			}
			toolCtx.frames = toolCtx.store.frames();
			// Existing messages stay intact; this cycle's turns and any changed
			// observations extend them.
			AMap<AString, ACell> effectiveLoads =
				ContextChain.effective(toolCtx.outerLoads, toolCtx.loads);
			Loads.Snapshot loads = toolCtx.refreshLoadSnapshot(engine, spec.labels());
			ACell taskMessage = toolCtx.tasks.message();
			AVector<ACell> tools = concatTools(fixedTools, loads.tools());
			ContextAssembler.Spec inference = spec
				.withLoads(loads, tools, effectiveLoads)
				.withFrames(toolCtx.frames)
				.withToolLoop(Vectors.empty())
				.withTask(taskMessage);
			if (!toolCtx.store.observe(0, ContextAssembler.observations(inference, loads),
					convex.core.util.Utils.getCurrentTimestamp())) {
				throw new JobFailedException("Session cycle was superseded while observing context");
			}
			toolCtx.frames = toolCtx.store.frames();
			inference = inference.withFrames(toolCtx.frames);
			inference = prepareRendering(inference, toolCtx);
			ContextAssembler.Prompt prompt = ContextAssembler.assemble(inference);

			ACell assistant = invokeLevel3(llmOperation, config, prompt, ctx);
			if (isLengthLimited(assistant)) {
				if (retriedAfterTruncation) {
					log.warn("LLM response reached its output token limit again — failing the transition");
					throw new JobFailedException(TRUNCATION_FAILURE_MESSAGE);
				}
				retriedAfterTruncation = true;
				log.warn("LLM response reached its output token limit — retrying once without partial output");
				AMap<AString, ACell> retry = RT.ensureMap(stampTs(truncationRetryTurn()));
				messages = messages.conj(retry);
				appendRootTurn(toolCtx, retry);
				iteration--; // truncation recovery is not a tool iteration
				continue;
			}
			AVector<ACell> calls = RT.ensureVector(RT.getIn(assistant, K_TOOL_CALLS));
			boolean hasCalls = calls != null && calls.count() > 0;
			if (!hasCalls && taskMessage != null) {
				AMap<AString, ACell> rewritten = ToolCycleEngine.recogniseTextualControlCall(
					assistant, iteration,
					TaskTools.NAMES);
				if (rewritten != null) {
					log.warn("Assistant emitted a harness control tool as text — honouring it (#215)");
					assistant = rewritten;
					calls = RT.ensureVector(RT.getIn(assistant, K_TOOL_CALLS));
					hasCalls = true;
				}
			}

			if (!hasCalls) {
				if (config != null) {
					// responseFormat is a provider hint here, not a contract: the
					// same judgement as a typed completion, logged rather than enforced.
					AMap<AString, ACell> schema = getResponseFormatSchema(config);
					AString content = RT.ensureString(RT.getIn(assistant, K_CONTENT));
					if (schema != null && content != null) {
						Completion completion = Completion.of(content, null, schema, null);
						if (!completion.accepted()) log.warn("LLM response schema violation: {}", completion.rejection());
					}
				}
				AMap<AString, ACell> finalTurn = RT.ensureMap(stampTs(assistant));
				appendRootTurn(toolCtx, finalTurn);
				return messages.conj(finalTurn);
			}

			AMap<AString, ACell> callTurn = RT.ensureMap(stampTs(assistant));
			messages = messages.conj(callTurn);
			appendRootTurn(toolCtx, callTurn);
			toolCtx.turnText = RT.ensureString(RT.getIn(assistant, K_CONTENT));
			sinkMessages[0] = messages;
			ToolCycleEngine.executeBatch(calls, iteration, registry, toolCtx, sink, log);
			messages = sinkMessages[0];
		}

		log.warn("Tool call loop reached iteration limit ({}) — failing the transition", maxToolIterations);
		throw new JobFailedException("Agent reached the tool-call iteration limit ("
			+ maxToolIterations + ") without completing the task.");
	}

	/** Applies the model's rendering policy before the provider call. The
	 * session CAS replaces a stale cached projection or removes one for an
	 * uncached model, leaving append-only conversation untouched. */
	@SuppressWarnings("unchecked")
	private ContextAssembler.Spec prepareRendering(
			ContextAssembler.Spec spec, ToolContext toolCtx) {
		if (toolCtx.frames.isEmpty()) return spec;
		if (!ContextAssembler.renderingUpdateRequired(spec)) return spec;
		ContextAssembler.Rendered candidate = null;
		if (spec.cachePrefix()) {
			AMap<AString, ACell> fixedIndex = ToolPalette.mergeIndex(
				toolCtx.toolIndex, toolCtx.currentPinnedToolIndex);
			fixedIndex = ToolPalette.mergeIndex(fixedIndex,
				ToolPalette.loadOwners(toolCtx.currentLoadToolIndex));
			AMap<AString, ACell> manifestIndex = new ToolPalette.Palette(
				null, fixedIndex, null).forManifest(spec.tools()).toolIndex();
			int baseStart = spec.toolCalling() ? toolCtx.baseToolStart : 0;
			int baseEnd = spec.toolCalling() ? toolCtx.baseToolEnd : 0;
			candidate = ContextAssembler.initialise(spec).withToolIndex(
				manifestIndex, baseStart, baseEnd);
			toolCtx.toolIndex = manifestIndex;
		}
		ContextAssembler.Rendered rendering = candidate;
		if (!toolCtx.store.update(frames -> {
			if (frames.isEmpty()) return frames;
			AMap<AString, ACell> root = (AMap<AString, ACell>) frames.get(0);
			AMap<AString, ACell> prepared = ContextAssembler.applyRendering(
				root, spec, rendering);
			return prepared == root ? frames : frames.assoc(0, prepared);
		})) {
			throw new JobFailedException("Session cycle was superseded while preparing context");
		}
		toolCtx.frames = toolCtx.store.frames();
		return spec.withFrames(toolCtx.frames);
	}

	@SuppressWarnings("unchecked")
	private static void appendRootTurn(ToolContext toolCtx, AMap<AString, ACell> turn) {
		if (turn.get(AgentState.K_TURN_TS) == null) {
			turn = turn.assoc(AgentState.K_TURN_TS,
				CVMLong.create(convex.core.util.Utils.getCurrentTimestamp()));
		}
		if (turn.get(AgentState.K_SOURCE) == null) {
			AString role = RT.ensureString(turn.get(AgentState.K_ROLE));
			turn = turn.assoc(AgentState.K_SOURCE,
				ROLE_TOOL.equals(role)
					? AgentState.SOURCE_TOOL : AgentState.SOURCE_TRANSITION);
		}
		if (!toolCtx.store.appendRoot(turn)) {
			throw new JobFailedException("Session cycle was superseded while appending conversation");
		}
		toolCtx.frames = toolCtx.store.frames();
	}

	@SuppressWarnings("unchecked")
	private static void compactRoot(ToolContext toolCtx, String summary) {
		if (!toolCtx.store.update(frames -> {
			if (frames.isEmpty()) return frames;
			AMap<AString, ACell> root = (AMap<AString, ACell>) frames.get(0);
			return frames.assoc(0, GoalTreeContext.compactFrame(root, summary));
		})) {
			throw new JobFailedException("Session cycle was superseded while compacting conversation");
		}
		toolCtx.frames = toolCtx.store.frames();
	}

	/**
	 * Per-cycle harness registry. Completion becomes terminal only after the
	 * lifecycle venue operation succeeds; a rejected completion remains
	 * retryable and does not fence later calls in the provider batch.
	 */
	private ToolCycleEngine.Registry<ToolContext> toolRegistry() {
		return new ToolCycleEngine.Registry<ToolContext>()
			.activityLabels((name, toolCtx) -> toolCtx.activityLabel(name))
			.register(TaskTools.COMPLETE, (call, toolCtx) -> toolCtx.tasks.complete(call, toolCtx.turnText))
			.register(TaskTools.FAIL, (call, toolCtx) -> toolCtx.tasks.fail(call, toolCtx.turnText))
			.register(HarnessTools.CONTEXT_LOAD, this::handleContextLoad)
			.register(HarnessTools.CONTEXT_UNLOAD, this::handleContextUnload)
			.register(HarnessTools.SKILL_LOAD, this::handleSkillLoad)
			.register(HarnessTools.MORE_TOOLS, this::handleMoreTools)
			.register(HarnessTools.INVOKE_TOOL, this::handleInvokeTool)
			.register(HarnessTools.COMPACT, this::handleCompact)
			.fallback((call, toolCtx) -> ToolCycleEngine.ToolOutcome.result(
				dispatchActiveTool(call.name(), call.input(), toolCtx)));
	}

	private ACell dispatchActiveTool(String name, ACell input, ToolContext toolCtx) {
		return dispatchTool(name, input, toolCtx.operation(name),
			toolCtx.ctx, toolCtx.toolCallTimeoutMs);
	}

	// ========== Built-in tool execution ==========

	// ========== Built-in context tools ==========

	ACell handleContextLoad(ACell input, ToolContext toolCtx) {
		HarnessTools.LoadScope scope = loadScope(toolCtx,
			"Error: no session in scope — context loads are per-conversation (#142)");
		ACell result = HarnessTools.contextLoad(input, scope);
		toolCtx.loads = scope.loads;
		return result;
	}

	private ToolCycleEngine.ToolOutcome handleContextLoad(
			ToolCycleEngine.ToolCall call, ToolContext toolCtx) {
		AMap<AString, ACell> before = toolCtx.loads;
		ACell result = handleContextLoad(call.input(), toolCtx);
		return loadedOutcome(call, toolCtx, before, result);
	}

	/**
	 * {@code skill_load} — thin glue only: the adapter checks the tier is
	 * writable, delegates ALL skill semantics to {@link Skills#load}, and
	 * writes the returned entry into the loads tier exactly as
	 * {@code context_load} does. Rendering and tool activation then follow
	 * from the entry via the generic context assembly (ContextAssembler), so
	 * this runtime carries no knowledge of what a skill IS.
	 */
	ACell handleSkillLoad(ACell input, ToolContext toolCtx) {
		HarnessTools.LoadScope scope = loadScope(toolCtx,
			"Error: no session in scope — skill loads are per-conversation (#142)");
		ACell result = HarnessTools.skillLoad(input, scope);
		toolCtx.loads = scope.loads;
		return result;
	}

	private ToolCycleEngine.ToolOutcome handleSkillLoad(
			ToolCycleEngine.ToolCall call, ToolContext toolCtx) {
		AMap<AString, ACell> before = toolCtx.loads;
		ACell result = handleSkillLoad(call.input(), toolCtx);
		return loadedOutcome(call, toolCtx, before, result);
	}

	private ToolCycleEngine.ToolOutcome loadedOutcome(ToolCycleEngine.ToolCall call,
			ToolContext toolCtx, AMap<AString, ACell> before, ACell result) {
		AString key = RT.ensureString(RT.getIn(result, K_PATH));
		if (key == null || before.equals(toolCtx.loads)) {
			return ToolCycleEngine.ToolOutcome.result(result);
		}
		AString eventId = ContextAssembler.contextEventId(call.id(), call.iteration(), key);
		Loads.Snapshot beforeSnapshot = loadSnapshot(toolCtx, before);
		Loads.Append appended = Loads.append(
			engine, toolCtx.ctx, toolCtx.loads, key, toolCtx.labels, eventId);
		toolCtx.loads = appended.loads();
		Loads.Snapshot afterSnapshot = loadSnapshot(toolCtx, toolCtx.loads);
		toolCtx.adoptLoadSnapshot(afterSnapshot);
		AVector<ACell> events = (AVector<ACell>) appended.messages().concat(
			HarnessTools.toolStateEvent(beforeSnapshot, afterSnapshot));
		return ToolCycleEngine.ToolOutcome.result(result, events);
	}

	ACell handleContextUnload(ACell input, ToolContext toolCtx) {
		HarnessTools.LoadScope scope = loadScope(toolCtx,
			"Error: no session in scope — context loads are per-conversation (#142)");
		ACell result = HarnessTools.contextUnload(input, scope);
		toolCtx.loads = scope.loads;
		return result;
	}

	private ToolCycleEngine.ToolOutcome handleContextUnload(
			ToolCycleEngine.ToolCall call, ToolContext toolCtx) {
		AMap<AString, ACell> before = toolCtx.loads;
		Loads.Snapshot beforeSnapshot = loadSnapshot(toolCtx, before);
		ACell result = handleContextUnload(call.input(), toolCtx);
		Loads.Snapshot afterSnapshot = loadSnapshot(toolCtx, toolCtx.loads);
		toolCtx.adoptLoadSnapshot(afterSnapshot);
		return ToolCycleEngine.ToolOutcome.result(result,
			HarnessTools.toolStateEvent(beforeSnapshot, afterSnapshot));
	}

	private ToolCycleEngine.ToolOutcome handleMoreTools(
			ToolCycleEngine.ToolCall call, ToolContext toolCtx) {
		AMap<AString, ACell> before = toolCtx.loads;
		Loads.Snapshot active = loadSnapshot(toolCtx, before);
		HarnessTools.LoadScope scope = loadScope(toolCtx,
			"Error: no session in scope — tool loads are per-conversation");
		ACell result = HarnessTools.moreTools(call.input(), scope,
			name -> toolCtx.containsFixedName(name)
				|| active.toolIndex().containsKey(Strings.create(name)));
		toolCtx.loads = scope.loads;
		return loadedOutcome(call, toolCtx, before, result);
	}

	private ToolCycleEngine.ToolOutcome handleInvokeTool(
			ToolCycleEngine.ToolCall call, ToolContext toolCtx) {
		HarnessTools.Invocation invocation = HarnessTools.invocation(call.input());
		if (invocation.error() != null) return ToolCycleEngine.ToolOutcome.result(invocation.error());
		return ToolCycleEngine.ToolOutcome.result(
			dispatchActiveTool(invocation.name(), invocation.input(), toolCtx));
	}

	private ToolCycleEngine.ToolOutcome handleCompact(
			ToolCycleEngine.ToolCall call, ToolContext toolCtx) {
		HarnessTools.Compaction compact = HarnessTools.compaction(call.input());
		if (compact.error() != null) return ToolCycleEngine.ToolOutcome.result(compact.error());
		AMap<AString, ACell> root = toolCtx.frames.isEmpty()
			? null : RT.ensureMap(toolCtx.frames.get(0));
		long turns = (root != null) ? GoalTreeContext.countLiveTurns(root) : 0;
		toolCtx.pendingCompactSummary = compact.summary();
		return ToolCycleEngine.ToolOutcome.result(Strings.create(
			"Compacted " + turns + " turns into an archived summary. Context freed."));
	}

	private Loads.Snapshot loadSnapshot(ToolContext toolCtx, AMap<AString, ACell> loads) {
		boolean resolvePinned = !toolCtx.cachePrefix || ContextAssembler.rendered(
			toolCtx.frames, toolCtx.sourceConfig, true) == null;
		return Loads.describe(engine, toolCtx.ctx,
			ContextChain.effective(toolCtx.outerLoads, loads),
			toolCtx::excludesLoadName, resolvePinned);
	}

	private HarnessTools.LoadScope loadScope(ToolContext toolCtx, String unavailableMessage) {
		return new HarnessTools.LoadScope(engine, toolCtx.ctx, toolCtx.loads,
			toolCtx.outerLoads, toolCtx.sessionInScope, unavailableMessage,
			toolCtx.skillSources);
	}

	/** @see TaskTools#renderTaskText */
	static String renderTaskText(ACell input) {
		return TaskTools.renderTaskText(input);
	}

	/**
	 * Fallback recognition of control tools emitted as plain TEXT (#215):
	 * smaller models frequently write {@code complete_task {"result": ...}} /
	 * {@code fail_task {"error": ...}} as assistant text even when the tools
	 * are offered structurally. Without recognition each such turn burns a
	 * loop iteration and the task never resolves.
	 *
	 * <p>Recognised only when the text starts with the tool name and the
	 * remainder is blank or a parseable JSON object — prose that merely
	 * mentions a tool name is left alone. A bare tool name gets empty
	 * arguments so the tool's own validation surfaces what's missing.</p>
	 *
	 * @return an assistant message carrying a synthetic toolCall (original
	 *         text preserved as content), or null when the text is not a
	 *         recognisable control-tool emission
	 */
	static AMap<AString, ACell> recogniseTextualControlCall(ACell assistantMsg, int iteration) {
		return ToolCycleEngine.recogniseTextualControlCall(assistantMsg, iteration,
			TaskTools.NAMES);
	}

	static AString getConfigValue(AMap<AString, ACell> config, AString key, AString defaultValue) {
		if (config == null) return defaultValue;
		AString val = RT.ensureString(config.get(key));
		return (val != null) ? val : defaultValue;
	}

	@SuppressWarnings("unchecked")
	private static AVector<ACell> concatTools(AVector<ACell> fixed, AVector<ACell> loads) {
		return (AVector<ACell>) fixed.concat(loads);
	}

	/**
	 * Extracts the JSON Schema from a responseFormat config, if present.
	 * responseFormat can be: "json" (no schema), "text" (no schema), or {name, schema} (has schema).
	 */
	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> getResponseFormatSchema(AMap<AString, ACell> config) {
		ACell rf = config.get(K_RESPONSE_FORMAT);
		if (rf instanceof AMap) {
			ACell schema = ((AMap<AString, ACell>) rf).get(Strings.intern("schema"));
			if (schema instanceof AMap) return (AMap<AString, ACell>) schema;
		}
		return null;
	}

	// ========== Tool context ==========

	/**
	 * Mutable context passed through the tool call loop for built-in tool state.
	 */
	static class ToolContext {
		final AString agentId;
		final RequestContext ctx;
		final TaskTools.Tasks tasks;
		final AVector<ACell> pending;
		final long toolCallTimeoutMs;
		AMap<AString, ACell> toolIndex;
		/** The innermost writable tier's loads (the session tier, #142). */
		AMap<AString, ACell> loads;
		/** Effective loads of the OUTER tiers (agent config.loads) — read-only environment. */
		AMap<AString, ACell> outerLoads = Maps.empty();
		/** Whether a session is in scope; without one there is no writable tier. */
		boolean sessionInScope = true;
		/** Text content of the assistant message whose tool batch is currently
		 *  executing — the fallback payload for an empty {@code complete_task}
		 *  (the dual of #215: control emitted as a tool, answer emitted as
		 *  text). Null when the turn carried no text. */
		AString turnText;
		AString labels = Labels.BRACKET;
		boolean cachePrefix = true;
		AMap<AString, ACell> sourceConfig;
		AVector<ACell> frames = Vectors.empty();
		FrameStore store;
		String pendingCompactSummary;
		/** Skill sources from {@code config.skills} + {@code config.skillsets} —
		 *  opaque to this runtime ({@link Skills} owns the semantics). */
		Skills.SkillSources skillSources = Skills.SkillSources.EMPTY;
		int baseToolStart;
		int baseToolEnd;
		private AMap<AString, ACell> currentLoadToolIndex = Maps.empty();
		private AMap<AString, ACell> currentPinnedToolIndex = Maps.empty();

		/**
		 * Tools contributed by the effective loads — the generic "a loads
		 * entry may declare tools" rule ({@link ToolPalette}).
		 * Projected for every inference from the loads' materialised bindings. The
		 * route set is replaced atomically, so unloading a source retracts both
		 * its advertised definition and its name-to-operation dispatch route.
		 */
		AVector<ACell> loadTools(Engine engine) {
			return refreshLoadSnapshot(engine, Labels.BRACKET).tools();
		}

		Loads.Snapshot refreshLoadSnapshot(Engine engine, AString labels) {
			boolean materialiseLive = ContextAssembler.rendered(
				frames, sourceConfig, cachePrefix) == null;
			Loads.Snapshot snapshot = Loads.resolveForInference(engine, ctx,
				ContextChain.effective(outerLoads, loads), this::excludesLoadName, labels,
				materialiseLive);
			adoptLoadSnapshot(snapshot);
			return snapshot;
		}

		void adoptLoadSnapshot(Loads.Snapshot snapshot) {
			currentLoadToolIndex = snapshot.toolIndex();
			currentPinnedToolIndex = snapshot.pinnedToolIndex();
		}

		AString operation(String name) {
			AString operation = ToolPalette.operation(currentLoadToolIndex, name);
			return (operation != null) ? operation : ToolPalette.operation(toolIndex, name);
		}

		String activityLabel(String name) {
			AString operation = ToolPalette.operation(currentLoadToolIndex, name);
			return (operation != null) ? ToolPalette.labelFor(currentLoadToolIndex, name).toString()
				: ToolPalette.labelFor(toolIndex, name).toString();
		}

		boolean excludesLoadName(String name, AString owner) {
			return ToolPalette.excludesLoadName(
				toolIndex, HARNESS_TOOL_NAMES, name, owner);
		}

		boolean containsFixedName(String name) {
			return HARNESS_TOOL_NAMES.contains(name)
				|| (toolIndex != null && toolIndex.containsKey(Strings.create(name)));
		}

		ToolContext(AString agentId, RequestContext ctx, AVector<ACell> tasks, AVector<ACell> pending,
				AMap<AString, ACell> toolIndex, AMap<AString, ACell> loads) {
			// No live task boundary: the tasks resolve in preview, never reaching a job.
			this(agentId, ctx, new TaskTools.Tasks(null, ctx, tasks, DEFAULT_TOOL_CALL_TIMEOUT_MS, true),
				pending, loads, DEFAULT_TOOL_CALL_TIMEOUT_MS, toolIndex);
		}

		private ToolContext(AString agentId, RequestContext ctx, TaskTools.Tasks tasks, AVector<ACell> pending,
				AMap<AString, ACell> loads, long toolCallTimeoutMs,
				AMap<AString, ACell> toolIndex) {
			this.agentId = agentId;
			this.ctx = ctx;
			this.tasks = tasks;
			this.pending = pending;
			this.toolIndex = (toolIndex != null) ? toolIndex : Maps.empty();
			this.loads = (loads != null) ? loads : Maps.empty();
			this.toolCallTimeoutMs = toolCallTimeoutMs;
		}

		void addLoad(AString path, AMap<AString, ACell> meta) {
			loads = loads.assoc(path, meta);
		}

		AMap<AString, ACell> getLoads() {
			return loads;
		}
	}
}
