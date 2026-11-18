package covia.venue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.lattice.CapabilityChecker;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.core.data.prim.CVMBool;
import convex.core.json.schema.JsonSchema;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.api.Abilities;
import covia.exception.AuthException;
import covia.exception.CoviaException;
import covia.exception.FaultException;
import covia.exception.InvalidInputException;
import covia.exception.InvalidStateException;
import covia.exception.NotFoundException;
import covia.exception.RateLimitException;
import covia.exception.ShuttingDownException;
import covia.exception.UnavailableException;
import covia.exception.UnsupportedException;
import covia.grid.Asset;
import covia.grid.Attribution;
import covia.grid.Job;
import covia.grid.Operation;
import covia.grid.Principals;
import covia.grid.Status;

/**
 * Manages job execution, admission, recovery and runtime handles.
 *
 * <p>JobManager is the runtime layer for jobs. It holds an in-memory cache of
 * active (non-terminal) handles over primary per-user lattice job components.
 * Every job has an owner identified by caller DID — there are no
 * anonymous jobs. Venue-internal operations use the venue's own DID.</p>
 *
 * <p>A job's record is in its owner's jobs ({@code :user-data → <DID> → j});
 * while it is unfinished the venue also keeps its entry in {@code :jobs}
 * ({@link JobIndex}), and recovery reads those entries. Terminal jobs are
 * evicted from in-memory cache — the lattice retains the full history.</p>
 *
 * <p>Dependencies are passed via constructor to avoid circular references
 * with Engine.</p>
 */
public class JobManager {

	private static final Logger log = LoggerFactory.getLogger(JobManager.class);
	private static final String JOB_ID_EXISTS = "Job ID already exists: ";
	/** A prepared Job started a second time. */
	private static final String ALREADY_STARTED = "Job already started: ";
	/** A prepared Job published before a transaction recorded it. */
	private static final String NOT_RECORDED = "Job has not been recorded: ";

	/** An invocation that names no operation. */
	public static final String OPERATION_REQUIRED = "Operation must be specified";
	/** An operation reference that resolves to nothing; the reference follows. */
	public static final String CANNOT_RESOLVE_OPERATION = "Cannot resolve operation: ";
	/** A reference that resolves to an asset which is not an operation; its id follows. */
	public static final String NOT_AN_OPERATION = "Asset is not an operation: ";

	/** Operation metadata without the adapter to dispatch to. */
	public static final String NO_ADAPTER_FIELD = "Metadata must contain operation.adapter field";
	/** Input that fails a strict operation's schema; the violation follows. */
	public static final String INPUT_SCHEMA_VIOLATION = "Input schema violation: ";
	/** Output that fails the operation's own schema under strict validation; the violation follows. */
	public static final String OUTPUT_SCHEMA_VIOLATION = "Output schema violation: ";
	/** An operation whose adapter this venue does not run; the adapter follows. */
	public static final String ADAPTER_NOT_AVAILABLE = "Adapter not available: ";
	/** A job id that names no job the caller can see; the id follows. */
	public static final String JOB_NOT_FOUND = "Job not found: ";
	/** A message or control to a job that has finished; the id follows. */
	public static final String JOB_TERMINAL = "Job is in terminal state: ";
	/** A message or control to a recorded job with no live handle; the id follows. */
	public static final String JOB_NOT_ACTIVE = "Job is not active: ";
	/** A message to a job whose operation does not take messages; the id follows. */
	public static final String JOB_NO_MESSAGES = "Job does not accept messages: ";
	/** A private run on a venue that does not enable them. */
	public static final String PRIVATE_RUNS_DISABLED =
		"Private runs are disabled on this venue (enablePrivateJobs=false)";
	/** New work refused while the venue shuts down. */
	public static final String SHUTTING_DOWN = "Venue is shutting down; new operations are not accepted";

	private static NotFoundException jobNotFound(Blob jobID) {
		return new NotFoundException(JOB_NOT_FOUND + jobID, jobID.toHexString());
	}

	private static InvalidInputException operationRequired() {
		return new InvalidInputException(OPERATION_REQUIRED);
	}

	private static NotFoundException unresolved(AString ref) {
		return new NotFoundException(CANNOT_RESOLVE_OPERATION + ref, ref.toString());
	}

	private static InvalidInputException notAnOperation(Asset asset) {
		return new InvalidInputException(NOT_AN_OPERATION + asset.getID());
	}

	private final Engine engine;

	/** Admission gate closed before engine shutdown begins. */
	/** Public top-level admission: closed first at shutdown. */
	private final AtomicBoolean acceptingTopLevel = new AtomicBoolean(true);
	/** Internal composition (sub-jobs of in-flight work): stays open through the grace window. */
	private final AtomicBoolean acceptingInternal = new AtomicBoolean(true);

	/** In-memory cache of active (non-terminal) jobs */
	private final ConcurrentHashMap<Blob, Job> activeJobs = new ConcurrentHashMap<>();

	/** Jobs being accepted, each completed once its handle is registered or its acceptance has failed ({@link #liveJob}). */
	final ConcurrentHashMap<Blob, CompletableFuture<Void>> accepting = new ConcurrentHashMap<>();

	/** Per-caller admission semaphores bounding concurrent top-level jobs. */
	private final ConcurrentHashMap<AString, JobSemaphore> jobPermits = new ConcurrentHashMap<>();

	/** Job IDs holding an admission permit → caller DID, for exactly-once release. */
	private final ConcurrentHashMap<Blob, AString> permitHolders = new ConcurrentHashMap<>();

	/** Semaphore with a recovery reservation hook. Negative permits are useful
	 *  when a restart restores more active jobs than the current configured cap:
	 *  new work remains blocked until enough restored jobs finish. */
	private static final class JobSemaphore extends Semaphore {
		private static final long serialVersionUID = 1L;
		JobSemaphore(int permits) { super(permits); }
		void reserveRecovered() { reducePermits(1); }
	}

	/** Concurrent-job cap settings, resolved once from venue config. */
	private final boolean jobCapEnabled;
	private final int maxConcurrentJobs;
	private final long jobBlockMs;

	/** Cross-cutting listeners notified on every Job in the venue (REST SSE,
	 *  MCP notifications). Per-Job subscribers attach directly to the Job
	 *  via {@link Job#subscribe(java.util.function.Consumer)}. */
	private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<Job>> jobUpdateListeners =
		new java.util.concurrent.CopyOnWriteArrayList<>();

	// Job ID generation state. The random half is unpredictable: an id is not a
	// credential, but one observed id must not reveal the next.
	private final Random rand = new java.security.SecureRandom();
	/**
	 * Monotonic 64-bit job-ID prefix: high 48 bits = millisecond timestamp,
	 * low 16 bits = per-millisecond counter. Advanced atomically as
	 * {@code max(prev+1, ts<<16)}, so IDs are strictly increasing — hence fully
	 * ordered and unique — even under concurrent submission, across
	 * same-millisecond bursts (the counter carries into the timestamp bits past
	 * 65535/ms), and across a backwards clock step (prev+1 wins).
	 */
	private final AtomicLong idPrefix = new AtomicLong(0);

	/**
	 * Creates a new JobManager.
	 *
	 * @param engine The venue engine
	 */
	public JobManager(Engine engine) {
		this.engine = engine;
		Config cfg = engine.config();
		this.maxConcurrentJobs = cfg.getMaxConcurrentJobsPerUser();
		this.jobBlockMs = cfg.getRateLimitBlockMs();
		this.jobCapEnabled = cfg.isRateLimitEnabled() && maxConcurrentJobs > 0;
	}

	/**
	 * Admission control for a top-level invoke: bounds concurrent (non-terminal)
	 * jobs per caller. Returns {@code true} if a permit was acquired (the caller
	 * must release it on job terminal via {@link #releaseJobPermit}); returns
	 * {@code false} when the cap does not apply — disabled, a sub-job (the context
	 * carries a current Job or inherited {@code jobId}), or an internal /
	 * unattributed caller. Blocks up to {@code jobBlockMs} to absorb bursts, then
	 * sheds with {@link RateLimitException} (→ HTTP 429).
	 */
	private boolean acquireJobPermit(RequestContext ctx, AString callerDID) {
		if (!jobCapEnabled) return false;
		if (ctx.getJob() != null || ctx.getJobId() != null) return false;
		if (callerDID == null) return false;       // internal / unattributed — exempt
		JobSemaphore sem = jobPermits.computeIfAbsent(callerDID, k -> new JobSemaphore(maxConcurrentJobs));
		try {
			if (sem.tryAcquire(jobBlockMs, TimeUnit.MILLISECONDS)) return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RateLimitException("Interrupted while awaiting a job slot", 1);
		}
		throw new RateLimitException(
			"Concurrent job limit (" + maxConcurrentJobs + ") reached for caller; retry shortly",
			Math.max(1, jobBlockMs / 1000));
	}

	/** Releases the admission permit held by a job, if any. Idempotent — safe to
	 *  call from both terminal eviction and explicit deletion. */
	private void releaseJobPermit(Blob jobID) {
		AString caller = permitHolders.remove(jobID);
		if (caller != null) releasePermit(caller);
	}

	/** Releases one of {@code caller}'s admission permits. */
	private void releasePermit(AString caller) {
		JobSemaphore sem = jobPermits.get(caller);
		if (sem != null) sem.release();
	}

	// ========== Job Invocation ==========

	/**
	 * Invoke an operation given a reference. Supports hex hash, DID URL,
	 * or catalog path. Resolves the reference (fetching remote DID-URL
	 * definitions if necessary) and requires invoke on that reference before
	 * executing it with the caller's resource authority.
	 *
	 * @param ref Operation reference (AString)
	 * @param input Input parameters
	 * @param ctx Request context (caller identity)
	 * @return Job tracking the execution
	 */
	public Job invokeOperation(String ref, ACell input, RequestContext ctx) {
		return invokeOperation(Strings.create(ref), input, ctx);
	}

	public Job invokeOperation(AString ref, ACell input, RequestContext ctx) {
		// Execution is always local: resolution returns a definition (remote
		// refs are FETCHED by resolveAsset, never delegated), so every
		// accepted invoke produces a job on THIS venue. Cross-venue
		// execution is explicit via grid:run / grid:invoke.
		// Scope the context to the caller-supplied reference — the only point
		// the human path form still exists — so requireInvoke can check a
		// resource-precise invoke capability (#211).
		return invokeOperation(ref, resolveOperation(ref, ctx), input, ctx);
	}

	/** Generic invocation of the definition the framework already resolved for {@code ref}. */
	public Job invokeOperation(AString ref, Operation operation, ACell input, RequestContext ctx) {
		return dispatchOperation(operation.meta(), input, ctx.withOp(ref), false, Invocation.INVOKE);
	}

	/**
	 * Calls a server-bound operation with a durable Job. The binding must come
	 * from the trusted endpoint or tool configuration, never an arbitrary
	 * operation argument. Resource checks remain in force; no invoke grant is
	 * needed merely to dispatch a bound call.
	 */
	public Job callOperation(String ref, ACell input, RequestContext ctx) {
		AString operation = Strings.create(ref);
		return dispatchOperation(resolveOperation(operation, ctx).meta(), input,
			ctx.withOp(operation), false, Invocation.CALL);
	}

	/**
	 * Authorises an explicit remote invocation against the target reference and
	 * the input being sent. The executing venue applies its defaults and checks
	 * its own invocation and resource authority independently, using the
	 * explicitly selected remote credentials, not the source's local scope.
	 */
	public void requireRemoteInvoke(AString ref, ACell input, RequestContext ctx) {
		if (ref == null) throw operationRequired();
		engine.requireInvoke(invocationContext(ctx.withOp(ref), input), ref);
	}

