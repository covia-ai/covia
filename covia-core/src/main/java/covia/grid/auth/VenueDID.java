package covia.grid.auth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;

import convex.auth.did.DID;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.exception.AuthException;
import covia.exception.ResponseException;
import covia.exception.UnavailableException;
import covia.grid.Grid;

/**
 * Client-side venue DID discovery and the rules by which a connection fixes
 * the DID its credentials are audienced to ({@link #establish}).
 *
 * <p>A discovered DID is the document's {@code id} — the identity the venue
 * presents: did:web when it has a public hostname, else its did:key. Consumers
 * use it as-is (store, pin, audience-bind) — {@code alsoKnownAs} entries are
 * informational cross-references, never a canonical identity to re-bind to
 * (covia#343). Venues accept tokens audienced to either published form.</p>
 *
 * <p>Results are cached <b>on success only</b> (a venue's DID is stable for its
 * lifetime); failures are never cached, so a venue that was briefly unreachable
 * is retried on the next call.</p>
 */
public final class VenueDID {

	/**
	 * Opening of the refusal when the venue a connection reaches presents
	 * another DID than the one the connection was opened for. A DID document
	 * is the DID's own only when its {@code id} is that DID (DID Core), and
	 * {@code alsoKnownAs} does not make two DIDs one.
	 */
	public static final String PRESENTS_OTHER = "The venue presents a different DID from the one the connection was opened for: ";

	/**
	 * Opening of the refusal when a venue reached by URL alone presents a DID
	 * it does not prove at that address.
	 */
	public static final String UNPROVEN = "The venue's DID is not proven at the address it was reached by; connect by its DID, or name it: ";

	/** Why an address does not prove a DID ({@link #provenAt}). */
	public static final String NOT_ITS_ADDRESS = "the address is not the DID's own; only a did:web DID is proven by its address";

	private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

	/** One client for every discovery: each client holds a selector thread for its lifetime. */
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private VenueDID() {}

	/**
	 * The DID a connection to {@code base} signs its credentials for.
	 *
	 * <p>Opened for a DID — by connecting with that DID, or naming it beside
	 * the address — the connection keeps it, provided the venue there presents
	 * the same DID. Reached by URL alone, it takes the DID the venue presents
	 * when the address proves it ({@link #provenAt}), or when the address is
	 * loopback, on the client's own machine. Anything else is refused: never
	 * re-bound to another DID.</p>
	 *
	 * @param base Any URI on the venue; its origin is used
	 * @param expected The DID the connection was opened for, or null
	 * @return The DID to audience credentials to, as the venue spells it
	 * @throws AuthException if the venue presents another DID, or one that
	 *         its address does not prove
	 * @throws RuntimeException if the venue's DID document cannot be fetched
	 */
	public static String establish(URI base, String expected) {
		String presented = discover(origin(base));
		String refusal = refusal(base, expected, presented);
		if (refusal != null) throw new AuthException(refusal);
		return presented;
	}

	/**
	 * Why a connection to {@code base} may not sign for the DID the venue
	 * there presents, or null when it may ({@link #establish}).
	 *
	 * @param base Any URI on the venue
	 * @param expected The DID the connection was opened for, or null
	 * @param presented The DID the venue at {@code base} presents
	 * @return null, or the reason for refusing
	 */
	public static String refusal(URI base, String expected, String presented) {
		String origin = origin(base);
		if (expected != null) {
			return sameDID(expected, presented) ? null
				: PRESENTS_OTHER + presented + " at " + origin + ", not " + expected;
		}
		if (provenAt(origin, presented) || isLoopback(base)) return null;
		return UNPROVEN + presented + " at " + origin;
	}

	/** Equality as {@link DID} defines it, which reads a percent-encoded identifier decoded. */
	private static boolean sameDID(String a, String b) {
		try {
			return DID.fromString(a).equals(DID.fromString(b));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static String origin(URI uri) {
		return uri.getScheme() + "://" + uri.getRawAuthority();
	}

	/**
	 * Whether reaching {@code target} proves the venue there holds {@code did}.
	 * A did:web venue proves itself at its own address ({@link Grid#webDIDAddress}),
	 * by TLS or on loopback; a target named by that DID is reached there. No other
	 * DID is proven at an address.
	 *
	 * @param target Venue URL, or DID
	 * @param did The DID to prove
	 * @return true if the address proves the DID
	 */
	public static boolean provenAt(String target, String did) {
		if (!did.startsWith("did:web:")) return false;
		if (target.equals(did)) return true;
		try {
			URI expected = Grid.webDIDAddress(DID.fromString(did));
			URI actual = URI.create(target.trim());
			return expected.getScheme().equalsIgnoreCase(actual.getScheme())
				&& expected.getHost() != null
				&& expected.getHost().equalsIgnoreCase(actual.getHost())
				&& port(expected) == port(actual);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static int port(URI uri) {
		if (uri.getPort() >= 0) return uri.getPort();
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	private static boolean isLoopback(URI uri) {
		String host = uri.getHost();
		return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
	}

	/**
	 * Resolves the venue's DID from {@code <baseUrl>/.well-known/did.json}.
	 *
	 * @param baseUrl the venue's base URL (e.g. {@code "https://venue.example"})
	 * @return the venue's published DID string (validated as a well-formed DID)
	 * @throws UnavailableException if the venue cannot be reached
	 * @throws ResponseException if the venue answers with an error or a
	 *         document that does not carry a well-formed DID {@code id}
	 */
	public static String discover(String baseUrl) {
		if (baseUrl == null) throw new IllegalArgumentException("baseUrl is required");
		String key = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		String cached = CACHE.get(key);
		if (cached != null) return cached;

		String did = fetch(key);
		CACHE.put(key, did); // success only — failures threw above
		return did;
	}

	private static String fetch(String base) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/.well-known/did.json"))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
			HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) {
				throw new ResponseException("Venue DID discovery failed: " + base
					+ "/.well-known/did.json returned HTTP " + resp.statusCode(), resp);
			}
			ACell doc = JSON.parse(resp.body());
			AString id = RT.ensureString(RT.getIn(doc, "id"));
			if (id == null) {
				throw new ResponseException("Venue DID document has no id: " + base);
			}
			// Validate as a well-formed DID (throws on malformed input).
			DID parsed = DID.fromString(id.toString());
			if (parsed == null) {
				throw new ResponseException("Venue DID document id is not a valid DID: " + id);
			}
			return id.toString();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CancellationException("Venue DID discovery interrupted for " + base);
		} catch (IOException e) {
			throw new UnavailableException("Venue DID discovery failed for " + base + ": " + e.getMessage(), e);
		}
	}
}
