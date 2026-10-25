package covia.adapter;

import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.Set;

import convex.auth.did.DID;
import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.grid.auth.VenueAuth;
import covia.venue.RequestContext;
import covia.venue.auth.AuthenticationMethods;
import covia.venue.auth.AuthResult;
import covia.venue.auth.VenueAuthenticator;

/**
 * Built-in authentication, identity introspection and caller token issuance.
 *
 * <p>{@code auth:whoami} — returns the caller DID as resolved by the venue's
 * auth middleware. Useful for diagnostics and for tests that need to verify
 * which identity the venue authenticated a request as.</p>
 */
public class AuthAdapter extends AAdapter implements AuthenticationMethods {
	public static final long DEFAULT_IDENTITY_LIFETIME = 300;
	public static final long MAX_IDENTITY_LIFETIME = 3600;
	public static final String IDENTITY_AUTH_REQUIRED = "An authenticated non-public caller is required";
	public static final String IDENTITY_NOT_OWNED = "The caller identity must be the venue signing key DID or nested beneath it";
	public static final String IDENTITY_INPUT = "Expected an object containing only aud, name, lifetime and overwrite";
	public static final String IDENTITY_AUDIENCE = "aud must be a target venue DID without a path, query or fragment";
	public static final String IDENTITY_LIFETIME = "lifetime must be an integer from 1 to 3600 seconds";
	public static final String IDENTITY_OVERWRITE = "overwrite must be a boolean";
	private static final AString IDENTITY_OP = Strings.intern("v/ops/auth/identity-token");
	private static final AString LIFETIME = Strings.intern("lifetime");
	private static final AString OVERWRITE = Strings.intern("overwrite");
	private static final AString SECRET = Strings.intern("secret");
	private static final Set<AString> IDENTITY_FIELDS = Set.of(JWT.AUD, Fields.NAME, LIFETIME, OVERWRITE);
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
		return "Authentication and identity — inspect the caller with whoami, or save "
			+ "an audience-bound identity token signed by this venue for its own nested identities.";
	}

	@Override
	protected void installAssets() {
		verifier = createAuthenticator();
		methods = verifier.builtInMethods();
		// The adapter's own skill: v/skills/auth lives and dies with this adapter.
		installSkill("auth/auth", "/skills/auth.json");
		installSkill("root/auth", "/skills/auth.json");
		installAsset("auth/whoami", "/adapters/auth/whoami.json");
		installAsset("auth/identity-token", "/adapters/auth/identity-token.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String op = getSubOperation(meta);
		if ("identity-token".equals(op)) {
			try {
				return CompletableFuture.completedFuture(identityToken(ctx, input));
			} catch (RuntimeException e) {
				return CompletableFuture.failedFuture(e);
			}
		}
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

	/** Issue identity evidence only; never accept caller-supplied claims or keys. */
	private ACell identityToken(RequestContext ctx, ACell input) {
		AString caller = ctx == null ? null : ctx.getCallerDID();
		if (caller == null || engine.isPublicPrincipal(caller) || engine.isPublicPrincipal(ctx.getUserDID())) {
			throw new IllegalArgumentException(IDENTITY_AUTH_REQUIRED);
		}
		AString signer = UCAN.toDIDKey(engine.getKeyPair().getAccountKey());
		if (!signer.equals(caller) && !VenueAuthenticator.isNestedIdentity(caller, signer)) {
			throw new IllegalArgumentException(IDENTITY_NOT_OWNED);
		}
		// Pin the signing action, so custom metadata/aliases cannot bypass its grant.
		engine.requireAuthority(ctx, IDENTITY_OP, INVOKE);
		if (!(input instanceof AMap<?, ?> params)) throw new IllegalArgumentException(IDENTITY_INPUT);
		for (long i = 0; i < params.count(); i++) {
			if (!(params.entryAt(i).getKey() instanceof AString key) || !IDENTITY_FIELDS.contains(key)) {
				throw new IllegalArgumentException(IDENTITY_INPUT);
			}
		}
		ACell audienceValue = params.get(JWT.AUD);
		if (!(audienceValue instanceof AString audience) || !DID.isCanonicalBase(audience)) {
			throw new IllegalArgumentException(IDENTITY_AUDIENCE);
		}
		long lifetime = DEFAULT_IDENTITY_LIFETIME;
		if (params.containsKey(LIFETIME)) {
			if (!(params.get(LIFETIME) instanceof CVMLong seconds)
					|| seconds.longValue() < 1 || seconds.longValue() > MAX_IDENTITY_LIFETIME) {
				throw new IllegalArgumentException(IDENTITY_LIFETIME);
			}
			lifetime = seconds.longValue();
		}
		if (params.containsKey(OVERWRITE) && !(params.get(OVERWRITE) instanceof CVMBool)) {
			throw new IllegalArgumentException(IDENTITY_OVERWRITE);
		}
		AString name = params.get(Fields.NAME) instanceof AString s ? s : null;
		AString token = Strings.create(VenueAuth.namedKeyPair(engine.getKeyPair(),
			caller.toString(), audience.toString(), lifetime).mintToken());
		// Reuse the secret/write gate, encrypted storage, collision handling and audit.
		SecretAdapter.storeSecret(engine, ctx, name, token, CVMBool.TRUE.equals(params.get(OVERWRITE)));
		return Maps.of(SECRET, Strings.create("s/" + name), JWT.SUB, caller, JWT.AUD, audience,
			JWT.EXP, JWT.parse(token).getClaims().get(JWT.EXP));
	}
}
