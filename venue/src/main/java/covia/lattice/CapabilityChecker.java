package covia.lattice;

import convex.auth.ucan.Capability;
import convex.auth.ucan.RootAuthorityPolicy;
import convex.auth.ucan.UCAN;
import convex.auth.ucan.UCANValidator;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;

/**
 * Checks agent tool calls against declared capability attenuations.
 *
 * <p>If an agent has a {@code caps} array in its config, every tool call
 * is checked before dispatch. The caps format matches UCAN attenuations:
 * {@code [{with: "w/decisions", can: "crud/write"}, ...]}</p>
 *
 * <p>No caps = full access (no restriction). This is the default.</p>
 *
 * <p>Matching uses {@link Capability#covers} from convex-core — the same
 * logic used for UCAN proof verification.</p>
 *
 * <p>See {@code venue/docs/UCAN.md §5.4} for the full design.</p>
 */
public class CapabilityChecker {


	/**
	 * Derive the self-attenuation ceiling from presented proof tokens: the union
	 * of capabilities the verified tokens grant to {@code caller} under the
	 * authority {@code issuer}, with temporal bounds re-checked. The result is the
	 * {@code caps} ceiling enforced by {@link #check}. The owner is the authority
	 * over its own namespace, so for a self-ceiling {@code issuer == caller}.
	 *
	 * <p>Assumes signatures and chains were verified at the transport boundary
	 * ({@code UCANValidator.parseTransportUCANs}); only temporal bounds are
	 * re-checked. Fail-closed: null {@code proofs}/{@code caller}/{@code issuer}
	 * → null (no ceiling), never a wildcard.</p>
	 *
	 * <p>A self-ceiling is genuinely <b>single-hop</b> and caller-rooted
	 * ({@code iss == aud == caller}) — no delegation chain, no root-authority
	 * policy — so it is derived by a local single-hop selection, kept isolated
	 * from the cross-user proof path ({@link #proofsCover}). On top of the
	 * selection this adds covia's self-attenuation guard: a self-ceiling may only
	 * <b>narrow</b>, so a capability with an empty/absent {@code with} — a "match
	 * any resource" wildcard that would broaden — is dropped from the ceiling.</p>
	 *
	 * <p><b>Scope of the wildcard-stripping (#211):</b> the guard above applies
	 * only to ceilings derived here, from UCAN <em>proof tokens</em>. An agent's
	 * {@code config.caps} array does not pass through this method — it reaches
	 * the checker unmodified, where an empty-{@code with} grant such as
	 * {@code {"with": "", "can": "invoke"}} acts as a match-any wildcard (see
	 * {@link #covered}). That is the supported way to give a restricted agent
	 * the {@code invoke} ability its tool calls need.</p>
	 */
	public static AVector<ACell> selfCapabilities(AVector<ACell> proofs,
			AString caller, AString issuer, long now) {
		if (proofs == null || caller == null || issuer == null) return null;
		// Single-hop selection: union the attenuations of tokens audienced to the
		// caller AND issued by `issuer` (== caller for a self-ceiling), still in-date.
		// Uses the typed UCAN accessors (canonical DID comparison), not raw fields.
		AVector<ACell> caps = Vectors.empty();
		for (long i = 0; i < proofs.count(); i++) {
			AMap<AString, ACell> tokenMap = RT.castMap(proofs.get(i));
			if (tokenMap == null) continue;
			UCAN token = UCAN.parse(tokenMap);
			if (token == null) continue;
			if (!UCANValidator.checkTemporalBounds(token, now)) continue;
			if (!caller.equals(token.getAudience())) continue;
			if (!issuer.equals(token.getIssuer())) continue;
			caps = caps.concat(token.getCapabilities());
		}
		if (caps.isEmpty()) return null;
		// Self-attenuation may only NARROW: drop empty/absent-`with` (match-any)
		// caps that would broaden the owner's own authority.
		AVector<ACell> narrowed = Vectors.empty();
		for (long i = 0; i < caps.count(); i++) {
			ACell c = caps.get(i);
			if (c instanceof AMap<?, ?> m) {
				@SuppressWarnings("unchecked")
				AString w = RT.ensureString(((AMap<AString, ACell>) m).get(Capability.WITH));
				if (w == null || w.count() == 0) continue;
			}
			narrowed = narrowed.conj(c);
		}
		return narrowed.isEmpty() ? null : narrowed;
	}

