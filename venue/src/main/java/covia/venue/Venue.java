package covia.venue;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.crypto.Hashing;
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

public class Venue {
	
	public static final Logger log=LoggerFactory.getLogger(Venue.class);

	

	public static final Keyword COVIA_KEY = Keyword.intern("covia");
	public static final Keyword ASSETS_KEY = Keyword.intern("assets");
	public static final Keyword JOBS_KEY = Keyword.intern("jobs");
	
	public static final AString JOB_STATUS_FIELD = Strings.intern("status");
	public static final AString JOB_ERROR_FIELD = Strings.intern("error");
	public static final AString JOB_PENDING = Strings.intern("PENDING");
	public static final AString JOB_FAILED = Strings.intern("FAILED");


	protected final AStore store;
	
	
	protected ACell lattice=Maps.create(COVIA_KEY, Maps.create(ASSETS_KEY,Index.EMPTY));
	
	
	public Venue() throws IOException {
		this(EtchStore.createTemp());
	}


	public Venue(EtchStore store) {
		this.store=store;
	}


	public Hash storeAsset(ACell meta, ACell content) {
		AString metaString=JSONUtils.toJSONString(meta);
		
		return storeAsset(metaString,content);
	}
	
	public synchronized Hash storeAsset(AString meta, ACell content) {
		Hash id=Hashing.sha256(meta.toBlob().getBytes());
		AMap<ABlob,AVector<?>> assets=getAssets();
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
			return JSONUtils.parse(meta.toString());
		} catch (Exception e) {
			return null;
		}
	}


	public static void addDemoAssets(Venue venue) {
		String BASE="/asset-examples/";
		try {
			venue.storeAsset(Strings.create(Utils.readResourceAsString(BASE+"empty.json")),null);
			venue.storeAsset(Strings.create(Utils.readResourceAsString(BASE+"randomop.json")),null);
		} catch (IOException e) {
			throw new Error(e);
		}
	}


	public ACell invokeOperation(AString op, ACell input) {
		Hash opID=Hash.parse(op);
		if (opID==null) throw new IllegalArgumentException("Operation must be a valid Hex asset ID");
		
		ACell meta=getMetaValue(opID);
		if (meta==null) return null;
		
		AString jobID=submitJob(opID,input);
		
		CompletableFuture.runAsync(()->{
			AMap<AString,ACell> job=getJobStatus(jobID);
			try {
				
			} catch (Exception e) {
				job=job.assoc(JOB_STATUS_FIELD,JOB_FAILED);
				job=job.assoc(JOB_ERROR_FIELD,Strings.create(e.getMessage()));
				setJobStatus(jobID,job);
			}
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
		setJobStatus(jobID, Maps.of("id",jobID,"status",JOB_PENDING));
		
		return jobID;
	}

	
	
}
