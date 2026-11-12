package covia.adapter;

import java.util.ArrayList;
import java.util.List;
import convex.auth.ucan.UCAN;
import convex.auth.ucan.UCANValidator;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import covia.grid.client.VenueHTTP;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.exception.ResponseException;
import covia.exception.UnavailableException;
import covia.grid.Venue;
import covia.grid.auth.VenueAuth;
import covia.grid.auth.VenueDID;
import covia.venue.Engine;
import covia.venue.RemoteJobs;
import covia.venue.RequestContext;
import covia.venue.UcanJwtValidator;

/**
 * Shared native venue connection policy. {@code credentials} is what recovery
 * needs to connect again, for protected storage only, and never holds a
 * sign-in credential: a hop that signed in as its caller is not resumed.
 */
public record VenueConnection(Venue venue, AMap<AString, ACell> credentials) {
	private static final AString AUTHENTICATE_AS = Strings.intern("authenticateAs");
	private static final AString RESUMABLE = Strings.intern("resumable");
	private static final AString CALLER = Strings.intern("caller");
	/** Saved when the caller could have the venue dial anywhere, so recovery reconnects alike. */
	private static final AString OUTBOUND_ANY = Strings.intern("outboundAny");

	/**
	 * Refusal of {@code authenticateAs=venue} for anyone but the venue itself.
	 * Authenticating as the venue carries all of its standing, so no grant can
	 * lend it: other callers authenticate as themselves and present grants.
	 */
	public static final String VENUE_MODE_DENIED =
		"authenticateAs=venue is only for the venue itself; use authenticateAs=caller with your own credential and grants";

	/**
	 * Opening of the refusal to send a credential to a venue whose DID is not
	 * proven at the address it is reached by ({@link Engine#venueProof}). The
	 * DID, the target and every proof mechanism's reason follow.
	 */
	public static final String VENUE_UNPROVEN =
		"No credential is sent to a venue whose DID is not proven where it is reached (auth.venueProof). Unproven: ";

	/**
	 * Refusal to sign in again when the job saved no DID for its target, so
	 * the target cannot be proven again.
	 */
	public static final String NO_SAVED_DID =
		"No credential is re-sent to a venue whose DID the job did not save: ";

	/**
	 * Refusal of {@code authenticateAs=caller} without the caller's identity
	 * credential for the target, which only a request presents. The venue
	 * keeps none, so work it runs later cannot sign in as its caller. The
	 * target venue's DID follows.
	 */
	public static final String NO_CALLER_CREDENTIAL =
		"authenticateAs=caller needs an identity credential presented with the request: an in-date UCAN with no "
		+ "capabilities, issued by the caller to the target venue. This venue keeps none, so a scheduled hop cannot "
		+ "sign in as its caller; schedule it at the target venue instead. Target venue: ";

	/**
	 * Filters raw tokens down to in-date, non-empty capability grants whose
	 * audience is exactly the principal authenticated at the target, judged by
	 * the venue's clock. Identity credentials never enter the grant channel.
	 * Returns raw JWT strings; {@code tokens} is index-aligned with
	 * {@link RequestContext#getRawUcans()}.
	 */
	static List<String> admissibleGrants(Engine engine, RequestContext ctx,
			List<UCAN> tokens, AString principal) {
		AVector<ACell> raw = ctx.getRawUcans();
		if (raw == null || tokens == null || principal == null) return null;
		long now = engine.currentTimestamp().longValue() / 1000;
		List<String> out = new ArrayList<>();
		for (int i = 0; i < raw.count(); i++) {
			AString jwt = RT.ensureString(raw.get(i));
			UCAN token = (i < tokens.size()) ? tokens.get(i) : null;
			if (jwt == null || token == null) continue;                    // unparseable → don't relay
			if (!UCANValidator.checkTemporalBounds(token, now)) continue;  // expired → inert
			AString aud = token.getAudience();
			AVector<ACell> capabilities = token.getCapabilities();
			if (aud == null || !aud.equals(principal)
					|| capabilities == null || capabilities.isEmpty()) continue;
			out.add(jwt.toString());
		}
		return out.isEmpty() ? null : out;
	}

