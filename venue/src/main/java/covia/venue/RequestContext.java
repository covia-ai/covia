package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Vectors;

/**
 * Represents the context of an API request, carrying caller identity,
 * UCAN proofs, and request-scoped metadata.
 */
public class RequestContext {

	private final AString callerDID;
	private final boolean internal;
	private final AVector<ACell> proofs;
	private final AVector<ACell> caps;
	private final AString agentId;
	private final Blob jobId;

	/**
	 * Context for internal operations (adapters, recovery, etc.) that bypass auth.
	 */
	public static final RequestContext INTERNAL = new RequestContext(null, true, null, null, null, null);

	/**
	 * Context for anonymous (unauthenticated) external requests.
	 */
	public static final RequestContext ANONYMOUS = new RequestContext(null, false, null, null, null, null);

	private RequestContext(AString callerDID, boolean internal, AVector<ACell> proofs, AVector<ACell> caps, AString agentId, Blob jobId) {
		this.callerDID = callerDID;
		this.internal = internal;
		this.proofs = proofs;
		this.caps = caps;
		this.agentId = agentId;
		this.jobId = jobId;
	}

	/**
	 * Creates a RequestContext for an external request with the given caller DID.
	 * Returns ANONYMOUS if callerDID is null.
	 */
	public static RequestContext of(AString callerDID) {
		if (callerDID == null) return ANONYMOUS;
		return new RequestContext(callerDID, false, null, null, null, null);
	}

	/**
	 * Creates a RequestContext with caller DID and UCAN proofs.
	 */
	public static RequestContext of(AString callerDID, AVector<ACell> proofs) {
		if (callerDID == null) return ANONYMOUS;
		return new RequestContext(callerDID, false, proofs, null, null, null);
	}

	/**
	 * Returns a new context with the given proofs added.
	 */
	public RequestContext withProofs(AVector<ACell> proofs) {
		return new RequestContext(this.callerDID, this.internal, proofs, this.caps, this.agentId, this.jobId);
	}

	/**
	 * Returns a new context with capability attenuations. Operations are checked
	 * against these caps before execution. Null = unrestricted.
	 */
	public RequestContext withCaps(AVector<ACell> caps) {
		return new RequestContext(this.callerDID, this.internal, this.proofs, caps, this.agentId, this.jobId);
	}

	/**
	 * Returns a new context with agent scope. When set, the {@code n/} path prefix
	 * resolves to the agent's private workspace at {@code g/{agentId}/n/}.
	 */
	public RequestContext withAgentId(AString agentId) {
		return new RequestContext(this.callerDID, this.internal, this.proofs, this.caps, agentId, this.jobId);
	}

	/**
	 * Returns a new context with job scope. When set, the {@code t/} path prefix
	 * resolves to the job's temp field at {@code j/{jobId}/temp/}.
	 */
	public RequestContext withJobId(Blob jobId) {
		return new RequestContext(this.callerDID, this.internal, this.proofs, this.caps, this.agentId, jobId);
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

	/**
	 * Gets the capability attenuations for this request, or null if unrestricted.
	 * When non-null, operations must be covered by at least one capability.
	 */
	public AVector<ACell> getCaps() {
		return caps;
	}

	/**
	 * Gets the agent ID for agent-scoped operations, or null if not in agent scope.
	 * When set, the {@code n/} path prefix resolves to the agent's private workspace.
	 */
	public AString getAgentId() {
		return agentId;
	}

	/**
	 * Gets the job ID for job-scoped operations, or null if not in job scope.
	 * When set, the {@code t/} path prefix resolves to the job's temp field.
	 */
	public Blob getJobId() {
		return jobId;
	}

	@Override
	public String toString() {
		if (internal) return "RequestContext[INTERNAL]";
		if (callerDID == null) return "RequestContext[ANONYMOUS]";
		String s = "RequestContext[" + callerDID;
		if (agentId != null) s += " agent=" + agentId;
		if (jobId != null) s += " job=" + jobId;
		return s + "]";
	}
}
