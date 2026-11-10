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
 * covers it — <em>either you have the right or you don't</em>. Nothing
 * subtracts. Grants arrive two ways, both additive:</p>
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

	/** The user whose namespace this authority acts within, when that differs
	 *  from {@link #did} (an agent, or a delegate acting for that user).
	 *  {@code null} = the principal is its own user. Read through
	 *  {@link #getUserDID()}, never directly. */
	private final AString user;

	/** The caller's own held capability scope. {@code null} = unrestricted. */
	private final AVector<ACell> grants;

	/** Presented, verified UCAN delegation tokens, or {@code null} if none. */
	private final AVector<ACell> proofs;

	/** The anonymous authority: no identity, unrestricted-irrelevant, no proofs. */
	public static final Authority ANONYMOUS = new Authority(null, null, null, null);

	private Authority(AString did, AString user, AVector<ACell> grants, AVector<ACell> proofs) {
		this.did = did;
		// An authority that is its own user stores null, so equality does not
		// split identical principals over a redundant field.
		this.user = (user != null && user.equals(did)) ? null : user;
		this.grants = grants;
		this.proofs = proofs;
	}

	/**
	 * An authority for the given identity, unrestricted ({@code null} scope) and
	 * carrying no presented proofs.
	 *
	 * <p>The DID is <b>its own namespace</b>, whatever its name looks like: an
	 * agent-shaped DID names no owner relationship by itself. Only the venue puts
	 * a principal in another namespace, from a relationship it holds — running
	 * its own agent ({@link #ofAgent}) or acting on a delegation
	 * ({@link #onBehalfOf}). Whatever stores an authority stores that namespace
	 * beside the DID and rebuilds from both
	 * ({@link #of(AString, AString, AVector)}).</p>
	 *
	 * @param did Caller DID, or null for anonymous
	 */
	public static Authority of(AString did) {
		return (did == null) ? ANONYMOUS : new Authority(did, null, null, null);
	}

	/**
	 * An authority for the given identity bound to the given capability scope.
	 * A {@code null} scope is unrestricted; a non-null scope binds the principal
	 * to exactly those capabilities (an agent's {@code config.caps}). The DID is
	 * its own namespace, as in {@link #of(AString)}.
	 * @param did Caller DID, or null for anonymous
	 * @param grants the held capability scope, or null for unrestricted
	 */
	public static Authority of(AString did, AVector<ACell> grants) {
		return (did == null && grants == null) ? ANONYMOUS
			: new Authority(did, null, grants, null);
	}

	/**
	 * An authority for an <b>agent sub-principal</b> of {@code userDID}: identity
	 * is the agent's own DID ({@code <userDID>:g:<agentId>}), while the namespace
	 * it acts within remains the owner's.
	 *
	 * <p>The agent is the principal — it is who acted, and who a delegation may be
	 * audienced to — but its bare lattice paths still resolve in the owner's
	 * namespace, because that is where its workspace, secrets and inbox live. The
	 * scope starts unrestricted; bind it with {@link #withGrantScope} from the
	 * agent's {@code config.caps}.</p>
	 *
	 * @param userDID the owning user's DID
	 * @param agentId the agent identifier within that user's namespace
	 */
	public static Authority ofAgent(AString userDID, AString agentId) {
		if (userDID == null) return ANONYMOUS;
		return of(Principals.agentDID(userDID, agentId), userDID, null);
	}

	/**
	 * An authority for {@code did} acting in the namespace of {@code userDID}
	 * under the given scope. This states a relationship rather than finding one,
	 * so it is for one the venue holds or recorded: its own agent's, or a stored
	 * context being rebuilt. Proofs, if any, are attached with {@link #withProofs}.
	 *
	 * @param did Caller DID, or null for anonymous
	 * @param userDID the namespace it acts in; null or {@code did} for its own
	 * @param grants the held capability scope, or null for unrestricted
	 */
	public static Authority of(AString did, AString userDID, AVector<ACell> grants) {
		return (did == null) ? ANONYMOUS : new Authority(did, userDID, grants, null);
	}

	/**
	 * Returns this actor executing on behalf of {@code userDID} under its
	 * presented delegation proofs.
	 *
	 * <p>The actor identity is preserved for attribution and proof-audience
	 * checks, while bare paths and per-user state resolve in the delegating
	 * user's namespace. The held scope is deliberately empty: authority over
	 * that namespace must come from the signed proofs, never from the actor's
	 * otherwise-unrestricted authority over its own account.</p>
	 *
	 * @param userDID the delegating user whose namespace the actor works in
	 * @return a proof-bounded delegated authority, or this authority when the
	 *         user is null or already the actor's user
	 */
	public Authority onBehalfOf(AString userDID) {
		if (userDID == null || userDID.equals(getUserDID())) return this;
		return new Authority(did, userDID, Vectors.empty(), proofs);
	}

	/** The caller identity, or null if anonymous. */
	public AString getDID() {
		return did;
	}

	/**
	 * The user whose namespace this authority acts within — the owner for an agent
	 * sub-principal, the delegating user for a delegate ({@link #onBehalfOf}),
	 * and {@link #getDID()} for every other principal.
	 *
	 * <p>This is the DID that resolves bare lattice paths and per-user state
	 * (workspace, secrets, jobs, inbox). It is <b>not</b> an identity: use
	 * {@link #getDID()} for attribution, for a delegation audience, and for any
	 * decision about who is acting.</p>
	 */
	public AString getUserDID() {
		return (user != null) ? user : did;
	}

	/** True if this authority acts within another principal's namespace. */
	public boolean isSubPrincipal() {
		return user != null;
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
		return new Authority(did, user, grants.concat(Vectors.of(grant)), proofs);
	}

	/**
	 * Returns a copy augmented with additional capabilities in its scope. A no-op
	 * on an unrestricted authority ({@code null} scope) or an empty argument.
	 * @param more capabilities (null/empty is a no-op)
	 */
	public Authority withGrants(AVector<ACell> more) {
		if (more == null || more.isEmpty() || grants == null) return this;
		return new Authority(did, user, grants.concat(more), proofs);
	}

	/**
	 * Returns a copy with its capability scope replaced. {@code null} = make the
	 * authority unrestricted; a vector binds it to exactly those capabilities.
	 * Identity and presented proofs are preserved.
	 */
	public Authority withGrantScope(AVector<ACell> grants) {
		return new Authority(did, user, grants, proofs);
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
		return new Authority(did, user, grants, proofs);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof Authority a)) return false;
		return Objects.equals(did, a.did)
			&& Objects.equals(user, a.user)
			&& Objects.equals(grants, a.grants)
			&& Objects.equals(proofs, a.proofs);
	}

	@Override
	public int hashCode() {
		return Objects.hash(did, user, grants, proofs);
	}

	@Override
	public String toString() {
		return "Authority[" + (did != null ? did : "anonymous")
			+ (user != null ? " of " + user : "")
			+ ", grants=" + (grants != null ? grants.count() : "unrestricted")
			+ ", proofs=" + (proofs != null ? proofs.count() : 0) + "]";
	}
}
