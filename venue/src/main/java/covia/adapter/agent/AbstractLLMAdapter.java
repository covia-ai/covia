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
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Status;
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
	public static final AString K_MAX_TOOL_ITERATIONS  = Strings.intern("maxToolIterations");

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
	                                    AMap<AString, ACell> session,
	                                    RequestContext ctx) {
		AMap<AString, ACell> l3Input = buildInspectionInput(recordConfig, state, taskInput, session, ctx);
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
	 * @param session optional session record — when non-null, include its
	 *        frames conversation via {@code withFrameStack}, exactly as the
	 *        live transition path does (#211)
	 * @param ctx request context
	 * @return L3 input map
	 */
	protected abstract AMap<AString, ACell> buildInspectionInput(
		AMap<AString, ACell> recordConfig, ACell state, ACell taskInput,
		AMap<AString, ACell> session, RequestContext ctx);

	/** The frames vector of a session record, or null when absent. */
	protected static AVector<ACell> sessionFramesOf(AMap<AString, ACell> session) {
		return (session != null) ? RT.ensureVector(session.get(Fields.FRAMES)) : null;
	}

	// ========== Token usage tally (#217) ==========

	/**
	 * Per-transition token tally: {@code [input, output, total, measured]}.
	 *
	 * <p>Thread-confined by design: a transition (and every
	 * {@link #invokeLevel3} call it makes — tool-loop iterations, goal-tree
	 * subgoal recursion, compaction) runs synchronously on one virtual
	 * thread, so a ThreadLocal carries the cycle's running totals without
	 * threading a parameter through the frame recursion. Nested agent
	 * transitions are dispatched onto their own virtual threads by
	 * {@code invokeInternal}, so tallies never cross agents.</p>
	 */
	private static final ThreadLocal<long[]> TOKEN_TALLY = new ThreadLocal<>();

	/** Opens a fresh tally for this transition (overwrites any stale one). */
	protected static void beginTokenTally() {
		TOKEN_TALLY.set(new long[4]);
	}

	/**
	 * Closes the tally and returns the cycle's totals as a
	 * {@code {input, output, total}} map, or null when no L3 call reported
	 * measured usage — callers must then omit the field entirely (absent
	 * means "not measured", never zero).
	 */
	protected static AMap<AString, ACell> endTokenTally() {
		long[] t = TOKEN_TALLY.get();
		TOKEN_TALLY.remove();
		if (t == null || t[3] == 0) return null;
		return Maps.of(
			Fields.INPUT,  CVMLong.create(t[0]),
			Fields.OUTPUT, CVMLong.create(t[1]),
			Fields.TOTAL,  CVMLong.create(t[2]));
	}

	/** Adds an L3 assistant message's {@code tokens} sub-map to the open
	 *  tally, if any. Missing counts contribute nothing; a missing total is
	 *  derived from input + output so the invariant total ≥ input + output
	 *  parts holds across providers that omit it. */
	static void tallyTokens(ACell l3Result) {
		long[] t = TOKEN_TALLY.get();
		if (t == null) return;
		ACell tokens = RT.getIn(l3Result, Fields.TOKENS);
		if (!(tokens instanceof AMap)) return;
		CVMLong in  = RT.ensureLong(RT.getIn(tokens, Fields.INPUT));
		CVMLong out = RT.ensureLong(RT.getIn(tokens, Fields.OUTPUT));
		CVMLong tot = RT.ensureLong(RT.getIn(tokens, Fields.TOTAL));
		if (in == null && out == null && tot == null) return;
		long inV = (in != null) ? in.longValue() : 0;
		long outV = (out != null) ? out.longValue() : 0;
		t[0] += inV;
		t[1] += outV;
		t[2] += (tot != null) ? tot.longValue() : inV + outV;
		t[3] = 1; // measured
	}

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
		// Park (cheaply, on a virtual thread) until the LLM op completes. No
		// caller-side wait timeout is imposed here — bounding the actual IO is
		// the provider op's job (LangChainAdapter's socket timeout); whether to
		// time out the wait is the caller's decision, not this dispatch helper's.
		ACell result = engine.jobs().invokeInternal(llmOperation, l3Input, ctx).join();

		// A level-3 op that completes with a failure VALUE — {status: FAILED,
		// message: ...} from Status.failure (missing/invalid API key, unknown
		// provider) — is not an assistant message. Without this guard the tool
		// loop sees no toolCalls and no content and silently emits an empty
		// response, hiding the real failure. Surface it as a transition failure
		// so the framework fails the caller's Job with the provider's message.
		// (An op that throws already propagates exceptionally via join().)
		if (result instanceof AMap && Status.FAILED.equals(RT.getIn(result, Fields.STATUS))) {
			AString message = RT.ensureString(RT.getIn(result, Fields.MESSAGE));
			throw new JobFailedException("LLM call failed (" + llmOperation + "): "
				+ (message != null ? message.toString() : "no message"));
		}
		// Provider-reported usage rides the assistant message (tokens
		// {input, output, total}); add it to this transition's tally (#217).
		tallyTokens(result);
		return result;
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
	 * @param ctx request context for the tool dispatch — carries the agent's
	 *        capability ceiling ({@link RequestContext#getCaps()})
	 * @param timeoutMs per-tool-call wall-clock budget; {@link TimeoutException}
	 *        is converted to an "Error: tool call timed out" string result so
	 *        the agent loop can continue
	 * @return tool result (ACell)
	 */
	protected ACell dispatchTool(String toolName, ACell input,
			Map<String, AString> configToolMap, RequestContext ctx,
			long timeoutMs) {
		// Resolve the operation: a config tool maps the LLM tool name to an op
		// ref, otherwise the tool name is dispatched as a grid op. Capability
		// enforcement happens at the dispatched op's OWN enforcement point
		// (invokeInternal → the adapter's requireCapability / requireInvoke),
		// under the agent's ceiling carried on ctx — no name-keyed pre-check here.
		AString operation = (configToolMap != null) ? configToolMap.get(toolName) : null;

		// Config tools — tool name maps to a resolved operation
		if (operation != null) {
			return invokeOperation(operation, input, ctx, timeoutMs);
		}

		// A harness pseudo-tool name reaching grid dispatch belongs to another
		// runtime — this runtime's own harness tools were intercepted by the
		// subclass before falling through. It exists in no catalog, so fail the
		// call with the actual reason instead of a generic resolution miss (#143).
		String provider = HarnessNames.PROVIDERS.get(toolName);
		if (provider != null) {
			return Strings.create("Error: '" + toolName + "' is a " + provider
				+ " harness tool — not available under this agent's runtime (" + getName() + ")");
		}

		// Fall through to grid dispatch
		return invokeOperation(Strings.create(toolName), input, ctx, timeoutMs);
	}

	/**
	 * Every runtime's harness pseudo-tool names → provider label, for diagnosable
	 * wrong-runtime tool failures. Lazy holder: initialised on first dispatch,
	 * avoiding subclass-static references during this class's own initialisation.
	 */
	private static final class HarnessNames {
		static final Map<String, String> PROVIDERS;
		static {
			Map<String, String> m = new java.util.HashMap<>();
			for (String n : GoalTreeAdapter.HARNESS_TOOL_REGISTRY.keySet()) m.put(n, "goaltree");
			for (String n : LLMAgentAdapter.HARNESS_TOOL_NAMES) m.merge(n, "llmagent", (a, b) -> a + ", " + b);
			PROVIDERS = Map.copyOf(m);
		}
	}

	/**
	 * The union of every runtime's harness pseudo-tool names (goaltree +
	 * llmagent, e.g. {@code subgoal}, {@code complete}, {@code context_load}).
	 * These are bare names the adapter resolves itself, not operation paths, so a
	 * config {@code tools} list may legitimately contain them — callers that
	 * validate tool-operation resolution skip these.
	 */
	public static java.util.Set<String> allHarnessToolNames() {
		return HarnessNames.PROVIDERS.keySet();
	}

	/**
	 * The failure text if a tool result is a failure, else null. Every tool
	 * failure shape — capability denial, timeout, op error, malformed
	 * arguments — funnels through the {@code "Error: …"} string convention
	 * (see {@link #invokeOperation} and the loops' argument-parse guards),
	 * so this single predicate identifies them all.
	 */
	protected static String toolFailureMessage(ACell result) {
		if (result instanceof AString s) {
			String str = s.toString();
			if (str.startsWith("Error:")) return str;
		}
		return null;
	}

	/**
	 * Invokes an operation with a per-call timeout, via the no-Job internal
	 * dispatch path. {@code invokeInternal} enforces the ceiling carried by
	 * {@code ctx} (today the agent cycle runs unrestricted at the context
	 * level); {@link #dispatchTool} has additionally checked the call against
	 * the agent's own config caps. Times out via
	 * {@link java.util.concurrent.CompletableFuture#get(long, TimeUnit)} so a
	 * stuck downstream op cannot hang the agent loop forever.
	 */
	protected ACell invokeOperation(AString operation, ACell input, RequestContext ctx, long timeoutMs) {
		// Internal dispatch preserves types exactly — no coercion here. Tool
		// arguments were already normalised once at the LLM wire boundary
		// (parseToolArguments); a wrong-shaped input is the caller's error and
		// surfaces from the op's own validation (#89).
		try {
			ACell result = engine.jobs().invokeInternal(operation, input, ctx)
				.get(timeoutMs, TimeUnit.MILLISECONDS);
			return (result != null) ? result : Maps.empty();
		} catch (TimeoutException e) {
			return Strings.create("Error: tool call timed out after " + timeoutMs + "ms");
		} catch (Exception e) {
			return Strings.create("Error: " + unwrap(e).getMessage());
		}
	}

	/**
	 * Resolves the tool-call iteration limit for a transition: the agent's
	 * {@code config.maxToolIterations} when valid ({@code >= 1}), else the
	 * venue default ({@code maxToolIterations} in venue config, 30 unset).
	 * A backstop against runaway loops, not a work quota — operators size it
	 * to the venue's spend tolerance, agents doing legitimately long tool
	 * sequences raise their own.
	 */
	public int resolveMaxToolIterations(AMap<AString, ACell> config) {
		ACell v = (config != null) ? config.get(K_MAX_TOOL_ITERATIONS) : null;
		if (v instanceof CVMLong l && l.longValue() >= 1) {
			return (int) Math.min(l.longValue(), Integer.MAX_VALUE);
		}
		return engine.config().getMaxToolIterations();
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

	// ========== Tool-call argument parsing (the LLM wire boundary) ==========

	/**
	 * Parses LLM tool-call {@code arguments} at the wire boundary. Per the
	 * OpenAI/Anthropic specs {@code arguments} is a JSON-encoded string, and
	 * LLMs are external systems whose output we cannot force to be well-formed,
	 * so this is deliberately generous: absent/empty → empty map;
	 * already-structured values pass through; a JSON string is parsed; a
	 * double-encoded string (a parse yielding another JSON-shaped string) gets
	 * one more pass. Outright garbage <b>throws</b> — callers turn that into a
	 * structured tool error the LLM sees and can correct on its next turn,
	 * never a silent {@code Maps.empty()} substitution.
	 *
	 * <p>This is the ONE place tolerant parsing is allowed. Everything
	 * downstream is internal dispatch and must preserve types exactly — no
	 * re-parsing, no coercion (#89).</p>
	 *
	 * @param rawArguments the {@code arguments} cell from an LLM tool call
	 * @return the parsed arguments value
	 * @throws IllegalArgumentException if the arguments are not valid JSON
	 */
	public static ACell parseToolArguments(ACell rawArguments) {
		if (rawArguments == null) return Maps.empty();
		if (!(rawArguments instanceof AString)) return rawArguments; // already structured
		String s = rawArguments.toString().trim();
		if (s.isEmpty()) return Maps.empty();
		ACell parsed;
		try {
			parsed = convex.core.util.JSON.parse(s);
		} catch (Exception e) {
			throw new IllegalArgumentException("Tool arguments are not valid JSON: " + snippet(s));
		}
		// Double-encoded tolerance: a parse that yields a JSON-shaped string
		// gets one more pass; if the inner parse fails, keep the outer value
		// (the op's own input validation reports the precise mismatch).
		if (parsed instanceof AString inner) {
			String is = inner.toString().trim();
			if (!is.isEmpty() && (is.charAt(0) == '{' || is.charAt(0) == '[')) {
				try {
					return convex.core.util.JSON.parse(is);
				} catch (Exception ignored) { /* keep the single-parsed value */ }
			}
		}
		return parsed;
	}

	/** Truncates a value for inclusion in an error message. */
	private static String snippet(String s) {
		return (s.length() <= 80) ? s : s.substring(0, 77) + "...";
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
