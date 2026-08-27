package covia.adapter.agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * The loads phase of assembly (AGENT_CONTEXT.md §4): the effective loads
 * chain resolved once per inference into what the model sees, the tools
 * those loads contribute, and the dispatch routes of those tools — together,
 * so a caller can never refresh one without the others.
 *
 * <p><b>Entry shape.</b> A loads entry is {@code key → spec}. By default the
 * key is the reference the entry renders (a lattice path, an asset, a
 * content ref). A spec may instead declare its own source — exactly one of
 * {@code ref}, {@code text}, {@code op} (+ {@code input}) or {@code job}
 * (+ {@code path}) — in which case the key is just the entry's identity: the
 * argument {@code context_unload} takes. The forms are the entry grammar of
 * §6.2; a loads tier is the same grammar keyed for unloading.</p>
 *
 * <p><b>Shape in the prompt.</b> A skill is instruction and renders as a
 * system element. Everything else a load brings in is data — the agent's
 * own workspace included — and renders as a <i>tool exchange</i>
 * ({@link ContextAssembler#exchange}): a {@code loaded_context} call naming
 * the key, the source and where it was declared, and the tool result
 * carrying the content. A skill's own context entries render the same way,
 * from the skill.</p>
 *
 * <p><b>Placement.</b> An entry is <i>volatile</i> when it declares
 * {@code volatile: true}, or is an {@code op} entry and does not declare
 * {@code volatile: false}. Volatile exchanges render in the tail, after the
 * conversation, so their per-inference changes bust only themselves; every
 * other exchange renders in the live surface. A volatile result also sits
 * between the latest input and the reply and is re-sent uncached every
 * inference, so it renders <b>within its budget whatever its shape</b>: a
 * structured value through the explorer as always, a string cut at the
 * budget with a visible trailer. Within a band, entries render in load order
 * — oldest first, undated (configured) entries before dated ones — so a new
 * load appends after everything already in context instead of landing
 * wherever its key hashes.</p>
 *
 * <p>The snapshot is ephemeral: resolved immediately before every provider
 * call under the agent's capability-narrowed authority, never persisted.</p>
 */
public final class Loads {

	private Loads() {}

	private static final AString K_KIND         = Strings.intern("kind");
	private static final AString K_STATUS       = Strings.intern("status");
	private static final AString K_TRUNCATED    = Strings.intern("truncated");
	private static final AString K_DEDUPLICATED = Strings.intern("deduplicated");
	private static final AString K_BAND         = Strings.intern("band");
	private static final AString BAND_LIVE      = Strings.intern("live");
	private static final AString BAND_TAIL      = Strings.intern("tail");

	/** The entry-source fields a spec may declare (AGENT_CONTEXT.md §6.2). */
	static final AString K_REF      = Fields.REF;
	static final AString K_TEXT     = Fields.TEXT;
	static final AString K_OP       = Fields.OP;
	static final AString K_INPUT    = Fields.INPUT;
	static final AString K_JOB      = Strings.intern("job");
	static final AString K_PATH     = Fields.PATH;
	static final AString K_REQUIRED = Fields.REQUIRED;
	static final AString K_LABEL    = Fields.LABEL;
	/** Placement: tail (never cached) rather than live surface. */
	static final AString K_VOLATILE = Strings.intern("volatile");
	static final AString K_TS       = Strings.intern("ts");

	private static final AString[] SOURCE_KEYS = { K_REF, K_TEXT, K_OP, K_JOB };
	private static final AString[] ENTRY_KEYS  = { K_REF, K_TEXT, K_OP, K_INPUT, K_JOB, K_PATH, K_REQUIRED };

	/**
	 * One inference's view of the effective loads: the loaded skills as
	 * system elements, every other live load as a tool exchange, the volatile
	 * exchanges for the tail, and the tools the loads contribute.
	 */
	public record Snapshot(AVector<ACell> skillElements, AVector<ACell> exchanges,
			AVector<ACell> volatileExchanges, AVector<ACell> tools, Map<String, AString> routes,
			AVector<ACell> diagnostics, AVector<ACell> toolProvenance) {
		public static final Snapshot EMPTY = new Snapshot(null, null, null, null, null, null, null);

		public Snapshot {
			skillElements = (skillElements != null) ? skillElements : Vectors.empty();
			exchanges = (exchanges != null) ? exchanges : Vectors.empty();
			volatileExchanges = (volatileExchanges != null) ? volatileExchanges : Vectors.empty();
			tools = (tools != null) ? tools : Vectors.empty();
			routes = (routes != null) ? Map.copyOf(routes) : Map.of();
			diagnostics = (diagnostics != null) ? diagnostics : Vectors.empty();
			toolProvenance = (toolProvenance != null) ? toolProvenance : Vectors.empty();
		}

		/** The live surface in order: the skill elements, then the exchanges. */
		@SuppressWarnings("unchecked")
		public AVector<ACell> elements() {
			return (AVector<ACell>) skillElements.concat(exchanges);
		}
	}

	// ========== Entry specs ==========

	/**
	 * Validates a spec's declared source and placement — at most one of
	 * {@code ref}/{@code text}/{@code op}/{@code job}, each a string;
	 * {@code input} only with {@code op}; {@code volatile} a boolean. A bad
	 * declaration is a configuration error and throws with the entry named.
	 */
	public static void validateSpec(AMap<AString, ACell> spec, String which, AString key) {
		int forms = 0;
		for (AString k : SOURCE_KEYS) {
			ACell v = spec.get(k);
			if (v == null) continue;
			forms++;
			if (RT.ensureString(v) == null) {
				throw new IllegalArgumentException(which + " entry '" + key + "' " + k
					+ " must be a string, got " + v.getClass().getSimpleName());
			}
		}
		if (forms > 1) {
			throw new IllegalArgumentException(which + " entry '" + key
				+ "' must declare at most one of ref, text, op, job");
		}
		if (spec.get(K_INPUT) != null && spec.get(K_OP) == null) {
			throw new IllegalArgumentException(which + " entry '" + key + "' input needs an op");
		}
		ACell vol = spec.get(K_VOLATILE);
		if (vol != null && !(vol instanceof CVMBool)) {
			throw new IllegalArgumentException(which + " entry '" + key + "' volatile must be a boolean, got "
				+ vol.getClass().getSimpleName());
		}
	}

	/** True when the spec declares its own source rather than rendering its key. */
	public static boolean declaresSource(AMap<AString, ACell> spec) {
		for (AString k : SOURCE_KEYS) {
			if (spec.get(k) != null) return true;
		}
		return false;
	}

	/**
	 * True when the entry renders in the tail: declared {@code volatile: true},
	 * or an {@code op} entry (re-run every inference) that does not declare
	 * {@code volatile: false}.
	 */
	public static boolean isVolatile(ACell spec) {
		if (!(spec instanceof AMap<?, ?> m)) return false;
		ACell v = m.get(K_VOLATILE);
		if (v instanceof CVMBool b) return b.booleanValue();
		return m.get(K_OP) != null;
	}

	/**
	 * The context entry a non-skill load resolves through: the key itself (a
	 * reference), or the map entry the spec declares — carrying the spec's
	 * label when it gives one.
	 */
	static ACell entryFor(AString key, AMap<AString, ACell> spec) {
		if (!declaresSource(spec)) return key;
		AMap<AString, ACell> entry = Maps.empty();
		for (AString k : ENTRY_KEYS) {
			ACell v = spec.get(k);
			if (v != null) entry = entry.assoc(k, v);
		}
		AString label = RT.ensureString(spec.get(K_LABEL));
		return (label != null) ? entry.assoc(K_LABEL, label) : entry;
	}

	/**
	 * The effective loads in render order: by load time, oldest first — an
	 * undated (configured) entry before any dated one — then by key. Loading
	 * therefore appends; nothing already in context moves.
	 */
	static List<Map.Entry<AString, ACell>> ordered(AMap<AString, ACell> loads) {
		List<Map.Entry<AString, ACell>> out = new ArrayList<>();
		if (loads == null) return out;
		for (Map.Entry<AString, ACell> e : loads.entrySet()) out.add(e);
		out.sort(Comparator.comparingLong((Map.Entry<AString, ACell> e) -> tsOf(e.getValue()))
			.thenComparing(e -> e.getKey().toString()));
		return out;
	}

	private static long tsOf(ACell spec) {
		return (spec instanceof AMap<?, ?> m && m.get(K_TS) instanceof CVMLong l) ? l.longValue() : 0L;
	}

	/** Where an entry was declared: the agent tier is undated, a dynamic tier is stamped. */
	private static String fromOf(AMap<AString, ACell> meta) {
		return (meta.get(K_TS) == null) ? ContextAssembler.FROM_CONFIG_LOADS : ContextAssembler.FROM_LOADED;
	}

	// ========== Resolution ==========

	/**
	 * Resolves the effective loads. Contributed tools are deduplicated against
	 * {@code fixedNames} — the harness and configured tools, which a load may
	 * never shadow.
	 */
	public static Snapshot resolve(Engine engine, RequestContext ctx,
			AMap<AString, ACell> effectiveLoads, Set<String> fixedNames, AString dialect) {
		if (effectiveLoads == null || effectiveLoads.count() == 0) return Snapshot.EMPTY;
		Map<String, AString> routes = new HashMap<>();
		List<AMap<AString, ACell>> toolEntries = new ArrayList<>();
		AMap<AString, ACell> toolLoads = Skills.resolveLoadTools(engine, ctx, effectiveLoads);
		AVector<ACell> tools = ToolPalette.loadsToolDefs(
			engine, ctx, toolLoads, fixedNames, routes, toolEntries);
		Resolved resolved = resolveElements(engine, ctx, effectiveLoads, dialect);
		return new Snapshot(resolved.skills(), resolved.exchanges(), resolved.volatiles(), tools, routes,
			resolved.diagnostics(), vector(toolEntries));
	}

	/**
	 * The live surface every loads entry renders to — skill elements, then
	 * exchanges — in load order. This is the single place that knows about
	 * entry kinds:
	 * <ul>
	 *   <li><b>Skill entries</b> ({@code skill: true}): the skill re-resolves
	 *       from the entry key and renders as a system skill element plus its
	 *       body; the skill's own context entries follow as exchanges from
	 *       the skill. Failures are <b>visible</b> — a skill the agent loaded
	 *       must not silently disappear. A skill reached under two addresses
	 *       renders once (content-identity dedup).</li>
	 *   <li><b>Everything else</b>: standard context-entry resolution of the
	 *       key, or of the source the spec declares, rendered as one tool
	 *       exchange — absent → skipped, error → a tool error.</li>
	 * </ul>
	 * The volatile exchanges are on the {@link Snapshot} (or {@link #volatileElements}).
	 */
	public static AVector<ACell> elements(Engine engine, RequestContext ctx,
			AMap<AString, ACell> loads, AString dialect) {
		Resolved r = resolveElements(engine, ctx, loads, dialect);
		return r.skills().concat(r.exchanges());
	}

	/** The exchanges that render in the tail — see {@link #isVolatile}. */
	public static AVector<ACell> volatileElements(Engine engine, RequestContext ctx,
			AMap<AString, ACell> loads, AString dialect) {
		return resolveElements(engine, ctx, loads, dialect).volatiles();
	}

	private record Resolved(AVector<ACell> skills, AVector<ACell> exchanges, AVector<ACell> volatiles,
			AVector<ACell> diagnostics) {}

	private record Element(AVector<ACell> skill, AVector<ACell> exchanges, String status, boolean deduplicated) {}

	@SuppressWarnings("unchecked")
	private static Resolved resolveElements(Engine engine, RequestContext ctx,
			AMap<AString, ACell> loads, AString dialect) {
		AVector<ACell> skills = Vectors.empty();
		AVector<ACell> exchanges = Vectors.empty();
		AVector<ACell> volatiles = Vectors.empty();
		AVector<ACell> diagnostics = Vectors.empty();
		if (loads == null || loads.count() == 0) return new Resolved(skills, exchanges, volatiles, diagnostics);
		ContextLoader loader = new ContextLoader(engine, dialect);
		Set<convex.core.data.Hash> seenSkillIds = new HashSet<>();
		long ordinal = 0;
		for (Map.Entry<AString, ACell> entry : ordered(loads)) {
			AString key = entry.getKey();
			AMap<AString, ACell> meta = (AMap<AString, ACell>) entry.getValue();
			long entryBudget = AbstractLLMAdapter.clampLoadBudget(meta.get(AbstractLLMAdapter.K_BUDGET));
			loader.beginTrace(entryBudget);
			boolean skill = Skills.isSkillEntry(meta);
			boolean tail = isVolatile(meta);
			Element resolved = element(engine, ctx, loader, key, meta, seenSkillIds, dialect, ordinal++);
			boolean capped = false;
			AVector<ACell> messages = resolved.exchanges();
			if (tail && !skill) {
				// The tail is re-sent uncached every inference and sits between
				// the input and the reply: a volatile result renders within its
				// budget whatever its shape. Structured values already do (the
				// explorer); strings are cut here with a visible trailer.
				AVector<ACell> bounded = Vectors.empty();
				for (long i = 0; i < messages.count(); i++) {
					ACell msg = messages.get(i);
					ACell cut = capped(msg, entryBudget);
					if (cut != msg) capped = true;
					bounded = bounded.conj(cut);
				}
				messages = bounded;
				volatiles = (AVector<ACell>) volatiles.concat(messages);
			} else {
				skills = (AVector<ACell>) skills.concat(resolved.skill());
				exchanges = (AVector<ACell>) exchanges.concat(messages);
			}
			long bytes = 0;
			for (long i = 0; i < resolved.skill().count(); i++) bytes += ContextAssembler.bytes(resolved.skill().get(i));
			for (long i = 0; i < messages.count(); i++) bytes += ContextAssembler.bytes(messages.get(i));
			diagnostics = diagnostics.conj(Maps.of(
				Fields.REF, key,
				K_KIND, Strings.create(skill ? "skill" : "load"),
				K_BAND, tail ? BAND_TAIL : BAND_LIVE,
				Fields.BYTES, CVMLong.create(bytes),
				AbstractLLMAdapter.K_BUDGET, CVMLong.create(entryBudget),
				K_STATUS, Strings.create(resolved.status()),
				K_TRUNCATED, CVMBool.create(loader.wasTruncated() || capped),
				K_DEDUPLICATED, CVMBool.create(resolved.deduplicated())));
		}
		return new Resolved(skills, exchanges, volatiles, diagnostics);
	}

	@SuppressWarnings("unchecked")
	private static Element element(Engine engine, RequestContext ctx, ContextLoader loader,
			AString key, AMap<AString, ACell> meta, Set<convex.core.data.Hash> seenSkillIds,
			AString dialect, long ordinal) {
		if (!Skills.isSkillEntry(meta)) {
			ContextLoader.Resolved r = loader.resolveValue(entryFor(key, meta), ctx);
			AVector<ACell> messages = (r != null)
				? ContextAssembler.exchange(key, fromOf(meta), r, ordinal) : Vectors.empty();
			return new Element(Vectors.empty(), messages, loader.resolution().name().toLowerCase(), false);
		}
		try {
			Skills.ResolvedSkill skill = Skills.resolveRef(engine, ctx, key);
			if (skill.id() != null && !seenSkillIds.add(skill.id())) {
				return new Element(Vectors.empty(), Vectors.empty(), "deduplicated", true);
			}
			AVector<ACell> body = Vectors.of(
				Skills.renderSkillMessage(dialect, skill.name(), key, skill.displayBody()));
			// The skill's own context entries are data it brings along: exchanges from the skill.
			AVector<ACell> exchanges = Vectors.empty();
			String from = "skill:" + skill.name();
			for (long i = 0; i < skill.contextEntries().count(); i++) {
				ContextLoader.Resolved r = loader.resolveValue(skill.contextEntries().get(i), ctx);
				if (r == null) continue;
				exchanges = (AVector<ACell>) exchanges.concat(ContextAssembler.exchange(key, from, r, ordinal * 1000 + i));
			}
			return new Element(body, exchanges, "resolved", false);
		} catch (RuntimeException e) {
			AString label = RT.ensureString(meta.get(AbstractLLMAdapter.K_LABEL));
			return new Element(Vectors.of(Skills.skillErrorMessage(dialect,
				(label != null) ? label.toString() : key.toString(), key,
				ContextLoader.rootMessage(e))), Vectors.empty(), "unavailable", false);
		}
	}

	/**
	 * A volatile result cut to its budget (UTF-8 bytes of content), with one
	 * trailer naming what was left out and the two ways to get it. The same
	 * message when it already fits, or when it carries no content (the call
	 * half of an exchange). Never splits a surrogate pair.
	 */
	static ACell capped(ACell msg, long budget) {
		AString content = RT.ensureString(RT.getIn(msg, AbstractLLMAdapter.K_CONTENT));
		if (content == null) return msg;
		String s = content.toString();
		long size = s.getBytes(StandardCharsets.UTF_8).length;
		if (size <= budget) return msg;
		int cut = (int) Math.min(s.length(), budget);           // chars ≤ bytes, so a safe upper bound
		while (cut > 0 && s.substring(0, cut).getBytes(StandardCharsets.UTF_8).length > budget) cut--;
		if (cut > 0 && cut < s.length() && Character.isHighSurrogate(s.charAt(cut - 1))) cut--;
		String trailer = "\n… (" + (size - s.substring(0, cut).getBytes(StandardCharsets.UTF_8).length)
			+ " more bytes beyond this entry's budget of " + budget
			+ " — reload it with a larger budget, or fetch the value with a tool)";
		return RT.ensureMap(msg).assoc(AbstractLLMAdapter.K_CONTENT, Strings.create(s.substring(0, cut) + trailer));
	}

	private static AVector<ACell> vector(List<? extends ACell> cells) {
		AVector<ACell> out = Vectors.empty();
		for (ACell cell : cells) out = out.conj(cell);
		return out;
	}
}
