package covia.venue;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import covia.lattice.Covia;

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
 * <p>The recommended pattern for Engine is: bootstrap with a connected
 * VenueState (so the DID initialisation is signed), then {@link #fork()}
 * for all subsequent request processing. {@code Engine.syncState()} calls
 * {@link #sync()} once per request, batching all writes into a single sign.</p>
 *
 * <p>Provides domain-specific accessors:</p>
 * <ul>
 *   <li>{@link #assets()} — content-addressed asset store</li>
 *   <li>{@link #jobs()} — timestamp-ordered job store</li>
 *   <li>{@link #users()} — per-user data store</li>
 *   <li>{@link #usersCursor()} — cursor for Auth</li>
 *   <li>{@link #authCursor()} — cursor for AccessControl</li>
 *   <li>{@link #capsCursor()} — cursor for capabilities</li>
 *   <li>{@link #storageCursor()} — cursor for LatticeStorage</li>
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
 * // ... bootstrap DID ...
 * VenueState forked = connected.fork();  // unsigned local writes
 * forked.assets().store(meta, content);  // local only
 * forked.sync();                         // merge + sign once
 * }</pre>
 */
public class VenueState extends ALatticeComponent<ACell> {

	private final AccountKey ownerKey;

	VenueState(ALatticeCursor<ACell> cursor, AccountKey ownerKey) {
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
		LatticeContext ctx = LatticeContext.create(null, kp);
		ALatticeCursor<Index<Keyword, ACell>> root = Cursors.createLattice(Covia.ROOT);
		root.withContext(ctx);
		AccountKey ownerKey = kp.getAccountKey();
		ALatticeCursor<ACell> venueCursor = root.path(
			Covia.GRID, Covia.VENUES, ownerKey, Keywords.VALUE);
		return new VenueState(venueCursor, ownerKey);
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
	 * Gets the venue's asset store.
	 *
	 * @return AssetStore cursor wrapper
	 */
	public AssetStore assets() {
		return new AssetStore(cursor.path(Covia.ASSETS));
	}

	/**
	 * Gets the venue's job store.
	 *
	 * @return JobStore cursor wrapper
	 */
	public JobStore jobs() {
		return new JobStore(cursor.path(Covia.JOBS));
	}

	/**
	 * Gets the users cursor for Auth construction.
	 *
	 * @return Cursor at the :users level
	 */
	public ACursor<AMap<AString, AMap<AString, ACell>>> usersCursor() {
		return cursor.path(Covia.USERS);
	}

	/**
	 * Gets the auth cursor for AccessControl construction.
	 *
	 * @return Cursor at the :auth level
	 */
	public ACursor<AMap<Keyword, ACell>> authCursor() {
		return cursor.path(Covia.AUTH);
	}

	/**
	 * Gets the storage cursor for LatticeStorage construction.
	 *
	 * @return Cursor at the :storage level
	 */
	public ACursor<Index<ABlob, ABlob>> storageCursor() {
		return cursor.path(Covia.STORAGE);
	}

	/**
	 * Gets the venue's per-user data store.
	 *
	 * @return Users cursor wrapper
	 */
	public Users users() {
		return new Users(cursor.path(Covia.USER_DATA));
	}

	/**
	 * Gets the capabilities cursor for AccessControl.
	 *
	 * @return Cursor at the :caps level
	 */
	public ACursor<AMap<AString, ACell>> capsCursor() {
		return cursor.path(Covia.CAPS);
	}

	/**
	 * Gets the raw venue state value.
	 *
	 * @return Venue state (typically an Index), or null if uninitialised
	 */
	public ACell get() {
		return cursor.get();
	}

	/**
	 * Sets the venue state value. Used for initial bootstrapping.
	 *
	 * @param value New venue state
	 */
	public void set(ACell value) {
		cursor.set(value);
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
	 * <p>This is the recommended mode for Engine: multiple writes within
	 * a request (asset stores, job updates) go to the local fork, then
	 * a single sync() at the end of the request signs and propagates
	 * all changes at once.</p>
	 *
	 * <p>The forked cursor uses a local {@code Root} backed by
	 * {@code AtomicReference} — reads and writes are lock-free and
	 * never touch the parent cursor chain until sync.</p>
	 *
	 * @return A new VenueState backed by a forked cursor
	 */
	public VenueState fork() {
		return new VenueState(cursor.fork(), ownerKey);
	}

}
