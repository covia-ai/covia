package covia.venue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.did.DID;
import covia.grid.AContent;
import covia.grid.Asset;
import covia.grid.Job;
import covia.grid.Venue;

public class LocalVenue extends Venue {

	private Engine engine;

	public LocalVenue(Engine e) {
		this.engine=e;
	}

	public static LocalVenue create(Engine e) {
		return new LocalVenue(e);
	}

	@Override
	public Asset getAsset(Hash assetID) {
		Asset asset = engine.getAsset(assetID);
		if (asset!=null) asset.setVenue(this);
		return asset;
	}

	public Engine getEngine() {
		return engine;
	}

	@Override
	public DID getDID() {
		return engine.getDID();
	}

	@Override
	public CompletableFuture<Job> invoke(Hash assetID, ACell input) {
		RequestContext rctx = RequestContext.of(getUser());
		return CompletableFuture.completedFuture(engine.invokeOperation(assetID.toCVMHexString(), input, rctx));
	}

	@Override
	public CompletableFuture<Job> invoke(String operation, ACell input) {
		if (operation == null) {
			throw new IllegalArgumentException("Operation must not be null");
		}
		RequestContext rctx = RequestContext.of(getUser());
		Job job = engine.invokeOperation(Strings.create(operation), input, rctx);
		return CompletableFuture.completedFuture(job);
	}

	@Override
	public CompletableFuture<Job> getJob(Blob jobId) {
		Job job = engine.getJob(jobId);
		if (job == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Job not found: " + jobId.toHexString()));
		}
		return CompletableFuture.completedFuture(job);
	}

	@Override
	public CompletableFuture<AMap<AString, ACell>> getJobStatus(Blob jobId) {
		AMap<AString, ACell> status = engine.getJobData(jobId);
		if (status == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Job not found: " + jobId.toHexString()));
		}
		return CompletableFuture.completedFuture(status);
	}

	@Override
	public CompletableFuture<ACell> awaitJobResult(Blob jobId) {
		Job job = engine.getJob(jobId);
		if (job == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Job not found: " + jobId.toHexString()));
		}
		return job.future();
	}

	@Override
	protected AContent getAssetContent(Hash id) throws IOException {
		return engine.getContent(id);
	}

	// ------------------------------------------------------------------
	// Asset resolution and registration
	// ------------------------------------------------------------------

	@Override
	public Asset resolveAsset(String ref) {
		Asset asset = engine.resolveAsset(Strings.create(ref));
		if (asset != null) asset.setVenue(this);
		return asset;
	}

	@Override
	public Hash registerAsset(AString metadata) {
		return engine.storeAsset(metadata, null);
	}

	@Override
	public long getAssetCount() {
		return engine.getAssets().count();
	}

	@Override
	public List<Hash> listAssetIDs(long offset, long limit) {
		AMap<ABlob, AVector<?>> allAssets = engine.getAssets();
		long n = allAssets.count();
		long start = Math.max(0, offset);
		long end = Math.min(n, start + limit);
		ArrayList<Hash> result = new ArrayList<>();
		for (long i = start; i < end; i++) {
			result.add(Hash.wrap(allAssets.entryAt(i).getKey().getBytes()));
		}
		return result;
	}

	// ------------------------------------------------------------------
	// Content operations
	// ------------------------------------------------------------------

	@Override
	public Hash putAssetContent(Asset asset, InputStream content) throws IOException {
		return engine.putContent(asset, content);
	}

	// ------------------------------------------------------------------
	// Job management
	// ------------------------------------------------------------------

	@Override
	public AMap<AString, ACell> cancelJob(Blob jobId) {
		return engine.cancelJob(jobId, RequestContext.of(getUser()));
	}

	@Override
	public AMap<AString, ACell> pauseJob(Blob jobId) {
		return engine.pauseJob(jobId, RequestContext.of(getUser()));
	}

	@Override
	public AMap<AString, ACell> resumeJob(Blob jobId) {
		return engine.resumeJob(jobId, RequestContext.of(getUser()));
	}

	@Override
	public boolean deleteJob(Blob jobId) {
		return engine.deleteJob(jobId, RequestContext.of(getUser()));
	}

	@Override
	public List<Blob> listJobs() {
		Index<Blob, ACell> jobs = engine.getJobs(RequestContext.of(getUser()));
		long n = jobs.count();
		List<Blob> result = new ArrayList<>((int) n);
		for (long i = 0; i < n; i++) {
			result.add((Blob) jobs.entryAt(i).getKey());
		}
		return result;
	}

	@Override
	public int sendMessage(String jobId, AMap<AString, ACell> message) {
		return engine.deliverMessage(Blob.parse(jobId), message, RequestContext.of(getUser()));
	}
}
