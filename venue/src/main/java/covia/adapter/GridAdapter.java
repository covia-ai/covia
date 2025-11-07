package covia.adapter;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Grid;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.LocalVenue;

/**
 * Adapter to call the Covia grid as a proxy
 */
public class GridAdapter extends AAdapter {

	private Hash RUN_OPERATION;
	private Hash INVOKE_OPERATION;

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
		RUN_OPERATION = installAsset("/adapters/grid/run.json");
		INVOKE_OPERATION = installAsset("/adapters/grid/invoke.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input) {
		Objects.requireNonNull(operation, "Operation must not be null");
		String[] parts = operation.split(":");
		if (parts.length < 2) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid grid operation format: " + operation));
		}
		String gridOp = parts[1];
		switch (gridOp) {
		case "run":
			return invokeRun(meta, input);
		case "invoke":
			return invokeAsync(meta, input);
		default:
			return CompletableFuture.failedFuture(new IllegalArgumentException("Unrecognised grid operation: " + gridOp));
		}
	}

	private CompletableFuture<ACell> invokeRun(ACell meta, ACell input) {
		AString targetOperation = RT.ensureString(RT.getIn(input, Fields.OPERATION));
		if (targetOperation == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("No grid operation specified"));
		}

		ACell operationInput = RT.getIn(input, Fields.INPUT);
		if (operationInput == null) operationInput = Maps.empty();
		AString venueSpec = resolveVenue(meta, input);

		Venue venue = (venueSpec != null)
			? Grid.connect(venueSpec.toString())
			: new LocalVenue(engine);

		CompletableFuture<Job> jobFuture = venue.invoke(targetOperation.toString(), operationInput);
		return jobFuture.thenCompose(job -> CompletableFuture.supplyAsync(job::awaitResult));
	}

	private CompletableFuture<ACell> invokeAsync(ACell meta, ACell input) {
		AString targetOperation = RT.ensureString(RT.getIn(input, Fields.OPERATION));
		if (targetOperation == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("No grid operation specified"));
		}

		ACell operationInput = RT.getIn(input, Fields.INPUT);
		if (operationInput == null) operationInput = Maps.empty();
		AString venueSpec = resolveVenue(meta, input);

		Venue venue = (venueSpec != null)
			? Grid.connect(venueSpec.toString())
			: new LocalVenue(engine);

		CompletableFuture<Job> jobFuture = venue.invoke(targetOperation.toString(), operationInput);
		return jobFuture.thenApply(Job::getData);
	}

	private AString resolveVenue(ACell meta, ACell input) {
		AString venue = RT.ensureString(RT.getIn(input, Fields.VENUE));
		if (venue != null) return venue;
		venue = RT.ensureString(RT.getIn(meta, Fields.OPERATION, Fields.VENUE));
		return venue;
	}

	public Hash getRunOperation() {
		return RUN_OPERATION;
	}

	public Hash getInvokeOperation() {
		return INVOKE_OPERATION;
	}

}
