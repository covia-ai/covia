package covia.grid;

import java.net.URI;

import convex.did.DID;
import covia.grid.auth.VenueAuth;
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
		return connect(did, VenueAuth.none());
	}

	/**
	 * Connect to a grid venue via a DID with authentication
	 * @param did DID e.g. 'did:web:venue-test.covia.ai"
	 * @param auth Authentication provider
	 * @return Venue instance
	 */
	public static Venue connect(DID did, VenueAuth auth) {
		String method=did.getMethod();
		if (method.equals("web")) {
			return VenueHTTP.create(URI.create("https://"+did.getID()), auth);
		}
		throw new IllegalArgumentException("Unrecognised DID method: "+method);
	}

	/**
	 * Connect to a grid venue via a connection string (URL or DID)
	 * @param conn Connection string
	 * @return Venue instance
	 */
	public static Venue connect(String conn) {
		return connect(conn, VenueAuth.none());
	}

	/**
	 * Connect to a grid venue via a connection string with authentication
	 * @param conn Connection string (URL or DID)
	 * @param auth Authentication provider
	 * @return Venue instance
	 */
	public static Venue connect(String conn, VenueAuth auth) {
		conn=conn.trim();
		if (conn.startsWith("http")) {
			URI uri=URI.create(conn);
			return VenueHTTP.create(uri, auth);
		} else if (conn.startsWith("did")){
			return connect(DID.fromString(conn), auth);
		}
		throw new IllegalArgumentException("Unrecognised connection string format: "+conn);
	}
}
