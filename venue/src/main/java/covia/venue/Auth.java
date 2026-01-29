package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.cursor.ACursor;

/**
 * Authentication and user management for a Covia venue.
 *
 * <p>Owns the user database backed by a lattice cursor. Users are stored
 * as maps keyed by user ID (e.g. "alice_gmail_com") with fields like
 * "did", "email", "name", "provider", "updated".
 *
 * <p>Created and owned by {@link Engine}.
 */
public class Auth {

	private final ACursor<AMap<AString, AMap<AString, ACell>>> users;

	public Auth(ACursor<AMap<AString, AMap<AString, ACell>>> users) {
		this.users = users;
	}

	/**
	 * Get a user record by ID
	 * @param id User identifier (e.g. "alice_gmail_com")
	 * @return User record map, or null if not found
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getUser(String id) {
		AMap<AString, AMap<AString, ACell>> usersMap = getUsers();
		if (usersMap == null) return null;
		return (AMap<AString, ACell>) usersMap.get(Strings.create(id));
	}

	/**
	 * Store or update a user record. Adds an :updated timestamp automatically.
	 * @param id User identifier (e.g. "alice_gmail_com")
	 * @param record User record map (should contain "did" and any other fields)
	 */
	public synchronized void putUser(String id, AMap<AString, ACell> record) {
		record = record.assoc(Strings.create("updated"), CVMLong.create(Utils.getCurrentTimestamp()));
		AMap<AString, AMap<AString, ACell>> usersMap = getUsers();
		if (usersMap == null) usersMap = Maps.empty();
		setUsers(usersMap.assoc(Strings.create(id), record));
	}

	/**
	 * Get all users from the lattice cursor
	 * @return Map of user ID to user record
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, AMap<AString, ACell>> getUsers() {
		return (AMap<AString, AMap<AString, ACell>>) (AMap<?,?>) RT.ensureMap(this.users.get());
	}

	private void setUsers(AMap<AString, AMap<AString, ACell>> usersMap) {
		this.users.set(usersMap);
	}
}
