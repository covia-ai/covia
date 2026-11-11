package covia.lattice;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import convex.auth.ucan.Capability;
import convex.auth.ucan.RootAuthorityPolicy;
import convex.auth.ucan.UCAN;
import convex.auth.ucan.UCANValidator;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.grid.HexId;

/**
 * Decides whether a request is allowed: a held scope ({@link #allows}), two
 * scopes narrowing each other ({@link #intersect}), and presented UCAN proofs
 * ({@link #proofsCover}).
 *
 * <p>What a capability and a set of them are is {@link Cap}'s and
 * {@link Caps}'s. This class adds what depends on where a check happens: how
 * a bare resource or gate is named in a namespace ({@link #canonicalResource},
 * {@link #bindGate}), running gates for the invocation, the denial message,
 * and the proof chain.</p>
 *
 * <p>See {@code venue/docs/UCAN.md §5.4} for the full design.</p>
 */
public class CapabilityChecker {

	private static final AString GATE = Cap.GATE;

	/**
	 * Cross-user proof check: do the caller's presented {@code proofs} authorise
	 * {@code (resource, ability)}? Delegates to the convex-core authority layer
	 * ({@link UCANValidator#isAuthorised}), which checks capability coverage, then
	 * per-hop delegation attenuation to the chain root, then root authority.
	 *
	 * <p>The caller supplies the complete root-authority policy. In Covia this is
	 * normally the self-sovereign owner rule plus a narrowly scoped local-custodial
	 * rule from {@code Engine.rootAuthorityPolicy()}. Keeping venue identity policy
	 * out of this generic checker prevents a venue signature from accidentally
	 * becoming root authority over every DID namespace.</p>
	 *
	 * <p>This is the single cross-user grant check — {@code CoviaAdapter.verifyProofs}
	 * and job-read authorisation both call it, so the model can't drift between the
	 * lattice-read path and the job path (they are the same right — covia#102).
	 * Signatures/chain structure were verified at transport ingress
	 * ({@link UCANValidator#parseTransportUCANs}).</p>
	 *
	 * @param proofs   the caller's presented UCAN proofs (from the RequestContext)
	 * @param caller   the caller's DID (proof audience)
	 * @param rootPolicy policy deciding who may root authority for the resource
	 * @param resource the full resource being accessed (e.g. {@code "did:key:z…/j/<id>"})
	 * @param ability  the required ability (e.g. {@link Capability#CRUD_READ})
	 * @param now      current time, unix seconds
	 * @return true if the presented proofs authorise the request
	 */
	public static boolean proofsCover(AVector<ACell> proofs, AString caller, RootAuthorityPolicy rootPolicy,
			AString resource, AString ability, long now) {
		return proofsCover(proofs, caller, rootPolicy, resource, ability, now,
			null, null, null);
	}

	/**
	 * Cross-user proof check with application caveat enforcement.
	 *
	 * <p>Convex verifies the proof structure and supplies every structurally valid
	 * root-to-leaf capability path. Covia then interprets the caveats on that
	 * complete path: every {@code nb.gate} must pass, while malformed or unknown
	 * caveats reject that path. Paths are alternatives, so another structurally
	 * valid path may still authorise. A missing evaluator rejects gated paths.
	 * Repeated references to the same gate are evaluated once per authorisation
	 * decision because the operation, input and caller are identical throughout
	 * that decision.</p>
	 */
	public static boolean proofsCover(AVector<ACell> proofs, AString caller, RootAuthorityPolicy rootPolicy,
			AString resource, AString ability, long now, AString opRef,
			ACell invocationInput, CapabilityGate gate) {
		return proofsCover(proofs, caller, rootPolicy, resource, ability, now, opRef, invocationInput, gate, null);
	}

