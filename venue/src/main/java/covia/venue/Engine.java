package covia.venue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import convex.lattice.ACursor;
import convex.lattice.Cursors;
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
import covia.grid.Assets;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.api.CoviaAPI;
import covia.venue.storage.AStorage;
import covia.venue.storage.MemoryStorage;

public class Engine {
	
	public static final Logger log=LoggerFactory.getLogger(Engine.class);

	

	public static final Keyword COVIA_KEY = Keyword.intern("covia");
	public static final Keyword ASSETS_KEY = Keyword.intern("assets");
	public static final Keyword JOBS_KEY = Keyword.intern("jobs");
	public static final Keyword USERS_KEY = Keyword.intern("users");


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
	 * Venue lattice 
	 * :covia -> Map
	 *     :assets -> Map
	 *         <AssetID> -> [Asset Record]
	 *     :users -> Index
	 *         <User> -> Map
	 *             :assets -> Set<AssetID>
	 *             :jobs -> Index
	 *                <JobID> -> {Job Status}
 	 */
	protected ACursor<AMap<Keyword,ACell>> lattice=Cursors.of(Maps.create(
			COVIA_KEY, Maps.of(
					ASSETS_KEY,Index.EMPTY,
					USERS_KEY,Index.EMPTY)));
	
	/** Lattice for assets data */
	protected ACursor<Index<AString,AVector<ACell>>> assets=lattice.path(COVIA_KEY, ASSETS_KEY);
	
	/**
	 * Map of named adapters that can handle different types of operations or resources
	 */
	protected final HashMap<String, AAdapter> adapters = new HashMap<>();



	
	public Engine(AMap<AString, ACell> config, EtchStore store) throws IOException {
		this.config=(config==null)?Maps.empty():config;
		this.store=store;
		this.contentStorage = new MemoryStorage();
		this.contentStorage.initialise();
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
		log.info("Registered adapter: {}", name);
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
	
	private AMap<ABlob,AVector<?>> getOperations() {
		return getAssets();
	}


	public static Engine createTemp(AMap<AString,ACell> config) {
		try {
			return new Engine(config,EtchStore.createTemp());
		} catch (IOException e) {
			throw new Error(e);
		}
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

	public Job invokeOperation(String op, ACell input) {
		return invokeOperation(Strings.create(op),input);
	}
	
	public Job invokeOperation(ACell op, ACell input) {
		return invokeOperation(Strings.create(op),input);
	}

	public Job invokeOperation(Hash op, ACell input) {
		return invokeOperation(op.toCVMHexString(),input);
	}

	public Job invokeOperation(AString op, ACell input) {
		if (op==null) throw new IllegalArgumentException("Operation must be a valid Hex asset ID");
		
		Hash opID=Hash.parse(op);
		AMap<AString,ACell> meta=null;
		AString adapterOp=op;
		
		if (opID!=null) {
			// It's a potentially valid asset ID, so look up the operation
			meta=getMetaValue(opID);
			if (meta==null) {
				// no metadata, so assume the op is an explicit adapter op
				return Job.failure("Unable to find asset metadata for "+op);
			} else {
				adapterOp = RT.ensureString(RT.getIn(meta, "operation", "adapter"));
				if (adapterOp == null) {
					throw new IllegalArgumentException("Operation metadata must specify an adapter");
				}
			}
		} 
		
		// Get the combined adapter:operation string from metadata
		String operation = adapterOp.toString();
		
		// Extract adapter name from the operation string
		String adapterName = operation.split(":")[0];
		
		// Get the adapter
		AAdapter adapter = getAdapter(adapterName);
		if (adapter == null) {
			throw new IllegalStateException("Adapter not available: "+adapterName);
		}
		
		Job job=submitJob(op,meta,input);
		
		// Invoke the operation. Adapter is responsible for completing the Job
		adapter.invoke(job, operation, meta,input);

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
				 "ops",getOperations().size()
				);
	}


	
}
