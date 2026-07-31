package covia.venue.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.interfaces.RSAPublicKey;
import java.util.Map;
import java.util.Set;

import convex.core.crypto.util.Multikey;
import convex.core.data.AccountKey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.auth.did.DID;
import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.auth.did.DIDVerifier;
import convex.auth.ucan.UCANValidator;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.venue.Auth;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.api.ACoviaAPI;
import covia.venue.auth.JWKSClient;
import covia.venue.auth.OAuthConfig;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.security.RouteRole;

/**
 * Middleware for extracting caller identity from JWT bearer tokens.
 *
 * Supports three verification modes:
 * 1. Self-issued EdDSA JWTs: the kid header encodes the signer's public key.
 *    The sub claim is either the matching did:key or a local named-user DID
 *    whose venue-owned authentication record admits that key.
 * 2. Venue-signed JWTs: signed by the venue's own key. The sub claim contains
 *    the user's DID (e.g. did:web:venue.host:u:alice).
 * 3. External provider RS256 JWTs: verified against configured OAuth provider
 *    JWKS endpoints. The email or sub claim is extracted as identity.
 *
 * Anonymous requests (no Authorization header) are allowed when
 * {@code auth.public.enabled} is true. Otherwise, requests without a valid
 * token are rejected with 401.
 */
public class AuthMiddleware {

	private static final Logger log = LoggerFactory.getLogger(AuthMiddleware.class);

	static final String AUTHENTICATED_IDENTITY_ATTR = "authenticatedIdentity";
	static final String CALLER_DID_ATTR = "callerDID";
	/**
	 * Context attribute holding the capability grant scope for an unauthenticated
	 * (public) caller, derived from {@code auth.public.caps}. Absent for
	 * authenticated callers (unrestricted unless a transport token attenuates).
	 */
	static final String CALLER_CAPS_ATTR = "callerCaps";
	/**
	 * Context attribute holding the raw UCAN JWT extracted from an
	 * {@code Authorization: Bearer ...} header, when the bearer is a valid
	 * UCAN (and so also serves as caller authentication). Downstream handlers
	 * merge this into their transport {@code ucans} vector via
	 * {@link UCANValidator#parseTransportUCANsWithBearer}. Null when the
	 * request has no bearer token or the bearer is not a UCAN.
	 */
	public static final String UCAN_BEARER_ATTR = "ucanBearer";
	private static final AString SUB = Fields.SUB;
	private static final AString KID = Fields.KID;
	private static final AString EMAIL = Fields.EMAIL;
	private static final AString ISS = Strings.intern("iss");
	private static final AString AUD = Strings.intern("aud");
	private static final AString EXP = Strings.intern("exp");
	private static final AString NBF = Strings.intern("nbf");

	// Per-instance state. Was static, which made running multiple VenueServers
	// in the same JVM (production multi-tenant, parallel test classes) racy:
	// every register() call would trample the previous instance's fields,
	// causing requests to be attributed to the wrong venue's public DID
	// and 403'd by AccessControl on cross-venue job lookups.
	private final AccountKey venueKey;
	private final AString venueDID;
	private final AString publicDID;
	private final Auth venueAuth;
	private final Map<String, OAuthConfig> externalProviders;
	private final boolean publicAccessEnabled;
	private final AVector<ACell> publicScope;
	private final String audiencePolicy;                  // "verify" | "require"
	private final java.util.Set<String> acceptedAudiences;
	private final Engine engine;

