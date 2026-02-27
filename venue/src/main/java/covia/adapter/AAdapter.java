package covia.adapter;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.util.ThreadUtils;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;

public abstract class AAdapter {

	private static final Logger log=LoggerFactory.getLogger(AAdapter.class);

	/**
	 * Virtual thread executor for IO-bound blocking operations.
	 * Adapters should use this instead of the default ForkJoinPool.
	 */
	public static final ExecutorService VIRTUAL_EXECUTOR = ThreadUtils.getVirtualExecutor();

	protected Engine engine;

	/**
	 * Index of assets installed by this adapter.
	 * Maps asset Hash to asset metadata (AString).
	 */
	@SuppressWarnings("unchecked")
	protected Index<Hash, AString> installedAssets = (Index<Hash, AString>) Index.EMPTY;

	/**
	 * Index of operation names registered by this adapter.
	 * Maps operation name (e.g. "test:echo") to the canonical asset Hash.
	 */
	@SuppressWarnings("unchecked")
	protected Index<AString, Hash> operationNames = (Index<AString, Hash>) Index.EMPTY;

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

		// Track operation name → asset hash mapping
		AMap<AString,ACell> meta = RT.ensureMap(JSON.parse(metaString));
		if (meta != null) {
			AString adapterOp = RT.ensureString(RT.getIn(meta, "operation", "adapter"));
			if (adapterOp != null) {
				operationNames = operationNames.assoc(adapterOp, assetHash);
			}
		}
		return assetHash;
    }

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
     * Returns the index of operation names registered by this adapter.
     * @return Index mapping operation name (e.g. "test:echo") to canonical asset Hash
     */
    public Index<AString, Hash> getOperationNames() {
        return operationNames;
    }

    // ========== Metadata Utility Methods ==========

    /**
     * Extracts the sub-operation name from operation metadata.
     * E.g. for metadata with {@code operation.adapter = "test:echo"}, returns {@code "echo"}.
     *
     * @param meta The operation metadata map
     * @return The sub-operation name, or null if not found
     */
    public static String getSubOperation(AMap<AString, ACell> meta) {
        if (meta == null) return null;
        AString adapterOp = RT.ensureString(RT.getIn(meta, "operation", "adapter"));
        if (adapterOp == null) return null;
        String s = adapterOp.toString();
        int colon = s.indexOf(':');
        return (colon >= 0) ? s.substring(colon + 1) : s;
    }

    /**
     * Extracts the full {@code adapter:operation} string from operation metadata.
     * E.g. for metadata with {@code operation.adapter = "test:echo"}, returns {@code "test:echo"}.
     *
     * @param meta The operation metadata map
     * @return The full adapter:operation string, or null if not found
     */
    public static String getAdapterOperation(AMap<AString, ACell> meta) {
        if (meta == null) return null;
        AString adapterOp = RT.ensureString(RT.getIn(meta, "operation", "adapter"));
        return (adapterOp != null) ? adapterOp.toString() : null;
    }

    /**
     * Extracts the adapter name from operation metadata.
     * E.g. for metadata with {@code operation.adapter = "test:echo"}, returns {@code "test"}.
     *
     * @param meta The operation metadata map
     * @return The adapter name, or null if not found
     */
    public static String getAdapterName(AMap<AString, ACell> meta) {
        String full = getAdapterOperation(meta);
        if (full == null) return null;
        int colon = full.indexOf(':');
        return (colon >= 0) ? full.substring(0, colon) : full;
    }

    // ========== Invocation Interface ==========

    /**
     * Invoke an operation with resolved metadata and request context, returning a future.
     *
     * <p>This is the primary invocation interface. The engine resolves all operation
     * reference forms to metadata before dispatching — meta is always non-null.
     * Adapters use {@link #getSubOperation(AMap)} to extract their sub-operation
     * from the metadata rather than parsing a raw operation string.
     *
     * @param ctx Request context (caller identity, internal flag)
     * @param meta The operation metadata (never null)
     * @param input The input parameters
     * @return A CompletableFuture that will complete with the result
     */
    public abstract CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input);

    /**
     * Invoke an operation with resolved metadata, request context, and job.
     *
     * <p>This is the primary job-aware invocation interface. The engine resolves all
     * operation reference forms to metadata before dispatching — meta is always non-null.
     *
     * <p>The default implementation wires the {@link #invokeFuture(RequestContext, AMap, ACell)}
     * result to the job lifecycle. Override for adapters that need direct job control
     * (e.g. multi-turn, caller DID propagation, orchestration).
     *
     * <p><b>Timeout policy:</b> Jobs intentionally have NO framework-level timeout.
     * Jobs can be long-running (days, weeks, or months for workflows, orchestrations,
     * or human-in-the-loop processes). Individual adapters SHOULD apply IO-level timeouts
     * on their external calls (HTTP requests, LLM API calls, etc.) to prevent network-level
     * hangs, but must not impose blanket timeouts on the job lifecycle.
     *
     * @param job The Job prepared to run
     * @param ctx Request context (caller identity, internal flag)
     * @param meta The operation metadata (never null)
     * @param input The input parameters
     */
    public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
        // Default one-shot: wire future to job lifecycle
        job.setStatus(Status.STARTED);
        invokeFuture(ctx, meta, input).thenAccept(result -> {
            job.completeWith(result);
        })
        .exceptionally(e -> {
            if (e instanceof CancellationException) {
                job.cancel();
            } else {
                Throwable cause = (e instanceof java.util.concurrent.CompletionException && e.getCause() != null)
                    ? e.getCause() : e;
                job.fail(cause.getMessage());
            }
            return null;
        });
    }

    /**
     * Handles a message delivered to a running job.
     * Override this method in adapters that support multi-turn interactions.
     * Default implementation does nothing (message remains in queue).
     *
     * @param job The job receiving the message
     * @param messageRecord The message record (contains "message", "source", "ts", "id" fields)
     */
    public void handleMessage(Job job, AMap<AString, ACell> messageRecord) {
    	// Default: no-op. Message stays in queue for polling by adapter.
    }

    /**
     * Returns true if this adapter supports multi-turn message handling.
     * @return true if handleMessage() is implemented
     */
    public boolean supportsMultiTurn() {
    	return false;
    }

}
