package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.auth.ucan.Capability;
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
import covia.api.Fields;
import covia.exception.AuthException;
import covia.lattice.CapabilityChecker;
import covia.venue.RequestContext;
import covia.venue.UcanJwtValidator;

/**
 * Adapter for UCAN token operations.
 *
 * <p>The issuance operation creates venue-signed roots for the venue's own
 * resources and its managed custodial users. Self-sovereign users issue with
 * their own key using the client SDK; the venue must not impersonate them.</p>
 */
public class UCANAdapter extends AAdapter {

	private static final AString K_TOKEN = Strings.intern("token");
	private static final AString K_AUTHORISES = Strings.intern("authorises");
	private static final AString K_AUDIENCE = Strings.intern("audience");

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
			return CompletableFuture.failedFuture(new RuntimeException("Authentication required"));
		}
		try {
			return switch (getSubOperation(meta)) {
				case "issue"  -> CompletableFuture.completedFuture(handleIssue(ctx, input));
				case "verify" -> CompletableFuture.completedFuture(handleVerify(ctx, input));
				default -> CompletableFuture.failedFuture(
					new RuntimeException("Unknown ucan operation: " + getSubOperation(meta)));
			};
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/**
	 * Issues a venue-signed UCAN token for a venue-controlled resource.
	 *
	 * <p>The venue signs with its own key pair. The issuer DID is the venue's DID.
	 * The caller must be authenticated. The resource must be owned by the venue
	 * or one of its managed custodial users; self-sovereign owners sign with
	 * their own key client-side.</p>
	 *
	 * @return The complete signed UCAN token as a CVM map
	 */
	private ACell handleIssue(RequestContext ctx, ACell input) {
		// Issuance is a GRANTING SURFACE (COG-17), so it is gated on who is
		// asking, before anything is canonicalised. An agent sub-principal acts
		// *within* its owner's namespace but does not speak *for* it: were it to
		// reach here, its bare `with` paths would absolutise against the acting
		// DID and it could mint venue-signed roots over resources it merely has
		// use of — granting itself, or a third party, authority its owner never
		// delegated. An agent that must confer authority asks its human for it
		// (HITL echo-consent), which issues under the responder's own identity.
		if (ctx.isSubPrincipal()) {
			throw new AuthException("Agents cannot issue capability grants: "
				+ ctx.getCallerDID() + " acts within the namespace of "
				+ ctx.getUserDID() + " but holds no granting authority over it. "
				+ "Request the grant from the namespace owner (v/ops/hitl/request) "
				+ "instead of minting one");
		}

		AString audDID = RT.ensureString(RT.getIn(input, UCAN.AUD));
		if (audDID == null) {
			throw new RuntimeException("aud (audience DID) is required");
		}

		@SuppressWarnings("unchecked")
		AVector<ACell> att = RT.getIn(input, UCAN.ATT);
		if (att == null || att.count() == 0) {
			throw new RuntimeException("att (attenuations) is required and must not be empty");
		}

		AMap<AString, ACell> inputMap = RT.castMap(input);
		ACell expValue = inputMap.get(UCAN.EXP);
		CVMLong expCell = RT.ensureLong(expValue);
		long now = System.currentTimeMillis() / 1000;
		// UCAN v0.10.0 (Convex #678, covia#322): a non-expiring token carries an
		// explicit exp: null. Tolerant API input treats an omitted exp as null;
		// the minted claim is always explicit.
		Long exp;
		if (expValue == null) {
			exp = null;
		} else if (expCell != null) {
			exp = expCell.longValue();
			if (exp <= now) {
				throw new RuntimeException("exp must be in the future, or null for no expiry");
			}
		} else {
			throw new RuntimeException("exp must be unix seconds or null for no expiry");
		}

		// Canonicalise + validate 'with' fields. A bare path names the ISSUER's
		// own resource and is qualified with the caller's DID before signing, so
		// the token in the wild always carries the absolute form (verification
		// never canonicalises — a bare `with` in a presented token is inert).
		// Scheme forms (file://, dlfs://) are not issuable — use the DID-scoped
		// path form. A DID URL outside the caller's namespace is issuable ONLY
		// under a held GRANTING RIGHT (COG-17): the caller's presented proofs
		// must cover grant/<can> on the resource. This is issuance as a granting
		// surface — the grant/ rule binds token production, never resolution.
		// Proofs travel in the transport proof channel (COG-15), never in
		// operation input (input is persisted in job records).
		AString callerDID = ctx.getCallerDID();
		String callerPrefix = callerDID.toString() + "/";
		AVector<ACell> canonAtt = Vectors.empty();
		for (long i = 0; i < att.count(); i++) {
			AMap<AString, ACell> cap = RT.castMap(att.get(i));
			AString with = (cap != null) ? RT.ensureString(cap.get(Capability.WITH)) : null;
			String w = (with != null) ? with.toString() : null;
			if (cap == null || w == null || w.contains("://")) {
				throw new RuntimeException("att[" + i + "].with must be a resource path — "
					+ "bare (scoped to your own namespace, e.g. w/) or a DID URL "
					+ "(e.g. " + callerPrefix + "w/)");
			}
			AString can = RT.ensureString(cap.get(Capability.CAN));
			if (can == null) {
				throw new RuntimeException("att[" + i + "].can (ability) is required");
			}
			if (w.startsWith("did:")) {
				if (!w.startsWith(callerPrefix)) {
					// Held granting right: minting over another principal's resource
					// requires proofs covering grant/<can> on it — and the minted
					// authority must not outlive the granting right enabling it
					// (checked by evaluating the same coverage at the minted expiry).
					AString grantAbility = Strings.create("grant/" + can);
					AString resource = Strings.create(w);
					if (!engine.proofsCover(ctx, resource, grantAbility, now)) {
						throw new RuntimeException("att[" + i + "].with is outside your namespace "
							+ "and your presented proofs do not establish the granting right "
							+ grantAbility + " over " + w + " — present a delegation from the "
							+ "resource owner (transport ucans / bearer), or use your own namespace");
					}
					// This second check asks only whether the granting chain still
					// exists at the minted token's expiry horizon — unbounded for a
					// non-expiring mint, so exp: null requires the enabling right to
					// be non-expiring too. Dynamic gates rule on the issuance
					// happening now (the check above); they must not execute a
					// second time against an imaginary future invocation.
					long horizon = (exp == null) ? Long.MAX_VALUE : exp - 1;
					if (!engine.proofsStructurallyCover(ctx, resource, grantAbility, horizon)) {
						throw new RuntimeException("att[" + i + "]: exp exceeds the validity of the "
							+ "granting right enabling this issuance — minted authority must not "
							+ "outlive the right it was minted under; request a shorter exp");
					}
				}
			} else {
				String path = w.startsWith("/") ? w.substring(1) : w;
				if (path.isEmpty()) {
					throw new RuntimeException("att[" + i + "].with must name a resource — "
						+ "an empty path would grant your entire namespace; say so explicitly ("
						+ callerPrefix + ") if that is intended");
				}
				cap = cap.assoc(Capability.WITH, Strings.create(callerPrefix + path));
			}
			canonAtt = canonAtt.conj(cap);
		}
		att = canonAtt;

		// This operation signs a new ROOT with the venue key. That is valid only
		// for resources the venue actually controls: its own DID or one of its
		// managed custodial users. A self-sovereign caller must sign with their
		// own key client-side; a held use/grant proof cannot turn the venue into
		// the root authority for an unrelated DID.
		AString venueDID = engine.getDIDString();
		for (long i = 0; i < att.count(); i++) {
			AMap<AString, ACell> cap = RT.castMap(att.get(i));
			AString with = (cap != null) ? RT.ensureString(cap.get(Capability.WITH)) : null;
			if (!engine.rootAuthorityPolicy().acceptsRoot(venueDID, with)) {
				throw new AuthException("Cannot issue a venue-signed root grant for " + with
					+ ": the resource is not controlled by this venue. Self-sovereign DID "
					+ "owners must sign the UCAN with their own key; use user:create with a "
					+ "username only for a venue-managed custodial identity");
			}
		}

		// Audience is an identity, not necessarily a key. Custodial did:web users
		// intentionally have no independent key, so requiring did:key here would
		// make it impossible to delegate to them. Validate DID syntax, then sign
		// the standard UCAN claims directly with the venue issuer key.
		try {
			if (DID.fromString(audDID.toString()) == null) throw new IllegalArgumentException();
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("aud must be a valid audience DID: " + audDID);
		}
		AKeyPair venueKP = engine.getKeyPair();
		// Mint the Convex UCAN JWT profile (0.8.11): the ucv claim and an
		// explicit exp key (null = non-expiring) are required for the token to
		// parse; JWT.signPublic supplies the typ header and kid. Claims are
		// built directly (not via UCAN.signJWT) because the venue's iss is its
		// declared identity, which may be did:web (covia#343) — the profile
		// accepts any DID-shaped issuer.
		AMap<AString, ACell> claims = Maps.of(
			UCAN.ISS, venueDID,
			UCAN.AUD, audDID,
			UCAN.UCV, UCAN.VERSION,
			UCAN.ATT, att,
			UCAN.PRF, Vectors.empty());
		claims = claims.assoc(UCAN.EXP, (exp == null) ? null : CVMLong.create(exp));
		AString token = JWT.signPublic(claims, venueKP);

		return Maps.of("token", token);
	}

	/**
	 * Verifies a UCAN token against this venue's trust policy and <b>explains</b>
	 * the verdict — the diagnostic counterpart to enforcement, which only says
	 * "Access denied". No signing, no side effects.
	 *
	 * <p>Reports signature/temporal/chain validity, the parsed claims, the
	 * delegation chain depth and root issuer, a per-capability root-authority
	 * verdict ({@code owner} = self-sovereign, {@code venue} = this venue's
	 * grant, {@code refused}), and — when {@code with}/{@code can} are supplied —
	 * whether the token would authorise that request here for the given audience
	 * (default: the caller).</p>
	 */
	private ACell handleVerify(RequestContext ctx, ACell input) {
		AString tokenStr = RT.ensureString(RT.getIn(input, K_TOKEN));
		AMap<AString, ACell> tokenMap = RT.castMap(RT.getIn(input, K_TOKEN));
		if (tokenStr == null && tokenMap == null) {
			throw new RuntimeException("token is required: a UCAN JWT string or token map");
		}

		long now = System.currentTimeMillis() / 1000;
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
				String verdict;
				if (self.acceptsRoot(rootIssuer, with)) verdict = "owner";
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
