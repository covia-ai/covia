package covia.grid.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.exceptions.TODOException;
import convex.core.lang.RT;
import convex.core.util.JSONUtils;
import convex.java.ARESTClient;
import convex.java.HTTPClients;
import covia.api.Fields;
import covia.exception.ConversionException;
import covia.exception.ResponseException;
import covia.grid.Assets;
import covia.grid.Job;
import covia.venue.storage.AContent;

public class Covia extends ARESTClient  {
	
	public static Logger log=LoggerFactory.getLogger(Covia.class);;

	private static final double BACKOFF_FACTOR = 1.5;
	private static final long INITIAL_POLL_DELAY = 300; // 1 second initial delay

	public Covia(URI host) {
		super(host,"/api/v1/");
	}

	/**
	 * Adds an asset to the connected venue
	 */
	public CompletableFuture<Hash> addAsset(String jsonString) {
		ACell meta=JSONUtils.parseJSON5(jsonString);
		return addAsset(meta);
	}
	
	/**
	 * Adds an asset to the connected venue
	 */
	public CompletableFuture<Hash> addAsset(ACell meta) {
		SimpleHttpRequest req=SimpleHttpRequest.create(Method.POST, getBaseURI().resolve("assets"));
		req.setBody(JSONUtils.toJSONPretty(meta).toString(), ContentType.APPLICATION_JSON);
		return doRequest(req).thenApplyAsync(r->{
			if (r.isError()) {
				throw new Error("Asset add failed "+r.getValue());
			}
			Hash v=Hash.parse(r.getValue());
			if (v!=null) return v;
			throw new ConversionException("Result did not contain a valid Hash"); 
		});
	} 

	/**
	 * Create a Covia grid client using the given venue URI
	 * @param host Host Venue
	 * @return Covia client instance
	 */
	public static Covia create(URI host) {
		return new Covia(host);
	}

	/**
	 * Gets metadata for a given asset on the connected venue
	 * @param asset ID for asset. Can be a full asset DID
	 * @return Asset metadata as a String, or null if asset is not found
	 */
	public CompletableFuture<String> getMeta(String asset) {
		Hash h=Assets.parseAssetID(asset);
		if (h==null) throw new IllegalArgumentException("Bad asset ID format");
		
		
		SimpleHttpRequest request=SimpleHttpRequest.create(Method.GET, getBaseURI().resolve("assets/"+h.toHexString()));
		CompletableFuture<SimpleHttpResponse> future=HTTPClients.execute(request);
		
		return future.thenApplyAsync(response-> {
			if (response.getCode()!=200) return null;
			return response.getBodyText();
		});
	}
	
	/**
	 * Gets status of connected venue
	 * @return Status map
	 */
	public CompletableFuture<AMap<AString,ACell>> getStatus() {
		
		SimpleHttpRequest request=SimpleHttpRequest.create(Method.GET, getBaseURI().resolve("status"));
		CompletableFuture<SimpleHttpResponse> future=HTTPClients.execute(request);
		
		return future.thenApplyAsync(response-> {
			int code=response.getCode();
			if (code!=200) {
				throw new ResponseException("getStatus resturned code: "+code,response);
			}
			return RT.ensureMap(JSONUtils.parse(response.getBodyText()));
		});
	}

	/**
	 * Invokes an operation on the connected venue
	 * @param assetID The AssetID of the operation to invoke
	 * @param input The input parameters for the operation as an ACell
	 * @return Future containing the job status map
	 */
	public CompletableFuture<Job> invoke(String assetID, ACell input) {
		Hash opID=Assets.parseAssetID(assetID);
		return invoke(opID,input);
	}
	
	/**
	 * Invokes an operation, returning a finished Job once complete
	 * @param opID Operation to invoke 
	 * @param input
	 * @return
	 */
	public Job invokeSync(String opID, ACell input) {
		throw new TODOException();
	}
	