	private RequestContext invocationContext(RequestContext ctx, ACell input) {
		return ctx.isGateEvaluation()
			? ctx.withInvocation(input, null)
			: ctx.withInvocation(input, (gateOp, opRef, gateInput, caller) ->
				evaluateGate(gateOp, opRef, gateInput, caller, ctx));
	}

	/** HTTP submission: acknowledge a durable handle independently of adapter execution. */
	public Job submitOperation(AString ref, ACell input, RequestContext ctx) {
		Prepared prepared = prepareOperation(resolveOperation(ref, ctx).meta(), input, ctx.withOp(ref),
			false, Invocation.INVOKE);
		prepared.commit();
		Job job = prepared.accept();
		try {
			engine.flush();
			AAdapter.VIRTUAL_EXECUTOR.execute(() -> {
				try { prepared.start(); }
				catch (RuntimeException | Error failure) {
					// start() has already recorded the execution failure on the Job.
					log.debug("Submitted job {} failed", job.getID(), failure);
				}
			});
		} catch (RuntimeException | Error failure) {
			CoviaException reported = FaultException.within(STARTING_JOB, FaultException.VENUE, failure);
			job.fail(reported);
			throw reported;
		}
		return job;
	}

	/**
	 * Prepare a durable, tracked Job on behalf of a trusted in-process caller,
	 * for it to record in its own venue transaction and then start — the
	 * scheduler's tracked fire. Resolves and scopes exactly like
	 * {@link #invokeOperation(AString, ACell, RequestContext)}, but is exempt
	 * from top-level admission like {@link #invokeInternal}: the venue itself
	 * is dispatching, not a user request. The Job is recorded in the owner's
	 * history (PENDING) regardless of operation metadata.
	 *
	 * @param ref Operation reference (hex hash, DID URL, workspace path, etc.)
	 * @param input Input parameters
	 * @param ctx Request context (caller identity, caps, scope)
	 * @return the prepared Job, not yet recorded
	 */
	Prepared prepareTracked(AString ref, ACell input, RequestContext ctx) {
		return prepareOperation(resolveOperation(ref, ctx).meta(), input, ctx.withOp(ref),
			false, Invocation.SCHEDULED);
	}

	/**
	 * Prepare a durable backend job for its parent to record with its own
	 * record of it, in one venue transaction. The job keeps the context it runs
	 * under ({@link #keepCallerContext}), so work it carries on after a restart
	 * acts with the same authority ({@link #callerContext}).
	 */
	public Prepared prepareAgentInteraction(AMap<AString, ACell> meta, ACell input, RequestContext ctx) {
		Prepared prepared = prepareOperation(meta, input, ctx, false, Invocation.TRACKED);
		prepared.admission.keptContext = keptContext(prepared.ctx);
		return prepared;
	}

	/** Prepare an untracked scheduled fire, bound to its account when it is recorded with its claim. */
	Prepared prepareUntracked(AString ref, ACell input, RequestContext ctx) {
		return prepareOperation(resolveOperation(ref, ctx).meta(), input, ctx.withOp(ref),
			true, Invocation.SCHEDULED_TRANSIENT);
	}

	/**
	 * A Job checked and ready to record: its operation resolved, its adapter
	 * found and the caller admitted, nothing written yet. Running it takes
	 * three steps:
	 * <ol>
	 * <li>{@link #record} in a venue transaction, which takes the Job's id and
	 *     writes it, PENDING, with whatever else that transaction commits;</li>
	 * <li>once that transaction has committed, {@link #start}, which runs the
	 *     adapter, or {@link #accept} first when the caller needs the live Job
	 *     before it runs;</li>
	 * <li>if the transaction failed instead, {@link #abandon}.</li>
	 * </ol>
	 * A Job that has been recorded is never left without an outcome by its
	 * start: a failure there fails the Job, unless the Job went with its
	 * account, which was deleted after the commit.
	 */
	public final class Prepared {
		private final Admission admission;
		private final AAdapter adapter;
		private final RequestContext ctx;
		private final AMap<AString, ACell> meta;
		private final ACell input;
		private final boolean lookup;
		private final AtomicBoolean started = new AtomicBoolean();
		private VenueJob job;
		private RequestContext jobCtx;

		Prepared(Admission admission, AAdapter adapter, RequestContext ctx,
				AMap<AString, ACell> meta, ACell input, boolean lookup) {
			this.admission = admission;
			this.adapter = adapter;
			this.ctx = ctx;
			this.meta = meta;
			this.input = input;
			this.lookup = lookup;
		}

		/**
		 * Records the Job in {@code venue}, the draft of a venue transaction,
		 * taking its id there. A transaction body that runs again records it
		 * again under a new id; the one that commits is the Job's.
		 *
		 * @return the Job's id in this run of the transaction
		 * @throws AuthException if the owner's account has been deleted
		 */
		public Blob record(VenueState venue) {
			return admission.record(venue);
		}

		/** Releases what recording took, after the transaction that recorded it failed. */
		public void abandon() {
			admission.abandon();
		}

		/** Records the Job in a venue transaction of its own. */
		void commit() {
			admission.commit();
		}

		/**
		 * The live Job, once the transaction that recorded it has committed,
		 * without running it yet.
		 *
		 * @throws AuthException if the owner's account has been deleted since,
		 *         taking the Job with it
		 */
		public Job accept() {
			if (job == null) {
				job = admission.publish();
				if (job.isRecorded()) log.info("Submitted job: {}", job.getID());
				else log.debug("Started transient job: {}", job.getID());
				// Every adapter sees the immediate Job wrapper, including a transient one.
				// jobId is narrower: it names a durable t/ temp scope. Preserve an inherited
				// parent scope for internal composition, and establish a new scope only for
				// a recorded Job. Giving a transient wrapper a jobId would let a t/ write
				// accidentally materialise a partial persistent Job record.
				RequestContext withJob = ctx.withJob(job);
				if (withJob.getJobId() == null && job.isRecorded()) withJob = withJob.withJobId(job.getID());
				jobCtx = withJob;
			}
			return job;
		}

		/**
		 * Invoke the adapter, once the transaction that recorded the Job has
		 * committed. A synchronous capability denial fails the Job (the same
		 * surface async adapters produce); any other synchronous failure is
		 * recorded on the Job and the report rethrown, so a Job is never stranded
		 * PENDING with no worker. A failure in the adapter is its operation's
		 * fault ({@link Job#report}); one in the venue's own check before it is
		 * the venue's.
		 *
		 * @throws AuthException if the owner's account has been deleted since
		 *         the commit, taking the Job with it
		 */
		public Job start() {
			accept();
			if (!started.compareAndSet(false, true)) throw new IllegalStateException(ALREADY_STARTED + job.getID());
			if (job.isFinished()) return job;
			try {
				job.requireAccount();
				// Only a generic reference invocation needs invoke. A bound tool
				// calls its operation directly and retains every resource gate.
				if (lookup) engine.requireInvoke(jobCtx, jobCtx.getOp());
			} catch (covia.exception.AuthException e) {
				job.fail(e);
				return job;
			} catch (RuntimeException | Error e) {
				throw failWith(FaultException.within(STARTING_JOB, FaultException.VENUE, e));
			}
			try {
				adapter.invoke(job, jobCtx, meta, input);
			} catch (java.util.concurrent.CancellationException e) {
				job.cancel(e.getMessage());
			} catch (covia.exception.AuthException e) {
				job.fail(e);
			} catch (RuntimeException | Error e) {
				throw failWith(job.report(e));
			}
			return job;
		}

		/** Records {@code reported} on the Job and returns it for the caller to throw. */
		private CoviaException failWith(CoviaException reported) {
			try { job.fail(reported); }
			catch (RuntimeException | Error recording) {
				if (recording != reported) reported.addSuppressed(recording);
			}
			return reported;
		}
	}

	/** What failed, when the venue cannot start a Job before its adapter runs. */
	public static final String STARTING_JOB = "Starting the job";

	/**
	 * Resolve a reference to an operation definition, failing fast otherwise:
	 * {@link NotFoundException} when it does not resolve,
	 * {@link InvalidInputException} when it is missing or names an asset that
	 * is not an operation, and whatever resolving it raised.
	 */
	public Operation resolveOperation(AString ref, RequestContext ctx) {
		if (ref == null) throw operationRequired();

		Asset asset = engine.resolveAsset(ref, ctx);
		if (asset == null) throw unresolved(ref);

		Operation op = Operation.from(asset);
		if (op == null) throw notAnOperation(asset);
		return op;
	}

	/**
	 * Invoke an operation given metadata. This is the canonical dispatch path:
	 * resolves the adapter, creates a job, and invokes the adapter.
	 *
	 * @param meta Operation metadata (must contain operation.adapter)
	 * @param input Input parameters
	 * @param ctx Request context (caller identity)
	 * @return Job tracking the execution
	 */
	public Job invokeOperation(AMap<AString, ACell> meta, ACell input, RequestContext ctx) {
		return dispatchOperation(meta, input, ctx, false, Invocation.CALL);
	}

	/** Trusted dispatch intent; never supplied by operation metadata. */
	private enum Invocation {
		INVOKE(true, false, true), RUN(true, true, true), TOOL(true, true, false),
		CALL(true, false, false), CALL_RUN(true, true, false),
		INTERNAL(false, true, false), INTERNAL_INVOKE(false, true, true), TRACKED(false, false, false),
		SCHEDULED(false, false, true), SCHEDULED_TRANSIENT(false, true, true);
		final boolean admission;
		final boolean result;
		final boolean lookup;
		Invocation(boolean admission, boolean result, boolean lookup) {
			this.admission = admission;
			this.result = result;
			this.lookup = lookup;
		}
	}

	/** Executes an operation through the canonical Job machinery. */
	private Job dispatchOperation(AMap<AString, ACell> meta, ACell input,
			RequestContext ctx, boolean memoryOnly, Invocation invocation) {
		Prepared prepared = prepareOperation(meta, input, ctx, memoryOnly, invocation);
		prepared.commit();
		return prepared.start();
	}

	/**
	 * Everything {@link #dispatchOperation} does before the Job is recorded:
	 * defaults, gates, adapter lookup and admission. The returned
	 * {@link Prepared} carries the Job's record, without its id, and what
	 * {@link Prepared#start()} needs to run it.
	 */
	private Prepared prepareOperation(AMap<AString, ACell> meta, ACell input,
			RequestContext ctx, boolean memoryOnly, Invocation invocation) {
		boolean applyAdmission = invocation.admission;
		boolean resultOriented = invocation.result;
		requireAccepting(applyAdmission);
		// Fill in the operation's declared argument defaults BEFORE gate
		// arming and job creation, so gates, job records and the adapter all
		// see the one effective input.
		input = applyDefaults(meta, input);
		// Arm capability gates (#216): every requireCapability fired during this
		// invocation can evaluate nb.gate-caveated grants against exactly this
		// op + input. Installed here because dispatch is the one place with the
		// engine, the reference and the input together.
		ctx = invocationContext(ctx, input);
		// Resource authority is always enforced by the executing operation.
		// A generic lookup additionally checks invoke on its reference when the
		// prepared job starts, with these defaults and capability gates in place.
		// Tool and framework bindings do not acquire lookup authority just by
		// creating a Job. No metadata field can choose this dispatch intent.
		AAdapter adapter = prepareInvocation(meta, input, ctx);
		// Jobs are owned by, quota'd against, and persisted into the USER's
		// namespace. An agent sub-principal runs inside its owner's account: it
		// must not open a job store of its own (its jobs would vanish from the
		// owner's listing) nor a second admission bucket (it would multiply the
		// owner's concurrency cap). Who *acted* stays on the context —
		// ctx.getAuthorityDID() / ctx.getAgentId().
		AString ownerDID = ctx.getUserDID();

		// Admission control: bound concurrent top-level jobs per user. Blocks up
		// to jobBlockMs to absorb bursts, then sheds with RateLimitException (429).
		// Sub-jobs (current Job or inherited jobId set) and internal callers are exempt.
		boolean permit = applyAdmission && acquireJobPermit(ctx, ownerDID);

		// The record's `op` is the reference that was actually invoked (e.g.
		// v/ops/json/merge, or a bare hash when the caller pinned a definition
		// explicitly). Only an inline definition has no reference, in which
		// case the metadata's own hash stands in (#499).
		AString opID = ctx.getOp();
		if (opID == null) opID = Strings.create(meta.getHash().toHexString());
		Blob parentID = parentJobID(ctx);
		long ts = engine.currentTimestamp().longValue();
		try {
			Admission admission = jobAdmission(ts, opID, meta, input, ownerDID, ctx,
				parentID, memoryOnly, resultOriented,
				(invocation == Invocation.RUN || invocation == Invocation.CALL_RUN) && ctx.getJob() == null && ctx.getJobId() == null,
				adapter, permit);
			return new Prepared(admission, adapter, ctx, meta, input, invocation.lookup);
		} catch (RuntimeException | Error e) {
			if (permit) releasePermit(ownerDID);
			throw e;
		}
	}

