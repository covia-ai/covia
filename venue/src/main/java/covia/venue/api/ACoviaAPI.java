package covia.venue.api;

import convex.api.ContentTypes;
import convex.core.util.JSONUtils;
import covia.venue.Venue;
import io.javalin.http.Context;

/*
 * Base class for Covia Venue APIs
 */
public abstract class ACoviaAPI  {
	
	protected Venue venue;

	public ACoviaAPI(Venue venue) {
		this.venue=venue;
	}

	public void buildRawResult(Context ctx,String jsonContent) {
		ctx.header("Content-type", ContentTypes.JSON);
		ctx.result(jsonContent);
	}
	
	public void buildResult(Context ctx,Object json) {
		buildRawResult(ctx,JSONUtils.toString(json));
	}
	
	public void buildResult(Context ctx,int status,Object json) {
		buildResult(ctx,json);
		ctx.status(status);
	}
	
	public void buildError(Context ctx,int status,String message) {
		if (status<400) throw new IllegalArgumentException("Unlikely HTTP error code: "+status);
		buildRawResult(ctx,"{\"error\": \""+JSONUtils.escape(message)+"\"}");
		ctx.header("Content-type", ContentTypes.JSON);
		ctx.status(status);
	}

}
