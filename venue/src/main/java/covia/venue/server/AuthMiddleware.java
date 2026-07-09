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
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.auth.ucan.UCANValidator;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.lattice.CapabilityChecker;
import covia.venue.Auth;
import covia.venue.RequestContext;
import covia.venue.auth.JWKSClient;
import covia.venue.auth.OAuthConfig;
import io.javalin.config.RoutesConfig;
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
 * Anonymous requests (no Authorization header) are allowed when
 * {@code auth.public.enabled} is true. Otherwise, requests without a valid
 * token are rejected with 401.
 */
public class AuthMiddleware {

	private static final Logger log = LoggerFactory.getLogger(AuthMiddleware.class);

	static final String CALLER_DID_ATTR = "callerDID";
	/**
	 * Context attribute holding the capability ceiling for an unauthenticated
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
	private final AVector<ACell> publicCeiling;
	private final String audiencePolicy;                  // "verify" | "require"
	private final java.util.Set<String> acceptedAudiences;

	private AuthMiddleware(AccountKey venueAccountKey, Auth auth, AString venueDIDString) {
		this.venueKey = venueAccountKey;
		this.venueDID = venueDIDString;
		this.publicDID = Strings.create(venueDIDString + ":public");
		this.venueAuth = auth;
		this.publicAccessEnabled = auth.isPublicAccessEnabled();
		this.externalProviders = auth.getLoginProviders().hasProviders()
			? auth.getLoginProviders().getProviders() : null;
		// Capability ceiling for public callers — secure read-only by default,
		// operator-overridable via auth.public.caps. Only relevant when public
		// access is enabled (otherwise every anonymous request is 401'd).
		this.publicCeiling = publicAccessEnabled ? auth.getPublicCeiling(publicDID) : null;
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
	 * Register auth middleware on API paths. Each call creates a fresh
	 * {@link AuthMiddleware} instance bound to the supplied venue identity,
	 * so multiple VenueServers in the same JVM (production multi-tenant or
	 * parallel test classes) do not share state.
	 *
	 * @param routes Javalin routes configuration
	 * @param venueAccountKey The venue's public key for verifying venue-signed JWTs
	 * @param auth Auth instance for access control configuration
	 * @param venueDIDString The venue's DID string for deriving user DIDs
	 * @return The constructed middleware instance (rarely needed by callers,
	 *         but useful for tests).
	 */
	public static AuthMiddleware register(RoutesConfig routes, AccountKey venueAccountKey, Auth auth, AString venueDIDString) {
		AuthMiddleware mw = new AuthMiddleware(venueAccountKey, auth, venueDIDString);
		routes.before("/api/*", mw::extractIdentity);
		routes.before("/a2a", mw::extractIdentity);
		routes.before("/a2a/*", mw::extractIdentity);  // per-agent A2A endpoints (COG-14)
		routes.before("/mcp", mw::extractIdentity);
		return mw;
	}

	/**
	 * Attribute an unauthenticated request to the venue's public DID and stash
	 * the public capability ceiling (if any), so the downstream
	 * {@link #callerContext} applies it uniformly.
	 */
	private void markPublic(Context ctx) {
		ctx.attribute(CALLER_DID_ATTR, publicDID);
		if (publicCeiling != null) ctx.attribute(CALLER_CAPS_ATTR, publicCeiling);
	}

