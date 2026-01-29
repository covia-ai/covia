package covia.venue.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.interfaces.RSAPublicKey;
import java.util.Map;

import convex.core.crypto.util.Multikey;
import convex.core.data.AccountKey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.json.JWT;
import convex.core.lang.RT;
import covia.venue.auth.JWKSClient;
import covia.venue.auth.OAuthConfig;
import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * Middleware for extracting caller identity from JWT bearer tokens.
 *
 * Supports three verification modes:
 * 1. Self-issued EdDSA JWTs: the kid header encodes the signer's public key,
 *    and the sub claim must be a did:key matching that key.
 * 2. Venue-signed JWTs: signed by the venue's own key. The sub claim contains
 *    the user's DID (e.g. did:web:venue.host:u:alice).
 * 3. External provider RS256 JWTs: verified against configured OAuth provider
 *    JWKS endpoints. The email or sub claim is extracted as identity.
 *
 * Anonymous requests (no Authorization header) are allowed — the caller DID
 * is simply null.
 */
public class AuthMiddleware {

	private static final Logger log = LoggerFactory.getLogger(AuthMiddleware.class);

	static final String CALLER_DID_ATTR = "callerDID";
	private static final AString SUB = Strings.create("sub");
	private static final AString KID = Strings.create("kid");
	private static final AString EMAIL = Strings.create("email");

	private static AccountKey venueKey;
	private static Map<String, OAuthConfig> externalProviders;

	/**
	 * Register auth middleware on API paths.
	 * @param app Javalin application
	 * @param venueAccountKey The venue's public key for verifying venue-signed JWTs
	 */
	public static void register(Javalin app, AccountKey venueAccountKey) {
		register(app, venueAccountKey, null);
	}

	/**
	 * Register auth middleware on API paths with external OAuth provider support.
	 * @param app Javalin application
	 * @param venueAccountKey The venue's public key for verifying venue-signed JWTs
	 * @param providers Configured OAuth providers for RS256 token verification, or null
	 */
	public static void register(Javalin app, AccountKey venueAccountKey, Map<String, OAuthConfig> providers) {
		venueKey = venueAccountKey;
		externalProviders = providers;
		app.before("/api/*", AuthMiddleware::extractIdentity);
		app.before("/a2a", AuthMiddleware::extractIdentity);
		app.before("/mcp", AuthMiddleware::extractIdentity);
	}

	static void extractIdentity(Context ctx) {
		String auth = ctx.header("Authorization");
		if (auth == null || !auth.startsWith("Bearer ")) return;

		String token = auth.substring(7).trim();
		if (token.isEmpty()) return;

		try {
			AString jwt = Strings.create(token);
			AString callerDID = tryVerifySelfIssued(jwt);
			if (callerDID == null && venueKey != null) {
				callerDID = tryVerifyVenueSigned(jwt);
			}
			if (callerDID == null && externalProviders != null) {
				callerDID = tryVerifyExternalProvider(jwt);
			}
			if (callerDID != null) {
				ctx.attribute(CALLER_DID_ATTR, callerDID);
			} else {
				log.debug("JWT bearer token failed all verification paths");
			}
		} catch (Exception e) {
			log.debug("Error processing bearer token", e);
		}
	}

	/**
	 * Verify a self-issued JWT where the kid header matches the did:key in the sub claim.
	 */
	private static AString tryVerifySelfIssued(AString jwt) {
		AMap<AString, ACell> claims = JWT.verifyPublic(jwt);
		if (claims == null) return null;

		AString sub = RT.ensureString(claims.get(SUB));
		if (sub == null) return null;

		// For self-issued tokens, verify kid matches the did:key in sub
		String subStr = sub.toString();
		if (subStr.startsWith("did:key:")) {
			// Extract multikey from did:key:z6Mk... and verify it matches the kid
			String multikey = subStr.substring("did:key:".length());
			AccountKey subKey = Multikey.decodePublicKey(multikey);
			if (subKey == null) return null;

			// Decode kid from header to compare
			AccountKey kidKey = extractKidKey(jwt);
			if (kidKey == null || !kidKey.equals(subKey)) return null;
		}
		// Non did:key subs can't be self-issued (need venue or external authority)
		else {
			return null;
		}

		return sub;
	}

	/**
	 * Verify a venue-signed JWT. The venue's key is the trusted signer.
	 * The sub claim contains the user's DID.
	 */
	private static AString tryVerifyVenueSigned(AString jwt) {
		AMap<AString, ACell> claims = JWT.verifyPublic(jwt, venueKey);
		if (claims == null) return null;

		return RT.ensureString(claims.get(SUB));
	}

	/**
	 * Extract the AccountKey from the kid header of a JWT.
	 */
	private static AccountKey extractKidKey(AString jwt) {
		try {
			String s = jwt.toString();
			int dot1 = s.indexOf('.');
			if (dot1 < 0) return null;
			String headerB64 = s.substring(0, dot1);
			byte[] headerBytes = java.util.Base64.getUrlDecoder().decode(headerB64);
			AMap<AString, ACell> header = RT.ensureMap(
				convex.core.util.JSON.parse(Strings.wrap(headerBytes)));
			if (header == null) return null;
			AString kid = RT.ensureString(header.get(KID));
			if (kid == null) return null;
			return Multikey.decodePublicKey(kid.toString());
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Verify an RS256 JWT from a configured external OAuth provider.
	 * Tries each provider's JWKS endpoint to find a matching key.
	 */
	private static AString tryVerifyExternalProvider(AString jwt) {
		try {
			JWT parsed = JWT.parse(jwt);
			if (parsed == null) return null;
			if (!"RS256".equals(parsed.getAlgorithm())) return null;

			String kid = parsed.getKeyID();
			if (kid == null) return null;

			for (OAuthConfig provider : externalProviders.values()) {
				if (provider.jwksUri == null) continue;

				RSAPublicKey key = JWKSClient.getKey(provider.jwksUri, kid);
				if (key == null) continue;
				if (!parsed.verifyRS256(key)) continue;
				if (!parsed.validateClaims(provider.issuer, provider.clientId)) continue;

				// Valid — extract email or sub as identity
				AMap<AString, ACell> claims = parsed.getClaims();
				AString email = RT.ensureString(claims.get(EMAIL));
				if (email != null) return email;
				AString sub = RT.ensureString(claims.get(SUB));
				return sub;
			}
		} catch (Exception e) {
			log.debug("External provider JWT verification failed", e);
		}
		return null;
	}

	/**
	 * Get the caller DID from the request context, if identified.
	 * @param ctx Javalin context
	 * @return Caller DID as AString, or null if anonymous
	 */
	public static AString getCallerDID(Context ctx) {
		return ctx.attribute(CALLER_DID_ATTR);
	}
}
