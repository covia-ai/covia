package covia.venue.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.Semaphore;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.ConfigurationException;
import covia.exception.InvalidInputException;
import covia.venue.Audit;
import covia.venue.Auth;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.api.ACoviaAPI;
import covia.venue.api.ApiErrors;
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
	// Per-instance state. Was static, which made running multiple VenueServers
	// in the same JVM (production multi-tenant, parallel test classes) racy:
	// every register() call would trample the previous instance's fields,
	// causing requests to be attributed to the wrong venue's public DID
	// and 403'd by AccessControl on cross-venue job lookups.
	private final AString publicDID;
	private final boolean publicAccessEnabled;
	private final Engine engine;
	private final VenueAuthenticator authenticator;
	/** Per-address backpressure on credential checks (covia#539); null when rate limiting is off. */
	private final AuthThrottle throttle;

	private AuthMiddleware(Engine engine, VenueAuthenticator authenticator) {
		this.engine = engine;
		this.authenticator = authenticator;
		this.publicDID = Strings.create(engine.getDIDString() + ":public");
		Auth auth = engine.getAuth();
		this.publicAccessEnabled = auth.isPublicAccessEnabled();
		Config config = engine.config();
		this.throttle = config.isRateLimitEnabled()
			? new AuthThrottle(config.getTrustedProxies(), config.getAuthFailureBurst(),
				config.getAuthFailuresPerMinute(), config.getAuthConcurrency(), config.getRateLimitBlockMs())
			: null;
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
		VenueAuthenticator.bindEngine(ctx, engine);
		Set<RouteRole> roles = ctx.routeRoles();
		if (VenueRouteFeature.has(roles, VenueRouteFeature.COVIA_MCP)) {
			Config config = engine.config();
			extractMCPIdentity(ctx, config.isMCPAuthRequired(),
				config.getMCPAllowedDids());
			return;
		}
		if (VenueRouteFeature.has(roles, VenueRouteFeature.COVIA_DISCOVERY)) {
			// Discovery answers strangers even on a private venue; a presented
			// bearer must still authenticate and admit, as on any native route.
			extractIdentity(ctx, true, null, true);
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
	 * Attribute an unauthenticated request to the venue's public DID, whose scope
	 * {@link #callerContext} applies ({@code auth.public.caps}). While public
	 * access is off the request stays anonymous: only COVIA_DISCOVERY routes
	 * such as /api/v1/status reach here then, so a client can find and verify
	 * the venue before it authenticates.
	 */
	private void markPublic(Context ctx) {
		if (publicAccessEnabled) ctx.attribute(CALLER_DID_ATTR, publicDID);
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
		String resourceMetadata = ACoviaAPI.getExternalBaseUrl(ctx, null, engine.config().getTrustedProxies())
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
		// RFC 7235: a 401 names its challenge; the MCP path has already set a richer one.
		if (status == 401 && ctx.res().getHeader("WWW-Authenticate") == null) ctx.header("WWW-Authenticate", "Bearer");
		ApiErrors.write(ctx, status, message);
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

		// A credential is presented: charge its address (covia#539). Over budget
		// means no verification at all; otherwise take the address's in-flight
		// slot so it cannot fan out DID resolution in parallel.
		String ip = engine.config().getTrustedProxies().clientIp(ctx.ip(), ctx.header("X-Forwarded-For"));
		Audit audit = engine.audit();
		Semaphore slot = null;
		if (throttle != null) {
			long retry = throttle.overBudgetRetryAfter(ip);
			if (retry > 0) {
				audit.event(Audit.AUTH_THROTTLED, Audit.K_IP, ip, Audit.K_REASON, "failure budget spent");
				rejectTooMany(ctx, retry, AuthThrottle.TOO_MANY_FAILURES + retry + "s");
				return;
			}
			slot = throttle.acquire(ip);
			if (slot == null) {
				audit.event(Audit.AUTH_THROTTLED, Audit.K_IP, ip, Audit.K_REASON, "concurrent attempts");
				rejectTooMany(ctx, 1, AuthThrottle.TOO_MANY_CONCURRENT);
				return;
			}
		}
		try {
			AString venueUserDID = authenticator.authenticate(ctx, Strings.create(token));
			audit.event(Audit.AUTH_SUCCESS, Audit.K_DID, venueUserDID,
				Audit.K_IDENTITY, getAuthenticatedIdentity(ctx), Audit.K_IP, ip);
			if (admitUser && !admitAuthenticated(ctx, venueUserDID)) return;
		} catch (AuthException e) {
			if (throttle != null) throttle.recordFailure(ip);
			// The reason describes the presented credential, never its bytes (#548).
			audit.event(Audit.AUTH_FAILURE, Audit.K_IP, ip, Audit.K_REASON, e.getMessage());
			log.debug("Bearer token rejected: {}", e.getMessage());
			rejectUnauthorized(ctx, e.getMessage(), resourceMetadata);
		} finally {
			if (slot != null) slot.release();
		}
	}

	private void rejectTooMany(Context ctx, long retryAfterSeconds, String message) {
		ctx.header("Retry-After", Long.toString(retryAfterSeconds));
		reject(ctx, 429, message);
	}

	/** The authentication throttle in force, or null when rate limiting is off — for tests and status. */
	public AuthThrottle throttle() {
		return throttle;
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
		VenueAuthenticator.setRequestIdentity(ctx, authenticatedIdentity, venueUserDID);
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
	 * Builds the base {@link RequestContext} for an inbound request: its caller
	 * as it acts now in its own namespace ({@link Engine#context(AString)}), so
	 * an anonymous caller holds the public scope and an agent-shaped subject
	 * holds nothing. Transport-token authority ({@link #withTransportGrants}) is
	 * layered on after. Null-safe: a null Javalin context (e.g. an MCP call with
	 * no HTTP context) or a request with no caller yields
	 * {@link RequestContext#ANONYMOUS}.
	 *
	 * @param ctx Javalin context, or null
	 * @return the caller's request context with its grant scope applied
	 * @throws IllegalStateException for a caller on a request no venue serves
	 */
	public static RequestContext callerContext(Context ctx) {
		if (ctx == null) return RequestContext.ANONYMOUS;
		AString caller = getCallerDID(ctx);
		if (caller == null) return RequestContext.ANONYMOUS;
		Engine engine = VenueAuthenticator.engineOf(ctx);
		if (engine == null) throw new ConfigurationException(NO_VENUE);
		return engine.context(caller);
	}

	/** Why a request's caller has no context: no venue's middleware or authenticator bound it. */
	public static final String NO_VENUE = "No venue serves this request: its caller was not bound by a venue's middleware or authenticator";

	/**
	 * The {@code ucans} array of a request body or tool arguments, in the vector
	 * shape {@link Engine#withTransportGrants} takes.
	 *
	 * @param request the request body, or null
	 * @return the presented proofs, or null when there are none
	 * @throws InvalidInputException if {@code ucans} is present but not an array
	 */
	public static AVector<ACell> bodyUcans(ACell request) {
		ACell ucans = RT.getIn(request, Fields.UCANS);
		if (ucans == null) return null;
		AVector<ACell> vector = RT.ensureVector(ucans);
		if (vector == null) throw new InvalidInputException(UCANS_NOT_ARRAY);
		return vector;
	}

	/** Why a request's {@code ucans} field was refused. */
	public static final String UCANS_NOT_ARRAY = "'ucans' must be an array of UCAN tokens";

	/**
	 * Raw JWT strings from the {@code X-Covia-Ucans} header (comma-separated),
	 * in the same vector shape as the request-body {@code ucans} array —
	 * verified downstream by {@link Engine#withTransportGrants} exactly like the body
	 * envelope. The header channel exists for body-less requests (job
	 * observation GETs): without it a federated hop can invoke a remote job
	 * but never observe it. Returns null when the header is absent or empty.
	 */
	public static AVector<ACell> headerUcans(Context ctx) {
		String h = ctx.header(covia.grid.client.VenueHTTP.UCANS_HEADER);
		if (h == null || h.isBlank()) return null;
		AVector<ACell> v = Vectors.empty();
		for (String part : h.split(",")) {
			String t = part.trim();
			if (!t.isEmpty()) v = v.conj(Strings.create(t));
		}
		return v.isEmpty() ? null : v;
	}

}
