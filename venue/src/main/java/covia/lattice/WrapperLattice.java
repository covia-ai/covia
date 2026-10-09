package covia.lattice;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.AUpdateCursor;
import convex.lattice.cursor.StampedCursor;
import convex.lattice.generic.ADelegatingLattice;
import convex.lattice.generic.JSONLattice;
import convex.lattice.generic.StringKeyedLattice;

/**
 * A navigable JSON region stored inside a {@code {updated, data}} container.
 *
 * <p>The stored cell is the container; the value this region <em>presents</em> is
 * the {@code data} inside it. Navigating below the region crosses a transparent
 * view boundary, so {@code cursor.path("w", "notes")} reads and writes
 * {@code w.data.notes}, and every changed write preserves the container while
 * ratcheting its {@code updated} stamp from the {@link LatticeContext} write
 * clock. The region's own value is reached through the virtual {@link #VIEW}
 * key, exactly as a signed envelope's value is reached through {@code :value}.
 * Callers never address {@code data} themselves.</p>
 *
 * <p>This is a structure layer only. Merge belongs to the enclosing node (the
 * venue value is whole-value LWW), so {@link JSONLattice} semantics apply.</p>
 */
public final class WrapperLattice extends ADelegatingLattice<ACell> {

	/** Virtual key selecting the wrapped {@code data} value: {@code path(ns, VIEW)}. */
	public static final Keyword VIEW = Keywords.VALUE;

	/** Container field holding the caller-visible value. */
	public static final AString DATA = Strings.intern("data");

	/** Container field: wall-clock millis of the last write into the region. */
	public static final AString UPDATED = Strings.intern("updated");

	/** Singleton wrapper over a navigable JSON interior. */
	public static final WrapperLattice INSTANCE = new WrapperLattice();

	/**
	 * Physical container structure. Writes enter through a {@link StampedCursor}
	 * at this level, then descend explicitly to {@link #DATA}; this is the
	 * same-value stamp-on-write composition used by {@code StampingLattice}.
	 */
	private static final ALattice<ACell> CONTAINER = ac(
		StringKeyedLattice.create(DATA, JSONLattice.INSTANCE));

	private WrapperLattice() {
		super(JSONLattice.INSTANCE);
	}

	/** True if {@code key} below {@code base} is a wrapped region. */
	public static boolean wraps(ALatticeCursor<?> base, ACell key) {
		ALattice<?> lattice = base.getLattice();
		return lattice != null && lattice.path(key) instanceof WrapperLattice;
	}

	@SuppressWarnings("unchecked")
	private static ALattice<ACell> ac(ALattice<?> lattice) {
		return (ALattice<ACell>) lattice;
	}

	/** Preserve the container and ratchet its last-modified stamp. */
	@SuppressWarnings("unchecked")
	private static ACell stampUpdated(ACell value, CVMLong timestamp) {
		if (!(value instanceof AMap<?, ?> map)) return value;
		AMap<ACell, ACell> container = (AMap<ACell, ACell>) map;
		ACell previous = container.get(UPDATED);
		if (previous instanceof CVMLong old && old.longValue() > timestamp.longValue()) {
			timestamp = old;
		}
		return container.assoc(UPDATED, timestamp);
	}

	@Override
	public boolean isWriteBoundary(ACell key) {
		return true;
	}

	@Override
	public boolean consumesPathKey(ACell key) {
		return VIEW.equals(key);
	}

	@SuppressWarnings("unchecked")
	@Override
	public AUpdateCursor<?, ?> createPathCursor(ALatticeCursor<?> base, ACell key, LatticeContext context) {
		StampedCursor<ACell> container = StampedCursor.create(
			(ACursor<ACell>) base, CONTAINER, context, WrapperLattice::stampUpdated);
		ALatticeCursor<ACell> data = container.path(DATA);
		return new DataCursor(data, inner);
	}

	/**
	 * Identity update boundary over the container's {@code data} path. The base
	 * path writes through the enclosing {@link StampedCursor}, which preserves
	 * the existing container and refreshes its {@code updated} field.
	 */
	private static final class DataCursor extends AUpdateCursor<ACell, ACell> {

		DataCursor(ACursor<ACell> base, ALattice<ACell> lattice) {
			super(base, lattice, null);
		}

		@Override
		protected ACell prepareWrite(ACell value) {
			return value;
		}

		@Override
		public ACell merge(ACell other) {
			return updateAndGet(current -> lattice.merge(getContext(), current, other));
		}
	}
}
