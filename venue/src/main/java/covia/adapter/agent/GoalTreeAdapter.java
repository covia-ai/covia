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
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.ContextBuilder;
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
 * <p>Each transition creates a fresh root frame from the incoming work. The frame
 * stack is in-memory during execution; between transitions only the root frame's
 * conversation is persisted in agent state.</p>
 *
 * @see GoalTreeContext for frame data model and context rendering (pure functions)
 * @see AbstractLLMAdapter for shared L3 invocation and tool dispatch
 */
public class GoalTreeAdapter extends AbstractLLMAdapter {

	private static final Logger log = LoggerFactory.getLogger(GoalTreeAdapter.class);

	// ========== Harness tool names ==========

	/** Maximum tool call loop iterations per frame */
	static final int MAX_ITERATIONS = 50;

	/** Live turn count above which the auto-compact nudge fires */
	static final int AUTO_COMPACT_THRESHOLD = 20;

	static final String TOOL_SUBGOAL        = "subgoal";
	static final String TOOL_COMPLETE       = "complete";
	static final String TOOL_FAIL           = "fail";
	static final String TOOL_COMPACT        = "compact";
	static final String TOOL_CONTEXT_LOAD   = "context_load";
	static final String TOOL_CONTEXT_UNLOAD = "context_unload";
	static final String TOOL_MORE_TOOLS     = "more_tools";

	// ========== Harness tool definitions ==========