	/**
	 * As above for a use that sends a value to {@code destination}: a
	 * {@code nb.url} caveat on any capability of the path must cover it, and a
	 * use with no destination never satisfies one.
	 */
	public static boolean proofsCover(AVector<ACell> proofs, AString caller, RootAuthorityPolicy rootPolicy,
			AString resource, AString ability, long now, AString opRef,
			ACell invocationInput, CapabilityGate gate, AString destination) {
		if (caller == null || rootPolicy == null || resource == null || ability == null) return false;
		// Bare `with` paths mean the TOKEN ISSUER's own namespace — resolved here,
		// at evaluation, against the signed `iss` that travels with every token.
		// The `with` form is covia-specific; convex-core validates structure and
		// signatures and treats resources as opaque, so the relying party
		// normalises its view of the verified claims before the authority check.
		Predicate<AVector<ACell>> caveats = new ProofCaveatEvaluator(
			opRef, invocationInput, caller, gate, destination);
		return UCANValidator.isAuthorised(normaliseProofs(proofs), caller, resource,
			ability, rootPolicy, now, caveats);
	}

	/**
	 * Structural proof check that deliberately does not interpret application
	 * caveats. This is not an enforcement primitive. It exists for questions such
	 * as whether a granting right remains structurally and temporally valid at a
	 * future expiry horizon; runtime access must use {@link #proofsCover}.
	 */
	public static boolean proofsStructurallyCover(AVector<ACell> proofs, AString caller,
			RootAuthorityPolicy rootPolicy, AString resource, AString ability, long now) {
		if (caller == null || rootPolicy == null || resource == null || ability == null) return false;
		return UCANValidator.isAuthorised(normaliseProofs(proofs), caller, resource,
			ability, rootPolicy, now);
	}

	/** Stateful per-decision predicate passed to convex-core's proof walker. */
	private static final class ProofCaveatEvaluator implements Predicate<AVector<ACell>> {
		private final AString opRef;
		private final ACell input;
		private final AString caller;
		private final CapabilityGate gate;
		private final AString destination;
		private final Map<AString, Boolean> gateResults = new HashMap<>();

		private ProofCaveatEvaluator(AString opRef, ACell input, AString caller,
				CapabilityGate gate, AString destination) {
			this.opRef = opRef;
			this.input = input;
			this.caller = caller;
			this.gate = gate;
			this.destination = destination;
		}

		@Override
		public boolean test(AVector<ACell> path) {
			if (path == null) return false;
			Set<AString> gates = new LinkedHashSet<>();
			for (long i = 0; i < path.count(); i++) {
				AMap<AString, ACell> map = RT.castMap(path.get(i));
				if (map == null) return false;
				Cap cap = Cap.of(map);
				if (!cap.valid() || cap.urlDenial(destination) != null) return false;
				AString gateOp = cap.gate();
				if (gateOp != null) {
					// normaliseProofs binds a bare gate to its link's issuer; one still
					// bare came from a link it could not rewrite, so names no one's gate.
					if (isBareGate(gateOp)) return false;
					gates.add(gateOp);
				}
			}
			if (gates.isEmpty()) return true;
			if (gate == null) return false;
			for (AString gateOp : gates) {
				Boolean allowed = gateResults.get(gateOp);
				if (allowed == null && !gateResults.containsKey(gateOp)) {
					try {
						allowed = (gate.evaluate(gateOp, opRef, input, caller) == null);
					} catch (Throwable t) {
						allowed = false;
					}
					gateResults.put(gateOp, allowed);
				}
				if (!allowed) return false;
			}
			return true;
		}
	}

