package covia.grid.auth;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.exception.ResponseException;
import covia.grid.client.VenueHTTP;

/**
 * Proof by challenge that the venue a connection reaches holds a DID (COG-21).
 *
 * <p>The verifier sends the venue a fresh nonce, and the venue answers with a
 * JWT signed by its DID's key that names the nonce and the venue's configured
 * base URL. The base binds the proof to the address the venue is configured
 * at, so it proves nothing at another address; the nonce binds it to one
 * request, so it proves nothing a second time.</p>
 *
 * <p>Only a {@code did:key} DID's key is resolved here; a {@code did:web}
 * venue is proven by its address ({@link VenueDID#provenAt}).</p>
 */
public final class VenueChallenge {

	/** The {@code typ} of a challenge proof. No other token a venue key signs carries it. */
	public static final String TYP = "venue-proof+jwt";

	/** API path, under {@code /api/v1/}, at which a venue answers a challenge. */
	public static final String PATH = "challenge";

	/** Request field carrying the nonce, and proof claim echoing it. */
	public static final AString NONCE = Strings.intern("nonce");

	/** Proof claim naming the venue's configured base URL. */
	public static final AString BASE = Strings.intern("base");

	/** Response field carrying the proof. */
	public static final AString PROOF = Strings.intern("proof");

	/** Random bytes in a nonce, sent base64url-encoded without padding. */
	public static final int NONCE_BYTES = 32;

	/** Longest validity a proof may state, from {@code iat} to {@code exp}, in seconds. */
	public static final long MAX_LIFETIME = 300;

	/** Clock difference tolerated between verifier and venue, in seconds. */
	public static final long CLOCK_SKEW = 60;

	/** Why a challenge does not prove the DID. */
	public static final class Reason {
		public static final String NO_KEY = "the DID's key cannot be resolved; a challenge proves a did:key only: ";
		public static final String UNREACHABLE = "the venue cannot be reached for a challenge: ";
		public static final String NOT_HTTP = "the venue is not reached over HTTP";
		public static final String NO_ANSWER = "the venue did not answer the challenge: ";
		public static final String NO_PROOF = "the venue's answer carries no proof";
		public static final String UNPARSEABLE = "the proof is not a JWT";
		public static final String WRONG_TYPE = "the proof's typ is not " + TYP;
		public static final String WRONG_ALGORITHM = "the proof is not signed with EdDSA";
		public static final String SIGNATURE_FAILS = "the proof is not signed by the DID's key";
		public static final String OTHER_ISSUER = "the proof's iss is not the DID";
		public static final String OTHER_NONCE = "the proof names another nonce";
		public static final String OTHER_BASE = "the proof names another base URL than the address dialled: ";
		public static final String NOT_A_BASE = "the address dialled is not an http or https base URL: ";
		public static final String SUBJECT_OR_AUDIENCE = "the proof has a sub or an aud";
		public static final String MALFORMED_TIMES = "the proof's iat and exp are not integer timestamps";
		public static final String LIFETIME_EXCEEDS = "the proof states a validity longer than " + MAX_LIFETIME + " seconds";
		public static final String NOT_YET_VALID = "the proof is issued in the future";
		public static final String EXPIRED = "the proof has expired";

		private Reason() {}
	}

	private static final SecureRandom RANDOM = new SecureRandom();

	private VenueChallenge() {}

