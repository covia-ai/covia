package covia.venue.api;

import convex.api.ContentTypes;
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

	protected void jsonResult(Context ctx,String jsonContent) {
		ctx.header("Content-type", ContentTypes.JSON);
		ctx.result(jsonContent);
		ctx.status(200);
	}

}