	/**
	 * The nearest recorded job enclosing a dispatch (#500). The immediate Job
	 * on the context wins when it is recorded; a transient wrapper forwards
	 * the parent it was itself stamped with. With no immediate Job, an
	 * explicitly inherited durable scope ({@code withJobId}) is the parent.
	 */
	private static Blob parentJobID(RequestContext ctx) {
		Job enclosing = ctx.getJob();
		if (enclosing != null) {
			if (enclosing.isRecorded()) return enclosing.getID();
			ACell inherited = enclosing.getData().get(Fields.PARENT);
			return (inherited != null) ? Job.parseID(inherited) : null;
		}
		return ctx.getJobId();
	}

	// ========== Result-oriented invocation ==========

	/**
	 * Runs an operation and returns only its result. This is the public,
	 * result-oriented counterpart to {@link #invokeOperation}: it still uses a
	 * Job wrapper and the normal admission/auth/adapter lifecycle, but does not
	 * expose the Job to its caller.
	 *
	 * <p>A declared {@code operation.readOnly:true} runs with a transient Job by
	 * default. Mutating or unclassified operations are recorded. An explicit
	 * {@code operation.internal:false}, or the venue's
	 * {@code recordReadOnlyOperations} policy, forces recording. A caller that
	 * explicitly requests a private run gets a transient wrapper regardless of
	 * operation metadata, provided the venue has opted in with
	 * {@code enablePrivateJobs:true}.</p>
	 */
	public CompletableFuture<ACell> runOperation(String ref, ACell input, RequestContext ctx) {
		return runOperation(Strings.create(ref), input, ctx, false);
	}

	/**
	 * Runs an operation to completion and returns only its result, optionally
	 * forcing the Job wrapper to remain transient. A private run has no durable
	 * Job handle to recover or poll; callers must keep awaiting this future.
	 *
	 * @param privateJob true to leave no Job record, subject to venue opt-in
	 */
	public CompletableFuture<ACell> runOperation(String ref, ACell input,
			RequestContext ctx, boolean privateJob) {
		return runOperation(Strings.create(ref), input, ctx, privateJob);
	}

	public CompletableFuture<ACell> runOperation(AString ref, ACell input, RequestContext ctx) {
		return runOperation(ref, input, ctx, false);
	}

	public CompletableFuture<ACell> runOperation(AString ref, ACell input,
			RequestContext ctx, boolean privateJob) {
		return runOperation(ref, input, ctx, privateJob, Invocation.RUN);
	}

	/** Public tool dispatch retains admission and result semantics, but cannot reveal owner-only results. */
	public CompletableFuture<ACell> runToolOperation(AString ref, ACell input, RequestContext ctx) {
		return startToolOperation(ref, input, ctx).result();
	}

	/**
	 * A run in progress: its Job, null if it failed before one was made, and
	 * its result. The Job lets a caller that stops waiting say where the
	 * result will be, or cancel it.
	 */
	public record Run(Job job, CompletableFuture<ACell> result) {
		static Run failed(Throwable t) {
			return new Run(null, CompletableFuture.failedFuture(t));
		}
	}

	/** {@link #runOperation(AString, ACell, RequestContext, boolean)}, keeping the Job. */
	public Run startRun(AString ref, ACell input, RequestContext ctx, boolean privateJob) {
		return startOperation(ref, input, ctx, privateJob, Invocation.RUN);
	}

	/** {@link #runToolOperation}, keeping the Job. */
	public Run startToolOperation(AString ref, ACell input, RequestContext ctx) {
		return startOperation(ref, input, ctx, false, Invocation.TOOL);
	}

	/**
	 * As above, for the definition the framework already resolved for
	 * {@code ref}: a tool bound by an MCP token, whose definition its issuer
	 * reads, while the call acts with the token's authority.
	 */
	public Run startToolOperation(AString ref, Operation operation, ACell input, RequestContext ctx) {
		return startOperation(operation.meta(), input, ctx.withOp(ref), false, Invocation.TOOL);
	}

	private CompletableFuture<ACell> runOperation(AString ref, ACell input,
			RequestContext ctx, boolean privateJob, Invocation invocation) {
		return startOperation(ref, input, ctx, privateJob, invocation).result();
	}

	private Run startOperation(AString ref, ACell input,
			RequestContext ctx, boolean privateJob, Invocation invocation) {
		if (ref == null) return Run.failed(operationRequired());
		try {
			Asset asset = engine.resolveAsset(ref, ctx);
			if (asset == null) return Run.failed(unresolved(ref));
			Operation op = Operation.from(asset);
			if (op == null) return Run.failed(notAnOperation(asset));
			return startOperation(op.meta(), input, ctx.withOp(ref), privateJob, invocation);
		} catch (Exception e) {
			return Run.failed(e);
		}
	}

	public CompletableFuture<ACell> runOperation(AMap<AString, ACell> meta,
			ACell input, RequestContext ctx) {
		return runOperation(meta, input, ctx, false);
	}

	public CompletableFuture<ACell> runOperation(AMap<AString, ACell> meta,
			ACell input, RequestContext ctx, boolean privateJob) {
		return runOperation(meta, input, ctx, privateJob, Invocation.CALL_RUN);
	}

	private CompletableFuture<ACell> runOperation(AMap<AString, ACell> meta,
			ACell input, RequestContext ctx, boolean privateJob, Invocation invocation) {
		return startOperation(meta, input, ctx, privateJob, invocation).result();
	}

	private Run startOperation(AMap<AString, ACell> meta,
			ACell input, RequestContext ctx, boolean privateJob, Invocation invocation) {
		try {
			if (privateJob && !engine.config().isPrivateJobsEnabled()) {
				return Run.failed(new UnsupportedException(PRIVATE_RUNS_DISABLED));
			}
			boolean memoryOnly = privateJob || (isReadOnly(meta)
				&& allowsInternal(meta)
				&& !engine.config().isRecordReadOnlyOperations());
			Job job = dispatchOperation(meta, input, ctx, memoryOnly, invocation);
			return new Run(job, operationResultFuture(job, meta, input));
		} catch (Exception e) {
			return Run.failed(e);
		}
	}

	// ========== Internal Invocation ==========

	/**
	 * Invokes an operation for trusted in-process composition and returns only
	 * its result. A transient Job wrapper provides the complete adapter lifecycle
	 * without adding a durable sub-Job to the caller's history.
	 *
	 * <p>Uses a trusted framework binding, retaining resource checks and the
	 * schema / adapter lifecycle without a generic invoke check. It is exempt
	 * from top-level admission and normally skips persistence and venue-wide Job
	 * notifications. An explicit {@code operation.internal:false} forces a
	 * durable Job. Read-only operations are also recorded when the venue sets
	 * {@code recordReadOnlyOperations:true}.</p>
	 *
	 *
	 * <p>Cancellation is cooperative: the returned future's
	 * {@link CompletableFuture#cancel(boolean)} signals the adapter if it
	 * cooperates, but does not guarantee interruption of in-flight work.</p>
	 *
	 * @param ref Operation reference (hex hash, DID URL, workspace path, etc.)
	 * @param input Input parameters
	 * @param ctx Request context (caller identity, caps, scope)
	 * @return future that completes with the adapter result, or exceptionally on failure
	 */
	public CompletableFuture<ACell> invokeInternal(String ref, ACell input, RequestContext ctx) {
		return invokeInternal(Strings.create(ref), input, ctx);
	}

	public CompletableFuture<ACell> invokeInternal(AString ref, ACell input, RequestContext ctx) {
		return invokeInternal(ref, input, ctx, null);
	}

	/**
	 * As {@link #invokeInternal(AString, ACell, RequestContext)}, showing the
	 * Job to {@code watch} once it exists and before its adapter starts, so a
	 * caller observing the work as it runs — a tool call relaying the
	 * operation's published progress — misses none of it. {@code watch} runs
	 * on this thread and must not block; null watches nothing.
	 */
	public CompletableFuture<ACell> invokeInternal(AString ref, ACell input, RequestContext ctx,
			Consumer<Job> watch) {
		return invokeInternal(ref, input, ctx, watch, false);
	}

