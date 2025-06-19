package covia.venue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blobs;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.util.JSONUtils;
import convex.core.util.Utils;
import convex.etch.EtchStore;
import covia.adapter.AAdapter;
import covia.adapter.LangChainAdapter;
import covia.adapter.Status;
import covia.adapter.TestAdapter;
import covia.client.Asset;

public class Venue {
	
	public static final Logger log=LoggerFactory.getLogger(Venue.class);

	

	public static final Keyword COVIA_KEY = Keyword.intern("covia");
	public static final Keyword ASSETS_KEY = Keyword.intern("assets");
	public static final Keyword JOBS_KEY = Keyword.intern("jobs");
	
	public static final AString JOB_STATUS_FIELD = Strings.intern("status");
	public static final AString JOB_ERROR_FIELD = Strings.intern("error");


	protected final AStore store;
	
	
	protected ACell lattice=Maps.create(COVIA_KEY, Maps.create(ASSETS_KEY,Index.EMPTY));
	
	/**
	 * Map of named adapters that can handle different types of operations or resources
	 */
	protected final HashMap<String, AAdapter> adapters = new HashMap<>();
	
	
	public Venue() throws IOException {
		this(EtchStore.createTemp());
	}


	public Venue(EtchStore store) {
		this.store=store;
	}

	/**
	 * Register an adapter
	 * @param adapter The adapter instance to register
	 */
	public void registerAdapter(AAdapter adapter) {
		String name = adapter.getName();
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


	public Hash storeAsset(ACell meta, ACell content) {
		AString metaString=JSONUtils.toJSONString(meta);
		return storeAsset(metaString,content);
	}

	public Hash storeAsset(String meta, ACell content) {
		return storeAsset(Strings.create(meta),content);
	}
	
	public synchronized Hash storeAsset(AString meta, ACell content) {
		Hash id=Asset.calcID(meta);
		AMap<ABlob,AVector<?>> assets=getAssets();
		boolean exists=assets.containsKey(id);
		log.info((exists?"Updated":"Stored")+" asset "+id);
		
		setAssets(assets.assoc(id, assetRecord(meta,content))); // TODO: asset record design?		
		return id;
	}


	private AVector<ACell> assetRecord(AString meta, ACell content) {
		return Vectors.create(meta,content);
	}


	private void setAssets(AMap<ABlob, AVector<?>> assets) {
		lattice=RT.assocIn(lattice, assets, COVIA_KEY, ASSETS_KEY);
	}


	public AMap<ABlob, AVector<?>> getAssets() {
		// Get assets from lattice
		return RT.ensureMap(RT.getIn(lattice, COVIA_KEY,ASSETS_KEY));
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
	 * @return
	 */
	public AString getMetadata(Hash assetID) {
		AVector<?> arec=getAssets().get(assetID);
		if (arec==null) return null;
		return RT.ensureString(arec.get(0));
	}
	
	/**
	 * Get metadata as a structured value
	 * @param opID
	 * @return Metadata value, or null if not valid metadata
	 */
	private ACell getMetaValue(Hash assetID) {
		AString meta=getMetadata(assetID);
		try {
			return JSONUtils.parseJSON5(meta.toString());
		} catch (Exception e) {
			return null;
		}
	}


	public static void addDemoAssets(Venue venue) {
		String BASE="/asset-examples/";
		try {
			venue.registerAdapter(new TestAdapter());
			venue.registerAdapter(new LangChainAdapter());
			venue.storeAsset(Utils.readResourceAsString(BASE+"qwen.json"),null);
			venue.storeAsset(Utils.readResourceAsString(BASE+"empty.json"),null);
			venue.storeAsset(Utils.readResourceAsString(BASE+"randomop.json"),null);
			venue.storeAsset(Utils.readResourceAsString(BASE+"echoop.json"),null);
			venue.storeAsset(Utils.readResourceAsString(BASE+"randomop.json"),null);
		} catch (IOException e) {
			throw new Error(e);
		}
	}

	public ACell invokeOperation(AString op, ACell input) {
		Hash opID=Hash.parse(op);
		return invokeOperation(opID,input);
	}

	public ACell invokeOperation(Hash opID, ACell input) {
		if (opID==null) throw new IllegalArgumentException("Operation must be a valid Hex asset ID");
		
		ACell meta=getMetaValue(opID);
		if (meta==null) {
			throw new IllegalStateException("Asset does not exist: "+opID);
		}
		
		// Get the combined adapter:operation string from metadata
		ACell adapterCell = RT.getIn(meta, "operation", "adapter");
		if (adapterCell == null) {
			throw new IllegalArgumentException("Operation metadata must specify an adapter");
		}
		String operation = RT.ensureString(adapterCell).toString();
		
		// Extract adapter name from the operation string
		String adapterName = operation.split(":")[0];
		
		// Get the adapter
		AAdapter adapter = getAdapter(adapterName);
		if (adapter == null) {
			AMap<AString,ACell> job = Maps.empty();
			job = job.assoc(JOB_STATUS_FIELD, Status.FAILED);
			job = job.assoc(JOB_ERROR_FIELD, Strings.create("Adapter not available: "+adapterName));
			return job;
		}
		
		AString jobID=submitJob(opID,input);
		
		// Start the async operation
		adapter.invoke(operation, meta,input)
			.thenAccept(result -> {
				AMap<AString,ACell> job = getJobStatus(jobID);
				job = job.assoc(JOB_STATUS_FIELD, Status.COMPLETE);
				job = job.assoc(Strings.create("output"), result);
				setJobStatus(jobID, job);
			})
			.exceptionally(e -> {
				AMap<AString,ACell> job = getJobStatus(jobID);
				job = job.assoc(JOB_STATUS_FIELD, Status.FAILED);
				job = job.assoc(JOB_ERROR_FIELD, Strings.create(e.getMessage()));
				setJobStatus(jobID, job);
				return null;
			});

		return getJobStatus(jobID);
	}

	private void setJobStatus(AString jobID, AMap<AString, ACell> job) {
		synchronized (jobs) {
			jobs.put(jobID,job);
		}
		log.info("Updated job: "+jobID);
	}


	AMap<AString,ACell> getJobStatus(AString jobID) {
		synchronized (jobs) {
			return jobs.get(jobID);
		}
	}


	private HashMap<AString,AMap<AString,ACell>> jobs= new HashMap<>();
	
	/**
	 * Start a job and return its ID
	 * @param opID
	 * @param input
	 * @return Job ID as a string
	 */
	private AString submitJob(Hash opID, ACell input) {
		AString jobID=Strings.create(Blobs.createRandom(16).toHexString());
		setJobStatus(jobID, Maps.of("id",jobID,"status",Status.PENDING));
		
		return jobID;
	}


	public List<AString> getJobs() {
		
		return new ArrayList<>(jobs.keySet());
	}

	
	
}
