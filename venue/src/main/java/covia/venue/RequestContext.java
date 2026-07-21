package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Strings;
import covia.exception.AuthException;
import covia.grid.Authority;
import covia.lattice.CapabilityChecker;
import covia.lattice.CapabilityGate;

/**
 * Represents the context of an API request. It <b>wraps an {@link Authority}</b>
 * — the caller's identity plus the grants it holds (its capability scope and any
 * presented UCAN proofs) — and adds venue-local, per-execution state (execution
 * scopes, job/session/task ids, the operation reference and invocation input the
 * capability check consults).
 *
 * <p>Authorisation lives on the {@code Authority}: an action is allowed iff a
 * grant covers it — <em>either you have the right or you don't</em>. A
 * {@code null} capability scope ({@link #getCaps()}) is an unrestricted
 * principal (the fast path), not a ceiling; presented proofs are additive.</p>
 */
public class RequestContext {

	/** The caller's identity and grants. Never null (ANONYMOUS wraps
	 *  {@link Authority#ANONYMOUS}). */
	private final Authority authority;

	private final AString agentId;
	private final Blob jobId;
	private final Blob sessionId;
	private final Blob taskId;
	/** The raw transport UCAN tokens (JWT strings) as presented, relayable on
	 *  cross-venue hops. Parsed proofs cannot be re-signed (a JWT signature covers
	 *  the JWT bytes), so forwarding requires the originals. */
	private final AVector<ACell> rawUcans;
	/** Cancellation signal for the transition cycle this context drives, or
	 *  null outside a run-loop cycle. Flipped by the framework alongside
	 *  {@code transitionFuture.cancel(true)} — cancelling the future does NOT
	 *  stop the running transition thread, so long-running transition
	 *  adapters poll this to stop work (and lattice writes) promptly. */
	private final java.util.concurrent.atomic.AtomicBoolean cancellation;
	/** The operation reference being invoked, in the form the caller supplied
	 *  (e.g. the catalog path {@code "v/ops/langchain/openai"}), or null when
	 *  the invocation was made directly from resolved metadata. Set by the
	 *  JobManager ref-form dispatch overloads; consumed by
	 *  {@code AAdapter.requireInvoke} so an {@code invoke} capability can be
	 *  scoped to specific operations (#211). */
	private final AString op;
	/** The input of the invocation this context executes, set by the JobManager
	 *  dispatch alongside {@link #gate} — capability gates rule on it (#216). */
	private final ACell invocationInput;
	/** Gate evaluator for {@code nb.gate}-caveated capability grants, installed
	 *  by the dispatch layer. Null → gated grants cannot authorise (fail-closed);
	 *  ungated grants are unaffected (#216). */
	private final CapabilityGate gate;

	/**
	 * Context for anonymous (unauthenticated) external requests.
	 */
	public static final RequestContext ANONYMOUS =
		new RequestContext(Authority.ANONYMOUS, null, null, null, null, null, null, null, null, null);

	private RequestContext(Authority authority, AString agentId, Blob jobId, Blob sessionId,
			Blob taskId, AVector<ACell> rawUcans, AString op,
			java.util.concurrent.atomic.AtomicBoolean cancellation,
			ACell invocationInput, CapabilityGate gate) {
		this.authority = (authority != null) ? authority : Authority.ANONYMOUS;
		this.agentId = agentId;
		this.jobId = jobId;
		this.sessionId = sessionId;
		this.taskId = taskId;
		this.rawUcans = rawUcans;
		this.op = op;
		this.cancellation = cancellation;
		this.invocationInput = invocationInput;
		this.gate = gate;
	}

	/**
	 * Creates a RequestContext for an external request with the given caller DID.
	 * Returns ANONYMOUS if callerDID is null.
	 *
	 * <p>Framework code that needs the venue itself as the caller (engine
	 * startup, materialisation, recovery) uses
	 * {@link covia.venue.Engine#venueContext()}, which returns a context
	 * bound to the venue's own DID with an unrestricted ({@code null})
	 * scope. Trust is a property of the context's authority, not the call
	 * path: both {@code JobManager.invokeOperation} and {@code invokeInternal}
	 * enforce whatever scope the context carries.</p>
	 */
	public static RequestContext of(AString callerDID) {
		if (callerDID == null) return ANONYMOUS;
		return new RequestContext(Authority.of(callerDID), null, null, null, null, null, null, null, null, null);
	}

	/**
	 * Creates a RequestContext with caller DID and UCAN proofs.
	 */
	public static RequestContext of(AString callerDID, AVector<ACell> proofs) {
		if (callerDID == null) return ANONYMOUS;
		return new RequestContext(Authority.of(callerDID).withProofs(proofs), null, null, null, null, null, null, null, null, null);
	}

