package covia.grid;

import java.net.URI;

import convex.auth.did.DID;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;

/**
 * Static utility class for interacting with the Covia Grid
 */
public class Grid {

	/**
	 * Connect to a grid venue via a DID
	 * @param did DID e.g. 'did:web:venue.example.com'
	 * @return Venue instance
	 */
	public static Venue connect(DID did) {
		return connect(did, VenueAuth.none());
	}

	/**
	 * Connect to a grid venue via a DID with authentication
	 * @param did DID e.g. 'did:web:venue.example.com'
	 * @param auth Authentication provider
	 * @return Venue instance
	 */
	public static Venue connect(DID did, VenueAuth auth) {
		String method=did.getMethod();
		if (method.equals("web")) {
			// did:web encodes an optional port with a percent-encoded colon
			// (did:web:example.com%3A3000) — decode it for the URL form.
			String host=did.getID().replace("%3A", ":").replace("%3a", ":");
			// did:web resolves over https; localhost may use http (no TLS
			// for loopback, per the did:web spec note).
			String hostName=host.contains(":")?host.substring(0, host.indexOf(':')):host;
			String scheme=(hostName.equals("localhost")||hostName.equals("127.0.0.1"))?"http":"https";
			return VenueHTTP.create(URI.create(scheme+"://"+host), auth);
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
