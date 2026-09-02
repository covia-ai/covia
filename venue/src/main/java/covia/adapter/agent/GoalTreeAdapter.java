package covia.adapter.agent;

import java.util.Map;
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
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.adapter.agent.ContextInspectable.Inspection;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.AgentState;
import covia.venue.RequestContext;

/**
 * Goal tree agent adapter — Level 2 in the three-level architecture.
 *
 * <p>A more powerful variant of {@code LLMAgentAdapter} that adds hierarchical
 * goal decomposition via a frame stack. Agents call {@code subgoal} to bracket
 * sub-goals, {@code complete}/{@code fail} to return results, and
 * {@code compact} to checkpoint long conversations.</p>
 *
 * <p>Registered as operation {@code goaltree:chat}. Selected via agent config:</p>
 * <pre>{@code {"operation": "v/ops/goaltree/chat"}}</pre>
 *
 * <p>A session owns one persistent root frame. Each transition appends its
 * incoming work to that frame; child frames are pushed temporarily for
 * subgoals. For sessioned runs every mutation is written live to the lattice
 * through an epoch-fenced frame store.</p>
 *
 * <h3>Why this design exists — read before "simplifying"</h3>
 *
 * <p>The frame stack is <b>not</b> just a decomposition convenience. It is the
 * mechanism that makes long-running agents tractable: at every inference, the
 * <i>active</i> frame's conversation is rendered in full while ancestor frames
 * are progressively summarised at decreasing byte budgets (parent ~300B,
 * grandparent ~150B, great-grandparent ~80B; see
 * {@link GoalTreeContext#renderAncestors}). A 50-turn child call costs the same
 * as a 5-turn child call from the parent's perspective on the next turn —
 * the grandparent stays small even as descendants explode.</p>
 *
 * <p>Without this progressive ancestor rendering, the design collapses to a
 * flat agent and `subgoal` becomes purely cosmetic. Don't remove the ancestor
 * pass in {@link GoalTreeContext#renderAncestors} or the active-frame render
 * in {@link #runFrame} thinking they're redundant — together they are the
 * value proposition. {@link ContextAssembler} renders the frame stack the same
 * way for the live driver and for inspection. {@code compact} is the in-frame
 * analogue (live turns → single summary segment) for keeping the active frame
 * itself bounded.</p>
 *
 * <p>Full design: {@code venue/docs/GOAL_TREE.md} — especially §"Context
 * Assembly".</p>
 *
 * @see GoalTreeContext for frame data model and context rendering (pure functions)
 * @see AbstractLLMAdapter for shared L3 invocation and tool dispatch
 */
public class GoalTreeAdapter extends AbstractLLMAdapter implements FramesOwning {

	private static final Logger log = LoggerFactory.getLogger(GoalTreeAdapter.class);

	// ========== Harness tool names ==========

	/** Maximum tool call loop iterations per frame */
	static final int MAX_ITERATIONS = 50;

	/**
	 * Maximum subgoal nesting depth. Each {@code subgoal} call recurses into a
	 * child frame, and each frame can run up to {@link #MAX_ITERATIONS} LLM
	 * calls — unbounded nesting risks many hours of work and deep stack growth.
	 * At this depth the harness refuses further decomposition and the model must
	 * make progress (complete/fail) at the current level.
	 */
	static final int MAX_SUBGOAL_DEPTH = 10;

	/** Live turn count above which the auto-compact nudge fires */
	static final int AUTO_COMPACT_THRESHOLD = 20;

	static final String TOOL_SUBGOAL        = "subgoal";
	static final String TOOL_COMPLETE       = "complete";
	static final String TOOL_FAIL           = "fail";
	static final String TOOL_COMPACT        = HarnessTools.COMPACT;

	// ========== Harness tool definitions ==========

	private static final AString K_OUTPUTS     = Strings.intern("outputs");
	private static final AString K_SCHEMA      = Strings.intern("schema");
	private static final String HARNESS_BASE = "/adapters/goaltree/harness/";

	static final AMap<AString, ACell> TOOL_DEF_SUBGOAL =
		HarnessTools.definition(HARNESS_BASE + "subgoal.json");
	static final AMap<AString, ACell> TOOL_DEF_COMPLETE =
		HarnessTools.definition(HARNESS_BASE + "complete.json");
	static final AMap<AString, ACell> TOOL_DEF_FAIL =
		HarnessTools.definition(HARNESS_BASE + "fail.json");
	private static final AMap<AString, ACell> TOOL_DEF_TYPED_FAIL =
		HarnessTools.definition(HARNESS_BASE + "typedFail.json");

	/**
	 * Default schema for the {@code fail} tool's parameters when an agent has
	 * declared {@code outputs} but not specified a custom fail schema. Strict-
	 * compatible: every property is required, additionalProperties is false.
	 */
	@SuppressWarnings("unchecked")
	static final AMap<AString, ACell> DEFAULT_FAIL_SCHEMA =
		(AMap<AString, ACell>) TOOL_DEF_TYPED_FAIL.get(K_PARAMETERS);

	/*
	 * Untyped complete/fail: parameters are open objects — the LLM can pass
	 * any fields. The entire tool input becomes the result/error value.
	 *
	 * Because LLM provider APIs require tool arguments to be JSON objects,
	 * agents cannot return arrays or primitives directly. To return an array,
	 * wrap it: complete({items: [...]}).
	 */

	/**
	 * This runtime's harness registry: the tools every runtime shares
	 * ({@link HarnessTools#SHARED}) plus the goal-tree frame tools. Offered by
	 * the one rule ({@link HarnessTools#offered}): opt-in by name in
	 * {@code config.tools}; {@code skill_load} and {@code context_unload}
	 * implied by declared skills.
	 */
	static final Map<String, AMap<AString, ACell>> HARNESS_TOOL_REGISTRY;
	static {
		Map<String, AMap<AString, ACell>> m = new java.util.HashMap<>(HarnessTools.SHARED);
		m.put(TOOL_SUBGOAL, TOOL_DEF_SUBGOAL);
		m.put(TOOL_COMPLETE, TOOL_DEF_COMPLETE);
		m.put(TOOL_FAIL, TOOL_DEF_FAIL);
		HARNESS_TOOL_REGISTRY = Map.copyOf(m);
	}

	/** The harness tools this agent opted into, by the shared rule ({@link HarnessTools#offered}). */
	static AVector<ACell> resolveHarnessTools(AMap<AString, ACell> config) {
		return HarnessTools.offered(config, HARNESS_TOOL_REGISTRY);
	}

	/** Every bare name this runtime resolves itself: the goal-tree harness
	 *  tools plus the framework's task tools ({@link TaskTools}). */
	static final java.util.Set<String> HARNESS_NAMES;
	static {
		java.util.Set<String> names = new java.util.HashSet<>(HARNESS_TOOL_REGISTRY.keySet());
		names.addAll(TaskTools.NAMES);
		HARNESS_NAMES = java.util.Set.copyOf(names);
	}

	/** Everything a cycle holds constant across its frames and inferences;
	 *  each frame adds its index and fixed base-tool projection. */
	record Cycle(AMap<AString, ACell> config, AString llmOperation,
			AMap<AString, ACell> toolIndex,
			RequestContext ctx, ContextAssembler.Spec spec, AVector<ACell> typedRootHarnessTools,
			long toolCallTimeoutMs, AMap<AString, ACell> outerLoads, TaskTools.Tasks tasks,
			AVector<ACell> unavailable) {}

	/** The provider-fixed operation/skill part of a root frame's manifest. */
	private record FixedPalette(AVector<ACell> baseTools,
			AMap<AString, ACell> toolIndex, AVector<ACell> unavailable) {
		AVector<ACell> provenance() {
			return new ToolPalette.Palette(null, toolIndex, null).provenance();
		}
	}

	/** Returns true if the given tool name is a harness tool. */
	public static boolean isHarnessTool(String name) {
		return HARNESS_TOOL_REGISTRY.containsKey(name);
	}

	/** Head notice for child frames — stable for the life of the frame (AGENT_CONTEXT.md §5.1). */
	static final String CHILD_FRAME_NOTICE =
		"You are inside a subgoal. Complete the specific goal described below. "
		+ "When done, just respond with your answer — a plain text response "
		+ "returns your result to the parent. Only call complete() if you need "
		+ "to return structured data.";

	// ========== Adapter registration ==========

	@Override
	public String getName() { return "goaltree"; }

	@Override
	public String getDescription() {
		return "Goal tree agent adapter — hierarchical goal decomposition with "
			+ "subgoal/complete/fail/compact harness tools.";
	}

	@Override
	protected void installAssets() {
		installAsset("goaltree/chat", "/adapters/goaltree/chat.json");
	}

	/**
	 * Builds the exact L3 input that would be sent to the LLM on the first
	 * iteration of a fresh transition — same code path as {@code processGoal}
	 * + the first iteration of {@code runFrame}, minus the actual LLM call.
	 *
	 * <p>Returns the map that {@code invokeLevel3} would dispatch:
	 * {@code {messages, tools, model, ...}}. Used by {@code agent:context}
	 * for inspection.</p>
	 *
	 * @param recordConfig agent's record-level config
	 * @param state agent's state
	 * @param task optional task input (if non-null, synthesises the goal user message)
	 * @param ctx request context
	 * @return the L3 input map
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> buildFirstIterationL3Input(
			AMap<AString, ACell> recordConfig, ACell state, ACell task, RequestContext ctx) {
		return buildFirstIterationL3Input(recordConfig, state, task, null, ctx);
	}

	/** Session-aware variant: when {@code session} is non-null, the session's
	 *  root frame is rendered exactly as the live driver renders it (#211). */
	public AMap<AString, ACell> buildFirstIterationL3Input(
			AMap<AString, ACell> recordConfig, ACell state, ACell task,
			AMap<AString, ACell> session, RequestContext ctx) {
		ContextAssembler.Spec spec = inspectionContext(
			new Inspection(recordConfig, state, session, null, null, task), ctx).spec();
		return ContextAssembler.assemble(spec).toL3Input(spec.config());
	}

