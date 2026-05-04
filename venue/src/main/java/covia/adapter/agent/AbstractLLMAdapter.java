package covia.adapter.agent;

import java.util.Map;

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
import covia.adapter.AAdapter;
import covia.adapter.CapabilityChecker;
import covia.venue.RequestContext;

/**
 * Common base for LLM-backed agent adapters (Level 2 in the three-level architecture).
 *
 * <p>Provides shared infrastructure for both the flat conversation model
 * ({@code LLMAgentAdapter}) and the goal tree model ({@code GoalTreeAdapter}).
 * Both adapters use the same Level 3 message format, tool dispatch pipeline,
 * and capability enforcement.</p>
 *
 * <h3>Shared responsibilities</h3>
 * <ul>
 *   <li>Level 3 invocation — dispatch to LLM via grid operation</li>
 *   <li>Tool dispatch — config tool resolution, capability checking, grid fallthrough</li>
 *   <li>Input parsing — LLM JSON-as-string handling</li>
 *   <li>Config constants — field keys shared across both adapters</li>
 * </ul>
 */
public abstract class AbstractLLMAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(AbstractLLMAdapter.class);

	// ========== Config field keys ==========

	public static final AString K_CONFIG          = Strings.intern("config");
	public static final AString K_LLM_OPERATION   = Strings.intern("llmOperation");
	public static final AString K_MODEL           = Strings.intern("model");
	public static final AString K_SYSTEM_PROMPT   = Strings.intern("systemPrompt");
	public static final AString K_URL             = Strings.intern("url");
	public static final AString K_API_KEY         = Strings.intern("apiKey");
	public static final AString K_TOOLS           = Strings.intern("tools");
	public static final AString K_RESPONSE_FORMAT = Strings.intern("responseFormat");
	public static final AString K_CAPS            = Strings.intern("caps");
	public static final AString K_CONTEXT         = Strings.intern("context");

	// ========== Message field keys ==========

	public static final AString K_ROLE       = Strings.intern("role");
	public static final AString K_CONTENT    = Strings.intern("content");
	public static final AString K_MESSAGES   = Strings.intern("messages");
	public static final AString K_TOOL_CALLS = Strings.intern("toolCalls");
	public static final AString K_ID         = Strings.intern("id");
	public static final AString K_NAME       = Strings.intern("name");
	public static final AString K_ARGUMENTS  = Strings.intern("arguments");
	public static final AString K_STRUCTURED_CONTENT = Strings.intern("structuredContent");

	// ========== Role values ==========

	public static final AString ROLE_SYSTEM    = Strings.intern("system");
	public static final AString ROLE_USER      = Strings.intern("user");
	public static final AString ROLE_ASSISTANT = Strings.intern("assistant");
	public static final AString ROLE_TOOL      = Strings.intern("tool");

	// ========== Defaults ==========

	public static final AString DEFAULT_LLM_OPERATION = Strings.create("v/ops/langchain/openai");

	// ========== Level 3 invocation ==========

	/**
	 * Invokes a Level 3 LLM operation with messages and optional tools.
	 *
	 * @param llmOperation the grid operation (e.g. "v/ops/langchain/openai")
	 * @param config agent config (model, url, apiKey, responseFormat extracted)
	 * @param messages full message history for this inference
	 * @param tools tool definitions (null or empty = no tools)
	 * @param ctx request context
	 * @return the L3 result (assistant message with content and/or toolCalls)
	 */
	protected ACell invokeLevel3(AString llmOperation, AMap<AString, ACell> config,
			AVector<ACell> messages, AVector<ACell> tools, RequestContext ctx) {
		AMap<AString, ACell> l3Input = buildL3Input(config, messages, tools);
		// LLM invocation is framework infrastructure: the agent's caps gate
		// what it can DO via tools, not the inference call itself. Trust is
		// established by going through invokeInternal — the framework path —
		// rather than the user-facing invokeOperation. Caps stay on ctx.
		return engine.jobs().invokeInternal(llmOperation, l3Input, ctx).join();
	}

	/**
	 * Builds the L3 input map: {@code {messages, tools, model, ...}} — the
	 * exact payload that goes to the LLM provider operation. Factored out of
	 * {@link #invokeLevel3} so the same construction can be used for
	 * inspection (e.g. {@code agent:context}) without actually calling the LLM.
	 */
	public static AMap<AString, ACell> buildL3Input(AMap<AString, ACell> config,
			AVector<ACell> messages, AVector<ACell> tools) {
		AMap<AString, ACell> l3Input = Maps.of(K_MESSAGES, messages);
		l3Input = copyIfPresent(config, l3Input, K_MODEL, K_URL, K_API_KEY, K_RESPONSE_FORMAT);
		if (tools != null && tools.count() > 0) {
			l3Input = l3Input.assoc(K_TOOLS, tools);
		}
		return l3Input;
	}

	/**
	 * Checks if an L3 result contains tool calls.
	 */
	@SuppressWarnings("unchecked")
	protected static boolean hasToolCalls(ACell l3Result) {
		ACell toolCallsCell = RT.getIn(l3Result, K_TOOL_CALLS);
		return (toolCallsCell instanceof AVector) && ((AVector<ACell>) toolCallsCell).count() > 0;
	}

	/**
	 * Extracts the tool calls vector from an L3 result.
	 */
	@SuppressWarnings("unchecked")
	protected static AVector<ACell> getToolCalls(ACell l3Result) {
		ACell tc = RT.getIn(l3Result, K_TOOL_CALLS);
		return (tc instanceof AVector) ? (AVector<ACell>) tc : Vectors.empty();
	}

	// ========== Tool dispatch ==========

	/**
	 * Dispatches a tool call through the capability-checked pipeline.
	 * Checks capabilities, resolves config tools, falls through to grid dispatch.
	 *
	 * <p>Subclasses should call this for non-harness tools (i.e. after checking
	 * their own built-in tools like subgoal/complete/compact).</p>
	 *
	 * @param toolName the tool name as returned by the LLM
	 * @param input the tool call arguments
	 * @param configToolMap mapping of LLM tool names to operation names
	 * @param caps capability attenuations (null = unrestricted)
	 * @param ctx request context for the tool dispatch
	 * @return tool result (ACell)
	 */
	protected ACell dispatchTool(String toolName, ACell input,
			Map<String, AString> configToolMap, AVector<ACell> caps, RequestContext ctx) {
		// Resolve the actual operation name for capability checking
		AString operation = (configToolMap != null) ? configToolMap.get(toolName) : null;
		String opName = (operation != null) ? operation.toString() : toolName;

		// Check agent capabilities before dispatch
		String denied = CapabilityChecker.check(caps, opName, input);
		if (denied != null) return Strings.create("Error: " + denied);

		// Config tools — tool name maps to a resolved operation
		if (operation != null) {
			return invokeOperation(operation, input, ctx);
		}

		// Fall through to grid dispatch
		return invokeOperation(Strings.create(toolName), input, ctx);
	}

	/**
	 * Invokes an operation and returns the result, handling errors gracefully.
	 * The caller ({@link #dispatchTool}) has already cap-checked the call
	 * explicitly. invokeInternal is the framework dispatch path and doesn't
	 * apply a second cap check, so trust is established by the call path.
	 * Internal dispatch — no sub-Job created.
	 */
	private ACell invokeOperation(AString operation, ACell input, RequestContext ctx) {
		ACell opInput = ensureParsedInput(input);
		try {
			ACell result = engine.jobs().invokeInternal(operation, opInput, ctx).join();
			return (result != null) ? result : Maps.empty();
		} catch (Exception e) {
			return Strings.create("Error: " + unwrap(e).getMessage());
		}
	}

	/**
	 * Unwraps a {@link java.util.concurrent.CompletionException} to expose the
	 * adapter's original exception — otherwise error messages read
	 * "java.util.concurrent.CompletionException: ...".
	 */
	public static Throwable unwrap(Throwable t) {
		if (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) {
			return t.getCause();
		}
		return t;
	}

	// ========== Tool result message construction ==========

	/**
	 * Creates a tool result message in the Level 3 message format.
	 */
	protected static AMap<AString, ACell> toolResultMessage(
			AString toolCallId, String toolName, ACell result) {
		AMap<AString, ACell> msg = Maps.of(
			K_ROLE, ROLE_TOOL,
			K_ID, toolCallId,
			K_NAME, Strings.create(toolName));
		if (result instanceof AMap || result instanceof AVector) {
			msg = msg.assoc(K_STRUCTURED_CONTENT, result);
		} else {
			AString content = RT.ensureString(result);
			msg = msg.assoc(K_CONTENT, (content != null) ? content : Strings.create(result.toString()));
		}
		return msg;
	}

	// ========== Input parsing ==========

	/**
	 * Ensures the input value is a parsed map. LLMs often double-stringify JSON,
	 * producing a string like "{\"key\": \"val\"}" instead of a map. This parses
	 * such strings into proper maps.
	 */
	public static ACell ensureParsedInput(ACell opInput) {
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

	// ========== Config helpers ==========

	/**
	 * Extracts the LLM operation from config, falling back to the default.
	 */
	public static AString getLLMOperation(AMap<AString, ACell> config) {
		if (config == null) return DEFAULT_LLM_OPERATION;
		AString op = RT.ensureString(config.get(K_LLM_OPERATION));
		return (op != null) ? op : DEFAULT_LLM_OPERATION;
	}

	/**
	 * Pretty-prints a Level 3 input map as JSON for inspection. Output is
	 * stable-ordered ({@code model}, {@code responseFormat}, {@code messages},
	 * {@code tools}) so diffs across turns stay readable. Used by
	 * {@code inspectContext} implementations.
	 */
	@SuppressWarnings("unchecked")
	public static AString renderL3InputAsJson(AMap<AString, ACell> l3Input) {
		AVector<ACell> messages = RT.ensureVector(l3Input.get(K_MESSAGES));
		AVector<ACell> tools = RT.ensureVector(l3Input.get(K_TOOLS));
		StringBuilder sb = new StringBuilder("{\n");
		ACell model = l3Input.get(K_MODEL);
		if (model != null) {
			sb.append("  \"model\": ").append(convex.core.util.JSON.toString(model)).append(",\n");
		}
		ACell rf = l3Input.get(K_RESPONSE_FORMAT);
		if (rf != null) {
			sb.append("  \"responseFormat\": ").append(convex.core.util.JSON.toString(rf)).append(",\n");
		}
		sb.append("  \"messages\": [\n");
		if (messages != null) {
			for (long i = 0; i < messages.count(); i++) {
				if (i > 0) sb.append(",\n");
				sb.append("    ").append(convex.core.util.JSON.toString(messages.get(i)));
			}
		}
		sb.append("\n  ],\n  \"tools\": [\n");
		if (tools != null) {
			for (long i = 0; i < tools.count(); i++) {
				if (i > 0) sb.append(",\n");
				sb.append("    ").append(convex.core.util.JSON.toString(tools.get(i)));
			}
		}
		sb.append("\n  ]\n}");
		return Strings.create(sb.toString());
	}

	/**
	 * Copies config fields into a target map if they are present in the source.
	 */
	protected static AMap<AString, ACell> copyIfPresent(
			AMap<AString, ACell> source, AMap<AString, ACell> target, AString... keys) {
		if (source == null) return target;
		for (AString key : keys) {
			ACell val = source.get(key);
			if (val != null) target = target.assoc(key, val);
		}
		return target;
	}
}