	private AuthMiddleware(Engine engine) {
		this.engine = engine;
		this.venueKey = engine.getAccountKey();
		this.venueDID = engine.getDIDString();
		this.publicDID = Strings.create(venueDID + ":public");
		Auth auth = engine.getAuth();
		this.venueAuth = auth;
		this.publicAccessEnabled = auth.isPublicAccessEnabled();
		this.externalProviders = auth.getLoginProviders().hasProviders()
			? auth.getLoginProviders().getProviders() : null;
		// Capability grant scope for public callers — secure read-only by default,
		// operator-overridable via auth.public.caps. Only relevant when public
		// access is enabled (otherwise every anonymous request is 401'd).
		this.publicScope = publicAccessEnabled ? auth.getPublicScope(publicDID) : null;
		// Accepted JWT audiences: the venue's own published DID (whatever its
		// method — did:key, did:web, …) plus any operator allowlist. A bearer
		// token's `aud` must name one of these (RFC 7519 §4.1.3), so a token
		// minted for another venue cannot be replayed here. We do NOT assume the
		// venue's identity is the did:key of its signing key — a did:web venue
		// publishes did:web, and clients audience tokens to the DID resolved from
		// did.json; operators add any additional forms via auth.acceptedAudiences.
		this.audiencePolicy = auth.getAudiencePolicy();
		java.util.Set<String> aud = new java.util.HashSet<>();
		aud.add(venueDID.toString()); // the venue's canonical published DID
		aud.addAll(auth.getConfiguredAudiences());
		// The did:web alias (covia#167): strictly-resolving clients audience
		// their tokens to the DID they resolved from /.well-known/did.json,
		// which is the did:web form when a public hostname is configured.
		// Accepting the alias string here does not change the venue's canonical
		// did:key identity — validation still uses the venue's own key.
		AString webDID = auth.getWebDID();
		if (webDID != null) aud.add(webDID.toString());
		this.acceptedAudiences = aud;
	}

	/** Thrown when a bearer token is otherwise valid (signature, temporal bounds)
	 *  but its audience does not satisfy the audience policy — surfaced as a hard
	 *  401, never a silent downgrade to the public identity. */
	private static final class AudienceRejected extends RuntimeException {
		private static final long serialVersionUID = 1L;
		AudienceRejected(String message) { super(message); }
	}

	/**
	 * The principal proven by a credential and the local venue user it maps to.
	 * They are normally equal for self-sovereign users, but differ for a named
	 * venue user authenticated by one of its registered keys.
	 */
	private record VerifiedPrincipal(
			AString authenticatedIdentity,
			AString venueUserDID) {}

	/**
	 * Enforces RFC 7519 §4.1.3 audience restriction. {@code aud} may be a string
	 * or an array of strings (StringOrURI), compared case-sensitively. A present
	 * {@code aud} must name an accepted audience; an absent {@code aud} is
	 * governed by the policy ({@code require} rejects, {@code verify} accepts).
	 *
	 * @throws AudienceRejected when the audience is not accepted
	 */
	private void requireAudience(ACell aud) {
		if (aud == null) {
			if ("require".equals(audiencePolicy)) {
				throw new AudienceRejected("audience (aud) is required but absent");
			}
			return; // verify: tolerate an absent aud (warn-then-enforce migration)
		}
		if (aud instanceof AString s) {
			if (!acceptedAudiences.contains(s.toString())) {
				throw new AudienceRejected("token audience is not this venue: " + s);
			}
			return;
		}
		if (aud instanceof AVector<?> arr) {
			for (long i = 0; i < arr.count(); i++) {
				AString m = RT.ensureString(arr.get(i));
				if (m != null && acceptedAudiences.contains(m.toString())) return;
			}
			throw new AudienceRejected("token audience list does not include this venue");
		}
		throw new AudienceRejected("malformed audience (aud) claim");
	}

	/** Clock-skew leeway (seconds) applied to JWT temporal bounds — a small grace
	 *  for clock drift between the signer and this venue (RFC 7519 permits it). */
	private static final long CLOCK_SKEW_SECONDS = 60;

	/** Checks JWT temporal bounds ({@code exp}/{@code nbf}) against {@code now}
	 *  (epoch seconds), with {@link #CLOCK_SKEW_SECONDS} leeway: false if expired
	 *  or not-yet-valid; a token with no bounds is temporally unbounded (true). */
	private static boolean temporalValid(AMap<AString, ACell> claims, long now) {
		CVMLong exp = RT.ensureLong(claims.get(EXP));
		if (exp != null && now > exp.longValue() + CLOCK_SKEW_SECONDS) return false;
		CVMLong nbf = RT.ensureLong(claims.get(NBF));
		if (nbf != null && now < nbf.longValue() - CLOCK_SKEW_SECONDS) return false;
		return true;
	}

	/**
	 * Register role-selected auth middleware. Each call creates a fresh
	 * {@link AuthMiddleware} instance bound to the supplied venue identity.
	 * Only endpoints carrying an authentication-related
	 * {@link VenueRouteFeature} are inspected, so multiple VenueServers in the
	 * same JVM do not share state and embedder routes stay independent.
	 *
	 * @param routes Javalin routes configuration
	 * @param engine venue engine providing identity, authentication and user admission
	 * @return The constructed middleware instance (rarely needed by callers,
	 *         but useful for tests).
	 */
	public static AuthMiddleware register(RoutesConfig routes, Engine engine) {
		AuthMiddleware mw = new AuthMiddleware(engine);
		routes.beforeMatched(mw::extractMatchedIdentity);
		return mw;
	}

