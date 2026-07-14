package covia.venue;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
import convex.auth.ucan.Capability;
import covia.lattice.CapabilityChecker;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.core.data.prim.CVMBool;
import convex.core.json.schema.JsonSchema;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.RateLimitException;
import covia.grid.Asset;
import covia.grid.Job;
import covia.grid.Operation;
import covia.grid.Status;

/**
 * Manages job lifecycle, persistence, and in-memory state for active jobs.
 *
 * <p>JobManager is the runtime layer for jobs. It holds an in-memory cache of
 * active (non-terminal) jobs and delegates persistence to per-user lattice
 * storage. Every job has an owner identified by caller DID — there are no
 * anonymous jobs. Venue-internal operations use the venue's own DID.</p>
 *
 * <p>Per-user lattice ({@code :user-data → <DID> → :jobs}) is the single
 * source of truth. The venue-level {@code :jobs} index is not used. Terminal
 * jobs are evicted from in-memory cache — the lattice retains the full
 * history.</p>
 *
 * <p>Dependencies are passed via constructor to avoid circular references
 * with Engine.</p>
 */
public class JobManager {

	private static final Logger log = LoggerFactory.getLogger(JobManager.class);

	private final Engine engine;

	/** In-memory cache of active (non-terminal) jobs */
	private final ConcurrentHashMap<Blob, Job> activeJobs = new ConcurrentHashMap<>();

	/** Per-caller admission semaphores bounding concurrent top-level jobs. */
	private final ConcurrentHashMap<AString, Semaphore> jobPermits = new ConcurrentHashMap<>();

	/** Job IDs holding an admission permit → caller DID, for exactly-once release. */
	private final ConcurrentHashMap<Blob, AString> permitHolders = new ConcurrentHashMap<>();

	/** Concurrent-job cap settings, resolved once from venue config. */
	private final boolean jobCapEnabled;
	private final int maxConcurrentJobs;
	private final long jobBlockMs;

	/** Cross-cutting listeners notified on every Job in the venue (REST SSE,
	 *  MCP notifications). Per-Job subscribers attach directly to the Job
	 *  via {@link Job#subscribe(java.util.function.Consumer)}. */
	private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<Job>> jobUpdateListeners =
		new java.util.concurrent.CopyOnWriteArrayList<>();

	// Job ID generation state
	private final Random rand = new Random();
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
	 * {@code false} when the cap does not apply — disabled, a sub-job (carries a
	 * parent {@code jobId}, e.g. orchestrator / agent fan-out), or an internal /
	 * unattributed caller. Blocks up to {@code jobBlockMs} to absorb bursts, then
	 * sheds with {@link RateLimitException} (→ HTTP 429).
	 */
	private boolean acquireJobPermit(RequestContext ctx, AString callerDID) {
		if (!jobCapEnabled) return false;
		if (ctx.getJobId() != null) return false; // sub-job — exempt, no self-deadlock
		if (callerDID == null) return false;       // internal / unattributed — exempt
		Semaphore sem = jobPermits.computeIfAbsent(callerDID, k -> new Semaphore(maxConcurrentJobs));
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
		if (caller != null) {
			Semaphore sem = jobPermits.get(caller);
			if (sem != null) sem.release();
		}
	}

	// ========== Job Invocation ==========

	/**
	 * Invoke an operation given a reference. Supports hex hash, DID URL,
	 * operation name, and adapter:operation strings. Resolves the reference
	 * to metadata (fetching remote DID-URL definitions if necessary), then
	 * calls the canonical {@link #invokeOperation(AMap, ACell, RequestContext)}.
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
		return invokeOperation(ref, input, ctx, false);
	}

	/**
	 * Invoke with an optional private (memory-only) job (#192): never
	 * persisted — no record in the caller's job index, no recovery, gone on
	 * restart. The live Job serves the caller normally (wait, or polling
	 * while active); on completion it is evicted and forgotten. Requires
	 * {@code enablePrivateJobs} on the venue — a private request against a
	 * venue without it is an error, never a silent downgrade.
	 */
	public Job invokeOperation(AString ref, ACell input, RequestContext ctx, boolean privateJob) {
		if (ref == null) throw new IllegalArgumentException("Operation must be specified");

		Asset asset = engine.resolveAsset(ref, ctx);
		if (asset == null) {
			throw new IllegalArgumentException("Cannot resolve operation: " + ref);
		}

		Operation op = Operation.from(asset);
		if (op == null) {
			throw new IllegalArgumentException("Asset is not an operation: " + asset.getID());
		}

		// Execution is always local: resolution returns a definition (remote
		// refs are FETCHED by resolveAsset, never delegated), so every
		// accepted invoke produces a job on THIS venue. Cross-venue
		// execution is explicit via grid:run / grid:invoke.
		// Scope the context to the caller-supplied reference — the only point
		// the human path form still exists — so requireInvoke can check a
		// resource-precise invoke capability (#211).
		return invokeOperation(op.meta(), input, ctx.withOp(ref), privateJob);
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
		return invokeOperation(meta, input, ctx, false);
	}

