package covia.venue;

import convex.auth.ucan.Capability;
import convex.auth.ucan.RootAuthorityPolicy;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Vectors;
import convex.core.data.Strings;
import covia.exception.AuthException;
import covia.exception.CapabilityDeniedException;
import covia.grid.Authority;
import covia.grid.Job;
import covia.grid.Principals;
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
 * principal (the fast path) with no bounding grants; nothing subtracts, and
 * presented proofs are additive.</p>
 *
 * <p>A context names one acting principal, whose authority it acts with, in
 * the namespace it acts in. An agent whose controllers lead out of its
 * account acts with the first such controller's authority narrowed by its
 * caps: the venue works that out from the agents' records at each check
 * ({@link Engine#authorityFrame}), on names resolved here
 * ({@link #inNamespace}).</p>
 */
public class RequestContext {

	/** The caller's identity and grants. Never null (ANONYMOUS wraps
	 *  {@link Authority#ANONYMOUS}). */
	private final Authority authority;

	/** Trusted local intake participant. Called on a venue draft, possibly repeatedly;
	 * no I/O or live writes. False discards duplicate intake. Never deserialised. */
	@FunctionalInterface
	public interface SessionDelivery {
		boolean accept(VenueState draft, Blob sessionId, Blob jobId);
	}
	private final SessionDelivery sessionDelivery;
	public SessionDelivery getSessionDelivery() { return sessionDelivery; }
	public RequestContext withSessionDelivery(SessionDelivery delivery) {
		return new RequestContext(authority, executionScope, rawUcans, op, cancellation,
			invocationInput, gate, gateEvaluation, job, cycle, delivery, carried, origin, caller, scopeSource);
	}

	/** The request's origin: the principal it authenticated as where it
	 * entered the venue. That is the caller a door authenticated, or the issuer
	 * of the access token it presented ({@link Engine#requestContext}), or the
	 * principal an in-process caller made it for ({@link #of(AString)}). Every
	 * context made for the request keeps it, a call into another principal's
	 * authority included, so it names who is present wherever the request
	 * goes. A context the venue makes for a principal itself
	 * ({@link Engine#context(AString)}) has none: work it resumes or fires
	 * later from what a job or event kept, its own work, and a message a
	 * channel binding brings, whose record is attribution, not evidence
	 * ({@link Engine#channelContext}). Neither does a gate's evaluation. Acts
	 * only the user may do in person read it ({@link Engine#inPersonDenial}).
	 * It grants and refuses nothing else (AUTHORITY.md). */
	private final AString origin;

	/** The request's origin, or null for work the venue resumes or fires itself ({@link #origin}). */
	public AString getOrigin() { return origin; }

	/** The authority of the frame that called into this one: whoever made a
	 * callable's call, for the call ({@link #calledFrom}). Null at the top
	 * level, and every context made within the frame keeps it. It grants and
	 * refuses nothing (AUTHORITY.md). */
	private final AString caller;

	/** The authority of the frame that called into this one, or null at the top level ({@link #caller}). */
	public AString getCaller() { return caller; }

	/** This context with no origin: work the venue makes or carries on itself,
	 * which is never anyone present in person. */
	RequestContext withoutOrigin() {
		if (origin == null) return this;
		return new RequestContext(authority, executionScope, rawUcans, op, cancellation,
			invocationInput, gate, gateEvaluation, job, cycle, sessionDelivery, carried, null, caller, scopeSource);
	}

	/** This context with the principal that acts in it as its origin: a
	 * request that a door of the venue authenticated as that principal
	 * ({@link Engine#requestContext}). */
	RequestContext asOrigin() {
		AString did = authority.getDID();
		if (did == null || did.equals(origin)) return this;
		return new RequestContext(authority, executionScope, rawUcans, op, cancellation,
			invocationInput, gate, gateEvaluation, job, cycle, sessionDelivery, carried, did, caller, scopeSource);
	}

	/**
	 * What a request carries for attribution, never for authority: how a channel
	 * binding authenticated its caller ({@link #getAuthentication}) and its route
	 * ({@link #getRoute}). Assurance policy and access checks never read it, and
	 * contexts the venue starts for an agent's own work carry none.
	 */
	private record Carried(AMap<AString, ACell> authentication, AVector<ACell> route) {
		/** The pair, or null when it holds nothing. */
		static Carried of(AMap<AString, ACell> authentication, AVector<ACell> route) {
			return (authentication == null && route == null) ? null : new Carried(authentication, route);
		}
	}
	private final Carried carried;

	/** How a channel binding authenticated this request's caller: the method's
	 * {@code AuthResult} projected to data, without its {@code userDID} (the
	 * caller), or null. Set only by {@link Engine#channelContext}; a binding's
	 * media and reply operations receive it. */
	public AMap<AString, ACell> getAuthentication() { return (carried == null) ? null : carried.authentication(); }
	RequestContext withAuthentication(AMap<AString, ACell> authentication) {
		return new RequestContext(authority, executionScope, rawUcans, op, cancellation,
			invocationInput, gate, gateEvaluation, job, cycle, sessionDelivery, Carried.of(authentication, getRoute()), origin, caller, scopeSource);
	}

	/** Most entries a route holds; past it, steps after the first {@link #ROUTE_HEAD} fold into one {@link #ROUTE_ELIDED} entry. */
	public static final int ROUTE_MAX = 8;
	/** Steps at the start of a route that are never folded: where the request entered. */
	public static final int ROUTE_HEAD = 2;
	/** The key of a route entry standing for steps folded out of its middle; its value is their count. */
	public static final AString ROUTE_ELIDED = Strings.intern("elided");

	/**
	 * What carried this request to here, one step per carrier in the order they
	 * carried it, or null when nothing did: the request came direct. A step is
	 * the carrying adapter's own record, naming that adapter as {@code adapter}.
	 * Attribution only; its shape is venue-internal (AGENT_CONTEXT.md §1.2).
	 */
	public AVector<ACell> getRoute() { return (carried == null) ? null : carried.route(); }

	/**
	 * This request carried one step further, by adapter {@code adapter}:
	 * {@code step} is the adapter's own record of how it carried the request,
	 * just enough to find its information again, never a copy of it, with whom
	 * it carried the request for where the adapter knows (a binding's sender, a
	 * callable's caller), and is stamped with the adapter's name. A carrier adds its step where the request
	 * reaches it, never when work it left is rebuilt later. A route longer than
	 * {@link #ROUTE_MAX} keeps its first {@link #ROUTE_HEAD} steps and its latest,
	 * folding those between into one {@link #ROUTE_ELIDED} entry.
	 */
	public RequestContext via(AString adapter, AMap<AString, ACell> step) {
		java.util.Objects.requireNonNull(adapter, "adapter");
		java.util.Objects.requireNonNull(step, "step");
		AVector<ACell> route = appended(getRoute(), step.assoc(covia.api.Fields.ADAPTER, adapter));
		return new RequestContext(authority, executionScope, rawUcans, op, cancellation,
			invocationInput, gate, gateEvaluation, job, cycle, sessionDelivery, Carried.of(getAuthentication(), route), origin, caller, scopeSource);
	}

	/** {@code route} with {@code step} added, folded to {@link #ROUTE_MAX} entries. */
	private static AVector<ACell> appended(AVector<ACell> route, ACell step) {
		AVector<ACell> longer = (route == null) ? Vectors.of(step) : route.conj(step);
		if (longer.count() <= ROUTE_MAX) return longer;
		long folded = foldedCount(longer.get(ROUTE_HEAD)) + foldedCount(longer.get(ROUTE_HEAD + 1));
		return longer.subVector(0, ROUTE_HEAD)
			.conj(convex.core.data.Maps.of(ROUTE_ELIDED, convex.core.data.prim.CVMLong.create(folded)))
			.concat(longer.subVector(ROUTE_HEAD + 2, longer.count() - ROUTE_HEAD - 2));
	}

	/** How many steps a route entry stands for: an elided entry its count, a step one. */
	private static long foldedCount(ACell entry) {
		return (entry instanceof AMap<?, ?> map && map.get(ROUTE_ELIDED) instanceof convex.core.data.prim.CVMLong n)
			? n.longValue() : 1;
	}

	/** Execution references are always qualified by their owning user. Delegating
	 * a namespace never changes that ownership. Agent sessions use the owner,
	 * agent ID and session ID together; job/task IDs are user-relative.
	 * Task revision is -1 outside a task cycle and fences task completion. The
	 * claim is the run-loop cycle's ({@code AGENT_LOOP.md} §2.5.1), null
	 * outside one; it fences the cycle's own writes. */
	public record ExecutionScope(AString ownerDID, AString agentId, Blob jobId,
			Blob sessionId, Blob taskId, long taskRevision, ACell claim) {
		public ExecutionScope {
			if (ownerDID == null && (agentId != null || jobId != null || sessionId != null || taskId != null)) {
				throw new IllegalArgumentException(EXECUTION_OWNER_REQUIRED);
			}
		}
	}

	public static final String EXECUTION_OWNER_REQUIRED = "Execution references require an owning user";
	private final ExecutionScope executionScope;
	/** The Job wrapper for the operation currently executing under this context.
	 * It may be transient; {@link #getExecutionScope()} remains the durable/inherited temp scope. */
	private final Job job;
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
	/** The live-event handle of the run-loop cycle this context drives, or
	 *  null outside one. Set by the run loop beside {@link #cancellation};
	 *  the transition adapter emits its inference and tool activity through
	 *  it (#394), so activity is attributed to exactly the cycle that made
	 *  it — a transition invoked outside a run loop emits nothing. */
	private final AgentEvents.Cycle cycle;
	/** The operation reference being invoked, in the form the caller supplied
	 *  (e.g. the catalog path {@code "v/ops/langchain/openai"}), or null when
	 *  the invocation was made directly from resolved metadata. Set by the
	 *  JobManager ref-form dispatch overloads; consumed by
	 *  {@code Engine.requireInvoke} so an {@code invoke} capability can be
	 *  scoped to specific operations (#211). */
	private final AString op;
	/** The input of the invocation this context executes, set by the JobManager
	 *  dispatch alongside {@link #gate} — capability gates rule on it (#216). */
	private final ACell invocationInput;
	/** Gate evaluator for {@code nb.gate}-caveated capability grants, installed
	 *  by the dispatch layer. Null → gated grants cannot authorise (fail-closed);
	 *  ungated grants are unaffected (#216). */
	private final CapabilityGate gate;
	/** True only while executing a capability's policy gate. Prevents nested
	 * dispatch from arming gated grants recursively. */
	private final boolean gateEvaluation;

	/**
	 * Where a principal's capability scope is read at each point of action,
	 * rather than held as a copy: an agent's current {@code config.caps}
	 * ({@link Engine#context(AString, AString)}).
	 */
	@FunctionalInterface
	public interface ScopeSource {
		/**
		 * The scope as it stands now, for a check on {@code resource}; null for
		 * unrestricted. It includes what control of the agent that resource belongs
		 * to gives ({@link Engine#context(AString, AString)}), so a null resource
		 * asks for the scope alone.
		 */
		AVector<ACell> read(AString resource);
	}

	/** The source of this context's scope, or null when it holds its own
	 *  ({@link Authority#getGrants()}). Read only through {@link #scopeFor}. */
	private final ScopeSource scopeSource;

	/**
	 * Context for anonymous (unauthenticated) external requests.
	 */
	public static final RequestContext ANONYMOUS =
		new RequestContext(Authority.ANONYMOUS, null);

	private RequestContext(Authority authority, AString origin) {
		this(authority, new ExecutionScope(authority.getUserDID(), null, null, null, null, -1L, null),
			null, null, null, null, null, false, null, null, null, null, origin, null, null);
	}

	private RequestContext(Authority authority, ExecutionScope executionScope,
			AVector<ACell> rawUcans, AString op,
			java.util.concurrent.atomic.AtomicBoolean cancellation,
			ACell invocationInput, CapabilityGate gate, boolean gateEvaluation, Job job,
			AgentEvents.Cycle cycle, SessionDelivery sessionDelivery, Carried carried,
			AString origin, AString caller, ScopeSource scopeSource) {
		this.caller = caller;
		this.scopeSource = scopeSource;
		this.sessionDelivery = sessionDelivery;
		this.carried = carried;
		this.origin = origin;
		this.authority = authority;
		this.executionScope = executionScope;
		this.job = job;
		this.rawUcans = rawUcans;
		this.op = op;
		this.cancellation = cancellation;
		this.cycle = cycle;
		this.invocationInput = invocationInput;
		this.gate = gate;
		this.gateEvaluation = gateEvaluation;
	}

	/** The fully qualified execution scope, preserved across namespace delegation. */
	public ExecutionScope getExecutionScope() {
		return executionScope;
	}

	private boolean hasLocalExecutionScope() {
		return java.util.Objects.equals(getUserDID(), executionScope.ownerDID());
	}

	/** Binding a scope under a different user starts a new execution, never
	 * reinterprets the previous user's IDs. */
	private ExecutionScope localExecutionScope() {
		return hasLocalExecutionScope() ? executionScope
			: new ExecutionScope(getUserDID(), null, null, null, null, -1L, null);
	}

	private RequestContext withExecutionScope(ExecutionScope scope) {
		return new RequestContext(authority, scope, rawUcans, op, cancellation,
			invocationInput, gate, gateEvaluation, job, cycle, sessionDelivery, carried, origin, caller, scopeSource);
	}

	/**
	 * This context driving {@code scope}, whose owner may be an account other
	 * than this context's: an agent's harness acts in its controller's frame and
	 * drives its agent's execution ({@link Engine#harnessContext}). The getters
	 * of execution ids ({@link #getAgentId}, {@link #getSessionId},
	 * {@link #getTaskId}, {@link #getClaim}) show a scope only in its owner's
	 * account, so harness code reaches the agent's records through
	 * {@link #getExecutionScope}. Setting one id ({@link #withSessionId} and the
	 * rest) on a scope owned elsewhere starts a new execution in this account,
	 * so the harness sets its scope whole here.
	 */
	public RequestContext inExecution(ExecutionScope scope) {
		return withExecutionScope(java.util.Objects.requireNonNull(scope, "scope"));
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
	 *
	 * <p>The host making it vouches that {@code callerDID} is present: it is
	 * the context's origin ({@link #getOrigin()}).</p>
	 */
	public static RequestContext of(AString callerDID) {
		if (callerDID == null) return ANONYMOUS;
		return new RequestContext(Authority.of(callerDID), callerDID);
	}

	/**
	 * Creates a RequestContext for {@code callerDID} acting in the namespace of
	 * {@code userDID} under the given capability scope
	 * ({@link Authority#of(AString, AString, AVector)}). This rebuilds a stored
	 * context: one that persists records both DIDs, since neither can be
	 * recovered from the other. Deciding what a principal may do now is
	 * {@link covia.venue.Engine#context(AString, AString)}.
	 *
	 * @param callerDID who acted, or null for anonymous; it is the context's
	 *        origin, as in {@link #of(AString)}
	 * @param userDID the namespace it acted in; null or {@code callerDID} for its own
	 * @param caps the scope, or null for unrestricted
	 */
	public static RequestContext of(AString callerDID, AString userDID, AVector<ACell> caps) {
		if (callerDID == null) return ANONYMOUS;
		return new RequestContext(Authority.of(callerDID, userDID, caps), callerDID);
	}

	/**
	 * Creates a RequestContext for an <b>agent sub-principal</b> of
	 * {@code userDID}: the caller is the agent's own DID
	 * ({@code <userDID>:g:<agentId>}), while the namespace it acts within stays
	 * the owner's. Also sets the agent execution scope, so {@code n/} resolves to
	 * the agent's private workspace.
	 *
	 * <p>Used by the agent run loop, which knows the owner as an address
	 * component rather than as a caller — the waking caller's identity never
	 * reaches the loop (#91), and the agent's own identity now names it.</p>
	 */
	public static RequestContext ofAgent(AString userDID, AString agentId) {
		if (userDID == null || agentId == null) return ANONYMOUS;
		return new RequestContext(Authority.ofAgent(userDID, agentId), null).withAgentId(agentId);
	}

	/**
	 * Creates a RequestContext from an {@link Authority} — the caller's identity
	 * plus the grants (capability scope and presented proofs) it carries. Returns
	 * ANONYMOUS for a null or anonymous authority. The context's origin is the
	 * authority's DID, as in {@link #of(AString)}.
	 */
	public static RequestContext ofAuthority(Authority authority) {
		if (authority == null || authority.isAnonymous()) return ANONYMOUS;
		return new RequestContext(authority, authority.getDID());
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
	 * {@link UcanJwtValidator#parseTransportUCANs}, which is the single
	 * trust boundary for inbound transport tokens. Tests that fabricate
	 * CAD3-signed tokens directly are implicitly trusted by construction.</p>
	 */
	public RequestContext withProofs(AVector<ACell> proofs) {
		return new RequestContext(authority.withProofs(proofs), executionScope, rawUcans, op, cancellation, invocationInput, gate, gateEvaluation, job, cycle, sessionDelivery, carried, origin, caller, scopeSource);
	}

	/**
	 * This execution (its job, session, task, cancellation and invocation)
	 * carried on by {@code principal}'s authority instead of the caller's, its
	 * scope read as {@code principal}'s is. The caller's transport tokens,
	 * authentication record and route stay with the caller: the principal acts
	 * as itself. {@link Engine#stepContext} hands a run to an agent this way,
	 * and {@link Engine#authorityFrame} checks a request against its
	 * authority's frame this way.
	 */
	RequestContext actingAs(RequestContext principal) {
		return new RequestContext(principal.authority, executionScope, null, op, cancellation, invocationInput, gate, gateEvaluation, job, cycle, sessionDelivery, null, origin, caller, principal.scopeSource);
	}

	/**
	 * This frame, entered by a call from {@code invoker}: a callable's call
	 * (CALLABLES.md), where this context is the callable's issuer narrowed by
	 * the callable, or {@code admin/sudo}, where it is the user's whole
	 * authority. It acts with this frame's authority alone, in this frame's
	 * namespace and execution scope, and its caller is the invoker's authority
	 * ({@link #getCaller()}). It goes on with the invoker's job, as the parent
	 * of what it starts, its cancellation and the request's origin
	 * ({@link #getOrigin()}), so a call is never the frame's principal in
	 * person unless that principal made it. The call is
	 * still the invoker's request, so it keeps the invoker's route. The
	 * invoker's execution scope, transport grants, authentication record, agent
	 * cycle and session delivery stay with the invoker. Only
	 * {@link Engine#callContext} and {@link Engine#sudoContext} make one.
	 */
	RequestContext calledFrom(RequestContext invoker) {
		return new RequestContext(authority, executionScope, null, null, invoker.cancellation, null, null,
			invoker.gateEvaluation, invoker.job, null, null, Carried.of(null, invoker.getRoute()),
			invoker.origin, invoker.getAuthorityDID(), scopeSource);
	}

	/** This frame as entered by a call from {@code caller}, rebuilt from what work
	 * in it kept ({@link Engine#keptAuthority}): nobody is present in it. */
	RequestContext calledBy(AString caller) {
		return new RequestContext(authority, executionScope, rawUcans, op, cancellation, invocationInput, gate,
			gateEvaluation, job, cycle, sessionDelivery, carried, null, caller, scopeSource);
	}

	/**
	 * {@code resource} as this context's namespace names it, for a check against
	 * its authority's frame ({@link Engine#authorityFrame}): a bare path made
	 * absolute in this namespace. A DID
	 * URL, a scheme reference and the venue's {@code v/} view, which reads the
	 * same from every namespace, are returned as given.
	 */
	public AString inNamespace(AString resource) {
		AString user = getUserDID();
		if (resource == null || user == null) return resource;
		String bare = CapabilityChecker.canonicalResource(resource.toString(), null);
		if (bare.isEmpty() || bare.equals("v") || bare.startsWith("v/")) return resource;
		String resolved = CapabilityChecker.canonicalResource(resource.toString(), user);
		return resolved.equals(resource.toString()) ? resource : Strings.create(resolved);
	}

	/**
	 * This context with its scope read from {@code source} at each point of
	 * action rather than held. Only {@link Engine#context(AString, AString)}
	 * installs one, for an agent; its own grants are then empty, so nothing
	 * that reads them directly finds an unrestricted scope.
	 */
	RequestContext readingScope(ScopeSource source) {
		return new RequestContext(authority.withGrantScope(Vectors.empty()), executionScope, rawUcans, op, cancellation, invocationInput, gate, gateEvaluation, job, cycle, sessionDelivery, carried, origin, caller, source);
	}

	/**
	 * This context with the authority it acts with narrowed further to
	 * {@code bound}, read at each point of action. Bare paths in {@code bound}
	 * are {@code boundOwner}'s.
	 */
	RequestContext narrowedBy(ScopeSource bound, AString boundOwner) {
		AString owner = getUserDID();
		ScopeSource narrowed = resource -> CapabilityChecker.intersect(scopeFor(resource), owner, bound.read(resource), boundOwner);
		return readingScope(Narrowed.of(narrowed, getTokens(), getCallable()));
	}

	/**
	 * This context narrowed further by {@code narrower}'s scope, read at each
	 * point of action, and by the access tokens and callable that narrow
	 * {@code narrower}, which it then carries: the frame of the authority an
	 * agent acts with when its controllers lead out of its account
	 * ({@link Engine#authorityFrame}).
	 */
	RequestContext narrowedAs(RequestContext narrower) {
		AString owner = getUserDID();
		AString narrowerOwner = narrower.getUserDID();
		ScopeSource narrowed = resource -> CapabilityChecker.intersect(scopeFor(resource), owner,
			narrower.scopeFor(resource), narrowerOwner);
		AMap<AString, ACell> callable = (narrower.getCallable() != null) ? narrower.getCallable() : getCallable();
		return readingScope(Narrowed.of(narrowed, getTokens().concat(narrower.getTokens()), callable));
	}

	/**
	 * A scope narrowed by access tokens or a callable: what it reads, the tokens
	 * narrowing it, outermost first, and the callable whose caps narrow it, if
	 * any, each as its reference ({@code {owner, name, id}}). They go wherever
	 * the scope does, so work the request keeps or creates can record what
	 * narrowed it ({@link #getTokens}, {@link #getCallable}).
	 */
	private record Narrowed(ScopeSource scope, AVector<ACell> tokens, AMap<AString, ACell> callable) implements ScopeSource {
		/** {@code scope} carrying what narrowed it, or {@code scope} itself when nothing did. */
		static ScopeSource of(ScopeSource scope, AVector<ACell> tokens, AMap<AString, ACell> callable) {
			return (tokens.isEmpty() && callable == null) ? scope : new Narrowed(scope, tokens, callable);
		}

		@Override
		public AVector<ACell> read(AString resource) {
			return scope.read(resource);
		}
	}

	/**
	 * This context with the authority it acts with narrowed further by an MCP
	 * token: its caps, read at each point of action, whose bare paths are
	 * {@code capsOwner}'s. The token's reference joins {@link #getTokens}. Only
	 * the venue narrows a request by a token ({@link Engine#tokenContext}).
	 */
	RequestContext withToken(AMap<AString, ACell> reference, ScopeSource caps, AString capsOwner) {
		AString owner = getUserDID();
		ScopeSource narrowed = resource -> CapabilityChecker.intersect(scopeFor(resource), owner, caps.read(resource), capsOwner);
		return readingScope(Narrowed.of(narrowed, getTokens().conj(reference), getCallable()));
	}

	/**
	 * This context with the authority it acts with narrowed further by a
	 * callable: its caps, read at each point of action, whose bare paths are
	 * {@code capsOwner}'s. The callable's reference becomes
	 * {@link #getCallable}. Only the venue narrows a request by a callable
	 * ({@link Engine#callContext}).
	 */
	RequestContext withCallable(AMap<AString, ACell> reference, ScopeSource caps, AString capsOwner) {
		AString owner = getUserDID();
		ScopeSource narrowed = resource -> CapabilityChecker.intersect(scopeFor(resource), owner, caps.read(resource), capsOwner);
		return readingScope(Narrowed.of(narrowed, getTokens(), reference));
	}

	/**
	 * The access tokens narrowing the authority this request acts with, outermost
	 * first, each as its reference; empty when no token narrows it.
	 */
	public AVector<ACell> getTokens() {
		return (scopeSource instanceof Narrowed narrowed) ? narrowed.tokens() : Vectors.empty();
	}

	/**
	 * The callable whose call this request is, as its reference
	 * ({@code {owner, name, id}}): its caps narrow the authority the request
	 * acts with (CALLABLES.md). Null outside a call.
	 */
	public AMap<AString, ACell> getCallable() {
		return (scopeSource instanceof Narrowed narrowed) ? narrowed.callable() : null;
	}

	/**
	 * Whether an access token or a callable narrows the authority this request
	 * acts with. Such a request never acts with its namespace's own authority,
	 * for another user, or as the venue, whatever the caps allow.
	 */
	public boolean isNarrowed() {
		return !getTokens().isEmpty() || getCallable() != null;
	}

	/**
	 * This context with the authority it acts with narrowed to {@code caps}, an
	 * agent's caps: their bare paths name the account of the execution it drives
	 * and their {@code n/} that agent's notes ({@link #getExecutionScope}), which
	 * for an agent's harness is the agent's, whatever account its controller's
	 * frame is in. Null leaves it unchanged. A harness narrowed by its agent's
	 * caps sees what the agent may.
	 */
	public RequestContext narrowedTo(AVector<ACell> caps) {
		if (caps == null) return this;
		AVector<ACell> own = CapabilityChecker.inAgent(caps, executionScope.agentId());
		return narrowedBy(resource -> own, executionScope.ownerDID());
	}

	/**
	 * Returns a new context whose capability scope is replaced. Every operation
	 * executed under this context is checked against that scope at the adapter's
	 * point of action ({@link #requireCapability} / Engine's common gates), on
	 * both recorded and transient Job dispatch paths. The scope composes downward into
	 * sub-operations. {@code null} = unrestricted.
	 *
	 * <p>The scope replaces the caller's own authority's. To narrow what a
	 * context may do, use {@link #narrowedTo}.</p>
	 */
	public RequestContext withCaps(AVector<ACell> caps) {
		return new RequestContext(authority.withGrantScope(CapabilityChecker.inAgent(caps, getAgentId())), executionScope, rawUcans, op, cancellation, invocationInput, gate, gateEvaluation, job, cycle, sessionDelivery, carried, origin, caller, null);
	}

	/**
	 * Returns a new context with agent scope bound to the current user. When set, the {@code n/} path prefix
	 * resolves to the agent's private workspace at {@code g/{agentId}/n/}.
	 */
	public RequestContext withAgentId(AString agentId) {
		ExecutionScope scope = localExecutionScope();
		return withExecutionScope(new ExecutionScope(getUserDID(), agentId, scope.jobId(), scope.sessionId(), scope.taskId(), scope.taskRevision(), scope.claim()));
	}

	/**
	 * Returns a new context with job scope. When set, the {@code t/} path prefix
	 * resolves to the Job's temp field at {@code j/{jobId}/temp/}. When an
	 * agent task is also focused, its task id (which is the caller-facing Job
	 * id) takes precedence over an internal transition Job id.
	 */
	public RequestContext withJobId(Blob jobId) {
		ExecutionScope scope = localExecutionScope();
		return withExecutionScope(new ExecutionScope(getUserDID(), scope.agentId(), jobId, scope.sessionId(), scope.taskId(), scope.taskRevision(), scope.claim()));
	}

	/**
	 * Returns a new context carrying the Job wrapper for the operation currently
	 * executing. This is distinct from {@link #withJobId}: a transient Job has an
	 * execution identity but no durable {@code t/} namespace, while an inherited
	 * durable Job id may remain the temp scope of an internal sub-invocation.
	 */
	public RequestContext withJob(Job job) {
		return new RequestContext(authority, executionScope, rawUcans, op, cancellation, invocationInput, gate, gateEvaluation, job, cycle, sessionDelivery, carried, origin, caller, scopeSource);
	}

	/**
	 * Returns a new context with session scope. When set together with an
	 * {@code agentId}, the {@code c/} path prefix resolves to the session's
	 * conversation-scoped slot at {@code g/{agentId}/sessions/{sessionId}/c/}.
	 */
	public RequestContext withSessionId(Blob sessionId) {
		ExecutionScope scope = localExecutionScope();
		return withExecutionScope(new ExecutionScope(getUserDID(), scope.agentId(), scope.jobId(), sessionId, scope.taskId(), scope.taskRevision(), scope.claim()));
	}

	/**
	 * Returns a new context with task scope. When set together with an
	 * {@code agentId}, the {@code t/} path prefix resolves to the task Job's
	 * temp field at {@code j/{taskId}/temp/}. A task id is its
	 * {@code agent:request} Job id; the agent's task index is only a pending
	 * work queue.
	 */
	public RequestContext withTaskId(Blob taskId) {
		ExecutionScope scope = localExecutionScope();
		return withExecutionScope(new ExecutionScope(getUserDID(), scope.agentId(), scope.jobId(), scope.sessionId(), taskId, -1L, scope.claim()));
	}

	/** Returns a context focused on the task row revision presented this cycle. */
	public RequestContext withTaskId(Blob taskId, long taskRevision) {
		ExecutionScope scope = localExecutionScope();
		return withExecutionScope(new ExecutionScope(getUserDID(), scope.agentId(), scope.jobId(), scope.sessionId(), taskId, taskRevision, scope.claim()));
	}

	/**
	 * Returns a context carrying the claim of the run-loop cycle it drives
	 * ({@code AGENT_LOOP.md} §2.5.1). The cycle's own writes made under it,
	 * such as resolving its task, take effect only while the claim is current.
	 */
	public RequestContext withClaim(ACell claim) {
		ExecutionScope scope = localExecutionScope();
		return withExecutionScope(new ExecutionScope(getUserDID(), scope.agentId(), scope.jobId(), scope.sessionId(), scope.taskId(), scope.taskRevision(), claim));
	}

	/** The claim of the run-loop cycle this context drives, or null outside one. */
	public ACell getClaim() {
		return hasLocalExecutionScope() ? executionScope.claim() : null;
	}

	/**
	 * Returns a new context scoped to the operation reference being invoked, in
	 * the form the caller supplied (e.g. {@code "v/ops/langchain/openai"}). Set
	 * by the JobManager ref-form dispatch overloads — the only point where the
	 * human-authored reference still exists (the resolved metadata does not
	 * carry its own catalog path). Consumed by {@code Engine.requireInvoke}
	 * so an {@code invoke} capability can be scoped to specific operations
	 * (#211): {@code {"with": "v/ops/getmine", "can": "invoke"}}.
	 */
	public RequestContext withOp(AString op) {
		return new RequestContext(authority, executionScope, rawUcans, op, cancellation, invocationInput, gate, gateEvaluation, job, cycle, sessionDelivery, carried, origin, caller, scopeSource);
	}

	/**
	 * Gets the operation reference being invoked as supplied by the caller, or
	 * null when the call was made directly from resolved metadata. Direct calls
	 * enforce resource authority without an additional invoke check.
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
		return new RequestContext(authority, executionScope, rawUcans, op, cancellation, invocationInput, gate, gateEvaluation, job, cycle, sessionDelivery, carried, origin, caller, scopeSource);
	}

	/**
	 * Builds the constrained context used to execute an {@code nb.gate}
	 * operation. The gate may invoke its own definition and use the caller's
	 * ordinary ungated grants, but it does not receive an unrestricted scope or
	 * additive cross-user proofs. Gated grants remain fail-closed because nested
	 * dispatch does not install a gate evaluator while this flag is set. Called
	 * on the frame of the authority the request acts with
	 * ({@link Engine#authorityFrame}), which is what holds the gated grant.
	 */
	RequestContext forGateEvaluation(AString gateOp) {
		AVector<ACell> grants = scopeFor(null);
		if (grants == null) grants = Vectors.empty();
		if (gateOp != null) {
			grants = grants.conj(Capability.create(gateOp, covia.api.Abilities.INVOKE));
		}
		Authority gateAuthority = authority.withGrantScope(grants).withProofs(null);
		return new RequestContext(gateAuthority, executionScope,
			null, gateOp, cancellation, null, null, true, job, cycle, null, null, null, null, null);
	}

	/**
	 * This context running gate {@code gateOp} with the authority it acts with,
	 * unconstrained: a callable's gate, which runs with the callable's authority
	 * as a trust monitor runs as its actor (CALLABLES.md). As in
	 * {@link #forGateEvaluation}, gated grants stay fail-closed within it.
	 */
	RequestContext asGateEvaluation(AString gateOp) {
		return new RequestContext(authority, executionScope, null, gateOp, cancellation, null, null, true, job, null,
			null, null, null, null, scopeSource);
	}

	boolean isGateEvaluation() {
		return gateEvaluation;
	}

	/**
	 * Returns a new context carrying a cancellation signal for the transition
	 * cycle it drives. Set by the run loop before dispatching a transition;
	 * flipped alongside {@code transitionFuture.cancel(true)}, which does not
	 * stop the running transition thread by itself.
	 */
	public RequestContext withCancellation(java.util.concurrent.atomic.AtomicBoolean cancellation) {
		return new RequestContext(authority, executionScope, rawUcans, op, cancellation, invocationInput, gate, gateEvaluation, job, cycle, sessionDelivery, carried, origin, caller, scopeSource);
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
	 * Returns a new context carrying the live-event handle of the run-loop
	 * cycle it drives (#394). Set by the run loop beside the cancellation
	 * signal; the transition adapter opens its {@code CycleRecord} on it.
	 */
	public RequestContext withCycle(AgentEvents.Cycle cycle) {
		return new RequestContext(authority, executionScope, rawUcans, op, cancellation, invocationInput, gate, gateEvaluation, job, cycle, sessionDelivery, carried, origin, caller, scopeSource);
	}

	/**
	 * The live-event handle of the run-loop cycle this context drives, or
	 * null when it does not drive one (a direct transition invocation,
	 * {@code agent:step}). Activity emitted through it is attributed to that
	 * cycle on the agent's event stream.
	 */
	public AgentEvents.Cycle getCycle() {
		return cycle;
	}

	/**
	 * The caller's {@link Authority} — identity plus the grants it holds — as a
	 * single immutable value. This context <em>wraps</em> it; the credential
	 * getters below ({@link #getAuthorityDID()}, {@link #getCaps()},
	 * {@link #getProofs()}) read through to it.
	 */
	public Authority getAuthority() {
		return (scopeSource == null) ? authority : authority.withGrantScope(scopeFor(null));
	}

	/**
	 * The scope this context acts under now for a check on {@code resource}: read
	 * from its source, else its own grants. Null asks for the scope alone
	 * ({@link ScopeSource#read}).
	 */
	AVector<ACell> scopeFor(AString resource) {
		return (scopeSource != null) ? scopeSource.read(resource) : authority.getGrants();
	}

	/**
	 * The principal this request acts as, whose authority applies to it: who
	 * acted, for attribution, who a delegation is audienced to and who holds
	 * granting authority. Null if anonymous.
	 */
	public AString getAuthorityDID() {
		return authority.getDID();
	}

	/**
	 * The account of the principal this request acts as: a user's own, or an
	 * agent's owner's.
	 *
	 * <p><b>Use this for namespace questions</b> — which per-user lattice record,
	 * whose secret store, whose workspace a bare {@code w/foo} names. <b>Use
	 * {@link #getAuthorityDID()} for identity questions</b> — who acted
	 * (attribution), who a delegation is audienced to, and who holds granting
	 * authority. An agent works inside its owner's namespace but is not its
	 * owner; conflating the two is how a caller's identity leaks into state it
	 * should not reach (#91), and how an agent would silently acquire its
	 * owner's granting reach.</p>
	 */
	public AString getUserDID() {
		return authority.getUserDID();
	}

	/**
	 * True if this context acts within another principal's namespace — for
	 * example an agent or a caller carrying an owner-issued delegation.
	 */
	public boolean isSubPrincipal() {
		return authority.isSubPrincipal();
	}

	/**
	 * How {@code targetDID} stands relative to this caller — itself, its owning
	 * user, another principal under the same user, or a foreign one.
	 *
	 * <p>Proximity is not permission: a same-user target is a reasonable place to
	 * relax <em>friction</em> (an agent addressing its own owner, say), never a
	 * substitute for a capability check.</p>
	 */
	public Principals.Relation relationTo(AString targetDID) {
		return Principals.relate(getAuthorityDID(), getUserDID(), targetDID);
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
	 * Evaluate this context's presented UCAN proofs for a runtime action.
	 * Structural chain checks live in convex-core; application caveats are
	 * interpreted by {@link CapabilityChecker} using the invocation and gate
	 * evaluator carried by this context. Keeping those execution details here
	 * avoids exposing mutable-looking policy plumbing as public getters.
	 */
	boolean delegatedProofsCover(RootAuthorityPolicy rootPolicy, AString resource,
			AString ability, long now) {
		return delegatedProofsCover(rootPolicy, resource, ability, now, null);
	}

	/** As above for a use that sends a value to {@code destination}. */
	boolean delegatedProofsCover(RootAuthorityPolicy rootPolicy, AString resource,
			AString ability, long now, AString destination) {
		return CapabilityChecker.proofsCover(authority.getProofs(), authority.getDID(),
			rootPolicy, proofResource(resource), ability, now, op, invocationInput, gate, destination);
	}

	/** Bare capability resources execute in a sub-principal's user namespace. */
	private AString proofResource(AString resource) {
		if (!authority.isSubPrincipal() || resource == null || CapabilityChecker.isScheme(resource.toString())) return resource;
		return Strings.create(CapabilityChecker.canonicalResource(resource.toString(), authority.getUserDID()));
	}

	/**
	 * Returns a new context carrying the raw transport UCAN tokens (JWT strings)
	 * as originally presented. These are the relayable form for cross-venue
	 * forwarding — the parsed {@link #getProofs() proofs} cannot be re-signed.
	 */
	public RequestContext withRawUcans(AVector<ACell> rawUcans) {
		return new RequestContext(authority, executionScope, rawUcans, op, cancellation, invocationInput, gate, gateEvaluation, job, cycle, sessionDelivery, carried, origin, caller, scopeSource);
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
	 * Gets the caller's capability scope as it stands now, or null if
	 * unrestricted: an agent's is read from its {@code config.caps} at each
	 * call ({@link ScopeSource}). When non-null, an operation is authorised only
	 * if a grant in the scope covers it; a null scope simply has no bounding
	 * grants (nothing subtracts).
	 */
	public AVector<ACell> getCaps() {
		return scopeFor(null);
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
		if (denial != null) throw denied(this, denial, resource, ability);
	}

	/**
	 * Requires an explicitly declared grant, including when this context's
	 * ordinary capability scope is unrestricted. This is for opt-in authority
	 * boundaries such as admitting arbitrary instructions or tools: a null scope
	 * must not silently mean consent to widen an agent's advertised surface.
	 * Coverage, resource canonicalisation and capability gates otherwise use the
	 * same rules as {@link #requireCapability(AString, AString)}.
	 */
	public void requireExplicitCapability(AString resource, AString ability) {
		AVector<ACell> grants = scopeFor(resource);
		if (grants == null) grants = Vectors.empty();
		String denial = CapabilityChecker.allows(grants, resource, ability,
			authority.getUserDID(), authority.getDID(), op, invocationInput, gate);
		if (denial != null) throw denied(this, denial, resource, ability);
	}

	/**
	 * The refusal of {@code (resource, ability)} to {@code ctx}'s acting
	 * principal (null for a request with no context), with the resource as it
	 * resolves there.
	 */
	static CapabilityDeniedException denied(RequestContext ctx, String reason, AString resource, AString ability) {
		if (ctx == null) return new CapabilityDeniedException(reason, resource, ability, null);
		return new CapabilityDeniedException(reason, ctx.inNamespace(resource), ability, ctx.getAuthorityDID());
	}

	/**
	 * The grant check against this context's own authority: the denial message if
	 * the caller's capability scope does <em>not</em> cover {@code (resource,
	 * ability)}, or {@code null} if a grant covers it (an unrestricted, {@code
	 * null} scope always returns null). Package-visible so the {@code Engine}
	 * authority seam ({@code authorityCovers}) can compose it with the cross-user
	 * proof check without re-plumbing {@code op}/{@code invocationInput}/{@code
	 * gate}.
	 *
	 * <p>Bare paths canonicalise against {@link #getUserDID()}, not the caller —
	 * this is the single point at which the grant-scope check decides <em>whose
	 * namespace</em> {@code w/foo} names. For an agent that is its owner's, so a
	 * {@code config.caps} grant of {@code w/decisions/} keeps meaning the owner's
	 * workspace once the agent has a DID of its own. The request resource and
	 * every grant's {@code with} canonicalise against the same DID inside
	 * {@code allows}, so they still match. The proof path is unaffected: bare
	 * {@code with} in a token binds to that token's signed {@code iss}.</p>
	 */
	String grantsDenial(AString resource, AString ability) {
		return grantsDenial(resource, ability, null);
	}

	/** As {@link #grantsDenial(AString, AString)} for a use that sends a value to
	 *  {@code destination}: a grant's {@code nb.url} caveat must cover it. */
	String grantsDenial(AString resource, AString ability, AString destination) {
		return denialUnder(scopeFor(resource), resource, ability, authority.getUserDID(), destination);
	}

	/** The denial of {@code (resource, ability)} under an explicit grant scope,
	 *  evaluated with this context's invocation facts (operation, input, gate)
	 *  and the use's {@code destination}; null when the scope covers it. */
	String denialUnder(AVector<ACell> scope, AString resource, AString ability, AString ownerDID,
			AString destination) {
		return CapabilityChecker.allows(scope, resource, ability, ownerDID, authority.getDID(),
			op, invocationInput, gate, destination);
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
	 * Gets the agent ID in the current user namespace, or null if no local scope.
	 * For attribution across namespaces, use {@link #getExecutionScope()}.
	 * When set, the {@code n/} path prefix resolves to the agent's private workspace.
	 */
	public AString getAgentId() {
		return hasLocalExecutionScope() ? executionScope.agentId() : null;
	}

	/**
	 * Gets the job ID in the current user namespace, or null if no local scope.
	 * When set, the {@code t/} path prefix resolves to the job's temp field
	 * (unless a focused task id is also set, which takes precedence).
	 */
	public Blob getJobId() {
		return hasLocalExecutionScope() ? executionScope.jobId() : null;
	}

	/**
	 * Gets the Job wrapper for the operation currently executing, or null before
	 * dispatch. Unlike {@link #getJobId()}, this always identifies the immediate
	 * invocation and may return an unrecorded Job.
	 */
	public Job getJob() {
		return job;
	}

	/**
	 * Gets the session ID in the current user namespace, or null if no local scope.
	 * Used together with {@link #getAgentId()} to resolve the
	 * {@code c/} prefix.
	 */
	public Blob getSessionId() {
		return hasLocalExecutionScope() ? executionScope.sessionId() : null;
	}

	/**
	 * Gets the task ID in the current user namespace, or null if no local scope.
	 * Used together with {@link #getAgentId()} to resolve the
	 * {@code t/} prefix to the task Job's temp field.
	 */
	public Blob getTaskId() {
		return hasLocalExecutionScope() ? executionScope.taskId() : null;
	}

	/** Task-row revision in the current namespace, or -1 outside a local task cycle. */
	public long getTaskRevision() {
		return hasLocalExecutionScope() ? executionScope.taskRevision() : -1L;
	}

	@Override
	public String toString() {
		AString callerDID = authority.getDID();
		if (callerDID == null) return "RequestContext[ANONYMOUS]";
		String s = "RequestContext[" + callerDID;
		s += " execution=" + executionScope;
		if (job != null && !java.util.Objects.equals(job.getID(), executionScope.jobId())) {
			s += " invocation=" + job.getID() + (job.isRecorded() ? "" : " transient");
		}
		return s + "]";
	}
}
