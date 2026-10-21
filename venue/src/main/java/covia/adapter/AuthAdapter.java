package covia.adapter;

import java.util.concurrent.CompletableFuture;
import java.util.Map;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.venue.RequestContext;
import covia.venue.auth.AuthenticationMethods;
import covia.venue.auth.AuthResult;
import covia.venue.auth.VenueAuthenticator;

/**
 * Built-in authentication implementations and identity introspection.
 *
 * <p>{@code auth:whoami} — returns the caller DID as resolved by the venue's
 * auth middleware. Useful for diagnostics and for tests that need to verify
 * which identity the venue authenticated a request as.</p>
 */
public class AuthAdapter extends AAdapter implements AuthenticationMethods {
	private VenueAuthenticator verifier;
	private Map<AString, Verifier> methods = Map.of();

	/** Built-in verification stays in the existing venue authentication service. */
	protected VenueAuthenticator createAuthenticator() { return new VenueAuthenticator(engine); }

	@Override
	public Map<AString, Verifier> authenticationMethods() { return methods; }

	/** Legacy venue JWTs have unknown origin and are not a named method. */
	public AuthResult verifyLegacyCredential(ACell input) { return verifier.verifyLegacyCredential(input); }

	private static final AString K_AUTHENTICATED = Strings.intern("authenticated");
	private static final AString K_INTERNAL = Strings.intern("internal");

	@Override
	public String getName() {
		return "auth";
	}

	@Override
	public String getDescription() {
		return "Authentication introspection — exposes the caller's authenticated identity "
			+ "as resolved by the venue. Use whoami to verify which DID the venue attributes "
			+ "to the current request.";
	}

	@Override
	protected void installAssets() {
		verifier = createAuthenticator();
		methods = verifier.builtInMethods();
		// The adapter's own skill: v/skills/auth lives and dies with this adapter.
		installSkill("auth/auth", "/skills/auth.json");
		installSkill("root/auth", "/skills/auth.json");
		installAsset("auth/whoami", "/adapters/auth/whoami.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String op = getSubOperation(meta);
		if ("whoami".equals(op)) {
			AString caller = (ctx != null) ? ctx.getCallerDID() : null;
			// "internal" in the response is now a derived flag: caller IS
			// the venue itself. Engine-side trust paths (venueContext) show
			// up here; UCAN-authenticated callers don't.
			boolean internal = (caller != null) && caller.equals(engine.getDIDString());
			AMap<AString, ACell> out = Maps.of(
				Fields.CALLER, caller,
				K_AUTHENTICATED, caller != null,
				K_INTERNAL, internal
			);
			return CompletableFuture.completedFuture(out);
		}
		return CompletableFuture.failedFuture(
			new IllegalArgumentException("Unknown auth operation: " + op));
	}
}