	/**
	 * Invokes an operation on the connected venue
	 * @param assetID The AssetID of the operation to invoke
	 * @param input The input parameters for the operation as an ACell
	 * @return Future containing the job status map
	 */
	public CompletableFuture<Job> invoke(Hash assetID, ACell input) {
		SimpleHttpRequest req = SimpleHttpRequest.create(Method.POST, getBaseURI().resolve("invoke"));
		ACell requestBody = Maps.of(
			"operation", assetID.toCVMHexString(),
			"input", input
		);
		req.setBody(JSONUtils.toString(requestBody), ContentType.APPLICATION_JSON);
		
		CompletableFuture<SimpleHttpResponse> responseFuture = HTTPClients.execute(req);
		CompletableFuture<Job> result= responseFuture.thenApply(response -> {
			if (response.getCode() != 201) {
				throw new ResponseException("Failed to invoke operation: " + response+" = "+response.getBodyText(),response);
			}
			AMap<AString,ACell> body=RT.ensureMap(JSONUtils.parseJSON5(response.getBodyText()));
			if (body==null) {
				throw new ResponseException("Invalid response body",response);
			}
			return Job.create(body);
		});
		return result;
	}
	
	/**
	 * Starts a operation on the Grid.
	 * @param opID Operation ID
	 * @param input Operation input
	 * @return Job instance (likely to be PENDING)
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws TimeoutException
	 */
	public Job startJob(Hash opID, ACell input) throws InterruptedException, ExecutionException, TimeoutException {
		return startJobAsync(opID,input).get(5,TimeUnit.SECONDS);
	}
	
	/**
	 * Starts an operation on the connected venue
	 * @param opID The AssetID of the operation to invoke
	 * @param input The input parameters for the operation as an ACell
	 * @return Future containing the job status, likely to be PENDING
	 */
	public CompletableFuture<Job> startJobAsync(Hash opID, ACell input) {
		SimpleHttpRequest req = SimpleHttpRequest.create(Method.POST, getBaseURI().resolve("invoke"));
		ACell requestBody = Maps.of(
			"operation", opID,
			"input", input
		);
		req.setBody(JSONUtils.toString(requestBody), ContentType.APPLICATION_JSON);
		
		CompletableFuture<SimpleHttpResponse> responseFuture = HTTPClients.execute(req);
		CompletableFuture<Job> result= responseFuture.thenApply(response -> {
			if (response.getCode() != 201) {
				throw new ResponseException("Failed to start operation: " + response+" = "+response.getBodyText(),response);
			}
			AMap<AString,ACell> body=RT.ensureMap(JSONUtils.parseJSON5(response.getBodyText()));
			if (body==null) {
				throw new ResponseException("Invalid response body",response);
			}
			return Job.create(body);
		});
		return result;
	}
	
	
	
	public Job invokeAndWait(Hash opID, ACell input) throws InterruptedException {
		CompletableFuture<Job> future = invoke(opID,input);
		Job job=future.join();
		waitForFinish(job);
		return job;
	}
	
