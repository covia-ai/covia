package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.AForkableCursor;
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
public class Users extends ALatticeComponent<AMap<AString, ACell>> implements TransactionalComponent<AMap<AString, ACell>, Users> {
	@Override
	public Users withCursor(AForkableCursor<AMap<AString, ACell>> draft) {
		return new Users(context, draft);
	}

	private final LatticeContext context;

	public LatticeContext getContext() { return context; }

	public static final String ACCOUNT_DELETED = "User account has been deleted";
	static final AString GENERATION = Strings.intern("generation");
	static final AString DELETING = Strings.intern("deleting");
	static final AString DELETED_AT = Strings.intern("deletedAt");
	static final AString DELETED_BY = Strings.intern("deletedBy");

	public boolean isDeleted(AString did) {
		return tombstone(did) != null;
	}

	boolean isDeleting(AString did) {
		return RT.getIn(cursor().get(), did, DELETING) != null;
	}

	public ACell tombstone(AString did) {
		return did == null ? null : RT.getIn(cursor().get(), did, Covia.K_DELETED);
	}

	public void requireNotDeleted(AString did) {
		if (isDeleted(did)) throw new AuthException(ACCOUNT_DELETED);
	}

	/** The same lifecycle fence for account cursors and adapter-owned worker state. */
	static void requireCurrent(ACell record, ACell generation) {
		if (RT.getIn(record, Covia.K_DELETED) != null
				|| !java.util.Objects.equals(generation, RT.getIn(record, GENERATION))) {
			throw new AuthException(ACCOUNT_DELETED);
		}
	}

	private static ACell initialise(ACell current) {
		if (RT.getIn(current, Covia.K_DELETED) != null) throw new AuthException(ACCOUNT_DELETED);
		return current != null ? current : Maps.empty();
	}

	Users(LatticeContext context, AForkableCursor<AMap<AString, ACell>> cursor) {
		super(cursor);
		this.context = context;
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
		AForkableCursor<ACell> userCursor = cursor().withDefault(Maps.empty()).path(did);
		ACell current = userCursor.get();
		if (current == null || RT.getIn(current, Covia.K_DELETED) != null) return null;
		return new User(context, new AccountCursor(userCursor), did);
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
		AForkableCursor<ACell> userCursor = cursor().withDefault(Maps.empty()).path(did);
		// Atomic init: read-then-set is racy under concurrent first-touches —
		// a late reader could observe null and set(zero), clobbering an earlier
		// writer's committed user data (jobs, secrets, agents).
		userCursor.updateAndGet(Users::initialise);
		return new User(context, new AccountCursor(userCursor), did);
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
		AForkableCursor<ACell> userCursor = cursor().withDefault(Maps.empty()).path(did);
		ACell fresh = Maps.of(GENERATION, Strings.create(java.util.UUID.randomUUID().toString()));
		ACell previous = userCursor.getAndUpdate(
			current -> {
				if (RT.getIn(current, DELETING) != null) throw new AuthException(ACCOUNT_DELETED);
				return RT.getIn(current, Covia.K_DELETED) != null ? fresh : initialise(current);
			});
		return previous == null || RT.getIn(previous, Covia.K_DELETED) != null;
	}

	public static final String NOT_EMPTY = "The target account already holds user state";

	/** Lifecycle and activity bookkeeping of a record, never part of a user's state. */
	private static final AString[] FRAMEWORK_SLOTS = { GENERATION, DELETING, Covia.K_DELETED, Covia.K_META };

	private static AMap<AString, ACell> withoutSlots(AMap<AString, ACell> record) {
		for (AString slot : FRAMEWORK_SLOTS) record = record.dissoc(slot);
		return record;
	}

	/** A record's user state without its framework slots, or null for no live record. */
	static AMap<AString, ACell> content(ACell record) {
		AMap<AString, ACell> map = RT.ensureMap(record);
		if (map == null || map.get(Covia.K_DELETED) != null) return null;
		return withoutSlots(map);
	}

	/** A registered user's state without its framework slots, or null if not registered. */
	public AMap<AString, ACell> content(AString did) {
		AMap<AString, ACell> all = cursor().get();
		return all == null ? null : content(all.get(did));
	}

	/**
	 * Registers {@code did} if needed and fills it with user state. The account
	 * must hold none: its own framework slots are kept, and the write through
	 * {@link User} mints its {@code meta} as the account's first activity.
	 *
	 * @return true when this call registered the account
	 */
	boolean restore(AString did, AMap<AString, ACell> state) {
		AMap<AString, ACell> incoming = withoutSlots(state);
		boolean created = create(did);
		User user = get(did);
		if (user == null) throw new AuthException(ACCOUNT_DELETED);
		user.cursor().updateAndGet(current -> {
			AMap<AString, ACell> record = RT.ensureMap(current);
			if (record == null) record = Maps.empty();
			if (!withoutSlots(record).isEmpty()) throw new IllegalStateException(NOT_EMPTY);
			for (var entry : incoming.entrySet()) record = record.assoc(entry.getKey(), entry.getValue());
			return record;
		});
		return created;
	}

	/** Opens recreation only after runtime cleanup has completed. */
	void finishDeletion(AString did) {
		cursor().withDefault(Maps.empty()).path(did).updateAndGet(current -> {
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
		AMap<AString, ACell> all = cursor().get();
		if (all == null) return null;
		for (var entry : all.entrySet()) {
			if (RT.getIn(entry.getValue(), Covia.K_DELETED) != null) all = all.dissoc(entry.getKey());
		}
		return all;
	}
}
