package covia.venue.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.auth.jwt.JWKSKeys;

/**
 * JWKS key fetcher with caching for OAuth provider public keys.
 *
 * <p>Fetches and caches RSA public keys from provider JWKS endpoints.
 * Keys are refreshed when a kid is not found in cache (handles key rotation).
 * Full cache refresh happens at most once per REFRESH_INTERVAL.
 */
public class JWKSClient {

	private static final Logger log = LoggerFactory.getLogger(JWKSClient.class);

	private static final long REFRESH_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes minimum between refreshes

	private static final HttpClient client = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private static final ConcurrentHashMap<String, CachedKeySet> cache = new ConcurrentHashMap<>();

	/**
	 * Get an RSA public key by kid from a JWKS endpoint.
	 * Returns cached key if available, otherwise fetches from endpoint.
	 * If kid not found in cache, forces a refresh (handling key rotation).
	 *
	 * @param jwksUri The JWKS endpoint URL
	 * @param kid The key ID to look up
	 * @return RSAPublicKey or null if not found
	 */
	public static RSAPublicKey getKey(String jwksUri, String kid) {
		CachedKeySet cached = cache.get(jwksUri);

		// Try cached first
		if (cached != null) {
			RSAPublicKey key = cached.keys.get(kid);
			if (key != null) return key;

			// kid not found — maybe key rotation. Refresh if not too recent.
			if (System.currentTimeMillis() - cached.fetchedAt < REFRESH_INTERVAL_MS) {
				return null;
			}
		}

		// Fetch fresh
		CachedKeySet fresh = fetchKeys(jwksUri);
		if (fresh == null) return null;

		cache.put(jwksUri, fresh);
		return fresh.keys.get(kid);
	}

	private static CachedKeySet fetchKeys(String jwksUri) {
		try {
			HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(jwksUri))
				.GET()
				.timeout(Duration.ofSeconds(10))
				.build();
			HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) {
				log.warn("JWKS fetch failed: {} returned {}", jwksUri, resp.statusCode());
				return null;
			}

			Map<String, RSAPublicKey> keys = JWKSKeys.parseKeys(resp.body());
			log.info("Fetched {} keys from JWKS: {}", keys.size(), jwksUri);
			return new CachedKeySet(keys, System.currentTimeMillis());
		} catch (Exception e) {
			log.warn("JWKS fetch error for {}: {}", jwksUri, e.getMessage());
			return null;
		}
	}

	private static class CachedKeySet {
		final Map<String, RSAPublicKey> keys;
		final long fetchedAt;
		CachedKeySet(Map<String, RSAPublicKey> keys, long fetchedAt) {
			this.keys = keys;
			this.fetchedAt = fetchedAt;
		}
	}
}
