package covia.adapter.agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.AssetAdapter;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.RequestContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single resolver for agent skills — named, discoverable bundles of
 * instructions, context, and tools that an agent loads on demand.
 *
 * <p>A skill is an <b>asset</b>: ordinary asset metadata ({@code name},
 * {@code description}, the standard {@code content} descriptor) with the
 * loadable extras under a {@code skill} facet ({@code tools}, {@code skills},
 * {@code skillsets}, {@code context}, {@code budget}) — mirroring how
 * invocability sits under the {@code operation} facet. The body is the asset's content, resolved
 * through the venue's universal content resolution ({@link Engine#resolveContent}),
 * falling back to the {@code description}. A value is interpreted as a skill
 * only through an explicit skill surface: a {@code config.skills} source, an
 * entry in a skills directory, or the target of {@code skill_load}.</p>
 *
 * <p>Discovery has two <b>declared</b> kinds, never sniffed: a
 * <b>skill</b> ref addresses one skill (a path or an {@code a/<hash>} asset
 * ref), and a <b>skillset</b> ref addresses a directory whose keys are skill
 * names. Assets and directories are never mixed at one level, so
 * {@code v/skills} holds skillsets while {@code v/skills/root} holds skills.
 * Skillset members are asset metadata maps or string references to skill
 * assets (the template string-ref idiom, one hop). Inline bodies use the
 * standard {@code content.inline} metadata declaration; SKILL.md YAML
 * frontmatter supplies {@code name}/{@code description} and may declare
 * {@code tools}/{@code skills}/{@code skillsets} when the metadata facet
 * leaves them empty.</p>
 *
 * <p>Used by the {@code skills} venue op, the {@code skill_load} harness tool,
 * and {@link ContextBuilder}'s per-turn index/rendering — one resolver, so the
 * surfaces can never drift. See {@code venue/docs/SKILLS.md} for the design.</p>
 */
public final class Skills {

	private Skills() {}

	private static final Logger log = LoggerFactory.getLogger(Skills.class);

	/** The venue's skill library root — a directory of skillsets, never of skills. */
	public static final String VENUE_SKILLS = "v/skills";

	/** The skill facet key on asset metadata, and the flag key on loads entries. */
	public static final AString K_SKILL = Strings.intern("skill");
	/** Individual child skill refs contributed while a skill is loaded. */
	public static final AString K_SKILLS = Strings.intern("skills");
	/** Child skillset refs — directories of skills — contributed while loaded. */
	public static final AString K_SKILLSETS = Strings.intern("skillsets");

	private static final AString K_CONTEXT = Strings.intern("context");
	private static final AString K_BUDGET  = Strings.intern("budget");
	private static final AString K_LABEL   = Strings.intern("label");
	private static final AString K_TS      = Strings.intern("ts");
	private static final AString K_ROLE    = Strings.intern("role");
	private static final AString K_CONTENT = Strings.intern("content");
	private static final AString ROLE_SYSTEM = Strings.intern("system");

	/**
	 * A fully resolved skill: body materialised, tools and context extracted.
	 *
	 * @param name Canonical skill name (directory key, metadata name, or frontmatter name)
	 * @param description The index line — always present (resolution fails without one)
	 * @param body The instructions text — the asset's content (CAS blob,
	 *        {@code content.inline}, {@code content.dlfs}), frontmatter
	 *        stripped. <b>Null when the skill has no content</b> — a pure
	 *        toolset; renderers fall back to the description one-liner
	 * @param toolOps Operation refs to add to the palette while loaded — the
	 *        {@code skill.tools} entries, plus the skill's own ref when its
	 *        metadata carries an {@code operation} facet
	 * @param contextEntries {@code skill.context} entries (standard entry grammar)
	 * @param skills {@code skill.skills} — individual skill refs made
	 *        discoverable while this skill is loaded
	 * @param skillsets {@code skill.skillsets} — skillset (directory) refs made
	 *        discoverable while this skill is loaded
	 * @param budget Facet-declared default load budget, 0 when unset
	 * @param path Canonical loads key — the address the skill re-resolves from each turn
	 * @param id Content identity — the value hash of the resolved metadata map,
	 *        which Convex already computes and memoises on every cell (an
	 *        asset's ID is exactly this hash), so it costs nothing to carry.
	 *        {@code content.sha256}/{@code content.inline} live inside the
	 *        metadata, so the hash pins the body too. Two addresses resolving
	 *        to the same metadata are the SAME skill; dedup compares these
	 *        identities LIVE (nothing is persisted), accumulated in a
	 *        transient set per pass
	 */
	public record ResolvedSkill(String name, String description, String body,
			AVector<ACell> toolOps, AVector<ACell> contextEntries,
			AVector<ACell> skills, AVector<ACell> skillsets, long budget, AString path,
			convex.core.data.Hash id) {

		/** What a renderer shows for this skill: the body, else the
		 *  description one-liner (a contentless skill still announces itself). */
		public String displayBody() {
			return (body != null) ? body : description;
		}

		/** True when loading this skill widens the discovery surface. */
		public boolean contributesSources() {
			return skills.count() > 0 || skillsets.count() > 0;
		}
	}

	/**
	 * The discovery surface: {@code skills} are refs to individual skills,
	 * {@code skillsets} are refs to directories of skills. The kind is
	 * <b>declared</b>, never sniffed from the resolved value — a skillset is a
	 * directory and a skill is an asset, and the two are not interchangeable
	 * (SKILLS.md §4.1).
	 *
	 * <p>Listing walks {@code skills} before {@code skillsets}, and name
	 * collisions are first-wins, so an explicitly named skill always beats a
	 * same-named member of a skillset.</p>
	 */
	public record SkillSources(AVector<ACell> skills, AVector<ACell> skillsets) {
		public static final SkillSources EMPTY =
			new SkillSources(Vectors.empty(), Vectors.empty());

		/** A surface of skillsets (directories) only — the common case. */
		public static SkillSources ofSkillsets(AVector<ACell> skillsets) {
			return new SkillSources(Vectors.empty(), skillsets);
		}

		/** A surface of individually named skills only. */
		public static SkillSources ofSkills(AVector<ACell> skills) {
			return new SkillSources(skills, Vectors.empty());
		}

		public long count() {
			return skills.count() + skillsets.count();
		}

		public boolean isEmpty() {
			return count() == 0;
		}
	}

