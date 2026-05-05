package covia.adapter.agent;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.json.schema.JsonSchema;
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
import covia.adapter.AgentAdapter;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.AgentState;
import covia.venue.RequestContext;

/**
 * LLM-backed transition function for agents (level 2).
 *
 * <p>Invoked by the agent run loop as a transition operation ({@code llmagent:chat}).
 * Maintains conversation history in the agent's {@code state} field and delegates
 * LLM calls to a level 3 grid operation (e.g. {@code langchain:openai}).</p>
 *
 * <p>This adapter has no LLM library dependencies — it works entirely with
 * structured message maps and invokes level 3 via the grid operation dispatch.</p>
 *
 * <h3>Tool palette</h3>
 * <p>Unless disabled via {@code defaultTools: false}, agents start with the
 * tool set in {@link #DEFAULT_TOOL_OPS} (covia CRUD, agent lifecycle, asset
 * management, schema, grid). Task tools ({@code complete_task},
 * {@code fail_task}) are added dynamically when tasks are pending.</p>
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
 *   <li>{@code {role: "assistant", content: "...", toolCalls?: [{id, name, arguments}]}}</li>
 *   <li>{@code {role: "tool", id: "...", name: "...", content: "..."}}</li>
 * </ul>
 *
 * <h3>Tool call loop</h3>
 * <p>When level 3 returns an assistant message with {@code toolCalls}, level 2
 * executes each tool as a grid operation, appends tool result messages, and
 * calls level 3 again. This loops until the LLM returns a text response
 * (no tool calls) or {@link #MAX_TOOL_ITERATIONS} is reached.</p>
 *
 * <h3>State structure</h3>
 * <pre>{@code
 * { "config": {
 *     "llmOperation": "v/ops/langchain/openai",
 *     "model": "gpt-4o-mini",
 *     "systemPrompt": "You are...",
 *     "tools": [{name, description, parameters}]
 *   },
 *   "history": [
 *     { "role": "system",    "content": "You are..." },
 *     { "role": "user",      "content": "Hello" },
 *     { "role": "assistant", "content": "Hi there!" }
 *   ]
 * }}</pre>
 */
public class LLMAgentAdapter extends AbstractLLMAdapter {

	private static final Logger log = LoggerFactory.getLogger(LLMAgentAdapter.class);

	// State field keys
	private static final AString K_LOADS = Strings.intern("loads");

	// Config keys specific to this adapter (parent provides K_CONFIG, K_LLM_OPERATION,
	// K_MODEL, K_SYSTEM_PROMPT, K_URL, K_API_KEY, K_TOOLS, K_RESPONSE_FORMAT,
	// K_CONTEXT, K_CAPS, K_TOOL_CALL_TIMEOUT_MS).
	private static final AString K_DEFAULT_TOOLS   = Strings.intern("defaultTools");

	// Built-in tool names (only task tools remain as built-ins)
	private static final String TOOL_COMPLETE_TASK = "complete_task";
	private static final String TOOL_FAIL_TASK     = "fail_task";

	/** Maximum tool call loop iterations to prevent runaway loops */
	static final int MAX_TOOL_ITERATIONS = 20;

	// ========== Default tool definitions ==========
	// MCP-style: {name, description, parameters: {type: "object", properties: {...}, required: [...]}}