	public Job invokeOperation(AMap<AString, ACell> meta, ACell input, RequestContext ctx, boolean privateJob) {
		// Advance the lattice write clock for this dispatch — the harness owns
		// write time (see Engine.refreshWriteClock).
		engine.refreshWriteClock();
		// Capability enforcement is the executing adapter's responsibility: each
		// op asserts its exact (resource, ability) at its own enforcement point
		// (requireCapability / requireInvoke), before any side effect. There is
		// no central name-keyed pre-dispatch check — a denial surfaces as the
		// adapter failing this Job.
		AAdapter adapter = prepareInvocation(meta, input, ctx);
		AString callerDID = ctx.getCallerDID();

		// Admission control: bound concurrent top-level jobs per caller. Blocks up
		// to jobBlockMs to absorb bursts, then sheds with RateLimitException (429).
		// Sub-jobs (parent jobId set) and internal callers are exempt.
		boolean permit = acquireJobPermit(ctx, callerDID);

		// Persist the bare hex hash of the metadata as the op reference.
		// Hex hashes are universally resolvable via the resolvePath bare-hex
		// branch and survive any change in catalog naming or adapter registry.
		AString opID = Strings.create(meta.getHash().toHexString());
		Job job;
		try {
			job = submitJob(opID, meta, input, callerDID, privateJob);
		} catch (RuntimeException e) {
			// Never obtained a jobID to track — release the permit directly.
			if (permit && callerDID != null) {
				Semaphore sem = jobPermits.get(callerDID);
				if (sem != null) sem.release();
			}
			throw e;
		}
		// Track the permit against the job so it is released exactly once when the
		// job reaches a terminal state (evictActive) or is deleted. Recorded
		// before dispatch so a synchronously-completing adapter still finds it.
		if (permit) permitHolders.put(job.getID(), callerDID);

		// Set jobId on context so adapters can use t/ (job-scoped temp).
		// Preserve existing jobId if already set — parent scope takes precedence
		// (e.g. GoalTreeAdapter sets root job ID for all tool calls).
		RequestContext jobCtx = (ctx.getJobId() != null) ? ctx : ctx.withJobId(job.getID());
		try {
			adapter.invoke(job, jobCtx, meta, input);
		} catch (covia.exception.AuthException e) {
			// A capability denial thrown synchronously from a job-aware adapter's
			// enforcement point fails the Job — the same surface async adapters
			// already produce (a denied request was authenticated and dispatched,
			// so the denial is a FAILED Job, not a thrown exception). Other
			// synchronous failures (bad input, schema) propagate as before.
			job.fail(e.getMessage() != null ? e.getMessage() : e.toString());
		}
		return job;
	}

	// ========== Internal Invocation (no Job) ==========

