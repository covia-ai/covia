package covia.api;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Method;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.util.JSONUtils;
import convex.java.HTTPClients;
import convex.restapi.api.ARESTClient;

public class Covia extends ARESTClient  {

	public Covia(URI host) {
		super(host,"/api/v1/");
	}

	public Future<Result> addAsset(String jsonString) {
		ACell meta=JSONUtils.parse(jsonString);
		return addAsset(meta);
	}
	public Future<Result> addAsset(ACell meta) {
		SimpleHttpRequest req=SimpleHttpRequest.create(Method.POST, getBaseURI().resolve("asset"));
		req.setBody(JSONUtils.toString(meta), ContentType.APPLICATION_JSON);
		return doRequest(req);
	}

	/**
	 * Makes a HTTP request as a CompletableFuture
	 * @param request Request object
	 * @param body Body of request (as String, should normally be valid JSON)
	 * @return Future to be filled with JSON response.
	 */
	protected CompletableFuture<Result> doRequest(SimpleHttpRequest request) {
		try {
			CompletableFuture<SimpleHttpResponse> future=HTTPClients.execute(request);
			return future.thenApply(response->{
				String rbody=null;
				try {
					rbody=response.getBody().getBodyText();
					return Result.create(null,JSONUtils.parse(rbody));
				} catch (Exception e) {
					if (rbody==null) rbody="<Body not readable as String>";
					Result res= Result.error(ErrorCodes.FORMAT,"Can't parse JSON body: " +rbody);
					return res;
				}
			});
		} catch (Exception e) {
			return CompletableFuture.completedFuture(Result.fromException(e));
		}
	}

	public static Covia create(URI host) {
		return new Covia(host);
	}


}
