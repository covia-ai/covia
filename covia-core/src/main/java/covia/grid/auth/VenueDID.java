package covia.grid.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import convex.auth.did.DID;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.lang.RT;
import convex.core.util.JSON;

/**
 * Client-side venue DID discovery: resolves a venue's published DID from its
 * {@code /.well-known/did.json} document, so callers can audience-bind their
 * self-issued tokens ({@link VenueAuth#keyPair(convex.core.crypto.AKeyPair, String)})
 * without hand-rolling discovery.
 *
 * <p>The returned DID is the document's {@code id} — the identity the venue
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

	private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

	private VenueDID() {}

	/**
	 * Resolves the venue's DID from {@code <baseUrl>/.well-known/did.json}.
	 *
	 * @param baseUrl the venue's base URL (e.g. {@code "https://venue.example"})
	 * @return the venue's published DID string (validated as a well-formed DID)
	 * @throws RuntimeException if the document cannot be fetched or does not
	 *         carry a well-formed DID {@code id}
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
			HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.build();
			HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/.well-known/did.json"))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
			HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) {
				throw new RuntimeException("Venue DID discovery failed: " + base
					+ "/.well-known/did.json returned HTTP " + resp.statusCode());
			}
			ACell doc = JSON.parse(resp.body());
			AString id = RT.ensureString(RT.getIn(doc, "id"));
			if (id == null) {
				throw new RuntimeException("Venue DID document has no id: " + base);
			}
			// Validate as a well-formed DID (throws on malformed input).
			DID parsed = DID.fromString(id.toString());
			if (parsed == null) {
				throw new RuntimeException("Venue DID document id is not a valid DID: " + id);
			}
			return id.toString();
		} catch (RuntimeException e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Venue DID discovery interrupted for " + base, e);
		} catch (Exception e) {
			throw new RuntimeException("Venue DID discovery failed for " + base + ": " + e.getMessage(), e);
		}
	}
}
