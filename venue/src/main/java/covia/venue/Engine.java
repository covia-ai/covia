package covia.venue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
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
import convex.core.data.Keyword;
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
import covia.adapter.ConvexAdapter;
import covia.adapter.CoviaAdapter;
import covia.adapter.GridAdapter;
import covia.adapter.HTTPAdapter;
import covia.adapter.JSONAdapter;
import covia.adapter.JVMAdapter;
import covia.adapter.SchemaAdapter;
import covia.adapter.LangChainAdapter;
import covia.adapter.LLMAgentAdapter;
import covia.adapter.MCPAdapter;
import covia.adapter.Orchestrator;
import covia.adapter.SecretAdapter;
import covia.adapter.DLFSAdapter;
import covia.adapter.VaultAdapter;
import covia.adapter.UCANAdapter;
import covia.adapter.TestAdapter;
import covia.api.Fields;
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
	 * Map of named adapters that can handle different types of operations or resources
	 */
	protected final HashMap<String, AAdapter> adapters = new HashMap<>();

	/**
	 * Registry of named operations mapping operation name (e.g. "test:echo") to canonical asset Hash.
	 * Populated during adapter registration from each adapter's operation names.
	 */
	@SuppressWarnings("unchecked")
	protected Index<AString, Hash> operations = (Index<AString, Hash>) Index.EMPTY;

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
		// Set signing context so SignedCursor can sign writes through OwnerLattice
		LatticeContext ctx = LatticeContext.create(null, this.keyPair);
		this.lattice.withContext(ctx);
		initialiseFromCursor();
		this.jobManager = new JobManager(this);
		this.contentStorage = createStorage();
		this.contentStorage.initialise();

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
		venueState.sync();
		lattice.sync();
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
		persistHandler.persist(lattice.get()); // synchronous persist
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
		} catch (Exception e) {
			log.warn("Final persistence flush failed during close", e);
		}
	}

	public static void addDemoAssets(Engine venue) {
		venue.registerAdapter(new TestAdapter());
		venue.registerAdapter(new HTTPAdapter());
		venue.registerAdapter(new JVMAdapter());
		venue.registerAdapter(new SchemaAdapter());
		venue.registerAdapter(new JSONAdapter());
		venue.registerAdapter(new Orchestrator());
		venue.registerAdapter(new MCPAdapter());
		venue.registerAdapter(new LangChainAdapter());
		venue.registerAdapter(new CoviaAdapter());
		venue.registerAdapter(new AssetAdapter());
		venue.registerAdapter(new GridAdapter());
		venue.registerAdapter(new ConvexAdapter());
		venue.registerAdapter(new AgentAdapter());
		venue.registerAdapter(new SecretAdapter());
		venue.registerAdapter(new UCANAdapter());
		venue.registerAdapter(new DLFSAdapter());
		venue.registerAdapter(new VaultAdapter());
		venue.registerAdapter(new LLMAgentAdapter());
		venue.registerAdapter(new covia.adapter.agent.GoalTreeAdapter());
	}

	/**
	 * Register an adapter
	 * @param adapter The adapter instance to register
	 */
	public void registerAdapter(AAdapter adapter) {
		String name = adapter.getName();
		if (adapters.containsKey(name) ) {
			throw new IllegalStateException("Trying to install same adapter twice: "+name);
		}
		adapter.install(this);
		adapters.put(name, adapter);

		// Collect operation names from adapter into engine-level registry
		Index<AString, Hash> adapterOps = adapter.getOperationNames();
		long n = adapterOps.count();
		for (long i = 0; i < n; i++) {
			var entry = adapterOps.entryAt(i);
			operations = operations.assoc(entry.getKey(), entry.getValue());
		}
		log.info("Registered adapter: {} ({} operations)", name, n);
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

	/**
	 * Get the operation registry mapping operation names to asset Hashes.
	 * @return Index of operation name → asset Hash
	 */
	public Index<AString, Hash> getOperationRegistry() {
		return operations;
	}

	/**
	 * Resolve an operation name to its canonical asset Hash.
	 * @param name Operation name (e.g. "test:echo")
	 * @return Asset Hash, or null if not found
	 */
	public Hash resolveOperation(String name) {
		return resolveOperation(Strings.create(name));
	}

	public Hash resolveOperation(AString name) {
		return operations.get(name);
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
		// instanceof — RT.ensureMap(null) returns an empty map, which would
		// violate the "null if not valid metadata" contract.
		ACell meta = arec.get(AssetStore.POS_META);
		return (meta instanceof AMap) ? (AMap<AString, ACell>) meta : null;
	}

	// ========== Reference resolution ==========

	/** Namespace prefix for immutable content-addressed assets */
	private static final AString NS_ASSET = Strings.intern("/a/");
	private static final AString NS_OPS   = Strings.intern("/o/");
	/** Namespace prefix for DID URLs */
	private static final AString NS_DID   = Strings.intern("did:");

	/**
	 * Resolves a reference to an Asset. Supports (in priority order):
	 * <ul>
	 *   <li>Bare hex hash — shorthand for {@code /a/<hash>}</li>
	 *   <li>{@code /a/<hash>} — explicit asset namespace</li>
	 *   <li>{@code /o/<name>} — caller's user-scoped operation namespace</li>
	 *   <li>{@code did:.../<namespace>/<path>} — local or remote DID URL</li>
	 *   <li>Operation name (e.g. "test:echo") — venue operation registry</li>
	 * </ul>
	 *
	 * @param ref Reference string
	 * @param ctx Request context (caller identity for /o/ namespace scoping)
	 * @return Resolved Asset, or null if not resolvable
	 */
	public Asset resolveAsset(AString ref, RequestContext ctx) {
		return resolveAsset(ref, ctx, 0);
	}

	/** Maximum recursion depth for {@link #resolveAsset} when dereferencing
	 *  workspace path values that themselves contain references. Prevents
	 *  infinite loops if a path stores a reference back to itself. */
	private static final int MAX_RESOLVE_DEPTH = 8;

	private Asset resolveAsset(AString ref, RequestContext ctx, int depth) {
		if (ref == null) return null;
		if (depth >= MAX_RESOLVE_DEPTH) return null;

		// 1. Bare hex hash → check user /a/ then venue /a/
		Hash h = Hash.parse(ref);
		if (h != null) return getAsset(h, ctx);

		// 2. Namespace prefix dispatch
		if (ref.startsWith(NS_ASSET)) {
			Hash ah = Hash.parse(ref.slice(3));
			if (ah != null) return getAsset(ah, ctx);
			return null;
		}

		// 3. /o/<name> — caller's user-scoped operation namespace
		if (ref.startsWith(NS_OPS)) {
			return resolveUserOp(ref.slice(3), ctx);
		}

		// 4. DID URL
		if (ref.startsWith(NS_DID)) {
			return resolveDIDURL(ref);
		}

		// 5. Workspace path — dereference through caller's lattice and recurse.
		// Recognises any of the venue namespace prefixes (w/, g/, o/, j/, s/,
		// n/, h/) WITHOUT a leading slash. The value at the path can be:
		//   - a string → treated as a reference and resolved recursively
		//   - a map with an "operation" field → treated as inline metadata
		// This is the primary mechanism for using human-friendly paths like
		// "w/config/ap-pipeline" as operation references instead of opaque
		// asset hashes. Mirrors ContextLoader.isNamespacePath.
		if (isUserNamespacePath(ref)) {
			Asset deref = resolveWorkspaceRef(ref, ctx, depth);
			if (deref != null) return deref;
			// Fall through to step 6 (operation registry) — a path-shaped
			// alias is unlikely but technically possible.
		}

		// 6. Operation name registry (venue-level named operations)
		Hash opHash = operations.get(ref);
		if (opHash != null) return getAsset(opHash);

		return null;
	}

	/**
	 * Returns true if {@code ref} starts with a known user-namespace prefix
	 * (w/, g/, o/, j/, s/, n/, h/) without a leading slash. Mirrors
	 * {@link covia.adapter.ContextLoader} so the two resolvers stay aligned.
	 */
	static boolean isUserNamespacePath(AString ref) {
		if (ref == null || ref.count() < 2) return false;
		String s = ref.toString();
		return s.startsWith("w/") || s.startsWith("g/") || s.startsWith("o/")
			|| s.startsWith("j/") || s.startsWith("s/") || s.startsWith("h/")
			|| s.startsWith("n/");
	}

	/**
	 * Dereferences a user-namespace path and resolves the resulting value as
	 * an Asset. Returns null if the path is missing, the user has no lattice
	 * state, or the value is not a recognisable reference.
	 */
	@SuppressWarnings("unchecked")
	private Asset resolveWorkspaceRef(AString ref, RequestContext ctx, int depth) {
		if (ctx == null || ctx.getCallerDID() == null) return null;
		try {
			Users users = venueState.users();
			User user = users.get(ctx.getCallerDID());
			if (user == null) return null;

			ACell[] pathKeys = covia.adapter.CoviaAdapter.parseStringPath(ref.toString());
			if (pathKeys.length == 0) return null;

			ACell value = covia.adapter.CoviaAdapter.readPath(user.cursor(), pathKeys);
			if (value == null) return null;

			// Inline operation metadata: a map with an "operation" field
			if (value instanceof AMap) {
				AMap<AString, ACell> map = (AMap<AString, ACell>) value;
				if (map.get(Strings.create("operation")) != null) {
					return Asset.fromMeta(map);
				}
				return null;
			}

			// String reference: recurse with the value as the new ref
			AString strRef = RT.ensureString(value);
			if (strRef != null) {
				return resolveAsset(strRef, ctx, depth + 1);
			}

			return null;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Resolves a reference to an Asset (internal use, no caller identity).
	 *
	 * @param ref Reference string
	 * @return Resolved Asset, or null if not resolvable
	 */
	public Asset resolveAsset(AString ref) {
		return resolveAsset(ref, RequestContext.INTERNAL);
	}

	/**
	 * Resolves a hash string within the /a/ namespace.
	 */
	private Asset resolveAssetRef(AString hashStr) {
		Hash h = Hash.parse(hashStr);
		return (h != null) ? getAsset(h) : null;
	}

	/**
	 * Resolves an operation from the caller's {@code /o/} namespace.
	 *
	 * <p>The value at the path can be:</p>
	 * <ul>
	 *   <li>A map with an {@code operation} field — treated as inline operation metadata</li>
	 *   <li>A string — treated as a reference and resolved recursively (e.g. an {@code /a/} hash)</li>
	 * </ul>
	 */
	private Asset resolveUserOp(AString name, RequestContext ctx) {
		if (ctx == null || ctx.getCallerDID() == null) return null;

		Users users = venueState.users();
		User user = users.get(ctx.getCallerDID());
		if (user == null) return null;

		// Read from the user's /o/ namespace
		ACell value = RT.getIn(user.get(), "o", name);
		if (value == null) return null;

		// If it's a map with an "operation" field, treat as inline metadata
		if (value instanceof AMap) {
			ACell opField = RT.getIn(value, "operation");
			if (opField != null) {
				return Asset.fromMeta((AMap<AString, ACell>) value);
			}
		}

		// If it's a string, treat as a reference and resolve recursively
		AString strRef = RT.ensureString(value);
		if (strRef != null) {
			return resolveAsset(strRef, ctx);
		}

		return null;
	}

	/**
	 * Resolves a DID URL reference using {@link DIDURL} parsing.
	 * Dispatches on the path namespace prefix. Local DID → local lookup,
	 * remote DID → remote venue reference.
	 *
	 * <p><b>Local DID handling.</b> The venue's own DID may have sub-id
	 * variants — e.g. {@code did:key:VENUE:public} for the public/anonymous
	 * user namespace. We treat any DID URL whose method matches the venue's
	 * own DID method AND whose id starts with the venue's id as local. The
	 * id-prefix match also catches user DIDs that have stored data, which we
	 * resolve via the per-user asset record.</p>
	 *
	 * <p><b>Federation scope.</b> {@link Grid#connect} currently only handles
	 * {@code did:web:}. For DID methods this venue cannot federate with —
	 * including its own {@code did:key} method — we return {@code null}
	 * rather than throwing, so callers can fall through to other resolution
	 * forms or surface a clean "not found" error.</p>
	 */
	private Asset resolveDIDURL(AString ref) {
		DIDURL didurl;
		try {
			didurl = DIDURL.create(ref.toString());
		} catch (IllegalArgumentException e) {
			return null;
		}
		String path = didurl.getPath();
		if (path == null || !path.startsWith("/a/")) return null;

		Hash hash = Hash.parse(path.substring(3));
		if (hash == null) return null;

		// Compare structurally on parsed (method, id) — NOT on DID.toString(),
		// which URL-encodes colons in the id and would break sub-id matching
		// like "VENUE:public".
		DID venueDID;
		try {
			venueDID = getDID();
		} catch (Exception e) {
			venueDID = null;
		}
		DID parsedDID = didurl.getDID();
		String parsedMethod = parsedDID.getMethod();
		String parsedID = parsedDID.getID();

		// Local resolution path 1: exact venue DID match
		if (venueDID != null && venueDID.equals(parsedDID)) {
			return getAsset(hash);
		}

		// Local resolution path 2: venue DID with sub-id (e.g. ":public")
		// The DID URI parser keeps "id:subid" as a single id field. We detect
		// the sub-id form by checking prefix on the unescaped id. Resolve as
		// per-user asset (the public user is keyed by "<venueDID>:public");
		// fall back to venue-level lookup if no user record is present.
		if (venueDID != null
				&& parsedMethod.equals(venueDID.getMethod())
				&& parsedID.startsWith(venueDID.getID() + ":")) {
			// Reconstruct the unescaped DID string for the user lookup. We
			// can't use parsedDID.toString() (URL-encodes the colon).
			AString unescapedDIDString = Strings.create("did:" + parsedMethod + ":" + parsedID);
			User user = getVenueState().users().get(unescapedDIDString);
			if (user != null) {
				AVector<?> rec = getAssetRecord(hash, unescapedDIDString);
				if (rec != null) {
					AString metaString = RT.ensureString(rec.get(AssetStore.POS_JSON));
					if (metaString != null) return Asset.create(hash, metaString);
				}
			}
			// Fall back to venue-level lookup — the asset hash may have been
			// stored in the venue store independently of any user record.
			return getAsset(hash);
		}

		// Local resolution path 3: known user DID (different method/id, stored locally)
		AString didString = Strings.create("did:" + parsedMethod + ":" + parsedID);
		User user = getVenueState().users().get(didString);
		if (user != null) {
			AVector<?> rec = getAssetRecord(hash, didString);
			if (rec == null) return null;
			AString metaString = RT.ensureString(rec.get(AssetStore.POS_JSON));
			return (metaString != null) ? Asset.create(hash, metaString) : null;
		}

		// Remote dispatch — only for DID methods Grid.connect can handle.
		// did:key cannot federate today, so a non-local did:key URL means
		// "not found" rather than "federate to it".
		if (!"web".equals(parsedMethod)) {
			return null;
		}

		Venue remoteVenue = Grid.connect(didString.toString());
		Operation remoteOp = Operation.create(hash, null);
		remoteOp.setVenue(remoteVenue);
		return remoteOp;
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
		// TODO: /o/ — resolve operation name to pinned /a/ hash (Phase 3)

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

		// 4. Operation name registry
		return operations.get(ref);
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

	public AMap<AString, ACell> getDIDDocument(String endpoint) {
		AString did=getDIDString();

		AString key=Multikey.encodePublicKey(keyPair.getAccountKey());
		AString keyID=Strings.create(did+"#"+key);
		AVector<AString> keyVector=Vectors.create(keyID);

		AMap<AString,ACell> ddo=Maps.of(
			"id", did,
			"@context", "https://www.w3.org/ns/did/v1",
			"verificationMethod",Vectors.of(Maps.of(
						"id",keyID,
						"type","Multikey",
						"controller",did,
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
	 * Gets the root lattice cursor. Used by adapters that need access to
	 * top-level lattice regions (e.g. DLFSAdapter for the :dlfs region).
	 */
	@SuppressWarnings("unchecked")
	public ALatticeCursor<Index<Keyword, ACell>> getRootCursor() {
		return lattice;
	}

	public AString getName() {
		return config.getName();
	}

	public AMap<AString, ACell> getStats() {
		AMap<AString, AMap<AString, ACell>> usersMap = auth.getUsers();
		return Maps.of(
				 "assets",getAssets().size(),
				 "users",usersMap != null ? usersMap.count() : 0,
				 "ops",operations.count()
				);
	}

	/**
	 * Gets the JobManager for job lifecycle operations.
	 * @return JobManager instance
	 */
	public JobManager jobs() {
		return jobManager;
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
		if (user == null) return null;

		try {
			byte[] encKey = SecretStore.deriveKey(keyPair);
			AString value = user.secrets().decrypt(Strings.create(name), encKey);
			return (value != null) ? value.toString() : null;
		} catch (Exception e) {
			log.debug("Could not resolve secret '{}': {}", name, e.getMessage());
			return null;
		}
	}

}
