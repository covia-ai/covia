package covia.venue;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;
import covia.lattice.Covia;
import covia.venue.storage.LatticeStorage;

/**
 * Cursor wrapper for a single venue's state.
 *
 * <p>A {@code VenueState} wraps a lattice cursor at the venue level
 * (through the OwnerLattice/SignedLattice boundary at {@code :value}).
 * The cursor chain determines when writes are signed:</p>
 * <ul>
 *   <li><b>Connected</b> (from {@link #fromRoot}): every write propagates
 *       through {@code SignedCursor} and is signed immediately.</li>
 *   <li><b>Forked</b> (from {@link #fork()}): writes accumulate in a local
 *       {@code Root} cursor (unsigned). Call {@link #sync()} to merge all
 *       changes into the parent cursor in one atomic operation — triggering
 *       a single sign through the {@code SignedCursor} chain.</li>
 * </ul>
 *
 * <p>Engine uses a connected {@code VenueState} as its authoritative state.
 * That keeps component writes visible to every Engine consumer immediately
 * and leaves publication and physical durability as separate concerns.</p>
 *
 * <p>Forks are explicit, bounded transactions. Use one when several writes
 * must become visible together: perform the work against the fork, call
 * {@link #sync()} only after all work succeeds, and discard the fork on
 * failure. A whole-venue fork also requires an exclusive mutation boundary;
 * active features should prefer the narrowest component fork that contains
 * their transaction. A fork should not be retained as a second long-lived copy
 * of venue state.</p>
 *
 * <p>Provides domain-specific component accessors:</p>
 * <ul>
 *   <li>{@link #assets()} — content-addressed asset store</li>
 *   <li>{@link #users()} — per-user data store</li>
 *   <li>{@link #scheduleCursor()} — extensible scheduler record</li>
 *   <li>{@link #storage()} — content-addressed blob storage</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Standalone (tests, demos)
 * VenueState vs = VenueState.create(keyPair);
 * vs.assets().store(meta, content);
 *
 * // Connected to a root lattice cursor (Engine)
 * VenueState connected = VenueState.fromRoot(rootCursor, accountKey);
 * connected.assets().store(meta, content); // immediately visible at root
 *
 * // Bounded transaction
 * VenueState transaction = connected.fork();
 * transaction.assets().store(meta, content); // private until commit
 * transaction.sync();                        // commit atomically
 * }</pre>
 */
public class VenueState extends ALatticeComponent<ACell> {

	private final AccountKey ownerKey;

	VenueState(ALatticeComponent<?> parent, ALatticeCursor<ACell> cursor,
			AccountKey ownerKey) {
		super(parent, cursor);
		this.ownerKey = ownerKey;
	}

	/** Compatibility constructor for a standalone raw-cursor component. */
	private VenueState(ALatticeCursor<ACell> cursor, AccountKey ownerKey) {
		super(cursor);
		this.ownerKey = ownerKey;
	}

	/**
	 * Creates a standalone VenueState with its own root cursor.
	 * Suitable for tests and demos.
	 *
	 * @param kp Key pair for signing updates
	 * @return New VenueState instance
	 */
	public static VenueState create(AKeyPair kp) {
		return CoviaApplication.create(kp).venue(kp.getAccountKey());
	}

	/**
	 * Connects a venue component beneath a containing Covia application.
	 * Component policy (including persistence) delegates through the parent,
	 * while cursor navigation crosses the owner-signing boundary normally.
	 */
	public static VenueState connect(ALatticeComponent<?> parent,
			AccountKey ownerKey) {
		if (parent == null) {
			throw new IllegalArgumentException("Parent component must not be null");
		}
		if (ownerKey == null) {
			throw new IllegalArgumentException("Venue owner key must not be null");
		}
		ALatticeCursor<ACell> venueCursor = parent.cursor().path(
			Covia.GRID, Covia.VENUES, ownerKey, Keywords.VALUE);
		return new VenueState(parent, venueCursor, ownerKey);
	}

	/**
	 * Connects to an existing root lattice cursor by navigating to the
	 * venue level. The root cursor is typically held by Engine.
	 *
	 * @param root Root lattice cursor
	 * @param ownerKey Venue owner's account key
	 * @return VenueState connected to the root cursor
	 */
	public static VenueState fromRoot(ALatticeCursor<?> root, AccountKey ownerKey) {
		ALatticeCursor<ACell> venueCursor = root.path(
			Covia.GRID, Covia.VENUES, ownerKey, Keywords.VALUE);
		return new VenueState(venueCursor, ownerKey);
	}