	/**
	 * Applies authentication only when the matched endpoint explicitly requests
	 * a Covia route feature. An unmarked route is wholly extender-owned: bearer
	 * headers are not inspected and no venue user is admitted.
	 */
	private void extractMatchedIdentity(Context ctx) {
		Set<RouteRole> roles = ctx.routeRoles();
		if (VenueRouteFeature.has(roles, VenueRouteFeature.COVIA_MCP)) {
			Config config = engine.config();
			extractMCPIdentity(ctx, config.isMCPAuthRequired(),
				config.getMCPAllowedDids());
			return;
		}
		if (VenueRouteFeature.has(roles, VenueRouteFeature.COVIA_API)
				|| VenueRouteFeature.has(roles, VenueRouteFeature.COVIA_A2A)) {
			extractIdentity(ctx, publicAccessEnabled, null, true);
			return;
		}
		if (VenueRouteFeature.has(roles, VenueRouteFeature.ADMITTED_USER)) {
			extractIdentity(ctx, false, null, true);
			return;
		}
		if (VenueRouteFeature.has(roles,
				VenueRouteFeature.AUTHENTICATED_IDENTITY)) {
			extractIdentity(ctx, false, null, false);
		}
	}

	/**
	 * Attribute an unauthenticated request to the venue's public DID and stash
	 * the public capability grant scope (if any), so the downstream
	 * {@link #callerContext} applies it uniformly.
	 */
	private void markPublic(Context ctx) {
		ctx.attribute(CALLER_DID_ATTR, publicDID);
		if (publicScope != null) ctx.attribute(CALLER_CAPS_ATTR, publicScope);
	}

	/**
	 * Records the credential identity and its mapped venue user, optionally
	 * admitting the latter as a Covia venue user.
	 */
	private boolean markAuthenticated(Context ctx, VerifiedPrincipal principal,
			boolean admitUser) {
		try {
			if (admitUser) engine.admitUser(principal.venueUserDID());
			setRequestIdentity(ctx, principal.authenticatedIdentity(),
				principal.venueUserDID());
			return true;
		} catch (AuthException e) {
			ctx.status(403).result(e.getMessage());
			ctx.skipRemainingHandlers();
			return false;
		}
	}

	private void extractMCPIdentity(Context ctx, boolean required, Set<String> allowedDids) {
		String resourceMetadata = ACoviaAPI.getExternalBaseUrl(ctx, null)
			+ "/.well-known/oauth-protected-resource/mcp";
		extractIdentity(ctx, !required && publicAccessEnabled, resourceMetadata,
			true);
		AString venueUserDID = getVenueUserDID(ctx);
		if (venueUserDID == null) return; // authentication already rejected the request
		if (!allowedDids.isEmpty() && !allowedDids.contains(venueUserDID.toString())) {
			ctx.status(403).result("Caller DID is not allowed to use MCP");
			ctx.skipRemainingHandlers();
		}
	}

	private void rejectUnauthorized(Context ctx, String message, String resourceMetadata) {
		if (resourceMetadata != null) {
			ctx.header("WWW-Authenticate",
				"Bearer resource_metadata=\"" + resourceMetadata + "\"");
		}
		ctx.status(401).result(message);
		ctx.skipRemainingHandlers();
	}

