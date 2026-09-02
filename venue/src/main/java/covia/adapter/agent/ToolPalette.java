package covia.adapter.agent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * {@link ContextAssembler}; only genuinely late load contributions resolve
 * after that boundary.</p>
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
	 * Per-engine cache of the resolved default pack. Every default op lives
	 * under venue-scoped {@code v/ops/}, so the result is the same for every
	 * caller; adapters install their assets at startup and never change them,
	 * so it is never invalidated.
	 */
	private static final java.util.concurrent.ConcurrentHashMap<Engine, Palette>
		DEFAULT_TOOL_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	/** A resolved fixed palette plus configured operations that did not resolve. */
	public record Palette(Bindings bindings, AVector<ACell> unavailable) {
		public Palette {
			bindings = (bindings != null) ? bindings : Bindings.EMPTY;
			unavailable = (unavailable != null) ? unavailable : Vectors.empty();
		}

		public AVector<ACell> tools() { return bindings.tools(); }
		public Map<String, AString> routes() { return bindings.routes(); }
		public Map<String, AString> activityLabels() { return bindings.activityLabels(); }
		public AVector<ACell> provenance() { return bindings.provenance(); }
	}

	/**
	 * Immutable tool bindings in provider-manifest order. Every entry carries
	 * its exact {@code definition}; operation-backed entries also carry their
	 * route. Optional metadata is sparse: {@code activityLabel} defaults to the
	 * tool name and {@code source} defaults to {@code config} when an operation
	 * is present. A definition-only entry is runtime-owned or is backed by its
	 * owning load.
	 */
	public record Bindings(AVector<ACell> cells) {
		public static final Bindings EMPTY = new Bindings(null);

		public Bindings {
			cells = (cells != null) ? cells : Vectors.empty();
		}

		static Bindings of(AVector<ACell> tools, Map<String, AString> activityLabels,
				AVector<ACell> provenance) {
			if (tools == null || tools.isEmpty()) return EMPTY;
			Map<String, ACell> origins = new HashMap<>();
			for (long i = 0; provenance != null && i < provenance.count(); i++) {
				ACell item = provenance.get(i);
				AString name = RT.ensureString(RT.getIn(item, K_NAME));
				if (name != null) origins.putIfAbsent(name.toString(), item);
			}
			AVector<ACell> bindings = Vectors.empty();
			for (long i = 0; i < tools.count(); i++) {
				ACell definition = tools.get(i);
				AString name = RT.ensureString(RT.getIn(definition, K_NAME));
				ACell origin = (name != null) ? origins.get(name.toString()) : null;
				bindings = bindings.conj(binding(definition,
					RT.ensureString(RT.getIn(origin, Fields.OPERATION)),
					(name != null && activityLabels != null)
						? activityLabels.get(name.toString()) : null,
					name, RT.ensureString(RT.getIn(origin, Fields.SOURCE)),
					RT.ensureString(RT.getIn(origin, Fields.REF))));
			}
			return new Bindings(bindings);
		}

		/** Definition-only bindings for runtime-owned tools. */
		public static Bindings definitions(AVector<ACell> definitions) {
			AVector<ACell> cells = Vectors.empty();
			for (long i = 0; definitions != null && i < definitions.count(); i++) {
				cells = cells.conj(Maps.of(Fields.DEFINITION, definitions.get(i)));
			}
			return cells.isEmpty() ? EMPTY : new Bindings(cells);
		}

		public Bindings merge(Bindings contributed) {
			if (contributed == null || contributed.cells().isEmpty()) return this;
			Set<String> names = new HashSet<>(names());
			AVector<ACell> merged = cells;
			for (long i = 0; i < contributed.cells().count(); i++) {
				ACell item = contributed.cells().get(i);
				AString name = name(item);
				if (name == null || names.add(name.toString())) merged = merged.conj(item);
			}
			return merged == cells ? this : new Bindings(merged);
		}

		/** Reorders bindings to an exact manifest, retaining known metadata. */
		public Bindings forManifest(AVector<ACell> tools) {
			Map<String, ACell> known = new HashMap<>();
			for (long i = 0; i < cells.count(); i++) {
				AString name = name(cells.get(i));
				if (name != null) known.putIfAbsent(name.toString(), cells.get(i));
			}
			AVector<ACell> ordered = Vectors.empty();
			for (long i = 0; tools != null && i < tools.count(); i++) {
				ACell definition = tools.get(i);
				AString name = RT.ensureString(RT.getIn(definition, K_NAME));
				AMap<AString, ACell> value = (name != null && known.get(name.toString()) instanceof AMap<?, ?> map)
					? castMap(map) : Maps.empty();
				ordered = ordered.conj(value.assoc(Fields.DEFINITION, definition));
			}
			return ordered.isEmpty() ? EMPTY : new Bindings(ordered);
		}

		/** Only bindings whose dispatch route is fixed by the rendered context. */
		public Bindings operations() {
			AVector<ACell> out = Vectors.empty();
			for (long i = 0; i < cells.count(); i++) {
				ACell value = cells.get(i);
				if (RT.getIn(value, Fields.OPERATION) != null) out = out.conj(value);
			}
			return out.isEmpty() ? EMPTY : new Bindings(out);
		}

		public AVector<ACell> tools() {
			AVector<ACell> tools = Vectors.empty();
			for (long i = 0; i < cells.count(); i++) {
				ACell definition = RT.getIn(cells.get(i), Fields.DEFINITION);
				if (definition != null) tools = tools.conj(definition);
			}
			return tools;
		}

		public Set<String> names() {
			Set<String> names = new HashSet<>();
			for (long i = 0; i < cells.count(); i++) {
				AString name = name(cells.get(i));
				if (name != null) names.add(name.toString());
			}
			return Set.copyOf(names);
		}

		public Map<String, AString> routes() {
			Map<String, AString> routes = new HashMap<>();
			for (long i = 0; i < cells.count(); i++) {
				ACell item = cells.get(i);
				AString name = name(item);
				AString operation = RT.ensureString(RT.getIn(item, Fields.OPERATION));
				if (name != null && operation != null) routes.put(name.toString(), operation);
			}
			return Map.copyOf(routes);
		}

		public Map<String, AString> activityLabels() {
			Map<String, AString> labels = new HashMap<>();
			for (long i = 0; i < cells.count(); i++) {
				ACell item = cells.get(i);
				AString name = name(item);
				if (name == null || RT.getIn(item, Fields.OPERATION) == null) continue;
				AString label = nonBlank(RT.ensureString(
					RT.getIn(item, Fields.ACTIVITY_LABEL)));
				labels.put(name.toString(), (label != null) ? label : name);
			}
			return Map.copyOf(labels);
		}

		/** Inspection projection in provider-manifest order. */
		public AVector<ACell> provenance() {
			AVector<ACell> out = Vectors.empty();
			for (long i = 0; i < cells.count(); i++) {
				ACell value = cells.get(i);
				AString name = name(value);
				AString operation = RT.ensureString(RT.getIn(value, Fields.OPERATION));
				if (name == null || operation == null) continue;
				AString source = RT.ensureString(RT.getIn(value, Fields.SOURCE));
				if (source == null) source = SOURCE_CONFIG;
				out = out.conj(entry(name, source, operation,
					RT.ensureString(RT.getIn(value, Fields.REF))));
			}
			return out;
		}

		private static AString name(ACell binding) {
			return RT.ensureString(RT.getIn(binding, Fields.DEFINITION, K_NAME));
		}

		@SuppressWarnings("unchecked")
		private static AMap<AString, ACell> castMap(AMap<?, ?> map) {
			return (AMap<AString, ACell>) map;
		}
	}

	/** Tools contributed by the skills visible in the initial catalog. Their
	 * schemas and routes join the fixed context together: loading a skill adds
	 * instructions, not authority to invoke an already offered tool. */
	public record DeclaredSkillTools(Bindings bindings) {
		public static final DeclaredSkillTools EMPTY =
			new DeclaredSkillTools(null);

		public DeclaredSkillTools {
			bindings = (bindings != null) ? bindings : Bindings.EMPTY;
		}

		public AVector<ACell> tools() { return bindings.tools(); }
		public Set<String> names() { return bindings.names(); }
		public AVector<ACell> provenance() { return bindings.provenance(); }
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
		Map<String, AString> routes = new HashMap<>();
		Map<String, AString> activityLabels = new HashMap<>();

		AVector<ACell> tools = Vectors.empty();
		AVector<ACell> entries = Vectors.empty();
		Palette defaults = null;
		if (config != null && CVMBool.TRUE.equals(config.get(K_DEFAULT_TOOLS))) {
			defaults = DEFAULT_TOOL_CACHE.computeIfAbsent(engine, e -> {
				Map<String, AString> freshRoutes = new HashMap<>();
				Map<String, AString> freshLabels = new HashMap<>();
				List<AMap<AString, ACell>> provenance = new java.util.ArrayList<>();
				AVector<ACell> defs = build(e, ctx, DEFAULT_TOOL_OPS, Set.of(), freshRoutes, freshLabels, null,
					provenance, SOURCE_DEFAULT, null);
				return new Palette(Bindings.of(
					defs, freshLabels, vector(provenance)), Vectors.empty());
			});
			tools = defaults.tools();
			routes.putAll(defaults.routes());
			activityLabels.putAll(defaults.activityLabels());
			entries = defaults.provenance();
		}

		List<AMap<AString, ACell>> unavailable = new java.util.ArrayList<>();
		List<AMap<AString, ACell>> configuredEntries = new java.util.ArrayList<>();
		AVector<ACell> configured = RT.ensureVector(config != null ? config.get(K_TOOLS) : null);
		if ((configured == null || configured.isEmpty()) && defaults != null) return defaults;
		if (configured != null) {
			Set<String> skip = (skipNames != null) ? skipNames : Set.of();
			tools = merge(tools, build(engine, resolutionCtx, configured, skip, routes, activityLabels, unavailable,
				configuredEntries, SOURCE_CONFIG, null));
		}
		entries = mergeEntries(entries, vector(configuredEntries));
		return new Palette(Bindings.of(tools, activityLabels, entries), vector(unavailable));
	}

	/**
	 * Resolves the tools of every skill in the initial discovery surface. The
	 * catalog is read under operator authority, exactly like its rendered index;
	 * operation schemas are resolved under the agent's capability scope. Name
	 * precedence is the provider manifest's precedence: harness/config names in
	 * {@code occupiedNames} win, then the first skill in catalog order wins.
	 */
	public static DeclaredSkillTools declaredSkillTools(Engine engine,
			RequestContext catalogCtx, RequestContext operationCtx,
			Skills.SkillSources sources, Set<String> occupiedNames) {
		if (sources == null || sources.isEmpty()) return DeclaredSkillTools.EMPTY;
		Set<String> names = new HashSet<>();
		if (occupiedNames != null) names.addAll(occupiedNames);
		AVector<ACell> bindings = Vectors.empty();
		for (Skills.SkillIndexEntry listed : Skills.listSkills(engine, catalogCtx, sources)) {
			if (listed.name() == null || listed.error() != null) continue;
			try {
				Skills.ResolvedSkill skill = Skills.resolveRef(engine, catalogCtx, listed.path());
				AVector<ACell> resolved = bindingsForOperations(
					engine, operationCtx, skill.toolOps());
				for (long i = 0; i < resolved.count(); i++) {
					ACell resolvedBinding = resolved.get(i);
					@SuppressWarnings("unchecked")
					AMap<AString, ACell> definition = (AMap<AString, ACell>)
						RT.getIn(resolvedBinding, Fields.DEFINITION);
					AString name = RT.ensureString(definition.get(K_NAME));
					if (name == null || !names.add(name.toString())) continue;
					AString route = RT.ensureString(RT.getIn(resolvedBinding, Fields.OPERATION));
					AString label = RT.ensureString(RT.getIn(resolvedBinding, Fields.ACTIVITY_LABEL));
					bindings = bindings.conj(binding(definition, route, label,
						name, SOURCE_SKILL, listed.path()));
				}
			} catch (RuntimeException e) {
				log.warn("Skill tool declaration: skipping '{}': {}",
					listed.path(), safeMessage(e));
			}
		}
		return new DeclaredSkillTools(new Bindings(bindings));
	}

	/**
	 * Tool definitions for explicit operation refs — the shape used by
	 * {@code more_tools}, skill tool declarations and loads. Routes gain the
	 * dispatch of every returned definition. An unreachable remote definition
	 * is skipped; any other resolution failure propagates.
	 */
	public static AVector<ACell> forOperations(Engine engine, RequestContext ctx,
			AVector<ACell> operations, Map<String, AString> routes) {
		return build(engine, ctx, operations, Set.of(), routes, null, null, null, null, null);
	}

	/**
	 * Resolves operation refs once into the durable binding shape carried by a
	 * load: {@code [{operation, definition, activityLabel}]}. The provider
	 * projection uses only {@code definition}; dispatch and UI events use the
	 * co-located operation and label.
	 */
	static AVector<ACell> bindingsForOperations(Engine engine, RequestContext ctx,
			AVector<ACell> operations) {
		Map<String, AString> routes = new HashMap<>();
		Map<String, AString> activityLabels = new HashMap<>();
		AVector<ACell> definitions = build(engine, ctx, operations, Set.of(), routes,
			activityLabels, null, null, null, null);
		AVector<ACell> bindings = Vectors.empty();
		for (long i = 0; i < definitions.count(); i++) {
			ACell definition = definitions.get(i);
			AString name = RT.ensureString(RT.getIn(definition, K_NAME));
			AString operation = (name != null) ? routes.get(name.toString()) : null;
			if (operation != null) {
				AMap<AString, ACell> value = Maps.of(
					Fields.OPERATION, operation,
					Fields.DEFINITION, definition);
				AString label = activityLabels.get(name.toString());
				if (label != null && !label.equals(name)) {
					value = value.assoc(Fields.ACTIVITY_LABEL, label);
				}
				bindings = bindings.conj(value);
			}
		}
		return bindings;
	}

	/** Materialises a stable load's tool declarations once. */
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

	/**
	 * Tools contributed by loads entries — the generic "a loads entry may
	 * declare {@code tools}" rule (skills are the first producer; the mechanism
	 * is kind-agnostic). Deduplicated against {@code excludeNames} and each
	 * other; {@code routes} gains the dispatch of every returned definition.
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> loadsToolDefs(Engine engine, RequestContext ctx,
			AMap<AString, ACell> effectiveLoads, Set<String> excludeNames,
			Map<String, AString> routes) {
		return loadsToolDefs(engine, ctx, effectiveLoads, excludeNames, routes, null, null);
	}

	/** Same resolution as {@link #loadsToolDefs}, retaining provenance for inspection. */
	@SuppressWarnings("unchecked")
	static AVector<ACell> loadsToolDefs(Engine engine, RequestContext ctx,
			AMap<AString, ACell> effectiveLoads, Set<String> excludeNames,
			Map<String, AString> routes, Map<String, AString> activityLabels,
			List<AMap<AString, ACell>> provenance) {
		AVector<ACell> added = Vectors.empty();
		if (effectiveLoads == null || effectiveLoads.count() == 0) return added;
		Set<String> names = new HashSet<>();
		if (excludeNames != null) names.addAll(excludeNames);
		// Load order, so a new load's tools append after existing definitions
		// and the cached tool block is not reshuffled.
		for (var entry : Loads.ordered(effectiveLoads)) {
			ACell spec = entry.getValue();
			if (!(spec instanceof AMap)) continue;
			AMap<AString, ACell> meta = (AMap<AString, ACell>) spec;
			AVector<ACell> bindings = RT.ensureVector(meta.get(Loads.K_TOOL_BINDINGS));
			if (bindings == null) {
				AVector<ACell> ops = RT.ensureVector(meta.get(K_TOOLS));
				if (ops == null || ops.count() == 0) continue;
				bindings = bindingsForOperations(engine, ctx, ops);
			}
			for (long i = 0; i < bindings.count(); i++) {
				ACell binding = bindings.get(i);
				ACell def = RT.getIn(binding, Fields.DEFINITION);
				AString n = RT.ensureString(RT.getIn(def, K_NAME));
				if (n == null || !names.add(n.toString())) continue;
				added = added.conj(def);
				AString route = RT.ensureString(RT.getIn(binding, Fields.OPERATION));
				if (route != null) routes.put(n.toString(), route);
				AString activityLabel = nonBlank(
					RT.ensureString(RT.getIn(binding, Fields.ACTIVITY_LABEL)));
				if (activityLabels != null) {
					activityLabels.put(n.toString(),
						(activityLabel != null) ? activityLabel : n);
				}
				if (provenance != null) provenance.add(entry(n,
					Skills.isSkillEntry(meta) ? SOURCE_SKILL : SOURCE_LOAD,
					route, entry.getKey()));
			}
		}
		return added;
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

	/** Appends the contributed definitions whose names are not already present.
	 *  Returns {@code fixed} itself when nothing is appended. */
	public static AVector<ACell> merge(AVector<ACell> fixed, AVector<ACell> contributed) {
		if (contributed == null || contributed.count() == 0) return fixed;
		if (fixed == null || fixed.count() == 0) return contributed;
		Set<String> existing = names(fixed);
		AVector<ACell> out = fixed;
		for (long i = 0; i < contributed.count(); i++) {
			ACell def = contributed.get(i);
			AString n = RT.ensureString(RT.getIn(def, K_NAME));
			if (n == null || existing.add(n.toString())) out = out.conj(def);
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
	private static AVector<ACell> build(Engine engine, RequestContext ctx,
			AVector<ACell> entries, Set<String> skipNames, Map<String, AString> routes,
			Map<String, AString> activityLabels,
			List<AMap<AString, ACell>> unavailable, List<AMap<AString, ACell>> provenance,
			AString source, AString ref) {
		AVector<ACell> result = Vectors.empty();
		for (long i = 0; i < entries.count(); i++) {
			AString[] parsed = parseConfigToolEntry(entries.get(i));
			if (parsed == null) continue;
			AString operation = parsed[0];
			if (skipNames.contains(operation.toString())) continue;

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
			if (skipNames.contains(toolName) || routes.containsKey(toolName)) continue;

			AString rawDescription = (parsed[2] != null)
				? parsed[2] : RT.ensureString(asset.meta().get(Fields.DESCRIPTION));
			result = result.conj(operationToolDefinition(asset.meta(),
				Strings.create(toolName), rawDescription));
			routes.put(toolName, operation);
			if (activityLabels != null) {
				activityLabels.put(toolName, activityLabel(asset.meta(), toolName));
			}
			if (provenance != null) provenance.add(entry(Strings.create(toolName), source, operation, ref));
		}
		return result;
	}

	/** UI-only activity text, deliberately kept outside provider tool definitions. */
	static AString activityLabel(AMap<AString, ACell> metadata, String toolName) {
		AString label = nonBlank(RT.ensureString(
			RT.getIn(metadata, Fields.OPERATION, Fields.ACTIVITY_LABEL)));
		if (label == null) label = nonBlank(RT.ensureString(metadata.get(Fields.NAME)));
		return (label != null) ? label : Strings.create(toolName);
	}

	/** One sparse durable binding value. */
	private static AMap<AString, ACell> binding(ACell definition, AString operation,
			AString activityLabel, AString toolName, AString source, AString ref) {
		AMap<AString, ACell> value = Maps.of(Fields.DEFINITION, definition);
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

	private static AVector<ACell> mergeEntries(AVector<ACell> first, AVector<ACell> second) {
		Set<String> names = new HashSet<>();
		AVector<ACell> out = Vectors.empty();
		for (AVector<ACell> entries : List.of(first, second)) {
			for (long i = 0; i < entries.count(); i++) {
				ACell entry = entries.get(i);
				AString name = RT.ensureString(RT.getIn(entry, K_NAME));
				if (name != null && names.add(name.toString())) out = out.conj(entry);
			}
		}
		return out;
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
