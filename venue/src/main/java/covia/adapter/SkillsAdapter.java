package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.agent.Skills;
import covia.api.Abilities;
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
 * (immutable assets). Reads pin {@code crud/read} on path sources and
 * {@code asset/read} on content-addressed refs — both inside the anonymous
 * read-only grant scope, so venue skills are publicly discoverable.</p>
 */
public class SkillsAdapter extends AAdapter {

	/** Default sources when the caller names none. */
	static final AVector<ACell> DEFAULT_SOURCES = Vectors.of(
		Strings.intern("w/skills"), Strings.intern("v/skills/root"));

	private static final AString ASSET_READ = Abilities.ASSET_READ;
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

	@Override
	public String getDescription() {
		return "Discover agent skills — named bundles of instructions, context, tools, and child skill sources. "
			+ "Pick the action with `command`: 'list' renders the skill index, one '- name — description' "
			+ "line per skill (also usable as a config.context assemble-op); 'read' returns one skill in "
			+ "full (name, description, body, tools, context, path). Sources may be workspace/catalog "
			+ "directories, one named skill path, or a content-addressed asset. Read-only — author "
			+ "skills with covia:write or asset:store.";
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

	private ACell handleList(RequestContext ctx, ACell input) {
		Skills.SkillSources sources = sourcesOf(input);
		requireReadCaps(ctx, sources);
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
	 * (directories, the common case — default {@link #DEFAULT_SOURCES}), while
	 * the optional {@code skills} input names individual skills.
	 */
	private static Skills.SkillSources sourcesOf(ACell input) {
		return new Skills.SkillSources(
			refVector(input, "skills", Vectors.empty()),
			refVector(input, "sources", DEFAULT_SOURCES));
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

	/** Pins the read capability for one source: {@code asset/read} for
	 *  content-addressed refs, {@code crud/read} for paths — mirroring
	 *  AssetAdapter and CoviaAdapter's read pins exactly. */
	private void requireReadCap(RequestContext ctx, AString source) {
		if (AssetAdapter.parseAssetId(source) != null) {
			engine.requireResourceAccess(ctx, source, ASSET_READ);
		} else {
			engine.requireResourceAccess(ctx, source, Capability.CRUD_READ);
		}
	}

	private static String strInput(ACell input, String key) {
		AString s = RT.ensureString(RT.getIn(input, key));
		return (s != null) ? s.toString() : "";
	}
}
