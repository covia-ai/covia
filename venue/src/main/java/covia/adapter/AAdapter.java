package covia.adapter;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;

public abstract class AAdapter {
	
	
	protected Engine engine;

	public void install(Engine engine) {
		this.engine=engine;
		installAssets();
	}
	
	/**
	 * Override this method to install adapter-specific assets.
	 * Default implementation does nothing.
	 */
	protected void installAssets() {
		// Default implementation - subclasses can override
	}
	
	/**
	 * Helper method to install a single asset from a resource path.
	 * @param resourcePath The resource path to read the asset from
	 * @param assetName Optional name for the asset (can be null)
	 */
	protected void installAsset(String resourcePath, String assetName) {
		try {
			engine.storeAsset(convex.core.util.Utils.readResourceAsAString(resourcePath), null);
		} catch (Exception e) {
			// Log warning but don't fail installation
			System.err.println("Failed to install asset from " + resourcePath + ": " + e.getMessage());
		}
	}
	
    /**
     * Returns the name of this adapter.
     * @return The adapter name (e.g. "mcp")
     */
    public abstract String getName();
    
    /**
     * Invoke an operation with the given input, returning a future for the result.
     * Adapters SHOULD launch an asynchronous task to produce the result and update the job status accordingly
     * Adapters MAY update the Job immediately if the Job can be completed in O(1) time
     * 
     * @param operation The operation ID in the format "adapter:operation"
     * @param meta The metadata for the operation
     * @param input The input parameters for the operation
     * @return A CompletableFuture that will complete with the result of the operation, or fail exceptionally
     */
    public abstract CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input);
    
    /**
     * Invoke an operation with the given input.
     * Adapters SHOULD launch an asynchronous task to produce the result and update the job status accordingly
     * Adapters MAY update the Job immediately if the Job can be completed in O(1) time
     * 
     * @param Job the Job prepared to run within the registered venue
     * @param operation The operation ID in the format "adapter:operation"
     * @param meta The metadata for the operation
     * @param input The input parameters for the operation
     */
    public void invoke(Job job, String operation, ACell meta, ACell input) {
    	job.setStatus(Status.STARTED);
 		invokeFuture(operation,meta,input).thenAccept(result -> {
			job.completeWith(result);
		})
		.exceptionally(e -> {
			AString newStatus;
			if (e instanceof CancellationException) {
				newStatus=Status.CANCELLED;
			} else {
				newStatus=Status.FAILED;
			}
			job.update(jd->{
				jd = jd.assoc(Fields.JOB_STATUS_FIELD, newStatus);
				jd = jd.assoc(Fields.JOB_ERROR_FIELD, Strings.create(e.getMessage()));
				return jd;
			});
			return null;
		});
    }
    
    protected void finishJob(AMap<AString,ACell> job, AString statusString) {
    	AString id=Job.parseID(job.get(Fields.ID));
    	if (id==null) {
    		throw new IllegalStateException("Job has no ID");
    	}
    	job=job.assoc(Fields.JOB_STATUS_FIELD, statusString);
    	engine.updateJobStatus(id,job);
    }
    
    protected void completeJobResult(AMap<AString,ACell> job, ACell result) {
    	job=job.assoc(Fields.OUTPUT, result);
    	finishJob(job,Status.COMPLETE);
    }
    
    
    protected void failJobResult(AMap<AString,ACell> job, Object message) {
    	job=job.assoc(Fields.JOB_ERROR_FIELD, Strings.create(message));
    	finishJob(job,Status.COMPLETE);
    }
}
