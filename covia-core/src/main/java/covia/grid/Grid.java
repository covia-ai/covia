package covia.grid;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

import convex.auth.did.DID;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;

/**
 * Static utility class for interacting with the Covia Grid
 */
public class Grid {

	/** Resolves one DID method to a transport connection. */
	@FunctionalInterface
	public interface DIDVenueResolver {
		Venue resolve(DID did, VenueAuth auth);
	}

	private static final ConcurrentHashMap<String, DIDVenueResolver> DID_RESOLVERS =
		new ConcurrentHashMap<>();

	static {
		DID_RESOLVERS.put("web", Grid::connectWebDID);
	}

	/**
	 * Registers transport discovery for a DID method. Identity and signature
	 * verification remain separate concerns; this hook only locates the venue.
	 * A future {@code did:convex} module can therefore add routing without
	 * changing grid invocation code.
	 */
	public static void registerDIDResolver(String method, DIDVenueResolver resolver) {
		if (method == null || !method.matches("[a-z0-9]+")) {
			throw new IllegalArgumentException("DID method must be lower-case letters/digits");
		}
		if (resolver == null) throw new IllegalArgumentException("resolver is required");
		if (DID_RESOLVERS.putIfAbsent(method, resolver) != null) {
			throw new IllegalStateException("DID venue resolver already registered: " + method);
		}
	}

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
		if (did == null) throw new IllegalArgumentException("Venue DID is required");
		String method = did.getMethod();
		DIDVenueResolver resolver = DID_RESOLVERS.get(method);
		if (resolver == null) {
			throw new IllegalArgumentException("No venue resolver registered for DID method: "
				+ method);
		}
		return resolver.resolve(did, auth);
	}

	private static Venue connectWebDID(DID did, VenueAuth auth) {
		// did:web encodes an optional port with a percent-encoded colon
		// (did:web:example.com%3A3000) — decode it for the URL form.
		String host = did.getID().replace("%3A", ":").replace("%3a", ":");
		// did:web resolves over https; localhost may use http (no TLS
		// for loopback, per the did:web spec note).
		String hostName = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
		String scheme = (hostName.equals("localhost") || hostName.equals("127.0.0.1"))
			? "http" : "https";
		return VenueHTTP.create(URI.create(scheme + "://" + host), auth);
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
		if (conn == null || conn.isBlank()) {
			throw new IllegalArgumentException("Venue reference is required");
		}
		conn=conn.trim();
		if (conn.startsWith("http")) {
			URI uri=URI.create(conn);
			return VenueHTTP.create(uri, auth);
		} else if (conn.startsWith("did")){
			return connect(DID.fromString(conn), auth);
		}
		if (!conn.contains(":")) {
			throw new IllegalArgumentException("Unqualified venue reference '" + conn
				+ "'; use an absolute HTTP(S) URL or a DID with a registered venue resolver. "
				+ "Bare venue labels are caller-local and must be resolved before grid invocation.");
		}
		throw new IllegalArgumentException("Unrecognised connection string format: "+conn);
	}
}