	/** What inspection and step share with a live cycle's root frame. */
	private record Preview(ContextAssembler.Spec spec, Cycle cycle, AVector<ACell> rootFrames,
			AVector<ACell> harness, AVector<ACell> baseTools, Loads.Snapshot loads,
			boolean typedOutputs, ContextAssembler.Diagnostics diagnostics) {}

	/**
	 * The first iteration of a transition with these inputs: typed outputs
	 * resolved, the root frame with this cycle's input appended as turns —
	 * inbox envelopes as chat turns, a task as the request turn — exactly as
	 * {@code processGoal} persists them, and the task rendered last with its
	 * tools offered. Pending results arrive in this runtime as session
	 * turns, so none are rendered separately.
	 */
	@SuppressWarnings("unchecked")
	private Preview preview(Inspection in, RequestContext ctx) {
		AMap<AString, ACell> config = in.config();
		AMap<AString, ACell> outputs = resolveOutputs(config);
		AMap<AString, ACell> completeSchema = outputsCompleteSchema(outputs);
		AMap<AString, ACell> l3Config = config;
		AVector<ACell> typedTools = null;
		if (completeSchema != null && config != null) {
			AMap<AString, ACell> responseFormat = Maps.of(
				Strings.create("name"), Strings.create("agent_output"),
				Strings.create("schema"), completeSchema);
			l3Config = config.assoc(K_RESPONSE_FORMAT, responseFormat);
			AMap<AString, ACell> failSchema = outputsFailSchema(outputs);
			typedTools = (AVector<ACell>) Vectors.of(
				(ACell) typedCompleteTool(completeSchema),
				(ACell) typedFailTool(failSchema));
		}
		// The applicability stamp is always the declarative agent config. The
		// responseFormat above is a deterministic invocation projection of its
		// configured outputs, not a second configuration generation.
		AMap<AString, ACell> sourceConfig = config;
		l3Config = effectiveModelConfig(l3Config, ctx);

		// Same scope-chain view as processGoal (agent config + root frame), so the inspected
		// skills index carries the right (loaded) markers.
		AMap<AString, ACell> configLoads = ContextChain.operatorLoads(
			RT.getIn(config, Fields.LOADS), "config.loads");
		AMap<AString, ACell> outerLoads = configLoads;
		AMap<AString, ACell> rootLoads = ContextChain.sessionRootLoads(in.session());
		AMap<AString, ACell> indexLoads = ContextChain.effective(outerLoads, rootLoads);
		AVector<ACell> sessionFrames = sessionFramesOf(in.session());
		AVector<ACell> rootFrames = Vectors.empty();
		if (sessionFrames != null && sessionFrames.count() > 0
				&& sessionFrames.get(0) instanceof AMap) {
			AMap<AString, ACell> rootFrame = (AMap<AString, ACell>) sessionFrames.get(0);
			rootFrames = Vectors.of((ACell) rootFrame);
		}

		// This cycle's input, appended as a live cycle appends it. A session
		// with no frame yet gets the root a first transition would create.
		AVector<ACell> inbox = envelopes(in.messages());
		AVector<ACell> tasks = (in.task() != null)
			? Vectors.of((ACell) Maps.of(Fields.JOB_ID, TaskTools.PREVIEW_JOB_ID, Fields.INPUT, in.task()))
			: null;
		if (rootFrames.isEmpty() && (inbox != null || tasks != null)) {
			rootFrames = Vectors.of((ACell) GoalTreeContext.createFrame(
				GoalTreeContext.describeTransitionInput(inbox, tasks, in.pending())));
		}
		if (!rootFrames.isEmpty()) {
			long ts = convex.core.util.Utils.getCurrentTimestamp();
			if (inbox != null) rootFrames = FrameStore.appendCycleInputTurns(rootFrames, inbox, null, ts);
			if (in.task() != null) {
				rootFrames = FrameStore.appendCycleInputTurns(
					rootFrames, null, Maps.of(Fields.NEW_INPUT, in.task()), ts);
			}
		}
		if (rootFrames.isEmpty()) {
			rootFrames = Vectors.of((ACell) GoalTreeContext.createFrame("", rootLoads));
		}
		rootFrames = GoalTreeContext.withRootLoads(rootFrames, rootLoads);

		// --- same as the first iteration of runFrame ---
		RequestContext capsCtx = capsContext(config, ctx);
		long toolCallTimeoutMs = resolveToolCallTimeoutMs(l3Config);
		TaskTools.Tasks taskTools = new TaskTools.Tasks(engine, capsCtx, tasks, toolCallTimeoutMs, true);
		AVector<ACell> harness = harnessForFrame(config, 0, typedTools);
		ModelProfile profile = modelProfileFor(l3Config, ctx);
		boolean cachePrefix = promptCaching(profile, l3Config);
		FixedPalette fixed = fixedPalette(sourceConfig, ctx, capsCtx,
			rootFrames, cachePrefix);
		AVector<ACell> baseTools = fixed.baseTools();
		AVector<ACell> fixedTools = (AVector<ACell>) TaskTools.DEFINITIONS.concat(harness).concat(baseTools);
		AMap<AString, ACell> frameLoads = rootFrames.isEmpty()
			? Maps.empty()
			: GoalTreeContext.getLoads((AMap<AString, ACell>) rootFrames.get(0));
		boolean materialiseLive = ContextAssembler.rendered(
			rootFrames, sourceConfig, cachePrefix) == null;
		Loads.Snapshot loads = Loads.resolveForInference(engine, capsCtx, indexLoads,
			(name, owner) -> ToolPalette.excludesLoadName(
				fixed.toolIndex(), HARNESS_NAMES, name, owner),
			profile.labels(), materialiseLive);

		// The loads ride in exactly as runFrame sets them — stable elements
		// through withLoads and watched values through the frame observation — so an inspected
		// context matches a live inference by construction. The pre-split
		// elements dropped every loads-derived exchange from inspection (#418).
		AVector<ACell> offered = concatTools(fixedTools, loads.tools());
		ContextAssembler.Spec spec = new ContextAssembler.Spec(
			engine, ctx, capsCtx, l3Config,
			ContextAssembler.sessionHex(RT.getIn(in.session(), Fields.ID)), null,
			profile.budget(), profile.labels(), profile.toolCalling(),
			offered, null, indexLoads,
			rootFrames, null, null, true, null, taskTools.message(), fixed.unavailable(), null, null)
			.withLoads(loads, offered, indexLoads)
			.withSourceConfig(sourceConfig)
			.withCachePrefix(cachePrefix);
		AMap<AString, ACell> observedRoot = GoalTreeContext.applyObservations(
			(AMap<AString, ACell>) rootFrames.get(0),
			ContextAssembler.observations(spec, loads),
			convex.core.util.Utils.getCurrentTimestamp());
		rootFrames = rootFrames.assoc(0, observedRoot);
		spec = spec.withFrames(rootFrames);
		Cycle cycle = new Cycle(l3Config, getLLMOperation(l3Config), fixed.toolIndex(),
			capsCtx, spec, typedTools, toolCallTimeoutMs, outerLoads, taskTools,
			fixed.unavailable());
		AVector<ACell> entries = Vectors.empty();
		if (profile.toolCalling()) {
			entries = (AVector<ACell>) ToolPalette.provenance(TaskTools.DEFINITIONS, "harness")
				.concat(ToolPalette.provenance(harness, "harness"))
				.concat(fixed.provenance())
				.concat(loads.toolProvenance());
		}
		ContextAssembler.Diagnostics diagnostics = new ContextAssembler.Diagnostics(
			entries, loads.diagnostics(), fixed.unavailable());
		return new Preview(spec, cycle, rootFrames, harness, baseTools, loads,
			typedTools != null, diagnostics);
	}

	@Override
	protected InspectionContext inspectionContext(Inspection in, RequestContext ctx) {
		Preview p = preview(in, ctx);
		return new InspectionContext(p.spec(), p.diagnostics());
	}

	/** The inbox as the envelopes a live cycle carries — a plain string
	 *  becomes {@code {message}}. Null when there is nothing. */
	private static AVector<ACell> envelopes(AVector<ACell> messages) {
		if (messages == null || messages.isEmpty()) return null;
		AVector<ACell> out = Vectors.empty();
		for (long i = 0; i < messages.count(); i++) {
			ACell m = messages.get(i);
			out = out.conj((m instanceof AMap) ? m : Maps.of(Fields.MESSAGE, m));
		}
		return out;
	}

	/** {@code subgoal} is the one harness tool a step cannot run. */
	static final String STEP_SUBGOAL_NOTE =
		"[not executed by agent:step] subgoal would push a child frame and call the model.";

