package covia.venue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.auth.did.DID;
import convex.auth.did.DIDURL;
import convex.auth.ucan.Capability;
import convex.auth.ucan.RootAuthorityPolicy;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.Hashing;
import convex.core.crypto.util.Multikey;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.cvm.Keywords;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.prim.CVMBool;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.core.util.Utils;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFileSystem;
import convex.lattice.fs.impl.DLFSLocal;
import covia.adapter.AAdapter;
import covia.adapter.AgentAdapter;
import covia.adapter.AssetAdapter;
import covia.adapter.AuthAdapter;
import covia.adapter.UserAdapter;
import covia.adapter.ConvexAdapter;
import covia.adapter.CoviaAdapter;
import covia.adapter.GridAdapter;
import covia.adapter.HTTPAdapter;
import covia.adapter.OAuthAdapter;
import covia.adapter.JSONAdapter;
import covia.adapter.JVMAdapter;
import covia.adapter.SchemaAdapter;
import covia.adapter.LangChainAdapter;
import covia.adapter.MCPAdapter;
import covia.adapter.Orchestrator;
import covia.adapter.SecretAdapter;
import covia.adapter.DLFSAdapter;
import covia.adapter.FileAdapter;
import covia.adapter.VaultAdapter;
import covia.adapter.agent.LLMAgentAdapter;
import covia.adapter.UCANAdapter;
import covia.adapter.TestAdapter;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.CoviaException;
import covia.exception.AuthException;
import covia.exception.RemoteFetchException;
import covia.exception.WrongScopeException;
import covia.grid.AContent;
import covia.grid.Asset;
import covia.grid.Grid;
import covia.grid.Operation;
import covia.grid.Venue;
import covia.grid.client.VenueHTTP;
import covia.lattice.Covia;
import covia.venue.api.CoviaAPI;
import covia.venue.storage.AStorage;
import covia.venue.storage.FileStorage;
import covia.venue.storage.MemoryStorage;

public class Engine {

	public static final Logger log=LoggerFactory.getLogger(Engine.class);



	protected final Config config;

	/** Hosted Covia application, or null for the legacy raw-cursor embedding path. */
	private final CoviaApplication application;

	protected AKeyPair keyPair=AKeyPair.generate();

	/**
	 * Storage instance for content associated with assets
	 */
	protected AStorage contentStorage;

	/**
	 * Venue lattice using Covia.ROOT structure see COG-004.
	 * ALatticeCursor provides lattice-aware merge and sync semantics.
 	 */
	protected ALatticeCursor<Index<Keyword,ACell>> lattice;

	/** Venue state wrapper providing typed access to assets, jobs, and child cursors */
	protected VenueState venueState;

	/** Authentication and user management */
	protected Auth auth;

	/** Authorisation / access control */
	protected AccessControl accessControl;

	/** Job lifecycle manager (submission, queries, persistence, recovery) */
	private final JobManager jobManager;

	/** MainVenue process control; absent for embedded and test engines. */
	private volatile VenueProcess processControl;
	/** Host-installed store maintenance seam (covia#452); null when the host installed none. */
	private volatile StoreControl storeControl;

	/**
	 * Per-venue grid scheduler. Fires any deferred grid operation at a future
	 * wall-clock time; an agent wake is one consumer (a scheduled
	 * {@code agent:wake}). See {@code venue/docs/GRID_SCHEDULER.md}.
	 */
	private final Scheduler gridScheduler;
	/** The live agent tap (#394): run-loop, cycle, inference, tool and status
	 *  events for every agent hosted here. */
	private final AgentEvents agentEvents = new AgentEvents();

	/**
	 * Map of named adapters that can handle different types of operations or resources
	 */
	protected final ConcurrentHashMap<String, AAdapter> adapters = new ConcurrentHashMap<>();
	/** Active adapter precedence, oldest to newest. Later registrations win catalog paths. */
	private final java.util.LinkedHashSet<String> adapterRegistrationOrder = new java.util.LinkedHashSet<>();

	/**
	 * Adapters that are registered but <em>disabled</em> — parked at boot by
	 * {@code adapters.<name>.enabled: false} (or by declining
	 * {@link AAdapter#configure}), or retracted at runtime via
	 * {@link #disableAdapter}. Not dispatchable and absent from
	 * {@code v/info/adapters}; durable catalog metadata may remain and is
	 * overwritten on a later registration.
	 */
	private final ConcurrentHashMap<String, AAdapter> disabledAdapters = new ConcurrentHashMap<>();

	/**
	 * Runtime adapter configuration overrides ({@code v/ops/venue/adapter/configure}),
	 * overlaid on the static {@code adapters.<name>} config by
	 * {@link #adapterConfig(String)}. Not persisted: config is authoritative
	 * again after a restart.
	 */
	private final ConcurrentHashMap<String, AMap<AString, ACell>> runtimeAdapterConfig = new ConcurrentHashMap<>();

	/**
	 * Venue modules loaded into this engine (see {@link Modules}), in load
	 * order. Classloaders are closed on unload or {@link #close()}.
	 */
	private final java.util.LinkedHashMap<String, Modules.LoadedModule> modules = new java.util.LinkedHashMap<>();

	/**
	 * Set once the venue catalog and {@code v/info} snapshot have been
	 * published (see {@link #materialiseBootstrapState()}). Before that,
	 * registrations accumulate for the bulk bootstrap write; after it, every
	 * registration change is published incrementally.
	 */
	private volatile boolean catalogPublished = false;

	/**
	 * Monotonic version of the active adapter set — bumped on every activate,
	 * disable and remove. Cheap staleness signal for consumers that snapshot
	 * the adapter set (e.g. the MCP tool registry) without a listener
	 * mechanism.
	 */
	private final java.util.concurrent.atomic.AtomicLong adapterRegistryVersion = new java.util.concurrent.atomic.AtomicLong();

	/**
	 * Adapters the venue itself commonly dereferences by name (Engine, API
	 * surfaces, server wiring, or other adapters via {@code getAdapter}). This
	 * marker is informational; venue-authorised configuration and module loads
	 * may still replace, disable or remove them.
	 */
	public static final java.util.Set<String> KERNEL_ADAPTERS = java.util.Set.of(
		"covia", "agent", "dlfs", "hitl", "http", "file", "grid", "venue");

	/**
	 * Compatibility persistence callback for legacy raw-cursor embedders.
	 * Hosted applications publish and flush through {@link CoviaApplication}.
	 */
	private final PersistenceHandler persistHandler;

	/**
	 * Background sweep daemon — periodically pulls venueState fork into the
	 * root and triggers the lattice's sync callback so the propagator
	 * persists durable. See {@code venue/docs/PERSISTENCE.md}.
	 */
	private ScheduledExecutorService persistenceSweep;

	/** Explicit lifecycle: construction is inert; start/close own active resources. */
	private enum Lifecycle { NEW, STARTING, STARTED, FAILED, CLOSING, CLOSED }
	private volatile Lifecycle lifecycle = Lifecycle.NEW;

	/** How often the persistence sweep daemon runs (ms). */
	private static final long SWEEP_INTERVAL_MS = 100;

	/**
	 * How often the sweep forces a store-level fsync. The sweep runs every
	 * {@link #SWEEP_INTERVAL_MS} but only crosses the host's durability barrier
	 * if at least this many ms have elapsed since the last flush. Bounds the
	 * data-loss window on unclean shutdown (kernel panic, power loss, hard
	 * VM stop) to roughly this interval. App-requested {@link #flush()} resets
	 * the counter, so explicit flushes naturally suppress the periodic one.
	 *
	 * <p>Package-private (non-final, volatile) so tests can shrink the
	 * interval to exercise the cadence quickly. Tests must restore the
	 * original value. Volatile ensures the sweep daemon thread sees the
	 * change without a synchronisation primitive — without this, the JIT
	 * may cache the field per thread and tests changing it from the test
	 * thread won't affect the sweep.</p>
	 */
	static volatile long FLUSH_INTERVAL_MS = 10_000;

	/** Timestamp of the last completed flush. Initialised at construction. */
	private volatile long lastFlushMillis;

	/**
	 * Primary constructor: assembles an inert Engine around the caller's cursor.
	 * Call {@link #start()} before use. Generates a random venue key pair.
	 */
	public Engine(AMap<AString, ACell> config, ALatticeCursor<Index<Keyword,ACell>> cursor) throws IOException {
		this(config, cursor, AKeyPair.generate(), PersistenceHandler.NOOP);
	}

	/**
	 * Inert constructor with explicit key pair. Call {@link #start()} before use.
	 * Use when the venue identity must be
	 * stable across restarts (same AccountKey = same OwnerLattice slot).
	 *
	 * <p>This raw-cursor overload is retained for compatibility. New hosted
	 * integrations should construct a {@link CoviaApplication} from their
	 * {@code RootComponent} and use the application constructor.</p>
	 */
	public Engine(AMap<AString, ACell> config, ALatticeCursor<Index<Keyword,ACell>> cursor, AKeyPair keyPair) throws IOException {
		this(config, cursor, keyPair, PersistenceHandler.NOOP);
	}

	/**
	 * Canonical inert constructor with persistence handler. Call {@link #start()}
	 * before use.
	 *
	 * <p>The handler is invoked synchronously by {@link #flush()} (and during
	 * the close-time final flush) to make the venue's lattice value durable.
	 * Pass {@link PersistenceHandler#NOOP} for in-memory venues.</p>
	 */
	public Engine(AMap<AString, ACell> config, ALatticeCursor<Index<Keyword,ACell>> cursor,
			AKeyPair keyPair, PersistenceHandler persistHandler) throws IOException {
		this(new Config(config), null, cursor, keyPair, persistHandler);
	}

	/**
	 * Canonical constructor for a caller that has already validated and resolved
	 * its Config. VenueServer uses this to reuse the same instance without
	 * validating twice (and duplicating unknown-field warnings).
	 */
	public Engine(Config config, ALatticeCursor<Index<Keyword,ACell>> cursor,
			AKeyPair keyPair, PersistenceHandler persistHandler) throws IOException {
		this(config, null, cursor, keyPair, persistHandler);
	}

	/**
	 * Canonical hosted-application constructor. Publication and durability flow
	 * through the application's {@code RootComponent}; the engine does not need
	 * to know whether that host is local or networked.
	 */
	public Engine(Config config, CoviaApplication application, AKeyPair keyPair)
			throws IOException {
		this(config, java.util.Objects.requireNonNull(application, "application"),
			application.cursor(), keyPair, PersistenceHandler.NOOP);
	}

	/** Hosted-application constructor accepting the external JSON config form. */
	public Engine(AMap<AString, ACell> config, CoviaApplication application,
			AKeyPair keyPair) throws IOException {
		this(new Config(config), application, keyPair);
	}

	private Engine(Config config, CoviaApplication application,
			ALatticeCursor<Index<Keyword,ACell>> cursor, AKeyPair keyPair,
			PersistenceHandler persistHandler) throws IOException {
		this.config=java.util.Objects.requireNonNull(config, "config");
		this.application=application;
		this.keyPair=keyPair;
		validateDeclaredIdentity();
		this.lattice=java.util.Objects.requireNonNull(cursor, "cursor");
		this.persistHandler = (persistHandler != null) ? persistHandler : PersistenceHandler.NOOP;
		this.lastFlushMillis = System.currentTimeMillis();
		this.jobManager = new JobManager(this);
		this.gridScheduler = new Scheduler(this);
	}

	/**
	 * Starts this engine and all resources it owns.
	 *
	 * <p>Construction is deliberately inert so callers retain an Engine
	 * reference before any storage or threads are started. Startup is
	 * all-or-nothing: a failure closes every resource acquired so far in the
	 * reverse of startup order, then rethrows the original failure.</p>
	 *
	 * @return this engine
	 * @throws IOException if content storage cannot be initialised
	 */
	public synchronized Engine start() throws IOException {
		if (lifecycle == Lifecycle.STARTED) return this;
		if (lifecycle != Lifecycle.NEW) {
			throw new IllegalStateException("Engine cannot start from state " + lifecycle);
		}
		lifecycle = Lifecycle.STARTING;
		try {
			// Set signing context only when active startup begins.
			// Preserve host policy (future-skew checks, owner verification, custom
			// clock) and override only Covia's signing capability.
			LatticeContext ctx = this.lattice.getContext()
				.withSigningKey(this.keyPair);
			this.lattice.setContext(ctx);
			initialiseFromCursor();

			this.contentStorage = createStorage();
			this.contentStorage.initialise();

			// The authoritative schedule is persisted in the lattice. Start its
			// in-memory alarm, then heal per-agent wake handles from durable state.
			gridScheduler.start();
			rebuildSchedulerFromLattice();

			bootstrapUsers();
			startPersistenceSweep();
			lifecycle = Lifecycle.STARTED;
			return this;
		} catch (IOException | RuntimeException | Error failure) {
			lifecycle = Lifecycle.FAILED;
			closeStartedResources(false, failure);
			throw failure;
		}
	}

	private void bootstrapUsers() {
		// The venue is also a user, providing its /v/ virtual namespace.
		this.venueState.users().ensure(getDIDString());
		// :public is one framework-owned shared principal, not a visitor account.
		if (this.config.isPublicAccess()) {
			this.venueState.users().ensure(Strings.create(getDIDString() + ":public"));
		}
		// Admit venue-managed login identities created by the older split store.
		AMap<AString, AMap<AString, ACell>> knownUsers = auth.getUsers();
		if (knownUsers != null) {
			for (var entry : knownUsers.entrySet()) {
				AString did = RT.ensureString(entry.getValue().get(Fields.DID));
				if (did != null) this.venueState.users().ensure(did);
			}
		}
		bootstrapConfiguredNamedUsers();
	}

	/**
	 * First-use provisioning for operator-declared named users. Configuration is
	 * deliberately bootstrap-only: once a user has any authenticator history,
	 * later startup never adds, revokes or reactivates keys.
	 */
	private void bootstrapConfiguredNamedUsers() {
		AMap<AString, ACell> configured = config.getUserBootstrapConfig();
		if (configured == null || configured.isEmpty()) return;

		// Validate the complete declaration before writing anything.
		java.util.HashSet<AString> declaredKeys = new java.util.HashSet<>();
		for (var entry : configured.entrySet()) {
			AString id = entry.getKey();
			AString did = managedUserDID(id); // validates username + hostname
			AMap<AString, ACell> existingUser = auth.getUser(id);
			if (existingUser != null
					&& !did.equals(RT.ensureString(existingUser.get(Fields.DID)))) {
				throw new IllegalStateException("Named user " + id
					+ " is already bound to a different DID");
			}
			AMap<AString, ACell> spec = RT.ensureMap(entry.getValue());
			AVector<ACell> keys = (spec != null)
				? RT.ensureVector(spec.get(Fields.AUTHENTICATION_KEYS)) : null;
			if (keys == null || keys.isEmpty()) {
				throw new IllegalArgumentException("users.bootstrap." + id
					+ " requires a non-empty authenticationKeys array");
			}
			for (long i = 0; i < keys.count(); i++) {
				AString key = RT.ensureString(keys.get(i));
				Auth.requireValidAuthenticationKey(key);
				if (!declaredKeys.add(key)) {
					throw new IllegalArgumentException(
						"One authentication key cannot bootstrap two named users");
				}
				AMap<AString, AMap<AString, ACell>> known = auth.getUsers();
				if (known != null) {
					for (var other : known.entrySet()) {
						if (other.getKey().equals(id)) continue;
						AMap<AString, ACell> otherKeys = RT.ensureMap(
							other.getValue().get(Fields.AUTHENTICATION_KEYS));
						if (otherKeys != null && otherKeys.containsKey(key)) {
							throw new IllegalArgumentException(
								"Authentication key is already bound to named user "
								+ other.getKey());
						}
					}
				}
			}
			// Make the exact DID computation part of validation, even though the
			// value is recomputed below after the all-or-nothing validation pass.
			if (did == null) throw new IllegalStateException("Managed user DID unavailable");
		}

		for (var entry : configured.entrySet()) {
			AString id = entry.getKey();
			AString did = managedUserDID(id);
			AMap<AString, ACell> spec = RT.ensureMap(entry.getValue());
			AVector<ACell> keys =
				RT.ensureVector(spec.get(Fields.AUTHENTICATION_KEYS));
			venueState.users().ensure(did);
			auth.ensureManagedUser(id, did);
			if (!auth.getAuthenticationKeys(id).isEmpty()) continue;
			auth.addAuthenticationKeys(id, keys, getDIDString());
		}
	}

