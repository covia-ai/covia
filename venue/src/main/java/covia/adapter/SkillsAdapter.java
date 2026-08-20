package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.agent.Skills;
import covia.api.Fields;
import covia.venue.RequestContext;

/**
 * Read-only discovery of agent skills — named bundles of instructions,
 * context, and tools (see {@code venue/docs/SKILLS.md}).
 *
 * <p>A single op/tool ({@code v/ops/skills}) dispatched by the {@code command}
 * input, following the memory adapter idiom — one tool definition is far less
 * agent tool-context than two. All resolution logic lives in
 * {@link covia.adapter.agent.Skills}, shared with the agent runtimes'
 * skills index and {@code skill_load} tool so the surfaces can never drift.</p>
 *
 * <p>There is deliberately <b>no write surface</b>: skills are authored with
 * the existing {@code covia:write} (workspace) and {@code asset:store}
 * (immutable assets). A skill is an asset, so reads are pinned as metadata
 * reads — either {@code crud/read} or {@code asset/read} over the resource
 * suffices, whichever way it was addressed. Both sit inside the anonymous
 * read-only grant scope, so venue skills are publicly discoverable.</p>
 */
public class SkillsAdapter extends AAdapter {

	/**
	 * Default skillsets when the caller names none: the user's own skills
	 * first (so they shadow venue skills of the same name), then the venue's
	 * entry skillset. Overridable per venue with
	 * {@code adapters.skills.defaultSkillsets}.
	 */
	static final AVector<ACell> DEFAULT_SKILLSETS = Vectors.of(
		Strings.intern("w/skills"), Strings.intern("v/skills/root"));

	/** Default individually-named skills: none — the entry skillset is enough. */
	static final AVector<ACell> DEFAULT_SKILLS = Vectors.empty();

	/** Operator-configurable defaults for the two kinds ({@code docs/CONFIG.md}). */
	static final AString K_DEFAULT_SKILLSETS = Strings.intern("defaultSkillsets");
	static final AString K_DEFAULT_SKILLS    = Strings.intern("defaultSkills");

	private static final AString K_SOURCES  = Strings.intern("sources");
	private static final AString K_REF      = Strings.intern("ref");
	private static final AString K_BODY     = Strings.intern("body");
	private static final AString K_CONTEXT  = Strings.intern("context");
	private static final AString K_SKILLS   = Strings.intern("skills");
	private static final AString K_SKILLSETS = Strings.intern("skillsets");

	@Override
	public String getName() {
		return "skills";
	}

	/**
	 * Validates the operator-configured defaults up front. Both settings are
	 * read lazily at each call, but a malformed value would otherwise surface
	 * as an opaque failure on every {@code list}/{@code read} rather than as
	 * an actionable message at the moment it is set.
	 */
	@Override
	public boolean configure(AMap<AString, ACell> config, boolean strict) {
		if (config == null) return true;
		validateRefs(config.get(K_DEFAULT_SKILLSETS), K_DEFAULT_SKILLSETS);
		validateRefs(config.get(K_DEFAULT_SKILLS), K_DEFAULT_SKILLS);
		return true;
	}

	private static void validateRefs(ACell raw, AString key) {
		if (raw == null) return;
		AVector<ACell> refs = RT.ensureVector(raw);
		if (refs == null) {
			throw new IllegalArgumentException("adapters.skills." + key
				+ " must be an array of refs");
		}
		for (long i = 0; i < refs.count(); i++) {
			if (RT.ensureString(refs.get(i)) == null) {
				throw new IllegalArgumentException("adapters.skills." + key
					+ " entries must be ref strings, got: " + refs.get(i));
			}
		}
	}

	/**
	 * The configured defaults are public: they are lattice paths, not secrets,
	 * and a client benefits from seeing what this venue actually reads.
	 */
	@Override
	public AMap<AString, ACell> publicConfig() {
		return publicConfig("defaultSkillsets", "defaultSkills");
	}

	/**
	 * This venue's skill entry point, for {@code v/info/adapters/skills}.
	 * Always the EFFECTIVE defaults, so a client discovers where to start
	 * rather than hardcoding {@code v/skills/root} — which a venue that
	 * curates its own library may not use.
	 */
	@Override
	public AMap<AString, ACell> info() {
		AMap<AString, ACell> out = Maps.of(K_DEFAULT_SKILLSETS, defaultRefs(K_DEFAULT_SKILLSETS, DEFAULT_SKILLSETS));
		AVector<ACell> skills = defaultRefs(K_DEFAULT_SKILLS, DEFAULT_SKILLS);
		if (skills.count() > 0) out = out.assoc(K_DEFAULT_SKILLS, skills);
		return out;
	}