	/**
	 * One iteration of the root frame on the supplied reply, over an
	 * in-memory frame store: the reply appended, text-as-control recognised
	 * as live, the batch dispatched through the frame's own registry — except
	 * that {@code subgoal} is not run, since it would start a child frame and
	 * call the model, and a task resolution never reaches a job — and the next
	 * prompt rebuilt as the frame loop rebuilds it, a requested compact
	 * applied first. A terminal call ends the cycle with its value, as live.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> stepContext(Inspection in, AMap<AString, ACell> assistant, RequestContext ctx) {
		Preview p = preview(in, ctx);
		Cycle cycle = p.cycle();
		AMap<AString, ACell> config = cycle.config();
		AVector<ACell> frames = p.rootFrames();
		if (frames.isEmpty()) {
			// A wake-up with nothing to act on: the root a first transition would create.
			frames = Vectors.of((ACell) GoalTreeContext.createFrame(
				GoalTreeContext.describeTransitionInput(null, null, null)));
		}
		AMap<AString, ACell> activeFrame = (AMap<AString, ACell>) frames.get(0);
		if (RT.ensureVector(activeFrame.get(GoalTreeContext.K_CONVERSATION)).isEmpty()) {
			AMap<AString, ACell> goalMsg = GoalTreeContext.renderGoal(activeFrame);
			if (goalMsg != null) activeFrame = GoalTreeContext.appendTurn(activeFrame, goalMsg);
		}

		FrameStore store = new FrameStore.LocalFrameStore(frames.assoc(0, activeFrame));
		FrameToolContext frameTools = new FrameToolContext(null, store, 0, p.baseTools(), cycle);
		frameTools.activeFrame = activeFrame;
		frameTools.adoptLoadSnapshot(p.loads());
		ToolCycleEngine.Registry<FrameToolContext> registry = frameTools.registry()
			.register(TOOL_SUBGOAL, (call, ignored) ->
				ToolCycleEngine.ToolOutcome.result(Strings.create(STEP_SUBGOAL_NOTE)));

		AMap<AString, ACell> reply = assistant;
		AVector<ACell> calls = RT.ensureVector(reply.get(K_TOOL_CALLS));
		if (calls == null) {
			AMap<AString, ACell> rewritten = ToolCycleEngine.recogniseTextualControlCall(
				reply, 0, controlNames(p.harness(), cycle, 0));
			if (rewritten != null) {
				reply = rewritten;
				calls = RT.ensureVector(reply.get(K_TOOL_CALLS));
			}
		}
		if (calls == null) {
			// A text reply completes the frame — unless typed outputs reject
			// it, in which case the loop asks again.
			AString content = RT.ensureString(reply.get(K_CONTENT));
			ACell value = content;
			if (p.typedOutputs() && content != null) {
				Completion completion = Completion.of(content, null, rootSchema(config), null);
				if (!completion.accepted()) {
					AMap<AString, ACell> retry = retryTurn(completion);
					frameTools.activeFrame = GoalTreeContext.appendTurn(
						GoalTreeContext.appendTurn(activeFrame, reply), retry);
					AMap<AString, ACell> retryFrame = frameTools.activeFrame;
					store.update(current -> current.assoc(0, retryFrame));
					ContextAssembler.Spec next = inferenceSpec(frameTools, p.harness(), config,
						Vectors.of((ACell) frameTools.activeFrame));
					return new Step(reply, Vectors.of((ACell) reply, (ACell) retry),
						null, null, null, null, next).report();
				}
				value = completion.value();
			}
			return Step.done(reply, value).report();
		}

		frameTools.activeFrame = GoalTreeContext.appendTurn(activeFrame, reply);
		frameTools.turnText = RT.ensureString(reply.get(K_CONTENT));
		StepSink sink = new StepSink();
		ToolCycleEngine.BatchResult batch = ToolCycleEngine.executeBatch(
			calls, 0, registry, frameTools, sink, log);
		AVector<ACell> turns = Vectors.of((ACell) reply).concat(sink.turns());
		for (long i = 0; i < sink.turns().count(); i++) {
			frameTools.activeFrame = GoalTreeContext.appendTurn(
				frameTools.activeFrame, RT.ensureMap(sink.turns().get(i)));
		}
		if (batch.isTerminal()) {
			boolean failed = "failed".equals(batch.terminalStatus());
			turns = turns.conj(terminalAssistantMessage(batch.terminalValue(), failed));
			return new Step(reply, turns, sink, batch.terminalStatus(), batch.terminalValue(),
				batch.terminalValue(), null).report();
		}
		// The next iteration: a requested compact applied first, then the prompt rebuilt.
		if (frameTools.pendingCompactSummary != null) {
			frameTools.activeFrame = GoalTreeContext.compactFrame(
				frameTools.activeFrame, frameTools.pendingCompactSummary);
		}
		frameTools.compacted = frameTools.pendingCompactSummary != null;
		AMap<AString, ACell> nextFrame = frameTools.activeFrame;
		store.update(current -> current.assoc(0, nextFrame));
		ContextAssembler.Spec next = inferenceSpec(frameTools, p.harness(), config,
			Vectors.of((ACell) frameTools.activeFrame));
		return new Step(reply, turns, sink, null, null, null, next).report();
	}

	// ========== Invocation ==========

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		return CompletableFuture.supplyAsync(() -> processGoal(null, ctx, input), VIRTUAL_EXECUTOR);
	}

	/**
	 * Processes a single agent transition using the goal tree model.
	 *
	 * <p>Resumes the session's root frame (or creates a local root for an
	 * unsessioned invocation), runs the frame's tool loop, and returns the
	 * transition output ({@code {state, result}}).</p>
	 *
	 * @param ctx request context (caller identity, capabilities)
	 * @param input transition input: {@code {agentId, state, tasks, pending, messages, config, newInput, session?}}
	 * @return transition output: {@code {state, response | error}}
	 */
	ACell processGoal(Job job, RequestContext ctx, ACell input) {
		// The cycle record (#392) collects every inference and tool call of
		// the frame run below — subgoal recursion included, all on this
		// virtual thread — and rides out on the output, or on the failure
		// that ends the cycle.
		CycleRecord.begin(ctx.getCycle());
		try {
			return goal(job, ctx, input);
		} catch (RuntimeException e) {
			throw CycleRecord.Failure.of(e);
		}
	}

	@SuppressWarnings("unchecked")
	private ACell goal(Job job, RequestContext ctx, ACell input) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		ACell state = RT.getIn(input, AgentState.KEY_STATE);
		// S3c: prefer session.pending over agent-level messages when a session
		// is in scope. effectiveMessages picks the right one (no duplication).
		AVector<ACell> messages = covia.adapter.AgentAdapter.effectiveMessages(input);
		AVector<ACell> tasks = (AVector<ACell>) RT.getIn(input, Fields.TASKS);
		AVector<ACell> pending = (AVector<ACell>) RT.getIn(input, Fields.PENDING);

		AMap<AString, ACell> recordConfig = (RT.getIn(input, AgentState.KEY_CONFIG) instanceof AMap m) ? m : null;
		AMap<AString, ACell> config = recordConfig;

		// Resolve the configured root-frame output contract. When a schema is in
		// effect, the framework supports BOTH
		// completion paths to maximise provider compatibility:
		//   1. response_format with the schema — OpenAI/Gemini/Mistral/etc.
		//      enforce conformance server-side on the assistant's text
		//      response. This is the preferred path where supported.
		//   2. Typed complete/fail tools with the schema as parameters —
		//      works on Anthropic and other providers without response_format
		//      JSON schema support. The LLM calls complete(...) and the
		//      harness extracts the args as the result.
		// The agent author chooses how to coach the LLM via the system prompt;
		// the framework wires up both mechanisms. A requester's responseSchema
		// is deliberately not folded into either: it is session input, rendered
		// in the outstanding-task turn and enforced by TaskTools / the common
		// completion seam without changing this frame's persistent prefix.
		//
		// Provider handling lives in the ADAPTER (#81): LangChainAdapter
		// suppresses response_format for providers without native schema
		// support and realises it via forced tool calling instead, converting
		// the output-tool call back into schema-conformant text. This harness
		// stays provider-blind — flipping llmOperation between providers
		// changes nothing here.
		AMap<AString, ACell> outputs = resolveOutputs(config);
		AMap<AString, ACell> configuredSchema = outputsCompleteSchema(outputs);
		boolean typedOutputs = (configuredSchema != null);

		AMap<AString, ACell> l3Config = config;
		if (typedOutputs && config != null) {
			AMap<AString, ACell> responseFormat = Maps.of(
				Strings.create("name"), Strings.create("agent_output"),
				Strings.create("schema"), configuredSchema);
			l3Config = config.assoc(K_RESPONSE_FORMAT, responseFormat);
		}
		AMap<AString, ACell> sourceConfig = config;
		l3Config = effectiveModelConfig(l3Config, ctx);

		// Build typed root-frame tools only from the stable configured contract.
		AVector<ACell> typedHarnessTools = null;
		if (typedOutputs) {
			AMap<AString, ACell> failSchema = outputsFailSchema(outputs);
			typedHarnessTools = (AVector<ACell>) Vectors.of(
				(ACell) typedCompleteTool(configuredSchema),
				(ACell) typedFailTool(failSchema));
		}

		// Generate root goal description from incoming work. Only used when
		// the session has no frames yet (first transition).
		String rootDescription = GoalTreeContext.describeTransitionInput(messages, tasks, pending);

		long cycleTs = convex.core.util.Utils.getCurrentTimestamp();
		FrameStore.Opened opened = FrameStore.open(engine, ctx, agentId, input,
			messages, rootDescription, cycleTs,
			CVMBool.TRUE.equals(RT.getIn(recordConfig, Strings.intern("recordCaller"))), log);
		if (opened.failed()) {
			return Maps.of(AgentState.KEY_STATE, Maps.empty(), Fields.ERROR, opened.error());
		}
		FrameStore store = opened.store();
		boolean tidyInterrupted = opened.interrupted();
		AVector<ACell> frames = store.frames();
		// The shared context is always built from the ROOT view of the stack —
		// exactly what every pre-cutover cycle saw (clean cycles end
		// root-only). An interrupted stack may still hold child frames; the
		// cleanup driver settles them without execution. Rendering a child's conversation into
		// the shared history here would leak it into the root's context.
		if (frames.count() > 1) {
			frames = (AVector<ACell>) frames.slice(0, 1);
		}

		// The operator-pinned config tier is constant within a cycle; each frame
		// composes its own durable loads on top (inner shadows/masks outer).
		AMap<AString, ACell> outerLoads = ContextChain.operatorLoads(
			RT.getIn(recordConfig, Fields.LOADS), "config.loads");

		// Authority, palette and the cycle's Spec. Harness tool names in
		// config.tools are skipped by the palette — they're resolved separately
		// by resolveHarnessTools / buildTypedRootHarnessTools.
		RequestContext capsCtx = capsContext(recordConfig, ctx).withAgentId(agentId);
		ModelProfile profile = modelProfileFor(l3Config, ctx);
		boolean cachePrefix = promptCaching(profile, l3Config);
		FixedPalette fixed = fixedPalette(sourceConfig, ctx, capsCtx,
			frames, cachePrefix);
		AVector<ACell> baseTools = fixed.baseTools();
		AString llmOperation = getLLMOperation(l3Config);