	/**
	 * Invokes an operation on the connected venue
	 * @return Future containing the operation execution result
	 */
	public CompletableFuture<List<Hash>> getAssets() {
		SimpleHttpRequest req = SimpleHttpRequest.create(Method.GET, getBaseURI().resolve("assets"));
		
		CompletableFuture<List<Hash>> result = 
		HTTPClients.execute(req).thenApply(response -> {
			int code=response.getCode();
			if (code != 200) {
				throw new ConversionException("assets API returned status: "+code);
			}
			ACell body=JSONUtils.parseJSON5(response.getBodyText());
			AVector<?> items=null;
			if (body instanceof AVector v) {
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
		
		return result;
	}
	
	/**
	 * Waits for a job to finish
	 * @param job
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
	 */
	public CompletableFuture<Hash> addContent(String assetID, AContent content) {
		Hash h = Assets.parseAssetID(assetID);
		if (h == null) throw new IllegalArgumentException("Bad asset ID format");
		
		SimpleHttpRequest req = SimpleHttpRequest.create(Method.PUT, getBaseURI().resolve("assets/" + h.toHexString() + "/content"));
		req.setBody(content.getBlob().getBytes(), ContentType.APPLICATION_OCTET_STREAM);
		
		return  HTTPClients.execute(req).thenApplyAsync(response -> {
			if (response.getCode() == 200 || response.getCode() == 201) {
				ACell json=JSONUtils.parse(response.getBodyText());
				if (json instanceof AString hashString) {
					Hash hash=Hash.parse(hashString);
					if (hash!=null) {
						return hash;
					}
				}
				throw new RuntimeException("Failed to parse result: " + response.getCode() + " - " + response.getBodyText());
			} else {
				throw new RuntimeException("Failed to add content: " + response.getCode() + " - " + response.getBodyText());
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
		Hash h = Assets.parseAssetID(assetID);
		if (h == null) throw new IllegalArgumentException("Bad asset ID format");
		
		SimpleHttpRequest req = SimpleHttpRequest.create(Method.GET, getBaseURI().resolve("assets/" + h.toHexString() + "/content"));
		
		return HTTPClients.execute(req).thenApply(response -> {
			int code=response.getCode();
			if (response.getCode() != 200) {
				throw new RuntimeException("Content get failed with status: " +code+" "+ response.getBodyText()+" -- asset ID: "+assetID);
			}
			
			try {
				// Create a BlobContent from the response body
				byte[] data = response.getBodyBytes();
				convex.core.data.Blob blob = convex.core.data.Blob.wrap(data);
				return new covia.venue.storage.BlobContent(blob);
			} catch (Exception e) {
				throw new RuntimeException("Failed to create content from response", e);
			}
		});
	}

	/**
	 * Cancels a job on the connected venue
	 * @param jobId The job ID to cancel
	 * @return Future that completes when the job is successfully cancelled, or completes exceptionally if the job doesn't exist
	 */
	public CompletableFuture<Void> cancelJob(String jobId) {
		SimpleHttpRequest req = SimpleHttpRequest.create(Method.PUT, getBaseURI().resolve("jobs/" + jobId + "/cancel"));
		
		return HTTPClients.execute(req).thenApply(response -> {
			int code = response.getCode();
			if (code == 200) {
				return null; // Success
			} else if (code == 404) {
				throw new RuntimeException("Job not found: " + jobId);
			} else {
				throw new RuntimeException("Failed to cancel job: " + code + " - " + response.getBodyText());
			}
		});
	}

	/**
	 * Deletes a job from the connected venue
	 * @param jobId The job ID to delete
	 * @return Future that completes when the job is successfully deleted, or completes exceptionally if the job doesn't exist
	 */
	public CompletableFuture<Void> deleteJob(String jobId) {
		SimpleHttpRequest req = SimpleHttpRequest.create(Method.PUT, getBaseURI().resolve("jobs/" + jobId + "/delete"));
		
		return HTTPClients.execute(req).thenApply(response -> {
			int code = response.getCode();
			if (code == 200) {
				return null; // Success
			} else if (code == 404) {
				throw new RuntimeException("Job not found: " + jobId);
			} else {
				throw new RuntimeException("Failed to delete job: " + code + " - " + response.getBodyText());
			}
		});
	}

	/**
	 * Gets the status of a job by job ID.
	 * @param jobID The job ID to check
	 * @return Future containing the job status as an AMap, or null if not found
	 */
	public CompletableFuture<AMap<AString, ACell>> getJobStatus(AString jobID) {
		SimpleHttpRequest req = SimpleHttpRequest.create(Method.GET, getBaseURI().resolve("jobs/" + jobID));
		return HTTPClients.execute(req).thenApply(response -> {
			int code=response.getCode();
			if (code == 200) {
				return RT.ensureMap(JSONUtils.parse(response.getBodyText()));
			} else if (code == 404) {
				return null;
			} else {
				throw new RuntimeException("Failed to get job status: " + response.getCode() + " - " + response.getBodyText());
			}
		});
	}
	
	/**
	 * Gets the status of a job by job ID.
	 * @param jobID The job ID to check
	 * @return Future containing the job status as an AMap, or null if not found
	 */
	public CompletableFuture<AMap<AString, ACell>> getJobStatus(Object jobID) {
		return getJobStatus(Job.parseID(jobID));
	}
	
	public void updateJobStatus(Job job) throws InterruptedException, ExecutionException, TimeoutException {
		AString jobID=job.getID();
		CompletableFuture<AMap<AString, ACell>> future = getJobStatus(jobID).thenApply(data->{
			job.setData(data);
			return data;
		});
		future.get(5,TimeUnit.SECONDS);
	}

}
