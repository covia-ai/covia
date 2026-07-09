package covia.grid.auth;

import java.net.http.HttpRequest;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.auth.jwt.JWT;

/**
 * Self-issued EdDSA JWT authentication using an Ed25519 key pair.
 *
 * <p>Generates a fresh, short-lived JWT for each request. The JWT is signed
 * with the provided key pair and contains:
 * <ul>
 *   <li>{@code sub} — a {@code did:key} derived from the public key</li>
 *   <li>{@code iss} — same as {@code sub} (self-issued)</li>
 *   <li>{@code aud} — the target venue's DID, when configured (RECOMMENDED:
 *       binds the token to one venue, so a captured JWT cannot be replayed at
 *       any other venue that accepts self-issued tokens)</li>
 *   <li>{@code iat} — current time</li>
 *   <li>{@code exp} — current time + token lifetime</li>
 * </ul>
 *
 * <p>The JWT's {@code kid} header is set automatically by
 * {@link JWT#signPublic} to the Multikey-encoded public key.
 */
class KeyPairAuth extends VenueAuth {

	/** Default token lifetime: 5 minutes */
	public static final long DEFAULT_TOKEN_LIFETIME = 300;

	private static final AString SUB = Strings.intern("sub");
	private static final AString ISS = Strings.intern("iss");
	private static final AString AUD = Strings.intern("aud");
	private static final AString IAT = Strings.intern("iat");
	private static final AString EXP = Strings.intern("exp");

	private final AKeyPair keyPair;
	private final long tokenLifetime;
	private final String didKey;
	/** Target venue DID for the {@code aud} claim; null = no audience binding. */
	private final String audience;

	KeyPairAuth(AKeyPair keyPair) {
		this(keyPair, DEFAULT_TOKEN_LIFETIME, null);
	}

	KeyPairAuth(AKeyPair keyPair, long tokenLifetime) {
		this(keyPair, tokenLifetime, null);
	}

	KeyPairAuth(AKeyPair keyPair, long tokenLifetime, String audience) {
		if (keyPair == null) {
			throw new IllegalArgumentException("Key pair must not be null");
		}
		if (tokenLifetime <= 0) {
			throw new IllegalArgumentException("Token lifetime must be positive");
		}
		this.keyPair = keyPair;
		this.tokenLifetime = tokenLifetime;
		this.audience = audience;
		// Pre-compute the did:key from the public key
		AString multikey = Multikey.encodePublicKey(keyPair.getAccountKey());
		this.didKey = "did:key:" + multikey;
	}

	@Override
	public void apply(HttpRequest.Builder builder) {
		long nowSecs = System.currentTimeMillis() / 1000;
		AMap<AString, ACell> claims = Maps.of(
			SUB, Strings.create(didKey),
			ISS, Strings.create(didKey),
			IAT, nowSecs,
			EXP, nowSecs + tokenLifetime
		);
		if (audience != null) claims = claims.assoc(AUD, Strings.create(audience));
		AString jwt = JWT.signPublic(claims, keyPair);
		builder.header("Authorization", "Bearer " + jwt);
	}

	/**
	 * Get the DID (did:key) for this key pair.
	 * @return The did:key string
	 */
	@Override
	public String getDID() {
		return didKey;
	}
}
