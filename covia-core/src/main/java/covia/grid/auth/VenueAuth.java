package covia.grid.auth;

import java.net.http.HttpRequest;

import convex.core.crypto.AKeyPair;

/**
 * Abstract base class for venue authentication strategies.
 *
 * <p>Subclass this to implement custom authentication schemes.
 * {@link #apply(HttpRequest.Builder)} is called before every HTTP request
 * to inject credentials into the outgoing request headers.
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@link #none()} — no authentication (default)</li>
 *   <li>{@link #bearer(String)} — static bearer token</li>
 *   <li>{@link #keyPair(AKeyPair)} — self-issued EdDSA JWT per request</li>
 * </ul>
 *
 * <p>Custom implementations can be created by extending this class:
 * <pre>{@code
 * VenueAuth apiKey = new VenueAuth() {
 *     public void apply(HttpRequest.Builder b) {
 *         b.header("X-API-Key", "my-secret");
 *     }
 * };
 * Venue v = Grid.connect("http://localhost:8080", apiKey);
 * }</pre>
 */
public abstract class VenueAuth {

	/**
	 * Apply authentication credentials to an HTTP request builder.
	 * Implementations should add headers (e.g. Authorization) to the builder.
	 *
	 * @param builder The HTTP request builder to add auth headers to
	 */
	public abstract void apply(HttpRequest.Builder builder);

	/**
	 * Get the DID associated with this auth provider, if known.
	 * For key-pair auth this is the {@code did:key} derived from the public key.
	 * For bearer tokens the DID is typically not known by the client.
	 *
	 * @return DID string, or null if not available
	 */
	public String getDID() {
		return null;
	}

	/**
	 * No authentication. Requests are sent without credentials.
	 * @return A no-op auth instance
	 */
	public static VenueAuth none() {
		return NoAuth.INSTANCE;
	}

	/**
	 * Static bearer token authentication.
	 * Adds {@code Authorization: Bearer <token>} to every request.
	 *
	 * @param token The bearer token string
	 * @return A bearer auth instance
	 */
	public static VenueAuth bearer(String token) {
		return new BearerAuth(token);
	}

	/**
	 * Self-issued EdDSA JWT authentication using an Ed25519 key pair.
	 * Generates a fresh short-lived JWT for each request, signed with
	 * the given key pair. The JWT's {@code sub} claim is a {@code did:key}
	 * derived from the public key.
	 *
	 * @param keyPair Ed25519 key pair for signing
	 * @return A key pair auth instance
	 */
	public static VenueAuth keyPair(AKeyPair keyPair) {
		return new KeyPairAuth(keyPair);
	}

	/**
	 * As {@link #keyPair(AKeyPair)}, with the JWT's {@code aud} claim bound to
	 * the target venue's DID. RECOMMENDED for any venue whose DID is known: a
	 * captured token then cannot be replayed at any other venue that accepts
	 * self-issued tokens (RFC 7519 §4.1.3). Discover the DID with
	 * {@link VenueDID#discover(String)}.
	 *
	 * @param keyPair Ed25519 key pair for signing
	 * @param audienceDID the target venue's DID (did:key or its did:web alias)
	 * @return A key pair auth instance audience-bound to the venue
	 */
	public static VenueAuth keyPair(AKeyPair keyPair, String audienceDID) {
		return new KeyPairAuth(keyPair, KeyPairAuth.DEFAULT_TOKEN_LIFETIME, audienceDID);
	}

	/**
	 * As {@link #keyPair(AKeyPair, String)}, with an explicit token lifetime.
	 * The default (300s) suits per-request tokens — signing is effectively
	 * free, so short lifetimes cost nothing. Longer lifetimes are for minted
	 * long-lived credentials; pass a null {@code audienceDID} only when the
	 * target venue's DID is genuinely unknowable.
	 *
	 * @param keyPair Ed25519 key pair for signing
	 * @param audienceDID the target venue's DID, or null for no audience binding
	 * @param lifetimeSeconds token validity window in seconds (must be positive)
	 * @return A key pair auth instance
	 */
	public static VenueAuth keyPair(AKeyPair keyPair, String audienceDID, long lifetimeSeconds) {
		return new KeyPairAuth(keyPair, lifetimeSeconds, audienceDID);
	}

	/**
	 * Local (trusted) authentication that claims a user identity without
	 * sending any HTTP credentials. The identity is set on the Venue
	 * automatically. Intended for in-process use with LocalVenue.
	 *
	 * @param did User DID to claim
	 * @return A local auth instance
	 */
	public static VenueAuth local(String did) {
		return new LocalAuth(did);
	}
}
