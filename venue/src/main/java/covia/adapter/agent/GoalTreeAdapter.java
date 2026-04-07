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
 * <pre>{@code {"operation": "goaltree:chat"}}</pre>
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
	static final int MAX_ITERATIONS = 20;

	static final String TOOL_SUBGOAL        = "subgoal";
	static final String TOOL_COMPLETE       = "complete";
	static final String TOOL_FAIL           = "fail";
	static final String TOOL_COMPACT        = "compact";
	static final String TOOL_CONTEXT_LOAD   = "context_load";
	static final String TOOL_CONTEXT_UNLOAD = "context_unload";

	// ========== Harness tool definitions ==========

	private static final AString K_DESCRIPTION = Strings.intern("description");
	private static final AString K_PARAMETERS  = Strings.intern("parameters");
	private static final AString K_TYPE        = Strings.intern("type");
	private static final AString K_PROPERTIES  = Strings.intern("properties");
	private static final AString K_REQUIRED    = Strings.intern("required");
	private static final AString K_RESPONSE    = Strings.intern("response");
	private static final AString K_HISTORY     = Strings.intern("history");

	static final AMap<AString, ACell> TOOL_DEF_SUBGOAL = Maps.of(
		K_NAME, Strings.create(TOOL_SUBGOAL),
		K_DESCRIPTION, Strings.create(
			"Open a subgoal frame. Brackets a section of work — the child sees its "
			+ "description, ancestor context (summarised), and inherited loads. Runs "
			+ "until complete() or fail() is called, or a text-only response is given "
			+ "(implicit complete). Returns the child's structured result."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Strings.create("description"), Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create("What the subgoal should accomplish"))),
			K_REQUIRED, Vectors.of(Strings.create("description"))));

	static final AMap<AString, ACell> TOOL_DEF_COMPLETE = Maps.of(
		K_NAME, Strings.create(TOOL_COMPLETE),
		K_DESCRIPTION, Strings.create(
			"Finish current goal with structured result. Returns to parent."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Strings.create("result"), Maps.of(
					K_DESCRIPTION, Strings.create("Structured result value")))));

	static final AMap<AString, ACell> TOOL_DEF_FAIL = Maps.of(
		K_NAME, Strings.create(TOOL_FAIL),
		K_DESCRIPTION, Strings.create(
			"Fail current goal with error info. Parent can retry, skip, or escalate."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Strings.create("error"), Maps.of(
					K_DESCRIPTION, Strings.create("Structured error information")))));

	static final AMap<AString, ACell> TOOL_DEF_COMPACT = Maps.of(
		K_NAME, Strings.create(TOOL_COMPACT),
		K_DESCRIPTION, Strings.create(
			"Checkpoint your conversation so far. Live turns become a compacted segment "
			+ "with your summary. Frees context space for continued work. Data is not lost "
			+ "— the segment retains all turns, just rendered at reduced detail."),
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
			"Pin a lattice path to your persistent loaded context. "
			+ "The path is resolved fresh each turn and injected as a system message. "
			+ "Use for reference material you need across multiple turns. "
			+ "Loads are scoped to your current frame — children inherit them, "
			+ "but your loads are restored when a child completes. "
			+ "For one-shot reads, use inspect instead."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Strings.create("path"), Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create("Lattice path to load (e.g. w/docs/rules, n/notes)")),
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
			"Remove a path from your persistent loaded context. "
			+ "Frees the budget allocated to that path."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Strings.create("path"), Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create("Lattice path to unload"))),
			K_REQUIRED, Vectors.of(Strings.create("path"))));

	/** Harness tools for root frame — includes subgoal for decomposition */
	@SuppressWarnings("unchecked")
	static final AVector<ACell> HARNESS_TOOLS = Vectors.of(
		(ACell) TOOL_DEF_SUBGOAL,
		(ACell) TOOL_DEF_COMPLETE,
		(ACell) TOOL_DEF_FAIL,
		(ACell) TOOL_DEF_COMPACT,
		(ACell) TOOL_DEF_CONTEXT_LOAD,
		(ACell) TOOL_DEF_CONTEXT_UNLOAD);

	/** Harness tools for child frames — no subgoal (complete/fail/compact only) */
	@SuppressWarnings("unchecked")
	static final AVector<ACell> CHILD_HARNESS_TOOLS = Vectors.of(
		(ACell) TOOL_DEF_COMPLETE,
		(ACell) TOOL_DEF_FAIL,
		(ACell) TOOL_DEF_COMPACT,
		(ACell) TOOL_DEF_CONTEXT_LOAD,
		(ACell) TOOL_DEF_CONTEXT_UNLOAD);

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
		installAsset("/adapters/goaltree/chat.json");
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
				ACell output = processGoal(ctx, input);
				job.completeWith(output);
			} catch (Exception e) {
				log.error("GoalTreeAdapter error", e);
				job.fail(e.getMessage());
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
	ACell processGoal(RequestContext ctx, ACell input) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		ACell state = RT.getIn(input, AgentState.KEY_STATE);
		AVector<ACell> messages = (AVector<ACell>) RT.getIn(input, Fields.MESSAGES);
		AVector<ACell> tasks = (AVector<ACell>) RT.getIn(input, Fields.TASKS);
		AVector<ACell> pending = (AVector<ACell>) RT.getIn(input, Fields.PENDING);

		AMap<AString, ACell> recordConfig = (RT.getIn(input, AgentState.KEY_CONFIG) instanceof AMap m) ? m : null;
		AMap<AString, ACell> config = extractConfig(recordConfig, state);

		// Generate root goal description from incoming work
		String rootDescription = GoalTreeContext.describeTransitionInput(messages, tasks, pending);

		// Build context (system prompt, tools, caps) via ContextBuilder
		ContextBuilder builder = new ContextBuilder(engine, ctx);
		ContextBuilder.ContextResult context = builder
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
		AString llmOperation = getLLMOperation(config);

		// Set up capability-scoped context for tool dispatch
		RequestContext capsCtx = context.capsCtx();

		// Run the root frame
		FrameResult result = runFrame(frames, 0, config, llmOperation, baseTools,
			configToolMap, caps, capsCtx, context.history());

		// Build response text from root result
		String responseText = "";
		if (result.value() != null) {
			AString s = RT.ensureString(result.value());
			responseText = (s != null) ? s.toString() : result.value().toString();
		}

		// Persist: save root frame conversation in state for next transition's context
		AMap<AString, ACell> newState = Maps.of(
			K_HISTORY, Vectors.empty()); // goal tree doesn't use flat history
		if (config != null) {
			newState = newState.assoc(K_CONFIG, config);
		}

		AMap<AString, ACell> output = Maps.of(
			AgentState.KEY_STATE, newState,
			Fields.RESULT, Maps.of(K_RESPONSE, Strings.create(responseText)));

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
	 */
	record FrameResult(String status, ACell value) {
		static FrameResult complete(ACell value) { return new FrameResult("complete", value); }
		static FrameResult failed(ACell error) { return new FrameResult("failed", error); }
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
	 * @return the frame's result
	 */
	@SuppressWarnings("unchecked")
	FrameResult runFrame(AVector<ACell> frames, int frameIndex,
			AMap<AString, ACell> config, AString llmOperation,
			AVector<ACell> baseTools, Map<String, AString> configToolMap,
			AVector<ACell> caps, RequestContext ctx, AVector<ACell> systemMessages) {

		// Assemble tools = harness tools + configured tools
		// Root frames get all harness tools; child frames get only complete/fail/compact
		// (no subgoal — prevents unnecessary nesting from smaller models)
		AVector<ACell> harnessForFrame = (frameIndex == 0)
			? HARNESS_TOOLS
			: CHILD_HARNESS_TOOLS;
		AVector<ACell> tools = (AVector<ACell>) harnessForFrame.concat(baseTools);

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

			// Context map
			long consumed = fullHistory.count() * 100; // rough estimate
			AMap<AString, ACell> ctxMap = GoalTreeContext.renderContextMap(
				frames, consumed, ContextBuilder.DEFAULT_BUDGET);
			fullHistory = fullHistory.conj(ctxMap);

			// Conversation (segments + live turns — includes goal as first user message)
			AVector<ACell> convMessages = GoalTreeContext.renderConversation(activeFrame);
			fullHistory = (AVector<ACell>) fullHistory.concat(convMessages);

			// Invoke L3
			ACell l3Result = invokeLevel3(llmOperation, config, fullHistory, tools, ctx);

			if (!hasToolCalls(l3Result)) {
				// Text-only = implicit complete
				AString content = RT.ensureString(RT.getIn(l3Result, K_CONTENT));
				// Record the assistant message in frame conversation
				frames = updateFrame(frames, frameIndex,
					GoalTreeContext.appendTurn(activeFrame, l3Result));
				return FrameResult.complete(content);
			}

			// Record assistant message (with tool calls) in conversation
			activeFrame = GoalTreeContext.appendTurn(activeFrame, l3Result);

			// Process each tool call
			AVector<ACell> toolCalls = getToolCalls(l3Result);
			for (long t = 0; t < toolCalls.count(); t++) {
				ACell tc = toolCalls.get(t);
				AString toolCallId = RT.ensureString(RT.getIn(tc, K_ID));
				String toolName = RT.ensureString(RT.getIn(tc, K_NAME)).toString();
				ACell toolInput = ensureParsedInput(RT.getIn(tc, K_ARGUMENTS));

				ACell toolResult;

				if (TOOL_COMPLETE.equals(toolName)) {
					ACell result = RT.getIn(toolInput, Strings.create("result"));
					// Record tool result before returning
					activeFrame = GoalTreeContext.appendTurn(activeFrame,
						toolResultMessage(toolCallId, toolName, Maps.of(Strings.create("status"), Strings.create("complete"))));
					frames = updateFrame(frames, frameIndex, activeFrame);
					return FrameResult.complete(result);

				} else if (TOOL_FAIL.equals(toolName)) {
					ACell error = RT.getIn(toolInput, Strings.create("error"));
					activeFrame = GoalTreeContext.appendTurn(activeFrame,
						toolResultMessage(toolCallId, toolName, Maps.of(Strings.create("status"), Strings.create("failed"))));
					frames = updateFrame(frames, frameIndex, activeFrame);
					return FrameResult.failed(error);

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

				} else if (TOOL_SUBGOAL.equals(toolName)) {
					String desc = RT.ensureString(RT.getIn(toolInput, Strings.create("description"))).toString();

					// Push child frame with inherited loads (copy-on-push)
					AMap<AString, ACell> parentLoads = GoalTreeContext.getLoads(activeFrame);
					AMap<AString, ACell> childFrame = GoalTreeContext.createFrame(desc, parentLoads);
					frames = updateFrame(frames, frameIndex, activeFrame);
					AVector<ACell> childFrames = frames.conj(childFrame);

					// Recurse into child
					FrameResult childResult = runFrame(childFrames, frameIndex + 1,
						config, llmOperation, baseTools, configToolMap, caps, ctx, systemMessages);

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
		return FrameResult.failed(Strings.create("Max iterations reached"));
	}

	// ========== Helpers ==========

	/** Updates a frame at the given index in the frame stack. */
	private static AVector<ACell> updateFrame(AVector<ACell> frames, int index, ACell frame) {
		return frames.assoc(index, frame);
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
