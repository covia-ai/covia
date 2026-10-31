package covia.grid.auth;

import java.net.http.HttpRequest;
import java.util.function.Supplier;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;

/**
 * Abstract base class for venue authentication strategies.
 *
 * <p>Subclass this to implement custom authentication schemes.
 * {@link #apply(HttpRequest.Builder, Supplier)} is called before every HTTP
 * request to inject credentials into the outgoing request headers.
 *
 * <p>A strategy holds the caller's credentials, not the venue's identity: the
 * connection fixes which venue a request goes to, and a strategy that signs
 * credentials takes that venue's DID from it.
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@link #none()} — no authentication (default)</li>
 *   <li>{@link #bearer(String)} — static bearer token</li>
 *   <li>{@link #keyPair(AKeyPair)} — self-issued EdDSA JWT per request</li>
 *   <li>{@link #namedKeyPair(AKeyPair, String)} — self-issued JWT for a
 *       named identity the key is authorised for</li>
 * </ul>
 *
 * <p>Custom implementations can be created by extending this class:
 * <pre>{@code
 * VenueAuth apiKey = new VenueAuth() {
 *     public void apply(HttpRequest.Builder b, Supplier<String> venueDID) {
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
	 * @param venueDID Supplies the DID of the venue the request goes to, as the
	 *        connection established it. A strategy that signs a credential for
	 *        the venue names it as audience; others need not call it. It throws
	 *        {@link covia.exception.AuthException} when the connection cannot
	 *        establish that DID.
	 */
	public abstract void apply(HttpRequest.Builder builder, Supplier<String> venueDID);

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
	 * Mint the raw credential token this strategy would attach to a request to
	 * the venue {@code venueDID} — for callers that need to STORE a credential
	 * (typically long-lived, e.g. handed to a proxy as an API key) rather than
	 * authenticate a request in flight.
	 *
	 * <p>For key-pair auth the token carries exactly the claims
	 * {@link #apply} signs, with {@code venueDID} as audience and the
	 * instance's lifetime — mint a long-lived credential from a
	 * purpose-configured instance:
	 * <pre>{@code
	 * String token = VenueAuth.keyPair(kp, 30 * 24 * 3600).mintToken(venueDID);
	 * }</pre>
	 *
	 * <p>Rotation of a stored token is the caller's responsibility;
	 * {@code apply()} never needs rotation because it signs fresh per
	 * request. A credential without {@code aud} is no strategy's to make: sign
	 * it yourself and present it with {@link #bearer(String)}.
	 *
	 * @param venueDID DID of the venue the credential is for
	 * @return The signed token string
	 * @throws UnsupportedOperationException for strategies that cannot mint
	 *         (none, bearer, local — they hold no signing key)
	 */
	public String mintToken(String venueDID) {
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
	 * derived from the public key, and its {@code aud} the DID of the venue
	 * the connection reaches, so a captured token cannot be replayed at any
	 * other venue (RFC 8725 §3.9).
	 *
	 * @param keyPair Ed25519 key pair for signing
	 * @return A key pair auth instance
	 */
	public static VenueAuth keyPair(AKeyPair keyPair) {
		return new KeyPairAuth(keyPair, null, KeyPairAuth.DEFAULT_TOKEN_LIFETIME);
	}

	/**
	 * As {@link #keyPair(AKeyPair)}, with an explicit token lifetime.
	 * The default (300s) suits per-request tokens — signing is effectively
	 * free, so short lifetimes cost nothing. Longer lifetimes are for minted
	 * long-lived credentials ({@link #mintToken(String)}).
	 *
	 * @param keyPair Ed25519 key pair for signing
	 * @param lifetimeSeconds token validity window in seconds (must be positive)
	 * @return A key pair auth instance
	 */
	public static VenueAuth keyPair(AKeyPair keyPair, long lifetimeSeconds) {
		return new KeyPairAuth(keyPair, null, lifetimeSeconds);
	}

	/**
	 * Self-issued EdDSA authentication as a named DID the key is authorised
	 * for: a venue user holding one of its registered public keys, an
	 * identity nested below the signing key's DID
	 * ({@code did:key:<key>:u:<name>:g:<agent>}), or a DID whose key the
	 * target resolves by its DID method, as a venue presents its declared DID
	 * to another venue.
	 *
	 * <p>The token carries {@code iss == sub == subjectDID}, is signed by
	 * {@code keyPair}, and names the venue the connection reaches as audience.
	 * The target venue authenticates a local named user only when that public
	 * key is active in the user's venue-owned authentication record. A nested
	 * identity needs no such record: its subject must have nonempty
	 * colon-separated segments below the signing key's exact DID, using
	 * letters, numbers, '.', '_' and '-'. User admission remains separate, and
	 * the subject inherits no authority from the signing identity or its
	 * parent names.
	 *
	 * @param keyPair Ed25519 key authorised for the subject
	 * @param subjectDID named identity asserted in {@code iss}/{@code sub}
	 * @return a named key-pair authentication strategy
	 */
	public static VenueAuth namedKeyPair(AKeyPair keyPair, String subjectDID) {
		return namedKeyPair(keyPair, subjectDID, KeyPairAuth.DEFAULT_TOKEN_LIFETIME);
	}

	/**
	 * As {@link #namedKeyPair(AKeyPair, String)}, with an explicit token
	 * lifetime.
	 *
	 * @param keyPair Ed25519 key authorised for the subject
	 * @param subjectDID named identity asserted in {@code iss}/{@code sub}
	 * @param lifetimeSeconds token validity window in seconds
	 * @return a named key-pair authentication strategy
	 */
	public static VenueAuth namedKeyPair(AKeyPair keyPair, String subjectDID,
			long lifetimeSeconds) {
		requireDID(subjectDID, "subjectDID");
		return new KeyPairAuth(keyPair, subjectDID, lifetimeSeconds);
	}

	static void requireDID(String value, String label) {
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