	/**
	 * One skills-index row. {@code name == null} marks a source-level failure
	 * (the whole source was unreadable); a non-null {@code error} with a name
	 * marks an invalid individual skill. {@code id} is the content identity
	 * when known (null for error rows).
	 */
	public record SkillIndexEntry(String name, String description, AString path, String error,
			convex.core.data.Hash id) {}

	// ========== Enumeration (the index) ==========

	/**
	 * Enumerates all skills across the given sources, first-wins on name
	 * collisions. Per-skill and per-source failures become error entries
	 * (fail-visible); an absent source is skipped quietly. Throws on a
	 * malformed sources value (non-string entry) — that is a configuration
	 * error, not a resolution failure.
	 */
	public static List<SkillIndexEntry> listSkills(Engine engine, RequestContext ctx,
			SkillSources sources) {
		List<SkillIndexEntry> out = new ArrayList<>();
		if (sources == null) return out;
		Set<String> seen = new HashSet<>();
		// Individual skills first: an explicitly named skill wins a name
		// collision against a same-named member of a skillset.
		for (long i = 0; i < sources.skills().count(); i++) {
			AString source = sourceRef(sources.skills().get(i));
			try {
				requireRead(engine, ctx, source);
				ACell value = engine.resolvePath(source, ctx);
				if (value == null) continue;                          // absent → skip quietly
				addEntry(out, seen, describe(engine, ctx, source, value, source));
			} catch (RuntimeException e) {
				out.add(new SkillIndexEntry(null, null, source, rootMessage(e), null));
			}
		}
		for (long i = 0; i < sources.skillsets().count(); i++) {
			AString source = sourceRef(sources.skillsets().get(i));
			try {
				requireRead(engine, ctx, source);
				ACell value = engine.resolvePath(source, ctx);
				if (value == null) continue;                          // absent → skip quietly
				if (!(value instanceof AMap)) {
					out.add(new SkillIndexEntry(null, null, source,
						"not a skillset (resolves to " + value.getClass().getSimpleName() + ")", null));
					continue;
				}
				@SuppressWarnings("unchecked")
				AMap<ACell, ACell> dir = (AMap<ACell, ACell>) value;
				// Sort keys for a stable, readable index order.
				List<String> keys = new ArrayList<>();
				for (var entry : dir.entrySet()) {
					AString k = RT.ensureString(entry.getKey());
					if (k != null) keys.add(k.toString());
				}
				keys.sort(String::compareTo);
				for (String key : keys) {
					AString path = Strings.create(source + "/" + key);
					ACell member = dir.get(Strings.create(key));
					if (!isSkillValue(member)) {
						// A nested directory (or junk) inside a skillset. Not an
						// invalid skill — skills and directories are separate
						// kinds — so it renders only on the operator surface.
						out.add(new SkillIndexEntry(null, null, path,
							"not a skill — declare a nested directory as a skillset, not a skillset member",
							null));
						continue;
					}
					addEntry(out, seen, describe(engine, ctx, path, member, path));
				}
			} catch (RuntimeException e) {
				out.add(new SkillIndexEntry(null, null, source, rootMessage(e), null));
			}
		}
		return out;
	}

	/** A source ref must be a string; anything else is a configuration error. */
	private static AString sourceRef(ACell raw) {
		AString source = RT.ensureString(raw);
		if (source == null) {
			throw new IllegalArgumentException("skills sources must be strings — got: " + raw);
		}
		return source;
	}

	/**
	 * True when a skillset member is a skill rather than a nested directory.
	 *
	 * <p>Deliberately permissive: a skill needs only a {@code description}
	 * (name falls back to the path segment), and a <i>broken</i> skill must
	 * still reach {@link #describe} so it renders INVALID rather than being
	 * silently skipped. Only a map carrying none of the skill-shaped keys is
	 * treated as a nested directory — the one case that must not be reported
	 * as a broken skill.</p>
	 *
	 * <p>This heuristic applies ONLY to members of an already-declared
	 * skillset, never to a source: kinds are declared, so a directory is only
	 * ever guessed at one level down. It can still be fooled by a directory
	 * whose own key set happens to look skill-shaped (a member literally named
	 * {@code skill}); keep skillset members named after what they do.</p>
	 */
	private static boolean isSkillValue(ACell value) {
		if (value instanceof AString) return true;                   // string ref → one skill
		if (!(value instanceof AMap<?, ?> map)) return false;
		if (map.isEmpty()) return false;
		return map.containsKey(Fields.DESCRIPTION)
			|| map.containsKey(Fields.NAME)
			|| map.containsKey(K_SKILL)
			|| map.containsKey(Fields.CONTENT)
			|| map.containsKey(Fields.OPERATION);
	}

	/** First-wins dedup by name; source-error entries always pass through. */
	private static void addEntry(List<SkillIndexEntry> out, Set<String> seen, SkillIndexEntry entry) {
		if (entry.name() != null && !seen.add(entry.name())) return;
		out.add(entry);
	}

	/** Describes one skill for the index (light: skips the content read when
	 *  metadata already carries name + description). Failures → error entry. */
	private static SkillIndexEntry describe(Engine engine, RequestContext ctx,
			AString path, ACell value, AString opRef) {
		try {
			ResolvedSkill s = resolveValue(engine, ctx, path, opRef, value, false);
			return new SkillIndexEntry(s.name(), s.description(), path, null, s.id());
		} catch (RuntimeException e) {
			return new SkillIndexEntry(lastSegment(path.toString()), null, path, rootMessage(e), null);
		}
	}

