package covia.adapter.agent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Asset;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * The tool palette: which tool definitions an agent is offered, in what order,
 * and how each tool name dispatches (AGENT_CONTEXT.md §3.2.3, §4).
 *
 * <p>Three producers feed it — harness tools (the runtime's own), configured
 * tools ({@code config.tools}, plus the opt-in default pack) and tools
 * contributed by loads — merged in that order: a name already fixed by harness
 * or config is never shadowed by a load. The initial manifest is persisted by
 * {@link ContextAssembler}; load contributions append after that boundary.</p>
 */
public final class ToolPalette {

	private static final Logger log = LoggerFactory.getLogger(ToolPalette.class);

	private ToolPalette() {}

	private static final AString K_TOOLS         = Strings.intern("tools");
	private static final AString K_DEFAULT_TOOLS = Strings.intern("defaultTools");
	private static final AString K_CAPS          = Strings.intern("caps");
	private static final AString K_NAME          = Strings.intern("name");
	private static final AString K_DESCRIPTION   = Strings.intern("description");
	private static final AString K_PARAMETERS    = Strings.intern("parameters");
	private static final AString K_TYPE          = Strings.intern("type");
	private static final AString K_PROPERTIES    = Strings.intern("properties");
	private static final AString SOURCE_DEFAULT  = Strings.intern("default");
	private static final AString SOURCE_CONFIG   = Strings.intern("config");
	private static final AString SOURCE_SKILL    = Strings.intern("skill");
	private static final AString SOURCE_LOAD     = Strings.intern("load");

	/** Default tool operations — deliberately minimal: read-only situational
	 *  awareness. Everything with side effects arrives via skills or the agent's
	 *  explicit {@code tools} allowlist. Opt-in with {@code defaultTools: true}. */
	static final AVector<ACell> DEFAULT_TOOL_OPS = (AVector<ACell>) Vectors.of(
		(ACell) Strings.create("v/ops/covia/read"),
		(ACell) Strings.create("v/ops/covia/list"));

	/**
	 * Exact provider definitions plus a direct lookup by provider tool name.
	 * Lookup values are sparse: {@code operation} is present only when this
	 * palette owns dispatch; {@code activityLabel} defaults to the name and
	 * {@code source} defaults to {@code config}. Route-free values reserve names
	 * for harness or unloadable load definitions without duplicating schemas.
	 */
	public record Palette(AVector<ACell> tools, AMap<AString, ACell> toolIndex,
			AVector<ACell> unavailable) {
		public static final Palette EMPTY = new Palette(null, null, null);

		public Palette {
			tools = (tools != null) ? tools : Vectors.empty();
			toolIndex = (toolIndex != null) ? toolIndex : Maps.empty();
			unavailable = (unavailable != null) ? unavailable : Vectors.empty();
		}

		public boolean contains(String name) {
			return name != null && toolIndex.containsKey(Strings.create(name));
		}

		public AString operation(String name) {
			return ToolPalette.operation(toolIndex, name);
		}

		public AString activityLabel(String name) {
			return ToolPalette.labelFor(toolIndex, name);
		}

		/** Appends only definitions whose name is not already present. */
		public Palette merge(Palette contributed) {
			if (contributed == null) return this;
			if (contributed.tools().isEmpty()) {
				return contributed.unavailable().isEmpty() ? this
					: new Palette(tools, toolIndex,
						concat(unavailable, contributed.unavailable()));
			}
			AVector<ACell> mergedTools = tools;
			AMap<AString, ACell> mergedIndex = toolIndex;
			for (long i = 0; i < contributed.tools().count(); i++) {
				ACell definition = contributed.tools().get(i);
				AString name = RT.ensureString(RT.getIn(definition, K_NAME));
				if (name != null && mergedIndex.containsKey(name)) continue;
				mergedTools = mergedTools.conj(definition);
				if (name != null) {
					ACell info = contributed.toolIndex().get(name);
					mergedIndex = mergedIndex.assoc(name, (info != null) ? info : Maps.empty());
				}
			}
			return new Palette(mergedTools, mergedIndex,
				concat(unavailable, contributed.unavailable()));
		}

		/** Exact manifest with one lookup entry for every named definition. */
		public Palette forManifest(AVector<ACell> manifest) {
			AMap<AString, ACell> index = Maps.empty();
			for (long i = 0; manifest != null && i < manifest.count(); i++) {
				AString name = RT.ensureString(RT.getIn(manifest.get(i), K_NAME));
				if (name == null || index.containsKey(name)) continue;
				ACell info = toolIndex.get(name);
				index = index.assoc(name, (info != null) ? info : Maps.empty());
			}
			return new Palette(manifest, index, unavailable);
		}

		/** Inspection-only projection; ordinary inference uses direct lookup. */
		public AVector<ACell> provenance() {
			AVector<ACell> out = Vectors.empty();
			for (var e : toolIndex.entrySet()) {
				AString operation = RT.ensureString(RT.getIn(e.getValue(), Fields.OPERATION));
				if (operation == null) continue;
				AString source = RT.ensureString(RT.getIn(e.getValue(), Fields.SOURCE));
				if (source == null) source = SOURCE_CONFIG;
				out = out.conj(entry(e.getKey(), source, operation,
					RT.ensureString(RT.getIn(e.getValue(), Fields.REF))));
			}
			return out;
		}
	}

	/** Active load tools plus the subset whose immutable owner is the rendered prefix. */
	record LoadPalette(Palette active, AMap<AString, ACell> pinnedIndex) {
		static final LoadPalette EMPTY = new LoadPalette(null, null);

		LoadPalette {
			active = (active != null) ? active : Palette.EMPTY;
			pinnedIndex = (pinnedIndex != null) ? pinnedIndex : Maps.empty();
		}
	}

	/**
	 * Resolves the configured palette: the default pack when opted in, then
	 * {@code config.tools}, deduplicated by name. Resolution runs under the
	 * agent's capability-narrowed context ({@code config.caps}); a configured
	 * tool that cannot be resolved is reported in {@code unavailable} rather
	 * than failing the cycle.
	 *
	 * @param skipNames bare names the runtime resolves itself (its harness
	 *        tools) — silently skipped when they appear in {@code config.tools}
	 */
	public static Palette resolve(Engine engine, RequestContext ctx,
			AMap<AString, ACell> config, Set<String> skipNames) {
		AVector<ACell> caps = RT.ensureVector(config != null ? config.get(K_CAPS) : null);
		RequestContext resolutionCtx = (caps != null) ? ctx.withCaps(caps) : ctx;
		Set<String> skipped = (skipNames != null) ? skipNames : Set.of();
		Palette palette = Palette.EMPTY;
		if (config != null && CVMBool.TRUE.equals(config.get(K_DEFAULT_TOOLS))) {
			palette = build(engine, ctx, DEFAULT_TOOL_OPS,
				skipped::contains, null, SOURCE_DEFAULT, null);
		}

		List<AMap<AString, ACell>> unavailable = new java.util.ArrayList<>();
		AVector<ACell> configured = RT.ensureVector(config != null ? config.get(K_TOOLS) : null);
		if (configured != null) {
			Palette existing = palette;
			Palette additions = build(engine, resolutionCtx, configured,
				name -> skipped.contains(name) || existing.contains(name),
				unavailable, SOURCE_CONFIG, null);
			palette = palette.merge(additions);
		}
		return new Palette(palette.tools(), palette.toolIndex(), vector(unavailable));
	}

	/**
	 * Resolves operation refs once into the durable binding shape carried by a
	 * load: {@code [{operation, definition, activityLabel}]}. The provider
	 * projection uses only {@code definition}; dispatch and UI events use the
	 * co-located operation and label.
	 */
	static AVector<ACell> bindingsForOperations(Engine engine, RequestContext ctx,
			AVector<ACell> operations) {
		Palette palette = build(engine, ctx, operations, name -> false,
			null, SOURCE_CONFIG, null);
		AVector<ACell> bindings = Vectors.empty();
		for (long i = 0; i < palette.tools().count(); i++) {
			ACell definition = palette.tools().get(i);
			AString name = RT.ensureString(RT.getIn(definition, K_NAME));
			AString operation = (name != null) ? palette.operation(name.toString()) : null;
			if (operation != null) {
				AMap<AString, ACell> value = Maps.of(
					Fields.OPERATION, operation,
					Fields.DEFINITION, definition);
				AString label = palette.activityLabel(name.toString());
				if (label != null && !label.equals(name)) {
					value = value.assoc(Fields.ACTIVITY_LABEL, label);
				}
				bindings = bindings.conj(value);
			}
		}
		return bindings;
	}

	/** Materialises a load's tool declarations once. */
	static AMap<AString, ACell> materialiseLoadToolBindings(Engine engine,
			RequestContext ctx, AMap<AString, ACell> spec) {
		if (spec.containsKey(Loads.K_TOOL_BINDINGS)) return spec;
		AVector<ACell> operations = RT.ensureVector(spec.get(K_TOOLS));
		if (operations == null) return spec;
		return spec.assoc(Loads.K_TOOL_BINDINGS,
			bindingsForOperations(engine, ctx, operations));
	}

	/**
	 * Configured tools that do not resolve under the agent's authority, as
	 * {@code {operation, reason}} — the author-time diagnostic used by
	 * {@code agent:create} warnings and {@code agent:info}.
	 */
	public static AVector<ACell> unavailableConfigTools(Engine engine, RequestContext ctx,
			AMap<AString, ACell> config, Set<String> skipNames) {
		if (config == null || RT.ensureVector(config.get(K_TOOLS)) == null) return Vectors.empty();
		return resolve(engine, ctx, config, skipNames).unavailable();
	}

	/** Resolves load-owned tools without parallel route/label projections. */
	@SuppressWarnings("unchecked")
	static LoadPalette loadPalette(Engine engine, RequestContext ctx,
			AMap<AString, ACell> effectiveLoads, BiPredicate<String, AString> excluded,
			boolean resolvePinned) {
		AVector<ACell> added = Vectors.empty();
		AMap<AString, ACell> index = Maps.empty();
		AMap<AString, ACell> pinned = Maps.empty();
		if (effectiveLoads == null || effectiveLoads.count() == 0) return LoadPalette.EMPTY;
		Set<String> names = new HashSet<>();
		// Load order, so a new load's tools append after existing definitions
		// and the cached tool block is not reshuffled.
		for (var entry : Loads.ordered(effectiveLoads)) {
			ACell spec = entry.getValue();
			if (!(spec instanceof AMap)) continue;
			AMap<AString, ACell> meta = (AMap<AString, ACell>) spec;
			AVector<ACell> bindings = RT.ensureVector(meta.get(Loads.K_TOOL_BINDINGS));
			if (bindings == null) {
				if (!resolvePinned && !Loads.isAgentManaged(meta)) continue;
				AVector<ACell> ops = RT.ensureVector(meta.get(K_TOOLS));
				if (ops == null || ops.count() == 0) continue;
				bindings = bindingsForOperations(engine, ctx, ops);
			}
			for (long i = 0; i < bindings.count(); i++) {
				ACell binding = bindings.get(i);
				ACell def = RT.getIn(binding, Fields.DEFINITION);
				AString n = RT.ensureString(RT.getIn(def, K_NAME));
				if (n == null || (excluded != null && excluded.test(n.toString(), entry.getKey()))
						|| !names.add(n.toString())) continue;
				added = added.conj(def);
				AString route = RT.ensureString(RT.getIn(binding, Fields.OPERATION));
				AString activityLabel = nonBlank(
					RT.ensureString(RT.getIn(binding, Fields.ACTIVITY_LABEL)));
				AMap<AString, ACell> info = toolInfo(route, activityLabel, n,
					Skills.isSkillEntry(meta) ? SOURCE_SKILL : SOURCE_LOAD,
					entry.getKey());
				index = index.assoc(n, info);
				if (!Loads.isAgentManaged(meta)) pinned = pinned.assoc(n, info);
			}
		}
		return new LoadPalette(new Palette(added, index, null), pinned);
	}

	/** Inspection entries for runtime-owned definitions that have no operation route. */
	static AVector<ACell> provenance(AVector<ACell> tools, String source) {
		AVector<ACell> out = Vectors.empty();
		if (tools == null) return out;
		AString src = Strings.create(source);
		for (long i = 0; i < tools.count(); i++) {
			AString name = RT.ensureString(RT.getIn(tools.get(i), K_NAME));
			if (name != null) out = out.conj(entry(name, src, null, null));
		}
		return out;
	}

	/** The names of the given tool definitions. */
	public static Set<String> names(AVector<ACell> tools) {
		Set<String> names = new HashSet<>();
		if (tools == null) return names;
		for (long i = 0; i < tools.count(); i++) {
			AString n = RT.ensureString(RT.getIn(tools.get(i), K_NAME));
			if (n != null) names.add(n.toString());
		}
		return names;
	}

	/**
	 * Resolves tool entries to definitions. {@code unavailable == null} means
	 * "do not report": a remote fetch failure is skipped, any other failure
	 * propagates. Otherwise every failure is recorded and resolution continues.
	 */
	private static Palette build(Engine engine, RequestContext ctx,
			AVector<ACell> entries, Predicate<String> skipName,
			List<AMap<AString, ACell>> unavailable, AString source, AString ref) {
		AVector<ACell> tools = Vectors.empty();
		AMap<AString, ACell> index = Maps.empty();
		for (long i = 0; i < entries.count(); i++) {
			AString[] parsed = parseConfigToolEntry(entries.get(i));
			if (parsed == null) continue;
			AString operation = parsed[0];
			if (skipName.test(operation.toString())) continue;

			Asset asset;
			try {
				requireToolMetadataRead(engine, ctx, operation);
				asset = engine.resolveAsset(operation, ctx);
			} catch (covia.exception.RemoteFetchException e) {
				log.warn("Config tool: skipping '{}' — remote fetch failed: {}", operation, e.getMessage());
				record(unavailable, operation, "remote metadata fetch failed: " + safeMessage(e));
				continue;
			} catch (RuntimeException e) {
				if (unavailable == null) throw e;
				log.warn("Config tool: cannot read operation '{}': {}", operation, e.getMessage());
				record(unavailable, operation, "operation metadata is not readable: " + safeMessage(e));
				continue;
			}
			if (asset == null) {
				log.warn("Config tool: cannot resolve operation '{}'", operation);
				record(unavailable, operation, "operation metadata was not found or is not an operation");
				continue;
			}

			// toolName lives inside the operation block. If absent, sanitise the
			// dispatch string (e.g. "agent:create") rather than the catalog path.
			AString assetToolName = RT.ensureString(RT.getIn(asset.meta(), Fields.OPERATION, Fields.TOOL_NAME));
			AString dispatchAdapter = RT.ensureString(RT.getIn(asset.meta(), Fields.OPERATION, Fields.ADAPTER));
			String toolName = deriveToolName(parsed[1], assetToolName,
				(dispatchAdapter != null) ? dispatchAdapter : operation);
			// Manifest order owns name precedence. Do not let a later operation
			// replace the route behind an earlier definition with the same name.
			AString name = Strings.create(toolName);
			if (skipName.test(toolName) || index.containsKey(name)) continue;

			AString rawDescription = (parsed[2] != null)
				? parsed[2] : RT.ensureString(asset.meta().get(Fields.DESCRIPTION));
			tools = tools.conj(operationToolDefinition(asset.meta(), name, rawDescription));
			index = index.assoc(name, toolInfo(operation,
				activityLabel(asset.meta(), toolName), name, source, ref));
		}
		return new Palette(tools, index, null);
	}

	/** UI-only activity text, deliberately kept outside provider tool definitions. */
	static AString activityLabel(AMap<AString, ACell> metadata, String toolName) {
		AString label = nonBlank(RT.ensureString(
			RT.getIn(metadata, Fields.OPERATION, Fields.ACTIVITY_LABEL)));
		if (label == null) label = nonBlank(RT.ensureString(metadata.get(Fields.NAME)));
		return (label != null) ? label : Strings.create(toolName);
	}

	/** One sparse lookup value. Provider definitions live only in the tool vector. */
	private static AMap<AString, ACell> toolInfo(AString operation,
			AString activityLabel, AString toolName, AString source, AString ref) {
		AMap<AString, ACell> value = Maps.empty();
		if (operation != null) value = value.assoc(Fields.OPERATION, operation);
		if (activityLabel != null && !activityLabel.equals(toolName)) {
			value = value.assoc(Fields.ACTIVITY_LABEL, activityLabel);
		}
		if (source != null && !SOURCE_CONFIG.equals(source)) {
			value = value.assoc(Fields.SOURCE, source);
		}
		if (ref != null) value = value.assoc(Fields.REF, ref);
		return value;
	}

	/** Direct fixed-route lookup. */
	static AString operation(AMap<AString, ACell> toolIndex, String name) {
		return (toolIndex == null || name == null) ? null
			: RT.ensureString(RT.getIn(toolIndex.get(Strings.create(name)), Fields.OPERATION));
	}

	/** Direct UI-label lookup; absent labels use the provider tool name. */
	static AString labelFor(AMap<AString, ACell> toolIndex, String name) {
		if (name == null) return null;
		AString label = (toolIndex != null)
			? nonBlank(RT.ensureString(RT.getIn(
				toolIndex.get(Strings.create(name)), Fields.ACTIVITY_LABEL))) : null;
		return (label != null) ? label : Strings.create(name);
	}

	/** Adds previously unknown names; the earlier manifest owns collisions. */
	static AMap<AString, ACell> mergeIndex(AMap<AString, ACell> fixed,
			AMap<AString, ACell> contributed) {
		AMap<AString, ACell> merged = (fixed != null) ? fixed : Maps.empty();
		if (contributed == null) return merged;
		for (var e : contributed.entrySet()) {
			if (!merged.containsKey(e.getKey())) merged = merged.assoc(e.getKey(), e.getValue());
		}
		return merged;
	}

	/** Retains load ownership metadata while leaving dispatch with active load state. */
	@SuppressWarnings("unchecked")
	static AMap<AString, ACell> loadOwners(AMap<AString, ACell> activeLoadIndex) {
		AMap<AString, ACell> owners = Maps.empty();
		if (activeLoadIndex == null) return owners;
		for (var e : activeLoadIndex.entrySet()) {
			AMap<AString, ACell> info = (e.getValue() instanceof AMap<?, ?> map)
				? (AMap<AString, ACell>) map : Maps.empty();
			owners = owners.assoc(e.getKey(), info.dissoc(Fields.OPERATION));
		}
		return owners;
	}

	/**
	 * Whether an active load may contribute a name to an immutable manifest.
	 * A route already owned by the manifest always wins. A route-free entry is
	 * a reservation for the load whose key is in {@code ref}; it keeps that
	 * load active without allowing another load to reuse the frozen name.
	 */
	static boolean excludesLoadName(AMap<AString, ACell> manifestIndex,
			Set<String> harnessNames, String name, AString loadKey) {
		if (name == null || (harnessNames != null && harnessNames.contains(name))) return true;
		ACell info = (manifestIndex != null) ? manifestIndex.get(Strings.create(name)) : null;
		if (info == null) return false;
		if (RT.getIn(info, Fields.OPERATION) != null) return true;
		AString owner = RT.ensureString(RT.getIn(info, Fields.REF));
		return owner == null || !owner.equals(loadKey);
	}

	private static AString nonBlank(AString value) {
		return value != null && !value.toString().isBlank() ? value : null;
	}

	private static AMap<AString, ACell> entry(AString name, AString source,
			AString operation, AString ref) {
		AMap<AString, ACell> out = Maps.of(K_NAME, name, Fields.SOURCE, source);
		if (operation != null) out = out.assoc(Fields.OPERATION, operation);
		if (ref != null) out = out.assoc(Fields.REF, ref);
		return out;
	}

	private static AVector<ACell> vector(List<? extends ACell> cells) {
		AVector<ACell> out = Vectors.empty();
		for (ACell cell : cells) out = out.conj(cell);
		return out;
	}

	@SuppressWarnings("unchecked")
	private static AVector<ACell> concat(AVector<ACell> first, AVector<ACell> second) {
		return (AVector<ACell>) first.concat(second);
	}

	private static void record(List<AMap<AString, ACell>> unavailable, AString operation, String reason) {
		if (unavailable == null) return;
		for (AMap<AString, ACell> u : unavailable) {
			if (operation.equals(u.get(Fields.OPERATION))) return;
		}
		unavailable.add(Maps.of(Fields.OPERATION, operation, Fields.REASON, Strings.create(reason)));
	}

	/** Private user-scoped operation definitions are data: the agent must be
	 *  able to read their metadata as well as invoke them. Venue catalog entries
	 *  are shared public metadata; remote DID references apply the publishing
	 *  venue's policy during fetch. */
	private static void requireToolMetadataRead(Engine engine, RequestContext ctx, AString operation) {
		String ref = operation.toString();
		String normalised = ref.startsWith("/") ? ref.substring(1) : ref;
		if (normalised.startsWith("v/")) return;
		if (Hash.parse(operation) != null || normalised.startsWith("a/")) {
			engine.requireResourceAccess(ctx, operation, Abilities.ASSET_READ);
			return;
		}
		if (normalised.startsWith("w/") || normalised.startsWith("o/")
				|| normalised.startsWith("g/") || normalised.startsWith("j/")
				|| normalised.startsWith("s/") || normalised.startsWith("h/")
				|| normalised.startsWith("n/") || normalised.startsWith("c/")) {
			engine.requireResourceAccess(ctx, operation, Capability.CRUD_READ);
		}
	}

	private static String safeMessage(Throwable error) {
		String message = error.getMessage();
		return (message == null || message.isBlank()) ? error.getClass().getSimpleName() : message;
	}

	/**
	 * Parses a config tool entry — a string (operation path) or a map with
	 * {@code operation} plus optional {@code name} / {@code description}.
	 *
	 * @return {@code [operation, nameOverride, descOverride]}, or null if invalid
	 */
	@SuppressWarnings("unchecked")
	public static AString[] parseConfigToolEntry(ACell entry) {
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

	/** True when the declarative agent config already names this operation.
	 * This is a source-state check for load authority, not a reconstruction from
	 * provider definitions or rendered context. */
	static boolean declaresOperation(AMap<AString, ACell> config, AString operation) {
		if (config == null || operation == null) return false;
		if (CVMBool.TRUE.equals(config.get(K_DEFAULT_TOOLS))) {
			for (long i = 0; i < DEFAULT_TOOL_OPS.count(); i++) {
				if (operation.equals(DEFAULT_TOOL_OPS.get(i))) return true;
			}
		}
		AVector<ACell> configured = RT.ensureVector(config.get(K_TOOLS));
		for (long i = 0; configured != null && i < configured.count(); i++) {
			AString[] parsed = parseConfigToolEntry(configured.get(i));
			if (parsed != null && operation.equals(parsed[0])) return true;
		}
		return false;
	}

	/** Tool name priority: override → asset toolName → operation with colons/slashes as underscores. */
	public static String deriveToolName(AString nameOverride, AString assetToolName, AString operation) {
		if (nameOverride != null) return nameOverride.toString();
		if (assetToolName != null) return assetToolName.toString();
		return operation.toString().replace(':', '_').replace('/', '_');
	}

	/** An LLM tool definition {@code {name, description?, parameters}}. */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> buildToolDefinition(String toolName, AString description, ACell inputSchema) {
		AMap<AString, ACell> parameters = (inputSchema instanceof AMap)
			? (AMap<AString, ACell>) inputSchema
			: Maps.of(K_TYPE, Strings.create("object"), K_PROPERTIES, Maps.empty());
		AMap<AString, ACell> toolDef = Maps.of(K_NAME, Strings.create(toolName), K_PARAMETERS, parameters);
		if (description != null) toolDef = toolDef.assoc(K_DESCRIPTION, description);
		return toolDef;
	}

	/**
	 * Projects ordinary operation metadata into the provider tool shape. Harness
	 * controls use the same metadata resources as venue operations, but are not
	 * installed in the operation catalog; an optional name/description override
	 * supplies a cycle-local alias without duplicating its JSON Schema.
	 */
	static AMap<AString, ACell> operationToolDefinition(AMap<AString, ACell> metadata,
			AString nameOverride, AString descriptionOverride) {
		if (!(RT.getIn(metadata, Fields.OPERATION) instanceof AMap)) {
			throw new IllegalArgumentException("Tool metadata must contain an operation object");
		}
		AString name = (nameOverride != null) ? nameOverride
			: RT.ensureString(RT.getIn(metadata, Fields.OPERATION, Fields.TOOL_NAME));
		if (name == null || name.toString().isBlank()) {
			throw new IllegalArgumentException("Tool metadata must declare operation.toolName");
		}
		AString description = (descriptionOverride != null) ? descriptionOverride
			: RT.ensureString(metadata.get(Fields.DESCRIPTION));
		return buildToolDefinition(name.toString(), description,
			RT.getIn(metadata, Fields.OPERATION, Fields.INPUT));
	}
}
