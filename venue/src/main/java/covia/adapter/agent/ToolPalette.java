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
	private static final AString K_REQUIRES_SKILL = Strings.intern("requiresSkill");
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

	/**
	 * Resolved tool definitions with their dispatch routes (tool name → operation
	 * ref), configured tools that did not resolve ({@code {operation, reason}}),
	 * and inspection-only provenance in definition order.
	 * {@code routes} is a fresh mutable map per palette: runtimes add routes for
	 * tools adopted mid-run.
	 */
	public record Palette(AVector<ACell> tools, Map<String, AString> routes,
			AVector<ACell> unavailable, AVector<ACell> provenance) {
		public Palette {
			tools = (tools != null) ? tools : Vectors.empty();
			routes = (routes != null) ? routes : new HashMap<>();
			unavailable = (unavailable != null) ? unavailable : Vectors.empty();
			provenance = (provenance != null) ? provenance : Vectors.empty();
		}
	}

	/** Tools contributed by the skills visible in the initial catalog. They are
	 * declared to the provider up front for stable schemas, but their routes do
	 * not become callable until the corresponding skill is loaded. */
	public record DeclaredSkillTools(AVector<ACell> tools,
			Map<String, AString> routes, Map<String, String> skills,
			AVector<ACell> provenance) {
		public static final DeclaredSkillTools EMPTY =
			new DeclaredSkillTools(null, null, null, null);

		public DeclaredSkillTools {
			tools = (tools != null) ? tools : Vectors.empty();
			routes = (routes != null) ? Map.copyOf(routes) : Map.of();
			skills = (skills != null) ? Map.copyOf(skills) : Map.of();
			provenance = (provenance != null) ? provenance : Vectors.empty();
		}

		public Set<String> names() { return skills.keySet(); }
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

		AVector<ACell> tools = Vectors.empty();
		AVector<ACell> entries = Vectors.empty();
		if (config != null && CVMBool.TRUE.equals(config.get(K_DEFAULT_TOOLS))) {
			Palette defaults = DEFAULT_TOOL_CACHE.computeIfAbsent(engine, e -> {
				Map<String, AString> freshRoutes = new HashMap<>();
				List<AMap<AString, ACell>> provenance = new java.util.ArrayList<>();
				AVector<ACell> defs = build(e, ctx, DEFAULT_TOOL_OPS, Set.of(), freshRoutes, null,
					provenance, SOURCE_DEFAULT, null);
				return new Palette(defs, Map.copyOf(freshRoutes), Vectors.empty(), vector(provenance));
			});
			tools = defaults.tools();
			routes.putAll(defaults.routes());
			entries = defaults.provenance();
		}

		List<AMap<AString, ACell>> unavailable = new java.util.ArrayList<>();
		List<AMap<AString, ACell>> configuredEntries = new java.util.ArrayList<>();
		AVector<ACell> configured = RT.ensureVector(config != null ? config.get(K_TOOLS) : null);
		if (configured != null) {
			Set<String> skip = (skipNames != null) ? skipNames : Set.of();
			tools = merge(tools, build(engine, resolutionCtx, configured, skip, routes, unavailable,
				configuredEntries, SOURCE_CONFIG, null));
		}
		entries = mergeEntries(entries, vector(configuredEntries));
		return new Palette(tools, routes, vector(unavailable), entries);
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
		AVector<ACell> tools = Vectors.empty();
		Map<String, AString> routes = new HashMap<>();
		Map<String, String> owners = new HashMap<>();
		List<AMap<AString, ACell>> entries = new java.util.ArrayList<>();
		for (Skills.SkillIndexEntry listed : Skills.listSkills(engine, catalogCtx, sources)) {
			if (listed.name() == null || listed.error() != null) continue;
			try {
				Skills.ResolvedSkill skill = Skills.resolveRef(engine, catalogCtx, listed.path());
				Map<String, AString> skillRoutes = new HashMap<>();
				AVector<ACell> definitions = forOperations(
					engine, operationCtx, skill.toolOps(), skillRoutes);
				for (long i = 0; i < definitions.count(); i++) {
					@SuppressWarnings("unchecked")
					AMap<AString, ACell> definition = (AMap<AString, ACell>) definitions.get(i);
					AString name = RT.ensureString(definition.get(K_NAME));
					if (name == null || !names.add(name.toString())) continue;
					AString description = RT.ensureString(definition.get(K_DESCRIPTION));
					definition = definition.assoc(K_DESCRIPTION, Strings.create(
						"Available after loading skill '" + skill.name()
						+ "' from [Skills].\n\n"
						+ (description != null ? description.toString() : "")))
						.assoc(K_REQUIRES_SKILL, Strings.create(skill.name()));
					tools = tools.conj(definition);
					AString route = skillRoutes.get(name.toString());
					if (route != null) routes.put(name.toString(), route);
					owners.put(name.toString(), skill.name());
					entries.add(entry(name, SOURCE_SKILL, route, listed.path()));
				}
			} catch (RuntimeException e) {
				log.warn("Skill tool declaration: skipping '{}': {}",
					listed.path(), safeMessage(e));
			}
		}
		return new DeclaredSkillTools(tools, routes, owners, vector(entries));
	}

	/**
	 * Recovers the load gates embedded in an already-persisted provider
	 * manifest. Unknown definition fields are ignored by provider adapters, so
	 * this annotation is cache-stable Covia metadata rather than a second copy
	 * of the tool schemas.
	 */
	@SuppressWarnings("unchecked")
	public static DeclaredSkillTools declaredSkillTools(AVector<ACell> manifest) {
		if (manifest == null || manifest.isEmpty()) return DeclaredSkillTools.EMPTY;
		AVector<ACell> tools = Vectors.empty();
		Map<String, String> owners = new HashMap<>();
		for (long i = 0; i < manifest.count(); i++) {
			ACell definition = manifest.get(i);
			AString name = RT.ensureString(RT.getIn(definition, K_NAME));
			AString skill = RT.ensureString(RT.getIn(definition, K_REQUIRES_SKILL));
			if (name == null || skill == null) continue;
			tools = tools.conj(definition);
			owners.put(name.toString(), skill.toString());
		}
		return new DeclaredSkillTools(tools, Map.of(), owners, Vectors.empty());
	}

	/**
	 * Tool definitions for explicit operation refs — the shape used by
	 * {@code more_tools}, skill tool declarations and loads. Routes gain the
	 * dispatch of every returned definition. An unreachable remote definition
	 * is skipped; any other resolution failure propagates.
	 */
	public static AVector<ACell> forOperations(Engine engine, RequestContext ctx,
			AVector<ACell> operations, Map<String, AString> routes) {
		return build(engine, ctx, operations, Set.of(), routes, null, null, null, null);
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
		return loadsToolDefs(engine, ctx, effectiveLoads, excludeNames, routes, null);
	}

	/** Same resolution as {@link #loadsToolDefs}, retaining provenance for inspection. */
	@SuppressWarnings("unchecked")
	static AVector<ACell> loadsToolDefs(Engine engine, RequestContext ctx,
			AMap<AString, ACell> effectiveLoads, Set<String> excludeNames,
			Map<String, AString> routes, List<AMap<AString, ACell>> provenance) {
		AVector<ACell> added = Vectors.empty();
		if (effectiveLoads == null || effectiveLoads.count() == 0) return added;
		Set<String> names = new HashSet<>();
		if (excludeNames != null) names.addAll(excludeNames);
		// Load order, so a new load's tools append after existing definitions
		// and the cached tool block is not reshuffled.
		for (var entry : Loads.ordered(effectiveLoads)) {
			ACell spec = entry.getValue();
			if (!(spec instanceof AMap)) continue;
			AVector<ACell> ops = RT.ensureVector(((AMap<AString, ACell>) spec).get(K_TOOLS));
			if (ops == null || ops.count() == 0) continue;
			Map<String, AString> newRoutes = new HashMap<>();
			AVector<ACell> defs = forOperations(engine, ctx, ops, newRoutes);
			for (long i = 0; i < defs.count(); i++) {
				ACell def = defs.get(i);
				AString n = RT.ensureString(RT.getIn(def, K_NAME));
				if (n == null || !names.add(n.toString())) continue;
				added = added.conj(def);
				AString route = newRoutes.get(n.toString());
				if (route != null) routes.put(n.toString(), route);
				if (provenance != null) provenance.add(entry(n,
					Skills.isSkillEntry((AMap<AString, ACell>) spec) ? SOURCE_SKILL : SOURCE_LOAD,
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

			// The catalog path is co-located with the tool name so the model can
			// reason about provenance, discover siblings and pin by the path it sees.
			AString rawDescription = (parsed[2] != null)
				? parsed[2] : RT.ensureString(asset.meta().get(Fields.DESCRIPTION));
			AString description = Strings.create("Operation: " + operation + "\n\n"
				+ (rawDescription != null ? rawDescription.toString() : ""));
			ACell inputSchema = RT.getIn(asset.meta(), Fields.OPERATION, Fields.INPUT);

			result = result.conj(buildToolDefinition(toolName, description, inputSchema));
			routes.put(toolName, operation);
			if (provenance != null) provenance.add(entry(Strings.create(toolName), source, operation, ref));
		}
		return result;
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
}
