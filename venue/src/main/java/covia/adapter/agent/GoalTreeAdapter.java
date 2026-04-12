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
 * sub-tasks, {@code complete}/{@code fail} to return results, and
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
	private static final AString K_RESPONSE    = Strings.intern("response");
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
			+ "Use when a task has distinct parts you want done separately (e.g. "
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
			+ "space so you can continue working on long tasks. Call this after completing "
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
			"You are inside a subgoal. Complete the specific task described below. "
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
		AVector<ACell> typedRootHarnessTools = (outputs != null)
			? buildTypedRootHarnessTools(outputs, config) : null;
		AMap<AString, ACell> l3Config = (outputs != null && config != null)
			? config.dissoc(K_RESPONSE_FORMAT) : config;

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
		AVector<ACell> harnessTools = (typedRootHarnessTools != null)
			? typedRootHarnessTools : resolveHarnessTools(config);
		AVector<ACell> allTools = (AVector<ACell>) harnessTools.concat(baseTools);

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
		throw new UnsupportedOperationException("GoalTreeAdapter uses invoke() with direct job control");
	}

	@Override
	public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		job.setStatus(Strings.create("STARTED"));
		CompletableFuture.runAsync(() -> {
			try {
				ACell output = processGoal(job, ctx, input);
				if (!job.isFinished()) {
					job.completeWith(output);
				}
			} catch (Exception e) {
				log.error("GoalTreeAdapter error", e);
				if (!job.isFinished()) job.fail(e.getMessage());
			}
		}, VIRTUAL_EXECUTOR);
	}

	/**
	 * Processes a single agent transition using the goal tree model.
	 *
	 * <p>Creates a fresh root frame from incoming work, runs the frame's tool loop,
	 * and returns the transition output ({@code {state, result}}).</p>
	 *
	 * @param ctx request context (caller identity, capabilities)
	 * @param input transition input: {@code {agentId, state, messages, tasks, pending}}
	 * @return transition output: {@code {state, result}}
	 */
	@SuppressWarnings("unchecked")
	ACell processGoal(Job job, RequestContext ctx, ACell input) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		ACell state = RT.getIn(input, AgentState.KEY_STATE);
		AVector<ACell> messages = (AVector<ACell>) RT.getIn(input, Fields.MESSAGES);
		AVector<ACell> tasks = (AVector<ACell>) RT.getIn(input, Fields.TASKS);
		AVector<ACell> pending = (AVector<ACell>) RT.getIn(input, Fields.PENDING);

		AMap<AString, ACell> recordConfig = (RT.getIn(input, AgentState.KEY_CONFIG) instanceof AMap m) ? m : null;
		AMap<AString, ACell> config = extractConfig(recordConfig, state);

		// Resolve typed outputs declaration. When set, the agent's complete/
		// fail tools become typed (parameters carry the user's schema), the
		// text-only path is rejected (LLM must call complete or fail), and
		// responseFormat is suppressed at the L3 level — schema enforcement
		// happens via OpenAI strictTools on the tool call arguments instead.
		AMap<AString, ACell> outputs = resolveOutputs(config);
		AVector<ACell> typedRootHarnessTools = (outputs != null)
			? buildTypedRootHarnessTools(outputs, config)
			: null;

		// When outputs are typed, drop responseFormat from the config we pass
		// to L3 — strict mode would otherwise also try to constrain the (now
		// non-existent) text content path, which conflicts with our tool-only
		// completion model.
		AMap<AString, ACell> l3Config = (outputs != null && config != null)
			? config.dissoc(K_RESPONSE_FORMAT)
			: config;

		// Generate root goal description from incoming work
		String rootDescription = GoalTreeContext.describeTransitionInput(messages, tasks, pending);

		// Build context (system prompt, tools, caps) via ContextBuilder.
		// Harness tool names in config.tools are skipped here — they're
		// resolved separately by resolveHarnessTools / buildTypedRootHarnessTools.
		ContextBuilder builder = new ContextBuilder(engine, ctx);
		ContextBuilder.ContextResult context = builder
			.withSkipToolNames(HARNESS_TOOL_REGISTRY.keySet())
			.withConfig(recordConfig, state)
			.withSystemPrompt(Vectors.empty()) // fresh transition, no prior history
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

		// Run the root frame
		FrameResult result = runFrame(job, frames, 0, l3Config, llmOperation, baseTools,
			configToolMap, caps, capsCtx, context.history(), typedRootHarnessTools);

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

		AMap<AString, ACell> output = Maps.of(
			AgentState.KEY_STATE, newState,
			Fields.RESULT, Maps.of(K_RESPONSE, result.value()));

		// Auto-complete all pending tasks with the root goal result
		if (tasks != null && tasks.count() > 0) {
			AMap<AString, ACell> taskResults = Maps.empty();
			AString statusKey = "complete".equals(result.status())
				? covia.grid.Status.COMPLETE : covia.grid.Status.FAILED;
			for (long i = 0; i < tasks.count(); i++) {
				AString jobId = RT.ensureString(RT.getIn(tasks.get(i), Fields.JOB_ID));
				if (jobId != null) {
					taskResults = taskResults.assoc(jobId, Maps.of(
						Fields.STATUS, statusKey,
						Fields.OUTPUT, result.value()));
				}
			}
			if (taskResults.count() > 0) {
				output = output.assoc(Fields.TASK_RESULTS, taskResults);
			}
		}

		return output;
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
	 * @param typedRootHarnessTools per-agent typed root harness tools (with
	 *        schema-enforced complete/fail), or null for untyped agents.
	 *        Only applied at root frame; child frames resolve their own
	 *        harness tools from config (excluding subgoal to prevent nesting).
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

		// Harness tool selection: root frames use the typed harness tools
		// (if outputs declared) or the config-resolved harness tools.
		// Child frames get the same config-resolved set minus subgoal
		// (prevents unnecessary nesting from smaller models).
		AVector<ACell> harnessForFrame;
		if (frameIndex == 0) {
			harnessForFrame = (typedRootHarnessTools != null)
				? typedRootHarnessTools
				: resolveHarnessTools(config);
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
		boolean typedOutputs = (frameIndex == 0 && typedRootHarnessTools != null);

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
			ACell l3Result = invokeLevel3(llmOperation, config, fullHistory, tools, ctx);

			if (!hasToolCalls(l3Result)) {
				// Text-only response — handling depends on whether the agent
				// has declared typed outputs.
				AString content = RT.ensureString(RT.getIn(l3Result, K_CONTENT));

				// Typed outputs: text-only is forbidden — the LLM MUST call
				// complete() or fail() to deliver a result. Nudge it back.
				if (typedOutputs) {
					log.info("Frame[{}] iter={} text response rejected — typed outputs require complete()/fail()",
						frameIndex, iteration);
					activeFrame = GoalTreeContext.appendTurn(activeFrame, l3Result);
					AMap<AString, ACell> nudge = Maps.of(
						K_ROLE, ROLE_USER,
						K_CONTENT, Strings.create(
							"Your response was plain text, but this agent has typed outputs. "
							+ "You must call either complete(result=...) with the structured "
							+ "result, or fail(error=...) with a structured error. Text-only "
							+ "responses are not accepted."));
					activeFrame = GoalTreeContext.appendTurn(activeFrame, nudge);
					frames = updateFrame(frames, frameIndex, activeFrame);
					continue;
				}

				// Legacy responseFormat path: validate content parses as JSON
				// when a schema is declared. Strict mode usually catches this
				// server-side but some providers (and pre-strict-mode setups)
				// bail to plain text on tool errors — catch that here.
				if (content != null && hasSchemaResponseFormat(config)) {
					boolean valid;
					try {
						convex.core.util.JSON.parse(content.toString());
						valid = true;
					} catch (Exception e) { valid = false; }
					if (!valid) {
						log.info("Frame[{}] iter={} text response does not parse as JSON; nudging LLM",
							frameIndex, iteration);
						activeFrame = GoalTreeContext.appendTurn(activeFrame, l3Result);
						AMap<AString, ACell> nudge = Maps.of(
							K_ROLE, ROLE_USER,
							K_CONTENT, Strings.create(
								"Your response was plain text, but a JSON schema is required. "
								+ "Respond with valid JSON matching the schema. If a tool call "
								+ "failed or data was unavailable, populate the relevant fields "
								+ "with safe defaults (empty string, false, 0, empty array) and "
								+ "describe the issue in a warnings or detail field — never "
								+ "abandon the schema."));
						activeFrame = GoalTreeContext.appendTurn(activeFrame, nudge);
						frames = updateFrame(frames, frameIndex, activeFrame);
						continue;
					}
				}

				// Record the assistant message in frame conversation
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

					// Recurse into child. Child frames don't inherit typed root
					// harness tools — they always use the generic complete/fail
					// (a subgoal's contract is "return any value to the parent",
					// not the parent's typed output schema). Pass null so the
					// child uses CHILD_HARNESS_TOOLS regardless of typing.
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
				"Finish your goal with the structured result. This is the ONLY way to "
				+ "successfully complete — text-only responses are not accepted. "
				+ "Parameters are schema-enforced; make sure you have gathered "
				+ "enough data via tool calls to populate every required field before "
				+ "calling this."),
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
				"Report that your goal cannot be completed. Use this when a required "
				+ "tool call has failed, data is missing, or capability denials prevent "
				+ "progress. Parameters are schema-enforced — provide a clear "
				+ "explanation in the required fields. Do not bail to text — call this "
				+ "tool instead."),
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
