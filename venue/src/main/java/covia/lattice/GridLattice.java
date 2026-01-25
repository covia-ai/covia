package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.util.MergeFunction;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * Top-level lattice for the Covia Grid.
 *
 * <p>GridLattice manages the global grid state with the following structure:
 * <pre>
 * :grid
 *   :venues    ->  Index&lt;DID-String, VenueLattice&gt;  (per-venue state, sorted by DID)
 *   :meta      ->  Index&lt;Hash, AString&gt;             (shared content-addressable metadata as JSON)
 * </pre>
 *
 * <h2>Design Rationale</h2>
 * <ul>
 *   <li><b>:venues</b> - Per-venue state keyed by DID string. Uses Index for efficient
 *       sorted access with blob-like keys. Each venue manages its own assets and jobs.</li>
 *   <li><b>:meta</b> - Shared metadata at grid level. Since metadata is content-addressable
 *       (keyed by SHA256 hash), it is immutable and can be safely shared across venues.
 *       This enables deduplication and efficient cross-venue references.</li>
 * </ul>
 *
 * <h2>Merge Semantics</h2>
 * <ul>
 *   <li><b>:venues</b> - Index merge with VenueLattice for each venue entry</li>
 *   <li><b>:meta</b> - Union merge (content-addressed, same hash = same content)</li>
 * </ul>
 */
public class GridLattice extends ALattice<AMap<Keyword, ACell>> {

	/**
	 * Keyword for the grid root in the lattice path
	 */
	public static final Keyword GRID = Keyword.intern("grid");

	/**
	 * Keyword for venues map within grid state
	 */
	public static final Keyword VENUES = Keyword.intern("venues");

	/**
	 * Keyword for shared metadata at grid level
	 */
	public static final Keyword META = Keyword.intern("meta");

	/**
	 * Singleton instance
	 */
	public static final GridLattice INSTANCE = new GridLattice();

	/**
	 * Child lattice for venues (Index of DID -> VenueLattice)
	 */
	private final ALattice<Index<AString, ACell>> venuesLattice;

	/**
	 * Child lattice for shared metadata (content-addressed storage)
	 * Uses CASLattice&lt;Hash, AString&gt; for type-safe content-addressed storage
	 */
	private final CASLattice<Hash, AString> metaLattice;

	private GridLattice() {
		// Venues use a custom Index lattice that applies VenueLattice to each venue entry
		this.venuesLattice = new VenuesIndexLattice();

		// Meta uses CASLattice for content-addressed storage (same hash = same content)
		this.metaLattice = CASLattice.create();
	}

	/**
	 * Get the singleton GridLattice instance
	 * @return GridLattice instance
	 */
	public static GridLattice create() {
		return INSTANCE;
	}

	@Override
	public AMap<Keyword, ACell> merge(AMap<Keyword, ACell> ownValue, AMap<Keyword, ACell> otherValue) {
		// Handle null cases
		if (otherValue == null) return ownValue;
		if (ownValue == null) return otherValue;

		// Fast path for identical values
		if (Utils.equals(ownValue, otherValue)) return ownValue;

		// Merge each field using appropriate child lattice
		AMap<Keyword, ACell> result = ownValue;

		// Merge venues
		result = mergeVenuesField(result, otherValue);

		// Merge meta
		result = mergeMetaField(result, otherValue);

		return result;
	}

	@Override
	public AMap<Keyword, ACell> merge(LatticeContext context, AMap<Keyword, ACell> ownValue, AMap<Keyword, ACell> otherValue) {
		// For now, delegate to simple merge
		// Future: use context for signing merged values
		return merge(ownValue, otherValue);
	}

	@SuppressWarnings("unchecked")
	private AMap<Keyword, ACell> mergeVenuesField(
			AMap<Keyword, ACell> result,
			AMap<Keyword, ACell> other) {

		Index<AString, ACell> ownField = (Index<AString, ACell>) result.get(VENUES);
		Index<AString, ACell> otherField = (Index<AString, ACell>) other.get(VENUES);

		if (otherField == null) return result;

		Index<AString, ACell> merged = venuesLattice.merge(ownField, otherField);

		if (!Utils.equals(merged, ownField)) {
			result = result.assoc(VENUES, merged);
		}

		return result;
	}

	@SuppressWarnings("unchecked")
	private AMap<Keyword, ACell> mergeMetaField(
			AMap<Keyword, ACell> result,
			AMap<Keyword, ACell> other) {

		Index<Hash, AString> ownField = (Index<Hash, AString>) result.get(META);
		Index<Hash, AString> otherField = (Index<Hash, AString>) other.get(META);

		if (otherField == null) return result;

		Index<Hash, AString> merged = metaLattice.merge(ownField, otherField);

		if (!Utils.equals(merged, ownField)) {
			result = result.assoc(META, merged);
		}

		return result;
	}

	@Override
	public AMap<Keyword, ACell> zero() {
		return Maps.of(
			VENUES, Index.none(),
			META, Index.none()
		);
	}

	@Override
	public boolean checkForeign(AMap<Keyword, ACell> value) {
		if (value == null) return false;
		if (!(value instanceof AMap)) return false;
		return true;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		if (VENUES.equals(childKey)) {
			return (ALattice<T>) venuesLattice;
		}
		if (META.equals(childKey)) {
			return (ALattice<T>) metaLattice;
		}
		return null;
	}

	/**
	 * Index lattice for venues - applies VenueLattice merge to each venue entry.
	 * Uses Index for efficient sorted access with blob-like DID string keys.
	 */
	private static class VenuesIndexLattice extends ALattice<Index<AString, ACell>> {

		private final VenueLattice venueLattice = VenueLattice.INSTANCE;
		private final MergeFunction<ACell> mergeFunction;

		public VenuesIndexLattice() {
			this.mergeFunction = (a, b) -> {
				@SuppressWarnings("unchecked")
				AMap<Keyword, ACell> va = (AMap<Keyword, ACell>) a;
				@SuppressWarnings("unchecked")
				AMap<Keyword, ACell> vb = (AMap<Keyword, ACell>) b;
				return venueLattice.merge(va, vb);
			};
		}

		@Override
		public Index<AString, ACell> merge(Index<AString, ACell> ownValue, Index<AString, ACell> otherValue) {
			if (otherValue == null) return ownValue;
			if (ownValue == null) return otherValue;
			if (Utils.equals(ownValue, otherValue)) return ownValue;

			// Use mergeDifferences to apply VenueLattice merge to each venue
			return ownValue.mergeDifferences(otherValue, mergeFunction);
		}

		@Override
		public Index<AString, ACell> zero() {
			return Index.none();
		}

		@Override
		public boolean checkForeign(Index<AString, ACell> value) {
			return value instanceof Index;
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T extends ACell> ALattice<T> path(ACell childKey) {
			// Any key under venues returns VenueLattice
			return (ALattice<T>) venueLattice;
		}
	}

}