	/** One configured default ref list, falling back to the shipped default. */
	private AVector<ACell> defaultRefs(AString key, AVector<ACell> fallback) {
		if (engine == null) return fallback;
		AVector<ACell> configured = RT.ensureVector(engine.adapterConfig(getName()).get(key));
		return (configured != null) ? configured : fallback;
	}

	@Override
	public String getDescription() {
		return "Discover agent skills — named bundles of instructions, context, tools, and further skills. "
			+ "Pick the action with `command`: 'list' renders the skill index, one '- name — description' "
			+ "line per skill (also usable as a config.context assemble-op); 'read' returns one skill in "
			+ "full (name, description, body, tools, skills, skillsets, context, path). `sources` names "
			+ "skillsets (directories of skills such as w/skills or v/skills/root) and `skills` names "
			+ "individual skills or asset refs; both default to this venue's configured entry point. "
			+ "Read-only — author skills with covia:write or asset:store.";
	}

	/**
	 * The platform skills — the ones about Covia and the venue as a whole rather
	 * than any one adapter: orientation ({@code covia}, {@code venue}), how to
	 * find things ({@code discovery}, {@code provenance}) and the skills system
	 * itself. Materialised under {@code v/skills/<skillset>/} on boot (see
	 * {@link #LIBRARY_PATHS}); bodies ship in {@code content.inline} (one JSON
	 * resource per skill).
	 *
	 * <p>Every other skill belongs to its adapter: each adapter calls
	 * {@link #installSkill} for its own {@code /skills/<name>.json} in
	 * {@code installAssets()}, so the skill is published exactly when the
	 * adapter is active — disable or unload the adapter and its skill goes with
	 * it — and modules ship theirs from their own jars (covia-sql's {@code sql},
	 * covia-telegram's {@code telegram}). Because grouping is decided by the
	 * owning adapter, a skillset only ever lists what is actually active.</p>
	 *
	 * <p>{@code v/skills} holds SKILLSETS, never skills. Agent templates declare
	 * {@code skillsets: ["w/skills", "v/skills/root"]}, so out-of-the-box agents
	 * see a compact entry-point index and reach the rest by loading a root skill
	 * that opens its family. A user's own {@code w/skills/<name>} shadows the
	 * venue skill of the same name.</p>
	 */
	static final String[] LIBRARY = {
		"covia", "venue", "discovery", "provenance", "skills", "skill-authoring"
	};

	/**
	 * Where each platform skill is installed: {@code <skillset>/<name>}, plus a
	 * {@code root/} mirror for the ones that are entry points into a family.
	 * Mirroring installs the SAME resource at both paths, so both addresses
	 * resolve to identical metadata and content-identity dedup treats them as
	 * one skill — never two entries in an agent's context.
	 */
	private static final String[][] LIBRARY_PATHS = {
		{"covia",           "root/covia"},
		{"skills",          "root/skills"},
		{"venue",           "venue/venue",          "root/venue"},
		{"discovery",       "ops-tools/discovery",  "root/discovery"},
		{"provenance",      "ops-tools/provenance"},
		{"skill-authoring", "building/skill-authoring"},
	};

