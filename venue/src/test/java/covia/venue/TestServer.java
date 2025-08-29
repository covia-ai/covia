package covia.venue;

import java.net.URI;

import convex.core.data.Maps;
import covia.api.Fields;
import covia.grid.client.Covia;
import covia.venue.server.VenueServer;

public class TestServer {

	public static final int PORT=8099;
	public static final String BASE_URL="http://localhost:"+PORT;
	
	public static final VenueServer SERVER;
	public static final Engine VENUE;
	public static final Covia COVIA;
	
	static {
		SERVER=VenueServer.launch(Maps.of(Fields.PORT,PORT));
		VENUE=SERVER.getEngine();


		COVIA = Covia.create(URI.create(BASE_URL));
	}
}
