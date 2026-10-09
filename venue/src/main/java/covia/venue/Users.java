package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;
import covia.exception.AuthException;
import covia.lattice.Covia;
import convex.core.lang.RT;

/**
 * Cursor wrapper for the venue's user data store.
 *
 * <p>Wraps a lattice cursor at the {@code :user-data} level
 * ({@code MapLattice<AString, StringKeyedLattice>}). Provides typed
 * accessors for per-user state, following the same pattern as
 * {@link AssetStore}.</p>
 *
 * <p>Each user is identified by a DID string and gets an independent
 * {@link User} lattice component backed by a
 * {@link convex.lattice.generic.StringKeyedLattice} with AString keys
 * for JSON compatibility.</p>
 */
public class Users extends ALatticeComponent<AMap<AString, ACell>> {
	public static final String ACCOUNT_DELETED = "User account has been deleted";
	static final AString GENERATION = Strings.intern("generation");
	static final AString DELETING = Strings.intern("deleting");
	static final AString DELETED_AT = Strings.intern("deletedAt");
	static final AString DELETED_BY = Strings.intern("deletedBy");

	public boolean isDeleted(AString did) {
		return tombstone(did) != null;
	}

	public ACell tombstone(AString did) {
		return did == null ? null : RT.getIn(cursor.get(), did, Covia.K_DELETED);
	}

	public void requireNotDeleted(AString did) {
		if (isDeleted(did)) throw new AuthException(ACCOUNT_DELETED);
	}

	private static ACell initialise(ACell current) {
		if (RT.getIn(current, Covia.K_DELETED) != null) throw new AuthException(ACCOUNT_DELETED);
		return current != null ? current : Maps.empty();
	}

	Users(ALatticeComponent<?> parent, ALatticeCursor<AMap<AString, ACell>> cursor) {
		super(parent, cursor);
	}

	/**
	 * Gets the User for the given DID, or null if the user
	 * doesn't exist (no data written at that cursor path).
	 *
	 * @param did User DID string
	 * @return User wrapping the per-user cursor, or null
	 */
	public User get(String did) {
		return get(Strings.create(did));
	}

	public User get(AString did) {
		ALatticeCursor<ACell> userCursor = cursor.path(did);
		ACell current = userCursor.get();
		if (current == null || RT.getIn(current, Covia.K_DELETED) != null) return null;
		return new User(this, new AccountCursor(userCursor), did);
	}

	/**
	 * Gets or creates the User for the given DID.
	 * If no data exists at the user's cursor path, initialises it
	 * with the USER lattice zero value.
	 *
	 * @param did User DID string
	 * @return User, never null
	 */
	public User ensure(String did) {
		return ensure(Strings.create(did));
	}

	public User ensure(AString did) {
		ALatticeCursor<ACell> userCursor = cursor.path(did);
		// Atomic init: read-then-set is racy under concurrent first-touches —
		// a late reader could observe null and set(zero), clobbering an earlier
		// writer's committed user data (jobs, secrets, agents).
		userCursor.updateAndGet(Users::initialise);
		return new User(this, new AccountCursor(userCursor), did);
	}

	/**
	 * Atomically creates an empty registration for a DID if it is not already
	 * present. Unlike {@link #ensure(AString)}, this reports whether the call
	 * actually created the record, which makes administrative provisioning
	 * idempotent and observable.
	 * A completed deletion may be explicitly reprovisioned; its new generation
	 * invalidates every cursor retained from the previous account lifecycle.
	 *
	 * @param did user DID
	 * @return true when a new registration was created
	 */
	public boolean create(AString did) {
		ALatticeCursor<ACell> userCursor = cursor.path(did);
		ACell fresh = Maps.of(GENERATION, Strings.create(java.util.UUID.randomUUID().toString()));
		ACell previous = userCursor.getAndUpdate(
			current -> {
				if (RT.getIn(current, DELETING) != null) throw new AuthException(ACCOUNT_DELETED);
				return RT.getIn(current, Covia.K_DELETED) != null ? fresh : initialise(current);
			});
		return previous == null || RT.getIn(previous, Covia.K_DELETED) != null;
	}

	/** Opens recreation only after runtime cleanup has completed. */
	void finishDeletion(AString did) {
		cursor.path(did).updateAndGet(current -> {
			AMap<AString, ACell> record = RT.ensureMap(current);
			return record != null ? record.dissoc(DELETING) : current;
		});
	}

	/**
	 * Gets all user data for iteration (e.g. job recovery).
	 *
	 * @return Map of DID string to user state, or null if none
	 */
	public AMap<AString, ACell> getAll() {
		AMap<AString, ACell> all = cursor.get();
		if (all == null) return null;
		for (var entry : all.entrySet()) {
			if (RT.getIn(entry.getValue(), Covia.K_DELETED) != null) all = all.dissoc(entry.getKey());
		}
		return all;
	}
}