	/**
	 * Invokes an operation without creating a Job. Used for adapter-to-adapter
	 * dispatch inside a single external interaction: the caller's Job is the
	 * only audit-relevant record, and creating sub-Jobs for transition /
	 * LLM / tool calls just adds persistence and lifecycle overhead.
	 *
	 * <p>Performs the same resolve / caps / schema / adapter-lookup plumbing
	 * as {@link #invokeOperation(AString, ACell, RequestContext)}, but skips
	 * Job creation, activeJobs tracking, persistence, and update listeners.
	 * Calls {@link AAdapter#invokeFuture} directly and returns its future.</p>
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
		if (ref == null) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("Operation must be specified"));
		}

		Asset asset;
		try {
			asset = engine.resolveAsset(ref, ctx);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
		if (asset == null) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("Cannot resolve operation: " + ref));
		}

		Operation op = Operation.from(asset);
		if (op == null) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("Asset is not an operation: " + asset.getID()));
		}

		// Always local — see invokeOperation(AString,...): resolution
		// returns definitions, never remote execution handles.
		// Scope to the caller-supplied reference for resource-precise
		// invoke-gating (#211) — this seam also covers the agent tool loop,
		// which dispatches config.tools references through here.
		return invokeInternal(op.meta(), input, ctx.withOp(ref));
	}

	public CompletableFuture<ACell> invokeInternal(AMap<AString, ACell> meta, ACell input, RequestContext ctx) {
		// Advance the lattice write clock — internal dispatch (agent transitions,
		// tool calls) is high-frequency, so long cycles keep stamping fresh time.
		engine.refreshWriteClock();
		AAdapter adapter;
		try {
			// Enforcement is the adapter's responsibility (see invokeOperation):
			// the op asserts its cap at its enforcement point; a denial surfaces
			// as a failed future. invokeInternal differs from invokeOperation only
			// in Job creation.
			adapter = prepareInvocation(meta, input, ctx);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
		try {
			CompletableFuture<ACell> f = adapter.invokeFuture(ctx, meta, input);
			return (f != null) ? f : CompletableFuture.completedFuture(null);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
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
	public void validateOutput(AMap<AString, ACell> meta, ACell result) {
		String mode = engine.config().getOutputValidation();
		if (mode == null || "off".equals(mode)) return;
		AMap<AString, ACell> outputSchema = getMap(RT.getIn(meta, Fields.OPERATION, Fields.OUTPUT));
		if (outputSchema == null || outputSchema.isEmpty()) return;
		String err = JsonSchema.validate(outputSchema, result);
		if (err == null) return;
		if ("strict".equals(mode)) {
			throw new IllegalArgumentException("Output schema violation: " + err);
		}
		log.warn("Output schema violation for {}: {}", AAdapter.getAdapterName(meta), err);
	}

	/**
	 * Shared invocation prelude: validates metadata, auth, caps, schema;
	 * returns the adapter ready to invoke. Used by both
	 * {@link #invokeOperation(AMap, ACell, RequestContext)} (Job path) and
	 * {@link #invokeInternal(AMap, ACell, RequestContext)} (no-Job path).
	 *
	 * @throws IllegalArgumentException invalid meta or schema violation
	 * @throws AuthException caller not authenticated
	 * @throws RuntimeException capability denied
	 * @throws IllegalStateException adapter not registered
	 */
	private AAdapter prepareInvocation(AMap<AString, ACell> meta, ACell input, RequestContext ctx) {
		if (meta == null) throw new IllegalArgumentException("Metadata must be specified");
		AString callerDID = ctx.getCallerDID();
		if (callerDID == null) throw new AuthException("Authentication required");

		String adapterName = AAdapter.getAdapterName(meta);
		if (adapterName == null) {
			throw new IllegalArgumentException("Metadata must contain operation.adapter field");
		}

		// Validate input against operation schema when strict mode is enabled
		if (CVMBool.TRUE.equals(RT.getIn(meta, Fields.OPERATION, K_STRICT))) {
			AMap<AString, ACell> inputSchema = getMap(RT.getIn(meta, Fields.OPERATION, Fields.INPUT));
			if (inputSchema != null && !inputSchema.isEmpty()) {
				String schemaErr = JsonSchema.validate(inputSchema, input);
				if (schemaErr != null) {
					throw new IllegalArgumentException("Input schema violation: " + schemaErr);
				}
			}
		}

		AAdapter adapter = engine.getAdapter(adapterName);
		if (adapter == null) {
			throw new IllegalStateException("Adapter not available: " + adapterName);
		}
		return adapter;
	}

	private static final AString K_STRICT = Strings.intern("strict");

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> getMap(ACell cell) {
		return (cell instanceof AMap) ? (AMap<AString, ACell>) cell : null;
	}

	// ========== Secret Redaction ==========

	private static final AString K_SECRET_FIELDS = Strings.intern("secretFields");

	/**
	 * Returns a copy of {@code input} with secret fields replaced by {@link Fields#HIDDEN}.
	 * Secret fields are declared in {@code meta.operation.secretFields} as a vector of field names.
	 * If there are no secret fields (or input is not a map), returns input unchanged.
	 */
	@SuppressWarnings("unchecked")
	private static ACell redactSecrets(ACell input, AMap<AString, ACell> meta) {
		if (!(input instanceof AMap)) return input;
		ACell sf = RT.getIn(meta, Fields.OPERATION, K_SECRET_FIELDS);
		if (!(sf instanceof AVector)) return input;

		AMap<AString, ACell> map = (AMap<AString, ACell>) input;
		AVector<ACell> secretFields = (AVector<ACell>) sf;
		for (long i = 0; i < secretFields.count(); i++) {
			AString field = RT.ensureString(secretFields.get(i));
			if (field != null && map.containsKey(field)) {
				map = map.assoc(field, Fields.HIDDEN);
			}
		}
		return map;
	}

