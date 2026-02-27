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
import convex.lattice.generic.StringKeyedLattice;


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
 *           :user-data ->  MapLattice (DID -> per-user StringKeyedLattice)
 *             &lt;DID-string&gt;  ->  StringKeyedLattice (USER, AString keys)
 *               "j"  ->  IndexLattice + LWW (user's job references)
 *               "g"  ->  MapLattice + LWW (user's agents — single atomic record per agent)
 *               "s"  ->  MapLattice + LWW (user's encrypted credentials)
 *     :meta  ->  CASLattice (shared content-addressable metadata)
 * </pre>
 *
 * <p>Agent records are single atomic LWW values (latest "ts" wins).
 * See {@code AGENT_LOOP.md} for the agent record structure and transitions.</p>
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
	 * Key used for "ts" timestamp in agent records (AString, not Keyword).
	 */
	private static final AString TS_KEY = Strings.intern("ts");

	/**
	 * LWW value lattice using the "ts" field as timestamp.
	 * Used for agent records — single atomic value, latest ts wins.
	 * See {@code AGENT_LOOP.md} for the agent record structure.
	 */
	private static final LWWLattice<ACell> AGENT_LWW = LWWLattice.create(Covia::extractTsTimestamp);

	/**
	 * Per-user lattice structure. Each user (identified by DID string) gets
	 * an independent {@link StringKeyedLattice} with short AString keys matching
	 * {@link Namespace} constants. String keys keep the user lattice
	 * JSON-compliant throughout.
	 */
	public static final StringKeyedLattice USER = StringKeyedLattice.create(
		"j", IndexLattice.create(LWW),         // user's job references
		"g", MapLattice.create(AGENT_LWW),     // user's agents (LWW per agent, latest ts wins)
		"s", MapLattice.create(LWW)            // user's encrypted credentials
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

	/**
	 * Extracts the "ts" timestamp from a map value for agent LWW merge.
	 * Agent records use AString "ts" key with CVMLong values.
	 */
	@SuppressWarnings("unchecked")
	private static long extractTsTimestamp(ACell value) {
		if (value instanceof AMap<?,?>) {
			ACell ts = ((AMap<ACell, ACell>) value).get(TS_KEY);
			if (ts instanceof CVMLong l) return l.longValue();
		}
		return 0;
	}

	private Covia() {
		// Prevent instantiation
	}
}
