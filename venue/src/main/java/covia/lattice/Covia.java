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
 *   <li>{@link StampingLattice} — re-stamps {@code :timestamp} on every deep
 *       write with a strictly-increasing, wall-clock-anchored stamp
 *       ({@code max(existing+1, clock)}): distinct values must never share the
 *       LWW merge key, or the positional tie-break makes merges
 *       non-convergent and committed writes get merged away (#214). Drift
 *       above real time is bounded by +1ms per write burst;</li>
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

	/** User-record metadata slot — framework-owned (not a writable namespace).
	 *  See {@code GRID_LATTICE_DESIGN.md} §"User meta record". */
	public static final AString K_META = Strings.intern("meta");
	/** User-record first-write time, minted once by the stamp. */
	public static final AString K_CREATED = Strings.intern("created");

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

	/**
	 * One-way ratchet for stamp timestamps: never below the stamp the value
	 * already carries. Cursors capture their LatticeContext when derived, so
	 * long-lived cursors can hold an older write clock than the engine's
	 * current one — a plain assoc would REGRESS the stamp, and under
	 * whole-value LWW a regressed {@code :timestamp} makes the merge discard
	 * the newer write wholesale (observed as lost content/appends). The
	 * ratchet makes mixed-age contexts safe by construction, and also guards
	 * against backwards system-clock steps.
	 */
	private static CVMLong ratchet(ACell existing, CVMLong ts) {
		if (existing instanceof CVMLong prev && prev.longValue() > ts.longValue()) return prev;
		return ts;
	}

	/**
	 * Strictly-increasing ratchet for LWW <b>merge keys</b>: always above the
	 * stamp the value already carries, even when the write clock has not
	 * advanced (same-millisecond writes; the clock-refresh throttle; a
	 * long-lived cursor's older context). Two DISTINCT venue values carrying
	 * the SAME {@code :timestamp} are unorderable by whole-value LWW — the
	 * merge tie-break is positional ("own wins"), so different merge sites
	 * (fork sync, local reconcile, propagator merge-back) pick different
	 * winners and the state flip-flops, discarding committed writes
	 * (covia#214: a crash-resume repair was written, then merged away).
	 * Strict monotonicity totally orders every successive value from this
	 * venue's single writer, making every merge site converge identically.
	 * The stamp is a Lamport-style clock anchored to wall time: at most
	 * +1ms per write burst ahead of the real clock, caught up as time
	 * advances. Only applied to values that actually changed
	 * ({@code StampedCursor} keeps the current cell for unchanged writes),
	 * so no-op writes never bump it.
	 */
	private static CVMLong strictRatchet(ACell existing, CVMLong ts) {
		if (existing instanceof CVMLong prev && prev.longValue() >= ts.longValue()) {
			return CVMLong.create(prev.longValue() + 1);
		}
		return ts;
	}

	/** Injects the write-clock timestamp (from the LatticeContext) into the venue
	 *  value's {@code :timestamp} — the whole-value-LWW merge key, so the stamp
	 *  must strictly increase on every distinct write (see {@link #strictRatchet}). */
	@SuppressWarnings("unchecked")
	private static ACell stampVenue(ACell v, CVMLong ts) {
		if (v instanceof AMap<?,?>) {
			AMap<ACell, ACell> m = (AMap<ACell, ACell>) v;
			return m.assoc(K_TIMESTAMP, strictRatchet(m.get(K_TIMESTAMP), ts));
		}
		return v;
	}

	/** Injects the write-clock timestamp into a {@code w}/{@code o}/{@code h} value's {@code updated} field. */
	@SuppressWarnings("unchecked")
	private static ACell stampUpdated(ACell v, CVMLong ts) {
		if (v instanceof AMap<?,?>) {
			AMap<ACell, ACell> m = (AMap<ACell, ACell>) v;
			return m.assoc(K_UPDATED, ratchet(m.get(K_UPDATED), ts));
		}
		return v;
	}

	/**
	 * Maintains the user record's {@code meta} slot on every write: bumps
	 * {@code meta.updated} and mints {@code meta.created} on first activity.
	 * The {@code StampedCursor} deep-write re-stamp means ANY write anywhere
	 * in a user's subtree (agents, sessions, frames, jobs, secrets,
	 * workspace) refreshes {@code meta.updated} — the activity signal for
	 * identity-lifecycle policy (TTL/reaping). Idempotent under CAS retry.
	 * See {@code GRID_LATTICE_DESIGN.md} §"User meta record".
	 *
	 * <p><b>Clock source:</b> the context write clock ({@code ts}), which the
	 * harness keeps fresh ({@code Engine.refreshWriteClock} — time is the
	 * harness's responsibility, convex#561). Pure: safe under CAS retry.</p>
	 *
	 * <p><b>Semantics:</b> only writes <em>into</em> the record pass the
	 * boundary — the bare record-init write in {@code Users.ensure} does not,
	 * so {@code created} marks the identity's first <em>activity</em>, not
	 * its first touch. Deliberate: an ensured-but-never-used identity has no
	 * activity to keep alive.</p>
	 */
	@SuppressWarnings("unchecked")
	private static ACell stampUserMeta(ACell v, CVMLong ts) {
		if (!(v instanceof AMap<?,?>)) return v;
		AMap<ACell, ACell> user = (AMap<ACell, ACell>) v;
		AMap<ACell, ACell> meta = (user.get(K_META) instanceof AMap<?,?> m)
			? (AMap<ACell, ACell>) m
			: (AMap<ACell, ACell>) (AMap<?, ?>) convex.core.data.Maps.empty();
		if (meta.get(K_CREATED) == null) meta = meta.assoc(K_CREATED, ts);
		meta = meta.assoc(K_UPDATED, ratchet(meta.get(K_UPDATED), ts));
		return user.assoc(K_META, meta);
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
	 *
	 * <p>The whole record sits behind its own stamping boundary: every deep
	 * write refreshes the record's {@code meta} slot ({@code created} minted
	 * once, {@code updated} bumped) — see {@link #stampUserMeta} and the
	 * "User meta record" section of {@code GRID_LATTICE_DESIGN.md}.</p>
	 */
	private static final ALattice<ACell> USER_RECORD = StampingLattice.create(
		ac(StringKeyedLattice.create(
			"j", JSONLattice.INSTANCE,
			"g", JSONLattice.INSTANCE,
			"s", JSONLattice.INSTANCE,
			"a", JSONLattice.INSTANCE,
			"w", WRAPPED_NS,
			"o", WRAPPED_NS,
			"h", WRAPPED_NS)),
		Covia::stampUserMeta);

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
