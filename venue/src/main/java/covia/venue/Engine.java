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
import covia.grid.Job;
import covia.grid.Status;
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

	
	protected final AMap<AString, ACell> config;

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

	public Engine(AMap<AString, ACell> config, AStore store) throws IOException {
		this.config=(config==null)?Maps.empty():config;
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
		// Get storage config
		AMap<AString, ACell> storageConfig = RT.ensureMap(config.get(Config.STORAGE));
		AString storageType = Config.STORAGE_TYPE_LATTICE; // default
		String storagePath = null;

		if (storageConfig != null) {
			AString contentValue = RT.ensureString(storageConfig.get(Config.CONTENT));
			if (contentValue != null) {
				storageType = contentValue;
			}
			AString pathValue = RT.ensureString(storageConfig.get(Config.PATH));
			if (pathValue != null) {
				storagePath = pathValue.toString();
			}
		}

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
			ACursor<Index<Hash, ABlob>> storageCursor =
				(ACursor<Index<Hash, ABlob>>) (ACursor<?>) lattice.path(
					Covia.GRID, GridLattice.VENUES, getDIDString(), VenueLattice.STORAGE);
			return new LatticeStorage(storageCursor);
		}
	}

	/**
	 * Initialises the lattice with the default venue state structure.
	 * Sets up the :grid -> :venues -> venue structure using Covia.ROOT.
	 */
	protected void initialiseLattice() {
		AMap<Keyword,ACell> initialState = emptyLattice();
		this.lattice = Cursors.of(initialState);
		this.assets = lattice.path(Covia.GRID, GridLattice.VENUES, getDIDString(), VenueLattice.ASSETS);
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


	public AMap<ABlob, AVector<?>> getAssets() {
		// Get assets from lattice cursor
		return RT.ensureMap(this.assets.get());
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
		return operations.get(Strings.create(name));
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

	/**
	 * Resolves an asset reference to an Asset. Supports hex hash, DID URL, and operation name.
	 * @param ref Asset reference string
	 * @return Resolved Asset, or null if not resolvable to an asset on this venue
	 */
	public Asset resolveAsset(String ref) {
		Hash h = resolveAssetHash(ref);
		if (h==null) return null;
		return getAsset(h);
	}

	/**
	 * Resolves an asset reference to a Hash. Supports:
	 * <ul>
	 *   <li>Hex hash string (64 chars) — direct asset ID on this venue</li>
	 *   <li>DID URL with asset path — e.g. "did:key:z6Mk.../a/&lt;hash&gt;" or "did:web:venue.example.com/a/&lt;hash&gt;"</li>
	 *   <li>Operation name — e.g. "test:echo" (looked up in operation registry)</li>
	 * </ul>
	 *
	 * @param ref Asset reference string
	 * @return Resolved Hash, or null if not resolvable
	 */
	public Hash resolveAssetHash(String ref) {
		if (ref==null) return null;

		// 1. Try direct hex hash (shorthand for asset on this venue)
		Hash h = Hash.parse(ref);
		if (h!=null) return h;

		// 2. Try DID URL with /a/<hash> path (any DID method)
		if (ref.startsWith("did:")) {
			int idx = ref.lastIndexOf("/a/");
			if (idx>=0) {
				String hashPart = ref.substring(idx+3);
				Hash parsed = Hash.parse(hashPart);
				if (parsed!=null) return parsed;
			}
		}

		// 3. Try operation name registry
		Hash opHash = operations.get(Strings.create(ref));
		if (opHash!=null) return opHash;

		return null;
	}

	public Job invokeOperation(String op, ACell input) {
		return invokeOperation(Strings.create(op),input);
	}

	public Job invokeOperation(ACell op, ACell input) {
		return invokeOperation(Strings.create(op),input);
	}

	public Job invokeOperation(Hash op, ACell input) {
		return invokeOperation(op.toCVMHexString(),input);
	}

	/**
	 * Invoke an operation given a resolved Asset.
	 * @param asset The operation asset (must have an adapter specified in metadata)
	 * @param input Input parameters
	 * @return Job tracking the execution
	 */
	public Job invokeOperation(Asset asset, ACell input) {
		if (asset==null) throw new IllegalArgumentException("Asset must be specified");
		AMap<AString,ACell> meta = asset.meta();
		AString adapterOp = RT.ensureString(RT.getIn(meta, "operation", "adapter"));
		if (adapterOp == null) {
			throw new IllegalArgumentException("Asset is not an operation (no adapter specified)");
		}

		String operation = adapterOp.toString();
		String adapterName = operation.split(":")[0];
		AAdapter adapter = getAdapter(adapterName);
		if (adapter == null) {
			throw new IllegalStateException("Adapter not available: "+adapterName);
		}

		Job job=submitJob(asset.getID().toCVMHexString(),meta,input);
		adapter.invoke(job, operation, meta, input);
		return job;
	}

	public Job invokeOperation(AString op, ACell input) {
		if (op==null) throw new IllegalArgumentException("Operation must be specified");

		// Resolve the operation reference (hex hash, DID URL, or operation name)
		Asset asset = resolveAsset(op.toString());
		if (asset!=null) {
			return invokeOperation(asset, input);
		}

		// Fall through: use op directly as adapter:operation string
		String operation = op.toString();
		String adapterName = operation.split(":")[0];
		AAdapter adapter = getAdapter(adapterName);
		if (adapter == null) {
			throw new IllegalStateException("Adapter not available: "+adapterName);
		}

		Job job=submitJob(op,null,input);
		adapter.invoke(job, operation, null, input);
		return job;
	}

	public void updateJobStatus(AString jobID, AMap<AString, ACell> job) {
		job=job.assoc(Fields.UPDATED, CVMLong.create(Utils.getCurrentTimestamp()));
		synchronized (jobs) {
			AMap<AString, ACell> oldJob = jobs.get(jobID);
			if (oldJob!=null && Job.isFinished(oldJob)) {
				// can't update already complete job
				return;
			}
			jobs.put(jobID,job);
		}
		log.info("Updated job: "+jobID);
	}

	/**
	 * Gets a snapshot of the current job status
	 * @param jobID
	 * @return Job status record, or null if not found
	 */
	public AMap<AString,ACell> getJobData(AString jobID) {
		synchronized (jobs) {
			return jobs.get(jobID);
		}
	}

	private HashMap<AString,AMap<AString,ACell>> jobs= new HashMap<>();
	
	/** 
	 * Record a Job
	 * @param opID
	 * @param input
	 * @param meta 
	 * @return Job record
	 */
	private Job submitJob(AString opID, AMap<AString,ACell> meta, ACell input) {
		long ts=Utils.getCurrentTimestamp();
		AString jobID = generateJobID(ts);
		// TODO: check very slim chance of JobID collisions?
		
		AMap<AString,ACell> status= Maps.of(
				Fields.ID,jobID,
				Fields.OP,opID,
				Fields.STATUS,Status.PENDING,
				Fields.UPDATED,ts,
				Fields.CREATED,ts,
				Fields.INPUT,input);
		
		AString name=RT.ensureString(RT.getIn(meta, Fields.NAME));
		if (name!=null) {
			status=status.assoc(Fields.NAME, name);
		}
		
		updateJobStatus(jobID,status);
		Job job= new Job(status) {
			@Override public void onUpdate(AMap<AString,ACell> data) {
				updateJobStatus(getID(),data);
			}
		};
		return job;
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

	public List<AString> getJobs() {
		return new ArrayList<>(jobs.keySet());
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
		return contentStorage.getContent(contentHash).getInputStream();
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
		
		// Read the input stream to calculate the actual hash. 
		// TODO: validate while writing? Maybe use DigestInputStream?
		byte[] data = is.readAllBytes();
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
	 * Cancels a Job if it not already complete
	 * @param id ID of Job
	 * @return updates Job status
	 */
	public AMap<AString, ACell> cancelJob(AString id) {
		AMap<AString, ACell> status = getJobData(id);
		if (status==null) return null;
		if (Job.isFinished(status)) return status;
		AMap<AString, ACell> newStatus=status.assoc(Fields.JOB_STATUS_FIELD, Status.CANCELLED);
		synchronized (jobs) {
			jobs.put(id, newStatus);
		}
		return newStatus;
	}

	public AMap<AString,ACell> getConfig() {
		return config;
	}

	public DID getDID() {
		return DID.fromString(getDIDString().toString());
	}
	
	public AString getDIDString() {
		AString s=RT.ensureString(config.get(Fields.DID));
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

	public AString getName() {
		return RT.str(config.get(Fields.NAME));
	}

	public AMap<AString, ACell> getStats() {
		return Maps.of(
				 "jobs",getJobs().size(),
				 "assets",getAssets().size(),
				 "users",101,
				 "ops",operations.count()
				);
	}


	
}
