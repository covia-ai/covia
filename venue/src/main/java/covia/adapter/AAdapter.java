package covia.adapter;

import java.util.concurrent.CompletableFuture;
import convex.core.data.ACell;

public abstract class AAdapter {
    /**
     * Returns the name of this adapter.
     * @return The adapter name (e.g. "mcp")
     */
    public abstract String getName();
    
    /**
     * Invoke an operation with the given input
     * @param operation The operation ID in the format "adapter:operation"
     * @param input The input parameters for the operation
     * @return A CompletableFuture that will complete with the result of the operation
     */
    public abstract CompletableFuture<ACell> invoke(String operation, ACell input);
}