	/**
	 * Cross-user proof check: do the caller's presented {@code proofs} authorise
	 * {@code (resource, ability)}? Delegates to the convex-core authority layer
	 * ({@link UCANValidator#isAuthorised}), which checks capability coverage, then
	 * per-hop delegation attenuation to the chain root, then root authority.
	 *
	 * <p>Root authority is {@link RootAuthorityPolicy#SELF_SOVEREIGN} (the resource
	 * owner may root a grant over its own namespace) <b>or</b> the venue (for the
	 * grants it issues/attests — Phase C1 custodial). The venue arm preserves every
	 * currently venue-issued grant; the self-sovereign arm is the covia#100 enabler
	 * (cross-venue tokens rooted by the owner verify without naming the venue).
	 * covia#100 will later narrow the venue arm to the venue's own custodial users.</p>
	 *
	 * <p>This is the single cross-user grant check — {@code CoviaAdapter.verifyProofs}
	 * and job-read authorisation both call it, so the model can't drift between the
	 * lattice-read path and the job path (they are the same right — covia#102).
	 * Signatures/chain structure were verified at transport ingress
	 * ({@link UCANValidator#parseTransportUCANs}).</p>
	 *
	 * @param proofs   the caller's presented UCAN proofs (from the RequestContext)
	 * @param caller   the caller's DID (proof audience)
	 * @param venueDID the venue's DID — accepted as a root authority (Phase C1)
	 * @param resource the full resource being accessed (e.g. {@code "did:key:z…/j/<id>"})
	 * @param ability  the required ability (e.g. {@link Capability#CRUD_READ})
	 * @param now      current time, unix seconds
	 * @return true if the presented proofs authorise the request
	 */
	public static boolean proofsCover(AVector<ACell> proofs, AString caller, AString venueDID,
			AString resource, AString ability, long now) {
		if (caller == null || resource == null || ability == null) return false;
		RootAuthorityPolicy policy = RootAuthorityPolicy.SELF_SOVEREIGN.or(
			(root, with) -> venueDID != null && venueDID.equals(root));
		// Bare `with` paths mean the TOKEN ISSUER's own namespace — resolved here,
		// at evaluation, against the signed `iss` that travels with every token.
		// The `with` form is covia-specific; convex-core validates structure and
		// signatures and treats resources as opaque, so the relying party
		// normalises its view of the verified claims before the authority check.
		return UCANValidator.isAuthorised(normaliseProofs(proofs), caller, resource, ability, policy, now);
	}

	/**
	 * Normalises verified UCAN token maps so a bare capability resource means
	 * <b>the token issuer's own namespace</b>: each bare (non-DID, non-scheme),
	 * non-empty {@code att[i].with} is rewritten to {@code "<iss>/<path>"} (one
	 * leading slash stripped). Applied recursively through map-form {@code prf}
	 * chains, each hop against its own issuer — so a bare path in a
	 * re-delegation binds to the re-delegator, and passing another principal's
	 * authority onward still requires the absolute form.
	 *
	 * <p>Empty {@code with} stays empty (inert in the proof path — a
	 * whole-namespace grant must be written explicitly); scheme forms
	 * ({@code file://}, {@code dlfs://}) are untouched (not issuable, and
	 * fail-closed at the root policy). JWT-string {@code prf} entries cannot be
	 * rewritten and are left as-is — bare paths inside them stay inert, exactly
	 * the pre-normalisation behaviour.</p>
	 *
	 * <p>Safe with respect to integrity: signatures are verified over the wire
	 * form at transport ingress; the authority layer consumes verified claim
	 * maps and performs no re-verification, so normalising the relying party's
	 * in-memory view cannot invalidate a token.</p>
	 */
	public static AVector<ACell> normaliseProofs(AVector<ACell> proofs) {
		if (proofs == null) return null;
		AVector<ACell> out = Vectors.empty();
		for (long i = 0; i < proofs.count(); i++) {
			ACell entry = proofs.get(i);
			AMap<AString, ACell> m = RT.castMap(entry);
			out = out.conj((m != null) ? normaliseTokenMap(m) : entry);
		}
		return out;
	}

	/** Normalises one token map — full {@code {header, payload, sig}} form or a
	 *  bare payload map ({@code att} at top level); anything else unchanged. */
	private static ACell normaliseTokenMap(AMap<AString, ACell> token) {
		AMap<AString, ACell> payload = RT.castMap(token.get(UCAN.PAYLOAD));
		if (payload != null) {
			AMap<AString, ACell> normalised = normalisePayload(payload);
			return (normalised == payload) ? token : token.assoc(UCAN.PAYLOAD, normalised);
		}
		if (token.containsKey(UCAN.ATT)) return normalisePayload(token);
		return token;
	}

