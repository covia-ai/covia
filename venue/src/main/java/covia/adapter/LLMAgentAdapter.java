package covia.adapter;

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
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.AgentState;
import covia.venue.RequestContext;

/**
 * LLM-backed transition function for agents (level 2).
 *
 * <p>Invoked by {@code agent:run} as a transition operation ({@code llmagent:chat}).
 * Maintains conversation history in the agent's {@code state} field and delegates
 * LLM calls to a level 3 grid operation (e.g. {@code langchain:openai}).</p>
 *
 * <p>This adapter has no LLM library dependencies — it works entirely with
 * structured message maps and invokes level 3 via the grid operation dispatch.</p>
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
		"You are a helpful AI assistant on the Covia platform. "
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

	// Role values
	private static final AString ROLE_SYSTEM    = Strings.intern("system");
	private static final AString ROLE_USER      = Strings.intern("user");
	private static final AString ROLE_ASSISTANT = Strings.intern("assistant");
	private static final AString ROLE_TOOL      = Strings.intern("tool");

	/** Maximum tool call loop iterations to prevent runaway loops */
	static final int MAX_TOOL_ITERATIONS = 20;

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
			+ "operations and feeds results back until a text response is produced.";
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
	 * @param input Transition function contract: { agentId, state, messages }
	 * @return Transition function output: { state, result }
	 */
	@SuppressWarnings("unchecked")
	ACell processChat(RequestContext ctx, ACell input) {
		ACell state = RT.getIn(input, AgentState.KEY_STATE);
		AVector<ACell> messages = (AVector<ACell>) RT.getIn(input, Fields.MESSAGES);

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

		// Get tool definitions from config (if any)
		AVector<ACell> tools = null;
		if (config != null) {
			ACell toolsCell = config.get(K_TOOLS);
			if (toolsCell instanceof AVector) tools = (AVector<ACell>) toolsCell;
		}

		// Invoke level 3 with tool call loop — returns all messages to append
		AVector<ACell> newMessages = invokeWithToolLoop(llmOperation, config, history, tools, ctx);
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
		return Maps.of(
			AgentState.KEY_STATE, newState,
			Fields.RESULT, result
		);
	}

	/**
	 * Invokes level 3 with a tool call loop.
	 *
	 * <p>Calls the LLM operation. If the response contains {@code toolCalls},
	 * executes each tool, appends tool result messages, and calls the LLM again.
	 * Repeats until a text-only response or the iteration limit.</p>
	 *
	 * @return Vector of new messages to append to history (includes any tool call
	 *         assistant messages, tool result messages, and the final text response)
	 */
	@SuppressWarnings("unchecked")
	private AVector<ACell> invokeWithToolLoop(
			AString llmOperation, AMap<AString, ACell> config,
			AVector<ACell> history, AVector<ACell> tools, RequestContext ctx) {

		AVector<ACell> newMessages = Vectors.empty();

		for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {
			// Build level 3 input (full history including new messages from this loop)
			AVector<ACell> fullHistory = (AVector<ACell>) history.concat(newMessages);
			AMap<AString, ACell> l3Input = Maps.of(K_MESSAGES, fullHistory);
			if (config != null) {
				ACell model = config.get(K_MODEL);
				if (model != null) l3Input = l3Input.assoc(K_MODEL, model);
				ACell url = config.get(K_URL);
				if (url != null) l3Input = l3Input.assoc(K_URL, url);
				ACell responseFormat = config.get(K_RESPONSE_FORMAT);
				if (responseFormat != null) l3Input = l3Input.assoc(K_RESPONSE_FORMAT, responseFormat);
			}
			if (tools != null) {
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

				// Execute the tool as a grid operation
				String toolResult;
				try {
					ACell toolInput = (arguments != null)
						? convex.core.util.JSON.parse(arguments.toString())
						: Maps.empty();
					Job toolJob = engine.jobs().invokeOperation(name, toolInput, ctx);
					ACell toolOutput = toolJob.awaitResult();
					toolResult = (toolOutput != null) ? toolOutput.toString() : "null";
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

	// ========== Internal helpers ==========

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

}
