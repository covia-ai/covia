package covia.venue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.auth.did.DID;
import convex.auth.did.DIDURL;
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
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.core.util.Utils;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.RootLatticeCursor;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFileSystem;
import covia.adapter.AAdapter;
import covia.adapter.AgentAdapter;
import covia.adapter.AssetAdapter;
import covia.adapter.AuthAdapter;
import covia.adapter.ConvexAdapter;
import covia.adapter.CoviaAdapter;
import covia.adapter.GridAdapter;
import covia.adapter.HTTPAdapter;
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
import covia.api.Fields;
import covia.exception.CoviaException;
import covia.exception.RemoteFetchException;
import covia.exception.WrongScopeException;
import covia.grid.AContent;
import covia.grid.Asset;
import covia.grid.Grid;
import covia.grid.Operation;
import covia.grid.Venue;
import covia.lattice.Covia;
import covia.venue.api.CoviaAPI;
import covia.venue.storage.AStorage;
import covia.venue.storage.FileStorage;
import covia.venue.storage.MemoryStorage;

public class Engine {

	public static final Logger log=LoggerFactory.getLogger(Engine.class);



	protected final Config config;

	protected AKeyPair keyPair=AKeyPair.generate();

	/**
	 * Storage instance for content associated with assets
	 */
	protected final AStorage contentStorage;

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

	/**
	 * Per-venue grid scheduler. Fires any deferred grid operation at a future
	 * wall-clock time; an agent wake is one consumer (a scheduled
	 * {@code agent:wake}). See {@code venue/docs/GRID_SCHEDULER.md}.
	 */
	private final Scheduler gridScheduler;

	/**
	 * Map of named adapters that can handle different types of operations or resources
	 */
	protected final ConcurrentHashMap<String, AAdapter> adapters = new ConcurrentHashMap<>();

	/**
	 * Persistence callback supplied at construction. Wired by VenueServer to
	 * NodeServer.persistSnapshot for the production stack; no-op for tests
	 * and in-memory engines. See {@link PersistenceHandler}.
	 */
	private final PersistenceHandler persistHandler;

	/**
	 * Background sweep daemon — periodically pulls venueState fork into the
	 * root and triggers the lattice's sync callback so the propagator
	 * persists durable. See {@code venue/docs/PERSISTENCE.md}.
	 */
	private final ScheduledExecutorService persistenceSweep;

	/** Atomic close flag — prevents double-close and serves as the sweep daemon's stop signal. */
	private final AtomicBoolean closed = new AtomicBoolean(false);

	/** How often the persistence sweep daemon runs (ms). */
	private static final long SWEEP_INTERVAL_MS = 100;

	/**
	 * How often the sweep forces a store-level fsync. The sweep runs every
	 * {@link #SWEEP_INTERVAL_MS} but only calls {@link PersistenceHandler#flush()}
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
	 * Primary constructor: Engine receives an ALatticeCursor from its caller.
	 * Engine is agnostic to persistence and replication — it just uses the cursor.
	 * Generates a new random key pair for this venue.
	 */
	public Engine(AMap<AString, ACell> config, ALatticeCursor<Index<Keyword,ACell>> cursor) throws IOException {
		this(config, cursor, AKeyPair.generate(), PersistenceHandler.NOOP);
	}

	/**
	 * Constructor with explicit key pair. Use when the venue identity must be
	 * stable across restarts (same AccountKey = same OwnerLattice slot).
	 *
	 * <p>Uses a no-op persistence handler — appropriate for in-memory venues
	 * and tests that don't need synchronous flush. For a production venue,
	 * use the four-arg constructor and pass a real {@link PersistenceHandler}
	 * (typically wired to {@code NodeServer.persistSnapshot}).</p>
	 */
	public Engine(AMap<AString, ACell> config, ALatticeCursor<Index<Keyword,ACell>> cursor, AKeyPair keyPair) throws IOException {
		this(config, cursor, keyPair, PersistenceHandler.NOOP);
	}

