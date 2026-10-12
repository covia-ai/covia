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
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Grid;
import covia.grid.Venue;
import covia.grid.auth.VenueAuth;
import covia.grid.auth.VenueDID;
import covia.venue.Engine;
import covia.venue.RequestContext;

/** Shared native venue connection policy. Saved authority is for protected storage only. */
public record VenueConnection(Venue venue, AMap<AString, ACell> credentials) {
	private static final AString AUTHENTICATE_AS = Strings.intern("authenticateAs");
	public static VenueConnection connect(Engine engine, RequestContext ctx, AString venueSpec, ACell input) {
		AString venueDID = engine.getDIDString();
		List<UCAN> tokens = GridAdapter.parsedRawUcans(ctx, engine.didVerifier());
		AString modeCell = RT.ensureString(RT.getIn(input, AUTHENTICATE_AS));
		String mode = (modeCell != null) ? modeCell.toString() : "anonymous";

		VenueAuth auth;
		AString principal;
		AMap<AString, ACell> saved = Maps.of(AUTHENTICATE_AS, Strings.create(mode));
		switch (mode) {
			case "anonymous" -> {
				auth = VenueAuth.none();
				principal = null;
			}
			case "caller" -> {
				AString targetDID = Strings.create(targetVenueDID(venueSpec));
				String credential = GridAdapter.identityCredential(ctx, tokens, ctx.getCallerDID(), targetDID);
				if (credential == null) {
					throw new AuthException("authenticateAs=caller requires an in-date, empty-att "
						+ "identity credential issued by the caller to " + targetDID);
				}
				auth = VenueAuth.bearer(credential);
				saved = saved.assoc(Fields.BEARER_TOKEN, Strings.create(credential));
				principal = ctx.getCallerDID();
			}
			case "venue" -> {
				AString targetDID = Strings.create(targetVenueDID(venueSpec));
				if (!GridAdapter.hasRelayGrant(tokens, ctx.getCallerDID(), venueDID)) {
					throw new AuthException("authenticateAs=venue requires venue/relay on "
						+ ctx.getCallerDID() + " granted to " + venueDID);
				}
				auth = VenueAuth.identityKeyPair(engine.getKeyPair(), venueDID.toString(),
					targetDID.toString());
				saved = saved.assoc(Fields.VENUE, targetDID);
				principal = venueDID;
			}
			default -> throw new IllegalArgumentException(
				"authenticateAs must be one of: anonymous, caller, venue");
		}

		Venue venue = Grid.connect(venueSpec.toString(), auth);
		List<String> grants = GridAdapter.admissibleGrants(ctx, tokens, principal);
		venue.setUcans(grants);
		if (grants != null) saved = saved.assoc(Fields.UCANS, convex.core.data.Vectors.create(grants.stream().map(Strings::create).toList()));
		return new VenueConnection(venue, saved);
	}

	public static Venue restore(Engine engine, AString target, AMap<AString, ACell> saved) {
		String mode = RT.ensureString(saved.get(AUTHENTICATE_AS)).toString();
		VenueAuth auth = switch (mode) {
			case "anonymous" -> VenueAuth.none();
			case "caller" -> VenueAuth.bearer(RT.ensureString(saved.get(Fields.BEARER_TOKEN)).toString());
			case "venue" -> VenueAuth.identityKeyPair(engine.getKeyPair(), engine.getDIDString().toString(),
				RT.ensureString(saved.get(Fields.VENUE)).toString());
			default -> throw new IllegalArgumentException("Invalid saved remote authentication mode");
		};
		Venue venue = Grid.connect(target.toString(), auth);
		AVector<ACell> grants = RT.ensureVector(saved.get(Fields.UCANS));
		if (grants != null) {
			List<String> raw = new ArrayList<>();
			for (ACell grant : grants) raw.add(grant.toString());
			venue.setUcans(raw);
		}
		return venue;
	}

	/** Resolve the target identity once for audience-bound venue authentication. */
	private static String targetVenueDID(AString venueSpec) {
		String target = venueSpec.toString();
		if (target.startsWith("did:")) return target;
		return VenueDID.discover(target);
	}

}
