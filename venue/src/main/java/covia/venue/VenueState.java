package covia.venue;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

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
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.store.AStore;
import covia.lattice.ACoviaComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.OwnedMap;
import convex.lattice.Timestamped;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import covia.lattice.Covia;
import covia.lattice.CoviaPath;
import covia.lattice.Position;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.NotFoundException;
import covia.venue.migration.MigrationStep;
import covia.venue.migration.VenueMigrations;
import covia.venue.storage.LatticeStorage;

/**
 * Authoritative state of a running venue application and its domain components.
 *
 * <p>Ordinary domain mutations are atomic and immediately visible locally.
 * Use {@link #execute} for compound changes, or {@link #transact} when the
 * change returns a result: the callback receives an isolated venue with the
 * same domain API, including children bound to that draft. Success commits the
 * whole change; exceptions discard it and conflicts retry the callback, so
 * {@code transact} returns what the committed run returned. Use only the
 * supplied draft and keep external I/O outside it. Local commit does not imply
 * host publication or physical durability.</p>
 *
 * <pre>{@code
 * venue.execute(draft -> {
 *     var user = draft.users().ensure(did);
 *     user.ensureAgent("assistant", config, null);
 *     user.workspace().write(CoviaPath.parse("assistant/ready"), value);
 * });
 * }</pre>
 *
 * <p>Whole-venue LWW selects complete snapshots for backup, replication and
 * failover. It does not combine arbitrarily forked executions: their state
 * invariants and external effects cannot in general be merged. One authoritative
 * running instance owns a venue; failover must fence the previous writer.
 * {@link #fork()} creates an independent snapshot, and {@link #sync()} selects
 * a snapshot rather than replaying operations. Live work uses transactions.
 * Children have no independent publication or merge boundary.</p>
 */
public class VenueState extends ACoviaComponent<ACell> implements TransactionalComponent<ACell, VenueState> {
	/** The venue schema version: the number of {@link VenueMigrations#steps}. */
	public static final long CURRENT_VERSION = VenueMigrations.VERSION;
	public static final String INVALID_STATE = "Venue state must be a keyword-keyed Index";
	public static final String INVALID_VERSION = "Venue :version must be a non-negative integer";
	public static final String UNSUPPORTED_VERSION = "Venue version %d is newer than supported version %d";
	public static final String UNSUPPORTED_ADAPTER_VERSION = "Adapter '%s' state version %d is newer than supported version %d";
	public static final String INVALID_ADAPTER_VERSION = "Adapter state version must be a non-negative integer: ";
	public static final String INVALID_STATE_VERSION = "Adapter state version must not be negative: ";
	@Override
	public VenueState withCursor(AForkableCursor<ACell> draft) {
		return new VenueState(draft, ownerKey, context);
	}

	private final AccountKey ownerKey;
	private final VenueContext context;
	/** The venue value as one timestamped record: local edits stamp it, merges keep the newest. */
	private final Timestamped<ACell> record;

	public VenueContext getContext() { return context; }

	@Override
	public AForkableCursor<ACell> cursor() { return record.cursor(); }

	@Override
	public ACell merge(ACell incoming) {
		return record.merge(incoming);
	}

	@Override
	protected ACell mergePublication(ACell own, ACell other) {
		return record.getLattice().merge(own, other);
	}

