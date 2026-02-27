package covia.venue;

import java.util.HashMap;
import java.util.Random;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.grid.Asset;
import covia.grid.Job;
import covia.grid.Operation;
import covia.grid.Status;
import covia.grid.Venue;

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

	private final VenueState venueState;
	private final AccessControl accessControl;
	private final Function<String, AAdapter> adapterLookup;
	private final Function<AString, Asset> assetResolver;
	private final AString venueDID;

	/** In-memory cache of active (non-terminal) jobs */
	private final HashMap<Blob, Job> activeJobs = new HashMap<>();

	/** Listeners notified on every job state update (SSE, MCP notifications) */
	private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<Job>> jobUpdateListeners =
		new java.util.concurrent.CopyOnWriteArrayList<>();

	// Job ID generation state
	private final Random rand = new Random();
	private short jobCounter = 0;
	private long lastJobTS = 0;

	/**
	 * Creates a new JobManager.
	 *
	 * @param venueState Venue lattice state (for per-user job persistence)
	 * @param accessControl Access control for ownership checks
	 * @param adapterLookup Resolves adapter name to AAdapter instance
	 * @param assetResolver Resolves operation reference string to Asset
	 * @param venueDID Venue's own DID (used as caller for internal jobs)
	 */
	public JobManager(VenueState venueState, AccessControl accessControl,
			Function<String, AAdapter> adapterLookup,
			Function<AString, Asset> assetResolver,
			AString venueDID) {
		this.venueState = venueState;
		this.accessControl = accessControl;
		this.adapterLookup = adapterLookup;
		this.assetResolver = assetResolver;
		this.venueDID = venueDID;
	}

	// ========== Job Invocation ==========

	/**
	 * Invoke an operation given a reference with request context.
	 */
	public Job invokeOperation(AString ref, ACell input, RequestContext ctx) {
		return invokeOperation(ref, input, ctx.getCallerDID());
	}

	/**
	 * Invoke an operation given a reference. Supports hex hash, DID URL,
	 * operation name, and adapter:operation strings. The reference is always
	 * resolved to full metadata before dispatching to the adapter.
	 * @param ref Operation reference (AString)
	 * @param input Input parameters
	 * @param callerDID Caller DID string (required, non-null)
	 * @return Job tracking the execution
	 */
	public Job invokeOperation(AString ref, ACell input, AString callerDID) {
		if (ref == null) throw new IllegalArgumentException("Operation must be specified");
		if (callerDID == null) throw new IllegalArgumentException("callerDID is required");

		// Resolve the operation reference (hex hash, DID URL, or operation name)
		Asset asset = assetResolver.apply(ref);
		if (asset == null) {
			throw new IllegalArgumentException("Cannot resolve operation: " + ref);
		}
		return invokeOperation(asset, input, callerDID);
	}

	/**
	 * Invoke an operation given a resolved Asset. If the asset has a remote venue
	 * reference, delegates to the remote venue via asset.invoke().
	 * @param asset The operation asset
	 * @param input Input parameters
	 * @param callerDID Caller DID string (required, non-null)
	 * @return Job tracking the execution
	 */
	public Job invokeOperation(Asset asset, ACell input, AString callerDID) {
		if (asset == null) throw new IllegalArgumentException("Asset must be specified");
		if (callerDID == null) throw new IllegalArgumentException("callerDID is required");

		// Ensure we have an Operation (not a plain Asset)
		Operation op = Operation.from(asset);
		if (op == null) {
			throw new IllegalArgumentException("Asset is not an operation: " + asset.getID());
		}

		// Check for remote operation — delegate to the operation's venue
		Venue opVenue = op.getVenue();
		if (opVenue != null && !(opVenue instanceof LocalVenue)) {
			return op.invoke(input).join();
		}

		// Local operation — dispatch via adapter
		AMap<AString, ACell> meta = op.meta();
		String adapterName = AAdapter.getAdapterName(meta);
		if (adapterName == null) {
			throw new IllegalArgumentException("Operation metadata missing operation.adapter: " + asset.getID());
		}
		AAdapter adapter = adapterLookup.apply(adapterName);
		if (adapter == null) {
			throw new IllegalStateException("Adapter not available: " + adapterName);
		}

		RequestContext ctx = RequestContext.of(callerDID);
		Job job = submitJob(op, meta, input, callerDID);
		adapter.invoke(job, ctx, meta, input);
		return job;
	}

	/**
	 * Invoke an operation given literal metadata. Skips resolution — uses the
	 * metadata map directly. The adapter name is extracted from
	 * {@code meta.operation.adapter}.
	 *
	 * @param meta Operation metadata (must contain operation.adapter)
	 * @param input Input parameters
	 * @param ctx Request context (caller identity)
	 * @return Job tracking the execution
	 */
	public Job invokeOperation(AMap<AString, ACell> meta, ACell input, RequestContext ctx) {
		if (meta == null) throw new IllegalArgumentException("Metadata must be specified");
		AString callerDID = ctx.isInternal() ? venueDID : ctx.getCallerDID();
		if (callerDID == null) throw new IllegalArgumentException("callerDID is required");

		String adapterName = AAdapter.getAdapterName(meta);
		if (adapterName == null) {
			throw new IllegalArgumentException("Metadata must contain operation.adapter field");
		}
		AAdapter adapter = adapterLookup.apply(adapterName);
		if (adapter == null) {
			throw new IllegalStateException("Adapter not available: " + adapterName);
		}

		AString adapterOp = RT.ensureString(RT.getIn(meta, "operation", "adapter"));
		Job job = submitJob(adapterOp, meta, input, null, callerDID);
		adapter.invoke(job, ctx, meta, input);
		return job;
	}

	// ========== Job Submission ==========

	/**
	 * Submit a Job for a resolved Operation.
	 */
	private Job submitJob(Operation operation, AMap<AString, ACell> meta, ACell input, AString callerDID) {
		return submitJob(operation.getID().toCVMHexString(), meta, input, operation, callerDID);
	}

	/**
	 * Submit a Job for an operation.
	 */
	private Job submitJob(AString opID, AMap<AString, ACell> meta, ACell input, Operation operation, AString callerDID) {
		if (callerDID == null) throw new IllegalArgumentException("callerDID is required");

		long ts = Utils.getCurrentTimestamp();
		Blob jobID = generateJobID(ts);

		AMap<AString, ACell> status = Maps.of(
				Fields.ID, jobID,
				Fields.OP, opID,
				Fields.STATUS, Status.PENDING,
				Fields.UPDATED, CVMLong.create(ts),
				Fields.CREATED, CVMLong.create(ts),
				Fields.INPUT, input,
				Fields.CALLER, callerDID);

		AString name = RT.ensureString(RT.getIn(meta, Fields.NAME));
		if (name != null) {
			status = status.assoc(Fields.NAME, name);
		}

		final AString effectiveCaller = callerDID;
		Job job = new Job(status, operation) {
			@Override public AMap<AString, ACell> processUpdate(AMap<AString, ACell> newData) {
				newData = newData.assoc(Fields.UPDATED, CVMLong.create(Utils.getCurrentTimestamp()));
				persistJobRecord(getID(), newData, effectiveCaller);
				if (Job.isFinished(newData)) {
					synchronized (activeJobs) {
						activeJobs.remove(getID());
					}
				}
				return newData;
			}
		};

		// Set update listener if configured (e.g. for SSE broadcasting)
		if (!jobUpdateListeners.isEmpty()) {
			job.setUpdateListener(j -> jobUpdateListeners.forEach(l -> l.accept(j)));
		}

		synchronized (activeJobs) {
			activeJobs.put(jobID, job);
		}

		// Persist initial job record
		persistJobRecord(jobID, status, callerDID);

		log.info("Submitted job: {}", jobID);
		return job;
	}

	// ========== Job Queries ==========

	/**
	 * Gets a snapshot of the current job status data with request context.
	 * Checks in-memory cache first, falls back to user's lattice.
	 * @throws SecurityException if the caller does not own the job
	 */
	public AMap<AString, ACell> getJobData(Blob jobID, RequestContext ctx) {
		// 1. Hot cache (active jobs)
		Job job;
		synchronized (activeJobs) {
			job = activeJobs.get(jobID);
		}
		if (job != null) {
			if (!accessControl.canAccessJob(ctx, job.getData())) {
				throw new SecurityException("Access denied to job: " + jobID.toHexString());
			}
			return job.getData();
		}

		// 2. User's lattice (authoritative)
		AString did = ctx.isInternal() ? venueDID : ctx.getCallerDID();
		if (did == null) return null;
		User user = venueState.users().get(did);
		if (user == null) return null;
		return user.jobs().get(jobID);
	}

	/**
	 * Gets a snapshot of the current job status data (internal use).
	 * Checks in-memory cache only — no lattice fallback without user context.
	 */
	public AMap<AString, ACell> getJobData(Blob jobID) {
		Job job;
		synchronized (activeJobs) {
			job = activeJobs.get(jobID);
		}
		if (job != null) return job.getData();
		return null;
	}

	/**
	 * Gets the live Job object for the given job ID.
	 * Checks in-memory cache first, falls back to constructing a read-only Job from lattice.
	 */
	public Job getJob(Blob jobID) {
		synchronized (activeJobs) {
			Job job = activeJobs.get(jobID);
			if (job != null) return job;
		}
		return null;
	}

	/**
	 * Gets the live Job object with lattice fallback using request context.
	 */
	public Job getJob(Blob jobID, RequestContext ctx) {
		synchronized (activeJobs) {
			Job job = activeJobs.get(jobID);
			if (job != null) return job;
		}

		// Fall back to user's lattice
		AString did = ctx.isInternal() ? venueDID : ctx.getCallerDID();
		if (did == null) return null;
		User user = venueState.users().get(did);
		if (user == null) return null;
		AMap<AString, ACell> record = user.jobs().get(jobID);
		if (record == null) return null;
		return new Job(record, null);
	}

	/**
	 * Gets the jobs Index for the given request context.
	 * Reads directly from the user's per-user lattice.
	 */
	public Index<Blob, ACell> getJobs(RequestContext ctx) {
		AString did = ctx.isInternal() ? venueDID : ctx.getCallerDID();
		if (did == null) return Index.none();
		User user = venueState.users().get(did);
		if (user == null) return Index.none();
		return user.jobs().getAll();
	}

	// ========== Job Lifecycle Control ==========

	public void updateJobStatus(Blob jobID, AMap<AString, ACell> newData) {
		Job job;
		synchronized (activeJobs) {
			job = activeJobs.get(jobID);
		}
		if (job == null) return;
		job.updateData(newData);
	}

	/**
	 * Cancels a Job with request context.
	 * @throws SecurityException if the caller does not own the job
	 */
	public AMap<AString, ACell> cancelJob(Blob id, RequestContext ctx) {
		AMap<AString, ACell> data = getJobData(id, ctx);
		if (data == null) return null;
		// access already checked by getJobData(id, ctx)
		return cancelJob(id);
	}

	public AMap<AString, ACell> cancelJob(Blob id) {
		Job job;
		synchronized (activeJobs) {
			job = activeJobs.get(id);
		}
		if (job == null) return null;
		job.cancel();
		return job.getData();
	}

	/**
	 * Pauses a running Job with request context.
	 * @throws SecurityException if the caller does not own the job
	 */
	public AMap<AString, ACell> pauseJob(Blob id, RequestContext ctx) {
		AMap<AString, ACell> data = getJobData(id, ctx);
		if (data == null) return null;
		return pauseJob(id);
	}

	public AMap<AString, ACell> pauseJob(Blob id) {
		Job job;
		synchronized (activeJobs) {
			job = activeJobs.get(id);
		}
		if (job == null) return null;
		job.pause();
		return job.getData();
	}

	/**
	 * Resumes a paused Job with request context.
	 * @throws SecurityException if the caller does not own the job
	 */
	public AMap<AString, ACell> resumeJob(Blob id, RequestContext ctx) {
		AMap<AString, ACell> data = getJobData(id, ctx);
		if (data == null) return null;
		return resumeJob(id);
	}

	public AMap<AString, ACell> resumeJob(Blob id) {
		Job job;
		synchronized (activeJobs) {
			job = activeJobs.get(id);
		}
		if (job == null) return null;
		job.resume();

		// Re-engage the adapter
		AAdapter adapter = resolveJobAdapter(job);
		if (adapter != null) {
			AMap<AString, ACell> meta = resolveJobMeta(job);
			AString callerDID = RT.ensureString(job.getData().get(Fields.CALLER));
			RequestContext ctx = (callerDID != null) ? RequestContext.of(callerDID) : RequestContext.INTERNAL;
			if (meta != null) {
				adapter.invoke(job, ctx, meta, job.getData().get(Fields.INPUT));
			}
		}

		return job.getData();
	}

	/**
	 * Deletes a job permanently with request context.
	 * @throws SecurityException if the caller does not own the job
	 */
	public boolean deleteJob(Blob id, RequestContext ctx) {
		AMap<AString, ACell> data = getJobData(id, ctx);
		if (data == null) return false;
		// Remove from active cache if present (may already be evicted for terminal jobs)
		deleteJob(id);
		return true;
	}

	public boolean deleteJob(Blob id) {
		synchronized (activeJobs) {
			activeJobs.remove(id);
		}
		return true;
	}

	// ========== Message Delivery ==========

	/**
	 * Delivers a message to a job's message queue with request context.
	 * @throws SecurityException if the caller does not own the job
	 */
	public int deliverMessage(Blob jobID, AMap<AString, ACell> message, RequestContext ctx) {
		AMap<AString, ACell> data = getJobData(jobID);
		if (data != null && !accessControl.canAccessJob(ctx, data)) {
			throw new SecurityException("Access denied to job: " + jobID.toHexString());
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

		job.enqueueMessage(record);
		int depth = job.getQueueSize();

		// Dispatch to adapter if it supports multi-turn
		AAdapter adapter = resolveJobAdapter(job);
		if (adapter != null && adapter.supportsMultiTurn()) {
			adapter.handleMessage(job, record);
		}

		return depth;
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
		AMap<AString, ACell> userData = venueState.users().getAll();
		if (userData == null || userData.isEmpty()) return;

		int refired = 0, kept = 0, failed = 0;
		for (var entry : userData.entrySet()) {
			AString did = (AString) entry.getKey();
			User user = venueState.users().get(did);
			if (user == null) continue;

			Index<Blob, ACell> userJobs = user.jobs().getAll();
			long n = userJobs.count();
			for (long i = 0; i < n; i++) {
				var jobEntry = userJobs.entryAt(i);
				Blob jobID = (Blob) jobEntry.getKey();
				ACell value = jobEntry.getValue();
				if (!(value instanceof AMap)) continue;
				AMap<AString, ACell> record = (AMap<AString, ACell>) value;

				// Skip jobs already in memory
				synchronized (activeJobs) {
					if (activeJobs.containsKey(jobID)) continue;
				}

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
	@SuppressWarnings("unchecked")
	private boolean refireJob(Blob jobID, AMap<AString, ACell> record, AString callerDID) {
		AString opRef = RT.ensureString(record.get(Fields.OP));
		if (opRef == null) {
			markJobFailed(jobID, record, "Cannot re-fire: no operation reference", callerDID);
			return false;
		}

		// Resolve operation
		Asset asset = assetResolver.apply(opRef);
		Operation op = (asset != null) ? Operation.from(asset) : null;

		// Resolve adapter
		AAdapter adapter = resolveAdapterForOp(op, opRef);
		if (adapter == null) {
			markJobFailed(jobID, record, "Cannot re-fire: adapter not available for " + opRef, callerDID);
			return false;
		}

		// Create a live Job wrapping the persisted record
		Job job = new Job(record, op) {
			@Override public AMap<AString, ACell> processUpdate(AMap<AString, ACell> newData) {
				newData = newData.assoc(Fields.UPDATED, CVMLong.create(Utils.getCurrentTimestamp()));
				persistJobRecord(getID(), newData, callerDID);
				if (Job.isFinished(newData)) {
					synchronized (activeJobs) {
						activeJobs.remove(getID());
					}
				}
				return newData;
			}
		};

		if (!jobUpdateListeners.isEmpty()) {
			job.setUpdateListener(j -> jobUpdateListeners.forEach(l -> l.accept(j)));
		}

		synchronized (activeJobs) {
			activeJobs.put(jobID, job);
		}

		// Dispatch via new interface with resolved metadata
		AMap<AString, ACell> meta = (op != null) ? op.meta() : null;
		RequestContext ctx = RequestContext.of(callerDID);
		if (meta != null) {
			adapter.invoke(job, ctx, meta, record.get(Fields.INPUT));
		} else {
			markJobFailed(jobID, record, "Cannot re-fire: no operation metadata for " + opRef, callerDID);
			return false;
		}
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
			Asset asset = assetResolver.apply(opRef);
			op = (asset != null) ? Operation.from(asset) : null;
		}

		Job job = new Job(record, op) {
			@Override public AMap<AString, ACell> processUpdate(AMap<AString, ACell> newData) {
				newData = newData.assoc(Fields.UPDATED, CVMLong.create(Utils.getCurrentTimestamp()));
				persistJobRecord(getID(), newData, callerDID);
				if (Job.isFinished(newData)) {
					synchronized (activeJobs) {
						activeJobs.remove(getID());
					}
				}
				return newData;
			}
		};

		if (!jobUpdateListeners.isEmpty()) {
			job.setUpdateListener(j -> jobUpdateListeners.forEach(l -> l.accept(j)));
		}

		synchronized (activeJobs) {
			activeJobs.put(jobID, job);
		}
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
	private void persistJobRecord(Blob jobID, AMap<AString, ACell> record, AString callerDID) {
		User user = venueState.users().ensure(callerDID);
		user.jobs().persist(jobID, record);
	}

	// ========== Adapter Resolution ==========

	/**
	 * Resolves the metadata for a job, trying the Operation object first,
	 * then falling back to resolving the operation reference from the job record.
	 */
	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> resolveJobMeta(Job job) {
		Operation operation = job.getOperation();
		if (operation != null) return operation.meta();

		AString opStr = RT.ensureString(job.getData().get(Fields.OP));
		if (opStr == null) return null;
		Asset asset = assetResolver.apply(opStr);
		return (asset != null) ? asset.meta() : null;
	}

	/**
	 * Resolves the adapter responsible for a job.
	 */
	private AAdapter resolveJobAdapter(Job job) {
		AMap<AString, ACell> meta = resolveJobMeta(job);
		if (meta != null) {
			String adapterName = AAdapter.getAdapterName(meta);
			if (adapterName != null) return adapterLookup.apply(adapterName);
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
				AAdapter adapter = adapterLookup.apply(adapterName);
				if (adapter != null) return adapter;
			}
		}
		return null;
	}

	// ========== Job ID Generation ==========

	/**
	 * Generates a unique job ID.
	 * Format: 6 bytes timestamp + 2 bytes counter + 8 bytes random.
	 * Time-ordered, unpredictable, collision-resistant.
	 */
	private Blob generateJobID(long ts) {
		if (ts > lastJobTS) jobCounter = 0;
		lastJobTS = ts;
		byte[] bs = new byte[16];

		Utils.writeLong(bs, 0, ts << 16);
		Utils.writeShort(bs, 6, jobCounter++);
		Utils.writeLong(bs, 8, rand.nextLong());

		return Blob.wrap(bs);
	}

	// ========== Listeners ==========

	/**
	 * Adds a listener to be notified on all job state updates.
	 */
	public void addJobUpdateListener(java.util.function.Consumer<Job> listener) {
		this.jobUpdateListeners.add(listener);
	}

	// ========== Accessors ==========

	/**
	 * Gets the venue DID used for internal job ownership.
	 */
	public AString getVenueDID() {
		return venueDID;
	}
}
