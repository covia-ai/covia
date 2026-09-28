package covia.venue.auth;

import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.auth.did.DID;
import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.venue.Auth;
import covia.venue.Engine;
import covia.venue.UcanJwtValidator;
import io.javalin.http.Context;

/**
 * Public authentication service for venue embedders.
 *
 * <p>This is the single policy implementation for credentials accepted by a
 * venue: self-issued and named-user EdDSA tokens, venue-issued sessions,
 * empty-att UCAN identity bearers, and configured external OAuth providers.
 * Embedders should use this service rather than reproducing the venue's signature, audience, temporal,
 * or local-user mapping rules.</p>
 *
 * <p>Authentication does not admit or create a venue user. Route middleware or
 * an embedder may apply admission separately when the route requires it.</p>
 */
public final class VenueAuthenticator {
	private static final Logger log =
		LoggerFactory.getLogger(VenueAuthenticator.class);

	private static final String AUTHENTICATED_IDENTITY_ATTR = "authenticatedIdentity";
	private static final String VENUE_USER_ATTR = "callerDID";

	private static final AString SUB = Fields.SUB;
	private static final AString KID = Fields.KID;
	private static final AString EMAIL = Fields.EMAIL;
	private static final AString ISS = Strings.intern("iss");
	private static final AString AUD = Strings.intern("aud");
	private static final AString EXP = Strings.intern("exp");
	private static final AString NBF = Strings.intern("nbf");

	/** Clock-skew leeway for JWT temporal bounds, in seconds. */
	private static final long CLOCK_SKEW_SECONDS = 60;

	/**
	 * Prefixes on a 401 message, naming which credential shape the verifier
	 * read before the reason: a caller who meant to send a UCAN bearer and
	 * forgot {@code att} learns their token was read as self-issued.
	 */
	public static final String UCAN_REJECTED_PREFIX = "UCAN bearer rejected: ";
	public static final String SELF_ISSUED_REJECTED_PREFIX = "Self-issued token rejected: ";
	public static final String VENUE_TOKEN_REJECTED_PREFIX = "Venue token rejected: ";
	public static final String PROVIDER_TOKEN_REJECTED_PREFIX = "Provider token rejected: ";
	/** For a token no verifier claims, or that does not parse at all. */
	public static final String TOKEN_REJECTED_PREFIX = "Token rejected: ";

	private final AccountKey venueKey;
	private final AString venueDID;
	private final Auth venueAuth;
	private final Map<String, OAuthConfig> externalProviders;
	private final String audiencePolicy;
	private final Set<String> acceptedAudienceStrings;
	private final Set<AString> acceptedAudiences;
	private final Engine engine;
	/** Bearer credentials must expire ({@code auth.requireExp}); a cap on how far ahead, 0 = none ({@code auth.maxTokenLifetime}). */
	private final boolean requireExp;
	private final long maxLifetimeSeconds;

	private record VerifiedPrincipal(
			AString authenticatedIdentity,
			AString venueUserDID) {}

	/**
	 * One verifier's answer for a token it has claimed: the principal, or the
	 * reason the token was rejected — never both. The same null-means-fine
	 * convention as {@link covia.lattice.CapabilityChecker#allows} and
	 * {@link UcanJwtValidator.Validation}: a check returns null when it passes
	 * and a human-readable reason when it does not, and the reason bubbles
	 * unchanged into the 401.
	 */
	private record Verdict(VerifiedPrincipal principal, String reason) {
		static Verdict ok(AString authenticatedIdentity, AString venueUserDID) {
			return new Verdict(new VerifiedPrincipal(authenticatedIdentity, venueUserDID), null);
		}

		static Verdict fail(String reason) {
			return new Verdict(null, reason);
		}
	}

