package covia.venue.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import convex.api.ContentTypes;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.auth.did.DIDVerifier;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.auth.ucan.UCANValidator;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Abilities;
import covia.exception.AuthException;
import covia.venue.Auth;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.UcanJwtValidator;
import covia.venue.api.ACoviaAPI;
import covia.venue.auth.VenueAuthenticator;
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
	 * merge this into their transport {@code ucans} vector at ingress. Null when the
	 * request has no bearer token or the bearer is not a UCAN.
	 */
	public static final String UCAN_BEARER_ATTR = "ucanBearer";
	// Per-instance state. Was static, which made running multiple VenueServers
	// in the same JVM (production multi-tenant, parallel test classes) racy:
	// every register() call would trample the previous instance's fields,
	// causing requests to be attributed to the wrong venue's public DID
	// and 403'd by AccessControl on cross-venue job lookups.
	private final AString publicDID;
	private final boolean publicAccessEnabled;
	private final AVector<ACell> publicScope;
	private final Engine engine;
	private final VenueAuthenticator authenticator;

	private AuthMiddleware(Engine engine, VenueAuthenticator authenticator) {
		this.engine = engine;
		this.authenticator = authenticator;
		this.publicDID = Strings.create(engine.getDIDString() + ":public");
		Auth auth = engine.getAuth();
		this.publicAccessEnabled = auth.isPublicAccessEnabled();
		// Capability grant scope for public callers — secure read-only by default,
		// operator-overridable via auth.public.caps. Only relevant when public
		// access is enabled (otherwise every anonymous request is 401'd).
		this.publicScope = publicAccessEnabled ? auth.getPublicScope(publicDID) : null;
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
		return register(routes, engine, new VenueAuthenticator(engine));
	}

	/** Registers middleware backed by the venue's shared authenticator. */
	public static AuthMiddleware register(RoutesConfig routes, Engine engine,
			VenueAuthenticator authenticator) {
		AuthMiddleware mw = new AuthMiddleware(engine, authenticator);
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
	private boolean admitAuthenticated(Context ctx, AString venueUserDID) {
		try {
			engine.admitUser(venueUserDID);
			return true;
		} catch (AuthException e) {
			reject(ctx, 403, e.getMessage());
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
			reject(ctx, 403, "Caller DID is not allowed to use MCP");
		}
	}

	/**
	 * Emit the same JSON error envelope used by Covia API handlers. Middleware
	 * rejects before an endpoint handler (and often before a Job exists), so it
	 * must render the response itself rather than relying on endpoint error handling.
	 */
	private void reject(Context ctx, int status, String message) {
		String body = "{\"error\": \"" + JSON.escape(message) + "\"}";
		ctx.status(status)
			.header("Content-Type", ContentTypes.JSON + "; charset=utf-8")
			.result(body.getBytes(StandardCharsets.UTF_8));
		ctx.skipRemainingHandlers();
	}

	private void rejectUnauthorized(Context ctx, String message, String resourceMetadata) {
		if (resourceMetadata != null) {
			ctx.header("WWW-Authenticate",
				"Bearer resource_metadata=\"" + resourceMetadata + "\"");
		}
		reject(ctx, 401, message);
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
			AString venueUserDID = authenticator.authenticate(ctx, Strings.create(token));
			if (admitUser && !admitAuthenticated(ctx, venueUserDID)) return;
		} catch (AuthException e) {
			log.debug("Bearer token rejected: {}", e.getMessage());
			rejectUnauthorized(ctx, e.getMessage(), resourceMetadata);
		}
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
	 * cryptographically-verified tokens, attached for cross-user grant checks
	 * (§5.2). Ordinary proofs are additive. A body token carrying
	 * {@code user/act} over its issuer and issued directly to the authenticated caller selects
	 * on-behalf-of execution: the caller remains the actor, the issuer becomes
	 * the user namespace, and that context is bounded to the presented proofs.
	 * This prevents a delegate's unrestricted authority over its own account from
	 * becoming unrestricted authority over the issuer's account (#406).
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
		return withTransportAuth(rctx, bearer, ucans, venueDID, DIDVerifier.CONVEX);
	}

	/**
	 * As {@link #withTransportAuth(RequestContext, AString, AVector, AString)},
	 * verifying token signatures with the supplied DID verifier. Venue call
	 * sites pass {@code engine.didVerifier()} so did:web-identified issuers
	 * (covia#343) verify at ingress exactly like did:key ones; the
	 * {@link DIDVerifier#CONVEX} default of the shorter overloads remains
	 * did:key-only.
	 */
	public static RequestContext withTransportAuth(RequestContext rctx, AString bearer,
			AVector<ACell> ucans, AString venueDID, DIDVerifier verifier) {
		// Verify signatures at ingress with an explicit DID verifier.
		DIDVerifier effectiveVerifier = (verifier != null) ? verifier : DIDVerifier.CONVEX;
		AVector<ACell> bearerProofs = (bearer != null)
			? UcanJwtValidator.parseTransportUCANs(Vectors.of(bearer), effectiveVerifier)
			: null;
		AVector<ACell> bodyProofs = UcanJwtValidator.parseTransportUCANs(ucans, effectiveVerifier);
		AVector<ACell> proofs = concatProofs(bearerProofs, bodyProofs);
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
		// A body token carrying user/act on its issuer is an on-behalf-of
		// delegation. Keep the audience as caller/actor, but execute in the issuer's
		// namespace under proof-only authority. Ordinary cross-user grants stay
		// additive; namespace selection is itself explicit capability data. A single
		// invocation cannot act for two users at once.
		AString delegatedUser = delegatedUser(bodyProofs, rctx.getCallerDID());
		if (delegatedUser != null) rctx = rctx.onBehalfOf(delegatedUser);
		// Retain the raw body tokens for cross-venue relay (C3a): proofs are
		// self-verifying, so forwarding them is safe — but only the original
		// signed JWTs verify at the next hop, not the parsed maps. The bearer is
		// NOT retained for relay — it is audienced to THIS venue (#149) and must
		// never be replayed elsewhere.
		if (ucans != null && !ucans.isEmpty()) rctx = rctx.withRawUcans(ucans);
		// Outside the explicit on-behalf-of case above, presented proofs are
		// additive and never subtractive. To act with reduced authority, hand the
		// callee a narrower Authority (Authority.of(did, grants)) or present only the
		// UCANs the request needs — you never send everything and then subtract.
		return rctx;
	}

	private static AVector<ACell> concatProofs(AVector<ACell> first, AVector<ACell> second) {
		if (first == null || first.isEmpty()) return second;
		if (second == null || second.isEmpty()) return first;
		return first.concat(second);
	}

	/** The unique direct issuer granting {@code user/act} over itself, if any. */
	private static AString delegatedUser(AVector<ACell> proofs, AString actor) {
		if (proofs == null || actor == null) return null;
		AString user = null;
		for (long i = 0; i < proofs.count(); i++) {
			AMap<AString, ACell> map = RT.castMap(proofs.get(i));
			UCAN token = (map != null) ? UCAN.parse(map) : null;
			if (token == null || !actor.equals(token.getAudience())) continue;
			AString issuer = token.getIssuer();
			if (issuer == null || issuer.equals(actor)) continue;
			AVector<ACell> capabilities = token.getCapabilities();
			if (!grantsActAs(capabilities, issuer)) continue;
			if (user != null && !user.equals(issuer)) {
				throw new AuthException("Ambiguous delegated namespace: proofs were issued by both "
					+ user + " and " + issuer + "; submit one user's delegation per invocation");
			}
			user = issuer;
		}
		return user;
	}

	private static boolean grantsActAs(AVector<ACell> capabilities, AString issuer) {
		if (capabilities == null) return false;
		for (long i = 0; i < capabilities.count(); i++) {
			AMap<AString, ACell> cap = RT.castMap(capabilities.get(i));
			if (cap == null) continue;
			if (issuer.equals(RT.ensureString(cap.get(Capability.WITH)))
					&& Abilities.USER_ACT.equals(
						RT.ensureString(cap.get(Capability.CAN)))) {
				return true;
			}
		}
		return false;
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
