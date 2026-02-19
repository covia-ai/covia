package covia.venue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.Hashing;
import convex.core.crypto.util.Multikey;
import convex.core.data.ABlob;
import convex.core.data.AccountKey;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.util.JSON;
import convex.core.util.Utils;
import convex.did.DID;
import convex.etch.EtchStore;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFileSystem;
import covia.adapter.AAdapter;
import covia.adapter.ConvexAdapter;
import covia.adapter.CoviaAdapter;
import covia.adapter.GridAdapter;
import covia.adapter.HTTPAdapter;
import covia.adapter.JVMAdapter;
import covia.adapter.LangChainAdapter;
import covia.adapter.MCPAdapter;
import covia.adapter.Orchestrator;
import covia.adapter.TestAdapter;
import covia.api.Fields;
import covia.grid.AContent;
import covia.grid.Asset;
import covia.grid.Assets;
import covia.grid.Grid;
import covia.grid.Job;
import covia.grid.Operation;
import covia.grid.Status;
import covia.grid.Venue;
import covia.lattice.Covia;
import covia.lattice.GridLattice;
import covia.lattice.VenueLattice;
import covia.venue.api.CoviaAPI;
import covia.venue.storage.AStorage;
import covia.venue.storage.FileStorage;
import covia.venue.storage.LatticeStorage;
import covia.venue.storage.MemoryStorage;

public class Engine {
	
	public static final Logger log=LoggerFactory.getLogger(Engine.class);

	

    // Structure of asset record
	public static final long POS_JSON = 0;
	public static final long POS_CONTENT= 1;
	public static final long POS_META = 2;

	
	protected final Config config;

	protected final AStore store;
	
	protected AKeyPair keyPair=AKeyPair.generate();
	
	/**
	 * Storage instance for content associated with assets
	 */
	protected final AStorage contentStorage;
	
	/**
	 * Venue lattice using Covia.ROOT structure see COG-004 
 	 */
	protected ACursor<AMap<Keyword,ACell>> lattice;

	/** Lattice cursor for assets data */
	protected ACursor<Index<AString,AVector<ACell>>> assets;

	/** Lattice cursor for jobs data (Index for natural time-ordering of timestamp-prefixed IDs) */
	protected ACursor<Index<AString, ACell>> jobsCursor;

	/** Authentication and user management */
	protected Auth auth;

	/** Authorization / access control */
	protected AccessControl accessControl;
	
	/**
	 * Map of named adapters that can handle different types of operations or resources
	 */
	protected final HashMap<String, AAdapter> adapters = new HashMap<>();

	/**
	 * Listeners notified on every job state update.
	 * Used by SseServer for per-job event broadcasting and MCP SSE notifications.
	 */
	private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<Job>> jobUpdateListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

	/**
	 * Registry of named operations mapping operation name (e.g. "test:echo") to canonical asset Hash.
	 * Populated during adapter registration from each adapter's operation names.
	 */
	@SuppressWarnings("unchecked")
	protected Index<AString, Hash> operations = (Index<AString, Hash>) Index.EMPTY;