	/**
	 * Creates the authenticator for an engine. Venue embedders normally obtain
	 * this instance from {@code VenueServer.authenticator()}.
	 */
	public VenueAuthenticator(Engine engine) {
		if (engine == null) throw new IllegalArgumentException("engine is required");
		this.engine = engine;
		this.venueKey = engine.getAccountKey();
		this.venueDID = engine.getDIDString();
		this.venueAuth = engine.getAuth();
		this.externalProviders = venueAuth.getLoginProviders().hasProviders()
			? venueAuth.getLoginProviders().getProviders() : null;
		this.audiencePolicy = venueAuth.getAudiencePolicy();
		this.requireExp = engine.config().isRequireExp();
		this.maxLifetimeSeconds = engine.config().getMaxTokenLifetime();

		Set<String> audienceStrings = new HashSet<>();
		audienceStrings.add(venueDID.toString());
		audienceStrings.addAll(venueAuth.getConfiguredAudiences());
		AString webDID = venueAuth.getWebDID();
		if (webDID != null) audienceStrings.add(webDID.toString());
		// The key-derived did:key is always an accepted audience: a declared
		// did:web identity (covia#343) must not orphan clients that
		// audience-bind to the venue's key form.
		if (venueKey != null) {
			audienceStrings.add("did:key:" + Multikey.encodePublicKey(venueKey));
		}
		this.acceptedAudienceStrings = Set.copyOf(audienceStrings);

		Set<AString> audiences = new HashSet<>();
		for (String audience : audienceStrings) {
			audiences.add(Strings.create(audience));
		}
		this.acceptedAudiences = Set.copyOf(audiences);
	}

	/**
	 * Authenticates a credential and returns the effective local venue user.
	 * This form does not bind a request or admit the user.
	 *
	 * @throws AuthException if the token is absent, malformed, expired, not yet
	 *         valid, incorrectly audienced, or otherwise not accepted
	 */
	public AString authenticate(AString token) throws AuthException {
		return verify(token).venueUserDID();
	}

	/**
	 * Authenticates a credential and binds both the directly proven identity and
	 * effective venue user to a Javalin request. Authentication credentials are
	 * not retained as capability proofs. This does not admit the user.
	 *
	 * @return the effective local venue user
	 * @throws AuthException if the token is not accepted
	 */
	public AString authenticate(Context context, AString token) throws AuthException {
		if (context == null) throw new IllegalArgumentException("context is required");
		VerifiedPrincipal principal = verify(token);
		bindIdentity(context, principal.authenticatedIdentity(), principal.venueUserDID());
		return principal.venueUserDID();
	}

	/**
	 * Publishes an identity established by embedder-owned authentication.
	 * This is attribution only: it does not verify credentials or admit a user.
	 */
	public void bindIdentity(Context context, AString authenticatedIdentity,
			AString venueUserDID) {
		if (context == null) throw new IllegalArgumentException("context is required");
		if (authenticatedIdentity == null) {
			throw new IllegalArgumentException("authenticatedIdentity is required");
		}
		if (venueUserDID == null) {
			throw new IllegalArgumentException("venueUserDID is required");
		}
		context.attribute(AUTHENTICATED_IDENTITY_ATTR, authenticatedIdentity);
		context.attribute(VENUE_USER_ATTR, venueUserDID);
	}

	/** Returns the identity directly proven by the request credential. */
	public AString authenticatedIdentity(Context context) {
		return context.attribute(AUTHENTICATED_IDENTITY_ATTR);
	}

	/** Returns the effective local venue user bound to the request. */
	public AString authenticatedUser(Context context) {
		return context.attribute(VENUE_USER_ATTR);
	}

	/** Returns the immutable set of JWT audiences accepted by this venue. */
	public Set<AString> acceptedAudiences() {
		return acceptedAudiences;
	}

