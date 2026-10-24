package covia.venue;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import convex.auth.did.DID;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeContext;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.api.Fields;

/** Stored authentication directory and key lifecycle rules. No providers or runtime services. */
public final class AuthState extends ALatticeComponent<AMap<AString, AMap<AString, ACell>>>
		implements TransactionalComponent<AMap<AString, AMap<AString, ACell>>, AuthState> {
	private final LatticeContext context;

	AuthState(LatticeContext context, AForkableCursor<AMap<AString, AMap<AString, ACell>>> cursor) {
		super(cursor);
		this.context = context;
	}

	@Override
	public AuthState withCursor(AForkableCursor<AMap<AString, AMap<AString, ACell>>> draft) {
		return new AuthState(context, draft);
	}

	private LatticeContext getContext() { return context; }
	public static final AString ACTIVE = Strings.intern("active");
	public static final AString REVOKED = Strings.intern("revoked");
	/** Refusal messages; tests assert against these, never against the wording. */
	public static final String CANNOT_REVOKE_FINAL_KEY = "Cannot revoke the final active authentication key";
	public static final String KEY_ALREADY_BOUND = "Authentication key is already bound to named user ";

	/**
	 * Get a user record by ID
	 * @param id User identifier as AString (e.g. "alice_gmail_com")
	 * @return User record map, or null if not found
	 */
	public AMap<AString, ACell> getUser(AString id) {
		AMap<AString, AMap<AString, ACell>> usersMap = getUsers();
		if (usersMap == null) return null;
		return (AMap<AString, ACell>) usersMap.get(id);
	}

	/**
	 * Store or update a user record. Adds an "updated" timestamp automatically.
	 * @param id User identifier as AString (e.g. "alice_gmail_com")
	 * @param record User record map (should contain "did" and any other fields)
	 */
	public void putUser(AString id, AMap<AString, ACell> record) {
		CVMLong timestamp = getContext().currentTimestamp();
		cursor().updateAndGet(current -> {
			@SuppressWarnings("unchecked")
			AMap<AString, AMap<AString, ACell>> m = (AMap<AString, AMap<AString, ACell>>) (AMap<?,?>) RT.castMap(current);
			if (m == null) m = Maps.empty();
			AMap<AString, ACell> previous = m.get(id);
			return m.assoc(id, stampUser(record, previous, timestamp));
		});
	}

	/** Atomically updates one user row without replacing unrelated fields. */
	public AMap<AString, ACell> updateUser(AString id,
			UnaryOperator<AMap<AString, ACell>> updater) {
		CVMLong timestamp = getContext().currentTimestamp();
		AMap<AString, AMap<AString, ACell>> result = cursor().updateAndGet(current -> {
			@SuppressWarnings("unchecked")
			AMap<AString, AMap<AString, ACell>> users =
				(AMap<AString, AMap<AString, ACell>>) (AMap<?, ?>) RT.castMap(current);
			if (users == null) users = Maps.empty();
			AMap<AString, ACell> previous = users.get(id);
			AMap<AString, ACell> updated = updater.apply(previous);
			if (updated == null || Objects.equals(previous, updated)) return users;
			return users.assoc(id, stampUser(updated, previous, timestamp));
		});
		return (result != null) ? result.get(id) : null;
	}

	static AMap<AString, ACell> stampUser(AMap<AString, ACell> record,
			AMap<AString, ACell> previous, CVMLong proposed) {
		if (previous != null) {
			ACell oldValue = previous.get(Fields.UPDATED);
			if (oldValue instanceof CVMLong old
					&& old.longValue() > proposed.longValue()) proposed = old;
		}
		return record.assoc(Fields.UPDATED, proposed);
	}

	/**
	 * Creates the venue-owned authentication-directory row for a managed user,
	 * or verifies the existing row still names the same stable DID.
	 *
	 * @return true when the row was created
	 */
	public boolean ensureManagedUser(AString id, AString did) {
		CVMLong timestamp = getContext().currentTimestamp();
		AtomicBoolean created = new AtomicBoolean(false);
		cursor().updateAndGet(current -> {
			@SuppressWarnings("unchecked")
			AMap<AString, AMap<AString, ACell>> users =
				(AMap<AString, AMap<AString, ACell>>) (AMap<?, ?>) RT.castMap(current);
			if (users == null) users = Maps.empty();
			AMap<AString, ACell> existing = users.get(id);
			if (existing != null) {
				created.set(false);
				AString stored = RT.ensureString(existing.get(Fields.DID));
				if (!did.equals(stored)) {
					throw new IllegalStateException("Named user " + id
						+ " is already bound to a different DID: " + stored);
				}
				return users;
			}
			created.set(true);
			AMap<AString, ACell> record = stampUser(
				Maps.of(Fields.DID, did, Fields.NAME, id), null, timestamp);
			return users.assoc(id, record);
		});
		return created.get();
	}

	/** Public authenticator lifecycle records for one named user. */
	public AMap<AString, ACell> getAuthenticationKeys(AString id) {
		AMap<AString, ACell> record = getUser(id);
		if (record == null) return Maps.empty();
		AMap<AString, ACell> keys = RT.ensureMap(record.get(Fields.AUTHENTICATION_KEYS));
		return (keys != null) ? keys : Maps.empty();
	}

	/** True when the named user's key is currently admitted for authentication. */
	public boolean isAuthenticationKeyActive(AString id, AString keyDID) {
		AMap<AString, ACell> entry =
			RT.ensureMap(getAuthenticationKeys(id).get(keyDID));
		return entry != null && ACTIVE.equals(entry.get(Fields.STATUS));
	}

	/** Active public key DIDs, in deterministic lattice-map order. */
	public AVector<ACell> getActiveAuthenticationKeys(AString id) {
		AVector<ACell> result = Vectors.empty();
		for (var entry : getAuthenticationKeys(id).entrySet()) {
			AMap<AString, ACell> state = RT.ensureMap(entry.getValue());
			if (state != null && ACTIVE.equals(state.get(Fields.STATUS))) {
				result = result.conj(entry.getKey());
			}
		}
		return result;
	}

	/**
	 * Adds one public {@code did:key} authenticator. A key may have history under
	 * only one named user on this venue, and a revoked key is never silently
	 * reactivated.
	 *
	 * @return true when a new active binding was added; false when already active
	 */
	private boolean addAuthenticationKeyInDraft(AString id, AString keyDID,
			AString actorDID, AString label) {
		requireValidAuthenticationKey(keyDID);
		requireActorDID(actorDID);
		AMap<AString, ACell> user = getUser(id);
		if (user == null) throw new IllegalArgumentException("Unknown named user: " + id);
		requireKeyNotBoundElsewhere(id, keyDID);

		AMap<AString, ACell> keys = getAuthenticationKeys(id);
		AMap<AString, ACell> existing = RT.ensureMap(keys.get(keyDID));
		if (existing != null) {
			if (ACTIVE.equals(existing.get(Fields.STATUS))) return false;
			throw new IllegalArgumentException(
				"Revoked authentication keys cannot be reactivated");
		}

		CVMLong now = context.currentTimestamp();
		AMap<AString, ACell> state = Maps.of(
			Fields.STATUS, ACTIVE,
			Fields.ADDED_AT, now,
			Fields.ADDED_BY, actorDID);
		if (label != null && !label.isEmpty()) state = state.assoc(Fields.LABEL, label);
		AMap<AString, ACell> updatedKeys = keys.assoc(keyDID, state);
		updateUser(id, current -> current.assoc(Fields.AUTHENTICATION_KEYS, updatedKeys));
		return true;
	}

	/**
	 * Atomically installs an initial set of public authenticators. The complete
	 * set is validated before the user record is changed.
	 *
	 * @return number of newly installed keys
	 */
	private long addAuthenticationKeysInDraft(AString id,
			AVector<ACell> keyDIDs, AString actorDID) {
		if (keyDIDs == null || keyDIDs.isEmpty()) {
			throw new IllegalArgumentException("At least one authentication key is required");
		}
		requireActorDID(actorDID);
		AMap<AString, ACell> user = getUser(id);
		if (user == null) throw new IllegalArgumentException("Unknown named user: " + id);

		AMap<AString, ACell> keys = getAuthenticationKeys(id);
		java.util.HashSet<AString> requested = new java.util.HashSet<>();
		for (long i = 0; i < keyDIDs.count(); i++) {
			AString keyDID = RT.ensureString(keyDIDs.get(i));
			requireValidAuthenticationKey(keyDID);
			if (!requested.add(keyDID)) {
				throw new IllegalArgumentException("Duplicate authentication key");
			}
			requireKeyNotBoundElsewhere(id, keyDID);
			AMap<AString, ACell> existing = RT.ensureMap(keys.get(keyDID));
			if (existing != null && !ACTIVE.equals(existing.get(Fields.STATUS))) {
				throw new IllegalArgumentException(
					"Revoked authentication keys cannot be reactivated");
			}
		}

		long added = 0;
		CVMLong now = context.currentTimestamp();
		for (AString keyDID : requested) {
			if (keys.containsKey(keyDID)) continue;
			keys = keys.assoc(keyDID, Maps.of(
				Fields.STATUS, ACTIVE,
				Fields.ADDED_AT, now,
				Fields.ADDED_BY, actorDID));
			added++;
		}
		if (added > 0) {
			AMap<AString, ACell> updatedKeys = keys;
			updateUser(id, current -> current.assoc(Fields.AUTHENTICATION_KEYS, updatedKeys));
		}
		return added;
	}

	/**
	 * Revokes one authenticator while retaining its audit tombstone.
	 *
	 * @param allowLast true only for direct venue recovery authority
	 * @return true when an active key was revoked; false when absent/already revoked
	 */
	private boolean revokeAuthenticationKeyInDraft(AString id, AString keyDID,
			AString actorDID, boolean allowLast) {
		requireActorDID(actorDID);
		AMap<AString, ACell> user = getUser(id);
		if (user == null) throw new IllegalArgumentException("Unknown named user: " + id);
		AMap<AString, ACell> keys = getAuthenticationKeys(id);
		AMap<AString, ACell> existing = RT.ensureMap(keys.get(keyDID));
		if (existing == null || REVOKED.equals(existing.get(Fields.STATUS))) return false;
		if (!ACTIVE.equals(existing.get(Fields.STATUS))) {
			throw new IllegalStateException("Malformed authentication key status");
		}
		if (!allowLast && getActiveAuthenticationKeys(id).count() <= 1) {
			throw new IllegalArgumentException(
				CANNOT_REVOKE_FINAL_KEY);
		}
		AMap<AString, ACell> revoked = existing
			.assoc(Fields.STATUS, REVOKED)
			.assoc(Fields.REVOKED_AT, context.currentTimestamp())
			.assoc(Fields.REVOKED_BY, actorDID);
		AMap<AString, ACell> updatedKeys = keys.assoc(keyDID, revoked);
		updateUser(id, current -> current.assoc(Fields.AUTHENTICATION_KEYS, updatedKeys));
		return true;
	}

	private void requireKeyNotBoundElsewhere(AString id, AString keyDID) {
		AMap<AString, AMap<AString, ACell>> users = getUsers();
		if (users == null) return;
		for (var other : users.entrySet()) {
			if (other.getKey().equals(id)) continue;
			AMap<AString, ACell> otherKeys =
				RT.ensureMap(other.getValue().get(Fields.AUTHENTICATION_KEYS));
			if (otherKeys != null && otherKeys.containsKey(keyDID)) {
				throw new IllegalArgumentException(
					KEY_ALREADY_BOUND + other.getKey());
			}
		}
	}

	private static void requireActorDID(AString actorDID) {
		if (actorDID == null) {
			throw new IllegalArgumentException("Authentication key actor DID is required");
		}
	}

	static void requireValidAuthenticationKey(AString keyDID) {
		if (keyDID == null || !keyDID.toString().startsWith("did:key:")) {
			throw new IllegalArgumentException("Authentication key must be a did:key");
		}
		String multikey = keyDID.toString().substring("did:key:".length());
		try {
			if (DID.fromString(keyDID.toString()) == null
					|| Multikey.decodePublicKey(multikey) == null) {
				throw new IllegalArgumentException("Invalid did:key authentication key");
			}
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Invalid did:key authentication key", e);
		}
	}

	/**
	 * Get all users from the lattice cursor
	 * @return Map of user ID to user record
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, AMap<AString, ACell>> getUsers() {
		return (AMap<AString, AMap<AString, ACell>>) (AMap<?,?>) RT.castMap(cursor().get());
	}

	/** Atomic directory mutation, including cross-user key constraints. */
	public boolean addAuthenticationKey(AString id, AString keyDID, AString actorDID, AString label) {
		var result = new AtomicBoolean();
		transact(draft -> result.set(draft.addAuthenticationKeyInDraft(id, keyDID, actorDID, label)));
		return result.get();
	}

	/** Atomic directory mutation, including cross-user key constraints. */
	public long addAuthenticationKeys(AString id, AVector<ACell> keyDIDs, AString actorDID) {
		var result = new java.util.concurrent.atomic.AtomicLong();
		transact(draft -> result.set(draft.addAuthenticationKeysInDraft(id, keyDIDs, actorDID)));
		return result.get();
	}

	/** Atomic directory mutation, including cross-user key constraints. */
	public boolean revokeAuthenticationKey(AString id, AString keyDID, AString actorDID, boolean allowLast) {
		var result = new AtomicBoolean();
		transact(draft -> result.set(draft.revokeAuthenticationKeyInDraft(id, keyDID, actorDID, allowLast)));
		return result.get();
	}
	/** Erase profile aliases while retaining public authenticator revocations. */
	static AMap<AString, ACell> eraseUser(AMap<AString, ACell> directory, AString did, AString actor, CVMLong now) {
		AMap<AString, ACell> revoked = Maps.empty();
		for (var row : directory.entrySet()) {
			if (!did.equals(RT.getIn(row.getValue(), Fields.DID))) continue;
			AMap<AString, ACell> keys = RT.ensureMap(RT.getIn(row.getValue(), Fields.AUTHENTICATION_KEYS));
			if (keys != null) for (var key : keys.entrySet()) {
				revoked = revoked.assoc(key.getKey(), Maps.of(Fields.STATUS, REVOKED,
					Fields.REVOKED_AT, now, Fields.REVOKED_BY, actor));
			}
			directory = directory.dissoc(row.getKey());
		}
		return revoked.isEmpty() ? directory : directory.assoc(did,
			Maps.of(Fields.DID, did, Fields.AUTHENTICATION_KEYS, revoked));
	}
}