	private static final AString K_DESCRIPTION = Strings.intern("description");
	private static final AString K_PARAMETERS  = Strings.intern("parameters");
	private static final AString K_TYPE        = Strings.intern("type");
	private static final AString K_PROPERTIES  = Strings.intern("properties");
	private static final AString K_REQUIRED    = Strings.intern("required");
	private static final AString K_HISTORY     = Strings.intern("history");
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
					K_DESCRIPTION, Strings.create("What the subgoal should accomplish"))),
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
			+ "repeatedly. The data is refreshed automatically each turn. Subgoals "
			+ "inherit your loaded data. For data you only need once, use covia_read "
			+ "instead. Call context_unload with the same path to remove it."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Strings.create("path"), Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create("Workspace path to load (e.g. w/docs/rules, w/vendor-records/acme)")),
				Strings.create("budget"), Maps.of(
					K_TYPE, Strings.create("integer"),
					K_DESCRIPTION, Strings.create("Byte budget for rendering this path (default 500, max 10000)")),
				Strings.create("label"), Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create("Optional human-readable label for this context entry"))),
			K_REQUIRED, Vectors.of(Strings.create("path"))));

	static final AMap<AString, ACell> TOOL_DEF_CONTEXT_UNLOAD = Maps.of(
		K_NAME, Strings.create(TOOL_CONTEXT_UNLOAD),
		K_DESCRIPTION, Strings.create(
			"Remove a path previously added with context_load. Pass the same "
			+ "path string you used when loading. Frees context space for other "
			+ "work or data."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Strings.create("path"), Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create("The path you passed to context_load"))),
			K_REQUIRED, Vectors.of(Strings.create("path"))));

	static final AMap<AString, ACell> TOOL_DEF_MORE_TOOLS = Maps.of(
		K_NAME, Strings.create(TOOL_MORE_TOOLS),
		K_DESCRIPTION, Strings.create(
			"Add operations to your tool set for the rest of this run. "
			+ "Use covia_list to discover available operations first "
			+ "(e.g. covia_list path=v/ops), then call this with the paths "
			+ "you need. Added tools appear on your next turn."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Strings.create("operations"), Maps.of(
					K_TYPE, Strings.create("array"),
					K_DESCRIPTION, Strings.create("Operation paths to add as tools (e.g. [\"v/ops/agent/create\", \"v/ops/grid/run\"])"),
					Strings.create("items"), Maps.of(K_TYPE, Strings.create("string")))),
			K_REQUIRED, Vectors.of(Strings.create("operations"))));

	/**
	 * Registry of all optional harness tool definitions by name.
	 * Agents opt into harness tools by listing their names in config.tools
	 * alongside regular operation paths.
	 */
	static final Map<String, AMap<AString, ACell>> HARNESS_TOOL_REGISTRY = Map.of(
		TOOL_SUBGOAL, TOOL_DEF_SUBGOAL,
		TOOL_COMPLETE, TOOL_DEF_COMPLETE,
		TOOL_FAIL, TOOL_DEF_FAIL,
		TOOL_COMPACT, TOOL_DEF_COMPACT,
		TOOL_CONTEXT_LOAD, TOOL_DEF_CONTEXT_LOAD,
		TOOL_CONTEXT_UNLOAD, TOOL_DEF_CONTEXT_UNLOAD,
		TOOL_MORE_TOOLS, TOOL_DEF_MORE_TOOLS);

	/**
	 * Resolves harness tools from the agent's config.tools list.
	 *
	 * <p>Scans config.tools for entries matching known harness tool names
	 * (subgoal, complete, fail, compact, context_load, context_unload,
	 * more_tools). Returns their definitions as a vector. Entries that don't
	 * match are ignored (they'll be resolved as operations by ContextBuilder).</p>
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

	/** System note prepended to child frame context */
	private static final AMap<AString, ACell> CHILD_FRAME_NOTE = Maps.of(
		K_ROLE, ROLE_SYSTEM,
		K_CONTENT, Strings.create(
			"You are inside a subgoal. Complete the specific goal described below. "
			+ "When done, just respond with your answer — a plain text response "
			+ "returns your result to the parent. Only call complete() if you need "
			+ "to return structured data."));

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
		// --- same as processGoal ---
		AMap<AString, ACell> config = extractConfig(recordConfig, state);
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

		ContextBuilder builder = new ContextBuilder(engine, ctx);
		ContextBuilder.ContextResult context = builder
			.withSkipToolNames(HARNESS_TOOL_REGISTRY.keySet())
			.withConfig(recordConfig, state)
			.withSystemPrompt(Vectors.empty())
			.withContextEntries(state)
			.withTools()
			.build();

		AVector<ACell> baseTools = context.tools();

		// --- same as first iteration of runFrame ---
		AVector<ACell> harnessTools = resolveHarnessTools(config);
		AVector<ACell> allTools = (typedTools != null)
			? (AVector<ACell>) typedTools.concat(harnessTools).concat(baseTools)
			: (AVector<ACell>) harnessTools.concat(baseTools);

		AVector<ACell> fullHistory = context.history();

		// Synthesise goal from task (same as GoalTreeContext.describeTransitionInput
		// + renderGoal + appendTurn in the real path)
		if (task != null) {
			AVector<ACell> tasks = Vectors.of(
				(ACell) Maps.of(Strings.intern("input"), task));
			String goalDesc = GoalTreeContext.describeTransitionInput(null, tasks, null);
			ACell goalMsg = Maps.of(K_ROLE, ROLE_USER, K_CONTENT, Strings.create(goalDesc));
			fullHistory = fullHistory.conj(goalMsg);
		}

		// --- same as invokeLevel3 ---
		return buildL3Input(l3Config, fullHistory, allTools);
	}

	// ========== Invocation ==========

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		return CompletableFuture.supplyAsync(() -> processGoal(null, ctx, input), VIRTUAL_EXECUTOR);
	}

	/**
	 * Processes a single agent transition using the goal tree model.
	 *
	 * <p>Creates a fresh root frame from incoming work, runs the frame's tool loop,
	 * and returns the transition output ({@code {state, result}}).</p>
	 *
	 * @param ctx request context (caller identity, capabilities)
	 * @param input transition input: {@code {agentId, state, tasks, pending, messages, config, newInput, session?}}
	 * @return transition output: {@code {state, response | error}}
	 */
	@SuppressWarnings("unchecked")
	ACell processGoal(Job job, RequestContext ctx, ACell input) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		ACell state = RT.getIn(input, AgentState.KEY_STATE);
		// S3c: prefer session.pending over agent-level messages when a session
		// is in scope. effectiveMessages picks the right one (no duplication).
		AVector<ACell> messages = covia.adapter.AgentAdapter.effectiveMessages(input);
		AVector<ACell> tasks = (AVector<ACell>) RT.getIn(input, Fields.TASKS);
		AVector<ACell> pending = (AVector<ACell>) RT.getIn(input, Fields.PENDING);

		AMap<AString, ACell> recordConfig = (RT.getIn(input, AgentState.KEY_CONFIG) instanceof AMap m) ? m : null;
		AMap<AString, ACell> config = extractConfig(recordConfig, state);

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
		AMap<AString, ACell> outputs = resolveOutputs(config);
		AMap<AString, ACell> defaultSchema = outputsCompleteSchema(outputs);
		AMap<AString, ACell> perRequestSchema = extractPerRequestResponseSchema(tasks);
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

		// Generate root goal description from incoming work
		String rootDescription = GoalTreeContext.describeTransitionInput(messages, tasks, pending);

		// Build context (system prompt, tools, caps) via ContextBuilder.
		// Harness tool names in config.tools are skipped here — they're
		// resolved separately by resolveHarnessTools / buildTypedRootHarnessTools.
		AVector<ACell> sessionTurns = covia.adapter.AgentAdapter.sessionHistory(input);
		ContextBuilder builder = new ContextBuilder(engine, ctx);
		ContextBuilder.ContextResult context = builder
			.withSkipToolNames(HARNESS_TOOL_REGISTRY.keySet())
			.withConfig(recordConfig, state)
			.withSessionHistory(sessionTurns)
			.withContextEntries(state)
			.withTools()
			.build();

		// Create root frame
		AMap<AString, ACell> rootFrame = GoalTreeContext.createFrame(rootDescription);
		AVector<ACell> frames = Vectors.of((ACell) rootFrame);

		// Prepare tool dispatch context
		AVector<ACell> baseTools = context.tools();
		Map<String, AString> configToolMap = context.configToolMap();
		AVector<ACell> caps = context.caps();
		AString llmOperation = getLLMOperation(l3Config);

		// Set up capability-scoped context for tool dispatch
		RequestContext capsCtx = context.capsCtx();

		// Run the root frame. typedHarnessTools (if non-null) injects the
		// typed complete/fail tools alongside the regular harness/operation
		// tools, supporting providers that prefer tool calls over response_format.
		FrameResult result = runFrame(job, frames, 0, l3Config, llmOperation, baseTools,
			configToolMap, caps, capsCtx, context.history(), typedHarnessTools);

		// Goal tree is stateless across transitions for everything except config:
		// the frame stack is in-memory only. Config (caps, responseFormat, prompt,
		// loaded paths…) must survive every transition because agents are typically
		// configured by writing config into state at create time. Wiping it would
		// silently strip caps/schema enforcement on the second invocation.
		AMap<AString, ACell> newState = Maps.empty();
		if (state instanceof AMap) {
			ACell sc = RT.getIn(state, K_CONFIG);
			if (sc != null) newState = newState.assoc(K_CONFIG, sc);
		}

		// On failure, persist the deepest frame's conversation as a debug
		// aid under state.lastFailure. Without this, when an agent loops or
		// hits max iterations the only thing visible post-mortem is the
		// final outcome string — making it nearly impossible to investigate
		// what tools were called, what they returned, and why. The frame
		// stack is otherwise dropped after the transition. On success this
		// field is cleared, so it always reflects the most recent failure.
		if ("failed".equals(result.status()) && result.framesAtFailure() != null) {
			AVector<ACell> finalFrames = result.framesAtFailure();
			if (finalFrames.count() > 0) {
				AMap<AString, ACell> deepest = (AMap<AString, ACell>) finalFrames.get(finalFrames.count() - 1);
				AVector<ACell> conversation = GoalTreeContext.renderConversation(deepest);
				AMap<AString, ACell> lastFailure = Maps.of(
					Strings.create("error"), result.value() != null ? result.value() : Strings.create(""),
					Strings.create("conversation"), conversation,
					Strings.create("frameDepth"), CVMLong.create(finalFrames.count()),
					Strings.create("ts"), CVMLong.create(convex.core.util.Utils.getCurrentTimestamp()));
				newState = newState.assoc(Strings.create("lastFailure"), lastFailure);
			}
		}

		// Lean transition output: emit {response | error}. When a task was
		// picked this cycle, complete it explicitly via the venue op
		// (agent:complete-task / agent:fail-task), which parks a completion
		// envelope into the framework's deferredCompletions map. The run
		// loop drains that map after mergeRunResult to build taskResults.
		// State is still emitted because GoalTree's config currently rides
		// in state.config across transitions — that carry-over moves to
		// the session record in the later sub-stage when state is fully
		// retired.
		boolean failed = "failed".equals(result.status());
		if (tasks != null && tasks.count() > 0 && ctx.getTaskId() != null) {
			completeTaskViaVenueOp(ctx, failed, result.value());
		}
		AMap<AString, ACell> output = Maps.of(AgentState.KEY_STATE, newState);
		if (failed) {
			output = output.assoc(Fields.ERROR, result.value());
		} else {
			output = output.assoc(Fields.RESPONSE, result.value());
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
	 * <p>On failure, {@code framesAtFailure} carries the final frame stack
	 * for post-mortem debugging — the caller can render the deepest frame's
	 * conversation and persist it as a debug aid. Null on success because
	 * GoalTreeAdapter is intentionally stateless across transitions for
	 * everything except config.</p>
	 */
	record FrameResult(String status, ACell value, AVector<ACell> framesAtFailure) {
		static FrameResult complete(ACell value) { return new FrameResult("complete", value, null); }
		static FrameResult failed(ACell error, AVector<ACell> frames) {
			return new FrameResult("failed", error, frames);
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
	 * @param caps capability attenuations
	 * @param ctx request context for tool dispatch
	 * @param systemMessages system messages (prompt, context entries)
	 * @param typedRootHarnessTools typed complete/fail tools injected at the
	 *        root frame (alongside config-resolved harness tools), or null
	 *        for untyped agents. When non-null, the root frame ALSO has
	 *        response_format set in its L3 config — both completion paths
	 *        are wired up. Children are always free-form.
	 * @return the frame's result
	 */
	@SuppressWarnings("unchecked")
	FrameResult runFrame(Job job, AVector<ACell> frames, int frameIndex,
			AMap<AString, ACell> config, AString llmOperation,
			AVector<ACell> baseToolsParam, Map<String, AString> configToolMap,
			AVector<ACell> caps, RequestContext ctx, AVector<ACell> systemMessages,
			AVector<ACell> typedRootHarnessTools) {

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
		// Typed outputs only at the root frame — children produce free-form
		// results back to their parent
		boolean typedOutputs = (frameIndex == 0 && typedRootHarnessTools != null);
		// Strip responseFormat from the L3 config for child frames so they
		// can produce arbitrary results back to the parent
		AMap<AString, ACell> frameL3Config = (frameIndex == 0)
			? config
			: (config != null ? config.dissoc(K_RESPONSE_FORMAT) : null);

		// Inject goal as first user message in the conversation (once, not every iteration)
		AMap<AString, ACell> activeFrame = (AMap<AString, ACell>) frames.get(frameIndex);
		if (GoalTreeContext.countLiveTurns(activeFrame) == 0) {
			AMap<AString, ACell> goalMsg = GoalTreeContext.renderGoal(activeFrame);
			if (goalMsg != null) {
				activeFrame = GoalTreeContext.appendTurn(activeFrame, goalMsg);
				frames = updateFrame(frames, frameIndex, activeFrame);
			}
		}

		// Deferred compact: applied at the start of the next iteration so we never
		// split an assistant message from its tool results (OpenAI requires every
		// tool result to follow its assistant tool_calls message)
		String pendingCompactSummary = null;

		for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
			// Check for cancellation before each L3 call
			if (job != null && job.isFinished()) {
				return FrameResult.failed(Strings.create("Job cancelled"), frames);
			}

			activeFrame = (AMap<AString, ACell>) frames.get(frameIndex);

			// Apply deferred compaction before assembling context
			if (pendingCompactSummary != null) {
				activeFrame = GoalTreeContext.compactFrame(activeFrame, pendingCompactSummary);
				// Re-inject goal as user message so the LLM retains frame context
				AMap<AString, ACell> goalMsg = GoalTreeContext.renderGoal(activeFrame);
				if (goalMsg != null) {
					activeFrame = GoalTreeContext.appendTurn(activeFrame, goalMsg);
				}
				frames = updateFrame(frames, frameIndex, activeFrame);
				pendingCompactSummary = null;
			}

			// Assemble tools = harness + base (recomputed each iteration
			// so more_tools additions are picked up)
			AVector<ACell> tools = (AVector<ACell>) harnessForFrame.concat(baseTools);

			// Assemble full context for this inference
			AVector<ACell> fullHistory = systemMessages;

			// Child frame note (if not root)
			if (frameIndex > 0) fullHistory = fullHistory.conj(CHILD_FRAME_NOTE);

			// Ancestor context (if not root)
			AMap<AString, ACell> ancestorMsg = GoalTreeContext.renderAncestors(frames);
			if (ancestorMsg != null) fullHistory = fullHistory.conj(ancestorMsg);

			// Loaded data (active frame's loads, resolved fresh each turn)
			AMap<AString, ACell> frameLoads = GoalTreeContext.getLoads(activeFrame);
			if (frameLoads.count() > 0) {
				ContextBuilder loadBuilder = new ContextBuilder(engine, ctx);
				fullHistory = (AVector<ACell>) fullHistory.concat(
					loadBuilder.resolveLoads(frameLoads));
			}

			// Conversation (segments + live turns — includes goal as first user message)
			AVector<ACell> convMessages = GoalTreeContext.renderConversation(activeFrame);
			fullHistory = (AVector<ACell>) fullHistory.concat(convMessages);

			// Auto-compact nudge: when conversation grows large, inject a
			// system hint so the LLM calls compact() before running out of
			// context. Only fires if the agent has compact in its tool set.
			long liveTurns = GoalTreeContext.countLiveTurns(activeFrame);
			if (liveTurns > AUTO_COMPACT_THRESHOLD && hasCompactTool(harnessForFrame)) {
				fullHistory = fullHistory.conj(Maps.of(
					K_ROLE, ROLE_SYSTEM,
					K_CONTENT, Strings.create(
						"Your conversation has " + liveTurns + " turns. "
						+ "Call compact(summary) now to free context space before continuing.")));
			}

			// Invoke L3
			ACell l3Result = invokeLevel3(llmOperation, frameL3Config, fullHistory, tools, ctx);

			if (!hasToolCalls(l3Result)) {
				// Text-only response — this completes the goal.
				AString content = RT.ensureString(RT.getIn(l3Result, K_CONTENT));

				// Typed outputs: parse the response as JSON (response_format
				// strict mode guarantees the LLM produces valid JSON matching
				// the schema). Return the parsed structured value.
				if (typedOutputs && content != null) {
					try {
						ACell parsed = convex.core.util.JSON.parse(content.toString());
						frames = updateFrame(frames, frameIndex,
							GoalTreeContext.appendTurn(activeFrame, l3Result));
						return FrameResult.complete(parsed);
					} catch (Exception e) {
						// Schema enforcement should prevent this, but if the LLM
						// somehow bails to non-JSON text, nudge it to retry.
						log.warn("Frame[{}] iter={} typed output did not parse as JSON: {}",
							frameIndex, iteration, e.getMessage());
						activeFrame = GoalTreeContext.appendTurn(activeFrame, l3Result);
						AMap<AString, ACell> nudge = Maps.of(
							K_ROLE, ROLE_USER,
							K_CONTENT, Strings.create(
								"Your response did not parse as JSON. The output must conform "
								+ "to the declared schema. Respond again with valid JSON."));
						activeFrame = GoalTreeContext.appendTurn(activeFrame, nudge);
						frames = updateFrame(frames, frameIndex, activeFrame);
						continue;
					}
				}

				// Untyped: text is the result
				frames = updateFrame(frames, frameIndex,
					GoalTreeContext.appendTurn(activeFrame, l3Result));
				return FrameResult.complete(content);
			}

			// Record assistant message (with tool calls) in conversation
			activeFrame = GoalTreeContext.appendTurn(activeFrame, l3Result);

			// Process each tool call
			AVector<ACell> toolCalls = getToolCalls(l3Result);
			log.info("Frame[{}] iter={} tools={}", frameIndex, iteration,
				toolCalls.count() > 0 ? toolCalls.toString().substring(0, Math.min(200, toolCalls.toString().length())) : "0");
			for (long t = 0; t < toolCalls.count(); t++) {
				ACell tc = toolCalls.get(t);
				AString toolCallId = RT.ensureString(RT.getIn(tc, K_ID));
				String toolName = RT.ensureString(RT.getIn(tc, K_NAME)).toString();
				ACell toolInput = ensureParsedInput(RT.getIn(tc, K_ARGUMENTS));

				ACell toolResult;

				if (TOOL_COMPLETE.equals(toolName)) {
					// Flattened: the entire tool input IS the result.
					// With typed outputs, OpenAI strictTools enforces schema
					// conformance at the API level — by the time we see the
					// call, toolInput already matches the declared schema.
					activeFrame = GoalTreeContext.appendTurn(activeFrame,
						toolResultMessage(toolCallId, toolName, Maps.of(Strings.create("status"), Strings.create("complete"))));
					frames = updateFrame(frames, frameIndex, activeFrame);
					return FrameResult.complete(toolInput);

				} else if (TOOL_FAIL.equals(toolName)) {
					// Flattened: the entire tool input IS the error.
					activeFrame = GoalTreeContext.appendTurn(activeFrame,
						toolResultMessage(toolCallId, toolName, Maps.of(Strings.create("status"), Strings.create("failed"))));
					frames = updateFrame(frames, frameIndex, activeFrame);
					return FrameResult.failed(toolInput, frames);

				} else if (TOOL_COMPACT.equals(toolName)) {
					String summary = RT.ensureString(RT.getIn(toolInput, Strings.create("summary"))).toString();
					long turnsBefore = GoalTreeContext.countLiveTurns(activeFrame);
					// Defer compaction to the start of the next iteration — compacting
					// now would archive the current assistant message, orphaning any
					// remaining tool results in this batch
					pendingCompactSummary = summary;
					toolResult = Strings.create("Compacted " + turnsBefore + " turns into segment. Context freed.");

				} else if (TOOL_CONTEXT_LOAD.equals(toolName)) {
					AString path = RT.ensureString(RT.getIn(toolInput, Strings.create("path")));
					if (path == null) {
						toolResult = Strings.create("Error: path is required");
					} else {
						long budget = 500;
						ACell budgetCell = RT.getIn(toolInput, Strings.create("budget"));
						if (budgetCell instanceof CVMLong l) {
							budget = Math.max(256, Math.min(l.longValue(), 10_000));
						}
						AString label = RT.ensureString(RT.getIn(toolInput, Strings.create("label")));
						AMap<AString, ACell> entryMeta = Maps.of(
							Strings.create("budget"), CVMLong.create(budget),
							Strings.create("ts"), CVMLong.create(convex.core.util.Utils.getCurrentTimestamp()));
						if (label != null) entryMeta = entryMeta.assoc(Strings.create("label"), label);
						activeFrame = GoalTreeContext.addLoad(activeFrame, path, entryMeta);
						toolResult = Maps.of(
							Strings.create("path"), path,
							Strings.create("loaded"), CVMBool.TRUE,
							Strings.create("budget"), CVMLong.create(budget),
							Strings.create("note"), Strings.create("Path will appear in context next turn."));
					}

				} else if (TOOL_CONTEXT_UNLOAD.equals(toolName)) {
					AString path = RT.ensureString(RT.getIn(toolInput, Strings.create("path")));
					if (path == null) {
						toolResult = Strings.create("Error: path is required");
					} else if (GoalTreeContext.getLoads(activeFrame).get(path) == null) {
						toolResult = Strings.create("Error: path not loaded: " + path);
					} else {
						activeFrame = GoalTreeContext.removeLoad(activeFrame, path);
						toolResult = Maps.of(
							Strings.create("path"), path,
							Strings.create("unloaded"), CVMBool.TRUE);
					}

				} else if (TOOL_MORE_TOOLS.equals(toolName)) {
					ACell opsCell = RT.getIn(toolInput, Strings.create("operations"));
					if (!(opsCell instanceof AVector)) {
						toolResult = Strings.create("Error: operations must be an array of operation paths");
					} else {
						AVector<ACell> ops = (AVector<ACell>) opsCell;
						// Resolve operations into tool definitions using the
						// same pipeline as ContextBuilder.withTools
						ContextBuilder resolver = new ContextBuilder(engine, ctx);
						java.util.Map<String, AString> newToolMap = new java.util.HashMap<>();
						AVector<ACell> newTools = resolver.buildConfigTools(ops, newToolMap);

						// Deduplicate against existing tools
						java.util.Set<String> existing = new java.util.HashSet<>();
						for (long j = 0; j < baseTools.count(); j++) {
							ACell et = baseTools.get(j);
							if (et instanceof AMap) {
								ACell n = ((AMap<?,?>) et).get(K_NAME);
								if (n != null) existing.add(n.toString());
							}
						}

						AVector<ACell> added = Vectors.empty();
						for (long j = 0; j < newTools.count(); j++) {
							ACell et = newTools.get(j);
							String n = null;
							if (et instanceof AMap) {
								ACell nc = ((AMap<?,?>) et).get(K_NAME);
								if (nc != null) n = nc.toString();
							}
							if (n != null && !existing.contains(n)) {
								baseTools = baseTools.conj(et);
								configToolMap.put(n, newToolMap.get(n));
								added = added.conj(Strings.create(n));
							}
						}

						toolResult = Maps.of(
							Strings.create("added"), added,
							Strings.create("total_tools"), CVMLong.create(baseTools.count()),
							Strings.create("note"), Strings.create("Tools available on your next turn."));
						log.info("more_tools: added {} tools", added.count());
					}

				} else if (TOOL_SUBGOAL.equals(toolName)) {
					String desc = RT.ensureString(RT.getIn(toolInput, Strings.create("description"))).toString();
					log.info("Subgoal pushed: {}", desc);

					// Push child frame with inherited loads (copy-on-push)
					AMap<AString, ACell> parentLoads = GoalTreeContext.getLoads(activeFrame);
					AMap<AString, ACell> childFrame = GoalTreeContext.createFrame(desc, parentLoads);
					frames = updateFrame(frames, frameIndex, activeFrame);
					AVector<ACell> childFrames = frames.conj(childFrame);

					// Recurse into child. Child frames don't inherit typed
					// outputs — a subgoal's contract is "return any value to
					// the parent", not the parent's typed output schema. The
					// child also gets responseFormat stripped from its L3
					// config (handled inside the recursive runFrame).
					FrameResult childResult = runFrame(job, childFrames, frameIndex + 1,
						config, llmOperation, baseTools, configToolMap, caps, ctx, systemMessages, null);

					// Pop child — result becomes tool result in parent
					AMap<AString, ACell> resultMap = Maps.of(
						Strings.create("status"), Strings.create(childResult.status()));
					if (childResult.value() != null) {
						resultMap = resultMap.assoc(Strings.create("result"), childResult.value());
					}
					toolResult = resultMap;

				} else {
					// Config tool or grid dispatch
					toolResult = dispatchTool(toolName, toolInput, configToolMap, caps, ctx);
				}

				// Record tool result in conversation
				activeFrame = GoalTreeContext.appendTurn(activeFrame,
					toolResultMessage(toolCallId, toolName, toolResult));
			}

			// Update frame in stack for next iteration
			frames = updateFrame(frames, frameIndex, activeFrame);
		}

		log.warn("GoalTreeAdapter: max iterations reached for frame");
		return FrameResult.failed(Strings.create("Max iterations reached"), frames);
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

	/** Returns true if the tool set includes the compact tool. */
	private static boolean hasCompactTool(AVector<ACell> tools) {
		for (long i = 0; i < tools.count(); i++) {
			ACell tool = tools.get(i);
			if (tool instanceof AMap) {
				ACell name = ((AMap<?,?>) tool).get(Strings.intern("name"));
				if (name != null && TOOL_COMPACT.equals(name.toString())) return true;
			}
		}
		return false;
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

	/** Extracts merged config from record-level and state-level config. */
	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> extractConfig(AMap<AString, ACell> recordConfig, ACell state) {
		AMap<AString, ACell> stateConfig = null;
		if (state != null) {
			ACell sc = RT.getIn(state, K_CONFIG);
			if (sc instanceof AMap) stateConfig = (AMap<AString, ACell>) sc;
		}
		if (recordConfig == null) return stateConfig;
		if (stateConfig == null) return recordConfig;
		// State config takes precedence
		return (AMap<AString, ACell>) recordConfig.merge(stateConfig);
	}
}
