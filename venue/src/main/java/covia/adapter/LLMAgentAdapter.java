package covia.adapter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
public class LLMAgentAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(LLMAgentAdapter.class);

	private static final AString DEFAULT_SYSTEM_PROMPT = Strings.create(
		"You are a helpful AI agent on the Covia platform. "
		+ "Use tools and grid operations to complete tasks. "
		+ "Give concise, clear and accurate responses.");

	private static final AString DEFAULT_LLM_OPERATION = Strings.create("v/ops/langchain/openai");

	// State field keys
	private static final AString K_LOADS = Strings.intern("loads");

	// Config field keys (read from state.config)
	private static final AString K_CONFIG        = Strings.intern("config");
	private static final AString K_LLM_OPERATION = Strings.intern("llmOperation");
	private static final AString K_MODEL         = Strings.intern("model");
	private static final AString K_SYSTEM_PROMPT = Strings.intern("systemPrompt");
	private static final AString K_URL             = Strings.intern("url");
	private static final AString K_API_KEY         = Strings.intern("apiKey");
	private static final AString K_TOOLS           = Strings.intern("tools");
	private static final AString K_DEFAULT_TOOLS   = Strings.intern("defaultTools");
	private static final AString K_RESPONSE_FORMAT = Strings.intern("responseFormat");
	private static final AString K_CONTEXT        = Strings.intern("context");
	private static final AString K_CAPS           = Strings.intern("caps");

	// History / message field keys
	private static final AString K_HISTORY    = Strings.intern("history");
	private static final AString K_TRANSCRIPT = Strings.intern("transcript");
	private static final AString K_ROLE       = Strings.intern("role");
	private static final AString K_CONTENT    = Strings.intern("content");
	private static final AString K_RESPONSE   = Strings.intern("response");
	private static final AString K_MESSAGES   = Strings.intern("messages");
	private static final AString K_TOOL_CALLS = Strings.intern("toolCalls");
	private static final AString K_ID         = Strings.intern("id");
	private static final AString K_NAME       = Strings.intern("name");
	private static final AString K_ARGUMENTS  = Strings.intern("arguments");
	private static final AString K_DESCRIPTION = Strings.intern("description");
	private static final AString K_PARAMETERS  = Strings.intern("parameters");
	private static final AString K_TYPE        = Strings.intern("type");
	private static final AString K_PROPERTIES  = Strings.intern("properties");
	private static final AString K_REQUIRED    = Strings.intern("required");
	private static final AString K_REASON      = Strings.intern("reason");
	private static final AString K_STRUCTURED_CONTENT = Strings.intern("structuredContent");

	// Role values
	private static final AString ROLE_SYSTEM    = Strings.intern("system");
	private static final AString ROLE_USER      = Strings.intern("user");
	private static final AString ROLE_ASSISTANT = Strings.intern("assistant");
	private static final AString ROLE_TOOL      = Strings.intern("tool");

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
			"Complete an inbound task with a result. "
			+ "Call this once you have produced the output the requester asked for. "
			+ "The output will be delivered to the caller."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Fields.JOB_ID, Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create("The Job ID of the task to complete (hex string from the tasks list)")),
				Fields.OUTPUT, Maps.of(
					K_TYPE, Maps.empty(),
					K_DESCRIPTION, Strings.create("The result to return to the requester. Can be any JSON value — string, object, array, etc."))
			),
			K_REQUIRED, Vectors.of(Strings.create("jobId"))
		)
	);

	private static final AMap<AString, ACell> TOOL_DEF_FAIL_TASK = Maps.of(
		K_NAME, Strings.create(TOOL_FAIL_TASK),
		K_DESCRIPTION, Strings.create(
			"Reject or fail an inbound task. Call this when you cannot fulfil the request — "
			+ "e.g. the task is outside your capabilities or the input is invalid."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Fields.JOB_ID, Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create("The Job ID of the task to fail (hex string from the tasks list)")),
				K_REASON, Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create("Human-readable explanation of why the task cannot be completed"))
			),
			K_REQUIRED, Vectors.of(Strings.create("jobId"), Strings.create("reason"))
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
					K_DESCRIPTION, Strings.create("Optional human-readable label for this context entry"))
			),
			K_REQUIRED, Vectors.of(Strings.create("path"))
		)
	);

	private static final AMap<AString, ACell> TOOL_DEF_CONTEXT_UNLOAD = Maps.of(
		K_NAME, Strings.create(TOOL_CONTEXT_UNLOAD),
		K_DESCRIPTION, Strings.create(
			"Remove a path from your persistent loaded context. "
			+ "Frees the budget allocated to that path. "
			+ "Cannot unload pinned context entries from config."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Strings.create("path"), Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create("Lattice path to unload (must match the path used in context_load)"))
			),
			K_REQUIRED, Vectors.of(Strings.create("path"))
		)
	);

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
		throw new UnsupportedOperationException(
			"LLMAgentAdapter requires caller DID — use invoke(Job, ...) path");
	}

	@Override
	public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		job.setStatus(covia.grid.Status.STARTED);

		CompletableFuture.supplyAsync(() -> {
			return processChat(ctx, input);
		}, VIRTUAL_EXECUTOR)
		.thenAccept(job::completeWith)
		.exceptionally(e -> {
			Throwable cause = (e instanceof java.util.concurrent.CompletionException && e.getCause() != null)
				? e.getCause() : e;
			job.fail(cause.getMessage());
			return null;
		});
	}

	/**
	 * Core transition function logic.
	 *
	 * <p>Builds conversation history, invokes level 3 (with tool call loop),
	 * and returns the updated state.</p>
	 *
	 * @param ctx Request context (caller identity for level 3 invocation)
	 * @param input Transition function contract: { agentId, state, tasks, pending, messages }
	 * @return Transition function output: { state, result, taskResults? }
	 */
	@SuppressWarnings("unchecked")
	ACell processChat(RequestContext ctx, ACell input) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		ACell state = RT.getIn(input, AgentState.KEY_STATE);
		AVector<ACell> messages = (AVector<ACell>) RT.getIn(input, Fields.MESSAGES);
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

		// Build per-turn LLM context. Per AGENT_CONTEXT_PLAN.md §4 Option C
		// (transcript model): system prompt + context entries + loads +
		// [Context Map] are rebuilt FRESH every turn and never persisted.
		// Only the persistent transcript (real user/assistant/tool turns)
		// carries forward via withTranscript(state).
		//
		// Order matters: ephemeral background → transcript (history) →
		// this turn's pending/inbox → empty signal. The system prompt
		// goes first for primacy, the new input goes last for recency.
		ContextBuilder builder = new ContextBuilder(engine, ctx);
		ContextBuilder.ContextResult context = builder
			.withConfig(recordConfig, state)
			.withSystemPrompt(Vectors.empty())   // always fresh
			.withContextEntries(state)            // ephemeral
			.withLoadedPaths(existingLoads)       // ephemeral
			.withContextMap(existingLoads)        // ephemeral
			.withTranscript(state)                // persisted conversation
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
		AString llmOperation = ContextBuilder.getConfigValue(config, K_LLM_OPERATION, DEFAULT_LLM_OPERATION);

		// Task context is built dynamically per tool-loop iteration (not baked into
		// history) so the LLM only sees outstanding tasks, not already-resolved ones.

		// Create tool context for built-in tool execution
		ToolContext toolCtx = new ToolContext(agentId, capsCtx, tasks, pending, configToolMap, caps, activeLoads);

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

		// Compute the transcript delta to persist. The delta is just the
		// real conversation turns from this run: incoming task inputs and
		// inbox messages (as user role), plus the LLM-side new messages
		// (assistant + tool). Pending results, [Context Map], context
		// entries, etc. are ephemeral status / background and are NOT
		// persisted.
		AVector<ACell> oldTranscript = extractTranscript(state);
		AVector<ACell> transcriptDelta = (AVector<ACell>) Vectors.empty();
		// Synthesize a user message for each task input — captures what
		// the agent was asked to do, so the transcript is intelligible
		// across turns even though the LLM saw the task via the dynamic
		// [Tasks assigned to you] injection inside the tool loop.
		if (tasks != null) {
			for (long i = 0; i < tasks.count(); i++) {
				ACell taskMsg = wrapTaskAsUserMessage(tasks.get(i));
				if (taskMsg != null) transcriptDelta = transcriptDelta.conj(taskMsg);
			}
		}
		if (messages != null) {
			// Inbox messages get the same wrapping as withInboxMessages —
			// re-derive here so the transcript stores plain user messages
			// rather than the [Message from: ...] decorated form.
			for (long i = 0; i < messages.count(); i++) {
				ACell wrapped = wrapInboxAsUserMessage(messages.get(i));
				if (wrapped != null) transcriptDelta = transcriptDelta.conj(wrapped);
			}
		}
		transcriptDelta = (AVector<ACell>) transcriptDelta.concat(newMessagesFiltered);
		AVector<ACell> newTranscript = (AVector<ACell>) oldTranscript.concat(transcriptDelta);

		// Return transition function output. State holds: transcript (the
		// only durable conversation record), config, and loads. The legacy
		// K_HISTORY field is no longer written.
		AMap<AString, ACell> newState = Maps.of(K_TRANSCRIPT, newTranscript);
		if (config != null) {
			newState = newState.assoc(K_CONFIG, config);
		}
		AMap<AString, ACell> finalLoads = toolCtx.getLoads();
		if (finalLoads != null && finalLoads.count() > 0) {
			newState = newState.assoc(K_LOADS, finalLoads);
		}
		ACell result = Maps.of(K_RESPONSE, Strings.create(responseText));
		AMap<AString, ACell> output = Maps.of(
			AgentState.KEY_STATE, newState,
			Fields.RESULT, result
		);
		// Include task results from built-in tool calls
		if (toolCtx.taskResults != null) {
			output = output.assoc(Fields.TASK_RESULTS, toolCtx.taskResults);
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

			AMap<AString, ACell> l3Input = Maps.of(K_MESSAGES, fullHistory);
			l3Input = copyIfPresent(config, l3Input, K_MODEL, K_URL, K_API_KEY, K_RESPONSE_FORMAT);

			// Include task tools when tasks remain; context tools always available
			AVector<ACell> tools = (taskMsg != null)
				? (AVector<ACell>) TASK_TOOLS.concat(CONTEXT_TOOLS).concat(baseTools)
				: (AVector<ACell>) CONTEXT_TOOLS.concat(baseTools);
			if (tools != null && tools.count() > 0) {
				l3Input = l3Input.assoc(K_TOOLS, tools);
			}

			// Dispatch to level 3
			Job l3Job = engine.jobs().invokeOperation(llmOperation, l3Input, ctx);
			ACell l3Result = l3Job.awaitResult();

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

				// Append tool result message — text in content, structured in structured_content
				AMap<AString, ACell> toolMsg = Maps.of(
					K_ROLE, ROLE_TOOL,
					K_NAME, (name != null) ? name : Strings.create("unknown")
				);
				if (toolResult instanceof AString s) {
					toolMsg = toolMsg.assoc(K_CONTENT, s);
				} else {
					toolMsg = toolMsg.assoc(K_STRUCTURED_CONTENT, toolResult);
				}
				if (id != null) toolMsg = toolMsg.assoc(K_ID, id);
				newMessages = newMessages.conj(toolMsg);
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

		// Resolve the actual operation name for capability checking
		AString operation = toolCtx.configToolMap.get(toolName);
		String opName = (operation != null) ? operation.toString() : toolName;

		// Check agent capabilities before dispatch (fast rejection, avoids job creation)
		String denied = CapabilityChecker.check(toolCtx.caps, opName, input);
		if (denied != null) return Strings.create("Error: " + denied);

		// Use the capability-scoped context for tool dispatch
		// (caps also enforced at JobManager level for defence in depth)
		RequestContext toolRequestCtx = toolCtx.ctx;

		// Config tools — tool name maps to a resolved operation
		if (operation != null) {
			return handleConfigTool(operation, input, toolRequestCtx);
		}

		// Fall through to grid dispatch (tool name used as operation name)
		return handleGridDispatch(toolName, input, toolRequestCtx);
	}

	/**
	 * Completes an inbound task with a result. Side effect — immediate.
	 * Tasks are lattice data, not JobManager jobs. Validation checks against
	 * the presented task list; completion is recorded in taskResults for the
	 * agent framework to process.
	 */
	private ACell handleCompleteTask(ACell input, ToolContext toolCtx) {
		AString jobIdStr = RT.ensureString(RT.getIn(input, Fields.JOB_ID));
		if (jobIdStr == null) return Strings.create("Error: jobId is required");

		if (!isKnownTask(jobIdStr, toolCtx)) return Strings.create("Error: task not found: " + jobIdStr);
		if (isAlreadyCompleted(jobIdStr, toolCtx)) return Strings.create("Error: task already finished: " + jobIdStr);

		ACell output = RT.getIn(input, Fields.OUTPUT);

		// Record in taskResults for the agent framework to remove from lattice
		toolCtx.recordTaskResult(jobIdStr,
			Maps.of(Fields.STATUS, Status.COMPLETE, Fields.OUTPUT, output));

		return Maps.of(Fields.JOB_ID, jobIdStr, Fields.STATUS, Status.COMPLETE,
			Fields.OUTPUT, output != null ? output : Maps.empty());
	}

	/**
	 * Fails/rejects an inbound task with a reason. Side effect — immediate.
	 * Tasks are lattice data, not JobManager jobs.
	 */
	private ACell handleFailTask(ACell input, ToolContext toolCtx) {
		AString jobIdStr = RT.ensureString(RT.getIn(input, Fields.JOB_ID));
		if (jobIdStr == null) return Strings.create("Error: jobId is required");

		if (!isKnownTask(jobIdStr, toolCtx)) return Strings.create("Error: task not found: " + jobIdStr);
		if (isAlreadyCompleted(jobIdStr, toolCtx)) return Strings.create("Error: task already finished: " + jobIdStr);

		AString reason = RT.ensureString(RT.getIn(input, K_REASON));
		String reasonStr = (reason != null) ? reason.toString() : "Rejected by agent";

		// Record in taskResults for the agent framework to remove from lattice
		toolCtx.recordTaskResult(jobIdStr,
			Maps.of(Fields.STATUS, Status.FAILED, Fields.ERROR, Strings.create(reasonStr)));

		return Maps.of(Fields.JOB_ID, jobIdStr, Fields.STATUS, Status.FAILED,
			Fields.ERROR, Strings.create(reasonStr));
	}

	// ========== Built-in context tools ==========

	ACell handleContextLoad(ACell input, ToolContext toolCtx) {
		AString path = RT.ensureString(RT.getIn(input, Strings.create("path")));
		if (path == null) return Strings.create("Error: path is required");

		long budget = 500;
		ACell budgetCell = RT.getIn(input, Strings.create("budget"));
		if (budgetCell instanceof CVMLong l) {
			budget = Math.max(256, Math.min(l.longValue(), 10_000));
		}
		AString label = RT.ensureString(RT.getIn(input, Strings.create("label")));

		AMap<AString, ACell> entryMeta = Maps.of(
			Strings.create("budget"), CVMLong.create(budget),
			Strings.create("ts"), CVMLong.create(convex.core.util.Utils.getCurrentTimestamp()));
		if (label != null) entryMeta = entryMeta.assoc(Strings.create("label"), label);

		toolCtx.addLoad(path, entryMeta);

		return Maps.of(
			Strings.create("path"), path,
			Strings.create("loaded"), CVMBool.TRUE,
			Strings.create("budget"), CVMLong.create(budget),
			Strings.create("note"), Strings.create("Path will appear in context next turn. Use inspect for immediate reads."));
	}

	ACell handleContextUnload(ACell input, ToolContext toolCtx) {
		AString path = RT.ensureString(RT.getIn(input, Strings.create("path")));
		if (path == null) return Strings.create("Error: path is required");

		if (!toolCtx.hasLoad(path)) {
			return Strings.create("Error: path not loaded: " + path);
		}

		toolCtx.removeLoad(path);

		return Maps.of(
			Strings.create("path"), path,
			Strings.create("unloaded"), CVMBool.TRUE);
	}

	/**
	 * Ensures the input value is a parsed map. LLMs often double-stringify JSON,
	 * producing a string like "{\"key\": \"val\"}" instead of a map. This parses
	 * such strings into proper maps.
	 */
	static ACell ensureParsedInput(ACell opInput) {
		if (opInput == null) return Maps.empty();
		if (opInput instanceof AString s) {
			try {
				return convex.core.util.JSON.parse(s.toString());
			} catch (Exception e) {
				// Not valid JSON — return as-is
			}
		}
		return opInput;
	}

	/**
	 * Falls through to grid dispatch for unrecognised tool names.
	 */
	private ACell handleGridDispatch(String toolName, ACell input, RequestContext ctx) {
		try {
			Job toolJob = engine.jobs().invokeOperation(
				Strings.create(toolName), input, ctx);
			ACell toolOutput = toolJob.awaitResult();
			return (toolOutput != null) ? toolOutput : Maps.empty();
		} catch (Exception e) {
			return Strings.create("Error: " + e.getMessage());
		}
	}

	// ========== Config tool resolution ==========

	// ========== Delegates to ContextBuilder (package-private for testing) ==========

	/** @see ContextBuilder#parseConfigToolEntry(ACell) */
	static AString[] parseConfigToolEntry(ACell entry) {
		return ContextBuilder.parseConfigToolEntry(entry);
	}

	/** @see ContextBuilder#deriveToolName(AString, AString, AString) */
	static String deriveToolName(AString nameOverride, AString assetToolName, AString operation) {
		return ContextBuilder.deriveToolName(nameOverride, assetToolName, operation);
	}

	/** @see ContextBuilder#buildToolDefinition(String, AString, ACell) */
	static AMap<AString, ACell> buildToolDefinition(String toolName, AString description, ACell inputSchema) {
		return ContextBuilder.buildToolDefinition(toolName, description, inputSchema);
	}

	/**
	 * Invokes a config tool's operation directly with the LLM's input.
	 */
	private ACell handleConfigTool(AString operation, ACell input, RequestContext ctx) {
		ACell opInput = ensureParsedInput(input);
		try {
			Job opJob = engine.jobs().invokeOperation(operation, opInput, ctx);
			ACell result = opJob.awaitResult();
			return (result != null) ? result : Maps.empty();
		} catch (Exception e) {
			return Strings.create("Error: " + e.getMessage());
		}
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

	/**
	 * Copies each of the given keys from {@code src} to {@code dst} if present
	 * (and non-null) in src. Returns the updated dst map (AMap is immutable).
	 */
	static AMap<AString, ACell> copyIfPresent(
			AMap<AString, ACell> src, AMap<AString, ACell> dst, AString... keys) {
		if (src == null) return dst;
		for (AString key : keys) {
			ACell v = src.get(key);
			if (v != null) dst = dst.assoc(key, v);
		}
		return dst;
	}

	@SuppressWarnings("unchecked")
	static AVector<ACell> extractHistory(ACell state) {
		if (state == null) return Vectors.empty();
		ACell h = RT.getIn(state, K_HISTORY);
		if (h instanceof AVector) return (AVector<ACell>) h;
		return Vectors.empty();
	}

	/**
	 * Extracts the persistent transcript from agent state. This is the
	 * canonical conversation record per AGENT_CONTEXT_PLAN.md Option C —
	 * only real user / assistant / tool turns, no ephemeral context.
	 */
	@SuppressWarnings("unchecked")
	static AVector<ACell> extractTranscript(ACell state) {
		if (state == null) return Vectors.empty();
		ACell t = RT.getIn(state, K_TRANSCRIPT);
		if (t instanceof AVector) return (AVector<ACell>) t;
		return Vectors.empty();
	}

	/**
	 * Converts a task record into a plain user-role transcript entry. The
	 * task input is serialised as JSON so the LLM, on a later turn, can
	 * see what it was originally asked to do. Mirrors the dynamic
	 * {@code [Tasks assigned to you]} injection from
	 * {@link #buildOutstandingTaskMessage} but in a one-time persistent
	 * form for the transcript.
	 */
	@SuppressWarnings("unchecked")
	private static ACell wrapTaskAsUserMessage(ACell task) {
		if (task == null) return null;
		ACell taskInput = RT.getIn(task, Fields.INPUT);
		if (taskInput == null) return null;
		String inputStr;
		try {
			inputStr = convex.core.util.JSON.toString(taskInput);
		} catch (Exception e) {
			inputStr = taskInput.toString();
		}
		return Maps.of(K_ROLE, ROLE_USER, K_CONTENT, Strings.create(inputStr));
	}

	/**
	 * Converts an inbox message into a plain user-role transcript entry.
	 * Mirrors {@link ContextBuilder#withInboxMessages} but without the
	 * "[Message from: ...]" decoration — the transcript stores the raw
	 * user content.
	 */
	@SuppressWarnings("unchecked")
	private static ACell wrapInboxAsUserMessage(ACell msg) {
		AString content = null;
		if (msg instanceof AString s) {
			content = s;
		} else if (msg instanceof AMap) {
			ACell msgBody = RT.getIn(msg, Fields.MESSAGE);
			if (msgBody != null) {
				content = RT.ensureString(msgBody);
			} else {
				AString c = RT.ensureString(RT.getIn(msg, K_CONTENT));
				content = (c != null) ? c : Strings.create(msg.toString());
			}
		}
		if (content == null) return null;
		return Maps.of(K_ROLE, ROLE_USER, K_CONTENT, content);
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
		AMap<AString, ACell> taskResults;
		AMap<AString, ACell> loads;

		ToolContext(AString agentId, RequestContext ctx, AVector<ACell> tasks, AVector<ACell> pending,
				Map<String, AString> configToolMap, AVector<ACell> caps, AMap<AString, ACell> loads) {
			this.agentId = agentId;
			this.ctx = ctx;
			this.tasks = tasks;
			this.pending = pending;
			this.configToolMap = (configToolMap != null) ? configToolMap : Map.of();
			this.caps = caps;
			this.loads = (loads != null) ? loads : Maps.empty();
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