		// Everything the assembler needs that holds for the whole cycle; each
		// frame and inference supplies the rest (AGENT_CONTEXT.md §8).
		ContextAssembler.Spec cycleSpec = new ContextAssembler.Spec(
			engine, ctx, capsCtx, l3Config,
			ContextAssembler.sessionHex(RT.getIn(input, Fields.SESSION, Fields.ID)), null,
			profile.budget(), profile.labels(), profile.toolCalling(),
			null, null, null, null, null, null, true, null, null,
			fixed.unavailable(), null, null)
			.withSourceConfig(sourceConfig)
			.withCachePrefix(cachePrefix);

		// Per-tool-call timeout — bounds any single grid op invoked as a tool
		// so a stuck sub-job cannot hang this loop. Resolved once and shared
		// with subgoal recursion.
		long toolCallTimeoutMs = resolveToolCallTimeoutMs(l3Config);
		Cycle cycle = new Cycle(l3Config, llmOperation, fixed.toolIndex(), capsCtx, cycleSpec,
			typedHarnessTools, toolCallTimeoutMs, outerLoads,
			new TaskTools.Tasks(engine, capsCtx, tasks, toolCallTimeoutMs, false),
			fixed.unavailable());

		// Run the root frame. typedHarnessTools (if non-null) injects the
		// typed complete/fail tools alongside the regular harness/operation
		// tools, supporting providers that prefer tool calls over response_format.
		// An interrupted stack first settles child frames deepest-first without
		// resuming their internal execution, then starts the root afresh.
		FrameResult result = tidyInterrupted
			? settleInterruptedFrames(job, store, baseTools, cycle)
			: runFrame(job, store, 0, baseTools, cycle);

		// No per-adapter state is persisted here: the frame stack lives on the
		// session record, and config's single home is record.config (#144) —
		// the runtime reads config, never writes it.
		AMap<AString, ACell> newState = Maps.empty();

