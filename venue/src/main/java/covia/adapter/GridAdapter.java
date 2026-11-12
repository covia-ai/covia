package covia.adapter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.InvalidDefinitionException;
import covia.exception.InvalidInputException;
import covia.exception.NotSupportedException;
import covia.exception.ResponseException;
import covia.exception.UnavailableException;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.Venue;
import covia.venue.LocalVenue;
import covia.venue.RequestContext;
import covia.venue.RemoteJobs;
import covia.grid.client.VenueHTTP;

/**
 * Adapter that proxies Covia grid operations to the local engine or a remote venue.
 */
public class GridAdapter extends AAdapter {
	private static final AString AUTHENTICATE_AS = Strings.intern("authenticateAs");
	public static final String INVALID_VENUE = "'venue' must be a string when provided; omit it for local execution";
	public static final String INVALID_DEFAULT_VENUE = "Operation 'venue' must be a string when provided";
	public static final String RESULT_TIMEOUT = "grid:jobResult timed out after %dms waiting for job %s";

    /** Asset hash for synchronous grid run operation. */
    public static Hash RUN_OPERATION;
    /** Asset hash for asynchronous grid invoke operation. */
    public static Hash INVOKE_OPERATION;
    /** Asset hash for job status lookup operation. */
    public static Hash JOB_STATUS_OPERATION;
    /** Asset hash for job result retrieval operation. */
    public static Hash JOB_RESULT_OPERATION;

	@Override
	public String getName() {
		return "grid";
	}
	
	@Override
	public String getDescription() {
		return "Invoke operations by reference locally or on another venue. " +
			"Use the job adapter to inspect, await and control existing work; legacy grid job queries remain available for compatibility.";
	}

