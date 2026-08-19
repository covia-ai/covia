package covia.venue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import convex.auth.did.DID;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Strings;
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

	/** Verified UCAN proofs carried into every local request context — set by
	 *  the grid wrapper so a local hop keeps the caller's authority just like a
	 *  remote hop forwards it (covia#100/#102). Null = none. */
	private convex.core.data.AVector<ACell> proofs;

	/**
	 * Complete immutable request context for an in-process hop. A local grid
	 * invocation must preserve the same scoped authority as its caller; rebuilding
	 * a context from only the DID and proofs would turn a restricted agent's
	 * non-null grant scope into unrestricted ({@code null}) authority.
	 */
	private RequestContext requestContext;

	public void setProofs(convex.core.data.AVector<ACell> proofs) {
		this.proofs = proofs;
	}

	/**
	 * Carries a complete caller context across an in-process venue boundary.
	 * RequestContext is immutable, and JobManager derives operation/job scope from
	 * it without mutating this prototype.
	 */
	public void setRequestContext(RequestContext requestContext) {
		this.requestContext = requestContext;
		if (requestContext != null) setUser(requestContext.getCallerDID());
	}

	/** Builds the request context for this venue's user, carrying any authority. */
	private RequestContext context() {
		if (requestContext != null) return requestContext;
		return RequestContext.of(getUser(), proofs);
	}

	@Override
	public Asset getAsset(Hash assetID) {
		Asset asset = engine.getAsset(assetID);
		if (asset!=null) {
			asset.setVenue(this);
			asset.setReference(engine.assetDIDURL(assetID).toString());
		}
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
		RequestContext rctx = context();
		return CompletableFuture.completedFuture(engine.jobs().invokeOperation(assetID.toCVMHexString(), input, rctx));
	}

	@Override
	public CompletableFuture<Job> invoke(String operation, ACell input) {
		if (operation == null) {
			throw new IllegalArgumentException("Operation must not be null");
		}
		try {
			RequestContext rctx = context();
			Job job = engine.jobs().invokeOperation(Strings.create(operation), input, rctx);
			return CompletableFuture.completedFuture(job);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	@Override
	public CompletableFuture<ACell> run(Hash assetID, ACell input) {
		return engine.jobs().runOperation(assetID.toCVMHexString(), input, context());
	}

	@Override
	public CompletableFuture<ACell> run(String operation, ACell input) {
		if (operation == null) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("Operation must not be null"));
		}
		return engine.jobs().runOperation(Strings.create(operation), input, context());
	}

	@Override
	public CompletableFuture<Job> getJob(Blob jobId) {
		Job job = engine.jobs().getJob(jobId, context());
		if (job == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Job not found: " + jobId.toHexString()));
		}
		return CompletableFuture.completedFuture(job);
	}

	@Override
	public CompletableFuture<AMap<AString, ACell>> getJobStatus(Blob jobId) {
		AMap<AString, ACell> status = engine.jobs().getJobData(jobId, context());
		if (status == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Job not found: " + jobId.toHexString()));
		}
		return CompletableFuture.completedFuture(status);
	}

	@Override
	public CompletableFuture<ACell> awaitJobResult(Blob jobId) {
		Job job = engine.jobs().getJob(jobId, context());
		if (job == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Job not found: " + jobId.toHexString()));
		}
		return job.future();
	}

	@Override
	protected AContent getAssetContent(Hash id) throws IOException {
		// The universal resolver serves every content form identically — inline,
		// content-store blob, per-record POS_CONTENT, dlfs. Both the REST
		// /assets/{id}/content endpoint and the client Asset.getContent() reach
		// here, so routing through it (rather than the blob-only getContent) is
		// what makes inline content fetchable over HTTP (covia#289). The Hash
		// overload denotes LocalVenue.getAsset(Hash), i.e. the published venue
		// catalog, so qualify it explicitly instead of inheriting a caller context.
		covia.venue.storage.ContentProvider.Resolved resolved =
			engine.resolveContent(engine.assetDIDURL(id), engine.venueContext());
		return (resolved == null) ? null : resolved.content();
	}

	@Override
	protected AContent getAssetContent(String ref) throws IOException {
		RequestContext rctx = (requestContext == null && getUser() == null)
			? engine.venueContext() : context();
		covia.venue.storage.ContentProvider.Resolved resolved =
			engine.resolveContent(Strings.create(ref), rctx);
		return (resolved == null) ? null : resolved.content();
	}

	// ------------------------------------------------------------------
	// Asset resolution and registration
	// ------------------------------------------------------------------

	@Override
	public Asset resolveAsset(String ref) {
		Asset asset = engine.resolveAsset(Strings.create(ref), context());
		if (asset != null) {
			asset.setVenue(this);
			asset.setReference(ref);
		}
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
		return engine.jobs().cancelJob(jobId, context());
	}

	@Override
	public AMap<AString, ACell> pauseJob(Blob jobId) {
		return engine.jobs().pauseJob(jobId, context());
	}

	@Override
	public AMap<AString, ACell> resumeJob(Blob jobId) {
		return engine.jobs().resumeJob(jobId, context());
	}

	@Override
	public boolean deleteJob(Blob jobId) {
		return engine.jobs().deleteJob(jobId, context());
	}

	@Override
	public List<Blob> listJobs() {
		Index<Blob, ACell> jobs = engine.jobs().getJobs(context());
		long n = jobs.count();
		List<Blob> result = new ArrayList<>((int) n);
		for (long i = 0; i < n; i++) {
			result.add((Blob) jobs.entryAt(i).getKey());
		}
		return result;
	}

	@Override
	public int sendMessage(String jobId, AMap<AString, ACell> message) {
		return engine.jobs().deliverMessage(Blob.parse(jobId), message, context());
	}
}
