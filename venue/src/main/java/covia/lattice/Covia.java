package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.lattice.generic.KeyedLattice;

/**
 * Root lattice definition for Covia venue state.
 *
 * <p>This class defines the top-level lattice structure that a venue maintains.
 * Similar to Convex's global {@code Lattice.ROOT}, but scoped to venue-specific
 * state and grid participation.
 *
 * <h2>Lattice Structure</h2>
 * <pre>
 * ROOT  ->  KeyedLattice
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
 * KeyedLattice root = Covia.ROOT;
 *
 * // Navigate to grid state
 * ALattice&lt;?&gt; gridLattice = root.path(Covia.GRID);
 *
 * // Merge two venue states
 * AMap&lt;Keyword, ?&gt; merged = root.merge(state1, state2);
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
	 * <p>This is the entry point for all venue state management. Uses KeyedLattice
	 * to map the :grid keyword to GridLattice, following the same pattern as
	 * Convex's Lattice.ROOT.
	 */
	public static final KeyedLattice ROOT = KeyedLattice.create(
		GRID, GridLattice.INSTANCE
	);

	private Covia() {
		// Prevent instantiation - this is a constants/utility class
	}

	/**
	 * Get an empty state for a new venue with proper structure.
	 *
	 * <p>Unlike {@code ROOT.zero()} which returns an empty map, this method
	 * returns a fully-structured empty state with the :grid key populated
	 * with an empty grid state.
	 *
	 * @return Empty venue root state with proper structure
	 */
	public static AMap<Keyword, ACell> empty() {
		return Maps.of(GRID, GridLattice.INSTANCE.zero());
	}
}