	/**
	 * The reasons a credential is refused, as the 401 states them after a
	 * {@code *_REJECTED_PREFIX}. Code composes messages from these and tests
	 * assert against them, never against the wording, so the wording is free
	 * to change. A constant ending in a space or colon is a fragment the
	 * message completes with the offending value.
	 */
	public static final class Reason {
		public static final String UNPARSEABLE = "unparseable JWT: expected three base64url-encoded segments";
		public static final String MISSING_CLAIMS = "missing JWT claims";
		public static final String UNRECOGNISED = "unrecognised credential: no sub claim, no att claim, "
			+ "not issued by this venue, alg ";
		public static final String UNRECOGNISED_HINT = " — expected a self-issued JWT (sub = your DID), "
			+ "a UCAN bearer (att), a token issued by this venue, or an RS256 provider token";
		public static final String UNSUPPORTED_ALGORITHM = "unsupported JWT algorithm: expected EdDSA, got ";
		public static final String MISSING_ISSUER = "missing issuer";
		public static final String NON_EMPTY_ATT = "a bearer credential must have empty att; present "
			+ "capability tokens as transport proofs, not as the Authorization bearer";
		public static final String EXPIRED = "token expired at ";
		public static final String NOT_YET_VALID = "token not valid before ";
		public static final String MISSING_EXP = "missing exp: this venue requires bearer credentials to expire (auth.requireExp)";
		public static final String NON_EXPIRING = "non-expiring credential: this venue requires bearer credentials to expire (auth.requireExp)";
		public static final String LIFETIME_EXCEEDS = "token expires further ahead than this venue allows (auth.maxTokenLifetime): ";
		public static final String AUDIENCE_REQUIRED = "audience (aud) is required but absent";
		public static final String AUDIENCE_NOT_THIS_VENUE = "token audience is not this venue: ";
		public static final String AUDIENCE_LIST_EXCLUDES_VENUE = "token audience list does not include this venue";
		public static final String MALFORMED_AUDIENCE = "malformed audience (aud) claim";
		public static final String MISSING_KID = "missing kid header: a self-issued token names its signing key in kid";
		public static final String KID_NOT_OF_SUBJECT = "is a key of ";
		public static final String KID_NOT_A_KEY = "is not a Multikey-encoded Ed25519 public key";
		public static final String WRONG_KEY_FOR_SUBJECT = "but the subject is ";
		public static final String WRONG_KEY_HINT = ": a did:key subject signs with its own key";
		public static final String SIGNATURE_FAILS = "signature does not verify for ";
		public static final String SIGNATURE_FAILS_PER_DID_DOCUMENT = " (per its DID document)";
		public static final String DID_METHOD_FAILURE = "could not verify, through its DID method, the subject ";
		public static final String KEY_NOT_ACTIVE = "is not an active authentication key of ";
		public static final String KEY_REVOKED = "has been revoked for ";
		public static final String ISSUER_MISMATCH = "does not match sub ";
		public static final String ISSUER_MISSING = "missing iss: ";
		public static final String ISSUED_BY_SUBJECT = "token is issued by its subject (iss = sub)";
		public static final String MALFORMED_SUBJECT = "malformed subject DID: ";
		public static final String MISSING_SUBJECT = "missing sub claim";
		public static final String NO_VENUE_KEY = "this venue has no signing key, so it cannot have issued this token";
		public static final String VENUE_SIGNATURE_FAILS = "signature does not verify for this venue's key";
		public static final String NO_PROVIDERS = "no external identity provider is configured on this venue";
		public static final String MISSING_PROVIDER_KID = "missing kid header: a provider token names its signing key in kid";
		public static final String NO_PROVIDER_FOR_KEY = "no configured identity provider publishes signing key ";
		public static final String PROVIDER_SIGNATURE_FAILS = "signature does not verify against the key published by ";
		public static final String PROVIDER_CLAIMS_MISMATCH = "token issuer or audience does not match provider ";
		public static final String PROVIDER_KEYS_UNAVAILABLE = "could not fetch the signing keys of ";
		public static final String NO_EMAIL_CLAIM = "provider token carries no email claim";
		public static final String NO_USER_FOR_EMAIL = "no venue user is linked to ";

		private Reason() {}
	}

