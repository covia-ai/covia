package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;
import convex.lattice.generic.CASLattice;
import convex.lattice.generic.JSONLattice;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.OwnerLattice;
import convex.lattice.generic.StampingLattice;
import convex.lattice.generic.StringKeyedLattice;
import convex.lattice.fs.DLFSLattice;


/**
 * Root lattice definition for Covia venue state.
 *
 * <p>Defines the lattice hierarchy for venue state management using standard
 * convex-core lattice types. Follows the same declarative pattern as Convex's
 * {@code Lattice.ROOT}.
 *
 * <h2>Lattice Structure</h2>
 * <pre>
 * ROOT  ->  KeyedLattice
 *   :grid  ->  KeyedLattice
 *     :venues  ->  OwnerLattice (per-AccountKey signed state)
 *       &lt;AccountKey&gt;  ->  SignedLattice
 *         :value  ->  KeyedLattice (venue state)
 *           :assets   ->  CASLattice (union merge, content-addressed)
 *           :storage  ->  CASLattice (union merge, content-addressed blobs)
 *           :did      ->  FunctionLattice (first-writer-wins, set once)
 *           :state    ->  navigable whole-value-LWW (all mutable state)
 *             :timestamp   &lt;wall-clock millis&gt;  (re-stamped on every write)
 *             :users       login directory (Auth)
 *             :schedule    scheduled-event store
 *             :user-data   &lt;DID-string&gt; -> per-user record { j, g, s, w, o, h, a }
 *     :meta  ->  CASLattice (shared content-addressable metadata)
 * </pre>
 *
 * <h2>The {@code :state} region</h2>
 * <p>All mutable, deletable venue state lives under a single {@code :state} node
 * composed from three orthogonal convex lattice layers (see convex#593):</p>
 * <ul>
 *   <li>{@link StampingLattice} — re-stamps {@code :timestamp} with real
 *       wall-clock time on every deep write (never inflated, never {@code +1});</li>
 *   <li>{@link LWWLattice} — whole-value merge: the newer {@code :timestamp}
 *       wins wholesale (tie → own), never recursing, so <b>deletions survive</b>
 *       the propagator's merge-back;</li>
 *   <li>{@link JSONLattice} — structural navigation: stock cursors descend and
 *       write the JSON interior, building each intermediate from the key shape
 *       (keyword/blob → {@code Index}, string → map, integer → vector); keys are
 *       used exactly as given (no coercion).</li>
 * </ul>
 *
 * <p>This is correct because a venue's {@code :value} has a single authoritative
 * writer (its signing key under {@code OwnerLattice}/{@code SignedLattice}), so
 * every merge is a stale-vs-fresh snapshot of the same lineage, never independent
 * concurrent contributors — for which "newest coherent snapshot wins wholesale"
 * (whole-value LWW) is the correct CRDT. Per-entry union (the previous model)
 * resurrected deleted keys on merge-back.</p>
 */
public final class Covia {

	// ========== Root-level keywords ==========

	/** Keyword for the grid state at the root level */
	public static final Keyword GRID = Keyword.intern("grid");

	/** Keyword for the DLFS (Decentralised Lattice File System) region at root level.
	 *  Independent from venue state — per-user drives signed with user's own key. */
	public static final Keyword DLFS = Keyword.intern("dlfs");

	// ========== Grid-level keywords ==========

	/** Keyword for venues map within grid state */
	public static final Keyword VENUES = Keyword.intern("venues");

	/** Keyword for shared metadata at grid level */
	public static final Keyword META = Keyword.intern("meta");

	// ========== Venue-level keywords (children of the venue :value) ==========

	/** Keyword for the content-addressed asset store */
	public static final Keyword ASSETS = Keyword.intern("assets");

	/** Keyword for content-addressed blob storage */
	public static final Keyword STORAGE = Keyword.intern("storage");

	/** Keyword for the venue DID string (set once at venue creation) */
	public static final Keyword DID = Keyword.intern("did");

	/** Login directory (Auth). */
	public static final Keyword USERS = Keyword.intern("users");

	/** Per-DID user state. */
	public static final Keyword USER_DATA = Keyword.intern("user-data");

	/** Per-venue scheduled-event store. */
	public static final Keyword SCHEDULE = Keyword.intern("schedule");

	// ========== Venue-value whole-value-LWW plumbing ==========

	/** Timestamp keyword re-stamped on every write to the venue value; LWW picks the newest. */
	static final Keyword K_TIMESTAMP = LWWLattice.KEY_TIMESTAMP;   // :timestamp

	/** Wrapper metadata key for {@code w}/{@code o}/{@code h}: last-modified time (auto-stamped). */
	static final AString K_UPDATED = Strings.intern("updated");
	/** Wrapper payload key for {@code w}/{@code o}/{@code h}: the namespace content. */
	public static final AString K_DATA = Strings.intern("data");

	/** Unchecked cast helper: a typed navigation lattice used only for structure. */
	@SuppressWarnings("unchecked")
	private static ALattice<ACell> ac(ALattice<?> l) { return (ALattice<ACell>) l; }

	/** Extracts the {@code :timestamp} from the venue value for LWW merge. */
	@SuppressWarnings("unchecked")
	private static long venueTimestamp(ACell v) {
		if (v instanceof AMap<?,?>) {
			ACell t = ((AMap<ACell, ACell>) v).get(K_TIMESTAMP);
			if (t instanceof CVMLong l) return l.longValue();
		}
		return 0;
	}

