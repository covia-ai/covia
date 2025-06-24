package covia.client;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Method;

import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSONUtils;
import convex.java.ARESTClient;
import convex.java.HTTPClients;

public class Covia extends ARESTClient  {

	private static final double BACKOFF_FACTOR = 1.5;
	private static final long INITIAL_POLL_DELAY = 300; // 1 second initial delay

	public Covia(URI host) {
		super(host,"/api/v1/");
	}

	public Future<Result> addAsset(String jsonString) {
		ACell meta=JSONUtils.parseJSON5(jsonString);
		return addAsset(meta);
	}
	
	public Future<Result> addAsset(ACell meta) {
		SimpleHttpRequest req=SimpleHttpRequest.create(Method.POST, getBaseURI().resolve("assets"));
		req.setBody(JSONUtils.toString(meta), ContentType.APPLICATION_JSON);
		return doRequest(req);
	}

	public static Covia create(URI host) {
		return new Covia(host);
	}

	/**
	 * Gets metadata for a given asset on the4 connected venue
	 * @param asset ID for asset. Can be a full asset DID
	 * @return Asset metadata as a String, or null if asset is not found
	 */
	public Future<String> getMeta(String asset) {
		Hash h=Asset.parseAssetID(asset);
		if (h==null) throw new IllegalArgumentException("Bad asset ID format");
		
		
		SimpleHttpRequest request=SimpleHttpRequest.create(Method.GET, getBaseURI().resolve("assets/"+h.toHexString()));
		CompletableFuture<SimpleHttpResponse> future=HTTPClients.execute(request);
		
		return future.thenApply(response-> {
			if (response.getCode()!=200) return null;
			return response.getBodyText();
		});
	}

	/**
	 * Invokes an operation on the connected venue
	 * @param operation The name of the operation to invoke
	 * @param input The input parameters for the operation as an ACell
	 * @return Future containing the operation execution result
	 */
	public Future<Result> invoke(String operation, ACell input) {
		SimpleHttpRequest req = SimpleHttpRequest.create(Method.POST, getBaseURI().resolve("invoke"));
		ACell requestBody = Maps.of(
			"operation", Strings.create(operation),
			"input", input
		);
		req.setBody(JSONUtils.toString(requestBody), ContentType.APPLICATION_JSON);
		
		CompletableFuture<Result> result = new CompletableFuture<>();
		
		HTTPClients.execute(req).thenAccept(response -> {
			System.out.println(req);
			System.out.println(response);
			if (response.getCode() != 201) {
				result.completeExceptionally(new RuntimeException("Failed to invoke operation: " + response));
				return;
			}
			ACell body=JSONUtils.parseJSON5(response.getBodyText());
			
			AString jobId=RT.ensureString(RT.getIn(body, "id"));
			if (jobId == null) {
				result.completeExceptionally(new RuntimeException("No job ID returned: "+body));
				return;
			}
			
			// Start polling for job status
			pollJobStatus(jobId.toString(), result);
		}).exceptionally(ex -> {
			result.completeExceptionally(ex);
			return null;
		});
		
		return result;
	}
	
	private void pollJobStatus(String jobId, CompletableFuture<Result> result) {
		Runnable pollingTask = () -> {
			try {
				long currentDelay = INITIAL_POLL_DELAY;
				while (true) {
					SimpleHttpRequest req = SimpleHttpRequest.create(Method.GET, getBaseURI().resolve("jobs/" + jobId));
					SimpleHttpResponse response = HTTPClients.execute(req).get();
					if (response.getCode() != 200) {
						result.completeExceptionally(new RuntimeException("Failed to get job status: " + response.getCode()+" for job "+jobId));
						return;
					}
					ACell status = JSONUtils.parseJSON5(response.getBodyText());
					String jobStatus = RT.getIn(status, "status").toString();
					if ("PENDING".equals(jobStatus)) {
						Thread.sleep(currentDelay);
						currentDelay = (long) (currentDelay * BACKOFF_FACTOR);
						continue;
					} else if ("FAILED".equals(jobStatus)) {
						result.completeExceptionally(new RuntimeException("Job failed: " + status));
						return;
					} else {
						result.complete(Result.value(RT.getIn(status, "output")));
						return;
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
				result.completeExceptionally(e);
			}
		};
		// Use virtual thread if available, otherwise fallback to regular thread
		try {
			Thread.startVirtualThread(pollingTask);
		} catch (Throwable t) {
			new Thread(pollingTask).start();
		}
	}

}
