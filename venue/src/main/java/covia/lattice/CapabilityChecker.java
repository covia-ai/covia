package covia.lattice;

import convex.auth.ucan.Capability;
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
	 * <p>Selection delegates to {@link UCANValidator#capabilitiesFor} in
	 * convex-core (the generic UCAN primitive); this adds only covia's
	 * self-attenuation guard: a self-ceiling may only <b>narrow</b>, so a
	 * capability with an empty/absent {@code with} — a "match any resource"
	 * wildcard that would broaden — is dropped from the derived ceiling.</p>
	 */
	public static AVector<ACell> selfCapabilities(AVector<ACell> proofs,
			AString caller, AString issuer, long now) {
		AVector<ACell> caps = UCANValidator.capabilitiesFor(proofs, caller, issuer, now);
		if (caps == null) return null;
		AVector<ACell> narrowed = Vectors.empty();
		for (long i = 0; i < caps.count(); i++) {
			ACell c = caps.get(i);
			if (c instanceof AMap<?, ?> m) {
				@SuppressWarnings("unchecked")
				AString w = RT.ensureString(((AMap<AString, ACell>) m).get(Capability.WITH));
				// Self-attenuation may only NARROW: an empty/absent `with` is a
				// match-any wildcard that would broaden, so drop it (Convex #585).
				if (w == null || w.count() == 0) continue;
			}
			narrowed = narrowed.conj(c);
		}
		return narrowed.isEmpty() ? null : narrowed;
	}

	/**
	 * Cross-user proof check: do the caller's presented {@code proofs} grant
	 * {@code (resource, ability)}? A proof grants it when it is audienced to the
	 * caller, issued by {@code venueDID} (Phase C1 — the venue is authority for
	 * hosted data; generalised in Phase C3, covia#100), in-date, and carries an
	 * attenuation that {@link Capability#covers covers} the request.
	 *
	 * <p>Selection reuses {@link UCANValidator#capabilitiesFor} (convex-core);
	 * this is the single cross-user grant check — {@code CoviaAdapter.verifyProofs}
	 * and job-read authorisation both call it, so the model can't drift between
	 * the lattice-read path and the job path (they are the same right — covia#102).</p>
	 *
	 * @param proofs   the caller's presented UCAN proofs (from the RequestContext)
	 * @param caller   the caller's DID (proof audience)
	 * @param venueDID the verifying venue's DID (required proof issuer, Phase C1)
	 * @param resource the full resource being accessed (e.g. {@code "did:key:z…/j/<id>"})
	 * @param ability  the required ability (e.g. {@link Capability#CRUD_READ})
	 * @param now      current time, unix seconds
	 * @return true if some presented proof grants the request
	 */
	public static boolean proofsCover(AVector<ACell> proofs, AString caller, AString venueDID,
			AString resource, AString ability, long now) {
		if (caller == null || resource == null || ability == null) return false;
		AVector<ACell> caps = UCANValidator.capabilitiesFor(proofs, caller, venueDID, now);
		if (caps == null) return false;
		for (long i = 0; i < caps.count(); i++) {
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> cap = (caps.get(i) instanceof AMap<?, ?> m)
				? (AMap<AString, ACell>) m : null;
			if (cap != null && Capability.covers(cap, resource, ability)) return true;
		}
		return false;
	}

	/**
	 * Checks whether a capability ceiling allows a specific {@code (resource,
	 * ability)} pair supplied <em>directly</em> by the executing adapter — not
	 * derived from an operation name.
	 *
	 * <p>This is the enforcement primitive meant to be co-located with the code
	 * that performs the action: the implementation names the exact resource and
	 * ability it requires, so the enforced capability cannot drift from what the
	 * code actually does (unlike a name-keyed {@link #operationAbility} mapping,
	 * which is a separate source of truth that can fall out of sync).</p>
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
		if (caps == null) return null;              // no ceiling = unrestricted
		String canonResource = canonicalResource(resource != null ? resource.toString() : null, ownerDID);
		if (canonResource == null) canonResource = "";
		String ab = (ability != null) ? ability.toString() : "";
		AString resourceStr = Strings.create(canonResource);
		AString abilityStr = Strings.create(ab);

		if (covered(caps, resourceStr, abilityStr, ownerDID)) return null;

		StringBuilder sb = new StringBuilder("Capability denied: requires ")
			.append(ab.isEmpty() ? "(any ability)" : ab)
			.append(" on ").append(canonResource.isEmpty() ? "(any)" : canonResource)
			.append(". Your capabilities are: ");
		appendCapsList(sb, caps);
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
	private static boolean covered(AVector<ACell> caps, AString resourceStr, AString abilityStr, AString ownerDID) {
		for (long i = 0; i < caps.count(); i++) {
			if (!(caps.get(i) instanceof AMap<?,?> capMap)) continue;
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> cap = (AMap<AString, ACell>) capMap;

			AString rawWith = RT.ensureString(cap.get(Capability.WITH));
			String canonWith = canonicalResource(rawWith != null ? rawWith.toString() : null, ownerDID);
			AString grantWith = (canonWith != null) ? Strings.create(canonWith) : null;
			AString grantCan = RT.ensureString(cap.get(Capability.CAN));

			// Resource matching is done locally (boundary-aware) rather than via
			// Capability.resourceCovers, which prefix-matches without a path
			// segment boundary (Convex #585) — "…/w/notes" would otherwise cover
			// the sibling "…/w/notesSECRET". Ability matching reuses the already
			// boundary-aware Capability.abilityCovers (false for a null ability).
			if (resourceMatches(grantWith, resourceStr)
					&& Capability.abilityCovers(grantCan, abilityStr)) return true;
		}
		return false;
	}

	/**
	 * Boundary-aware resource matching — the hardened local replacement for
	 * {@link Capability#resourceCovers}, which prefix-matches without a path
	 * segment boundary (Convex #585): a grant on {@code "…/w/notes"} would
	 * otherwise cover the sibling {@code "…/w/notesSECRET"}, not just descendants
	 * {@code "…/w/notes/…"}.
	 *
	 * <p>A {@code null}/empty grant resource still means "any resource" — the
	 * venue's own {@code asset/read} grant ({@code {with:""}}) relies on this,
	 * while <em>user</em>-supplied empty-{@code with} caps are stripped earlier
	 * at the {@link #selfCapabilities} boundary. A concrete grant matches an
	 * exact resource, a descendant at a {@code '/'} boundary, or (for a grant
	 * ending in {@code '/'}) its slash-less parent.</p>
	 */
	static boolean resourceMatches(AString grant, AString request) {
		if (grant == null) return true;                 // wildcard (e.g. venue asset/read grant)
		long gLen = grant.count();
		if (gLen == 0) return true;                     // empty = wildcard
		if (request == null) return false;
		long rLen = request.count();
		if (grant.equals(request)) return true;         // exact
		if (rLen > gLen && request.startsWith(grant)
				&& (grant.charAt(gLen - 1) == '/' || request.charAt(gLen) == '/')) return true;
		// Trailing-slash parent: "w/x/" covers "w/x".
		if (grant.charAt(gLen - 1) == '/' && rLen == gLen - 1
				&& request.equals(grant.slice(0, gLen - 1))) return true;
		return false;
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
	 * already a DID URL (a cross-user path, {@code "did:key:…/w/…"}) or
	 * scheme-qualified ({@code "file://…"}, {@code "dlfs://…"}) is absolute as-is
	 * and returned unchanged. With no owner context ({@code ownerDID == null})
	 * the resource is returned as given (compare-as-is).</p>
	 *
	 * <p>Applied identically to the op's resource and each cap's {@code with}, so
	 * an absolute (token) grant and a bare (agent-config) grant match a bare
	 * own-namespace operation the same way.</p>
	 */
	static String canonicalResource(String resource, AString ownerDID) {
		if (resource == null || resource.isEmpty()) return resource;
		if (resource.startsWith("did:")) return resource;   // already owner-qualified (DID URL)
		if (resource.contains("://")) return resource;       // scheme-qualified (file://, dlfs://)
		if (ownerDID == null) return resource;               // no owner context — compare as given
		return ownerDID + "/" + resource;                    // bare lattice path → owner-scoped
	}

}