	/** Injects the write-clock timestamp (from the LatticeContext) into the venue value's {@code :timestamp}. */
	@SuppressWarnings("unchecked")
	private static ACell stampVenue(ACell v, CVMLong ts) {
		if (v instanceof AMap<?,?>) {
			return ((AMap<ACell, ACell>) v).assoc(K_TIMESTAMP, ts);
		}
		return v;
	}

	/** Injects the write-clock timestamp into a {@code w}/{@code o}/{@code h} value's {@code updated} field. */
	@SuppressWarnings("unchecked")
	private static ACell stampUpdated(ACell v, CVMLong ts) {
		if (v instanceof AMap<?,?>) {
			return ((AMap<ACell, ACell>) v).assoc(K_UPDATED, ts);
		}
		return v;
	}

	/**
	 * The {@code w}/{@code o}/{@code h} namespaces: stored as {@code {updated, data}},
	 * with content under {@code data} (navigable JSON) and {@code updated}
	 * auto-stamped on every write by the {@code StampedCursor} that
	 * {@link StampingLattice} inserts. A path {@code w/foo} resolves to
	 * {@code w/data/foo}, so callers never address {@code data}; {@code updated} is
	 * metadata and is not merged (whole-value merge happens at {@code :value}).
	 */
	private static final ALattice<ACell> WRAPPED_NS = StampingLattice.create(
		ac(StringKeyedLattice.create("data", JSONLattice.INSTANCE)),
		Covia::stampUpdated);

	/**
	 * Per-DID user record. Framework namespaces ({@code j}/{@code g}/{@code s}/{@code a})
	 * are plain navigable JSON; user-writable {@code w}/{@code o}/{@code h} are the
	 * stamped {@code {updated, data}} wrappers. Merge is whole-value at {@code :value},
	 * so these child lattices are used only for navigation and write-stamping.
	 */
	private static final ALattice<ACell> USER_RECORD = ac(StringKeyedLattice.create(
		"j", JSONLattice.INSTANCE,
		"g", JSONLattice.INSTANCE,
		"s", JSONLattice.INSTANCE,
		"a", JSONLattice.INSTANCE,
		"w", WRAPPED_NS,
		"o", WRAPPED_NS,
		"h", WRAPPED_NS));

	/**
	 * Venue interior — keyword-keyed regions; {@code :user-data} maps each DID to a
	 * {@link #USER_RECORD}. Used for navigation and write-stamping only; the merge
	 * is whole-value at {@code :value}.
	 */
	private static final ALattice<ACell> INTERIOR = ac(KeyedLattice.create(
		ASSETS, JSONLattice.INSTANCE,
		STORAGE, JSONLattice.INSTANCE,
		DID, JSONLattice.INSTANCE,
		USERS, JSONLattice.INSTANCE,
		SCHEDULE, JSONLattice.INSTANCE,
		USER_DATA, MapLattice.create(USER_RECORD)));

	/**
	 * Venue lattice — the whole per-venue {@code :value} is a single navigable
	 * whole-value-LWW node: whole-value LWW merge by {@code :timestamp} (newer wins,
	 * tie&nbsp;→&nbsp;own, non-recursive, so deletions and asset GC survive the
	 * propagator merge-back), with a typed navigable interior ({@link #INTERIOR}) and
	 * a stamp-on-write boundary. Composed as
	 * {@code stamping(write) -> LWW(merge) -> typed-navigation}.
	 *
	 * <p>Correct because a venue's {@code :value} has a single authoritative writer
	 * (its signing key under {@code OwnerLattice}/{@code SignedLattice}), so every
	 * merge is a stale-vs-fresh snapshot of the same lineage — for which "newest
	 * coherent snapshot wins wholesale" is the right CRDT. Content
	 * ({@code :assets}/{@code :storage}) rides the same node (single-writer, so the
	 * newest value already holds all content, making removal/GC durable). Genuinely
	 * shared multi-writer content lives at grid {@code :meta} (a CAS region).
	 */
	public static final ALattice<ACell> VENUE = StampingLattice.create(
		LWWLattice.create(INTERIOR, Covia::venueTimestamp),
		Covia::stampVenue);

	/**
	 * Per-user DLFS drives lattice. Each user (AccountKey) signs their own drive
	 * map. Drives are keyed by name, each a DLFSLattice tree with rsync-like CRDT
	 * merge semantics.
	 */
	public static final OwnerLattice<?> DLFS_USERS = OwnerLattice.create(
		MapLattice.create(DLFSLattice.INSTANCE)    // drive-name → DLFS tree
	);

	/**
	 * Root lattice for Covia state. Two sibling regions: {@code :grid} (venue
	 * state, signed by the venue's key) and {@code :dlfs} (per-user DLFS drives,
	 * each signed by the user's own key).
	 */
	public static final KeyedLattice ROOT = KeyedLattice.create(
		GRID, KeyedLattice.create(
			VENUES, OwnerLattice.create(VENUE),
			META, CASLattice.create()
		),
		DLFS, DLFS_USERS
	);

	private Covia() {
		// Prevent instantiation
	}
}