    @Override
    protected void installAssets() {
    	// The adapter's own skill: v/skills/grid lives and dies with this adapter.
    	installSkill("grid/grid", "/skills/grid.json");
		installSkill("root/grid", "/skills/grid.json");
        RUN_OPERATION        = installAsset("grid/run",        "/adapters/grid/run.json");
        INVOKE_OPERATION     = installAsset("grid/invoke",     "/adapters/grid/invoke.json");
        JOB_STATUS_OPERATION = installAsset("grid/job-status", "/adapters/grid/jobStatus.json");
        JOB_RESULT_OPERATION = installAsset("grid/job-result", "/adapters/grid/jobResult.json");
    }

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String gridOp = getSubOperation(meta);
		if (("run".equals(gridOp) || ("jobResult".equals(gridOp) && parseTimeoutMs(input) <= 0))
				&& resolveVenue(meta, input) != null) {
			return engine.jobs().invokeInternal(meta, input, ctx);
		}
		if (gridOp == null) {
			return CompletableFuture.failedFuture(new InvalidDefinitionException("Invalid grid operation: no sub-operation in metadata"));
		}
		return switch (gridOp) {
			case "run"       -> invokeRun(ctx, meta, input);
			case "invoke"    -> invokeAsync(ctx, meta, input);
			case "jobStatus" -> invokeJobStatus(ctx, meta, input);
			case "jobResult" -> invokeJobResult(ctx, meta, input);
			default          -> CompletableFuture.failedFuture(new NotSupportedException("Unrecognised grid operation: " + gridOp));
		};
	}

	@Override
	public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String operation = getSubOperation(meta);
		if (("run".equals(operation) || ("jobResult".equals(operation) && parseTimeoutMs(input) <= 0))
				&& resolveVenue(meta, input) != null) {
			job.start(() -> delegate(job, ctx, meta, input));
		} else {
			super.invoke(job, ctx, meta, input);
		}
	}

	private void delegate(Job job, RequestContext ctx, ACell meta, ACell input) {
		AString operation = RT.ensureString(RT.getIn(input, Fields.OPERATION));
		boolean existing = "jobResult".equals(getSubOperation(RT.ensureMap(meta)));
		Blob existingID = existing ? parseJobId(RT.getIn(input, Fields.ID)) : null;
		if (existing && existingID == null) throw new InvalidInputException("'id' is required");
		if (!existing && operation == null) throw new InvalidInputException("'operation' is required");
		// Observing an existing job only reads; submitting one sends
		if (!existing) engine.remoteJobs().requireNotClosing();
		AString target = resolveVenue(meta, input);
		if (!existing) engine.jobs().requireRemoteInvoke(operation, RT.getIn(input, Fields.INPUT), ctx);
		VenueConnection remote = remoteConnection(ctx, target, input);
		RemoteJobs observer = engine.remoteJobs();
		observer.prepare(job, "covia", target.toString(), remote.credentials(), !existing);
		if (job.isFinished()) return;
		if (existing) {
			try { observer.accepted(job, existingID.toHexString()); }
			finally { watch(job, remote.venue(), existingID); }
			return;
		}
		// Submit once and retain the handle. SDK background polling is a bounded
		// caller convenience and must not define the delegated Job's lifetime.
		CompletableFuture<Job> submitted = remote.venue() instanceof VenueHTTP http
			? http.startJobAsync(operation, RT.getIn(input, Fields.INPUT))
			: remote.venue().invoke(operation.toString(), RT.getIn(input, Fields.INPUT));
		submitted.whenComplete((accepted, error) -> {
			observer.received(job, () -> {
			if (error != null) { observer.submissionFailed(job, error); return; }
			try {
				observer.accepted(job, accepted.getID().toHexString());
				bindCancellation(job, remote.venue(), accepted.getID());
				if (job.isFinished()) return;
				applyRemote(job, accepted.getData());
			} catch (RuntimeException | Error failure) {
				observer.submissionUnknown(job, failure);
			} finally {
				AString id = RemoteJobs.remoteId(job);
				if (id != null) watch(job, remote.venue(), Job.parseID(id));
			}
			});
		});
	}

	private void watch(Job job, Venue venue, Blob id) {
		engine.remoteJobs().observe(job, () -> venue.getJobStatus(id), snapshot -> applyRemote(job, snapshot));
	}

	/** A running job's remote job: what cancelling the local job cancels. */
	private record RemoteCall(Venue venue, Blob id) { }

	/** The remote job each running job is watching, by local job id. */
	private final java.util.concurrent.ConcurrentHashMap<Blob, RemoteCall> remoteCalls =
		new java.util.concurrent.ConcurrentHashMap<>();

	/** Records the remote job {@code job} runs on. A job already cancelled cancels it at once. */
	private void bindCancellation(Job job, Venue venue, Blob id) {
		Blob localId = job.getID();
		RemoteCall call = new RemoteCall(venue, id);
		remoteCalls.put(localId, call);
		if (Status.CANCELLED.equals(job.getStatus())) {
			cancelRemote(job);
			return;
		}
		job.future().whenComplete((result, error) -> remoteCalls.remove(localId, call));
	}

	private void cancelRemote(Job job) {
		RemoteCall call = remoteCalls.remove(job.getID());
		if (call == null) return;
		engine.remoteJobs().attemptCancellation(job, call.id().toHexString(),
			() -> RemoteJobs.cancellationResult(call.id(), call.venue().cancelJob(call.id())));
	}

	/** Cancelling a local job asks its venue to cancel the remote one. */
	@Override
	public void stop(Job job) {
		cancelRemote(job);
		super.stop(job);
	}

	private void applyRemote(Job job, AMap<AString, ACell> snapshot) {
		AString status = RT.ensureString(snapshot.get(Fields.STATUS));
		if (status == null) throw new ResponseException("Remote Job has no status");
		Blob expected = Job.parseID(RemoteJobs.remoteId(job));
		if (expected == null || !expected.equals(Job.parseID(snapshot.get(Fields.ID)))) throw new ResponseException("Remote Job ID mismatch");
		if (Status.COMPLETE.equals(status)) {
			job.completeWith(snapshot.get(Fields.OUTPUT), data -> RemoteJobs.observed(data, status));
			return;
		}
		if (!(Status.PENDING.equals(status) || Status.STARTED.equals(status)
			|| Status.PAUSED.equals(status) || Status.INPUT_REQUIRED.equals(status)
			|| Status.AUTH_REQUIRED.equals(status) || Status.FAILED.equals(status)
			|| Status.CANCELLED.equals(status) || Status.REJECTED.equals(status))) {
			throw new ResponseException("Invalid remote Job status");
		}
		// Local processing is STARTED even while the remote worker is queued.
		AString localStatus = Status.PENDING.equals(status) ? Status.STARTED : status;
		job.update(data -> {
			AMap<AString, ACell> next = RemoteJobs.observed(data, status).assoc(Fields.STATUS, localStatus);
			if (Job.isFinished(snapshot)) next = next.assoc(Fields.ERROR, snapshot.get(Fields.ERROR));
			return next.equals(data) ? null : next;
		});
	}

	@Override public void recoverJob(Job job) {
		AMap<AString, ACell> kept = engine.remoteJobs().kept(job);
		if (kept == null) { super.recoverJob(job); return; }
		AString id = RemoteJobs.remoteId(job);
		if (id == null) { engine.remoteJobs().submissionUnknown(job, null); return; }
		try {
			AMap<AString, ACell> saved = engine.remoteJobs().connection(job);
			if (!VenueConnection.resumable(saved)) { engine.remoteJobs().credentialNotKept(job); return; }
			Venue venue = restoreConnection(RT.ensureString(kept.get(RemoteJobs.TARGET)), saved);
			if (convex.core.data.prim.CVMBool.TRUE.equals(kept.get(RemoteJobs.OWNS_REMOTE))) {
				bindCancellation(job, venue, Job.parseID(id));
			}
			watch(job, venue, Job.parseID(id));
		} catch (RuntimeException e) { engine.remoteJobs().unavailable(job, e); }
	}

	@Override public void suspendJob(Job job) {
		if (engine.remoteJobs().kept(job) != null) engine.remoteJobs().suspend(job);
		else super.suspendJob(job);
	}

	/**
	 * Executes a grid operation and waits for completion, returning the finished result.
	 */
	private CompletableFuture<ACell> invokeRun(RequestContext ctx, ACell meta, ACell input) {
		AString targetOperation = RT.ensureString(RT.getIn(input, Fields.OPERATION));
		if (targetOperation == null) {
			return CompletableFuture.failedFuture(new InvalidInputException(
				"'operation' is required; pass an operation path or asset hash"));
		}

        ACell operationInput = RT.getIn(input, Fields.INPUT);
        AString venueSpec = resolveVenue(meta, input);

        if (venueSpec != null) engine.jobs().requireRemoteInvoke(targetOperation, operationInput, ctx);
        Venue venue = selectVenue(ctx, venueSpec, input);

		return venue.run(targetOperation.toString(), operationInput);
	}

	/**
	 * Submits a grid operation but returns immediately with the job status payload.
	 */
	private CompletableFuture<ACell> invokeAsync(RequestContext ctx, ACell meta, ACell input) {
		AString targetOperation = RT.ensureString(RT.getIn(input, Fields.OPERATION));
		if (targetOperation == null) {
			return CompletableFuture.failedFuture(new InvalidInputException(
				"'operation' is required; pass an operation path or asset hash"));
		}

        // The inner input is the target operation's input and is passed
        // verbatim. A Covia operation may take any JSON value, so a string here
        // is a valid input, not a serialised object to repair: string-to-object
        // parsing belongs only where a schema admits nothing but an object
        // (tool-call arguments at the MCP and provider boundaries, #508).
        ACell operationInput = RT.getIn(input, Fields.INPUT);
        AString venueSpec = resolveVenue(meta, input);
        if (venueSpec != null) engine.remoteJobs().requireNotClosing();

        if (venueSpec != null) engine.jobs().requireRemoteInvoke(targetOperation, operationInput, ctx);
        Venue venue = selectVenue(ctx, venueSpec, input);

        // Only submission is requested here; nobody consumes the SDK Job's future.
        CompletableFuture<Job> jobFuture = venue instanceof VenueHTTP http
            ? http.startJobAsync(targetOperation, operationInput)
            : venue.invoke(targetOperation.toString(), operationInput);
        return jobFuture.thenApply(Job::getData);
	}

	private CompletableFuture<ACell> invokeJobStatus(RequestContext ctx, ACell meta, ACell input) {
		Blob jobId = parseJobId(RT.getIn(input, Fields.ID));
		if (jobId == null) {
			return CompletableFuture.failedFuture(new InvalidInputException(
				"'id' is required; use the job ID returned by grid:invoke"));
		}

		Venue venue = selectVenue(ctx, resolveVenue(meta, input), input);
		return venue.getJobStatus(jobId).thenApply(status -> status);
	}

	private CompletableFuture<ACell> invokeJobResult(RequestContext ctx, ACell meta, ACell input) {
		Blob jobId = parseJobId(RT.getIn(input, Fields.ID));
		if (jobId == null) {
			return CompletableFuture.failedFuture(new InvalidInputException(
				"'id' is required; use the job ID returned by grid:invoke"));
		}

		Venue venue = selectVenue(ctx, resolveVenue(meta, input), input);
		long timeoutMs = parseTimeoutMs(input);
		// A remote bounded wait must use the caller's budget, not the SDK's
		// shorter default. Unbounded remote waits use the durable observer above.
		if (timeoutMs > 0 && venue instanceof VenueHTTP http) http.setTimeout(timeoutMs);
		CompletableFuture<ACell> jobFuture = venue.awaitJobResult(jobId);

		if (timeoutMs <= 0) return jobFuture;

		// Derive a new future so the timeout doesn't corrupt the underlying Job's future.
		// thenApply(x -> x) creates an independent CompletableFuture that completes
		// when jobFuture does; applying orTimeout to it fails only this derivative.
		return jobFuture.thenApply(x -> x)
				.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
				.exceptionallyCompose(ex -> {
					if (ex instanceof TimeoutException || ex.getCause() instanceof TimeoutException) {
						// The caller's wait ran out; the job itself may still finish
						return CompletableFuture.failedFuture(
							new UnavailableException(RESULT_TIMEOUT.formatted(timeoutMs, jobId.toHexString())));
					}
					return CompletableFuture.failedFuture(ex);
				});
	}

	private static long parseTimeoutMs(ACell input) {
		ACell v = RT.getIn(input, Fields.TIMEOUT);
		if (v instanceof CVMLong l) return l.longValue();
		return 0;
	}

	/**
	 * Resolves the target venue for a grid op. The operation input explicitly
	 * chooses authentication behaviour with {@code authenticateAs}; credentials
	 * authenticate and UCANs authorise, with no token-presence mode switch:
	 * <ul>
	 *   <li>{@code anonymous} (default): no authentication and no grants.</li>
	 *   <li>{@code caller}: requires a target-audienced, empty-att identity
	 *       credential from the caller; it travels in Authorization, while only
	 *       non-empty grants audienced to that caller travel in {@code ucans}.</li>
	 *   <li>{@code venue}: this venue authenticates as itself, and only when the
	 *       venue itself is the caller; no grant lends it to anyone else. Only
	 *       grants audienced to this venue are forwarded.</li>
	 * </ul>
	 * A local target carries the complete caller context because there is no
	 * transport boundary at which to reconstruct it.
	 */
	private Venue selectVenue(RequestContext ctx, AString venueSpec, ACell input) {
		if (venueSpec != null) return connectRemote(ctx, venueSpec, input);
		LocalVenue lv = new LocalVenue(engine);
		// An in-process hop has no transport boundary at which to reconstruct
		// authority. Carry the complete immutable context so agent caps,
		// sub-principal scope, proofs, cancellation and parent job scope survive.
		lv.setRequestContext(ctx);
		return lv;
	}

	private Venue connectRemote(RequestContext ctx, AString venueSpec, ACell input) {
		return remoteConnection(ctx, venueSpec, input).venue();
	}

	private VenueConnection remoteConnection(RequestContext ctx, AString venueSpec, ACell input) {
		return VenueConnection.connect(engine, ctx, venueSpec, input);
	}

	private Venue restoreConnection(AString target, AMap<AString, ACell> saved) {
		return VenueConnection.restore(engine, target, saved);
	}

	/**
	 * Writes a value at a mutable path hosted by another venue.
	 *
	 * <p>The caller's authority is checked locally against the exact destination
	 * before any network request, then the ordinary {@code covia:write}
	 * operation checks it again at the destination. Raw UCANs are forwarded by
	 * {@link #connectRemote}; no framework-only bypass exists on either side.</p>
	 */
	CompletableFuture<ACell> writeRemotePath(RequestContext ctx, AString venueSpec,
			AString path, ACell value) {
		ACell writeInput = Maps.of(Fields.PATH, path, Fields.VALUE, value);
		RequestContext writeCtx = ctx.withInvocation(writeInput, null);
		engine.requireResourceAccess(writeCtx, path, Capability.CRUD_WRITE);
		// A foreign output path is itself an explicit request to write remotely as
		// the current caller. The identity credential authenticates that caller;
		// its mere presence still cannot select this behaviour.
		engine.remoteJobs().requireNotClosing();
		Venue venue = connectRemote(ctx, venueSpec,
			Maps.of(AUTHENTICATE_AS, Strings.create("caller")));
		return venue.invoke("v/ops/covia/write",
				writeInput)
			.thenCompose(Job::future);
	}

    private Blob parseJobId(ACell jobIdCell) {
        if (jobIdCell == null) return null;
        try {
            Blob id = Job.parseID(jobIdCell);
            if (id == null) {
                throw new InvalidInputException("Invalid job ID format: " + jobIdCell);
            }
            return id;
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("Invalid job ID format: " + jobIdCell, e);
        }
    }

	/**
	 * Finds the venue specification from input (or metadata) if provided.
	 */
	private AString resolveVenue(ACell meta, ACell input) {
		AMap<?, ?> args = RT.ensureMap(input);
		if (args != null && args.containsKey(Fields.VENUE)) {
			ACell venue = args.get(Fields.VENUE);
			if (venue instanceof AString target) return target;
			throw new InvalidInputException(INVALID_VENUE);
		}
		AMap<?, ?> operation = RT.ensureMap(RT.getIn(meta, Fields.OPERATION));
		if (operation != null && operation.containsKey(Fields.VENUE)) {
			ACell venue = operation.get(Fields.VENUE);
			if (venue instanceof AString target) return target;
			throw new InvalidDefinitionException(INVALID_DEFAULT_VENUE);
		}
		return null;
	}

}
