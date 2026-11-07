package covia.adapter;

import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import convex.auth.did.DID;
import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.ConfigurationException;
import covia.exception.InvalidInputException;
import covia.exception.NotSupportedException;
import covia.grid.Venue;
import covia.grid.auth.VenueAuth;
import covia.grid.auth.VenueChallenge;
import covia.grid.auth.VenueDID;
import covia.grid.client.VenueHTTP;
import covia.venue.RequestContext;
import covia.venue.auth.AuthenticationMethods;
import covia.venue.auth.AuthResult;
import covia.venue.auth.VenueAuthenticator;
import covia.venue.auth.VenueProofMethods;

/**
 * Built-in authentication, venue identity proof, identity introspection and
 * caller token issuance.
 *
 * <p>{@code auth:whoami} — returns the caller DID as resolved by the venue's
 * auth middleware. Useful for diagnostics and for tests that need to verify
 * which identity the venue authenticated a request as.</p>
 */
public class AuthAdapter extends AAdapter implements AuthenticationMethods, VenueProofMethods {
	/** Venue proof by address: a did:web venue at its own address ({@link VenueDID#provenAt}). */
	public static final AString ADDRESS_PROOF = Strings.intern("address");
	/** Venue proof by challenge ({@link VenueChallenge}). */
	public static final AString CHALLENGE_PROOF = Strings.intern("challenge");
	/** Seconds for which a challenge proof is reused for the same target and DID. */
	public static final long CHALLENGE_REUSE = 120;
	public static final long DEFAULT_IDENTITY_LIFETIME = 300;
	public static final String IDENTITY_AUTH_REQUIRED = "An authenticated non-public caller is required";
	public static final String IDENTITY_DIRECT_ONLY = "The caller must act under its own authority, or as a sub-principal; "
		+ "a principal acting as itself under a restricted scope cannot mint its identity";
	public static final String IDENTITY_NOT_OWNED = "No key here signs for the caller identity: it must be the venue signing key DID, "
		+ "a did:key whose key it holds in its own keyring, or nested beneath one of these";
	public static final String IDENTITY_INPUT = "Expected an object containing only aud, name, lifetime and overwrite";
	public static final String IDENTITY_AUDIENCE = "aud must be a target venue DID without a path, query or fragment";
	public static final String IDENTITY_LIFETIME = "lifetime must be a whole number of seconds, at least 1, with an expiry time that can be represented";
	public static final String IDENTITY_OVERWRITE = "overwrite must be a boolean";
	private static final AString IDENTITY_OP = Strings.intern("v/ops/auth/identity-token");
	private static final AString LIFETIME = Strings.intern("lifetime");
	private static final AString OVERWRITE = Strings.intern("overwrite");
	private static final AString SECRET = Strings.intern("secret");
	private static final Set<AString> IDENTITY_FIELDS = Set.of(JWT.AUD, Fields.NAME, LIFETIME, OVERWRITE);
	private VenueAuthenticator verifier;
	private Map<AString, Verifier> methods = Map.of();
	private Map<AString, VenueProofMethods.Method> proofs = Map.of();

	/** Built-in verification stays in the existing venue authentication service. */
	protected VenueAuthenticator createAuthenticator() { return new VenueAuthenticator(engine); }

	@Override
	public Map<AString, Verifier> authenticationMethods() { return methods; }

	@Override
	public Map<AString, VenueProofMethods.Method> venueProofMethods() { return proofs; }