	private static AMap<AString, ACell> normalisePayload(AMap<AString, ACell> payload) {
		AString iss = RT.ensureString(payload.get(UCAN.ISS));
		if (iss == null) return payload;
		ACell attCell = payload.get(UCAN.ATT);
		if (attCell instanceof AVector<?> attVec) {
			AVector<ACell> newAtt = Vectors.empty();
			boolean changed = false;
			for (long i = 0; i < attVec.count(); i++) {
				ACell capCell = attVec.get(i);
				AMap<AString, ACell> cap = RT.castMap(capCell);
				AString with = (cap != null) ? RT.ensureString(cap.get(Capability.WITH)) : null;
				String w = (with != null) ? with.toString() : null;
				if (w != null && !w.isEmpty() && !w.startsWith("did:") && !w.contains("://")) {
					String path = w.startsWith("/") ? w.substring(1) : w;
					if (!path.isEmpty()) {
						capCell = cap.assoc(Capability.WITH, Strings.create(iss + "/" + path));
						changed = true;
					}
				}
				newAtt = newAtt.conj(capCell);
			}
			if (changed) payload = payload.assoc(UCAN.ATT, newAtt);
		}
		ACell prfCell = payload.get(UCAN.PRF);
		if (prfCell instanceof AVector<?> prfVec) {
			AVector<ACell> newPrf = Vectors.empty();
			boolean changed = false;
			for (long i = 0; i < prfVec.count(); i++) {
				ACell p = prfVec.get(i);
				AMap<AString, ACell> pm = RT.castMap(p);
				ACell np = (pm != null) ? normaliseTokenMap(pm) : p;
				if (np != p) changed = true;
				newPrf = newPrf.conj(np);
			}
			if (changed) payload = payload.assoc(UCAN.PRF, newPrf);
		}
		return payload;
	}

	/**
	 * Checks whether a capability ceiling allows a specific {@code (resource,
	 * ability)} pair supplied <em>directly</em> by the executing adapter — not
	 * derived from an operation name.
	 *
	 * <p>This is the enforcement primitive meant to be co-located with the code
	 * that performs the action: the implementation names the exact resource and
	 * ability it requires, so the enforced capability cannot drift from what the
	 * code actually does (unlike a name-keyed operation→ability mapping, which
	 * is a separate source of truth that can fall out of sync).</p>
	 *
	 * <p>Lattice resources and abilities are {@link AString}s, so this AString
	 * form is the primary entry point; a {@link String} overload is provided for
	 * literal arguments.</p>
	 *
	 * @param caps     the caller's granted capability ceiling; {@code null} = unrestricted
	 * @param resource the exact resource acted on — a bare lattice path
	 *                 ({@code "w/x"}, owner-scoped), a DID URL, or a scheme URI;
	 *                 {@code null}/empty means "no specific resource"
	 * @param ability  the exact ability required (e.g.
	 *                 {@link Capability#CRUD_WRITE}, {@code "secret/write"})
	 * @param ownerDID the caller's DID, used to canonicalise bare resources; may be null
	 * @return {@code null} if allowed, else an actionable denial message
	 */
	public static String allows(AVector<ACell> caps, AString resource, AString ability, AString ownerDID) {
		return allows(caps, resource, ability, ownerDID, null, null, null);
	}

