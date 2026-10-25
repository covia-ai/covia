package covia.venue;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import static convex.lattice.LatticeComponent.resolve;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.StampedCursor;
import convex.lattice.cursor.SignedCursor;
import convex.lattice.cursor.AForkableCursor;
import covia.lattice.Covia;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.venue.storage.LatticeStorage;

/**
 * Venue state and its domain components, backed by a signed lattice value.
 *
 * <p>Ordinary domain mutations are atomic and immediately visible locally.
 * Use {@link #transact} for compound changes: its callback receives an isolated
 * venue with the same domain API, including children bound to that draft.
 * Success commits the whole change; exceptions discard it and conflicts retry
 * the callback. Use only the supplied draft and keep external I/O outside it.
 * Local commit does not imply host publication or physical durability.</p>
 *
 * <pre>{@code
 * venue.transact(draft -> {
 *     var user = draft.users().ensure(did);
 *     user.ensureAgent("assistant", config, null);
 *     user.workspaceRoot().write(path, value);
 * });
 * }</pre>
 *
 * <p>{@link #fork()} creates an independent working copy for explicit lattice
 * reconciliation. Its {@link #sync()} uses whole-venue last-write-wins selection,
 * not transactional conflict retry. Use {@code transact} for local compound edits.
 * Only the venue defines this publication policy; children offer transactions
 * rather than independent publication boundaries.</p>
 */
public class VenueState extends ALatticeComponent<ACell> implements TransactionalComponent<ACell, VenueState> {
	@Override
	public VenueState withCursor(AForkableCursor<ACell> draft) {
		return new VenueState(draft, ownerKey, context);
	}

	private final AccountKey ownerKey;
	private final LatticeContext context;
	private final AForkableCursor<ACell> edits;

	public LatticeContext getContext() { return context; }

	@Override
	public AForkableCursor<ACell> cursor() { return edits; }

	@Override
	public ACell merge(ACell incoming) {
		return storage.updateAndGet(own -> Covia.VENUE.merge(context, own, incoming));
	}

	@Override
	protected ACell mergePublication(ACell own, ACell other) {
		return Covia.VENUE.merge(context, own, other);
	}