	public Engine(AMap<AString, ACell> config, AStore store) throws IOException {
		this.config=new Config(config);
		this.store=store;
		initialiseLattice();
		this.contentStorage = createStorage();
		this.contentStorage.initialise();
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
	@SuppressWarnings("unchecked")
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
			// Create lattice storage backed by venue's :storage cursor
			ACursor<Index<ABlob, ABlob>> storageCursor =
				(ACursor<Index<ABlob, ABlob>>) (ACursor<?>) lattice.path(
					Covia.GRID, GridLattice.VENUES, getDIDString(), VenueLattice.STORAGE);
			return new LatticeStorage(storageCursor);
		}
	}

	/**
	 * Initialises the lattice, loading from the Etch store if state exists,
	 * otherwise creating a fresh empty structure.
	 * Sets up the :grid -> :venues -> venue structure using Covia.ROOT.
	 */
	protected void initialiseLattice() {
		AMap<Keyword,ACell> initialState = loadStateFromStore();
		if (initialState == null) {
			initialState = emptyLattice();
		}
		this.lattice = Cursors.of(initialState);
		this.assets = lattice.path(Covia.GRID, GridLattice.VENUES, getDIDString(), VenueLattice.ASSETS);
		this.jobsCursor = lattice.path(Covia.GRID, GridLattice.VENUES, getDIDString(), VenueLattice.JOBS);
		ACursor<AMap<AString, AMap<AString, ACell>>> usersCursor = lattice.path(Covia.GRID, GridLattice.VENUES, getDIDString(), VenueLattice.USERS);
		this.auth = new Auth(this, usersCursor);

		ACursor<AMap<Keyword, ACell>> authCursor = lattice.path(Covia.GRID, GridLattice.VENUES, getDIDString(), VenueLattice.AUTH);
		this.accessControl = new AccessControl(authCursor);
	}

	/**
	 * Attempts to load lattice state from the Etch store.
	 * @return The stored lattice root, or null if no state exists or loading fails
	 */
	private AMap<Keyword,ACell> loadStateFromStore() {
		try {
			ACell root = store.getRootData();
			return RT.ensureMap(root);
		} catch (Exception e) {
			log.warn("Could not load state from store", e);
			return null;
		}
	}

	/**
	 * Persists the entire lattice root to the Etch store.
	 * This writes all lattice data (assets, jobs, users, storage) to durable storage.
	 * @throws IOException if persistence fails
	 */
	public void persistState() throws IOException {
		ACell latticeRoot = lattice.get();
		store.setRootData(latticeRoot);
	}

	private AHashMap<Keyword, ACell> emptyLattice() {
		return Maps.of(
			Covia.GRID, Maps.of(
				GridLattice.VENUES, Index.of(
					getDIDString(), VenueLattice.INSTANCE.zero()
				)
			)
		);
	}
	
	public static void addDemoAssets(Engine venue) {
		venue.registerAdapter(new TestAdapter());
		venue.registerAdapter(new HTTPAdapter());
		venue.registerAdapter(new JVMAdapter());
		venue.registerAdapter(new Orchestrator());
		venue.registerAdapter(new MCPAdapter());
		venue.registerAdapter(new LangChainAdapter());
		venue.registerAdapter(new CoviaAdapter());
		venue.registerAdapter(new GridAdapter());
		venue.registerAdapter(new ConvexAdapter());
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

	public Hash storeAsset(AString meta, ACell content) {
		AMap<AString,ACell> metaMap=RT.ensureMap(JSON.parse(meta));
		if (metaMap==null) {
			throw new IllegalArgumentException("Metadata is not a valid JSON object");
		}
		return storeAsset(meta,content,metaMap);
	}
	
	private synchronized Hash storeAsset(AString meta, ACell content, AMap<AString,ACell> metaMap) {
		Hash id=Assets.calcID(meta);
		AMap<ABlob,AVector<?>> assets=getAssets();
		boolean exists=assets.containsKey(id);
		log.info((exists?"Updated":"Stored")+" asset "+id +" : "+RT.getIn(metaMap, Fields.NAME));
		
		setAssets(assets.assoc(id, assetRecord(meta,content,metaMap))); // TODO: asset record design?		
		return id;
	}


	private AVector<ACell> assetRecord(AString meta, ACell content, AMap<AString,ACell> metaMap) {
		return Vectors.create(meta,content,metaMap);
	}


	private void setAssets(AMap<ABlob, AVector<?>> assets) {
		this.assets.set(assets);
	}


	/**
	 * Recovers jobs from the lattice after a restart.
	 * PENDING jobs are re-fired; in-progress jobs (STARTED, PAUSED, etc.) are marked FAILED.
	 * Terminal jobs (COMPLETE, FAILED, CANCELLED, REJECTED) are left as-is.
	 * Should be called after adapters are registered.
	 */
	@SuppressWarnings("unchecked")
	public void recoverJobs() {
		Index<AString, ACell> jobsIndex = (Index<AString, ACell>) jobsCursor.get();
		if (jobsIndex == null || jobsIndex.isEmpty()) return;

		long n = jobsIndex.count();
		int refired = 0;
		int kept = 0;
		int failed = 0;
		for (long i = 0; i < n; i++) {
			var entry = jobsIndex.entryAt(i);
			AString jobID = entry.getKey();
			ACell value = entry.getValue();
			if (!(value instanceof AMap)) continue;
			AMap<AString, ACell> record = (AMap<AString, ACell>) value;

			// Skip jobs already in memory (shouldn't happen on fresh start, but safe)
			synchronized (jobs) {
				if (jobs.containsKey(jobID)) continue;
			}

			// Skip terminal jobs
			if (Job.isFinished(record)) continue;

			AString status = RT.ensureString(record.get(Fields.STATUS));
			if (Status.PENDING.equals(status) || Status.STARTED.equals(status)) {
				// Re-fire PENDING and STARTED jobs
				if (refireJob(jobID, record)) {
					refired++;
				} else {
					failed++;
				}
			} else {
				// PAUSED, INPUT_REQUIRED, AUTH_REQUIRED — restore as live Job, awaiting external action
				restoreJob(jobID, record);
				kept++;
			}
		}

		if (refired > 0 || kept > 0 || failed > 0) {
			log.info("Job recovery: {} re-fired, {} kept (paused/waiting), {} failed", refired, kept, failed);
		}
	}

	/**
	 * Re-fires a PENDING job from a persisted lattice record.
	 * Resolves the operation and adapter from the :op field and invokes.
	 * @return true if successfully re-fired, false if resolution failed
	 */
	private boolean refireJob(AString jobID, AMap<AString, ACell> record) {
		AString opRef = RT.ensureString(record.get(Fields.OP));
		if (opRef == null) {
			markJobFailed(jobID, record, "Cannot re-fire: no operation reference");
			return false;
		}

		// Resolve operation
		Asset asset = resolveAsset(opRef);
		Operation op = (asset != null) ? Operation.from(asset) : null;

		// Resolve adapter
		AAdapter adapter = resolveAdapterForOp(op, opRef);
		if (adapter == null) {
			markJobFailed(jobID, record, "Cannot re-fire: adapter not available for " + opRef);
			return false;
		}

		// Create a live Job wrapping the persisted record
		Job job = new Job(record, op) {
			@Override public AMap<AString,ACell> processUpdate(AMap<AString,ACell> newData) {
				newData = newData.assoc(Fields.UPDATED, CVMLong.create(Utils.getCurrentTimestamp()));
				persistJobRecord(getID(), newData);
				return newData;
			}
		};

		if (!jobUpdateListeners.isEmpty()) {
			job.setUpdateListener(j -> jobUpdateListeners.forEach(l -> l.accept(j)));
		}

		synchronized (jobs) {
			jobs.put(jobID, job);
		}

		// Determine adapter:operation string for invocation
		String adapterStr = opRef.toString();
		AMap<AString, ACell> meta = (op != null) ? op.meta() : null;
		if (meta != null) {
			AString adapterOp = RT.ensureString(RT.getIn(meta, "operation", "adapter"));
			if (adapterOp != null) adapterStr = adapterOp.toString();
		}

		adapter.invoke(job, adapterStr, meta, record.get(Fields.INPUT));
		log.info("Re-fired job: {}", jobID);
		return true;
	}

	/**
	 * Resolves the adapter for an operation, trying the Operation metadata first,
	 * then falling back to parsing the opRef string as adapter:operation.
	 */
	private AAdapter resolveAdapterForOp(Operation op, AString opRef) {
		// Try operation metadata
		if (op != null) {
			AString adapterOp = RT.ensureString(RT.getIn(op.meta(), "operation", "adapter"));
			if (adapterOp != null) {
				String adapterName = adapterOp.toString().split(":")[0];
				AAdapter adapter = getAdapter(adapterName);
				if (adapter != null) return adapter;
			}
		}

		// Fall back to parsing opRef as adapter:operation
		String adapterName = opRef.toString().split(":")[0];
		return getAdapter(adapterName);
	}

	private void markJobFailed(AString jobID, AMap<AString, ACell> record, String reason) {
		AMap<AString, ACell> failedRecord = record
				.assoc(Fields.STATUS, Status.FAILED)
				.assoc(Fields.ERROR, Strings.create(reason))
				.assoc(Fields.UPDATED, CVMLong.create(Utils.getCurrentTimestamp()));
		persistJobRecord(jobID, failedRecord);
		log.warn("Job {} failed on recovery: {}", jobID, reason);
	}

	/**
	 * Restores a paused/waiting job into the in-memory jobs map as a live Job object
	 * with write-through persistence. Does not re-invoke the adapter.
	 */
	private void restoreJob(AString jobID, AMap<AString, ACell> record) {
		AString opRef = RT.ensureString(record.get(Fields.OP));
		Operation op = null;
		if (opRef != null) {
			Asset asset = resolveAsset(opRef);
			op = (asset != null) ? Operation.from(asset) : null;
		}

		Job job = new Job(record, op) {
			@Override public AMap<AString,ACell> processUpdate(AMap<AString,ACell> newData) {
				newData = newData.assoc(Fields.UPDATED, CVMLong.create(Utils.getCurrentTimestamp()));
				persistJobRecord(getID(), newData);
				return newData;
			}
		};

		if (!jobUpdateListeners.isEmpty()) {
			job.setUpdateListener(j -> jobUpdateListeners.forEach(l -> l.accept(j)));
		}

		synchronized (jobs) {
			jobs.put(jobID, job);
		}
	}

	public AMap<ABlob, AVector<?>> getAssets() {
		// Get assets from lattice cursor
		return RT.ensureMap(this.assets.get());
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
	public Hash resolveOperation(AString name) {
		return operations.get(name);
	}


	public static Engine createTemp(AMap<AString,ACell> config) {
		try {
			return new Engine(config,EtchStore.createTemp());
		} catch (IOException e) {
			throw new Error(e);
		}
	}

	/**
	 * Get an Asset by its Hash ID.
	 * @param assetID Asset ID
	 * @return Asset instance, or null if not found
	 */
	public Asset getAsset(Hash assetID) {
		AVector<?> arec=getAssets().get(assetID);
		if (arec==null) return null;
		AString metaString = RT.ensureString(arec.get(POS_JSON));
		if (metaString==null) return null;
		return Asset.create(assetID, metaString);
	}

	/**
	 * Get metadata as a JSON string
	 * @param assetID
	 * @return Metadata string for the given Asset ID, or null if not found
	 */
	public AString getMetadata(Hash assetID) {
		AVector<?> arec=getAssets().get(assetID);
		if (arec==null) return null;
		return RT.ensureString(arec.get(POS_JSON));
	}
	
	/**
	 * Get metadata as a structured value
	 * @param assetID Asset ID of operation
	 * @return Metadata value, or null if not valid metadata
	 */
	public AMap<AString,ACell> getMetaValue(Hash assetID) {
		AVector<?> arec=getAssets().get(assetID);
		if (arec==null) return null;
		return RT.ensureMap(arec.get(POS_META));
	}

	// TODO: Consider whether asset IDs should be AString (hex) or Hash throughout.
	// Currently assets are keyed by Hash in the lattice but referenced by hex AString
	// in operation refs, job records, and DID URLs. Unifying on one canonical type
	// would eliminate conversions.

	/**
	 * Resolves an asset reference to an Asset. Supports hex hash, DID URL, operation name,
	 * and remote DID URLs (returns an Asset with a remote venue reference).
	 * @param ref Asset reference (AString)
	 * @return Resolved Asset, or null if not resolvable
	 */
	public Asset resolveAsset(AString ref) {
		if (ref==null) return null;
		String s = ref.toString();

		// 1. Try direct hex hash (shorthand for asset on this venue)
		Hash h = Hash.parse(s);
		if (h!=null) return getAsset(h);

		// 2. Try DID URL with /a/<hash> path
		if (s.startsWith("did:")) {
			int idx = s.lastIndexOf("/a/");
			if (idx>=0) {
				String didPart = s.substring(0, idx);
				String hashPart = s.substring(idx+3);
				Hash parsed = Hash.parse(hashPart);
				if (parsed!=null) {
					// Check if this DID refers to the local venue or a remote one
					String localDID = getDIDString().toString();
					if (localDID.equals(didPart)) {
						// Local DID — look up asset locally
						return getAsset(parsed);
					} else {
						// Remote DID — create Operation with remote venue reference
						Venue remoteVenue = Grid.connect(didPart);
						Operation remoteOp = Operation.create(parsed, null);
						remoteOp.setVenue(remoteVenue);
						return remoteOp;
					}
				}
			}
		}

		// 3. Try operation name registry
		Hash opHash = operations.get(ref);
		if (opHash!=null) return getAsset(opHash);

		return null;
	}

	/**
	 * Resolves an asset reference to a local Hash. Supports hex hash, DID URL (local only),
	 * and operation name. Does not handle remote DIDs — use resolveAsset() for full resolution.
	 *
	 * @param ref Asset reference (AString)
	 * @return Resolved Hash, or null if not resolvable locally
	 */
	public Hash resolveAssetHash(AString ref) {
		if (ref==null) return null;
		String s = ref.toString();

		// 1. Try direct hex hash (shorthand for asset on this venue)
		Hash h = Hash.parse(s);
		if (h!=null) return h;

		// 2. Try DID URL with /a/<hash> path (local DID only)
		if (s.startsWith("did:")) {
			int idx = s.lastIndexOf("/a/");
			if (idx>=0) {
				String hashPart = s.substring(idx+3);
				Hash parsed = Hash.parse(hashPart);
				if (parsed!=null) return parsed;
			}
		}

		// 3. Try operation name registry
		Hash opHash = operations.get(ref);
		if (opHash!=null) return opHash;

		return null;
	}

	/**
	 * Invoke an operation given a reference with request context.
	 */
	public Job invokeOperation(AString ref, ACell input, RequestContext ctx) {
		return invokeOperation(ref, input, ctx.getCallerDID());
	}

	/**
	 * Invoke an operation given a reference (internal/programmatic use, no caller identity).
	 */
	public Job invokeOperation(AString ref, ACell input) {
		return invokeOperation(ref, input, (AString) null);
	}

	/**
	 * Invoke an operation given a reference. Supports hex hash, DID URL,
	 * operation name, and adapter:operation strings.
	 * @param ref Operation reference (AString)
	 * @param input Input parameters
	 * @param callerDID Caller DID string, or null if anonymous
	 * @return Job tracking the execution
	 */
	public Job invokeOperation(AString ref, ACell input, AString callerDID) {
		if (ref==null) throw new IllegalArgumentException("Operation must be specified");

		// Resolve the operation reference (hex hash, DID URL, or operation name)
		Asset asset = resolveAsset(ref);
		if (asset!=null) {
			return invokeOperation(asset, input, callerDID);
		}

		// Fall through: use ref directly as adapter:operation string
		String refStr = ref.toString();
		String adapterName = refStr.split(":")[0];
		AAdapter adapter = getAdapter(adapterName);
		if (adapter == null) {
			throw new IllegalStateException("Adapter not available: "+adapterName);
		}

		Job job=submitJob(ref,null,input,null,callerDID);
		adapter.invoke(job, refStr, null, input);
		return job;
	}

	/**
	 * Invoke an operation given a resolved Asset (internal/programmatic use, no caller identity).
	 */
	public Job invokeOperation(Asset asset, ACell input) {
		return invokeOperation(asset, input, null);
	}

	/**
	 * Invoke an operation given a resolved Asset. If the asset has a remote venue
	 * reference, delegates to the remote venue via asset.invoke().
	 * @param asset The operation asset
	 * @param input Input parameters
	 * @param callerDID Caller DID string, or null if anonymous
	 * @return Job tracking the execution
	 */
	public Job invokeOperation(Asset asset, ACell input, AString callerDID) {
		if (asset==null) throw new IllegalArgumentException("Asset must be specified");

		// Ensure we have an Operation (not a plain Asset)
		Operation op = Operation.from(asset);
		if (op==null) {
			throw new IllegalArgumentException("Asset is not an operation: "+asset.getID());
		}

		// Check for remote operation — delegate to the operation's venue
		Venue opVenue = op.getVenue();
		if (opVenue != null && !(opVenue instanceof LocalVenue)) {
			return op.invoke(input).join();
		}

		// Local operation — dispatch via adapter
		AMap<AString,ACell> meta = op.meta();
		AString adapterOp = RT.ensureString(RT.getIn(meta, "operation", "adapter"));

		String adapterStr = adapterOp.toString();
		String adapterName = adapterStr.split(":")[0];
		AAdapter adapter = getAdapter(adapterName);
		if (adapter == null) {
			throw new IllegalStateException("Adapter not available: "+adapterName);
		}

		Job job=submitJob(op,meta,input,callerDID);
		adapter.invoke(job, adapterStr, meta, input);
		return job;
	}

	public void updateJobStatus(AString jobID, AMap<AString, ACell> newData) {
		Job job;
		synchronized (jobs) {
			job = jobs.get(jobID);
		}
		if (job == null) return;
		job.updateData(newData);
	}

	/**
	 * Gets a snapshot of the current job status data with request context.
	 */
	public AMap<AString,ACell> getJobData(AString jobID, RequestContext ctx) {
		return getJobData(jobID);
	}

	/**
	 * Gets a snapshot of the current job status data.
	 * Checks in-memory cache first, falls back to lattice.
	 * @param jobID
	 * @return Job status record, or null if not found
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString,ACell> getJobData(AString jobID) {
		Job job;
		synchronized (jobs) {
			job = jobs.get(jobID);
		}
		if (job != null) return job.getData();

		// Fall back to lattice
		Index<AString, ACell> jobsIndex = (Index<AString, ACell>) jobsCursor.get();
		if (jobsIndex == null) return null;
		ACell record = jobsIndex.get(jobID);
		return (record instanceof AMap) ? (AMap<AString, ACell>) record : null;
	}

	/**
	 * Gets the live Job object for the given job ID.
	 * Checks in-memory cache first, falls back to constructing a read-only Job from lattice.
	 * @param jobID Job ID
	 * @return Job, or null if not found
	 */
	@SuppressWarnings("unchecked")
	public Job getJob(AString jobID) {
		synchronized (jobs) {
			Job job = jobs.get(jobID);
			if (job != null) return job;
		}

		// Fall back to lattice — construct a bare Job from persisted record
		Index<AString, ACell> jobsIndex = (Index<AString, ACell>) jobsCursor.get();
		if (jobsIndex == null) return null;
		ACell record = jobsIndex.get(jobID);
		if (!(record instanceof AMap)) return null;
		return new Job((AMap<AString, ACell>) record, null);
	}

	/**
	 * Delivers a message to a job's message queue with request context.
	 */
	public int deliverMessage(AString jobID, AMap<AString, ACell> message, RequestContext ctx) {
		AString did = ctx.getCallerDID();
		return deliverMessage(jobID, message, did);
	}

	/**
	 * Delivers a message to a job's message queue.
	 * Wraps the raw message in an extensible record with metadata.
	 * @param jobID Job ID (AString)
	 * @param message Raw message content (arbitrary JSON)
	 * @param source Source identifier (DID or client ID), may be null
	 * @return Queue depth after enqueue
	 * @throws IllegalArgumentException if job not found
	 * @throws IllegalStateException if job is in terminal state
	 */
	public int deliverMessage(AString jobID, AMap<AString, ACell> message, AString source) {
		Job job = getJob(jobID);
		if (job == null) throw new IllegalArgumentException("Job not found: " + jobID);
		if (job.isFinished()) throw new IllegalStateException("Job is in terminal state: " + jobID);

		long ts = Utils.getCurrentTimestamp();
		AString msgId = generateJobID(ts); // reuse ID generator for unique message IDs

		AMap<AString, ACell> record = Maps.of(
				Fields.MESSAGE, message,
				Fields.TS, CVMLong.create(ts),
				Fields.ID, msgId);
		if (source != null) {
			record = record.assoc(Fields.SOURCE, source);
		}

		job.enqueueMessage(record);
		int depth = job.getQueueSize();

		// Dispatch to adapter if it supports multi-turn
		AAdapter adapter = resolveJobAdapter(job);
		if (adapter != null && adapter.supportsMultiTurn()) {
			adapter.handleMessage(job, record);
		}

		return depth;
	}

	/**
	 * Resolves the adapter responsible for a job based on its asset or operation field.
	 * @param job The job
	 * @return The adapter, or null if not resolvable
	 */
	private AAdapter resolveJobAdapter(Job job) {
		// Prefer the stored Operation reference (avoids re-resolution)
		Operation operation = job.getOperation();
		if (operation != null) {
			AString adapterOp = RT.ensureString(RT.getIn(operation.meta(), "operation", "adapter"));
			if (adapterOp != null) {
				String adapterName = adapterOp.toString().split(":")[0];
				return getAdapter(adapterName);
			}
		}

		// Fall back to resolving from the :op field
		AString opStr = RT.ensureString(job.getData().get(Fields.OP));
		if (opStr == null) return null;
		Asset asset = resolveAsset(opStr);
		if (asset != null) {
			AString adapterOp = RT.ensureString(RT.getIn(asset.meta(), "operation", "adapter"));
			if (adapterOp != null) {
				String adapterName = adapterOp.toString().split(":")[0];
				return getAdapter(adapterName);
			}
		}

		// Last resort: parse :op as adapter:operation string
		String adapterName = opStr.toString().split(":")[0];
		return getAdapter(adapterName);
	}

	private HashMap<AString, Job> jobs = new HashMap<>();
	
	/**
	 * Submit a Job for a resolved Operation.
	 */
	private Job submitJob(Operation operation, AMap<AString,ACell> meta, ACell input, AString callerDID) {
		return submitJob(operation.getID().toCVMHexString(), meta, input, operation, callerDID);
	}

	/**
	 * Submit a Job for an unresolved adapter:operation string.
	 */
	private Job submitJob(AString opID, AMap<AString,ACell> meta, ACell input, Operation operation, AString callerDID) {
		long ts=Utils.getCurrentTimestamp();
		AString jobID = generateJobID(ts);

		AMap<AString,ACell> status= Maps.of(
				Fields.ID,jobID,
				Fields.OP,opID,
				Fields.STATUS,Status.PENDING,
				Fields.UPDATED,CVMLong.create(ts),
				Fields.CREATED,CVMLong.create(ts),
				Fields.INPUT,input);

		if (callerDID!=null) {
			status=status.assoc(Fields.CALLER, callerDID);
		}

		AString name=RT.ensureString(RT.getIn(meta, Fields.NAME));
		if (name!=null) {
			status=status.assoc(Fields.NAME, name);
		}

		Job job = new Job(status, operation) {
			@Override public AMap<AString,ACell> processUpdate(AMap<AString,ACell> newData) {
				newData = newData.assoc(Fields.UPDATED, CVMLong.create(Utils.getCurrentTimestamp()));
				persistJobRecord(getID(), newData);
				return newData;
			}
		};

		// Set update listener if configured (e.g. for SSE broadcasting)
		if (!jobUpdateListeners.isEmpty()) {
			job.setUpdateListener(j -> jobUpdateListeners.forEach(l -> l.accept(j)));
		}

		synchronized (jobs) {
			jobs.put(jobID, job);
		}

		// Persist initial job record to lattice
		persistJobRecord(jobID, status);

		log.info("Submitted job: "+jobID);
		return job;
	}


	/**
	 * Persists a job record to the lattice :jobs index.
	 * Called on initial submission and every status update via processUpdate().
	 */
	@SuppressWarnings("unchecked")
	private void persistJobRecord(AString jobID, AMap<AString, ACell> record) {
		jobsCursor.updateAndGet(jobs -> {
			if (jobs == null) jobs = Index.none();
			return ((Index<AString, ACell>) jobs).assoc(jobID, record);
		});
	}

	private Random rand=new Random();
	private short jobCounter=0;
	private long lastJobTS=0;
	/** Internal method to generate job IDs 
	 * Format is:
	 * 6 bytes timestamp (low 48 bits of Long)
	 * 2 bytes incrementing counter for same timestamp
	 * 8 bytes randomness
	 * 
	 * This is so that job IDs are usually sorted, but relatively unpredictable and very unlikely to collide
	 */
	private AString generateJobID(long ts) {
		if (ts>lastJobTS) jobCounter=0; // reset job counter when TS increments
		byte[] bs=new byte[16];
		
		Utils.writeLong(bs, 0, ts<<16); // 48 bits enough for all plausible timestamps
		Utils.writeShort(bs, 6, jobCounter++);
		Utils.writeLong(bs, 8, rand.nextLong());

		AString jobID=Blob.wrap(bs).toCVMHexString();
		return jobID;
	}

	/**
	 * Adds a listener to be notified on all job state updates.
	 * Multiple listeners can be registered (e.g. SSE server + MCP notifications).
	 * @param listener Update listener
	 */
	public void addJobUpdateListener(java.util.function.Consumer<Job> listener) {
		this.jobUpdateListeners.add(listener);
	}

	/**
	 * Returns the current lattice state root.
	 * @return Lattice state as ACell, or null if not initialised
	 */
	public ACell getLatticeState() {
		return lattice.get();
	}

	/**
	 * Gets the jobs Index with request context.
	 */
	public Index<AString, ACell> getJobs(RequestContext ctx) {
		return getJobs();
	}

	/**
	 * Gets the jobs Index directly from the lattice (naturally time-ordered
	 * since job IDs are timestamp-prefixed).
	 * @return Index of job IDs to job records, or empty Index if none
	 */
	@SuppressWarnings("unchecked")
	public Index<AString, ACell> getJobs() {
		Index<AString, ACell> jobsIndex = (Index<AString, ACell>) jobsCursor.get();
		if (jobsIndex == null) return Index.none();
		return jobsIndex;
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
	 * Deletes a job permanently with request context.
	 */
	public boolean deleteJob(AString id, RequestContext ctx) {
		return deleteJob(id);
	}

	/**
	 * Deletes a job permanently
	 * @param id ID of Job
	 * @return true if removed, false if did not exist anyway
	 */
	public boolean deleteJob(AString id) {
		synchronized (jobs) {
			return jobs.remove(id)!=null;
		}
	}

	/**
	 * Cancels a Job with request context.
	 */
	public AMap<AString, ACell> cancelJob(AString id, RequestContext ctx) {
		return cancelJob(id);
	}

	/**
	 * Cancels a Job if it not already complete
	 * @param id ID of Job
	 * @return updated Job status, or null if not found
	 */
	public AMap<AString, ACell> cancelJob(AString id) {
		Job job;
		synchronized (jobs) {
			job = jobs.get(id);
		}
		if (job == null) return null;
		job.cancel();
		return job.getData();
	}

	/**
	 * Pauses a running Job with request context.
	 */
	public AMap<AString, ACell> pauseJob(AString id, RequestContext ctx) {
		return pauseJob(id);
	}

	/**
	 * Pauses a running Job.
	 * @param id ID of Job
	 * @return updated Job status, or null if not found
	 */
	public AMap<AString, ACell> pauseJob(AString id) {
		Job job;
		synchronized (jobs) {
			job = jobs.get(id);
		}
		if (job == null) return null;
		job.pause();
		return job.getData();
	}

	/**
	 * Resumes a paused Job with request context.
	 */
	public AMap<AString, ACell> resumeJob(AString id, RequestContext ctx) {
		return resumeJob(id);
	}

	/**
	 * Resumes a paused Job. Re-engages the adapter to continue execution.
	 * @param id ID of Job
	 * @return updated Job status, or null if not found
	 */
	public AMap<AString, ACell> resumeJob(AString id) {
		Job job;
		synchronized (jobs) {
			job = jobs.get(id);
		}
		if (job == null) return null;
		job.resume();

		// Re-engage the adapter
		AAdapter adapter = resolveJobAdapter(job);
		if (adapter != null) {
			Operation op = job.getOperation();
			AMap<AString,ACell> meta = (op != null) ? op.meta() : null;
			String adapterStr = null;
			if (meta != null) {
				AString adapterOp = RT.ensureString(RT.getIn(meta, "operation", "adapter"));
				if (adapterOp != null) adapterStr = adapterOp.toString();
			}
			if (adapterStr == null) {
				AString opRef = RT.ensureString(job.getData().get(Fields.OP));
				if (opRef != null) adapterStr = opRef.toString();
			}
			if (adapterStr != null) {
				adapter.invoke(job, adapterStr, meta, job.getData().get(Fields.INPUT));
			}
		}

		return job.getData();
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

	public AString getName() {
		return config.getName();
	}

	public AMap<AString, ACell> getStats() {
		AMap<AString, AMap<AString, ACell>> usersMap = auth.getUsers();
		return Maps.of(
				 "jobs",getJobs().count(),
				 "assets",getAssets().size(),
				 "users",usersMap != null ? usersMap.count() : 0,
				 "ops",operations.count()
				);
	}


	
}
