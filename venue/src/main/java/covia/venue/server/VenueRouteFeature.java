package covia.venue.server;

import java.util.Set;

import covia.venue.auth.VenueAuthenticator;
import io.javalin.security.RouteRole;

/**
 * Opt-in Covia services for an individual Javalin endpoint.
 *
 * <p>Embedder-contributed routes are raw Javalin routes by default, regardless
 * of their URL. Adding one of these roles asks the venue to apply the named
 * service to that endpoint. This keeps route ownership with the embedder while
 * making Covia's verified identity, mapped venue user, user admission, rate
 * limiting, and lattice durability available when useful. These features can
 * be combined with an embedder's own {@link RouteRole} values; Covia ignores
 * roles it does not recognise. Extenders may instead own authentication
 * end-to-end and publish its result with
 * {@link VenueAuthenticator#bindIdentity}.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * routes.get("/api/product/health", ctx -> ctx.result("ok"));
 *
 * routes.get("/api/product/me", handler,
 *     VenueRouteFeature.AUTHENTICATED_IDENTITY);
 *
 * routes.post("/api/product/action", handler,
 *     VenueRouteFeature.ADMITTED_USER,
 *     VenueRouteFeature.RATE_LIMITED,
 *     VenueRouteFeature.LATTICE_SYNC);
 * }</pre>
 *
 * <p>The {@code COVIA_*} roles preserve native protocol policy and are public
 * so an embedder can deliberately request exactly the same behaviour. Most
 * contributed routes should use the smaller composable features instead.</p>
 */
public enum VenueRouteFeature implements RouteRole {

	/**
	 * Require a bearer credential accepted by the venue and expose its verified
	 * identity and mapped venue user through the venue's
	 * {@link VenueAuthenticator}. Does not admit or create a Covia user, even
	 * when {@code users.autoCreate} is enabled.
	 */
	AUTHENTICATED_IDENTITY,

	/**
	 * Require a venue-accepted bearer credential and admit its mapped venue user.
	 * Unknown users follow {@code users.autoCreate}.
	 */
	ADMITTED_USER,

	/** Apply the venue's configured per-caller HTTP rate limiter. */
	RATE_LIMITED,

	/** Sync lattice state after the matched route handler completes. */
	LATTICE_SYNC,

	/**
	 * Native Covia REST policy: configured public access when enabled, otherwise
	 * authenticated admitted users, plus rate limiting and lattice sync.
	 */
	COVIA_API,

	/**
	 * Native MCP transport policy, including its authentication requirement and
	 * optional DID allowlist, plus rate limiting and lattice sync.
	 */
	COVIA_MCP,

	/**
	 * Native A2A policy: configured public access when enabled, otherwise
	 * authenticated admitted users, plus rate limiting and lattice sync.
	 */
	COVIA_A2A;

	static boolean has(Set<RouteRole> roles, VenueRouteFeature feature) {
		return roles != null && roles.contains(feature);
	}

	static boolean usesRateLimit(Set<RouteRole> roles) {
		return has(roles, RATE_LIMITED)
			|| has(roles, COVIA_API)
			|| has(roles, COVIA_MCP)
			|| has(roles, COVIA_A2A);
	}

	static boolean syncsLattice(Set<RouteRole> roles) {
		return has(roles, LATTICE_SYNC)
			|| has(roles, COVIA_API)
			|| has(roles, COVIA_MCP)
			|| has(roles, COVIA_A2A);
	}
}