	/** Finds the caller's explicit, target-audienced identity credential, in date by the venue's clock. */
	static String identityCredential(Engine engine, RequestContext ctx, List<UCAN> tokens,
			AString caller, AString targetVenueDID) {
		AVector<ACell> raw = ctx.getRawUcans();
		if (raw == null || tokens == null || caller == null || targetVenueDID == null) return null;
		long now = engine.currentTimestamp().longValue() / 1000;
		String found = null;
		for (int i = 0; i < raw.count(); i++) {
			AString jwt = RT.ensureString(raw.get(i));
			UCAN token = (i < tokens.size()) ? tokens.get(i) : null;
			if (jwt == null || token == null
					|| !UCANValidator.checkTemporalBounds(token, now)
					|| !caller.equals(token.getIssuer())
					|| !targetVenueDID.equals(token.getAudience())) continue;
			AVector<ACell> capabilities = token.getCapabilities();
			if (capabilities == null || !capabilities.isEmpty()) continue;
			if (found != null && !found.equals(jwt.toString())) {
				throw new InvalidInputException(
					"Multiple caller identity credentials were supplied for " + targetVenueDID);
			}
			found = jwt.toString();
		}
		return found;
	}

	/**
	 * Parses the caller's raw transport tokens for audience/issuer inspection,
	 * verified with the venue's DID verifier and clock; null-padded on failure
	 * so indices align with {@link RequestContext#getRawUcans()}.
	 */
	static List<UCAN> parsedRawUcans(Engine engine, RequestContext ctx) {
		AVector<ACell> raw = ctx.getRawUcans();
		if (raw == null || raw.isEmpty()) return null;
		List<UCAN> out = new ArrayList<>();
		long now = engine.currentTimestamp().longValue() / 1000;
		for (long i = 0; i < raw.count(); i++) {
			UCAN token = null;
			AString jwt = RT.ensureString(raw.get(i));
			if (jwt != null) {
				try {
					token = UcanJwtValidator.validateJWT(jwt, now, engine.didVerifier());
				} catch (Exception e) {
					// Defective token: null slot, grants nothing.
				}
			}
			out.add(token);
		}
		return out;
	}

	public static VenueConnection connect(Engine engine, RequestContext ctx, AString venueSpec, ACell input) {
		AString venueDID = engine.getDIDString();
		List<UCAN> tokens = parsedRawUcans(engine, ctx);
		AString modeCell = RT.ensureString(RT.getIn(input, AUTHENTICATE_AS));
		String mode = (modeCell != null) ? modeCell.toString() : "anonymous";
		boolean unrestricted = engine.outboundUnrestricted(ctx);

		VenueAuth auth;
		AString principal;
		String targetDID = null;
		AMap<AString, ACell> saved = Maps.of(AUTHENTICATE_AS, Strings.create(mode));
		if (unrestricted) saved = saved.assoc(OUTBOUND_ANY, CVMBool.TRUE);
		switch (mode) {
			case "anonymous" -> {
				auth = VenueAuth.none();
				principal = null;
			}
			case "caller" -> {
				targetDID = targetVenueDID(engine, venueSpec, unrestricted);
				String credential = identityCredential(engine, ctx, tokens, ctx.getCallerDID(),
					Strings.create(targetDID));
				if (credential == null) throw new AuthException(NO_CALLER_CREDENTIAL + targetDID);
				auth = VenueAuth.bearer(credential);
				principal = ctx.getCallerDID();
			}
			case "venue" -> {
				if (!engine.isDirectVenue(ctx)) throw new AuthException(VENUE_MODE_DENIED);
				targetDID = targetVenueDID(engine, venueSpec, unrestricted);
				auth = VenueAuth.namedKeyPair(engine.getKeyPair(), venueDID.toString(),
					() -> engine.currentTimestamp().longValue());
				saved = saved.assoc(Fields.VENUE, Strings.create(targetDID));
				principal = venueDID;
			}
			default -> throw new InvalidInputException(
				"authenticateAs must be one of: anonymous, caller, venue");
		}

		Venue venue = engine.connectVenue(venueSpec.toString(), targetDID, auth, unrestricted);
		List<String> grants = admissibleGrants(engine, ctx, tokens, principal);
		venue.setUcans(grants);
		// A hop that signed in as its caller is never resumed, so it keeps no grants either
		if (grants != null && !"caller".equals(mode)) {
			saved = saved.assoc(Fields.UCANS, convex.core.data.Vectors.create(grants.stream().map(Strings::create).toList()));
		}
		return new VenueConnection(venue, saved);
	}

	public static final String JOB_OWNER_UNAVAILABLE = "The remote Job owner could not be established; supply owner explicitly";
	public static final String INVALID_JOB_OWNER = "'owner' must be a DID string when provided";

	/** Validate a lookup namespace without replacing the caller's identity. */
	public static AString jobOwner(ACell input) {
		ACell value = RT.getIn(input, Fields.OWNER);
		if (value == null) return null;
		if (value instanceof AString owner && owner.toString().startsWith("did:")) return owner;
		throw new InvalidInputException(INVALID_JOB_OWNER);
	}

