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

	// Per-instance state. Was static, which made running multiple VenueServers
	// in the same JVM (production multi-tenant, parallel test classes) racy:
	// every register() call would trample the previous instance's fields,
	// causing requests to be attributed to the wrong venue's public DID
	// and 403'd by AccessControl on cross-venue job lookups.
	private final AccountKey venueKey;
	@SuppressWarnings("unused")
	private final AString venueDID;
	private final AString publicDID;
	private final Auth venueAuth;
	private final Map<String, OAuthConfig> externalProviders;
	private final boolean publicAccessEnabled;
	private final AVector<ACell> publicCeiling;

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
	}

	/**
	 * Register auth middleware on API paths. Each call creates a fresh
	 * {@link AuthMiddleware} instance bound to the supplied venue identity,
	 * so multiple VenueServers in the same JVM (production multi-tenant or
	 * parallel test classes) do not share state.
	 *
	 * @param app Javalin application
	 * @param venueAccountKey The venue's public key for verifying venue-signed JWTs
	 * @param auth Auth instance for access control configuration
	 * @param venueDIDString The venue's DID string for deriving user DIDs
	 * @return The constructed middleware instance (rarely needed by callers,
	 *         but useful for tests).
	 */
	public static AuthMiddleware register(Javalin app, AccountKey venueAccountKey, Auth auth, AString venueDIDString) {
		AuthMiddleware mw = new AuthMiddleware(venueAccountKey, auth, venueDIDString);
		app.before("/api/*", mw::extractIdentity);
		app.before("/a2a", mw::extractIdentity);
		app.before("/mcp", mw::extractIdentity);
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
				log.debug("JWT bearer token failed all verification paths");
				if (!publicAccessEnabled) {
					ctx.status(401).result("Invalid or expired token");
					ctx.skipRemainingHandlers();
				} else {
					markPublic(ctx);
				}
			}
		} catch (Exception e) {
			log.debug("Error processing bearer token", e);
			if (!publicAccessEnabled) {
				ctx.status(401).result("Authentication required");
				ctx.skipRemainingHandlers();
			} else {
				markPublic(ctx);
			}
		}
	}

	/**
	 * Verify a UCAN bearer token. EdDSA signature is verified via the JWT
	 * {@code kid} header; temporal bounds are checked; the token must have a
	 * non-null {@code aud} (distinguishing it from generic self-issued auth
	 * JWTs). On success, the issuer DID is returned as the caller identity —
	 * matching the IETF UCAN-HTTP bearer convention that the invocation
	 * token's {@code iss} is the caller.
	 *
	 * @param jwt Bearer token
	 * @return Issuer DID (did:key form) on success, null if the token is not
	 *         a valid UCAN
	 */
	private static AString tryVerifyUCAN(AString jwt) {
		UCAN token = UCAN.fromJWT(jwt);
		if (token == null) return null;
		// A UCAN must declare an audience — this is what distinguishes it from
		// other self-signed EdDSA JWTs that happen to verify.
		if (token.getAudience() == null) return null;
		// Bind the signature to the claimed issuer. UCAN.fromJWT verifies the
		// signature against the JWT `kid` header key — which the caller fully
		// controls — but attributes identity from the independent `iss` claim.
		// Without re-verifying against the issuer's OWN key, anyone can sign a
		// token with their key, set `iss` to any victim DID, and be attributed
		// that identity. Re-verify against the issuer key (the same signature can
		// only satisfy both checks when kid-key == iss-key) — mirroring the
		// kid==sub bind that tryVerifySelfIssued enforces for plain JWTs.
		AString issuer = token.getIssuer();
		AccountKey issuerKey = UCAN.fromDIDKey(issuer);
		if (issuerKey == null) return null;
		if (JWT.verifyPublic(jwt, issuerKey) == null) return null;
		long now = System.currentTimeMillis() / 1000;
		if (!UCANValidator.checkTemporalBounds(token, now)) return null;
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
		// TODO(convex-release): replace selfCapabilities with
		// UCANValidator.capabilitiesFor once covia's Convex dependency includes it.
		AVector<ACell> caps = CapabilityChecker.selfCapabilities(proofs, caller, caller, now);
		if (caps != null) rctx = rctx.withCaps(caps);
		return rctx;
	}
}
