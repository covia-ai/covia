package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.generic.CASLattice;
import convex.lattice.generic.FunctionLattice;
import convex.lattice.generic.IndexLattice;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.OwnerLattice;

/**
 * Root lattice definition for Covia venue state.
 *
 * <p>Defines the complete lattice hierarchy for venue state management using
 * standard convex-core lattice types. Follows the same declarative pattern
 * as Convex's {@code Lattice.ROOT}.
 *
 * <h2>Lattice Structure</h2>
 * <pre>
 * ROOT  ->  KeyedLattice
 *   :grid  ->  KeyedLattice
 *     :venues  ->  OwnerLattice (per-AccountKey signed state)
 *       &lt;AccountKey&gt;  ->  SignedLattice
 *         :value  ->  KeyedLattice (venue state)
 *           :assets    ->  CASLattice (union merge, content-addressed)
 *           :jobs      ->  IndexLattice + LWW (newer "updated" wins)
 *           :users     ->  MapLattice + LWW (newer "updated" wins)
 *           :storage   ->  CASLattice (union merge, content-addressed blobs)
 *           :auth      ->  MapLattice + LWW (newer "updated" wins)
 *           :did       ->  FunctionLattice (first-writer-wins)
 *           :caps      ->  MapLattice + LWW (per-DID capability sets)
 *           :user-data ->  MapLattice (DID -> per-user KeyedLattice)
 *             &lt;DID-string&gt;  ->  KeyedLattice (USER)
 *               :jobs  ->  MapLattice + LWW (user's job references)
 *     :meta  ->  CASLattice (shared content-addressable metadata)
 * </pre>
 */
public final class Covia {

	// ========== Root-level keywords ==========

	/** Keyword for the grid state at the root level */
	public static final Keyword GRID = Keyword.intern("grid");

	// ========== Grid-level keywords ==========

	/** Keyword for venues map within grid state */
	public static final Keyword VENUES = Keyword.intern("venues");

	/** Keyword for shared metadata at grid level */
	public static final Keyword META = Keyword.intern("meta");

	// ========== Venue-level keywords ==========

	/** Keyword for assets index within venue state */
	public static final Keyword ASSETS = Keyword.intern("assets");

	/** Keyword for jobs index within venue state */
	public static final Keyword JOBS = Keyword.intern("jobs");

	/** Keyword for users within venue state */
	public static final Keyword USERS = Keyword.intern("users");

	/** Keyword for content-addressed storage within venue state */
	public static final Keyword STORAGE = Keyword.intern("storage");

	/** Keyword for authorization state within venue state */
	public static final Keyword AUTH = Keyword.intern("auth");

	/** Keyword for DID string within venue state (set once at venue creation) */
	public static final Keyword DID = Keyword.intern("did");

	/** Keyword for per-DID capability sets within venue state */
	public static final Keyword CAPS = Keyword.intern("caps");

	/** Keyword for per-DID user state within venue state */
	public static final Keyword USER_DATA = Keyword.intern("user-data");

	// ========== Lattice definitions ==========

	/**
	 * Key used for "updated" timestamp in job/user/auth records (AString, not Keyword).
	 */
	private static final AString UPDATED_KEY = Strings.intern("updated");

	/**
	 * LWW value lattice using the "updated" field as timestamp.
	 * Shared by jobs, users, and auth child lattices.
	 */
	private static final LWWLattice<ACell> LWW = LWWLattice.create(Covia::extractUpdatedTimestamp);

	/**
	 * Per-user lattice structure. Each user (identified by DID string) gets
	 * an independent KeyedLattice. Extensible in future phases with
	 * :workspace, :assets, :ops.
	 */
	public static final KeyedLattice USER = KeyedLattice.create(
		JOBS, IndexLattice.create(LWW)                    // user's job references (Index, not Map)
	);

	/**
	 * Venue lattice — per-venue state with keyword-keyed fields.
	 *
	 * <p>Each venue's state is a keyword-keyed Index containing assets, jobs,
	 * users, storage, auth, DID, capabilities, and per-user data with
	 * appropriate merge semantics.
	 */
	public static final KeyedLattice VENUE = KeyedLattice.create(
		ASSETS, CASLattice.create(),                      // union merge (content-addressed)
		JOBS, IndexLattice.create(LWW),                   // per-job LWW by "updated" timestamp
		USERS, MapLattice.create(LWW),                    // per-user LWW by "updated" timestamp
		STORAGE, CASLattice.create(),                     // union merge (content-addressed blobs)
		AUTH, MapLattice.create(LWW),                     // per-entry LWW by "updated" timestamp
		DID, FunctionLattice.create((a, b) -> a),         // first-writer-wins
		CAPS, MapLattice.create(LWW),                     // per-DID capability sets
		USER_DATA, MapLattice.create(USER)                // per-DID user state
	);

	/**
	 * Root lattice for Covia venue state.
	 *
	 * <p>Entry point for all venue state management. Uses nested KeyedLattice
	 * composition following the same pattern as Convex's {@code Lattice.ROOT}.
	 */
	public static final KeyedLattice ROOT = KeyedLattice.create(
		GRID, KeyedLattice.create(
			VENUES, OwnerLattice.create(VENUE),
			META, CASLattice.create()
		)
	);

	/**
	 * Extracts the "updated" timestamp from a map value for LWW merge.
	 * Records use AString "updated" key with CVMLong values.
	 */
	@SuppressWarnings("unchecked")
	private static long extractUpdatedTimestamp(ACell value) {
		if (value instanceof AMap<?,?>) {
			ACell ts = ((AMap<ACell, ACell>) value).get(UPDATED_KEY);
			if (ts instanceof CVMLong l) return l.longValue();
		}
		return 0;
	}

	private Covia() {
		// Prevent instantiation
	}
}
