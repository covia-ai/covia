package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.venue.RequestContext;

/**
 * Adapter providing pure data manipulation primitives over JSON / CVM data.
 *
 * <p>All operations are pure functions: no IO, no LLM, sub-millisecond. They
 * exist so that orchestrations can compose structured outputs and branch on
 * prior step results without needing custom adapters or LLM-based decision
 * logic in the data path.</p>
 *
 * <p>Operations:</p>
 * <ul>
 *   <li>{@code json:merge} — RFC 7396 deep merge of a vector of maps</li>
 *   <li>{@code json:cond}  — pick the value of the first truthy case</li>
 *   <li>{@code json:assoc} — set a value at a (possibly nested) path in a map</li>
 *   <li>{@code json:select} — pick a value from a map of cases by key</li>
 * </ul>
 *
 * <p>Truthiness rule (json:cond): {@code null} and JSON {@code false} are
 * falsy; everything else is truthy.</p>
 */
public class JSONAdapter extends AAdapter {

	private static final AString K_VALUES = Strings.intern("values");
	private static final AString K_RESULT = Strings.intern("result");
	private static final AString K_CASES = Strings.intern("cases");
	private static final AString K_DEFAULT = Strings.intern("default");
	private static final AString K_WHEN = Strings.intern("when");
	private static final AString K_THEN = Strings.intern("then");
	private static final AString K_KEY = Strings.intern("key");
	private static final AString K_TARGET = Strings.intern("target");
	private static final AString K_PATH = Strings.intern("path");
	private static final AString K_VALUE = Strings.intern("value");

	@Override
	public String getName() {
		return "json";
	}

	@Override
	public String getDescription() {
		return "Pure data manipulation primitives over JSON / CVM data. " +
			"Provides deep merge, conditional selection, path-based assoc, " +
			"and key-based selection. All operations are pure functions with " +
			"no IO and sub-millisecond execution. Use these to compose " +
			"structured outputs and branch on prior step results in " +
			"declarative orchestrations.";
	}

