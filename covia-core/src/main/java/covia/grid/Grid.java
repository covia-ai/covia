package covia.grid;

import java.net.URI;

import covia.api.DID;
import covia.grid.client.VenueHTTP;

/**
 * Static utility class for interacting with the Covia Grid
 */
public class Grid {

	/**
	 * Connect to a grid venue via a DID
	 * @param did DID e.g. 'did:web:venue-test.covia.ai"
	 * @return Venue instance
	 */
	public static Venue connect(DID did) {
		String method=did.getMethod();
		if (did.getMethod().equals("web")) {
			return VenueHTTP.create(URI.create("https://"+did.getId()));
		}
		
		throw new IllegalArgumentException("Unrecognised DID method: "+method);
	}

	public static Venue connect(String conn) {
		conn=conn.trim();
		if (conn.startsWith("http")) {
			URI uri=URI.create(conn);
			return VenueHTTP.create(uri);
		} else if (conn.startsWith("did")){
			return connect(DID.fromString(conn));
		}
		throw new IllegalArgumentException("Unrecognised connection string format: "+conn);
	}
}