		// Lean transition output: emit {response | error}. When a task was
		// picked this cycle, complete it explicitly via the venue op
		// (agent:complete-task / agent:fail-task), which parks a completion
		// envelope into the framework's deferredCompletions map. The run
		// loop drains that map after mergeRunResult to build taskResults.
		//
		// Sessioned (lattice-resident) runs do NOT emit frames: the session
		// record is the single authoritative copy — every mutation already
		// landed live, and the FramesOwning marker keeps the framework's
		// merge out of frames entirely. Only the local-store paths
		// (unsessioned / direct-invoke) return the stack in the output,
		// where the caller is the only consumer.
		// A task the agent resolved itself (complete_task / fail_task) has
		// reached its job already; one still open when the root frame
		// completes takes the frame's outcome — in this runtime a reply is
		// the answer, where llmagent would yield (AGENT_SESSIONS.md §6.3).
		boolean failed = "failed".equals(result.status());
		if (tasks != null && tasks.count() > 0 && ctx.getTaskId() != null && !cycle.tasks().resolved()) {
			completeTaskViaVenueOp(ctx, failed, result.value());
		}
		AMap<AString, ACell> output = Maps.of(AgentState.KEY_STATE, newState);
		if (store instanceof FrameStore.LocalFrameStore && result.frames() != null) {
			output = output.assoc(Fields.FRAMES, result.frames());
		}
		if (failed) {
			output = output.assoc(Fields.ERROR, result.value());
		} else {
			output = output.assoc(Fields.RESPONSE, result.value());
		}
		// The cycle record and its token totals (#217: measured only; absent
		// means the provider reported nothing, never zero). Per-call usage
		// additionally rides each assistant turn in the frame conversation.
		CycleRecord.Result record = CycleRecord.end();
		output = output.assoc(Fields.CYCLE, record.cycle());
		if (record.tokens() != null) output = output.assoc(Fields.TOKENS, record.tokens());
		return output;
	}

	/**
	 * Completes (or fails) the in-scope task via the venue op. The framework
	 * passes a cycle ctx scoped with both agentId and taskId; the op reads
	 * those from the RequestContext, parks a completion envelope into the
	 * framework's deferred-completion map, and removes the task entry.
	 *
	 * <p>Failures (agent missing, task gone, op rejected) propagate up to
	 * {@link #processGoal}, which in turn propagates to {@link #invoke},
	 * which fails the transition Job. The framework's outer catch then
	 * fails the caller's pending task Job — without that, the caller would
	 * block on {@code awaitResult} forever.</p>
	 */
	private void completeTaskViaVenueOp(RequestContext ctx, boolean failed, ACell value) {
		AMap<AString, ACell> opInput;
		AString opPath;
		if (failed) {
			AString errorStr = (value instanceof AString s) ? s
				: Strings.create(value == null ? "Task failed" : value.toString());
			opInput = Maps.of(Fields.ERROR, errorStr);
			opPath = Strings.create("v/ops/agent/fail-task");
		} else {
			opInput = (value != null) ? Maps.of(Fields.RESULT, value) : Maps.empty();
			opPath = Strings.create("v/ops/agent/complete-task");
		}
		engine.jobs().invokeInternal(opPath, opInput, ctx).join();
	}

	// ========== Frame execution ==========

	/**
	 * Result of running a frame — either complete or failed.
	 *
	 * <p>{@code frames} always carries the final frame stack so the caller can
	 * emit it back to {@code mergeRunResult} as {@code Fields.FRAMES}. The
	 * session record on the lattice is the sole post-mortem — there is no
	 * separate failure snapshot.</p>
	 */
	record FrameResult(String status, ACell value, AVector<ACell> frames) {
		static FrameResult complete(ACell value, AVector<ACell> frames) {
			return new FrameResult("complete", value, frames);
		}
		static FrameResult failed(ACell error, AVector<ACell> frames) {
			return new FrameResult("failed", error, frames);
		}
	}

	/** Mutable policy state used by the shared tool-batch engine for one frame. */
	private final class FrameToolContext {
		final Job job;
		final FrameStore store;
		final int frameIndex;
		final Cycle cycle;
		final AMap<AString, ACell> config;
		final AString llmOperation;
		final RequestContext ctx;
		final ContextAssembler.Spec cycleSpec;
		final long toolCallTimeoutMs;
		final AMap<AString, ACell> outerLoads;
		final AVector<ACell> baseTools;
		AMap<AString, ACell> toolIndex;
		int baseToolStart;
		int baseToolEnd;
		AMap<AString, ACell> iterationToolIndex = Maps.empty();
		AMap<AString, ACell> pinnedToolIndex = Maps.empty();
		AMap<AString, ACell> activeFrame;
		String pendingCompactSummary;
		boolean compacted;
		AString turnText;

		FrameToolContext(Job job, FrameStore store, int frameIndex, AVector<ACell> baseTools, Cycle cycle) {
			this.job = job;
			this.store = store;
			this.frameIndex = frameIndex;
			this.baseTools = baseTools;
			this.cycle = cycle;
			this.config = cycle.config();
			this.llmOperation = cycle.llmOperation();
			this.toolIndex = cycle.toolIndex();
			this.ctx = cycle.ctx();
			this.cycleSpec = cycle.spec();
			this.toolCallTimeoutMs = cycle.toolCallTimeoutMs();
			this.outerLoads = cycle.outerLoads();
		}

		ToolCycleEngine.Registry<FrameToolContext> registry() {
			return new ToolCycleEngine.Registry<FrameToolContext>()
				.activityLabels((name, ignored) -> activityLabel(name))
				// The framework's task boundary (TaskTools): a task resolved
				// here reaches its job at tool time and ends the frame.
				.register(TaskTools.COMPLETE, (call, ignored) -> cycle.tasks().complete(call, turnText))
				.register(TaskTools.FAIL, (call, ignored) -> cycle.tasks().fail(call, turnText))
				.register(TOOL_COMPLETE, (call, ignored) -> complete(call, false))
				.register(TOOL_FAIL, (call, ignored) -> complete(call, true))
				.register(TOOL_COMPACT, (call, ignored) -> compact(call))
				.register(HarnessTools.CONTEXT_LOAD, (call, ignored) -> contextLoad(call))
				.register(HarnessTools.CONTEXT_UNLOAD, (call, ignored) -> contextUnload(call))
				.register(HarnessTools.SKILL_LOAD, (call, ignored) -> skillLoad(call))
				.register(HarnessTools.MORE_TOOLS, (call, ignored) -> moreTools(call))
				.register(HarnessTools.INVOKE_TOOL, (call, ignored) -> invokeTool(call))
				.register(TOOL_SUBGOAL, (call, ignored) -> subgoal(call))
				.fallback((call, ignored) -> ToolCycleEngine.ToolOutcome.result(
					dispatchActiveTool(call.name(), call.input())));
		}

		private String activityLabel(String name) {
			AString operation = ToolPalette.operation(iterationToolIndex, name);
			return (operation != null) ? ToolPalette.labelFor(iterationToolIndex, name).toString()
				: ToolPalette.labelFor(toolIndex, name).toString();
		}

		private void adoptLoadSnapshot(Loads.Snapshot snapshot) {
			iterationToolIndex = snapshot.toolIndex();
			pinnedToolIndex = snapshot.pinnedToolIndex();
		}

		private ACell dispatchActiveTool(String name, ACell input) {
			AString operation = ToolPalette.operation(iterationToolIndex, name);
			if (operation == null) operation = ToolPalette.operation(toolIndex, name);
			return dispatchTool(name, input, operation, ctx, toolCallTimeoutMs);
		}

		private boolean excludesLoadName(String name, AString owner) {
			return ToolPalette.excludesLoadName(
				toolIndex, HARNESS_NAMES, name, owner);
		}

		private boolean containsFixedName(String name) {
			return HARNESS_NAMES.contains(name)
				|| (toolIndex != null && toolIndex.containsKey(Strings.create(name)));
		}

		private ToolCycleEngine.ToolOutcome complete(
				ToolCycleEngine.ToolCall call, boolean failed) {
			// The contract in force: the root frame's declared output schema.
			// A failure reason and a child's result are free-form.
			AMap<AString, ACell> schema = (!failed && frameIndex == 0) ? rootSchema(config) : null;
			Completion completion = Completion.of(call.input(), turnText, schema, call.name());
			if (!completion.accepted()) return ToolCycleEngine.ToolOutcome.result(completion.toolError());
			ACell result = Maps.of(Strings.create("status"),
				Strings.create(failed ? "failed" : "complete"));
			return ToolCycleEngine.ToolOutcome.terminal(result,
				failed ? "failed" : "complete", completion.value());
		}

		private ToolCycleEngine.ToolOutcome compact(
				ToolCycleEngine.ToolCall call) {
			HarnessTools.Compaction compact = HarnessTools.compaction(call.input());
			if (compact.error() != null) return ToolCycleEngine.ToolOutcome.result(compact.error());
			long turnsBefore = GoalTreeContext.countLiveTurns(activeFrame);
			pendingCompactSummary = compact.summary();
			return ToolCycleEngine.ToolOutcome.result(Strings.create(
				"Compacted " + turnsBefore + " turns into segment. Context freed."));
		}

		private ToolCycleEngine.ToolOutcome contextLoad(
				ToolCycleEngine.ToolCall call) {
			HarnessTools.LoadScope scope = loadScope();
			AMap<AString, ACell> before = scope.loads;
			ACell result = HarnessTools.contextLoad(call.input(), scope);
			return loadedOutcome(call, scope, before, result);
		}

		private ToolCycleEngine.ToolOutcome contextUnload(
				ToolCycleEngine.ToolCall call) {
			HarnessTools.LoadScope scope = loadScope();
			Loads.Snapshot before = loadSnapshot(scope.loads);
			ACell result = HarnessTools.contextUnload(call.input(), scope);
			activeFrame = GoalTreeContext.withLoads(activeFrame, scope.loads);
			Loads.Snapshot after = loadSnapshot(scope.loads);
			adoptLoadSnapshot(after);
			return ToolCycleEngine.ToolOutcome.result(result,
				HarnessTools.toolStateEvent(before, after));
		}

		private ToolCycleEngine.ToolOutcome skillLoad(
				ToolCycleEngine.ToolCall call) {
			HarnessTools.LoadScope scope = loadScope();
			AMap<AString, ACell> before = scope.loads;
			ACell result = HarnessTools.skillLoad(call.input(), scope);
			return loadedOutcome(call, scope, before, result);
		}

		private ToolCycleEngine.ToolOutcome loadedOutcome(ToolCycleEngine.ToolCall call,
				HarnessTools.LoadScope scope, AMap<AString, ACell> before, ACell result) {
			AString key = RT.ensureString(RT.getIn(result, K_PATH));
			if (key != null && !before.equals(scope.loads)) {
				AString eventId = ContextAssembler.contextEventId(call.id(), call.iteration(), key);
				Loads.Snapshot beforeSnapshot = loadSnapshot(before);
				Loads.Append appended = Loads.append(
					engine, ctx, scope.loads, key, cycleSpec.labels(), eventId);
				scope.loads = appended.loads();
				activeFrame = GoalTreeContext.withLoads(activeFrame, scope.loads);
				Loads.Snapshot afterSnapshot = loadSnapshot(scope.loads);
				adoptLoadSnapshot(afterSnapshot);
				AVector<ACell> events = (AVector<ACell>) appended.messages().concat(
					HarnessTools.toolStateEvent(beforeSnapshot, afterSnapshot));
				return ToolCycleEngine.ToolOutcome.result(result, events);
			}
			activeFrame = GoalTreeContext.withLoads(activeFrame, scope.loads);
			return ToolCycleEngine.ToolOutcome.result(result);
		}

		private HarnessTools.LoadScope loadScope() {
			return new HarnessTools.LoadScope(engine, ctx,
				GoalTreeContext.getLoads(activeFrame), outerLoads, true, "", Skills.sourcesOf(config));
		}

		/** {@code more_tools}: create a durable tool-only load. */
		private ToolCycleEngine.ToolOutcome moreTools(ToolCycleEngine.ToolCall call) {
			HarnessTools.LoadScope scope = loadScope();
			AMap<AString, ACell> before = scope.loads;
			Loads.Snapshot active = loadSnapshot(before);
			ACell result = HarnessTools.moreTools(call.input(), scope,
				name -> containsFixedName(name)
					|| active.toolIndex().containsKey(Strings.create(name)));
			return loadedOutcome(call, scope, before, result);
		}

		private ToolCycleEngine.ToolOutcome invokeTool(ToolCycleEngine.ToolCall call) {
			HarnessTools.Invocation invocation = HarnessTools.invocation(call.input());
			if (invocation.error() != null) return ToolCycleEngine.ToolOutcome.result(invocation.error());
			return ToolCycleEngine.ToolOutcome.result(
				dispatchActiveTool(invocation.name(), invocation.input()));
		}

		private Loads.Snapshot loadSnapshot(AMap<AString, ACell> frameLoads) {
			boolean resolvePinned = !cycleSpec.cachePrefix()
				|| ContextAssembler.Rendered.fromCell(RT.getIn(
					activeFrame, GoalTreeContext.K_RENDERED_CONTEXT)) == null;
			return Loads.describe(engine, ctx,
				ContextChain.effective(outerLoads, frameLoads),
				this::excludesLoadName, resolvePinned);
		}

		@SuppressWarnings("unchecked")
		private ToolCycleEngine.ToolOutcome subgoal(
				ToolCycleEngine.ToolCall call) {
			AString description = RT.ensureString(RT.getIn(call.input(), Strings.create("description")));
			if (description == null || description.toString().isBlank()) {
				return ToolCycleEngine.ToolOutcome.result(Strings.create(
					"Error: description is required for a subgoal"));
			}
			log.info("Subgoal pushed: {}", description);
			if (frameIndex + 1 >= MAX_SUBGOAL_DEPTH) {
				return ToolCycleEngine.ToolOutcome.result(Maps.of(
					Strings.create("status"), Strings.create("error"),
					Strings.create("error"), Strings.create(
						"Maximum subgoal depth (" + MAX_SUBGOAL_DEPTH + ") reached. Complete or "
						+ "fail the current goal at this level instead of decomposing further.")));
			}

			AMap<AString, ACell> childLoads = GoalTreeContext.getLoads(activeFrame);
			AMap<AString, ACell> declared;
			try {
				ACell declaredCell = RT.getIn(call.input(), Fields.LOADS);
				declared = (declaredCell != null)
					? ContextChain.declaredLoads(declaredCell, "subgoal loads") : Maps.empty();
			} catch (IllegalArgumentException e) {
				return ToolCycleEngine.ToolOutcome.result(Strings.create("Error: " + e.getMessage()));
			}
			for (var entry : declared.entrySet()) {
				long budget = clampLoadBudget(RT.getIn(entry.getValue(), K_BUDGET));
				childLoads = childLoads.assoc(entry.getKey(), buildLoadEntryMeta(budget, null));
			}

			AMap<AString, ACell> childFrame = GoalTreeContext.createFrame(
				description.toString(), childLoads);
			if (call.id() != null) childFrame = childFrame.assoc(GoalTreeContext.K_CALL_ID, call.id());
			final AMap<AString, ACell> parentSnapshot = activeFrame;
			final AMap<AString, ACell> childToPush = childFrame;
			if (!store.update(frames ->
					updateFrame(frames, frameIndex, parentSnapshot).conj(childToPush))) {
				return ToolCycleEngine.ToolOutcome.abort();
			}

			// The child's exchange is recorded under this call (#392): the
			// frame is popped from the session when it completes, so the
			// cycle's entry is where its history lives.
			CycleRecord record = CycleRecord.current();
			if (record != null) record.openFrame();
			FrameResult childResult;
			try {
				childResult = runFrame(job, store, frameIndex + 1, baseTools, cycle);
			} finally {
				if (record != null) record.attachFrame(call.id(), record.closeFrame());
			}
			if (store.aborted()) return ToolCycleEngine.ToolOutcome.abort();

			AMap<AString, ACell> result = Maps.of(
				Strings.create("status"), Strings.create(childResult.status()));
			if (childResult.value() != null) {
				result = result.assoc(Strings.create("result"), childResult.value());
			}
			AMap<AString, ACell> withResult = GoalTreeContext.appendTurn(activeFrame,
				stampTs(toolResultMessage(call.id(), call.name(), result)));
			if (!store.update(frames -> updateFrame(
					(AVector<ACell>) frames.slice(0, frameIndex + 1), frameIndex, withResult))) {
				return ToolCycleEngine.ToolOutcome.abort();
			}
			activeFrame = withResult;
			return ToolCycleEngine.ToolOutcome.recorded(result);
		}
	}

	/**
	 * Runs a single frame's tool call loop. Recursively invokes child frames
	 * when subgoal is called.
	 *
	 * @param frameIndex index of the active frame
	 * @param baseToolsParam configured operation tools (grown by more_tools)
	 * @param cycle what holds for the whole cycle — config, operation, routes,
	 *        authority, Spec, typed root tools, timeout, outer loads, tasks.
	 *        Typed complete/fail tools apply at the root frame only, where the
	 *        L3 config ALSO carries response_format; children are free-form.
	 * @return the frame's result
	 */
	@SuppressWarnings("unchecked")
	FrameResult runFrame(Job job, FrameStore store, int frameIndex,
			AVector<ACell> baseToolsParam, Cycle cycle) {
		AMap<AString, ACell> config = cycle.config();
		AString llmOperation = cycle.llmOperation();
		RequestContext ctx = cycle.ctx();
		ContextAssembler.Spec cycleSpec = cycle.spec();
		AVector<ACell> typedRootHarnessTools = cycle.typedRootHarnessTools();

		// Fixed provider projection; later tools are represented by load events.
		AVector<ACell> baseTools = baseToolsParam;

		AVector<ACell> harnessForFrame = harnessForFrame(config, frameIndex, typedRootHarnessTools);
		// Typed outputs only at the root frame — children produce free-form
		// results back to their parent
		boolean typedOutputs = (frameIndex == 0 && typedRootHarnessTools != null);
		// Strip responseFormat from the L3 config for child frames so they
		// can produce arbitrary results back to the parent
		AMap<AString, ACell> frameL3Config = (frameIndex == 0)
			? config
			: (config != null ? config.dissoc(K_RESPONSE_FORMAT) : null);

		// Inject goal as first user message in the conversation (once, not every iteration)
		AVector<ACell> frames = store.frames();
		if (frameIndex >= frames.count()) {
			return FrameResult.failed(Strings.create(
				"Frame stack no longer holds frame " + frameIndex + " — cycle superseded"), frames);
		}
		AMap<AString, ACell> activeFrame = (AMap<AString, ACell>) frames.get(frameIndex);
		if (RT.ensureVector(activeFrame.get(GoalTreeContext.K_CONVERSATION)).isEmpty()) {
			AMap<AString, ACell> goalMsg = GoalTreeContext.renderGoal(activeFrame);
			if (goalMsg != null) {
				activeFrame = GoalTreeContext.appendTurn(activeFrame, goalMsg);
				if (!persist(store, frameIndex, activeFrame)) return abortedResult(store);
			}
		}

		// Deferred compact: applied at the start of the next iteration so we never
		// split an assistant message from its tool results (OpenAI requires every
		// tool result to follow its assistant tool_calls message)
		FrameToolContext frameTools = new FrameToolContext(job, store, frameIndex, baseTools, cycle);
		frameTools.activeFrame = activeFrame;
		ToolCycleEngine.Registry<FrameToolContext> toolRegistry = frameTools.registry();

		int maxIterations = (frameL3Config != null
				&& frameL3Config.get(K_MAX_TOOL_ITERATIONS) != null)
			? resolveMaxToolIterations(frameL3Config) : MAX_ITERATIONS;
		ToolCycleEngine.BatchSink sink = new ToolCycleEngine.BatchSink() {
			@Override
			public void append(AMap<AString, ACell> message) {
				frameTools.activeFrame = GoalTreeContext.appendTurn(
					frameTools.activeFrame, message);
			}
		};
		boolean retriedAfterTruncation = false;

		for (int iteration = 0; iteration < maxIterations; iteration++) {
			if (store.aborted()) return abortedResult(store);
			if (job != null && job.isFinished()) {
				return FrameResult.failed(Strings.create("Job cancelled"), store.frames());
			}

			AVector<ACell> currentFrames = store.frames();
			if (frameIndex >= currentFrames.count()) {
				return FrameResult.failed(Strings.create(
					"Frame stack no longer holds frame " + frameIndex
					+ " — cycle superseded"), currentFrames);
			}
			frameTools.activeFrame = (AMap<AString, ACell>) currentFrames.get(frameIndex);
			if (frameTools.pendingCompactSummary != null) {
				frameTools.activeFrame = GoalTreeContext.compactFrame(
					frameTools.activeFrame, frameTools.pendingCompactSummary);
				if (!persist(store, frameIndex, frameTools.activeFrame)) return abortedResult(store);
				frameTools.pendingCompactSummary = null;
				frameTools.compacted = true;
			}

			// A cached model retains its config-owned projection. An uncached
			// model renders it ephemerally and carries no projection in the frame.
			AVector<ACell> stack = (AVector<ACell>) currentFrames.slice(0, frameIndex + 1)
				.assoc(frameIndex, frameTools.activeFrame);
			ContextAssembler.Spec inference =
				inferenceSpec(frameTools, harnessForFrame, frameL3Config, stack);
			if (inference == null) return abortedResult(store);
			inference = prepareRendering(frameTools, inference);
			if (inference == null) return abortedResult(store);
			ContextAssembler.Prompt prompt = ContextAssembler.assemble(inference);

			ACell assistant = invokeLevel3(llmOperation, frameL3Config, prompt, ctx);
			if (isLengthLimited(assistant)) {
				if (retriedAfterTruncation) {
					log.warn("Frame[{}] response reached its output token limit again — failing the frame",
						frameIndex);
					AMap<AString, ACell> failed = GoalTreeContext.withStatus(
						frameTools.activeFrame, GoalTreeContext.STATUS_FAILED);
					if (!persist(store, frameIndex, failed)) return abortedResult(store);
					return FrameResult.failed(Strings.create(TRUNCATION_FAILURE_MESSAGE), store.frames());
				}
				retriedAfterTruncation = true;
				log.warn("Frame[{}] response reached its output token limit — retrying once without partial output",
					frameIndex);
				frameTools.activeFrame = GoalTreeContext.appendTurn(
					frameTools.activeFrame, stampTs(truncationRetryTurn()));
				if (!persist(store, frameIndex, frameTools.activeFrame)) return abortedResult(store);
				iteration--; // truncation recovery is not a tool iteration
				continue;
			}
			AVector<ACell> calls = RT.ensureVector(RT.getIn(assistant, K_TOOL_CALLS));
			boolean hasCalls = calls != null && calls.count() > 0;
			if (!hasCalls) {
				AMap<AString, ACell> rewritten = ToolCycleEngine.recogniseTextualControlCall(
					assistant, iteration, controlNames(harnessForFrame, cycle, frameIndex));
				if (rewritten != null) {
					log.warn("Assistant emitted a harness control tool as text — honouring it (#215)");
					assistant = rewritten;
					calls = RT.ensureVector(RT.getIn(assistant, K_TOOL_CALLS));
					hasCalls = true;
				}
			}

			if (!hasCalls) {
				AString content = RT.ensureString(RT.getIn(assistant, K_CONTENT));
				ACell value = content;
				if (typedOutputs && content != null) {
					// A typed reply is a completion: judged like complete(), and
					// asked again with the rejection when it does not conform.
					Completion completion = Completion.of(content, null, rootSchema(frameL3Config), null);
					if (!completion.accepted()) {
						log.warn("Frame[{}] iter={} typed output rejected: {}",
							frameIndex, iteration, completion.rejection());
						frameTools.activeFrame = GoalTreeContext.appendTurn(
							GoalTreeContext.appendTurn(frameTools.activeFrame, stampTs(assistant)),
							retryTurn(completion));
						if (!persist(store, frameIndex, frameTools.activeFrame)) return abortedResult(store);
						continue;
					}
					value = completion.value();
				}
				AMap<AString, ACell> terminal = GoalTreeContext.withStatus(
					GoalTreeContext.appendTurn(frameTools.activeFrame, stampTs(assistant)),
					GoalTreeContext.STATUS_COMPLETE);
				if (!persist(store, frameIndex, terminal)) return abortedResult(store);
				return FrameResult.complete(value, store.frames());
			}

			frameTools.activeFrame = GoalTreeContext.appendTurn(frameTools.activeFrame, stampTs(assistant));
			frameTools.turnText = RT.ensureString(RT.getIn(assistant, K_CONTENT));
			if (!persist(store, frameIndex, frameTools.activeFrame)) return abortedResult(store);
			log.info("Frame[{}] iter={} tools={}", frameIndex, iteration, calls.count());

			ToolCycleEngine.BatchResult batch = ToolCycleEngine.executeBatch(
				calls, iteration, toolRegistry, frameTools, sink, log);
			if (batch.isAborted()) return abortedResult(store);
			if (batch.isTerminal()) {
				boolean failed = "failed".equals(batch.terminalStatus());
				frameTools.activeFrame = GoalTreeContext.appendTurn(
					frameTools.activeFrame,
					terminalAssistantMessage(batch.terminalValue(), failed));
				AMap<AString, ACell> terminal = GoalTreeContext.withStatus(
					frameTools.activeFrame,
					failed ? GoalTreeContext.STATUS_FAILED : GoalTreeContext.STATUS_COMPLETE);
				if (!persist(store, frameIndex, terminal)) return abortedResult(store);
				return failed
					? FrameResult.failed(batch.terminalValue(), store.frames())
					: FrameResult.complete(batch.terminalValue(), store.frames());
			}
			if (!persist(store, frameIndex, frameTools.activeFrame)) return abortedResult(store);
		}

		log.warn("GoalTreeAdapter: max iterations reached for frame ({})", maxIterations);
		store.update(current -> (frameIndex < current.count())
			? updateFrame(current, frameIndex, GoalTreeContext.withStatus(
				(AMap<AString, ACell>) current.get(frameIndex),
				GoalTreeContext.STATUS_FAILED)) : current);
		return FrameResult.failed(Strings.create("Max iterations reached"), store.frames());
	}

	/**
	 * The harness tools a frame offers, from {@code config.tools}. Child
	 * frames exclude {@code subgoal} (prevents unnecessary nesting from
	 * smaller models). The root frame adds the typed complete/fail tools when
	 * typed outputs are active, deduplicated against any complete/fail in
	 * config.tools — typed wins. {@code skill_load} is offered automatically
	 * when the agent declares skill sources — same rule as llmagent; skills
	 * semantics live in {@link Skills} and the context assembly.
	 */
	@SuppressWarnings("unchecked")
	private static AVector<ACell> harnessForFrame(AMap<AString, ACell> config, int frameIndex,
			AVector<ACell> typedRootHarnessTools) {
		AVector<ACell> configHarness = resolveHarnessTools(config);
		AVector<ACell> harness;
		if (frameIndex == 0 && typedRootHarnessTools != null) {
			java.util.Set<String> typedNames = new java.util.HashSet<>();
			for (long i = 0; i < typedRootHarnessTools.count(); i++) {
				ACell n = RT.getIn(typedRootHarnessTools.get(i), K_NAME);
				if (n != null) typedNames.add(n.toString());
			}
			harness = typedRootHarnessTools;
			for (long i = 0; i < configHarness.count(); i++) {
				ACell n = RT.getIn(configHarness.get(i), K_NAME);
				if (n == null || !typedNames.contains(n.toString())) harness = harness.conj(configHarness.get(i));
			}
		} else if (frameIndex == 0) {
			harness = configHarness;
		} else {
			harness = Vectors.empty();
			for (long i = 0; i < configHarness.count(); i++) {
				ACell n = RT.getIn(configHarness.get(i), K_NAME);
				if (n != null && !TOOL_SUBGOAL.equals(n.toString())) harness = harness.conj(configHarness.get(i));
			}
		}
		return harness;
	}

	/**
	 * The Spec one inference of a frame assembles from — rebuilt before every
	 * call: appended persistent loads skipped, watched loads resolved under the
	 * agent's authority and atomically observed, the frame stack (ancestors compacted, the
	 * active frame in full), the compaction nudge once the frame has grown.
	 */
	@SuppressWarnings("unchecked")
	private ContextAssembler.Spec inferenceSpec(FrameToolContext frameTools,
			AVector<ACell> harnessForFrame, AMap<AString, ACell> frameL3Config, AVector<ACell> stack) {
		AMap<AString, ACell> frameLoads = GoalTreeContext.getLoads(frameTools.activeFrame);
		AMap<AString, ACell> effectiveLoads = ContextChain.effective(
			frameTools.outerLoads, frameLoads);
		// Task controls are a stable part of the root harness. Keeping them in
		// the initial vector avoids a palette rewrite when a task arrives.
		boolean root = frameTools.frameIndex == 0;
		AVector<ACell> taskTools = root ? TaskTools.DEFINITIONS : Vectors.empty();
		AVector<ACell> fixedTools = (AVector<ACell>) taskTools.concat(harnessForFrame).concat(frameTools.baseTools);
		frameTools.baseToolStart = Math.toIntExact(taskTools.count() + harnessForFrame.count());
		frameTools.baseToolEnd = Math.toIntExact(frameTools.baseToolStart + frameTools.baseTools.count());
		boolean materialiseLive = ContextAssembler.rendered(
			stack, frameTools.cycleSpec.sourceConfig(),
			frameTools.cycleSpec.cachePrefix()) == null;
		Loads.Snapshot loads = Loads.resolveForInference(engine, frameTools.ctx, effectiveLoads,
			frameTools::excludesLoadName, frameTools.cycleSpec.labels(), materialiseLive);
		frameTools.adoptLoadSnapshot(loads);
		long liveTurns = GoalTreeContext.countLiveTurns(frameTools.activeFrame);
		String notice = (liveTurns > AUTO_COMPACT_THRESHOLD && hasCompactTool(harnessForFrame))
			? "Your conversation has " + liveTurns
				+ " turns. Call compact(summary) now to free context space before continuing."
			: null;
		ContextAssembler.Spec base = frameTools.cycleSpec
			.forFrame(frameL3Config, (frameTools.frameIndex > 0) ? CHILD_FRAME_NOTICE : null)
			.withFrames(stack);
		if (frameTools.compacted) base = base.afterCompaction(stack);
		ContextAssembler.Spec inference = base
			.withLoads(loads, concatTools(fixedTools, loads.tools()), effectiveLoads)
			.withNotice(notice)
			.withTask(root ? frameTools.cycle.tasks().message() : null);
		if (!frameTools.store.observe(frameTools.frameIndex,
				ContextAssembler.observations(inference, loads),
				convex.core.util.Utils.getCurrentTimestamp())) return null;
		AVector<ACell> observedFrames = frameTools.store.frames();
		if (frameTools.frameIndex >= observedFrames.count()) return null;
		frameTools.activeFrame = (AMap<AString, ACell>) observedFrames.get(frameTools.frameIndex);
		AVector<ACell> observedStack = (AVector<ACell>) observedFrames
			.slice(0, frameTools.frameIndex + 1);
		return inference.withFrames(observedStack);
	}

	/** The control tools a text reply may spell out instead of calling
	 *  (#215): complete / fail when offered, and the task tools while a task
	 *  is outstanding at the root. */
	private static java.util.Set<String> controlNames(AVector<ACell> harness, Cycle cycle, int frameIndex) {
		java.util.Set<String> controls = new java.util.HashSet<>();
		if (hasToolNamed(harness, TOOL_COMPLETE)) controls.add(TOOL_COMPLETE);
		if (hasToolNamed(harness, TOOL_FAIL)) controls.add(TOOL_FAIL);
		if (frameIndex == 0 && cycle.tasks().outstanding()) controls.addAll(TaskTools.NAMES);
		return controls;
	}

	/** The contract in force at the root frame: the declared output schema
	 *  when typed outputs are active, else null. */
	private static AMap<AString, ACell> rootSchema(AMap<AString, ACell> frameL3Config) {
		return RT.ensureMap(RT.getIn(frameL3Config, K_RESPONSE_FORMAT, K_SCHEMA));
	}

	/** The turn that asks again after a rejected typed reply — the rejection
	 *  itself, so the model knows what to fix. */
	private static AMap<AString, ACell> retryTurn(Completion completion) {
		return Maps.of(K_ROLE, ROLE_USER, K_CONTENT, Strings.create(completion.rejection()));
	}

	/**
	 * Settles a non-quiescent frame stack, deepest child first, then runs the
	 * root. Each child is resolved and popped atomically (result recorded in
	 * the parent + child truncated, deduped by the child's {@code callId}):
	 *
	 * <ul>
	 *   <li>terminal child (status marker) — popped with its recorded outcome;
	 *       a text completion's value is recovered from its final turn, an
	 *       unrecoverable value becomes an honest "may be lost" note. Never
	 *       re-run (I8).</li>
	 *   <li>live child — popped un-run with an "interrupted — re-issue if
	 *       needed" failure. Internal execution is never resumed.</li>
	 * </ul>
	 */
	private FrameResult settleInterruptedFrames(Job job, FrameStore store,
			AVector<ACell> baseTools, Cycle cycle) {
		while (true) {
			if (store.aborted()) return abortedResult(store);
			AVector<ACell> fs = store.frames();
			int deepest = (int) fs.count() - 1;
			if (deepest <= 0) break;

			@SuppressWarnings("unchecked")
			AMap<AString, ACell> child = (AMap<AString, ACell>) fs.get(deepest);
			AString status = GoalTreeContext.getStatus(child);

			String popStatus;
			ACell popValue;
			if (status != null) {
				popStatus = GoalTreeContext.STATUS_COMPLETE.equals(status) ? "complete" : "failed";
				ACell recovered = GoalTreeContext.terminalValue(child);
				popValue = (recovered != null) ? recovered : Strings.create(
					"Result may be lost — venue restarted at subgoal completion; "
					+ "re-issue the subgoal if needed");
			} else {
				popStatus = "failed";
				popValue = Strings.create(
					"Subgoal interrupted — not resumed; re-issue if needed");
			}

			AMap<AString, ACell> resultMap = Maps.of(
				Strings.create("status"), Strings.create(popStatus));
			if (popValue != null) {
				resultMap = resultMap.assoc(Strings.create("result"), popValue);
			}
			final AString callId = GoalTreeContext.getCallId(child);
			final int parentIndex = deepest - 1;
			final AMap<AString, ACell> popResult = resultMap;
			boolean ok = store.update(f -> {
				if (f.count() <= parentIndex) return f;
				@SuppressWarnings("unchecked")
				AVector<ACell> truncated = (AVector<ACell>) f.slice(0, parentIndex + 1);
				@SuppressWarnings("unchecked")
				AMap<AString, ACell> parent = (AMap<AString, ACell>) truncated.get(parentIndex);
				if (callId == null || !GoalTreeContext.hasToolResultFor(parent, callId)) {
					parent = GoalTreeContext.appendTurn(parent,
						toolResultMessage(callId, TOOL_SUBGOAL, popResult));
				}
				return updateFrame(truncated, parentIndex, parent);
			});
			if (!ok) return abortedResult(store);
		}

		return runFrame(job, store, 0, baseTools, cycle);
	}

	/** Transition output for a cycle that lost frame ownership mid-flight. */
	private static AMap<AString, ACell> abortedOutput(FrameStore store) {
		FrameResult aborted = abortedResult(store);
		return Maps.of(
			AgentState.KEY_STATE, Maps.empty(),
			Fields.ERROR, aborted.value());
	}

	/** Resolves outside the lattice update, then atomically installs or removes
	 * the optional cache projection for this frame. */
	private ContextAssembler.Spec prepareRendering(FrameToolContext frameTools,
			ContextAssembler.Spec spec) {
		if (!ContextAssembler.renderingUpdateRequired(spec)) return spec;
		ContextAssembler.Rendered candidate = null;
		if (spec.cachePrefix()) {
			AMap<AString, ACell> fixedIndex = ToolPalette.mergeIndex(
				frameTools.toolIndex, frameTools.pinnedToolIndex);
			fixedIndex = ToolPalette.mergeIndex(fixedIndex,
				ToolPalette.loadOwners(frameTools.iterationToolIndex));
			AMap<AString, ACell> manifestIndex = new ToolPalette.Palette(
				null, fixedIndex, null).forManifest(spec.tools()).toolIndex();
			int baseStart = spec.toolCalling() ? frameTools.baseToolStart : 0;
			int baseEnd = spec.toolCalling() ? frameTools.baseToolEnd : 0;
			candidate = ContextAssembler.initialise(spec).withToolIndex(
				manifestIndex, baseStart, baseEnd);
			frameTools.toolIndex = manifestIndex;
		}
		AMap<AString, ACell> prepared = ContextAssembler.applyRendering(
			frameTools.activeFrame, spec, candidate);
		if (prepared != frameTools.activeFrame) {
			frameTools.activeFrame = prepared;
			if (!persist(frameTools.store, frameTools.frameIndex, prepared)) return null;
		}
		AVector<ACell> frames = frameTools.store.frames();
		if (frameTools.frameIndex >= frames.count()) return null;
		frameTools.activeFrame = (AMap<AString, ACell>) frames.get(frameTools.frameIndex);
		return spec.withFrames((AVector<ACell>) frames.slice(0, frameTools.frameIndex + 1));
	}

	/** Persists the active frame back into the stack; false = cycle superseded. */
	private static boolean persist(FrameStore store, int frameIndex, AMap<AString, ACell> activeFrame) {
		return store.replace(frameIndex, activeFrame);
	}

	/** The uniform give-up result when this cycle no longer owns the frames. */
	private static FrameResult abortedResult(FrameStore store) {
		return FrameResult.failed(Strings.create(
			"Cycle superseded — frames are no longer owned by this transition "
			+ "(cancelled, session removed, or reclaimed by a newer cycle)"), store.frames());
	}

	// ========== Helpers ==========

	/** Returns true if the tool set includes the compact tool. */
	private static boolean hasCompactTool(AVector<ACell> tools) {
		return hasToolNamed(tools, TOOL_COMPACT);
	}

	/** Returns true if the tool set includes a definition with the given name. */
	private static boolean hasToolNamed(AVector<ACell> tools, String toolName) {
		for (long i = 0; i < tools.count(); i++) {
			ACell tool = tools.get(i);
			if (tool instanceof AMap) {
				ACell name = ((AMap<?,?>) tool).get(K_NAME);
				if (name != null && toolName.equals(name.toString())) return true;
			}
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	private static AVector<ACell> concatTools(AVector<ACell> fixed, AVector<ACell> loads) {
		return (AVector<ACell>) fixed.concat(loads);
	}

	/**
	 * Builds a new root's fixed palette once, or recovers it from an existing
	 * root's exact persisted provider manifest. The latter avoids consulting a
	 * mutable skill catalog during ordinary inference.
	 */
	private FixedPalette fixedPalette(AMap<AString, ACell> config,
			RequestContext catalogCtx, RequestContext capsCtx,
			AVector<ACell> frames,
			boolean cachePrefix) {
		ContextAssembler.Rendered rendered = ContextAssembler.rendered(
			frames, config, cachePrefix);
		if (rendered != null) {
			return new FixedPalette(rendered.baseTools(), rendered.toolIndex(), Vectors.empty());
		}
		ToolPalette.Palette palette = ToolPalette.resolve(
			engine, catalogCtx, config, HARNESS_NAMES);
		ToolPalette.Palette declared = ToolPalette.declaredSkillTools(
			engine, catalogCtx, capsCtx, Skills.sourcesOf(config),
			name -> HARNESS_NAMES.contains(name) || palette.contains(name));
		ToolPalette.Palette base = palette.merge(declared);
		return new FixedPalette(base.tools(), base.toolIndex(), palette.unavailable());
	}

	/** Provider-neutral history projection for an explicit complete/fail call. */
	private static AMap<AString, ACell> terminalAssistantMessage(ACell value, boolean failed) {
		AString content;
		if (value instanceof AString s) {
			content = s;
		} else if (value == null) {
			content = Strings.EMPTY;
		} else {
			content = convex.core.util.JSON.print(value);
		}
		if (failed) content = Strings.create("Goal failed: " + content);
		return Maps.of(
			K_ROLE, ROLE_ASSISTANT,
			K_CONTENT, content,
			AgentState.K_SOURCE, AgentState.SOURCE_TRANSITION);
	}

	/** Updates a frame at the given index in the frame stack. */
	private static AVector<ACell> updateFrame(AVector<ACell> frames, int index, ACell frame) {
		return frames.assoc(index, frame);
	}

	/** True if config declares a responseFormat with a JSON schema (not just "json"/"text"). */
	private static boolean hasSchemaResponseFormat(AMap<AString, ACell> config) {
		if (config == null) return false;
		ACell rf = config.get(K_RESPONSE_FORMAT);
		if (!(rf instanceof AMap)) return false;
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> rfMap = (AMap<AString, ACell>) rf;
		return rfMap.get(Strings.create("schema")) instanceof AMap;
	}

	// ========== Typed outputs ==========

	/**
	 * Resolves the agent's typed outputs declaration. Returns the
	 * {@code outputs} map from config when present. Otherwise, when
	 * {@code responseFormat} declares a JSON schema, synthesises a
	 * shimmed outputs declaration so existing agents that only specify
	 * responseFormat get the typed-tool treatment too.
	 *
	 * <p>Returns null if neither outputs nor a schema-bearing responseFormat
	 * is declared — the agent uses the legacy untyped harness tools.</p>
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> resolveOutputs(AMap<AString, ACell> config) {
		if (config == null) return null;
		ACell explicit = config.get(K_OUTPUTS);
		if (explicit instanceof AMap) return (AMap<AString, ACell>) explicit;
		// Migration shim: lift responseFormat.schema into outputs.complete.schema
		if (hasSchemaResponseFormat(config)) {
			AMap<AString, ACell> rf = (AMap<AString, ACell>) config.get(K_RESPONSE_FORMAT);
			ACell schema = rf.get(K_SCHEMA);
			return Maps.of(
				Strings.create(TOOL_COMPLETE),
				Maps.of(K_SCHEMA, schema));
		}
		return null;
	}

	/** Pulls the {@code complete} schema from a resolved outputs map, or null. */
	@SuppressWarnings("unchecked")
	static AMap<AString, ACell> outputsCompleteSchema(AMap<AString, ACell> outputs) {
		if (outputs == null) return null;
		ACell entry = outputs.get(Strings.create(TOOL_COMPLETE));
		if (!(entry instanceof AMap)) return null;
		ACell schema = ((AMap<AString, ACell>) entry).get(K_SCHEMA);
		return (schema instanceof AMap) ? (AMap<AString, ACell>) schema : null;
	}

	/**
	 * Pulls the {@code fail} schema from a resolved outputs map. Falls back to
	 * {@link #DEFAULT_FAIL_SCHEMA} when outputs is set but no fail schema is
	 * declared, so typed agents always get a structured fail path.
	 */
	@SuppressWarnings("unchecked")
	static AMap<AString, ACell> outputsFailSchema(AMap<AString, ACell> outputs) {
		if (outputs == null) return null;
		ACell entry = outputs.get(Strings.create(TOOL_FAIL));
		if (entry instanceof AMap) {
			ACell schema = ((AMap<AString, ACell>) entry).get(K_SCHEMA);
			if (schema instanceof AMap) return (AMap<AString, ACell>) schema;
		}
		return DEFAULT_FAIL_SCHEMA;
	}

	/**
	 * Synthesises a typed {@code complete} tool whose parameters ARE the
	 * agent's declared output schema. OpenAI's strictTools mode enforces the
	 * schema at the API level — the LLM's tool call arguments must match.
	 * Flattened: {@code complete({field1: v1, field2: v2})} — no wrapper.
	 *
	 * <p><b>Protocol limitation:</b> LLM tool call arguments must be JSON
	 * objects (OpenAI, Anthropic, all major providers). This means agents
	 * cannot return arrays or primitives directly from {@code complete()} —
	 * they must wrap them in an object (e.g. {@code {items: [...]}}). This
	 * is an unfortunate constraint imposed by LLM provider APIs, not a
	 * design choice. If providers ever support non-object tool arguments,
	 * this restriction should be removed.</p>
	 */
	static AMap<AString, ACell> typedCompleteTool(AMap<AString, ACell> resultSchema) {
		return TOOL_DEF_COMPLETE.assoc(K_PARAMETERS, resultSchema);
	}

	/**
	 * Synthesises a typed {@code fail} tool whose parameters ARE the agent's
	 * declared fail schema (or {@link #DEFAULT_FAIL_SCHEMA}). Flattened:
	 * {@code fail({reason: "...", details: "..."})} — no wrapper.
	 */
	static AMap<AString, ACell> typedFailTool(AMap<AString, ACell> errorSchema) {
		return TOOL_DEF_TYPED_FAIL.assoc(K_PARAMETERS, errorSchema);
	}

	/**
	 * Builds the root-frame harness tool list for an agent with typed outputs.
	 *
	 * <p>Auto-injects typed {@code complete} and {@code fail} tools (with schema
	 * enforcement) regardless of config — these are required for the typed output
	 * model. Other optional tools (subgoal, compact, etc.) are included only if
	 * they appear in {@code config.tools}.</p>
	 *
	 * @param outputs resolved outputs declaration
	 * @param config agent config (scanned for optional harness tools)
	 * @return typed harness tools vector, or null if outputs has no complete schema
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> buildTypedRootHarnessTools(
			AMap<AString, ACell> outputs, AMap<AString, ACell> config) {
		AMap<AString, ACell> completeSchema = outputsCompleteSchema(outputs);
		if (completeSchema == null) return null; // no typing
		AMap<AString, ACell> failSchema = outputsFailSchema(outputs);

		// Start with typed complete/fail — always injected for typed outputs
		AVector<ACell> result = Vectors.of(
			(ACell) typedCompleteTool(completeSchema),
			(ACell) typedFailTool(failSchema));

		// Add optional harness tools from config (excluding complete/fail
		// which we already handled with typed versions)
		AVector<ACell> optional = resolveHarnessTools(config);
		for (long i = 0; i < optional.count(); i++) {
			ACell tool = optional.get(i);
			if (tool instanceof AMap) {
				ACell name = ((AMap<?,?>) tool).get(K_NAME);
				String n = (name != null) ? name.toString() : "";
				if (!TOOL_COMPLETE.equals(n) && !TOOL_FAIL.equals(n)) {
					result = result.conj(tool);
				}
			}
		}
		return result;
	}

}