	/**
	 * As {@link #allows(AVector, AString, AString, AString)}, with capability
	 * gate evaluation (#216). A grant may carry {@code nb: {gate: "v/ops/…"}} —
	 * the grant then authorises a request only when the gate operation
	 * succeeds for the invocation being checked. Ungated covering grants are
	 * checked first and short-circuit (no gate invocations on the common
	 * path); gates only run when a gated grant is the deciding authority.
	 * Fail-closed: with no {@code gate} evaluator in scope, gated grants
	 * cannot authorise anything.
	 *
	 * @param opRef the operation reference being invoked (for the gate's
	 *              {@code operation} field; may be null)
	 * @param invocationInput the invocation input the gate rules on
	 * @param gate  the gate evaluator, or null when gates cannot be evaluated
	 *              in this context
	 */
	public static String allows(AVector<ACell> caps, AString resource, AString ability, AString ownerDID,
			AString opRef, ACell invocationInput, CapabilityGate gate) {
		if (caps == null) return null;              // no ceiling = unrestricted
		String canonResource = canonicalResource(resource != null ? resource.toString() : null, ownerDID);
		if (canonResource == null) canonResource = "";
		String ab = (ability != null) ? ability.toString() : "";
		AString resourceStr = Strings.create(canonResource);
		AString abilityStr = Strings.create(ab);

		// Pass 1: any covering grant WITHOUT a gate authorises outright.
		if (covered(caps, resourceStr, abilityStr, ownerDID, false)) return null;

		// Pass 2: covering grants WITH a gate — evaluated in declaration order,
		// first passing gate wins. Denial details accumulate so a refused
		// caller sees why each applicable gate said no.
		StringBuilder gateDenials = null;
		for (long i = 0; i < caps.count(); i++) {
			if (!(caps.get(i) instanceof AMap<?, ?> capMap)) continue;
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> cap = (AMap<AString, ACell>) capMap;
			AString gateOp = gateOf(cap);
			if (gateOp == null) continue;                       // ungated — pass 1's job
			if (!grantCovers(cap, resourceStr, abilityStr, ownerDID)) continue;
			String detail;
			if (gate == null) {
				detail = "gate " + gateOp + " cannot be evaluated in this context";
			} else {
				detail = gate.evaluate(gateOp, opRef, invocationInput, ownerDID);
				if (detail == null) return null;                // gate passed — authorised
			}
			if (gateDenials == null) gateDenials = new StringBuilder();
			else gateDenials.append("; ");
			gateDenials.append(detail);
		}
		if (gateDenials != null) {
			return "Capability denied by gate: " + gateDenials
				+ ". The grant covering " + (ab.isEmpty() ? "(any ability)" : ab)
				+ " on " + (canonResource.isEmpty() ? "(any)" : canonResource)
				+ " is conditional on its gate operation succeeding.";
		}

		StringBuilder sb = new StringBuilder("Capability denied: requires ")
			.append(ab.isEmpty() ? "(any ability)" : ab)
			.append(" on ").append(canonResource.isEmpty() ? "(any)" : canonResource)
			.append(". Your capabilities are: ");
		appendCapsList(sb, caps);
		// For the venue's public/anonymous identity, the remedy is an auth
		// action — point to it (covia#206). Scoped to the public caller (DID
		// ends ":public") so a capped agent's own-ceiling denial, which it is
		// meant to just handle (#211), and cross-user denials stay clean.
		if (ownerDID != null && ownerDID.toString().endsWith(":public")) {
			sb.append(". Authenticate with a UCAN bearer token (Authorization: "
				+ "Bearer <jwt>, aud = venue DID) to act as your own identity, or "
				+ "ask the operator to widen auth.public.caps — see UCAN.md §4.7");
		}
		return sb.toString();
	}

	/**
	 * {@link String}-argument convenience overload of
	 * {@link #allows(AVector, AString, AString, AString)} — interns the literal
	 * arguments and delegates.
	 */
	public static String allows(AVector<ACell> caps, String resource, String ability, AString ownerDID) {
		return allows(caps,
			resource != null ? Strings.create(resource) : null,
			ability != null ? Strings.create(ability) : null,
			ownerDID);
	}

	/**
	 * The default read-only capability ceiling for an identity: read the
	 * identity's own (owner-scoped) lattice and venue paths, and read
	 * content-addressed assets. It grants <em>no</em> write, delete, secret,
	 * agent, asset-store, or invoke ability — so every mutating operation is
	 * denied. This is the secure-by-default profile for the public/anonymous
	 * identity; operators widen it explicitly for permissive venues.
	 *
	 * @param scopeDID the identity the read grant is scoped to — must be
	 *                 non-null (e.g. the venue public DID, {@code "<venueDID>:public"});
	 *                 a null scope would yield an unscoped, over-broad grant
	 */
	public static AVector<ACell> readOnlyCeiling(AString scopeDID) {
		return Vectors.of(
			Capability.create(scopeDID, Capability.CRUD_READ),
			Capability.create(Strings.create(""), Strings.create("asset/read")));
	}

	/**
	 * The capability match loop shared by {@link #check} and {@link #allows}:
	 * returns true iff some grant in {@code caps} covers the already-canonical
	 * {@code (resourceStr, abilityStr)} request. Non-map and malformed entries
	 * are skipped defensively (they grant nothing). An empty {@code caps}
	 * vector therefore grants nothing — only a {@code null} ceiling is "full
	 * access" (handled by the callers).
	 */
	private static boolean covered(AVector<ACell> caps, AString resourceStr, AString abilityStr,
			AString ownerDID, boolean includeGated) {
		for (long i = 0; i < caps.count(); i++) {
			if (!(caps.get(i) instanceof AMap<?,?> capMap)) continue;
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> cap = (AMap<AString, ACell>) capMap;
			if (!includeGated && gateOf(cap) != null) continue;   // gated grants decide in pass 2
			if (grantCovers(cap, resourceStr, abilityStr, ownerDID)) return true;
		}
		return false;
	}

