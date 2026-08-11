package covia.venue.auth;

import java.security.interfaces.RSAPublicKey;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.auth.did.DID;
import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.venue.Auth;
import covia.venue.Engine;
import covia.venue.UcanJwtValidator;
import covia.venue.server.AuthMiddleware;
import io.javalin.http.Context;

/**
 * Public authentication service for venue embedders.
 *
 * <p>This is the single policy implementation for credentials accepted by a
 * venue: self-issued and named-user EdDSA tokens, venue-issued sessions, UCAN
 * bearers, and configured external OAuth providers. Embedders should use this
 * service rather than reproducing the venue's signature, audience, temporal,
 * or local-user mapping rules.</p>
 *
 * <p>Authentication does not admit or create a venue user. Route middleware or
 * an embedder may apply admission separately when the route requires it.</p>
 */
public final class VenueAuthenticator {
	private static final Logger log =
		LoggerFactory.getLogger(VenueAuthenticator.class);

	private static final String AUTHENTICATED_IDENTITY_ATTR = "authenticatedIdentity";
	private static final String VENUE_USER_ATTR = "callerDID";

	private static final AString SUB = Fields.SUB;
	private static final AString KID = Fields.KID;
	private static final AString EMAIL = Fields.EMAIL;
	private static final AString ISS = Strings.intern("iss");
	private static final AString AUD = Strings.intern("aud");
	private static final AString EXP = Strings.intern("exp");
	private static final AString NBF = Strings.intern("nbf");

	/** Clock-skew leeway for JWT temporal bounds, in seconds. */
	private static final long CLOCK_SKEW_SECONDS = 60;

	private final AccountKey venueKey;
	private final AString venueDID;
	private final Auth venueAuth;
	private final Map<String, OAuthConfig> externalProviders;
	private final String audiencePolicy;
	private final Set<String> acceptedAudienceStrings;
	private final Set<AString> acceptedAudiences;
	private final Engine engine;

	private record VerifiedPrincipal(
			AString authenticatedIdentity,
			AString venueUserDID,
			boolean ucanBearer) {}

	private static final class AudienceRejected extends RuntimeException {
		private static final long serialVersionUID = 1L;

		AudienceRejected(String message) {
			super(message);
		}
	}

	/**
	 * Creates the authenticator for an engine. Venue embedders normally obtain
	 * this instance from {@code VenueServer.authenticator()}.
	 */
	public VenueAuthenticator(Engine engine) {
		if (engine == null) throw new IllegalArgumentException("engine is required");
		this.engine = engine;
		this.venueKey = engine.getAccountKey();
		this.venueDID = engine.getDIDString();
		this.venueAuth = engine.getAuth();
		this.externalProviders = venueAuth.getLoginProviders().hasProviders()
			? venueAuth.getLoginProviders().getProviders() : null;
		this.audiencePolicy = venueAuth.getAudiencePolicy();

		Set<String> audienceStrings = new HashSet<>();
		audienceStrings.add(venueDID.toString());
		audienceStrings.addAll(venueAuth.getConfiguredAudiences());
		AString webDID = venueAuth.getWebDID();
		if (webDID != null) audienceStrings.add(webDID.toString());
		// The key-derived did:key is always an accepted audience: a declared
		// did:web identity (covia#343) must not orphan clients that
		// audience-bind to the venue's key form.
		if (venueKey != null) {
			audienceStrings.add("did:key:" + Multikey.encodePublicKey(venueKey));
		}
		this.acceptedAudienceStrings = Set.copyOf(audienceStrings);

		Set<AString> audiences = new HashSet<>();
		for (String audience : audienceStrings) {
			audiences.add(Strings.create(audience));
		}
		this.acceptedAudiences = Set.copyOf(audiences);
	}

	/**
	 * Authenticates a credential and returns the effective local venue user.
	 * This form does not bind a request or admit the user.
	 *
	 * @throws AuthException if the token is absent, malformed, expired, not yet
	 *         valid, incorrectly audienced, or otherwise not accepted
	 */
	public AString authenticate(AString token) throws AuthException {
		return verify(token).venueUserDID();
	}

	/**
	 * Authenticates a credential and binds both the directly proven identity and
	 * effective venue user to a Javalin request. A UCAN bearer is also retained
	 * for downstream capability processing. This does not admit the user.
	 *
	 * @return the effective local venue user
	 * @throws AuthException if the token is not accepted
	 */
	public AString authenticate(Context context, AString token) throws AuthException {
		if (context == null) throw new IllegalArgumentException("context is required");
		VerifiedPrincipal principal = verify(token);
		bindIdentity(context, principal.authenticatedIdentity(), principal.venueUserDID());
		if (principal.ucanBearer()) {
			context.attribute(AuthMiddleware.UCAN_BEARER_ATTR, token);
		}
		return principal.venueUserDID();
	}

