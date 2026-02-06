package covia.grid.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.prim.CVMLong;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.did.DID;
import covia.api.Fields;
import covia.exception.ConversionException;
import covia.exception.ResponseException;
import covia.grid.AContent;
import covia.grid.Asset;
import covia.grid.Assets;
import covia.grid.Job;
import covia.grid.Venue;
import covia.grid.auth.VenueAuth;
import covia.grid.impl.BlobContent;

public class VenueHTTP extends Venue {

	public static Logger log=LoggerFactory.getLogger(VenueHTTP.class);

	private static final double BACKOFF_FACTOR = 1.5;
	private static final long INITIAL_POLL_DELAY = 300;
	private static final long MAX_POLL_DELAY = 10000;
	private static final long DEFAULT_TIMEOUT = 600000; // 10 minutes, allows for slow LLM responses

	private long timeout=DEFAULT_TIMEOUT;
	private final HttpClient httpClient;
	private final URI baseURI;
	private final VenueAuth auth;

	public VenueHTTP(URI host) {
		this(host, VenueAuth.none());
	}

	public VenueHTTP(URI host, VenueAuth auth) {
		this.baseURI = host.resolve("/api/v1/");
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
		this.auth = auth;

		// Auto-set user identity from auth if available
		String did = auth.getDID();
		if (did != null) {
			setUser(did);
		}
	}
	
	/**
	 * Gets the base URI for API requests
	 * @return The base URI
	 */
	protected URI getBaseURI() {
		return baseURI;
	}

