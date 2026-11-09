package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.auth.ucan.Capability;
import convex.auth.ucan.RootAuthorityPolicy;
import convex.auth.ucan.UCAN;
import convex.auth.did.DID;
import convex.auth.jwt.JWT;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.exception.NotSupportedException;
import covia.lattice.CapabilityChecker;
import covia.venue.Audit;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.UcanJwtValidator;
import covia.venue.auth.VenueAuthenticator;

/**
 * Adapter for UCAN token operations.
 *
 * <p>The issuance operation creates venue-signed roots for the venue's own
 * resources and its managed custodial users. Self-sovereign users issue with
 * their own key using the client SDK; the venue must not impersonate them.
 * Given {@code prf}, it instead signs the next link of a chain, passing on
 * part of a grant made to the venue; only the venue itself may ask.</p>
 */
public class UCANAdapter extends AAdapter {

	private static final AString K_TOKEN = Strings.intern("token");
	private static final AString K_AUTHORISES = Strings.intern("authorises");
	private static final AString K_AUDIENCE = Strings.intern("audience");

	/** Refusal of {@code prf} from any caller but the venue itself. */
	public static final String PRF_VENUE_ONLY = "Only the venue itself may pass on a grant it holds (prf)";
	public static final String KEY_OR_PRF = "A grant signed with a held key is a root: give key or prf, not both";
	public static final String KEY_GRANTS_OWN_ONLY = "a grant signed with a held key covers only resources its did:key is root of: its own and those of identities nested below it";
	/** Why the venue will not sign a root: it does not host the resource's owner. */
	public static final String NOT_HOSTED = "The venue roots grants only over identities it hosts; "
		+ "an identity it does not host signs its own grants with its own key";
	/** Refusal of a {@code prf} entry that does not verify. */
	public static final String PRF_INVALID = "A prf entry is not a valid UCAN";
	/** Refusal of a {@code prf} entry addressed to someone other than this venue. */
	public static final String PRF_NOT_TO_VENUE = "A prf entry is not a grant to this venue";
	/** Refusal of an {@code att} entry that no {@code prf} entry covers. */
	public static final String PRF_NOT_COVERED = "No grant in prf covers";
	/** Refusal of an {@code exp} later than the covering {@code prf} entry allows. */
	public static final String PRF_OUTLIVED = "exp outlives the grant in prf that covers";

	@Override
	public String getName() {
		return "ucan";
	}

	@Override
	public String getDescription() {
		return "UCAN token operations for capability-based authorisation.";
	}