	/**
	 * Claim first, then judge. A credential's shape says which verifier it is
	 * for — a UCAN bearer carries {@code att}, a provider token is RS256, a
	 * venue-issued token names this venue as {@code iss}, a self-issued token
	 * names its subject — so exactly one verifier reads it, and every failure
	 * after that is a reason rather than a fall-through to the next verifier.
	 * The reason bubbles unchanged into the 401 (covia#548).
	 *
	 * <p>Reasons describe the presented token only: its shape, its own claims,
	 * whether its signature verifies for the key it asserts. Checks that depend
	 * on venue state — key status, audience, user records — run after the
	 * signature, and their wording never says whether a named account exists
	 * (AUTH.md §11): on the named-user path the signature proves possession of
	 * the key {@code kid} names, which anyone can mint, so an unknown user and
	 * an unregistered key read the same.</p>
	 */
	private VerifiedPrincipal verify(AString token) {
		if (token == null || token.toString().isBlank()) {
			throw new AuthException("Authentication required");
		}
		JWT parsed = JWT.parse(token);
		if (parsed == null) throw new AuthException(TOKEN_REJECTED_PREFIX + Reason.UNPARSEABLE);
		AMap<AString, ACell> claims = parsed.getClaims();
		if (claims == null) throw new AuthException(TOKEN_REJECTED_PREFIX + Reason.MISSING_CLAIMS);

		String prefix;
		Verdict verdict;
		try {
			AString iss = RT.ensureString(claims.get(ISS));
			if (claims.containsKey(UCAN.ATT)) {
				prefix = UCAN_REJECTED_PREFIX;
				verdict = verifyUCAN(token);
			} else if ("RS256".equals(parsed.getAlgorithm())) {
				prefix = PROVIDER_TOKEN_REJECTED_PREFIX;
				verdict = verifyExternalProvider(parsed, claims);
			} else if (venueDID.equals(iss)) {
				prefix = VENUE_TOKEN_REJECTED_PREFIX;
				verdict = verifyVenueSigned(token);
			} else if (RT.ensureString(claims.get(SUB)) != null) {
				prefix = SELF_ISSUED_REJECTED_PREFIX;
				verdict = verifySelfIssued(token, parsed, claims);
			} else {
				throw new AuthException(TOKEN_REJECTED_PREFIX + Reason.UNRECOGNISED
					+ printable(parsed.getAlgorithm()) + Reason.UNRECOGNISED_HINT);
			}
		} catch (AuthException e) {
			throw e;
		} catch (Exception e) {
			log.warn("Error processing authentication token", e);
			throw new AuthException("Authentication failed", e);
		}
		if (verdict.reason() != null) throw new AuthException(prefix + verdict.reason());
		return verdict.principal();
	}

	/** A UCAN-shaped bearer: valid, audience-bound, and with empty {@code att}. */
	private Verdict verifyUCAN(AString jwt) {
		long now = System.currentTimeMillis() / 1000;
		// Signature + temporal bounds under the venue's DID verifier, so a
		// did:web-identified issuer (covia#343) verifies exactly like did:key.
		// The validator's reason describes only the token's own bytes and
		// claims (covia#503); audience and att policy run after the signature.
		UcanJwtValidator.Validation validation =
			UcanJwtValidator.validate(jwt, now, engine.didVerifier());
		if (!validation.valid()) return Verdict.fail(validation.reason());
		UCAN token = validation.token();
		AString issuer = token.getIssuer();
		if (issuer == null) return Verdict.fail(Reason.MISSING_ISSUER);
		// The validator rejected an expired token; the venue's own rules on a
		// bearer's lifetime apply here, to the bearer only — a transport grant
		// with exp: null is a delegation, not a credential.
		Long exp = token.getExpiry();
		if (exp == null) {
			if (requireExp) return Verdict.fail(Reason.NON_EXPIRING);
		} else {
			String bound = expiryProblem(exp, now);
			if (bound != null) return Verdict.fail(bound);
		}
		AVector<ACell> capabilities = token.getCapabilities();
		if (capabilities == null || !capabilities.isEmpty()) return Verdict.fail(Reason.NON_EMPTY_ATT);
		String audience = audienceProblem(token.getAudience());
		if (audience != null) return Verdict.fail(audience);
		return Verdict.ok(issuer, issuer);
	}