	/**
	 * Create a new HTTP request builder with auth headers applied.
	 * @param uri The request URI
	 * @return Pre-authenticated request builder
	 */
	private HttpRequest.Builder requestBuilder(URI uri) {
		HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri);
		auth.apply(builder);
		return builder;
	}

	/**
	 * Create a new HTTP request builder for an API path with auth headers applied.
	 * @param path The API path relative to the base URI
	 * @return Pre-authenticated request builder
	 */
	private HttpRequest.Builder requestBuilder(String path) {
		return requestBuilder(getBaseURI().resolve(path));
	}

	/**
	 * Adds an asset to the connected venue
	 * 
	 * @param jsonMeta JSON metadata string for the asset
	 */
	public CompletableFuture<Hash> addAsset(String jsonMeta) {
		ACell meta=JSON.parseJSON5(jsonMeta);
		return addAsset(meta);
	}
	
	/**
	 * Adds an asset to the connected venue
	 */
	public CompletableFuture<Hash> addAsset(ACell meta) {
		HttpRequest req = requestBuilder("assets")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(JSON.printPretty(meta).toString()))
			.build();
		
		return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApplyAsync(response -> {
			if (response.statusCode() != 201) {
				throw new ResponseException("Failed to add asset: " + response.statusCode() + " - " + response.body(), response);
			}
			ACell body=JSON.parseJSON5(response.body());
			Hash v=Hash.parse(body);
			if (v!=null) return v;
			throw new ConversionException("Result did not contain a valid Hash: got "+body); 
		});
	} 

	/**
	 * Create a Covia grid client using the given venue URI
	 * @param host Host Venue
	 * @return Covia client instance
	 */
	public static VenueHTTP create(URI host) {
		return new VenueHTTP(host);
	}

	/**
	 * Create a Covia grid client with authentication
	 * @param host Host Venue
	 * @param auth Authentication provider
	 * @return Covia client instance
	 */
	public static VenueHTTP create(URI host, VenueAuth auth) {
		return new VenueHTTP(host, auth);
	}

	/**
	 * Gets metadata for a given asset on the connected venue
	 * @param asset ID for asset. Can be a full asset DID
	 * @return Asset metadata as a String, or null if asset is not found
	 */
	public CompletableFuture<String> getMeta(String asset) {
		Hash h=Assets.parseAssetID(asset);
		if (h==null) throw new IllegalArgumentException("Bad asset ID format");
		
		HttpRequest request=requestBuilder("assets/"+h.toHexString())
			.GET()
			.build();
		
		return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApplyAsync(response-> {
			if (response.statusCode()!=200) return null;
			return response.body();
		});
	}
	
	/**
	 * Gets status of connected venue
	 * @return Status map
	 */
	public CompletableFuture<AMap<AString,ACell>> getStatus() {
		
		HttpRequest request=requestBuilder("status")
			.GET()
			.build();
		
		return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApplyAsync(response-> {
			int code=response.statusCode();
			if (code!=200) {
				throw new ResponseException("getStatus returned code: "+code,response);
			}
			return RT.ensureMap(JSON.parse(response.body()));
		});
	}

	/**
	 * Invokes an operation on the connected venue, returning a submitted Job.
	 * The returned future completes when the job is submitted (typically PENDING).
	 * Callers can use {@code job.future()} or {@code job.awaitResult()} to wait for completion.
	 *
	 * @param assetID The AssetID of the operation to invoke
	 * @param input The input parameters for the operation as an ACell
	 * @return Future for the submitted Job (likely PENDING)
	 */
	@Override
	public CompletableFuture<Job> invoke(String assetID, ACell input)  {
		AString opID=Strings.create(assetID);
		return invoke(opID,input);
	}

	@Override
	public CompletableFuture<Job> getJob(AString jobId) {
		return getJobData(jobId).thenApply(status -> {
			if (status == null) {
				throw new IllegalArgumentException("Job not found: " + jobId);
			}
			return Job.create(status);
		});
	}

	@Override
	public CompletableFuture<AMap<AString, ACell>> getJobStatus(AString jobId) {
		return getJobData(jobId).thenApply(status -> {
			if (status == null) {
				throw new IllegalArgumentException("Job not found: " + jobId);
			}
			return status;
		});
	}

	@Override
	public CompletableFuture<ACell> awaitJobResult(AString jobId) {
		return getJob(jobId).thenCompose(job -> {
			startBackgroundPolling(job);
			return job.future();
		});
	}

	@Override
	public CompletableFuture<Job> invoke(Hash assetID, ACell input)  {
		return invoke(assetID.toCVMHexString(),input);
	}
	
	/**
	 * Invokes an operation, returning a finished Job once complete
	 * @param opID Operation to invoke 
	 * @param input
	 * @return Finished Job
	 * @throws TimeoutException 
	 * @throws ExecutionException 
	 * @throws InterruptedException 
	 */
	public Job invokeSync(String opID, ACell input) throws InterruptedException, ExecutionException, TimeoutException {
		Job job=startJob(Strings.create(opID), input);
		waitForFinish(job);
		return job;
	}

	/**
	 * Invokes an operation, returning a finished Job once complete
	 * @param opID Operation to invoke
	 * @param input Input parameters
	 * @param timeoutMs Maximum time to wait in milliseconds
	 * @return Finished Job
	 * @throws TimeoutException
	 * @throws ExecutionException
	 * @throws InterruptedException
	 */
	public Job invokeSync(String opID, ACell input, long timeoutMs) throws InterruptedException, ExecutionException, TimeoutException {
		Job job=startJob(Strings.create(opID), input);
		waitForFinish(job, timeoutMs);
		return job;
	}

	/**
	 * Invokes an operation, returning a finished Job once complete
	 * @param opID Operation to invoke as an Asset ID or adapter operation alias
	 * @param input
	 * @return Finished Job
	 * @throws TimeoutException
	 * @throws ExecutionException
	 * @throws InterruptedException
	 */
	public Job invokeSync(Hash opID, ACell input) throws InterruptedException, ExecutionException, TimeoutException {
		Job job=startJob(opID, input);
		waitForFinish(job);
		return job;
	}

	/**
	 * Invokes an operation, returning a finished Job once complete
	 * @param opID Operation to invoke as an Asset ID
	 * @param input Input parameters
	 * @param timeoutMs Maximum time to wait in milliseconds
	 * @return Finished Job
	 * @throws TimeoutException
	 * @throws ExecutionException
	 * @throws InterruptedException
	 */
	public Job invokeSync(Hash opID, ACell input, long timeoutMs) throws InterruptedException, ExecutionException, TimeoutException {
		Job job=startJob(opID, input);
		waitForFinish(job, timeoutMs);
		return job;
	}
	
	/**
	 * Invokes an operation on the connected venue. Submits the job and starts
	 * background polling on a virtual thread. The returned future completes when
	 * the job is submitted (typically PENDING). Callers can use {@code job.future()}
	 * or {@code job.awaitResult()} to wait for completion.
	 * @param opID The AssetID of the operation to invoke
	 * @param input The input parameters for the operation as an ACell
	 * @return Future containing the submitted Job (likely PENDING)
	 */
	public CompletableFuture<Job> invoke(AString opID, ACell input)  {
		return startJobAsync(opID, input).thenApply(job -> {
			startBackgroundPolling(job);
			return job;
		});
	}
	
	/**
	 * Starts a operation on the Grid. Does not start polling.
	 * @param opID Operation ID
	 * @param input Operation input
	 * @return Job instance (likely to be PENDING)
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws TimeoutException
	 */
	public Job startJob(AString opID, ACell input) throws InterruptedException, ExecutionException, TimeoutException {
		return startJobAsync(opID,input).get(5,TimeUnit.SECONDS);
	}
	
	/**
	 * Starts a operation on the Grid. Does not start polling.
	 * @param opID Operation ID
	 * @param input Operation input
	 * @return Job instance (likely to be PENDING)
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws TimeoutException
	 */
	public Job startJob(Hash opID, ACell input) throws InterruptedException, ExecutionException, TimeoutException {
		return startJobAsync(opID.toCVMHexString(),input).get(5,TimeUnit.SECONDS);
	}
	
	/**
	 * Starts an operation on the connected venue. Does not start polling.
	 * @param opID The Asset ID of the operation to invoke
	 * @param input The input parameters for the operation as an ACell
	 * @return Future containing the job status, likely to be PENDING
	 */
	public CompletableFuture<Job> startJobAsync(AString opID, ACell input) {
		HttpRequest req = requestBuilder("invoke")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(JSON.toString(Maps.of(
				"operation", opID,
				"input", input
			))))
			.build();
		
		return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			if (response.statusCode() != 201) {
				throw new ResponseException("Failed to start operation: " + response+" = "+response.body(),response);
			}
			AMap<AString,ACell> body=RT.ensureMap(JSON.parseJSON5(response.body()));
			if (body==null) {
				throw new ResponseException("Invalid response body",response);
			}
			return Job.create(body);
		});
	}
	
	/**
	 * Invokes a Job and waits for it to finish
	 * @param opID Identifier of operation
	 * @param input Input to the operation
	 * @return Job in finished state (COMPLETE, FAILED or CANCELLED)
	 * @throws InterruptedException
	 */
	public Job invokeAndWait(AString opID, ACell input) throws InterruptedException {
		Job job=startJobAsync(opID,input).join();
		waitForFinish(job);
		return job;
	}

	/**
	 * Invokes a Job and waits for it to finish
	 * @param opID Identifier of operation
	 * @param input Input to the operation
	 * @param timeoutMs Maximum time to wait in milliseconds
	 * @return Job in finished state (COMPLETE, FAILED or CANCELLED)
	 * @throws InterruptedException
	 */
	public Job invokeAndWait(AString opID, ACell input, long timeoutMs) throws InterruptedException {
		Job job=startJobAsync(opID,input).join();
		waitForFinish(job, timeoutMs);
		return job;
	}

	/**
	 * Invokes a Job and waits for it to finish
	 * @param opID Identifier of operation
	 * @param input Input to the operation
	 * @return Job in finished state (COMPLETE, FAILED or CANCELLED)
	 * @throws InterruptedException
	 */
	public Job invokeAndWait(Hash opID, ACell input) throws InterruptedException {
		return invokeAndWait(opID.toCVMHexString(),input);
	}

	/**
	 * Invokes a Job and waits for it to finish
	 * @param opID Identifier of operation
	 * @param input Input to the operation
	 * @param timeoutMs Maximum time to wait in milliseconds
	 * @return Job in finished state (COMPLETE, FAILED or CANCELLED)
	 * @throws InterruptedException
	 */
	public Job invokeAndWait(Hash opID, ACell input, long timeoutMs) throws InterruptedException {
		return invokeAndWait(opID.toCVMHexString(), input, timeoutMs);
	}
	
	/**
	 * Invokes an operation on the connected venue
	 * @return Future containing the operation execution result
	 */
	public CompletableFuture<List<Hash>> getAssets() {
		HttpRequest req = requestBuilder("assets")
			.GET()
			.build();
		
		return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			int code=response.statusCode();
			if (code != 200) {
				throw new ConversionException("assets API returned status: "+code);
			}
			ACell body=JSON.parseJSON5(response.body());
			AVector<?> items=null;
			if (body instanceof AVector v) {
				// Support for legacy API version that returned a list of asset IDs
				items=v;
			} else if (body instanceof AMap m) {
				items=RT.ensureVector(m.get(Fields.ITEMS));
			} 
			
			if (items==null) {
				throw new ConversionException("assets API did not return a list of assets");
			}
			
			ArrayList<Hash> al=new ArrayList<>();
			long n=items.count();
			for (int i=0; i<n; i++) {
				al.add(Hash.parse(items.get(i)));
			}
			return al;
			
		});
		

	}
	
	/**
	 * Starts background polling for job completion on a virtual thread.
	 * When polling detects the job is finished, the Job's lazy future is completed,
	 * unblocking any callers awaiting {@code job.future()} or {@code job.awaitResult()}.
	 * @param job Job to poll for
	 */
	private void startBackgroundPolling(Job job) {
		if (job.isFinished()) return;
		Thread.ofVirtual().start(() -> {
			try {
				waitForFinish(job);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				job.cancel();
			}
		});
	}

	/**
	 * Waits for a remote job to finish, polling with exponential backoff.
	 * Uses the default timeout configured via {@link #setTimeout}.
	 *
	 * <p><b>Timeout semantics:</b> This is a <em>client-side</em> polling timeout only.
	 * The remote job continues running on the venue regardless of whether this
	 * client times out. After a timeout, callers can re-acquire the latest job
	 * status by calling {@link #getJobStatus(AString)} with the job ID — the job
	 * may have completed, failed, or still be running.
	 *
	 * @param job Any Job, presumably not yet finished
	 * @return true if successfully completed, false if failed
	 * @throws InterruptedException if interrupted while waiting
	 * @throws ResponseException if polling times out (job may still be running remotely)
	 */
	public boolean waitForFinish(Job job) throws InterruptedException {
		return waitForFinish(job, timeout);
	}

	/**
	 * Waits for a remote job to finish, polling with exponential backoff.
	 *
	 * <p><b>Timeout semantics:</b> This is a <em>client-side</em> polling timeout only.
	 * The remote job continues running on the venue regardless of whether this
	 * client times out. After a timeout, callers can re-acquire the latest job
	 * status by calling {@link #getJobStatus(AString)} with the job ID — the job
	 * may have completed, failed, or still be running.
	 *
	 * @param job Any Job, presumably not yet finished
	 * @param timeoutMs Maximum time to wait in milliseconds
	 * @return true if successfully completed, false if failed or timed out
	 * @throws InterruptedException if interrupted while waiting
	 * @throws ResponseException if polling times out (job may still be running remotely)
	 */
	public boolean waitForFinish(Job job, long timeoutMs) throws InterruptedException {
		AString id=job.getID();
		if (id==null) throw new IllegalStateException("Job has no ID");
		long currentDelay = INITIAL_POLL_DELAY;
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!job.isFinished()) {
			if (System.currentTimeMillis() > deadline) {
				throw new ResponseException("Job polling timed out after "+timeoutMs+"ms for job: "+id);
			}
			try {
				updateJobStatus(job);
				if (job.isFinished()) break;
			} catch (ExecutionException | TimeoutException e) {
				throw new ResponseException("Job status polling failed", (Throwable) e);
			}
			Thread.sleep(currentDelay);
			currentDelay = Math.min((long) (currentDelay * BACKOFF_FACTOR), MAX_POLL_DELAY);
		}
		return job.isComplete();
	}

	/**
	 * Sets the timeout in milliseconds for polling operations like {@link #waitForFinish}.
	 * @param timeoutMs Timeout in milliseconds
	 */
	public void setTimeout(long timeoutMs) {
		this.timeout = timeoutMs;
	}

	/**
	 * Gets the timeout in milliseconds for polling operations.
	 * @return Timeout in milliseconds
	 */
	public long getTimeout() {
		return timeout;
	}

	/**
	 * Adds content to an asset using the PUT API endpoint.
	 * 
	 * @param assetID The asset ID to add content to
	 * @param content The content to add
	 * @return Future that completes when the content is successfully added
	 * @throws IOException If not possible to obtain the content
	 */
	public CompletableFuture<Hash> addContent(String assetID, AContent content) throws IOException {
		Hash h = Assets.parseAssetID(assetID);
		if (h == null) throw new IllegalArgumentException("Bad asset ID format");
		
		HttpRequest req = requestBuilder("assets/" + h.toHexString() + "/content")
			.header("Content-Type", "application/octet-stream")
			.PUT(HttpRequest.BodyPublishers.ofByteArray(content.getBlob().getBytes()))
			.build();
		
		return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApplyAsync(response -> {
			if (response.statusCode() == 200 || response.statusCode() == 201) {
				ACell json=JSON.parse(response.body());
				if (json instanceof AString hashString) {
					Hash hash=Hash.parse(hashString);
					if (hash!=null) {
						return hash;
					}
				}
				throw new ConversionException("Failed to parse content hash from response: " + response.body());
			} else {
				throw new ResponseException("Failed to add content: " + response.statusCode() + " - " + response.body(), response);
			}
		});
	
	}
	
	/**
	 * Gets content for an asset using the GET API endpoint.
	 * 
	 * @param assetID The asset ID to get content for
	 * @return Future containing the content as an AContent, or null if not found
	 */
	public CompletableFuture<AContent> getContent(String assetID) {
		Hash id = Assets.parseAssetID(assetID);
		return getContent(id);
	}
	
	/**
	 * Gets content for an asset using the GET API endpoint.
	 * 
	 * @param assetID The asset ID to get content for
	 * @return Future containing the content as an AContent, or null if not found
	 */
	public CompletableFuture<AContent> getContent(Hash assetID) {

		HttpRequest req = requestBuilder("assets/" + assetID.toHexString() + "/content")
			.GET()
			.build();

		return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).thenApply(response -> {
			int code=response.statusCode();
			if (code != 200) {
				throw new ResponseException("Content get failed with status: " +code+" -- asset ID: "+assetID);
			}

			byte[] data = response.body();
			convex.core.data.Blob blob = convex.core.data.Blob.wrap(data);
			return BlobContent.of(blob);
		});
	}
	
	@Override
	protected AContent getAssetContent(Hash assetID) {
		return getContent(assetID).join();
	}

	/**
	 * Cancels a job on the connected venue
	 * @param jobId The job ID to cancel
	 * @return Future that completes when the job is successfully cancelled, or completes exceptionally if the job doesn't exist
	 */
	public CompletableFuture<AMap<AString,ACell>> cancelJob(String jobId) {
		HttpRequest req = requestBuilder("jobs/" + jobId + "/cancel")
			.PUT(HttpRequest.BodyPublishers.ofString(""))
			.build();
		
		return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			int code = response.statusCode();
			if (code == 200) {
				return JSON.parse(response.body()); // Success
			} else if (code == 404) {
				throw new ResponseException("Job not found: " + jobId, response);
			} else {
				throw new ResponseException("Failed to cancel job: " + code + " - " + response.body(), response);
			}
		});
	}

	/**
	 * Deletes a job from the connected venue
	 * @param jobId The job ID to delete
	 * @return Future that completes when the job is successfully deleted, or completes exceptionally if the job doesn't exist
	 */
	public CompletableFuture<Void> deleteJob(String jobId) {
		HttpRequest req = requestBuilder("jobs/" + jobId + "/delete")
			.PUT(HttpRequest.BodyPublishers.ofString(""))
			.build();
		
		return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			int code = response.statusCode();
			if (code == 200) {
				return null; // Success
			} else if (code == 404) {
				throw new ResponseException("Job not found: " + jobId, response);
			} else {
				throw new ResponseException("Failed to delete job: " + code + " - " + response.body(), response);
			}
		});
	}

	/**
	 * Gets the status of a job by job ID.
	 * @param jobID The job ID to check
	 * @return Future containing the job status as an AMap, or null if not found
	 */
	public CompletableFuture<AMap<AString, ACell>> getJobData(AString jobID) {
		HttpRequest req = requestBuilder("jobs/" + jobID)
			.GET()
			.build();
		return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			int code=response.statusCode();
			if (code == 200) {
				return RT.ensureMap(JSON.parse(response.body()));
			} else if (code == 404) {
				return null;
			} else {
				throw new ResponseException("Failed to get job status: " + response.statusCode() + " - " + response.body(), response);
			}
		});
	}
	
	/**
	 * Gets the status of a job by job ID.
	 * @param jobID The job ID to check
	 * @return Future containing the job status as an AMap, or null if not found
	 */
	public CompletableFuture<AMap<AString, ACell>> getJobData(Object jobID) {
		return getJobData(Job.parseID(jobID));
	}
	
	/**
	 * Perform a remote status update for the given job
	 * @param job Job to update
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws TimeoutException
	 */
	public void updateJobStatus(Job job) throws InterruptedException, ExecutionException, TimeoutException {
		AString jobID=job.getID();
		CompletableFuture<AMap<AString, ACell>> future = getJobData(jobID).thenApply(data->{
			job.updateData(data);
			// System.out.println(JSONUtils.toJSONPretty(data));
			return data;
		});
		future.get(5,TimeUnit.SECONDS);
	}

	@Override
	public Asset getAsset(Hash id) throws IOException {
		HttpRequest request=requestBuilder("assets/"+id.toHexString())
				.GET()
				.build();
			
		HttpResponse<String> response;
		try {
			response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode()!=200) return null;
			AString metadata=Strings.create(response.body());
			
			Asset asset=Asset.forString(metadata);
			asset.setVenue(this);
			return asset;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		
	}

	@Override
	public DID getDID() {
		return DID.create("web",baseURI.getHost());
	}

	// ------------------------------------------------------------------
	// Abstract Venue method implementations
	// ------------------------------------------------------------------

	@Override
	public Hash registerAsset(AString metadata) throws IOException {
		return addAsset(metadata.toString()).join();
	}

	@Override
	public long getAssetCount() {
		return getAssets().join().size();
	}

	@Override
	public List<Hash> listAssetIDs(long offset, long limit) {
		List<Hash> all = getAssets().join();
		int start = (int) Math.max(0, offset);
		int end = (int) Math.min(all.size(), start + limit);
		if (start >= all.size()) return List.of();
		return all.subList(start, end);
	}

	@Override
	public Hash putAssetContent(Asset asset, InputStream content) throws IOException {
		byte[] data = content.readAllBytes();
		convex.core.data.Blob blob = convex.core.data.Blob.wrap(data);
		AContent acontent = BlobContent.of(blob);
		return addContent(asset.getID().toHexString(), acontent).join();
	}

	@Override
	public AMap<AString, ACell> cancelJob(AString jobId) {
		return cancelJob(jobId.toString()).join();
	}

	@Override
	public boolean deleteJob(AString jobId) {
		try {
			deleteJob(jobId.toString()).join();
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	@Override
	public List<AString> listJobs() {
		HttpRequest req = requestBuilder("jobs")
			.GET()
			.build();

		try {
			HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new ResponseException("Failed to list jobs: " + response.statusCode());
			}
			ACell body = JSON.parseJSON5(response.body());
			AVector<?> items = null;
			if (body instanceof AVector<?> v) {
				items = v;
			} else if (body instanceof AMap<?,?> m) {
				items = RT.ensureVector(m.get(Fields.ITEMS));
			}
			ArrayList<AString> result = new ArrayList<>();
			if (items != null) {
				for (long i = 0; i < items.count(); i++) {
					result.add(RT.ensureString(items.get(i)));
				}
			}
			return result;
		} catch (IOException | InterruptedException e) {
			throw new ResponseException("Failed to list jobs", e);
		}
	}

	@Override
	public int sendMessage(String jobId, AMap<AString, ACell> message) {
		HttpRequest req = requestBuilder("jobs/" + jobId)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(JSON.toString(message)))
			.build();

		try {
			HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
			int code = response.statusCode();
			if (code == 202) {
				AMap<AString, ACell> body = RT.ensureMap(JSON.parseJSON5(response.body()));
				if (body != null) {
					ACell depth = body.get(Strings.create("queueDepth"));
					CVMLong cl = RT.ensureLong(depth);
					if (cl != null) return (int) cl.longValue();
				}
				return 0;
			} else if (code == 404) {
				throw new IllegalArgumentException("Job not found: " + jobId);
			} else if (code == 409) {
				throw new IllegalStateException("Job is in terminal state: " + jobId);
			}
			throw new ResponseException("Failed to send message: HTTP " + code);
		} catch (IOException | InterruptedException e) {
			throw new ResponseException("Failed to send message to job " + jobId, e);
		}
	}
}