	/**
	 * Publishes an identity established by embedder-owned authentication.
	 * This is attribution only: it does not verify credentials or admit a user.
	 */
	public void bindIdentity(Context context, AString authenticatedIdentity,
			AString venueUserDID) {
		if (context == null) throw new IllegalArgumentException("context is required");
		if (authenticatedIdentity == null) {
			throw new IllegalArgumentException("authenticatedIdentity is required");
		}
		if (venueUserDID == null) {
			throw new IllegalArgumentException("venueUserDID is required");
		}
		context.attribute(AUTHENTICATED_IDENTITY_ATTR, authenticatedIdentity);
		context.attribute(VENUE_USER_ATTR, venueUserDID);
	}

	/** Returns the identity directly proven by the request credential. */
	public AString authenticatedIdentity(Context context) {
		return context.attribute(AUTHENTICATED_IDENTITY_ATTR);
	}

	/** Returns the effective local venue user bound to the request. */
	public AString authenticatedUser(Context context) {
		return context.attribute(VENUE_USER_ATTR);
	}

	/** Returns the immutable set of JWT audiences accepted by this venue. */
	public Set<AString> acceptedAudiences() {
		return acceptedAudiences;
	}

	private VerifiedPrincipal verify(AString token) {
		if (token == null || token.toString().isBlank()) {
			throw new AuthException("Authentication required");
		}
		try {
			VerifiedPrincipal principal = tryVerifyUCAN(token);
			if (principal == null) principal = tryVerifySelfIssued(token);
			if (principal == null && venueKey != null) {
				principal = tryVerifyVenueSigned(token);
			}
			if (principal == null && externalProviders != null) {
				principal = tryVerifyExternalProvider(token);
			}
			if (principal == null) {
				throw new AuthException("Invalid or expired token");
			}
			return principal;
		} catch (AudienceRejected e) {
			throw new AuthException("Token audience not accepted by this venue");
		} catch (AuthException e) {
			throw e;
		} catch (Exception e) {
			log.warn("Error processing authentication token", e);
			throw new AuthException("Authentication failed", e);
		}
	}

	private void requireAudience(ACell aud) {
		if (aud == null) {
			if ("require".equals(audiencePolicy)) {
				throw new AudienceRejected("audience (aud) is required but absent");
			}
			return;
		}
		if (aud instanceof AString s) {
			if (!acceptedAudienceStrings.contains(s.toString())) {
				throw new AudienceRejected("token audience is not this venue: " + s);
			}
			return;
		}
		if (aud instanceof AVector<?> arr) {
			for (long i = 0; i < arr.count(); i++) {
				AString member = RT.ensureString(arr.get(i));
				if (member != null
						&& acceptedAudienceStrings.contains(member.toString())) return;
			}
			throw new AudienceRejected(
				"token audience list does not include this venue");
		}
		throw new AudienceRejected("malformed audience (aud) claim");
	}

	private static boolean temporalValid(AMap<AString, ACell> claims, long now) {
		CVMLong exp = RT.ensureLong(claims.get(EXP));
		if (exp != null && now > exp.longValue() + CLOCK_SKEW_SECONDS) return false;
		CVMLong nbf = RT.ensureLong(claims.get(NBF));
		if (nbf != null && now < nbf.longValue() - CLOCK_SKEW_SECONDS) return false;
		return true;
	}

	private VerifiedPrincipal tryVerifyUCAN(AString jwt) {
		JWT parsed = JWT.parse(jwt);
		AMap<AString, ACell> claims = parsed == null ? null : parsed.getClaims();
		if (claims == null || claims.get(UCAN.ATT) == null) return null;

		long now = System.currentTimeMillis() / 1000;
		// Signature + temporal bounds under the venue's DID verifier, so a
		// did:web-identified issuer (covia#343) verifies exactly like did:key.
		UCAN token = UcanJwtValidator.validateJWT(jwt, now, engine.didVerifier());
		if (token == null) return null;
		AString issuer = token.getIssuer();
		if (issuer == null) return null;
		requireAudience(token.getAudience());
		return new VerifiedPrincipal(issuer, issuer, true);
	}

