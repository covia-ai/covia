package covia.adapter;

import java.util.concurrent.CompletableFuture;
import convex.core.data.ACell;
import covia.venue.Venue;

public abstract class AAdapter {
	
	
	protected Venue venue;

	public void install(Venue venue) {
		this.venue=venue;
	}
	
    /**
     * Returns the name of this adapter.
     * @return The adapter name (e.g. "mcp")
     */
    public abstract String getName();
    
    /**
     * Invoke an operation with the given input, returning a future for the result.
     * Adapters SHOULD launch an asynchronous task to produce the result and update the job status accordingly
     * Adapters MAY return a completed Job immediately if the Job can be completed in O(1) time
     * 
     * @param operation The operation ID in the format "adapter:operation"
     * @param meta The metadata for the operation
     * @param input The input parameters for the operation
     * @return A CompletableFuture that will complete with the result of the operation
     */
    public abstract CompletableFuture<ACell> invoke(String operation, ACell meta, ACell input);
}