	/**
	 * As {@link #invokeInternal(AString, ACell, RequestContext, Consumer)},
	 * recording the Job from the start when {@code record} is true, as for an
	 * operation declaring {@code internal:false}.
	 */
	public CompletableFuture<ACell> invokeInternal(AString ref, ACell input, RequestContext ctx,
			Consumer<Job> watch, boolean record) {
		if (ref == null) return CompletableFuture.failedFuture(operationRequired());

		Asset asset;
		try {
			asset = engine.resolveAsset(ref, ctx);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
		if (asset == null) return CompletableFuture.failedFuture(unresolved(ref));

		Operation op = Operation.from(asset);
		if (op == null) return CompletableFuture.failedFuture(notAnOperation(asset));
		return invokeInternal(ref, op, input, ctx, watch, record);
	}

	/**
	 * As {@link #invokeInternal(AString, ACell, RequestContext, Consumer)}, for
	 * an operation the caller has already resolved from {@code ref}, so the
	 * reference is not resolved again.
	 */
	public CompletableFuture<ACell> invokeInternal(AString ref, Operation op, ACell input,
			RequestContext ctx, Consumer<Job> watch) {
		return invokeInternal(ref, op, input, ctx, watch, false);
	}

	/**
	 * As {@link #invokeInternal(AString, Operation, ACell, RequestContext, Consumer)},
	 * recording the Job from the start when {@code record} is true.
	 */
	public CompletableFuture<ACell> invokeInternal(AString ref, Operation op, ACell input,
			RequestContext ctx, Consumer<Job> watch, boolean record) {
		// Always local — see invokeOperation(AString,...): resolution
		// returns definitions, never remote execution handles.
		// Retain the bound reference for attribution and resource caveats.
		return invokeInternal(op.meta(), input, ctx.withOp(ref), watch, record);
	}

	/**
	 * Invokes a caller-selected reference resolved by the framework, for example
	 * an agent's raw-reference fallback outside its bound tool palette. Retains
	 * internal composition's observation and recording rules, but checks invoke
	 * on the reference. The supplied operation must be the definition resolved
	 * for that reference under this context.
	 */
	public CompletableFuture<ACell> invokeReference(AString ref, Operation op, ACell input,
			RequestContext ctx, Consumer<Job> watch, boolean record) {
		return executeInternal(op.meta(), input, ctx.withOp(ref), watch, record, Invocation.INTERNAL_INVOKE);
	}

	public CompletableFuture<ACell> invokeInternal(AMap<AString, ACell> meta, ACell input, RequestContext ctx) {
		return invokeInternal(meta, input, ctx, null, false);
	}

	private CompletableFuture<ACell> invokeInternal(AMap<AString, ACell> meta, ACell input, RequestContext ctx,
			Consumer<Job> watch, boolean record) {
		return executeInternal(meta, input, ctx, watch, record, Invocation.INTERNAL);
	}

	private CompletableFuture<ACell> executeInternal(AMap<AString, ACell> meta, ACell input, RequestContext ctx,
			Consumer<Job> watch, boolean record, Invocation invocation) {
		try {
			boolean memoryOnly = !record && allowsInternal(meta)
				&& !(isReadOnly(meta) && engine.config().isRecordReadOnlyOperations());
			Prepared prepared = prepareOperation(meta, input, ctx, memoryOnly, invocation);
			prepared.commit();
			if (watch != null) watch.accept(prepared.accept());
			Job job = prepared.start();
			return operationResultFuture(job, meta, input);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/** Only an explicit true marks an operation as safe for unrecorded public run. */
	static boolean isReadOnly(AMap<AString, ACell> meta) {
		return CVMBool.TRUE.equals(RT.getIn(meta, Fields.OPERATION, Fields.READ_ONLY));
	}

	/** Only an explicit false opts an operation out of transient/internal execution. */
	static boolean allowsInternal(AMap<AString, ACell> meta) {
		return !CVMBool.FALSE.equals(RT.getIn(meta, Fields.OPERATION, Fields.INTERNAL));
	}

	/** Return the Job result while preserving cooperative cancellation. */
	private CompletableFuture<ACell> operationResultFuture(Job job,
			AMap<AString, ACell> meta, ACell input) {
		String adapterName = AAdapter.getAdapterName(meta);
		AAdapter adapter = (adapterName != null) ? engine.getAdapter(adapterName) : null;
		CompletableFuture<ACell> future = (adapter != null)
			? adapter.resultFuture(job, meta, input)
			: job.future();
		future.whenComplete((result, error) -> {
			if (future.isCancelled() && !job.isFinished()) job.cancel();
		});
		return future;
	}

	/**
	 * Applies the operation's declared argument defaults: {@code operation.default}
	 * is a map merged UNDER the caller's input — caller-supplied values always
	 * win, and default values may be any cell type (strings, numbers, booleans,
	 * vectors, maps). Purpose-shaping only, never policy: a caller can override
	 * any default; enforcement belongs to capability gates (#216).
	 *
	 * <p>A null input becomes the defaults map; a non-map input passes through
	 * untouched (defaults only make sense for named arguments). Shallow,
	 * top-level merge.</p>
	 */
	@SuppressWarnings("unchecked")
	static ACell applyDefaults(AMap<AString, ACell> meta, ACell input) {
		ACell defs = RT.getIn(meta, Fields.OPERATION, Fields.DEFAULT);
		if (!(defs instanceof AMap<?, ?>)) return input;
		AMap<AString, ACell> defaults = (AMap<AString, ACell>) defs;
		if (defaults.count() == 0) return input;
		if (input == null) return defaults;
		if (!(input instanceof AMap<?, ?>)) return input;
		AMap<AString, ACell> in = (AMap<AString, ACell>) input;
		for (var entry : defaults.entrySet()) {
			if (!in.containsKey(entry.getKey())) {
				in = in.assoc(entry.getKey(), entry.getValue());
			}
		}
		return in;
	}

	/** Bound on a single capability-gate evaluation — gates sit on the
	 *  invocation hot path, so a hung gate must deny, not hang the caller. */
	private static final long GATE_TIMEOUT_MS = 30_000;

	/**
	 * Evaluates a capability gate (#216): invokes the grant's {@code nb.gate}
	 * operation with {@code {operation, input, caller}} describing the
	 * invocation being authorised: {@code caller} is the principal whose
	 * authority the check runs on. The capability applies iff the gate op
	 * succeeds; any failure — the op fails, doesn't resolve, or times out —
	 * denies with the reason (fail-closed, never fail-open).
	 *
	 * <p>The gate runs under a constrained derivative of the caller's authority:
	 * it may invoke the gate definition itself and use ordinary ungated grants,
	 * but it receives neither unrestricted scope nor additive UCAN proofs.
	 * Nested gated grants are disabled structurally by the gate-evaluation flag.
	 * {@code invokeInternal} uses a transient Job wrapper, so checks retain the
	 * normal execution context without bloating the etch.</p>
	 *
	 * <p>{@code gateOp} is already bound to the grant's issuer or owner
	 * ({@link CapabilityChecker#bindGate}). Naming a gate is consent to run it as
	 * a gate check, so its definition is resolved without a read check: the
	 * caller need not be able to read a private gate to be checked by it.</p>
	 */
	String evaluateGate(AString gateOp, AString opRef, ACell input, AString caller,
			RequestContext authorityCtx) {
		return runGate(gateOp, gateInput(opRef, input, caller),
			engine.authorityFrame(authorityCtx).forGateEvaluation(gateOp));
	}

	/**
	 * Evaluates a callable's gate (CALLABLES.md): as a capability gate, with
	 * the same input, outcome and limits, describing a call of the callable at
	 * {@code callable} by {@code invoker}, but run with the call's own
	 * authority ({@code call}, the callable's), as a Convex trust monitor runs
	 * as its actor, so it can consult the provider's private records.
	 *
	 * @param callable the callable's resource, {@code <owner>/x/callable/<name>}
	 * @return null when the gate admits the call, else why not
	 */
	public String evaluateCallableGate(AString gateOp, AString callable, ACell input, RequestContext invoker,
			RequestContext call) {
		return runGate(gateOp, gateInput(callable, input, invoker.getAuthorityDID()),
			call.asGateEvaluation(gateOp));
	}

	/** A gate's input: the operation being authorised, its input and the principal that acted. */
	private static AMap<AString, ACell> gateInput(AString operation, ACell input, AString caller) {
		AMap<AString, ACell> gateInput = Maps.of(Fields.OPERATION, operation, Fields.INPUT, input);
		return (caller == null) ? gateInput : gateInput.assoc(Fields.CALLER, caller);
	}

	/** Runs gate {@code gateOp} on {@code gateInput} under {@code gateCtx}: null when it succeeds, else why not. */
	private String runGate(AString gateOp, AMap<AString, ACell> gateInput, RequestContext gateCtx) {
		try {
			Asset gate = engine.resolveAssetUnchecked(gateOp, gateCtx);
			if (gate == null) return "gate " + gateOp + ": cannot resolve operation";
			invokeInternal(gate.meta(), gateInput, gateCtx)
				.get(GATE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
			return null;                                        // gate succeeded — capability applies
		} catch (java.util.concurrent.TimeoutException e) {
			return "gate " + gateOp + " timed out after " + GATE_TIMEOUT_MS + "ms";
		} catch (Exception e) {
			Throwable cause = (e.getCause() != null) ? e.getCause() : e;
			String msg = cause.getMessage();
			return "gate " + gateOp + ": " + (msg != null ? msg : cause.toString());
		}
	}

	/**
	 * Validate a completing one-shot job's result against the operation's
	 * declared output schema, per the operator-configured {@code outputValidation}
	 * mode ({@code off}/{@code warn}/{@code strict}). Off (the default) does
	 * nothing — no validation, no logging — so results are never checked unless
	 * an operator opts in. {@code warn} logs a mismatch; {@code strict} throws so
	 * the job fails. Unlike input validation (a per-operation {@code strict}
	 * contract), this is a venue-level operator policy.
	 *
	 * @throws IllegalArgumentException in {@code strict} mode when the result
	 *         does not satisfy the operation's output schema
	 */
	public void validateOutput(OutputRules rules, ACell result) {
		String mode = engine.config().getOutputValidation();
		if (mode == null || "off".equals(mode)) return;
		AMap<AString, ACell> outputSchema = rules.outputSchema();
		if (outputSchema == null) return;
		String err = JsonSchema.validate(outputSchema, result);
		if (err == null) return;
		if ("strict".equals(mode)) {
			// The operation broke its own declared contract: its adapter's fault,
			// not the caller's input
			throw new FaultException(OUTPUT_SCHEMA_VIOLATION + err);
		}
		log.warn("Output schema violation: {}", err);
	}

	/**
	 * Shared invocation prelude: validates metadata, auth, caps, schema;
	 * returns the adapter ready to invoke. Used by both recorded
	 * {@link #invokeOperation(AMap, ACell, RequestContext)} and result-oriented
	 * {@link #invokeInternal(AMap, ACell, RequestContext)} dispatch.
	 *
	 * @throws InvalidInputException invalid meta or schema violation
	 * @throws AuthException caller not authenticated
	 * @throws RuntimeException capability denied
	 * @throws UnsupportedException adapter not registered
	 */
	private AAdapter prepareInvocation(AMap<AString, ACell> meta, ACell input, RequestContext ctx) {
		if (ctx.getJob() instanceof VenueJob parent) parent.requireAccount();
		if (meta == null) throw new IllegalArgumentException("Metadata must be specified");
		AString callerDID = ctx.getAuthorityDID();
		if (callerDID == null) throw new AuthException("Authentication required");
		// Authentication establishes who the caller is; admission establishes
		// whether that DID has an account at this venue. Keep this in the shared
		// invocation prelude so HTTP, MCP, A2A, proof-channel and internal job
		// paths cannot drift or persist a job before the decision is made.
		// Admission is a question about the *account*: an agent sub-principal is
		// admitted through its owner, never registered as a user in its own right.
		engine.admitUser(ctx.getUserDID());

		String adapterName = AAdapter.getAdapterName(meta);
		if (adapterName == null) throw new InvalidInputException(NO_ADAPTER_FIELD);

		// Validate input against operation schema when strict mode is enabled
		if (CVMBool.TRUE.equals(RT.getIn(meta, Fields.OPERATION, K_STRICT))) {
			AMap<AString, ACell> inputSchema = getMap(RT.getIn(meta, Fields.OPERATION, Fields.INPUT));
			if (inputSchema != null && !inputSchema.isEmpty()) {
				String schemaErr = JsonSchema.validate(inputSchema, input);
				if (schemaErr != null) throw new InvalidInputException(INPUT_SCHEMA_VIOLATION + schemaErr);
			}
		}

		requireRunning(adapterName);
		AAdapter adapter = engine.getAdapter(adapterName);
		if (adapter == null) throw new UnsupportedException(ADAPTER_NOT_AVAILABLE + adapterName);
		return adapter;
	}

	/**
	 * Refuses work for an enabled adapter that is not running: its start
	 * failed, or it could not take back its settings after a refused change.
	 * Why is for operators ({@link Engine#adapterFailure}), not callers.
	 * @throws UnavailableException if the adapter is not running
	 */
	private void requireRunning(String adapterName) {
		if (engine.adapterFailure(adapterName) != null) {
			throw new UnavailableException(Engine.ADAPTER_NOT_RUNNING.formatted(adapterName));
		}
	}

	private static final AString K_STRICT = Strings.intern("strict");

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> getMap(ACell cell) {
		return (cell instanceof AMap) ? (AMap<AString, ACell>) cell : null;
	}

	// ========== Job Submission ==========

	/**
	 * The admission of a Job for an operation: its record, still without its
	 * id, and how its live handle is made.
	 *
	 * @param ownerDID the account the job is kept in, and what access control
	 *        and quota key on
	 * @param ctx the request, whose authority made the call: the record names
	 *        it as its caller, left out when it is the account itself
	 *        ({@link Attribution})
	 * @param parentID nearest recorded enclosing job, or null for a top-level
	 *        job. Stamped on transient wrappers too (in memory only) so a
	 *        recorded grandchild dispatched through a transient layer still
	 *        links to its nearest recorded ancestor.
	 */
	private Admission jobAdmission(long ts, AString opID, AMap<AString, ACell> meta, ACell input, AString ownerDID,
			RequestContext ctx, Blob parentID, boolean memoryOnly, boolean resultOriented, boolean directRun,
			AAdapter adapter, boolean permit) {
		AString callerDID = ctx.getAuthorityDID();
		if (ownerDID == null || callerDID == null) throw new AuthException("Authentication required");

		OutputRules rules = OutputRules.of(meta);

		AMap<AString, ACell> status = Maps.of(
				Fields.OP, opID,
				Fields.STATUS, Status.PENDING,
				Fields.UPDATED, CVMLong.create(ts),
				Fields.CREATED, CVMLong.create(ts),
				Fields.INPUT, rules.redact(input));
		status = Attribution.attribute(status, callerDID, ownerDID);
		if (parentID != null) {
			status = status.assoc(Fields.PARENT, parentID);
		}

		AString name = RT.ensureString(RT.getIn(meta, Fields.NAME));
		if (name != null) {
			status = status.assoc(Fields.NAME, name);
		}

		// The adapter the venue dispatches to (#520): the one job-list fact a
		// client cannot derive from a hash-valued `op` without a fetch per row,
		// and it survives the definition becoming unavailable later.
		String adapterName = AAdapter.getAdapterName(meta);
		if (adapterName != null) {
			status = status.assoc(Fields.ADAPTER, Strings.create(adapterName));
		}

		return new Admission(ts, status, rules, ownerDID, memoryOnly, permit, resultOriented,
			(record, data) -> new VenueJob(record, data, rules, ownerDID, this, !memoryOnly, directRun, adapter));
	}

	/**
	 * A Job being admitted. Its id is taken, and its row and entry
	 * ({@link JobIndex}) written, in a venue transaction: the Job's own, or a
	 * caller's that commits its own record of the Job with it. Its live handle
	 * is published once that transaction has committed. A transaction body
	 * that runs again takes a new id, and the run that committed holds the
	 * Job's. Each id is listed as accepting before its row is written, and
	 * stays listed until the handle is published or the admission abandoned,
	 * so a caller who reads the committed row first waits for the handle
	 * ({@link #liveJob}).
	 *
	 * <p>The row comes first because a handle reads a missing row as an erased
	 * account. The transaction refuses a deleted account and notes the
	 * account's generation, and the handle is published only while the account
	 * is that one. Deletion can commit after the transaction and scan the
	 * active jobs before the handle is published, so the account is checked
	 * again once the handle can be seen: either the scan finds the handle or
	 * this check finds the deletion.</p>
	 *
	 * <p>Recording runs on the thread making the transaction; publishing and
	 * abandoning after it, on that thread or one it hands the Job to.</p>
	 */
	final class Admission {
		private final long ts;
		/** The Job's record without its id. */
		private final AMap<AString, ACell> status;
		private final OutputRules rules;
		private final AString ownerDID;
		private final boolean memoryOnly;
		/** Whether the Job holds one of its owner's admission permits. */
		private final boolean permit;
		private final boolean preserveFailureCause;
		private final BiFunction<JobRecord, AMap<AString, ACell>, VenueJob> handle;
		/** The caller's context the entry keeps ({@link #keepCallerContext}), or null. */
		private AMap<AString, ACell> keptContext;
		/** The id each run of the transaction took, in order. */
		private final List<Blob> ids = new ArrayList<>();
		/** The generation of the owner's account the Job was recorded in ({@link Users#requireCurrent}). */
		private ACell generation;

		Admission(long ts, AMap<AString, ACell> status, OutputRules rules, AString ownerDID,
				boolean memoryOnly, boolean permit, boolean preserveFailureCause,
				BiFunction<JobRecord, AMap<AString, ACell>, VenueJob> handle) {
			this.ts = ts;
			this.status = status;
			this.rules = rules;
			this.ownerDID = ownerDID;
			this.memoryOnly = memoryOnly;
			this.permit = permit;
			this.preserveFailureCause = preserveFailureCause;
			this.handle = handle;
		}

		/** Records the Job in {@code venue}, a venue transaction's draft, under a new id. */
		Blob record(VenueState venue) {
			Blob id = generateJobID(ts);
			ids.add(id);
			accepting.put(id, new CompletableFuture<>());
			if (memoryOnly) {
				venue.users().requireNotDeleted(ownerDID);
				generation = RT.getIn(venue.users().get(ownerDID), Users.GENERATION);
				return id;
			}
			User account = venue.users().ensure(ownerDID);
			if (account.jobs().create(id, status.assoc(Fields.ID, id)) == null) {
				throw new IllegalStateException(JOB_ID_EXISTS + id);
			}
			AMap<AString, ACell> entry = Maps.of(JobIndex.OWNER, ownerDID);
			AMap<AString, ACell> kept = rules.toCell();
			if (kept != null) entry = entry.assoc(JobIndex.RULES, kept);
			if (keptContext != null) {
				// A context with no job scope of its own is the Job's
				AMap<AString, ACell> context = keptContext.containsKey(Fields.JOB_ID) ? keptContext
					: keptContext.assoc(Fields.JOB_ID, id);
				entry = entry.assoc(JobIndex.CONTEXT, context);
			}
			venue.jobIndex().put(id, entry);
			generation = RT.getIn(account.get(), Users.GENERATION);
			return id;
		}

		/** Records the Job in a venue transaction of its own, which a memory-only Job does not need. */
		void commit() {
			try {
				if (memoryOnly) record(venue());
				else venue().execute(this::record);
			} catch (RuntimeException | Error e) {
				abandon();
				throw e;
			}
		}

		/**
		 * Publishes the live handle of the Job the committed transaction
		 * recorded.
		 *
		 * @throws AuthException if the owner's account has been deleted since,
		 *         taking the Job with it
		 */
		VenueJob publish() {
			if (ids.isEmpty()) throw new IllegalStateException(NOT_RECORDED + ownerDID);
			Blob id = ids.get(ids.size() - 1);
			boolean attached = false;
			try {
				for (Blob other : ids) if (!other.equals(id)) release(other);
				requireAccount();
				JobRecord record = null;
				if (!memoryOnly) {
					record = account(ownerDID).jobs().at(id);
					if (record.getRecord() == null) throw new IllegalStateException(NOT_RECORDED + id);
				}
				VenueJob job = handle.apply(record, status.assoc(Fields.ID, id));
				job.setPreserveFailureCause(preserveFailureCause);
				// Attached before the Job can be seen, so whatever ends it releases the permit
				if (permit) {
					permitHolders.put(id, ownerDID);
					attached = true;
				}
				activeJobs.put(id, job);
				try {
					requireAccount();
				} catch (AuthException deleted) {
					job.cancel();
					throw deleted;
				}
				return job;
			} catch (RuntimeException | Error e) {
				if (permit && !attached) releasePermit(ownerDID);
				throw e;
			} finally {
				release(id);
			}
		}

		/** Releases what recording took, after the transaction that recorded the Job failed. */
		void abandon() {
			for (Blob id : ids) release(id);
			ids.clear();
			if (permit) releasePermit(ownerDID);
		}

		/**
		 * Refuses once the owner's account is no longer the one the Job was
		 * recorded in. An account never deleted has no generation, so null is
		 * its first.
		 */
		private void requireAccount() {
			Users.requireCurrent(venue().users().get(ownerDID), generation);
		}

		/** Ends the acceptance listed for {@code id}, letting go of anyone waiting on it. */
		private void release(Blob id) {
			CompletableFuture<Void> acceptance = accepting.remove(id);
			if (acceptance != null) acceptance.complete(null);
		}
	}

	// ========== Caller context kept for work that continues ==========

	/**
	 * Keeps what {@code ctx} carries beyond its caller with {@code job}, for
	 * work that continues for the caller after this request, such as a session
	 * that resumes after a restart ({@link #callerContext}): the verified UCAN
	 * proofs it presents, in the form checked on arrival, the separate
	 * authority it acts with if it has one ({@link Engine#keptAuthority}), its
	 * route ({@link RequestContext#getRoute}), since the work is the same
	 * request carried on, and its execution scopes (agent, session, job,
	 * task). They are kept in the job's entry
	 * ({@link JobIndex}), which no route reads, and go with it when the job
	 * ends. A job keeps a context even when its caller presented nothing
	 * beyond itself, so {@link #keepsCallerContext} tells it from one whose
	 * adapter never asked. A job that is not recorded does not outlive the
	 * request, so it keeps none.
	 */
	public void keepCallerContext(Job job, RequestContext ctx) {
		if (!job.isRecorded()) return;
		AMap<AString, ACell> context = keptContext(ctx);
		venue().jobIndex().update(job.getID(), entry -> entry.assoc(JobIndex.CONTEXT, context));
	}

	/** What {@link #keepCallerContext} keeps of {@code ctx}. */
	private static AMap<AString, ACell> keptContext(RequestContext ctx) {
		AMap<AString, ACell> kept = Maps.empty();
		AVector<ACell> proofs = ctx.getProofs();
		if (proofs != null && !proofs.isEmpty()) kept = kept.assoc(Fields.PROOFS, proofs);
		AMap<AString, ACell> authority = Engine.keptAuthority(ctx);
		if (authority != null) kept = kept.assoc(Fields.AUTHORITY, authority);
		AVector<ACell> route = ctx.getRoute();
		if (route != null && !route.isEmpty()) kept = kept.assoc(Fields.ROUTE, route);
		if (ctx.getAgentId() != null) kept = kept.assoc(Fields.AGENT_ID, ctx.getAgentId());
		if (ctx.getSessionId() != null) kept = kept.assoc(Fields.SESSION_ID, ctx.getSessionId());
		if (ctx.getJobId() != null) kept = kept.assoc(Fields.JOB_ID, ctx.getJobId());
		if (ctx.getTaskId() != null) kept = kept.assoc(Fields.TASK_ID, ctx.getTaskId());
		return kept;
	}

	/**
	 * Whether {@code job} keeps its caller's context ({@link #keepCallerContext}),
	 * so that {@link #callerContext} rebuilds the context it started in rather
	 * than its caller alone.
	 *
	 * @return false when its adapter never asked, or the job has finished
	 */
	public boolean keepsCallerContext(Job job) {
		return RT.getIn(venue().jobIndex().entry(job.getID()), JobIndex.CONTEXT) != null;
	}

	/**
	 * The context a job's caller acts under now, in the job's namespace:
	 * what a live request of the caller's would hold on arrival
	 * ({@link Engine#context(AString, AString, AVector, AMap)}), presenting again
	 * the proofs the job keeps and acting with the separate authority it keeps,
	 * on the route and with the execution scopes it keeps
	 * ({@link #keepCallerContext}). This is how work that continues after a
	 * restart regains its authority. Nobody is present in it, so it has no
	 * origin, though its route still names where the request started.
	 *
	 * @throws AuthException when the job names no caller, or its caller could not act now
	 */
	public RequestContext callerContext(Job job) {
		AString caller = job.getCaller();
		if (caller == null) throw new AuthException(NO_CALLER + job.getID());
		AMap<AString, ACell> kept = RT.ensureMap(RT.getIn(venue().jobIndex().entry(job.getID()), JobIndex.CONTEXT));
		RequestContext ctx = engine.context(accountOf(job), caller, RT.ensureVector(RT.getIn(kept, Fields.PROOFS)),
			RT.ensureMap(RT.getIn(kept, Fields.AUTHORITY)));
		if (kept == null) return ctx;
		AVector<ACell> route = RT.ensureVector(kept.get(Fields.ROUTE));
		if (route != null) ctx = ctx.withRoute(route);
		AString agentId = RT.ensureString(kept.get(Fields.AGENT_ID));
		if (agentId != null) ctx = ctx.withAgentId(agentId);
		if (kept.get(Fields.SESSION_ID) instanceof Blob sessionId) ctx = ctx.withSessionId(sessionId);
		if (kept.get(Fields.JOB_ID) instanceof Blob jobId) ctx = ctx.withJobId(jobId);
		if (kept.get(Fields.TASK_ID) instanceof Blob taskId) ctx = ctx.withTaskId(taskId);
		return ctx;
	}

	// ========== Adapter data ==========

	/**
	 * What a job's adapter keeps to carry the job to its end
	 * ({@link #updateAdapterData}).
	 *
	 * @return the data, or null when the adapter keeps none or the job has finished
	 */
	public AMap<AString, ACell> adapterData(Job job) {
		if (job instanceof VenueJob held && held.isMemoryOnly()) return held.adapterData();
		return venue().jobIndex().adapterData(job.getID());
	}

	/**
	 * Changes what a job's adapter keeps to carry the job to its end. It is
	 * kept in the job's entry ({@link JobIndex}), which no route reads and which
	 * goes when the job finishes, or with the job itself when it is held only in
	 * memory. A finished job keeps none.
	 *
	 * @param change Pure edit of the data, empty when there is none; null removes it
	 */
	public void updateAdapterData(Job job, UnaryOperator<AMap<AString, ACell>> change) {
		updateAdapterData(venue(), job, change);
	}

	/**
	 * As {@link #updateAdapterData(Job, UnaryOperator)}, in {@code venue}, the
	 * draft of a venue transaction, so the change commits with whatever else
	 * that transaction writes. A Job held only in memory changes at once.
	 */
	public void updateAdapterData(VenueState venue, Job job, UnaryOperator<AMap<AString, ACell>> change) {
		if (job instanceof VenueJob held && held.isMemoryOnly()) {
			held.updateAdapterData(change);
			return;
		}
		venue.jobIndex().updateAdapterData(job.getID(), change);
	}

	/** Why a job's work cannot regain authority: its record names no caller. */
	public static final String NO_CALLER = "The job records no caller to act as: ";

	// ========== Mirrors ==========

	/** On a mirror job, the id of the unrecorded job it follows ({@link #mirror}). */
	public static final AString MIRROR_OF = Strings.intern("mirrorOf");

	/** A mirror asked of a job this manager did not start as an internal or tool call. */
	public static final String MIRROR_NEEDS_CALL = "Only an unrecorded internal or tool call can be mirrored";

	/** What a mirror copies from its call when it is made. */
	private static final AString[] MIRRORED_FIELDS = {
		Fields.OP, Fields.INPUT, Fields.NAME, Fields.ADAPTER,
		Fields.CALLER, Fields.PARENT, Fields.CREATED };

	/** What a mirror copies from its call when the call ends. */
	private static final AString[] OUTCOME_FIELDS = { Fields.OUTPUT, Fields.ERROR, Fields.ERROR_CODE };

	/**
	 * A recorded job for a call that has none, so a caller who stops waiting
	 * can be pointed at a job to look up, one that outlasts a restart. The
	 * mirror looks like the call: its operation, input (secrets hidden), name,
	 * adapter, caller, parent and creation time, kept in the call's account, and it names the call
	 * in {@link #MIRROR_OF}. It stays STARTED until the call ends, then takes
	 * the call's status, output and error; cancelling it cancels the call.
	 * No adapter runs it: it holds no admission permit, takes no messages,
	 * and a restart fails it and shutdown cancels it by the default path
	 * ({@link AAdapter#defaultRecover}, {@link AAdapter#defaultSuspend}).
	 *
	 * @param call a Job this manager started, never a direct or private run
	 * @return the call itself when it is recorded, else its new mirror
	 * @throws ShuttingDownException once the venue has closed admission
	 */
	public Job mirror(Job call) {
		if (call.isRecorded()) return call;
		if (!(call instanceof VenueJob unrecorded) || unrecorded.isDirectRun()) {
			throw new IllegalArgumentException(MIRROR_NEEDS_CALL);
		}
		requireAccepting(false);
		AMap<AString, ACell> data = call.getData();
		long ts = engine.currentTimestamp().longValue();
		AMap<AString, ACell> status = Maps.of(
			Fields.STATUS, Status.STARTED,
			Fields.CREATED, CVMLong.create(ts),
			Fields.UPDATED, CVMLong.create(ts),
			MIRROR_OF, call.getID());
		for (AString field : MIRRORED_FIELDS) {
			ACell value = data.get(field);
			if (value != null) status = status.assoc(field, value);
		}
		AString owner = unrecorded.accountDID();
		Admission admission = new Admission(ts, status, unrecorded.rules(), owner, false, false, false,
			(record, mirrored) -> new MirrorJob(record, mirrored, unrecorded.rules(), owner, this, call));
		admission.commit();
		VenueJob mirror = admission.publish();
		Blob jobID = mirror.getID();
		whenFinished(call, () -> {
			AMap<AString, ACell> outcome = call.getData();
			try {
				mirror.update(current -> settled(current, outcome));
			} catch (RuntimeException | Error e) {
				// Nothing waits on this, so a failure would otherwise vanish
				log.error("Mirror {} did not take the outcome of job {}", jobID, call.getID(), e);
			}
		});
		log.info("Mirrored job {} as {}", call.getID(), jobID);
		return mirror;
	}

	/**
	 * Runs {@code action} once when {@code job} reaches a final status, or at
	 * once if it already has. Driven by the job's own updates, not its result
	 * future: a waiter may cancel that future before the job has finished,
	 * and shutdown releases it without finishing the job.
	 */
	public static void whenFinished(Job job, Runnable action) {
		AtomicBoolean ran = new AtomicBoolean();
		Consumer<Job> listener = new Consumer<>() {
			@Override public void accept(Job updated) {
				if (!updated.isFinished() || !ran.compareAndSet(false, true)) return;
				updated.unsubscribe(this);
				action.run();
			}
		};
		job.subscribe(listener);
		listener.accept(job); // a job that finished before the listener was added
	}

	/** A mirror's record carrying its call's outcome. */
	private static AMap<AString, ACell> settled(AMap<AString, ACell> mirror, AMap<AString, ACell> outcome) {
		AMap<AString, ACell> next = mirror.assoc(Fields.STATUS, outcome.get(Fields.STATUS));
		for (AString field : OUTCOME_FIELDS) {
			ACell value = outcome.get(field);
			next = (value == null) ? next.dissoc(field) : next.assoc(field, value);
		}
		return next;
	}

	/** Whether a job is a mirror ({@link #mirror}), which no adapter runs. */
	static boolean isMirror(Job job) {
		return job.getData().get(MIRROR_OF) != null;
	}

	// ========== Job Queries ==========

	/** Canonical capability resource for a Job, independent of its execution adapter. */
	public static AString resource(AString owner, Blob id) {
		return owner.append("/j/" + id.toHexString());
	}

	/** Opening of every refusal of access to a job. */
	public static final String JOB_ACCESS_DENIED = "Access denied to job: ";

	/** Read a Job, defaulting the retained-record lookup to the caller's namespace. */
	public AMap<AString, ACell> getJobData(Blob id, RequestContext ctx) {
		return getJobData(id, ctx, null);
	}

	/** An explicit owner locates retained Jobs; it never changes the caller's authority. */
	public AMap<AString, ACell> getJobData(Blob id, RequestContext ctx, AString owner) {
		Authorized read = authorized(id, ctx, owner, Abilities.JOB_READ);
		return (read == null) ? null : Attribution.inFull(read.data(), read.account());
	}

	private AMap<AString, ACell> authorizedRecord(Blob id, RequestContext ctx, AString owner, AString ability) {
		Authorized read = authorized(id, ctx, owner, ability);
		return (read == null) ? null : read.data();
	}

	/**
	 * A job's record as it is kept, the account it is kept in, and the live
	 * handle it was read from, or null for a stored record.
	 */
	private record Authorized(Job live, AString account, AMap<AString, ACell> data) {}

	/**
	 * Reads job {@code id} for a caller with {@code ability}, or null when there
	 * is none it may know of. A caller that wants the job's handle takes it from
	 * here, never from a second lookup: a job that finishes in between leaves
	 * the active set, and the second lookup would find only the record read
	 * before it finished, a handle that never finishes.
	 */
	private Authorized authorized(Blob id, RequestContext ctx, AString owner, AString ability) {
		Job live = liveJob(id);
		AMap<AString, ACell> data;
		AString account;
		if (live != null) {
			if (!live.isRecorded()) return null;
			account = ((VenueJob) live).accountDID();
			data = ((VenueJob) live).primaryRecord();
			if (data == null || (owner != null && !owner.equals(account))) return null;
		} else {
			account = owner == null ? ctx.getUserDID() : owner;
			User user = account == null ? null : engine.getVenueState().users().user(account);
			data = user == null ? null : user.jobs().getRecord(id);
		}
		if (data == null) return null;
		requireJobAbility(ctx, id, account, data, ability);
		return new Authorized(live, account, data);
	}

	/** Authorize a task-specific control without exposing its record. */
	public void requireAccess(Blob id, RequestContext ctx, AString owner, AString ability) {
		if (authorizedRecord(id, ctx, owner, ability) == null) throw jobNotFound(id);
	}

	/** A result wait rechecks access at delivery, so an expired grant discloses no result. */
	public CompletableFuture<ACell> awaitResult(Blob id, RequestContext ctx, AString owner) {
		Job job = getJob(id, ctx, owner);
		if (job == null) throw jobNotFound(id);
		return job.future().handle((result, error) -> {
			requireAccess(id, ctx, owner, Abilities.JOB_READ);
			if (error != null) throw new java.util.concurrent.CompletionException(error);
			return result;
		});
	}

	private void requireJobAbility(RequestContext ctx, Blob id, AString account, AMap<AString, ACell> data, AString ability) {
		if (!engine.getAccessControl().canAccessJob(ctx, account, data, ability))
			throw new AuthException(JOB_ACCESS_DENIED + id + " (" + ability + ")");
	}

	/** Controls acknowledge acceptance without disclosing the Job's input, output or history. */
	public static AMap<AString, ACell> acknowledgement(Blob id) {
		return Maps.of(Fields.ID, id, Fields.ACCEPTED, CVMBool.TRUE);
	}

	/**
	 * Gets a snapshot of the current job status data (internal use).
	 * Checks in-memory cache only — no lattice fallback without user context.
	 */
	public AMap<AString, ACell> getJobData(Blob jobID) {
		Job job;
		job = activeJobs.get(jobID);
		if (job != null) return job.getData();
		return null;
	}

	/**
	 * Gets the live Job object for the given job ID.
	 * Checks in-memory cache first, falls back to constructing a read-only Job from lattice.
	 */
	public Job getJob(Blob jobID) {
		Job job = activeJobs.get(jobID);
		if (job != null) return job;
		return null;
	}

	/**
	 * The live handle for a job a caller names by id. A recorded job's row is
	 * written before its handle is registered, so a caller who has just read the
	 * row can arrive in between; that caller alone waits, for that job's
	 * acceptance ({@link #accepting}). The acceptance is listed before the row
	 * exists and removed after the handle is registered, so the last read finds
	 * a handle whichever side of the removal the caller arrived on.
	 */
	private Job liveJob(Blob jobID) {
		Job job = activeJobs.get(jobID);
		if (job != null) return job;
		CompletableFuture<Void> acceptance = accepting.get(jobID);
		if (acceptance != null) acceptance.join();
		return activeJobs.get(jobID);
	}

	/**
	 * Gets the live Job object with lattice fallback using request context.
	 */
	public Job getJob(Blob jobID, RequestContext ctx) {
		return getJob(jobID, ctx, null);
	}

	/**
	 * The live handle of a job the caller may read, the one its record was read
	 * from, or a Job of its stored record once it is no longer live.
	 */
	public Job getJob(Blob jobID, RequestContext ctx, AString owner) {
		Authorized read = authorized(jobID, ctx, owner, Abilities.JOB_READ);
		if (read == null) return null;
		return (read.live() != null) ? read.live() : new Job(Attribution.inFull(read.data(), read.account()));
	}

	/**
	 * Gets the jobs Index for the given request context.
	 * Reads directly from the user's per-user lattice. Listing the jobs reads
	 * their collection, so the caller needs {@code job/read} on {@code j/}.
	 *
	 * @throws AuthException when the caller may not read {@code j/}
	 */
	public Index<Blob, ACell> getJobs(RequestContext ctx) {
		AString did = ctx.getUserDID();
		if (did == null) return Index.none();
		engine.requireAuthority(ctx, ctx.getUserDID().append("/j/"), Abilities.JOB_READ,
			Strings.create(engine.config().getBaseUrl()));
		User user = engine.getVenueState().users().user(did);
		if (user == null) return Index.none();
		return user.jobs().getAll();
	}

	/**
	 * Caller-scoped count, without materialising a listing; read-checked as
	 * {@link #getJobs}.
	 *
	 * @throws AuthException when the caller may not read {@code j/}
	 */
	public long countJobs(RequestContext ctx) {
		AString did = ctx.getUserDID();
		if (did == null) return 0;
		engine.requireAuthority(ctx, ctx.getUserDID().append("/j/"), Abilities.JOB_READ,
			Strings.create(engine.config().getBaseUrl()));
		User user = engine.getVenueState().users().user(did);
		return user == null ? 0 : user.jobs().size();
	}

	// ========== Job Lifecycle Control ==========

	/**
	 * Cancels a Job with request context.
	 * @throws AuthException if the caller lacks the required Job ability
	 */
	public AMap<AString, ACell> cancelJob(Blob id, RequestContext ctx) {
		return cancelJob(id, ctx, null);
	}

	/**
	 * Cancels a Job with request context and an optional reason, which becomes
	 * the job's {@code error} ({@link Job#cancel(String)}).
	 * @throws AuthException if the caller lacks the required Job ability
	 */
	public AMap<AString, ACell> cancelJob(Blob id, RequestContext ctx, String reason) {
		return cancelJob(id, ctx, reason, null);
	}

	public AMap<AString, ACell> cancelJob(Blob id, RequestContext ctx, String reason, AString owner) {
		AMap<AString, ACell> data = authorizedRecord(id, ctx, owner, Abilities.JOB_MANAGE);
		if (data == null) return null;
		AMap<AString, ACell> cancelled = cancelJob(id, reason);
		if (cancelled != null) return acknowledgement(id);
		// Cancelling a finished job changes nothing and acknowledges acceptance,
		// whether or not its live handle has been evicted
		if (Job.isFinished(data)) return acknowledgement(id);
		throw notLive(id, data);
	}

	public AMap<AString, ACell> cancelJob(Blob id) {
		return cancelJob(id, (String) null);
	}

	public AMap<AString, ACell> cancelJob(Blob id, String reason) {
		Job job = liveJob(id);
		if (job == null) return null;
		job.cancel(reason);
		return job.getData();
	}

	/**
	 * Pauses a running Job with request context.
	 * @throws AuthException if the caller lacks the required Job ability
	 */
	public AMap<AString, ACell> pauseJob(Blob id, RequestContext ctx) {
		return pauseJob(id, ctx, null);
	}

	public AMap<AString, ACell> pauseJob(Blob id, RequestContext ctx, AString owner) {
		AMap<AString, ACell> data = authorizedRecord(id, ctx, owner, Abilities.JOB_MANAGE);
		if (data == null) return null;
		Job job = liveJob(id);
		if (job == null) throw notLive(id, data);
		// A stopped adapter takes no new work for its jobs
		resolveJobAdapter(job);
		job.pause();
		return acknowledgement(id);
	}

	/**
	 * Resumes a paused Job with request context.
	 * @throws AuthException if the caller lacks the required Job ability
	 */
	public AMap<AString, ACell> resumeJob(Blob id, RequestContext ctx) {
		return resumeJob(id, ctx, null);
	}

	public AMap<AString, ACell> resumeJob(Blob id, RequestContext ctx, AString owner) {
		AMap<AString, ACell> data = authorizedRecord(id, ctx, owner, Abilities.JOB_MANAGE);
		if (data == null) return null;
		Job job = liveJob(id);
		if (job == null) throw notLive(id, data);
		// A stopped adapter takes no new work for its jobs
		resolveJobAdapter(job);
		job.resume();
		return acknowledgement(id);
	}

	/**
	 * Refusal for a recorded job with no live handle to act on: it has finished,
	 * or it is not running on this venue now.
	 */
	private static InvalidStateException notLive(Blob id, AMap<AString, ACell> data) {
		return new InvalidStateException((Job.isFinished(data) ? JOB_TERMINAL : JOB_NOT_ACTIVE) + id);
	}

	/**
	 * Deletes a job permanently with request context: removes the durable
	 * record from the owning user's job index and evicts the active cache.
	 * @throws AuthException if the caller lacks the required Job ability
	 */
	public boolean deleteJob(Blob id, RequestContext ctx) {
		return deleteJob(id, ctx, null);
	}

	public boolean deleteJob(Blob id, RequestContext ctx, AString owner) {
		Authorized read = authorized(id, ctx, owner, Abilities.JOB_MANAGE);
		if (read == null) return false;
		AString ownerDID = read.account();
		boolean removed = ownerDID != null && venue().transact(draft -> {
			User user = draft.users().user(ownerDID);
			if (user == null || user.jobs().remove(id) == null) return false;
			draft.jobIndex().remove(id);
			return true;
		});
		// Remove from active cache if present (may already be evicted for terminal jobs)
		deleteJob(id);
		return removed;
	}

	public boolean deleteJob(Blob id) {
		boolean removed = activeJobs.remove(id) != null;
		releaseJobPermit(id);
		return removed;
	}

	// ========== Message Delivery ==========

	/**
	 * Delivers a message to a job's message queue with request context.
	 * @throws AuthException if the caller lacks the required Job ability
	 */
	public void deliverMessage(Blob jobID, AMap<AString, ACell> message, RequestContext ctx) {
		deliverMessage(jobID, message, ctx, null);
	}

	public void deliverMessage(Blob jobID, AMap<AString, ACell> message, RequestContext ctx, AString owner) {
		AMap<AString, ACell> data = authorizedRecord(jobID, ctx, owner, Abilities.JOB_MESSAGE);
		if (data == null) throw jobNotFound(jobID);
		Job job = liveJob(jobID);
		if (job == null) throw notLive(jobID, data);
		deliverMessage(job, message, ctx.getAuthorityDID());
	}

	private void deliverMessage(Job job, AMap<AString, ACell> message, AString source) {
		Blob jobID = job.getID();
		if (job.isFinished()) throw new InvalidStateException(JOB_TERMINAL + jobID);
		// Only a multi-turn adapter takes messages; any other job would drop it
		AAdapter adapter = resolveJobAdapter(job);
		if (adapter == null || !adapter.supportsMultiTurn()) {
			throw new UnsupportedException(JOB_NO_MESSAGES + jobID);
		}

		long ts = engine.currentTimestamp().longValue();
		Blob msgId = generateJobID(ts);

		AMap<AString, ACell> record = Maps.of(
				Fields.MESSAGE, message,
				Fields.TS, CVMLong.create(ts),
				Fields.ID, msgId);
		if (source != null) {
			record = record.assoc(Fields.SOURCE, source);
		}

		try {
			adapter.handleMessage(job, record);
		} catch (RuntimeException | Error e) {
			// The adapter taking the message: an unexpected exception is its fault
			throw FaultException.within(DELIVERING_MESSAGE.formatted(jobID), adapter.faultOwner(), e);
		}
	}

	/** What failed, when an adapter fails taking a message: the Job's id follows. */
	public static final String DELIVERING_MESSAGE = "Message delivery to job %s";

	// ========== History ==========

	/**
	 * Append a message record to a Job's history. Used by the A2A server to
	 * populate {@code Task.history} for Agent-to-Agent conversations.
	 *
	 * <p>Deliberately separate from {@link #deliverMessage} — this method only
	 * persists to history and does not dispatch to the adapter. A2A's
	 * {@code SendMessage} path calls both: append inbound message to history,
	 * then deliverMessage (which triggers adapter handling). For a freshly
	 * invoked task, only the append is needed (the adapter already received
	 * the first message via the operation's input).</p>
	 *
	 * <p>The record is stored as-is under {@link Fields#HISTORY}, a vector of
	 * per-message maps. Caller-provided {@code messageId} / role / parts
	 * fields are preserved.</p>
	 *
	 * @throws AuthException if the caller lacks the required Job ability
	 */
	public void appendToHistory(Blob jobID, AMap<AString, ACell> record, RequestContext ctx) {
		Authorized current = authorized(jobID, ctx, null, Abilities.JOB_MESSAGE);
		if (current == null) throw jobNotFound(jobID);
		appendToHistory(jobID, current.account(), record);
	}

	/**
	 * Framework form of {@link #appendToHistory(Blob, AMap, RequestContext)}:
	 * records a message the venue itself has accepted for a job of
	 * {@code ownerDID}, such as the per-agent A2A endpoint recording an ask it
	 * admitted. No caller check: trust is established by the call path, as for
	 * {@link #getJobData(Blob)}.
	 */
	public void appendToHistory(Blob jobID, AString ownerDID, AMap<AString, ACell> record) {
		Job live = activeJobs.get(jobID);
		User user = live instanceof VenueJob job ? job.account()
			: engine.getVenueState().users().user(ownerDID);
		JobRecord state = (user == null) ? null : user.jobs().job(jobID);
		if (state != null && state.appendHistory(record) && live instanceof VenueJob job) {
			job.historyUpdated();
		}
	}

	// ========== Job Recovery ==========

	/**
	 * Recovers durable Jobs after a restart, from the venue's entries of
	 * unfinished jobs ({@link JobIndex}). The decision is the owning adapter's
	 * ({@link AAdapter#recoverJob}), whether or not it will start: the
	 * framework rebuilds each Job from its record and entry, binds it to its
	 * installed adapter, registers it, and calls the hook — whatever state the
	 * Job is in afterwards is the durable truth. Jobs still non-terminal are
	 * restored live and re-occupy their caller's concurrency permit. A Job
	 * whose adapter is missing (module not loaded, or its adapter declined or
	 * refused its settings and was never installed) gets the default: PENDING
	 * and STARTED fail as interrupted and are never re-executed; the paused
	 * family is restored.
	 */
	public void recoverJobs() {
		Index<Blob, ACell> entries = venue().jobIndex().all();
		int stabilised = 0, kept = 0;
		java.util.List<VenueJob> restored = new java.util.ArrayList<>();
		for (var e : entries.entrySet()) {
			Blob jobID = e.getKey();
			if (activeJobs.containsKey(jobID)) continue; // already live
			AMap<AString, ACell> entry = RT.ensureMap(e.getValue());
			AString ownerDID = RT.ensureString(RT.getIn(entry, JobIndex.OWNER));
			User user = (ownerDID == null) ? null : venue().users().user(ownerDID);
			JobRecord state = (user == null) ? null : user.jobs().job(jobID);
			// An entry goes with its record and with the update that finishes it.
			AMap<AString, ACell> record = (state == null) ? null : state.getRecord();
			if (record == null || Job.isFinished(record)) continue;
			OutputRules rules = OutputRules.fromCell(RT.getIn(entry, JobIndex.RULES));
			VenueJob job = new VenueJob(state, record, rules, ownerDID, this, owningAdapter(record, ownerDID));
			activeJobs.put(jobID, job);
			restored.add(job);
		}
		// Parent interactions may reference children owned by another user. Restore
		// every handle first so subscriptions bind to the canonical live object.
		for (VenueJob job : restored) {
			if (job.isFinished()) continue;
			Blob jobID = job.getID();
			AAdapter adapter = job.adapter();
			try {
				if (adapter != null) adapter.recoverJob(job);
				else if (engine.remoteJobs().kept(job) != null) engine.remoteJobs().adapterUnavailable(job);
				else AAdapter.defaultRecover(job);
			} catch (RuntimeException e) {
				log.warn("Adapter {} failed recovering job {}: {}",
					(adapter != null) ? adapter.getName() : "<none>", jobID, e.toString());
				if (engine.remoteJobs().kept(job) != null) engine.remoteJobs().unavailable(job, e);
				else if (!job.isFinished()) AAdapter.defaultRecover(job);
			}
			if (job.isFinished()) {
				stabilised++;
			} else {
				reserveRecoveredPermit(jobID, job.accountDID());
				kept++;
			}
		}
		if (stabilised > 0 || kept > 0) {
			log.info("Job recovery: {} stabilised, {} restored live", stabilised, kept);
		}
	}

	/** A restored non-terminal Job re-occupies its owner's concurrency permit. */
	private void reserveRecoveredPermit(Blob jobID, AString ownerDID) {
		if (jobCapEnabled && ownerDID != null) {
			JobSemaphore sem = jobPermits.computeIfAbsent(ownerDID,
				k -> new JobSemaphore(maxConcurrentJobs));
			sem.reserveRecovered();
			permitHolders.put(jobID, ownerDID);
		}
	}

	/**
	 * The adapter that owns the Job whose record is {@code data}
	 * ({@link #adapterName}), started or not: the installed one registered
	 * under its name. Null for a mirror, or when that adapter is missing.
	 */
	private AAdapter owningAdapter(AMap<AString, ACell> data, AString account) {
		String name = adapterName(data, account);
		return (name == null) ? null : engine.getInstalledAdapter(name);
	}

	// ========== Persistence ==========


	User account(AString did) { return engine.getVenueState().users().ensure(did); }

	/** The venue's state: job records with every other area it holds, one transaction for both. */
	VenueState venue() { return engine.getVenueState(); }

	/**
	 * The account {@code job} is kept in: a venue handle's own, else the
	 * account of the caller its record, handed out in full, names.
	 */
	public static AString accountOf(Job job) {
		if (job instanceof VenueJob venueJob) return venueJob.accountDID();
		return accountOf(job.getData());
	}

	/**
	 * The account a job record handed out in full ({@link Attribution#inFull})
	 * is kept in: its caller's, which for an agent is its owner's. A finished
	 * record a delegate left before {@code admin/sudo} acted as the user names
	 * the delegate, and is kept in the user's account, so this reads only
	 * records of work their caller can still do.
	 */
	public static AString accountOf(AMap<AString, ACell> inFull) {
		AString caller = (inFull == null) ? null : RT.ensureString(inFull.get(Fields.CALLER));
		return (caller == null) ? null : Principals.userOf(caller);
	}

	/** Cancel all accepted work owned by a now-deleted account, including transient jobs. */
	void cancelUserJobs(AString did, Job deletion) {
		for (Job job : activeJobs.values()) {
			if (job != deletion && did.equals(accountOf(job))) job.cancel();
		}
	}

	/**
	 * Removes a job from the active cache. Called by {@link VenueJob} when a
	 * state transition lands the job in a terminal status.
	 */
	void evictActive(Blob jobID) {
		activeJobs.remove(jobID);
		releaseJobPermit(jobID);
		synchronized (drain) {
			drain.notifyAll();
		}
	}

	// ========== Adapter Resolution ==========

	/**
	 * Resolves the metadata for a job by looking up the operation reference
	 * in the job record.
	 */
	private AMap<AString, ACell> resolveJobMeta(Job job) {
		return resolveJobMeta(job.getData(), accountOf(job));
	}

	/** The metadata of the operation {@code data}, a job record kept in {@code account}, names. */
	private AMap<AString, ACell> resolveJobMeta(AMap<AString, ACell> data, AString account) {
		AString opStr = RT.ensureString(data.get(Fields.OP));
		if (opStr == null) return null;
		Asset asset = resolveRecordedOp(opStr, account);
		return (asset != null) ? asset.meta() : null;
	}

	/**
	 * Resolves a job record's {@code op}. A record carries the reference that
	 * was invoked (#499): venue paths and bare hashes resolve under the venue
	 * context exactly as before, while a caller-relative reference ({@code o/…},
	 * {@code w/…}) resolves in the namespace the job was accepted in, the
	 * record's owner. Never throws — an
	 * unresolvable reference (renamed catalog entry, unreachable remote) leaves
	 * the job without metadata rather than aborting recovery.
	 */
	Asset resolveRecordedOp(AString opRef, AString ownerDID) {
		try {
			Asset asset = engine.resolveAsset(opRef);
			if (asset == null && ownerDID != null) {
				// Recovery of a job already accepted: the read was checked at submission.
				asset = engine.resolveAssetUnchecked(opRef, RequestContext.of(ownerDID));
			}
			return asset;
		} catch (RuntimeException e) {
			log.warn("Could not resolve recorded op {}: {}", opRef, e.getMessage());
			return null;
		}
	}

	/**
	 * Resolves the adapter responsible for a job: the one its record says it
	 * was dispatched to, else the one its operation names, else null. A
	 * mirror names its call's adapter, but no adapter runs it.
	 * @throws UnavailableException if that adapter is not enabled and
	 *         running here now
	 */
	private AAdapter resolveJobAdapter(Job job) {
		String adapterName = adapterName(job);
		if (adapterName == null) return null;
		requireRunning(adapterName);
		AAdapter adapter = engine.getAdapter(adapterName);
		if (adapter == null) throw new UnavailableException(ADAPTER_NOT_AVAILABLE + adapterName);
		return adapter;
	}

	/**
	 * The name of the adapter that runs a Job: the one its record says it was
	 * dispatched to, else, for a record written before 0.9.9, the one its
	 * operation names; null for a mirror, which no adapter runs.
	 */
	private String adapterName(Job job) {
		return adapterName(job.getData(), accountOf(job));
	}

	private String adapterName(AMap<AString, ACell> data, AString account) {
		if (data.get(MIRROR_OF) != null) return null;
		AString recorded = RT.ensureString(data.get(Fields.ADAPTER));
		if (recorded != null) return recorded.toString();
		AMap<AString, ACell> meta = resolveJobMeta(data, account);
		return (meta != null) ? AAdapter.getAdapterName(meta) : null;
	}

	// ========== Job ID Generation ==========

	/**
	 * Generates a unique job ID.
	 * Format: 6 bytes timestamp + 2 bytes counter + 8 bytes random.
	 * Time-ordered, monotonic, unpredictable, collision-resistant, thread-safe.
	 */
	private Blob generateJobID(long ts) {
		byte[] bs = new byte[16];

		Utils.writeLong(bs, 0, nextSequence(ts));
		Utils.writeLong(bs, 8, rand.nextLong());

		return Blob.wrap(bs);
	}

	/**
	 * The next value of the venue's strictly increasing sequence, which also
	 * leads every Job id, so anything stamped with it orders against Jobs by
	 * when it arrived (an agent's queue, AGENT_SESSIONS.md §5.5.2).
	 */
	public long nextSequence() {
		return nextSequence(engine.currentTimestamp().longValue());
	}

	private long nextSequence(long ts) {
		// Strictly-increasing: a new millisecond resets the counter to 0
		// (ts<<16 wins); a same-millisecond or backwards-clock call advances
		// the counter (prev+1 wins), carrying into the timestamp bits if a single
		// millisecond ever exceeds 65535 values. Lock-free via AtomicLong.
		return idPrefix.updateAndGet(prev -> Math.max(prev + 1, ts << 16));
	}

	// ========== Listeners ==========

	/**
	 * Add a cross-cutting listener notified on every Job state update in the
	 * venue. Used by venue-wide fan-outs (REST SSE broadcasting, MCP
	 * notifications). For per-Job subscriptions use
	 * {@link Job#subscribe(java.util.function.Consumer)} instead.
	 */
	public void addJobUpdateListener(java.util.function.Consumer<Job> listener) {
		this.jobUpdateListeners.add(listener);
	}

	/** Remove a previously-added cross-cutting listener. */
	public boolean removeJobUpdateListener(java.util.function.Consumer<Job> listener) {
		return this.jobUpdateListeners.remove(listener);
	}

	/** Called by {@link VenueJob#onUpdate} to fan each Job update out to the
	 *  cross-cutting listeners. */
	void notifyGlobalListeners(Job job) {
		for (java.util.function.Consumer<Job> l : jobUpdateListeners) {
			try {
				l.accept(job);
			} catch (Throwable t) {
				log.warn("Job update listener threw: {}", t.getMessage());
			}
		}
	}

	// ========== Accessors ==========

	/**
	 * Closes public top-level admission. Internal composition stays open so
	 * in-flight work can still run the sub-operations it needs to finish.
	 */
	void beginShutdown() {
		acceptingTopLevel.set(false);
	}

	/** Closes all admission, top-level and internal, before persistence shutdown. */
	void closeAdmission() {
		acceptingTopLevel.set(false);
		acceptingInternal.set(false);
	}

	/**
	 * Shutdown sequence for Jobs: close top-level admission, let in-flight
	 * work finish for up to {@code graceMs} — returning as soon as nothing is
	 * in flight — then ask each remaining Job's adapter, started or not, to
	 * suspend it ({@link AAdapter#suspendJob}): in-process work is bounded and is
	 * cancelled, a durable wait is recorded and its thread let go. Finally
	 * admission closes completely and every waiter still parked on a
	 * surviving Job's in-memory future is released without touching the
	 * record, so no thread outlives the venue on a Job's account.
	 *
	 * @param graceMs upper bound on the wait for in-flight work; 0 suspends at once
	 */
	void shutdown(long graceMs) {
		beginShutdown();
		awaitDrain(graceMs);
		for (Job job : activeJobs.values()) {
			if (job.isFinished()) continue;
			// The adapter that owns the job decides, started or not
			AAdapter adapter = (job instanceof VenueJob owned) ? owned.adapter() : null;
			try {
				if (adapter != null) adapter.suspendJob(job);
				else if (engine.remoteJobs().kept(job) != null) engine.remoteJobs().suspend(job);
				else AAdapter.defaultSuspend(job);
			} catch (RuntimeException e) {
				log.warn("Adapter {} failed suspending job {}: {}",
					(adapter != null) ? adapter.getName() : "<none>", job.getID(), e.toString());
			}
		}
		closeAdmission();
		for (Job job : activeJobs.values()) {
			if (!job.isFinished()) job.pollingFailed(new ShuttingDownException(AAdapter.VENUE_SHUT_DOWN));
		}
	}

	/** Signalled whenever a Job leaves the active set, so a shutdown drain wakes at once. */
	private final Object drain = new Object();

	/** Cap on one drain wait: catches status-only changes (a Job parking itself) that do not evict. */
	private static final long DRAIN_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

	/**
	 * True while some active Job may still be working: PENDING or STARTED, or
	 * finished with its finish effects still running, such as a cancel hook
	 * telling a remote venue. Every active Job starts unfinished and leaves the
	 * active set when those effects end ({@link #evictActive}), which wakes the
	 * drain.
	 */
	private boolean inFlight() {
		for (Job job : activeJobs.values()) {
			AString s = job.getStatus();
			if (Status.PENDING.equals(s) || Status.STARTED.equals(s) || job.isFinished()) return true;
		}
		return false;
	}

	/** Waits until nothing is in flight or {@code graceMs} has elapsed, whichever is first. */
	private void awaitDrain(long graceMs) {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMs);
		synchronized (drain) {
			while (inFlight()) {
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0) return;
				try {
					TimeUnit.NANOSECONDS.timedWait(drain, Math.min(remaining, DRAIN_POLL_NANOS));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	private void requireAccepting(boolean topLevel) {
		AtomicBoolean gate = topLevel ? acceptingTopLevel : acceptingInternal;
		if (!gate.get()) throw new ShuttingDownException(SHUTTING_DOWN);
	}
}