	/** Connect using explicit remote credentials; the hosting venue authorizes the Job action. */
	public static VenueConnection job(Engine engine, RequestContext ctx, AString target,
			ACell input) {
		VenueConnection connection = connect(engine, ctx, target, input);
		Venue venue = connection.venue();
		AString owner = jobOwner(input);
		if (owner == null) {
			AString mode = RT.ensureString(RT.getIn(input, AUTHENTICATE_AS));
			if (CALLER.equals(mode)) owner = ctx.getCallerDID();
			else if (Fields.VENUE.equals(mode)) owner = engine.getDIDString();
			else {
				try {
					owner = Strings.create((venue instanceof VenueHTTP http ? http.venueDID() : venue.getDID().toString()) + ":public");
				} catch (IllegalArgumentException e) { throw new ResponseException(JOB_OWNER_UNAVAILABLE, e); }
			}
		}
		venue.setJobOwner(owner);
		return new VenueConnection(venue, connection.credentials().assoc(Fields.OWNER, owner));
	}

	/**
	 * Whether a job can reach its remote job again after a restart over the
	 * connection {@code saved} describes. Not when it signed in as its caller:
	 * only the request presented that credential, and the venue keeps none.
	 */
	public static boolean resumable(AMap<AString, ACell> saved) {
		return !CVMBool.FALSE.equals(saved.get(RESUMABLE)) && !CALLER.equals(saved.get(AUTHENTICATE_AS));
	}

	/**
	 * Rebuilds observation of a job {@link #connect} already authorised; grants
	 * nothing new. The target passes the outbound guard again, as it stands now,
	 * unless the original caller could have the venue dial anywhere, and the
	 * venue signs in as itself again only once the target is proven again to
	 * hold the DID saved with it. A connection that signed in as its caller is
	 * not {@link #resumable}.
	 *
	 * @throws AuthException if the target is not proven to hold the saved DID
	 * @throws UnavailableException with {@link RemoteJobs#NO_CONNECTION} when {@code saved} keeps no connection
	 */
	public static Venue restore(Engine engine, AString target, AMap<AString, ACell> saved) {
		AString kept = RT.ensureString(saved.get(AUTHENTICATE_AS));
		if (kept == null) throw new UnavailableException(RemoteJobs.NO_CONNECTION);
		String mode = kept.toString();
		boolean unrestricted = CVMBool.TRUE.equals(saved.get(OUTBOUND_ANY));
		AString savedDID = RT.ensureString(saved.get(Fields.VENUE));
		String targetDID = savedDID == null ? null : savedDID.toString();
		if (!"anonymous".equals(mode)) {
			if (targetDID == null) throw new AuthException(NO_SAVED_DID + target);
			requireProven(engine, target.toString(), targetDID, unrestricted);
		}
		VenueAuth auth = switch (mode) {
			case "anonymous" -> VenueAuth.none();
			case "venue" -> VenueAuth.namedKeyPair(engine.getKeyPair(), engine.getDIDString().toString(),
				() -> engine.currentTimestamp().longValue());
			default -> throw new IllegalArgumentException("Invalid saved remote authentication mode");
		};
		Venue venue = engine.connectVenue(target.toString(), targetDID, auth, unrestricted);
		venue.setJobOwner(RT.ensureString(saved.get(Fields.OWNER)));
		AVector<ACell> grants = RT.ensureVector(saved.get(Fields.UCANS));
		if (grants != null) {
			List<String> raw = new ArrayList<>();
			for (ACell grant : grants) raw.add(grant.toString());
			venue.setUcans(raw);
		}
		return venue;
	}

	/**
	 * Resolve the target identity once for audience-bound venue authentication.
	 * Discovery dials the target itself, so it passes the outbound guard first
	 * unless the caller could have the venue dial anywhere. A credential goes
	 * only to a venue proven to hold the DID it is audienced to
	 * ({@link Engine#venueProof}).
	 */
	private static String targetVenueDID(Engine engine, AString venueSpec, boolean unrestricted) {
		String target = venueSpec.toString();
		String did;
		if (target.startsWith("did:")) {
			did = target;
		} else {
			if (!unrestricted) engine.requireSafeUrl(target);
			did = VenueDID.discover(target);
		}
		requireProven(engine, target, did, unrestricted);
		return did;
	}

	private static void requireProven(Engine engine, String target, String did, boolean unrestricted) {
		String reason = engine.venueProof(target, did, unrestricted);
		if (reason != null) throw new AuthException(VENUE_UNPROVEN + did + " at " + target + ": " + reason);
	}

}
