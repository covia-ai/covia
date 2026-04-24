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
	private final Blob sessionId;
	private final Blob taskId;

	/**
	 * Context for internal operations (adapters, recovery, etc.) that bypass auth.
	 */
	public static final RequestContext INTERNAL = new RequestContext(null, true, null, null, null, null, null, null);

	/**
	 * Context for anonymous (unauthenticated) external requests.
	 */
	public static final RequestContext ANONYMOUS = new RequestContext(null, false, null, null, null, null, null, null);

	private RequestContext(AString callerDID, boolean internal, AVector<ACell> proofs, AVector<ACell> caps,
			AString agentId, Blob jobId, Blob sessionId, Blob taskId) {
		this.callerDID = callerDID;
		this.internal = internal;
		this.proofs = proofs;
		this.caps = caps;
		this.agentId = agentId;
		this.jobId = jobId;
		this.sessionId = sessionId;
		this.taskId = taskId;
	}

	/**
	 * Creates a RequestContext for an external request with the given caller DID.
	 * Returns ANONYMOUS if callerDID is null.
	 */
	public static RequestContext of(AString callerDID) {
		if (callerDID == null) return ANONYMOUS;
		return new RequestContext(callerDID, false, null, null, null, null, null, null);
	}

	/**
	 * Creates a RequestContext with caller DID and UCAN proofs.
	 */
	public static RequestContext of(AString callerDID, AVector<ACell> proofs) {
		if (callerDID == null) return ANONYMOUS;
		return new RequestContext(callerDID, false, proofs, null, null, null, null, null);
	}

	/**
	 * Creates a RequestContext for scheduler-initiated wakes. Carries the
	 * agent owner's DID for access control (so the wake runs against the
	 * agent's own namespace) but is marked internal — auth and rate-limit
	 * checks are bypassed because the scheduler is the venue itself.
	 * Falls back to {@link #INTERNAL} if userDid is null.
	 */
	public static RequestContext scheduler(AString userDid) {
		if (userDid == null) return INTERNAL;
		return new RequestContext(userDid, true, null, null, null, null, null, null);
	}

	/**
	 * Returns a new context with the given UCAN proofs attached.
	 *
	 * <p><b>Trust contract:</b> the caller is responsible for ensuring that
	 * every token in {@code proofs} has already been cryptographically
	 * verified (signature, expiry at the time of verification, proof chain
	 * integrity). Downstream authorisation code trusts this invariant and
	 * will <em>not</em> re-verify signatures — it only re-checks temporal
	 * bounds and policy (audience, issuer, attenuation) at use time.</p>
	 *
	 * <p>The canonical way to obtain a verified proof vector is via
	 * {@code UCANValidator.parseTransportUCANs(...)}, which is the single
	 * trust boundary for inbound transport tokens. Tests that fabricate
	 * CAD3-signed tokens directly are implicitly trusted by construction.</p>
	 */
	public RequestContext withProofs(AVector<ACell> proofs) {
		return new RequestContext(this.callerDID, this.internal, proofs, this.caps, this.agentId, this.jobId, this.sessionId, this.taskId);
	}

	/**
	 * Returns a new context with capability attenuations. Operations are checked
	 * against these caps before execution. Null = unrestricted.
	 */
	public RequestContext withCaps(AVector<ACell> caps) {
		return new RequestContext(this.callerDID, this.internal, this.proofs, caps, this.agentId, this.jobId, this.sessionId, this.taskId);
	}

	/**
	 * Returns a new context with agent scope. When set, the {@code n/} path prefix
	 * resolves to the agent's private workspace at {@code g/{agentId}/n/}.
	 */
	public RequestContext withAgentId(AString agentId) {
		return new RequestContext(this.callerDID, this.internal, this.proofs, this.caps, agentId, this.jobId, this.sessionId, this.taskId);
	}

	/**
	 * Returns a new context with job scope. When set, the {@code t/} path prefix
	 * resolves to the job's temp field at {@code j/{jobId}/temp/} (legacy
	 * goal-tree behaviour) unless an agent + task scope is also set, in which
	 * case the agent/task path takes precedence.
	 */
	public RequestContext withJobId(Blob jobId) {
		return new RequestContext(this.callerDID, this.internal, this.proofs, this.caps, this.agentId, jobId, this.sessionId, this.taskId);
	}

	/**
	 * Returns a new context with session scope. When set together with an
	 * {@code agentId}, the {@code c/} path prefix resolves to the session's
	 * conversation-scoped slot at {@code g/{agentId}/sessions/{sessionId}/c/}.
	 */
	public RequestContext withSessionId(Blob sessionId) {
		return new RequestContext(this.callerDID, this.internal, this.proofs, this.caps, this.agentId, this.jobId, sessionId, this.taskId);
	}

	/**
	 * Returns a new context with task scope. When set together with an
	 * {@code agentId}, the {@code t/} path prefix resolves to the task's
	 * private slot at {@code g/{agentId}/tasks/{taskId}/t/}.
	 */
	public RequestContext withTaskId(Blob taskId) {
		return new RequestContext(this.callerDID, this.internal, this.proofs, this.caps, this.agentId, this.jobId, this.sessionId, taskId);
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
	 * When set, the {@code t/} path prefix resolves to the job's temp field
	 * (unless an agent + task scope is also set, which takes precedence).
	 */
	public Blob getJobId() {
		return jobId;
	}

	/**
	 * Gets the session ID for session-scoped operations, or null if not in
	 * session scope. Used together with {@link #getAgentId()} to resolve the
	 * {@code c/} prefix.
	 */
	public Blob getSessionId() {
		return sessionId;
	}

	/**
	 * Gets the task ID for task-scoped operations, or null if not in task
	 * scope. Used together with {@link #getAgentId()} to resolve the
	 * {@code t/} prefix to a per-task slot.
	 */
	public Blob getTaskId() {
		return taskId;
	}

	@Override
	public String toString() {
		if (internal) return "RequestContext[INTERNAL]";
		if (callerDID == null) return "RequestContext[ANONYMOUS]";
		String s = "RequestContext[" + callerDID;
		if (agentId != null) s += " agent=" + agentId;
		if (sessionId != null) s += " session=" + sessionId;
		if (taskId != null) s += " task=" + taskId;
		if (jobId != null) s += " job=" + jobId;
		return s + "]";
	}
}