	/**
	 * Normalises verified UCAN token maps so a bare capability resource means
	 * <b>the token issuer's own namespace</b>: each bare (non-DID, non-scheme),
	 * non-empty {@code att[i].with} is resolved there ({@link #canonicalResource}).
	 * Applied recursively through map-form {@code prf}
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

	/**
	 * A gate reference bound to the principal whose grant names it, as a bare
	 * {@code with} is: a bare path in a user namespace means that principal's
	 * ({@code o/gate} → {@code <owner>/o/gate}). The venue catalog ({@code v/…}),
	 * content hashes, DID URLs and scheme forms already name one definition.
	 * Naming a gate is consent to have it run as a gate check, not to read it.
	 *
	 * @return the bound reference, or {@code gate} itself when nothing binds
	 */
	public static AString bindGate(AString gate, AString owner) {
		if (gate == null || owner == null || !isBareGate(gate)) return gate;
		return Strings.create(canonicalResource(gate.toString(), owner));
	}

	/** Whether a gate reference is a bare path whose meaning depends on its owner. */
	static boolean isBareGate(AString gate) {
		String g = CoviaPath.relative(gate.toString());
		if (g.isEmpty() || g.startsWith("did:") || isScheme(g) || g.startsWith("v/")) return false;
		return HexId.parseHash(Strings.create(g)) == null;
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
				if (w != null && !isScheme(w)) {
					String bound = canonicalResource(w, iss);
					if (!bound.equals(w)) {
						cap = cap.assoc(Capability.WITH, Strings.create(bound));
						capCell = cap;
						changed = true;
					}
				}
				AMap<AString, ACell> nb = (cap != null) ? RT.castMap(cap.get(Capability.NB)) : null;
				AString gate = (nb != null) ? RT.ensureString(nb.get(GATE)) : null;
				AString bound = bindGate(gate, iss);
				if (bound != gate) {
					capCell = cap.assoc(Capability.NB, nb.assoc(GATE, bound));
					changed = true;
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
	 * Checks whether a capability scope — the grants a caller holds — covers a
	 * specific {@code (resource, ability)} pair supplied <em>directly</em> by the
	 * executing adapter, not derived from an operation name. Either a grant covers
	 * it or you don't have the right; a {@code null} scope is unrestricted.
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
	 * @param caps     the caller's held grant scope; {@code null} = unrestricted
	 * @param resource the exact resource acted on — a bare lattice path
	 *                 ({@code "w/x"}, owner-scoped), a DID URL, or a scheme URI;
	 *                 {@code null}/empty means "no specific resource"
	 * @param ability  the exact ability required (e.g.
	 *                 {@link Capability#CRUD_WRITE}, {@code "secret/write"})
	 * @param ownerDID the caller's DID, used to canonicalise bare resources; may be null
	 * @return {@code null} if allowed, else an actionable denial message
	 */
	public static String allows(AVector<ACell> caps, AString resource, AString ability, AString ownerDID) {
		return allows(caps, resource, ability, ownerDID, ownerDID, null, null, null);
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
	 * @param callerDID the principal that acted, for the gate's {@code caller}
	 *              field; {@code ownerDID} is the namespace it acted in
	 * @param opRef the operation reference being invoked (for the gate's
	 *              {@code operation} field; may be null)
	 * @param invocationInput the invocation input the gate rules on
	 * @param gate  the gate evaluator, or null when gates cannot be evaluated
	 *              in this context
	 */
	public static String allows(AVector<ACell> caps, AString resource, AString ability, AString ownerDID,
			AString callerDID, AString opRef, ACell invocationInput, CapabilityGate gate) {
		return allows(caps, resource, ability, ownerDID, callerDID, opRef, invocationInput, gate, null);
	}

	/**
	 * As above for a use that sends a value to {@code destination} (an
	 * outbound url): a grant's {@code nb.url} caveat must cover it. A caveated
	 * grant authorises only when every caveat it carries is satisfied.
	 */
	public static String allows(AVector<ACell> caps, AString resource, AString ability, AString ownerDID,
			AString callerDID, AString opRef, ACell invocationInput, CapabilityGate gate, AString destination) {
		if (caps == null) return null;              // no scope = unrestricted (no bounding grants)
		String canonResource = canonicalResource(resource != null ? resource.toString() : null, ownerDID);
		if (canonResource == null) canonResource = "";
		String ab = (ability != null) ? ability.toString() : "";
		AString resourceStr = Strings.create(canonResource);
		AString abilityStr = Strings.create(ab);
		Caps scope = Caps.of(namedIn(caps, ownerDID));

		// Pass 1: any covering, valid grant WITHOUT a caveat authorises outright.
		if (scope.covers(resourceStr, abilityStr)) return null;

		// Pass 2: covering grants WITH caveats — evaluated in declaration order,
		// the first grant whose every caveat holds wins. Denial details
		// accumulate so a refused caller sees why each applicable grant said no.
		StringBuilder denials = null;
		for (Cap cap : scope.conditionallyCovering(resourceStr, abilityStr)) {
			String detail = cap.urlDenial(destination);
			if (detail == null && cap.gate() != null) {
				// A held grant is its owner's: a bare gate names the owner's operation.
				AString gateOp = bindGate(cap.gate(), ownerDID);
				detail = (gate == null)
					? "gate " + gateOp + " cannot be evaluated in this context"
					: gate.evaluate(gateOp, opRef, invocationInput, callerDID);
			}
			if (detail == null) return null;                    // every caveat holds — authorised
			if (denials == null) denials = new StringBuilder();
			else denials.append("; ");
			denials.append(detail);
		}
		if (denials != null) {
			// The prefix is wording embedders match on; a url caveat is a gate on
			// the use in the same sense, so it keeps the same prefix.
			return "Capability denied by gate: " + denials
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
		// ends ":public") so a scoped agent's own-scope denial, which it is
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
	 * The default read-only capability grant scope for an identity: read the
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
	public static AVector<ACell> readOnlyScope(AString scopeDID) {
		return Vectors.of(
			Capability.create(scopeDID, Capability.CRUD_READ),
			Capability.create(Strings.create(""), Abilities.ASSET_READ));
	}

	/**
	 * {@code caps} with each grant's {@code with} named as a request in
	 * {@code namespace} is ({@link #canonicalResource}), so a grant and a request
	 * compare in one form. A missing or empty {@code with}, the match-any
	 * wildcard of a held scope, and a {@code with} that is not a string, which
	 * makes its grant malformed, are left as they are.
	 */
	private static AVector<ACell> namedIn(AVector<ACell> caps, AString namespace) {
		AVector<ACell> named = caps;
		for (long i = 0; i < caps.count(); i++) {
			AMap<AString, ACell> grant = RT.castMap(caps.get(i));
			AString with = (grant == null) ? null : RT.ensureString(grant.get(Capability.WITH));
			if (with == null || with.count() == 0) continue;
			String canonical = canonicalResource(with.toString(), namespace);
			if (!canonical.equals(with.toString())) {
				named = named.assoc(i, grant.assoc(Capability.WITH, Strings.create(canonical)));
			}
		}
		return named;
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
	 * The canonical form of a resource name: the one place a name is resolved
	 * in a namespace.
	 *
	 * <p>Leading slashes change nothing. A bare name ({@code "w/health/bp"}) is
	 * resolved in {@code namespace} → {@code "<namespace>/w/health/bp"}, and
	 * {@code "/"} is that namespace's root. A DID URL or scheme reference
	 * ({@code "file://…"}) already says where it is. With a null
	 * {@code namespace} a bare name stays bare, which is the form it is stored
	 * in. The empty name has no canonical form and is returned as it is.</p>
	 *
	 * <p>The namespace is only where the name is resolved, not whose authority
	 * uses it. Applied identically to an operation's resource and each grant's
	 * {@code with}, so an absolute and a bare grant match alike.</p>
	 */
	public static String canonicalResource(String resource, AString namespace) {
		if (resource == null || resource.isEmpty()) return resource;
		String name = CoviaPath.relative(resource);
		if (name.isEmpty()) return (namespace == null) ? "/" : namespace + "/";
		if (name.startsWith("did:")) return name;
		// Legacy own-drive shorthand: "dlfs://<drive>/…" is accepted and normalised
		// to the DID-scoped path form "dlfs/<drive>/…", so existing agent caps
		// written in the scheme form keep working. Cross-user UCAN grants use the
		// path form only — the scheme form never reaches the fail-closed proof path.
		if (name.startsWith("dlfs://")) {
			name = "dlfs/" + name.substring("dlfs://".length());
		} else if (isScheme(name)) {
			return name;
		}
		return (namespace == null) ? name : namespace + "/" + name;
	}

	/** Whether {@code name} is a scheme reference ({@code scheme://…}), once any leading slashes are ignored. */
	public static boolean isScheme(String name) {
		if (name == null) return false;
		String relative = CoviaPath.relative(name);
		int slash = relative.indexOf('/');
		return slash > 1 && relative.charAt(slash - 1) == ':' && relative.startsWith("//", slash);
	}

	/**
	 * {@code caps} as agent {@code agentId} means them: a bare {@code n/} names
	 * its own notes, as {@code n/} does in any path it uses, so a capability
	 * over it is one over {@code g/<agentId>/n/}. Paths naming an owner, and
	 * every other bare path, are unchanged. {@code c/} and {@code t/} need no
	 * capability, so name nothing here.
	 *
	 * @return {@code caps} itself when nothing names {@code n/}, or when there is no agent
	 */
	public static AVector<ACell> inAgent(AVector<ACell> caps, AString agentId) {
		if (caps == null || agentId == null) return caps;
		AVector<ACell> resolved = caps;
		for (long i = 0; i < caps.count(); i++) {
			AMap<AString, ACell> grant = RT.castMap(caps.get(i));
			AString with = (grant == null) ? null : RT.ensureString(grant.get(Capability.WITH));
			if (with == null) continue;
			String value = CoviaPath.relative(with.toString());
			if (!value.equals("n") && !value.startsWith("n/")) continue;
			resolved = resolved.assoc(i, grant.assoc(Capability.WITH, Strings.create(Namespace.G + "/" + agentId + "/" + value)));
		}
		return resolved;
	}

	/**
	 * The scope allowing exactly what both {@code a} and {@code b} allow, for
	 * authority narrowed twice, as an agent's is by its controller's authority
	 * and its own caps. A null scope is unrestricted, so the other scope is the
	 * answer. Two grants covering one request are nested, since both are
	 * segment-boundary prefixes of its resource and ability, so the narrower
	 * {@code with} and narrower {@code can} of each pair cover just what the pair
	 * does together.
	 *
	 * <p>Every caveat of a pair still binds, so the result carries them all. A
	 * pair asking different things of the same caveat cannot be carried on one
	 * grant and allows nothing. A malformed grant allows nothing.</p>
	 *
	 * <p>Each scope's bare paths and gates are its own owner's. When the owners
	 * differ the result names them absolutely, so it reads the same wherever it
	 * is evaluated; the venue's {@code v/} view is the same from every namespace
	 * and stays as written.</p>
	 *
	 * @param aOwner the namespace {@code a}'s bare paths name
	 * @param bOwner the namespace {@code b}'s bare paths name
	 */
	public static AVector<ACell> intersect(AVector<ACell> a, AString aOwner, AVector<ACell> b, AString bOwner) {
		boolean sameOwner = Objects.equals(aOwner, bOwner);
		if (a == null) return sameOwner ? b : absoluteScope(b, bOwner);
		if (b == null) return sameOwner ? a : absoluteScope(a, aOwner);
		AVector<ACell> met = Vectors.empty();
		for (long i = 0; i < a.count(); i++) {
			for (long j = 0; j < b.count(); j++) {
				AMap<AString, ACell> grant = meet(a.get(i), aOwner, b.get(j), bOwner, sameOwner);
				if (grant != null) met = met.conj(grant);
			}
		}
		return met;
	}

	/** The grant covering what both grants cover, or null when they share nothing or their caveats conflict. */
	private static AMap<AString, ACell> meet(ACell a, AString aOwner, ACell b, AString bOwner, boolean sameOwner) {
		AMap<AString, ACell> p = RT.castMap(a);
		AMap<AString, ACell> q = RT.castMap(b);
		if (p == null || q == null) return null;
		Cap pCap = Cap.of(p);
		Cap qCap = Cap.of(q);
		if (!pCap.valid() || !qCap.valid()) return null;

		AString can = Cap.narrowerAbility(pCap.can(), qCap.can());
		if (can == null) return null;

		AString pWith = comparableWith(p, aOwner);
		AString qWith = comparableWith(q, bOwner);
		boolean pNarrower;
		if (pWith.count() == 0) pNarrower = false;
		else if (qWith.count() == 0) pNarrower = true;
		else if (Capability.resourceCovers(pWith, qWith)) pNarrower = false;
		else if (Capability.resourceCovers(qWith, pWith)) pNarrower = true;
		else return null;

		AMap<AString, ACell> nb = Cap.combinedCaveats(caveatMap(p, aOwner, sameOwner), caveatMap(q, bOwner, sameOwner));
		if (nb == null) return null;
		AString with;
		if (sameOwner) {
			with = RT.ensureString((pNarrower ? p : q).get(Capability.WITH));
			if (with == null) with = Strings.EMPTY;
		} else {
			with = pNarrower ? pWith : qWith;
		}
		AMap<AString, ACell> grant = Maps.of(Capability.WITH, with, Capability.CAN, can);
		return nb.isEmpty() ? grant : grant.assoc(Capability.NB, nb);
	}

	/** A grant's resource in the form two scopes compare: empty for match-any, absolute in its owner's namespace otherwise. */
	private static AString comparableWith(AMap<AString, ACell> grant, AString owner) {
		AString with = RT.ensureString(grant.get(Capability.WITH));
		if (with == null || with.count() == 0) return Strings.EMPTY;
		return absoluteWith(with, owner);
	}

	/** {@code with} made absolute in {@code owner}'s namespace, except the venue's {@code v/} view, which is every namespace's. */
	private static AString absoluteWith(AString with, AString owner) {
		String value = CoviaPath.relative(with.toString());
		if (value.equals("v") || value.startsWith("v/")) return with;
		return Strings.create(canonicalResource(value, owner));
	}

	/** A grant's caveats as a map, its gate bound to its owner when the result will be read in another namespace. */
	private static AMap<AString, ACell> caveatMap(AMap<AString, ACell> grant, AString owner, boolean sameOwner) {
		AMap<AString, ACell> nb = RT.castMap(grant.get(Capability.NB));
		if (nb == null) return Maps.empty();
		if (sameOwner) return nb;
		AString gate = RT.ensureString(nb.get(GATE));
		return (gate == null) ? nb : nb.assoc(GATE, bindGate(gate, owner));
	}

	/** {@code scope} with its paths and gates named absolutely in {@code owner}'s namespace; null stays unrestricted. */
	private static AVector<ACell> absoluteScope(AVector<ACell> scope, AString owner) {
		if (scope == null) return null;
		AVector<ACell> absolute = Vectors.empty();
		for (long i = 0; i < scope.count(); i++) {
			AMap<AString, ACell> grant = RT.castMap(scope.get(i));
			if (grant == null || !Cap.of(grant).valid()) continue;
			AMap<AString, ACell> nb = caveatMap(grant, owner, false);
			AMap<AString, ACell> named = grant.assoc(Capability.WITH, comparableWith(grant, owner));
			absolute = absolute.conj(nb.isEmpty() ? named : named.assoc(Capability.NB, nb));
		}
		return absolute;
	}

}
