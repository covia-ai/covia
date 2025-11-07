package covia.grid.client;

import java.io.IOException;
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
import covia.grid.impl.BlobContent;
import covia.exception.JobFailedException;

public class VenueHTTP extends Venue {
	
	public static Logger log=LoggerFactory.getLogger(VenueHTTP.class);

	private static final double BACKOFF_FACTOR = 1.5;
	private static final long INITIAL_POLL_DELAY = 300; // 1 second initial delay

	private long timeout=5000;
	private final HttpClient httpClient;
	private final URI baseURI;

	public VenueHTTP(URI host) {
		this.baseURI = host.resolve("/api/v1/");
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	}
	
	/**
	 * Gets the base URI for API requests
	 * @return The base URI
	 */
	protected URI getBaseURI() {
		return baseURI;
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
		HttpRequest req = HttpRequest.newBuilder()
			.uri(getBaseURI().resolve("assets"))
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
	 * Gets metadata for a given asset on the connected venue
	 * @param asset ID for asset. Can be a full asset DID
	 * @return Asset metadata as a String, or null if asset is not found
	 */
	public CompletableFuture<String> getMeta(String asset) {
		Hash h=Assets.parseAssetID(asset);
		if (h==null) throw new IllegalArgumentException("Bad asset ID format");
		
		HttpRequest request=HttpRequest.newBuilder()
			.uri(getBaseURI().resolve("assets/"+h.toHexString()))
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
		
		HttpRequest request=HttpRequest.newBuilder()
			.uri(getBaseURI().resolve("status"))
			.GET()
			.build();
		
		return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApplyAsync(response-> {
			int code=response.statusCode();
			if (code!=200) {
				throw new ResponseException("getStatus resturned code: "+code,response);
			}
			return RT.ensureMap(JSON.parse(response.body()));
		});
	}

	/**
	 * Invokes an operation on the connected venue, returning a Job
	 * 
	 * @param assetID The AssetID of the operation to invoke
	 * @param input The input parameters for the operation as an ACell
	 * @return Future for the finished Job
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
		return CompletableFuture.supplyAsync(() -> {
			AMap<AString, ACell> status = getJobData(jobId).join();
			if (status == null) {
				throw new IllegalArgumentException("Job not found: " + jobId);
			}
			Job job = Job.create(status);
			try {
				waitForFinish(job);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new JobFailedException(e);
			}
			return job.awaitResult();
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
		Job job=invoke(opID,input).get(timeout,TimeUnit.MILLISECONDS);
		waitForFinish(job);
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
		Job job=invoke(opID.toCVMHexString(),input).get(timeout,TimeUnit.MILLISECONDS);
		waitForFinish(job);
		return job;
	}
	
	/**
	 * Invokes an operation on the connected venue. Will start polling for status updates.
	 * @param opID The AssetID of the operation to invoke
	 * @param input The input parameters for the operation as an ACell
	 * @return Future containing the job status map
	 */
	public CompletableFuture<Job> invoke(AString opID, ACell input)  {
		CompletableFuture<Job> submit=startJobAsync(opID,input);
		CompletableFuture<Job> start=submit.thenApply(job->{
			try {
				waitForFinish(job);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				job.cancel();
			}
			return job;
		});
		
		return start;
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
		HttpRequest req = HttpRequest.newBuilder()
			.uri(getBaseURI().resolve("invoke"))
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
	 * @return Updates Job in finished state (COMPLETE, FAILED or CANCELLED)
	 * @throws InterruptedException
	 */
	public Job invokeAndWait(AString opID, ACell input) throws InterruptedException {
		CompletableFuture<Job> future = invoke(opID,input);
		Job job=future.join();
		waitForFinish(job);
		return job;
	}
	
	/**
	 * Invokes a Job and waits for it to finish
	 * @param opID Identifier of operation
	 * @param input Input to the operation
	 * @return Updates Job in finished state (COMPLETE, FAILED or CANCELLED)
	 * @throws InterruptedException
	 */
	public Job invokeAndWait(Hash opID, ACell input) throws InterruptedException {
		return invokeAndWait(opID.toCVMHexString(),input);
	}
	
	/**
	 * Invokes an operation on the connected venue
	 * @return Future containing the operation execution result
	 */
	public CompletableFuture<List<Hash>> getAssets() {
		HttpRequest req = HttpRequest.newBuilder()
			.uri(getBaseURI().resolve("assets"))
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
	 * Waits for a remote job to finish, polling
	 * @param job Any Job, presumably not yet finished
	 * @return true if successfully completed, false if failed
	 * @throws InterruptedException if interrupted while waiting
	 */
	public boolean waitForFinish(Job job) throws InterruptedException {
		AString id=job.getID();
		if (id==null) throw new IllegalStateException("Job has no ID");
		long currentDelay = INITIAL_POLL_DELAY;
		while (!job.isFinished()) {
			try {
				updateJobStatus(job);
				if (job.isFinished()) break;
			} catch (ExecutionException | TimeoutException e) {
				throw new ResponseException("Job status polling failed",e);
			}
			Thread.sleep(currentDelay);
			currentDelay = (long) (currentDelay * BACKOFF_FACTOR);
		}
		return job.isComplete();
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
		
		HttpRequest req = HttpRequest.newBuilder()
			.uri(getBaseURI().resolve("assets/" + h.toHexString() + "/content"))
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
				throw new RuntimeException("Failed to parse result: " + response.statusCode() + " - " + response.body());
			} else {
				throw new RuntimeException("Failed to add content: " + response.statusCode() + " - " + response.body());
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
		
		HttpRequest req = HttpRequest.newBuilder()
			.uri(getBaseURI().resolve("assets/" + assetID.toHexString() + "/content"))
			.GET()
			.build();
		
		return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			int code=response.statusCode();
			if (response.statusCode() != 200) {
				throw new RuntimeException("Content get failed with status: " +code+" "+ response.body()+" -- asset ID: "+assetID);
			}
			
			try {
				// Create a BlobContent from the response body
				byte[] data = response.body().getBytes();
				convex.core.data.Blob blob = convex.core.data.Blob.wrap(data);
				return BlobContent.of(blob);
			} catch (Exception e) {
				throw new RuntimeException("Failed to create content from response", e);
			}
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
		HttpRequest req = HttpRequest.newBuilder()
			.uri(getBaseURI().resolve("jobs/" + jobId + "/cancel"))
			.PUT(HttpRequest.BodyPublishers.ofString(""))
			.build();
		
		return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			int code = response.statusCode();
			if (code == 200) {
				return JSON.parse(response.body()); // Success
			} else if (code == 404) {
				throw new RuntimeException("Job not found: " + jobId);
			} else {
				throw new RuntimeException("Failed to cancel job: " + code + " - " + response.body());
			}
		});
	}

	/**
	 * Deletes a job from the connected venue
	 * @param jobId The job ID to delete
	 * @return Future that completes when the job is successfully deleted, or completes exceptionally if the job doesn't exist
	 */
	public CompletableFuture<Void> deleteJob(String jobId) {
		HttpRequest req = HttpRequest.newBuilder()
			.uri(getBaseURI().resolve("jobs/" + jobId + "/delete"))
			.PUT(HttpRequest.BodyPublishers.ofString(""))
			.build();
		
		return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			int code = response.statusCode();
			if (code == 200) {
				return null; // Success
			} else if (code == 404) {
				throw new RuntimeException("Job not found: " + jobId);
			} else {
				throw new RuntimeException("Failed to delete job: " + code + " - " + response.body());
			}
		});
	}

	/**
	 * Gets the status of a job by job ID.
	 * @param jobID The job ID to check
	 * @return Future containing the job status as an AMap, or null if not found
	 */
	public CompletableFuture<AMap<AString, ACell>> getJobData(AString jobID) {
		HttpRequest req = HttpRequest.newBuilder()
			.uri(getBaseURI().resolve("jobs/" + jobID))
			.GET()
			.build();
		return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			int code=response.statusCode();
			if (code == 200) {
				return RT.ensureMap(JSON.parse(response.body()));
			} else if (code == 404) {
				return null;
			} else {
				throw new RuntimeException("Failed to get job status: " + response.statusCode() + " - " + response.body());
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
		HttpRequest request=HttpRequest.newBuilder()
				.uri(getBaseURI().resolve("assets/"+id.toHexString()))
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




}