	/**
	 * Canonical constructor with persistence handler.
	 *
	 * <p>The handler is invoked synchronously by {@link #flush()} (and during
	 * the close-time final flush) to make the venue's lattice value durable.
	 * Pass {@link PersistenceHandler#NOOP} for in-memory venues.</p>
	 */
	public Engine(AMap<AString, ACell> config, ALatticeCursor<Index<Keyword,ACell>> cursor,
			AKeyPair keyPair, PersistenceHandler persistHandler) throws IOException {
		this.config=new Config(config);
		this.keyPair=keyPair;
		this.lattice=cursor;
		this.persistHandler = (persistHandler != null) ? persistHandler : PersistenceHandler.NOOP;
		this.lastFlushMillis = System.currentTimeMillis();
		// Set signing context so SignedCursor can sign writes through OwnerLattice
		LatticeContext ctx = LatticeContext.create(
			convex.core.data.prim.CVMLong.create(convex.core.util.Utils.getCurrentTimestamp()), this.keyPair);
		this.lattice.withContext(ctx);
		initialiseFromCursor();
		this.jobManager = new JobManager(this);
		this.gridScheduler = new Scheduler(this);
		this.contentStorage = createStorage();
		this.contentStorage.initialise();

		// The grid scheduler's authoritative state is the :schedule lattice
		// index; arm its in-memory alarm from the persisted head. GRID_SCHEDULER.md.
		gridScheduler.start();
		// Per-thread agent wakeTimes are authoritative; re-derive each agent's
		// single agent:wake event from them, healing any drift from a prior run.
		rebuildSchedulerFromLattice();

		// Ensure the venue's own user record exists in :user-data. The venue
		// is treated as a user (it has its own DID and keypair) so that the
		// /v/ virtual namespace can resolve to its /w/global/ sub-tree
		// (per OPERATIONS.md §3). Idempotent — Users.ensure creates if
		// missing, returns existing otherwise.
		this.venueState.users().ensure(getDIDString());

		// Start the persistence sweep daemon ONLY if a real persistence
		// handler is wired. In-memory engines (createTemp, NOOP handler)
		// have nothing to flush to, so the sweep is meaningless and would
		// just leak a thread per test.
		if (this.persistHandler != PersistenceHandler.NOOP) {
			this.persistenceSweep = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "covia-persistence-sweep");
				t.setDaemon(true);
				return t;
			});
			this.persistenceSweep.scheduleWithFixedDelay(
				this::sweep, SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
		} else {
			this.persistenceSweep = null;
		}
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
	private AStorage createStorage() {
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
		VenueState connected = VenueState.fromRoot(lattice, getAccountKey());
		connected.initialise(getDIDString());

		// Fork: subsequent writes accumulate locally (unsigned).
		// Engine.syncState() calls venueState.sync() to merge + sign once.
		this.venueState = connected.fork();

		this.auth = new Auth(this, venueState.authCursor());
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
	 *   <li>{@code lattice.sync()} — triggers persistence and broadcast via
	 *       NodeServer callbacks. What sync does depends on the cursor's
	 *       configuration; Engine is agnostic.</li>
	 * </ol>
	 *
	 * <p>Called by VenueServer's {@code app.after("/api/*")} handler, so all
	 * writes within a single HTTP request are batched into one sign + persist.</p>
	 */
	public void syncState() {
		refreshWriteClock();
		venueState.sync();
		lattice.sync();
	}

	/** Last write-clock refresh, epoch millis. Volatile: refreshers race benignly. */
	private volatile long lastClockRefresh = 0;

	/**
	 * Advances the lattice write clock: installs a fresh {@link LatticeContext}
	 * (current wall time + the venue signing key) on the root and venue-state
	 * cursors, so cursors derived from them stamp writes with current time.
	 *
	 * <p>Time is the harness's responsibility — convex lattice code reads the
	 * timestamp from the context it is given and deliberately never consults
	 * the system clock itself (determinism, convex#561). A context set once at
	 * boot therefore freezes the write clock: the venue-level LWW
	 * {@code :timestamp} and every {@code StampingLattice} field (namespace
	 * {@code updated}, user {@code meta.updated}) would carry engine start
	 * time forever. The engine's policy: refresh at every operation dispatch
	 * ({@code JobManager}) and at every state sync, throttled to at most once
	 * per few milliseconds. Cursors capture the context when derived, and
	 * covia derives its lattice cursors per access, so freshness propagates
	 * immediately.</p>
	 */
	public void refreshWriteClock() {
		long now = Utils.getCurrentTimestamp();
		if (now - lastClockRefresh < 2) return;   // throttle context churn
		lastClockRefresh = now;
		LatticeContext fresh = LatticeContext.create(
			convex.core.data.prim.CVMLong.create(now), this.keyPair);
		lattice.withContext(fresh);
		if (venueState != null) venueState.cursor().withContext(fresh);
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
		if (closed.get()) return;
		try {
			venueState.sync();   // pull fork writes into the root
			lattice.sync();      // fire NodeServer.onSync → propagator
			// Periodic durability barrier. The propagator's setRootData
			// writes new root data to the mmap'd Etch but does not fsync;
			// the OS may take minutes to write dirty pages out on its own.
			// Forcing fsync every FLUSH_INTERVAL_MS bounds the unclean-
			// shutdown data-loss window. Cheap on idle venues — fsync of
			// an unchanged file is a no-op below the kernel.
			if (System.currentTimeMillis() - lastFlushMillis >= FLUSH_INTERVAL_MS) {
				persistHandler.flush();
				lastFlushMillis = System.currentTimeMillis();
			}
		} catch (Exception e) {
			log.warn("Persistence sweep failed", e);
		}
	}

	/**
	 * Synchronously syncs venueState into the root and persists the current
	 * value through the propagator on the caller's thread. Returns when the
	 * write set is on disk.
	 *
	 * <p>Bypasses the propagator's background queue by calling the
	 * {@link PersistenceHandler} (typically wired to
	 * {@code NodeServer.persistSnapshot}). This is the correct primitive for
	 * "make this write durable before I return" — there is no
	 * "wait for the background drain queue to flush" API on a running
	 * propagator (this is a known upstream gap).</p>
	 *
	 * <p>Use sparingly — most writes don't need this. Default eventual
	 * durability via the background sweep is fine for in-flight job state,
	 * conversation history, etc. Use {@code flush()} for: job completion,
	 * audit records, secret rotation, agent TERMINATED, OAuth login.</p>
	 */
	public void flush() {
		venueState.sync();                   // pull fork into root
		persistHandler.persist(lattice.get()); // synchronous persist (mmap)
		try {
			persistHandler.flush();            // fsync — durable on disk
		} catch (java.io.IOException e) {
			throw new RuntimeException("flush failed", e);
		}
		lastFlushMillis = System.currentTimeMillis();
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
	public void close() {
		if (!closed.compareAndSet(false, true)) return; // already closed

		// Stop the scheduler first so no new fires land during shutdown. In-
		// flight fires on virtual threads keep running; their invocations
		// tolerate a closing venue because the run loops themselves gate on
		// agent state in the lattice.
		gridScheduler.shutdown();

		// Stop accepting new sweep tasks; wait briefly for in-flight sweep to finish.
		// May be null for in-memory engines that have no persistence handler.
		if (persistenceSweep != null) {
			persistenceSweep.shutdown();
			try {
				if (!persistenceSweep.awaitTermination(2, TimeUnit.SECONDS)) {
					persistenceSweep.shutdownNow();
				}
			} catch (InterruptedException e) {
				persistenceSweep.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}

		// Final synchronous flush — guarantees the venueState fork's writes
		// are on disk before VenueServer's nodeServer.close() reads from
		// the root cursor for its graceful drain.
		try {
			venueState.sync();
			persistHandler.persist(lattice.get());
			persistHandler.flush();
		} catch (Exception e) {
			log.warn("Final persistence flush failed during close", e);
		}
	}

	public static void addDemoAssets(Engine venue) {
		venue.registerAdapter(new TestAdapter());
		venue.registerAdapter(new HTTPAdapter());
		venue.registerAdapter(new JVMAdapter());
		venue.registerAdapter(new FileAdapter());
		venue.registerAdapter(new SchemaAdapter());
		venue.registerAdapter(new JSONAdapter());
		venue.registerAdapter(new Orchestrator());
		venue.registerAdapter(new MCPAdapter());
		venue.registerAdapter(new LangChainAdapter());
		venue.registerAdapter(new CoviaAdapter());
		venue.registerAdapter(new covia.adapter.UserMemoryAdapter());
		venue.registerAdapter(new AssetAdapter());
		venue.registerAdapter(new GridAdapter());
		venue.registerAdapter(new covia.adapter.A2AAdapter());
		venue.registerAdapter(new ConvexAdapter());
		venue.registerAdapter(new AgentAdapter());
		venue.registerAdapter(new SecretAdapter());
		venue.registerAdapter(new covia.adapter.SchedulerAdapter());
		venue.registerAdapter(new AuthAdapter());
		venue.registerAdapter(new UCANAdapter());
		venue.registerAdapter(new DLFSAdapter());
		venue.registerAdapter(new VaultAdapter());
		venue.registerAdapter(new LLMAgentAdapter());
		venue.registerAdapter(new covia.adapter.agent.GoalTreeAdapter());

		// Flush pending /v/ops/ entries from all adapters. Per OPERATIONS.md
		// §7, installAsset(catalogPath, resourcePath) defers the catalog
		// write until after all adapters are registered, because covia:write
		// (which the materialiser uses) requires CoviaAdapter to be present.
		venue.materialiseVOps();

		// Materialise /v/info/ from venue config + registered adapters.
		// Per OPERATIONS.md §7, this is re-run on every startup. Idempotent
		// modulo /v/info/started which legitimately reflects the current boot.
		venue.materialiseVenueInfo();

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
	 * or {@code v/test/ops/echo}) as inline asset metadata via
	 * {@code covia:write} with internal context.
	 *
	 * <p>Called once at startup after all adapters are registered (so that
	 * {@code CoviaAdapter} — which provides {@code covia:write} — is
	 * available). Idempotent on re-run.</p>
	 */
	public void materialiseVOps() {
		RequestContext ctx = venueContext();
		// Bootstrap: covia:write is itself a v/ops/ entry that doesn't yet
		// exist when materialisation begins, so we can't reference it by
		// catalog path. Look up its hash from the CoviaAdapter's pending
		// entries once and dispatch by bare hex hash for every write.
		String writeRef = lookupCoviaWriteRef();
		if (writeRef == null) {
			throw new IllegalStateException(
				"Cannot materialise /v/ops — covia:write hash not available");
		}
		for (var adapter : adapters.values()) {
			if (adapter == null) continue;
			for (var entry : adapter.pendingCatalogEntries.entrySet()) {
				String fullPath = entry.getKey();
				Hash hash = entry.getValue();
				try {
					AString metaString = adapter.getInstalledAssets().get(hash);
					if (metaString == null) continue;
					ACell meta = convex.core.util.JSON.parse(metaString);
					jobManager.invokeOperation(writeRef,
						Maps.of(
							Fields.PATH, Strings.create(fullPath),
							Fields.VALUE, meta),
						ctx).awaitResult(5000);
				} catch (Exception e) {
					// Fail loudly — a venue that cannot materialise its own ops
					// catalog is broken; swallowing this masks the real cause as a
					// downstream "Cannot resolve operation" cascade.
					throw new RuntimeException(
						"Failed to materialise " + adapter.getName() + " at /" + fullPath, e);
				}
			}
		}
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
	 * Look up the covia:write asset hash from the CoviaAdapter's pending
	 * catalog entries. Used by the venue startup materialiser to invoke
	 * writes before {@code v/ops/covia/write} is itself materialised.
	 *
	 * @return the bare hex hash of covia:write, or null if unavailable
	 */
	private String lookupCoviaWriteRef() {
		AAdapter coviaAdapter = adapters.get("covia");
		if (coviaAdapter == null) return null;
		Hash hash = coviaAdapter.pendingCatalogEntries.get("v/ops/covia/write");
		return (hash != null) ? hash.toHexString() : null;
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
	 * <p>Writes go through the {@code covia:write} op with
	 * {@link RequestContext#INTERNAL}, which the {@code v/} resolver
	 * recognises as the venue identity.</p>
	 */
	public void materialiseVenueInfo() {
		try {
			RequestContext ctx = venueContext();

			// /v/info/name
			AString name = config.getName();
			if (name != null) writeVenueInfo("v/info/name", name, ctx);

			// /v/info/did
			AString did = getDIDString();
			if (did != null) writeVenueInfo("v/info/did", did, ctx);

			// /v/info/version
			String version = jarVersion();
			if (version != null) writeVenueInfo("v/info/version", Strings.create(version), ctx);

			// /v/info/started — current boot time
			writeVenueInfo("v/info/started", CVMLong.create(System.currentTimeMillis()), ctx);

			// /v/info/protocols — list of enabled protocol handlers (left
			// as a TODO until VenueServer wires it; for now write what the
			// engine knows about its own surface)
			AVector<ACell> protocols = Vectors.of(
				(ACell) Strings.create("rest"),
				(ACell) Strings.create("mcp"),
				(ACell) Strings.create("a2a"));
			writeVenueInfo("v/info/protocols", protocols, ctx);

			// /v/info/adapters/<name> — per-adapter summary. The "operations"
			// field is a vector of full catalog paths — each entry is directly
			// invocable via grid:run. Agents can count with length(operations).
			for (String adapterName : adapters.keySet()) {
				AAdapter adapter = adapters.get(adapterName);
				if (adapter == null) continue;
				AVector<ACell> ops = Vectors.empty();
				for (String catalogPath : adapter.getOperationPaths()) {
					ops = ops.conj(Strings.create(catalogPath));
				}
				AMap<AString, ACell> summary = Maps.of(
					Fields.NAME, Strings.create(adapter.getName()),
					Fields.DESCRIPTION, Strings.create(adapter.getDescription()),
					Strings.create("operations"), ops);
				writeVenueInfo("v/info/adapters/" + adapterName, summary, ctx);
			}
		} catch (Exception e) {
			log.warn("Failed to materialise /v/info/", e);
		}
	}

	private void writeVenueInfo(String path, ACell value, RequestContext ctx) {
		// Same bootstrap rule as materialiseVOps: invoke covia:write by hash
		// because v/ops/covia/write may not be materialised yet (and even
		// after it is, going through it adds no value here).
		String writeRef = lookupCoviaWriteRef();
		if (writeRef == null) return;
		try {
			jobManager.invokeOperation(writeRef,
				Maps.of(Fields.PATH, Strings.create(path), Fields.VALUE, value),
				ctx).awaitResult(5000);
		} catch (Exception e) {
			log.warn("Failed to write {}: {}", path, e.getMessage());
		}
	}

	/**
	 * Best-effort jar version lookup. Returns null if the version can't be
	 * determined (e.g. running from IDE classes rather than a packaged jar).
	 */
	private static String jarVersion() {
		Package pkg = Engine.class.getPackage();
		if (pkg == null) return null;
		String v = pkg.getImplementationVersion();
		return (v != null) ? v : "dev";
	}

	/**
	 * Register an adapter
	 * @param adapter The adapter instance to register
	 */
	public synchronized void registerAdapter(AAdapter adapter) {
		String name = adapter.getName();
		if (adapters.containsKey(name) ) {
			throw new IllegalStateException("Trying to install same adapter twice: "+name);
		}
		adapter.install(this);
		adapters.put(name, adapter);
		log.info("Registered adapter: {} ({} primitives)", name,
			adapter.pendingCatalogEntries.size());
	}

	/**
	 * Get an adapter by name
	 * @param name The name of the adapter to retrieve
	 * @return The adapter instance, or null if not found
	 */
	public AAdapter getAdapter(String name) {
		return adapters.get(name);
	}

	/**
	 * Check if an adapter with the given name exists
	 * @param name The name of the adapter to check
	 * @return true if the adapter exists, false otherwise
	 */
	public boolean hasAdapter(String name) {
		return adapters.containsKey(name);
	}

	/**
	 * Remove an adapter by name
	 * @param name The name of the adapter to remove
	 * @return The removed adapter, or null if not found
	 */
	public AAdapter removeAdapter(String name) {
		AAdapter removed = adapters.remove(name);
		if (removed != null) {
			log.info("Removed adapter: {}", name);
		}
		return removed;
	}

	/**
	 * Get all adapter names
	 * @return Set of all registered adapter names
	 */
	public java.util.Set<String> getAdapterNames() {
		return adapters.keySet();
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
		AString callerDID = ctx.getCallerDID();
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
			RootLatticeCursor<Index<Keyword,ACell>> cursor = Cursors.createLattice(Covia.ROOT);
			return new Engine(config, cursor);
		} catch (IOException e) {
			throw new Error(e);
		}
	}

	/**
	 * Get an asset record by Hash — checks user namespace first, then venue.
	 * This is the single resolution point for all asset lookups by hash.
	 */
	public AVector<?> getAssetRecord(Hash assetID, RequestContext ctx) {
		// Check user's /a/ first
		if (ctx != null && ctx.getCallerDID() != null) {
			User user = getVenueState().users().get(ctx.getCallerDID());
			if (user != null) {
				AVector<?> arec = user.assets().getRecord(assetID);
				if (arec != null) return arec;
			}
		}
		// Fall back to venue-level assets
		return venueState.assets().getRecord(assetID);
	}

	/**
	 * Get an asset record by Hash — checks a specific user, then venue.
	 */
	public AVector<?> getAssetRecord(Hash assetID, AString userDID) {
		if (userDID != null) {
			User user = getVenueState().users().get(userDID);
			if (user != null) {
				AVector<?> arec = user.assets().getRecord(assetID);
				if (arec != null) return arec;
			}
		}
		return venueState.assets().getRecord(assetID);
	}

	/**
	 * Get an Asset by its Hash ID — checks user namespace first, then venue.
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
	private static final AString NS_OPS   = Strings.intern("/o/");
	/** Namespace prefix for DID URLs */
	private static final AString NS_DID   = Strings.intern("did:");
	/** Optional leading-slash sugar, stripped before virtual/workspace resolution */
	private static final AString SLASH    = Strings.intern("/");

	/** Strips a single optional leading slash: {@code "/w/x"} → {@code "w/x"}; {@code "w/x"} unchanged. */
	private static AString stripLeadingSlash(AString ref) {
		return ref.startsWith(SLASH) ? ref.slice(1) : ref;
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
		AString assetRef = stripLeadingSlash(ref);
		if (assetRef.startsWith(NS_ASSET)) {
			Hash ah = Hash.parse(assetRef.slice(2));
			if (ah == null) return null;
			Asset asset = getAsset(ah, ctx);
			return (asset != null) ? asset.meta() : null;
		}

		// 3. /o/<name> → caller's own /o/, return literal value
		if (ref.startsWith(NS_OPS)) {
			return readUserOpValue(ref.slice(3), ctx);
		}

		// 4. DID URL — local cases only; remote is handled by resolveAsset
		if (ref.startsWith(NS_DID)) {
			Asset local = resolveLocalDIDURL(ref);
			return (local != null) ? local.meta() : null;
		}

		// Steps 5–6 cover the virtual and workspace namespaces, where a
		// leading slash is optional sugar (it is already accepted for /a/ and
		// /o/ above). Normalise it away once so "/w/notes" resolves exactly
		// like "w/notes" and "/v/ops/x" like "v/ops/x".
		AString navRef = stripLeadingSlash(ref);

		// 5. Virtual namespace prefix (n/, v/, ...) — delegate to the
		// registered resolver via CoviaAdapter. Handles cursor-based
		// virtual namespaces uniformly. (t/ — job-scoped temp — is not
		// handled here; covia:read has its own t/ branch.)
		ACell virtualValue = resolveVirtualNamespace(navRef, ctx);
		if (virtualValue != null) return virtualValue;

		// 6. Workspace path (w/, g/, o/, j/, s/, h/) → caller's lattice
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
			Asset asset = resolveDIDURL(ref);
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
	 * Reads the literal value at the caller's {@code /o/<name>} namespace.
	 * Returns whatever's stored — a map, string, vector, or null if absent.
	 * No interpretation, no asset wrapping, no reference chasing.
	 */
	private ACell readUserOpValue(AString name, RequestContext ctx) {
		if (ctx == null || ctx.getCallerDID() == null) return null;
		Users users = venueState.users();
		User user = users.get(ctx.getCallerDID());
		if (user == null) return null;
		// Pre-split keys: the name is a single literal /o/ key (it may
		// contain slashes). readPath handles the namespace wrapper.
		return covia.adapter.CoviaAdapter.readPath(user.cursor(),
			new ACell[] { Strings.create("o"), name });
	}

	/**
	 * Reads the literal value at a workspace path through the caller's
	 * lattice cursor. Returns whatever's there, with no interpretation.
	 */
	private ACell readWorkspacePathValue(AString ref, RequestContext ctx) {
		if (ctx == null || ctx.getCallerDID() == null) return null;
		Users users = venueState.users();
		User user = users.get(ctx.getCallerDID());
		if (user == null) return null;

		ACell[] pathKeys = covia.adapter.CoviaAdapter.parseStringPath(ref.toString());
		if (pathKeys.length == 0) return null;

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
	private Asset resolveLocalDIDURL(AString ref) {
		AssetRef r = parseAssetRef(ref);
		// Named refs have no local copy (bindings are publisher-scoped).
		return (r == null || r.hash() == null) ? null : lookupLocalAsset(r);
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

	/**
	 * Looks up a parsed asset reference in local stores: the named
	 * principal's asset records first, then the venue store. Content
	 * addressing makes any local copy authoritative — the hash alone
	 * identifies the value, so WHO the reference names cannot change
	 * what is returned, only where we look first.
	 */
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
	private Asset resolveDIDURL(AString ref) {
		AssetRef r = parseAssetRef(ref);
		if (r == null) return null;
		if (r.name() != null) {
			// Named refs are publisher-scoped bindings — OUR v/ops/x is not
			// THEIR v/ops/x, so there is no local-copy shortcut.
			return "web".equals(r.method()) ? fetchRemoteNamedAsset(r.didString(), r.name()) : null;
		}
		Asset local = lookupLocalAsset(r);
		if (local != null) return local;
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

			// Venue-bound handle for the content stream itself.
			Asset handle = Asset.create(id, def.getMetadata());
			handle.setVenue(remote);
			AContent content = handle.getContent();
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
			Asset fetched = remote.getAsset(id);
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

		// 2. Namespace prefix
		if (ref.startsWith(NS_ASSET)) {
			return Hash.parse(ref.slice(3));
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
		if (meta==null) return null;
		AMap<AString,ACell> content=RT.ensureMap(meta.get(Fields.CONTENT));
		if (content==null) return null;
		Hash contentHash=Hash.parse(RT.ensureString(content.get(Fields.SHA256)));
		if (contentHash==null) {
			throw new IllegalArgumentException("Metadata does not have valid content hash");
		}
		AContent c = contentStorage.getContent(contentHash);
		if (c==null) return null;
		return c.getInputStream();
	}

	/**
	 * Gets a content stream for the given asset
	 * @param meta Metadata of asset
	 * @return Content, or null if not available / does not exist
	 */
	public AContent getContent(AMap<AString,ACell> meta) throws IOException {
		if (meta==null) return null;
		AMap<AString,ACell> content=RT.ensureMap(meta.get(Fields.CONTENT));
		if (content==null) return null;
		Hash contentHash=Hash.parse(RT.ensureString(content.get(Fields.SHA256)));
		if (contentHash==null) {
			throw new IllegalArgumentException("Metadata does not have valid content hash");
		}
		return contentStorage.getContent(contentHash);
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
		if (ref == null) return null;

		// Alternative storage mechanisms (e.g. DLFS drive paths).
		covia.venue.storage.ContentProvider.Resolved provided = providerContent(ref, ctx);
		if (provided != null) return provided;

		// Content-addressed store: locate the CAS record. Hash-form refs name it
		// directly; other refs resolve first (a lattice slot may hold a reference
		// string — followed one hop — asset metadata, or a raw blob).
		AVector<?> record = null;
		Hash hash = covia.adapter.AssetAdapter.parseAssetId(ref);
		if (hash != null) {
			record = getAssetRecord(hash, ctx);
		} else {
			ACell value = resolvePath(ref, ctx);
			if (value instanceof AString s) {
				Hash hop = covia.adapter.AssetAdapter.parseAssetId(s);
				if (hop != null) record = getAssetRecord(hop, ctx);
			} else if (value instanceof AMap) {
				record = getAssetRecord(((AMap<?, ?>) value).getHash(), ctx);
			} else if (value instanceof convex.core.data.ABlob b) {
				return new covia.venue.storage.ContentProvider.Resolved(
					covia.grid.impl.BlobContent.of(b), null);
			}
		}
		if (record == null) return null;
		ACell meta = record.get(AssetStore.POS_META);
		AString ct = RT.ensureString(RT.getIn(meta, Fields.CONTENT_TYPE));
		ACell content = record.get(AssetStore.POS_CONTENT);
		if (content instanceof convex.core.data.ABlob b) {
			return new covia.venue.storage.ContentProvider.Resolved(
				covia.grid.impl.BlobContent.of(b), (ct != null) ? ct.toString() : null);
		}

		// Metadata-declared alternative storage: content.dlfs names a drive path.
		// With content.sha256 present the asset is PINNED — fetched bytes are
		// verified against the declared hash (drift after minting fails loudly,
		// never serves silently-different bytes). Without sha256 the asset is a
		// LIVE binding: content is whatever the file currently holds, served
		// lazily, no verification possible (the asset identity covers the
		// pointer, not the bytes).
		AString altPath = RT.ensureString(RT.getIn(meta, Fields.CONTENT, "dlfs"));
		if (altPath != null) {
			covia.venue.storage.ContentProvider.Resolved r = providerContent(altPath, ctx);
			if (r == null) {
				throw new IllegalArgumentException(
					"Asset declares content at '" + altPath + "' but no storage mechanism resolves it");
			}
			String type = (ct != null) ? ct.toString() : r.contentType();
			AString declaredSha = RT.ensureString(RT.getIn(meta, Fields.CONTENT, Fields.SHA256));
			if (declaredSha != null) {
				convex.core.data.ABlob bytes = r.content().getBlob();
				Hash actual = Hashing.sha256(bytes.getBytes());
				if (!actual.toHexString().equals(declaredSha.toString())) {
					throw new IllegalStateException(
						"Content hash mismatch for asset content at '" + altPath
						+ "': stored file has changed since the asset was minted (expected "
						+ declaredSha + ", got " + actual.toHexString() + ") — re-mint the asset");
				}
				return new covia.venue.storage.ContentProvider.Resolved(
					covia.grid.impl.BlobContent.of(bytes), type);
			}
			return new covia.venue.storage.ContentProvider.Resolved(r.content(), type);
		}
		return null;
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
		// jarVersion() reads the (shaded) jar's Implementation-Version and falls
		// back to "dev" when running from classes — never null. See #139.
		status=status.assoc(Fields.VERSION, Strings.create(jarVersion()));

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
	 * Builds the venue DID document served at {@code /.well-known/did.json}.
	 *
	 * <p>The document {@code id} must equal the DID a resolver asked for (DID
	 * Core), so when the venue has a public hostname configured the document is
	 * presented under its <b>did:web alias</b> ({@code did:web:<hostname>}) with
	 * the canonical did:key in {@code alsoKnownAs} — making strict did:web
	 * resolution work (covia#167). The alias is a derived, per-request view and
	 * is discovery only: the venue's identity remains its did:key (the same
	 * ed25519 key material verifies in both presentations), and nothing durable
	 * references the did:web form. Without a public hostname the document is
	 * served under the did:key directly, unchanged.</p>
	 *
	 * @param endpoint Service endpoint URL for the CoviaGrid service entry
	 * @return DID document map
	 */
	public AMap<AString, ACell> getDIDDocument(String endpoint) {
		AString canonicalDID=getDIDString();

		// Presentation identity: the did:web alias when a public hostname is
		// configured (and isn't already the canonical DID), else the did:key.
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

		// Bind the alias to the canonical identity: consumers resolving
		// did:web re-bind to the did:key for anything they store or pin.
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
				 "ops",opCount
				);
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
	 * Re-derives every agent's single {@code agent:wake} event from the
	 * authoritative per-thread {@code wakeTime} fields in the lattice. Called
	 * once during Engine construction: the {@code :schedule} index already
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

	// ========== Secret resolution ==========

	/**
	 * Resolves a secret from the caller's secret store.
	 *
	 * <p>Accepts both {@code "/s/NAME"} and bare {@code "NAME"} formats.
	 * The caller's identity is taken from the {@link RequestContext} for
	 * access control — only the caller's own secrets are accessible.</p>
	 *
	 * @param secretRef Secret name or "/s/NAME" reference
	 * @param ctx Request context (caller identity for access control)
	 * @return Decrypted plaintext, or null if not found or not authorised
	 */
	public String resolveSecret(String secretRef, RequestContext ctx) {
		if (secretRef == null || ctx == null) return null;
		AString callerDID = ctx.getCallerDID();
		if (callerDID == null) return null;

		// Strip s/ or /s/ prefix if present
		String name = secretRef.startsWith("/s/") ? secretRef.substring(3)
				: secretRef.startsWith("s/") ? secretRef.substring(2)
				: secretRef;
		if (name.isEmpty()) return null;

		User user = venueState.users().get(callerDID);
		if (user == null) return null;   // identity has no store — genuinely absent

		AString value;
		try {
			byte[] encKey = SecretStore.deriveKey(keyPair);
			value = user.secrets().decrypt(Strings.create(name), encKey);
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