	/**
	 * An EdDSA token whose subject issued it: a self-certifying {@code did:key},
	 * a venue-managed named user signing with a registered key, or any other
	 * DID verified through its method resolver.
	 */
	private Verdict verifySelfIssued(AString jwt, JWT parsed, AMap<AString, ACell> claims) {
		if (!"EdDSA".equals(parsed.getAlgorithm())) {
			return Verdict.fail(Reason.UNSUPPORTED_ALGORITHM + printable(parsed.getAlgorithm()));
		}
		AString sub = RT.ensureString(claims.get(SUB));
		AString iss = RT.ensureString(claims.get(ISS));
		String subject = sub.toString();
		long now = System.currentTimeMillis() / 1000;

		if (subject.startsWith("did:key:")) {
			// Self-certifying: the subject is its own key, so kid must name it.
			KeyRef key = signingKeyOf(parsed, sub);
			if (key.reason() != null) return Verdict.fail(key.reason());
			if (!sub.equals(key.did())) {
				return Verdict.fail("kid names key " + key.did() + " " + Reason.WRONG_KEY_FOR_SUBJECT + sub
					+ Reason.WRONG_KEY_HINT);
			}
			AccountKey signingKey = Multikey.decodePublicKey(
				key.did().toString().substring("did:key:".length()));
			if (signingKey == null || JWT.verifyPublic(jwt, signingKey) == null) {
				return Verdict.fail(Reason.SIGNATURE_FAILS + sub);
			}
			String when = temporalProblem(claims, now);
			if (when != null) return Verdict.fail(when);
			String audience = audienceProblem(claims.get(AUD));
			if (audience != null) return Verdict.fail(audience);
			return Verdict.ok(key.did(), sub);
		}

		AString userId = engine.managedUserName(sub);
		if (userId != null) {
			if (!sub.equals(iss)) return Verdict.fail(issuerMismatch("named-user", sub, iss));
			KeyRef key = signingKeyOf(parsed, sub);
			if (key.reason() != null) return Verdict.fail(key.reason());
			AccountKey signingKey = Multikey.decodePublicKey(
				key.did().toString().substring("did:key:".length()));
			if (signingKey == null || JWT.verifyPublic(jwt, signingKey) == null) {
				return Verdict.fail(Reason.SIGNATURE_FAILS + "key " + key.did());
			}
			// Venue state, only now. One wording whether the user is unknown or
			// the key is: the signature proved possession of the kid key, which
			// anyone can mint, so this must not become a username oracle.
			AMap<AString, ACell> record = venueAuth.getUser(userId);
			boolean registered = record != null && sub.equals(record.get(Fields.DID));
			AMap<AString, ACell> entry = registered
				? RT.ensureMap(venueAuth.getAuthenticationKeys(userId).get(key.did())) : null;
			if (entry == null) {
				return Verdict.fail("key " + key.did() + " " + Reason.KEY_NOT_ACTIVE + sub);
			}
			if (!Auth.ACTIVE.equals(entry.get(Fields.STATUS))) {
				return Verdict.fail("key " + key.did() + " " + Reason.KEY_REVOKED + sub);
			}
			String when = temporalProblem(claims, now);
			if (when != null) return Verdict.fail(when);
			String audience = audienceProblem(claims.get(AUD));
			if (audience != null) return Verdict.fail(audience);
			return Verdict.ok(key.did(), sub);
		}

		// A non-local DID is authenticated by its method resolver. No caller in
		// this layer assumes did:web, did:key, or any future DID method. Unlike a
		// self-certifying did:key subject or a locally registered key, this path
		// also requires the issuer claim to name the resolved identity explicitly.
		if (!sub.equals(iss)) return Verdict.fail(issuerMismatch("resolvable-DID", sub, iss));
		try {
			if (DID.fromString(subject) == null) return Verdict.fail(Reason.MALFORMED_SUBJECT + sub);
		} catch (RuntimeException e) {
			return Verdict.fail(Reason.MALFORMED_SUBJECT + sub);
		}
		boolean verifies;
		try {
			Blob message = Blob.wrap(parsed.getSigningInput().getBytes(StandardCharsets.UTF_8));
			Blob signature = Blob.wrap(parsed.getSignatureBytes());
			verifies = engine.didVerifier().verifies(sub, message, signature);
		} catch (RuntimeException e) {
			// The detail (a fetch error, a proxy, a timeout) is the venue's to
			// know, not the caller's: the reason names the step, the log the cause.
			log.debug("DID method verification failed for {}", sub, e);
			return Verdict.fail(Reason.DID_METHOD_FAILURE + sub);
		}
		if (!verifies) return Verdict.fail(Reason.SIGNATURE_FAILS + sub + Reason.SIGNATURE_FAILS_PER_DID_DOCUMENT);
		String when = temporalProblem(claims, now);
		if (when != null) return Verdict.fail(when);
		String audience = audienceProblem(claims.get(AUD));
		if (audience != null) return Verdict.fail(audience);
		return Verdict.ok(sub, sub);
	}

