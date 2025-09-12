package covia.adapter;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;

public abstract class AAdapter {
	
	private static final Logger log=LoggerFactory.getLogger(AAdapter.class);

	
	protected Engine engine;
	
	/**
	 * Index of assets installed by this adapter.
	 * Maps asset Hash to asset metadata (AString).
	 */
	@SuppressWarnings("unchecked")
	protected Index<Hash, AString> installedAssets = (Index<Hash, AString>) Index.EMPTY;

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
	 */
	protected Hash installAsset(String resourcePath) {
		try {
			return installAsset(convex.core.util.Utils.readResourceAsAString(resourcePath));
		} catch (Exception e) {
			// Log warning but don't fail installation
			log.warn("Failed to install asset from " + resourcePath ,e);
			return null;
		}
		
	}
	
	/**
	 * Helper method to install a constructed asset.
	 * @param resourcePath The resource path to read the asset from
	 */
	protected Hash installAsset(AMap<AString,ACell> meta) {
		return installAsset(JSON.printPretty(meta));
	}
	
    protected Hash installAsset(AString metaString) {
		Hash assetHash = engine.storeAsset(metaString, null);
		installedAssets = installedAssets.assoc(assetHash, metaString);
		return assetHash;
    };

	    /**
     * Returns the name of this adapter.
     * @return The adapter name (e.g. "mcp")
     */
    public abstract String getName();
    
    /**
     * Returns a description of what this adapter is used for.
     * This should be a compelling, LLM-friendly description that explains
     * the adapter's purpose and capabilities.
     * @return A description of the adapter's functionality
     */
    public abstract String getDescription();
    
    /**
     * Returns the index of assets installed by this adapter.
     * @return Index mapping asset Hash to asset metadata
     */
    public Index<Hash, AString> getInstalledAssets() {
        return installedAssets;
    }
    
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
}
