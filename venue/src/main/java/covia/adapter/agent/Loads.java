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
 * The loads phase of assembly (AGENT_CONTEXT.md §4): explicit persistent
 * loads resolve into an appended event once, while declared volatile loads
 * resolve per inference. Tool contributions and dispatch routes are derived
 * alongside the active loads view.
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
 * system element. An operator-stamped trusted entry does likewise. Every
 * untrusted value — the agent's own workspace included — renders inside one
 * aggregate {@code pinned_context} or {@code loaded_context} tool result for
 * its band. A skill's own context entries remain data.</p>
 *
 * <p><b>Placement.</b> An entry is <i>volatile</i> when it declares
 * {@code volatile: true}, or is an {@code op} entry and does not declare
 * {@code volatile: false}. Volatile entries render in the tail, after the
 * conversation, so their per-inference changes bust only themselves; every
 * other entry renders in the live surface. A volatile result also sits
 * between the latest input and the reply and is re-sent uncached every
 * inference, so it renders <b>within its budget whatever its shape</b>: a
 * structured value through the explorer as always, a string cut at the
 * budget with a visible trailer. Resolution runs in load order — oldest
 * first, undated (configured) entries before dated ones. Pinned entries keep
 * that vector order; agent-managed entries are keyed in a map so their exact
 * unload handles are unambiguous.</p>
 *
 * <p>Agent-managed, non-volatile entries are resolved once and persisted as
 * conversation events. Operator-pinned and legacy unmarked entries still form
 * an ephemeral snapshot immediately before each provider call; volatile
 * entries deliberately do so on every call.</p>
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
	/** Instruction authority, stamped by the declaration boundary. */
	static final AString K_TRUSTED  = Strings.intern("trusted");
	/** Ownership marker written only by agent-side context/skill loads. */
	static final AString K_AGENT_MANAGED = Strings.intern("agentManaged");
	/** The non-volatile value has already been appended to conversation. */
	static final AString K_APPENDED = Strings.intern("appended");

	private static final AString[] SOURCE_KEYS = { K_REF, K_TEXT, K_OP, K_JOB };
	private static final AString[] ENTRY_KEYS  = { K_REF, K_TEXT, K_OP, K_INPUT, K_JOB, K_PATH, K_REQUIRED };

	/**
	 * One inference's active-load view: unappended instructions as system elements,
	 * unappended data loads as aggregate-ready entries, volatile elements for the
	 * tail, and the tools contributed by every active load. Entries already
	 * persisted in conversation contribute metadata and tools, but no duplicate
	 * content elements.
	 */
	public record Snapshot(AVector<ACell> instructionElements, AVector<ACell> exchanges,
			AVector<ACell> volatileElements, AVector<ACell> tools, Map<String, AString> routes,
			AVector<ACell> diagnostics, AVector<ACell> toolProvenance) {
		public static final Snapshot EMPTY = new Snapshot(null, null, null, null, null, null, null);

		public Snapshot {
			instructionElements = (instructionElements != null) ? instructionElements : Vectors.empty();
			exchanges = (exchanges != null) ? exchanges : Vectors.empty();
			volatileElements = (volatileElements != null) ? volatileElements : Vectors.empty();
			tools = (tools != null) ? tools : Vectors.empty();
			routes = (routes != null) ? Map.copyOf(routes) : Map.of();
			diagnostics = (diagnostics != null) ? diagnostics : Vectors.empty();
			toolProvenance = (toolProvenance != null) ? toolProvenance : Vectors.empty();
		}

		/** The live surface in provider-message form. */
		@SuppressWarnings("unchecked")
		public AVector<ACell> elements() {
			return (AVector<ACell>) instructionElements.concat(
				ContextAssembler.contextExchanges(exchanges, false));
		}
	}

	/** One explicit load rendered once, plus the tier carrying its append marker. */
	public record Append(AMap<AString, ACell> loads, AVector<ACell> messages) {}

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
		ACell trusted = spec.get(K_TRUSTED);
		if (trusted != null && !(trusted instanceof CVMBool)) {
			throw new IllegalArgumentException(which + " entry '" + key + "' trusted must be a boolean, got "
				+ trusted.getClass().getSimpleName());
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
	 * True when the entry belongs to the agent-managed working set and may be
	 * removed with {@code context_unload}. Configured and session-mint entries
	 * are pinned. The timestamp fallback keeps pre-marker dynamic loads
	 * unloadable across an upgrade; new persistence boundaries always write the
	 * ownership marker explicitly.
	 */
	public static boolean isAgentManaged(ACell spec) {
		if (!(spec instanceof AMap<?, ?> m)) return false;
		ACell marked = m.get(K_AGENT_MANAGED);
		if (marked instanceof CVMBool b) return b.booleanValue();
		return m.get(K_TS) != null;
	}

	/** True only when a trusted declaration boundary stamped this data load. */
	static boolean isTrusted(ACell spec) {
		return !isAgentManaged(spec)
			&& spec instanceof AMap<?, ?> m && CVMBool.TRUE.equals(m.get(K_TRUSTED));
	}

	/** True when at least one visible entry in a tier is agent-managed. */
	static boolean hasAgentManaged(AMap<AString, ACell> loads) {
		if (loads == null) return false;
		for (var entry : loads.entrySet()) {
			if (entry.getValue() != null && isAgentManaged(entry.getValue())) return true;
		}
		return false;
	}

	/**
	 * The context entry a non-skill load resolves through: the key itself (a
	 * reference), or the map entry the spec declares — carrying the spec's
	 * label when it gives one.
	 */
	static ACell entryFor(AString key, AMap<AString, ACell> spec) {
		if (!declaresSource(spec)) {
			// The compact string form is enough unless the declaration modifies
			// how that default reference is presented or whether it is required.
			AString label = RT.ensureString(spec.get(K_LABEL));
			ACell required = spec.get(K_REQUIRED);
			if (label == null && required == null) return key;
			AMap<AString, ACell> entry = Maps.of(K_REF, key);
			if (label != null) entry = entry.assoc(K_LABEL, label);
			if (required != null) entry = entry.assoc(K_REQUIRED, required);
			return entry;
		}
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
		return new Snapshot(resolved.instructions(), resolved.exchanges(), resolved.volatiles(), tools, routes,
			resolved.diagnostics(), vector(toolEntries));
	}

	/**
	 * Resolves one newly loaded non-volatile entry and marks it as appended in
	 * the same tier value that the runtime persists with the resulting turns.
	 * A later inference therefore retains the conversation event without
	 * reading the source again. Volatile entries deliberately stay unmarked and
	 * continue to resolve in the uncached tail.
	 */
	@SuppressWarnings("unchecked")
	static Append append(Engine engine, RequestContext ctx,
			AMap<AString, ACell> loads, AString key, AString dialect, AString eventId) {
		if (loads == null || key == null || !(loads.get(key) instanceof AMap<?, ?> raw)) {
			return new Append((loads != null) ? loads : Maps.empty(), Vectors.empty());
		}
		AMap<AString, ACell> meta = (AMap<AString, ACell>) raw;
		if (isVolatile(meta)) return new Append(loads, Vectors.empty());
		Resolved resolved = resolveElements(engine, ctx, Maps.of(key, meta), dialect);
		AVector<ACell> messages = (AVector<ACell>) resolved.instructions().concat(
			ContextAssembler.contextExchanges(resolved.exchanges(), false, eventId));
		return new Append(loads.assoc(key, meta.assoc(K_APPENDED, CVMBool.TRUE)), messages);
	}

	/**
	 * The live surface every loads entry renders to — instruction elements, then
	 * aggregate-ready entries — in load order. This is the single place that knows about
	 * entry kinds:
	 * <ul>
	 *   <li><b>Skill entries</b> ({@code skill: true}): an unapplied entry resolves
	 *       from the entry key and renders as a system skill element plus its
	 *       body; the skill's own context entries follow as data entries from
	 *       the skill. Failures are <b>visible</b> — a skill the agent loaded
	 *       must not silently disappear. A skill reached under two addresses
	 *       renders once (content-identity dedup).</li>
	 *   <li><b>Everything else</b>: standard context-entry resolution of the
	 *       key, or of the source the spec declares. Operator-trusted values are
	 *       system instructions; all others are tool-result entries. Optional
	 *       pinned absence remains visible; dynamic absence is skipped.</li>
	 * </ul>
	 * Entries already appended are skipped without reading their source. The
	 * volatile entries are on the {@link Snapshot} (or {@link #volatileElements}).
	 */
	public static AVector<ACell> elements(Engine engine, RequestContext ctx,
			AMap<AString, ACell> loads, AString dialect) {
		Resolved r = resolveElements(engine, ctx, loads, dialect);
		return r.instructions().concat(r.exchanges());
	}

	/** Trusted messages and aggregate-ready data entries for the tail — see {@link #isVolatile}. */
	public static AVector<ACell> volatileElements(Engine engine, RequestContext ctx,
			AMap<AString, ACell> loads, AString dialect) {
		return resolveElements(engine, ctx, loads, dialect).volatiles();
	}

	private record Resolved(AVector<ACell> instructions, AVector<ACell> exchanges, AVector<ACell> volatiles,
			AVector<ACell> diagnostics) {}

	private record Element(AVector<ACell> instructions, AVector<ACell> exchanges,
			String status, boolean deduplicated) {}

	@SuppressWarnings("unchecked")
	private static Resolved resolveElements(Engine engine, RequestContext ctx,
			AMap<AString, ACell> loads, AString dialect) {
		AVector<ACell> instructions = Vectors.empty();
		AVector<ACell> exchanges = Vectors.empty();
		AVector<ACell> volatiles = Vectors.empty();
		AVector<ACell> diagnostics = Vectors.empty();
		if (loads == null || loads.count() == 0) return new Resolved(instructions, exchanges, volatiles, diagnostics);
		ContextLoader loader = new ContextLoader(engine);
		Set<convex.core.data.Hash> seenSkillIds = new HashSet<>();
		for (Map.Entry<AString, ACell> entry : ordered(loads)) {
			AString key = entry.getKey();
			AMap<AString, ACell> meta = (AMap<AString, ACell>) entry.getValue();
			long entryBudget = AbstractLLMAdapter.clampLoadBudget(meta.get(AbstractLLMAdapter.K_BUDGET));
			boolean skill = Skills.isSkillEntry(meta);
			boolean tail = isVolatile(meta);
			boolean agentManaged = isAgentManaged(meta);
			if (!tail && CVMBool.TRUE.equals(meta.get(K_APPENDED))) {
				diagnostics = diagnostics.conj(Maps.of(
					Fields.REF, key,
					K_KIND, Strings.create(skill ? "skill" : "load"),
					K_BAND, BAND_LIVE,
					Fields.BYTES, CVMLong.ZERO,
					AbstractLLMAdapter.K_BUDGET, CVMLong.create(entryBudget),
					K_STATUS, Strings.create("appended"),
					K_TRUNCATED, CVMBool.FALSE,
					K_DEDUPLICATED, CVMBool.FALSE));
				continue;
			}
			loader.beginTrace(entryBudget);
			Element resolved = element(engine, ctx, loader, key, meta, seenSkillIds,
				dialect, agentManaged);
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
				AVector<ACell> trusted = Vectors.empty();
				for (long i = 0; i < resolved.instructions().count(); i++) {
					ACell msg = resolved.instructions().get(i);
					ACell cut = capped(msg, entryBudget);
					if (cut != msg) capped = true;
					trusted = trusted.conj(cut);
				}
				volatiles = (AVector<ACell>) volatiles.concat(trusted).concat(messages);
			} else {
				instructions = (AVector<ACell>) instructions.concat(resolved.instructions());
				exchanges = (AVector<ACell>) exchanges.concat(messages);
			}
			long bytes = 0;
			for (long i = 0; i < resolved.instructions().count(); i++) bytes += ContextAssembler.bytes(resolved.instructions().get(i));
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
		return new Resolved(instructions, exchanges, volatiles, diagnostics);
	}

	@SuppressWarnings("unchecked")
	private static Element element(Engine engine, RequestContext ctx, ContextLoader loader,
			AString key, AMap<AString, ACell> meta, Set<convex.core.data.Hash> seenSkillIds,
			AString dialect, boolean agentManaged) {
		if (!Skills.isSkillEntry(meta)) {
			boolean trusted = isTrusted(meta);
			ContextLoader.Resolved r = loader.resolveValue(entryFor(key, meta), ctx, !agentManaged);
			if (r == null) {
				return new Element(Vectors.empty(), Vectors.empty(),
					loader.resolution().name().toLowerCase(), false);
			}
			if (trusted) {
				return new Element(Vectors.of(
					ContextAssembler.trustedContextMessage(dialect, r, key)), Vectors.empty(),
					loader.resolution().name().toLowerCase(), false);
			}
			return new Element(Vectors.empty(),
				Vectors.of(ContextAssembler.contextEntry(key, !agentManaged, r)),
				loader.resolution().name().toLowerCase(), false);
		}
		try {
			Skills.ResolvedSkill skill = Skills.resolveRef(engine, ctx, key);
			if (skill.id() != null && !seenSkillIds.add(skill.id())) {
				return new Element(Vectors.empty(), Vectors.empty(), "deduplicated", true);
			}
			AVector<ACell> body = Vectors.of(
				Skills.renderSkillMessage(dialect, skill.name(), key, skill.displayBody(), agentManaged));
			// The skill's own context entries are data it brings along: exchanges from the skill.
			AVector<ACell> exchanges = Vectors.empty();
			for (long i = 0; i < skill.contextEntries().count(); i++) {
				ContextLoader.Resolved r = loader.resolveValue(skill.contextEntries().get(i), ctx);
				if (r == null) continue;
				exchanges = exchanges.conj(ContextAssembler.contextEntry(key, !agentManaged, r));
			}
			return new Element(body, exchanges, "resolved", false);
		} catch (RuntimeException e) {
			AString label = RT.ensureString(meta.get(AbstractLLMAdapter.K_LABEL));
			return new Element(Vectors.of(Skills.skillErrorMessage(dialect,
				(label != null) ? label.toString() : key.toString(), key,
				ContextLoader.rootMessage(e), agentManaged)), Vectors.empty(), "unavailable", false);
		}
	}

	/**
	 * A volatile result cut to its budget (UTF-8 bytes of content), with one
	 * trailer naming what was left out and the two ways to get it. The same
	 * message when it already fits, or when it carries no content (the call
	 * metadata fields untouched. Never splits a surrogate pair.
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
