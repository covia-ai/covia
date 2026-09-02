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
 * The context scope chain (#142): agent ({@code config.loads}) → frame
 * ({@code frame.loads}), with lexical-scoping semantics. A flat LLM agent has
 * one root frame; a goal-tree agent pushes child frames.
 *
 * <p>Each tier is a map {@code path → spec}, where spec is a map (budget,
 * ts, label…) or <b>nil — a legacy tombstone</b> masking an outer tier's entry
 * from that tier inward. Rules:</p>
 * <ul>
 *   <li><b>Assembly</b> = union outer→inner; inner shadows outer; nil masks.</li>
 *   <li><b>Mutation targets the innermost tier only</b> — outer tiers are
 *       environment, never workspace.</li>
 *   <li>The agent tier is principal-authored ({@code config.loads}) and never
 *       pruned; dynamic tiers prune innermost-first.</li>
 * </ul>
 *
 * <p>All methods are pure functions on immutable maps. The model-facing
 * harness applies ownership on top: it removes only local agent-managed
 * entries and never calls {@link #unload} to mask pinned context. The tombstone
 * algebra remains for backward-compatible stored state and internal callers.</p>
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
	 * Low-level lexical masking operation retained for legacy/internal callers:
	 * unloads a path from the innermost tier. The local
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
	 * Parses an untrusted tier's declared loads ({@code {path: {budget?, label?}}}),
	 * such as a session-mint {@code loads} param. Absent →
	 * empty; a non-map value or a non-map spec is a configuration error and
	 * throws loudly (a malformed declaration must not be silently dropped).
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> declaredLoads(ACell raw, String which) {
		return declaredLoads(raw, which, false);
	}

	/**
	 * Parses operator-owned agent configuration. Non-skill data is trusted by
	 * default, while an entry may explicitly choose {@code trusted: false}.
	 * The marker is written before scope-chain merging so caller-owned tiers can
	 * never acquire operator authority merely by also being pinned.
	 */
	public static AMap<AString, ACell> operatorLoads(ACell raw, String which) {
		return declaredLoads(raw, which, false, true);
	}

	/**
	 * Parses and normalises a declared loads tier. Budgets use the same
	 * advisory range/default as runtime context_load; labels and timestamps
	 * are type-checked, and a declared source ({@code ref}/{@code text}/
	 * {@code op}/{@code job}) or placement ({@code volatile}) is validated by
	 * {@link Loads#validateSpec}. A missing timestamp is stamped only at a
	 * persistence boundary (for example session mint), never during ordinary
	 * rendering — otherwise a stable declaration would appear newest on every
	 * inference.
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> declaredLoads(ACell raw, String which,
			boolean stampMissingTimestamp) {
		return declaredLoads(raw, which, stampMissingTimestamp, false);
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> declaredLoads(ACell raw, String which,
			boolean stampMissingTimestamp, boolean operatorOwned) {
		if (raw == null) return Maps.empty();
		if (!(raw instanceof AMap)) {
			throw new IllegalArgumentException(which + " must be a map of key → {budget?, …}, got "
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
			Loads.validateSpec(meta, which, entry.getKey());
			long budget = AbstractLLMAdapter.clampLoadBudget(meta.get(budgetKey));
			meta = meta.assoc(budgetKey, CVMLong.create(budget));
			if (ts == null && stampMissingTimestamp) {
				meta = meta.assoc(tsKey, CVMLong.create(convex.core.util.Utils.getCurrentTimestamp()));
			}
			// Every declared tier is external to the agent's mutable working set.
			// Trust is a separate authority marker: operator config may opt out,
			// while caller/session declarations may never opt themselves in.
			meta = meta.assoc(Loads.K_AGENT_MANAGED, convex.core.data.prim.CVMBool.FALSE);
			ACell declaredTrust = meta.get(Loads.K_TRUSTED);
			boolean trusted = operatorOwned
				&& !convex.core.data.prim.CVMBool.FALSE.equals(declaredTrust);
			meta = meta.assoc(Loads.K_TRUSTED,
				convex.core.data.prim.CVMBool.create(trusted));
			normalised = normalised.assoc(entry.getKey(), meta);
		}
		return normalised;
	}

	/**
	 * Returns the root frame's loads from a session map. Until the session next
	 * starts a cycle, this also folds in the legacy sibling {@code session.loads}
	 * tier so read-only inspection has the same view as the atomic migration.
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> sessionRootLoads(ACell session) {
		AMap<AString, ACell> legacy = (RT.getIn(session, covia.api.Fields.LOADS) instanceof AMap lm)
			? (AMap<AString, ACell>) lm : Maps.empty();
		AMap<AString, ACell> root = Maps.empty();
		ACell frames = RT.getIn(session, covia.api.Fields.FRAMES);
		if (frames instanceof convex.core.data.AVector<?> fv) {
			root = GoalTreeContext.rootLoads((convex.core.data.AVector<ACell>) fv);
		}
		return effective(legacy, root);
	}
}