	/** A token this venue issued (a login session), signed with its own key. */
	private Verdict verifyVenueSigned(AString jwt) {
		if (venueKey == null) return Verdict.fail(Reason.NO_VENUE_KEY);
		AMap<AString, ACell> claims = JWT.verifyPublic(jwt, venueKey);
		if (claims == null) return Verdict.fail(Reason.VENUE_SIGNATURE_FAILS);
		String when = temporalProblem(claims, System.currentTimeMillis() / 1000);
		if (when != null) return Verdict.fail(when);
		String audience = audienceProblem(claims.get(AUD));
		if (audience != null) return Verdict.fail(audience);
		AString sub = RT.ensureString(claims.get(SUB));
		if (sub == null) return Verdict.fail(Reason.MISSING_SUBJECT);
		try {
			if (DID.fromString(sub.toString()) == null) return Verdict.fail(Reason.MALFORMED_SUBJECT + sub);
		} catch (RuntimeException e) {
			return Verdict.fail(Reason.MALFORMED_SUBJECT + sub);
		}
		return Verdict.ok(sub, sub);
	}

	/**
	 * An RS256 ID token from a configured external provider, mapped to the
	 * venue user linked to its email. The email is the caller's own (the
	 * provider vouched for it), so "no venue user is linked" is theirs to hear.
	 */
	private Verdict verifyExternalProvider(JWT parsed, AMap<AString, ACell> claims) {
		if (externalProviders == null) return Verdict.fail(Reason.NO_PROVIDERS);
		String kid = parsed.getKeyID();
		if (kid == null) return Verdict.fail(Reason.MISSING_PROVIDER_KID);
		String reason = Reason.NO_PROVIDER_FOR_KEY + kid;
		for (Map.Entry<String, OAuthConfig> e : externalProviders.entrySet()) {
			String name = e.getKey();
			OAuthConfig provider = e.getValue();
			if (provider.jwksUri == null) continue;
			try {
				RSAPublicKey key = JWKSClient.getKey(provider.jwksUri, kid);
				if (key == null) continue;
				if (!parsed.verifyRS256(key)) {
					reason = Reason.PROVIDER_SIGNATURE_FAILS + name;
					continue;
				}
				if (!parsed.validateClaims(provider.issuer, provider.clientId)) {
					reason = Reason.PROVIDER_CLAIMS_MISMATCH + name;
					continue;
				}
			} catch (Exception ex) {
				// Same rule: the caller learns which step failed, the log why.
				log.debug("Provider {} key lookup failed", name, ex);
				reason = Reason.PROVIDER_KEYS_UNAVAILABLE + name;
				continue;
			}
			AString email = RT.ensureString(claims.get(EMAIL));
			if (email == null) return Verdict.fail(Reason.NO_EMAIL_CLAIM);
			AString venueUserDID = findUserDIDByEmail(email);
			if (venueUserDID == null) return Verdict.fail(Reason.NO_USER_FOR_EMAIL + email);
			AString subject = RT.ensureString(claims.get(SUB));
			return Verdict.ok(subject != null ? subject : email, venueUserDID);
		}
		return Verdict.fail(reason);
	}

	// ------------------------------------------------------------- the checks
	// Each returns null when it passes and the reason when it does not, the
	// convention CapabilityChecker.allows and UcanJwtValidator.Validation use.

	/**
	 * Null when the token is within its exp / nbf bounds (with clock-skew
	 * leeway), carries an expiry if the venue requires one, and does not expire
	 * further ahead than the venue allows; otherwise why not.
	 */
	String temporalProblem(AMap<AString, ACell> claims, long now) {
		CVMLong exp = RT.ensureLong(claims.get(EXP));
		if (exp == null) {
			if (requireExp) return Reason.MISSING_EXP;
		} else {
			String bound = expiryProblem(exp.longValue(), now);
			if (bound != null) return bound;
		}
		CVMLong nbf = RT.ensureLong(claims.get(NBF));
		if (nbf != null && now < nbf.longValue() - CLOCK_SKEW_SECONDS) {
			return Reason.NOT_YET_VALID + instant(nbf.longValue()) + " (now " + instant(now) + ")";
		}
		return null;
	}