	private VerifiedPrincipal tryVerifySelfIssued(AString jwt) {
		JWT parsed = JWT.parse(jwt);
		if (parsed == null || !"EdDSA".equals(parsed.getAlgorithm())) return null;
		AMap<AString, ACell> claims = parsed.getClaims();

		AString sub = RT.ensureString(claims.get(SUB));
		if (sub == null) return null;
		AString keyDID = authenticationKeyDID(jwt, sub);
		if (keyDID == null) return null;
		AccountKey signingKey = Multikey.decodePublicKey(
			keyDID.toString().substring("did:key:".length()));
		if (signingKey == null || JWT.verifyPublic(jwt, signingKey) == null) return null;

		String subject = sub.toString();
		if (subject.startsWith("did:key:")) {
			if (!sub.equals(keyDID)) return null;
		} else {
			AString userId = engine.managedUserName(sub);
			if (userId == null || !sub.equals(RT.ensureString(claims.get(ISS)))) return null;
			AMap<AString, ACell> record = venueAuth.getUser(userId);
			if (record == null || !sub.equals(record.get(Fields.DID))) return null;
			if (!venueAuth.isAuthenticationKeyActive(userId, keyDID)) return null;
		}

		if (!temporalValid(claims, System.currentTimeMillis() / 1000)) return null;
		requireAudience(claims.get(AUD));
		return new VerifiedPrincipal(keyDID, sub, false);
	}

	private VerifiedPrincipal tryVerifyVenueSigned(AString jwt) {
		AMap<AString, ACell> claims = JWT.verifyPublic(jwt, venueKey);
		if (claims == null) return null;
		if (!venueDID.equals(RT.ensureString(claims.get(ISS)))) return null;
		if (!temporalValid(claims, System.currentTimeMillis() / 1000)) return null;
		requireAudience(claims.get(AUD));

		AString sub = RT.ensureString(claims.get(SUB));
		if (sub == null) return null;
		try {
			if (DID.fromString(sub.toString()) == null) return null;
		} catch (RuntimeException e) {
			return null;
		}
		return new VerifiedPrincipal(sub, sub, false);
	}

	private static AString authenticationKeyDID(AString jwt, AString subject) {
		try {
			JWT parsed = JWT.parse(jwt);
			if (parsed == null) return null;
			AString kid = RT.ensureString(parsed.getHeader().get(KID));
			if (kid == null) return null;
			String value = kid.toString();
			String multikey;
			if (value.startsWith("did:key:")) {
				multikey = value.substring("did:key:".length());
			} else {
				String namedPrefix = subject + "#";
				multikey = value.startsWith(namedPrefix)
					? value.substring(namedPrefix.length()) : value;
			}
			if (Multikey.decodePublicKey(multikey) == null) return null;
			return Strings.create("did:key:" + multikey);
		} catch (Exception e) {
			return null;
		}
	}

	private VerifiedPrincipal tryVerifyExternalProvider(AString jwt) {
		try {
			JWT parsed = JWT.parse(jwt);
			if (parsed == null || !"RS256".equals(parsed.getAlgorithm())) return null;
			String kid = parsed.getKeyID();
			if (kid == null) return null;

			for (OAuthConfig provider : externalProviders.values()) {
				if (provider.jwksUri == null) continue;
				RSAPublicKey key = JWKSClient.getKey(provider.jwksUri, kid);
				if (key == null || !parsed.verifyRS256(key)) continue;
				if (!parsed.validateClaims(provider.issuer, provider.clientId)) continue;

				AMap<AString, ACell> claims = parsed.getClaims();
				AString email = RT.ensureString(claims.get(EMAIL));
				if (email == null) return null;
				AString venueUserDID = findUserDIDByEmail(email);
				AString subject = RT.ensureString(claims.get(SUB));
				if (venueUserDID == null) return null;
				if (subject == null) subject = email;
				return new VerifiedPrincipal(subject, venueUserDID, false);
			}
		} catch (Exception e) {
			// A provider miss is indistinguishable from the other verifier misses.
			log.debug("External provider JWT verification failed", e);
		}
		return null;
	}

	private AString findUserDIDByEmail(AString email) {
		AMap<AString, AMap<AString, ACell>> users = venueAuth.getUsers();
		if (users == null) return null;
		for (var entry : users.entrySet()) {
			AMap<AString, ACell> record = entry.getValue();
			if (email.equals(record.get(EMAIL))) {
				AString did = RT.ensureString(record.get(Fields.DID));
				if (did != null) return did;
			}
		}
		return null;
	}
}
