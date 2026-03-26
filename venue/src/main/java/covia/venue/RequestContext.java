package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Vectors;

/**
 * Represents the context of an API request, carrying caller identity,
 * UCAN proofs, and request-scoped metadata.
 */
public class RequestContext {

	private final AString callerDID;
	private final boolean internal;
	private final AVector<ACell> proofs;

	/**
	 * Context for internal operations (adapters, recovery, etc.) that bypass auth.
	 */
	public static final RequestContext INTERNAL = new RequestContext(null, true, null);

	/**
	 * Context for anonymous (unauthenticated) external requests.
	 */
	public static final RequestContext ANONYMOUS = new RequestContext(null, false, null);

	private RequestContext(AString callerDID, boolean internal, AVector<ACell> proofs) {
		this.callerDID = callerDID;
		this.internal = internal;
		this.proofs = proofs;
	}

	/**
	 * Creates a RequestContext for an external request with the given caller DID.
	 * Returns ANONYMOUS if callerDID is null.
	 */
	public static RequestContext of(AString callerDID) {
		if (callerDID == null) return ANONYMOUS;
		return new RequestContext(callerDID, false, null);
	}

	/**
	 * Creates a RequestContext with caller DID and UCAN proofs.
	 */
	public static RequestContext of(AString callerDID, AVector<ACell> proofs) {
		if (callerDID == null) return ANONYMOUS;
		return new RequestContext(callerDID, false, proofs);
	}

	/**
	 * Returns a new context with the given proofs added.
	 */
	public RequestContext withProofs(AVector<ACell> proofs) {
		return new RequestContext(this.callerDID, this.internal, proofs);
	}

	/**
	 * Gets the caller's DID, or null if anonymous/internal.
	 */
	public AString getCallerDID() {
		return callerDID;
	}

	/**
	 * Returns true if this is an internal request (bypasses auth checks).
	 */
	public boolean isInternal() {
		return internal;
	}

	/**
	 * Returns true if this is an anonymous (unauthenticated, non-internal) request.
	 */
	public boolean isAnonymous() {
		return callerDID == null && !internal;
	}

	/**
	 * Returns true if a caller DID is present.
	 */
	public boolean isAuthenticated() {
		return callerDID != null;
	}

	/**
	 * Gets the UCAN proofs attached to this request, or null if none.
	 */
	public AVector<ACell> getProofs() {
		return proofs;
	}

	@Override
	public String toString() {
		if (internal) return "RequestContext[INTERNAL]";
		if (callerDID == null) return "RequestContext[ANONYMOUS]";
		return "RequestContext[" + callerDID + "]";
	}
}
