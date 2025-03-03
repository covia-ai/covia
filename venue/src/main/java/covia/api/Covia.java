package covia.api;

import java.net.URI;
import java.util.concurrent.Future;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Method;

import convex.core.Result;
import convex.core.data.ACell;
import convex.core.util.JSONUtils;
import convex.java.ARESTClient;

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


	public static Covia create(URI host) {
		return new Covia(host);
	}


}
