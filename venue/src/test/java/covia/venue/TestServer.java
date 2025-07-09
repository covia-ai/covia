package covia.venue;

import java.net.URI;

import covia.client.Covia;
import covia.venue.server.VenueServer;

public class TestServer {

	public static final int PORT=8099;
	public static final String BASE_URL="http://localhost:"+PORT;
	
	public static final VenueServer SERVER;
	public static final Venue VENUE;
	public static final Covia COVIA;
	
	static {
		SERVER=VenueServer.create(null);
		VENUE=SERVER.getVenue();
		Venue.addDemoAssets(VENUE);

		SERVER.start(PORT);
		COVIA = Covia.create(URI.create(BASE_URL));
	}
}