	/**
	 * Renders the compact skills index — one {@code - <name> — <description>}
	 * line per skill, {@code (loaded)} marking skills already in effective
	 * context, and INVALID lines for broken skills. Returns null when the
	 * sources yield nothing at all (the assemble-op contract: null → skipped).
	 *
	 * <p>Source-level failures ({@code [skills source X — unavailable: …]})
	 * render only when {@code sourceDiagnostics} is set — the inspection
	 * surface ({@code skills:list}) for whoever configured the sources. Agent
	 * context passes false: an agent cannot act on setup diagnostics, and
	 * sources are maybe-style paths, so their absence or failure is not the
	 * agent's concern.</p>
	 *
	 * @param effectiveLoads Effective loads map for the {@code (loaded)}
	 *        marker; null when no loads tier is in scope (e.g. the venue op)
	 * @param sourceDiagnostics include per-source failure lines (inspection
	 *        surface only)
	 */
	public static String renderIndex(Engine engine, RequestContext ctx,
			SkillSources sources, AMap<AString, ACell> effectiveLoads, boolean sourceDiagnostics) {
		List<SkillIndexEntry> entries = listSkills(engine, ctx, sources);
		if (entries.isEmpty()) return null;
		// Live identities of loaded skills, once per render — the (loaded)
		// marker must not miss a skill loaded via a different address.
		java.util.Set<convex.core.data.Hash> loadedIds =
			loadedSkillIds(engine, ctx, effectiveLoads);
		StringBuilder sb = new StringBuilder();
		for (SkillIndexEntry e : entries) {
			if (e.name() == null) {
				if (!sourceDiagnostics) continue;
				if (sb.length() > 0) sb.append('\n');
				sb.append("[skills source ").append(e.path()).append(" — unavailable: ").append(e.error()).append(']');
			} else if (e.error() != null) {
				if (sb.length() > 0) sb.append('\n');
				sb.append("- ").append(e.name()).append(" — INVALID: ").append(e.error());
			} else {
				if (sb.length() > 0) sb.append('\n');
				sb.append("- ").append(e.name()).append(" — ").append(e.description());
				if (isLoaded(effectiveLoads, e.path(), e.id(), loadedIds)) sb.append(" (loaded)");
			}
		}
		return (sb.length() == 0) ? null : sb.toString();
	}

	/** Loaded by path, or by content identity under any other path. */
	private static boolean isLoaded(AMap<AString, ACell> effectiveLoads, AString path,
			convex.core.data.Hash id, java.util.Set<convex.core.data.Hash> loadedIds) {
		if (effectiveLoads == null) return false;
		if (path != null && isSkillEntry(effectiveLoads.get(path))) return true;
		return id != null && loadedIds.contains(id);
	}

	// ========== Resolution ==========

	/**
	 * Resolves a skill by name across the given sources (the {@code skill_load
	 * {name}} lookup). First match in source order wins — consistent with the
	 * index dedup. Throws with a diagnosable message when not found.
	 */
	public static ResolvedSkill resolveByName(Engine engine, RequestContext ctx,
			SkillSources sources, String name) {
		if (sources != null) {
			// Individual skills first — same precedence as the index.
			for (long i = 0; i < sources.skills().count(); i++) {
				AString source = RT.ensureString(sources.skills().get(i));
				if (source == null) continue;
				try {
					ResolvedSkill s = resolveRef(engine, ctx, source);
					if (name.equals(s.name())) return s;
				} catch (RuntimeException e) {
					// A broken single-skill source cannot match by name. Keep
					// looking so later sources retain first-valid-match semantics.
				}
			}
			for (long i = 0; i < sources.skillsets().count(); i++) {
				AString source = RT.ensureString(sources.skillsets().get(i));
				if (source == null) continue;
				requireRead(engine, ctx, source);
				ACell value = engine.resolvePath(source, ctx);
				if (!(value instanceof AMap)) continue;
				ACell entry = ((AMap<?, ?>) value).get(Strings.create(name));
				if (entry != null && isSkillValue(entry)) {
					AString path = Strings.create(source + "/" + name);
					return resolveValue(engine, ctx, path, path, entry, true);
				}
			}
		}
		throw new RuntimeException("skill '" + name + "' not found. " + availability(engine, ctx, sources));
	}

	/** How many names a not-found error lists before it stops being useful. */
	private static final int MAX_SUGGESTED = 40;

	/**
	 * What the caller could have asked for instead — the whole point of the
	 * message. An agent that names a skill wrongly should be able to correct
	 * itself from the error alone rather than guessing again, so this lists the
	 * names actually in scope rather than echoing the source refs it searched.
	 *
	 * <p>Never throws: this runs on an error path, and a diagnostic that fails
	 * would replace a useful message with a confusing one.</p>
	 */
	private static String availability(Engine engine, RequestContext ctx, SkillSources sources) {
		List<String> names = new ArrayList<>();
		try {
			for (SkillIndexEntry e : listSkills(engine, ctx, sources)) {
				if (e.name() != null && e.error() == null) names.add(e.name());
			}
		} catch (RuntimeException e) {
			return "The available skills could not be listed: " + rootMessage(e);
		}
		if (names.isEmpty()) {
			return "No skills are currently available to you"
				+ (sources != null && sources.count() > 0
					? " from " + sources.skillsets() + sources.skills() : "")
				+ ".";
		}
		boolean truncated = names.size() > MAX_SUGGESTED;
		if (truncated) names = names.subList(0, MAX_SUGGESTED);
		return "Available: " + String.join(", ", names)
			+ (truncated ? ", …" : "")
			+ ". Load one of these by name, or use a direct ref.";
	}

	/** True when a resolved map is one skill rather than a directory of skills. */
	private static boolean isSkillMetadata(ACell value) {
		if (!(value instanceof AMap<?,?> map)) return false;
		// The facet/content keys are unambiguous. name+description covers a
		// contentless pure-toolset or instruction-only skill while avoiding
		// misclassifying an ordinary directory containing a coincidentally named
		// entry such as "description".
		return map.containsKey(K_SKILL)
			|| map.containsKey(Fields.CONTENT)
			|| map.containsKey(Fields.OPERATION)
			|| (map.containsKey(Fields.NAME) && map.containsKey(Fields.DESCRIPTION));
	}

	/**
	 * Resolves a skill at a direct address — an asset ref ({@code a/<hash>}),
	 * or a path whose value is a single skill (metadata map, string reference,
	 * or inline markdown). Throws with a diagnosable message on failure.
	 */
	public static ResolvedSkill resolveRef(Engine engine, RequestContext ctx, AString ref) {
		requireRead(engine, ctx, ref);
		ACell value = engine.resolvePath(ref, ctx);
		return resolveValue(engine, ctx, ref, ref, value, true);
	}

