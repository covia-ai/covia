package covia.adapter;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Asset;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.AgentState;
import covia.venue.RequestContext;
import covia.venue.User;
import covia.venue.Users;

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
 * <h3>Default tool palette</h3>
 * <p>Level 2 provides built-in tools to every LLM agent (see AGENT_LOOP.md §3.5):
 * {@code complete_task}, {@code fail_task}, {@code grid_run}, {@code grid_invoke},
 * {@code message_agent}. These are intercepted in the tool call loop and handled
 * locally — they are not dispatched as grid operations.</p>
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
 *     "llmOperation": "langchain:openai",
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
		+ "Use tools and grid oprtaions to complete tasks."
		+ "Give concise, clear and accurate responses.");

	private static final AString DEFAULT_LLM_OPERATION = Strings.create("langchain:openai");

	// Config field keys (read from state.config)
	private static final AString K_CONFIG        = Strings.intern("config");
	private static final AString K_LLM_OPERATION = Strings.intern("llmOperation");
	private static final AString K_MODEL         = Strings.intern("model");
	private static final AString K_SYSTEM_PROMPT = Strings.intern("systemPrompt");
	private static final AString K_URL             = Strings.intern("url");
	private static final AString K_TOOLS           = Strings.intern("tools");
	private static final AString K_RESPONSE_FORMAT = Strings.intern("responseFormat");

	// History / message field keys
	private static final AString K_HISTORY    = Strings.intern("history");
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

	// Role values
	private static final AString ROLE_SYSTEM    = Strings.intern("system");
	private static final AString ROLE_USER      = Strings.intern("user");
	private static final AString ROLE_ASSISTANT = Strings.intern("assistant");
	private static final AString ROLE_TOOL      = Strings.intern("tool");

	// Built-in tool names
	private static final String TOOL_COMPLETE_TASK = "complete_task";
	private static final String TOOL_FAIL_TASK     = "fail_task";
	private static final String TOOL_GRID_RUN       = "grid_run";
	private static final String TOOL_GRID_INVOKE   = "grid_invoke";
	private static final String TOOL_MESSAGE_AGENT = "message_agent";
	private static final String TOOL_REQUEST_AGENT = "request_agent";
	private static final String TOOL_COVIA_LIST    = "covia_list";
	private static final String TOOL_COVIA_GET     = "covia_get";

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

	private static final AMap<AString, ACell> TOOL_DEF_GRID_RUN = Maps.of(
		K_NAME, Strings.create(TOOL_GRID_RUN),
		K_DESCRIPTION, Strings.create(
			"Invoke a grid operation synchronously and wait for the result. "
			+ "IMPORTANT: Use covia_get first to look up the operation's input schema before calling this. "
			+ "Available operations include: "
			+ "agent:create (create a new agent), "
			+ "agent:request (submit a task to another agent), "
			+ "agent:query (read an agent's state), "
			+ "agent:list (list all agents), "
			+ "http:get / http:post (HTTP requests), "
			+ "convex:query / convex:transact (blockchain operations), "
			+ "mcp:tools:list / mcp:tools:call (MCP tool discovery and invocation). "
			+ "For long-running operations, use grid_invoke instead."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Fields.OPERATION, Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create("The operation to invoke, e.g. \"agent:create\", \"http:get\", \"convex:query\"")),
				Fields.INPUT, Maps.of(
					K_TYPE, Strings.create("object"),
					K_DESCRIPTION, Strings.create("Input parameters as a JSON object. Use covia_get to check the required schema first."))
			),
			K_REQUIRED, Vectors.of(Strings.create("operation"))
		)
	);

	private static final AMap<AString, ACell> TOOL_DEF_GRID_INVOKE = Maps.of(
		K_NAME, Strings.create(TOOL_GRID_INVOKE),
		K_DESCRIPTION, Strings.create(
			"Invoke a grid operation asynchronously. Returns immediately with a Job ID — "
			+ "the operation runs in the background. The Job ID is added to your pending "
			+ "list and you will be woken automatically when it completes. "
			+ "Use this for long-running operations or delegation to other agents "
			+ "(e.g. agent:request to submit a task and get the result later)."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Fields.OPERATION, Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create("The operation to invoke, e.g. \"agent:request\", \"http:post\"")),
				Fields.INPUT, Maps.of(
					K_TYPE, Strings.create("object"),
					K_DESCRIPTION, Strings.create("Input parameters as a JSON object. Use covia_get to check the required schema first."))
			),
			K_REQUIRED, Vectors.of(Strings.create("operation"))
		)
	);

	private static final AMap<AString, ACell> TOOL_DEF_MESSAGE_AGENT = Maps.of(
		K_NAME, Strings.create(TOOL_MESSAGE_AGENT),
		K_DESCRIPTION, Strings.create(
			"Send a fire-and-forget message to another agent's inbox. "
			+ "The target agent will be woken and see the message on its next run. "
			+ "No response is returned — use this for notifications or nudges. "
			+ "For tracked work that produces a result, use grid_run/request_agent "
			+ "with agent:request instead."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Fields.AGENT_ID, Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create("The ID of the target agent (must be owned by the same user)")),
				Fields.MESSAGE, Maps.of(
					K_TYPE, Maps.empty(),
					K_DESCRIPTION, Strings.create("The message content to deliver. Can be any JSON value."))
			),
			K_REQUIRED, Vectors.of(Strings.create("agentId"), Strings.create("message"))
		)
	);

	private static final AMap<AString, ACell> TOOL_DEF_REQUEST_AGENT = Maps.of(
		K_NAME, Strings.create(TOOL_REQUEST_AGENT),
		K_DESCRIPTION, Strings.create(
			"Submit a task to another agent and wait for the result. "
			+ "Use this to delegate work — the target agent will be woken, "
			+ "process the task, and return an output. "
			+ "The target agent must already exist (use grid_run with agent:create first if needed)."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Fields.AGENT_ID, Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create("The ID of the target agent")),
				Fields.INPUT, Maps.of(
					K_TYPE, Maps.empty(),
					K_DESCRIPTION, Strings.create("The task input — describes what you want the agent to do. Can be any JSON value."))
			),
			K_REQUIRED, Vectors.of(Strings.create("agentId"), Strings.create("input"))
		)
	);

	private static final AMap<AString, ACell> TOOL_DEF_COVIA_LIST = Maps.of(
		K_NAME, Strings.create(TOOL_COVIA_LIST),
		K_DESCRIPTION, Strings.create(
			"List all operations available on this venue. "
			+ "Returns operation names and descriptions. "
			+ "Use this to discover what operations you can call with grid_run."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.empty()
		)
	);

	private static final AMap<AString, ACell> TOOL_DEF_COVIA_GET = Maps.of(
		K_NAME, Strings.create(TOOL_COVIA_GET),
		K_DESCRIPTION, Strings.create(
			"Get the full metadata for an operation, including its input and output schemas. "
			+ "Use this before calling grid_run to learn what parameters an operation expects."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Fields.OPERATION, Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create("The operation name, e.g. \"agent:create\", \"http:get\""))
			),
			K_REQUIRED, Vectors.of(Strings.create("operation"))
		)
	);

	/** Base tools always available */
	private static final AVector<ACell> BASE_TOOLS = (AVector<ACell>) Vectors.of(
		(ACell) TOOL_DEF_GRID_RUN,
		(ACell) TOOL_DEF_GRID_INVOKE,
		(ACell) TOOL_DEF_MESSAGE_AGENT,
		(ACell) TOOL_DEF_REQUEST_AGENT,
		(ACell) TOOL_DEF_COVIA_LIST,
		(ACell) TOOL_DEF_COVIA_GET
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
			+ "Provides built-in tools: complete_task, fail_task, invoke, "
			+ "grid_invoke, message_agent, request_agent.";
	}

	@Override
	protected void installAssets() {
		installAsset("/adapters/llmagent/chat.json");
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

		// Read LLM config from state
		AMap<AString, ACell> config = extractConfig(state);

		// Extract settings from config
		AString llmOperation = getConfigValue(config, K_LLM_OPERATION, DEFAULT_LLM_OPERATION);
		AString systemPrompt = getConfigValue(config, K_SYSTEM_PROMPT, null);

		// Reconstruct conversation history from state
		AVector<ACell> history = extractHistory(state);

		// If no system prompt in history yet, prepend one
		if (history.count() == 0 || !ROLE_SYSTEM.equals(RT.getIn(history.get(0), K_ROLE))) {
			AString sysContent = (systemPrompt != null) ? systemPrompt : DEFAULT_SYSTEM_PROMPT;
			history = (AVector<ACell>) Vectors.of(
				(ACell) Maps.of(K_ROLE, ROLE_SYSTEM, K_CONTENT, sysContent)
			).concat(history);
		}

		// Task context is built dynamically per tool-loop iteration (not baked into
		// history) so the LLM only sees outstanding tasks, not already-resolved ones.

		// Append pending job results as a user turn
		if (pending != null && pending.count() > 0) {
			StringBuilder sb = new StringBuilder("[Pending job results]\n");
			for (long i = 0; i < pending.count(); i++) {
				ACell p = pending.get(i);
				AString jobId = RT.ensureString(RT.getIn(p, Fields.JOB_ID));
				ACell status = RT.getIn(p, Fields.STATUS);
				ACell output = RT.getIn(p, Fields.OUTPUT);
				sb.append("- Job ").append(jobId).append(" status=").append(status)
				  .append(" output=").append(output).append("\n");
			}
			history = history.conj(Maps.of(K_ROLE, ROLE_USER, K_CONTENT, Strings.create(sb.toString())));
		}

		// Append each inbox message as a user turn
		if (messages != null) {
			for (long i = 0; i < messages.count(); i++) {
				ACell msg = messages.get(i);
				AString content = RT.ensureString(RT.getIn(msg, K_CONTENT));
				if (content == null) content = RT.ensureString(msg);
				if (content != null) {
					history = history.conj(Maps.of(K_ROLE, ROLE_USER, K_CONTENT, content));
				}
			}
		}

		// Build the base tool list (task tools added dynamically per loop iteration)
		AVector<ACell> baseTools = BASE_TOOLS;
		if (config != null) {
			ACell toolsCell = config.get(K_TOOLS);
			if (toolsCell instanceof AVector) {
				baseTools = (AVector<ACell>) baseTools.concat((AVector<ACell>) toolsCell);
			}
		}

		// Create tool context for built-in tool execution
		ToolContext toolCtx = new ToolContext(agentId, ctx, tasks, pending);

		// Invoke level 3 with tool call loop — returns all messages to append
		AVector<ACell> newMessages = invokeWithToolLoop(
			llmOperation, config, history, baseTools, ctx, toolCtx);
		history = (AVector<ACell>) history.concat(newMessages);

		// Extract text content from the final assistant message
		ACell lastMsg = newMessages.get(newMessages.count() - 1);
		AString contentText = RT.ensureString(RT.getIn(lastMsg, K_CONTENT));
		String responseText = (contentText != null) ? contentText.toString() : "";

		// Return transition function output (preserve config in state)
		AMap<AString, ACell> newState = Maps.of(K_HISTORY, history);
		if (config != null) {
			newState = newState.assoc(K_CONFIG, config);
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
			if (config != null) {
				ACell model = config.get(K_MODEL);
				if (model != null) l3Input = l3Input.assoc(K_MODEL, model);
				ACell url = config.get(K_URL);
				if (url != null) l3Input = l3Input.assoc(K_URL, url);
				ACell responseFormat = config.get(K_RESPONSE_FORMAT);
				if (responseFormat != null) l3Input = l3Input.assoc(K_RESPONSE_FORMAT, responseFormat);
			}

			// Include task tools (complete_task, fail_task) only when tasks remain
			AVector<ACell> tools = (taskMsg != null)
				? (AVector<ACell>) TASK_TOOLS.concat(baseTools)
				: baseTools;
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
				// Text-only response — append and we're done
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
				String toolResult;
				try {
					String toolName = (name != null) ? name.toString() : "";
					toolResult = executeToolCall(toolName, toolInput, ctx, toolCtx);
				} catch (Exception e) {
					toolResult = "Error: " + e.getMessage();
					log.warn("Tool execution failed: {} — {}", name, e.getMessage());
				}

				// Append tool result message
				AMap<AString, ACell> toolMsg = Maps.of(
					K_ROLE, ROLE_TOOL,
					K_NAME, (name != null) ? name : Strings.create("unknown"),
					K_CONTENT, Strings.create(toolResult)
				);
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
	 */
	private String executeToolCall(String toolName, ACell input, RequestContext ctx, ToolContext toolCtx) {
		return switch (toolName) {
			case TOOL_COMPLETE_TASK  -> handleCompleteTask(input, toolCtx);
			case TOOL_FAIL_TASK     -> handleFailTask(input, toolCtx);
			case TOOL_GRID_RUN      -> handleInvoke(input, ctx);
			case TOOL_GRID_INVOKE   -> handleInvokeAsync(input, ctx, toolCtx);
			case TOOL_MESSAGE_AGENT -> handleMessageAgent(input, ctx);
			case TOOL_REQUEST_AGENT -> handleRequestAgent(input, ctx);
			case TOOL_COVIA_LIST    -> handleCoviaList();
			case TOOL_COVIA_GET     -> handleCoviaGet(input);
			default -> handleGridDispatch(toolName, input, ctx);
		};
	}

	/**
	 * Completes an inbound task with a result. Side effect — immediate.
	 * Tasks are lattice data, not JobManager jobs. Validation checks against
	 * the presented task list; completion is recorded in taskResults for the
	 * agent framework to process.
	 */
	private String handleCompleteTask(ACell input, ToolContext toolCtx) {
		AString jobIdStr = RT.ensureString(RT.getIn(input, Fields.JOB_ID));
		if (jobIdStr == null) return "Error: jobId is required";

		if (!isKnownTask(jobIdStr, toolCtx)) return "Error: task not found: " + jobIdStr;
		if (isAlreadyCompleted(jobIdStr, toolCtx)) return "Error: task already finished: " + jobIdStr;

		ACell output = RT.getIn(input, Fields.OUTPUT);

		// Record in taskResults for the agent framework to remove from lattice
		toolCtx.recordTaskResult(jobIdStr,
			Maps.of(Fields.STATUS, Status.COMPLETE, Fields.OUTPUT, output));

		return "{\"status\":\"COMPLETE\"}";
	}

	/**
	 * Fails/rejects an inbound task with a reason. Side effect — immediate.
	 * Tasks are lattice data, not JobManager jobs.
	 */
	private String handleFailTask(ACell input, ToolContext toolCtx) {
		AString jobIdStr = RT.ensureString(RT.getIn(input, Fields.JOB_ID));
		if (jobIdStr == null) return "Error: jobId is required";

		if (!isKnownTask(jobIdStr, toolCtx)) return "Error: task not found: " + jobIdStr;
		if (isAlreadyCompleted(jobIdStr, toolCtx)) return "Error: task already finished: " + jobIdStr;

		AString reason = RT.ensureString(RT.getIn(input, K_REASON));
		String reasonStr = (reason != null) ? reason.toString() : "Rejected by agent";

		// Record in taskResults for the agent framework to remove from lattice
		toolCtx.recordTaskResult(jobIdStr,
			Maps.of(Fields.STATUS, Status.FAILED, Fields.ERROR, Strings.create(reasonStr)));

		return "{\"status\":\"FAILED\"}";
	}

	/**
	 * Ensures the input value is a parsed map. LLMs often double-stringify JSON,
	 * producing a string like "{\"key\": \"val\"}" instead of a map. This parses
	 * such strings into proper maps.
	 */
	private static ACell ensureParsedInput(ACell opInput) {
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
	 * Invokes a grid operation synchronously, returning the result directly.
	 */
	private String handleInvoke(ACell input, RequestContext ctx) {
		AString operation = RT.ensureString(RT.getIn(input, Fields.OPERATION));
		if (operation == null) return "Error: operation is required";

		ACell opInput = ensureParsedInput(RT.getIn(input, Fields.INPUT));

		Job opJob = engine.jobs().invokeOperation(operation, opInput, ctx);
		ACell result = opJob.awaitResult();
		return (result != null) ? result.toString() : "null";
	}

	/**
	 * Invokes a grid operation asynchronously. Adds to pending, registers wake callback.
	 */
	private String handleInvokeAsync(ACell input, RequestContext ctx, ToolContext toolCtx) {
		AString operation = RT.ensureString(RT.getIn(input, Fields.OPERATION));
		if (operation == null) return "Error: operation is required";

		ACell opInput = ensureParsedInput(RT.getIn(input, Fields.INPUT));

		Job opJob = engine.jobs().invokeOperation(operation, opInput, ctx);
		Blob jobId = opJob.getID();
		AString jobIdHex = Strings.create(jobId.toHexString());

		// Add to agent's pending index with snapshot
		if (toolCtx.agentId != null) {
			AMap<AString, ACell> snapshot = engine.jobs().getJobData(jobId);
			addToPending(toolCtx.ctx, toolCtx.agentId, jobId, snapshot);

			// Register wake callback — when the job completes, wake the agent
			opJob.future().whenComplete((result, error) -> {
				wakeAgent(toolCtx.agentId, toolCtx.ctx);
			});
		}

		return "{\"jobId\":\"" + jobIdHex + "\"}";
	}

	/**
	 * Sends an ephemeral message to another agent's inbox.
	 */
	private String handleMessageAgent(ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) return "Error: agentId is required";

		ACell message = RT.getIn(input, Fields.MESSAGE);
		if (message == null) return "Error: message is required";

		Users users = engine.getVenueState().users();
		User user = users.get(ctx.getCallerDID());
		if (user == null) return "Error: user not found";

		AgentState agent = user.agent(agentId);
		if (agent == null) return "Error: agent not found: " + agentId;
		if (!agent.exists()) return "Error: agent not found: " + agentId;
		if (AgentState.TERMINATED.equals(agent.getStatus())) {
			return "Error: agent is terminated: " + agentId;
		}

		agent.deliverMessage(message);
		return "{\"delivered\":true}";
	}

	/**
	 * Submits a task to another agent synchronously and returns the result.
	 */
	private String handleRequestAgent(ACell input, RequestContext ctx) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) return "Error: agentId is required";

		ACell taskInput = RT.getIn(input, Fields.INPUT);

		// Delegate to agent:request with wait=true
		ACell requestInput = Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.INPUT, taskInput,
			Fields.WAIT, CVMBool.TRUE
		);

		try {
			Job requestJob = engine.jobs().invokeOperation("agent:request", requestInput, ctx);
			ACell result = requestJob.awaitResult();
			return convex.core.util.JSON.toString(result);
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Lists all operations available on the venue with names and descriptions.
	 */
	private String handleCoviaList() {
		Index<AString, Hash> ops = engine.getOperationRegistry();
		StringBuilder sb = new StringBuilder();
		sb.append("{\"operations\":[");
		boolean first = true;
		for (long i = 0; i < ops.count(); i++) {
			var entry = ops.entryAt(i);
			AString name = entry.getKey();
			Hash hash = entry.getValue();
			Asset asset = engine.getAsset(hash);

			if (!first) sb.append(",");
			first = false;
			sb.append("{\"name\":\"").append(name).append("\"");
			if (asset != null) {
				AMap<AString, ACell> meta = asset.meta();
				ACell desc = meta.get(Fields.DESCRIPTION);
				if (desc != null) {
					sb.append(",\"description\":\"").append(escapeJson(desc.toString())).append("\"");
				}
			}
			sb.append("}");
		}
		sb.append("]}");
		return sb.toString();
	}

	/**
	 * Gets full metadata for a named operation, including input/output schemas.
	 */
	private String handleCoviaGet(ACell input) {
		AString opName = RT.ensureString(RT.getIn(input, Fields.OPERATION));
		if (opName == null) return "Error: operation name is required";

		Hash hash = engine.resolveOperation(opName);
		if (hash == null) return "Error: operation not found: " + opName;

		Asset asset = engine.getAsset(hash);
		if (asset == null) return "Error: asset not found for operation: " + opName;

		AMap<AString, ACell> meta = asset.meta();
		return convex.core.util.JSON.toString(meta);
	}

	private static String escapeJson(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
	}

	/**
	 * Falls through to grid dispatch for non-built-in tools.
	 */
	private String handleGridDispatch(String toolName, ACell input, RequestContext ctx) {
		try {
			Job toolJob = engine.jobs().invokeOperation(
				Strings.create(toolName), input, ctx);
			ACell toolOutput = toolJob.awaitResult();
			return (toolOutput != null) ? toolOutput.toString() : "null";
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	// ========== Agent state mutations ==========

	/**
	 * Adds a Job ID to the agent's pending index.
	 */
	private void addToPending(RequestContext ctx, AString agentId, Blob jobId, ACell snapshot) {
		try {
			Users users = engine.getVenueState().users();
			User user = users.get(ctx.getCallerDID());
			if (user == null) return;
			AgentState agent = user.agent(agentId);
			if (agent == null) return;
			agent.addPending(jobId, snapshot);
		} catch (Exception e) {
			log.warn("Failed to add pending job {} for agent {}", jobId.toHexString(), agentId, e);
		}
	}

	/**
	 * Wakes the agent via AgentAdapter's wake mechanism. Checks the lattice
	 * status and starts the run loop if the agent is SLEEPING with work.
	 */
	private void wakeAgent(AString agentId, RequestContext ctx) {
		AgentAdapter agentAdapter = (AgentAdapter) engine.getAdapter("agent");
		if (agentAdapter != null) {
			agentAdapter.wakeAgent(agentId, ctx);
		}
	}

	// ========== Internal helpers ==========

	private static Blob parseJobId(AString jobIdStr) {
		try {
			return Job.parseID(jobIdStr);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Checks whether the given jobId string matches a task in the presented task list.
	 */
	private static boolean isKnownTask(AString jobIdStr, ToolContext toolCtx) {
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
	private static boolean isAlreadyCompleted(AString jobIdStr, ToolContext toolCtx) {
		return toolCtx.taskResults != null && toolCtx.taskResults.get(jobIdStr) != null;
	}

	/**
	 * Builds a user message listing only outstanding (unresolved) tasks.
	 * Returns null if no tasks remain, signalling the loop to omit task tools.
	 */
	private static AMap<AString, ACell> buildOutstandingTaskMessage(ToolContext toolCtx) {
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
			sb.append("- Task ").append(jobId).append(": ").append(taskInput).append("\n");
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

	private static AString getConfigValue(AMap<AString, ACell> config, AString key, AString defaultValue) {
		if (config == null) return defaultValue;
		AString val = RT.ensureString(config.get(key));
		return (val != null) ? val : defaultValue;
	}

	@SuppressWarnings("unchecked")
	static AVector<ACell> extractHistory(ACell state) {
		if (state == null) return Vectors.empty();
		ACell h = RT.getIn(state, K_HISTORY);
		if (h instanceof AVector) return (AVector<ACell>) h;
		return Vectors.empty();
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
		AMap<AString, ACell> taskResults;

		ToolContext(AString agentId, RequestContext ctx, AVector<ACell> tasks, AVector<ACell> pending) {
			this.agentId = agentId;
			this.ctx = ctx;
			this.tasks = tasks;
			this.pending = pending;
		}

		void recordTaskResult(AString jobId, AMap<AString, ACell> result) {
			if (taskResults == null) taskResults = Maps.empty();
			taskResults = taskResults.assoc(jobId, result);
		}
	}
}