	/**
	 * Creates a RequestContext from an {@link Authority} — the caller's identity
	 * plus the grants (capability scope and presented proofs) it carries. Returns
	 * ANONYMOUS for a null or anonymous authority.
	 */
	public static RequestContext ofAuthority(Authority authority) {
		if (authority == null || authority.isAnonymous()) return ANONYMOUS;
		return new RequestContext(authority, null, null, null, null, null, null, null, null, null);
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
		return new RequestContext(authority.withProofs(proofs), agentId, jobId, sessionId, taskId, rawUcans, op, cancellation, invocationInput, gate);
	}

	/**
	 * Returns a new context whose capability scope is replaced. Every operation
	 * executed under this context is checked against that scope at the point it
	 * runs — by the executing adapter ({@link #requireCapability}) and by the
	 * {@link covia.venue.JobManager} dispatch safety net, on both the
	 * {@code invokeOperation} and {@code invokeInternal} paths. The scope
	 * composes downward into sub-operations. {@code null} = unrestricted (an
	 * action is allowed iff a grant covers it, and there are no bounding grants).
	 */
	public RequestContext withCaps(AVector<ACell> caps) {
		return new RequestContext(authority.withGrantScope(caps), agentId, jobId, sessionId, taskId, rawUcans, op, cancellation, invocationInput, gate);
	}

	/**
	 * Returns a new context with agent scope. When set, the {@code n/} path prefix
	 * resolves to the agent's private workspace at {@code g/{agentId}/n/}.
	 */
	public RequestContext withAgentId(AString agentId) {
		return new RequestContext(authority, agentId, jobId, sessionId, taskId, rawUcans, op, cancellation, invocationInput, gate);
	}

	/**
	 * Returns a new context with job scope. When set, the {@code t/} path prefix
	 * resolves to the job's temp field at {@code j/{jobId}/temp/} (legacy
	 * goal-tree behaviour) unless an agent + task scope is also set, in which
	 * case the agent/task path takes precedence.
	 */
	public RequestContext withJobId(Blob jobId) {
		return new RequestContext(authority, agentId, jobId, sessionId, taskId, rawUcans, op, cancellation, invocationInput, gate);
	}

	/**
	 * Returns a new context with session scope. When set together with an
	 * {@code agentId}, the {@code c/} path prefix resolves to the session's
	 * conversation-scoped slot at {@code g/{agentId}/sessions/{sessionId}/c/}.
	 */
	public RequestContext withSessionId(Blob sessionId) {
		return new RequestContext(authority, agentId, jobId, sessionId, taskId, rawUcans, op, cancellation, invocationInput, gate);
	}

	/**
	 * Returns a new context with task scope. When set together with an
	 * {@code agentId}, the {@code t/} path prefix resolves to the task's
	 * private slot at {@code g/{agentId}/tasks/{taskId}/t/}.
	 */
	public RequestContext withTaskId(Blob taskId) {
		return new RequestContext(authority, agentId, jobId, sessionId, taskId, rawUcans, op, cancellation, invocationInput, gate);
	}

	/**
	 * Returns a new context scoped to the operation reference being invoked, in
	 * the form the caller supplied (e.g. {@code "v/ops/langchain/openai"}). Set
	 * by the JobManager ref-form dispatch overloads — the only point where the
	 * human-authored reference still exists (the resolved metadata does not
	 * carry its own catalog path). Consumed by {@code AAdapter.requireInvoke}
	 * so an {@code invoke} capability can be scoped to specific operations
	 * (#211): {@code {"with": "v/ops/getmine", "can": "invoke"}}.
	 */
	public RequestContext withOp(AString op) {
		return new RequestContext(authority, agentId, jobId, sessionId, taskId, rawUcans, op, cancellation, invocationInput, gate);
	}

	/**
	 * Gets the operation reference being invoked as supplied by the caller, or
	 * null when the invocation was made directly from resolved metadata (in
	 * which case invoke-gating falls back to a resource-less check, coverable
	 * only by a wildcard {@code invoke} grant).
	 */
	public AString getOp() {
		return op;
	}

	/**
	 * Returns a new context carrying the invocation's input and a gate
	 * evaluator (#216). Set by the JobManager dispatch methods — the one
	 * place that has the engine, the operation reference and the invocation
	 * input together — so {@code nb.gate}-caveated grants can be evaluated
	 * against the exact invocation being authorised. Contexts without an
	 * evaluator treat gated grants as unable to authorise (fail-closed).
	 */
	public RequestContext withInvocation(ACell invocationInput, CapabilityGate gate) {
		return new RequestContext(authority, agentId, jobId, sessionId, taskId, rawUcans, op, cancellation, invocationInput, gate);
	}

	/**
	 * Returns a new context carrying a cancellation signal for the transition
	 * cycle it drives. Set by the run loop before dispatching a transition;
	 * flipped alongside {@code transitionFuture.cancel(true)}, which does not
	 * stop the running transition thread by itself.
	 */
	public RequestContext withCancellation(java.util.concurrent.atomic.AtomicBoolean cancellation) {
		return new RequestContext(authority, agentId, jobId, sessionId, taskId, rawUcans, op, cancellation, invocationInput, gate);
	}