	private void extractIdentity(Context ctx, boolean allowPublic,
			String resourceMetadata, boolean admitUser) {
		String auth = ctx.header("Authorization");
		if (auth == null || !auth.startsWith("Bearer ")) {
			if (!allowPublic) {
				rejectUnauthorized(ctx, "Authentication required", resourceMetadata);
			} else {
				markPublic(ctx);
			}
			return;
		}

		String token = auth.substring(7).trim();
		if (token.isEmpty()) {
			if (!allowPublic) {
				rejectUnauthorized(ctx, "Authentication required", resourceMetadata);
			} else {
				markPublic(ctx);
			}
			return;
		}

		try {
			AString jwt = Strings.create(token);
			// Try UCAN first — if the bearer is a UCAN, iss is the caller DID
			// and the token is also stashed as a capability proof for downstream
			// handlers (IETF UCAN-HTTP bearer semantics).
			VerifiedPrincipal principal = tryVerifyUCAN(jwt);
			if (principal != null) {
				ctx.attribute(UCAN_BEARER_ATTR, jwt);
			}
			if (principal == null) {
				principal = tryVerifySelfIssued(jwt);
			}
			if (principal == null && venueKey != null) {
				principal = tryVerifyVenueSigned(jwt);
			}
			if (principal == null && externalProviders != null) {
				principal = tryVerifyExternalProvider(jwt);
			}
			if (principal != null) {
				if (!markAuthenticated(ctx, principal, admitUser)) return;
			} else {
				// A presented token that fails every verification path is a hard
				// 401 — NEVER a silent downgrade to the public identity, even when
				// public access is enabled. Presenting credentials is an explicit
				// identity claim; failing that claim must be visible to the caller
				// (an anonymous request without a token still gets public access).
				log.debug("JWT bearer token failed all verification paths");
				rejectUnauthorized(ctx, "Invalid or expired token", resourceMetadata);
			}
		} catch (AudienceRejected e) {
			// A presented token failed the audience policy — a hard 401, NOT a
			// silent downgrade to the public identity (the replay defence).
			log.debug("Bearer token audience rejected: {}", e.getMessage());
			rejectUnauthorized(ctx, "Token audience not accepted by this venue",
				resourceMetadata);
		} catch (Exception e) {
			// Same rule for unexpected failures while verifying a PRESENTED token
			// (JWKS outage, parse bug, ...): fail the claim visibly. Warn-level —
			// this path is abnormal and should be diagnosable, not debug-hidden.
			log.warn("Error processing bearer token", e);
			rejectUnauthorized(ctx, "Authentication failed", resourceMetadata);
		}
	}

	/**
	 * Verify a UCAN bearer token. A token is classified as a UCAN by the presence
	 * of an {@code att} (capability) array — NOT by {@code aud}, since identity
	 * tokens now legitimately carry {@code aud} too (#149). The EdDSA signature is
	 * verified against the issuer key, temporal bounds and audience are checked.
	 * On success, the issuer DID is returned as the caller identity — matching the
	 * IETF UCAN-HTTP bearer convention that the invocation token's {@code iss} is
	 * the caller.
	 *
	 * @param jwt Bearer token
	 * @return issuer as both authenticated identity and venue user on success,
	 *         or null if the token is not a valid UCAN
	 */
	private VerifiedPrincipal tryVerifyUCAN(AString jwt) {
		UCAN token = UCAN.fromJWT(jwt);
		if (token == null) return null;
		// Classify by the presence of an `att` (capability) array — NOT `aud`.
		// Identity tokens now legitimately carry `aud` (#149), so aud-presence
		// would misroute them through the UCAN path. UCAN.getCapabilities()
		// collapses absent→empty, so inspect the raw claims: UCAN.create always
		// writes an `att` field (≥ empty); a plain identity JWT never does.
		JWT parsed = JWT.parse(jwt);
		AMap<AString, ACell> claims = (parsed != null) ? parsed.getClaims() : null;
		if (claims == null || claims.get(UCAN.ATT) == null) return null;
		// Bind the signature to the claimed issuer. Re-verify against the issuer's
		// OWN key so identity is attributed only when the signature actually
		// satisfies the iss key — regardless of how UCAN.fromJWT selects its
		// verification key. Otherwise anyone could sign with their key, set `iss`
		// to a victim DID, and be attributed that identity (issuer spoofing).
		// Mirrors the kid==sub bind that tryVerifySelfIssued enforces.
		AString issuer = token.getIssuer();
		AccountKey issuerKey = UCAN.fromDIDKey(issuer);
		if (issuerKey == null) return null;
		if (JWT.verifyPublic(jwt, issuerKey) == null) return null;
		long now = System.currentTimeMillis() / 1000;
		if (!UCANValidator.checkTemporalBounds(token, now)) return null;
		// Audience restriction (RFC 7519 §4.1.3): the bearer must be intended for
		// THIS venue, else a token minted for another venue could be replayed.
		requireAudience(token.getAudience());
		return new VerifiedPrincipal(issuer, issuer);
	}

	/**
	 * Verify a self-issued JWT. A did:key subject is bound directly to the
	 * header key; a local named-user subject is bound through the venue-owned
	 * authentication-key registry.
	 */
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