	/**
	 * Core resolution: interpret a value as a skill.
	 *
	 * @param path Canonical skill address (loads key, index identity)
	 * @param opRef Where the skill's asset actually lives — differs from
	 *        {@code path} after following a string reference; used for the
	 *        content read and as the self-tool ref for operation skills
	 * @param needBody When false (index description pass), the content read is
	 *        skipped if metadata already supplies name + description
	 */
	private static ResolvedSkill resolveValue(Engine engine, RequestContext ctx,
			AString path, AString opRef, ACell value, boolean needBody) {
		if (value == null) {
			throw new RuntimeException("skill not found: " + path);
		}

		if (value instanceof AString s) {
			String str = s.toString();
			if (ContextLoader.isAssetReference(str)) {
				// String reference — one hop to the skill asset (no chains,
				// matching Engine.resolveContent's one-hop rule).
				requireRead(engine, ctx, s);
				ACell target = engine.resolvePath(s, ctx);
				if (target == null) throw new RuntimeException("skill reference does not resolve: " + str);
				if (target instanceof AString) {
					throw new RuntimeException("skill reference chains are not followed: " + path + " → " + str);
				}
				return resolveValue(engine, ctx, path, s, target, needBody);
			}
			throw new RuntimeException("skill at " + path
				+ " is not a skill (expected asset metadata or a reference; inline bodies go in content.inline)");
		}

		if (value instanceof AMap) {
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> meta = (AMap<AString, ACell>) value;
			String name = str(meta.get(Fields.NAME));
			String description = str(meta.get(Fields.DESCRIPTION));

			// The skill facet — loadable extras, mirroring the operation facet.
			ACell facetCell = meta.get(K_SKILL);
			if (facetCell != null && !(facetCell instanceof AMap)) {
				throw new RuntimeException("skill facet at " + path + " must be a map");
			}
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> facet = (AMap<AString, ACell>) facetCell;
			AVector<ACell> tools = facetVector(facet, Fields.TOOLS, path);
			AVector<ACell> context = facetVector(facet, K_CONTEXT, path);
			AVector<ACell> childSkills = facetVector(facet, K_SKILLS, path);
			AVector<ACell> childSkillsets = facetVector(facet, K_SKILLSETS, path);
			long budget = (facet != null && facet.get(K_BUDGET) instanceof CVMLong l) ? l.longValue() : 0;
			for (long i = 0; i < tools.count(); i++) {
				if (RT.ensureString(tools.get(i)) == null) {
					throw new RuntimeException("skill.tools entries at " + path + " must be operation ref strings");
				}
			}
			requireRefStrings(childSkills, K_SKILLS, path);
			requireRefStrings(childSkillsets, K_SKILLSETS, path);

			// The index pass reads content ONLY when it still needs the
			// description: a missing name falls back to the path segment, which
			// is also the key skill_load matches inside a skillset, so probing
			// content for a frontmatter name would make the index show a name
			// that cannot be loaded. It also kept the "light" pass from being
			// light, and made a name-less skill demand asset/read to appear in
			// an index its named neighbours needed only crud/read for.
			String body = null;
			if (needBody || description == null) {
				body = contentOf(engine, ctx, opRef);
				if (body != null) {
					// SKILL.md frontmatter is metadata's compatibility fallback;
					// it is always stripped from the body.
					Frontmatter fm = parseFrontmatter(body);
					if (fm != null) {
						if (name == null) name = fm.name();
						if (description == null) description = fm.description();
						// Metadata wins: frontmatter only fills what the facet
						// left empty, so a stored facet is never overridden by
						// the content it points at.
						if (tools.count() == 0) tools = fm.tools();
						if (childSkills.count() == 0) childSkills = fm.skills();
						if (childSkillsets.count() == 0) childSkillsets = fm.skillsets();
						body = fm.body();
					}
				}
			}
			// An operation skill offers itself as a tool. Applied after the
			// frontmatter merge so the merge sees whether tools were declared.
			if (meta.get(Fields.OPERATION) != null) {
				tools = tools.conj(opRef);
			}
			if (description == null) throw missingDescription(path);
			if (name == null) name = lastSegment(path.toString());
			// A null body is valid: a contentless skill is a pure toolset.
			// Identity is the metadata's value hash — content equality, not path.
			return new ResolvedSkill(name, description, body, tools, context,
				childSkills, childSkillsets, budget, path, meta.getHash());
		}

		throw new RuntimeException("skill at " + path
			+ " is not a skill (expected asset metadata, a reference, or markdown text)");
	}

	/**
	 * Shared read pin for every skill source/ref, including harness
	 * {@code skill_load}. A skill is an asset, so either {@code crud/read} or
	 * {@code asset/read} over it is enough — see
	 * {@link Engine#requireMetadataRead}. Whether a skill was reached by path
	 * or by hash, and whether it declares a {@code name} (which decides
	 * whether resolution reads content), must not change what a caller needs.
	 */
	private static void requireRead(Engine engine, RequestContext ctx, AString ref) {
		engine.requireMetadataRead(ctx, ref);
	}

	private static AVector<ACell> facetVector(AMap<AString, ACell> facet, AString key, AString path) {
		if (facet == null) return Vectors.empty();
		ACell v = facet.get(key);
		if (v == null) return Vectors.empty();
		AVector<ACell> vec = RT.ensureVector(v);
		if (vec == null) throw new RuntimeException("skill." + key + " at " + path + " must be an array");
		return vec;
	}

