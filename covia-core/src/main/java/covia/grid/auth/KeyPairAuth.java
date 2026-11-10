package covia.grid.auth;

import java.net.http.HttpRequest;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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
 *   <li>{@code sub} — normally the key's {@code did:key}, or a named DID
 *       the key is authorised for</li>
 *   <li>{@code iss} — same as {@code sub} (self-issued)</li>
 *   <li>{@code aud} — the DID of the venue the request goes to, as the
 *       connection established it, so a captured JWT cannot be replayed at
 *       any other venue</li>
 *   <li>{@code iat} — current time</li>
 *   <li>{@code exp} — current time + token lifetime</li>
 * </ul>
 *
 * <p>For a key-derived identity the JWT's {@code kid} header is the bare
 * Multikey. For a named identity it is the verification-method DID URL
 * {@code <subject>#<multikey>} published by that identity's DID document.
 */
class KeyPairAuth extends VenueAuth {

	/** Default token lifetime: 5 minutes */
	public static final long DEFAULT_TOKEN_LIFETIME = 300;

	private final AKeyPair keyPair;
	private final long tokenLifetime;
	private final String didKey;
	/** Identity asserted in {@code iss}/{@code sub}; normally {@link #didKey}. */
	private final String subject;
	/** JOSE key identifier: bare Multikey, or the named subject's DID URL. */
	private final AString keyID;
	/** The time a token is issued at, in epoch milliseconds. */
	private final LongSupplier clock;

	/** As {@link #KeyPairAuth(AKeyPair, String, long, LongSupplier)}, on the system clock. */
	KeyPairAuth(AKeyPair keyPair, String subject, long tokenLifetime) {
		this(keyPair, subject, tokenLifetime, System::currentTimeMillis);
	}

	/**
	 * Creates a key-pair authenticator whose subject may differ from the
	 * signing key's {@code did:key}. A local named user requires a registered
	 * authentication key; an identity nested below the signing key's DID does not.
	 * Authentication alone grants neither user admission nor parent authority.
	 *
	 * @param subject named identity, or null for the key's own {@code did:key}
	 * @param clock the time each token is issued at, in epoch milliseconds
	 */
	KeyPairAuth(AKeyPair keyPair, String subject, long tokenLifetime, LongSupplier clock) {
		if (keyPair == null) {
			throw new IllegalArgumentException("Key pair must not be null");
		}
		if (tokenLifetime <= 0) {
			throw new IllegalArgumentException("Token lifetime must be positive");
		}
		this.keyPair = keyPair;
		this.tokenLifetime = tokenLifetime;
		this.clock = Objects.requireNonNull(clock, "clock");
		// Pre-compute the did:key from the public key
		AString multikey = Multikey.encodePublicKey(keyPair.getAccountKey());
		this.didKey = "did:key:" + multikey;
		this.subject = (subject != null) ? subject : didKey;
		this.keyID = (subject != null)
			? Strings.create(this.subject + "#" + multikey)
			: multikey;
	}

	@Override
	public void apply(HttpRequest.Builder builder, Supplier<String> venueDID) {
		builder.header("Authorization", "Bearer " + buildToken(venueDID.get()));
	}

	@Override
	public String mintToken(String venueDID) {
		requireDID(venueDID, "venueDID");
		return buildToken(venueDID).toString();
	}

	/**
	 * The ONE place the claim set exists — {@code sub}/{@code iss} = the
	 * configured subject, {@code aud} = the venue, {@code iat} now,
	 * {@code exp} now + lifetime — signed with {@link JWT#signPublic}.
	 * Shared by {@link #apply} and {@link #mintToken} so a minted token is
	 * exactly what a request would carry, by construction.
	 */
	private AString buildToken(String venueDID) {
		long nowSecs = clock.getAsLong() / 1000;
		AMap<AString, ACell> claims = Maps.of(
			JWT.SUB, Strings.create(subject),
			JWT.ISS, Strings.create(subject),
			JWT.AUD, Strings.create(venueDID),
			JWT.IAT, nowSecs,
			JWT.EXP, nowSecs + tokenLifetime
		);
		return JWT.signPublic(claims, keyPair, keyID);
	}

	/**
	 * Get the identity asserted by this authentication strategy.
	 * @return the stable subject DID
	 */
	@Override
	public String getDID() {
		return subject;
	}
}