		String subStr = sub.toString();
		if (subStr.startsWith("did:key:")) {
			if (!sub.equals(keyDID)) return null;
		} else {
			AString userId = engine.managedUserName(sub);
			if (userId == null) return null; // never resolve a foreign did:web
			if (!sub.equals(RT.ensureString(claims.get(ISS)))) return null;
			AMap<AString, ACell> record = venueAuth.getUser(userId);
			if (record == null || !sub.equals(record.get(Fields.DID))) return null;
			if (!venueAuth.isAuthenticationKeyActive(userId, keyDID)) return null;
		}

		// Temporal bounds — previously unchecked, so a self-issued token was
		// accepted forever; reject expired / not-yet-valid tokens.
		if (!temporalValid(claims, System.currentTimeMillis() / 1000)) return null;
		// Audience restriction (RFC 7519 §4.1.3): a self-issued token minted for
		// another venue must not authenticate here.
		requireAudience(claims.get(AUD));
		return new VerifiedPrincipal(keyDID, sub);
	}

	/**
	 * Verify a venue-signed JWT. The venue's key is the trusted signer.
	 * The sub claim contains the user's DID.
	 */
	private VerifiedPrincipal tryVerifyVenueSigned(AString jwt) {
		AMap<AString, ACell> claims = JWT.verifyPublic(jwt, venueKey);
		if (claims == null) return null;

		// A venue session is an attestation BY this venue, not a bearer signed
		// "as" the managed user. Bind the claims to that model explicitly: a
		// token that verifies with the venue key must not claim another issuer, stale
		// sessions must expire, and tokens intended for another venue must not be
		// replayed here.
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
		return new VerifiedPrincipal(sub, sub);
	}

	/**
	 * Resolve the header {@code kid} to a canonical did:key. Convex JWT
	 * signers use the bare multikey; named-user-aware signers may use either
	 * {@code did:key:<multikey>} or {@code <subject>#<multikey>}.
	 */
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

	/**
	 * Verify an RS256 JWT from a configured external OAuth provider.
	 * Tries each provider's JWKS endpoint to find a matching key.
	 */
	private VerifiedPrincipal tryVerifyExternalProvider(AString jwt) {
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

				// Valid — look up existing user by email to get their DID
				AMap<AString, ACell> claims = parsed.getClaims();
				AString email = RT.ensureString(claims.get(EMAIL));
				if (email == null) return null;
				AString venueUserDID = findUserDIDByEmail(email);
				AString subject = RT.ensureString(claims.get(SUB));
				if (venueUserDID == null) return null;
				// Retain compatibility with providers that only supplied the
				// email previously used for the local mapping.
				if (subject == null) subject = email;
				return new VerifiedPrincipal(subject, venueUserDID);
			}
		} catch (Exception e) {
			log.debug("External provider JWT verification failed", e);
		}
		return null;
	}

	/**
	 * Look up an existing user by email in the venue's user database.
	 * Returns the user's DID if found, null otherwise.
	 */
	private AString findUserDIDByEmail(AString email) {
		if (venueAuth == null) return null;
		AMap<AString, AMap<AString, ACell>> users = venueAuth.getUsers();
		if (users == null) return null;

		// Search user records for matching email
		for (var entry : users.entrySet()) {
			AMap<AString, ACell> record = entry.getValue();
			if (email.equals(record.get(EMAIL))) {
				AString did = RT.ensureString(record.get(Fields.DID));
				if (did != null) return did;
			}
		}
		return null;
	}

	/**
	 * Set the two identities for a request authenticated by embedder-owned
	 * middleware. This is an attribution seam, not an authentication or admission
	 * operation: the extender must first verify the credential and decide which
	 * existing venue user it maps to.
	 *
	 * <p>Covia-native authorization, job ownership, rate limiting and
	 * {@link #callerContext} use {@code venueUserDID}. The separately retained
	 * {@code authenticatedIdentity} is available to extension handlers and logs
	 * without changing who acts inside the venue.</p>
	 *
	 * @param ctx Javalin request context
	 * @param authenticatedIdentity principal proven by the credential
	 * @param venueUserDID local venue user represented by that principal
	 */
	public static void setRequestIdentity(Context ctx,
			AString authenticatedIdentity, AString venueUserDID) {
		if (authenticatedIdentity == null) {
			throw new IllegalArgumentException("authenticatedIdentity is required");
		}
		if (venueUserDID == null) {
			throw new IllegalArgumentException("venueUserDID is required");
		}
		ctx.attribute(AUTHENTICATED_IDENTITY_ATTR, authenticatedIdentity);
		ctx.attribute(CALLER_DID_ATTR, venueUserDID);
	}

	/**
	 * Get the identity directly proven by the request credential. For a named
	 * user key this is the key's {@code did:key}; it can differ from
	 * {@link #getVenueUserDID(Context)}.
	 *
	 * @return authenticated identity, or null for public/unauthenticated routes
	 */
	public static AString getAuthenticatedIdentity(Context ctx) {
		return ctx.attribute(AUTHENTICATED_IDENTITY_ATTR);
	}

	/**
	 * Get the local venue user represented by this request. This is the identity
	 * used for admission, authorization and job ownership.
	 */
	public static AString getVenueUserDID(Context ctx) {
		return ctx.attribute(CALLER_DID_ATTR);
	}

	/**
	 * Get the effective caller DID from the request context.
	 * Always non-null for requests that pass through the middleware when
	 * public access is enabled (anonymous requests get the venue's public DID).
	 *
	 * <p>This compatibility name returns the venue user, not necessarily the
	 * identity that directly authenticated. New extension code should use
	 * {@link #getAuthenticatedIdentity(Context)} and
	 * {@link #getVenueUserDID(Context)} explicitly.</p>
	 *
	 * @param ctx Javalin context
	 * @return Caller DID as AString, or null if auth was required and missing
	 */
	public static AString getCallerDID(Context ctx) {
		return getVenueUserDID(ctx);
	}

	/**
	 * Builds the base {@link RequestContext} for an inbound request: the caller
	 * DID plus, for unauthenticated (public) callers, the configured capability
	 * grant scope ({@code auth.public.caps}, default read-only). This is the single
	 * seam where a request's grant scope is established, before transport-token
	 * authority ({@link #withTransportAuth}) is layered on. Null-safe: a null
	 * Javalin context (e.g. an MCP call with no HTTP context) yields
	 * {@link RequestContext#ANONYMOUS}.
	 *
	 * @param ctx Javalin context, or null
	 * @return the caller's request context with its grant scope applied
	 */
	public static RequestContext callerContext(Context ctx) {
		if (ctx == null) return RequestContext.ANONYMOUS;
		RequestContext rctx = RequestContext.of(getCallerDID(ctx));
		AVector<ACell> caps = ctx.attribute(CALLER_CAPS_ATTR);
		if (caps != null) rctx = rctx.withCaps(caps);
		return rctx;
	}

	/**
	 * Attach transport-presented UCAN authority to a request context — the single
	 * seam used by every invoke transport (REST, MCP). Presented proofs are the
	 * cryptographically-verified tokens, attached for <b>cross-user grant
	 * checks</b> (§5.2). Proofs are <b>additive</b>: a token only ever <em>adds</em>
	 * a grant, it never subtracts from the caller's own authority — so there is no
	 * wire-level self-attenuation. To act with reduced authority, run under a
	 * narrower {@link covia.grid.Authority} (an agent's {@code config.caps}) or
	 * present only the UCANs the request needs.
	 *
	 * <p>With no token presented, nothing is attached and access is unrestricted
	 * (the common case).</p>
	 *
	 * @param rctx context for the authenticated caller (caller DID already set)
	 * @param bearer Authorization bearer JWT, or null
	 * @param ucans transport {@code ucans} vector, or null
	 * @return rctx with the presented proofs attached
	 */
	public static RequestContext withTransportAuth(RequestContext rctx, AString bearer,
			AVector<ACell> ucans) {
		return withTransportAuth(rctx, bearer, ucans, null);
	}

	/**
	 * Raw JWT strings from the {@code X-Covia-Ucans} header (comma-separated),
	 * in the same vector shape as the request-body {@code ucans} array —
	 * verified downstream by {@link #withTransportAuth} exactly like body
	 * proofs. The header channel exists for body-less requests (job
	 * observation GETs): without it a federated hop can invoke a remote job
	 * but never observe it. Returns null when the header is absent or empty.
	 */
	public static AVector<ACell> headerUcans(Context ctx) {
		String h = ctx.header(covia.grid.client.VenueHTTP.UCANS_HEADER);
		if (h == null || h.isBlank()) return null;
		AVector<ACell> v = convex.core.data.Vectors.empty();
		for (String part : h.split(",")) {
			String t = part.trim();
			if (!t.isEmpty()) v = v.conj(Strings.create(t));
		}
		return v.isEmpty() ? null : v;
	}

	/**
	 * As {@link #withTransportAuth(RequestContext, AString, AVector)}, and — when
	 * {@code venueDID} is supplied and the transport is unauthenticated — derives
	 * the caller's identity from a presented <b>identity token</b>: a verified
	 * UCAN with {@code aud == venueDID} and an <b>empty</b> attenuation list
	 * (pure identity, no dual use as a grant). The issuer signed a token naming
	 * this venue as audience, so the identity is proven by the caller's own key
	 * and cannot be replayed at another venue (audience-bound). This is how a
	 * relayed cross-venue request carries the original caller's identity — the
	 * relay forwards the token, this venue verifies the caller's signature
	 * directly (zero trust in the relay). Two identity tokens with different
	 * issuers are ambiguous and rejected (401-equivalent).
	 *
	 * <p>Applies only when the transport is anonymous/public — an Authorization
	 * header always wins (a transport-authenticated peer, e.g. a relaying venue
	 * acting as itself, is the caller regardless of what it forwards).</p>
	 */
	public static RequestContext withTransportAuth(RequestContext rctx, AString bearer,
			AVector<ACell> ucans, AString venueDID) {
		// Verify signatures at ingress with an explicit DID verifier. DIDVerifier.CONVEX
		// handles did:key; swap to DIDVerifier.forState(state) to also verify did:convex
		// issuers when those arrive (covia#100).
		AVector<ACell> proofs = UCANValidator.parseTransportUCANsWithBearer(bearer, ucans, DIDVerifier.CONVEX);
		if (proofs == null) return rctx;

		// Identity from the proof channel: only for an unauthenticated transport
		// (no bearer identity), and only when the venue context is known.
		if (venueDID != null && bearer == null && isPublicOrAnonymous(rctx, venueDID)) {
			AString identity = identityFromProofs(proofs, venueDID);
			if (identity != null) {
				// A proven caller: fresh context — the public read-only grant scope
				// does not apply to an authenticated identity.
				rctx = RequestContext.of(identity);
			}
		}

		rctx = rctx.withProofs(proofs);
		// Retain the raw body tokens for cross-venue relay (C3a): proofs are
		// self-verifying, so forwarding them is safe — but only the original
		// signed JWTs verify at the next hop, not the parsed maps. The bearer is
		// NOT retained for relay — it is audienced to THIS venue (#149) and must
		// never be replayed elsewhere.
		if (ucans != null && !ucans.isEmpty()) rctx = rctx.withRawUcans(ucans);
		// No self-attenuation from the wire: presented proofs are additive grants,
		// never subtractive. To act with reduced authority, hand the
		// callee a narrower Authority (Authority.of(did, grants)) or present only the
		// UCANs the request needs — you never send everything and then subtract.
		return rctx;
	}

	/** True when the context carries no authenticated identity: anonymous, or the
	 *  venue's shared {@code :public} DID. */
	private static boolean isPublicOrAnonymous(RequestContext rctx, AString venueDID) {
		AString caller = rctx.getCallerDID();
		if (caller == null) return true;
		return caller.toString().equals(venueDID + ":public");
	}

	/**
	 * Extracts a caller identity from verified proofs: a UCAN audienced to this
	 * venue with an empty attenuation list. Returns null when absent; throws on
	 * ambiguity (two identity tokens with different issuers).
	 */
	private static AString identityFromProofs(AVector<ACell> proofs, AString venueDID) {
		AString identity = null;
		long now = System.currentTimeMillis() / 1000;
		for (long i = 0; i < proofs.count(); i++) {
			AMap<AString, ACell> map = convex.core.lang.RT.castMap(proofs.get(i));
			if (map == null) continue;
			convex.auth.ucan.UCAN token = convex.auth.ucan.UCAN.parse(map);
			if (token == null) continue;
			if (!venueDID.equals(token.getAudience())) continue;
			AVector<ACell> att = token.getCapabilities();
			if (att != null && !att.isEmpty()) continue; // a grant, not an identity token
			if (!UCANValidator.checkTemporalBounds(token, now)) continue;
			AString iss = token.getIssuer();
			if (iss == null || iss.equals(venueDID)) continue;
			if (identity != null && !identity.equals(iss)) {
				throw new IllegalStateException(
					"Ambiguous caller identity: multiple identity tokens with different issuers");
			}
			identity = iss;
		}
		return identity;
	}
}
