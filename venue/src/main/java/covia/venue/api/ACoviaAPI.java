package covia.venue.api;

import convex.api.ContentTypes;
import convex.core.util.JSONUtils;
import covia.venue.Engine;
import io.javalin.http.Context;

/*
 * Base class for Covia Venue APIs
 */
public abstract class ACoviaAPI  {
	
	protected Engine venue;

	public ACoviaAPI(Engine venue) {
		this.venue=venue;
	}

	public void buildRawResult(Context ctx,String jsonContent) {
		ctx.header("Content-type", ContentTypes.JSON);
		ctx.result(jsonContent);
	}
	
	public void buildResult(Context ctx,Object json) {
		try {
			buildRawResult(ctx,JSONUtils.toString(json));
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
		buildRawResult(ctx,"{\"error\": \""+JSONUtils.escape(message)+"\"}");
		ctx.header("Content-type", ContentTypes.JSON);
		ctx.status(status);
	}

}
