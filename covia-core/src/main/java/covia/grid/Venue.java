package covia.grid;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.did.DID;

public abstract class Venue {

	/**
	 * Gets the asset from the connected Venue
	 * @param assetID
	 * @return Asset instance
	 * @throws IOException 
	 */
	public Asset getAsset(String assetID) throws IOException {
		Hash h=Assets.parseAssetID(assetID);
		return getAsset(h);
	}
	
	/**
	 * Gets an  asset from the connected Venue
	 * @param assetID The asset ID
	 * @return Asset instance, or null if does not exist at the target venue
	 * @throws IOException If fetching the asset metadata failed
	 */
	public abstract Asset getAsset(Hash assetID) throws IOException;
	
	/**
	 * Get the DID for this Venue, or null if the Venue has no public DID
	 */
	public abstract DID getDID();

	/**
	 * Invokes an operation on the connected venue, returning a Job
	 * 
	 * @param assetID The AssetID of the operation to invoke
	 * @param input The input parameters for the operation as an ACell
	 * @return Future for the finished Job
	 */
	public abstract CompletableFuture<Job> invoke(Hash assetID, ACell input);

	/**
	 * Invokes an operation specified by a string alias or asset ID.
	 * Default implementation accepts hexadecimal asset IDs.
	 * Concrete subclasses should override if they support aliases.
	 *
	 * @param operation Operation identifier (alias or asset ID)
	 * @param input Operation input
	 * @return Future for the started Job
	 */
	public CompletableFuture<Job> invoke(String operation, ACell input) {
		Hash assetID = Hash.parse(operation);
		if (assetID == null) {
			throw new IllegalArgumentException("Operation must be an asset hash for this venue implementation: " + operation);
		}
		return invoke(assetID, input);
	}

	public CompletableFuture<Job> getJob(String jobId) {
		return getJob(Job.parseID(jobId));
	}

	public abstract CompletableFuture<Job> getJob(AString jobId);

	public CompletableFuture<AMap<AString, ACell>> getJobStatus(String jobId) {
		return getJobStatus(Job.parseID(jobId));
	}

	public abstract CompletableFuture<AMap<AString, ACell>> getJobStatus(AString jobId);

	public CompletableFuture<ACell> awaitJobResult(String jobId) {
		return awaitJobResult(Job.parseID(jobId));
	}

	public abstract CompletableFuture<ACell> awaitJobResult(AString jobId);

	public DID getAssetDID(Hash id) {
		return getDID().withPath("/a/"+id.toHexString());
	}

	protected abstract AContent getAssetContent(Hash id) throws IOException;

	// ------------------------------------------------------------------
	// Asset resolution and registration
	// ------------------------------------------------------------------

	/**
	 * Resolves an asset reference to an Asset. Default implementation handles
	 * hex hash and DID URL formats. Subclasses may add additional resolution
	 * (e.g. operation names).
	 *
	 * @param ref Asset reference string (hex hash, DID URL, or implementation-specific)
	 * @return Resolved Asset, or null if not found
	 */
	public Asset resolveAsset(String ref) throws IOException {
		try {
			Hash h = Assets.parseAssetID(ref);
			return getAsset(h);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Register a new asset at this venue.
	 * @param metadata JSON metadata string
	 * @return The asset ID (SHA256 hash of metadata)
	 */
	public abstract Hash registerAsset(AString metadata) throws IOException;

	/**
	 * Get the total number of assets at this venue.
	 * @return Asset count
	 */
	public abstract long getAssetCount();

	/**
	 * List asset IDs with pagination.
	 * @param offset Starting offset (0-based)
	 * @param limit Maximum number of IDs to return
	 * @return List of asset Hash IDs
	 */
	public abstract List<Hash> listAssetIDs(long offset, long limit);

	// ------------------------------------------------------------------
	// Content operations
	// ------------------------------------------------------------------

	/**
	 * Upload content for an asset. The content hash must match the SHA256
	 * specified in the asset's metadata.
	 *
	 * @param asset Asset to upload content for
	 * @param content Content data as an input stream
	 * @return Hash of the stored content
	 */
	public abstract Hash putAssetContent(Asset asset, InputStream content) throws IOException;

	// ------------------------------------------------------------------
	// Job management
	// ------------------------------------------------------------------

	/**
	 * Cancel a running job.
	 * @param jobId Job identifier
	 * @return Updated job status, or null if job not found
	 */
	public abstract AMap<AString, ACell> cancelJob(AString jobId);

	/**
	 * Delete a job record.
	 * @param jobId Job identifier
	 * @return true if the job was deleted, false if not found
	 */
	public abstract boolean deleteJob(AString jobId);

	/**
	 * List all job IDs at this venue.
	 * @return List of job ID strings
	 */
	public abstract List<AString> listJobs();
}
