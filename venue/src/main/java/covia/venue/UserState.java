package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.lattice.cursor.ALatticeCursor;
import covia.lattice.Covia;

/**
 * Cursor wrapper for a single user's state within a venue.
 *
 * <p>Wraps a lattice cursor at {@code :user-data → <did>} within the
 * venue state. Provides typed accessors for per-user data. Created by
 * {@link VenueState#user(AString)} (returns null if the user doesn't
 * exist) or {@link VenueState#ensureUser(AString)} (creates if needed).</p>
 *
 * <p>Follows the same lattice app wrapper pattern as {@link AssetStore}
 * and {@link JobStore}. The per-user lattice is a KeyedLattice
 * ({@link Covia#USER}) containing:</p>
 * <ul>
 *   <li>{@code :jobs} — MapLattice of user's job references (LWW merge)</li>
 * </ul>
 *
 * <p>Future phases will add :workspace, :assets, :ops.</p>
 */
public class UserState {

	private final ALatticeCursor<ACell> cursor;
	private final AString did;

	UserState(ALatticeCursor<ACell> cursor, AString did) {
		this.cursor = cursor;
		this.did = did;
	}

	/**
	 * Gets the user's DID string.
	 *
	 * @return User DID
	 */
	public AString getDID() {
		return did;
	}

	/**
	 * Gets the user's job store (per-user job references, LWW merge).
	 *
	 * @return JobStore wrapping the user's :jobs cursor
	 */
	public JobStore jobs() {
		return new JobStore(cursor.path(Covia.JOBS));
	}

	/**
	 * Gets the raw per-user state value.
	 *
	 * @return User state, or null if uninitialised
	 */
	public ACell get() {
		return cursor.get();
	}

	/**
	 * Returns the underlying lattice cursor for direct operations.
	 *
	 * @return Cursor at the per-user level
	 */
	public ALatticeCursor<ACell> cursor() {
		return cursor;
	}
}
