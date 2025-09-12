package covia.venue.api;

import convex.api.ContentTypes;
import convex.core.util.JSON;
import covia.venue.Engine;
import io.javalin.http.Context;

/*
 * Base class for Covia Venue APIs
 */
public abstract class ACoviaAPI  {
	
	/**
	 * Utility method to construct the external base URL
	 * @param ctx Javalin context
	 * @param basePath Path for base URL e.g. "mcp"
	 * @return Base URL for external use (possible localhost if external URL not available from Context)
	 */
	public static String getExternalBaseUrl(Context ctx, String basePath) {
	    // Try to get information from forwarded headers
	    String proto = ctx.header("X-Forwarded-Proto");
	    String host = ctx.header("X-Forwarded-Host");
	    String port = ctx.header("X-Forwarded-Port");
	    String prefix = ctx.header("X-Forwarded-Prefix");
	
	    // Fallback to local request info if headers are missing
	    if (proto == null) {
	        proto = ctx.scheme(); // e.g., "http" or "https"
	    }
	    if (host == null) {
	        host = ctx.host(); // e.g., "localhost:8080" or "my-server.org"
	    }
	
	    // Build the base URL
	    StringBuilder baseUrl = new StringBuilder();
	
	    // Append protocol
	    baseUrl.append(proto).append("://");
	
	    // Append host
	    baseUrl.append(host);
	
	    // Append port if non-standard and not already included in host
	    if (port != null && !host.contains(":")) {
	        if (!("https".equalsIgnoreCase(proto) && "443".equals(port)) &&
	            !("http".equalsIgnoreCase(proto) && "80".equals(port))) {
	            baseUrl.append(":").append(port);
	        }
	    }
	
	    // Append base path (e.g., "/api/v1")
	    if (basePath != null && !basePath.isEmpty()) {
	        // Ensure basePath starts with a slash and doesn't end with one
	        String cleanedBasePath = basePath.startsWith("/") ? basePath : "/" + basePath;
	        cleanedBasePath = cleanedBasePath.endsWith("/") ? 
	                          cleanedBasePath.substring(0, cleanedBasePath.length() - 1) : 
	                          cleanedBasePath;
	        baseUrl.append(cleanedBasePath);
	    }
	
	    // Append prefix if provided by proxy
	    if (prefix != null && !prefix.isEmpty()) {
	        // Ensure prefix starts with a slash and doesn't end with one
	        prefix = prefix.startsWith("/") ? prefix : "/" + prefix;
	        prefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
	        // Only append prefix if it's not already part of the basePath
	        if (!baseUrl.toString().endsWith(prefix)) {
	            baseUrl.append(prefix);
	        }
	    }
	
	    return baseUrl.toString();
	}

	protected Engine engine;

	public ACoviaAPI(Engine venue) {
		this.engine=venue;
	}

	public void buildRawResult(Context ctx,String jsonContent) {
		ctx.header("Content-type", ContentTypes.JSON);
		ctx.result(jsonContent);
	}
	
	public void buildResult(Context ctx,Object json) {
		try {
			buildRawResult(ctx,JSON.toString(json));
		} catch (Exception e) {
			System.err.println("Error in JSON content building: "+json);
			e.printStackTrace();
			throw e;
		}
	}
	
	public void buildResult(Context ctx,int status,Object json) {
		buildResult(ctx,json);
		ctx.status(status);
	}
	
	public void buildError(Context ctx,int status,String message) {
		if (status<400) throw new IllegalArgumentException("Unlikely HTTP error code: "+status);
		buildRawResult(ctx,"{\"error\": \""+JSON.escape(message)+"\"}");
		ctx.header("Content-type", ContentTypes.JSON);
		ctx.status(status);
	}

}
