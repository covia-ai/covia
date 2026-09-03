package covia.adapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.adapter.agent.Skills;
import covia.api.Fields;
import covia.venue.RequestContext;
import covia.venue.storage.ContentProvider;

/**
 * Discovery and import of agent skills — named bundles of instructions,
 * context, and tools (see {@code venue/docs/SKILLS.md}).
 *
 * <p>Four ops with precise schemas, one question each: {@code skills/list}
 * (the skills in a skillset), {@code skills/read} (one skill in full),
 * {@code skills/parse} (one SKILL.md translated to skill metadata, nothing
 * stored) and {@code skills/import} (one SKILL.md parsed and written to
 * {@code <skillset>/<name>}). A union input could only express which
 * arguments belong to which in prose. All resolution logic lives in
 * {@link covia.adapter.agent.Skills}, shared with the agent runtimes'
 * skills index and {@code skill_load} tool so the surfaces can never drift.</p>
 *
 * <p>There is no skill store: skills are ordinary assets, authored with
 * {@code covia:write} (workspace) and {@code asset:store} (immutable
 * assets). {@code import} is a translator over that same write — it hands
 * the parsed metadata to the lattice write seam, so the namespace rules and
 * the {@code crud/write} pin are exactly {@code covia:write}'s, and it
 * exists so a SKILL.md body reaches the venue without ever passing through a
 * model's context. Reads are pinned as metadata reads — either
 * {@code crud/read} or {@code asset/read} over the resource suffices,
 * whichever way it was addressed — and content references
 * ({@code file://}, {@code dlfs/}) are pinned by their provider. Both read
 * abilities sit inside the anonymous read-only grant scope, so venue skills
 * are publicly discoverable.</p>
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
	private static final AString K_IGNORED  = Strings.intern("ignored");
	private static final AString K_EXISTED  = Strings.intern("existed");

	/** Where {@code import} writes when the caller names no skillset: the
	 *  personal skills directory every standard template indexes first. */
	static final String DEFAULT_IMPORT_SKILLSET = "w/skills";

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
		return "Discover and import agent skills — named bundles of instructions, context, tools, "
			+ "and further skills. Read-only: list the skills in a skillset (a directory of "
			+ "skills, such as w/skills or v/skills/data), read one skill in full by its "
			+ "resolved path or asset ref, and parse a SKILL.md (Anthropic Agent Skills format) "
			+ "into the skill metadata the lattice write and asset store operations accept. "
			+ "Import parses one SKILL.md and writes it to <skillset>/<name> in one step, so the "
			+ "body never passes through a model. Skills are ordinary assets: edit and remove "
			+ "them with the lattice operations.";
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
		"covia", "venue", "discovery", "provenance", "skills", "skill-authoring", "skill-import", "lattice"
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
		{"skill-import",    "building/skill-import"},
		{"lattice",         "data/lattice",         "root/lattice"},
	};

	@Override
	protected void installAssets() {
		// Two ops with precise schemas rather than one command-dispatched union:
		// a skillset listing and a single-skill read are different questions.
		installAsset("skills/list", "/adapters/skills/list.json");
		installAsset("skills/read", "/adapters/skills/read.json");
		installAsset("skills/parse", "/adapters/skills/parse.json");
		installAsset("skills/import", "/adapters/skills/import.json");
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
				case "parse" -> CompletableFuture.completedFuture(handleParse(ctx, input));
				case "import" -> CompletableFuture.completedFuture(handleImport(ctx, input));
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

	// ========== parse — one SKILL.md to skill metadata ==========

	/**
	 * Translates a SKILL.md into the metadata map {@code covia:write} and
	 * {@code asset:store} accept, storing nothing. The text comes from exactly
	 * one of {@code source} (a content reference) or {@code text} (the SKILL.md
	 * itself — for a caller that already has it in hand). {@code content}
	 * chooses how the map carries the body: copied into {@code content.inline},
	 * or bound live through {@code content.ref} to the source.
	 */
	private ACell handleParse(RequestContext ctx, ACell input) {
		AString source = RT.ensureString(RT.getIn(input, Fields.SOURCE));
		AString text = RT.ensureString(RT.getIn(input, Fields.TEXT));
		if ((source == null) == (text == null)) {
			throw new IllegalArgumentException("provide exactly one of 'source' (one content reference to a"
				+ " SKILL.md, e.g. file://<root>/<dir>/SKILL.md, dlfs/<drive>/<path> or a/<hash>)"
				+ " or 'text' (the SKILL.md itself)");
		}
		boolean inline = inlineBody(input);
		if (!inline && source == null) {
			throw new IllegalArgumentException("content 'ref' binds the body to a source —"
				+ " give a 'source', or keep content 'inline' for text");
		}
		String skillText = (source != null) ? readSkillText(ctx, source) : text.toString();
		Skills.ParsedSkill parsed = Skills.parseSkillText(skillText, source, inline);
		AMap<AString, ACell> out = Maps.of(
			Fields.METADATA, parsed.metadata(),
			Fields.NAME, Strings.create(parsed.name()),
			Fields.DESCRIPTION, Strings.create(parsed.description()));
		return withIgnored(out, parsed.ignored());
	}

	// ========== import — one SKILL.md written as a skill ==========

	/**
	 * Parses one SKILL.md and writes the result to {@code <skillset>/<name>}.
	 * The SKILL.md comes from exactly one of {@code source} (one content
	 * reference, never a directory) or {@code text} (the body itself, for a
	 * caller that already has it in hand, such as a UI paste). Importing a
	 * library means naming each SKILL.md, so nothing is walked and nothing lands
	 * unasked. The name is the frontmatter's (the directory key is canonical
	 * for a skillset member, so the two cannot disagree), and the skillset
	 * defaults to the caller's own {@code w/skills}.
	 *
	 * <p>Read and parse complete before the write, so a bad input writes
	 * nothing. The write goes through the lattice write seam, which enforces
	 * the namespace rules and the {@code crud/write} pin; a {@code source} read
	 * is pinned by its provider or as a metadata read. {@code text} carries no
	 * source, so only {@code content: inline} is possible with it.</p>
	 */
	private ACell handleImport(RequestContext ctx, ACell input) {
		AString source = RT.ensureString(RT.getIn(input, Fields.SOURCE));
		AString text = RT.ensureString(RT.getIn(input, Fields.TEXT));
		if ((source == null) == (text == null)) {
			throw new IllegalArgumentException("provide exactly one of 'source' (one content reference to a"
				+ " SKILL.md, e.g. file://<root>/<dir>/SKILL.md, dlfs/<drive>/<path> or a/<hash>)"
				+ " or 'text' (the SKILL.md itself). Import one skill per call: a directory is not a source.");
		}
		ACell rawSkillset = RT.getIn(input, K_SKILLSET);
		AString skillset = RT.ensureString(rawSkillset);
		if (skillset == null && rawSkillset != null) {
			throw new IllegalArgumentException("skillset must be one writable directory path, e.g. 'w/skills'");
		}
		String dir = (skillset == null) ? DEFAULT_IMPORT_SKILLSET : skillset.toString().strip();
		while (dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
		if (dir.isEmpty()) {
			throw new IllegalArgumentException("skillset must be a directory path such as 'w/skills'");
		}
		boolean inline = inlineBody(input);
		if (!inline && source == null) {
			throw new IllegalArgumentException("content 'ref' binds the body to a source —"
				+ " give a 'source', or keep content 'inline' for text");
		}

		String skillText = (source != null) ? readSkillText(ctx, source) : text.toString();
		Skills.ParsedSkill parsed = Skills.parseSkillText(skillText, source, inline);
		AString path = Strings.create(dir + "/" + parsed.name());
		ACell written = write(ctx, path, parsed.metadata());

		AMap<AString, ACell> out = Maps.of(
			Fields.PATH, path,
			Fields.NAME, Strings.create(parsed.name()),
			Fields.DESCRIPTION, Strings.create(parsed.description()),
			Fields.CONTENT, inline ? Fields.INLINE : Fields.REF,
			K_EXISTED, CVMBool.of(RT.bool(RT.getIn(written, K_EXISTED))));
		if (source != null) out = out.assoc(Fields.SOURCE, source);
		return withIgnored(out, parsed.ignored());
	}

	/** The {@code content} option: {@code inline} (default) or {@code ref}. */
	private static boolean inlineBody(ACell input) {
		ACell raw = RT.getIn(input, Fields.CONTENT);
		if (raw == null) return true;
		AString form = RT.ensureString(raw);
		if (Fields.INLINE.equals(form)) return true;
		if (Fields.REF.equals(form)) return false;
		throw new IllegalArgumentException("content must be 'inline' (copy the body) or 'ref' (bind it live to the source)");
	}

	/**
	 * The SKILL.md text behind a content reference. A provider reference
	 * ({@code file://}, {@code dlfs/}) is pinned by its provider; a lattice
	 * path or asset ref is pinned as a metadata read first — so a caller
	 * needs exactly what the skill-read operation would need for the same
	 * address. Whatever resolves is handed to the parser as text: a value
	 * that is not a SKILL.md fails there with the reason.
	 */
	private String readSkillText(RequestContext ctx, AString source) {
		if (!Skills.isContentRef(source.toString())) requireReadCap(ctx, source);
		ABlob blob;
		try {
			ContentProvider.Resolved resolved = engine.resolveContent(source, ctx);
			if (resolved == null || resolved.content() == null) {
				throw new IllegalArgumentException("source has no content: " + source);
			}
			blob = resolved.content().getBlob();
		} catch (IOException e) {
			throw new IllegalArgumentException("cannot read source " + source + ": " + e.getMessage(), e);
		}
		if (blob == null || blob.count() == 0) {
			throw new IllegalArgumentException("source is empty: " + source);
		}
		return new String(blob.getBytes(), StandardCharsets.UTF_8);
	}

	/** Writes through the lattice write seam — {@code covia:write}'s namespace
	 *  rules and capability pin, not a second implementation of them. */
	private ACell write(RequestContext ctx, AString path, ACell value) {
		AAdapter raw = engine.getAdapter("covia");
		if (!(raw instanceof CoviaAdapter covia)) {
			throw new IllegalStateException("covia adapter is unavailable — nothing to write the skill with");
		}
		try {
			return covia.writeResolvedPath(ctx, path, value).get();
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException re) throw re;
			throw new RuntimeException(cause);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("interrupted while writing " + path, e);
		}
	}

	/** Dropped frontmatter keys are reported, never silently lost. */
	private static AMap<AString, ACell> withIgnored(AMap<AString, ACell> out, List<String> ignored) {
		if (ignored.isEmpty()) return out;
		AVector<ACell> keys = Vectors.empty();
		for (String k : ignored) keys = keys.conj(Strings.create(k));
		return out.assoc(K_IGNORED, keys);
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
