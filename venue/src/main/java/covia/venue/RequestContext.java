package covia.venue;

import convex.core.data.AString;

/**
 * Represents the context of an API request, carrying caller identity and
 * request-scoped metadata. Threaded through Engine methods to support
 * authorization checks.
 */
public class RequestContext {

	private final AString callerDID;
	private final boolean internal;

	/**
	 * Context for internal operations (adapters, recovery, etc.) that bypass auth.
	 */
	public static final RequestContext INTERNAL = new RequestContext(null, true);

	/**
	 * Context for anonymous (unauthenticated) external requests.
	 */
	public static final RequestContext ANONYMOUS = new RequestContext(null, false);

	private RequestContext(AString callerDID, boolean internal) {
		this.callerDID = callerDID;
		this.internal = internal;
	}

	/**
	 * Creates a RequestContext for an external request with the given caller DID.
	 * Returns ANONYMOUS if callerDID is null.
	 */
	public static RequestContext of(AString callerDID) {
		if (callerDID == null) return ANONYMOUS;
		return new RequestContext(callerDID, false);
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

	@Override
	public String toString() {
		if (internal) return "RequestContext[INTERNAL]";
		if (callerDID == null) return "RequestContext[ANONYMOUS]";
		return "RequestContext[" + callerDID + "]";
	}
}
