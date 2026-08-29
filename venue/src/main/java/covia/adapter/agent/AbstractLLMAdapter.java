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
import covia.adapter.agent.ContextInspectable.Inspection;
import covia.adapter.ToolCallArguments;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Asset;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.AgentState;
import covia.venue.Engine;
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
	/** Message indices the assembler marks as prompt-cache breakpoints (AGENT_CONTEXT.md §3.1). */
	public static final AString K_CACHE_MARKS = Strings.intern("cacheMarks");
	public static final AString K_TOOL_CALLS = Strings.intern("toolCalls");
	public static final AString K_FINISH_REASON = Strings.intern("finishReason");
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

	// ========== context_load / context_unload helpers ==========

	public static final AString K_PATH   = Strings.intern("path");
	public static final AString K_PATHS  = Strings.intern("paths");
	public static final AString K_BUDGET = Strings.intern("budget");
	public static final AString K_LABEL  = Strings.intern("label");

	/** Default render budget per loaded context entry, in bytes. */
	public static final long CONTEXT_LOAD_DEFAULT_BUDGET = 500L;
	public static final long CONTEXT_LOAD_MIN_BUDGET     = 256L;
	public static final long CONTEXT_LOAD_MAX_BUDGET     = 10_000L;

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

	// ========== skill_load helpers (SKILLS.md §5) ==========

	public static final AString K_REF = Strings.intern("ref");

	/** Default accounting budget for a loaded skill — bodies run bigger than
	 *  data loads (overridable per call, and per skill via {@code skill.budget}). */
	public static final long SKILL_LOAD_DEFAULT_BUDGET = 2_000L;

	/**
	 * Builds the loaded-context entry metadata: {@code {budget, ts, label?}}.
	 * The {@code label} key is omitted when the input is null.
	 */
	public static AMap<AString, ACell> buildLoadEntryMeta(long budget, AString label) {
		AMap<AString, ACell> meta = Maps.of(
			K_BUDGET, CVMLong.create(budget),
			Strings.intern("ts"), CVMLong.create(convex.core.util.Utils.getCurrentTimestamp()),
			Loads.K_AGENT_MANAGED, CVMBool.TRUE,
			Loads.K_TRUSTED, CVMBool.FALSE);
		if (label != null) meta = meta.assoc(K_LABEL, label);
		return meta;
	}

	// ========== Role values ==========

	public static final AString ROLE_SYSTEM    = Strings.intern("system");
	public static final AString ROLE_USER      = Strings.intern("user");
	public static final AString ROLE_ASSISTANT = Strings.intern("assistant");
	public static final AString ROLE_TOOL      = Strings.intern("tool");
	private static final AString FINISH_LENGTH = Strings.intern("length");

	static final String TRUNCATION_RETRY_MESSAGE =
		"The previous assistant response reached its output token limit and was incomplete. "
		+ "Regenerate a complete, concise response from the original request. Do not continue "
		+ "or act on any partial tool call from that response.";
	static final String TRUNCATION_FAILURE_MESSAGE =
		"LLM response reached its output token limit twice. Increase maxTokens or request a shorter response.";

	// ========== Defaults ==========

	public static final AString DEFAULT_LLM_OPERATION = Strings.create(
		"v/models/anthropic/claude-sonnet-5");

	// ========== Authority and model profile (AGENT_CONTEXT.md §4, §8) ==========

	/** The agent's capability-narrowed context: {@code config.caps} applied, else {@code ctx} unchanged. */
	public static RequestContext capsContext(AMap<AString, ACell> config, RequestContext ctx) {
		AVector<ACell> caps = RT.ensureVector(config != null ? config.get(K_CAPS) : null);
		return (caps != null) ? ctx.withCaps(caps) : ctx;
	}

	/**
	 * What the model declares that assembly needs: its context budget in
	 * bytes, its label dialect, and whether it can call tools at all — with
	 * tool calling off, no tool is presented (OPERATIONS.md, <i>The
	 * {@code model} facet</i>).
	 */
	public record ModelProfile(long budget, AString labels, boolean toolCalling) {
		public static final ModelProfile DEFAULT =
			new ModelProfile(ContextAssembler.DEFAULT_BUDGET, LABELS_BRACKET, true);

		/** The profile a resolved facet map declares; defaults fill what it does not. */
		public static ModelProfile of(AMap<AString, ACell> profile) {
			return new ModelProfile(
				AbstractLLMAdapter.budgetBytes(profile, ContextAssembler.DEFAULT_BUDGET),
				AbstractLLMAdapter.labelDialect(profile),
				AbstractLLMAdapter.toolCalling(profile));
		}
	}

	/**
	 * The model profile for an agent: the {@code model} facet of its LLM
	 * operation, resolved for its model, with the agent's own
	 * {@code config.modelProfile} layered last. Defaults when the asset cannot
	 * be read: a missing provider must fail at the call, not at assembly.
	 */
	protected ModelProfile modelProfileFor(AMap<AString, ACell> config, RequestContext ctx) {
		try {
			Asset asset = engine.resolveAsset(getLLMOperation(config), ctx);
			return ModelProfile.of(resolveModel(engine, asset,
				RT.ensureString(config != null ? config.get(K_MODEL) : null), config, ctx).assemblyProfile());
		} catch (RuntimeException e) {
			return ModelProfile.DEFAULT;
		}
	}

	/**
	 * A selected provider or model operation reduced to the same effective
	 * execution data. Caller-supplied {@code model} wins over operation
	 * defaults; a model preset contributes its own facet only while its selected
	 * id remains effective.
	 */
	public record ResolvedModel(Asset operation, Asset provider, AString modelId,
			AMap<AString, ACell> executionProfile, AMap<AString, ACell> assemblyProfile) {}

	/** Resolves the data shared by provider-edge rendering and agent assembly. */
	public static ResolvedModel resolveModel(Engine engine, Asset selected, AString requestedModel,
			AMap<AString, ACell> config, RequestContext ctx) {
		if (selected == null) throw new IllegalArgumentException("LLM operation does not resolve");
		AMap<AString, ACell> selectedMeta = selected.meta();
		AMap<AString, ACell> selectedFacet = modelFacet(selectedMeta);
		AString selectedId = RT.ensureString(selectedFacet.get(K_MODEL_ID));
		AString modelId = (requestedModel != null) ? requestedModel : declaredModelDefault(selectedMeta);
		if (modelId == null) modelId = selectedId;

		Asset provider = selected;
		AString providerRef = RT.ensureString(selectedFacet.get(K_MODEL_PROVIDER));
		if (providerRef != null) {
			provider = engine.resolveAsset(providerRef, ctx);
			if (provider == null) throw new IllegalArgumentException(
				"Model provider does not resolve: " + providerRef);
		}

		AMap<AString, ACell> execution = modelProfile(provider.meta(), modelId);
		if (selectedId != null && selectedId.equals(modelId)) {
			execution = layer(execution, modelDefinitionProfile(selectedFacet));
		}
		AMap<AString, ACell> assembly = layer(execution,
			RT.ensureMap(config != null ? config.get(K_MODEL_PROFILE) : null));
		return new ResolvedModel(selected, provider, modelId, execution, assembly);
	}

	/**
	 * The cycle's effective config: the identity prompt resolved to text and
	 * the operation's model default added, for inspection and L3 input. Both
	 * runtimes call this once per cycle, so a {@code systemPrompt} entry is
	 * read once and the head stays identical across the cycle's inferences.
	 */
	protected AMap<AString, ACell> effectiveModelConfig(AMap<AString, ACell> config,
			RequestContext ctx) {
		AMap<AString, ACell> effective = resolveSystemPrompt((config != null) ? config : Maps.empty(), ctx);
		if (effective.get(K_MODEL) != null) return effective;
		try {
			Asset selected = engine.resolveAsset(getLLMOperation(effective), ctx);
			AString modelId = resolveModel(engine, selected, null, effective, ctx).modelId();
			return (modelId != null) ? effective.assoc(K_MODEL, modelId) : effective;
		} catch (RuntimeException e) {
			return effective;
		}
	}

	/**
	 * {@code config.systemPrompt} as text. A string is the prompt itself. A map
	 * is a context entry (AGENT_CONTEXT.md §6.2 — {@code ref}, {@code text},
	 * {@code op} + {@code input}, {@code job}) resolved through the same
	 * loader as pinned context and loads, once per cycle, under the cycle's
	 * identity: a prompt kept at a workspace path or in a DLFS file, or built
	 * by a read-only operation. A prompt that does not resolve fails the
	 * cycle — a missing identity is a configuration error, not something to
	 * render around.
	 */
	protected AMap<AString, ACell> resolveSystemPrompt(AMap<AString, ACell> config, RequestContext ctx) {
		ACell prompt = config.get(K_SYSTEM_PROMPT);
		if (!(prompt instanceof AMap)) return config;
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> entry = (AMap<AString, ACell>) prompt;
		try {
			String text = new ContextLoader(engine).resolveText(entry, ctx);
			return config.assoc(K_SYSTEM_PROMPT, Strings.create(text));
		} catch (RuntimeException e) {
			throw new IllegalStateException("config.systemPrompt did not resolve — " + entry
				+ ": " + ContextLoader.rootMessage(e), e);
		}
	}

	// ========== Inspection (template method) ==========

	/**
	 * The context this runtime would assemble for the hypothetical call —
	 * the same Spec through the same assembler as a live transition, minus
	 * the provider call. Final on the parent: subclasses supply the Spec via
	 * {@link #inspectionContext}.
	 */
	@Override
	public final AMap<AString, ACell> inspectContext(Inspection inspection, RequestContext ctx) {
		InspectionContext inspected = inspectionContext(inspection, ctx);
		return ContextAssembler.report(inspected.spec(), inspected.diagnostics());
	}

	/** The Spec and resolution sidecars a live transition with these inputs would assemble. */
	protected record InspectionContext(ContextAssembler.Spec spec,
			ContextAssembler.Diagnostics diagnostics) {}

	protected abstract InspectionContext inspectionContext(Inspection inspection, RequestContext ctx);

	// ========== Step: one harness iteration on a supplied reply ==========

	public static final AString K_TERMINAL = Strings.intern("terminal");
	public static final AString K_DONE     = Strings.intern("done");
	public static final AString K_NEXT     = Strings.intern("next");

	/** A conversation turn stamped with the wall-clock moment it was recorded. */
	static ACell stampTs(ACell message) {
		if (!(message instanceof AMap<?, ?>)) return message;
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> m = (AMap<AString, ACell>) message;
		return m.assoc(Fields.TS, CVMLong.create(convex.core.util.Utils.getCurrentTimestamp()));
	}

	/**
	 * The reply {@code agent:step} steps through, normalised to the shape the
	 * loops see from a provider: a string is text only; a map carries
	 * {@code content} and/or {@code toolCalls}, each call given an id when it
	 * has none and empty arguments when it has none. Null in, null out.
	 *
	 * @throws IllegalArgumentException when a call has no name, or the value is neither form
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> stepAssistant(ACell raw) {
		if (raw == null) return null;
		if (raw instanceof AString text) return Maps.of(K_ROLE, ROLE_ASSISTANT, K_CONTENT, text);
		if (!(raw instanceof AMap)) throw new IllegalArgumentException(
			"assistant must be a string or {content?, toolCalls?}");
		AMap<AString, ACell> reply = ((AMap<AString, ACell>) raw).assoc(K_ROLE, ROLE_ASSISTANT);
		AVector<ACell> calls = RT.ensureVector(reply.get(K_TOOL_CALLS));
		if (calls == null || calls.isEmpty()) return reply.dissoc(K_TOOL_CALLS);
		AVector<ACell> normalised = Vectors.empty();
		for (long i = 0; i < calls.count(); i++) {
			AMap<AString, ACell> call = RT.ensureMap(calls.get(i));
			AString name = (call != null) ? RT.ensureString(call.get(K_NAME)) : null;
			if (name == null) throw new IllegalArgumentException(
				"assistant.toolCalls[" + i + "] needs a name");
			if (call.get(K_ID) == null) call = call.assoc(K_ID, Strings.create("step-" + i));
			if (call.get(K_ARGUMENTS) == null) call = call.assoc(K_ARGUMENTS, Maps.empty());
			normalised = normalised.conj(call);
		}
		return reply.assoc(K_TOOL_CALLS, normalised);
	}

	/** Collects what one batch produces — the tool-result turns in order
	 *  and each call's wall-clock — for the step report. */
	protected static final class StepSink implements ToolCycleEngine.BatchSink {
		private AVector<ACell> turns = Vectors.empty();
		private final Map<AString, Long> millis = new java.util.HashMap<>();

		@Override
		public void append(AMap<AString, ACell> message) { turns = turns.conj(message); }

		@Override
		public void recordCall(ToolCycleEngine.ToolCall call, ToolCycleEngine.ToolOutcome outcome, long ms) {
			if (call.id() != null) millis.put(call.id(), ms);
		}

		AVector<ACell> turns() { return turns; }
	}

	/**
	 * One stepped iteration, reported. {@code turns} is everything the
	 * iteration would append to the conversation, the reply first;
	 * {@code next} is null when the cycle would end here.
	 */
	protected record Step(AMap<AString, ACell> assistant, AVector<ACell> turns, StepSink sink,
			String terminalStatus, ACell terminalValue, ACell response, ContextAssembler.Spec next) {

		/** A reply the loop would return as the cycle's response. */
		static Step done(AMap<AString, ACell> assistant, ACell response) {
			return new Step(assistant, Vectors.of((ACell) assistant), null, null, null, response, null);
		}

		AMap<AString, ACell> report() {
			AMap<AString, ACell> r = Maps.of(
				ROLE_ASSISTANT, assistant,
				Fields.TURNS, turns,
				Fields.CALLS, calls(),
				K_DONE, CVMBool.create(next == null));
			if (terminalStatus != null) {
				AMap<AString, ACell> t = Maps.of(Fields.STATUS, Strings.create(terminalStatus));
				if (terminalValue != null) t = t.assoc(Fields.VALUE, terminalValue);
				r = r.assoc(K_TERMINAL, t);
			}
			if (response != null) r = r.assoc(Fields.RESPONSE, response);
			if (next != null) r = r.assoc(K_NEXT, ContextAssembler.report(next));
			return r;
		}

		/** Each dispatched call paired with its result: {@code {id, name, arguments, result, isError?, ms}}. */
		private AVector<ACell> calls() {
			AVector<ACell> out = Vectors.empty();
			if (sink == null) return out;
			Map<AString, ACell> arguments = new java.util.HashMap<>();
			AVector<ACell> requested = RT.ensureVector(assistant.get(K_TOOL_CALLS));
			for (long i = 0; requested != null && i < requested.count(); i++) {
				AString id = RT.ensureString(RT.getIn(requested.get(i), K_ID));
				if (id != null) arguments.put(id, RT.getIn(requested.get(i), K_ARGUMENTS));
			}
			for (long i = 0; i < sink.turns().count(); i++) {
				AMap<AString, ACell> turn = RT.ensureMap(sink.turns().get(i));
				if (turn == null || !ROLE_TOOL.equals(turn.get(K_ROLE))) continue;
				AString id = RT.ensureString(turn.get(K_ID));
				AMap<AString, ACell> call = Maps.of(K_NAME, turn.get(K_NAME));
				if (id != null) call = call.assoc(K_ID, id);
				ACell args = (id != null) ? arguments.get(id) : null;
				if (args != null) call = call.assoc(K_ARGUMENTS, args);
				ACell result = turn.containsKey(K_STRUCTURED_CONTENT)
					? turn.get(K_STRUCTURED_CONTENT) : turn.get(K_CONTENT);
				if (result != null) call = call.assoc(Fields.RESULT, result);
				if (CVMBool.TRUE.equals(turn.get(K_IS_ERROR))) call = call.assoc(K_IS_ERROR, CVMBool.TRUE);
				Long ms = (id != null) ? sink.millis.get(id) : null;
				if (ms != null) call = call.assoc(Fields.MS, CVMLong.create(ms));
				out = out.conj(call);
			}
			return out;
		}
	}

	/** The frames vector of a session record, or null when absent. */
	protected static AVector<ACell> sessionFramesOf(AMap<AString, ACell> session) {
		return (session != null) ? RT.ensureVector(session.get(Fields.FRAMES)) : null;
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
		return invokeLevel3(llmOperation, config, buildL3Input(config, messages, tools), messages, ctx);
	}

	/**
	 * As {@link #invokeLevel3(AString, AMap, AVector, AVector, RequestContext)}
	 * for an assembled prompt: the L3 input also carries the prompt's cache
	 * marks, so a caching provider can place its breakpoints at the band
	 * boundaries (AGENT_CONTEXT.md §3.1).
	 */
	protected ACell invokeLevel3(AString llmOperation, AMap<AString, ACell> config,
			ContextAssembler.Prompt prompt, RequestContext ctx) {
		// The cycle record (#392) sees every call made from an assembled
		// prompt: what it newly sent, then the reply verbatim or the failure.
		CycleRecord record = CycleRecord.current();
		if (record != null) {
			record.beginInference(prompt, llmOperation,
				RT.ensureString(config != null ? config.get(K_MODEL) : null));
		}
		try {
			ACell reply = invokeLevel3(llmOperation, config, prompt.toL3Input(config), prompt.messages(), ctx);
			if (record != null) record.endInference(reply);
			return reply;
		} catch (RuntimeException e) {
			if (record != null) record.failInference(describeFailure(e));
			throw e;
		}
	}

	private ACell invokeLevel3(AString llmOperation, AMap<AString, ACell> config,
			AMap<AString, ACell> l3Input, AVector<ACell> messages, RequestContext ctx) {
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
			JobFailedException failure = new JobFailedException("LLM call timed out after " + llmTimeoutMs
				+ "ms (" + llmOperation + ")");
			failure.initCause(e);
			throw failure;
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
		return ToolCallArguments.canonicaliseAssistantMessage(result);
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
	/** Canonical model id on a model-operation asset. */
	public static final AString K_MODEL_ID = Strings.intern("id");
	/** Provider operation reference on a model-operation asset. */
	public static final AString K_MODEL_PROVIDER = Strings.intern("provider");
	private static final AString K_MODEL_TAGS = Strings.intern("tags");
	private static final AString K_MODEL_RECOMMENDED = Strings.intern("recommended");
	/** Model option: {@code false} declares a model that cannot call tools. */
	public static final AString OPT_TOOL_CALLING = Strings.intern("toolCalling");
	/** Model option: tool support varies per model, so it must be probed, never assumed. */
	public static final AString OPT_TOOL_CALLING_BY_MODEL = Strings.intern("toolCallingByModel");
	/** Provider-specific rendering hints, inside the {@code model} facet. */
	public static final AString K_OPTIONS = Strings.intern("options");
	/** Per-model overrides inside the {@code model} facet, keyed by model id. */
	public static final AString K_BY_MODEL = Strings.intern("byModel");
	/** Agent config: the facet's shape again, layered over the operation's for this agent. */
	public static final AString K_MODEL_PROFILE = Strings.intern("modelProfile");
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
		return layer(profile, RT.ensureMap(((AMap<AString, ACell>) byModel).get(modelId)));
	}

	/**
	 * The facet resolved for one model and one agent: the provider level, the
	 * model's {@code byModel} entry, then the agent's {@code config.modelProfile}
	 * — each layered one key deep, stating only what it changes. An agent
	 * override speaks to assembly ({@code options.toolCalling},
	 * {@code options.labels}, {@code budget.bytes}); the provider edge reads the
	 * operation's own facet.
	 */
	public static AMap<AString, ACell> modelProfile(AMap<AString, ACell> meta, AString modelId,
			AMap<AString, ACell> config) {
		return layer(modelProfile(meta, modelId),
			RT.ensureMap(config != null ? config.get(K_MODEL_PROFILE) : null));
	}

	/** Operation default first, legacy schema default second. */
	private static AString declaredModelDefault(AMap<AString, ACell> meta) {
		AString model = RT.ensureString(RT.getIn(meta, Fields.OPERATION, Fields.DEFAULT, K_MODEL));
		if (model != null) return model;
		return RT.ensureString(RT.getIn(meta, Fields.OPERATION, Fields.INPUT,
			K_PROPERTIES, K_MODEL, Fields.DEFAULT));
	}

	/** Removes discovery/identity fields from a model asset's executable profile. */
	private static AMap<AString, ACell> modelDefinitionProfile(AMap<AString, ACell> facet) {
		return facet.dissoc(K_MODEL_ID)
			.dissoc(K_MODEL_PROVIDER)
			.dissoc(K_MODEL_TAGS)
			.dissoc(Fields.DEFAULT)
			.dissoc(K_MODEL_RECOMMENDED);
	}

	/** {@code override} over {@code base}, one key deep: maps merge key-wise, anything else replaces. */
	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> layer(AMap<AString, ACell> base, AMap<AString, ACell> override) {
		if (override == null) return base;
		AMap<AString, ACell> profile = base;
		for (Map.Entry<AString, ACell> e : override.entrySet()) {
			AString key = e.getKey();
			ACell value = e.getValue();
			ACell under = profile.get(key);
			if (under instanceof AMap && value instanceof AMap) {
				AMap<AString, ACell> merged = (AMap<AString, ACell>) under;
				for (Map.Entry<AString, ACell> inner : ((AMap<AString, ACell>) value).entrySet()) {
					merged = merged.assoc(inner.getKey(), inner.getValue());
				}
				value = merged;
			}
			profile = profile.assoc(key, value);
		}
		return profile;
	}

	/** Whether a resolved profile allows tool calling: {@code options.toolCalling}, true unless declared false. */
	public static boolean toolCalling(AMap<AString, ACell> profile) {
		return !CVMBool.FALSE.equals(RT.getIn(profile, K_OPTIONS, OPT_TOOL_CALLING));
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
	 * <li>{@code toolCalling}: {@code false} declares that the model cannot call
	 *     tools — {@code agent:create} warns when an agent declares tools or
	 *     skills against it. Absent means tool calling is available.</li>
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

	/** One string option from an already resolved execution profile. */
	public static String modelOptionText(AMap<AString, ACell> profile, AString key) {
		AString v = RT.ensureString(RT.getIn(profile, K_OPTIONS, key));
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
		return budgetBytes(modelProfile(meta, modelId), defaultBytes);
	}

	/** {@code budget.bytes} of a resolved profile, or the default when absent or not a positive integer. */
	public static long budgetBytes(AMap<AString, ACell> profile, long defaultBytes) {
		CVMLong bytes = RT.ensureLong(RT.getIn(profile, K_BUDGET, K_BYTES));
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
		return labelDialect(modelProfile(meta, modelId));
	}

	/** The label dialect a resolved profile declares, by the same rule. */
	public static AString labelDialect(AMap<AString, ACell> profile) {
		AString declared = RT.ensureString(RT.getIn(profile, K_OPTIONS, OPT_LABELS));
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
		l3Input = copyIfPresent(config, l3Input, K_MODEL, K_URL, K_API_KEY, K_RESPONSE_FORMAT,
			Strings.intern("maxTokens"), Strings.intern("temperature"), Strings.intern("topP"),
			Strings.intern("cache"));
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

	/** True when the provider says the assistant response stopped at its output bound. */
	static boolean isLengthLimited(ACell assistant) {
		AString reason = RT.ensureString(RT.getIn(assistant, K_FINISH_REASON));
		return reason != null && FINISH_LENGTH.toString().equalsIgnoreCase(reason.toString());
	}

	/** Persisted diagnostic that changes the retry prompt without retaining partial output. */
	static AMap<AString, ACell> truncationRetryTurn() {
		return Maps.of(
			K_ROLE, ROLE_SYSTEM,
			K_CONTENT, Strings.create(TRUNCATION_RETRY_MESSAGE),
			AgentState.K_SOURCE, AgentState.SOURCE_TRANSITION);
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
			for (String n : GoalTreeAdapter.HARNESS_NAMES) m.put(n, "goaltree");
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
	 * Invokes an operation with a per-call timeout, normally via the no-Job
	 * internal dispatch path. Sessioned {@code hitl:request} is the deliberate
	 * exception: it returns its durable Job handle immediately and re-enters the
	 * session on resolution. {@code invokeInternal} enforces the grant scope carried by
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
			// HITL is naturally longer-lived than a model turn. Its operation
			// already owns a durable Job, so a sessioned agent receives that handle
			// immediately; HITLAdapter delivers the eventual outcome back to this
			// session. Direct/API callers retain the ordinary parked-Job contract.
			if ("v/ops/hitl/request".equals(operation.toString())
					&& ctx.getAgentId() != null && ctx.getSessionId() != null) {
				Job job = engine.jobs().invokeOperation(operation, input, ctx);
				String error = job.getErrorMessage();
				if (error != null) return Strings.create("Error: " + error);
				AMap<AString, ACell> receipt = Maps.of(
					Fields.ID, Strings.create(job.getID().toHexString()),
					Fields.STATUS, job.getStatus());
				AString title = RT.ensureString(RT.getIn(input, Fields.TITLE));
				if (title != null) receipt = receipt.assoc(Fields.TITLE, title);
				return receipt;
			}
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