	@Override
	protected void installAssets() {
		installAsset("json/merge",  "/adapters/json/merge.json");
		installAsset("json/cond",   "/adapters/json/cond.json");
		installAsset("json/assoc",  "/adapters/json/assoc.json");
		installAsset("json/select", "/adapters/json/select.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String op = getSubOperation(meta);
		try {
			return switch (op) {
				case "merge"  -> CompletableFuture.completedFuture(handleMerge(input));
				case "cond"   -> CompletableFuture.completedFuture(handleCond(input));
				case "assoc"  -> CompletableFuture.completedFuture(handleAssoc(input));
				case "select" -> CompletableFuture.completedFuture(handleSelect(input));
				default -> CompletableFuture.failedFuture(
					new IllegalArgumentException("Unknown json operation: " + op));
			};
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	// ========================================================================
	// json:merge — RFC 7396 JSON Merge Patch deep merge
	// ========================================================================

	private ACell handleMerge(ACell input) {
		ACell valuesCell = RT.getIn(input, K_VALUES);
		AVector<?> values = RT.ensureVector(valuesCell);
		if (values == null) {
			throw new IllegalArgumentException("'values' must be a vector of maps");
		}
		ACell acc = null;
		long n = values.count();
		for (long i = 0; i < n; i++) {
			ACell v = values.get(i);
			acc = mergePatch(acc, v);
		}
		if (acc == null) acc = Maps.empty();
		return Maps.of(K_RESULT, acc);
	}

	/**
	 * RFC 7396 JSON Merge Patch.
	 * <ul>
	 *   <li>If patch is not a map, the patch replaces target entirely.</li>
	 *   <li>If target is not a map, treat as empty map.</li>
	 *   <li>For each key in patch: if patch[key] is null, remove key from
	 *       target; otherwise recursively merge target[key] with patch[key].</li>
	 * </ul>
	 */
	@SuppressWarnings("unchecked")
	static ACell mergePatch(ACell target, ACell patch) {
		if (!(patch instanceof AMap)) {
			// Non-map patch replaces target entirely
			return patch;
		}
		AMap<AString, ACell> patchMap = (AMap<AString, ACell>) patch;
		AMap<AString, ACell> result;
		if (target instanceof AMap) {
			result = (AMap<AString, ACell>) target;
		} else {
			result = Maps.empty();
		}
		long n = patchMap.count();
		for (long i = 0; i < n; i++) {
			var entry = patchMap.entryAt(i);
			AString key = entry.getKey();
			ACell patchValue = entry.getValue();
			if (patchValue == null) {
				// null in patch means delete the key
				result = result.dissoc(key);
			} else {
				ACell existing = result.get(key);
				ACell merged = mergePatch(existing, patchValue);
				result = result.assoc(key, merged);
			}
		}
		return result;
	}

	// ========================================================================
	// json:cond — pick the value of the first truthy case
	// ========================================================================

	private ACell handleCond(ACell input) {
		ACell casesCell = RT.getIn(input, K_CASES);
		AVector<?> cases = RT.ensureVector(casesCell);
		if (cases == null) {
			throw new IllegalArgumentException("'cases' must be a vector of {when, then} maps");
		}
		long n = cases.count();
		for (long i = 0; i < n; i++) {
			ACell c = cases.get(i);
			if (!(c instanceof AMap)) {
				throw new IllegalArgumentException(
					"Each case must be a map with 'when' and 'then' keys, got: " + c);
			}
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> caseMap = (AMap<AString, ACell>) c;
			ACell when = caseMap.get(K_WHEN);
			if (truthy(when)) {
				return Maps.of(K_RESULT, caseMap.get(K_THEN));
			}
		}
		// No case matched — return default if present, else null result
		ACell def = RT.getIn(input, K_DEFAULT);
		return Maps.of(K_RESULT, def);
	}

	/**
	 * Truthiness rule: null and JSON false are falsy. Everything else
	 * (including 0, "", empty maps/vectors) is truthy. Strict by design —
	 * forces orchestration authors to use explicit boolean conditions.
	 */
	static boolean truthy(ACell v) {
		if (v == null) return false;
		if (v instanceof CVMBool b) return b.booleanValue();
		return true;
	}

	// ========================================================================
	// json:assoc — set a value at a path in a map
	// ========================================================================

	private ACell handleAssoc(ACell input) {
		ACell target = RT.getIn(input, K_TARGET);
		ACell pathCell = RT.getIn(input, K_PATH);
		ACell value = RT.getIn(input, K_VALUE);

		if (target == null) target = Maps.empty();

		ACell[] keys = pathToKeys(pathCell);
		if (keys.length == 0) {
			throw new IllegalArgumentException("'path' must be a non-empty string or vector of keys");
		}

		ACell result = RT.assocIn(target, value, keys);
		return Maps.of(K_RESULT, result);
	}

	/**
	 * Convert a path spec into a key array. Accepts:
	 * <ul>
	 *   <li>{@link AString} — single key (one-element path)</li>
	 *   <li>{@link AVector} — vector of keys (any cell type)</li>
	 * </ul>
	 */
	private static ACell[] pathToKeys(ACell pathCell) {
		if (pathCell instanceof AString) {
			return new ACell[] { pathCell };
		}
		if (pathCell instanceof AVector v) {
			int n = (int) v.count();
			ACell[] keys = new ACell[n];
			for (int i = 0; i < n; i++) {
				keys[i] = v.get(i);
			}
			return keys;
		}
		throw new IllegalArgumentException(
			"'path' must be a string or vector of keys, got: " + pathCell);
	}

	// ========================================================================
	// json:select — pick a value from a map of cases by key
	// ========================================================================

	private ACell handleSelect(ACell input) {
		ACell key = RT.getIn(input, K_KEY);
		ACell casesCell = RT.getIn(input, K_CASES);
		if (!(casesCell instanceof AMap)) {
			throw new IllegalArgumentException("'cases' must be a map");
		}
		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> cases = (AMap<ACell, ACell>) casesCell;

		if (key != null && cases.containsKey(key)) {
			return Maps.of(K_RESULT, cases.get(key));
		}
		ACell def = RT.getIn(input, K_DEFAULT);
		return Maps.of(K_RESULT, def);
	}
}