	@Override
	protected void installAssets() {
		installAsset("ucan/issue", "/adapters/ucan/issue.json");
		installAsset("ucan/verify", "/adapters/ucan/verify.json");
		installSkill("caps-permissions/capabilities", "/skills/capabilities.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		if (ctx.getCallerDID() == null) {
			return CompletableFuture.failedFuture(new AuthException("Authentication required"));
		}
		try {
			return switch (getSubOperation(meta)) {
				case "issue"  -> CompletableFuture.completedFuture(handleIssue(ctx, input));
				case "verify" -> CompletableFuture.completedFuture(handleVerify(ctx, input));
				default -> CompletableFuture.failedFuture(
					new NotSupportedException("Unknown ucan operation: " + getSubOperation(meta)));
			};
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/**
	 * A grant {@link #mint} signed and has not yet recorded.
	 *
	 * @param token The signed UCAN JWT
	 * @param issuer Its {@code iss}
	 * @param caller Who had it minted
	 * @param audience Its {@code aud}
	 * @param exp Its expiry in unix seconds, or null for none
	 */
	public record Grant(AString token, AString issuer, AString caller, AString audience, Long exp) {
		/**
		 * Records that this grant was issued. {@code ucan:issue} records each
		 * grant as it returns it; work that mints a grant to hand over later
		 * records it once it has.
		 */
		public void audit(Audit audit) {
			audit.event(Audit.TOKEN_ISSUED, Audit.K_TYPE, "ucan", Audit.K_DID, caller,
				Audit.K_SUBJECT, audience, Audit.K_EXP, (exp == null) ? "never" : exp);
		}
	}

	/**
	 * Issues a UCAN token (UCAN.md, "Issuing grants"): {@link #mint}, then
	 * {@link Grant#audit}.
	 *
	 * @return The complete signed UCAN token as a CVM map
	 */
	private ACell handleIssue(RequestContext ctx, ACell input) {
		Grant grant = mint(ctx, input);
		grant.audit(engine.audit());
		return Maps.of("token", grant.token(), UCAN.ISS, grant.issuer());
	}

	/**
	 * Signs the grant {@code ucan:issue} would, with every check it makes, and
	 * records nothing: whoever hands the token over records it then
	 * ({@link Grant#audit}). Signing changes no state, so a grant that is never
	 * handed over leaves no trace.
	 *
	 * <p>Without {@code key} or {@code prf} the venue signs a root, valid only
	 * here, over resources it hosts, for a caller who may grant them
	 * ({@link #requireGrantable}). With {@code key} a held key signs the root as
	 * itself, over resources its did:key is root of. With {@code prf} the venue
	 * passes on grants made to it ({@link #requireHeld}).</p>
	 *
	 * @param ctx Whose authority the grant is made with
	 * @param input As {@code ucan:issue} takes it
	 */
	public Grant mint(RequestContext ctx, ACell input) {
		AString audDID = RT.ensureString(RT.getIn(input, UCAN.AUD));
		if (audDID == null) {
			throw new InvalidInputException("aud (audience DID) is required");
		}

		@SuppressWarnings("unchecked")
		AVector<ACell> att = RT.getIn(input, UCAN.ATT);
		if (att == null || att.count() == 0) {
			throw new InvalidInputException("att (attenuations) is required and must not be empty");
		}

		// prf passes on the venue's own authority, so no request but the
		// venue's own selects it; an empty prf is a root, as in the token.
		ACell prfCell = RT.getIn(input, UCAN.PRF);
		AVector<ACell> prf = RT.ensureVector(prfCell);
		if (prfCell != null && prf == null) {
			throw new InvalidInputException("prf must be an array of UCAN JWTs");
		}
		if (prf != null && prf.isEmpty()) prf = null;
		if (prf != null && !engine.isDirectVenue(ctx)) {
			throw new AuthException(PRF_VENUE_ONLY + ", not " + ctx.getCallerDID());
		}

		// A held key signs the grant as itself: its did:key is the issuer and the
		// root of everything the grant names (HELD_KEYS.md).
		ACell keyRef = RT.getIn(input, Fields.KEY);
		if (keyRef != null && prf != null) throw new InvalidInputException(KEY_OR_PRF);
		AString keyIssuer = (keyRef != null)
			? UCAN.toDIDKey(CryptoAdapter.of(engine).heldPublicKey(ctx, keyRef)) : null;

		AMap<AString, ACell> inputMap = RT.castMap(input);
		ACell expValue = inputMap.get(UCAN.EXP);
		CVMLong expCell = RT.ensureLong(expValue);
		long now = engine.currentTimestamp().longValue() / 1000;
		// UCAN v0.10.0 (Convex #678, covia#322): a non-expiring token carries an
		// explicit exp: null. Tolerant API input treats an omitted exp as null;
		// the minted claim is always explicit.
		Long exp;
		if (expValue == null) {
			exp = null;
		} else if (expCell != null) {
			exp = expCell.longValue();
			if (exp <= now) {
				throw new InvalidInputException("exp must be in the future, or null for no expiry");
			}
		} else {
			throw new InvalidInputException("exp must be unix seconds or null for no expiry");
		}

		// Canonicalise + validate 'with' fields. A bare path names a resource
		// where it does everywhere else, in the namespace the request acts in
		// (the key's own with key), and is qualified before signing, so the token
		// in the wild always carries the absolute form (verification never
		// canonicalises — a bare `with` in a presented token is inert). Scheme
		// forms (file://, dlfs://) are not issuable — use the DID-scoped path form.
		AString base = (keyIssuer != null) ? keyIssuer : ctx.getUserDID();
		String basePrefix = base.toString() + "/";
		AVector<ACell> canonAtt = Vectors.empty();
		for (long i = 0; i < att.count(); i++) {
			AMap<AString, ACell> cap = RT.castMap(att.get(i));
			AString with = (cap != null) ? RT.ensureString(cap.get(Capability.WITH)) : null;
			String w = (with != null) ? with.toString() : null;
			if (cap == null || w == null || CapabilityChecker.isScheme(w)) {
				throw new InvalidInputException("att[" + i + "].with must be a resource path — "
					+ "bare (in the namespace you act in, e.g. w/) or a DID URL "
					+ "(e.g. " + basePrefix + "w/)");
			}
			AString can = RT.ensureString(cap.get(Capability.CAN));
			if (can == null) {
				throw new InvalidInputException("att[" + i + "].can (ability) is required");
			}
			String absolute = CapabilityChecker.canonicalResource(w, base);
			if (w.isEmpty() || absolute.equals(basePrefix)) {
				throw new InvalidInputException("att[" + i + "].with must name a resource — "
					+ "an empty path would grant your entire namespace; say so explicitly ("
					+ base + ") if that is intended");
			}
			cap = cap.assoc(Capability.WITH, Strings.create(absolute));
			AString resource = RT.ensureString(cap.get(Capability.WITH));
			if (keyIssuer != null) {
				if (!VenueAuthenticator.signsInAs(keyIssuer, Engine.ownerOf(resource))) {
					throw new InvalidInputException("att[" + i + "]: " + KEY_GRANTS_OWN_ONLY + " (" + keyIssuer + ")");
				}
			} else if (prf == null) {
				requireGrantable(ctx, i, resource, can, exp);
			}
			// With prf, held grants authorise the entry instead (requireHeld)
			canonAtt = canonAtt.conj(cap);
		}
		att = canonAtt;

		// Without key or prf the venue signs a new ROOT, which it may do only
		// over what it hosts (Engine.rootAuthorityPolicy): no right to grant
		// makes it the root of an identity it does not host, whose owner signs
		// with its own key instead.
		AString venueDID = engine.getDIDString();
		if (keyIssuer != null) {
			// Every entry is the key's own or nested below it, checked above.
		} else if (prf != null) {
			requireHeld(prf, att, now, exp);
		} else {
			for (long i = 0; i < att.count(); i++) {
				AMap<AString, ACell> cap = RT.castMap(att.get(i));
				AString with = (cap != null) ? RT.ensureString(cap.get(Capability.WITH)) : null;
				if (!engine.rootAuthorityPolicy().acceptsRoot(venueDID, with)) {
					throw new AuthException(NOT_HOSTED + " (att[" + i + "]: " + with + ")");
				}
			}
		}

		// Audience is an identity, not necessarily a key. Custodial did:web users
		// intentionally have no independent key, so requiring did:key here would
		// make it impossible to delegate to them. Validate DID syntax, then sign
		// the standard UCAN claims directly with the venue issuer key.
		try {
			if (DID.fromString(audDID.toString()) == null) throw new IllegalArgumentException();
		} catch (RuntimeException e) {
			throw new InvalidInputException("aud must be a valid audience DID: " + audDID);
		}
		AKeyPair signer = (keyIssuer != null)
			? CryptoAdapter.of(engine).heldKeyPair(ctx, keyRef, Abilities.KEY_SIGN_GRANT)
			: engine.getKeyPair();
		AString issuer = (keyIssuer != null) ? keyIssuer : venueDID;
		// Mint the Convex UCAN JWT profile (0.8.11): the ucv claim and an
		// explicit exp key (null = non-expiring) are required for the token to
		// parse; JWT.signPublic supplies the typ header and kid. Claims are
		// built directly (not via UCAN.signJWT) because the venue's iss is its
		// declared identity, which may be did:web (covia#343) — the profile
		// accepts any DID-shaped issuer.
		AMap<AString, ACell> claims = Maps.of(
			UCAN.ISS, issuer,
			UCAN.AUD, audDID,
			UCAN.UCV, UCAN.VERSION,
			UCAN.ATT, att,
			UCAN.PRF, (prf != null) ? prf : Vectors.empty());
		claims = claims.assoc(UCAN.EXP, (exp == null) ? null : CVMLong.create(exp));
		return new Grant(JWT.signPublic(claims, signer), issuer, ctx.getCallerDID(), audDID, exp);
	}

	/**
	 * Whether the caller may have the venue grant {@code can} on
	 * {@code resource} until {@code exp}: the check wherever authority is handed
	 * on ({@link Engine#requireGrantable}). Proofs travel in the transport proof
	 * channel (COG-15), never in operation input, which job records persist.
	 */
	private void requireGrantable(RequestContext ctx, long i, AString resource, AString can, Long exp) {
		try {
			engine.requireGrantable(ctx, ctx.getUserDID(), resource, can, exp, null);
		} catch (AuthException e) {
			throw new AuthException(e.getMessage() + " (att[" + i + "])");
		}
	}

	/**
	 * A token with proofs is the next link of their chains, never a root: each
	 * proof must be a valid grant made to this venue, and one of them must cover
	 * every entry of {@code att} now and through {@code exp}. These are the
	 * checks a verifier makes of the chain, made before the venue signs it.
	 */
	private void requireHeld(AVector<ACell> prf, AVector<ACell> att, long now, Long exp) {
		AString venueDID = engine.getDIDString();
		AVector<ACell> held = Vectors.empty();
		for (long i = 0; i < prf.count(); i++) {
			AString jwt = RT.ensureString(prf.get(i));
			UcanJwtValidator.Validation parent = (jwt != null)
				? UcanJwtValidator.validate(jwt, now, engine.didVerifier()) : null;
			if (parent == null || !parent.valid()) {
				throw new InvalidInputException(PRF_INVALID + " (prf[" + i + "]"
					+ ((parent != null) ? ": " + parent.reason() : ": expected a JWT string") + ")");
			}
			if (!venueDID.equals(parent.token().getAudience())) {
				throw new AuthException(PRF_NOT_TO_VENUE + " (prf[" + i + "] is addressed to "
					+ parent.token().getAudience() + ")");
			}
			held = held.conj(parent.token().toMap());
		}
		RootAuthorityPolicy roots = engine.rootAuthorityPolicy();
		long horizon = (exp == null) ? Long.MAX_VALUE : exp - 1;
		for (long i = 0; i < att.count(); i++) {
			AMap<AString, ACell> cap = RT.castMap(att.get(i));
			AString with = RT.ensureString(cap.get(Capability.WITH));
			AString can = RT.ensureString(cap.get(Capability.CAN));
			if (!CapabilityChecker.proofsStructurallyCover(held, venueDID, roots, with, can, now)) {
				throw new AuthException(PRF_NOT_COVERED + " " + can + " on " + with
					+ ": a token with prf only passes on grants the venue holds, so grants over "
					+ "the venue's own resources need a token without prf");
			}
			if (!CapabilityChecker.proofsStructurallyCover(held, venueDID, roots, with, can, horizon)) {
				throw new AuthException(PRF_OUTLIVED + " " + can + " on " + with
					+ "; request a shorter exp");
			}
		}
	}

	/**
	 * Verifies a UCAN token against this venue's trust policy and <b>explains</b>
	 * the verdict — the diagnostic counterpart to enforcement, which only says
	 * "Access denied". No signing, no side effects.
	 *
	 * <p>Reports signature/temporal/chain validity, the parsed claims, the
	 * delegation chain depth and root issuer, a per-capability root-authority
	 * verdict ({@code owner} = an identity root, {@code venue} = this venue's
	 * grant, {@code refused}), and — when {@code with}/{@code can} are supplied —
	 * whether the token would authorise that request here for the given audience
	 * (default: the caller).</p>
	 */
	private ACell handleVerify(RequestContext ctx, ACell input) {
		AString tokenStr = RT.ensureString(RT.getIn(input, K_TOKEN));
		AMap<AString, ACell> tokenMap = RT.castMap(RT.getIn(input, K_TOKEN));
		if (tokenStr == null && tokenMap == null) {
			throw new InvalidInputException("token is required: a UCAN JWT string or token map");
		}

		long now = engine.currentTimestamp().longValue() / 1000;
		AString venueDID = engine.getDIDString();

		// Full verification (signature at every hop, temporal bounds, chain structure).
		// JWTs pass through the same narrow legacy-profile compatibility boundary
		// used by transport enforcement, so diagnostics and real calls agree.
		UCAN token = null;
		String failure = null;
		try {
			if (tokenStr != null) {
				UcanJwtValidator.Validation validation =
					UcanJwtValidator.validate(tokenStr, now, engine.didVerifier());
				token = validation.token();
				failure = validation.reason();
			} else {
				token = convex.auth.ucan.UCANValidator.validate(
					UCAN.parse(tokenMap), now, engine.didVerifier());
			}
		} catch (Exception e) {
			failure = e.getMessage();
		}

		if (token == null) {
			// Parse WITHOUT verification purely to sharpen the diagnostic.
			UCAN unverified = null;
			try {
				unverified = (tokenStr != null) ? UCAN.parseJWT(tokenStr) : UCAN.parse(tokenMap);
			} catch (Exception ignored) {}
			String reason;
			if (failure != null) {
				reason = failure;
			} else if (unverified == null) {
				reason = "unparseable: not a well-formed UCAN";
			} else if (!convex.auth.ucan.UCANValidator.checkTemporalBounds(unverified, now)) {
				reason = "expired or not yet valid (exp/nbf out of bounds)";
			} else {
				reason = "verification failed: bad signature or invalid delegation chain"
					+ (failure != null ? " (" + failure + ")" : "");
			}
			return Maps.of("valid", false, "reason", reason);
		}

		// Chain shape: depth and root issuer. A prf entry is a JWT string in
		// JWT transport form, or a token map in CVM/lattice form — handle both.
		UCAN root = token;
		long depth = 0;
		while (true) {
			AVector<ACell> prf = root.getProofs();
			if (prf == null || prf.isEmpty()) break;
			ACell entry = prf.get(0);
			UCAN parent = null;
			AString parentJwt = RT.ensureString(entry);
			if (parentJwt != null) {
				parent = UcanJwtValidator.validateJWT(parentJwt, now, engine.didVerifier());
			} else {
				AMap<AString, ACell> parentMap = RT.castMap(entry);
				if (parentMap != null) parent = UCAN.parse(parentMap);
			}
			if (parent == null) break;
			root = parent;
			depth++;
		}
		AString rootIssuer = root.getIssuer();

		// Per-capability root-authority verdict under this venue's policy.
		convex.auth.ucan.RootAuthorityPolicy self = convex.auth.ucan.RootAuthorityPolicy.SELF_SOVEREIGN;
		convex.auth.ucan.RootAuthorityPolicy venuePolicy = engine.rootAuthorityPolicy();
		AVector<ACell> att = token.getCapabilities();
		AVector<ACell> verdicts = Vectors.empty();
		if (att != null) {
			for (long i = 0; i < att.count(); i++) {
				AMap<AString, ACell> cap = RT.castMap(att.get(i));
				AString with = (cap != null) ? RT.ensureString(cap.get(Capability.WITH)) : null;
				// owner: an identity root, the owner or one that signs in as it;
				// venue: this venue's root over what it hosts (Engine.rootAuthorityPolicy).
				String verdict;
				if (self.acceptsRoot(rootIssuer, with)
					|| VenueAuthenticator.signsInAs(rootIssuer, Engine.ownerOf(with))) verdict = "owner";
				else if (venuePolicy.acceptsRoot(rootIssuer, with)) verdict = "venue";
				else verdict = "refused";
				verdicts = verdicts.conj(Maps.of(
					Capability.WITH, with,
					Capability.CAN, (cap != null) ? cap.get(Capability.CAN) : null,
					"rootAuthority", verdict));
			}
		}

		Long tokenExp = token.getExpiry();
		AMap<AString, ACell> result = Maps.of(
			"valid", true,
			"iss", token.getIssuer(),
			"aud", token.getAudience(),
			"exp", (tokenExp == null) ? null : CVMLong.create(tokenExp),
			"chainDepth", CVMLong.create(depth),
			"rootIssuer", rootIssuer,
			"att", verdicts);

		// Optional: would this token authorise (with, can) here, for the given
		// audience (default the caller)? Uses the SAME gate enforcement uses.
		AString reqWith = RT.ensureString(RT.getIn(input, Capability.WITH));
		AString reqCan = RT.ensureString(RT.getIn(input, Capability.CAN));
		if (reqWith != null && reqCan != null) {
			AString audience = RT.ensureString(RT.getIn(input, UCAN.AUD));
			if (audience == null) audience = ctx.getCallerDID();
			// Canonicalise a bare queried resource against the audience — the
			// same rule enforcement applies to a caller's bare paths. No concrete
			// invocation/gate evaluator exists in this diagnostic, so a caveated
			// path reports authorises:false rather than silently ignoring its
			// policy.
			String canonWith = CapabilityChecker.canonicalResource(reqWith.toString(), audience);
			boolean authorises = CapabilityChecker.proofsCover(
				Vectors.of(token.toMap()), audience, engine.rootAuthorityPolicy(),
				Strings.create(canonWith), reqCan, now);
			result = result.assoc(K_AUTHORISES, convex.core.data.prim.CVMBool.of(authorises));
			result = result.assoc(K_AUDIENCE, audience);
		}
		return result;
	}
}