	/**
	 * Gets the transition-cycle cancellation signal, or null when this context
	 * does not drive a run-loop cycle. Long-running transition adapters poll
	 * this to stop work promptly after a suspend/delete.
	 */
	public java.util.concurrent.atomic.AtomicBoolean getCancellation() {
		return cancellation;
	}

	/**
	 * The caller's {@link Authority} — identity plus the grants it holds — as a
	 * single immutable value. This context <em>wraps</em> it; the credential
	 * getters below ({@link #getCallerDID()}, {@link #getCaps()},
	 * {@link #getProofs()}) read through to it.
	 */
	public Authority getAuthority() {
		return authority;
	}

	/**
	 * Gets the caller's DID, or null if anonymous.
	 */
	public AString getCallerDID() {
		return authority.getDID();
	}

	/**
	 * Returns true if this is an anonymous (unauthenticated) request.
	 */
	public boolean isAnonymous() {
		return authority.isAnonymous();
	}

	/**
	 * Returns true if a caller DID is present.
	 */
	public boolean isAuthenticated() {
		return !authority.isAnonymous();
	}

	/**
	 * Gets the UCAN proofs attached to this request, or null if none.
	 */
	public AVector<ACell> getProofs() {
		return authority.getProofs();
	}

	/**
	 * Returns a new context carrying the raw transport UCAN tokens (JWT strings)
	 * as originally presented. These are the relayable form for cross-venue
	 * forwarding — the parsed {@link #getProofs() proofs} cannot be re-signed.
	 */
	public RequestContext withRawUcans(AVector<ACell> rawUcans) {
		return new RequestContext(authority, agentId, jobId, sessionId, taskId, rawUcans, op, cancellation, invocationInput, gate);
	}

	/**
	 * Gets the raw transport UCAN tokens (JWT strings) as presented, or null.
	 * Self-verifying — safe to relay on cross-venue hops (the receiving venue
	 * verifies them itself; a relay can neither forge nor widen them).
	 */
	public AVector<ACell> getRawUcans() {
		return rawUcans;
	}

	/**
	 * Gets the caller's held capability scope, or null if unrestricted. When
	 * non-null, an operation is authorised only if a grant in the scope covers
	 * it (there is no ceiling — a null scope simply has no bounding grants).
	 */
	public AVector<ACell> getCaps() {
		return authority.getGrants();
	}

	/**
	 * Enforces a capability at the point an operation executes. The executing
	 * adapter names the <em>exact</em> resource it acts on and the <em>exact</em>
	 * ability it requires, so the enforced capability is pinned to the
	 * implementation and cannot drift from a name-keyed mapping. An unrestricted
	 * scope ({@code null} {@link #getCaps()}) is a no-op for internal and
	 * authenticated callers that carry no bounding grants; only a caller with an
	 * explicit scope (e.g. the public read-only profile) is gated.
	 *
	 * <p>Lattice resources and abilities are {@link AString}s, so this is the
	 * primary form; see {@link #requireCapability(String, String)} for a
	 * literal-argument convenience overload.</p>
	 *
	 * @param resource the exact resource acted on (bare lattice path, DID URL,
	 *                 or scheme URI); may be null for "no specific resource"
	 * @param ability  the exact ability required (e.g.
	 *                 {@link convex.auth.ucan.Capability#CRUD_WRITE})
	 * @throws AuthException if no grant covers {@code (resource, ability)}
	 */
	public void requireCapability(AString resource, AString ability) {
		String denial = grantsDenial(resource, ability);
		if (denial != null) throw new AuthException(denial);
	}

	/**
	 * The grant check against this context's own authority: the denial message if
	 * the caller's capability scope does <em>not</em> cover {@code (resource,
	 * ability)}, or {@code null} if a grant covers it (an unrestricted, {@code
	 * null} scope always returns null). Package-visible so the {@code Engine}
	 * authority seam ({@code authorityCovers}) can compose it with the cross-user
	 * proof check without re-plumbing {@code op}/{@code invocationInput}/{@code
	 * gate}.
	 */
	String grantsDenial(AString resource, AString ability) {
		return CapabilityChecker.allows(authority.getGrants(), resource, ability, authority.getDID(), op, invocationInput, gate);
	}

	/**
	 * {@link String}-literal convenience overload of
	 * {@link #requireCapability(AString, AString)} — interns the arguments and
	 * delegates. Use when the resource or ability is a Java string literal
	 * (e.g. {@code "s/" + name}, {@code "secret/write"}).
	 */
	public void requireCapability(String resource, String ability) {
		requireCapability(resource != null ? Strings.create(resource) : null,
			ability != null ? Strings.create(ability) : null);
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
		AString callerDID = authority.getDID();
		if (callerDID == null) return "RequestContext[ANONYMOUS]";
		String s = "RequestContext[" + callerDID;
		if (agentId != null) s += " agent=" + agentId;
		if (sessionId != null) s += " session=" + sessionId;
		if (taskId != null) s += " task=" + taskId;
		if (jobId != null) s += " job=" + jobId;
		return s + "]";
	}
}
