package covia.adapter.agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import convex.auth.ucan.Capability;
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
import covia.api.Abilities;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * The single resolver for agent skills — named, discoverable bundles of
 * instructions, context, and tools that an agent loads on demand.
 *
 * <p>A skill is an <b>asset</b>: ordinary asset metadata ({@code name},
 * {@code description}, the standard {@code content} descriptor) with the
 * loadable extras under a {@code skill} facet ({@code tools}, {@code context},
 * {@code budget}) — mirroring how invocability sits under the {@code operation}
 * facet. The body is the asset's content, resolved through the venue's
 * universal content resolution ({@link Engine#resolveContent}), falling back
	 * to the {@code description}. A value is interpreted as a skill only through
	 * an explicit skill surface: a {@code config.skills} source, an entry in a
	 * skills directory, or the target of {@code skill_load}.</p>
	 *
	 * <p>Skill sources are positional: a path may resolve to a directory whose
	 * keys are skill names or directly to one skill; an asset ref is also one
	 * skill. Directory values are asset metadata maps or string references to
	 * skill assets (the template string-ref idiom, one hop). Inline bodies use the standard
 * {@code content.inline} metadata declaration; SKILL.md YAML frontmatter in
 * any content supplies name/description when the metadata lacks them.</p>
 *
 * <p>Used by the {@code skills} venue op, the {@code skill_load} harness tool,
 * and {@link ContextBuilder}'s per-turn index/rendering — one resolver, so the
 * surfaces can never drift. See {@code venue/docs/SKILLS.md} for the design.</p>
 */
public final class Skills {

	private Skills() {}

	/** The skill facet key on asset metadata, and the flag key on loads entries. */
	public static final AString K_SKILL = Strings.intern("skill");

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
			AVector<ACell> toolOps, AVector<ACell> contextEntries, long budget, AString path,
			convex.core.data.Hash id) {

		/** What a renderer shows for this skill: the body, else the
		 *  description one-liner (a contentless skill still announces itself). */
		public String displayBody() {
			return (body != null) ? body : description;
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
	public static List<SkillIndexEntry> listSkills(Engine engine, RequestContext ctx, AVector<ACell> sources) {
		List<SkillIndexEntry> out = new ArrayList<>();
		if (sources == null) return out;
		Set<String> seen = new HashSet<>();
		for (long i = 0; i < sources.count(); i++) {
			AString source = RT.ensureString(sources.get(i));
			if (source == null) {
				throw new IllegalArgumentException("skills sources must be strings — got: " + sources.get(i));
			}
			try {
				requireRead(engine, ctx, source);
				if (AssetAdapter.parseAssetId(source) != null) {
					// Asset ref source → a single skill
					addEntry(out, seen, describe(engine, ctx, source, engine.resolvePath(source, ctx), source));
				} else {
					ACell value = engine.resolvePath(source, ctx);
					if (value == null) continue;                     // absent → skip quietly
					if (isSkillMetadata(value)) {
						// A stable path to one skill (for example
						// v/skills/workspace) is a first-class source. This lets
						// templates curate a compact role-specific index without
						// embedding content hashes or manufacturing directories.
						addEntry(out, seen, describe(engine, ctx, source, value, source));
						continue;
					}
					if (!(value instanceof AMap)) {
						out.add(new SkillIndexEntry(null, null, source,
							"not a skill directory (resolves to " + value.getClass().getSimpleName() + ")", null));
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
						addEntry(out, seen, describe(engine, ctx, path, dir.get(Strings.create(key)), path));
					}
				}
			} catch (RuntimeException e) {
				out.add(new SkillIndexEntry(null, null, source, rootMessage(e), null));
			}
		}
		return out;
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
			AVector<ACell> sources, AMap<AString, ACell> effectiveLoads, boolean sourceDiagnostics) {
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
			AVector<ACell> sources, String name) {
		if (sources != null) {
			for (long i = 0; i < sources.count(); i++) {
				AString source = RT.ensureString(sources.get(i));
				if (source == null) continue;
				if (AssetAdapter.parseAssetId(source) != null) {
					try {
						ResolvedSkill s = resolveRef(engine, ctx, source);
						if (name.equals(s.name())) return s;
					} catch (RuntimeException e) {
						// A broken single-skill source can't match by name — keep looking.
					}
					continue;
				}
				requireRead(engine, ctx, source);
				ACell value = engine.resolvePath(source, ctx);
				if (isSkillMetadata(value)) {
					try {
						ResolvedSkill s = resolveValue(engine, ctx, source, source, value, true);
						if (name.equals(s.name())) return s;
					} catch (RuntimeException e) {
						// A broken single-skill source cannot match by name. Keep
						// looking so later sources retain first-valid-match semantics.
					}
					continue;
				}
				if (!(value instanceof AMap)) continue;
				ACell entry = ((AMap<?, ?>) value).get(Strings.create(name));
				if (entry != null) {
					AString path = Strings.create(source + "/" + name);
					return resolveValue(engine, ctx, path, path, entry, true);
				}
			}
		}
		throw new RuntimeException("skill '" + name + "' not found in skill sources"
			+ (sources != null ? " " + sources : ""));
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
			long budget = (facet != null && facet.get(K_BUDGET) instanceof CVMLong l) ? l.longValue() : 0;
			for (long i = 0; i < tools.count(); i++) {
				if (RT.ensureString(tools.get(i)) == null) {
					throw new RuntimeException("skill.tools entries at " + path + " must be operation ref strings");
				}
			}
			// An operation skill offers itself as a tool.
			if (meta.get(Fields.OPERATION) != null) {
				tools = tools.conj(opRef);
			}

			String body = null;
			if (needBody || name == null || description == null) {
				body = contentOf(engine, ctx, opRef);
				if (body != null) {
					// SKILL.md frontmatter is metadata's compatibility fallback;
					// it is always stripped from the body.
					Frontmatter fm = parseFrontmatter(body);
					if (fm != null) {
						if (name == null) name = fm.name();
						if (description == null) description = fm.description();
						body = fm.body();
					}
				}
			}
			if (description == null) throw missingDescription(path);
			if (name == null) name = lastSegment(path.toString());
			// A null body is valid: a contentless skill is a pure toolset.
			// Identity is the metadata's value hash — content equality, not path.
			return new ResolvedSkill(name, description, body, tools, context, budget, path,
				meta.getHash());
		}

		throw new RuntimeException("skill at " + path
			+ " is not a skill (expected asset metadata, a reference, or markdown text)");
	}

	/** Shared read pin for every skill source/ref, including harness skill_load. */
	private static void requireRead(Engine engine, RequestContext ctx, AString ref) {
		if (ref == null) return;
		AString ability = (AssetAdapter.parseAssetId(ref) != null)
			? Abilities.ASSET_READ : Capability.CRUD_READ;
		engine.requireResourceAccess(ctx, ref, ability);
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

	record Frontmatter(String name, String description, String body) {}

	/**
	 * Parses Anthropic-style SKILL.md YAML frontmatter: a leading
	 * {@code ---} block with simple {@code key: value} lines. Only
	 * {@code name} and {@code description} are read; other keys are ignored.
	 * Returns null when the text has no frontmatter.
	 */
	static Frontmatter parseFrontmatter(String text) {
		if (text == null) return null;
		if (!(text.startsWith("---\n") || text.startsWith("---\r\n"))) return null;
		int start = text.indexOf('\n') + 1;
		String name = null, description = null;
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
				return new Frontmatter(name, description, body);
			}
			int colon = line.indexOf(':');
			if (colon > 0) {
				String key = line.substring(0, colon).strip();
				String val = line.substring(colon + 1).strip();
				if ("name".equals(key)) name = val;
				else if ("description".equals(key)) description = val;
			}
		}
		return null;
	}

	// ========== Loads-entry integration ==========

	/**
	 * Builds the loads-entry spec for a loaded skill:
	 * {@code {skill: true, budget, ts, label, tools?}}. A plain map — fully
	 * compatible with ContextChain (tombstones, masking), context_unload,
	 * the Context Map, and safety-valve eviction. The body is NOT
	 * denormalised (re-resolved each turn via the entry key); the tool refs
	 * ARE (their LLM defs still resolve fresh each turn).
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
		return meta;
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
	 * The skill sources declared on an agent config ({@code config.skills}),
	 * validated. Empty when the agent declares none. Adapters treat the result
	 * as opaque — all skills semantics live here and in {@link ContextBuilder}.
	 */
	public static AVector<ACell> sourcesOf(AMap<AString, ACell> config) {
		return ContextBuilder.skillSources(config == null ? null : config.get(K_SKILLS_CONFIG));
	}

	private static final AString K_SKILLS_CONFIG = Strings.intern("skills");

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
	 * body for immediate same-turn use, the activated tool names, and any
	 * declared-but-unresolvable tools. Throws with a diagnosable message on
	 * any failure (the handler renders it as an {@code Error:} tool result).
	 *
	 * <p><b>Content-identity dedup</b>: when {@code effectiveLoads} already
	 * carries this skill (same metadata hash) under a <i>different</i> path,
	 * nothing is added — the outcome names the existing entry and its
	 * {@code entryMeta} is null. Reloading under the SAME path still
	 * overwrites (budget updates).</p>
	 */
	public static LoadOutcome load(Engine engine, RequestContext ctx,
			AVector<ACell> sources, ACell toolInput, AMap<AString, ACell> effectiveLoads) {
		AString name = RT.ensureString(RT.getIn(toolInput, Fields.NAME));
		AString ref = RT.ensureString(RT.getIn(toolInput, AbstractLLMAdapter.K_REF));
		if ((name == null) == (ref == null)) {
			throw new IllegalArgumentException("skill_load requires exactly one of 'name' or 'ref'");
		}
		ResolvedSkill skill = (ref != null)
			? resolveRef(engine, ctx, ref)
			: resolveByName(engine, ctx, sources, name.toString());

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
				+ "Tools are active from your next step."));
		if (toolNames.count() > 0) result = result.assoc(Fields.TOOLS, toolNames);
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
