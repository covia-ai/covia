package covia.adapter.agent;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.lattice.CapabilityChecker;
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
public abstract class AbstractLLMAdapter extends AAdapter implements ContextInspectable {

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
	public static final AString K_TOOL_CALL_TIMEOUT_MS = Strings.intern("toolCallTimeoutMs");

	/**
	 * Per-tool-call timeout default. Bounds the wait on any single grid op
	 * invoked as a tool so a stuck sub-job cannot hang the parent agent's
	 * run loop indefinitely. See covia-ai/covia#82.
	 */
	public static final long DEFAULT_TOOL_CALL_TIMEOUT_MS = 300_000L;

	// ========== Message field keys ==========

	public static final AString K_ROLE       = Strings.intern("role");
	public static final AString K_CONTENT    = Strings.intern("content");
	public static final AString K_MESSAGES   = Strings.intern("messages");
	public static final AString K_TOOL_CALLS = Strings.intern("toolCalls");
	public static final AString K_ID         = Strings.intern("id");
	public static final AString K_NAME       = Strings.intern("name");
	public static final AString K_ARGUMENTS  = Strings.intern("arguments");
	public static final AString K_STRUCTURED_CONTENT = Strings.intern("structuredContent");

	// ========== Tool definition (JSON Schema) keys ==========

	public static final AString K_DESCRIPTION = Strings.intern("description");
	public static final AString K_PARAMETERS  = Strings.intern("parameters");
	public static final AString K_TYPE        = Strings.intern("type");
	public static final AString K_PROPERTIES  = Strings.intern("properties");
	public static final AString K_REQUIRED    = Strings.intern("required");

	// ========== context_load / context_unload — shared schema + helpers ==========

	public static final AString K_PATH   = Strings.intern("path");
	public static final AString K_BUDGET = Strings.intern("budget");
	public static final AString K_LABEL  = Strings.intern("label");

	/** Default render budget per loaded context entry, in bytes. */
	public static final long CONTEXT_LOAD_DEFAULT_BUDGET = 500L;
	public static final long CONTEXT_LOAD_MIN_BUDGET     = 256L;
	public static final long CONTEXT_LOAD_MAX_BUDGET     = 10_000L;

	/**
	 * Shared parameter schema for the {@code context_load} tool. Subclasses
	 * pair this with their own outer description (which may differ in wording
	 * — e.g. "subgoals inherit your loaded data" only makes sense for
	 * {@link GoalTreeAdapter}).
	 */
	public static final AMap<AString, ACell> CONTEXT_LOAD_PARAMS = Maps.of(
		K_TYPE, Strings.create("object"),
		K_PROPERTIES, Maps.of(
			K_PATH, Maps.of(
				K_TYPE, Strings.create("string"),
				K_DESCRIPTION, Strings.create(
					"Workspace path to load (e.g. w/docs/rules, n/notes)")),
			K_BUDGET, Maps.of(
				K_TYPE, Strings.create("integer"),
				K_DESCRIPTION, Strings.create(
					"Byte budget for rendering this path (default 500, max 10000)")),
			K_LABEL, Maps.of(
				K_TYPE, Strings.create("string"),
				K_DESCRIPTION, Strings.create(
					"Optional human-readable label for this context entry"))),
		K_REQUIRED, Vectors.of(K_PATH));

	/**
	 * Shared parameter schema for the {@code context_unload} tool.
	 */
	public static final AMap<AString, ACell> CONTEXT_UNLOAD_PARAMS = Maps.of(
		K_TYPE, Strings.create("object"),
		K_PROPERTIES, Maps.of(
			K_PATH, Maps.of(
				K_TYPE, Strings.create("string"),
				K_DESCRIPTION, Strings.create(
					"Workspace path to unload (must match the path used in context_load)"))),
		K_REQUIRED, Vectors.of(K_PATH));

	/**
	 * Clamps a budget value supplied by the LLM. Returns the default
	 * {@link #CONTEXT_LOAD_DEFAULT_BUDGET} when the cell is not a number;
	 * otherwise clamps to [{@link #CONTEXT_LOAD_MIN_BUDGET},
	 * {@link #CONTEXT_LOAD_MAX_BUDGET}].
	 */
	public static long clampLoadBudget(ACell budgetCell) {
		if (budgetCell instanceof CVMLong l) {
			return Math.max(CONTEXT_LOAD_MIN_BUDGET,
				Math.min(l.longValue(), CONTEXT_LOAD_MAX_BUDGET));
		}
		return CONTEXT_LOAD_DEFAULT_BUDGET;
	}

	/**
	 * Builds the loaded-context entry metadata: {@code {budget, ts, label?}}.
	 * The {@code label} key is omitted when the input is null.
	 */
	public static AMap<AString, ACell> buildLoadEntryMeta(long budget, AString label) {
		AMap<AString, ACell> meta = Maps.of(
			K_BUDGET, CVMLong.create(budget),
			Strings.intern("ts"), CVMLong.create(convex.core.util.Utils.getCurrentTimestamp()));
		if (label != null) meta = meta.assoc(K_LABEL, label);
		return meta;
	}

