package covia.adapter.agent;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.data.type.Types;
import convex.core.lang.RT;

/**
 * The context scope chain (#142): agent ({@code config.loads}) → session
 * ({@code sessions.<sid>.loads}) → frame ({@code frame.loads}, goaltree),
 * with lexical-scoping semantics.
 *
 * <p>Each tier is a map {@code path → spec}, where spec is a map (budget,
 * ts, label…) or <b>nil — a tombstone</b> masking an outer tier's entry from
 * that tier inward. Rules:</p>
 * <ul>
 *   <li><b>Assembly</b> = union outer→inner; inner shadows outer; nil masks.</li>
 *   <li><b>Mutation targets the innermost tier only</b> — outer tiers are
 *       environment, never workspace ({@link #unload} writes a tombstone when
 *       the path is supplied by an outer tier).</li>
 *   <li>The agent tier is principal-authored ({@code config.loads}) and never
 *       pruned; dynamic tiers prune innermost-first.</li>
 * </ul>
 *
 * <p>All methods are pure functions on immutable maps.</p>
 */
public class ContextChain {

	private ContextChain() {}

	/**
	 * Assembles the effective loads over the chain, outer tiers first. A nil
	 * spec at any tier masks the path from that tier inward; a non-nil spec
	 * shadows any outer entry. Null tiers are skipped.
	 */
	@SafeVarargs
	public static AMap<AString, ACell> effective(AMap<AString, ACell>... tiers) {
		AMap<AString, ACell> result = Maps.empty();
		for (AMap<AString, ACell> tier : tiers) {
			if (tier == null) continue;
			for (var entry : tier.entrySet()) {
				ACell spec = entry.getValue();
				result = (spec == null)
					? result.dissoc(entry.getKey())
					: result.assoc(entry.getKey(), spec);
			}
		}
		return result;
	}

	/**
	 * Unloads a path from the innermost tier per lexical rules: the local
	 * entry (if any) is removed; if the path is still supplied by an outer
	 * tier, a nil tombstone is written so the intent ("stop looking at this")
	 * holds from this tier inward while the outer entry — and every other
	 * session/frame — is untouched.
	 *
	 * @param inner          the innermost tier's loads
	 * @param effectiveOuter the assembled effective loads of all OUTER tiers
	 * @param path           the path to unload
	 * @return the updated inner tier, or {@code null} when the path is not in
	 *         the effective context at all (the caller reports a diagnosable
	 *         tool error)
	 */
	public static AMap<AString, ACell> unload(AMap<AString, ACell> inner,
			AMap<AString, ACell> effectiveOuter, AString path) {
		if (inner == null) inner = Maps.empty();
		boolean visibleHere = inner.get(path) != null;
		boolean maskedHere = !visibleHere && inner.containsKey(path);
		boolean visibleOuter = effectiveOuter != null && effectiveOuter.get(path) != null;
		// Not in effective context: neither loaded here nor (unmasked and) supplied outside.
		if (!visibleHere && (maskedHere || !visibleOuter)) return null;

		AMap<AString, ACell> updated = inner.dissoc(path);
		if (visibleOuter) updated = updated.assoc(path, null);
		return updated;
	}

	/**
	 * Parses a tier's declared loads ({@code {path: {budget?, label?}}}), e.g.
	 * {@code config.loads} or a session-mint {@code loads} param. Absent →
	 * empty; a non-map value or a non-map spec is a configuration error and
	 * throws loudly (a malformed declaration must not be silently dropped).
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> declaredLoads(ACell raw, String which) {
		return declaredLoads(raw, which, false);
	}

	/**
	 * Parses and normalises a declared loads tier. Budgets use the same
	 * advisory range/default as runtime context_load; labels and timestamps
	 * are type-checked. A missing timestamp is stamped only at a persistence
	 * boundary (for example session mint), never during ordinary rendering —
	 * otherwise a stable declaration would appear newest on every inference.
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> declaredLoads(ACell raw, String which,
			boolean stampMissingTimestamp) {
		if (raw == null) return Maps.empty();
		if (!(raw instanceof AMap)) {
			throw new IllegalArgumentException(which + " must be a map of path → {budget?}, got "
				+ Types.get(raw));
		}
		AMap<AString, ACell> loads = (AMap<AString, ACell>) raw;
		AMap<AString, ACell> normalised = Maps.empty();
		AString budgetKey = Strings.intern("budget");
		AString labelKey = Strings.intern("label");
		AString tsKey = Strings.intern("ts");
		for (var entry : loads.entrySet()) {
			ACell spec = entry.getValue();
			if (spec == null) {
				normalised = normalised.assoc(entry.getKey(), null);
				continue;
			}
			if (!(spec instanceof AMap)) {
				throw new IllegalArgumentException(which + " entry '" + entry.getKey()
					+ "' must be a map (e.g. {budget: 500}), got " + Types.get(spec));
			}
			AMap<AString, ACell> meta = (AMap<AString, ACell>) spec;
			ACell label = meta.get(labelKey);
			if (label != null && !(label instanceof AString)) {
				throw new IllegalArgumentException(which + " entry '" + entry.getKey()
					+ "' label must be a string, got " + Types.get(label));
			}
			ACell ts = meta.get(tsKey);
			if (ts != null && !(ts instanceof CVMLong)) {
				throw new IllegalArgumentException(which + " entry '" + entry.getKey()
					+ "' ts must be an integer, got " + Types.get(ts));
			}
			long budget = AbstractLLMAdapter.clampLoadBudget(meta.get(budgetKey));
			meta = meta.assoc(budgetKey, CVMLong.create(budget));
			if (ts == null && stampMissingTimestamp) {
				meta = meta.assoc(tsKey, CVMLong.create(convex.core.util.Utils.getCurrentTimestamp()));
			}
			normalised = normalised.assoc(entry.getKey(), meta);
		}
		return normalised;
	}

	/** The session tier from a transition input's session map, or empty. */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> sessionLoads(ACell input) {
		ACell loads = RT.getIn(input, covia.api.Fields.SESSION, covia.api.Fields.LOADS);
		return (loads instanceof AMap) ? (AMap<AString, ACell>) loads : Maps.empty();
	}
}