	/** The grant's {@code nb.gate} operation reference, or null when ungated. */
	static AString gateOf(AMap<AString, ACell> cap) {
		ACell nb = cap.get(Capability.NB);
		if (!(nb instanceof AMap)) return null;
		return RT.ensureString(((AMap<?, ?>) nb).get(GATE));
	}

	private static final AString GATE = Strings.intern("gate");

	/** Structural coverage of a single grant — {@code with}/{@code can} only;
	 *  gate caveats are the callers' concern. */
	private static boolean grantCovers(AMap<AString, ACell> cap, AString resourceStr,
			AString abilityStr, AString ownerDID) {
		AString rawWith = RT.ensureString(cap.get(Capability.WITH));
		String canonWith = canonicalResource(rawWith != null ? rawWith.toString() : null, ownerDID);
		AString grantWith = (canonWith != null) ? Strings.create(canonWith) : null;
		AString grantCan = RT.ensureString(cap.get(Capability.CAN));

		// A null/empty grant `with` is a "match any resource" wildcard — the
		// venue's own asset/read ceiling ({with:""}) relies on it. This wildcard
		// lives ONLY in the ceiling path, never in the fail-closed UCAN proof
		// path (proofsCover). A concrete grant matches via the boundary-aware
		// Capability.resourceCovers (Convex #585 fixed); ability via abilityCovers.
		boolean resourceOK = (grantWith == null || grantWith.count() == 0)
				|| Capability.resourceCovers(grantWith, resourceStr);
		return resourceOK && Capability.abilityCovers(grantCan, abilityStr);
	}

	/**
	 * Appends a compact "ability on resource, ability on resource, …" list
	 * for inclusion in error messages. Empty caps render as {@code (none)}.
	 */
	private static void appendCapsList(StringBuilder sb, AVector<ACell> caps) {
		if (caps == null || caps.count() == 0) {
			sb.append("(none)");
			return;
		}
		boolean first = true;
		for (long i = 0; i < caps.count(); i++) {
			if (!(caps.get(i) instanceof AMap<?,?> capMap)) continue;
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> cap = (AMap<AString, ACell>) capMap;
			AString with = RT.ensureString(cap.get(Strings.intern("with")));
			AString can  = RT.ensureString(cap.get(Strings.intern("can")));
			if (!first) sb.append(", ");
			first = false;
			sb.append(can != null ? can.toString() : "(any)")
			  .append(" on ")
			  .append(with != null ? with.toString() : "(any)");
		}
	}

	/**
	 * Canonicalises a resource to its absolute, owner-scoped form for matching.
	 *
	 * <p>A capability resource is absolute: it names its owner. A bare lattice
	 * path ({@code "w/health/bp"}) is the owner's own resource and is prefixed
	 * with the owner DID → {@code "<owner>/w/health/bp"}. A resource that is
	 * already a DID URL (a cross-user path, {@code "did:key:…/w/…"}, or the
	 * DID-scoped {@code "did:key:…/dlfs/…"}) or scheme-qualified ({@code "file://…"})
	 * is absolute as-is and returned unchanged. With no owner context ({@code ownerDID == null})
	 * the resource is returned as given (compare-as-is).</p>
	 *
	 * <p>Applied identically to the op's resource and each cap's {@code with}, so
	 * an absolute (token) grant and a bare (agent-config) grant match a bare
	 * own-namespace operation the same way.</p>
	 */
	public static String canonicalResource(String resource, AString ownerDID) {
		if (resource == null || resource.isEmpty()) return resource;
		if (resource.startsWith("did:")) return resource;   // already owner-qualified (DID URL)
		// Legacy own-drive shorthand: "dlfs://<drive>/…" is accepted and normalised
		// to the DID-scoped path form "dlfs/<drive>/…" (then owner-scoped below), so
		// existing agent caps written in the scheme form keep working. Cross-user
		// UCAN grants use the path form only — the scheme form never reaches the
		// fail-closed proof path.
		if (resource.startsWith("dlfs://")) {
			resource = "dlfs/" + resource.substring("dlfs://".length());
		} else if (resource.contains("://")) {
			return resource;                                  // scheme-qualified (file://)
		}
		if (ownerDID == null) return resource;               // no owner context — compare as given
		return ownerDID + "/" + resource;                    // bare lattice path → owner-scoped
	}

}