	/**
	 * Reads the venue owner keys recorded in an already-open store without
	 * constructing a {@link Engine} or binding a venue identity.
	 *
	 * <p>This method neither mutates nor closes the store; ownership remains with
	 * the caller. A fresh store has no venue keys and returns an empty set.</p>
	 *
	 * @param store Open Covia lattice store.
	 * @return Immutable set of persisted venue owner keys.
	 * @throws IOException if the persisted root cannot be read.
	 */
	@SuppressWarnings("unchecked")
	public static Set<AccountKey> peekVenueKeys(AStore store) throws IOException {
		ACell root = Objects.requireNonNull(store, "store").getRootData();
		if (root == null) return Set.of();

		ACell value = RT.getIn(root, Covia.GRID, Covia.VENUES);
		if (!(value instanceof AMap<?, ?> venues) || venues.isEmpty()) return Set.of();

		LinkedHashSet<AccountKey> keys = new LinkedHashSet<>();
		AMap<ACell, ACell> entries = (AMap<ACell, ACell>) venues;
		for (long i = 0; i < entries.count(); i++) {
			MapEntry<ACell, ACell> entry = entries.entryAt(i);
			ACell rawKey = entry.getKey();
			if (rawKey instanceof AccountKey key) {
				keys.add(key);
			} else if (rawKey instanceof ABlob blob && blob.count() == AccountKey.LENGTH) {
				// Map-key canonicalisation may decode AccountKey as its equivalent
				// 32-byte blob; restore the domain type for callers.
				keys.add(AccountKey.create(blob));
			}
		}
		return Collections.unmodifiableSet(keys);
	}

	/**
	 * Gets the venue's asset store.
	 *
	 * @return AssetStore cursor wrapper
	 */
	public AssetStore assets() {
		return new AssetStore(this, cursor.path(Covia.ASSETS));
	}

	/**
	 * Gets the venue's per-user data store.
	 *
	 * @return Users cursor wrapper
	 */
	public Users users() {
		return new Users(this, cursor.path(Covia.USER_DATA));
	}

	/**
	 * Gets a lattice cursor at the {@code :users} level for Auth construction.
	 *
	 * @return Cursor at the :users level
	 */
	ALatticeCursor<ACell> authCursor() {
		return cursor.path(Covia.USERS);
	}

	/**
	 * Gets the lattice cursor at the per-venue {@code :schedule} record. Its
	 * {@code events} field is the time-ordered store used by {@link Scheduler};
	 * sibling fields hold record metadata and leave room for future additions.
	 *
	 * @return Cursor at the :schedule level (value is a record)
	 */
	public ALatticeCursor<ACell> scheduleCursor() {
		return cursor.path(Covia.SCHEDULE);
	}

	/**
	 * Gets the venue's content-addressed blob storage.
	 *
	 * @return LatticeStorage instance backed by this venue's cursor
	 */
	public LatticeStorage storage() {
		return new LatticeStorage(cursor.path(Covia.STORAGE));
	}

	/**
	 * Gets the raw venue state value.
	 *
	 * @return Map-shaped venue value, or null if uninitialised
	 */
	public ACell get() {
		return cursor.get();
	}

	/**
	 * Initialises this venue with the given DID if not already initialised.
	 * Sets the venue state to the VENUE lattice zero value with the DID field.
	 *
	 * @param did The venue's DID string
	 */
	public void initialise(ACell did) {
		// Atomic init via CAS — read-then-set would let a late initialise()
		// clobber state another thread already wrote. The venue value is a
		// keyword-keyed Index; the whole-value-LWW lattice's zero() is null, so
		// materialise an empty Index for a fresh venue and test by DID presence.
		cursor.updateAndGet(current -> {
			@SuppressWarnings("unchecked")
			Index<Keyword, ACell> v = (current instanceof Index<?,?>)
				? (Index<Keyword, ACell>) current
				: Index.none();
			return v.containsKey(Covia.DID) ? v : v.assoc(Covia.DID, did);
		});
	}

	/**
	 * Gets this venue's owner account key.
	 *
	 * @return The owner's Ed25519 public key
	 */
	public AccountKey getOwnerKey() {
		return ownerKey;
	}

	/**
	 * Creates a forked VenueState that accumulates writes locally without
	 * signing. Call {@link #sync()} to propagate all changes to the parent
	 * cursor, which triggers a single sign through the SignedCursor chain.
	 *
	 * <p>This is intended for a bounded transaction whose writes must become
	 * visible together. Call {@code sync()} only on success; dropping the fork
	 * without syncing discards its local writes. Engine itself keeps a connected
	 * VenueState rather than a long-lived fork. The caller must exclude concurrent
	 * parent mutations for a whole-venue transaction; otherwise fork the narrowest
	 * independently owned component instead.</p>
	 *
	 * <p>The forked cursor uses a local {@code Root} backed by
	 * {@code AtomicReference} — reads and writes are lock-free and
	 * never touch the parent cursor chain until sync.</p>
	 *
	 * @return A new VenueState backed by a forked cursor
	 */
	public VenueState fork() {
		return new VenueState(parent(), cursor.fork(), ownerKey);
	}

}
