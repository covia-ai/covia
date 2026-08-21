package covia.adapter.agent;

import java.util.Map;
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
import convex.core.json.schema.JsonSchema;
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
	static final String TOOL_COMPACT        = "compact";
	static final String TOOL_CONTEXT_LOAD   = "context_load";
	static final String TOOL_CONTEXT_UNLOAD = "context_unload";
	static final String TOOL_MORE_TOOLS     = "more_tools";
	static final String TOOL_SKILL_LOAD     = "skill_load";

	// ========== Harness tool definitions ==========

	private static final AString K_OUTPUTS     = Strings.intern("outputs");
	private static final AString K_SCHEMA      = Strings.intern("schema");
	private static final AString K_ADDITIONAL_PROPERTIES = Strings.intern("additionalProperties");

	/**
	 * Default schema for the {@code fail} tool's parameters when an agent has
	 * declared {@code outputs} but not specified a custom fail schema. Strict-
	 * compatible: every property is required, additionalProperties is false.
	 */
	@SuppressWarnings("unchecked")
	static final AMap<AString, ACell> DEFAULT_FAIL_SCHEMA = Maps.of(
		K_TYPE, Strings.create("object"),
		K_PROPERTIES, Maps.of(
			Strings.create("reason"), Maps.of(
				K_TYPE, Strings.create("string"),
				K_DESCRIPTION, Strings.create("Brief explanation of why the goal failed")),
			Strings.create("details"), Maps.of(
				K_TYPE, Strings.create("string"),
				K_DESCRIPTION, Strings.create("Additional context: tool errors, missing data, partial work done"))),
		K_REQUIRED, Vectors.of((ACell) Strings.create("reason"), (ACell) Strings.create("details")),
		K_ADDITIONAL_PROPERTIES, convex.core.data.prim.CVMBool.FALSE);

	static final AMap<AString, ACell> TOOL_DEF_SUBGOAL = Maps.of(
		K_NAME, Strings.create(TOOL_SUBGOAL),
		K_DESCRIPTION, Strings.create(
			"Delegate a self-contained piece of work. Describe what you need done "
			+ "— a sub-agent will execute it independently and return the result. "
			+ "Use when your current goal has distinct parts you want done separately (e.g. "
			+ "'analyse vendor Acme' then 'analyse vendor Globex'). The sub-agent "
			+ "has access to all your tools and loaded data."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Strings.create("description"), Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create("What the subgoal should accomplish")),
				Strings.create("loads"), Maps.of(
					K_TYPE, Strings.create("object"),
					K_DESCRIPTION, Strings.create(
						"Optional context to pre-load for the sub-agent: a map of lattice "
						+ "path to {budget} (bytes). Added on top of your own loaded data, "
						+ "which the sub-agent inherits."))),
			K_REQUIRED, Vectors.of(Strings.create("description"))));

	/*
	 * Untyped complete/fail: parameters are open objects — the LLM can pass
	 * any fields. The entire tool input becomes the result/error value.
	 *
	 * Because LLM provider APIs require tool arguments to be JSON objects,
	 * agents cannot return arrays or primitives directly. To return an array,
	 * wrap it: complete({items: [...]}).
	 */

	static final AMap<AString, ACell> TOOL_DEF_COMPLETE = Maps.of(
		K_NAME, Strings.create(TOOL_COMPLETE),
		K_DESCRIPTION, Strings.create(
			"Finish your current goal and return the result. Only needed when "
			+ "your caller needs structured output. For text answers, just "
			+ "respond normally — that also completes the goal."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.empty()));

	static final AMap<AString, ACell> TOOL_DEF_FAIL = Maps.of(
		K_NAME, Strings.create(TOOL_FAIL),
		K_DESCRIPTION, Strings.create(
			"Report that your goal cannot be completed. Explain what went wrong "
			+ "so the caller can decide whether to retry, try a different approach, "
			+ "or give up."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.empty()));

	static final AMap<AString, ACell> TOOL_DEF_COMPACT = Maps.of(
		K_NAME, Strings.create(TOOL_COMPACT),
		K_DESCRIPTION, Strings.create(
			"Archive your conversation so far into a summary you write. Frees context "
			+ "space so you can continue working on long goals. Call this after completing "
			+ "a significant chunk of work when you still have more to do. Your summary "
			+ "should capture key findings and what remains."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Strings.create("summary"), Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create(
						"Your summary of the work done so far (required — only you know what matters)"))),
			K_REQUIRED, Vectors.of(Strings.create("summary"))));

	static final AMap<AString, ACell> TOOL_DEF_CONTEXT_LOAD = Maps.of(
		K_NAME, Strings.create(TOOL_CONTEXT_LOAD),
		K_DESCRIPTION, Strings.create(
			"Keep data from a workspace path visible in your context across turns. "
			+ "Use for rules, schemas, or reference material you need to consult "
			+ "repeatedly. The data is visible on the next model invocation and "
			+ "refreshed automatically thereafter. Subgoals "
			+ "inherit your loaded data. For data needed once, use an advertised "
			+ "bounded inspection or exact-read operation instead. Remove a loaded path "
			+ "with the context-removal control when finished."),
		K_PARAMETERS, CONTEXT_LOAD_PARAMS);

	static final AMap<AString, ACell> TOOL_DEF_CONTEXT_UNLOAD = Maps.of(
		K_NAME, Strings.create(TOOL_CONTEXT_UNLOAD),
		K_DESCRIPTION, Strings.create(
			"Remove a path previously added to persistent context. Pass the same "
			+ "path string you used when loading. Frees context space for other "
			+ "work or data."),
		K_PARAMETERS, CONTEXT_UNLOAD_PARAMS);

	static final AMap<AString, ACell> TOOL_DEF_MORE_TOOLS = Maps.of(
		K_NAME, Strings.create(TOOL_MORE_TOOLS),
		K_DESCRIPTION, Strings.create(
			"Add operations to your tool set for the rest of this run. "
			+ "Use an advertised catalog-listing operation to discover available operations first "
			+ "(for example, list path=v/ops), then call this with the exact paths "
			+ "you need. Added tools appear on your next turn."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Strings.create("operations"), Maps.of(
					K_TYPE, Strings.create("array"),
					K_DESCRIPTION, Strings.create("Operation paths to add as tools (e.g. [\"v/ops/agent/create\", \"v/ops/grid/run\"])"),
					Strings.create("items"), Maps.of(K_TYPE, Strings.create("string")))),
			K_REQUIRED, Vectors.of(Strings.create("operations"))));

	static final AMap<AString, ACell> TOOL_DEF_SKILL_LOAD = Maps.of(
		K_NAME, Strings.create(TOOL_SKILL_LOAD),
		K_DESCRIPTION, Strings.create(
			"Load a skill from the [Skills] index by name (or any skill by direct ref). "
			+ "The result includes the skill's full instructions for immediate use; they "
			+ "also stay in your context each turn until you remove the skill's loaded "
			+ "path. The skill's tools join your palette from your next step. Subgoals "
			+ "inherit your loaded skills."),
		K_PARAMETERS, SKILL_LOAD_PARAMS);

	/**
	 * Registry of all optional harness tool definitions by name.
	 * Agents opt into harness tools by listing their names in config.tools
	 * alongside regular operation paths. ({@code skill_load} is also offered
	 * automatically when the agent declares {@code config.skills}.)
	 */
	static final Map<String, AMap<AString, ACell>> HARNESS_TOOL_REGISTRY = Map.of(
		TOOL_SUBGOAL, TOOL_DEF_SUBGOAL,
		TOOL_COMPLETE, TOOL_DEF_COMPLETE,
		TOOL_FAIL, TOOL_DEF_FAIL,
		TOOL_COMPACT, TOOL_DEF_COMPACT,
		TOOL_CONTEXT_LOAD, TOOL_DEF_CONTEXT_LOAD,
		TOOL_CONTEXT_UNLOAD, TOOL_DEF_CONTEXT_UNLOAD,
		TOOL_MORE_TOOLS, TOOL_DEF_MORE_TOOLS,
		TOOL_SKILL_LOAD, TOOL_DEF_SKILL_LOAD);

	/**
	 * Resolves harness tools from the agent's config.tools list.
	 *
	 * <p>Scans config.tools for entries matching known harness tool names
	 * (subgoal, complete, fail, compact, context_load, context_unload,
	 * more_tools). Returns their definitions as a vector. Entries that don't
	 * match are ignored (they'll be resolved as operations by ToolPalette).</p>
	 *
	 * @param config agent config (may be null)
	 * @return vector of harness tool definitions found in config
	 */
	@SuppressWarnings("unchecked")
	static AVector<ACell> resolveHarnessTools(AMap<AString, ACell> config) {
		AVector<ACell> result = Vectors.empty();
		if (config == null) return result;
		ACell toolsCell = config.get(K_TOOLS);
		if (!(toolsCell instanceof AVector)) return result;

		AVector<ACell> toolsList = (AVector<ACell>) toolsCell;
		for (long i = 0; i < toolsList.count(); i++) {
			ACell entry = toolsList.get(i);
			if (entry instanceof AString s) {
				AMap<AString, ACell> def = HARNESS_TOOL_REGISTRY.get(s.toString());
				if (def != null) result = result.conj(def);
			}
		}
		return result;
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
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> buildFirstIterationL3Input(
			AMap<AString, ACell> recordConfig, ACell state, ACell task,
			AMap<AString, ACell> session, RequestContext ctx) {
		// --- same as processGoal ---
		AMap<AString, ACell> config = recordConfig;
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

		// Same scope-chain view as processGoal (agent + session tiers, plus the
		// root frame's tier when a session carries frames), so the inspected
		// skills index carries the right (loaded) markers.
		AMap<AString, ACell> configLoads = ContextChain.declaredLoads(
			RT.getIn(recordConfig, Fields.LOADS), "config.loads");
		ACell sessLoads = RT.getIn(session, Fields.LOADS);
		AMap<AString, ACell> sessionTier = (sessLoads instanceof AMap)
			? (AMap<AString, ACell>) sessLoads : null;
		AMap<AString, ACell> indexLoads = ContextChain.effective(configLoads, sessionTier);
		AVector<ACell> sessionFrames = sessionFramesOf(session);
		AVector<ACell> rootFrames = Vectors.empty();
		if (sessionFrames != null && sessionFrames.count() > 0
				&& sessionFrames.get(0) instanceof AMap) {
			AMap<AString, ACell> rootFrame = (AMap<AString, ACell>) sessionFrames.get(0);
			indexLoads = ContextChain.effective(indexLoads, GoalTreeContext.getLoads(rootFrame));
			rootFrames = Vectors.of((ACell) rootFrame);
		}

		// --- same as the first iteration of runFrame ---
		RequestContext capsCtx = capsContext(recordConfig, ctx);
		ToolPalette.Palette palette = ToolPalette.resolve(engine, ctx, recordConfig, HARNESS_TOOL_REGISTRY.keySet());
		AVector<ACell> harnessTools = resolveHarnessTools(config);
		AVector<ACell> fixedTools = (typedTools != null)
			? (AVector<ACell>) typedTools.concat(harnessTools).concat(palette.tools())
			: (AVector<ACell>) harnessTools.concat(palette.tools());
		ModelProfile profile = modelProfileFor(l3Config, ctx);
		Loads.Snapshot loads = Loads.resolve(engine, capsCtx, indexLoads,
			fixedToolNames(fixedTools), profile.labels());

		// The optional task is the goal the first iteration would see, rendered last.
		ACell goal = null;
		if (task != null) {
			AVector<ACell> tasks = Vectors.of((ACell) Maps.of(Strings.intern("input"), task));
			goal = ContextAssembler.user(GoalTreeContext.describeTransitionInput(null, tasks, null));
		}

		ContextAssembler.Spec spec = new ContextAssembler.Spec(
			engine, ctx, capsCtx, l3Config,
			ContextAssembler.sessionHex(RT.getIn(session, Fields.ID)), null,
			profile.budget(), profile.labels(),
			ToolPalette.merge(fixedTools, loads.tools()), loads.elements(), indexLoads,
			rootFrames, null, null, true, null, goal, palette.unavailable(), null, null);
		return ContextAssembler.assemble(spec).toL3Input(l3Config);
	}

	// ========== Invocation ==========

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		return CompletableFuture.supplyAsync(() -> processGoal(null, ctx, input), VIRTUAL_EXECUTOR);
	}

	/**
	 * Builds the first-iteration L3 input for {@code agent:context} inspection.
	 * Same pipeline as the first iteration of {@link #runFrame}: the same Spec,
	 * the same assembler, optional typed-output handling. Delegates
	 * to {@link #buildFirstIterationL3Input}, which is also exposed for tests
	 * that assert on the input directly (without going through the JSON render).
	 */
	@Override
	protected AMap<AString, ACell> buildInspectionInput(
			AMap<AString, ACell> recordConfig, ACell state, ACell taskInput,
			AMap<AString, ACell> session, RequestContext ctx) {
		return buildFirstIterationL3Input(recordConfig, state, taskInput, session, ctx);
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
	@SuppressWarnings("unchecked")
	ACell processGoal(Job job, RequestContext ctx, ACell input) {
		// Cycle-scoped token tally (#217): every invokeLevel3 in the frame
		// run below (tool iterations, subgoal recursion, compaction — all on
		// this virtual thread) adds its provider-reported usage; drained
		// into the transition output at the end.
		beginTokenTally();
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		ACell state = RT.getIn(input, AgentState.KEY_STATE);
		// S3c: prefer session.pending over agent-level messages when a session
		// is in scope. effectiveMessages picks the right one (no duplication).
		AVector<ACell> messages = covia.adapter.AgentAdapter.effectiveMessages(input);
		AVector<ACell> tasks = (AVector<ACell>) RT.getIn(input, Fields.TASKS);
		AVector<ACell> pending = (AVector<ACell>) RT.getIn(input, Fields.PENDING);

		AMap<AString, ACell> recordConfig = (RT.getIn(input, AgentState.KEY_CONFIG) instanceof AMap m) ? m : null;
		AMap<AString, ACell> config = recordConfig;

		// Resolve the response schema. Order: per-request responseSchema
		// (passed in agent_request) overrides the agent's config.outputs
		// default. When a schema is in effect, the framework supports BOTH
		// completion paths to maximise provider compatibility:
		//   1. response_format with the schema — OpenAI/Gemini/Mistral/etc.
		//      enforce conformance server-side on the assistant's text
		//      response. This is the preferred path where supported.
		//   2. Typed complete/fail tools with the schema as parameters —
		//      works on Anthropic and other providers without response_format
		//      JSON schema support. The LLM calls complete(...) and the
		//      harness extracts the args as the result.
		// The agent author chooses how to coach the LLM via the system
		// prompt; the framework wires up both mechanisms.
		//
		// Provider handling lives in the ADAPTER (#81): LangChainAdapter
		// suppresses response_format for providers without native schema
		// support and realises it via forced tool calling instead, converting
		// the output-tool call back into schema-conformant text. This harness
		// stays provider-blind — flipping llmOperation between providers
		// changes nothing here.
		AMap<AString, ACell> outputs = resolveOutputs(config);
		AMap<AString, ACell> defaultSchema = outputsCompleteSchema(outputs);
		AMap<AString, ACell> perRequestSchema = hasStrictPerRequestSchema(tasks)
			? extractPerRequestResponseSchema(tasks) : null;
		AMap<AString, ACell> activeSchema = (perRequestSchema != null) ? perRequestSchema : defaultSchema;
		boolean typedOutputs = (activeSchema != null);

		AMap<AString, ACell> l3Config = config;
		if (typedOutputs && config != null) {
			AMap<AString, ACell> responseFormat = Maps.of(
				Strings.create("name"), Strings.create("agent_output"),
				Strings.create("schema"), activeSchema);
			l3Config = config.assoc(K_RESPONSE_FORMAT, responseFormat);
		}

		// Build typed harness tools (complete/fail) when typed outputs are active.
		// Use the active schema (per-request override or agent default).
		AVector<ACell> typedHarnessTools = null;
		if (typedOutputs) {
			AMap<AString, ACell> failSchema = (perRequestSchema != null)
				? DEFAULT_FAIL_SCHEMA  // per-request schema only specifies complete
				: outputsFailSchema(outputs);
			typedHarnessTools = (AVector<ACell>) Vectors.of(
				(ACell) typedCompleteTool(activeSchema),
				(ACell) typedFailTool(failSchema));
		}

		// Generate root goal description from incoming work. Only used when
		// the session has no frames yet (first transition).
		String rootDescription = GoalTreeContext.describeTransitionInput(messages, tasks, pending);

		// Frame stack home (lattice-resident frames): for a sessioned run-loop
		// cycle the stack lives ON the session record and every mutation is an
		// epoch-fenced CAS — mid-run work is durable and observable, and a
		// superseded cycle (cancelled, session deleted, agent recreated) is
		// fenced out. Unsessioned / direct-invoke runs keep the pre-cutover
		// local behaviour behind the same seam.
		long cycleTs = convex.core.util.Utils.getCurrentTimestamp();
		FrameStore store;
		boolean tidyInterrupted = false;
		{
			Blob sid = ctx.getSessionId();
			AgentState agentState = resolveAgentState(ctx, agentId);
			if (sid != null && agentState != null && agentState.getSession(sid) != null) {
				// A stale epoch is an abandoned attempt, never a checkpoint. Its
				// externally visible tool interactions remain in conversation, but
				// unfinished child execution is settled as failed rather than resumed.
				boolean abandonedAttempt = agentState.getSessionCycleEpoch(sid) != null;

				// Claim first (epoch only), repair under the new epoch, THEN
				// append this cycle's input turns + drain in one CAS — repair
				// must precede the new user turns so synthetic tool results
				// land next to their assistant turns (provider ordering).
				ACell epoch = Blob.createRandom(new java.util.Random(), 8);
				if (!agentState.beginSessionCycle(sid, epoch, null, 0)) {
					return Maps.of(
						AgentState.KEY_STATE, Maps.empty(),
						Fields.ERROR, Strings.create("Session vanished before cycle start: " + sid));
				}
				store = new FrameStore.LatticeFrameStore(agentState, sid, epoch, ctx.getCancellation());

				// Any non-quiescent stack is settled without re-running children.
				tidyInterrupted = abandonedAttempt || store.frames().count() > 1;

				// An unanswered tool call is simply a FAILED tool call (#271).
				// Tool failures are already ordinary outcomes everywhere else —
				// a timeout, a denial or malformed arguments all come back as an
				// "Error: …" tool result the model reads and recovers from — so a
				// call that never returned gets the same treatment, and the agent
				// decides what to do. Repairing only when the session LOOKS
				// interrupted left any other abort — a
				// cancelled transition, a failed CAS, a suspend — with a
				// tool_use carrying no tool_result. Providers reject such a
				// history outright, so one transient failure poisoned the session
				// permanently, with nothing to heal it short of a venue restart.
				// Repairing on every load makes that state unreachable.
				if (store.frames().count() > 0) {
					AString note = Strings.create(
						"Error: this tool call did not return — "
						+ "its effects may or may not have applied; verify before retrying.");
					if (!store.update(f -> GoalTreeContext.repairDanglingToolCalls(f, note))) {
						return abortedOutput(store);
					}
					// Verify at the point of use (#214): the repair CAS can
					// transiently observe stale session state under load and
					// "succeed" as a no-op — running a frame loop on an
					// unrepaired conversation blindly re-dispatches pre-crash
					// tool calls. Check a fresh read (repair returns its input
					// unchanged when nothing is dangling); retry once, then
					// fail loudly rather than proceed.
					AVector<ACell> check = store.frames();
					if (check.isEmpty()
							|| GoalTreeContext.repairDanglingToolCalls(check, note) != check) {
						log.warn("Interruption repair no-oped on stale frames (agent {}, session {}, checkCount {}) — retrying (#214)",
							agentId, sid, check.count());
						if (!store.update(f -> GoalTreeContext.repairDanglingToolCalls(f, note))) {
							return abortedOutput(store);
						}
						check = store.frames();
						if (GoalTreeContext.repairDanglingToolCalls(check, note) != check) {
							return Maps.of(
								AgentState.KEY_STATE, Maps.empty(),
								Fields.ERROR, Strings.create(
									"Interruption cleanup could not repair dangling tool calls for session "
									+ sid + " — aborting rather than re-executing them (#214)"));
						}
					}
				}

				AVector<ACell> cycleTurns = buildCycleInputTurns(messages, input, cycleTs);
				long drainCount = (messages != null) ? messages.count() : 0;
				if (!agentState.beginSessionCycle(sid, epoch, cycleTurns, drainCount)) {
					return Maps.of(
						AgentState.KEY_STATE, Maps.empty(),
						Fields.ERROR, Strings.create("Session vanished before cycle start: " + sid));
				}
			} else {
				AVector<ACell> sessionFrames = covia.adapter.AgentAdapter.sessionFrames(input);
				// A cycle that CARRIES session state but falls through to the
				// local store is an anomaly: its writes are not durable and any
				// stale inCycle claim is never repaired. Name the failed
				// condition — this fallthrough is a bug class, not a mode.
				if (sessionFrames != null && sessionFrames.count() > 0) {
					log.warn("Sessioned cycle fell through to LOCAL frames (agent {}): sid={} agentState={} session={}",
						agentId, sid,
						(agentState != null),
						(sid != null && agentState != null) ? (agentState.getSession(sid) != null) : "n/a");
				}
				AVector<ACell> frames = (sessionFrames != null && sessionFrames.count() > 0)
					? sessionFrames
					: Vectors.of((ACell) GoalTreeContext.createFrame(rootDescription));
				frames = appendCycleInputTurns(frames, messages, input, cycleTs);
				store = new FrameStore.LocalFrameStore(frames);
			}
		}
		AVector<ACell> frames = store.frames();
		// The shared context is always built from the ROOT view of the stack —
		// exactly what every pre-cutover cycle saw (clean cycles end
		// root-only). An interrupted stack may still hold child frames; the
		// cleanup driver settles them without execution. Rendering a child's conversation into
		// the shared history here would leak it into the root's context.
		if (frames.count() > 1) {
			frames = (AVector<ACell>) frames.slice(0, 1);
		}

		// Outer tiers of the context scope chain (#142): agent (config.loads,
		// operator-pinned) + session (sessions.<sid>.loads). Constant within a
		// cycle; frames compose their tier on top (inner shadows/masks outer).
		AMap<AString, ACell> outerLoads = ContextChain.effective(
			ContextChain.declaredLoads(RT.getIn(recordConfig, Fields.LOADS), "config.loads"),
			ContextChain.sessionLoads(input));

		// Authority, palette and the cycle's Spec. Harness tool names in
		// config.tools are skipped by the palette — they're resolved separately
		// by resolveHarnessTools / buildTypedRootHarnessTools.
		RequestContext capsCtx = capsContext(recordConfig, ctx).withAgentId(agentId);
		ToolPalette.Palette palette = ToolPalette.resolve(engine, ctx, recordConfig, HARNESS_TOOL_REGISTRY.keySet());
		AVector<ACell> baseTools = palette.tools();
		Map<String, AString> configToolMap = palette.routes();
		AString llmOperation = getLLMOperation(l3Config);
		ModelProfile profile = modelProfileFor(l3Config, ctx);

		// Everything the assembler needs that holds for the whole cycle; each
		// frame and inference supplies the rest (AGENT_CONTEXT.md §8).
		ContextAssembler.Spec cycleSpec = new ContextAssembler.Spec(
			engine, ctx, capsCtx, l3Config,
			ContextAssembler.sessionHex(RT.getIn(input, Fields.SESSION, Fields.ID)), null,
			profile.budget(), profile.labels(),
			null, null, null, null, null, null, true, null, null,
			palette.unavailable(), null, null);

		// Per-tool-call timeout — bounds any single grid op invoked as a tool
		// so a stuck sub-job cannot hang this loop. Resolved once and shared
		// with subgoal recursion.
		long toolCallTimeoutMs = resolveToolCallTimeoutMs(l3Config);

		// Run the root frame. typedHarnessTools (if non-null) injects the
		// typed complete/fail tools alongside the regular harness/operation
		// tools, supporting providers that prefer tool calls over response_format.
		// An interrupted stack first settles child frames deepest-first without
		// resuming their internal execution, then starts the root afresh.
		ToolCycleEngine.Diagnostics diagnostics = new ToolCycleEngine.Diagnostics();
		FrameResult result = tidyInterrupted
			? settleInterruptedFrames(job, store, l3Config, llmOperation, baseTools,
				configToolMap, capsCtx, cycleSpec, typedHarnessTools, toolCallTimeoutMs,
				outerLoads, diagnostics)
			: runFrame(job, store, 0, l3Config, llmOperation, baseTools,
				configToolMap, capsCtx, cycleSpec, typedHarnessTools, toolCallTimeoutMs,
				outerLoads, diagnostics);

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
		boolean failed = "failed".equals(result.status());
		if (tasks != null && tasks.count() > 0 && ctx.getTaskId() != null) {
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
		if (diagnostics.failures().count() > 0) {
			output = output.assoc(Fields.TOOL_FAILURES, diagnostics.failures());
		}
		// Cycle token totals (#217) — measured only; absent means the
		// provider reported nothing, never zero. Per-call usage additionally
		// rides each assistant turn in the frame conversation (the raw L3
		// message, tokens included, is what appendTurn records).
		AMap<AString, ACell> cycleTokens = endTokenTally();
		if (cycleTokens != null) {
			output = output.assoc(Fields.TOKENS, cycleTokens);
		}
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
		final AMap<AString, ACell> config;
		final AString llmOperation;
		final RequestContext ctx;
		final ContextAssembler.Spec cycleSpec;
		final long toolCallTimeoutMs;
		final AMap<AString, ACell> outerLoads;
		final ToolCycleEngine.Diagnostics diagnostics;
		AVector<ACell> baseTools;
		final Map<String, AString> configToolMap;
		Map<String, AString> iterationToolMap = Map.of();
		AMap<AString, ACell> activeFrame;
		String pendingCompactSummary;
		AString turnText;

		FrameToolContext(Job job, FrameStore store, int frameIndex,
				AMap<AString, ACell> config, AString llmOperation,
				AVector<ACell> baseTools, Map<String, AString> configToolMap,
				RequestContext ctx, ContextAssembler.Spec cycleSpec,
				long toolCallTimeoutMs, AMap<AString, ACell> outerLoads,
				ToolCycleEngine.Diagnostics diagnostics) {
			this.job = job;
			this.store = store;
			this.frameIndex = frameIndex;
			this.config = config;
			this.llmOperation = llmOperation;
			this.baseTools = baseTools;
			this.configToolMap = configToolMap;
			this.ctx = ctx;
			this.cycleSpec = cycleSpec;
			this.toolCallTimeoutMs = toolCallTimeoutMs;
			this.outerLoads = outerLoads;
			this.diagnostics = diagnostics;
		}

		ToolCycleEngine.Registry<FrameToolContext> registry() {
			return new ToolCycleEngine.Registry<FrameToolContext>()
				.register(TOOL_COMPLETE, (call, ignored) -> complete(call, false))
				.register(TOOL_FAIL, (call, ignored) -> complete(call, true))
				.register(TOOL_COMPACT, (call, ignored) -> compact(call))
				.register(TOOL_CONTEXT_LOAD, (call, ignored) -> contextLoad(call))
				.register(TOOL_CONTEXT_UNLOAD, (call, ignored) -> contextUnload(call))
				.register(TOOL_SKILL_LOAD, (call, ignored) -> skillLoad(call))
				.register(TOOL_MORE_TOOLS, (call, ignored) -> moreTools(call))
				.register(TOOL_SUBGOAL, (call, ignored) -> subgoal(call))
				.fallback((call, ignored) -> ToolCycleEngine.ToolOutcome.result(
					dispatchTool(call.name(), call.input(), iterationToolMap,
						ctx, toolCallTimeoutMs)));
		}

		private ToolCycleEngine.ToolOutcome complete(
				ToolCycleEngine.ToolCall call, boolean failed) {
			ACell value = call.input();
			// Same empty-completion fallback as llmagent: models commonly write
			// the answer as assistant text and emit an empty control call beside it.
			if (value instanceof AMap<?, ?> map && map.isEmpty()
					&& turnText != null && !turnText.toString().isBlank()) {
				value = turnText;
			}
			if (!failed && frameIndex == 0) {
				AMap<AString, ACell> schema = RT.ensureMap(
					RT.getIn(config, K_RESPONSE_FORMAT, K_SCHEMA));
				if (schema != null) {
					ACell candidate = value;
					AString stringValue = RT.ensureString(value);
					if (stringValue != null) {
						try {
							ACell parsed = convex.core.util.JSON.parse(stringValue.toString());
							if (JsonSchema.validate(schema, parsed) == null) candidate = parsed;
						} catch (Exception ignored) {
							// Validate the raw string below.
						}
					}
					String schemaError = JsonSchema.validate(schema, candidate);
					if (schemaError != null) {
						return ToolCycleEngine.ToolOutcome.result(Strings.create(
							"Error: result does not conform to the required response schema — "
							+ schemaError + ". Correct the result and call complete again."));
					}
					value = candidate;
				}
			}
			ACell result = Maps.of(Strings.create("status"),
				Strings.create(failed ? "failed" : "complete"));
			return ToolCycleEngine.ToolOutcome.terminal(result,
				failed ? "failed" : "complete", value);
		}

		private ToolCycleEngine.ToolOutcome compact(
				ToolCycleEngine.ToolCall call) {
			AString summary = RT.ensureString(RT.getIn(call.input(), Strings.create("summary")));
			if (summary == null || summary.toString().isBlank()) {
				return ToolCycleEngine.ToolOutcome.result(Strings.create(
					"Error: summary is required — compact must preserve the important work so far."));
			}
			long turnsBefore = GoalTreeContext.countLiveTurns(activeFrame);
			pendingCompactSummary = summary.toString();
			return ToolCycleEngine.ToolOutcome.result(Strings.create(
				"Compacted " + turnsBefore + " turns into segment. Context freed."));
		}

		private ToolCycleEngine.ToolOutcome contextLoad(
				ToolCycleEngine.ToolCall call) {
			HarnessTools.LoadScope scope = loadScope();
			ACell result = HarnessTools.contextLoad(call.input(), scope);
			activeFrame = activeFrame.assoc(GoalTreeContext.K_LOADS, scope.loads);
			return ToolCycleEngine.ToolOutcome.result(result);
		}

		private ToolCycleEngine.ToolOutcome contextUnload(
				ToolCycleEngine.ToolCall call) {
			HarnessTools.LoadScope scope = loadScope();
			ACell result = HarnessTools.contextUnload(call.input(), scope);
			activeFrame = activeFrame.assoc(GoalTreeContext.K_LOADS, scope.loads);
			return ToolCycleEngine.ToolOutcome.result(result);
		}

		private ToolCycleEngine.ToolOutcome skillLoad(
				ToolCycleEngine.ToolCall call) {
			HarnessTools.LoadScope scope = loadScope();
			ACell result = HarnessTools.skillLoad(call.input(), scope);
			activeFrame = activeFrame.assoc(GoalTreeContext.K_LOADS, scope.loads);
			return ToolCycleEngine.ToolOutcome.result(result);
		}

		private HarnessTools.LoadScope loadScope() {
			return new HarnessTools.LoadScope(engine, ctx,
				GoalTreeContext.getLoads(activeFrame), outerLoads, true, "", Skills.sourcesOf(config));
		}

		@SuppressWarnings("unchecked")
		private ToolCycleEngine.ToolOutcome moreTools(
				ToolCycleEngine.ToolCall call) {
			ACell opsCell = RT.getIn(call.input(), Strings.create("operations"));
			if (!(opsCell instanceof AVector<?>)) {
				return ToolCycleEngine.ToolOutcome.result(Strings.create(
					"Error: operations must be an array of operation paths"));
			}
			AVector<ACell> operations = (AVector<ACell>) opsCell;
			Map<String, AString> newRoutes = new java.util.HashMap<>();
			AVector<ACell> newTools = ToolPalette.forOperations(engine, ctx, operations, newRoutes);
			java.util.Set<String> existing = fixedToolNames(baseTools);
			AVector<ACell> added = Vectors.empty();
			for (long i = 0; i < newTools.count(); i++) {
				ACell tool = newTools.get(i);
				AString name = RT.ensureString(RT.getIn(tool, K_NAME));
				if (name != null && existing.add(name.toString())) {
					baseTools = baseTools.conj(tool);
					configToolMap.put(name.toString(), newRoutes.get(name.toString()));
					added = added.conj(name);
				}
			}
			log.info("more_tools: added {} tools", added.count());
			return ToolCycleEngine.ToolOutcome.result(Maps.of(
				Strings.create("added"), added,
				Strings.create("total_tools"), CVMLong.create(baseTools.count()),
				Strings.create("note"), Strings.create("Tools available on your next turn.")));
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

			FrameResult childResult = runFrame(job, store, frameIndex + 1,
				config, llmOperation, baseTools, configToolMap, ctx, cycleSpec, null,
				toolCallTimeoutMs, outerLoads, diagnostics);
			if (store.aborted()) return ToolCycleEngine.ToolOutcome.abort();

			AMap<AString, ACell> result = Maps.of(
				Strings.create("status"), Strings.create(childResult.status()));
			if (childResult.value() != null) {
				result = result.assoc(Strings.create("result"), childResult.value());
			}
			AMap<AString, ACell> withResult = GoalTreeContext.appendTurn(activeFrame,
				toolResultMessage(call.id(), call.name(), result));
			if (!store.update(frames -> updateFrame(
					(AVector<ACell>) frames.slice(0, frameIndex + 1), frameIndex, withResult))) {
				return ToolCycleEngine.ToolOutcome.abort();
			}
			activeFrame = withResult;
			return ToolCycleEngine.ToolOutcome.recorded();
		}
	}

	/**
	 * Runs a single frame's tool call loop. Recursively invokes child frames
	 * when subgoal is called.
	 *
	 * @param frames the full frame stack
	 * @param frameIndex index of the active frame
	 * @param config agent config
	 * @param llmOperation L3 operation name
	 * @param baseTools configured operation tools
	 * @param configToolMap LLM tool name → operation name mapping
	 * @param ctx request context for tool dispatch — carries the agent's caps
	 * @param cycleSpec the cycle's assembly Spec; each inference derives its own
	 * @param typedRootHarnessTools typed complete/fail tools injected at the
	 *        root frame (alongside config-resolved harness tools), or null
	 *        for untyped agents. When non-null, the root frame ALSO has
	 *        response_format set in its L3 config — both completion paths
	 *        are wired up. Children are always free-form.
	 * @return the frame's result
	 */
	@SuppressWarnings("unchecked")
	FrameResult runFrame(Job job, FrameStore store, int frameIndex,
			AMap<AString, ACell> config, AString llmOperation,
			AVector<ACell> baseToolsParam, Map<String, AString> configToolMap,
			RequestContext ctx, ContextAssembler.Spec cycleSpec,
			AVector<ACell> typedRootHarnessTools, long toolCallTimeoutMs,
			AMap<AString, ACell> outerLoads,
			ToolCycleEngine.Diagnostics diagnostics) {

		// Mutable copy — more_tools can append to this mid-run
		AVector<ACell> baseTools = baseToolsParam;

		// Harness tool selection: from config.tools. Child frames exclude
		// subgoal (prevents unnecessary nesting from smaller models).
		// Plus typed complete/fail tools at the root frame when typed
		// outputs are active (deduplicated against any complete/fail in
		// config.tools — typed wins).
		AVector<ACell> harnessForFrame;
		if (frameIndex == 0) {
			AVector<ACell> configHarness = resolveHarnessTools(config);
			if (typedRootHarnessTools != null) {
				// Typed complete/fail wins; filter out any duplicates from config
				java.util.Set<String> typedNames = new java.util.HashSet<>();
				for (long i = 0; i < typedRootHarnessTools.count(); i++) {
					ACell t = typedRootHarnessTools.get(i);
					if (t instanceof AMap) {
						ACell n = ((AMap<?,?>) t).get(K_NAME);
						if (n != null) typedNames.add(n.toString());
					}
				}
				AVector<ACell> merged = typedRootHarnessTools;
				for (long i = 0; i < configHarness.count(); i++) {
					ACell t = configHarness.get(i);
					String n = null;
					if (t instanceof AMap) {
						ACell nc = ((AMap<?,?>) t).get(K_NAME);
						if (nc != null) n = nc.toString();
					}
					if (n == null || !typedNames.contains(n)) {
						merged = merged.conj(t);
					}
				}
				harnessForFrame = merged;
			} else {
				harnessForFrame = configHarness;
			}
		} else {
			// Child frames: resolve from config but exclude subgoal
			AVector<ACell> childHarness = resolveHarnessTools(config);
			AVector<ACell> filtered = Vectors.empty();
			for (long i = 0; i < childHarness.count(); i++) {
				ACell tool = childHarness.get(i);
				if (tool instanceof AMap) {
					ACell name = ((AMap<?,?>) tool).get(K_NAME);
					if (name != null && !TOOL_SUBGOAL.equals(name.toString())) {
						filtered = filtered.conj(tool);
					}
				}
			}
			harnessForFrame = filtered;
		}
		// Offer skill_load automatically when the agent declares skill sources
		// (config.skills) — same rule as llmagent; bare-name opt-in via
		// config.tools also works through the registry. Skills semantics live
		// in Skills and the context assembly; this runtime only offers and glues.
		if (!Skills.sourcesOf(config).isEmpty()
				&& !hasToolNamed(harnessForFrame, TOOL_SKILL_LOAD)) {
			harnessForFrame = harnessForFrame.conj(TOOL_DEF_SKILL_LOAD);
		}
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
		if (GoalTreeContext.countLiveTurns(activeFrame) == 0) {
			AMap<AString, ACell> goalMsg = GoalTreeContext.renderGoal(activeFrame);
			if (goalMsg != null) {
				activeFrame = GoalTreeContext.appendTurn(activeFrame, goalMsg);
				if (!persist(store, frameIndex, activeFrame)) return abortedResult(store);
			}
		}

		// Deferred compact: applied at the start of the next iteration so we never
		// split an assistant message from its tool results (OpenAI requires every
		// tool result to follow its assistant tool_calls message)
		FrameToolContext frameTools = new FrameToolContext(job, store, frameIndex,
			config, llmOperation, baseTools, configToolMap, ctx, cycleSpec,
			toolCallTimeoutMs, outerLoads, diagnostics);
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

			@Override
			public void recordFailure(AString name, String failure) {
				diagnostics.record(name, failure);
			}
		};

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
				AMap<AString, ACell> goal = GoalTreeContext.renderGoal(frameTools.activeFrame);
				if (goal != null) {
					frameTools.activeFrame = GoalTreeContext.appendTurn(frameTools.activeFrame, goal);
				}
				if (!persist(store, frameIndex, frameTools.activeFrame)) return abortedResult(store);
				frameTools.pendingCompactSummary = null;
			}

			// The whole prompt is rebuilt before every call: loads re-read under
			// the agent's authority, the frame stack rendered (ancestors compacted,
			// active frame in full), the tail re-rendered.
			AMap<AString, ACell> frameLoads = GoalTreeContext.getLoads(frameTools.activeFrame);
			AMap<AString, ACell> effectiveLoads = ContextChain.effective(outerLoads, frameLoads);
			AVector<ACell> fixedTools = (AVector<ACell>) harnessForFrame.concat(frameTools.baseTools);
			Loads.Snapshot loads = Loads.resolve(engine, ctx, effectiveLoads,
				fixedToolNames(fixedTools), cycleSpec.labels());
			frameTools.iterationToolMap = mergeRoutes(configToolMap, loads.routes());
			AVector<ACell> tools = ToolPalette.merge(fixedTools, loads.tools());

			long liveTurns = GoalTreeContext.countLiveTurns(frameTools.activeFrame);
			String notice = (liveTurns > AUTO_COMPACT_THRESHOLD && hasCompactTool(harnessForFrame))
				? "Your conversation has " + liveTurns
					+ " turns. Call compact(summary) now to free context space before continuing."
				: null;
			AVector<ACell> stack = (AVector<ACell>) currentFrames.slice(0, frameIndex + 1)
				.assoc(frameIndex, frameTools.activeFrame);
			ContextAssembler.Prompt prompt = ContextAssembler.assemble(cycleSpec
				.forFrame(frameL3Config, (frameIndex > 0) ? CHILD_FRAME_NOTICE : null)
				.withLoads(loads, tools, effectiveLoads)
				.withFrames(stack)
				.withNotice(notice));

			ACell assistant = invokeLevel3(llmOperation, frameL3Config, prompt, ctx);
			AVector<ACell> calls = RT.ensureVector(RT.getIn(assistant, K_TOOL_CALLS));
			boolean hasCalls = calls != null && calls.count() > 0;
			if (!hasCalls) {
				java.util.Set<String> controls = new java.util.HashSet<>();
				if (hasToolNamed(harnessForFrame, TOOL_COMPLETE)) controls.add(TOOL_COMPLETE);
				if (hasToolNamed(harnessForFrame, TOOL_FAIL)) controls.add(TOOL_FAIL);
				AMap<AString, ACell> rewritten = ToolCycleEngine.recogniseTextualControlCall(
					assistant, iteration, controls);
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
					try {
						ACell parsed = convex.core.util.JSON.parse(content.toString());
						AMap<AString, ACell> schema = RT.ensureMap(
							RT.getIn(frameL3Config, K_RESPONSE_FORMAT, K_SCHEMA));
						String schemaError = (schema != null)
							? JsonSchema.validate(schema, parsed) : null;
						if (schemaError != null) throw new IllegalArgumentException(schemaError);
						value = parsed;
					} catch (Exception e) {
						log.warn("Frame[{}] iter={} typed output was invalid: {}",
							frameIndex, iteration, e.getMessage());
						frameTools.activeFrame = GoalTreeContext.appendTurn(
							frameTools.activeFrame, assistant);
						frameTools.activeFrame = GoalTreeContext.appendTurn(
							frameTools.activeFrame, Maps.of(
								K_ROLE, ROLE_USER,
								K_CONTENT, Strings.create(
									"Your response did not conform to the declared output schema. "
									+ "Respond again with valid JSON matching that schema.")));
						if (!persist(store, frameIndex, frameTools.activeFrame)) return abortedResult(store);
						continue;
					}
				}
				AMap<AString, ACell> terminal = GoalTreeContext.withStatus(
					GoalTreeContext.appendTurn(frameTools.activeFrame, assistant),
					GoalTreeContext.STATUS_COMPLETE);
				if (!persist(store, frameIndex, terminal)) return abortedResult(store);
				return FrameResult.complete(value, store.frames());
			}

			frameTools.activeFrame = GoalTreeContext.appendTurn(frameTools.activeFrame, assistant);
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
			AMap<AString, ACell> config, AString llmOperation,
			AVector<ACell> baseTools, Map<String, AString> configToolMap,
			RequestContext ctx, ContextAssembler.Spec cycleSpec,
			AVector<ACell> typedRootHarnessTools, long toolCallTimeoutMs,
			AMap<AString, ACell> outerLoads,
			ToolCycleEngine.Diagnostics diagnostics) {
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

		return runFrame(job, store, 0, config, llmOperation, baseTools,
			configToolMap, ctx, cycleSpec, typedRootHarnessTools, toolCallTimeoutMs,
			outerLoads, diagnostics);
	}

	/** Transition output for a cycle that lost frame ownership mid-flight. */
	private static AMap<AString, ACell> abortedOutput(FrameStore store) {
		FrameResult aborted = abortedResult(store);
		return Maps.of(
			AgentState.KEY_STATE, Maps.empty(),
			Fields.ERROR, aborted.value());
	}

	/** Persists the active frame back into the stack; false = cycle superseded. */
	private static boolean persist(FrameStore store, int frameIndex, AMap<AString, ACell> activeFrame) {
		return store.update(f -> updateFrame(f, frameIndex, activeFrame));
	}

	/** The uniform give-up result when this cycle no longer owns the frames. */
	private static FrameResult abortedResult(FrameStore store) {
		return FrameResult.failed(Strings.create(
			"Cycle superseded — frames are no longer owned by this transition "
			+ "(cancelled, session removed, or reclaimed by a newer cycle)"), store.frames());
	}

	// ========== Helpers ==========

	/**
	 * Extracts a per-request response schema from the tasks vector. When
	 * a caller invokes agent_request with a responseSchema field, the
	 * framework attaches it to the task entry. Returns the first schema
	 * found, or null if none of the tasks have one.
	 *
	 * <p>If multiple tasks specify different schemas, the first wins.
	 * Mixing schemas in one batch is rare and not well-defined.</p>
	 */
	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> extractPerRequestResponseSchema(AVector<ACell> tasks) {
		if (tasks == null) return null;
		AString K_RESPONSE_SCHEMA = Strings.intern("responseSchema");
		for (long i = 0; i < tasks.count(); i++) {
			ACell t = tasks.get(i);
			if (t instanceof AMap) {
				ACell schema = ((AMap<AString, ACell>) t).get(K_RESPONSE_SCHEMA);
				if (schema instanceof AMap) return (AMap<AString, ACell>) schema;
			}
		}
		return null;
	}

	private static boolean hasStrictPerRequestSchema(AVector<ACell> tasks) {
		if (tasks == null) return false;
		for (long i = 0; i < tasks.count(); i++) {
			ACell task = tasks.get(i);
			if (RT.getIn(task, Fields.RESPONSE_SCHEMA) instanceof AMap
					&& CVMBool.TRUE.equals(RT.getIn(task, Fields.STRICT))) return true;
		}
		return false;
	}

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

	/** Names a load may never shadow: every harness tool plus the fixed palette. */
	private static java.util.Set<String> fixedToolNames(AVector<ACell> tools) {
		java.util.Set<String> names = new java.util.HashSet<>(HARNESS_TOOL_REGISTRY.keySet());
		names.addAll(ToolPalette.names(tools));
		return names;
	}

	/** Base/dynamic routes plus only this inference's live-load routes. */
	private static Map<String, AString> mergeRoutes(Map<String, AString> base,
			Map<String, AString> liveLoads) {
		if (liveLoads == null || liveLoads.isEmpty()) return base;
		Map<String, AString> effective = new java.util.HashMap<>();
		if (base != null) effective.putAll(base);
		effective.putAll(liveLoads);
		return effective;
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

	/**
	 * Appends this cycle's user-side inputs as turns on {@code frames[0].conversation}
	 * before the tool loop runs.
	 *
	 * <p>The framework used to build these turns in {@code mergeRunResult} from
	 * the picked task input and drained pending envelopes. With adapter-emitted
	 * frames that path is skipped — the adapter must record user turns itself
	 * so they land in chronological order (before this cycle's assistant / tool
	 * turns) and so the frame stack faithfully mirrors the conversation.</p>
	 *
	 * <p>Turn shape matches the S3 envelope contract
	 * ({@code {role, content, ts, source}}) — extra fields are ignored by the
	 * LLM adapter's message extraction, so LLM calls are unaffected.</p>
	 *
	 * @param frames current frame stack (must have at least one root frame)
	 * @param messages effective messages (session.pending or Fields.MESSAGES);
	 *        each entry is a chat/message envelope with a {@code message} field
	 * @param input the transition input (read for {@code Fields.NEW_INPUT})
	 * @param ts wall-clock millis to stamp on the new turns
	 * @return frame stack with user turns appended to frames[0].conversation
	 */
	@SuppressWarnings("unchecked")
	private static AVector<ACell> appendCycleInputTurns(AVector<ACell> frames,
			AVector<ACell> messages, ACell input, long ts) {
		if (frames == null || frames.count() == 0) return frames;
		AMap<AString, ACell> root = (AMap<AString, ACell>) frames.get(0);
		AVector<ACell> turns = buildCycleInputTurns(messages, input, ts);
		for (long i = 0; i < turns.count(); i++) {
			root = GoalTreeContext.appendTurn(root, turns.get(i));
		}
		return frames.assoc(0, root);
	}

	/**
	 * Builds this cycle's input turns (pending envelopes as chat-sourced user
	 * turns, picked task input as a request-sourced user turn) without applying
	 * them. The lattice path hands these to {@code beginSessionCycle} (applied
	 * atomically with the pending drain); the local path appends them via
	 * {@link #appendCycleInputTurns}.
	 */
	private static AVector<ACell> buildCycleInputTurns(
			AVector<ACell> messages, ACell input, long ts) {
		AVector<ACell> turns = Vectors.empty();
		CVMLong tsCell = CVMLong.create(ts);

		if (messages != null) {
			for (long i = 0; i < messages.count(); i++) {
				ACell envelope = messages.get(i);
				ACell content = RT.getIn(envelope, Fields.MESSAGE);
				if (content == null) continue;
				AMap<AString, ACell> turn = Maps.of(
					covia.venue.AgentState.K_ROLE,    covia.venue.AgentState.ROLE_USER,
					covia.venue.AgentState.K_CONTENT, content,
					covia.venue.AgentState.K_TURN_TS, tsCell,
					covia.venue.AgentState.K_SOURCE,  covia.venue.AgentState.SOURCE_CHAT);
				// Preserve the originating Job id as durable provenance; LLM
				// rendering strips this non-conversation field.
				ACell envJobId = RT.getIn(envelope, Fields.JOB_ID);
				if (envJobId != null) turn = turn.assoc(Fields.JOB_ID, envJobId);
				turns = turns.conj(turn);
			}
		}

		ACell newInput = RT.getIn(input, Fields.NEW_INPUT);
		if (newInput != null) {
			AMap<AString, ACell> turn = Maps.of(
				covia.venue.AgentState.K_ROLE,    covia.venue.AgentState.ROLE_USER,
				covia.venue.AgentState.K_CONTENT, newInput,
				covia.venue.AgentState.K_TURN_TS, tsCell,
				covia.venue.AgentState.K_SOURCE,  covia.venue.AgentState.SOURCE_REQUEST);
			// The delegating agent:request Job id, carried on the transition input,
			// ties this request turn back to the calling job as chat turns do (#378).
			ACell reqJobId = RT.getIn(input, Fields.JOB_ID);
			if (reqJobId != null) turn = turn.assoc(Fields.JOB_ID, reqJobId);
			turns = turns.conj(turn);
		}

		return turns;
	}

	/**
	 * Resolves the caller's AgentState for a run-loop cycle, or null when the
	 * caller/agent cannot be resolved (direct-invoke and test paths).
	 */
	private covia.venue.AgentState resolveAgentState(RequestContext ctx, AString agentId) {
		if (agentId == null || ctx.getUserDID() == null) return null;
		covia.venue.User user = engine.getVenueState().users().get(ctx.getUserDID());
		return (user != null) ? user.agent(agentId.toString()) : null;
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
		return Maps.of(
			K_NAME, Strings.create(TOOL_COMPLETE),
			K_DESCRIPTION, Strings.create(
				"Finish your goal with the structured result. Parameters carry the "
				+ "declared output schema."),
			K_PARAMETERS, resultSchema);
	}

	/**
	 * Synthesises a typed {@code fail} tool whose parameters ARE the agent's
	 * declared fail schema (or {@link #DEFAULT_FAIL_SCHEMA}). Flattened:
	 * {@code fail({reason: "...", details: "..."})} — no wrapper.
	 */
	static AMap<AString, ACell> typedFailTool(AMap<AString, ACell> errorSchema) {
		return Maps.of(
			K_NAME, Strings.create(TOOL_FAIL),
			K_DESCRIPTION, Strings.create(
				"Report that your goal cannot be completed. Parameters carry the "
				+ "structured error schema."),
			K_PARAMETERS, errorSchema);
	}

	/**
	 * Builds the root-frame harness tool list for an agent with typed outputs.
	 *
	 * <p>Auto-injects typed {@code complete} and {@code fail} tools (with schema
	 * enforcement) regardless of config — these are required for the typed output
	 * model. Other harness tools (subgoal, compact, etc.) are included only if
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
