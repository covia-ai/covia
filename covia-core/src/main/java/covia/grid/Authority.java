package covia.grid;

import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Vectors;

/**
 * An immutable credential of authority: <b>who</b> a caller is (a DID) plus
 * <b>what they may do</b> — the grants they hold. It is the portable "identity
 * plus authorisation" a request or handle acts under; a venue's
 * {@code RequestContext} wraps an {@code Authority} and adds only per-execution
 * state (execution scopes, job id).
 *
 * <p><b>Two grant sources, one rule.</b> An action is authorised iff a grant
 * covers it — <em>either you have the right or you don't</em>. There is no
 * ceiling: nothing here subtracts. Grants arrive two ways, both additive:</p>
 * <ul>
 *   <li><b>{@code grants}</b> — the caller's own held capability scope (an
 *       agent's {@code config.caps}). {@code null} means an <em>unrestricted</em>
 *       principal (the owner/venue) — not "a grant of everything" but "no
 *       explicit scope, so inherent authority stands"; it is the efficient
 *       fast-path. A non-null scope binds the principal to exactly those
 *       capabilities.</li>
 *   <li><b>{@code proofs}</b> — presented, verified UCAN delegation tokens, the
 *       additive cross-user reach a caller <em>brings</em> to a request.</li>
 * </ul>
 *
 * <p>An {@code Authority} can be augmented — a short-lived, audience-bound UCAN
 * handed to an agent widens what it may do without ever narrowing it
 * ({@link #withGrant}/{@link #withGrants} on the scope; {@link #withProofs} for
 * presented delegations). Immutable and thread-safe: every {@code with*} returns
 * a new instance. Equality is by value.</p>
 */
public final class Authority {

	/** Caller identity (DID), or {@code null} for anonymous. */
	private final AString did;

	/** The caller's own held capability scope. {@code null} = unrestricted. */
	private final AVector<ACell> grants;

	/** Presented, verified UCAN delegation tokens, or {@code null} if none. */
	private final AVector<ACell> proofs;

	/** The anonymous authority: no identity, unrestricted-irrelevant, no proofs. */
	public static final Authority ANONYMOUS = new Authority(null, null, null);

	private Authority(AString did, AVector<ACell> grants, AVector<ACell> proofs) {
		this.did = did;
		this.grants = grants;
		this.proofs = proofs;
	}

	/**
	 * An authority for the given identity, unrestricted ({@code null} scope) and
	 * carrying no presented proofs.
	 * @param did Caller DID, or null for anonymous
	 */
	public static Authority of(AString did) {
		return (did == null) ? ANONYMOUS : new Authority(did, null, null);
	}

	/**
	 * An authority for the given identity bound to the given capability scope.
	 * A {@code null} scope is unrestricted; a non-null scope binds the principal
	 * to exactly those capabilities (an agent's {@code config.caps}).
	 * @param did Caller DID, or null for anonymous
	 * @param grants the held capability scope, or null for unrestricted
	 */
	public static Authority of(AString did, AVector<ACell> grants) {
		return (did == null && grants == null) ? ANONYMOUS : new Authority(did, grants, null);
	}

	/** The caller identity, or null if anonymous. */
	public AString getDID() {
		return did;
	}

	/** The held capability scope; {@code null} = unrestricted (the fast path). */
	public AVector<ACell> getGrants() {
		return grants;
	}

	/** The presented, verified UCAN delegations, or null if none. */
	public AVector<ACell> getProofs() {
		return proofs;
	}

	/** True if this authority carries no identity. */
	public boolean isAnonymous() {
		return did == null;
	}

	/** True if this authority is bound to an explicit (non-null, non-empty) scope. */
	public boolean hasGrants() {
		return grants != null && !grants.isEmpty();
	}

	/** True if this authority carries at least one presented proof. */
	public boolean hasProofs() {
		return proofs != null && !proofs.isEmpty();
	}

	/**
	 * Returns a copy augmented with one additional capability in its scope — the
	 * additive way an authority acquires more authority. A no-op on an
	 * unrestricted authority (a {@code null} scope already covers everything).
	 * @param grant a capability (ignored if null)
	 */
	public Authority withGrant(ACell grant) {
		if (grant == null || grants == null) return this;
		return new Authority(did, grants.concat(Vectors.of(grant)), proofs);
	}

	/**
	 * Returns a copy augmented with additional capabilities in its scope. A no-op
	 * on an unrestricted authority ({@code null} scope) or an empty argument.
	 * @param more capabilities (null/empty is a no-op)
	 */
	public Authority withGrants(AVector<ACell> more) {
		if (more == null || more.isEmpty() || grants == null) return this;
		return new Authority(did, grants.concat(more), proofs);
	}

	/**
	 * Returns a copy with its capability scope replaced. {@code null} = make the
	 * authority unrestricted; a vector binds it to exactly those capabilities.
	 * Identity and presented proofs are preserved.
	 */
	public Authority withGrantScope(AVector<ACell> grants) {
		return new Authority(did, grants, proofs);
	}

	/**
	 * Returns a copy carrying the given presented, verified UCAN delegations,
	 * replacing any already attached. Identity and scope are preserved.
	 *
	 * <p><b>Trust contract:</b> every token must already be cryptographically
	 * verified (signature, expiry-at-verification, chain integrity) — downstream
	 * authorisation re-checks only temporal bounds and policy, never signatures.</p>
	 */
	public Authority withProofs(AVector<ACell> proofs) {
		return new Authority(did, grants, proofs);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof Authority a)) return false;
		return Objects.equals(did, a.did)
			&& Objects.equals(grants, a.grants)
			&& Objects.equals(proofs, a.proofs);
	}

	@Override
	public int hashCode() {
		return Objects.hash(did, grants, proofs);
	}

	@Override
	public String toString() {
		return "Authority[" + (did != null ? did : "anonymous")
			+ ", grants=" + (grants != null ? grants.count() : "unrestricted")
			+ ", proofs=" + (proofs != null ? proofs.count() : 0) + "]";
	}
}
