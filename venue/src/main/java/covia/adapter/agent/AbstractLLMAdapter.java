package covia.adapter.agent;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.AAdapter;
import covia.adapter.ToolCallArguments;
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
 *   <li>Input parsing — canonical structured tool arguments with provider compatibility</li>
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
	public static final AString K_LLM_TIMEOUT_MS       = Strings.intern("llmTimeoutMs");

	/**
	 * Per-tool-call timeout default. Bounds the wait on any single grid op
	 * invoked as a tool so a stuck sub-job cannot hang the parent agent's
	 * run loop indefinitely. See covia-ai/covia#82.
	 */
	public static final long DEFAULT_TOOL_CALL_TIMEOUT_MS = 300_000L;

	/**
	 * Per-call timeout default for the level 3 LLM invocation. Bounds the wait
	 * on a single provider call so a hung HTTP connection (or a provider that
	 * ignores its own client-side timeout) cannot stall an agent run loop
	 * indefinitely. On timeout the L3 invocation is cancelled — interrupting
	 * the provider worker — and the transition fails.
	 */
	public static final long DEFAULT_LLM_TIMEOUT_MS = 120_000L;

	// ========== Message field keys ==========

	public static final AString K_ROLE       = Strings.intern("role");
	public static final AString K_CONTENT    = Strings.intern("content");
	public static final AString K_MESSAGES   = Strings.intern("messages");
	public static final AString K_TOOL_CALLS = Strings.intern("toolCalls");
	public static final AString K_ID         = Strings.intern("id");
	public static final AString K_NAME       = Strings.intern("name");
	public static final AString K_ARGUMENTS  = Strings.intern("arguments");
	public static final AString K_STRUCTURED_CONTENT = Strings.intern("structuredContent");
	public static final AString K_IS_ERROR   = Strings.intern("isError");

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
		return clampLoadBudget(budgetCell, CONTEXT_LOAD_DEFAULT_BUDGET);
	}

	/** As {@link #clampLoadBudget(ACell)} with a caller-chosen default. */
	public static long clampLoadBudget(ACell budgetCell, long defaultBudget) {
		if (budgetCell instanceof CVMLong l) {
			return Math.max(CONTEXT_LOAD_MIN_BUDGET,
				Math.min(l.longValue(), CONTEXT_LOAD_MAX_BUDGET));
		}
		return Math.max(CONTEXT_LOAD_MIN_BUDGET,
			Math.min(defaultBudget, CONTEXT_LOAD_MAX_BUDGET));
	}

	// ========== skill_load — shared schema (SKILLS.md §5) ==========

	public static final AString K_REF = Strings.intern("ref");

	/** Default accounting budget for a loaded skill — bodies run bigger than
	 *  data loads (overridable per call, and per skill via {@code skill.budget}). */
	public static final long SKILL_LOAD_DEFAULT_BUDGET = 2_000L;

	/**
	 * Shared parameter schema for the {@code skill_load} tool. Exactly one of
	 * {@code name} / {@code ref} — enforced by the handler, where the error is
	 * diagnosable, rather than by schema {@code required}.
	 */
	public static final AMap<AString, ACell> SKILL_LOAD_PARAMS = Maps.of(
		K_TYPE, Strings.create("object"),
		K_PROPERTIES, Maps.of(
			K_NAME, Maps.of(
				K_TYPE, Strings.create("string"),
				K_DESCRIPTION, Strings.create(
					"A skill name from the [Skills] index")),
			K_REF, Maps.of(
				K_TYPE, Strings.create("string"),
				K_DESCRIPTION, Strings.create(
					"Direct skill address (a/<hash>, v/skills/<x>, w/skills/<x>) — alternative to name")),
			K_BUDGET, Maps.of(
				K_TYPE, Strings.create("integer"),
				K_DESCRIPTION, Strings.create(
					"Accounting budget for the skill's context entry (default 2000, max 10000)"))));

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

	public static final AString DEFAULT_LLM_OPERATION = Strings.create("v/ops/langchain/anthropic");

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
		// Park (cheaply, on a virtual thread) until the LLM op completes,
		// bounded by llmTimeoutMs: a hung provider connection must fail the
		// transition, not stall the agent run loop indefinitely. On timeout the
		// invocation is cancelled — LangChainAdapter bridges cancellation to a
		// worker-thread interrupt, closing the in-flight HTTP call.
		long llmTimeoutMs = resolveLlmTimeoutMs(config);
		CompletableFuture<ACell> invocation = engine.jobs().invokeInternal(llmOperation, l3Input, ctx);
		ACell result;
		try {
			result = invocation.get(llmTimeoutMs, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			invocation.cancel(true);
			throw new JobFailedException("LLM call timed out after " + llmTimeoutMs
				+ "ms (" + llmOperation + ")");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			invocation.cancel(true);
			throw new JobFailedException("Interrupted while waiting for LLM call (" + llmOperation + ")");
		} catch (ExecutionException e) {
			Throwable cause = (e.getCause() != null) ? e.getCause() : e;
			if (isContextSizeFailure(cause)) {
				throw new JobFailedException("LLM provider rejected the context as too large ("
					+ llmOperation + ", approximately " + Cells.storageSize(messages)
					+ " encoded bytes). Loaded references are not truncated automatically because "
					+ "provider tokenisation varies; inspect agent:context and context_unload unused "
					+ "paths, then retry. Provider detail: " + describeFailure(cause));
			}
			if (cause instanceof RuntimeException re) throw re;
			throw new JobFailedException("LLM call failed (" + llmOperation + "): " + cause.getMessage());
		}

		// A level-3 op that completes with a failure VALUE — {status: FAILED,
		// message: ...} from Status.failure (missing/invalid API key, unknown
		// provider) — is not an assistant message. Without this guard the tool
		// loop sees no toolCalls and no content and silently emits an empty
		// response, hiding the real failure. Surface it as a transition failure
		// so the framework fails the caller's Job with the provider's message.
		// (An op that throws already propagates exceptionally via join().)
		if (result instanceof AMap && Status.FAILED.equals(RT.getIn(result, Fields.STATUS))) {
			AString message = RT.ensureString(RT.getIn(result, Fields.MESSAGE));
			if (message != null && isContextSizeFailure(
					new IllegalArgumentException(message.toString()))) {
				throw new JobFailedException("LLM provider rejected the context as too large ("
					+ llmOperation + ", approximately " + Cells.storageSize(messages)
					+ " encoded bytes). Loaded references are not truncated automatically because "
					+ "provider tokenisation varies; inspect agent:context and context_unload unused "
					+ "paths, then retry. Provider detail: " + message);
			}
			throw new JobFailedException("LLM call failed (" + llmOperation + "): "
				+ (message != null ? message.toString() : "no message"));
		}
		// Keep the persisted agent protocol provider-neutral even when a custom
		// Level 3 operation returns OpenAI-style JSON text. Malformed text remains
		// intact so executeToolCall can produce a visible, correctable error.
		result = ToolCallArguments.canonicaliseAssistantMessage(result);

		// Provider-reported usage rides the assistant message (tokens
		// {input, output, total}); add it to this transition's tally (#217).
		tallyTokens(result);
		return result;
	}

	/** Recognises common provider context-window failures without guessing tokens. */
	private static boolean isContextSizeFailure(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause()) {
			String message = t.getMessage();
			if (message == null) continue;
			String m = message.toLowerCase(java.util.Locale.ROOT);
			if (m.contains("context_length_exceeded")
					|| m.contains("maximum context length")
					|| m.contains("prompt is too long")
					|| m.contains("input is too long")
					|| m.contains("too many input tokens")
					|| m.contains("request too large")
					|| m.contains("context window")) return true;
		}
		return false;
	}

	// ========== The `model` facet: rendering hints and context budget ==========

	/** The {@code model} facet key on an LLM operation asset. */
	public static final AString K_MODEL_FACET = Strings.intern("model");
	/** Provider-specific rendering hints, inside the {@code model} facet. */
	public static final AString K_OPTIONS = Strings.intern("options");
	/** Per-model overrides inside the {@code model} facet, keyed by model id. */
	public static final AString K_BY_MODEL = Strings.intern("byModel");
	/** Context budget in bytes of UTF-8, inside {@code model.budget}. */
	public static final AString K_BYTES = Strings.intern("bytes");

	/**
	 * The {@code model} facet on an LLM operation asset (e.g.
	 * {@code v/ops/langchain/anthropic}), or an empty map when absent or
	 * malformed — discovery must answer even for a broken asset.
	 *
	 * <p>The facet declares, as data on the asset rather than branches in
	 * code, facts about the model that change how a prompt is shaped and sized
	 * for it:</p>
	 * <ul>
	 * <li>{@code options} — rendering hints, see {@link #modelOptions};</li>
	 * <li>{@code budget.bytes} — an estimate of the context size appropriate
	 *     for the model, in bytes of UTF-8, see {@link #modelBudgetBytes};</li>
	 * <li>{@code byModel.<id>} — the same shape again for one model id, layered
	 *     over the provider level by {@link #modelProfile}.</li>
	 * </ul>
	 *
	 * <p>An absent facet means the OpenAI-compatible norm, so a provider only
	 * declares what differs. Every map is open — unknown keys are ignored, so a
	 * newer asset stays readable by an older venue.</p>
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> modelFacet(AMap<AString, ACell> meta) {
		ACell facet = (meta != null) ? meta.get(K_MODEL_FACET) : null;
		return (facet instanceof AMap) ? (AMap<AString, ACell>) facet : Maps.empty();
	}

	/**
	 * The facet resolved for one model: the provider level with that model's
	 * {@code byModel} entry layered over it, one key deep — {@code options}
	 * and {@code budget} merge key-wise, so an override states only what it
	 * changes. {@code byModel} itself is dropped from the result. A null or
	 * unknown model id yields the provider level.
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> modelProfile(AMap<AString, ACell> meta, AString modelId) {
		AMap<AString, ACell> facet = modelFacet(meta);
		ACell byModel = facet.get(K_BY_MODEL);
		AMap<AString, ACell> profile = facet.dissoc(K_BY_MODEL);
		if (modelId == null || !(byModel instanceof AMap)) return profile;
		ACell override = ((AMap<AString, ACell>) byModel).get(modelId);
		if (!(override instanceof AMap)) return profile;
		for (Map.Entry<AString, ACell> e : ((AMap<AString, ACell>) override).entrySet()) {
			AString key = e.getKey();
			ACell value = e.getValue();
			ACell base = profile.get(key);
			if (base instanceof AMap && value instanceof AMap) {
				AMap<AString, ACell> merged = (AMap<AString, ACell>) base;
				for (Map.Entry<AString, ACell> inner : ((AMap<AString, ACell>) value).entrySet()) {
					merged = merged.assoc(inner.getKey(), inner.getValue());
				}
				value = merged;
			}
			profile = profile.assoc(key, value);
		}
		return profile;
	}

	/**
	 * The declared <b>model options</b> for one model — rendering hints: facts
	 * about the provider's API that change how a prompt should be built for it.
	 *
	 * <p>Known keys:</p>
	 * <ul>
	 * <li>{@code systemMessages}: {@code "multiple"} — separate system messages
	 *     reach the model in the position they are placed; {@code "single"} —
	 *     the API has ONE system parameter and no system role in the message
	 *     list, so every system message is hoisted into it wherever it sits and
	 *     the boundaries between them carry no meaning downstream;
	 *     {@code "none"} — no system role at all, so system content must be
	 *     folded into the first user message.</li>
	 * <li>{@code requiresUserMessage}: the request is rejected without at least
	 *     one non-system message. Anthropic's Messages API does this, which is
	 *     why the empty-state signal is a {@code user} turn.</li>
	 * <li>{@code cachePrefix}: the provider caches an explicitly marked stable
	 *     prefix, so keeping volatile elements out of the head has a direct
	 *     cost saving (AGENT_CONTEXT.md §3.1).</li>
	 * <li>{@code toolCallingByModel}: tool support varies per model rather than
	 *     per provider, so it cannot be assumed from the provider alone.</li>
	 * <li>{@code labels}: {@code "bracket"} (default), {@code "xml"} or {@code "header"} — the
	 *     dialect in which context elements are labelled, applied by the one
	 *     label renderer (AGENT_CONTEXT.md §1.1). See {@link #labelDialect}.</li>
	 * </ul>
	 *
	 * @param meta resolved operation metadata for the LLM operation
	 * @param modelId the model in use, or null for the provider level
	 * @return the options map, or an empty map when nothing is declared
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> modelOptions(AMap<AString, ACell> meta, AString modelId) {
		ACell options = modelProfile(meta, modelId).get(K_OPTIONS);
		return (options instanceof AMap) ? (AMap<AString, ACell>) options : Maps.empty();
	}

	/** One boolean model option, defaulting to false when undeclared. */
	public static boolean modelOption(AMap<AString, ACell> meta, AString modelId, AString key) {
		return CVMBool.TRUE.equals(modelOptions(meta, modelId).get(key));
	}

	/** One string model option, or null when undeclared. */
	public static String modelOptionText(AMap<AString, ACell> meta, AString modelId, AString key) {
		AString v = RT.ensureString(modelOptions(meta, modelId).get(key));
		return (v != null) ? v.toString() : null;
	}

	/**
	 * The declared <b>context budget</b> for one model, in bytes of UTF-8:
	 * {@code model.budget.bytes}, a {@code byModel} override winning over the
	 * provider level. It is an estimate of the context size <em>appropriate</em>
	 * for the model — what an assembler should target — not the hard window the
	 * provider enforces. Bytes rather than tokens because bytes are what the
	 * venue can count without a provider-specific tokenizer; a
	 * {@code budget.tokens} sibling may follow.
	 *
	 * @param defaultBytes returned when nothing is declared, or the declaration
	 *        is not a positive integer
	 */
	public static long modelBudgetBytes(AMap<AString, ACell> meta, AString modelId, long defaultBytes) {
		CVMLong bytes = RT.ensureLong(RT.getIn(modelProfile(meta, modelId), K_BUDGET, K_BYTES));
		return (bytes != null && bytes.longValue() > 0) ? bytes.longValue() : defaultBytes;
	}

	/** The {@code labels} option: which dialect context elements are labelled in. */
	public static final AString OPT_LABELS = Strings.intern("labels");
	/** {@code [Label …]} lines — the default dialect. */
	public static final AString LABELS_BRACKET = Strings.intern("bracket");
	/** XML-style elements with explicit closing tags — opt-in. */
	public static final AString LABELS_XML = Strings.intern("xml");
	/** Markdown headings — opt-in. */
	public static final AString LABELS_HEADER = Strings.intern("header");

	/**
	 * The label dialect for one model: {@link #LABELS_BRACKET} unless the
	 * asset declares {@code "xml"} or {@code "header"}. Anything else is the
	 * default — a misspelt option must never change how a prompt is labelled.
	 */
	public static AString labelDialect(AMap<AString, ACell> meta, AString modelId) {
		AString declared = RT.ensureString(modelOptions(meta, modelId).get(OPT_LABELS));
		if (LABELS_XML.equals(declared)) return LABELS_XML;
		if (LABELS_HEADER.equals(declared)) return LABELS_HEADER;
		return LABELS_BRACKET;
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
	 *        grant scope ({@link RequestContext#getCaps()})
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
		// under the agent's grant scope carried on ctx — no name-keyed pre-check here.
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
	 * dispatch path. {@code invokeInternal} enforces the grant scope carried by
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
	 * Resolves the per-call level 3 LLM timeout from the agent's merged config.
	 * Accepts CVMLong (ms) with a 1s minimum; falls back to
	 * {@link #DEFAULT_LLM_TIMEOUT_MS} if absent, non-numeric, or below it.
	 */
	public static long resolveLlmTimeoutMs(AMap<AString, ACell> config) {
		ACell v = (config != null) ? config.get(K_LLM_TIMEOUT_MS) : null;
		if (v instanceof CVMLong l) {
			long ms = l.longValue();
			if (ms >= 1000) return ms;
		}
		return DEFAULT_LLM_TIMEOUT_MS;
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
	 *
	 * <p>This is the canonical agent turn, and may be persisted under the
	 * session's {@code g/...} state. Keep collection results as their original
	 * Convex value in {@code structuredContent}; provider adapters that require
	 * textual tool results are responsible for rendering a temporary wire copy.
	 * That boundary is deliberately provider-specific so durable agent state
	 * does not silently lose types.</p>
	 */
	protected static AMap<AString, ACell> toolResultMessage(
			AString toolCallId, String toolName, ACell result) {
		AMap<AString, ACell> msg = Maps.of(
			K_ROLE, ROLE_TOOL,
			K_ID, toolCallId,
			K_NAME, Strings.create(toolName));
		// Preserve failure semantics separately from display text. Anthropic maps
		// this to tool_result.is_error; other providers may safely ignore it.
		boolean isError = (result instanceof AString s
				&& s.toString().startsWith("Error:"))
			|| (result instanceof AMap<?, ?>
				&& CVMBool.TRUE.equals(RT.getIn(result, K_IS_ERROR)));
		if (isError) msg = msg.assoc(K_IS_ERROR, CVMBool.TRUE);
		if (result instanceof AMap || result instanceof AVector) {
			return msg.assoc(K_STRUCTURED_CONTENT, result);
		}
		AString content = RT.ensureString(result);
		return msg.assoc(K_CONTENT,
			(content != null) ? content : Strings.create(result.toString()));
	}

	// ========== Tool-call argument parsing ==========

	/**
	 * Accepts canonical structured tool-call {@code arguments}, plus JSON text
	 * from OpenAI-style provider boundaries and legacy conversation history.
	 * Parsing is deliberately generous: absent/empty → empty map; structured
	 * values pass through; a JSON string is parsed; a double-encoded object or
	 * array gets one more pass. Outright garbage <b>throws</b> — callers turn
	 * that into a structured tool error the LLM sees and can correct on its next
	 * turn, never a silent {@code Maps.empty()} substitution.
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
		return ToolCallArguments.parse(rawArguments);
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