	void extractIdentity(Context ctx) {
		String auth = ctx.header("Authorization");
		if (auth == null || !auth.startsWith("Bearer ")) {
			if (!publicAccessEnabled) {
				ctx.status(401).result("Authentication required");
				ctx.skipRemainingHandlers();
			} else {
				markPublic(ctx);
			}
			return;
		}

		String token = auth.substring(7).trim();
		if (token.isEmpty()) {
			if (!publicAccessEnabled) {
				ctx.status(401).result("Authentication required");
				ctx.skipRemainingHandlers();
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
			AString callerDID = tryVerifyUCAN(jwt);
			if (callerDID != null) {
				ctx.attribute(UCAN_BEARER_ATTR, jwt);
			}
			if (callerDID == null) {
				callerDID = tryVerifySelfIssued(jwt);
			}
			if (callerDID == null && venueKey != null) {
				callerDID = tryVerifyVenueSigned(jwt);
			}
			if (callerDID == null && externalProviders != null) {
				callerDID = tryVerifyExternalProvider(jwt);
			}
			if (callerDID != null) {
				ctx.attribute(CALLER_DID_ATTR, callerDID);
			} else {
				// A presented token that fails every verification path is a hard
				// 401 — NEVER a silent downgrade to the public identity, even when
				// public access is enabled. Presenting credentials is an explicit
				// identity claim; failing that claim must be visible to the caller
				// (an anonymous request without a token still gets public access).
				log.debug("JWT bearer token failed all verification paths");
				ctx.status(401).result("Invalid or expired token");
				ctx.skipRemainingHandlers();
			}
		} catch (AudienceRejected e) {
			// A presented token failed the audience policy — a hard 401, NOT a
			// silent downgrade to the public identity (the replay defence).
			log.debug("Bearer token audience rejected: {}", e.getMessage());
			ctx.status(401).result("Token audience not accepted by this venue");
			ctx.skipRemainingHandlers();
		} catch (Exception e) {
			// Same rule for unexpected failures while verifying a PRESENTED token
			// (JWKS outage, parse bug, ...): fail the claim visibly. Warn-level —
			// this path is abnormal and should be diagnosable, not debug-hidden.
			log.warn("Error processing bearer token", e);
			ctx.status(401).result("Authentication failed");
			ctx.skipRemainingHandlers();
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
	 * @return Issuer DID (did:key form) on success, null if the token is not
	 *         a valid UCAN
	 */
	private AString tryVerifyUCAN(AString jwt) {
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
		return issuer;
	}

	/**
	 * Verify a self-issued JWT where the kid header matches the did:key in the sub claim.
	 */
	private AString tryVerifySelfIssued(AString jwt) {
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

		// Temporal bounds — previously unchecked, so a self-issued token was
		// accepted forever; reject expired / not-yet-valid tokens.
		if (!temporalValid(claims, System.currentTimeMillis() / 1000)) return null;
		// Audience restriction (RFC 7519 §4.1.3): a self-issued token minted for
		// another venue must not authenticate here.
		requireAudience(claims.get(AUD));
		return sub;
	}

	/**
	 * Verify a venue-signed JWT. The venue's key is the trusted signer.
	 * The sub claim contains the user's DID.
	 */
	private AString tryVerifyVenueSigned(AString jwt) {
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
	private AString tryVerifyExternalProvider(AString jwt) {
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
				return findUserDIDByEmail(email);
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
	 * Get the caller DID from the request context.
	 * Always non-null for requests that pass through the middleware when
	 * public access is enabled (anonymous requests get the venue's public DID).
	 * @param ctx Javalin context
	 * @return Caller DID as AString, or null if auth was required and missing
	 */
	public static AString getCallerDID(Context ctx) {
		return ctx.attribute(CALLER_DID_ATTR);
	}

	/**
	 * Builds the base {@link RequestContext} for an inbound request: the caller
	 * DID plus, for unauthenticated (public) callers, the configured capability
	 * ceiling ({@code auth.public.caps}, default read-only). This is the single
	 * seam where a request's ceiling is established, before transport-token
	 * authority ({@link #withTransportAuth}) is layered on. Null-safe: a null
	 * Javalin context (e.g. an MCP call with no HTTP context) yields
	 * {@link RequestContext#ANONYMOUS}.
	 *
	 * @param ctx Javalin context, or null
	 * @return the caller's request context with its ceiling applied
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
	 * seam used by every invoke transport (REST, MCP). Two things, in order:
	 * <ol>
	 *   <li><b>Proofs</b> — the cryptographically-verified tokens, for cross-user
	 *       grant checks (unchanged behaviour).</li>
	 *   <li><b>Self-attenuation ceiling</b> — capabilities the <em>owner</em>
	 *       authored over their own resources, restricting this session. Set as
	 *       {@code caps} so the executing adapter applies them as a ceiling at its
	 *       enforcement point. Closes the gap where a presented attenuated token
	 *       ran with full authority (#131).</li>
	 * </ol>
	 *
	 * <p>The owner is the authority over its own namespace; the venue only
	 * enforces. So the ceiling is taken from tokens the caller authored for
	 * itself ({@code iss == aud == caller}). A ceiling can only <em>narrow</em>
	 * the caller's own authority — never widen it — so this is escalation-safe.
	 * With no token presented, nothing is attached and access is unrestricted
	 * (the common case).</p>
	 *
	 * @param rctx context for the authenticated caller (caller DID already set)
	 * @param bearer Authorization bearer JWT, or null
	 * @param ucans transport {@code ucans} vector, or null
	 * @return rctx with proofs and (if any) the self-cap ceiling attached
	 */
	public static RequestContext withTransportAuth(RequestContext rctx, AString bearer,
			AVector<ACell> ucans) {
		AVector<ACell> proofs = UCANValidator.parseTransportUCANsWithBearer(bearer, ucans);
		if (proofs == null) return rctx;
		rctx = rctx.withProofs(proofs);
		AString caller = rctx.getCallerDID();
		long now = System.currentTimeMillis() / 1000;
		// Derives the self-attenuation ceiling: UCANValidator.capabilitiesFor
		// (convex-core) for selection, plus covia's narrow-only guard.
		AVector<ACell> caps = CapabilityChecker.selfCapabilities(proofs, caller, caller, now);
		if (caps != null) rctx = rctx.withCaps(caps);
		return rctx;
	}
}