	private static final AMap<AString, ACell> TOOL_DEF_COMPLETE_TASK = Maps.of(
		K_NAME, Strings.create(TOOL_COMPLETE_TASK),
		K_DESCRIPTION, Strings.create(
			"Complete the in-scope task with a result. "
			+ "Call this once you have produced the output the requester asked for. "
			+ "The output will be delivered to the caller. "
			+ "The agent and task are determined from the current request context — you do not pass an id."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Fields.RESULT, Maps.of(
					K_DESCRIPTION, Strings.create("The result to return to the requester. Any JSON value — string, object, array, etc."))
			)
		)
	);

	private static final AMap<AString, ACell> TOOL_DEF_FAIL_TASK = Maps.of(
		K_NAME, Strings.create(TOOL_FAIL_TASK),
		K_DESCRIPTION, Strings.create(
			"Reject or fail the in-scope task. Call this when you cannot fulfil the request — "
			+ "e.g. the task is outside your capabilities or the input is invalid. "
			+ "The agent and task are determined from the current request context — you do not pass an id."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Fields.ERROR, Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create("Human-readable explanation of why the task cannot be completed"))
			),
			K_REQUIRED, Vectors.of(Strings.create("error"))
		)
	);

	// Built-in context tool names
	private static final String TOOL_CONTEXT_LOAD   = "context_load";
	private static final String TOOL_CONTEXT_UNLOAD = "context_unload";

	private static final AMap<AString, ACell> TOOL_DEF_CONTEXT_LOAD = Maps.of(
		K_NAME, Strings.create(TOOL_CONTEXT_LOAD),
		K_DESCRIPTION, Strings.create(
			"Add a lattice path to your persistent loaded context. "
			+ "The path is resolved fresh each turn and injected as a system message. "
			+ "Use for reference material you need across multiple turns. "
			+ "For one-shot reads, use inspect instead. Effect takes place next turn."),
		K_PARAMETERS, CONTEXT_LOAD_PARAMS);

	private static final AMap<AString, ACell> TOOL_DEF_CONTEXT_UNLOAD = Maps.of(
		K_NAME, Strings.create(TOOL_CONTEXT_UNLOAD),
		K_DESCRIPTION, Strings.create(
			"Remove a path from your persistent loaded context. "
			+ "Frees the budget allocated to that path. "
			+ "Cannot unload pinned context entries from config."),
		K_PARAMETERS, CONTEXT_UNLOAD_PARAMS);

	/** Context tools — always available to agents */
	private static final AVector<ACell> CONTEXT_TOOLS = (AVector<ACell>) Vectors.of(
		(ACell) TOOL_DEF_CONTEXT_LOAD,
		(ACell) TOOL_DEF_CONTEXT_UNLOAD
	);

	/** Default tool operations — resolved via buildConfigTools at runtime */
	private static final AVector<ACell> DEFAULT_TOOL_OPS = (AVector<ACell>) Vectors.of(
		(ACell) Strings.create("v/ops/agent/create"),
		(ACell) Strings.create("v/ops/agent/message"),
		(ACell) Strings.create("v/ops/agent/request"),
		(ACell) Strings.create("v/ops/asset/store"),
		(ACell) Strings.create("v/ops/asset/get"),
		(ACell) Strings.create("v/ops/asset/list"),
		(ACell) Strings.create("v/ops/asset/content"),
		(ACell) Strings.create("v/ops/asset/pin"),
		(ACell) Strings.create("v/ops/grid/run"),
		(ACell) Strings.create("v/ops/grid/job-result"),
		(ACell) Strings.create("v/ops/covia/read"),
		(ACell) Strings.create("v/ops/covia/write"),
		(ACell) Strings.create("v/ops/covia/delete"),
		(ACell) Strings.create("v/ops/covia/append"),
		(ACell) Strings.create("v/ops/covia/slice"),
		(ACell) Strings.create("v/ops/covia/list"),
		(ACell) Strings.create("v/ops/covia/inspect"),
		(ACell) Strings.create("v/ops/schema/validate"),
		(ACell) Strings.create("v/ops/schema/infer")
	);

	/** Task tools only available when there are outstanding tasks */
	private static final AVector<ACell> TASK_TOOLS = (AVector<ACell>) Vectors.of(
		(ACell) TOOL_DEF_COMPLETE_TASK,
		(ACell) TOOL_DEF_FAIL_TASK
	);

	@Override
	public String getName() {
		return "llmagent";
	}

	@Override
	public String getDescription() {
		return "LLM-backed transition function for agents. Maintains conversation "
			+ "history in agent state, processes inbox messages as user turns, and "
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
		return CompletableFuture.supplyAsync(() -> processChat(ctx, input), VIRTUAL_EXECUTOR);
	}

	/**
	 * Builds the L3 input for {@code agent:context} inspection. Same context
	 * pipeline as {@link #processChat}'s first iteration — system prompt,
	 * context entries, tool palette — minus the actual LLM invocation. Appends
	 * the optional {@code taskInput} as a user goal message.
	 */
	@Override
	protected AMap<AString, ACell> buildInspectionInput(
			AMap<AString, ACell> recordConfig, ACell state, ACell taskInput, RequestContext ctx) {
		ContextBuilder builder = new ContextBuilder(engine, ctx);
		ContextBuilder.ContextResult context = builder
			.withConfig(recordConfig, state)
			.withSystemPrompt()
			.withContextEntries(state)
			.withTools()
			.build();

		AVector<ACell> history = context.history();
		if (taskInput != null) {
			ACell goalMsg = Maps.of(K_ROLE, ROLE_USER, K_CONTENT,
				Strings.create(taskInput.toString()));
			history = history.conj(goalMsg);
		}

		return buildL3Input(context.config(), history, context.tools());
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
	@SuppressWarnings("unchecked")
	ACell processChat(RequestContext ctx, ACell input) {
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

		// Extract dynamic loaded paths from state
		AMap<AString, ACell> existingLoads = extractLoads(state);

		// Build per-turn LLM context. Ephemeral context model:
		// (transcript model): system prompt + context entries + loads +
		// [Context Map] are rebuilt FRESH every turn and never persisted.
		// Only the persistent history (session frames) carries forward
		// via withFrameStack.
		//
		// Order matters: ephemeral background → history → this turn's
		// pending/inbox → empty signal. System prompt goes first for
		// primacy, new input goes last for recency.
		AVector<ACell> sessionFrames = AgentAdapter.sessionFrames(input);
		ContextBuilder builder = new ContextBuilder(engine, ctx);
		ContextBuilder.ContextResult context = builder
			.withConfig(recordConfig, state)
			.withSystemPrompt()                   // always fresh
			.withContextEntries(state)            // ephemeral
			.withLoadedPaths(existingLoads)       // ephemeral
			.withContextMap(existingLoads)        // ephemeral
			.withFrameStack(sessionFrames)        // session.frames → LLM messages
			.withPendingResults(pending)          // ephemeral (this turn)
			.withInboxMessages(messages)          // this turn's user input
			.withEmptyStateSignal(hasInput)
			.withTools()
			.build();

		// Safety valve — may prune loads if budget exceeded
		AMap<AString, ACell> activeLoads = builder.applySafetyValve(existingLoads);

		AVector<ACell> llmMessages = context.history();
		AVector<ACell> baseTools = context.tools();
		Map<String, AString> configToolMap = context.configToolMap();
		AMap<AString, ACell> config = context.config();
		AVector<ACell> caps = context.caps();

		// Add agent scope to the capability context — all tool calls carry the agentId
		// so adapters (e.g. CoviaAdapter) can resolve n/ paths to agent-private workspace
		RequestContext capsCtx = context.capsCtx().withAgentId(agentId);

		// Extract LLM operation from merged config
		AString llmOperation = getLLMOperation(config);

		// Task context is built dynamically per tool-loop iteration (not baked into
		// history) so the LLM only sees outstanding tasks, not already-resolved ones.

		// Create tool context for built-in tool execution
		long toolCallTimeoutMs = resolveToolCallTimeoutMs(config);
		ToolContext toolCtx = new ToolContext(agentId, capsCtx, tasks, pending, configToolMap, caps, activeLoads, toolCallTimeoutMs);

		// Invoke level 3 with tool call loop — returns all messages to append
		// ctx (uncapped) for the L3 LLM call; capsCtx flows through toolCtx for tool dispatch
		AVector<ACell> newMessages = invokeWithToolLoop(
			llmOperation, config, llmMessages, baseTools, ctx, toolCtx);

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

		// Session.history is the sole conversation record — the adapter
		// does not maintain its own transcript. State holds only config + loads.
		AMap<AString, ACell> newState = Maps.empty();
		if (config != null) {
			newState = newState.assoc(K_CONFIG, config);
		}
		AMap<AString, ACell> finalLoads = toolCtx.getLoads();
		if (finalLoads != null && finalLoads.count() > 0) {
			newState = newState.assoc(K_LOADS, finalLoads);
		}
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

		if (toolCtx.taskResults != null && toolCtx.taskResults.count() > 0) {
			// One-task-per-cycle: take the single entry
			var entry = toolCtx.taskResults.entrySet().iterator().next();
			ACell taskResult = entry.getValue();
			AString status = RT.ensureString(RT.getIn(taskResult, Fields.STATUS));
			if (Status.FAILED.equals(status)) {
				ACell err = RT.getIn(taskResult, Fields.ERROR);
				if (err != null) output = output.assoc(Fields.ERROR, err)
					.dissoc(Fields.RESPONSE);
			} else {
				ACell taskOutput = RT.getIn(taskResult, Fields.OUTPUT);
				if (taskOutput != null) {
					output = output.assoc(Fields.RESPONSE, taskOutput);
				}
			}
		}
		return output;
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
			AString llmOperation, AMap<AString, ACell> config,
			AVector<ACell> history, AVector<ACell> baseTools, RequestContext ctx,
			ToolContext toolCtx) {

		AVector<ACell> newMessages = Vectors.empty();

		for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {
			// Build level 3 input (full history including new messages from this loop)
			AVector<ACell> fullHistory = (AVector<ACell>) history.concat(newMessages);

			// Inject dynamic task context — only outstanding (unresolved) tasks
			ACell taskMsg = buildOutstandingTaskMessage(toolCtx);
			if (taskMsg != null) {
				fullHistory = fullHistory.conj(taskMsg);
			}

			// Include task tools when tasks remain; context tools always available
			AVector<ACell> tools = (taskMsg != null)
				? (AVector<ACell>) TASK_TOOLS.concat(CONTEXT_TOOLS).concat(baseTools)
				: (AVector<ACell>) CONTEXT_TOOLS.concat(baseTools);

			// Dispatch to level 3 — internal, no sub-Job created
			ACell l3Result = invokeLevel3(llmOperation, config, fullHistory, tools, ctx);

			// Level 3 returns an assistant message: {role, content?, toolCalls?}
			ACell toolCallsCell = RT.getIn(l3Result, K_TOOL_CALLS);
			boolean hasToolCalls = (toolCallsCell instanceof AVector) && ((AVector<ACell>) toolCallsCell).count() > 0;

			if (!hasToolCalls) {
				// Text-only response — validate against responseFormat schema if present
				if (config != null) {
					AMap<AString, ACell> rfSchema = getResponseFormatSchema(config);
					if (rfSchema != null) {
						// Parse the content as JSON and validate against the schema
						AString content = RT.ensureString(RT.getIn(l3Result, K_CONTENT));
						if (content != null) {
							try {
								ACell parsed = convex.core.util.JSON.parse(content.toString());
								String schemaErr = JsonSchema.validate(rfSchema, parsed);
								if (schemaErr != null) {
									log.warn("LLM response schema violation: {}", schemaErr);
								}
							} catch (Exception e) {
								log.warn("LLM response not valid JSON despite responseFormat: {}", e.getMessage());
							}
						}
					}
				}
				newMessages = newMessages.conj(l3Result);
				return newMessages;
			}

			// Tool call response — record assistant message and execute tools
			newMessages = newMessages.conj(l3Result);
			AVector<ACell> toolCalls = (AVector<ACell>) toolCallsCell;

			for (long i = 0; i < toolCalls.count(); i++) {
				ACell tc = toolCalls.get(i);
				AString id = RT.ensureString(RT.getIn(tc, K_ID));
				AString name = RT.ensureString(RT.getIn(tc, K_NAME));
				AString arguments = RT.ensureString(RT.getIn(tc, K_ARGUMENTS));

				// Parse arguments
				ACell toolInput;
				try {
					toolInput = (arguments != null)
						? convex.core.util.JSON.parse(arguments.toString())
						: Maps.empty();
				} catch (Exception e) {
					toolInput = Maps.empty();
				}

				// Execute the tool — built-in or grid dispatch
				ACell toolResult;
				try {
					String toolName = (name != null) ? name.toString() : "";
					toolResult = executeToolCall(toolName, toolInput, ctx, toolCtx);
				} catch (Exception e) {
					toolResult = Strings.create("Error: " + e.getMessage());
					log.warn("Tool execution failed: {} — {}", name, e.getMessage());
				}

				// Append tool result message via the shared base helper
				// (parent rule: AMap/AVector → structuredContent, else stringify
				// into content). Synthesises a stand-in name when the LLM omits
				// it — the message format requires a non-null name.
				String toolNameForMsg = (name != null) ? name.toString() : "unknown";
				newMessages = newMessages.conj(toolResultMessage(id, toolNameForMsg, toolResult));
			}

		}

		// Iteration limit reached
		log.warn("Tool call loop reached iteration limit ({})", MAX_TOOL_ITERATIONS);
		newMessages = newMessages.conj(Maps.of(
			K_ROLE, ROLE_ASSISTANT,
			K_CONTENT, Strings.create("I reached the maximum number of tool call iterations. Please try again with a simpler request.")
		));
		return newMessages;
	}

	// ========== Built-in tool execution ==========

	/**
	 * Executes a tool call, dispatching to built-in handlers or grid operations.
	 * Returns ACell — either an AString (for errors/simple text) or a structured
	 * AMap/AVector. The caller converts structured results to JSON strings for
	 * the tool message content.
	 */
	private ACell executeToolCall(String toolName, ACell input, RequestContext ctx, ToolContext toolCtx) {
		// Built-in task tools (must mutate ToolContext directly) — always allowed
		if (TOOL_COMPLETE_TASK.equals(toolName)) return handleCompleteTask(input, toolCtx);
		if (TOOL_FAIL_TASK.equals(toolName)) return handleFailTask(input, toolCtx);

		// Built-in context tools (harness-level, like task tools)
		if (TOOL_CONTEXT_LOAD.equals(toolName)) return handleContextLoad(input, toolCtx);
		if (TOOL_CONTEXT_UNLOAD.equals(toolName)) return handleContextUnload(input, toolCtx);

		// Cap-checked, timeout-bounded dispatch via the shared base path.
		// Resolves config tools, falls through to grid dispatch for unknown names.
		return dispatchTool(toolName, input, toolCtx.configToolMap,
			toolCtx.caps, toolCtx.ctx, toolCtx.toolCallTimeoutMs);
	}

	/**
	 * Completes the in-scope task with a result by invoking the
	 * {@code agent:completeTask} venue op. The op reads {@code agentId} and
	 * {@code taskId} from the {@link RequestContext} (populated by the framework
	 * for every transition cycle), so the LLM only supplies {@code result}.
	 *
	 * <p>The venue op completes the caller's pending task Job and removes the
	 * task entry from the agent's task Index. We also record into
	 * {@link ToolContext#taskResults} so the surrounding {@code processChat}
	 * can promote the structured task output into the transition's
	 * {@code response} field for the timeline (otherwise the timeline
	 * {@code result} would just be the empty content of the assistant's
	 * tool-call message).</p>
	 */
	private ACell handleCompleteTask(ACell input, ToolContext toolCtx) {
		ACell result = RT.getIn(input, Fields.RESULT);
		AMap<AString, ACell> opInput = (result != null)
			? Maps.of(Fields.RESULT, result)
			: Maps.empty();
		ACell opResult;
		try {
			opResult = engine.jobs().invokeInternal(
				"v/ops/agent/complete-task", opInput, toolCtx.ctx)
				.get(toolCtx.toolCallTimeoutMs, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			return Strings.create("Error: " + unwrap(e).getMessage());
		}

		// Record locally so processChat can promote the structured output
		// into the transition's response field for the timeline.
		Blob taskId = toolCtx.ctx.getTaskId();
		if (taskId != null) {
			AString taskIdStr = Strings.create(taskId.toHexString());
			toolCtx.recordTaskResult(taskIdStr,
				Maps.of(Fields.STATUS, Status.COMPLETE, Fields.OUTPUT, result));
		}

		return (opResult != null) ? opResult : Maps.empty();
	}

	/**
	 * Fails the in-scope task by invoking the {@code agent:failTask} venue op.
	 * The op reads {@code agentId} and {@code taskId} from the
	 * {@link RequestContext}; the LLM supplies an {@code error} message.
	 *
	 * <p>As with {@link #handleCompleteTask}, we record locally into
	 * {@link ToolContext#taskResults} so {@code processChat} can promote
	 * the error into the transition's {@code error} field for the timeline.
	 * The venue op completes the pending Job and removes the task entry —
	 * the framework reads completion state directly from the now-finished
	 * Job, so no separate signal is required.</p>
	 */
	private ACell handleFailTask(ACell input, ToolContext toolCtx) {
		AString error = RT.ensureString(RT.getIn(input, Fields.ERROR));
		if (error == null) return Strings.create("Error: error is required");

		AMap<AString, ACell> opInput = Maps.of(Fields.ERROR, error);
		ACell opResult;
		try {
			opResult = engine.jobs().invokeInternal(
				"v/ops/agent/fail-task", opInput, toolCtx.ctx)
				.get(toolCtx.toolCallTimeoutMs, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			return Strings.create("Error: " + unwrap(e).getMessage());
		}

		Blob taskId = toolCtx.ctx.getTaskId();
		if (taskId != null) {
			AString taskIdStr = Strings.create(taskId.toHexString());
			toolCtx.recordTaskResult(taskIdStr,
				Maps.of(Fields.STATUS, Status.FAILED, Fields.ERROR, error));
		}

		return (opResult != null) ? opResult : Maps.empty();
	}

	// ========== Built-in context tools ==========

	ACell handleContextLoad(ACell input, ToolContext toolCtx) {
		AString path = RT.ensureString(RT.getIn(input, K_PATH));
		if (path == null) return Strings.create("Error: path is required");

		long budget = clampLoadBudget(RT.getIn(input, K_BUDGET));
		AString label = RT.ensureString(RT.getIn(input, K_LABEL));
		toolCtx.addLoad(path, buildLoadEntryMeta(budget, label));

		return Maps.of(
			K_PATH, path,
			Strings.create("loaded"), CVMBool.TRUE,
			K_BUDGET, CVMLong.create(budget),
			Strings.create("note"), Strings.create("Path will appear in context next turn. Use inspect for immediate reads."));
	}

	ACell handleContextUnload(ACell input, ToolContext toolCtx) {
		AString path = RT.ensureString(RT.getIn(input, K_PATH));
		if (path == null) return Strings.create("Error: path is required");

		if (!toolCtx.hasLoad(path)) {
			return Strings.create("Error: path not loaded: " + path);
		}

		toolCtx.removeLoad(path);

		return Maps.of(
			K_PATH, path,
			Strings.create("unloaded"), CVMBool.TRUE);
	}

	/**
	 * Checks whether the given jobId string matches a task in the presented task list.
	 */
	static boolean isKnownTask(AString jobIdStr, ToolContext toolCtx) {
		if (toolCtx.tasks == null) return false;
		for (long i = 0; i < toolCtx.tasks.count(); i++) {
			ACell task = toolCtx.tasks.get(i);
			AString taskJobId = RT.ensureString(RT.getIn(task, Fields.JOB_ID));
			if (jobIdStr.equals(taskJobId)) return true;
		}
		return false;
	}

	/**
	 * Checks whether the given jobId has already been recorded as completed/failed.
	 */
	static boolean isAlreadyCompleted(AString jobIdStr, ToolContext toolCtx) {
		return toolCtx.taskResults != null && toolCtx.taskResults.get(jobIdStr) != null;
	}

	/**
	 * Builds a user message listing only outstanding (unresolved) tasks.
	 * Returns null if no tasks remain, signalling the loop to omit task tools.
	 */
	static AMap<AString, ACell> buildOutstandingTaskMessage(ToolContext toolCtx) {
		if (toolCtx.tasks == null || toolCtx.tasks.count() == 0) return null;
		StringBuilder sb = new StringBuilder();
		int outstanding = 0;
		for (long i = 0; i < toolCtx.tasks.count(); i++) {
			ACell task = toolCtx.tasks.get(i);
			AString jobId = RT.ensureString(RT.getIn(task, Fields.JOB_ID));
			if (jobId != null && toolCtx.taskResults != null && toolCtx.taskResults.get(jobId) != null) {
				continue; // already resolved
			}
			if (outstanding == 0) sb.append("[Tasks assigned to you]\n");
			outstanding++;
			ACell taskInput = RT.getIn(task, Fields.INPUT);
			ACell caller = RT.getIn(task, Fields.CALLER);
			sb.append("- Task ").append(jobId);
			if (caller != null) sb.append(" (from: ").append(caller).append(")");
			sb.append(": ").append(taskInput).append("\n");
		}
		if (outstanding == 0) return null;
		sb.append("Use complete_task or fail_task to resolve each task.");
		return Maps.of(K_ROLE, ROLE_USER, K_CONTENT, Strings.create(sb.toString()));
	}

	@SuppressWarnings("unchecked")
	public
	static AMap<AString, ACell> extractConfig(ACell state) {
		if (state == null) return null;
		ACell c = RT.getIn(state, K_CONFIG);
		if (c instanceof AMap) return (AMap<AString, ACell>) c;
		return null;
	}

	static AString getConfigValue(AMap<AString, ACell> config, AString key, AString defaultValue) {
		if (config == null) return defaultValue;
		AString val = RT.ensureString(config.get(key));
		return (val != null) ? val : defaultValue;
	}

	@SuppressWarnings("unchecked")
	static AMap<AString, ACell> extractLoads(ACell state) {
		if (state == null) return Maps.empty();
		ACell l = RT.getIn(state, K_LOADS);
		return (l instanceof AMap) ? (AMap<AString, ACell>) l : Maps.empty();
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
		final AVector<ACell> tasks;
		final AVector<ACell> pending;
		final Map<String, AString> configToolMap;
		final AVector<ACell> caps;
		final long toolCallTimeoutMs;
		AMap<AString, ACell> taskResults;
		AMap<AString, ACell> loads;

		ToolContext(AString agentId, RequestContext ctx, AVector<ACell> tasks, AVector<ACell> pending,
				Map<String, AString> configToolMap, AVector<ACell> caps, AMap<AString, ACell> loads) {
			this(agentId, ctx, tasks, pending, configToolMap, caps, loads, DEFAULT_TOOL_CALL_TIMEOUT_MS);
		}

		ToolContext(AString agentId, RequestContext ctx, AVector<ACell> tasks, AVector<ACell> pending,
				Map<String, AString> configToolMap, AVector<ACell> caps, AMap<AString, ACell> loads,
				long toolCallTimeoutMs) {
			this.agentId = agentId;
			this.ctx = ctx;
			this.tasks = tasks;
			this.pending = pending;
			this.configToolMap = (configToolMap != null) ? configToolMap : Map.of();
			this.caps = caps;
			this.loads = (loads != null) ? loads : Maps.empty();
			this.toolCallTimeoutMs = toolCallTimeoutMs;
		}

		void recordTaskResult(AString jobId, AMap<AString, ACell> result) {
			if (taskResults == null) taskResults = Maps.empty();
			taskResults = taskResults.assoc(jobId, result);
		}

		void addLoad(AString path, AMap<AString, ACell> meta) {
			loads = loads.assoc(path, meta);
		}

		void removeLoad(AString path) {
			loads = loads.dissoc(path);
		}

		boolean hasLoad(AString path) {
			return loads.get(path) != null;
		}

		AMap<AString, ACell> getLoads() {
			return loads;
		}
	}
}
