package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Grid;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.LocalVenue;
import covia.venue.RequestContext;

/**
 * Adapter that proxies Covia grid operations to the local engine or a remote venue.
 */
public class GridAdapter extends AAdapter {

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
		return "Enables distributed processing and resource sharing across the Covia network via grid operations. " +
		   "Provides access to remote venues, distributed job execution, and collaborative computing capabilities. " +
		   "Perfect for scaling computational tasks, leveraging distributed resources, and building resilient, distributed AI applications.";
	}

    @Override
    protected void installAssets() {
        RUN_OPERATION        = installAsset("grid/run",        "/adapters/grid/run.json");
        INVOKE_OPERATION     = installAsset("grid/invoke",     "/adapters/grid/invoke.json");
        JOB_STATUS_OPERATION = installAsset("grid/job-status", "/adapters/grid/jobStatus.json");
        JOB_RESULT_OPERATION = installAsset("grid/job-result", "/adapters/grid/jobResult.json");
    }

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		throw new UnsupportedOperationException("GridAdapter requires Job context for caller DID propagation");
	}

	@Override
	public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String gridOp = getSubOperation(meta);
		if (gridOp == null) {
			job.fail("Invalid grid operation: no sub-operation in metadata");
			return;
		}

		// Extract caller DID from request context
		AString callerDID = ctx.getCallerDID();

		CompletableFuture<ACell> future;
        switch (gridOp) {
        case "run":
            future = invokeRun(meta, input, callerDID);
            break;
        case "invoke":
            future = invokeAsync(meta, input, callerDID);
            break;
        case "jobStatus":
            future = invokeJobStatus(meta, input, callerDID);
            break;
        case "jobResult":
            future = invokeJobResult(meta, input, callerDID);
            break;
        default:
            job.fail("Unrecognised grid operation: " + gridOp);
            return;
        }
		future.whenComplete((result, error) -> {
			if (error != null) {
				job.fail(error.getMessage());
			} else {
				job.completeWith(result);
			}
		});
	}

	/**
	 * Executes a grid operation and waits for completion, returning the finished result.
	 */
	private CompletableFuture<ACell> invokeRun(ACell meta, ACell input, AString callerDID) {
		AString targetOperation = RT.ensureString(RT.getIn(input, Fields.OPERATION));
		if (targetOperation == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("No grid operation specified"));
		}

        ACell operationInput = RT.getIn(input, Fields.INPUT); // might be null, that's ok
        AString venueSpec = resolveVenue(meta, input);

        Venue venue = selectVenue(venueSpec, callerDID);

        CompletableFuture<Job> jobFuture = venue.invoke(targetOperation.toString(), operationInput);
        return jobFuture.thenCompose(Job::future);
	}

	/**
	 * Submits a grid operation but returns immediately with the job status payload.
	 */
	private CompletableFuture<ACell> invokeAsync(ACell meta, ACell input, AString callerDID) {
		AString targetOperation = RT.ensureString(RT.getIn(input, Fields.OPERATION));
		if (targetOperation == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("No grid operation specified"));
		}

        ACell operationInput = RT.getIn(input, Fields.INPUT); // might be null, that's ok
        AString venueSpec = resolveVenue(meta, input);

        Venue venue = selectVenue(venueSpec, callerDID);

        CompletableFuture<Job> jobFuture = venue.invoke(targetOperation.toString(), operationInput);
        return jobFuture.thenApply(Job::getData);
	}

	private CompletableFuture<ACell> invokeJobStatus(ACell meta, ACell input, AString callerDID) {
		Blob jobId = parseJobId(RT.getIn(input, Fields.ID));
		if (jobId == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Job ID is required"));
		}

		Venue venue = selectVenue(resolveVenue(meta, input), callerDID);
		return venue.getJobStatus(jobId).thenApply(status -> status);
	}

	private CompletableFuture<ACell> invokeJobResult(ACell meta, ACell input, AString callerDID) {
		Blob jobId = parseJobId(RT.getIn(input, Fields.ID));
		if (jobId == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Job ID is required"));
		}

		Venue venue = selectVenue(resolveVenue(meta, input), callerDID);
		return venue.awaitJobResult(jobId);
	}

    private Venue selectVenue(AString venueSpec, AString callerDID) {
        if (venueSpec != null) {
            return Grid.connect(venueSpec.toString());
        }
        LocalVenue lv = new LocalVenue(engine);
        lv.setUser(callerDID);
        return lv;
    }

    private Blob parseJobId(ACell jobIdCell) {
        if (jobIdCell == null) return null;
        try {
            return Job.parseID(jobIdCell);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid job ID", e);
        }
    }

	/**
	 * Finds the venue specification from input (or metadata) if provided.
	 */
	private AString resolveVenue(ACell meta, ACell input) {
		AString venue = RT.ensureString(RT.getIn(input, Fields.VENUE));
		if (venue != null) return venue;
		venue = RT.ensureString(RT.getIn(meta, Fields.OPERATION, Fields.VENUE));
		return venue;
	}

}
