package covia.grid.auth;

import java.net.http.HttpRequest;

import convex.auth.did.DID;
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
 *   <li>{@link #namedKeyPair(AKeyPair, String, String)} — self-issued named
 *       identity JWT using a registered public key</li>
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
	 * Mint the raw credential token this strategy would attach to a request —
	 * for callers that need to STORE a credential (typically long-lived and
	 * audience-bound, e.g. handed to a proxy as an API key) rather than
	 * authenticate a request in flight.
	 *
	 * <p>For key-pair auth the token carries exactly the claims
	 * {@link #apply} signs, using the instance's configured audience and
	 * lifetime — mint a long-lived credential from a purpose-configured
	 * instance:
	 * <pre>{@code
	 * String token = VenueAuth.keyPair(kp, venueDID, 30 * 24 * 3600).mintToken();
	 * }</pre>
	 *
	 * <p>Rotation of a stored token is the caller's responsibility;
	 * {@code apply()} never needs rotation because it signs fresh per
	 * request. Bind {@code aud} for anything long-lived — a captured
	 * aud-less token replays anywhere self-issued tokens are accepted.
	 *
	 * @return The signed token string
	 * @throws UnsupportedOperationException for strategies that cannot mint
	 *         (none, bearer, local — they hold no signing key)
	 */
	public String mintToken() {
		throw new UnsupportedOperationException(
			getClass().getSimpleName() + " cannot mint tokens");
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
	 * Self-issued EdDSA authentication as a stable named DID using one of that
	 * identity's registered public keys.
	 *
	 * <p>The token carries {@code iss == sub == subjectDID}, is signed by
	 * {@code keyPair}, and is audience-bound to {@code audienceDID}. The target
	 * venue admits it only when that public key is active in the named user's
	 * venue-owned authentication record. The private key remains client-side.
	 *
	 * @param keyPair registered Ed25519 authentication key
	 * @param subjectDID stable named identity asserted in {@code iss}/{@code sub}
	 * @param audienceDID target venue DID
	 * @return a named-user key-pair authentication strategy
	 */
	public static VenueAuth namedKeyPair(AKeyPair keyPair, String subjectDID,
			String audienceDID) {
		return namedKeyPair(keyPair, subjectDID, audienceDID,
			KeyPairAuth.DEFAULT_TOKEN_LIFETIME);
	}

	/**
	 * As {@link #namedKeyPair(AKeyPair, String, String)}, with an explicit token
	 * lifetime. Named credentials always require an audience: they assert a
	 * venue-managed identity and must not become generic bearer credentials.
	 *
	 * @param keyPair registered Ed25519 authentication key
	 * @param subjectDID stable named identity asserted in {@code iss}/{@code sub}
	 * @param audienceDID target venue DID
	 * @param lifetimeSeconds token validity window in seconds
	 * @return a named-user key-pair authentication strategy
	 */
	public static VenueAuth namedKeyPair(AKeyPair keyPair, String subjectDID,
			String audienceDID, long lifetimeSeconds) {
		requireDID(subjectDID, "subjectDID");
		requireDID(audienceDID, "audienceDID");
		return new KeyPairAuth(keyPair, subjectDID, lifetimeSeconds, audienceDID);
	}

	/**
	 * Self-issued authentication for a DID whose signing key is resolved by the
	 * target's DID-method verifier. Unlike {@link #namedKeyPair}, the subject
	 * need not be a user registered at the target; this is how a venue presents
	 * its declared DID to another venue. The credential is always audience-bound.
	 *
	 * @param keyPair private key authorised by the subject DID
	 * @param subjectDID identity asserted in {@code iss}/{@code sub}
	 * @param audienceDID target venue's declared DID
	 * @return a DID-resolved, audience-bound authentication strategy
	 */
	public static VenueAuth identityKeyPair(AKeyPair keyPair, String subjectDID,
			String audienceDID) {
		requireDID(subjectDID, "subjectDID");
		requireDID(audienceDID, "audienceDID");
		return new KeyPairAuth(keyPair, subjectDID,
			KeyPairAuth.DEFAULT_TOKEN_LIFETIME, audienceDID);
	}

	private static void requireDID(String value, String label) {
		try {
			if (value == null || value.isBlank() || DID.fromString(value) == null) {
				throw new IllegalArgumentException(label + " must be a valid DID");
			}
		} catch (RuntimeException e) {
			throw new IllegalArgumentException(label + " must be a DID");
		}
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
