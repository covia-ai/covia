package covia.venue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import convex.core.json.JSONReader;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.util.JSONUtils;
import convex.core.util.Utils;
import convex.etch.EtchStore;
import convex.lattice.ACursor;
import convex.lattice.Cursors;
import covia.adapter.AAdapter;
import covia.adapter.LangChainAdapter;
import covia.adapter.Orchestrator;
import covia.adapter.TestAdapter;
import covia.api.Fields;
import covia.grid.Assets;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.storage.AStorage;
import covia.venue.storage.MemoryStorage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import convex.core.crypto.Hashing;

public class Venue {
	
	public static final Logger log=LoggerFactory.getLogger(Venue.class);

	

	public static final Keyword COVIA_KEY = Keyword.intern("covia");
	public static final Keyword ASSETS_KEY = Keyword.intern("assets");
	public static final Keyword JOBS_KEY = Keyword.intern("jobs");



	static final long POS_JSON = 0;
	static final long POS_CONTENT= 1;
	static final long POS_META = 2;
	

	protected final AStore store;
	
	/**
	 * Storage instance for content associated with assets
	 */
	protected final AStorage contentStorage;
	
	/** Overall Covia lattice */
	protected ACursor<AMap<Keyword,ACell>> lattice=Cursors.of(Maps.create(COVIA_KEY, Maps.create(ASSETS_KEY,Index.EMPTY)));
	
	/** Lattice for assets data */
	protected ACursor<Index<AString,AVector<ACell>>> assets=lattice.path(COVIA_KEY, ASSETS_KEY);
	
	/**
	 * Map of named adapters that can handle different types of operations or resources
	 */
	protected final HashMap<String, AAdapter> adapters = new HashMap<>();
	
	
	public Venue() throws IOException {
		this(EtchStore.createTemp());
	}


	public Venue(EtchStore store) throws IOException {
		this.store=store;
		this.contentStorage = new MemoryStorage();
		this.contentStorage.initialise();
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

	public Hash storeAsset(AMap<AString,ACell> meta, ACell content) {
		AString metaString=JSONUtils.toJSONString(meta);
		return storeAsset(metaString,content,meta);
	}

	public Hash storeAsset(String meta, ACell content) {
		AMap<AString,ACell> metaMap=RT.ensureMap(JSONReader.read(meta));
		if (metaMap==null) {
			throw new IllegalArgumentException("Metadata is not a valid JSON object");
		}
		return storeAsset(Strings.create(meta),content,metaMap);
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


	public static Venue createTemp() {
		try {
			return new Venue(EtchStore.createTemp());
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


	public static void addDemoAssets(Venue venue) {
		String BASE="/asset-examples/";
		try {
			venue.registerAdapter(new TestAdapter());
			venue.registerAdapter(new Orchestrator());
			venue.registerAdapter(new LangChainAdapter());
			venue.storeAsset(Utils.readResourceAsString(BASE+"qwen.json"),null);
		} catch (IOException e) {
			throw new Error(e);
		}
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
		ACell meta=null;
		AString adapterOp=op;
		if (opID!=null) {
			// It's a valid asset ID, so look up the operation
			meta=getMetaValue(opID);
			if (meta==null) {
				return null;
			}
			adapterOp = RT.ensureString(RT.getIn(meta, "operation", "adapter"));
			if (adapterOp == null) {
				throw new IllegalArgumentException("Operation metadata must specify an adapter");
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
		
		Job job=submitJob(op,input);
		
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
	 * @return Job record
	 */
	private Job submitJob(AString opID, ACell input) {
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
	 * @return Content stream, or null if no available / does not exist
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

	
	
}
