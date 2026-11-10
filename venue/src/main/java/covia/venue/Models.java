package covia.venue;

import java.util.Map;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;

/**
 * The model an agent's config names, and the profile its definition declares
 * (OPERATIONS.md, <i>The {@code model} facet</i>; MODELS.md). These are
 * functions of lattice values: the caller resolves each reference.
 */
public final class Models {

	private Models() {}

	/** Agent config: the LLM operation, a provider operation or a model definition. */
	public static final AString K_LLM_OPERATION = Strings.intern("llmOperation");
	/** Agent config: the model id to call. */
	public static final AString K_MODEL = Strings.intern("model");
	/** Agent config: the facet's shape again, layered over the operation's for this agent. */
	public static final AString K_MODEL_PROFILE = Strings.intern("modelProfile");

	/** The LLM operation of an agent whose config names none. */
	public static final AString DEFAULT_LLM_OPERATION = Strings.intern("v/models/anthropic/claude-sonnet-5-5");

	/** The {@code model} facet key on an LLM operation asset. */
	public static final AString K_MODEL_FACET = Strings.intern("model");
	/** Canonical model id on a model-operation asset. */
	public static final AString K_MODEL_ID = Strings.intern("id");
	/** Provider operation reference on a model-operation asset. */
	public static final AString K_MODEL_PROVIDER = Strings.intern("provider");
	private static final AString K_MODEL_TAGS = Strings.intern("tags");
	private static final AString K_MODEL_RECOMMENDED = Strings.intern("recommended");
	/** Provider-specific rendering hints, inside the {@code model} facet. */
	public static final AString K_OPTIONS = Strings.intern("options");
	/** Per-model overrides inside the {@code model} facet, keyed by model id. */
	public static final AString K_BY_MODEL = Strings.intern("byModel");
	/** Model option: {@code false} declares a model that cannot call tools. */
	public static final AString OPT_TOOL_CALLING = Strings.intern("toolCalling");

	/** The LLM operation {@code config} names, or {@link #DEFAULT_LLM_OPERATION}. */
	public static AString llmOperation(AMap<AString, ACell> config) {
		AString op = (config == null) ? null : RT.ensureString(config.get(K_LLM_OPERATION));
		return (op != null) ? op : DEFAULT_LLM_OPERATION;
	}

	/**
	 * The {@code model} facet on an LLM operation asset (e.g.
	 * {@code v/ops/langchain/anthropic}), or an empty map when absent or
	 * malformed — discovery must answer even for a broken asset.
	 *
	 * <p>The facet declares, as data on the asset rather than branches in
	 * code, facts about the model that change how a prompt is shaped and sized
	 * for it: {@code options} (rendering hints, among them
	 * {@link #OPT_TOOL_CALLING}), {@code budget.bytes} (the context size
	 * appropriate for the model, in bytes of UTF-8), and {@code byModel.<id>}
	 * (the same shape again for one model id, layered over the provider level
	 * by {@link #modelProfile}).</p>
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
	 * {@code options.labels}, {@code options.lateToolResults},
	 * {@code budget.bytes}); the provider edge reads the operation's own facet.
	 */
	public static AMap<AString, ACell> modelProfile(AMap<AString, ACell> meta, AString modelId,
			AMap<AString, ACell> config) {
		return layer(modelProfile(meta, modelId),
			RT.ensureMap(config != null ? config.get(K_MODEL_PROFILE) : null));
	}

	/** Whether a resolved profile allows tool calling: {@code options.toolCalling}, true unless declared false. */
	public static boolean toolCalling(AMap<AString, ACell> profile) {
		return !CVMBool.FALSE.equals(RT.getIn(profile, K_OPTIONS, OPT_TOOL_CALLING));
	}

	/**
	 * A selected provider or model operation reduced to the data its calls and
	 * an agent's context are built from: the model id in effect, the profile
	 * the provider edge reads, and that profile with the agent's own
	 * {@code config.modelProfile} over it.
	 */
	public record Resolved(AString modelId, AMap<AString, ACell> executionProfile,
			AMap<AString, ACell> assemblyProfile) {}

	/**
	 * Resolves a selected provider or model operation. A caller-supplied
	 * {@code requestedModel} wins over the operation's defaults; a model
	 * definition contributes its own facet only while its id remains the one in
	 * effect.
	 *
	 * @param selected The selected operation's metadata
	 * @param lookup The operation metadata a reference names, or null when it names none
	 * @return The resolution, or null when the provider a model definition names does not resolve
	 */
	public static Resolved resolve(AMap<AString, ACell> selected, AString requestedModel,
			AMap<AString, ACell> config, Function<AString, AMap<AString, ACell>> lookup) {
		AMap<AString, ACell> selectedFacet = modelFacet(selected);
		AString selectedId = RT.ensureString(selectedFacet.get(K_MODEL_ID));
		AString modelId = (requestedModel != null) ? requestedModel : declaredModelDefault(selected);
		if (modelId == null) modelId = selectedId;

		AMap<AString, ACell> provider = selected;
		AString providerRef = providerRef(selected);
		if (providerRef != null) {
			provider = lookup.apply(providerRef);
			if (provider == null) return null;
		}

		AMap<AString, ACell> execution = modelProfile(provider, modelId);
		if (selectedId != null && selectedId.equals(modelId)) {
			execution = layer(execution, modelDefinitionProfile(selectedFacet));
		}
		AMap<AString, ACell> assembly = layer(execution,
			RT.ensureMap(config != null ? config.get(K_MODEL_PROFILE) : null));
		return new Resolved(modelId, execution, assembly);
	}

	/** The provider operation a model definition names, or null for a provider operation itself. */
	public static AString providerRef(AMap<AString, ACell> selected) {
		return RT.ensureString(modelFacet(selected).get(K_MODEL_PROVIDER));
	}

	/** Operation default first, legacy schema default second. */
	private static AString declaredModelDefault(AMap<AString, ACell> meta) {
		AString model = RT.ensureString(RT.getIn(meta, Fields.OPERATION, Fields.DEFAULT, K_MODEL));
		if (model != null) return model;
		return RT.ensureString(RT.getIn(meta, Fields.OPERATION, Fields.INPUT,
			Fields.PROPERTIES, K_MODEL, Fields.DEFAULT));
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
}