	/** Binds signed storage and the policy its components share. */
	private VenueState(AForkableCursor<ACell> cursor, AccountKey ownerKey, VenueContext context) {
		super(cursor);
		this.ownerKey = ownerKey;
		this.context = context;
		this.record = new Timestamped<>(cursor, Covia.VENUE, context::currentTimestamp, Covia::stampVenue);
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
	 * Connects a venue component beneath a containing Covia application. The
	 * component signs with the context's signer and shares the context with its
	 * children. Publication belongs to the host.
	 */
	public static VenueState connect(CoviaApplication parent,
			AccountKey ownerKey, VenueContext context) {
		if (parent == null) {
			throw new IllegalArgumentException("Parent component must not be null");
		}
		if (ownerKey == null) {
			throw new IllegalArgumentException("Venue owner key must not be null");
		}
		return connect(parent.venues(), ownerKey, context::sign, context);
	}

	/**
	 * Connects to the venue records under a Covia container, such as a root
	 * cursor held by a test or a store snapshot.
	 *
	 * @param root Cursor at the container holding {@code :grid}
	 * @param ownerKey Venue owner's account key
	 * @param signer Signs a venue value as the owner, or returns null when it
	 *        cannot; the owner's signing rule checks each result
	 * @param context Policy shared by the venue's components
	 * @return VenueState connected to the root cursor
	 */
	@SuppressWarnings("unchecked")
	public static VenueState fromRoot(AForkableCursor<?> root, AccountKey ownerKey,
			Function<ACell, SignedData<ACell>> signer, VenueContext context) {
		var venues = CoviaApplication.venueRecords((AForkableCursor<Index<Keyword, ACell>>) root);
		return connect(venues, ownerKey, signer, context);
	}

	private static VenueState connect(OwnedMap<ACell, ACell> venues, AccountKey ownerKey,
			Function<ACell, SignedData<ACell>> signer, VenueContext context) {
		return new VenueState(venues.authoringCursor(ownerKey, signer), ownerKey, context);
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
		if (!(root instanceof Index<?, ?> index)) return Set.of();

		ACell value = CoviaApplication.venueRecords(Cursors.root((Index<Keyword, ACell>) index)).cursor().getOrDefault(null);
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
	 * Schema versions held in a store for one venue.
	 *
	 * @param venue the venue's own {@code :version}, 0 for a legacy venue
	 * @param adapters the state version each versioned adapter has reached
	 */
	public record StoredVersions(long venue, Map<String, Long> adapters) {}

	/**
	 * Reads the schema versions an already-open store holds for one venue,
	 * without constructing an {@link Engine} and without writing. A caller
	 * compares them with {@link #CURRENT_VERSION} and each adapter's
	 * {@code stateVersion()} to learn whether starting a venue on the store will
	 * migrate it, or refuse it with an {@link UnsupportedVersionException}.
	 *
	 * <p>The store stays open and owned by the caller.</p>
	 *
	 * @param store Open Covia lattice store
	 * @param ownerKey The venue's account key
	 * @return the versions, or empty when the store holds no state for this venue
	 * @throws IOException if the persisted root cannot be read
	 */
	public static Optional<StoredVersions> peekVersions(AStore store, AccountKey ownerKey) throws IOException {
		ACell root = Objects.requireNonNull(store, "store").getRootData();
		if (!(root instanceof Index<?, ?>)) return Optional.empty();
		VenueState venue = fromRoot(Cursors.root(root), Objects.requireNonNull(ownerKey, "ownerKey"),
			value -> null, VenueContext.SYSTEM);
		if (venue.get() == null) return Optional.empty();
		return Optional.of(new StoredVersions(venue.version(), venue.adapterVersions()));
	}

	/**
	 * Gets the venue's asset store.
	 *
	 * @return AssetStore cursor wrapper
	 */
	public AssetStore assets() {
		return new AssetStore(level(Covia.ASSETS));
	}

	/** One level of the venue record; a change through an absent record creates it. */
	private <T extends ACell> AForkableCursor<T> level(Keyword key) {
		return cursor().withDefault(Index.none()).path(key);
	}

	/**
	 * Gets the venue's per-user data store.
	 *
	 * @return Users cursor wrapper
	 */
	public Users users() {
		return new Users(context, level(Covia.USER_DATA));
	}

	/**
	 * A path from the venue starts with its owner's DID, as an address does
	 * ({@code <DID>/<namespace>/<path>}), and {@link Users} resolves it.
	 */
	@Override
	public Position resolve(CoviaPath path) {
		return path.isEmpty() ? super.resolve(path) : users().resolve(path);
	}

	/** Atomically provisions account state and its login directory row. */
	public AMap<AString, ACell> provisionLogin(AString id, AString defaultDID,
			AMap<AString, ACell> profile) {
		Objects.requireNonNull(id);
		Objects.requireNonNull(defaultDID);
		Objects.requireNonNull(profile);
		return transact(draft -> {
			AMap<AString, ACell> previous = draft.auth().getUser(id);
			AString did = previous == null ? null : RT.ensureString(previous.get(Fields.DID));
			if (did == null) did = defaultDID;
			draft.users().create(did);
			AMap<AString, ACell> updated = previous == null ? Maps.empty() : previous;
			for (var entry : profile.entrySet()) updated = updated.assoc(entry.getKey(), entry.getValue());
			draft.auth().putUser(id, updated.assoc(Fields.DID, did));
			return draft.auth().getUser(id);
		});
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
		execute(draft -> {
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
		return transact(draft -> {
			User user = draft.users().get(owner);
			AgentState agent = user == null ? null : user.agent(agentId);
			return agent != null && draft.reconcileAgentWake(owner, agent);
		});
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
			if (previous == null) throw new NotFoundException(UNKNOWN_USER, did.toString());
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
	public AdapterState adapterState(String name) {
		return adapterState(name, null);
	}

	/** A worker view guarded against deletion or recreation of its owner. */
	public AdapterState adapterState(String name, AString owner) {
		AdapterState.validateName(name);
		moveLegacyAdapterState(name);
		return AdapterState.bind(this, name, owner);
	}

	private User venueUser() {
		AString did = RT.ensureString(RT.getIn(get(), Covia.DID));
		return did == null ? null : users().get(did);
	}

	private ACell legacyState(String path) {
		User host = venueUser();
		return host == null ? null : host.readInternalPath(covia.lattice.CoviaPath.parse(path));
	}

	public static final String LEGACY_STATE_CONFLICT = "Conflicting legacy venue state at ";

	/**
	 * Moves an adapter's value at the legacy {@code w/adapters/<name>} to
	 * {@code :adapters/<name>} exactly, removing the old copy in the same commit.
	 */
	private void moveLegacyAdapterState(String name) {
		String path = "w/adapters/" + name;
		AString key = Strings.create(name);
		requireSupportedVersion();
		if (legacyState(path) == null) return;
		execute(draft -> {
			draft.requireSupportedVersion();
			ACell legacy = draft.legacyState(path);
			if (legacy == null) return;
			AMap<ACell, ACell> adapters = RT.ensureMap(RT.getIn(draft.get(), Covia.ADAPTERS));
			boolean present = adapters != null && adapters.containsKey(key);
			if (present && !Objects.equals(adapters.get(key), legacy)) {
				// There is no generic merge for arbitrary adapter-owned values.
				throw new IllegalStateException(LEGACY_STATE_CONFLICT + path);
			}
			if (!present) draft.cursor().path(Covia.ADAPTERS).withDefault(Maps.empty()).path(key).set(legacy);
			draft.venueUser().deleteInternalPath(covia.lattice.CoviaPath.parse(path));
		});
	}

	/** Only unfinished work is indexed here; no user/job scan is needed on startup. */
	AMap<AString, ACell> pendingUserDeletions(String name) {
		AMap<AString, ACell> pending = RT.ensureMap(RT.getIn(get(), Covia.USER_DELETIONS, convex.core.data.Strings.create(name)));
		return pending == null ? Maps.empty() : pending;
	}

	DeletedUser beginUserDeletion(AString did, AString actor,
			java.util.Map<String, java.util.function.Consumer<AdapterState>> cleanups) {
		return transact(draft -> {
			DeletedUser deleted = draft.deleteUser(did, actor);
			// The user's job records go with the account, so their entries do too.
			draft.jobIndex().removeOwner(did);
			Set<String> names = new LinkedHashSet<>(cleanups.keySet());
			AMap<AString, ACell> stored = RT.ensureMap(RT.getIn(draft.get(), Covia.ADAPTERS));
			if (stored != null) for (AString name : stored.keySet()) names.add(name.toString());
			AMap<AString, ACell> legacy = RT.ensureMap(draft.legacyState("w/adapters"));
			if (legacy != null) for (AString name : legacy.keySet()) names.add(name.toString());
			// Repeating deletion also repairs tombstones made before adapter hooks existed.
			for (String name : names) {
				AdapterState state = draft.adapterState(name);
				var cleanup = cleanups.get(name);
				if (cleanup != null) cleanup.accept(state);
				draft.markUserDeletion(name, did, deleted.tombstone());
			}
			draft.markUserDeletion("_venue", did, deleted.tombstone());
			return deleted;
		});
	}

	private void markUserDeletion(String name, AString did, ACell tombstone) {
		AMap<AString, ACell> pending = pendingUserDeletions(name).assoc(did, tombstone);
		cursor().path(Covia.USER_DELETIONS).withDefault(Maps.empty()).path(convex.core.data.Strings.create(name)).set(pending);
	}

	/** Acknowledge exactly this deletion, opening recreation only when all work is done. */
	void finishUserDeletion(String name, AString did, ACell tombstone) {
		execute(draft -> {
			AMap<AString, ACell> pending = draft.pendingUserDeletions(name);
			if (!Objects.equals(tombstone, pending.get(did))) return;
			pending = pending.dissoc(did);
			AMap<AString, ACell> all = RT.ensureMap(RT.getIn(draft.get(), Covia.USER_DELETIONS));
			AString adapter = convex.core.data.Strings.create(name);
			all = pending.isEmpty() ? all.dissoc(adapter) : all.assoc(adapter, pending);
			draft.cursor().path(Covia.USER_DELETIONS).set(all);
			for (ACell value : all.values()) {
				if (RT.getIn(value, did) != null) return;
			}
			draft.users().finishDeletion(did);
		});
	}

	public static final String UNKNOWN_USER = "User is not registered at this venue";

	/** Stored authentication directory; provider/runtime policy belongs to Auth. */
	public AuthState auth() {
		return new AuthState(context, level(Covia.USERS));
	}

	/** The entries of unfinished jobs; the jobs themselves belong to JobManager. */
	public JobIndex jobIndex() {
		return new JobIndex(level(Covia.JOBS));
	}

	/** Stored schedule; timer and execution lifecycle belong to Scheduler. */
	public ScheduleState schedule() {
		return new ScheduleState(context, level(Covia.SCHEDULE));
	}

	/**
	 * Gets the venue's content-addressed blob storage.
	 *
	 * @return LatticeStorage instance backed by this venue's cursor
	 */
	public LatticeStorage contentStorage() {
		return new LatticeStorage(level(Covia.STORAGE));
	}

	/**
	 * Gets the raw venue state value.
	 *
	 * @return Map-shaped venue value, or null if uninitialised
	 */
	public ACell get() {
		return cursor().getOrDefault(null);
	}

	/** Stored format version. An absent field (or a fresh venue) is legacy version 0. */
	public long version() {
		ACell value = get();
		if (value == null) return 0;
		if (!(value instanceof Index<?, ?> record)) throw new IllegalStateException(INVALID_STATE);
		if (!record.containsKey(Covia.VERSION)) return 0;
		ACell version = record.get(Covia.VERSION);
		if (!(version instanceof CVMLong number) || number.longValue() < 0) {
			throw new IllegalStateException(INVALID_VERSION);
		}
		return number.longValue();
	}

	private long requireSupportedVersion() {
		long version = version();
		if (version > CURRENT_VERSION) throw unsupportedVersion(version);
		return version;
	}

	private static UnsupportedVersionException unsupportedVersion(long version) {
		return new UnsupportedVersionException(UNSUPPORTED_VERSION.formatted(version, CURRENT_VERSION),
			null, version, CURRENT_VERSION);
	}

	/** The state version an adapter has reached; 0 when none is recorded. */
	public long adapterVersion(String name) {
		AdapterState.validateName(name);
		ACell version = RT.getIn(get(), Covia.ADAPTER_VERSIONS, Strings.create(name));
		if (version == null) return 0;
		if (!(version instanceof CVMLong number) || number.longValue() < 0) {
			throw new IllegalStateException(INVALID_ADAPTER_VERSION + name);
		}
		return number.longValue();
	}

	/** Every recorded adapter state version, by adapter name. */
	public Map<String, Long> adapterVersions() {
		AMap<AString, ACell> recorded = RT.ensureMap(RT.getIn(get(), Covia.ADAPTER_VERSIONS));
		if (recorded == null) return Map.of();
		Map<String, Long> versions = new TreeMap<>();
		for (AString name : recorded.keySet()) versions.put(name.toString(), adapterVersion(name.toString()));
		return Collections.unmodifiableMap(versions);
	}

	/**
	 * The recorded state version of an adapter that supports {@code supported},
	 * refusing one recorded above it.
	 */
	long requireAdapterVersion(String name, long supported) {
		long version = adapterVersion(name);
		if (version > supported) {
			throw new UnsupportedVersionException(UNSUPPORTED_ADAPTER_VERSION.formatted(name, version, supported),
				name, version, supported);
		}
		return version;
	}

	/** Records the state version an adapter has reached. */
	void setAdapterVersion(String name, long version) {
		AdapterState.validateName(name);
		cursor().withDefault(Index.none()).path(Covia.ADAPTER_VERSIONS).withDefault(Maps.empty())
			.path(Strings.create(name)).set(CVMLong.create(version));
	}

	/** One pure step of an adapter's state, from {@code version} to {@code version + 1}. */
	@FunctionalInterface
	public interface StateStep {
		ACell apply(long version, ACell state);
	}

	/**
	 * The migration rules of one adapter's state at {@code :adapters/<name>}:
	 * the version it reads and writes, and its step from each earlier version.
	 *
	 * @param name Adapter name
	 * @param supported State version the adapter supports, 0 when unversioned
	 * @param step Pure step from one version to the next
	 */
	public record AdapterMigration(String name, long supported, StateStep step) {
		public AdapterMigration {
			AdapterState.validateName(name);
			if (supported < 0) throw new IllegalStateException(INVALID_STATE_VERSION + name);
			Objects.requireNonNull(step, "step");
		}
	}

	/**
	 * Migrates one adapter's state in its own transaction with its version. For
	 * an adapter registered after start-up; a current state is not rewritten.
	 */
	public void migrateAdapter(AdapterMigration migration) {
		if (adapterVersion(migration.name()) == migration.supported()) return;
		execute(draft -> draft.migrateAdapterInPlace(migration));
	}

	/**
	 * Only called on a transaction draft: steps run in order, then the version is
	 * recorded. With no state stored there is nothing to misread, so the
	 * adapter's own version is recorded, lower or higher; otherwise state at a
	 * newer version is refused.
	 */
	private void migrateAdapterInPlace(AdapterMigration migration) {
		String name = migration.name();
		long supported = migration.supported();
		long version = adapterVersion(name);
		if (version == supported) return;
		AdapterState state = adapterState(name);
		ACell value = state.get();
		if (value != null) {
			requireAdapterVersion(name, supported);
			ACell migrated = value;
			for (long v = version; v < supported; v++) migrated = migration.step().apply(v, migrated);
			if (migrated != value) state.set(migrated);
		}
		setAdapterVersion(name, supported);
	}

	/**
	 * Apply ordered venue migrations and their version changes in one transaction.
	 * Current venues are a no-op. Callbacks are pure and may retry; failures leave
	 * the entire original value intact. Bulk record migrations remain lazy.
	 * Adapters' state is not migrated: use {@link #migrate(SecretStore.Keys, List)}.
	 *
	 * @param keys The venue's secret store keys ({@link SecretStore#keys}), which
	 *        read what a step carries over from the venue's secrets
	 */
	public void migrate(SecretStore.Keys keys) {
		migrate(keys, List.of());
	}

	/**
	 * Migrates the venue and the given adapters' state in one transaction: the
	 * venue's steps first, then each adapter's in order. Callable at any time,
	 * on a running venue or on venue state no venue holds, such as a store an
	 * embedder opens before launch. A refusal or a failing step leaves the venue
	 * exactly as it was; current state is not rewritten. Where two migrations
	 * name the same adapter, the caller passes only the one that applies.
	 *
	 * @param keys The venue's secret store keys ({@link SecretStore#keys}), which
	 *        read what a step carries over from the venue's secrets
	 * @param adapters Migration rules of the adapters to migrate with the venue,
	 *        from {@code AAdapter.stateMigration()}
	 * @throws UnsupportedVersionException if the venue, or an adapter's stored
	 *         state, is at a newer version than supported
	 */
	public void migrate(SecretStore.Keys keys, List<AdapterMigration> adapters) {
		execute(draft -> draft.migrateInPlace(keys, adapters));
	}

	/** Only called on a transaction draft: the venue's steps, then each adapter's. */
	private void migrateInPlace(SecretStore.Keys keys, List<AdapterMigration> adapters) {
		migrateInPlace(keys);
		for (AdapterMigration migration : adapters) migrateAdapterInPlace(migration);
	}

	/**
	 * Only called on a transaction draft: each venue step from the stored
	 * version, recording each version reached. Steps are in
	 * {@link VenueMigrations#steps}.
	 */
	private void migrateInPlace(SecretStore.Keys keys) {
		long version = requireSupportedVersion();
		if (version == CURRENT_VERSION) return;
		List<MigrationStep> steps = VenueMigrations.steps(keys);
		while (version < CURRENT_VERSION) {
			ACell value = get();
			ACell migrated = steps.get((int) version).apply(value);
			if (migrated != value) cursor().set(migrated);
			cursor().withDefault(Index.none()).path(Covia.VERSION).set(CVMLong.create(++version));
		}
	}

	/**
	 * Validate compatibility, migrate and initialise identity atomically, before
	 * Engine bootstrap or adapter activation. Existing identity is preserved.
	 * @param did The venue's DID string
	 * @param keys The venue's secret store keys ({@link SecretStore#keys})
	 */
	public void initialise(ACell did, SecretStore.Keys keys) {
		initialise(did, keys, List.of());
	}

	/**
	 * As {@link #initialise(ACell, SecretStore.Keys)}, also migrating the given adapters' state.
	 * The venue's steps run first, then each adapter's in order, then identity is
	 * initialised, all in one transaction: a refusal or a failing step leaves the
	 * venue exactly as it was. Where two migrations name the same adapter, the
	 * caller passes only the one that applies.
	 *
	 * @param did The venue's DID string
	 * @param keys The venue's secret store keys ({@link SecretStore#keys})
	 * @param adapters Migration rules of the adapters starting with the venue
	 */
	public void initialise(ACell did, SecretStore.Keys keys, List<AdapterMigration> adapters) {
		execute(draft -> {
			draft.migrateInPlace(keys, adapters);
			@SuppressWarnings("unchecked")
			Index<Keyword, ACell> value = (Index<Keyword, ACell>) draft.get();
			if (!value.containsKey(Covia.DID)) draft.cursor().set(value.assoc(Covia.DID, did));
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
	 * For compound local edits with conflict retry, use {@link #execute} or {@link #transact}.
	 */
	@Override
	public VenueState fork() {
		return withCursor(storage.fork());
	}

}