	/**
	 * A fresh nonce: {@value #NONCE_BYTES} random bytes, base64url-encoded
	 * without padding.
	 *
	 * @return The nonce
	 */
	public static String nonce() {
		byte[] bytes = new byte[NONCE_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * The form in which a proof names a base URL, and in which the address
	 * dialled is compared with it: scheme and host in lowercase, the default
	 * port omitted, no trailing slash.
	 *
	 * @param url An absolute http or https URL
	 * @return The normalised URL, or null if {@code url} is not an http or
	 *         https URL with a host and without user information, query or fragment
	 */
	public static String normalise(String url) {
		if (url == null) return null;
		URI uri;
		try {
			uri = new URI(url.trim());
		} catch (URISyntaxException e) {
			return null;
		}
		String scheme = uri.getScheme();
		String host = uri.getHost();
		if (scheme == null || host == null || uri.getRawUserInfo() != null
				|| uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
		scheme = scheme.toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) return null;
		int port = uri.getPort();
		boolean defaultPort = port < 0 || (scheme.equals("http") && port == 80)
			|| (scheme.equals("https") && port == 443);
		String path = uri.getRawPath() == null ? "" : uri.getRawPath();
		while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
		return scheme + "://" + host.toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port) + path;
	}

	/**
	 * Proves that the venue a connection reaches holds {@code did}: sends it a
	 * fresh nonce and verifies the proof it answers with against the address
	 * the connection dials. The challenge carries no credential, whatever the
	 * connection authenticates as.
	 *
	 * @param venue Connection to the venue
	 * @param did The DID to prove
	 * @return null when proven, else the reason it is not
	 */
	public static String prove(VenueHTTP venue, String did) {
		if (UCAN.fromDIDKey(Strings.create(did)) == null) return Reason.NO_KEY + did;
		URI api = venue.getBaseURI();
		String dialled = api.getScheme() + "://" + api.getRawAuthority();
		String nonce = nonce();
		String proof;
		try {
			proof = venue.challenge(nonce).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Reason.NO_ANSWER + "interrupted";
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			return Reason.NO_ANSWER + (cause instanceof ResponseException re ? re.getMessage()
				: cause == null ? "no response" : cause.getClass().getSimpleName());
		}
		return verify(proof, did, nonce, dialled, System.currentTimeMillis() / 1000);
	}

	/**
	 * Whether {@code proof} proves {@code did} for a challenge sent with
	 * {@code nonce} to the venue at {@code base}. The signature is checked
	 * against the key {@code did} encodes, never one the header names.
	 *
	 * @param proof The proof JWT the venue answered with
	 * @param did The DID to prove
	 * @param nonce The nonce sent
	 * @param base The address dialled
	 * @param now The current time, in seconds since the epoch
	 * @return null when proven, else the reason it is not
	 */
	public static String verify(String proof, String did, String nonce, String base, long now) {
		AccountKey key = UCAN.fromDIDKey(Strings.create(did));
		if (key == null) return Reason.NO_KEY + did;
		if (proof == null) return Reason.NO_PROOF;
		JWT jwt = JWT.parse(Strings.create(proof));
		if (jwt == null) return Reason.UNPARSEABLE;
		AMap<AString, ACell> claims = jwt.getClaims();
		if (!TYP.equals(text(jwt.getHeader().get(JWT.TYP)))) return Reason.WRONG_TYPE;
		if (!"EdDSA".equals(jwt.getAlgorithm())) return Reason.WRONG_ALGORITHM;
		if (!jwt.verifyEdDSA(key)) return Reason.SIGNATURE_FAILS;
		if (!did.equals(text(claims.get(JWT.ISS)))) return Reason.OTHER_ISSUER;
		if (!nonce.equals(text(claims.get(NONCE)))) return Reason.OTHER_NONCE;
		String expected = normalise(base);
		if (expected == null) return Reason.NOT_A_BASE + base;
		String claimed = text(claims.get(BASE));
		if (!expected.equals(claimed)) return Reason.OTHER_BASE + claimed;
		if (claims.containsKey(JWT.SUB) || claims.containsKey(JWT.AUD)) return Reason.SUBJECT_OR_AUDIENCE;
		CVMLong iat = RT.ensureLong(claims.get(JWT.IAT));
		CVMLong exp = RT.ensureLong(claims.get(JWT.EXP));
		if (iat == null || exp == null || exp.longValue() < iat.longValue()) return Reason.MALFORMED_TIMES;
		if (exp.longValue() - iat.longValue() > MAX_LIFETIME) return Reason.LIFETIME_EXCEEDS;
		if (iat.longValue() > now + CLOCK_SKEW) return Reason.NOT_YET_VALID;
		if (exp.longValue() < now - CLOCK_SKEW) return Reason.EXPIRED;
		return null;
	}

	private static String text(ACell cell) {
		AString s = RT.ensureString(cell);
		return s == null ? null : s.toString();
	}
}
