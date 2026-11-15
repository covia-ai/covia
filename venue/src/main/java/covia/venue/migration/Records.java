package covia.venue.migration;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;

/**
 * Rebuilds the records a migration part converts. Each returns the same cell
 * when nothing in it changes, so a part leaves a current record exactly as it
 * found it.
 */
final class Records {
	private Records() {}

	@SuppressWarnings("unchecked")
	static ACell assoc(ACell map, ACell key, ACell value) {
		return ((AMap<ACell, ACell>) map).assoc(key, value);
	}

	/** {@code record} with the value at {@code key} converted; the same cell when there is none or it does not change. */
	static ACell convertAt(ACell record, ACell key, UnaryOperator<ACell> convert) {
		if (!(record instanceof AMap<?, ?> map)) return record;
		ACell value = map.get(key);
		if (value == null) return record;
		ACell converted = convert.apply(value);
		return (converted == value) ? record : assoc(record, key, converted);
	}

	/** {@code map} with each value converted; the same cell when none changes. */
	@SuppressWarnings("unchecked")
	static ACell mapValues(AMap<?, ?> map, UnaryOperator<ACell> convert) {
		AMap<ACell, ACell> result = (AMap<ACell, ACell>) map;
		for (var entry : ((AMap<ACell, ACell>) map).entrySet()) {
			ACell converted = convert.apply(entry.getValue());
			if (converted != entry.getValue()) result = result.assoc(entry.getKey(), converted);
		}
		return result;
	}

	/** {@code index} with each value converted; the same cell when none changes. */
	@SuppressWarnings("unchecked")
	static ACell indexValues(Index<?, ?> index, UnaryOperator<ACell> convert) {
		Index<Blob, ACell> result = (Index<Blob, ACell>) index;
		for (long i = 0, n = index.count(); i < n; i++) {
			var entry = ((Index<Blob, ACell>) index).entryAt(i);
			ACell converted = convert.apply(entry.getValue());
			if (converted != entry.getValue()) result = result.assoc(entry.getKey(), converted);
		}
		return result;
	}

	/** {@code vector} with each element converted; the same cell when none changes. */
	@SuppressWarnings("unchecked")
	static ACell vectorValues(AVector<?> vector, UnaryOperator<ACell> convert) {
		AVector<ACell> result = (AVector<ACell>) vector;
		for (long i = 0, n = vector.count(); i < n; i++) {
			ACell element = vector.get(i);
			ACell converted = convert.apply(element);
			if (converted != element) result = result.assoc(i, converted);
		}
		return result;
	}
}
