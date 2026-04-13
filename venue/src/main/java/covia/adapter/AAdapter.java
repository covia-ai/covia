package covia.adapter;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Strings;
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
	 *
	 * <p><b>Legacy form.</b> Stores the asset in the venue CAS and adds an
	 * entry to the per-adapter operation registry indexed by
	 * {@code operation.adapter}. Per OPERATIONS.md, the new
	 * {@link #installAsset(String, String)} overload should be preferred —
	 * it adds an explicit catalog path that places the asset in
	 * {@code /v/ops/&lt;catalogPath&gt;}.</p>
	 *
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
	 * Installs an asset and registers it in the venue's operation catalog
	 * at {@code /v/ops/&lt;catalogPath&gt;}.
	 *
	 * <p>The catalog path is the user-facing name of the operation,
	 * decoupled from {@code operation.adapter} (which is internal dispatch
	 * info). Per OPERATIONS.md §7, adapters declare the catalog path
	 * explicitly rather than having the venue derive it from the dispatch
	 * string.</p>
	 *
	 * <p>Validation:</p>
	 * <ul>
	 *   <li>Each segment matches {@code ^[a-z][a-z0-9-]*$}</li>
	 *   <li>No segment is {@code .} or {@code ..}</li>
	 *   <li>No two adapters install at the same {@code /v/ops/} path</li>
	 * </ul>
	 *
	 * <p>Invalid paths log a warning and skip the catalog entry; the asset
	 * still lives in the venue CAS and remains callable by hash.</p>
	 *
	 * @param catalogPath The path under {@code /v/ops/} to install at
	 *                    (e.g. {@code "json/merge"})
	 * @param resourcePath The resource path to read the asset from
	 * @return The asset hash, or {@code null} if installation failed
	 */
	protected Hash installAsset(String catalogPath, String resourcePath) {
		return installAssetAt("v/ops/", catalogPath, resourcePath);
	}

	/**
	 * Installs a non-primitive example asset (e.g. a demo orchestration or a
	 * pre-canned sample). The asset is stored in the venue CAS and remains
	 * callable by hash, but is NOT registered in {@code /v/ops/}. Per
	 * OPERATIONS.md §7, only adapter primitives belong in the catalog.
	 *
	 * @param resourcePath Resource path of the asset JSON
	 * @return The asset hash, or {@code null} if installation failed
	 */
	protected Hash installExampleAsset(String resourcePath) {
		return installAsset(resourcePath);
	}

	/**
	 * Installs a test-only operation under {@code /v/test/ops/<catalogPath>}.
	 * This keeps the test ops in their own sub-namespace under {@code /v/test/}
	 * — hidden by default from {@code /v/ops/} listings while still callable
	 * via the explicit path. Per OPERATIONS.md §7.
	 *
	 * @param catalogPath The path under {@code /v/test/ops/} (e.g. {@code "echo"})
	 * @param resourcePath Resource path of the asset JSON
	 * @return The asset hash, or {@code null} if installation failed
	 */
	protected Hash installTestAsset(String catalogPath, String resourcePath) {
		return installAssetAt("v/test/ops/", catalogPath, resourcePath);
	}

	/**
	 * Installs an agent template under {@code /v/agents/templates/<catalogPath>}.
	 * Templates are flat config maps (systemPrompt, tools, caps, etc.) used by
	 * {@code agent:create} via {@code config="v/agents/templates/<name>"} —
	 * just standard lattice path resolution, no special-case lookup.
	 *
	 * @param catalogPath The template name (e.g. {@code "manager"})
	 * @param resourcePath Resource path of the template JSON
	 * @return The asset hash, or {@code null} if installation failed
	 */
	protected Hash installAgentTemplate(String catalogPath, String resourcePath) {
		return installAssetAt("v/agents/templates/", catalogPath, resourcePath);
	}

	/**
	 * Shared implementation: store the asset, validate the catalog path, and
	 * defer the materialisation write until {@link covia.venue.Engine#materialiseVOps}.
	 */
	private Hash installAssetAt(String prefix, String catalogPath, String resourcePath) {
		Hash hash = installAsset(resourcePath);
		if (hash == null) return null;

		if (!isValidCatalogPath(catalogPath)) {
			log.warn("Invalid catalog path '{}' for resource {} — asset stored in CAS only",
				catalogPath, resourcePath);
			return hash;
		}

		pendingCatalogEntries.put(prefix + catalogPath, hash);
		return hash;
	}

	/**
	 * Catalog entries collected during {@link #installAsset(String, String)}
	 * and {@link #installTestAsset(String, String)} calls, awaiting
	 * materialisation by {@link covia.venue.Engine#materialiseVOps}. Maps the
	 * full target path (e.g. {@code "v/ops/json/merge"} or
	 * {@code "v/test/ops/echo"}) to the asset hash.
	 */
	public final java.util.Map<String, Hash> pendingCatalogEntries = new java.util.LinkedHashMap<>();

	/**
	 * Validates a catalog path: non-empty {@code /}-separated segments,
	 * each matching {@code [a-z][a-z0-9-]*}, no {@code .} or {@code ..}.
	 */
	private static boolean isValidCatalogPath(String catalogPath) {
		if (catalogPath == null || catalogPath.isEmpty()) return false;
		String[] segments = catalogPath.split("/");
		for (String seg : segments) {
			if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) return false;
			if (!seg.matches("^[a-z][a-z0-9-]*$")) return false;
		}
		return true;
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
