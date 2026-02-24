package covia.venue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import convex.did.DID;
import convex.did.DIDURL;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.RootLatticeCursor;
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
import covia.grid.Grid;
import covia.grid.Operation;
import covia.grid.Venue;
import covia.lattice.Covia;
import covia.venue.api.CoviaAPI;
import covia.venue.storage.AStorage;
import covia.venue.storage.FileStorage;
import covia.venue.storage.LatticeStorage;
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

	/** Authorization / access control */
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
	 * Primary constructor: Engine receives an ALatticeCursor from its caller.
	 * Engine is agnostic to persistence and replication — it just uses the cursor.
	 * Generates a new random key pair for this venue.
	 */
	public Engine(AMap<AString, ACell> config, ALatticeCursor<Index<Keyword,ACell>> cursor) throws IOException {
		this(config, cursor, AKeyPair.generate());
	}

	/**
	 * Constructor with explicit key pair. Use when the venue identity must be
	 * stable across restarts (same AccountKey = same OwnerLattice slot).
	 */
	public Engine(AMap<AString, ACell> config, ALatticeCursor<Index<Keyword,ACell>> cursor, AKeyPair keyPair) throws IOException {
		this.config=new Config(config);
		this.keyPair=keyPair;
		this.lattice=cursor;
		// Set signing context so SignedCursor can sign writes through OwnerLattice
		LatticeContext ctx = LatticeContext.create(null, this.keyPair);
		this.lattice.withContext(ctx);
		initialiseFromCursor();
		this.jobManager = new JobManager(venueState, accessControl,
				this::getAdapter, this::resolveAsset, getDIDString());
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
			return new LatticeStorage(venueState.storageCursor());
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
		if (connected.get() == null) {
			connected.set(Covia.VENUE.zero().assoc(Covia.DID, getDIDString()));
		}

		// Fork: subsequent writes accumulate locally (unsigned).
		// Engine.syncState() calls venueState.sync() to merge + sign once.
		this.venueState = connected.fork();

		this.auth = new Auth(this, venueState.usersCursor());
		this.accessControl = new AccessControl(venueState.authCursor());
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
		Hash id = venueState.assets().store(meta, content);
		log.info("Stored asset {} : {}", id, RT.getIn(JSON.parse(meta), Fields.NAME));
		return id;
	}

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
	 * Get an Asset by its Hash ID.
	 * @param assetID Asset ID
	 * @return Asset instance, or null if not found
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
	public AMap<AString,ACell> getMetaValue(Hash assetID) {
		AVector<?> arec=venueState.assets().getRecord(assetID);
		if (arec==null) return null;
		return RT.ensureMap(arec.get(AssetStore.POS_META));
	}

	// ========== Reference resolution ==========

	/** Namespace prefix for immutable content-addressed assets */
	private static final AString NS_ASSET = Strings.intern("/a/");
	/** Namespace prefix for DID URLs */
	private static final AString NS_DID   = Strings.intern("did:");
	// TODO: private static final AString NS_OPS       = Strings.intern("/o/");
	// TODO: private static final AString NS_WORKSPACE = Strings.intern("/w/");
	// TODO: private static final AString NS_JOB       = Strings.intern("/j/");

	/**
	 * Resolves a reference to an Asset. Supports:
	 * <ul>
	 *   <li>Bare hex hash — shorthand for {@code /a/<hash>}</li>
	 *   <li>{@code /a/<hash>} — explicit asset namespace</li>
	 *   <li>{@code did:.../<namespace>/<path>} — local or remote DID URL</li>
	 *   <li>Operation name (e.g. "test:echo") — operation registry lookup</li>
	 * </ul>
	 *
	 * @param ref Reference string
	 * @param ctx Request context (caller identity for future per-user namespace scoping)
	 * @return Resolved Asset, or null if not resolvable
	 */
	public Asset resolveAsset(AString ref, RequestContext ctx) {
		if (ref == null) return null;

		// 1. Bare hex hash → /a/<hash>
		Hash h = Hash.parse(ref);
		if (h != null) return getAsset(h);

		// 2. Namespace prefix dispatch
		if (ref.startsWith(NS_ASSET)) {
			return resolveAssetRef(ref.slice(3));
		}
		// TODO: /o/ — operation registry (Phase 3)
		// if (ref.startsWith(NS_OPS)) { return resolveOpsRef(ref.slice(3), ctx); }
		// TODO: /w/ — per-user workspace (Phase 3+)
		// if (ref.startsWith(NS_WORKSPACE)) { return resolveWorkspaceRef(ref.slice(3), ctx); }

		// 3. DID URL
		if (ref.startsWith(NS_DID)) {
			return resolveDIDURL(ref);
		}

		// 4. Operation name registry (legacy — becomes /o/ in Phase 3)
		Hash opHash = operations.get(ref);
		if (opHash != null) return getAsset(opHash);

		return null;
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
	 * Resolves a DID URL reference using {@link DIDURL} parsing.
	 * Dispatches on the path namespace prefix. Local DID → local lookup,
	 * remote DID → remote venue reference.
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
		// TODO: dispatch on other namespace prefixes in path (/o/, /w/, etc.)

		Hash hash = Hash.parse(path.substring(3));
		if (hash == null) return null;

		// Local vs remote
		String didStr = didurl.getDID().toString();
		if (getDIDString().toString().equals(didStr)) {
			return getAsset(hash);
		}

		// Remote DID — create Operation with remote venue reference
		Venue remoteVenue = Grid.connect(didStr);
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


}
