package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * Root lattice definition for Covia venue state.
 *
 * <p>This class defines the top-level lattice structure that a venue maintains.
 * Similar to Convex's global {@code Lattice.ROOT}, but scoped to venue-specific
 * state and grid participation.
 *
 * <h2>Lattice Structure</h2>
 * <pre>
 * ROOT  ->  Covia.ROOT (CoviaMerge)
 *   :grid  ->  GridLattice
 *     :venues
 *       &lt;venue-did-string&gt;  ->  VenueLattice
 *         :assets  ->  Map&lt;Hash, AssetRecord&gt;
 *         :jobs    ->  Map&lt;AString, JobRecord&gt;
 *     :meta  ->  Index&lt;Hash, AString&gt;  (content-addressed metadata)
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>
 * // Get the root lattice for venue state management
 * ALattice&lt;AMap&lt;Keyword, ACell&gt;&gt; root = Covia.ROOT;
 *
 * // Navigate to grid state
 * ALattice&lt;?&gt; gridLattice = root.path(Covia.GRID);
 *
 * // Merge two venue states
 * AMap&lt;Keyword, ACell&gt; merged = root.merge(state1, state2);
 * </pre>
 *
 * @see GridLattice
 * @see VenueLattice
 */
public final class Covia {

	/**
	 * Keyword for the grid state at the root level
	 */
	public static final Keyword GRID = Keyword.intern("grid");

	/**
	 * Root lattice for Covia venue state.
	 *
	 * <p>This is the entry point for all venue state management. It wraps
	 * the GridLattice and provides the top-level merge semantics.
	 */
	public static final ALattice<AMap<Keyword, ACell>> ROOT = new CoviaMerge();

	private Covia() {
		// Prevent instantiation - this is a constants/utility class
	}

	/**
	 * Get an empty/zero state for a new venue.
	 *
	 * @return Empty venue root state
	 */
	public static AMap<Keyword, ACell> empty() {
		return ROOT.zero();
	}

	/**
	 * Root merge implementation for Covia venue state.
	 *
	 * <p>The root lattice contains a single field :grid which holds
	 * the GridLattice value. This structure allows for future expansion
	 * with additional root-level fields if needed.
	 */
	private static final class CoviaMerge extends ALattice<AMap<Keyword, ACell>> {

		private final GridLattice gridLattice = GridLattice.INSTANCE;

		@Override
		public AMap<Keyword, ACell> merge(AMap<Keyword, ACell> ownValue, AMap<Keyword, ACell> otherValue) {
			// Handle null cases
			if (otherValue == null) return ownValue;
			if (ownValue == null) return otherValue;

			// Fast path for identical values
			if (Utils.equals(ownValue, otherValue)) return ownValue;

			// Merge the :grid field using GridLattice
			AMap<Keyword, ACell> result = ownValue;
			result = mergeGridField(result, otherValue);

			return result;
		}

		@Override
		public AMap<Keyword, ACell> merge(LatticeContext context, AMap<Keyword, ACell> ownValue, AMap<Keyword, ACell> otherValue) {
			// For now, delegate to simple merge
			// Future: use context for signing, trust verification, etc.
			return merge(ownValue, otherValue);
		}

		@SuppressWarnings("unchecked")
		private AMap<Keyword, ACell> mergeGridField(
				AMap<Keyword, ACell> result,
				AMap<Keyword, ACell> other) {

			AMap<Keyword, ACell> ownGrid = (AMap<Keyword, ACell>) result.get(GRID);
			AMap<Keyword, ACell> otherGrid = (AMap<Keyword, ACell>) other.get(GRID);

			if (otherGrid == null) return result;

			AMap<Keyword, ACell> mergedGrid = gridLattice.merge(ownGrid, otherGrid);

			if (!Utils.equals(mergedGrid, ownGrid)) {
				result = result.assoc(GRID, mergedGrid);
			}

			return result;
		}

		@Override
		public AMap<Keyword, ACell> zero() {
			return Maps.of(
				GRID, gridLattice.zero()
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
			if (GRID.equals(childKey)) {
				return (ALattice<T>) gridLattice;
			}
			return null;
		}
	}
}