	/** Null when an expiry is neither past nor further ahead than the venue's cap; otherwise why not. */
	private String expiryProblem(long exp, long now) {
		if (now > exp + CLOCK_SKEW_SECONDS) {
			return Reason.EXPIRED + instant(exp) + " (now " + instant(now) + ")";
		}
		if (maxLifetimeSeconds > 0 && exp - now > maxLifetimeSeconds) {
			return Reason.LIFETIME_EXCEEDS + "exp " + instant(exp) + " is more than "
				+ maxLifetimeSeconds + "s ahead";
		}
		return null;
	}

	/** Null when the audience names this venue (or is absent and not required); otherwise why not. */
	private String audienceProblem(ACell aud) {
		if (aud == null) {
			return "require".equals(audiencePolicy) ? Reason.AUDIENCE_REQUIRED : null;
		}
		if (aud instanceof AString s) {
			return acceptedAudienceStrings.contains(s.toString()) ? null : Reason.AUDIENCE_NOT_THIS_VENUE + s;
		}
		if (aud instanceof AVector<?> arr) {
			for (long i = 0; i < arr.count(); i++) {
				AString member = RT.ensureString(arr.get(i));
				if (member != null && acceptedAudienceStrings.contains(member.toString())) return null;
			}
			return Reason.AUDIENCE_LIST_EXCLUDES_VENUE;
		}
		return Reason.MALFORMED_AUDIENCE;
	}

	/** The signing key a self-issued token names in {@code kid}, as a {@code did:key}; or why it names none usable. */
	private record KeyRef(AString did, String reason) {}

	private static KeyRef signingKeyOf(JWT parsed, AString subject) {
		AString kid = RT.ensureString(parsed.getHeader().get(KID));
		if (kid == null) return new KeyRef(null, Reason.MISSING_KID);
		String value = kid.toString();
		String multikey;
		// A DID-URL kid is meaningful only in the asserted subject's DID
		// document. Its fragment is the Multikey published by UserAPI.
		int fragment = value.lastIndexOf('#');
		if (fragment >= 0) {
			if (!value.substring(0, fragment).equals(subject.toString())) {
				return new KeyRef(null, "kid " + value + " " + Reason.KID_NOT_OF_SUBJECT
					+ value.substring(0, fragment) + ", not of the subject " + subject);
			}
			multikey = value.substring(fragment + 1);
		} else if (value.startsWith("did:key:")) {
			multikey = value.substring("did:key:".length());
		} else {
			multikey = value;
		}
		try {
			if (Multikey.decodePublicKey(multikey) == null) {
				return new KeyRef(null, "kid " + value + " " + Reason.KID_NOT_A_KEY);
			}
		} catch (RuntimeException e) {
			return new KeyRef(null, "kid " + value + " " + Reason.KID_NOT_A_KEY);
		}
		return new KeyRef(Strings.create("did:key:" + multikey), null);
	}

	private static String issuerMismatch(String kind, AString sub, AString iss) {
		return (iss == null)
			? Reason.ISSUER_MISSING + "a " + kind + " " + Reason.ISSUED_BY_SUBJECT
			: "iss " + iss + " " + Reason.ISSUER_MISMATCH + sub + ": a " + kind + " " + Reason.ISSUED_BY_SUBJECT;
	}

	private static String instant(long epochSeconds) {
		try {
			return Instant.ofEpochSecond(epochSeconds).toString();
		} catch (RuntimeException e) {
			return String.valueOf(epochSeconds);
		}
	}

	private static String printable(Object value) {
		return (value == null) ? "none" : "\"" + value + "\"";
	}

	private AString findUserDIDByEmail(AString email) {
		AMap<AString, AMap<AString, ACell>> users = venueAuth.getUsers();
		if (users == null) return null;
		for (var entry : users.entrySet()) {
			AMap<AString, ACell> record = entry.getValue();
			if (email.equals(record.get(EMAIL))) {
				AString did = RT.ensureString(record.get(Fields.DID));
				if (did != null) return did;
			}
		}
		return null;
	}
}