	/** Binds storage and its explicit signing/clock policy. */
	private VenueState(AForkableCursor<ACell> cursor, AccountKey ownerKey, LatticeContext context) {
		super(cursor);
		this.ownerKey = ownerKey;
		this.context = context;
		this.edits = StampedCursor.create(cursor, context::currentTimestamp, Covia::stampVenue);
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
	 * The component copies its parent's policy and explicitly composes the
	 * signing and timestamp boundaries. Publication belongs to the host.
	 */
	public static VenueState connect(CoviaApplication parent,
			AccountKey ownerKey) {
		if (parent == null) {
			throw new IllegalArgumentException("Parent component must not be null");
		}
		if (ownerKey == null) {
			throw new IllegalArgumentException("Venue owner key must not be null");
		}
		return fromRoot(parent.cursor(), ownerKey, parent.getContext());
	}

	/**
	 * Connects to an existing root lattice cursor by navigating to the
	 * venue level. The root cursor is typically held by Engine.
	 *
	 * @param root Root lattice cursor
	 * @param ownerKey Venue owner's account key
	 * @param context Signing and timestamp policy for this component
	 * @return VenueState connected to the root cursor
	 */
	public static VenueState fromRoot(AForkableCursor<?> root, AccountKey ownerKey, LatticeContext context) {
		var owners = resolve(root, Covia.ROOT, Covia.GRID, Covia.VENUES);
		var signing = Covia.VENUE_OWNERS.signingFor(ownerKey);
		var signed = SignedCursor.<ACell>create(resolve(owners, Covia.VENUE_OWNERS, ownerKey), value -> signing.sign(context, value));
		return new VenueState(signed, ownerKey, context);
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
		return new AssetStore(resolve(cursor(), Covia.VENUE, Covia.ASSETS));
	}

	/**
	 * Gets the venue's per-user data store.
	 *
	 * @return Users cursor wrapper
	 */
	public Users users() {
		return new Users(context, resolve(cursor(), Covia.VENUE, Covia.USER_DATA));
	}

	/** Atomically provisions account state and its login directory row. */
	public AMap<AString, ACell> provisionLogin(AString id, AString defaultDID,
			AMap<AString, ACell> profile) {
		Objects.requireNonNull(id);
		Objects.requireNonNull(defaultDID);
		Objects.requireNonNull(profile);
		var result = new java.util.concurrent.atomic.AtomicReference<AMap<AString, ACell>>();
		transact(draft -> {
			AMap<AString, ACell> previous = draft.auth().getUser(id);
			AString did = previous == null ? null : RT.ensureString(previous.get(Fields.DID));
			if (did == null) did = defaultDID;
			draft.users().create(did);
			AMap<AString, ACell> updated = previous == null ? Maps.empty() : previous;
			for (var entry : profile.entrySet()) updated = updated.assoc(entry.getKey(), entry.getValue());
			draft.auth().putUser(id, updated.assoc(Fields.DID, did));
			result.set(draft.auth().getUser(id));
		});
		return result.get();
	}

	/**
	 * Commits wake intent and its scheduled event together. No timer or job is
	 * touched, including when called inside an enclosing venue transaction.
	 */
	public void setAgentThreadWakeTime(AString owner, AString agentId,
			AgentState.ThreadKind kind, Blob threadId, long wakeTime) {
		User user = users().get(owner);
		if (user == null) return;
		setAgentThreadWakeTime(owner, agentId, kind, threadId, wakeTime, RT.getIn(user.get(), Users.GENERATION));
	}

	/** Runtime calls retain the generation observed before handing work to the timer. */
	void setAgentThreadWakeTime(AString owner, AString agentId,
			AgentState.ThreadKind kind, Blob threadId, long wakeTime, ACell generation) {
		transact(draft -> {
			User user = draft.users().get(owner);
			if (user == null || !Objects.equals(generation, RT.getIn(user.get(), Users.GENERATION))) {
				throw new AuthException(Users.ACCOUNT_DELETED);
			}
			AgentState agent = user.agent(agentId);
			if (agent != null && agent.setThreadWakeTime(kind, threadId, wakeTime)) {
				draft.reconcileAgentWake(owner, agent);
			}
		});
	}

	/** Rebuilds the event from existing wake intent, atomically with its handle. */
	public boolean rescheduleAgentWake(AString owner, AString agentId) {
		boolean[] armed = {false};
		transact(draft -> {
			User user = draft.users().get(owner);
			AgentState agent = user == null ? null : user.agent(agentId);
			armed[0] = agent != null && draft.reconcileAgentWake(owner, agent);
		});
		return armed[0];
	}

	/** Agent intent and event replacement share this private venue draft. */
	private boolean reconcileAgentWake(AString owner, AgentState agent) {
		Blob handle = schedule().replaceAgentWake(owner, agent.getAgentId(), agent.wakeHandle(), agent.earliestWake());
		agent.setWakeHandle(handle);
		return handle != null;
	}

	/** Atomically erases the namespace and login aliases, retaining revocation evidence. */
	@SuppressWarnings("unchecked")
	DeletedUser deleteUser(AString did, AString actor) {
		var now = getContext().currentTimestamp();
		ACell tombstone = Maps.of(Fields.DID, did, Users.DELETED_AT, now, Users.DELETED_BY, actor);
		ACell before = cursor().getAndUpdate(value -> {
			AMap<ACell, ACell> venue = (AMap<ACell, ACell>) value;
			AMap<AString, ACell> users = RT.ensureMap(venue.get(Covia.USER_DATA));
			ACell previous = users == null ? null : users.get(did);
			if (previous == null) throw new IllegalArgumentException(UNKNOWN_USER);
			if (RT.getIn(previous, Covia.K_DELETED) != null) {
				AMap<AString, ACell> record = RT.ensureMap(previous);
				return venue.assoc(Covia.USER_DATA, users.assoc(did,
					record.assoc(Users.DELETING, convex.core.data.prim.CVMBool.TRUE)));
			}
			venue = venue.assoc(Covia.USER_DATA, users.assoc(did, Maps.of(Covia.K_DELETED, tombstone,
				Users.DELETING, convex.core.data.prim.CVMBool.TRUE)));
			AMap<AString, ACell> directory = RT.ensureMap(venue.get(Covia.USERS));
			if (directory != null) venue = venue.assoc(Covia.USERS, AuthState.eraseUser(directory, did, actor, now));
			return venue;
		});
		ACell previous = RT.getIn(before, Covia.USER_DATA, did);
		ACell existing = RT.getIn(previous, Covia.K_DELETED);
		return new DeletedUser(existing != null ? existing : tombstone,
			existing == null ? RT.ensureMap(previous) : null);
	}

	record DeletedUser(ACell tombstone, AMap<AString, ACell> previousUser) {}

	/** Adapter storage obtained from a transaction draft stays in that draft. */
	public AdapterWorkspace adapterWorkspace(String name) {
		return new AdapterWorkspace(this, RT.ensureString(RT.getIn(get(), Covia.DID)), name);
	}

	private User venueUser() {
		return users().ensure(RT.ensureString(RT.getIn(get(), Covia.DID)));
	}

	private static ACell[] deletionPath(String name) {
		return covia.lattice.WorkspaceRoot.parseStringPath("w/user-deletions/" + name);
	}

	/** Only unfinished work is indexed here; no user/job scan is needed on startup. */
	AMap<AString, ACell> pendingUserDeletions(String name) {
		AMap<AString, ACell> pending = RT.ensureMap(venueUser().readInternalPath(deletionPath(name)));
		return pending == null ? Maps.empty() : pending;
	}

	DeletedUser beginUserDeletion(AString did, AString actor,
			java.util.Map<String, java.util.function.Consumer<AdapterWorkspace>> cleanups) {
		DeletedUser[] result = new DeletedUser[1];
		transact(draft -> {
			DeletedUser deleted = draft.deleteUser(did, actor);
			result[0] = deleted;
			Set<String> names = new LinkedHashSet<>(cleanups.keySet());
			AMap<AString, ACell> stored = RT.ensureMap(draft.venueUser().readInternalPath(
				covia.lattice.WorkspaceRoot.parseStringPath("w/adapters")));
			if (stored != null) for (AString name : stored.keySet()) names.add(name.toString());
			// Repeating deletion also repairs tombstones made before adapter hooks existed.
			for (String name : names) {
				AdapterWorkspace state = draft.adapterWorkspace(name);
				var cleanup = cleanups.get(name);
				if (cleanup != null) cleanup.accept(state);
				state.delete(state.userPath(did, ""));
				draft.markUserDeletion(name, did, deleted.tombstone());
			}
			draft.markUserDeletion("_venue", did, deleted.tombstone());
		});
		return result[0];
	}

	private void markUserDeletion(String name, AString did, ACell tombstone) {
		venueUser().writeInternalPath(deletionPath(name), pendingUserDeletions(name).assoc(did, tombstone));
	}

	/** Acknowledge exactly this deletion, opening recreation only when all work is done. */
	void finishUserDeletion(String name, AString did, ACell tombstone) {
		transact(draft -> {
			AMap<AString, ACell> pending = draft.pendingUserDeletions(name);
			if (!Objects.equals(tombstone, pending.get(did))) return;
			pending = pending.dissoc(did);
			User host = draft.venueUser();
			if (pending.isEmpty()) host.deleteInternalPath(deletionPath(name));
			else host.writeInternalPath(deletionPath(name), pending);
			AMap<AString, ACell> all = RT.ensureMap(host.readInternalPath(
				covia.lattice.WorkspaceRoot.parseStringPath("w/user-deletions")));
			if (all != null) for (ACell value : all.values()) {
				if (RT.getIn(value, did) != null) return;
			}
			draft.users().finishDeletion(did);
		});
	}

	public static final String UNKNOWN_USER = "User is not registered at this venue";

	/** Stored authentication directory; provider/runtime policy belongs to Auth. */
	public AuthState auth() {
		return new AuthState(context, resolve(cursor(), Covia.VENUE, Covia.USERS));
	}

	/** Stored schedule; timer and execution lifecycle belong to Scheduler. */
	public ScheduleState schedule() {
		return new ScheduleState(context, resolve(cursor(), Covia.VENUE, Covia.SCHEDULE));
	}

	/**
	 * Gets the venue's content-addressed blob storage.
	 *
	 * @return LatticeStorage instance backed by this venue's cursor
	 */
	public LatticeStorage contentStorage() {
		return new LatticeStorage(resolve(cursor(), Covia.VENUE, Covia.STORAGE));
	}

	/**
	 * Gets the raw venue state value.
	 *
	 * @return Map-shaped venue value, or null if uninitialised
	 */
	public ACell get() {
		return cursor().get();
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
		cursor().updateAndGet(current -> {
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
	 * Creates independent working state. Sync reconciles it with the destination
	 * using whole-venue LWW, which may select one complete concurrent version.
	 * For compound local edits with conflict retry, use {@link #transact}.
	 */
	@Override
	public VenueState fork() {
		return withCursor(storage.fork());
	}

}