	/**
	 * Returns true if the stored input contains any redacted secret values.
	 */
	@SuppressWarnings("unchecked")
	private static boolean hasRedactedSecrets(ACell input, AMap<AString, ACell> meta) {
		if (!(input instanceof AMap)) return false;
		ACell sf = RT.getIn(meta, Fields.OPERATION, K_SECRET_FIELDS);
		if (!(sf instanceof AVector)) return false;

		AMap<AString, ACell> map = (AMap<AString, ACell>) input;
		AVector<ACell> secretFields = (AVector<ACell>) sf;
		for (long i = 0; i < secretFields.count(); i++) {
			AString field = RT.ensureString(secretFields.get(i));
			if (field != null && Fields.HIDDEN.equals(map.get(field))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns a copy of job data with secret fields in the output redacted.
	 * Uses the same {@code secretFields} list as input redaction.
	 */
	static AMap<AString, ACell> redactOutputSecrets(AMap<AString, ACell> jobData, AMap<AString, ACell> meta) {
		ACell output = jobData.get(Fields.OUTPUT);
		if (output == null) return jobData;
		ACell redacted = redactSecrets(output, meta);
		if (redacted == output) return jobData;
		return jobData.assoc(Fields.OUTPUT, redacted);
	}

	// ========== Job Submission ==========

	/**
	 * Submit a Job for an operation.
	 */
	private Job submitJob(AString opID, AMap<AString, ACell> meta, ACell input, AString callerDID,
			boolean privateJob) {
		if (callerDID == null) throw new AuthException("Authentication required");
		if (privateJob && !engine.config().isPrivateJobsEnabled()) {
			throw new IllegalArgumentException(
				"Private jobs are not enabled on this venue (set enablePrivateJobs in venue config)");
		}

		long ts = Utils.getCurrentTimestamp();
		Blob jobID = generateJobID(ts);

		AMap<AString, ACell> status = Maps.of(
				Fields.ID, jobID,
				Fields.OP, opID,
				Fields.STATUS, Status.PENDING,
				Fields.UPDATED, CVMLong.create(ts),
				Fields.CREATED, CVMLong.create(ts),
				Fields.INPUT, redactSecrets(input, meta),
				Fields.CALLER, callerDID);

		AString name = RT.ensureString(RT.getIn(meta, Fields.NAME));
		if (name != null) {
			status = status.assoc(Fields.NAME, name);
		}

		VenueJob job = new VenueJob(status, meta, callerDID, this, privateJob);
		activeJobs.put(jobID, job);

		// Persist initial job record (no-op for a private job, #192)
		persistJobRecord(jobID, status, callerDID);

		log.info("Submitted job: {}", jobID);
		return job;
	}

	// ========== Job Queries ==========

	/**
	 * Gets a snapshot of the current job status data with request context.
	 * Checks in-memory cache first, falls back to user's lattice.
	 * @throws AuthException if the caller does not own the job
	 */
	public AMap<AString, ACell> getJobData(Blob jobID, RequestContext ctx) {
		// 1. Hot cache (active jobs)
		Job job;
		job = activeJobs.get(jobID);
		if (job != null) {
			if (!canReadJob(ctx, jobID, job.getData())) {
				throw new AuthException("Access denied to job: " + jobID.toHexString());
			}
			return job.getData();
		}

		// 2. User's lattice (authoritative). Scoped to the caller's own jobs;
		// a delegated read of another user's *terminal* (evicted) job goes via
		// the explicit DID-URL path (covia:read did:<owner>/j/<id>).
		AString did = ctx.getCallerDID();
		if (did == null) return null;
		User user = engine.getVenueState().users().get(did);
		if (user == null) return null;
		return user.getJob(jobID);
	}

	/**
	 * Read authorisation for a job: the owner, or a caller presenting a UCAN
	 * proof granting {@code crud/read} on the job resource {@code <owner>/j/<id>}
	 * — the same right as {@code covia:read did:<owner>/j/<id>} (covia#102), via
	 * the shared {@link CapabilityChecker#proofsCover} check. Reads are
	 * delegable like any other read; job *mutations* remain owner-only
	 * ({@link #requireJobOwner}).
	 */
	private boolean canReadJob(RequestContext ctx, Blob jobID, AMap<AString, ACell> data) {
		if (engine.getAccessControl().canAccessJob(ctx, data)) return true; // owner
		AString owner = RT.ensureString(data.get(Fields.CALLER));
		if (owner == null) return false; // venue-internal job — owner/internal only
		AString resource = owner.append("/j/" + jobID.toHexString());
		return CapabilityChecker.proofsCover(ctx.getProofs(), ctx.getCallerDID(),
			engine.getDIDString(), resource, Capability.CRUD_READ,
			System.currentTimeMillis() / 1000);
	}

	/**
	 * Owner-only gate for job <em>mutations</em> (cancel/pause/resume/delete).
	 * A {@code crud/read} delegation lets a caller read a job but not mutate it,
	 * so mutations check ownership explicitly rather than relying on the
	 * read-delegable {@link #getJobData(Blob, RequestContext)}.
	 */
	private void requireJobOwner(RequestContext ctx, Blob jobID, AMap<AString, ACell> data) {
		if (!engine.getAccessControl().canAccessJob(ctx, data)) {
			throw new AuthException("Access denied to job: " + jobID.toHexString());
		}
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
	 * Gets the live Job object with lattice fallback using request context.
	 */
	public Job getJob(Blob jobID, RequestContext ctx) {
		Job job = activeJobs.get(jobID);
		if (job != null) return job;

		// Fall back to user's lattice
		AString did = ctx.getCallerDID();
		if (did == null) return null;
		User user = engine.getVenueState().users().get(did);
		if (user == null) return null;
		AMap<AString, ACell> record = user.getJob(jobID);
		if (record == null) return null;
		return new Job(record);
	}

	/**
	 * Gets the jobs Index for the given request context.
	 * Reads directly from the user's per-user lattice.
	 */
	public Index<Blob, ACell> getJobs(RequestContext ctx) {
		AString did = ctx.getCallerDID();
		if (did == null) return Index.none();
		User user = engine.getVenueState().users().get(did);
		if (user == null) return Index.none();
		return user.getJobs();
	}

	// ========== Job Lifecycle Control ==========

	public void updateJobStatus(Blob jobID, AMap<AString, ACell> newData) {
		Job job;
		job = activeJobs.get(jobID);
		if (job == null) return;
		job.updateData(newData);
	}

	/**
	 * Cancels a Job with request context.
	 * @throws AuthException if the caller does not own the job
	 */
	public AMap<AString, ACell> cancelJob(Blob id, RequestContext ctx) {
		AMap<AString, ACell> data = getJobData(id, ctx);
		if (data == null) return null;
		requireJobOwner(ctx, id, data); // mutation: owner-only, not read-delegable
		return cancelJob(id);
	}

	public AMap<AString, ACell> cancelJob(Blob id) {
		Job job;
		job = activeJobs.get(id);
		if (job == null) return null;
		job.cancel();
		return job.getData();
	}

	/**
	 * Pauses a running Job with request context.
	 * @throws AuthException if the caller does not own the job
	 */
	public AMap<AString, ACell> pauseJob(Blob id, RequestContext ctx) {
		AMap<AString, ACell> data = getJobData(id, ctx);
		if (data == null) return null;
		requireJobOwner(ctx, id, data); // mutation: owner-only
		return pauseJob(id);
	}

	public AMap<AString, ACell> pauseJob(Blob id) {
		Job job;
		job = activeJobs.get(id);
		if (job == null) return null;
		job.pause();
		return job.getData();
	}

	/**
	 * Resumes a paused Job with request context.
	 * @throws AuthException if the caller does not own the job
	 */
	public AMap<AString, ACell> resumeJob(Blob id, RequestContext ctx) {
		AMap<AString, ACell> data = getJobData(id, ctx);
		if (data == null) return null;
		requireJobOwner(ctx, id, data); // mutation: owner-only
		return resumeJob(id);
	}

	public AMap<AString, ACell> resumeJob(Blob id) {
		Job job;
		job = activeJobs.get(id);
		if (job == null) return null;
		job.resume();

		// Re-engage the adapter
		AAdapter adapter = resolveJobAdapter(job);
		if (adapter != null) {
			AMap<AString, ACell> meta = resolveJobMeta(job);
			AString callerDID = RT.ensureString(job.getData().get(Fields.CALLER));
			RequestContext ctx = (callerDID != null) ? RequestContext.of(callerDID) : engine.venueContext();
			if (meta != null) {
				adapter.invoke(job, ctx, meta, job.getData().get(Fields.INPUT));
			}
		}

		return job.getData();
	}

	/**
	 * Deletes a job permanently with request context: removes the durable
	 * record from the owning user's job index (mirror of
	 * {@link #persistJobRecord}) and evicts the active cache.
	 * @throws AuthException if the caller does not own the job
	 */
	public boolean deleteJob(Blob id, RequestContext ctx) {
		AMap<AString, ACell> data = getJobData(id, ctx);
		if (data == null) return false;
		requireJobOwner(ctx, id, data); // mutation: owner-only
		AString ownerDID = RT.ensureString(data.get(Fields.CALLER));
		if (ownerDID != null) {
			User user = engine.getVenueState().users().get(ownerDID);
			if (user != null) user.removeJob(id);
		}
		// Remove from active cache if present (may already be evicted for terminal jobs)
		deleteJob(id);
		return true;
	}

	public boolean deleteJob(Blob id) {
		activeJobs.remove(id);
		releaseJobPermit(id);
		return true;
	}

	// ========== Message Delivery ==========

	/**
	 * Delivers a message to a job's message queue with request context.
	 * @throws AuthException if the caller does not own the job
	 */
	public int deliverMessage(Blob jobID, AMap<AString, ACell> message, RequestContext ctx) {
		AMap<AString, ACell> data = getJobData(jobID);
		if (data != null && !engine.getAccessControl().canAccessJob(ctx, data)) {
			throw new AuthException("Access denied to job: " + jobID.toHexString());
		}
		return deliverMessage(jobID, message, ctx.getCallerDID());
	}

	/**
	 * Delivers a message to a job's message queue.
	 */
	public int deliverMessage(Blob jobID, AMap<AString, ACell> message, AString source) {
		Job job = getJob(jobID);
		if (job == null) throw new IllegalArgumentException("Job not found: " + jobID.toHexString());
		if (job.isFinished()) throw new IllegalStateException("Job is in terminal state: " + jobID.toHexString());

		long ts = Utils.getCurrentTimestamp();
		Blob msgId = generateJobID(ts);

		AMap<AString, ACell> record = Maps.of(
				Fields.MESSAGE, message,
				Fields.TS, CVMLong.create(ts),
				Fields.ID, msgId);
		if (source != null) {
			record = record.assoc(Fields.SOURCE, source);
		}

		// Dispatch to adapter if it supports multi-turn. The message record is
		// passed directly — Job no longer buffers a per-job message queue (the
		// previous queue was write-only and never drained).
		AAdapter adapter = resolveJobAdapter(job);
		if (adapter != null && adapter.supportsMultiTurn()) {
			adapter.handleMessage(job, record);
		}

		return 0;
	}

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
	 * @throws AuthException if the caller does not own the job
	 */
	@SuppressWarnings("unchecked")
	public void appendToHistory(Blob jobID, AMap<AString, ACell> record, RequestContext ctx) {
		// Read the current record from the active cache, falling back to the
		// caller's lattice. The ctx-aware read both enforces ownership and finds
		// a job that has already completed and been evicted from the active
		// cache — e.g. a synchronously-completing A2A defaultChatOp, which the
		// old activeJobs-only lookup missed, throwing "Job not found" and
		// dropping the inbound message from the Task history (#178).
		AMap<AString, ACell> current = getJobData(jobID, ctx);
		if (current == null) {
			throw new IllegalArgumentException("Job not found: " + jobID.toHexString());
		}

		ACell histCell = current.get(Fields.HISTORY);
		AVector<ACell> history = (histCell instanceof AVector)
				? (AVector<ACell>) histCell
				: convex.core.data.Vectors.empty();
		AMap<AString, ACell> newData = current.assoc(Fields.HISTORY, history.conj(record));

		// Write back where the record actually lives: mutate the live job if it
		// is still active (persisted on completion), otherwise persist the
		// augmented record straight to the lattice so a finished job's Task
		// history still carries the inbound message rather than silently losing it.
		Job job = getJob(jobID);
		if (job != null) {
			job.updateData(newData);
		} else {
			persistJobRecord(jobID, newData, ctx.getCallerDID());
		}
	}

	// ========== Job Recovery ==========

	/**
	 * Recovers jobs from the lattice after a restart.
	 * Walks all users' job indices. PENDING/STARTED jobs are re-fired;
	 * PAUSED/INPUT_REQUIRED/AUTH_REQUIRED are restored as live objects.
	 * Terminal jobs are skipped.
	 */
	@SuppressWarnings("unchecked")
	public void recoverJobs() {
		AMap<AString, ACell> userData = engine.getVenueState().users().getAll();
		if (userData == null || userData.isEmpty()) return;

		int refired = 0, kept = 0, failed = 0;
		for (var entry : userData.entrySet()) {
			AString did = (AString) entry.getKey();
			User user = engine.getVenueState().users().get(did);
			if (user == null) continue;

			Index<Blob, ACell> userJobs = user.getJobs();
			long n = userJobs.count();
			for (long i = 0; i < n; i++) {
				var jobEntry = userJobs.entryAt(i);
				Blob jobID = (Blob) jobEntry.getKey();
				ACell value = jobEntry.getValue();
				if (!(value instanceof AMap)) continue;
				AMap<AString, ACell> record = (AMap<AString, ACell>) value;

				// Skip jobs already in memory
				if (activeJobs.containsKey(jobID)) continue;

				// Skip terminal jobs
				if (Job.isFinished(record)) continue;

				AString status = RT.ensureString(record.get(Fields.STATUS));
				if (Status.PENDING.equals(status) || Status.STARTED.equals(status)) {
					if (refireJob(jobID, record, did)) {
						refired++;
					} else {
						failed++;
					}
				} else {
					restoreJob(jobID, record, did);
					kept++;
				}
			}
		}

		if (refired > 0 || kept > 0 || failed > 0) {
			log.info("Job recovery: {} re-fired, {} kept (paused/waiting), {} failed", refired, kept, failed);
		}
	}

	/**
	 * Re-fires a PENDING/STARTED job from a persisted lattice record.
	 */
	private boolean refireJob(Blob jobID, AMap<AString, ACell> record, AString callerDID) {
		AString opRef = RT.ensureString(record.get(Fields.OP));
		if (opRef == null) {
			markJobFailed(jobID, record, "Cannot re-fire: no operation reference", callerDID);
			return false;
		}

		// Resolve operation
		Asset asset = engine.resolveAsset(opRef);
		Operation op = (asset != null) ? Operation.from(asset) : null;

		// Resolve adapter
		AAdapter adapter = resolveAdapterForOp(op, opRef);
		if (adapter == null) {
			markJobFailed(jobID, record, "Cannot re-fire: adapter not available for " + opRef, callerDID);
			return false;
		}

		// Resolve metadata before creating Job (needed for output redaction in processUpdate)
		AMap<AString, ACell> meta = (op != null) ? op.meta() : null;

		// Create a live Job wrapping the persisted record
		VenueJob job = new VenueJob(record, meta, callerDID, this);

		activeJobs.put(jobID, job);
		RequestContext ctx = RequestContext.of(callerDID);
		if (meta == null) {
			markJobFailed(jobID, record, "Cannot re-fire: no operation metadata for " + opRef, callerDID);
			return false;
		}

		// Fail fast if stored input contains redacted secrets
		if (hasRedactedSecrets(record.get(Fields.INPUT), meta)) {
			markJobFailed(jobID, record, "Cannot re-fire: job contains redacted secrets", callerDID);
			return false;
		}

		// Replay the EFFECTIVE input: the caller's original input plus any
		// sessionId the first invocation minted (stamped at the record top
		// level by agent:chat / agent:request). Without it, a re-fired
		// session-minting job mints a FRESH session instead of continuing the
		// one it created — producing a spurious duplicate session and a stale
		// answer to the caller, distinct from the boot-scan resume of the
		// original session.
		ACell input = record.get(Fields.INPUT);
		ACell sid = record.get(Fields.SESSION_ID);
		if (sid != null && input instanceof AMap<?, ?> && RT.getIn(input, Fields.SESSION_ID) == null) {
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> im = (AMap<AString, ACell>) input;
			input = im.assoc(Fields.SESSION_ID, sid);
		}

		adapter.invoke(job, ctx, meta, input);
		log.info("Re-fired job: {}", jobID);
		return true;
	}

	/**
	 * Restores a paused/waiting job into the in-memory cache.
	 */
	private void restoreJob(Blob jobID, AMap<AString, ACell> record, AString callerDID) {
		AString opRef = RT.ensureString(record.get(Fields.OP));
		Operation op = null;
		if (opRef != null) {
			Asset asset = engine.resolveAsset(opRef);
			op = (asset != null) ? Operation.from(asset) : null;
		}

		AMap<AString, ACell> meta = (op != null) ? op.meta() : null;
		VenueJob job = new VenueJob(record, meta, callerDID, this);

		activeJobs.put(jobID, job);
	}

	private void markJobFailed(Blob jobID, AMap<AString, ACell> record, String reason, AString callerDID) {
		AMap<AString, ACell> failedRecord = record
				.assoc(Fields.STATUS, Status.FAILED)
				.assoc(Fields.ERROR, Strings.create(reason))
				.assoc(Fields.UPDATED, CVMLong.create(Utils.getCurrentTimestamp()));
		persistJobRecord(jobID, failedRecord, callerDID);
		log.warn("Job {} failed on recovery: {}", jobID, reason);
	}

	// ========== Persistence ==========

	/**
	 * Persists a job record to the user's per-user lattice.
	 * This is the single source of truth for all jobs.
	 */
	void persistJobRecord(Blob jobID, AMap<AString, ACell> record, AString callerDID) {
		// Private (memory-only) job (#192): the single persistence choke point
		// is a no-op — nothing about the job ever touches the lattice. The job
		// is still in the active cache here for every persist trigger (initial
		// submit, status updates, history appends): terminal eviction happens
		// AFTER the final persist call.
		Job j = activeJobs.get(jobID);
		if (j instanceof VenueJob vj && vj.isPrivate()) return;
		User user = engine.getVenueState().users().ensure(callerDID);
		user.persistJob(jobID, record);
	}

	/**
	 * Removes a job from the active cache. Called by {@link VenueJob} when a
	 * state transition lands the job in a terminal status.
	 */
	void evictActive(Blob jobID) {
		activeJobs.remove(jobID);
		releaseJobPermit(jobID);
	}

	// ========== Adapter Resolution ==========

	/**
	 * Resolves the metadata for a job by looking up the operation reference
	 * in the job record.
	 */
	private AMap<AString, ACell> resolveJobMeta(Job job) {
		AString opStr = RT.ensureString(job.getData().get(Fields.OP));
		if (opStr == null) return null;
		Asset asset = engine.resolveAsset(opStr);
		return (asset != null) ? asset.meta() : null;
	}

	/**
	 * Resolves the adapter responsible for a job.
	 */
	private AAdapter resolveJobAdapter(Job job) {
		AMap<AString, ACell> meta = resolveJobMeta(job);
		if (meta != null) {
			String adapterName = AAdapter.getAdapterName(meta);
			if (adapterName != null) return engine.getAdapter(adapterName);
		}
		return null;
	}

	/**
	 * Resolves the adapter for an operation from its metadata.
	 */
	private AAdapter resolveAdapterForOp(Operation op, AString opRef) {
		if (op != null) {
			String adapterName = AAdapter.getAdapterName(op.meta());
			if (adapterName != null) {
				AAdapter adapter = engine.getAdapter(adapterName);
				if (adapter != null) return adapter;
			}
		}
		return null;
	}

	// ========== Job ID Generation ==========

	/**
	 * Generates a unique job ID.
	 * Format: 6 bytes timestamp + 2 bytes counter + 8 bytes random.
	 * Time-ordered, monotonic, unpredictable, collision-resistant, thread-safe.
	 */
	private Blob generateJobID(long ts) {
		// Strictly-increasing prefix: a new millisecond resets the counter to 0
		// (ts<<16 wins); a same-millisecond or backwards-clock submission advances
		// the counter (prev+1 wins), carrying into the timestamp bits if a single
		// millisecond ever exceeds 65535 IDs. Lock-free via AtomicLong.
		long prefix = idPrefix.updateAndGet(prev -> Math.max(prev + 1, ts << 16));
		byte[] bs = new byte[16];

		Utils.writeLong(bs, 0, prefix);
		Utils.writeLong(bs, 8, rand.nextLong());

		return Blob.wrap(bs);
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
	 * Gets the venue DID used for internal job ownership.
	 */
	public AString getVenueDID() {
		return engine.getDIDString();
	}
}