	private static Map<AString, VenueProofMethods.Method> builtInProofs() {
		VenueProofMethods.Method address = (target, did, connection) ->
			VenueDID.provenAt(target, did) ? null : VenueDID.NOT_ITS_ADDRESS;
		VenueProofMethods.Method challenge = new VenueProofMethods.Method() {
			@Override
			public String prove(String target, String did, Supplier<Venue> connection) {
				Venue venue;
				try {
					venue = connection.get();
				} catch (InvalidInputException | ConfigurationException
						| IllegalArgumentException | IllegalStateException e) {
					// Refused by the outbound guard, or no address to dial
					return VenueChallenge.Reason.UNREACHABLE + e.getMessage();
				}
				return venue instanceof VenueHTTP http ? VenueChallenge.prove(http, did) : VenueChallenge.Reason.NOT_HTTP;
			}

			@Override
			public long reuseSeconds() { return CHALLENGE_REUSE; }
		};
		return Map.of(ADDRESS_PROOF, address, CHALLENGE_PROOF, challenge);
	}

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
		proofs = builtInProofs();
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
			new NotSupportedException("Unknown auth operation: " + op));
	}

	/** Issue identity evidence only; never accept caller-supplied claims or keys. */
	/**
	 * The key that signs an identity token for {@code caller}: its root's. The
	 * venue's key for the venue's did:key and the identities nested below it;
	 * otherwise the key a root did:key holds for itself in its own keyring
	 * (HELD_KEYS.md, principle 4). Null when no key signs for it here.
	 */
	private AKeyPair identitySigner(RequestContext ctx, AString caller) {
		AString venue = UCAN.toDIDKey(engine.getKeyPair().getAccountKey());
		if (venue.equals(caller) || VenueAuthenticator.isNestedIdentity(caller, venue)) return engine.getKeyPair();
		String s = caller.toString();
		if (!s.startsWith(DID_KEY)) return null;
		int nested = s.indexOf(':', DID_KEY.length());
		AString root = (nested < 0) ? caller : Strings.create(s.substring(0, nested));
		if (!root.equals(caller) && !VenueAuthenticator.isNestedIdentity(caller, root)) return null;
		// With the crypto adapter disabled, no held key signs.
		if (!(engine.getAdapter("crypto") instanceof CryptoAdapter crypto)) return null;
		return crypto.identityKeyPair(ctx, root);
	}

	private static final String DID_KEY = "did:key:";

	private ACell identityToken(RequestContext ctx, ACell input) {
		AString caller = ctx == null ? null : ctx.getCallerDID();
		if (caller == null || engine.isPublicPrincipal(caller) || engine.isPublicPrincipal(ctx.getUserDID())) {
			throw new AuthException(IDENTITY_AUTH_REQUIRED);
		}
		// A restricted scope on a principal acting as itself (a scoped credential,
		// say) bounds what this request may do; a bearer for that identity would
		// carry it beyond the bound, so the op refuses the shape. A sub-principal
		// (an agent, a delegate) mints only its own identity, which the venue
		// bounds again wherever it is used, and a principal with its own
		// authority has no scope.
		if (ctx.getCaps() != null && !ctx.isSubPrincipal()) {
			throw new AuthException(IDENTITY_DIRECT_ONLY);
		}
		// Pin the signing action, so custom metadata/aliases cannot bypass its grant.
		// Checked before the signing key is opened: a refused request uses no key
		// and learns nothing about which keys are held.
		engine.requireInvoke(ctx, IDENTITY_OP);
		AKeyPair signer = identitySigner(ctx, caller);
		if (signer == null) throw new AuthException(IDENTITY_NOT_OWNED);
		if (!(input instanceof AMap<?, ?> params)) throw new InvalidInputException(IDENTITY_INPUT);
		for (long i = 0; i < params.count(); i++) {
			if (!(params.entryAt(i).getKey() instanceof AString key) || !IDENTITY_FIELDS.contains(key)) {
				throw new InvalidInputException(IDENTITY_INPUT);
			}
		}
		ACell audienceValue = params.get(JWT.AUD);
		if (!(audienceValue instanceof AString audience) || !DID.isCanonicalBase(audience)) {
			throw new InvalidInputException(IDENTITY_AUDIENCE);
		}
		// The caller chooses how long its token lasts; the receiver applies its
		// own limit when it is presented (auth.maxTokenLifetime on a venue).
		long lifetime = DEFAULT_IDENTITY_LIFETIME;
		if (params.containsKey(LIFETIME)) {
			if (!(params.get(LIFETIME) instanceof CVMLong seconds) || seconds.longValue() < 1
					|| seconds.longValue() > Long.MAX_VALUE - System.currentTimeMillis() / 1000) {
				throw new InvalidInputException(IDENTITY_LIFETIME);
			}
			lifetime = seconds.longValue();
		}
		if (params.containsKey(OVERWRITE) && !(params.get(OVERWRITE) instanceof CVMBool)) {
			throw new InvalidInputException(IDENTITY_OVERWRITE);
		}
		AString name = params.get(Fields.NAME) instanceof AString s ? s : null;
		AString token = Strings.create(VenueAuth.namedKeyPair(signer,
			caller.toString(), lifetime).mintToken(audience.toString()));
		// Reuse the secret/write gate, encrypted storage, collision handling and audit.
		SecretAdapter.storeSecret(engine, ctx, name, token, CVMBool.TRUE.equals(params.get(OVERWRITE)));
		return Maps.of(SECRET, Strings.create("s/" + name), JWT.SUB, caller, JWT.AUD, audience,
			JWT.EXP, JWT.parse(token).getClaims().get(JWT.EXP));
	}
}