	/** Reads the skill body via the venue's universal content resolution
	 *  (CAS blob, content.dlfs pinned/live, provider refs). Null when the
	 *  asset has no content — the caller falls back to the description. */
	private static String contentOf(Engine engine, RequestContext ctx, AString ref) {
		try {
			covia.venue.storage.ContentProvider.Resolved resolved = engine.resolveContent(ref, ctx);
			if (resolved == null || resolved.content() == null) return null;
			ABlob blob = resolved.content().getBlob();
			if (blob == null || blob.count() == 0) return null;
			return new String(blob.getBytes(), StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new RuntimeException("skill content read failed for " + ref + ": " + e.getMessage(), e);
		}
	}

	private static RuntimeException missingDescription(AString path) {
		return new RuntimeException("missing description");
	}

	// ========== SKILL.md frontmatter ==========

	record Frontmatter(String name, String description, String body,
			AVector<ACell> tools, AVector<ACell> skills, AVector<ACell> skillsets) {}

	/**
	 * Parses Anthropic-style SKILL.md YAML frontmatter: a leading {@code ---}
	 * block of {@code key: value} lines. {@code name} and {@code description}
	 * are scalars; {@code tools}, {@code skills} and {@code skillsets} are
	 * lists in either YAML form — flow ({@code tools: [a, b]}) or block
	 * (subsequent {@code  - a} lines). Other keys are ignored. Returns null
	 * when the text has no frontmatter.
	 *
	 * <p>Declaring these in metadata is equivalent and takes precedence; the
	 * frontmatter path exists so a self-contained SKILL.md file is a complete
	 * skill — including a router that contributes skillsets.</p>
	 */
	static Frontmatter parseFrontmatter(String text) {
		if (text == null) return null;
		if (!(text.startsWith("---\n") || text.startsWith("---\r\n"))) return null;
		int start = text.indexOf('\n') + 1;
		String name = null, description = null;
		AVector<ACell> tools = Vectors.empty(), skills = Vectors.empty(), skillsets = Vectors.empty();
		String listKey = null;                                       // open block-sequence key
		int pos = start;
		while (pos < text.length()) {
			int eol = text.indexOf('\n', pos);
			if (eol < 0) return null;                                // no closing delimiter → not frontmatter
			String line = text.substring(pos, eol).stripTrailing();
			pos = eol + 1;
			if (line.strip().equals("---")) {
				// Body starts after the closing delimiter; strip at most one
				// blank separator line, preserving intentional leading content.
				String body = text.substring(pos);
				if (body.startsWith("\r\n")) body = body.substring(2);
				else if (body.startsWith("\n")) body = body.substring(1);
				return new Frontmatter(name, description, body, tools, skills, skillsets);
			}
			// A block-sequence item continues the most recent list key.
			String stripped = line.strip();
			if (listKey != null && stripped.startsWith("-")) {
				String item = unquote(stripped.substring(1).strip());
				if (!item.isEmpty()) {
					switch (listKey) {
						case "tools" -> tools = tools.conj(Strings.create(item));
						case "skills" -> skills = skills.conj(Strings.create(item));
						case "skillsets" -> skillsets = skillsets.conj(Strings.create(item));
						default -> { }
					}
				}
				continue;
			}
			listKey = null;
			int colon = line.indexOf(':');
			if (colon > 0) {
				String key = line.substring(0, colon).strip();
				String val = line.substring(colon + 1).strip();
				switch (key) {
					case "name" -> name = unquote(val);
					case "description" -> description = unquote(val);
					case "tools" -> { tools = parseList(val); listKey = val.isEmpty() ? key : null; }
					case "skills" -> { skills = parseList(val); listKey = val.isEmpty() ? key : null; }
					case "skillsets" -> { skillsets = parseList(val); listKey = val.isEmpty() ? key : null; }
					default -> { }
				}
			}
		}
		return null;
	}

	/**
	 * A YAML flow sequence ({@code [a, b]}) or a bare scalar treated as a
	 * one-element list. An empty value opens a block sequence instead.
	 */
	private static AVector<ACell> parseList(String value) {
		AVector<ACell> out = Vectors.empty();
		if (value == null || value.isEmpty()) return out;
		String body = value;
		if (body.startsWith("[") && body.endsWith("]")) {
			body = body.substring(1, body.length() - 1);
		} else {
			// A bare scalar is a single entry — tolerant of `skills: v/skills/x`.
			String single = unquote(body);
			return single.isEmpty() ? out : out.conj(Strings.create(single));
		}
		for (String part : body.split(",")) {
			String item = unquote(part.strip());
			if (!item.isEmpty()) out = out.conj(Strings.create(item));
		}
		return out;
	}

	/** Strips one layer of matching YAML quotes. */
	private static String unquote(String s) {
		if (s.length() >= 2
			&& ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
			return s.substring(1, s.length() - 1);
		}
		return s;
	}

	// ========== Loads-entry integration ==========

	/** Every entry of a declared ref list must be a string ref. */
	private static void requireRefStrings(AVector<ACell> refs, AString key, AString path) {
		for (long i = 0; i < refs.count(); i++) {
			if (RT.ensureString(refs.get(i)) == null) {
				throw new RuntimeException("skill." + key + " entries at " + path
					+ " must be " + (K_SKILLSETS.equals(key) ? "skillset" : "skill") + " ref strings");
			}
		}
	}

	/**
	 * Builds the loads-entry spec for a loaded skill:
	 * {@code {skill: true, budget, ts, label, tools?, skills?, skillsets?}}. A
	 * plain map — fully compatible with ContextChain (tombstones, masking),
	 * context_unload, the Context Map, and safety-valve eviction. The body is
	 * NOT denormalised (re-resolved each turn via the entry key); tool and
	 * child refs ARE (their targets still resolve fresh each turn).
	 */
	public static AMap<AString, ACell> buildSkillLoadMeta(long budget, ResolvedSkill skill) {
		AMap<AString, ACell> meta = Maps.of(
			K_SKILL, CVMBool.TRUE,
			K_BUDGET, CVMLong.create(budget),
			K_TS, CVMLong.create(System.currentTimeMillis()),
			K_LABEL, Strings.create(skill.name()));
		if (skill.toolOps().count() > 0) {
			meta = meta.assoc(Fields.TOOLS, skill.toolOps());
		}
		if (skill.skills().count() > 0) {
			meta = meta.assoc(K_SKILLS, skill.skills());
		}
		if (skill.skillsets().count() > 0) {
			meta = meta.assoc(K_SKILLSETS, skill.skillsets());
		}
		return meta;
	}

	/**
	 * Combines configured sources with those contributed by currently loaded
	 * skills, per kind. Configured sources retain priority; exact duplicate
	 * refs are removed first-wins. Only the immediate refs on loaded entries
	 * are considered: children are discoverable, never recursively auto-loaded,
	 * so an unloaded subtree is never walked and cycles are inert.
	 */
	public static SkillSources effectiveSources(SkillSources configured,
			AMap<AString, ACell> effectiveLoads) {
		AVector<ACell> skills = Vectors.empty();
		AVector<ACell> skillsets = Vectors.empty();
		Set<String> seenSkills = new HashSet<>();
		Set<String> seenSets = new HashSet<>();
		if (configured != null) {
			skills = appendSources(skills, configured.skills(), seenSkills, "config.skills", K_SKILLS);
			skillsets = appendSources(skillsets, configured.skillsets(), seenSets,
				"config.skillsets", K_SKILLSETS);
		}
		if (effectiveLoads == null) return new SkillSources(skills, skillsets);
		for (var entry : effectiveLoads.entrySet()) {
			if (!isSkillEntry(entry.getValue())) continue;
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> spec = (AMap<AString, ACell>) entry.getValue();
			skills = appendContributed(skills, spec, K_SKILLS, seenSkills, entry.getKey());
			skillsets = appendContributed(skillsets, spec, K_SKILLSETS, seenSets, entry.getKey());
		}
		return new SkillSources(skills, skillsets);
	}

	/** Appends one kind of contributed refs from a loaded skill's entry. */
	private static AVector<ACell> appendContributed(AVector<ACell> out,
			AMap<AString, ACell> spec, AString key, Set<String> seen, AString entryKey) {
		ACell raw = spec.get(key);
		if (raw == null) return out;
		AVector<ACell> refs = RT.ensureVector(raw);
		if (refs == null) {
			throw new RuntimeException("loaded skill." + key + " at " + entryKey + " must be an array");
		}
		return appendSources(out, refs, seen, "loaded skill." + key + " at " + entryKey, key);
	}

	private static AVector<ACell> appendSources(AVector<ACell> out, AVector<ACell> sources,
			Set<String> seen, String label, AString kind) {
		if (sources == null) return out;
		for (long i = 0; i < sources.count(); i++) {
			AString source = RT.ensureString(sources.get(i));
			if (source == null) {
				throw new RuntimeException(label + " entries must be "
					+ (K_SKILLSETS.equals(kind) ? "skillset" : "skill") + " ref strings");
			}
			if (seen.add(source.toString())) out = out.conj(source);
		}
		return out;
	}

	/**
	 * Finds a skill entry in the effective loads whose LIVE content identity
	 * equals {@code id}, regardless of the path it was loaded under. Returns
	 * its path, or null. This is the dedup rule: skills are content-addressed,
	 * so the same skill reached via different addresses (a directory ref vs
	 * the asset hash, mirrored directories) must not load twice.
	 *
	 * <p>Identities are re-resolved here, not stored on entries — consistent
	 * with the liveness contract (bodies and tool defs also re-resolve). An
	 * entry that no longer resolves is not "the same skill" and never blocks
	 * a load. Effective loads are few, and cell hashes are memoised, so the
	 * live comparison is cheap.</p>
	 */
	public static AString findLoadedDuplicate(Engine engine, RequestContext ctx,
			AMap<AString, ACell> effectiveLoads, convex.core.data.Hash id) {
		if (effectiveLoads == null || id == null) return null;
		for (var entry : effectiveLoads.entrySet()) {
			if (!isSkillEntry(entry.getValue())) continue;
			try {
				if (id.equals(resolveRefLight(engine, ctx, entry.getKey()).id())) {
					return entry.getKey();
				}
			} catch (RuntimeException e) {
				// Dangling entry — renders visibly elsewhere; irrelevant to dedup.
			}
		}
		return null;
	}

	/** The live content identities of every skill entry in the effective
	 *  loads (unresolvable entries contribute nothing). */
	static java.util.Set<convex.core.data.Hash> loadedSkillIds(Engine engine,
			RequestContext ctx, AMap<AString, ACell> effectiveLoads) {
		java.util.Set<convex.core.data.Hash> ids = new HashSet<>();
		if (effectiveLoads == null) return ids;
		for (var entry : effectiveLoads.entrySet()) {
			if (!isSkillEntry(entry.getValue())) continue;
			try {
				ids.add(resolveRefLight(engine, ctx, entry.getKey()).id());
			} catch (RuntimeException e) {
				// Dangling entry — no identity to contribute.
			}
		}
		return ids;
	}

	/** Resolution without the body read (identity + index fields only). */
	private static ResolvedSkill resolveRefLight(Engine engine, RequestContext ctx, AString ref) {
		requireRead(engine, ctx, ref);
		return resolveValue(engine, ctx, ref, ref, engine.resolvePath(ref, ctx), false);
	}

	/** True when a loads-entry spec is skill-flagged. */
	public static boolean isSkillEntry(ACell spec) {
		if (!(spec instanceof AMap)) return false;
		return CVMBool.TRUE.equals(((AMap<?, ?>) spec).get(K_SKILL));
	}

	// ========== Venue-side configuration diagnostics ==========

	/**
	 * The first declared skillset that resolves to a directory of
	 * <b>directories</b> rather than of skills, or null when every skillset is
	 * either absent (normal — maybe-style paths) or genuinely a skillset.
	 *
	 * <p>This catches the one shape that is silently useless: pointing at a
	 * level of the tree that holds skillsets, classically {@code v/skills}
	 * instead of {@code v/skills/root}. Read failures are not diagnosed here —
	 * an unreadable source is a capability matter, not a shape mistake.</p>
	 *
	 * <p>Resolves in {@code ctx}'s namespace: {@code w/} paths are
	 * user-relative, so the CALLER's context must be used, never the
	 * venue's.</p>
	 */
	public static AString misdirectedSkillset(Engine engine, RequestContext ctx,
			AVector<ACell> skillsets) {
		if (engine == null || ctx == null || skillsets == null) return null;
		for (long i = 0; i < skillsets.count(); i++) {
			AString ref = RT.ensureString(skillsets.get(i));
			if (ref == null) continue;
			try {
				ACell value = engine.resolvePath(ref, ctx);
				if (!(value instanceof AMap<?, ?> dir) || dir.isEmpty()) continue;
				boolean anySkill = false;
				for (var entry : dir.entrySet()) {
					if (isSkillValue(entry.getValue())) { anySkill = true; break; }
				}
				if (!anySkill) return ref;
			} catch (RuntimeException e) {
				// Unreadable or absent — not a shape problem. Skip.
			}
		}
		return null;
	}

	/**
	 * The first declared source the caller cannot READ, or null when every one
	 * is readable or merely absent.
	 *
	 * <p>Absence is normal and stays undiagnosed — sources are maybe-style
	 * paths. A denial is not: it renders nothing in the agent's index and
	 * nothing in its logs, so a skillset the agent has no grant for looks
	 * exactly like an empty one. That is the single case worth telling the
	 * operator about, at the moment the config is set.</p>
	 *
	 * <p>Checked as {@code ctx}, so it catches the common setup mistakes — a
	 * typo, or another user's namespace. An agent later runs under its own
	 * {@code config.caps}, which can be narrower still, so a clean result here
	 * is not a guarantee.</p>
	 */
	public static AString unreadableSource(Engine engine, RequestContext ctx, SkillSources sources) {
		if (engine == null || ctx == null || sources == null) return null;
		AString denied = firstDenied(engine, ctx, sources.skills());
		return (denied != null) ? denied : firstDenied(engine, ctx, sources.skillsets());
	}

	private static AString firstDenied(Engine engine, RequestContext ctx, AVector<ACell> refs) {
		if (refs == null) return null;
		for (long i = 0; i < refs.count(); i++) {
			AString ref = RT.ensureString(refs.get(i));
			if (ref == null) continue;
			try {
				requireRead(engine, ctx, ref);
			} catch (covia.exception.AuthException denied) {
				return ref;
			} catch (RuntimeException e) {
				// Not a capability problem — absence and resolution errors are
				// not this check's business.
			}
		}
		return null;
	}

	/**
	 * Validates the venue's own skill library after catalog materialisation and
	 * logs a warning per problem. Unlike a user's agent config — whose sources
	 * are deliberately maybe-style — everything here was installed by this
	 * venue, so a ref that does not resolve is a packaging bug worth surfacing
	 * at boot rather than at an agent's first turn.
	 *
	 * <p>Checks each installed skill's declared {@code skill.skills} and
	 * {@code skill.skillsets} refs resolve and are of the declared kind, and
	 * that no skillset directly holds a nested directory (skills and
	 * directories are separate kinds — SKILLS.md §4.1). Never throws: a
	 * diagnostic must not take down a venue's boot.</p>
	 *
	 * @return the number of problems found (0 when the library is clean)
	 */
	public static int validateVenueLibrary(Engine engine) {
		if (engine == null) return 0;
		return validateLibrary(engine, RequestContext.of(engine.getDIDString()), VENUE_SKILLS);
	}

	/**
	 * {@link #validateVenueLibrary} against an arbitrary library root, so the
	 * same rules can be exercised over a workspace tree in tests.
	 */
	public static int validateLibrary(Engine engine, RequestContext ctx, String libraryRoot) {
		if (engine == null || ctx == null) return 0;
		int problems = 0;
		try {
			ACell root = engine.resolvePath(Strings.create(libraryRoot), ctx);
			if (!(root instanceof AMap<?, ?> sets)) return 0;
			for (var setEntry : sets.entrySet()) {
				AString setName = RT.ensureString(setEntry.getKey());
				if (setName == null) continue;
				String setPath = libraryRoot + "/" + setName;
				if (isSkillValue(setEntry.getValue())) {
					// A skill sitting where a skillset belongs. Mixing assets and
					// directories at one level is what the kind split exists to
					// prevent, so name the fix rather than the symptom.
					log.warn("Venue skills: {} is a skill at skillset level — {} holds skillsets, "
						+ "so move it into one (e.g. {}/root/{})",
						setPath, libraryRoot, libraryRoot, setName);
					problems++;
					continue;
				}
				if (!(setEntry.getValue() instanceof AMap<?, ?> members)) {
					log.warn("Venue skills: {} is not a skillset (a directory of skills)", setPath);
					problems++;
					continue;
				}
				for (var member : members.entrySet()) {
					AString skillName = RT.ensureString(member.getKey());
					if (skillName == null) continue;
					String skillPath = setPath + "/" + skillName;
					if (!isSkillValue(member.getValue())) {
						log.warn("Venue skills: {} is a nested directory inside a skillset — "
							+ "declare it as its own skillset", skillPath);
						problems++;
						continue;
					}
					problems += validateSkillRefs(engine, ctx, skillPath);
				}
			}
		} catch (RuntimeException e) {
			log.warn("Venue skills validation failed: {}", rootMessage(e));
		}
		return problems;
	}

	/** Warns for each unresolvable or wrong-kind child ref on one venue skill. */
	private static int validateSkillRefs(Engine engine, RequestContext ctx, String skillPath) {
		ResolvedSkill skill;
		try {
			skill = resolveRefLight(engine, ctx, Strings.create(skillPath));
		} catch (RuntimeException e) {
			log.warn("Venue skills: {} does not resolve as a skill: {}", skillPath, rootMessage(e));
			return 1;
		}
		int problems = 0;
		for (long i = 0; i < skill.skills().count(); i++) {
			AString ref = RT.ensureString(skill.skills().get(i));
			ACell value = safeResolve(engine, ctx, ref);
			if (value == null) {
				log.warn("Venue skills: {} declares skill.skills '{}' which does not resolve",
					skillPath, ref);
				problems++;
			} else if (!isSkillValue(value)) {
				log.warn("Venue skills: {} declares skill.skills '{}' which is not a skill "
					+ "(declare a directory under skill.skillsets)", skillPath, ref);
				problems++;
			}
		}
		for (long i = 0; i < skill.skillsets().count(); i++) {
			AString ref = RT.ensureString(skill.skillsets().get(i));
			ACell value = safeResolve(engine, ctx, ref);
			if (value == null) {
				log.warn("Venue skills: {} declares skill.skillsets '{}' which does not resolve",
					skillPath, ref);
				problems++;
			} else if (!(value instanceof AMap)) {
				log.warn("Venue skills: {} declares skill.skillsets '{}' which is not a directory",
					skillPath, ref);
				problems++;
			}
		}
		return problems;
	}

	private static ACell safeResolve(Engine engine, RequestContext ctx, AString ref) {
		if (ref == null) return null;
		try {
			return engine.resolvePath(ref, ctx);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The {@code [Skill: <name>]}-labelled system message carrying a skill body. */
	static ACell renderSkillMessage(String name, String body) {
		return Maps.of(K_ROLE, ROLE_SYSTEM, K_CONTENT,
			Strings.create("[Skill: " + name + "]\n" + body));
	}

	/** The visible element for a loaded skill that no longer resolves. */
	static ACell skillErrorMessage(String label, String reason) {
		return Maps.of(K_ROLE, ROLE_SYSTEM, K_CONTENT,
			Strings.create("[Skill: " + label + " — unavailable: " + reason + "]"));
	}

	// ========== skill_load (harness-tool semantics) ==========

	/**
	 * The discovery surface declared on an agent config — {@code config.skills}
	 * (individual skills) and {@code config.skillsets} (directories), validated.
	 * Empty when the agent declares neither. Adapters treat the result as
	 * opaque — all skills semantics live here and in {@link ContextBuilder}.
	 */
	public static SkillSources sourcesOf(AMap<AString, ACell> config) {
		if (config == null) return SkillSources.EMPTY;
		return new SkillSources(
			ContextBuilder.skillSources(config.get(K_SKILLS), K_SKILLS),
			ContextBuilder.skillSources(config.get(K_SKILLSETS), K_SKILLSETS));
	}

	/**
	 * The outcome of a {@code skill_load}: the loads entry to write (path +
	 * spec) and the tool result for the model. The caller (a harness-tool
	 * handler) glues the entry into its innermost loads tier — the one thing
	 * only the runtime can do. A null {@code entryMeta} means nothing to
	 * write: the skill is already loaded under {@code path} (content dedup).
	 */
	public record LoadOutcome(AString path, AMap<AString, ACell> entryMeta,
			AMap<AString, ACell> result) {}

	/**
	 * Executes the {@code skill_load} semantics: resolve the skill (by
	 * {@code name} across the agent's sources, or by direct {@code ref}),
	 * build its loads entry, and assemble the tool result — including the
	 * body for immediate same-turn use, the activated tool names, the refreshed
	 * skill index when it contributes sources, and any declared-but-unresolvable
	 * tools. Throws with a diagnosable message on
	 * any failure (the handler renders it as an {@code Error:} tool result).
	 *
	 * <p><b>Content-identity dedup</b>: when {@code effectiveLoads} already
	 * carries this skill (same metadata hash) under a <i>different</i> path,
	 * nothing is added — the outcome names the existing entry and its
	 * {@code entryMeta} is null. Reloading under the SAME path still
	 * overwrites (budget updates).</p>
	 */
	public static LoadOutcome load(Engine engine, RequestContext ctx,
			SkillSources sources, ACell toolInput, AMap<AString, ACell> effectiveLoads) {
		AString name = RT.ensureString(RT.getIn(toolInput, Fields.NAME));
		AString ref = RT.ensureString(RT.getIn(toolInput, AbstractLLMAdapter.K_REF));
		if ((name == null) == (ref == null)) {
			throw new IllegalArgumentException("skill_load requires exactly one of 'name' or 'ref'");
		}
		ResolvedSkill skill = (ref != null)
			? resolveRef(engine, ctx, ref)
			: resolveByName(engine, ctx, effectiveSources(sources, effectiveLoads), name.toString());

		// Same content already loaded under another address → no-op naming it.
		AString existing = findLoadedDuplicate(engine, ctx, effectiveLoads, skill.id());
		if (existing != null && !existing.equals(skill.path())) {
			return new LoadOutcome(existing, null, Maps.of(
				Strings.intern("loaded"), CVMBool.TRUE,
				K_SKILL, Strings.create(skill.name()),
				Strings.intern("path"), existing,
				Strings.intern("note"), Strings.create(
					"Already loaded (as " + existing + ") — identical skill content; nothing added.")));
		}

		// Budget precedence: caller > skill.budget facet > skill default.
		long defaultBudget = (skill.budget() > 0) ? skill.budget()
			: AbstractLLMAdapter.SKILL_LOAD_DEFAULT_BUDGET;
		long budget = AbstractLLMAdapter.clampLoadBudget(
			RT.getIn(toolInput, K_BUDGET), defaultBudget);

		AMap<AString, ACell> entryMeta = buildSkillLoadMeta(budget, skill);

		// Resolve the declared tools once for an honest result (activated
		// names + unresolvable refs). Per-turn activation re-resolves via the
		// generic loads rule (ContextBuilder.loadsToolDefs) — same liveness
		// as config.tools.
		AVector<ACell> toolNames = Vectors.empty();
		AVector<ACell> unresolved = Vectors.empty();
		if (skill.toolOps().count() > 0) {
			Map<String, AString> routes = new java.util.HashMap<>();
			AVector<ACell> defs = new ContextBuilder(engine, ctx).buildConfigTools(skill.toolOps(), routes);
			for (long i = 0; i < defs.count(); i++) {
				ACell n = RT.getIn(defs.get(i), Fields.NAME);
				if (n != null) toolNames = toolNames.conj(n);
			}
			Set<String> resolvedOps = new HashSet<>();
			for (AString route : routes.values()) resolvedOps.add(route.toString());
			for (long i = 0; i < skill.toolOps().count(); i++) {
				ACell op = skill.toolOps().get(i);
				if (op != null && !resolvedOps.contains(op.toString())) {
					unresolved = unresolved.conj(op);
				}
			}
		}

		AMap<AString, ACell> result = Maps.of(
			Strings.intern("loaded"), CVMBool.TRUE,
			K_SKILL, Strings.create(skill.name()),
			Strings.intern("path"), skill.path(),
			Strings.intern("body"), Strings.create(skill.displayBody()),
			Strings.intern("note"), Strings.create(
				"Skill instructions stay in context each turn (unload with context_unload). "
				+ "Tools and contributed skills are active from your next step."));
		if (toolNames.count() > 0) result = result.assoc(Fields.TOOLS, toolNames);
		if (skill.contributesSources()) {
			if (skill.skills().count() > 0) result = result.assoc(K_SKILLS, skill.skills());
			if (skill.skillsets().count() > 0) result = result.assoc(K_SKILLSETS, skill.skillsets());
			AMap<AString, ACell> prospectiveLoads = (effectiveLoads == null)
				? Maps.of(skill.path(), entryMeta)
				: effectiveLoads.assoc(skill.path(), entryMeta);
			String index = renderIndex(engine, ctx,
				effectiveSources(sources, prospectiveLoads), prospectiveLoads, false);
			if (index != null) result = result.assoc(Strings.intern("skillIndex"), Strings.create(index));
		}
		if (unresolved.count() > 0) result = result.assoc(Strings.intern("unresolved"), unresolved);

		return new LoadOutcome(skill.path(), entryMeta, result);
	}

	// ========== Small helpers ==========

	private static String str(ACell v) {
		AString s = RT.ensureString(v);
		return (s != null) ? s.toString() : null;
	}

	static String lastSegment(String path) {
		int slash = path.lastIndexOf('/');
		String seg = (slash >= 0) ? path.substring(slash + 1) : path;
		// Hash-form addresses index by a short prefix rather than 64 hex chars.
		if (seg.length() == 64 && seg.matches("[0-9a-fA-F]+")) return seg.substring(0, 12) + "...";
		return seg;
	}

	/** Unwraps the most useful message from a (possibly wrapped) throwable. */
	private static String rootMessage(Throwable e) {
		Throwable c = e;
		if (c instanceof java.util.concurrent.ExecutionException && c.getCause() != null) c = c.getCause();
		String m = c.getMessage();
		return (m != null && !m.isEmpty()) ? m : c.getClass().getSimpleName();
	}
}
