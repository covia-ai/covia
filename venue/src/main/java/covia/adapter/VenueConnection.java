package covia.adapter;

import java.util.ArrayList;
import java.util.List;
import convex.auth.ucan.UCAN;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Venue;
import covia.grid.auth.VenueAuth;
import covia.grid.auth.VenueDID;
import covia.venue.Engine;
import covia.venue.RequestContext;

/** Shared native venue connection policy. Saved authority is for protected storage only. */
public record VenueConnection(Venue venue, AMap<AString, ACell> credentials) {
	private static final AString AUTHENTICATE_AS = Strings.intern("authenticateAs");
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
	 * Refusal to re-send a saved credential when the job saved no DID for its
	 * target, so the target cannot be proven again.
	 */
	public static final String NO_SAVED_DID =
		"No credential is re-sent to a venue whose DID the job did not save: ";

	public static VenueConnection connect(Engine engine, RequestContext ctx, AString venueSpec, ACell input) {
		AString venueDID = engine.getDIDString();
		List<UCAN> tokens = GridAdapter.parsedRawUcans(ctx, engine.didVerifier());
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
				String credential = GridAdapter.identityCredential(ctx, tokens, ctx.getCallerDID(),
					Strings.create(targetDID));
				if (credential == null) {
					throw new AuthException("authenticateAs=caller requires an in-date, empty-att "
						+ "identity credential issued by the caller to " + targetDID);
				}
				auth = VenueAuth.bearer(credential);
				saved = saved.assoc(Fields.BEARER_TOKEN, Strings.create(credential))
					.assoc(Fields.VENUE, Strings.create(targetDID));
				principal = ctx.getCallerDID();
			}
			case "venue" -> {
				if (!engine.isDirectVenue(ctx)) throw new AuthException(VENUE_MODE_DENIED);
				targetDID = targetVenueDID(engine, venueSpec, unrestricted);
				auth = VenueAuth.namedKeyPair(engine.getKeyPair(), venueDID.toString());
				saved = saved.assoc(Fields.VENUE, Strings.create(targetDID));
				principal = venueDID;
			}
			default -> throw new IllegalArgumentException(
				"authenticateAs must be one of: anonymous, caller, venue");
		}

		Venue venue = engine.connectVenue(venueSpec.toString(), targetDID, auth, unrestricted);
		List<String> grants = GridAdapter.admissibleGrants(ctx, tokens, principal);
		venue.setUcans(grants);
		if (grants != null) saved = saved.assoc(Fields.UCANS, convex.core.data.Vectors.create(grants.stream().map(Strings::create).toList()));
		return new VenueConnection(venue, saved);
	}

	/**
	 * Rebuilds observation of a job {@link #connect} already authorised; grants
	 * nothing new. The target passes the outbound guard again, as it stands now,
	 * unless the original caller could have the venue dial anywhere, and a
	 * saved credential is re-sent only once the target is proven again to hold
	 * the DID saved with it.
	 *
	 * @throws AuthException if the target is not proven to hold the saved DID
	 */
	public static Venue restore(Engine engine, AString target, AMap<AString, ACell> saved) {
		String mode = RT.ensureString(saved.get(AUTHENTICATE_AS)).toString();
		boolean unrestricted = CVMBool.TRUE.equals(saved.get(OUTBOUND_ANY));
		AString savedDID = RT.ensureString(saved.get(Fields.VENUE));
		String targetDID = savedDID == null ? null : savedDID.toString();
		if (!"anonymous".equals(mode)) {
			if (targetDID == null) throw new AuthException(NO_SAVED_DID + target);
			requireProven(engine, target.toString(), targetDID, unrestricted);
		}
		VenueAuth auth = switch (mode) {
			case "anonymous" -> VenueAuth.none();
			case "caller" -> VenueAuth.bearer(RT.ensureString(saved.get(Fields.BEARER_TOKEN)).toString());
			case "venue" -> VenueAuth.namedKeyPair(engine.getKeyPair(), engine.getDIDString().toString());
			default -> throw new IllegalArgumentException("Invalid saved remote authentication mode");
		};
		Venue venue = engine.connectVenue(target.toString(), targetDID, auth, unrestricted);
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
