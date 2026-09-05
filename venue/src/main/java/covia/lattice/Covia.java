package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.generic.CASLattice;
import convex.lattice.generic.JSONLattice;
import convex.lattice.generic.IndexLattice;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.OwnerLattice;
import convex.lattice.generic.StampingLattice;
import convex.lattice.generic.StringKeyedLattice;
import convex.lattice.fs.DLFSLattice;
import covia.api.Fields;


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
 *         :value  ->  StampingLattice -> whole-value LWW -> KeyedLattice
 *           :timestamp   &lt;wall-clock millis&gt;  (re-stamped on every write)
 *           :assets      content-addressed asset store
 *           :storage     content-addressed blob store
 *           :did         venue DID
 *           :users       login directory (Auth)
 *           :schedule    extensible {updated, events, ...} record
 *           :user-data   &lt;DID-string&gt; -> per-user record {j, g, s, w, o, h, a, meta}
 *     :meta  ->  CASLattice (shared content-addressable metadata)
 *   :dlfs  ->  OwnerLattice -> SignedLattice -> per-user drive map
 * </pre>
 *
 * <h2>The venue {@code :value} region</h2>
 * <p>All mutable, deletable venue state is the signed venue {@code :value},
 * composed from three orthogonal Convex lattice layers (see convex#593):</p>
 * <ul>
 *   <li>{@link StampingLattice} — re-stamps {@code :timestamp} with real
 *       wall-clock time on every deep write (never inflated, never {@code +1});</li>
 *   <li>{@link LWWLattice} — whole-value merge: the newer {@code :timestamp}
 *       wins wholesale (tie → own), never recursing, so <b>deletions survive</b>
 *       the propagator's merge-back;</li>
 *   <li>typed keyed/map/index lattices with {@link JSONLattice} leaves —
 *       structural navigation and record-local write-stamping; keys are used
 *       exactly as given (no coercion).</li>
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

	/** User-record metadata slot — framework-owned (not a writable namespace).
	 *  See {@code GRID_LATTICE_DESIGN.md} §"User meta record". */
	public static final AString K_META = Strings.intern("meta");
	/** User-record first-write time, minted once by the stamp. */
	public static final AString K_CREATED = Strings.intern("created");
	/** Agent-record last-modified field. */
	private static final AString K_AGENT_TS = Strings.intern("ts");

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
	 * already carries. Derived and forked cursors retain the application's live
	 * context policy, but fixed test contexts, explicit timestamp overrides or a
	 * backwards host-clock step can still present a timestamp older than the
	 * value already stored. A plain assoc would REGRESS the stamp, and under
	 * whole-value LWW a regressed
	 * {@code :timestamp} makes the merge discard the newer write wholesale
	 * (observed as lost content/appends). The ratchet makes mixed-age
	 * contexts safe by construction.
	 *
	 * <p>Timestamps are never inflated past the write clock (no {@code +1}
	 * Lamport-style bumps): a stamp is real wall-clock time. Equal stamps on
	 * distinct values are resolvable because the merge is DIRECTIONAL — the
	 * contract documented by Convex 0.8.9 (convex#641): {@code own} wins an
	 * unresolved tie, and fork/sync reconciliation treats the local edit as
	 * own. Covia's merge sites order their arguments so the newer side is
	 * {@code own}, typically via fork + sync of the relevant lattice
	 * segment. See covia#214.</p>
	 */
	private static CVMLong ratchet(ACell existing, CVMLong ts) {
		if (existing instanceof CVMLong prev && prev.longValue() > ts.longValue()) return prev;
		return ts;
	}

	/** Injects the write-clock timestamp (from the LatticeContext) into the venue value's {@code :timestamp}. */
	@SuppressWarnings("unchecked")
	private static ACell stampVenue(ACell v, CVMLong ts) {
		if (v instanceof AMap<?,?>) {
			AMap<ACell, ACell> m = (AMap<ACell, ACell>) v;
			return m.assoc(K_TIMESTAMP, ratchet(m.get(K_TIMESTAMP), ts));
		}
		return v;
	}

	/** Injects a last-modified stamp into an extensible string-keyed record. */
	@SuppressWarnings("unchecked")
	private static ACell stampUpdated(ACell v, CVMLong ts) {
		if (v instanceof AMap<?,?>) {
			AMap<ACell, ACell> m = (AMap<ACell, ACell>) v;
			return m.assoc(Fields.UPDATED, ratchet(m.get(Fields.UPDATED), ts));
		}
		return v;
	}

	/** Injects the existing agent record's {@code ts} last-modified stamp. */
	@SuppressWarnings("unchecked")
	private static ACell stampAgent(ACell v, CVMLong ts) {
		if (v instanceof AMap<?,?>) {
			AMap<ACell, ACell> m = (AMap<ACell, ACell>) v;
			return m.assoc(K_AGENT_TS, ratchet(m.get(K_AGENT_TS), ts));
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
	 * <p><b>Clock source:</b> the context write policy ({@code ts}). Covia
	 * installs one live application context whose runtime clock is resolved once
	 * per logical write. Pure: safe under CAS retry.</p>
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
		meta = meta.assoc(Fields.UPDATED, ratchet(meta.get(Fields.UPDATED), ts));
		return user.assoc(K_META, meta);
	}

	/**
	 * Per-DID user record. Jobs and agents have typed record boundaries that
	 * maintain their existing {@code updated}/{@code ts} last-modified fields;
	 * framework namespaces {@code s}/{@code a} are plain navigable JSON; and
	 * user-writable {@code w}/{@code o}/{@code h} are
	 * {@link WrapperLattice} regions ({@code {updated, data}} containers whose
	 * {@code data} is the caller-visible value). Merge is whole-value at
	 * {@code :value}, so these child lattices are used only for navigation and
	 * write-stamping.
	 *
	 * <p>The whole record sits behind its own stamping boundary: every deep
	 * write refreshes the record's {@code meta} slot ({@code created} minted
	 * once, {@code updated} bumped) — see {@link #stampUserMeta} and the
	 * "User meta record" section of {@code GRID_LATTICE_DESIGN.md}.</p>
	 */
	private static final ALattice<ACell> JOB_RECORD = StampingLattice.create(
		JSONLattice.INSTANCE,
		Covia::stampUpdated);

	private static final ALattice<ACell> AGENT_RECORD = StampingLattice.create(
		JSONLattice.INSTANCE,
		Covia::stampAgent);

	private static final ALattice<ACell> USER_RECORD = StampingLattice.create(
		ac(StringKeyedLattice.create(
			"j", IndexLattice.create(JOB_RECORD),
			"g", MapLattice.create(AGENT_RECORD),
			"s", JSONLattice.INSTANCE,
			"a", JSONLattice.INSTANCE,
			"w", WrapperLattice.INSTANCE,
			"o", WrapperLattice.INSTANCE,
			"h", WrapperLattice.INSTANCE)),
		Covia::stampUserMeta);

	/**
	 * Extensible scheduler record. Its stable physical shape is
	 * {@code {updated, events, ...}}; writes below {@code events} refresh the
	 * record's last-modified stamp without rebuilding or narrowing the record.
	 */
	private static final ALattice<ACell> SCHEDULE_RECORD = StampingLattice.create(
		ac(StringKeyedLattice.create("events", JSONLattice.INSTANCE)),
		Covia::stampUpdated);

	/**
	 * Cursor at the caller-visible value of {@code key} below {@code base}: through
	 * the {@link WrapperLattice#VIEW data view} when the child is a wrapped region,
	 * plain navigation otherwise. Reads and writes at the returned cursor see and
	 * replace exactly what a caller addressing {@code key} would.
	 */
	public static ALatticeCursor<ACell> child(ALatticeCursor<ACell> base, ACell key) {
		return WrapperLattice.wraps(base, key) ? base.path(key, WrapperLattice.VIEW) : base.path(key);
	}

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
		SCHEDULE, SCHEDULE_RECORD,
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
