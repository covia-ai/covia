package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;
import covia.lattice.Covia;

/**
 * Cursor wrapper for the venue's user data store.
 *
 * <p>Wraps a lattice cursor at the {@code :user-data} level
 * ({@code MapLattice<AString, KeyedLattice>}). Provides typed
 * accessors for per-user state, following the same pattern as
 * {@link AssetStore} and {@link JobStore}.</p>
 *
 * <p>Each user is identified by a DID string and gets an independent
 * {@link User} lattice component containing per-user jobs and
 * (in future phases) workspace, assets, and operations.</p>
 */
public class Users extends ALatticeComponent<AMap<AString, ACell>> {

	Users(ALatticeCursor<AMap<AString, ACell>> cursor) {
		super(cursor);
	}

	/**
	 * Gets the User for the given DID, or null if the user
	 * doesn't exist (no data written at that cursor path).
	 *
	 * @param did User DID string
	 * @return User wrapping the per-user cursor, or null
	 */
	public User get(AString did) {
		ALatticeCursor<ACell> userCursor = cursor.path(did);
		if (userCursor.get() == null) return null;
		return new User(userCursor, did);
	}

	/**
	 * Gets or creates the User for the given DID.
	 * If no data exists at the user's cursor path, initialises it
	 * with the USER lattice zero value.
	 *
	 * @param did User DID string
	 * @return User, never null
	 */
	public User ensure(AString did) {
		ALatticeCursor<ACell> userCursor = cursor.path(did);
		if (userCursor.get() == null) {
			userCursor.set(Covia.USER.zero());
		}
		return new User(userCursor, did);
	}

	/**
	 * Gets all user data for iteration (e.g. job recovery).
	 *
	 * @return Map of DID string to user state, or null if none
	 */
	public AMap<AString, ACell> getAll() {
		return cursor.get();
	}
}
