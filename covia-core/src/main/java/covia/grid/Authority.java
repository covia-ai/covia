package covia.grid;

import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Vectors;

/**
 * An immutable credential of authority: the identity (a DID) a caller presents,
 * plus the set of <b>grants</b> (verified UCAN delegation tokens) it carries.
 *
 * <p>This is the portable "who, plus what has been delegated to them" that a
 * request or a handle acts under — the authority a caller <em>brings</em>. It is
 * distinct from a venue's evaluated {@code RequestContext}, which wraps an
 * {@code Authority} and adds venue-local, per-execution state (execution scopes,
 * job id).</p>
 *
 * <p><b>Grants are additive.</b> An {@code Authority} can be augmented with
 * further delegations (e.g. a short-lived, audience-bound UCAN handed to an
 * agent for a specific task) via {@link #withProof}/{@link #withProofs}. There is
 * no ceiling here: attenuation is a property of each individual delegation (you
 * can only delegate what you hold, and you mint it narrow), not of the
 * {@code Authority} as a whole.</p>
 *
 * <p>Immutable and thread-safe: every {@code with*} method returns a new
 * instance. Equality is by value (identity + grants).</p>
 */
public final class Authority {

	/** Caller identity (DID), or {@code null} for anonymous. */
	private final AString did;

	/** Verified UCAN delegation tokens this authority carries. Never null. */
	private final AVector<ACell> proofs;

	/** The anonymous authority: no identity, no grants. */
	public static final Authority ANONYMOUS = new Authority(null, Vectors.empty());

	private Authority(AString did, AVector<ACell> proofs) {
		this.did = did;
		this.proofs = (proofs != null) ? proofs : Vectors.empty();
	}

	/**
	 * An authority for the given identity, carrying no grants.
	 * @param did Caller DID, or null for anonymous
	 */
	public static Authority of(AString did) {
		return (did == null) ? ANONYMOUS : new Authority(did, Vectors.empty());
	}

	/**
	 * An authority for the given identity carrying the given verified grants.
	 * @param did Caller DID, or null for anonymous
	 * @param proofs Verified UCAN delegation tokens (null treated as none)
	 */
	public static Authority of(AString did, AVector<ACell> proofs) {
		return new Authority(did, proofs);
	}

	/** The caller identity, or null if anonymous. */
	public AString getDID() {
		return did;
	}

	/** The grants (verified UCAN delegations) this authority carries; never null. */
	public AVector<ACell> getProofs() {
		return proofs;
	}

	/** True if this authority carries no identity. */
	public boolean isAnonymous() {
		return did == null;
	}

	/** True if this authority carries at least one grant. */
	public boolean hasProofs() {
		return !proofs.isEmpty();
	}

	/**
	 * Returns a copy of this authority augmented with one additional grant.
	 * @param proof a verified UCAN delegation token (ignored if null)
	 */
	public Authority withProof(ACell proof) {
		if (proof == null) return this;
		return new Authority(did, proofs.concat(Vectors.of(proof)));
	}

	/**
	 * Returns a copy of this authority augmented with additional grants.
	 * @param more verified UCAN delegation tokens (null/empty is a no-op)
	 */
	public Authority withProofs(AVector<ACell> more) {
		if (more == null || more.isEmpty()) return this;
		return new Authority(did, proofs.concat(more));
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof Authority a)) return false;
		return Objects.equals(did, a.did) && proofs.equals(a.proofs);
	}

	@Override
	public int hashCode() {
		return Objects.hash(did, proofs);
	}

	@Override
	public String toString() {
		return "Authority[" + (did != null ? did : "anonymous") + ", grants=" + proofs.count() + "]";
	}
}
