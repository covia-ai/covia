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
 * <p>Two ops with precise schemas: {@code v/ops/skills/list} (the skills in a
 * skillset) and {@code v/ops/skills/read} (one skill in full). They answer
 * different questions and a union input could only express which arguments
 * belong to which in prose. All resolution logic lives in
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

	private static final AString K_SKILL     = Strings.intern("skill");
	private static final AString K_SKILLSET  = Strings.intern("skillset");
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
		return "Discover agent skills — named bundles of instructions, context, tools, and further "
			+ "skills. Two read-only operations: list the skills in a skillset (a directory of "
			+ "skills, such as w/skills or v/skills/data), and read one skill in full by its "
			+ "resolved path or asset ref. Listing returns each skill's path alongside its name "
			+ "and description, so a caller knows where to read it from. Skills are authored with "
			+ "the ordinary lattice write and asset store operations — there is no write surface "
			+ "here.";
	}

	/**
	 * The platform skills — the ones about Covia and the venue as a whole rather
	 * than any one adapter: orientation ({@code covia}, {@code venue}), how to
	 * find things ({@code discovery}, {@code provenance}), the lattice reference
	 * ({@code lattice} — namespace literacy, a skill rather than a line in every
	 * head) and the skills system itself. Materialised under {@code v/skills/<skillset>/} on boot (see
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
		"covia", "venue", "discovery", "provenance", "skills", "skill-authoring", "lattice"
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
		{"lattice",         "data/lattice",         "root/lattice"},
	};

	@Override
	protected void installAssets() {
		// Two ops with precise schemas rather than one command-dispatched union:
		// a skillset listing and a single-skill read are different questions.
		installAsset("skills/list", "/adapters/skills/list.json");
		installAsset("skills/read", "/adapters/skills/read.json");
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
		// The per-source capability pins do the gating (an anonymous caller's
		// read-only grant scope covers own-namespace reads + asset/read).
		try {
			return switch (getSubOperation(meta)) {
				case "list" -> CompletableFuture.completedFuture(handleList(ctx, input));
				case "read" -> CompletableFuture.completedFuture(handleRead(ctx, input));
				default -> CompletableFuture.failedFuture(new IllegalArgumentException(
					"Unknown skills operation: " + getSubOperation(meta)));
			};
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	// ========== list — the skills in a skillset ==========

	/**
	 * Lists one skillset, or this venue's configured entry skillsets when none
	 * is named, as a map from each skill's resolved <b>path</b> to its index
	 * information. Pairing the two is the point: a name alone does not say
	 * where a skill lives, and where it lives is what you need to read it, to
	 * see which skillset won a name collision, or to fix a source.
	 *
	 * <p>Only actual skills appear. A skillset may sit beside nested
	 * directories or unrelated data, and those are omitted rather than
	 * reported — a listing answers "what can I load here".</p>
	 *
	 * <p>Listing is a survey and <b>degrades</b>: a skillset that does not
	 * resolve, or that the caller may not read, contributes nothing instead of
	 * failing the call. The read is still pinned per skillset, so nothing from
	 * a denied one appears.</p>
	 */
	private ACell handleList(RequestContext ctx, ACell input) {
		AString named = RT.ensureString(RT.getIn(input, K_SKILLSET));
		if (named == null && RT.getIn(input, K_SKILLSET) != null) {
			throw new IllegalArgumentException("skillset must be a single path string,"
				+ " e.g. 'v/skills/data' — list one skillset per call");
		}
		AVector<ACell> skillsets = (named != null)
			? Vectors.of(named)
			: defaultRefs(K_DEFAULT_SKILLSETS, DEFAULT_SKILLSETS);

		AMap<ACell, ACell> out = Maps.empty();
		for (long i = 0; i < skillsets.count(); i++) {
			AString set = RT.ensureString(skillsets.get(i));
			if (set == null) continue;
			for (Skills.SkillIndexEntry e : Skills.listSkills(engine, ctx,
					Skills.SkillSources.ofSkillsets(Vectors.of(set)))) {
				// Source failures and unreadable skills carry no name; a survey
				// reports what IS there.
				if (e.name() == null || e.error() != null) continue;
				AMap<AString, ACell> info = Maps.of(
					Fields.NAME, Strings.create(e.name()),
					Fields.DESCRIPTION, Strings.create(e.description()));
				if (e.id() != null) {
					info = info.assoc(Fields.ID, Strings.create(e.id().toHexString()));
				}
				// First skillset wins a name collision, matching resolution order.
				if (out.get(e.path()) == null) out = out.assoc(e.path(), info);
			}
		}
		return out;
	}

	// ========== read — one skill in full ==========

	/**
	 * Reads one skill by resolved path or asset ref. Deliberately NOT by name:
	 * a name is only meaningful against a declared set of skillsets, resolved
	 * first-wins at the moment of the call — there is no index to look one up
	 * in. Callers list a skillset to obtain the path, and an agent's own
	 * by-name lookup is {@code skill_load}, which has its sources in scope.
	 *
	 * <p>Unlike list, a read is a specific request: an unreadable or absent
	 * skill is an error, not an omission.</p>
	 */
	private ACell handleRead(RequestContext ctx, ACell input) {
		AString ref = RT.ensureString(RT.getIn(input, K_SKILL));
		if (ref == null) {
			throw new IllegalArgumentException("skill must be a single path or asset ref,"
				+ " e.g. 'v/skills/data/workspace' or 'a/<hash>' — read one skill per call"
				+ " (list a skillset to find its path)");
		}
		requireReadCap(ctx, ref);
		Skills.ResolvedSkill skill = Skills.resolveRef(engine, ctx, ref);

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