	private void startPersistenceSweep() {
		// In-memory engines have nothing external to flush and get no daemon.
		if (this.application != null && this.application.isEphemeral()) return;
		if (this.application == null && this.persistHandler == PersistenceHandler.NOOP) return;
		this.persistenceSweep = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "covia-persistence-sweep");
			t.setDaemon(true);
			return t;
		});
		this.persistenceSweep.scheduleWithFixedDelay(
			this::sweep, SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}


	/**
	 * Creates the appropriate storage instance based on configuration.
	 *
	 * <p>Reads the "storage" config entry to determine storage type:
	 * <ul>
	 *   <li>"lattice" - Uses LatticeStorage backed by venue lattice cursor (default)</li>
	 *   <li>"memory" - Uses simple in-memory storage</li>
	 *   <li>"file" - Uses FileStorage with configured path</li>
	 *   <li>"dlfs" - Uses FileStorage backed by local DLFS filesystem</li>
	 * </ul>
	 *
	 * @return Configured storage instance
	 */
	protected AStorage createStorage() {
		AString storageType = config.getStorageType();
		String storagePath = config.getStoragePath();

		log.info("Configuring storage type: {}", storageType);

		if (Config.STORAGE_TYPE_MEMORY.equals(storageType)) {
			return new MemoryStorage();
		} else if (Config.STORAGE_TYPE_FILE.equals(storageType)) {
			if (storagePath == null || storagePath.isEmpty()) {
				throw new IllegalArgumentException("File storage requires 'path' configuration");
			}
			Path path = Paths.get(storagePath);
			if (!Files.exists(path)) {
				try {
					Files.createDirectories(path);
				} catch (IOException e) {
					throw new IllegalArgumentException("Failed to create storage directory: " + storagePath, e);
				}
			}
			log.info("Using file storage at: {}", storagePath);
			return new FileStorage(path);
		} else if (Config.STORAGE_TYPE_DLFS.equals(storageType)) {
			// TODO: DLFS replication - integrate with venue lattice for cross-venue sync
			// Currently uses a local in-memory DLFS filesystem
			try {
				DLFileSystem dlfs = DLFS.createLocal();
				Path dlfsStorageDir = Files.createDirectory(dlfs.getRoot().resolve("content"));
				log.info("Using DLFS storage (local)");
				return new FileStorage(dlfsStorageDir);
			} catch (IOException e) {
				throw new IllegalStateException("Failed to create DLFS storage", e);
			}
		} else {
			// Default to lattice storage
			if (!Config.STORAGE_TYPE_LATTICE.equals(storageType)) {
				log.warn("Unknown storage type '{}', defaulting to lattice", storageType);
			}
			return venueState.storage();
		}
	}

	/**
	 * Initialises venue state wrapper and components from the lattice cursor.
	 * Ensures the venue entry exists at [:grid :venues &lt;accountKey&gt; :value].
	 * Venues are keyed by AccountKey in OwnerLattice; the DID is stored inside venue state.
	 *
	 * <p>Bootstrap (DID initialisation) is performed on the connected cursor so
	 * the write is signed immediately — other peers need a signed DID to accept
	 * the venue. After bootstrap, the cursor is forked: all subsequent writes
	 * accumulate locally (unsigned) until {@link #syncState()} calls
	 * {@link VenueState#sync()}, which merges and signs once.</p>
	 */
	protected void initialiseFromCursor() {
		// Identity guard (#208): booting an existing store with the wrong key
		// would not fail — venues are keyed by AccountKey, so it would silently
		// create a fresh empty venue entry alongside the real one, orphaning all
		// existing data ("where did my agents go?"). A venue without a key that
		// can sign for its own persisted state is broken; fail before writing.
		requireKeyMatchesStore();

		// Bootstrap with connected VenueState (writes signed immediately).
		// DID initialisation must be signed so other peers accept it.
		VenueState connected = (application != null)
			? application.venue(getAccountKey())
			: VenueState.fromRoot(lattice, getAccountKey());
		connected.initialise(getDIDString());

		// Fork: subsequent writes accumulate locally (unsigned).
		// Engine.syncState() calls venueState.sync() to merge + sign once.
		// The fork captures the root's LatticeContext policy. That policy is
		// dynamic, so its clock and signer remain live without per-request
		// context replacement.
		this.venueState = connected.fork();

		this.auth = new Auth(this, venueState, venueState.authCursor());
		this.accessControl = new AccessControl();
	}

	/**
	 * Fails startup when the store already contains venue state but none of it
	 * belongs to this engine's key (#208). A fresh store (no venues) and a
	 * normal restart (our AccountKey present) both pass; other venues' entries
	 * alongside ours are fine. The error names the identities found so the
	 * operator can point {@code seed}/{@code keystore} at the right key.
	 *
	 * <p>Note: today a local store only ever holds the venue's own entry;
	 * if lattice-level federation ever replicates peer venue entries into
	 * {@code :venues}, a first boot on a pre-replicated store would need this
	 * check refined (our-entry-absent would no longer imply a wrong key).</p>
	 */
	@SuppressWarnings("unchecked")
	private void requireKeyMatchesStore() {
		// The :venues level is an OwnerLattice: AHashMap<AccountKey, SignedData>.
		ACell venuesVal = lattice.path(Covia.GRID, Covia.VENUES).get();
		if (!(venuesVal instanceof AMap<?, ?> venues) || venues.isEmpty()) return;   // fresh store
		AMap<ACell, ACell> vs = (AMap<ACell, ACell>) venues;
		if (vs.containsKey(getAccountKey())) return;                                  // normal restart

		StringBuilder sb = new StringBuilder();
		for (long i = 0; i < vs.count(); i++) {
			MapEntry<ACell, ACell> e = vs.entryAt(i);
			if (i > 0) sb.append(", ");
			ACell did = RT.getIn(e.getValue(), Keywords.VALUE, Covia.DID);
			sb.append(did != null ? did : "key " + e.getKey());
		}
		throw new IllegalStateException(
			"Venue key mismatch: this store already holds venue state for [" + sb
			+ "] but the configured key gives " + getDIDString()
			+ ". Starting anyway would create a fresh empty venue and orphan the existing"
			+ " data. Configure the owning key via 'seed' or 'keystore' (or point 'store'"
			+ " at a different file).");
	}

	/**
	 * Synchronises venue state to the persistent lattice.
	 *
	 * <p>Two-phase sync:</p>
	 * <ol>
	 *   <li>{@code venueState.sync()} — merges forked (unsigned) writes into
	 *       the parent cursor chain, triggering a single sign through the
	 *       SignedCursor boundary.</li>
	 *   <li>{@code application.sync()} — crosses the hosted root publication
	 *       boundary. The host decides whether publication is local or networked.</li>
	 * </ol>
	 *
	 * <p>Called by VenueServer's role-selected {@code afterMatched} handler, so
	 * all writes within one native protocol request—or an embedder route carrying
	 * {@code VenueRouteFeature.LATTICE_SYNC}—are batched into one sign and
	 * persist.</p>
	 */
	public void syncState() {
		if (lifecycle == Lifecycle.CLOSING || lifecycle == Lifecycle.CLOSED || lifecycle == Lifecycle.FAILED) {
			// A request unwinding across shutdown must not write to a closing store;
			// close() has taken (or will take) the final flush.
			log.debug("syncState skipped: engine is {}", lifecycle);
			return;
		}
		venueState.sync();
		publishApplicationRoot();
	}

	/**
	 * Compatibility hook retained for callers built against the former fixed
	 * timestamp context model.
	 *
	 * <p>The current Convex context is a live application policy. Covia installs
	 * it once at startup with a dynamic runtime clock, and forks retain that live
	 * policy, so there is no timestamp snapshot to refresh.</p>
	 */
	@Deprecated
	public void refreshWriteClock() {
		// Dynamic LatticeContext resolves time at each logical write.
	}

	/** Publishes through the hosted application policy or the legacy root cursor. */
	private void publishApplicationRoot() {
		if (application != null) {
			application.sync();
		} else {
			lattice.sync();
		}
	}

	// ========================================================================
	// Persistence — see venue/docs/PERSISTENCE.md
	// ========================================================================

	/**
	 * Background sweep step. Merges the venueState fork into the root, then
	 * fires the root cursor's onSync callback (which the NodeServer wires to
	 * the propagator). Both calls are required because
	 * {@code ForkedLatticeCursor.sync()} deliberately does NOT propagate sync
	 * up the chain — see {@code venue/docs/PERSISTENCE.md} §5.0.
	 *
	 * <p>Called from the persistence sweep daemon and from {@link #flush()}.
	 * Sync is a no-op when there are no pending writes, so this is cheap on
	 * idle venues.</p>
	 */
	private void sweep() {
		if (lifecycle != Lifecycle.STARTED) return;
		try {
			venueState.sync();   // pull fork writes into the root
			publishApplicationRoot();
			// Periodic durability barrier. The propagator's setRootData
			// writes new root data to the mmap'd Etch but does not fsync;
			// the OS may take minutes to write dirty pages out on its own.
			// Forcing fsync every FLUSH_INTERVAL_MS bounds the unclean-
			// shutdown data-loss window. Cheap on idle venues — fsync of
			// an unchanged file is a no-op below the kernel.
			if (System.currentTimeMillis() - lastFlushMillis >= FLUSH_INTERVAL_MS) {
				flushStore();
				lastFlushMillis = System.currentTimeMillis();
			}
		} catch (Exception e) {
			log.warn("Persistence sweep failed", e);
		}
	}

	/**
	 * Synchronises venue state, publishes the complete hosted root and invokes
	 * the host store's physical durability barrier. Legacy raw-cursor embedders
	 * use their supplied {@link PersistenceHandler} for the same boundary.
	 *
	 * <p>Use sparingly — most writes don't need this. Default eventual
	 * durability via the background sweep is fine for in-flight job state,
	 * conversation history, etc. Use {@code flush()} for: job completion,
	 * audit records, secret rotation, agent TERMINATED, OAuth login.</p>
	 */
	public void flush() {
		venueState.sync(); // pull fork into root
		try {
			if (application != null) {
				application.sync();  // host publication selects the retained root
				application.flush(); // physical durability barrier
			} else {
				persistHandler.persist(lattice.get());
				persistHandler.flush();
			}
		} catch (java.io.IOException e) {
			throw new RuntimeException("flush failed", e);
		}
		lastFlushMillis = System.currentTimeMillis();
	}

	private void flushStore() throws IOException {
		if (application != null) {
			application.flush();
		} else {
			persistHandler.flush();
		}
	}

	/**
	 * Stops the persistence sweep, runs a final flush, and releases engine
	 * resources. After close, the engine cannot be used.
	 *
	 * <p>Must be called BEFORE {@code nodeServer.close()} so the venueState
	 * fork is merged into the root before the propagator's shutdown drain
	 * reads from the root cursor. {@code VenueServer.close()} handles this
	 * ordering.</p>
	 *
	 * <p>Idempotent — calling close more than once is safe.</p>
	 */
	public synchronized void close() {
		if (lifecycle == Lifecycle.CLOSED || lifecycle == Lifecycle.CLOSING) return;
		boolean flush = lifecycle == Lifecycle.STARTED;
		lifecycle = Lifecycle.CLOSING;
		closeStartedResources(flush, null);
		lifecycle = Lifecycle.CLOSED;
	}

	/** Releases resources in the reverse of {@link #start()} acquisition order. */
	private void closeStartedResources(boolean flush, Throwable startupFailure) {
		jobManager.beginShutdown();

		// Release adapter-owned native/session resources before module classloaders.
		for (AAdapter adapter : adapters.values()) {
			if (!(adapter instanceof AutoCloseable closeable)) continue;
			try {
				closeable.close();
			} catch (Exception e) {
				recordCloseFailure(startupFailure,
					"Failed to close adapter " + adapter.getName(), e);
			}
		}
		// Disabled adapters may still hold resources acquired at install time.
		for (AAdapter adapter : disabledAdapters.values()) {
			if (!(adapter instanceof AutoCloseable closeable)) continue;
			try {
				closeable.close();
			} catch (Exception e) {
				recordCloseFailure(startupFailure,
					"Failed to close disabled adapter " + adapter.getName(), e);
			}
		}
		// Module loaders are installed after core Engine startup, so release
		// their classloaders before unwinding the core resources.
		java.util.List<Modules.LoadedModule> loaded = new java.util.ArrayList<>(modules.values());
		for (int i = loaded.size() - 1; i >= 0; i--) {
			try {
				loaded.get(i).loader().close();
			} catch (Exception e) {
				recordCloseFailure(startupFailure, "Failed to close module classloader", e);
			}
		}
		modules.clear();

		// Persistence sweep is acquired last, so it is stopped first.
		ScheduledExecutorService sweepExecutor = persistenceSweep;
		persistenceSweep = null;
		if (sweepExecutor != null) {
			sweepExecutor.shutdown();
			try {
				if (!sweepExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
					sweepExecutor.shutdownNow();
				}
			} catch (InterruptedException e) {
				sweepExecutor.shutdownNow();
				Thread.currentThread().interrupt();
				recordCloseFailure(startupFailure, "Interrupted while stopping persistence sweep", e);
			}
		}

		// Stop timers before the final durability barrier so no new jobs or
		// lattice writes can race with that barrier.
		try {
			gridScheduler.shutdown();
		} catch (RuntimeException e) {
			recordCloseFailure(startupFailure, "Failed to stop scheduler", e);
		}

		if (flush && venueState != null) {
			try {
				venueState.sync();
				persistHandler.persist(lattice.get());
				persistHandler.flush();
			} catch (Exception e) {
				recordCloseFailure(startupFailure, "Final persistence flush failed during close", e);
			}
		}

		AStorage storage = contentStorage;
		contentStorage = null;
		if (storage != null) {
			try {
				storage.close();
			} catch (RuntimeException e) {
				recordCloseFailure(startupFailure, "Failed to close content storage", e);
			}
		}
	}

	private static void recordCloseFailure(Throwable startupFailure, String message, Throwable failure) {
		if (startupFailure != null) {
			startupFailure.addSuppressed(failure);
		} else {
			log.warn(message, failure);
		}
	}

	/** True only after start completed successfully and before close began. */
	public boolean isStarted() {
		return lifecycle == Lifecycle.STARTED;
	}

	/** Installs process control before MainVenue publishes restart authority. */
	void setProcessControl(VenueProcess processControl) {
		if (this.processControl != null && this.processControl != processControl) {
			throw new IllegalStateException("Venue process control is already installed");
		}
		this.processControl = java.util.Objects.requireNonNull(processControl);
	}

	/** Requests a successor handoff from a standalone MainVenue process. */
	public VenueProcess.RestartPlan requestProcessRestart(String successor,
			String sha256, long startupTimeoutMillis, covia.grid.Job job) {
		VenueProcess control = processControl;
		if (control == null) {
			throw new IllegalStateException(
				"Process restart is unavailable: this venue is not managed by MainVenue");
		}
		return control.requestRestart(successor, sha256, startupTimeoutMillis, job);
	}

	/** Whether a standalone MainVenue process manages this venue, so restart requests can be honoured. */
	public boolean hasProcessControl() {
		return processControl != null;
	}

	/** Installs the host's store maintenance seam (covia#452); the host owns the store, the Engine only relays. */
	public void setStoreControl(StoreControl storeControl) {
		if (this.storeControl != null && this.storeControl != storeControl) {
			throw new IllegalStateException("Venue store control is already installed");
		}
		this.storeControl = java.util.Objects.requireNonNull(storeControl);
	}

	/**
	 * The host's store maintenance seam, for venue-owned operations.
	 *
	 * @throws IllegalStateException when the host installed none
	 */
	public StoreControl storeControl() {
		StoreControl control = storeControl;
		if (control == null) {
			throw new IllegalStateException(
				"Store maintenance is unavailable: this venue's host installed no store control");
		}
		return control;
	}

	public static void addDemoAssets(Engine venue) {
		venue.registerAdapter(new TestAdapter());
		venue.registerAdapter(new HTTPAdapter());
		venue.registerAdapter(new OAuthAdapter());
		venue.registerAdapter(new JVMAdapter());
		venue.registerAdapter(new FileAdapter());
		venue.registerAdapter(new covia.adapter.ArchiveAdapter());
		venue.registerAdapter(new SchemaAdapter());
		venue.registerAdapter(new JSONAdapter());
		venue.registerAdapter(new Orchestrator());
		venue.registerAdapter(new MCPAdapter());
		venue.registerAdapter(new LangChainAdapter());
		venue.registerAdapter(new CoviaAdapter());
		venue.registerAdapter(new covia.adapter.UserMemoryAdapter());
		venue.registerAdapter(new covia.adapter.SkillsAdapter());
		venue.registerAdapter(new AssetAdapter());
		venue.registerAdapter(new GridAdapter());
		venue.registerAdapter(new covia.adapter.A2AAdapter());
		venue.registerAdapter(new ConvexAdapter());
		venue.registerAdapter(new AgentAdapter());
		venue.registerAdapter(new SecretAdapter());
		venue.registerAdapter(new covia.adapter.SchedulerAdapter());
		venue.registerAdapter(new AuthAdapter());
		venue.registerAdapter(new UserAdapter());
		venue.registerAdapter(new UCANAdapter());
		venue.registerAdapter(new DLFSAdapter());
		venue.registerAdapter(new VaultAdapter());
		venue.registerAdapter(new LLMAgentAdapter());
		venue.registerAdapter(new covia.adapter.agent.GoalTreeAdapter());
		venue.registerAdapter(new covia.adapter.HITLAdapter());
		venue.registerAdapter(new covia.adapter.VenueAdapter());
		// Load operator-declared venue modules (external adapter jars) BEFORE
		// materialisation, so module ops enter the catalog with everyone
		// else's. Fail-fast on any load error — explicit config is explicit
		// intent.
		Modules.loadModules(venue);

		// Publish the adapter catalog and venue information as one native lattice
		// transaction. All writes and validation happen on a child fork; one sync
		// makes the complete bootstrap snapshot visible without creating Jobs.
		venue.materialiseBootstrapState();

		// Bridge config-declared MCP servers (#80). Config is the source of
		// truth for the names it declares (secrets-bootstrap rule); entries
		// it doesn't name — dynamically-added servers — are untouched.
		venue.seedMcpServers();
	}

	/**
	 * Flushes catalog entries that adapters collected via
	 * {@link covia.adapter.AAdapter#installAsset(String, String)} and
	 * {@link covia.adapter.AAdapter#installTestAsset(String, String)}. Each
	 * entry is written to its full target path (e.g. {@code v/ops/json/merge}
	 * or {@code v/test/ops/echo}) as inline asset metadata on a child lattice
	 * fork.
	 *
	 * <p>This catalog-only method is retained for callers that explicitly need
	 * it. Normal startup uses {@link #materialiseBootstrapState()} so catalog
	 * and venue information become visible together.</p>
	 */
	public void materialiseVOps() {
		VenueBootstrapMaterializer.materialiseAdapterCatalog(this);
		catalogPublished = true;
	}

	/**
	 * Materialises the adapter catalog and {@code /v/info/} snapshot together.
	 * The complete snapshot is built and validated on a child fork, then
	 * published to the Engine's live fork with one {@code sync()}. A failure
	 * before that point leaves the live state unchanged and creates no Jobs.
	 */
	public void materialiseBootstrapState() {
		VenueBootstrapMaterializer.materialiseBootstrapState(this);
		catalogPublished = true;
	}

	/**
	 * Bridges MCP servers declared in the {@code mcp.servers} config block
	 * (#80): each is registered at VENUE scope, its tools materialised at
	 * {@code v/ops/mcp/<name>/<tool>}. Best-effort per server — a server that
	 * is down at boot logs a warning (run {@code v/ops/mcp/refresh} once it
	 * is reachable) and never blocks startup; the last-known bridged catalog
	 * from a previous boot persists on the lattice regardless.
	 */
	public void seedMcpServers() {
		AMap<AString, ACell> mcpConfig = config().getMCPConfig();
		ACell serversCell = (mcpConfig != null) ? mcpConfig.get(Strings.intern("servers")) : null;
		if (!(serversCell instanceof AMap)) return;
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> servers = (AMap<AString, ACell>) serversCell;
		for (var entry : servers.entrySet()) {
			AString name = entry.getKey();
			ACell spec = entry.getValue();
			try {
				AMap<AString, ACell> input = Maps.of(
					Fields.NAME, name,
					Strings.intern("url"), RT.getIn(spec, "url"),
					Strings.intern("scope"), Strings.intern("venue"));
				ACell auth = RT.getIn(spec, "auth");
				if (auth != null) input = input.assoc(Strings.intern("auth"), auth);
				jobManager.invokeInternal(Strings.create("v/ops/mcp/add-server"),
					input, venueContext()).join();
				log.info("Bridged config-declared MCP server '{}'", name);
			} catch (Exception e) {
				log.warn("Could not bridge config-declared MCP server '{}' at boot: {} — "
					+ "the last-known catalog (if any) remains; run v/ops/mcp/refresh "
					+ "when the server is reachable", name, e.getMessage());
			}
		}
	}

	/**
	 * Writes the venue introspection data to {@code /v/info/} sub-paths.
	 * Called once at startup after all adapters are registered, and any
	 * time the venue wants to refresh the information.
	 *
	 * <p>Per OPERATIONS.md §3, the populated paths are:</p>
	 * <ul>
	 *   <li>{@code /v/info/name} — venue display name (from config)</li>
	 *   <li>{@code /v/info/did} — venue's own DID</li>
	 *   <li>{@code /v/info/version} — covia jar version</li>
	 *   <li>{@code /v/info/started} — startup time as epoch milliseconds</li>
	 *   <li>{@code /v/info/protocols} — array of enabled protocol handlers</li>
	 *   <li>{@code /v/info/adapters/&lt;name&gt;} — per-adapter summary</li>
	 * </ul>
	 *
	 * <p>Writes use the same cursor path semantics as {@code covia:write}, but
	 * execute directly on a child lattice fork and publish with one sync. No
	 * operation invocation or Job is involved.</p>
	 */
	public void materialiseVenueInfo() {
		VenueBootstrapMaterializer.materialiseVenueInformation(this);
	}

	/**
	 * Best-effort jar version lookup. Returns {@code "dev"} when running from
	 * IDE classes rather than a packaged jar.
	 */
	/**
	 * The venue's own version. The Maven descriptor the module jar carries
	 * ({@code META-INF/maven/ai.covia/venue/pom.properties}) comes first:
	 * shading and assembly into a host's fat jar preserve it, whereas the
	 * single manifest of such a jar names the host, so its
	 * {@code Implementation-Version} misreported an embedded venue (#420).
	 * The manifest is the fallback, then {@code "dev"}.
	 */
	public static String jarVersion() {
		return versionFrom(mavenDescriptor(), Engine.class.getPackage());
	}

	/** The version a Maven descriptor and a package resolve to — the descriptor first. */
	static String versionFrom(java.util.Properties descriptor, Package pkg) {
		String v = (descriptor != null) ? descriptor.getProperty("version") : null;
		if (v != null && !v.isBlank()) return v.trim();
		v = (pkg != null) ? pkg.getImplementationVersion() : null;
		return (v != null && !v.isBlank()) ? v : "dev";
	}

	private static java.util.Properties mavenDescriptor() {
		try (InputStream in = Engine.class.getResourceAsStream("/META-INF/maven/ai.covia/venue/pom.properties")) {
			if (in == null) return null;
			java.util.Properties p = new java.util.Properties();
			p.load(in);
			return p;
		} catch (IOException e) {
			return null;
		}
	}

	// ========== Adapter lifecycle ==========
	//
	// An adapter is REGISTERED once (configure → install → active or parked),
	// may be DISABLED / ENABLED any number of times (deregistered from
	// dispatch, catalog and v/info, instance retained), RECONFIGURED while
	// live, and REMOVED for good (module unload). The kernel marker is
	// informational; venue-authorised lifecycle operations remain decisive.
	// Every mutation that touches published introspection or catalog metadata
	// is one lattice transaction via the materialiser.

	/**
	 * Register an adapter: applies its effective configuration
	 * ({@link #adapterConfig}), installs it and makes it active. A non-kernel
	 * adapter whose config says {@code enabled: false}, or whose
	 * {@link AAdapter#configure} declines, is parked as disabled instead
	 * (installed lazily on {@link #enableAdapter}). After the bootstrap
	 * catalog has been published, an active registration is materialised
	 * immediately.
	 *
	 * @param adapter The adapter instance to register
	 */
	public synchronized void registerAdapter(AAdapter adapter) {
		String name = adapter.getName();
		AAdapter previous = adapters.get(name);
		if (previous == null) previous = disabledAdapters.get(name);
		AMap<AString, ACell> cfg = adapterConfig(name);
		if (CVMBool.FALSE.equals(cfg.get(Config.ENABLED))) {
			if (previous != null && adapters.get(name) == previous && catalogPublished) {
				VenueBootstrapMaterializer.dematerialiseAdapter(this, previous);
			}
			adapters.remove(name);
			adapterRegistrationOrder.remove(name);
			disabledAdapters.put(name, adapter);
			if (previous != null) adapterRegistryVersion.incrementAndGet();
			closeReplacedAdapter(previous);
			log.info("Adapter '{}' is disabled by configuration", name);
			return;
		}
		if (!adapter.configure(cfg, config.isStrictConfig())) {
			if (previous != null && adapters.get(name) == previous && catalogPublished) {
				VenueBootstrapMaterializer.dematerialiseAdapter(this, previous);
			}
			adapters.remove(name);
			adapterRegistrationOrder.remove(name);
			disabledAdapters.put(name, adapter);
			if (previous != null) adapterRegistryVersion.incrementAndGet();
			closeReplacedAdapter(previous);
			log.info("Adapter '{}' declined its configuration and is disabled", name);
			return;
		}
		activate(adapter, previous);
	}

	/** Install (once) and publish an adapter. Caller holds the engine monitor. */
	private void activate(AAdapter adapter, AAdapter previous) {
		String name = adapter.getName();
		if (adapter.engine == null) adapter.install(this);
		if (catalogPublished) {
			if (previous == null) {
				VenueBootstrapMaterializer.materialiseAdapter(this, adapter);
			} else {
				VenueBootstrapMaterializer.replaceAdapter(this, previous, adapter);
			}
		}
		adapters.put(name, adapter);
		disabledAdapters.remove(name);
		adapterRegistrationOrder.remove(name);
		adapterRegistrationOrder.add(name);
		adapterRegistryVersion.incrementAndGet();
		closeReplacedAdapter(previous);
		log.info("Registered adapter: {} ({} primitives)", name,
			adapter.pendingCatalogEntries.size());
	}

	private void closeReplacedAdapter(AAdapter previous) {
		if (previous == null) return;
		if (!(previous instanceof AutoCloseable closeable)) return;
		try {
			closeable.close();
		} catch (Exception e) {
			log.warn("Failed to close replaced adapter {}", previous.getName(), e);
		}
	}

	/**
	 * Version of the active adapter set: changes whenever an adapter is
	 * activated, disabled or removed. Consumers holding a derived snapshot
	 * compare against this to know when to rebuild.
	 */
	public long adapterRegistryVersion() {
		return adapterRegistryVersion.get();
	}

	/**
	 * Disable an active adapter: retract its {@code v/info/adapters/<name>}
	 * record and stop dispatching to it. Durable catalog metadata remains. The instance
	 * is retained (not closed) so {@link #enableAdapter} restores it exactly.
	 * In-flight jobs keep their adapter reference and finish; anything that
	 * re-resolves the adapter by name (multi-turn messages, recovery) fails at
	 * that point of use.
	 *
	 * @param name Adapter name
	 * @return true if the adapter was active and is now disabled; false if it
	 *         was already disabled
	 * @throws IllegalArgumentException if the adapter is unknown
	 */
	public synchronized boolean disableAdapter(String name) {
		AAdapter adapter = adapters.get(name);
		if (adapter == null) {
			if (disabledAdapters.containsKey(name)) return false;
			throw new IllegalArgumentException("Unknown adapter: " + name);
		}
		if (catalogPublished) VenueBootstrapMaterializer.dematerialiseAdapter(this, adapter);
		adapters.remove(name);
		adapterRegistrationOrder.remove(name);
		disabledAdapters.put(name, adapter);
		adapterRegistryVersion.incrementAndGet();
		log.info("Disabled adapter: {}", name);
		return true;
	}

	/**
	 * Enable a disabled adapter: install it if it never was, overwrite its
	 * catalog paths, publish {@code v/info/adapters/<name>}, and resume dispatch.
	 *
	 * @param name Adapter name
	 * @return true if the adapter was disabled and is now active; false if it
	 *         was already active
	 * @throws IllegalArgumentException if the adapter is unknown
	 * @throws IllegalStateException if the adapter declines its configuration
	 */
	public synchronized boolean enableAdapter(String name) {
		AAdapter adapter = disabledAdapters.get(name);
		if (adapter == null) {
			if (adapters.containsKey(name)) return false;
			throw new IllegalArgumentException("Unknown adapter: " + name);
		}
		if (adapter.engine == null && !adapter.configure(adapterConfig(name), config.isStrictConfig())) {
			throw new IllegalStateException("Adapter '" + name + "' declined its configuration");
		}
		activate(adapter, null);
		disabledAdapters.remove(name);
		return true;
	}

	/**
	 * Apply a new effective configuration to a registered adapter (active or
	 * disabled) and record it as the runtime override returned by
	 * {@link #adapterConfig}. The adapter sees the change through
	 * {@link AAdapter#configure}; if it declines, nothing changes.
	 *
	 * @param name Adapter name
	 * @param cfg The complete new effective configuration
	 * @throws IllegalArgumentException if the adapter is unknown or rejects the config
	 */
	public synchronized void configureAdapter(String name, AMap<AString, ACell> cfg) {
		AAdapter adapter = adapters.get(name);
		if (adapter == null) adapter = disabledAdapters.get(name);
		if (adapter == null) throw new IllegalArgumentException("Unknown adapter: " + name);
		if (cfg == null) cfg = Maps.empty();
		if (!adapter.configure(cfg, config.isStrictConfig())) {
			throw new IllegalArgumentException("Adapter '" + name + "' rejected the configuration");
		}
		runtimeAdapterConfig.put(name, cfg);
		// The adapter's published facts (AAdapter.info) follow its effective config.
		if (catalogPublished && adapters.containsKey(name)) {
			VenueBootstrapMaterializer.materialiseAdapterInfo(this, adapter);
		}
		log.info("Reconfigured adapter: {}", name);
	}

	/**
	 * The effective configuration for an adapter: the runtime override set by
	 * {@link #configureAdapter} if any, else the static
	 * {@code adapters.<name>} block. Never null. Adapters should read their
	 * settings through this rather than {@code config().getAdapterConfig()}
	 * so runtime reconfiguration reaches them.
	 */
	public AMap<AString, ACell> adapterConfig(String name) {
		AMap<AString, ACell> override = runtimeAdapterConfig.get(name);
		return (override != null) ? override : config.getAdapterConfig(name);
	}

	/** True if the adapter is one the venue itself depends on (see {@link #KERNEL_ADAPTERS}). */
	public boolean isKernelAdapter(String name) {
		return KERNEL_ADAPTERS.contains(name);
	}

	/**
	 * Get an active adapter by name
	 * @param name The name of the adapter to retrieve
	 * @return The adapter instance, or null if not found or disabled
	 */
	public AAdapter getAdapter(String name) {
		return adapters.get(name);
	}

	/** Active or parked adapter, for module lifecycle bookkeeping. */
	synchronized AAdapter getRegisteredAdapter(String name) {
		AAdapter adapter = adapters.get(name);
		return (adapter != null) ? adapter : disabledAdapters.get(name);
	}

	/**
	 * Check if an active adapter with the given name exists
	 * @param name The name of the adapter to check
	 * @return true if the adapter exists and is enabled, false otherwise
	 */
	public boolean hasAdapter(String name) {
		return adapters.containsKey(name);
	}

	/**
	 * Remove an adapter for good (active or disabled): retract its live
	 * introspection, close it if {@link AutoCloseable}, and forget it. Canonical
	 * catalog metadata remains until overwritten or explicitly deleted.
	 *
	 * @param name The name of the adapter to remove
	 * @return The removed adapter, or null if not found
	 */
	public synchronized AAdapter removeAdapter(String name) {
		AAdapter removed = adapters.get(name);
		if (removed != null) {
			if (catalogPublished) VenueBootstrapMaterializer.dematerialiseAdapter(this, removed);
			adapters.remove(name);
			adapterRegistrationOrder.remove(name);
			adapterRegistryVersion.incrementAndGet();
		} else {
			removed = disabledAdapters.remove(name);
		}
		if (removed == null) return null;
		runtimeAdapterConfig.remove(name);
		if (removed instanceof AutoCloseable closeable) {
			try {
				closeable.close();
			} catch (Exception e) {
				log.warn("Failed to close removed adapter {}", name, e);
			}
		}
		log.info("Removed adapter: {}", name);
		return removed;
	}

	/**
	 * The first registered adapter implementing an interface — the seam by
	 * which a core operation reaches a capability an optional module supplies
	 * (a {@link covia.adapter.TextExtractor} from covia-documents behind
	 * {@code file:read mode=extract}). Null when none is registered; the
	 * caller then fails naming the module rather than degrading silently.
	 */
	public <T> T findAdapter(Class<T> type) {
		for (String name : getAdapterNames()) {
			AAdapter adapter = getAdapter(name);
			if (type.isInstance(adapter)) return type.cast(adapter);
		}
		return null;
	}

	/**
	 * Get all active adapter names
	 * @return Set of all registered, enabled adapter names
	 */
	public java.util.Set<String> getAdapterNames() {
		return adapters.keySet();
	}

	/** Active adapters in operator precedence order, oldest to newest. */
	public synchronized java.util.List<String> getAdapterNamesInRegistrationOrder() {
		return java.util.List.copyOf(adapterRegistrationOrder);
	}

	/**
	 * Get the names of registered adapters that are currently disabled.
	 * @return Set of disabled adapter names
	 */
	public java.util.Set<String> getDisabledAdapterNames() {
		return disabledAdapters.keySet();
	}

	// ========== Modules ==========

	/** Records a loaded module; publishes its {@code v/info/modules} entry once the catalog exists. */
	synchronized void addModule(Modules.LoadedModule module) {
		modules.remove(module.name());
		modules.put(module.name(), module);
		if (catalogPublished) VenueBootstrapMaterializer.materialiseModule(this, module);
	}

	/** Forgets a module and retracts its {@code v/info/modules} entry. */
	synchronized void dropModule(Modules.LoadedModule module) {
		if (modules.get(module.name()) != module) return;
		modules.remove(module.name());
		if (catalogPublished) VenueBootstrapMaterializer.dematerialiseModule(this, module.name());
	}

	/** A loaded module by name, or null. */
	public synchronized Modules.LoadedModule getModule(String name) {
		return modules.get(name);
	}

	/** Loaded modules in load order (snapshot). */
	public synchronized java.util.List<Modules.LoadedModule> getModules() {
		return java.util.List.copyOf(modules.values());
	}

	/** The module that registered the named adapter, or null for a built-in. */
	public synchronized Modules.LoadedModule moduleOf(String adapterName) {
		java.util.ArrayList<Modules.LoadedModule> loaded = new java.util.ArrayList<>(modules.values());
		for (int i = loaded.size() - 1; i >= 0; i--) {
			Modules.LoadedModule module = loaded.get(i);
			if (module.adapterNames().contains(adapterName)) return module;
		}
		return null;
	}

	/**
	 * Stores an asset in the venue-level CAS (used by adapter registration).
	 */
	public Hash storeAsset(AString meta, ACell content) {
		Hash id = venueState.assets().store(meta, content);
		log.info("Stored asset {} : {}", id, RT.getIn(JSON.parse(meta), Fields.NAME));
		return id;
	}

	/**
	 * Stores an asset in the caller's per-user CAS namespace.
	 */
	public Hash storeUserAsset(AString meta, ACell content, RequestContext ctx) {
		AString callerDID = ctx.getUserDID();
		if (callerDID == null) throw new IllegalArgumentException("Authentication required to store assets");
		User user = getVenueState().users().ensure(callerDID);
		Hash id = user.assets().store(meta, content);
		log.info("Stored user asset {} : {} (user: {})", id, RT.getIn(JSON.parse(meta), Fields.NAME), callerDID);
		return id;
	}

	/**
	 * Gets venue-level assets (adapter registrations).
	 */
	public AMap<ABlob, AVector<?>> getAssets() {
		return venueState.assets().getAll();
	}

	/**
	 * Get the Auth instance for user management.
	 * @return Auth instance
	 */
	public Auth getAuth() {
		return auth;
	}

	public static Engine createTemp(AMap<AString,ACell> config) {
		try {
			AKeyPair keyPair = AKeyPair.generate();
			CoviaApplication application = CoviaApplication.create(keyPair);
			return new Engine(config, application, keyPair).start();
		} catch (IOException e) {
			throw new Error(e);
		}
	}

	/**
	 * Compatibility lookup for internal hash-based operation resolution.
	 *
	 * <p>Checks the caller first, then the venue catalog because installed
	 * operations have historically been invoked by hash. User-facing asset APIs,
	 * where a bare hash means {@code <callerDID>/a/<hash>}, use the exact-DID
	 * overload and never enter this compatibility fallback.</p>
	 */
	public AVector<?> getAssetRecord(Hash assetID, RequestContext ctx) {
		AString callerDID = (ctx != null) ? ctx.getUserDID() : null;
		AVector<?> callerRecord = getAssetRecord(assetID, callerDID);
		if (callerRecord != null) return callerRecord;
		// Compatibility for internal operation resolution: many installed
		// definitions are invoked by their hash. User-facing REST resolution does
		// not use this fallback; it calls the exact-DID overload above.
		return venueState.assets().getRecord(assetID);
	}

	/**
	 * Get an asset record by Hash from one explicitly named DID namespace.
	 * The venue DID names the venue catalog; every other DID names exactly that
	 * user's {@code /a} store. There is deliberately no cross-namespace fallback.
	 */
	public AVector<?> getAssetRecord(Hash assetID, AString userDID) {
		if (assetID == null || userDID == null) return null;
		if (userDID.equals(getDIDString())) return venueState.assets().getRecord(assetID);
		AString webDID = config.getWebDID();
		if (webDID != null && userDID.equals(webDID)) return venueState.assets().getRecord(assetID);
		User user = getVenueState().users().get(userDID);
		return (user != null) ? user.assets().getRecord(assetID) : null;
	}

	/**
	 * Compatibility Asset lookup: caller namespace first, then venue catalog.
	 */
	public Asset getAsset(Hash assetID, RequestContext ctx) {
		AVector<?> arec = getAssetRecord(assetID, ctx);
		if (arec == null) return null;
		AString metaString = RT.ensureString(arec.get(AssetStore.POS_JSON));
		if (metaString == null) return null;
		return Asset.create(assetID, metaString);
	}

	/**
	 * Get an Asset by its Hash ID from venue-level store (no user context).
	 */
	public Asset getAsset(Hash assetID) {
		AVector<?> arec=venueState.assets().getRecord(assetID);
		if (arec==null) return null;
		AString metaString = RT.ensureString(arec.get(AssetStore.POS_JSON));
		if (metaString==null) return null;
		return Asset.create(assetID, metaString);
	}

	/**
	 * Get metadata as a JSON string
	 * @param assetID
	 * @return Metadata string for the given Asset ID, or null if not found
	 */
	public AString getMetadata(Hash assetID) {
		AVector<?> arec=venueState.assets().getRecord(assetID);
		if (arec==null) return null;
		return RT.ensureString(arec.get(AssetStore.POS_JSON));
	}

	/**
	 * Get metadata as a structured value
	 * @param assetID Asset ID of operation
	 * @return Metadata value, or null if not valid metadata
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString,ACell> getMetaValue(Hash assetID) {
		AVector<?> arec=venueState.assets().getRecord(assetID);
		if (arec==null) return null;
		// instanceof — RT.castMap(null) returns an empty map, which would
		// violate the "null if not valid metadata" contract.
		ACell meta = arec.get(AssetStore.POS_META);
		return (meta instanceof AMap) ? (AMap<AString, ACell>) meta : null;
	}

	// ========== Path resolution ==========
	//
	// This module provides two layered resolution functions:
	//
	// 1. resolvePath(ref, ctx) — pure single-step path navigation. Returns
	//    the LITERAL value at the resolved local lattice cell as an ACell.
	//    Handles: bare hex hash, /a/<hash>, /o/<name>, /v/<path> (future),
	//    local DID URLs, plain workspace paths. Returns null for remote
	//    DIDs and unresolvable refs. NEVER chases references; NEVER
	//    interprets values; NEVER recurses.
	//
	// 2. resolveAsset(ref, ctx) — composes resolvePath + Asset.fromMeta,
	//    with a separate branch for remote DID URLs that creates federated
	//    Operation handles. The legacy bare-name registry fallback is also
	//    here as a deprecated final step.
	//
	// The split is per OPERATIONS.md §4: read-side ops use resolvePath
	// (which gives them universal resolution); op-invocation paths use
	// resolveAsset (which adds asset interpretation and federation).
	//
	// There is NO automatic reference-following anywhere. A user pin at
	// /o/<name> that contains a non-asset value (e.g. a string or a map
	// without an "operation" field) is opaque data, not a reference. This
	// keeps the resolver primitive simple and explicit.

	/** Namespace prefix for immutable content-addressed assets (leading slash optional: a/ or /a/) */
	private static final AString NS_ASSET = Strings.intern("a/");
	/** Namespace prefix for DID URLs */
	private static final AString NS_DID   = Strings.intern("did:");
	/** Optional leading-slash sugar, stripped before virtual/workspace resolution */
	private static final AString SLASH    = Strings.intern("/");

	/** Strips a single optional leading slash: {@code "/w/x"} → {@code "w/x"}; {@code "w/x"} unchanged. */
	private static AString stripLeadingSlash(AString ref) {
		return ref.startsWith(SLASH) ? ref.slice(1) : ref;
	}

	/**
	 * A physical path in a user's local namespace. Relative paths belong to the
	 * caller; DID URL paths name their owner explicitly.
	 */
	public record UserPathTarget(AString ownerDID, User user, ACell[] pathKeys, AString resource) {
		public UserPathTarget {
			pathKeys = pathKeys.clone();
		}

		@Override
		public ACell[] pathKeys() {
			return pathKeys.clone();
		}
	}

	private record ParsedUserPath(AString ownerDID, ACell[] pathKeys, AString resource) {}

	/**
	 * Resolves a physical user path without applying an access policy. This is
	 * the common location primitive for adapters that enforce their capability
	 * at the point of action.
	 *
	 * @param create whether to create an absent target user namespace
	 */
	public UserPathTarget resolveUserPath(RequestContext ctx, AString ref, boolean create) {
		ParsedUserPath parsed = parseUserPath(ctx, ref);
		return locateUserPath(parsed, create);
	}

	/**
	 * Authorises and resolves a physical user path. Authorisation precedes
	 * optional namespace creation, so a denied delegated write has no side
	 * effects.
	 */
	public UserPathTarget requireUserPath(RequestContext ctx, AString ref, AString ability, boolean create) {
		ParsedUserPath parsed = parseUserPath(ctx, ref);
		requireLocalAccess(ctx, parsed.resource(), ability);
		return locateUserPath(parsed, create);
	}

	private ParsedUserPath parseUserPath(RequestContext ctx, AString ref) {
		if (ctx == null || ctx.getUserDID() == null) {
			throw new IllegalArgumentException("User path resolution requires an authenticated user");
		}
		if (ref == null || ref.isEmpty()) {
			throw new IllegalArgumentException("User path must not be empty");
		}

		AString ownerDID;
		ACell[] pathKeys;
		if (ref.startsWith(NS_DID)) {
			DIDURL didURL = DIDURL.create(ref.toString());
			ownerDID = Strings.create(didURL.getDID().toString());
			pathKeys = CoviaAdapter.parseStringPath(didURL.getPath());
		} else {
			ownerDID = ctx.getUserDID();
			pathKeys = CoviaAdapter.parseStringPath(stripLeadingSlash(ref).toString());
		}

		if (pathKeys.length == 0) {
			throw new IllegalArgumentException("User path must include a namespace");
		}

		StringBuilder resource = new StringBuilder(ownerDID.toString());
		for (ACell key : pathKeys) {
			resource.append('/').append(key);
		}
		return new ParsedUserPath(ownerDID, pathKeys, Strings.create(resource.toString()));
	}

	private UserPathTarget locateUserPath(ParsedUserPath parsed, boolean create) {
		Users users = venueState.users();
		User user = create ? users.ensure(parsed.ownerDID()) : users.get(parsed.ownerDID());
		return new UserPathTarget(parsed.ownerDID(), user, parsed.pathKeys(), parsed.resource());
	}

	/**
	 * Pure single-step path navigation. Returns the literal value at the
	 * resolved local lattice cell. Does NOT chase references, follow
	 * indirections, or interpret the value in any way.
	 *
	 * <p>Accepted input forms:</p>
	 * <ul>
	 *   <li>Bare hex hash → asset metadata from CAS</li>
	 *   <li>{@code a/<hash>} or {@code /a/<hash>} → asset metadata from CAS</li>
	 *   <li>{@code /o/<name>} → caller's own /o/ entry value</li>
	 *   <li>Local DID URL with {@code /a/<hash>} path → asset metadata</li>
	 *   <li>DID-qualified physical user path ({@code <did>/w/...},
	 *       {@code <did>/g/...}, etc.) → that user's cursor value</li>
	 *   <li>Workspace path ({@code w/...}, {@code g/...}, etc.) → cursor value</li>
	 * </ul>
	 *
	 * <p>Returns null for unresolvable refs, remote DID URLs, and refs that
	 * resolve to a missing lattice cell. Remote DID URLs are handled by
	 * {@link #resolveAsset(AString, RequestContext)} via definition fetch —
	 * resolvePath itself never touches the network.</p>
	 *
	 * @param ref Reference string
	 * @param ctx Request context (caller identity for /o/ and workspace navigation)
	 * @return Literal value at the resolved location, or null
	 */
	public ACell resolvePath(AString ref, RequestContext ctx) {
		if (ref == null) return null;

		// 1. Bare hex hash → look up in CAS
		Hash h = Hash.parse(ref);
		if (h != null) {
			Asset asset = getAsset(h, ctx);
			return (asset != null) ? asset.meta() : null;
		}

		// 2. a/<hash> or /a/<hash> → look up in CAS (leading slash optional)
		AString localRef = stripLeadingSlash(ref);
		if (localRef.startsWith(NS_ASSET)) {
			Hash ah = Hash.parse(localRef.slice(2));
			if (ah == null) return null;
			Asset asset = getAsset(ah, ctx);
			return (asset != null) ? asset.meta() : null;
		}

		// 3. DID URL — local cases only; remote asset fetch is handled by
		// resolveAsset. Asset catalog refs and physical user paths share the
		// platform DID-path grammar.
		if (ref.startsWith(NS_DID)) {
			Asset local = resolveLocalDIDURL(ref, ctx);
			if (local != null) return local.meta();
			UserPathTarget target;
			try {
				target = resolveUserPath(ctx, ref, false);
			} catch (IllegalArgumentException e) {
				return null;
			}
			if (!isPhysicalUserNamespace(target.pathKeys())) return null;
			return readUserPathValue(target);
		}

		// Steps 4–5 cover the virtual and workspace namespaces, where a
		// leading slash is optional sugar. Normalise it away once so "/w/notes" resolves exactly
		// like "w/notes" and "/v/ops/x" like "v/ops/x".
		AString navRef = localRef;

		// 4. Virtual namespace prefix (n/, v/, ...) — delegate to the
		// registered resolver via CoviaAdapter. Handles cursor-based
		// virtual namespaces uniformly. (t/ — job-scoped temp — is not
		// handled here; covia:read has its own t/ branch.)
		ACell virtualValue = resolveVirtualNamespace(navRef, ctx);
		if (virtualValue != null) return virtualValue;

		// 5. Workspace path (w/, g/, o/, j/, s/, h/) → caller's lattice
		if (isUserNamespacePath(navRef)) {
			return readWorkspacePathValue(navRef, ctx);
		}

		return null;
	}

	/**
	 * Delegates resolution of a virtual-namespace path to the
	 * {@link covia.adapter.CoviaAdapter}'s registered resolvers. Returns
	 * the literal value at the resolved location, or null if the path
	 * doesn't match a registered virtual prefix.
	 */
	private ACell resolveVirtualNamespace(AString ref, RequestContext ctx) {
		covia.adapter.CoviaAdapter coviaAdapter =
			(covia.adapter.CoviaAdapter) getAdapter("covia");
		if (coviaAdapter == null) return null;
		try {
			return coviaAdapter.readVirtualNamespace(ctx, ref);
		} catch (WrongScopeException e) {
			// The only resolver condition that is a genuine absence for a read: the
			// prefix (n/, c/) names a scope this context doesn't provide, so there
			// is no such path here. Everything ELSE — an auth failure, a malformed
			// path, a lower-level store fault, an abnormal navigation bug — is NOT
			// absence and propagates rather than being masked as "not found" (#175).
			return null;
		}
	}

	/**
	 * Resolves a reference to an Asset. Composes {@link #resolvePath} with
	 * {@link Asset#fromMeta}, plus a definition-fetch branch for remote DID
	 * URLs and a deprecated fallback to the legacy global operation registry.
	 *
	 * <p>Op-invocation paths ({@code grid:run}, agent loop, orchestration
	 * step dispatch) use this function. Read-side ops should use
	 * {@link #resolvePath} instead.</p>
	 *
	 * <p><b>Absence vs failure (#174).</b> Returns {@code null} for a genuine
	 * absence — the reference does not resolve to an asset (locally or, for a
	 * remote {@code did:web} reference, the publisher is reachable but has no
	 * such asset / does not bind the name). It <b>throws</b>
	 * {@link covia.exception.RemoteFetchException} when a remote fetch fails
	 * <em>operationally</em> (venue unreachable, remote error, malformed venue
	 * reference, or metadata that fails the content-addressing check) — a down
	 * venue is not the same as a missing operation. Callers on a single-ref
	 * path let it propagate (invoke → HTTP 502, job-aware adapter → job failure);
	 * aggregate callers that resolve many refs (tool/context assembly) catch it
	 * and degrade visibly.</p>
	 *
	 * @param ref Reference string
	 * @param ctx Request context (caller identity for /o/ namespace scoping)
	 * @return Resolved Asset, or null if genuinely not resolvable as an asset
	 * @throws covia.exception.RemoteFetchException on operational remote-fetch failure
	 */
	public Asset resolveAsset(AString ref, RequestContext ctx) {
		if (ref == null) return null;

		// Remote DID URLs: fetch the content-addressed definition from the
		// publishing venue (hash-verified). The returned Asset carries no
		// venue — execution, if any, is local. See resolveDIDURL.
		if (ref.startsWith(NS_DID)) {
			Asset asset = resolveDIDURL(ref, ctx);
			if (asset != null) return asset;
			// Fall through — DID URL might be unresolvable but other forms
			// could match (rare; defensive).
		}

		// Pure navigation, then asset interpretation. Only maps that have
		// an "operation" field can be interpreted as callable Assets;
		// other map shapes (and strings, vectors, scalars) resolve as raw
		// data but are not callable as operations.
		ACell value = resolvePath(ref, ctx);
		if (value instanceof AMap) {
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> map = (AMap<AString, ACell>) value;
			if (map.get(Fields.OPERATION) != null) {
				return Asset.fromMeta(map);
			}
		}

		return null;
	}

	/**
	 * Returns true if {@code ref} starts with a known user-namespace prefix
	 * (w/, g/, o/, j/, s/, n/, h/, c/) without a leading slash. Mirrors
	 * {@link covia.adapter.agent.ContextLoader} so the two resolvers stay aligned.
	 */
	static boolean isUserNamespacePath(AString ref) {
		if (ref == null || ref.count() < 2) return false;
		String s = ref.toString();
		return s.startsWith("w/") || s.startsWith("g/") || s.startsWith("o/")
			|| s.startsWith("j/") || s.startsWith("s/") || s.startsWith("h/")
			|| s.startsWith("n/") || s.startsWith("c/");
	}

	/**
	 * Reads the literal value at a workspace path through the caller's
	 * lattice cursor. Returns whatever's there, with no interpretation.
	 */
	private ACell readWorkspacePathValue(AString ref, RequestContext ctx) {
		if (ctx == null || ctx.getUserDID() == null) return null;
		return readUserPathValue(resolveUserPath(ctx, ref, false));
	}

	private static boolean isPhysicalUserNamespace(ACell[] pathKeys) {
		if (pathKeys.length == 0) return false;
		return switch (pathKeys[0].toString()) {
			case "w", "g", "o", "j", "s", "h" -> true;
			default -> false;
		};
	}

	private ACell readUserPathValue(UserPathTarget target) {
		User user = target.user();
		if (user == null) return null;
		ACell[] pathKeys = target.pathKeys();

		// Absence is a null return (no user, empty path, or readPath finding
		// nothing — none of which throw). Anything that throws is a real failure —
		// an abnormal navigation error, or a lower-level store fault — and it
		// propagates rather than collapsing to a phantom "path not found" (#175).
		return covia.adapter.CoviaAdapter.readPath(user.cursor(), pathKeys);
	}

	/**
	 * Resolves a DID URL to a local Asset only — returns null if we hold no
	 * copy. Used by {@link #resolvePath}, which never touches the network.
	 * Remote definition FETCH happens only on the invocation path, via
	 * {@link #resolveAsset} → {@link #resolveDIDURL}.
	 */
	private Asset resolveLocalDIDURL(AString ref, RequestContext ctx) {
		AssetRef r = parseAssetRef(ref);
		if (r == null) return null;
		if (r.hash() != null) return lookupLocalAsset(r);

		// A fully-qualified name owned by this venue is the same local binding as
		// its relative v/... form. Previously all named DID refs were treated as
		// remote, which made did:key:<this-venue>/v/... impossible to resolve and
		// made a did:web self-reference perform an unnecessary network fetch.
		if (!isVenueIdentity(r.didString())) return null;
		ACell value = resolvePath(Strings.create(r.name()), ctx);
		return (value instanceof AMap) ? Asset.fromMeta(value) : null;
	}

	/** Whether a DID names this venue, including its discoverable did:web alias. */
	private boolean isVenueIdentity(String did) {
		if (getDIDString().toString().equals(did)) return true;
		AString web = config.getWebDID();
		return web != null && web.toString().equals(did);
	}

	/**
	 * Resolves a reference to an Asset (internal use, no caller identity).
	 *
	 * @param ref Reference string
	 * @return Resolved Asset, or null if not resolvable
	 */
	public Asset resolveAsset(AString ref) {
		return resolveAsset(ref, venueContext());
	}

	/**
	 * A parsed DID URL asset reference. Two kinds, same semantics
	 * (references denote definitions, never execution sites), different
	 * verification:
	 * <ul>
	 *   <li><b>Hash</b> ({@code did:…/a/<hash>}) — self-verifying: the
	 *       hash IS the identity, any copy from anywhere is authoritative.</li>
	 *   <li><b>Named</b> ({@code did:…/v/ops/<…>}) — a mutable catalog
	 *       binding maintained by the named principal; resolving it means
	 *       taking the publisher's word for the name → hash binding (the
	 *       definition itself is still hash-verified on fetch).</li>
	 * </ul>
	 * Exactly one of {@code hash} / {@code name} is non-null. The DID
	 * string is kept unescaped ({@code did:key:VENUE:public}, not
	 * URL-encoded) to match user-record keying.
	 */
	private record AssetRef(String didString, String method, Hash hash, String name) {}

	/**
	 * Parses a DID URL reference with an {@code /a/<hash>} (hash) or
	 * {@code /v/…} (named catalog) path. Returns null for any other shape.
	 */
	private AssetRef parseAssetRef(AString ref) {
		DIDURL didurl;
		try {
			didurl = DIDURL.create(ref.toString());
		} catch (IllegalArgumentException e) {
			return null;
		}
		String path = didurl.getPath();
		if (path == null) return null;

		// Reconstruct the unescaped DID string — DID.toString() URL-encodes
		// colons in the id, which would break sub-id keys like "VENUE:public".
		DID did = didurl.getDID();
		String didString = "did:" + did.getMethod() + ":" + did.getID();

		if (path.startsWith("/a/")) {
			Hash hash = Hash.parse(path.substring(3));
			if (hash == null) return null;
			return new AssetRef(didString, did.getMethod(), hash, null);
		}
		if (path.startsWith("/v/")) {
			return new AssetRef(didString, did.getMethod(), null, path.substring(1));
		}
		return null;
	}

	/** Looks up a parsed asset reference in exactly the named DID's store. */
	private Asset lookupLocalAsset(AssetRef r) {
		AVector<?> rec = getAssetRecord(r.hash(), Strings.create(r.didString()));
		if (rec == null) return null;
		AString metaString = RT.ensureString(rec.get(AssetStore.POS_JSON));
		return (metaString != null) ? Asset.create(r.hash(), metaString) : null;
	}

	/**
	 * Resolves a {@code did:…/a/<hash>} reference: local copy if we hold
	 * one, else a hash-verified fetch from the publishing venue.
	 *
	 * <p>A reference names a DEFINITION, never an execution site. The DID
	 * prefix says where the content-addressed metadata can be fetched from;
	 * anything subsequently invoked with it executes locally, as an ordinary
	 * local job. Cross-venue EXECUTION is always explicit: grid:run /
	 * grid:invoke with a venue argument.</p>
	 *
	 * <p>{@link Grid#connect} currently only handles {@code did:web:}; for
	 * DID methods we cannot fetch from — including {@code did:key} — a
	 * local miss is simply "not found", so callers can fall through to
	 * other resolution forms or surface a clean error.</p>
	 */
	private Asset resolveDIDURL(AString ref, RequestContext ctx) {
		AssetRef r = parseAssetRef(ref);
		if (r == null) return null;
		if (r.name() != null) {
			// Named refs are publisher-scoped bindings — OUR v/ops/x is not
			// THEIR v/ops/x, so there is no local-copy shortcut.
			return "web".equals(r.method()) ? fetchRemoteNamedAsset(r.didString(), r.name()) : null;
		}
		Asset local = lookupLocalAsset(r);
		if (local != null) {
			// Definition resolution precedes adapter dispatch, so it must enforce
			// the private /a/ read itself. A later invoke capability is a separate
			// right and cannot retroactively authorise this lookup.
			requireResourceAccess(ctx, ref, Abilities.ASSET_READ);
			return local;
		}
		return "web".equals(r.method()) ? fetchRemoteAsset(r.didString(), r.hash()) : null;
	}

	/**
	 * Fetches a content-addressed asset definition from a remote venue.
	 *
	 * <p>Content addressing is the trust boundary: the fetched metadata must
	 * hash to the requested id, so the remote venue is purely an availability
	 * provider — it cannot substitute a different definition. The returned
	 * Asset carries no venue: holding a definition implies nothing about
	 * where it executes.</p>
	 *
	 * @param venueConn Remote venue connection string (did:web or URL)
	 * @param id Asset id (CAD3 value hash of the metadata)
	 * @return Verified Asset with metadata, or null if the asset is genuinely
	 *         absent (the venue is reachable but holds no such asset)
	 * @throws covia.exception.RemoteFetchException if the venue is unreachable,
	 *         returns an error, the connection string is malformed, or the
	 *         returned metadata fails the content-addressing check (#174)
	 */
	/**
	 * Fetched remote definitions, keyed by content hash. Definitions are
	 * immutable by construction, so entries can never go stale — the cache
	 * trades repeat network round-trips for nothing. Memory-only and
	 * transient: this is NOT adoption (pin is), just plumbing. The crude
	 * size cap guards against unbounded growth from unique-hash traffic;
	 * clearing it costs only re-fetches.
	 */
	private final java.util.concurrent.ConcurrentHashMap<Hash, AString> definitionCache =
		new java.util.concurrent.ConcurrentHashMap<>();
	private static final int DEFINITION_CACHE_MAX = 1000;

	private Asset cachedDefinition(Hash id) {
		AString meta = definitionCache.get(id);
		return (meta == null) ? null : Asset.create(id, meta);
	}

	private void cacheDefinition(Asset fetched) {
		if (fetched == null) return;
		if (definitionCache.size() >= DEFINITION_CACHE_MAX) definitionCache.clear();
		definitionCache.put(fetched.getID(), fetched.getMetadata());
	}

	public Asset fetchRemoteAsset(String venueConn, Hash id) {
		// Cache first: the hash IS the identity, so for a definition we
		// already hold the connection string is irrelevant — a venue in a
		// reference is a retrieval hint, not part of the name.
		Asset cached = cachedDefinition(id);
		if (cached != null) return cached;

		Venue remote;
		try {
			remote = Grid.connect(venueConn);
		} catch (IllegalArgumentException e) {
			throw RemoteFetchException.malformedVenue(venueConn, e);
		}
		Asset fetched = fetchRemoteAsset(remote, id);   // throws on unreachable/integrity; null on a genuine 404
		cacheDefinition(fetched);
		return fetched;
	}

	/**
	 * Fetches a named catalog operation from a remote venue. Two steps:
	 * resolve the name to an asset id AT THE PUBLISHER (names are mutable
	 * bindings — this is the one step taken on the namer's word), then
	 * fetch the definition itself hash-verified via
	 * {@link #fetchRemoteAsset(Venue, Hash)}.
	 *
	 * <p>Transient like all fetches: nothing is stored locally. The
	 * binding's resolved hash survives as provenance in whatever job
	 * record an invocation creates (the job's op field records the hash
	 * the name resolved to at invoke time).</p>
	 *
	 * @param venueConn Remote venue connection string (did:web or URL)
	 * @param name Catalog operation name, e.g. "v/ops/json/merge"
	 * @return Verified Asset with metadata, or null if the venue is reachable
	 *         but does not bind the name (a genuine absence)
	 * @throws covia.exception.RemoteFetchException if the venue is unreachable,
	 *         returns an error, or the connection string is malformed (#174)
	 */
	public Asset fetchRemoteNamedAsset(String venueConn, String name) {
		Venue remote;
		try {
			remote = Grid.connect(venueConn);
		} catch (IllegalArgumentException e) {
			throw RemoteFetchException.malformedVenue(venueConn, e);
		}
		// Resolve the name to an id AT THE PUBLISHER. An operational failure here
		// (venue down / error) is a real error naming the venue — NOT "operation
		// not found". A reachable venue that simply does not bind the name
		// returns a null id — that is a genuine absence.
		Hash id;
		try {
			id = remote.getOperationId(name);   // the BINDING is never cached (names are mutable)
		} catch (IOException | RuntimeException e) {
			if (e instanceof RemoteFetchException rfe) throw rfe;
			log.warn("Remote named fetch failed for {} at {}: {}", name, venueConn, e.toString());
			throw RemoteFetchException.fetchFailed(venueConn, "operation '" + name + "'", e);
		}
		if (id == null) return null;   // genuine absence: the venue does not bind this name
		Asset cached = cachedDefinition(id);
		if (cached != null) return cached;
		Asset fetched = fetchRemoteAsset(remote, id);   // throws on unreachable/integrity; null on 404
		cacheDefinition(fetched);
		return fetched;
	}

	/**
	 * Fetches the binary content of a remote asset reference, for adoption
	 * by {@code asset:pin}. Returns null when there is genuinely nothing to
	 * fetch: the reference is not a fetchable remote ref, the asset/name is
	 * absent, or the metadata declares no content. When the metadata declares
	 * a sha256, the fetched bytes are verified against it — substituted content
	 * throws rather than being adopted. Operational failures (venue unreachable,
	 * error, malformed reference) also throw (#174).
	 *
	 * @param ref Remote DID URL reference (hash or named form)
	 * @return Content blob, or null if there is genuinely none to fetch
	 * @throws covia.exception.RemoteFetchException on operational fetch failure
	 *         or a content sha256 mismatch (#174)
	 */
	public ACell fetchRemoteContent(AString ref) {
		AssetRef r = parseAssetRef(ref);
		if (r == null || !"web".equals(r.method())) return null;   // not a fetchable remote ref
		Venue remote;
		try {
			remote = Grid.connect(r.didString());
		} catch (IllegalArgumentException e) {
			throw RemoteFetchException.malformedVenue(r.didString(), e);
		}
		try {
			Hash id = (r.hash() != null) ? r.hash() : remote.getOperationId(r.name());
			if (id == null) return null;   // genuine absence: name not bound

			Asset def = cachedDefinition(id);
			if (def == null) def = fetchRemoteAsset(remote, id);   // throws / null=404
			if (def == null) return null;
			ACell contentMeta = RT.getIn(def.meta(), Fields.CONTENT);
			if (contentMeta == null) return null;   // metadata declares no content — genuine

			// HTTP federation names the publisher's explicit venue catalog; a bare
			// hash on the normal user API would instead mean the remote caller's /a/.
			AContent content;
			if (remote instanceof VenueHTTP http) {
				content = http.getVenueContent(id).join();
			} else {
				Asset handle = Asset.create(id, def.getMetadata());
				handle.setVenue(remote);
				content = handle.getContent();
			}
			if (content == null) return null;
			ABlob blob = content.getBlob().toFlatBlob();

			ACell declared = RT.getIn(contentMeta, Fields.SHA256);
			if (declared != null) {
				Hash sha = Hashing.sha256(blob.getBytes());
				if (!sha.toHexString().equals(declared.toString())) {
					throw RemoteFetchException.integrity(r.didString(), "content of " + id);
				}
			}
			return blob;
		} catch (RemoteFetchException e) {
			throw e;   // already meaningful (from fetchRemoteAsset or the integrity check)
		} catch (IOException | RuntimeException e) {
			log.warn("Remote content fetch failed for {} at {}: {}", ref, r.didString(), e.toString());
			throw RemoteFetchException.fetchFailed(r.didString(), ref, e);
		}
	}

	/**
	 * Fetches and verifies an asset definition from an already-connected
	 * remote venue. See {@link #fetchRemoteAsset(String, Hash)}.
	 *
	 * @param remote Remote venue
	 * @param id Asset id (CAD3 value hash of the metadata)
	 * @return Verified Asset with metadata, or null if the remote is reachable
	 *         but holds no such asset (a genuine absence)
	 * @throws covia.exception.RemoteFetchException if the remote is unreachable,
	 *         errors, or returns metadata that does not hash to {@code id} (#174)
	 */
	/** Best-effort human label for a venue in an error message — never throws
	 *  (a label must not be able to crash a fetch). */
	private static String venueLabel(Venue v) {
		try {
			Object did = v.getDID();
			if (did != null) return did.toString();
		} catch (RuntimeException ignored) { /* stub / unusable identity */ }
		return String.valueOf(v);
	}

	public Asset fetchRemoteAsset(Venue remote, Hash id) {
		String venue = venueLabel(remote);
		try {
			Asset fetched = (remote instanceof VenueHTTP http)
				? http.getVenueAsset(id) : remote.getAsset(id);
			if (fetched == null) return null;   // genuine absence: the remote answered but has no such asset
			// getID() recomputes the CAD3 hash from the returned metadata —
			// equality with the requested id IS the integrity check. (Also
			// throws on unparseable metadata, caught below and treated as a
			// failed fetch — the remote returned data we cannot verify.)
			if (!id.equals(fetched.getID())) {
				throw RemoteFetchException.integrity(venue, id);
			}
			return Asset.create(id, fetched.getMetadata());
		} catch (RemoteFetchException e) {
			throw e;
		} catch (IOException | RuntimeException e) {
			log.warn("Remote asset fetch failed for {} at {}: {}", id, venue, e.toString());
			throw RemoteFetchException.fetchFailed(venue, id, e);
		}
	}

	/**
	 * Resolves a reference to a local Hash. Does not handle remote DIDs.
	 * Use {@link #resolveAsset(AString, RequestContext)} for full resolution
	 * including remote dispatch.
	 *
	 * @param ref Reference string
	 * @return Local Hash, or null if not resolvable
	 */
	public Hash resolveHash(String ref) {
		return resolveHash(Strings.create(ref));
	}

	public Hash resolveHash(AString ref) {
		if (ref == null) return null;

		// 1. Bare hex hash
		Hash h = Hash.parse(ref);
		if (h != null) return h;

		// 2. Namespace prefix (leading slash is optional, as in resolvePath)
		AString assetRef = stripLeadingSlash(ref);
		if (assetRef.startsWith(NS_ASSET)) {
			return Hash.parse(assetRef.slice(2));
		}

		// 3. DID URL (local only — no remote dispatch)
		if (ref.startsWith(NS_DID)) {
			try {
				DIDURL didurl = DIDURL.create(ref.toString());
				String path = didurl.getPath();
				if (path != null && path.startsWith("/a/")) {
					return Hash.parse(path.substring(3));
				}
			} catch (IllegalArgumentException e) {
				return null;
			}
			return null;
		}

		// 4. Catalog path or other resolvable form — go through resolveAsset
		// to get the canonical hash from the resolved asset metadata.
		Asset asset = resolveAsset(ref);
		return (asset != null) ? asset.getID() : null;
	}

	/**
	 * Returns the current lattice state root.
	 * @return Lattice state as ACell, or null if not initialised
	 */
	public ACell getLatticeState() {
		return lattice.get();
	}

	/**
	 * Gets a content stream for the given asset
	 * @param meta Metadata of asset
	 * @return Content stream, or null if not available / does not exist
	 */
	public InputStream getContentStream(AMap<AString,ACell> meta) throws IOException {
		AContent c = contentFromMeta(meta);
		return (c == null) ? null : c.getInputStream();
	}

	/**
	 * Content derivable from metadata <em>alone</em>: {@code content.inline} bytes
	 * (embedded in the metadata, so covered by the asset identity hash) or a
	 * {@code content.sha256} blob from the global content store (the
	 * {@code PUT /content} upload path). No asset record ({@code POS_CONTENT}) or
	 * caller authority ({@code content.dlfs}) is needed.
	 *
	 * <p>This helper is deliberately limited to forms that require no caller
	 * authority. The universal path uses {@link #resolveContentBlock} for the
	 * complete standard descriptor, including generic references.</p>
	 *
	 * @return the content, or {@code null} when the metadata declares neither form
	 */
	private AContent contentFromMeta(AMap<AString,ACell> meta) throws IOException {
		if (meta == null) return null;
		AString inline = RT.ensureString(RT.getIn(meta, Fields.CONTENT, Fields.INLINE));
		if (inline != null) {
			return covia.grid.impl.BlobContent.of(convex.core.data.Blob.wrap(
				inline.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		Hash contentHash = Hash.parse(RT.ensureString(RT.getIn(meta, Fields.CONTENT, Fields.SHA256)));
		if (contentHash != null) return contentStorage.getContent(contentHash);
		return null;
	}

	/**
	 * Gets a content stream for the given asset
	 * @param meta Metadata of asset
	 * @return Content, or null if not available / does not exist
	 */
	public AContent getContent(AMap<AString,ACell> meta) throws IOException {
		return contentFromMeta(meta);
	}


	/**
	 * Gets a content stream for the given asset ID
	 * @param assetID Asset ID
	 * @return Content stream, or null if not available / does not exist
	 */
	public AContent getContent(Hash assetID) throws IOException {
		AMap<AString,ACell> meta=this.getMetaValue(assetID);
		return getContent(meta);
	}

	/**
	 * Gets the content for the given Asset
	 * @param asset Asset with metadata
	 * @return Content, or null if not available / does not exist
	 */
	public AContent getContent(Asset asset) throws IOException {
		return getContent(asset.meta());
	}

	/**
	 * Gets a content stream for the given Asset
	 * @param asset Asset with metadata
	 * @return Content stream, or null if not available / does not exist
	 */
	public InputStream getContentStream(Asset asset) throws IOException {
		return getContentStream(asset.meta());
	}

	// ========== Reference-addressed content (get/put across storage mechanisms) ==========

	/**
	 * Resolves a reference to content across every storage mechanism, under the
	 * caller's authority. Adapter-registered {@link covia.venue.storage.ContentProvider}s
	 * are consulted first (alternative stores like DLFS drives — each enforces
	 * its own access checks); then the content-addressed store: a hash-form
	 * asset ref ({@code a/<hash>}, bare hex, DID URL), a lattice path resolving
	 * to asset metadata or a reference string (followed one hop), or a raw blob
	 * value. Returns null when nothing content-bearing is found; throws when a
	 * provider recognises the ref but denies or cannot read it.
	 *
	 * @param ref a content reference in any supported form
	 * @param ctx the caller's request context (authority for all resolution)
	 * @return resolved content + declared content type, or null
	 */
	public covia.venue.storage.ContentProvider.Resolved resolveContent(
			AString ref, RequestContext ctx) throws IOException {
		return resolveContent(ref, ctx, new HashSet<>());
	}

	private covia.venue.storage.ContentProvider.Resolved resolveContent(
			AString ref, RequestContext ctx, Set<String> resolving) throws IOException {
		if (ref == null) return null;
		String refString = ref.toString();
		if (resolving.size() >= 32) {
			throw new IllegalArgumentException("Content reference chain exceeds 32 hops at: " + refString);
		}
		if (!resolving.add(refString)) {
			throw new IllegalArgumentException("Cyclic content reference: " + refString);
		}
		try {

		// Alternative storage mechanisms (e.g. DLFS drive paths).
		covia.venue.storage.ContentProvider.Resolved provided = providerContent(ref, ctx);
		if (provided != null) return provided;

		// Everything below is asset/lattice content. Providers above own their
		// namespace and enforce the native ability for it (for example crud/read on
		// file:// and dlfs/); imposing asset/read on those references would make the
		// same file behave differently through the content API and its native op.
		requireResourceAccess(ctx, ref, Abilities.ASSET_READ);

		// An explicit remote DID URL is a fetch address, just as it is for asset
		// metadata. Keep the fetch transient: asset:pin remains the operation that
		// adopts metadata + bytes into the caller's local CAS. did:key references
		// with no locally held owner cannot be fetched and resolve as absent.
		if (ref.startsWith(NS_DID) && !isLocalDIDResource(ref)) {
			Asset remoteAsset = resolveDIDURL(ref, ctx);
			if (remoteAsset == null) return null;
			ACell remoteContent = fetchRemoteContent(ref);
			if (remoteContent instanceof convex.core.data.ABlob b) {
				return new covia.venue.storage.ContentProvider.Resolved(
					covia.grid.impl.BlobContent.of(b), declaredContentType(remoteAsset.meta()));
			}
			return null;
		}

		// Content-addressed store: locate the CAS record. Hash-form refs name it
		// directly; other refs resolve first (a lattice slot may hold a reference
		// string — followed one hop — asset metadata, or a raw blob). A metadata
		// map with no CAS record (e.g. hand-written in a workspace) still serves
		// its metadata-DECLARED content (content.inline / content.dlfs below).
		AVector<?> record = null;
		ACell meta = null;
		Hash hash = covia.adapter.AssetAdapter.parseAssetId(ref);
		if (hash != null) {
			AString owner = requireLocalAccess(ctx, ref, Abilities.ASSET_READ);
			record = getAssetRecord(hash, owner);
			if (record != null) meta = record.get(AssetStore.POS_META);
		} else {
			ACell value = resolvePath(ref, ctx);
			if (value instanceof AString s) {
				// A lattice binding may point at any content reference, not just an
				// asset hash. Re-enter the universal resolver so file://, DLFS, DID,
				// workspace and future provider references all compose uniformly.
				return resolveContent(s, ctx, resolving);
			} else if (value instanceof AMap) {
				RequestContext recordContext = isVenuePath(ref) ? venueContext() : ctx;
				record = getAssetRecord(((AMap<?, ?>) value).getHash(), recordContext);
				meta = (record != null) ? record.get(AssetStore.POS_META) : value;
			} else if (value instanceof convex.core.data.ABlob b) {
				return new covia.venue.storage.ContentProvider.Resolved(
					covia.grid.impl.BlobContent.of(b), null);
			}
		}
		if (meta == null) return null;
		String contentType = declaredContentType(meta);
		if (record != null) {
			ACell content = record.get(AssetStore.POS_CONTENT);
			if (content instanceof convex.core.data.ABlob b) {
				return new covia.venue.storage.ContentProvider.Resolved(
					covia.grid.impl.BlobContent.of(b), contentType);
			}
		}

		// Asset metadata and file create operations use this exact same content
		// descriptor resolver. The canonical pointer is content.ref; content.dlfs
		// remains a compatibility alias for existing metadata.
		AMap<AString,ACell> metaMap = RT.ensureMap(meta);
		covia.venue.storage.ContentProvider.Resolved declared = resolveContentBlock(
			metaMap != null ? metaMap.get(Fields.CONTENT) : null, ctx, resolving);
		if (declared != null && contentType != null
				&& !contentType.equals(declared.contentType())) {
			return new covia.venue.storage.ContentProvider.Resolved(
				declared.content(), contentType);
		}
		return declared;
		} finally {
			resolving.remove(refString);
		}
	}

	/**
	 * Resolves the standard asset {@code content} descriptor. The same method is
	 * used when serving asset metadata and when a file-style create operation
	 * consumes a descriptor, keeping all content forms identical at both seams.
	 *
	 * <p>Supported locators are {@code inline} (UTF-8 text), {@code ref} (any
	 * universally resolvable content reference), and {@code sha256} (a blob in
	 * the content-addressed store). The historical {@code dlfs} pointer is an
	 * alias for {@code ref}. A sha256 alongside inline/ref is an integrity pin.</p>
	 */
	public covia.venue.storage.ContentProvider.Resolved resolveContentBlock(
			ACell block, RequestContext ctx) throws IOException {
		return resolveContentBlock(block, ctx, new HashSet<>());
	}

	@SuppressWarnings("unchecked")
	private covia.venue.storage.ContentProvider.Resolved resolveContentBlock(
			ACell block, RequestContext ctx, Set<String> resolving) throws IOException {
		if (block == null) return null;
		if (!(block instanceof AMap<?, ?>)) {
			throw new IllegalArgumentException("content must be an object");
		}
		AMap<AString, ACell> descriptor = (AMap<AString, ACell>) block;
		AString inline = descriptorString(descriptor, Fields.INLINE);
		AString ref = descriptorString(descriptor, Fields.REF);
		AString dlfs = descriptorString(descriptor, Strings.intern("dlfs"));
		if (ref != null && dlfs != null && !ref.equals(dlfs)) {
			throw new IllegalArgumentException("content.ref conflicts with legacy content.dlfs");
		}
		if (ref == null) ref = dlfs;
		if (inline != null && ref != null) {
			throw new IllegalArgumentException("content must specify only one of inline or ref");
		}

		AString shaString = descriptorString(descriptor, Fields.SHA256);
		Hash declaredHash = null;
		if (shaString != null) {
			declaredHash = Hash.parse(shaString);
			if (declaredHash == null) {
				throw new IllegalArgumentException("content.sha256 must be a valid SHA-256 hash");
			}
		}
		AString typeString = descriptorString(descriptor, Fields.CONTENT_TYPE);
		String declaredType = typeString != null ? typeString.toString() : null;

		covia.venue.storage.ContentProvider.Resolved resolved;
		String source;
		if (inline != null) {
			resolved = new covia.venue.storage.ContentProvider.Resolved(
				covia.grid.impl.BlobContent.of(Blob.wrap(
					inline.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))),
				declaredType);
			source = "inline content";
		} else if (ref != null) {
			resolved = resolveContent(ref, ctx, resolving);
			if (resolved == null) {
				throw new IllegalArgumentException("No content at ref: " + ref);
			}
			source = "content at '" + ref + "'";
		} else if (declaredHash != null) {
			AContent stored = contentStorage.getContent(declaredHash);
			if (stored == null) return null;
			resolved = new covia.venue.storage.ContentProvider.Resolved(stored, declaredType);
			source = "content store blob " + declaredHash;
		} else {
			return null;
		}

		if (declaredHash != null && (inline != null || ref != null)) {
			ABlob bytes = resolved.content().getBlob();
			Hash actual = Hashing.sha256(bytes.getBytes());
			if (!actual.equals(declaredHash)) {
				throw new IllegalStateException("Content hash mismatch for " + source
					+ ": expected " + declaredHash + ", got " + actual);
			}
			resolved = new covia.venue.storage.ContentProvider.Resolved(
				covia.grid.impl.BlobContent.of(bytes), resolved.contentType());
		}

		String effectiveType = declaredType != null ? declaredType : resolved.contentType();
		if (effectiveType != null && !effectiveType.equals(resolved.contentType())) {
			return new covia.venue.storage.ContentProvider.Resolved(
				resolved.content(), effectiveType);
		}
		return resolved;
	}

	private static AString descriptorString(AMap<AString, ACell> descriptor,
			AString field) {
		if (!descriptor.containsKey(field)) return null;
		AString value = RT.ensureString(descriptor.get(field));
		if (value == null) {
			throw new IllegalArgumentException("content." + field + " must be a string");
		}
		return value;
	}

	/** Declared MIME type: content.contentType is canonical; the historical
	 * top-level contentType remains a compatibility fallback. */
	private static String declaredContentType(ACell meta) {
		AString nested = RT.ensureString(RT.getIn(meta, Fields.CONTENT, Fields.CONTENT_TYPE));
		if (nested != null) return nested.toString();
		AString legacy = RT.ensureString(RT.getIn(meta, Fields.CONTENT_TYPE));
		return (legacy != null) ? legacy.toString() : null;
	}

	private static boolean isVenuePath(AString ref) {
		if (ref == null) return false;
		String s = ref.toString();
		return s.startsWith("v/") || s.startsWith("/v/");
	}

	/** Consults adapter-registered content providers for a reference; null when
	 *  none recognises its shape. */
	private covia.venue.storage.ContentProvider.Resolved providerContent(
			AString ref, RequestContext ctx) throws IOException {
		for (AAdapter a : adapters.values()) {
			if (a instanceof covia.venue.storage.ContentProvider p) {
				covia.venue.storage.ContentProvider.Resolved r = p.getContent(ref, ctx);
				if (r != null) return r;
			}
		}
		return null;
	}

	/**
	 * Stores content at a reference via an adapter-registered
	 * {@link covia.venue.storage.ContentProvider} (e.g. a DLFS drive path) —
	 * the write half of reference-addressed content. Returns false when no
	 * provider recognises the reference (content-addressed storage stays
	 * hash-keyed via the asset store paths). Providers enforce their own
	 * access checks and throw on denial.
	 */
	public boolean putContent(AString ref, InputStream data, String contentType,
			RequestContext ctx) throws IOException {
		if (ref == null) return false;
		for (AAdapter a : adapters.values()) {
			if (a instanceof covia.venue.storage.ContentProvider p) {
				if (p.putContent(ref, data, contentType, ctx)) return true;
			}
		}
		return false;
	}

	/**
	 * Puts content for the given Asset
	 * @param asset Asset with metadata specifying expected content hash
	 * @param is Input stream of content data
	 * @return Hash of verified stored content
	 */
	public Hash putContent(Asset asset, InputStream is) throws IOException {
		return putContent(asset.meta(), is);
	}

	public Hash putContent(Hash assetID, InputStream is) throws IOException {
		AMap<AString, ACell> meta = getMetaValue(assetID);
		if (meta==null) throw new IllegalArgumentException("No metadata");
		return putContent(meta,is);
	}

	public Hash putContent(AMap<AString, ACell> meta, InputStream is) throws IOException {
		if (meta==null) throw new IllegalArgumentException("No metadata");
		AMap<AString,ACell> content=RT.ensureMap(meta.get(Fields.CONTENT));
		if (content==null) throw new IllegalArgumentException("Metadata does not have content object specified");
		Hash expectedHash=Hash.parse(RT.ensureString(content.get(Fields.SHA256)));
		if (expectedHash==null) {
			throw new IllegalArgumentException("Metadata does not have valid content hash");
		}

		// Read with size limit to prevent OOM from oversized uploads
		long maxSize = config.getMaxContentSize();
		byte[] data = is.readNBytes((int) Math.min(maxSize + 1, Integer.MAX_VALUE));
		if (data.length > maxSize) {
			throw new IllegalArgumentException("Content exceeds maximum size of " + maxSize + " bytes");
		}
		Blob contentBlob = Blob.wrap(data);
		Hash actualHash = Hashing.sha256(contentBlob.getBytes());

		// Verify the actual hash matches the expected hash from metadata
		if (!actualHash.equals(expectedHash)) {
			throw new IllegalArgumentException("Content hash mismatch. Expected: " + expectedHash.toHexString() + ", Actual: " + actualHash.toHexString());
		}

		// Store the content using the verified hash
		contentStorage.store(actualHash, new ByteArrayInputStream(data));
		log.info("Stored content with SHA256: "+actualHash);
		return actualHash;
	}

	private AMap<AString,ACell> STATUS_MAP=Maps.of(Fields.STATUS,Fields.OK);

	public AMap<AString,ACell> getStatus() {
		AMap<AString,ACell> status=STATUS_MAP;
		status=status.assoc(Fields.TS, CVMLong.create(Utils.getCurrentTimestamp()));
		status=status.assoc(Fields.DID, getDIDString());

		AString name=getName();
		if (name!=null) {
			status=status.assoc(Fields.NAME, name);
		}

		// Build version so operators can detect version drift across venues.
		// jarVersion() prefers the venue's own Maven descriptor, then the jar
		// manifest's Implementation-Version, else "dev" — never null. See #139, #420.
		status=status.assoc(Fields.VERSION, Strings.create(jarVersion()));
		status=status.assoc(Fields.UCAN_PROFILE, UCAN.VERSION);

		return status;
	}

	/**
	 * Get the Config instance for this engine.
	 * @return Config instance with typed accessors
	 */
	public Config config() {
		return config;
	}

	/**
	 * Get the AccessControl instance for this engine.
	 * @return AccessControl instance
	 */
	public AccessControl getAccessControl() {
		return accessControl;
	}

	/**
	 * Get the raw config map.
	 * @return Config map
	 * @deprecated Use {@link #config()} for typed access
	 */
	@Deprecated
	public AMap<AString,ACell> getConfig() {
		return config.getMap();
	}

	public DID getDID() {
		return DID.fromString(getDIDString().toString());
	}

	/**
	 * Returns a {@link RequestContext} bound to the venue's own DID — the
	 * caller is the venue itself. Used by engine-startup code (asset
	 * materialisation, /v/ writes, recovery) and any other code path where
	 * trust is established by being inside the venue runtime.
	 *
	 * <p>This replaces the older {@code RequestContext.INTERNAL} pattern,
	 * which used a no-DID context with an {@code internal} flag. The flag
	 * conflated "trusted code path" with "venue is the caller" and depended
	 * on every reader checking the flag explicitly. Naming the venue as the
	 * caller removes the special case — the cap check, ownership check,
	 * and all auth paths just work against the venue's DID directly.</p>
	 */
	public RequestContext venueContext() {
		return RequestContext.of(getDIDString());
	}

	/**
	 * Returns a durable venue-principal workspace scoped to one adapter at
	 * {@code w/adapters/<name>/}. Trusted adapter code uses this for private,
	 * schema-owned operational state; user-managed storage continues to use
	 * caller-selected paths through ordinary operations.
	 */
	public AdapterWorkspace adapterWorkspace(String adapterName) {
		return new AdapterWorkspace(this, adapterName);
	}

	/**
	 * Builds a DID URL for an asset: {@code <venue-did>/a/<hex-hash>}
	 */
	public AString assetDIDURL(Hash hash) {
		return getDIDString().append("/a/" + hash.toHexString());
	}

	public AString getDIDString() {
		AString s=config.getDID();
		if (s==null) {
			AString key=Multikey.encodePublicKey(keyPair.getAccountKey());
			s=Strings.create("did:key:"+key);
		}
		return s;
	}

	/**
	 * Fail-closed check that an operator-declared identity (config {@code did},
	 * covia#343) is one this venue can actually prove: a declared did:key must
	 * match the venue key pair — an explicit identity pin, so a venue started
	 * with the wrong seed or keystore refuses to run rather than silently
	 * assuming a new identity — and a declared did:web must match the public
	 * hostname's derived form. Shape validation lives in {@link Config}.
	 */
	private void validateDeclaredIdentity() {
		AString declared = config.getDID();
		if (declared == null) return;
		String d = declared.toString();
		if (d.startsWith("did:key:")) {
			AString derived = Strings.create("did:key:"
				+ Multikey.encodePublicKey(keyPair.getAccountKey()));
			if (!derived.equals(declared)) {
				throw new IllegalStateException("Declared venue identity " + declared
					+ " does not match the venue key pair (" + derived
					+ ") — wrong seed or keystore?");
			}
		} else if (d.startsWith("did:web:")) {
			AString web = config.getWebDID();
			if (!declared.equals(web)) {
				throw new IllegalStateException("Declared venue identity " + declared
					+ " does not match the venue's did:web form ("
					+ (web == null ? "no public hostname" : web) + ")");
			}
		}
	}

	private volatile covia.venue.auth.VenueDIDVerifier didVerifier;

	/**
	 * The venue's DID signature verifier (covia#343): its own identity and local
	 * users from venue state, then remote identities through a method-keyed
	 * resolver registry. Built-ins support did:key and did:web; future methods
	 * can register without changing ingress, UCAN, or federation code.
	 */
	public covia.venue.auth.VenueDIDVerifier didVerifier() {
		covia.venue.auth.VenueDIDVerifier v = didVerifier;
		if (v == null) {
			synchronized (this) {
				if (didVerifier == null) {
					didVerifier = new covia.venue.auth.VenueDIDVerifier(this);
				}
				v = didVerifier;
			}
		}
		return v;
	}

	/**
	 * Converts a venue-managed username to its canonical user DID. Publicly
	 * named venues use their did:web alias (for example
	 * {@code did:web:venue.example.com:u:alice}). A public hostname is required
	 * because a did:key identifies one key and is not a namespace for managed
	 * usernames.
	 */
	public AString managedUserDID(AString username) {
		if (username == null || username.isEmpty()) {
			throw new IllegalArgumentException("username is required");
		}
		if (!username.toString().matches("[A-Za-z0-9._-]+")) {
			throw new IllegalArgumentException(
				"username may contain only letters, numbers, '.', '_' and '-'");
		}
		AString base = config.getWebDID();
		if (base == null) {
			throw new IllegalStateException("Venue-managed usernames require a public hostname "
				+ "so they can use did:web; pass a full user DID instead");
		}
		return Strings.create(base + ":u:" + username);
	}

	/**
	 * Whether a DID is a venue-managed user identity minted under this venue's
	 * current {@code did:web} namespace.
	 *
	 * <p>This is deliberately stricter than a textual prefix check: the suffix
	 * must be one username segment accepted by {@link #managedUserDID(AString)}.
	 * An arbitrary DID merely registered at this venue remains externally
	 * controlled and must never be mistaken for a custodial identity.</p>
	 */
	public boolean isManagedUserDID(AString did) {
		return managedUserName(did) != null;
	}

	/**
	 * Local username encoded by a venue-managed DID, or null when the DID is
	 * outside this venue's exact named-user namespace.
	 */
	public AString managedUserName(AString did) {
		AString base = config.getWebDID();
		if (base == null || did == null) return null;
		return userNameBelow(did, base);
	}

	/**
	 * A venue-issued user below this venue's actual identity. Personal venues
	 * sign login identities such as {@code did:key:...:u:alice}; this does not
	 * make did:key a generally routable namespace, but it does make the venue
	 * authoritative for the exact subjects it issued itself.
	 */
	private boolean isVenueIssuedUserDID(AString did) {
		return userNameBelow(did, getDIDString()) != null;
	}

	/** One valid username segment immediately below an exact DID base. */
	private static AString userNameBelow(AString did, AString base) {
		if (base == null || did == null) return null;
		String prefix = base + ":u:";
		String value = did.toString();
		if (!value.startsWith(prefix)) return null;
		String username = value.substring(prefix.length());
		if (username.isEmpty() || !username.matches("[A-Za-z0-9._-]+")) return null;
		return Strings.create(username);
	}

	/**
	 * Root-authority policy for resources enforced by this venue.
	 *
	 * <p>Self-sovereign owners root their own grants. The venue may additionally
	 * attest only for user DIDs it issued directly below its own identity. This
	 * includes the venue-signed {@code did:key:...:u:name} subjects used by a
	 * personal venue as well as named {@code did:web} users. Merely registering
	 * an arbitrary external DID does not transfer control of that identity.</p>
	 */
	public RootAuthorityPolicy rootAuthorityPolicy() {
		AString venueDID = getDIDString();
		return RootAuthorityPolicy.SELF_SOVEREIGN.or((root, resource) -> {
			if (!venueDID.equals(root) || resource == null) return false;
			DID owner = RootAuthorityPolicy.ownerDID(resource);
			if (owner == null) return false;
			AString ownerDID = Strings.create("did:" + owner.getMethod() + ":" + owner.getID());
			return isManagedUserDID(ownerDID) || isVenueIssuedUserDID(ownerDID);
		});
	}

	/** Evaluate presented proofs under this venue's complete root policy. */
	public boolean proofsCover(RequestContext ctx, AString resource, AString ability, long now) {
		if (ctx == null) return false;
		return ctx.delegatedProofsCover(rootAuthorityPolicy(), resource, ability, now);
	}

	/**
	 * Structural/temporal proof check without application caveat evaluation.
	 * This is intentionally separate from {@link #proofsCover}: callers may use
	 * it for a future validity horizon or diagnostics, never to permit a runtime
	 * action.
	 */
	public boolean proofsStructurallyCover(RequestContext ctx, AString resource,
			AString ability, long now) {
		if (ctx == null) return false;
		return covia.lattice.CapabilityChecker.proofsStructurallyCover(
			ctx.getProofs(), ctx.getCallerDID(), rootAuthorityPolicy(),
			resource, ability, now);
	}

	/**
	 * Resolves the runtime account for an authenticated DID. Authentication
	 * proves control of an identity; it does not implicitly provision an
	 * account unless the venue explicitly enables users.autoCreate.
	 */
	public User admitUser(AString did) {
		if (did == null) throw new AuthException("Authentication required");
		requireNotSubPrincipal(did);
		Users users = venueState.users();
		User user = users.get(did);
		if (user != null) return user;
		if (config.isUserAutoCreate()) return users.ensure(did);
		throw new AuthException("User is not registered at this venue: " + did
			+ ". Ask a venue administrator to provision it with user:create; "
			+ "public test venues may enable users.autoCreate.");
	}

	/**
	 * Rejects an agent-shaped DID where a <em>user</em> identity is required.
	 *
	 * <p>An agent DID is {@code <owner>:g:<agentId>}, and that nesting is
	 * load-bearing: {@code Principals.userOf} resolves it to the owner's
	 * namespace. Admitting such a DID as a user in its own right would therefore
	 * hand its bearer the namespace of whoever the prefix names. Agents are
	 * minted by the venue as sub-principals of an existing account — they are
	 * never registered, never authenticate, and so must never reach a user
	 * record. Keeping that invariant at the admission boundary is what makes the
	 * name safe to parse everywhere else.</p>
	 *
	 * <p>Self-issued subjects may use any installed DID method, so this check is
	 * an intentional method-independent admission invariant rather than an
	 * assumption about how the subject's signing key is represented.</p>
	 */
	public void requireNotSubPrincipal(AString did) {
		if (covia.grid.Principals.isAgentDID(did)) {
			throw new AuthException("Not a user identity: " + did
				+ " names an agent sub-principal of " + covia.grid.Principals.userOf(did)
				+ ". Agents are created with agent:create under an existing account, "
				+ "not registered as users.");
		}
	}

	/**
	 * Builds the venue DID document served at {@code /.well-known/did.json}.
	 *
	 * <p>The document {@code id} must equal the DID a resolver asked for (DID
	 * Core), so when the venue has a public hostname configured the document is
	 * presented under {@code did:web:<hostname>}, with the did:key in
	 * {@code alsoKnownAs} — making strict did:web resolution work (covia#167).
	 * Consumers respect the presented identity as-is: {@code alsoKnownAs} is
	 * the DID spec's informational same-subject cross-reference, never a
	 * canonical identity to re-bind to (covia#343 — the did:key is key
	 * material, published as a verification method; the identity a persistent
	 * venue presents is its did:web). Without a public hostname the document
	 * is served under the did:key directly, unchanged.</p>
	 *
	 * @param endpoint Service endpoint URL for the CoviaGrid service entry
	 * @return DID document map
	 */
	public AMap<AString, ACell> getDIDDocument(String endpoint) {
		AString canonicalDID=getDIDString();

		// Presentation identity: the declared DID when it is did:web; otherwise
		// the discoverable did:web form when a public hostname exists, else the
		// key-derived DID. Consumers retain docID exactly as presented.
		AString webDID=config.getWebDID();
		boolean aliased=(webDID!=null) && !webDID.equals(canonicalDID);
		AString docID=aliased ? webDID : canonicalDID;

		AString key=Multikey.encodePublicKey(keyPair.getAccountKey());
		AString keyID=Strings.create(docID+"#"+key);
		AVector<AString> keyVector=Vectors.create(keyID);

		AMap<AString,ACell> ddo=Maps.of(
			"id", docID,
			"@context", "https://www.w3.org/ns/did/v1",
			"verificationMethod",Vectors.of(Maps.of(
						"id",keyID,
						"type","Multikey",
						"controller",docID,
						"publicKeyMultibase",key
					)),
			"authentication",keyVector,
			"assertionMethod",keyVector,
			"capabilityDelegation",keyVector,
			"capabilityInvocation",keyVector,
			"service",Vectors.of(
					Maps.of(
							"type",CoviaAPI.SERVICE_TYPE,
							"serviceEndpoint",endpoint
					))
		);

		// Informational same-subject cross-reference (non-authoritative, DID
		// Core): consumers keep the identity they resolved — no rebinding.
		if (aliased) {
			ddo=ddo.assoc(Strings.intern("alsoKnownAs"), Vectors.create(canonicalDID));
		}

		return ddo;
	}

	public AccountKey getAccountKey() {
		return keyPair.getAccountKey();
	}

	/**
	 * Get the key pair for this venue engine.
	 * Used for signing venue-issued JWTs and other cryptographic operations.
	 * @return The venue's AKeyPair
	 */
	public AKeyPair getKeyPair() {
		return keyPair;
	}

	/**
	 * Gets the venue state wrapper for direct access to lattice state.
	 * @return VenueState wrapping this venue's lattice cursor
	 */
	public VenueState getVenueState() {
		return venueState;
	}

	/**
	 * Looks up an agent's lattice state by owner DID and agent ID.
	 *
	 * <p>Returns null if the user doesn't exist, the agent isn't initialised,
	 * or the agent is {@link AgentState#TERMINATED}. Used by the harness to
	 * read agent record fields (config, tasks, pending, sessions) directly
	 * from the lattice instead of plumbing them through the step input.</p>
	 *
	 * @param callerDID Agent owner's DID (never null)
	 * @param agentId   Agent identifier (never null)
	 * @return AgentState wrapper, or null if not found / terminated
	 */
	public AgentState getAgent(AString callerDID, AString agentId) {
		if (callerDID == null || agentId == null) return null;
		Users users = venueState.users();
		User user = users.get(callerDID);
		if (user == null) return null;
		AgentState agent = user.agent(agentId);
		if (agent == null) return null;
		if (AgentState.TERMINATED.equals(agent.getStatus())) return null;
		return agent;
	}

	/**
	 * Gets the root lattice cursor. Used by adapters that need access to
	 * top-level lattice regions (e.g. DLFSAdapter for the :dlfs region).
	 */
	public ALatticeCursor<Index<Keyword, ACell>> getRootCursor() {
		return lattice;
	}

	/**
	 * Connects a DLFS drive to the hosted lattice, enabling incremental blob
	 * persistence when this engine has a store-backed application host.
	 *
	 * <p>Legacy raw-cursor embedders have no store policy available here and
	 * retain the heap-backed behaviour of the two-argument Convex API.</p>
	 */
	public DLFSLocal connectDLFSDrive(ALatticeCursor<?> parent, AString driveName) {
		if (application == null) return DLFS.connect(parent, driveName);
		return DLFS.connect(parent, driveName, application.hostStore());
	}

	public AString getName() {
		return config.getName();
	}

	public AMap<AString, ACell> getStats() {
		AMap<AString, AMap<AString, ACell>> usersMap = auth.getUsers();
		// Count primitives across all adapters' catalog entries — this is
		// the canonical "what's in /v/ops/ and /v/test/ops/" total.
		long opCount = 0;
		for (var adapter : adapters.values()) {
			opCount += adapter.pendingCatalogEntries.size();
		}
		return Maps.of(
				 "assets",getAssets().size(),
				 "users",usersMap != null ? usersMap.count() : 0,
				 "ops",opCount,
				 "jobs",countJobs()
				);
	}

	/** Total persisted jobs across all users — the {@code stats.jobs} count (#229). */
	public long countJobs() {
		AMap<AString, ACell> userData = getVenueState().users().getAll();
		if (userData == null) return 0;
		long total = 0;
		for (var entry : userData.entrySet()) {
			User user = getVenueState().users().get((AString) entry.getKey());
			if (user != null) total += user.getJobs().count();
		}
		return total;
	}

	/**
	 * Gets the JobManager for job lifecycle operations.
	 * @return JobManager instance
	 */
	public JobManager jobs() {
		return jobManager;
	}

	/**
	 * Gets the per-venue grid scheduler — fires deferred grid-operation
	 * invocations. See {@code venue/docs/GRID_SCHEDULER.md}.
	 * @return Scheduler instance
	 */
	public Scheduler gridScheduler() {
		return gridScheduler;
	}

	/**
	 * The live agent event tap (#394). In-process consumers subscribe here —
	 * per agent or venue-wide — and see the same ordered events the REST
	 * stream {@code GET /agents/{id}/sse} carries. See AGENT_LOOP.md §2.6.
	 * @return AgentEvents instance
	 */
	public AgentEvents agentEvents() {
		return agentEvents;
	}

	/**
	 * Re-derives every agent's single {@code agent:wake} event from the
	 * authoritative per-thread {@code wakeTime} fields in the lattice. Called
	 * once during {@link #start()}: the {@code :schedule} index already
	 * persists across restarts, but a crash could leave a stored handle stale,
	 * so each agent is rebuilt idempotently (cancel any prior handle, re-arm at
	 * the earliest pending wake). See {@code venue/docs/GRID_SCHEDULER.md §8}.
	 */
	void rebuildSchedulerFromLattice() {
		AMap<AString, ACell> userData = venueState.users().getAll();
		if (userData == null || userData.isEmpty()) return;

		int count = 0;
		for (var userEntry : userData.entrySet()) {
			AString userDid = (AString) userEntry.getKey();
			User user = venueState.users().get(userDid);
			if (user == null) continue;
			AMap<AString, ACell> agents = user.getAgents();
			if (agents == null || agents.isEmpty()) continue;
			for (var agentEntry : agents.entrySet()) {
				AString agentId = (AString) agentEntry.getKey();
				if (!(agentEntry.getValue() instanceof AMap)) continue;
				AgentState agent = user.agent(agentId);
				if (agent == null) continue;
				if (agent.rescheduleWake(gridScheduler, userDid)) count++;
			}
		}
		if (count > 0) {
			log.info("Scheduler: re-armed {} pending agent wake(s) from lattice", count);
		}
	}

	// ========== Cross-user authorisation ==========

	/**
	 * The single cross-user authorisation gate (covia#102): may this caller
	 * act on a resource belonging to another user?
	 *
	 * <p>Three rights compose, checked in order:</p>
	 * <ol>
	 *   <li><b>Ambient public access</b> (covia#254) — a resource owned by the
	 *       venue's PUBLIC identity follows the public capability grant scope for
	 *       ANY caller: an authenticated caller is at least as privileged as
	 *       the anonymous one. Checked only when the resource is actually
	 *       public-owned; tracks operator policy ({@code auth.public.caps} —
	 *       default read-only, widened at the operator's own risk).</li>
	 *   <li><b>Target-side admission</b> (covia#447) — an agent record's
	 *       {@code config.accepts}: the owner's standing policy for who may
	 *       talk to that agent ({@code agent/request}, {@code agent/message})
	 *       without a delegation — the venue operator, or exact principal
	 *       DIDs. Consulted for that one resource shape and those abilities
	 *       only; never admits the public principal. See {@link Admission}.</li>
	 *   <li><b>Presented UCAN proofs</b> — the pure fail-closed delegation
	 *       check ({@link covia.lattice.CapabilityChecker#proofsCover}).</li>
	 * </ol>
	 */
	public boolean crossUserAllows(RequestContext ctx, AString resource, AString ability) {
		if (ctx == null || resource == null || ability == null) return false;
		// The venue's explicitly addressed asset catalog is public. This is NOT
		// true of another user's /a/: knowing a hash is not authorisation to read
		// someone else's asset. On user-facing APIs a bare hash names the caller's
		// own /a/; internal operation resolution retains a documented compatibility
		// fallback for installed venue operations.
		if (isVenueCatalogRead(resource, ability)) return true;
		if (auth.isPublicAccessEnabled()) {
			String publicDIDStr = getDIDString().toString() + ":public";
			String r = resource.toString();
			if (r.equals(publicDIDStr) || r.startsWith(publicDIDStr + "/")) {
				AString publicDID = Strings.create(publicDIDStr);
				convex.core.data.AVector<ACell> publicScope = auth.getPublicScope(publicDID);
				// null scope = operator-configured unrestricted public access
				if (publicScope == null || covia.lattice.CapabilityChecker.allows(
						publicScope, resource, ability, publicDID) == null) {
					return true;
				}
			}
		}
		if (admissionAllows(ctx, resource, ability)) return true;
		return proofsCover(ctx, resource, ability, System.currentTimeMillis() / 1000);
	}

	/** The abilities that mean "talk to this agent": submit a request or a message. */
	private static boolean isTalkAbility(AString ability) {
		return Abilities.AGENT_REQUEST.equals(ability) || Abilities.AGENT_MESSAGE.equals(ability);
	}

	/**
	 * Target-side admission (covia#447): the resource owner's own standing policy
	 * for who may talk to their agent, consulted for exactly one resource shape —
	 * an agent record {@code <owner>/g/<id>} — and the two talk abilities. The
	 * policy algebra is {@link Admission}; this method only locates the record.
	 * The public principal is never admitted here: anonymous exposure stays
	 * A2A's {@code a2a.public} + {@code a2a.caps}.
	 */
	private boolean admissionAllows(RequestContext ctx, AString resource, AString ability) {
		if (!isTalkAbility(ability)) return false;
		AString caller = ctx.getCallerDID();
		if (caller == null || isPublicPrincipal(caller)) return false;
		AString owner = ownerOf(resource);
		if (owner == null) return false;
		String rest = resource.toString().substring(owner.toString().length());
		if (!rest.startsWith("/g/")) return false;
		String agentId = rest.substring(3);
		if (agentId.isEmpty() || agentId.indexOf('/') >= 0) return false;
		User user = venueState.users().get(owner);
		AgentState agent = (user != null) ? user.agent(agentId) : null;
		if (agent == null) return false;
		ACell accepts = RT.getIn(agent.getConfig(), Fields.ACCEPTS);
		return Admission.admits(accepts, caller, isVenuePrincipal(ctx.getUserDID()));
	}

	/**
	 * Whether {@code principal} is the venue operator's own user identity — the
	 * venue's canonical DID or its {@code did:web} alias. Agents the venue owns
	 * have this as their user, so "the venue" as an admission class means the
	 * operator and the operator's agents, never every user hosted here.
	 */
	public boolean isVenuePrincipal(AString principal) {
		if (principal == null) return false;
		if (principal.equals(getDIDString())) return true;
		AString web = config.getWebDID();
		return web != null && principal.equals(web);
	}

	/**
	 * Authorises an explicit request to execute within another user's namespace.
	 * The request itself is the instruction; the {@code user/sudo} capability is
	 * only the permission check. Authentication and actor attribution are retained.
	 *
	 * <p>The returned context is proof-bounded so the actor's ambient authority
	 * over its own account cannot leak into the target account. The sudo grant
	 * selects only the namespace: the invoked operation and every point of action
	 * must still be covered independently.</p>
	 */
	public RequestContext sudoContext(RequestContext ctx, AString userDID) {
		if (ctx == null || ctx.getCallerDID() == null
				|| isPublicPrincipal(ctx.getCallerDID())) {
			throw new AuthException("Authentication required for user/sudo");
		}
		if (userDID == null) throw new IllegalArgumentException("Target user DID is required");
		requireNotSubPrincipal(userDID);
		if (userDID.equals(ctx.getUserDID())) return ctx;
		if (!crossUserAllows(ctx, userDID, Abilities.USER_SUDO)) {
			throw new AuthException("Sudo denied: requires user/sudo on " + userDID
				+ " from that user");
		}
		return ctx.onBehalfOf(userDID);
	}

	/** A read of the venue's explicitly addressed content catalog
	 *  ({@code <venueDID>/a/…}). It is public only for reads and only for the
	 *  venue's own DID; bare hashes and another user's {@code /a/} are private. */
	private boolean isVenueCatalogRead(AString resource, AString ability) {
		if (!Capability.CRUD_READ.equals(ability) && !Abilities.ASSET_READ.equals(ability)) return false;
		String value = resource.toString();
		if (isVenueReference(value, getDIDString().toString())) return true;
		AString web = config.getWebDID();
		return web != null && isVenueReference(value, web.toString());
	}

	private static boolean isVenueReference(String resource, String did) {
		return resource.startsWith(did + "/a/") || resource.startsWith(did + "/v/");
	}

	/**
	 * The single gate for LOCAL, per-DID resource access ({@code a/}, {@code w/},
	 * {@code s/}, {@code g/}, {@code j/}, …), governed uniformly for reads and
	 * mutations alike — an asset ({@code a/}) exactly like a workspace path
	 * ({@code w/}). The caller's own resource goes through the capability scope
	 * seam; another user's needs {@code ability} rights (a presented proof, or
	 * public / venue-catalog policy). A cross-user resource the caller has no
	 * rights to is a <b>denial</b>, never a silent miss.
	 *
	 * <p>Returns the DID whose namespace the operation targets — the caller's own
	 * for an own/bare resource, the named owner for an authorised cross-user
	 * access — so a read serves, and a write lands in, exactly the right store.
	 * Callers that locate the target separately (e.g. via {@code resolveDIDURL})
	 * may ignore the return.</p>
	 *
	 * <p>This is the LOCAL access gate. It is not the invoke path (invoking an
	 * operation is a capability, not access to its owner's namespace) and it is
	 * not for remote references (another venue's DID), which federation resolves
	 * by asking that venue.</p>
	 */
	public AString requireLocalAccess(RequestContext ctx, AString resource, AString ability) {
		if (ownedByCaller(ctx, resource)) {
			requireAuthority(ctx, resource, ability);           // own namespace → scope seam
			return (ctx != null) ? ctx.getUserDID() : null;
		}
		if (!crossUserAllows(ctx, resource, ability)) {
			convex.core.data.AVector<ACell> proofs = (ctx != null) ? ctx.getProofs() : null;
			throw new covia.exception.AuthException("Access denied: " + ability + " on " + resource
				+ " — accessing another user's resource requires " + ability + " rights"
				+ ((proofs == null || proofs.isEmpty())
					? " (no proof presented)" : " (the presented proofs do not cover it)")
				+ (isTalkAbility(ability)
					? "; the target's accepts policy does not admit this caller" : ""));
		}
		return ownerOf(resource);                               // authorised cross-user → the owner's store
	}

	/**
	 * Common point-of-action gate for a resource that may be either caller-owned,
	 * another principal on this venue, or a remote/scheme reference.
	 *
	 * <p>Absolute DID resources belonging to a principal whose user record lives
	 * here go through {@link #requireLocalAccess}; this prevents the null-scope
	 * own-resource fast path from authorising a different local user's data.
	 * Bare, scheme-qualified and genuinely remote resources use the normal
	 * capability seam.</p>
	 */
	public void requireResourceAccess(RequestContext ctx, AString resource, AString ability) {
		if (isLocalDIDResource(resource)) {
			requireLocalAccess(ctx, resource, ability);
		} else {
			requireAuthority(ctx, resource, ability);
		}
	}

	/**
	 * Point-of-action gate for reading an <b>asset's metadata</b>: either
	 * {@code crud/read} or {@code asset/read} over that resource is enough.
	 *
	 * <p>Metadata is one thing whichever way it is addressed, so which ability
	 * happens to fit the ref's <i>shape</i> must not decide whether a holder
	 * can read it. Without this, the same skill is readable or not depending on
	 * whether it was reached by path or by hash — and, worse, on whether its
	 * author wrote a {@code name} field, since that decides whether resolution
	 * needs the content too.</p>
	 *
	 * <p><b>The alternative is only ever accepted in the narrowing
	 * direction.</b> For a content-addressed ref, {@code crud/read} over that
	 * same hash also suffices — a caller holding it was granted something
	 * strictly narrower than the public {@code asset/read}. The reverse is NOT
	 * true: the public read-only scope grants {@code asset/read} <i>unscoped</i>
	 * ({@code with: ""}), because content addressing means you must already
	 * hold the hash to ask for it. Honouring that grant against a PATH would
	 * turn it into a licence to read every user's workspace metadata, so a path
	 * still requires {@code crud/read} over that path and nothing else.</p>
	 *
	 * <p>Content bytes are not covered here — {@link #resolveContent} keeps its
	 * own {@code asset/read} pin.</p>
	 *
	 * @param resource the asset ref or path whose metadata is being read
	 * @throws covia.exception.AuthException when no accepted ability is held
	 */
	public void requireMetadataRead(RequestContext ctx, AString resource) {
		if (resource == null) return;
		if (AssetAdapter.parseAssetId(resource) == null) {
			// A path: namespace-scoped read only.
			requireResourceAccess(ctx, resource, Capability.CRUD_READ);
			return;
		}
		// Content-addressed: either ability over this hash is enough.
		try {
			requireResourceAccess(ctx, resource, Abilities.ASSET_READ);
		} catch (covia.exception.AuthException denied) {
			try {
				requireResourceAccess(ctx, resource, Capability.CRUD_READ);
			} catch (covia.exception.AuthException alsoDenied) {
				throw denied;                     // report the expected ability
			}
		}
	}

	/**
	 * Whether an absolute DID resource is hosted by this venue.
	 *
	 * <p>An existing user record is authoritative. The venue's canonical DID,
	 * public principal, did:web alias, and managed users are also local before
	 * their first write creates a user record. Callers that route mutable paths
	 * use this distinction to avoid creating a shadow local user for a genuinely
	 * remote did:web target.</p>
	 */
	public boolean isLocalDIDResource(AString resource) {
		AString owner = ownerOf(resource);
		if (owner == null) return false;
		if (venueState.users().get(owner) != null) return true;

		String value = owner.toString();
		String venue = getDIDString().toString();
		if (value.equals(venue) || value.equals(venue + ":public")) return true;

		AString web = config.getWebDID();
		if (web == null) return false;
		String webValue = web.toString();
		return value.equals(webValue)
			|| value.equals(webValue + ":public")
			|| value.startsWith(webValue + ":u:");
	}

	/**
	 * Whether a principal is this venue's synthetic public user.
	 *
	 * <p>The public user is a shared authorization namespace, not an
	 * authenticated identity. Stateful facilities must not use it as an owner,
	 * even if an operator grants public invocation of their operations.</p>
	 */
	public boolean isPublicPrincipal(AString principal) {
		if (principal == null) return false;
		String value = principal.toString();
		if (value.equals(getDIDString() + ":public")) return true;
		AString web = config.getWebDID();
		return web != null && value.equals(web + ":public");
	}

	/** True when {@code resource} is the caller's own: a bare/relative/scheme path,
	 *  a resource-less check ({@code null}), or an explicit {@code did:<self>/…}. A
	 *  {@code did:<other>/…} path is another principal's. Owner = the DID prefix
	 *  before the first {@code /}, compared to the caller's namespace by equality. */
	private static boolean ownedByCaller(RequestContext ctx, AString resource) {
		if (resource == null || ctx == null) return true;
		String s = resource.toString();
		if (!s.startsWith("did:")) return true;
		int slash = s.indexOf('/');
		AString owner = Strings.create(slash < 0 ? s : s.substring(0, slash));
		return owner.equals(ctx.getUserDID());
	}

	/** The owner DID of a {@code did:<owner>/…} resource (prefix before the first
	 *  {@code /}), or null for a bare/relative resource. */
	private static AString ownerOf(AString resource) {
		if (resource == null) return null;
		String s = resource.toString();
		if (!s.startsWith("did:")) return null;
		int slash = s.indexOf('/');
		return Strings.create(slash < 0 ? s : s.substring(0, slash));
	}

	/**
	 * The single authorisation seam. Does the caller's authority cover
	 * {@code (resource, ability)}? Grants are <b>additive</b> — <em>either you
	 * have the right or you don't</em>: an inherent (unrestricted, own-namespace)
	 * grant, an agent {@code config.caps} grant, or a cross-user proof each
	 * independently authorise; nothing subtracts. Callers pass the credential (the
	 * {@link RequestContext}, wrapping an {@code Authority}) and the exact
	 * resource+ability they guard. This method is the own/scoped authority seam;
	 * a resource that may name another local principal must enter through
	 * {@link #requireResourceAccess}, which classifies ownership before reaching
	 * the {@code null}-scope fast path.
	 */
	public boolean authorityCovers(RequestContext ctx, AString resource, AString ability) {
		if (ctx == null) return false;
		// Fast path FIRST — the common case by far. A null scope is unrestricted:
		// the caller carries no capability restriction, so they are authorised over
		// their OWN namespace with no proof/public evaluation at all. Every ordinary
		// authenticated user is null-scope; their cross-user reach is gated
		// separately by the adapter (which calls crossUserAllows directly), never by
		// this fast path — so returning here is correct AND skips the string
		// building, clock read and proof walk that crossUserAllows would do.
		if (ctx.getCaps() == null) return true;
		// Restricted (agent) scope. Grants are additive: a presented cross-user
		// proof (or the public read grant) authorises independently of the caller's
		// scope; otherwise a grant in the scope must cover the resource.
		if (crossUserAllows(ctx, resource, ability)) return true;
		return ctx.grantsDenial(resource, ability) == null;
	}

	/**
	 * Throwing form of {@link #authorityCovers}: enforces the authority at a point
	 * of action, raising {@link covia.exception.AuthException} with an actionable
	 * message when the caller's authority does not cover the request.
	 */
	public void requireAuthority(RequestContext ctx, AString resource, AString ability) {
		if (authorityCovers(ctx, resource, ability)) return;
		String denial = (ctx != null && ctx.getCaps() != null) ? ctx.grantsDenial(resource, ability) : null;
		throw new covia.exception.AuthException(denial != null ? denial
			: "Capability denied: requires " + (ability != null ? ability : "(any ability)")
				+ " on " + (resource != null ? resource : "(any)")
				+ (ctx == null || ctx.getCallerDID() == null ? " (authenticate to act as an identity)" : ""));
	}

	/**
	 * Venue-administration gate. Administrative operations (user provisioning,
	 * adapter and module lifecycle) are venue-owned: a null capability scope is
	 * deliberately NOT enough. Only direct execution as the venue identity, or
	 * a presented venue-rooted delegation covering
	 * {@code <venue DID>/<resource>} for {@code ability}, authorises them.
	 *
	 * @param ctx Request context
	 * @param resource Venue-relative resource segment (e.g. {@code "users"}, {@code "adapters"})
	 * @param ability Required ability
	 * @throws covia.exception.AuthException when the caller lacks the authority
	 */
	public void requireVenueAuthority(RequestContext ctx, String resource, AString ability) {
		AString caller = (ctx != null) ? ctx.getCallerDID() : null;
		if (getDIDString().equals(caller) && ctx.getAgentId() == null) return;
		AString full = Strings.create(getDIDString() + "/" + resource);
		if (crossUserAllows(ctx, full, ability)) return;
		throw new covia.exception.AuthException("Venue administration denied: requires " + ability
			+ " on " + full + " from the venue (call as the venue or present "
			+ "a venue-issued delegation)");
	}

	/**
	 * {@link String}-literal convenience overload of
	 * {@link #requireAuthority(RequestContext, AString, AString)} — mirrors
	 * {@link RequestContext#requireCapability(String, String)}; the conversion to
	 * {@link AString} happens here, not at the call site.
	 */
	public void requireAuthority(RequestContext ctx, String resource, String ability) {
		requireAuthority(ctx,
			resource != null ? Strings.create(resource) : null,
			ability != null ? Strings.create(ability) : null);
	}

	// ========== Secret resolution ==========

	/**
	 * Resolves a secret from the calling user's secret store.
	 *
	 * <p>Accepts both {@code "/s/NAME"} and bare {@code "NAME"} formats.
	 * The store is the caller's <em>user</em> namespace ({@code ctx.getUserDID()})
	 * — only that user's own secrets are accessible. An agent sub-principal
	 * resolves its owner's secrets, which is the whole point of it running inside
	 * the owner's namespace: an agent has no store of its own, and keying this on
	 * the acting identity instead would make every agent's secrets vanish. Which
	 * secrets an agent may actually use is bounded by its capability scope, not by
	 * which store it reads.</p>
	 *
	 * @param secretRef Secret name or "/s/NAME" reference
	 * @param ctx Request context (namespace identity for access control)
	 * @return Decrypted plaintext, or null if not found or not authorised
	 */
	public String resolveSecret(String secretRef, RequestContext ctx) {
		if (secretRef == null || ctx == null) return null;
		AString callerDID = ctx.getUserDID();
		if (callerDID == null) return null;

		// Strip s/ or /s/ prefix if present
		String name = secretRef.startsWith("/s/") ? secretRef.substring(3)
				: secretRef.startsWith("s/") ? secretRef.substring(2)
				: secretRef;
		if (name.isEmpty()) return null;

		User user = venueState.users().get(callerDID);

		AString value = null;
		try {
			byte[] encKey = SecretStore.deriveKey(keyPair);
			if (user != null) {
				value = user.secrets().decrypt(Strings.create(name), encKey);
			}
			// covia#254: fall back to the PUBLIC user's store — the anonymous
			// public caller resolves these as their own, and an authenticated
			// caller is at least as privileged. RESOLUTION-ONLY: the value flows
			// into operations (secretFields-redacted in records); secret:extract
			// remains gated separately, so this never enables disclosure. The
			// caller's own secret of the same name always shadows the public one.
			if (value == null) {
				String publicDIDStr = getDIDString().toString() + ":public";
				if (!publicDIDStr.equals(callerDID.toString())) {
					User publicUser = venueState.users().get(Strings.create(publicDIDStr));
					if (publicUser != null) {
						value = publicUser.secrets().decrypt(Strings.create(name), encKey);
					}
				}
			}
		} catch (Exception e) {
			// A decrypt/key failure is NOT the same as "secret not set".
			// Collapsing both to null (the old behaviour) masked real errors as
			// absence and made #91-class identity/key misconfigurations
			// undiagnosable — a failed resolution looked identical to a missing
			// key. Surface it loudly instead. Values are never logged.
			log.warn("Secret '{}' resolution errored for caller {}: {}",
				name, callerDID, e.toString());
			throw new CoviaException("Secret resolution failed for '" + name + "'", e);
		}
		return (value != null) ? value.toString() : null;   // null == genuinely absent
	}

	/**
	 * Provisions secrets declared in the venue config into the appropriate
	 * per-user encrypted secret stores.
	 *
	 * <p>Reads {@link Config#SECRETS} ({@code {<userKey>: {<name>: <value>}}}).
	 * Top-level keys resolve as follows:</p>
	 * <ul>
	 *   <li>{@code "venue"} — the venue's own DID (see {@link #getDIDString})</li>
	 *   <li>{@code "public"} — {@code <venueDID>:public}, the anonymous user</li>
	 *   <li>Anything else — used verbatim; expected to be a literal DID string</li>
	 * </ul>
	 *
	 * <p>Each named secret overwrites any existing value under that name for
	 * that user. Names not listed in config are left untouched. Per-secret
	 * failures are logged at warn but do not fail venue startup.</p>
	 *
	 * <p>Values themselves are never logged.</p>
	 *
	 * @return number of secrets successfully provisioned (0 if none configured)
	 */
	@SuppressWarnings("unchecked")
	public int provisionConfiguredSecrets() {
		AMap<AString, ACell> secrets = config.getSecrets();
		if (secrets == null || secrets.isEmpty()) return 0;

		AString venueDID = getDIDString();
		AString publicDID = Strings.create(venueDID.toString() + ":public");
		byte[] encKey = SecretStore.deriveKey(keyPair);

		int total = 0;
		for (long i = 0; i < secrets.count(); i++) {
			java.util.Map.Entry<AString, ACell> entry = secrets.entryAt(i);
			AString userKey = entry.getKey();
			ACell rawNames = entry.getValue();
			if (!(rawNames instanceof AMap)) {
				log.warn("Configured secrets for '{}' must be a map; ignoring", userKey);
				continue;
			}

			AString targetDID;
			String userKeyStr = userKey.toString();
			if ("venue".equals(userKeyStr)) {
				targetDID = venueDID;
			} else if ("public".equals(userKeyStr)) {
				targetDID = publicDID;
			} else {
				targetDID = userKey;
			}

			User user;
			try {
				user = venueState.users().ensure(targetDID);
			} catch (Exception e) {
				log.warn("Could not resolve user '{}' for configured secrets: {}",
					userKeyStr, e.getMessage());
				continue;
			}

			AMap<AString, ACell> nameMap = (AMap<AString, ACell>) rawNames;
			int provisioned = 0;
			for (long j = 0; j < nameMap.count(); j++) {
				java.util.Map.Entry<AString, ACell> nv = nameMap.entryAt(j);
				AString name = nv.getKey();
				AString value = RT.ensureString(nv.getValue());
				if (value == null) {
					log.warn("Configured secret '{}' for user '{}' has non-string value; skipping",
						name, userKeyStr);
					continue;
				}
				try {
					user.secrets().store(name, value, encKey);
					provisioned++;
				} catch (Exception e) {
					log.warn("Failed to provision secret '{}' for user '{}': {}",
						name, userKeyStr, e.getMessage());
				}
			}
			if (provisioned > 0) {
				log.info("Provisioned {} secret(s) for {} ({})",
					provisioned, userKeyStr, targetDID);
			}
			total += provisioned;
		}
		return total;
	}

}