	@Override
	protected void installAssets() {
		// A single op/tool (v/ops/skills) dispatched by the `command` input.
		installAsset("skills", "/adapters/skills/skills.json");
		for (String[] entry : LIBRARY_PATHS) {
			String resource = "/skills/" + entry[0] + ".json";
			for (int i = 1; i < entry.length; i++) {
				installSkill(entry[i], resource);
			}
		}
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		// No authentication precondition: v/skills is publicly discoverable.
		// The per-source capability pins below do the gating (an anonymous
		// caller's read-only grant scope covers own-namespace reads + asset/read).
		String command = strInput(input, "command");
		try {
			return switch (command) {
				case "list" -> CompletableFuture.completedFuture(handleList(ctx, input));
				case "read" -> CompletableFuture.completedFuture(handleRead(ctx, input));
				default -> CompletableFuture.failedFuture(new IllegalArgumentException(
					"skills requires command: list | read"));
			};
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	// ========== list — render the skill index ==========

	/**
	 * Listing is a survey, so it <b>degrades</b>: each source is still pinned
	 * as a read inside the resolver, but one the caller cannot read renders a
	 * visible {@code [skills source X — unavailable: …]} line instead of
	 * failing the whole call. A caller asking what is available should see
	 * what they can see — and, since the default sources are venue-configured,
	 * a default they lack access to must not make every list call fail.
	 */
	private ACell handleList(RequestContext ctx, ACell input) {
		Skills.SkillSources sources = sourcesOf(input);
		String index = Skills.renderIndex(engine, ctx, sources, null, true);
		// Null when no skills exist — the assemble-op contract (entry skipped).
		return (index != null) ? Strings.create(index) : null;
	}

	// ========== read — one skill in full ==========

	private ACell handleRead(RequestContext ctx, ACell input) {
		AString name = RT.ensureString(RT.getIn(input, "name"));
		AString ref = RT.ensureString(RT.getIn(input, K_REF));
		if ((name == null) == (ref == null)) {
			throw new IllegalArgumentException("skills:read requires exactly one of 'name' or 'ref'");
		}

		// Unlike list, read is a specific request for one skill: a source the
		// caller cannot read is an error to report, not a line to omit.
		Skills.ResolvedSkill skill;
		if (ref != null) {
			requireReadCap(ctx, ref);
			skill = Skills.resolveRef(engine, ctx, ref);
		} else {
			Skills.SkillSources sources = sourcesOf(input);
			requireReadCaps(ctx, sources);
			skill = Skills.resolveByName(engine, ctx, sources, name.toString());
		}

		AMap<AString, ACell> out = Maps.of(
			Fields.NAME, Strings.create(skill.name()),
			Fields.DESCRIPTION, Strings.create(skill.description()),
			Fields.TOOLS, skill.toolOps(),
			Fields.PATH, skill.path(),
			Fields.ID, Strings.create(skill.id().toHexString()));
		if (skill.body() != null) {
			// Absent when the skill has no content — a pure toolset.
			out = out.assoc(K_BODY, Strings.create(skill.body()));
		}
		if (skill.contextEntries().count() > 0) {
			out = out.assoc(K_CONTEXT, skill.contextEntries());
		}
		if (skill.skills().count() > 0) {
			out = out.assoc(K_SKILLS, skill.skills());
		}
		if (skill.skillsets().count() > 0) {
			out = out.assoc(K_SKILLSETS, skill.skillsets());
		}
		return out;
	}

	// ========== helpers ==========

	/**
	 * The caller's discovery surface: {@code sources} names skillsets
	 * (directories, the common case), while the optional {@code skills} input
	 * names individual skills. Either falls back to this venue's configured
	 * default, so a venue that curates its own library answers from it without
	 * every caller having to know the path.
	 */
	private Skills.SkillSources sourcesOf(ACell input) {
		return new Skills.SkillSources(
			refVector(input, "skills", defaultRefs(K_DEFAULT_SKILLS, DEFAULT_SKILLS)),
			refVector(input, "sources", defaultRefs(K_DEFAULT_SKILLSETS, DEFAULT_SKILLSETS)));
	}

	private static AVector<ACell> refVector(ACell input, String key, AVector<ACell> fallback) {
		ACell v = RT.getIn(input, key);
		if (v == null) return fallback;
		AVector<ACell> refs = RT.ensureVector(v);
		if (refs == null) throw new IllegalArgumentException(key + " must be an array of refs");
		return refs;
	}

	private void requireReadCaps(RequestContext ctx, Skills.SkillSources sources) {
		requireReadCaps(ctx, sources.skills());
		requireReadCaps(ctx, sources.skillsets());
	}

	private void requireReadCaps(RequestContext ctx, AVector<ACell> sources) {
		for (long i = 0; i < sources.count(); i++) {
			AString source = RT.ensureString(sources.get(i));
			if (source == null) throw new IllegalArgumentException(
				"sources must be strings — got: " + sources.get(i));
			requireReadCap(ctx, source);
		}
	}

	/** Pins the read capability for one source. A skill is an asset, so
	 *  either {@code crud/read} or {@code asset/read} over it is enough
	 *  ({@link covia.venue.Engine#requireMetadataRead}) — the ref's shape
	 *  must not decide which grant a caller needs. */
	private void requireReadCap(RequestContext ctx, AString source) {
		engine.requireMetadataRead(ctx, source);
	}

	private static String strInput(ACell input, String key) {
		AString s = RT.ensureString(RT.getIn(input, key));
		return (s != null) ? s.toString() : "";
	}
}