	// ========== Role values ==========

	public static final AString ROLE_SYSTEM    = Strings.intern("system");
	public static final AString ROLE_USER      = Strings.intern("user");
	public static final AString ROLE_ASSISTANT = Strings.intern("assistant");
	public static final AString ROLE_TOOL      = Strings.intern("tool");

	// ========== Defaults ==========

	public static final AString DEFAULT_LLM_OPERATION = Strings.create("v/ops/langchain/openai");

	// ========== Inspection (template method) ==========

	/**
	 * Renders the L3 input that would be sent to the LLM on a fresh transition.
	 * Final on the parent — subclasses provide the L3 input via
	 * {@link #buildInspectionInput} and the parent renders it identically for
	 * both adapters.
	 */
	@Override
	public final AString inspectContext(AMap<AString, ACell> recordConfig,
	                                    ACell state,
	                                    ACell taskInput,
	                                    RequestContext ctx) {
		AMap<AString, ACell> l3Input = buildInspectionInput(recordConfig, state, taskInput, ctx);
		return renderL3InputAsJson(l3Input);
	}

	/**
	 * Builds the L3 input map that {@link #inspectContext} will render. Subclasses
	 * compute the same context they would on a real transition — system prompt,
	 * tool palette, message history, optional task synthesised as a user goal —
	 * but skip the actual LLM call. Returns the {@code {messages, tools, model, …}}
	 * map that {@link #invokeLevel3} would dispatch.
	 *
	 * @param recordConfig record-level agent config (may be null)
	 * @param state agent state (may be null)
	 * @param taskInput optional task input — when non-null, append a synthesised
	 *        user goal message
	 * @param ctx request context
	 * @return L3 input map
	 */
	protected abstract AMap<AString, ACell> buildInspectionInput(
		AMap<AString, ACell> recordConfig, ACell state, ACell taskInput, RequestContext ctx);

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
	 * @param timeoutMs per-tool-call wall-clock budget; {@link TimeoutException}
	 *        is converted to an "Error: tool call timed out" string result so
	 *        the agent loop can continue
	 * @return tool result (ACell)
	 */
	protected ACell dispatchTool(String toolName, ACell input,
			Map<String, AString> configToolMap, AVector<ACell> caps, RequestContext ctx,
			long timeoutMs) {
		// Resolve the actual operation name for capability checking
		AString operation = (configToolMap != null) ? configToolMap.get(toolName) : null;
		String opName = (operation != null) ? operation.toString() : toolName;

		// Check agent capabilities before dispatch
		String denied = CapabilityChecker.check(caps, opName, input);
		if (denied != null) return Strings.create("Error: " + denied);

		// Config tools — tool name maps to a resolved operation
		if (operation != null) {
			return invokeOperation(operation, input, ctx, timeoutMs);
		}

		// Fall through to grid dispatch
		return invokeOperation(Strings.create(toolName), input, ctx, timeoutMs);
	}

	/**
	 * Invokes an operation with a per-call timeout. The caller
	 * ({@link #dispatchTool}) has already cap-checked the call explicitly.
	 * invokeInternal is the framework dispatch path and doesn't apply a
	 * second cap check, so trust is established by the call path. Internal
	 * dispatch — no sub-Job created. Times out via
	 * {@link java.util.concurrent.CompletableFuture#get(long, TimeUnit)} so
	 * a stuck downstream op cannot hang the agent loop forever.
	 */
	protected ACell invokeOperation(AString operation, ACell input, RequestContext ctx, long timeoutMs) {
		ACell opInput = ensureParsedInput(input);
		try {
			ACell result = engine.jobs().invokeInternal(operation, opInput, ctx)
				.get(timeoutMs, TimeUnit.MILLISECONDS);
			return (result != null) ? result : Maps.empty();
		} catch (TimeoutException e) {
			return Strings.create("Error: tool call timed out after " + timeoutMs + "ms");
		} catch (Exception e) {
			return Strings.create("Error: " + unwrap(e).getMessage());
		}
	}

	/**
	 * Resolves the per-tool-call timeout from the agent's merged config.
	 * Accepts CVMLong (ms) or any numeric ACell; falls back to the default
	 * if absent, non-numeric, or below the 1s minimum.
	 */
	public static long resolveToolCallTimeoutMs(AMap<AString, ACell> config) {
		ACell v = (config != null) ? config.get(K_TOOL_CALL_TIMEOUT_MS) : null;
		if (v instanceof CVMLong l) {
			long ms = l.longValue();
			if (ms >= 1000) return ms;
		}
		return DEFAULT_TOOL_CALL_TIMEOUT_MS;
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
