package covia.adapter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Hash;
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
 * <p>Default tools (unless disabled via {@code defaultTools: false}):
 * {@code list_functions}, {@code describe_function}, {@code message_agent},
 * {@code request_agent}. Task tools ({@code complete_task}, {@code fail_task})
 * are added dynamically when tasks are pending.</p>
 *
 * <p>Additional tools can be configured via {@code tools} in the agent's state config.
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
	private static final AString K_DEFAULT_TOOLS   = Strings.intern("defaultTools");
	private static final AString K_RESPONSE_FORMAT = Strings.intern("responseFormat");
	private static final AString K_CONTEXT        = Strings.intern("context");
	private static final AString K_CAPS           = Strings.intern("caps");

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

	/** Default tool operations — resolved via buildConfigTools at runtime */
	private static final AVector<ACell> DEFAULT_TOOL_OPS = (AVector<ACell>) Vectors.of(
		(ACell) Strings.create("agent:create"),
		(ACell) Strings.create("agent:message"),
		(ACell) Strings.create("agent:request"),
		(ACell) Strings.create("asset:store"),
		(ACell) Strings.create("asset:get"),
		(ACell) Strings.create("asset:list"),
		(ACell) Strings.create("grid:run"),
		(ACell) Strings.create("covia:read"),
		(ACell) Strings.create("covia:write"),
		(ACell) Strings.create("covia:delete"),
		(ACell) Strings.create("covia:append"),
		(ACell) Strings.create("covia:slice"),
		(ACell) Strings.create("covia:list"),
		(ACell) Strings.create("covia:functions"),
		(ACell) Strings.create("covia:describe")
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

		// Context loading — resolve state.config.context and state.context
		// entries as system messages. See venue/docs/CONTEXT.md for design.
		ContextLoader contextLoader = new ContextLoader(engine);
		if (config != null) {
			AVector<ACell> configContext = RT.ensureVector(config.get(K_CONTEXT));
			AVector<ACell> contextMsgs = contextLoader.resolve(configContext, ctx);
			history = (AVector<ACell>) history.concat(contextMsgs);
		}
		AVector<ACell> stateContext = RT.ensureVector(RT.getIn(state, K_CONTEXT));
		if (stateContext != null) {
			AVector<ACell> contextMsgs = contextLoader.resolve(stateContext, ctx);
			history = (AVector<ACell>) history.concat(contextMsgs);
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

		// Build the tool list from config
		boolean useDefaults = config == null || !CVMBool.FALSE.equals(config.get(K_DEFAULT_TOOLS));
		Map<String, AString> configToolMap = new HashMap<>();

		// Resolve default tools as operation references
		AVector<ACell> baseTools = Vectors.empty();
		if (useDefaults) {
			baseTools = buildConfigTools(DEFAULT_TOOL_OPS, configToolMap);
		}

		// Resolve additional config tools
		if (config != null) {
			ACell toolsCell = config.get(K_TOOLS);
			if (toolsCell instanceof AVector<?> toolsVec) {
				baseTools = (AVector<ACell>) baseTools.concat(
					buildConfigTools((AVector<ACell>) toolsVec, configToolMap));
			}
		}

		// Read agent capability attenuations (null = unrestricted)
		AVector<ACell> caps = RT.ensureVector(config != null ? config.get(K_CAPS) : null);

		// Create tool context for built-in tool execution
		ToolContext toolCtx = new ToolContext(agentId, ctx, tasks, pending, configToolMap, caps);

		// Invoke level 3 with tool call loop — returns all messages to append
		AVector<ACell> newMessages = invokeWithToolLoop(
			llmOperation, config, history, baseTools, ctx, toolCtx);

		// Filter out empty assistant messages (e.g. when LLM produces only <think> tags)
		// to avoid polluting history with useless entries that confuse future calls
		AVector<ACell> filtered = Vectors.empty();
		for (long i = 0; i < newMessages.count(); i++) {
			ACell msg = newMessages.get(i);
			if (ROLE_ASSISTANT.equals(RT.getIn(msg, K_ROLE))) {
				AString content = RT.ensureString(RT.getIn(msg, K_CONTENT));
				boolean hasContent = content != null && content.count() > 0;
				boolean hasToolCalls = RT.getIn(msg, K_TOOL_CALLS) instanceof AVector<?> v && v.count() > 0;
				if (!hasContent && !hasToolCalls) continue; // skip empty assistant msgs
			}
			filtered = filtered.conj(msg);
		}
		history = (AVector<ACell>) history.concat(filtered);

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
				ACell apiKey = config.get(Strings.intern("apiKey"));
				if (apiKey != null) l3Input = l3Input.assoc(Strings.intern("apiKey"), apiKey);
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

		// Resolve the actual operation name for capability checking
		AString operation = toolCtx.configToolMap.get(toolName);
		String opName = (operation != null) ? operation.toString() : toolName;

		// Check agent capabilities before dispatch
		String denied = CapabilityChecker.check(toolCtx.caps, opName, input);
		if (denied != null) return Strings.create("Error: " + denied);

		// Config tools — tool name maps to a resolved operation
		if (operation != null) {
			return handleConfigTool(operation, input, ctx);
		}

		// Fall through to grid dispatch (tool name used as operation name)
		return handleGridDispatch(toolName, input, ctx);
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

		return Maps.of(Fields.STATUS, Status.COMPLETE);
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

		return Maps.of(Fields.STATUS, Status.FAILED);
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

	/**
	 * Builds flattened tool definitions from the config {@code tools} vector.
	 *
	 * <p>Each entry is either a string (operation name shorthand) or a map with
	 * {@code operation} plus optional overrides ({@code name}, {@code description}).
	 * The operation is resolved via the engine to get the asset metadata, from which
	 * the tool definition (name, description, parameters) is derived.</p>
	 *
	 * @param toolsVec Config tools vector (strings and/or maps)
	 * @param configToolMap Populated with toolName → operation mapping for dispatch
	 * @return Vector of tool definition maps for the LLM
	 */
	@SuppressWarnings("unchecked")
	private AVector<ACell> buildConfigTools(AVector<ACell> toolsVec, Map<String, AString> configToolMap) {
		AVector<ACell> tools = Vectors.empty();
		for (long i = 0; i < toolsVec.count(); i++) {
			ACell entry = toolsVec.get(i);

			AString[] parsed = parseConfigToolEntry(entry);
			if (parsed == null) continue;

			AString operation = parsed[0];
			AString nameOverride = parsed[1];
			AString descOverride = parsed[2];

			// Resolve operation to asset
			Hash hash = engine.resolveOperation(operation);
			if (hash == null) {
				log.warn("Config tool: cannot resolve operation '{}'", operation);
				continue;
			}
			Asset asset = engine.getAsset(hash);
			if (asset == null) {
				log.warn("Config tool: asset not found for operation '{}'", operation);
				continue;
			}

			// Derive tool name and description from overrides / asset metadata
			AString assetToolName = RT.ensureString(asset.meta().get(Fields.TOOL_NAME));
			String toolName = deriveToolName(nameOverride, assetToolName, operation);

			AString description = (descOverride != null)
				? descOverride
				: RT.ensureString(asset.meta().get(Fields.DESCRIPTION));

			// Extract input schema from asset operation metadata
			ACell inputSchema = RT.getIn(asset.meta(), Fields.OPERATION, Fields.INPUT);

			AMap<AString, ACell> toolDef = buildToolDefinition(toolName, description, inputSchema);
			tools = tools.conj(toolDef);
			configToolMap.put(toolName, operation);
		}
		return tools;
	}

	// ========== Pure helper functions (package-private for testing) ==========

	/**
	 * Parses a config tool entry (string or map) into its components.
	 *
	 * @return Array of [operation, nameOverride, descOverride], or null if invalid
	 */
	@SuppressWarnings("unchecked")
	static AString[] parseConfigToolEntry(ACell entry) {
		AString operation;
		AString nameOverride = null;
		AString descOverride = null;

		if (entry instanceof AString s) {
			operation = s;
		} else if (entry instanceof AMap<?, ?> m) {
			AMap<AString, ACell> map = (AMap<AString, ACell>) m;
			operation = RT.ensureString(map.get(Fields.OPERATION));
			nameOverride = RT.ensureString(map.get(K_NAME));
			descOverride = RT.ensureString(map.get(K_DESCRIPTION));
		} else {
			return null;
		}

		if (operation == null) return null;
		return new AString[] { operation, nameOverride, descOverride };
	}

	/**
	 * Derives a tool name from overrides, asset metadata, or the operation name.
	 *
	 * <p>Priority: nameOverride → asset toolName → operation with colons/slashes→underscores</p>
	 */
	static String deriveToolName(AString nameOverride, AString assetToolName, AString operation) {
		if (nameOverride != null) return nameOverride.toString();
		if (assetToolName != null) return assetToolName.toString();
		return operation.toString().replace(':', '_').replace('/', '_');
	}

	/**
	 * Builds a tool definition map from resolved components.
	 */
	@SuppressWarnings("unchecked")
	static AMap<AString, ACell> buildToolDefinition(String toolName, AString description, ACell inputSchema) {
		AMap<AString, ACell> parameters;
		if (inputSchema instanceof AMap) {
			parameters = (AMap<AString, ACell>) inputSchema;
		} else {
			parameters = Maps.of(K_TYPE, Strings.create("object"), K_PROPERTIES, Maps.empty());
		}

		AMap<AString, ACell> toolDef = Maps.of(
			K_NAME, Strings.create(toolName),
			K_PARAMETERS, parameters);
		if (description != null) {
			toolDef = toolDef.assoc(K_DESCRIPTION, description);
		}
		return toolDef;
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

	static AString getConfigValue(AMap<AString, ACell> config, AString key, AString defaultValue) {
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
		final Map<String, AString> configToolMap;
		final AVector<ACell> caps;
		AMap<AString, ACell> taskResults;

		ToolContext(AString agentId, RequestContext ctx, AVector<ACell> tasks, AVector<ACell> pending,
				Map<String, AString> configToolMap, AVector<ACell> caps) {
			this.agentId = agentId;
			this.ctx = ctx;
			this.tasks = tasks;
			this.pending = pending;
			this.configToolMap = (configToolMap != null) ? configToolMap : Map.of();
			this.caps = caps;
		}

		void recordTaskResult(AString jobId, AMap<AString, ACell> result) {
			if (taskResults == null) taskResults = Maps.empty();
			taskResults = taskResults.assoc(jobId, result);
		}
	}
}
