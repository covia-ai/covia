package covia.venue;

import java.net.URI;

import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.grid.client.VenueHTTP;
import covia.venue.server.VenueServer;

public class TestServer {

	public static final int PORT=8099;
	public static final String BASE_URL="http://localhost:"+PORT;
	
	public static final VenueServer SERVER;
	public static final Engine ENGINE;
	public static final VenueHTTP COVIA;
	
	static {
		SERVER=VenueServer.launch(Maps.of(
				Strings.create("port"),PORT,
				Fields.MCP,Maps.of(),
				Fields.A2A,Maps.of()));
		ENGINE=SERVER.getEngine();


		COVIA = VenueHTTP.create(URI.create(BASE_URL));
	}
}
