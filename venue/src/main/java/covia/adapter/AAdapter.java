package covia.adapter;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.util.ThreadUtils;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.AdapterWorkspace;
import covia.venue.RequestContext;

public abstract class AAdapter {

	private static final Logger log=LoggerFactory.getLogger(AAdapter.class);
	private static final int MAX_FAILURE_CHARS = 1024;

	/**
	 * Virtual thread executor for IO-bound blocking operations.
	 * Adapters should use this instead of the default ForkJoinPool.
	 */
	public static final ExecutorService VIRTUAL_EXECUTOR = ThreadUtils.getVirtualExecutor();

	public Engine engine;
	private AdapterWorkspace adapterWorkspace;

	/**
	 * Configures an adapter discovered from a venue module before it is
	 * registered or installed.
	 *
	 * <p>The module loader passes the immutable {@code modules[].config} object
	 * verbatim. Existing modules need not override this method. Optional modules
	 * may return {@code false} when their runtime prerequisites are unavailable;
	 * this skips registration without turning venue startup into a failure.
	 * Malformed known settings should still throw. When {@code strict} is true,
	 * implementations should also reject unknown settings.</p>
	 *
	 * @param config module-local configuration, empty when omitted
	 * @param strict whether venue {@code strictConfig} is enabled
	 * @return true to register this adapter; false to leave it inactive
	 */
	public boolean configureModule(AMap<AString, ACell> config, boolean strict) {
		return true;
	}

	/**
	 * Applies this adapter's <em>effective adapter configuration</em> — the
	 * venue's {@code adapters.<name>} block, overlaid by any runtime
	 * reconfiguration ({@code v/ops/venue/adapter/configure}). Called by
	 * {@link Engine#registerAdapter} before {@link #install(Engine)}, and again
	 * whenever the configuration changes while the adapter is live.
	 *
	 * <p>Adapters that read their config lazily via
	 * {@link Engine#adapterConfig(String)} at each call need not override this
	 * — they see new settings automatically. Override to validate settings up
	 * front or to rebuild cached derived state (clients, connection pools,
	 * allow-lists). Return {@code false} to decline: at registration the
	 * adapter is parked as disabled; at reconfiguration the new settings are
	 * rejected and the previous configuration stays in force. Malformed known
	 * settings should throw {@link IllegalArgumentException} with an
	 * actionable message.</p>
	 *
	 * <p>Distinct from {@link #configureModule}: that receives the module-level
	 * bootstrap settings ({@code modules[].config}) exactly once, before
	 * registration, and answers "can this module run here at all?".</p>
	 *
	 * @param config effective adapter configuration, empty when none
	 * @param strict whether venue {@code strictConfig} is enabled
	 * @return true to accept the configuration; false to decline it
	 */
	public boolean configure(AMap<AString, ACell> config, boolean strict) {
		return true;
	}

	/**
	 * Adapter-owned introspection, merged into this adapter's
	 * {@code v/info/adapters/<name>} record — the one place an adapter tells
	 * agents and clients facts about itself that are not operations: where
	 * its protocol is mounted, which optional feature is on, what a client
	 * needs to know to connect. Published when the adapter is materialised and
	 * again after every {@link #configure} so it reflects the effective
	 * configuration; keep it to facts that hold until the next reconfigure —
	 * live state (counts, connections, sessions) belongs in an operation.
	 *
	 * <p>The framework keys {@code name}, {@code description}, {@code kernel},
	 * {@code module} and {@code operations} are reserved and win over anything
	 * returned here.</p>
	 *
	 * @return extra fields for the adapter's info record, or null for none
	 */
	public AMap<AString, ACell> info() {
		return null;
	}

	/**
	 * Index of assets installed by this adapter.
	 * Maps asset Hash to asset metadata (AString).
	 */
	@SuppressWarnings("unchecked")
	protected Index<Hash, AString> installedAssets = (Index<Hash, AString>) Index.EMPTY;

	public void install(Engine engine) {
		this.engine=engine;
		this.adapterWorkspace=engine.adapterWorkspace(getName());
		installAssets();
	}

	/**
	 * Durable state owned by this adapter in the venue principal's private
	 * workspace at {@code w/adapters/<name>/}. The returned handle is permanently
	 * bound to both this adapter name and the venue identity; invocation contexts
	 * cannot redirect it into a caller's workspace.
	 *
	 * <p>User-managed content does not belong here. Adapters should accept an
	 * ordinary caller-authorised path for that data (with a sane {@code w/...}
	 * default). Credentials remain in {@code s/}; this workspace stores secret
	 * references only.</p>
	 */
	protected final AdapterWorkspace adapterWorkspace() {
		AdapterWorkspace workspace=adapterWorkspace;
		if (workspace==null) throw new IllegalStateException(
			"Adapter workspace is unavailable before install: "+getName());
		return workspace;
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
			return installAsset(readResource(resourcePath));
		} catch (Exception e) {
			// A missing or unreadable adapter resource is a packaging bug — the
			// venue would boot with silently missing ops. Fail loudly by default,
			// matching the Engine.materialiseVOps policy; strictAssets=false
			// downgrades to a warning for test/debug scaffolding only.
			if (engine == null || engine.config().isStrictAssets()) {
				throw new IllegalStateException(
					"Failed to install adapter asset from " + resourcePath + ": " + describeFailure(e), e);
			}
			log.warn("Failed to install asset from {} (tolerated: strictAssets=false)", resourcePath, e);
			return null;
		}
	}

	/**
	 * Reads an adapter resource, resolving against the ADAPTER's own
	 * classloader first — a module-packaged adapter (see
	 * {@link covia.venue.Modules}) finds its asset JSONs inside its module
	 * jar, which the venue's classloader cannot see into. Falls back to the
	 * venue classpath for built-in adapters.
	 *
	 * @param resourcePath The resource path, e.g. {@code /adapters/sql/query.json}
	 * @return The resource content
	 * @throws java.io.IOException If the resource cannot be found or read
	 */
	protected convex.core.data.AString readResource(String resourcePath) throws java.io.IOException {
		java.io.InputStream is = getClass().getResourceAsStream(resourcePath);
		if (is == null) return convex.core.util.Utils.readResourceAsAString(resourcePath);
		try (is) {
			return convex.core.data.Strings.fromStream(is);
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

	/** Installs constructed operation metadata at a catalog path. */
	protected Hash installAsset(String catalogPath, AMap<AString, ACell> metadata) {
		Hash hash = installAsset(metadata);
		if (hash == null) return null;
		if (!isValidCatalogPath(catalogPath)) {
			throw new IllegalArgumentException("Invalid catalog path: " + catalogPath);
		}
		declare("v/ops/", catalogPath, hash);
		return hash;
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
	 * Templates are ordinary content-addressed assets with reusable construction
	 * data under their {@code agent.config} facet. {@code agent:create} accepts
	 * the catalog path as one ordered config layer; resolution uses the standard
	 * lattice/asset path machinery, with no template-only lookup subsystem.
	 *
	 * @param catalogPath The template name (e.g. {@code "manager"})
	 * @param resourcePath Resource path of the template JSON
	 * @return The asset hash, or {@code null} if installation failed
	 */
	protected Hash installAgentTemplate(String catalogPath, String resourcePath) {
		return installAssetAt("v/agents/templates/", catalogPath, resourcePath);
	}

	/**
	 * Installs a venue skill under {@code /v/skills/<catalogPath>}. Skills are
	 * asset metadata maps (name, description, optional {@code skill} facet)
	 * discovered via the {@code v/skills} source and loaded by agents with
	 * {@code skill_load} — see {@code venue/docs/SKILLS.md}. The materialised
	 * catalog entry is the metadata; a skill whose body is content (rather
	 * than its description or a {@code content.dlfs} binding) needs that
	 * content stored in the CAS separately.
	 *
	 * @param catalogPath The skill name (e.g. {@code "summarise"})
	 * @param resourcePath Resource path of the skill metadata JSON
	 * @return The asset hash, or {@code null} if installation failed
	 */
	protected Hash installSkill(String catalogPath, String resourcePath) {
		return installAssetAt("v/skills/", catalogPath, resourcePath);
	}

	/**
	 * Installs constructed model-operation metadata under
	 * {@code /v/models/<provider>/<model-id>}.
	 *
	 * <p>The provider segment uses the ordinary catalog grammar. Model-id
	 * segments additionally allow the punctuation commonly used by model
	 * vendors ({@code . _ : + @ -}) and may be nested, as in
	 * {@code openrouter/anthropic/claude-sonnet-5}. A model path may not be a
	 * prefix of another model path because lattice nodes cannot be both an asset
	 * and a directory.</p>
	 *
	 * @param catalogPath provider and model id below {@code v/models/}
	 * @param metadata complete invocable operation metadata for the model
	 * @return the installed asset hash
	 */
	protected Hash installModel(String catalogPath, AMap<AString, ACell> metadata) {
		if (!isValidModelCatalogPath(catalogPath)) {
			throw new IllegalArgumentException("Invalid model catalog path: " + catalogPath);
		}
		String path = "v/models/" + catalogPath;
		for (String existing : pendingCatalogEntries.keySet()) {
			if (!existing.startsWith("v/models/")) continue;
			if (existing.equals(path) || existing.startsWith(path + "/") || path.startsWith(existing + "/")) {
				throw new IllegalArgumentException("Conflicting model catalog paths: "
					+ existing.substring("v/models/".length()) + " and " + catalogPath);
			}
		}
		Hash hash = installAsset(metadata);
		if (hash != null) declare("v/models/", catalogPath, hash);
		return hash;
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

		declare(prefix, catalogPath, hash);
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
	 * The adapter-owned mirror of everything this adapter installs, keyed by
	 * its {@code v/adapters/<name>/…} path: operations under {@code ops/}
	 * (with the adapter's own name prefix dropped — {@code v/ops/telegram/send}
	 * → {@code v/adapters/telegram/ops/send}), skills under {@code skills/},
	 * agent templates under {@code templates/}. Same asset hashes as
	 * {@link #pendingCatalogEntries} — the lattice shares the values — so an
	 * adapter's subtree is a complete, invocable view of what it offers, and
	 * it is published and retracted with the adapter as one unit alongside
	 * {@code info} and {@code config}.
	 */
	public final java.util.Map<String, Hash> ownedCatalogEntries = new java.util.LinkedHashMap<>();

	/** Records a catalog declaration at its canonical path and in this adapter's own subtree. */
	private void declare(String prefix, String catalogPath, Hash hash) {
		pendingCatalogEntries.put(prefix + catalogPath, hash);
		ownedCatalogEntries.put(ownedPath(prefix, catalogPath), hash);
	}

	/** {@code v/adapters/<name>/<kind>/<rel>} for a canonical catalog declaration. */
	String ownedPath(String prefix, String catalogPath) {
		String kind = switch (prefix) {
			case "v/ops/", "v/test/ops/" -> "ops";
			case "v/skills/" -> "skills";
			case "v/agents/templates/" -> "templates";
			default -> prefix.substring("v/".length(), prefix.length() - 1).replace('/', '-');
		};
		String name = getName();
		String rel = catalogPath;
		if (rel.startsWith(name + "/")) rel = rel.substring(name.length() + 1);
		// Skills flatten: the adapter's own skills/ directory must be a usable
		// SKILLSET (a flat directory of skills), so a grouped canonical path
		// such as "data/workspace" mirrors as "workspace" rather than nesting a
		// directory inside it. Skills and directories are separate kinds and
		// must not be mixed — see venue/docs/SKILLS.md §4.1.
		if ("skills".equals(kind)) rel = rel.substring(rel.lastIndexOf('/') + 1);
		return "v/adapters/" + name + "/" + kind + "/" + rel;
	}

	/**
	 * This adapter's configuration as it is safe to publish at
	 * {@code v/adapters/<name>/config}. Nothing is published unless the adapter
	 * says so: the default is null (no record). An adapter that has public
	 * settings overrides this and returns exactly the keys it wants seen —
	 * {@link #publicConfig(String...)} picks named top-level keys from the
	 * effective configuration ({@link Engine#adapterConfig}) — and never a
	 * credential, endpoint or anything else an operator would not put on the
	 * venue's public bulletin board.
	 *
	 * @return the publishable configuration, or null for none
	 */
	public AMap<AString, ACell> publicConfig() {
		return null;
	}

	/**
	 * The named top-level keys of this adapter's effective configuration, for
	 * {@link #publicConfig()} overrides: an explicit allow-list, no guessing.
	 *
	 * @param keys top-level configuration keys that are public
	 * @return those keys that are present, in order; empty when none are
	 */
	protected AMap<AString, ACell> publicConfig(String... keys) {
		AMap<AString, ACell> cfg = (engine != null) ? engine.adapterConfig(getName()) : null;
		AMap<AString, ACell> out = Maps.empty();
		if (cfg == null) return out;
		for (String key : keys) {
			AString k = Strings.create(key);
			ACell v = cfg.get(k);
			if (v != null) out = out.assoc(k, v);
		}
		return out;
	}


	/**
	 * Returns the catalog paths of this adapter's installed <em>operations</em>
	 * ({@code v/ops/...} and {@code v/test/ops/...} entries), in installation
	 * order. Non-operation catalog entries such as agent templates
	 * ({@code v/agents/templates/...}) are excluded — they are config assets,
	 * not invocable operations.
	 *
	 * @return List of full catalog paths, each invocable via {@code grid:run}
	 */
	public java.util.List<String> getOperationPaths() {
		java.util.ArrayList<String> paths = new java.util.ArrayList<>();
		for (String path : pendingCatalogEntries.keySet()) {
			if (path.startsWith("v/ops/") || path.startsWith("v/test/ops/")) {
				paths.add(path);
			}
		}
		return paths;
	}

	/**
	 * Validates a catalog path: non-empty {@code /}-separated segments,
	 * each matching {@code [a-z][a-z0-9-]*}, no {@code .} or {@code ..}.
	 */
	private static boolean isValidCatalogPath(String catalogPath) {
		if (catalogPath == null || catalogPath.isEmpty()) return false;
		String[] segments = catalogPath.split("/", -1);
		for (String seg : segments) {
			if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) return false;
			if (!seg.matches("^[a-z][a-z0-9-]*$")) return false;
		}
		return true;
	}

	/** Model paths are {@code <provider>/<vendor-model-id>}. */
	private static boolean isValidModelCatalogPath(String catalogPath) {
		if (catalogPath == null || catalogPath.isEmpty()) return false;
		String[] segments = catalogPath.split("/", -1);
		if (segments.length < 2 || !segments[0].matches("^[a-z][a-z0-9-]*$")) return false;
		for (int i = 1; i < segments.length; i++) {
			String seg = segments[i];
			if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) return false;
			if (!seg.matches("^[A-Za-z0-9][A-Za-z0-9._:+@-]*$")) return false;
		}
		return true;
	}

	/**
	 * Helper method to install a constructed asset.
	 * @param meta Constructed asset metadata
	 * @return Hash of the installed asset
	 */
	protected Hash installAsset(AMap<AString,ACell> meta) {
		return installAsset(JSON.printPretty(meta));
	}

    protected Hash installAsset(AString metaString) {
		AMap<AString, ACell> meta = RT.ensureMap(JSON.parse(metaString));
		ACell operation = meta.get(Fields.OPERATION);
		if (operation != null) {
			AMap<AString, ACell> op = RT.ensureMap(operation);
			ACell readOnly = op.get(Fields.READ_ONLY);
			if (!(readOnly instanceof CVMBool)) {
				ACell adapter = op.get(Fields.ADAPTER);
				throw new IllegalArgumentException(
					"Adapter operation " + (adapter == null ? "<unknown>" : adapter)
					+ " must explicitly declare operation.readOnly as true or false");
			}
		}
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
     * Builds the absolute, scheme-qualified capability resource for a
     * root/drive-addressed adapter op: {@code "<scheme>://<authority>/<path>"}.
     * A null authority yields the bare {@code "<scheme>://"} namespace resource;
     * the path's leading slash is stripped so grants compose by prefix
     * ({@code "file://scratch/"} covers {@code "file://scratch/notes.txt"}).
     *
     * <p>The single source for the {@code file://} and {@code dlfs://} capability
     * resource: the boundary's name-keyed {@code extractResource} has been
     * retired, so each adapter builds its own resource here at its enforcement
     * point.</p>
     */
    protected static String schemeResource(String scheme, AString authority, AString path) {
        if (authority == null) return scheme + "://";
        String p = (path == null) ? "" : path.toString();
        if (p.startsWith("/")) p = p.substring(1);
        return scheme + "://" + authority + "/" + p;
    }

    /** The baseline op-invocation ability — "the right to run an operation".
     *  Required by adapters that do not act on a specific named lattice resource
     *  (compute, LLM, external I/O, federation, scheduling, …). */
    protected static final AString INVOKE = Strings.intern("invoke");

    /**
     * Asserts the baseline {@link #INVOKE} capability at the adapter's enforcement
     * point. An invoke-class adapter calls this at the top of its dispatch — before
     * any side effect — so the capability check happens where the op actually
     * runs, with no central name-keyed mapping. A {@code null} grant scope
     * (authenticated/internal) is unrestricted (no-op); a restricted scope
     * (e.g. the public read-only profile, which withholds {@code invoke}) denies.
     *
     * <p>The checked resource is the caller-supplied operation reference from
     * {@link RequestContext#getOp()} (e.g. {@code "v/ops/langchain/openai"}),
     * so an {@code invoke} grant can be scoped to specific operations:
     * {@code {"with": "v/ops/getmine", "can": "invoke"}} (#211). A wildcard
     * grant — {@code {"can": "invoke"}} or {@code {"with": "", "can": "invoke"}}
     * — covers every invoke, including metadata-direct and hash-form
     * invocations where no reference path is available ({@code getOp() == null}
     * → resource-less check, coverable only by the wildcard).</p>
     */
    protected void requireInvoke(RequestContext ctx) {
        // The framework always supplies a context (at minimum ANONYMOUS); a null
        // ctx only occurs in direct unit-test calls that bypass dispatch — treat
        // as no enforcement context. Route through the single authority seam so an
        // invoke grant may be satisfied by a config grant OR a presented proof
        // (additive); fall back to the scope-only check when no engine is wired
        // (adapters constructed directly in unit tests).
        if (ctx == null) return;
        if (engine != null) engine.requireAuthority(ctx, ctx.getOp(), INVOKE);
        else ctx.requireCapability(ctx.getOp(), INVOKE);
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
     * @param ctx Request context, including caller and current Job
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
     * @param ctx Request context, including caller and current Job
     * @param meta The operation metadata (never null)
     * @param input The input parameters
     */
    public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
        // Default one-shot: wire future to job lifecycle
        job.setStatus(Status.STARTED);
		CompletableFuture<ACell> invocation;
		try {
			invocation = invokeFuture(ctx, meta, input);
			if (invocation == null) invocation = CompletableFuture.completedFuture(null);
		} catch (RuntimeException e) {
			job.fail(e);
			throw e;
		}
		bridgeToJob(job, invocation);
	}

	/**
	 * Selects the result future exposed by {@code run}/{@code invokeInternal}
	 * after this adapter has been invoked with a Job. Most operations expose the
	 * Job's eventual output directly. Adapters may override when the operation's
	 * declared result contract includes an earlier snapshot (for example,
	 * {@code agent:request} with a bounded wait).
	 */
	public CompletableFuture<ACell> resultFuture(Job job,
			AMap<AString, ACell> meta, ACell input) {
		return job.future();
	}

	// ===== Durability: recovery at boot, suspension at shutdown =====

	/** Recovery message for a durable Job that never began executing. */
	public static final String RESTARTED_BEFORE_START =
		"Venue restarted before execution began — retry if desired";

	/** Recovery message for a durable Job interrupted mid-execution. */
	public static final String RESTARTED_DURING_EXECUTION =
		"Venue restarted during execution — effects may or may not have applied;"
		+ " verify state before retrying";

	/** Cancellation reason for in-process work ended by venue shutdown. */
	public static final String VENUE_SHUT_DOWN = "Venue shut down";

	/**
	 * Called once at boot, before the venue serves requests, for every
	 * non-terminal durable Job whose operation this adapter owns. The Job
	 * carries its persisted record and every verb; whatever state it is in
	 * when this returns is the durable truth. The default never re-executes:
	 * {@code PENDING} and {@code STARTED} fail with a message the caller can
	 * act on, the paused family is restored live. Override to re-attach to
	 * work that continued outside this process (poll a remote job again,
	 * re-arm a timer) or to retry an operation the adapter knows is
	 * idempotent, and call {@code super} for the cases not handled.
	 *
	 * @param job the recovered Job
	 */
	public void recoverJob(Job job) {
		defaultRecover(job);
	}

	/**
	 * Called at shutdown for every active Job whose operation this adapter
	 * owns and which is still in flight once the grace window has passed.
	 * In-process execution is bounded and ends with the process; a wait on
	 * something outside it is state, not a thread. The default pauses a Job
	 * whose adapter registered a pause hook — its declaration that the work
	 * can be suspended — and cancels any other {@code PENDING}/{@code STARTED}
	 * Job with {@link #VENUE_SHUT_DOWN}; the paused family is left as is.
	 * Override to record a durable wait and let the thread go; whatever
	 * remains non-terminal is handed to {@link #recoverJob} at the next boot.
	 *
	 * @param job the in-flight Job
	 */
	public void suspendJob(Job job) {
		defaultSuspend(job);
	}

	/** The framework's recovery rule, also applied when a Job's adapter is absent. */
	public static void defaultRecover(Job job) {
		AString status = job.getStatus();
		if (Status.PENDING.equals(status)) {
			job.fail(RESTARTED_BEFORE_START);
		} else if (Status.STARTED.equals(status)) {
			job.fail(RESTARTED_DURING_EXECUTION);
		}
	}

	/** The framework's suspension rule, also applied when a Job's adapter is absent. */
	public static void defaultSuspend(Job job) {
		if (job.isFinished() || job.isPaused()) return;
		if (job.isPausable()) {
			job.pause();
		} else {
			job.cancel(VENUE_SHUT_DOWN);
		}
	}

	// ===== Shared future -> Job completion bridge =====

	/**
	 * Bridge a future to a Job's lifecycle: wire the standard cancel hook
	 * ({@code future.cancel(true)}), then settle the job when the future
	 * completes. The canonical one-shot completion path. Adapters that manage
	 * their own cancellation (e.g. a remote cancel) set their own hook and call
	 * {@link #completeFromJobFuture} instead.
	 */
	protected static void bridgeToJob(Job job, CompletableFuture<ACell> future) {
		job.setCancelHook(() -> future.cancel(true));
		completeFromJobFuture(job, future);
	}

	/**
	 * Settle a Job when {@code future} completes, without touching the cancel
	 * hook. Use when the adapter has already wired a bespoke cancel hook.
	 */
	protected static void completeFromJobFuture(Job job, CompletableFuture<ACell> future) {
		future.whenComplete((result, error) -> settleJob(job, result, error));
	}

	/**
	 * Map a completed {@code (result, error)} pair onto Job verbs — the single
	 * place completion, cancellation and failure are decided. Terminal
	 * stickiness makes this safe after a racing cancel/complete.
	 */
	protected static void settleJob(Job job, ACell result, Throwable error) {
		if (error == null) {
			job.completeWith(result);
		} else if (unwrap(error) instanceof CancellationException) {
			job.cancel();
		} else {
			job.fail(unwrap(error));
		}
	}

	/** Unwrap {@code CompletionException}/{@code ExecutionException} wrappers to the root cause. */
	protected static Throwable unwrap(Throwable t) {
		Throwable cause = t;
		while ((cause instanceof java.util.concurrent.CompletionException
				|| cause instanceof java.util.concurrent.ExecutionException)
				&& cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause;
	}

	/**
	 * Render a Throwable into a non-empty diagnostic string for {@link Job#fail}:
	 * unwrap async wrappers, use the message when present, else fall back to the
	 * exception's {@code toString()} so a failure never surfaces as a blank error.
	 */
	public static String describeFailure(Throwable t) {
		if (t == null) return "Operation failed without an error detail";
		Throwable cause = unwrap(t);
		String msg = cause.getMessage();
		String text;
		if (msg == null || msg.isBlank()) {
			text = cause.getClass().getSimpleName() + " (no detail)";
		} else if (isSelfDescribing(cause)) {
			text = msg;
		} else {
			// A JVM error's or library exception's message alone can be opaque —
			// NoClassDefFoundError's is just a class name — so name the type.
			text = cause.getClass().getSimpleName() + ": " + msg;
		}
		if (cause instanceof LinkageError) {
			text += " — a class could not be loaded; if a jar was rebuilt or replaced while the venue "
				+ "was running, restart the venue";
		}
		return conciseDetail(text, MAX_FAILURE_CHARS);
	}

	/**
	 * Whether a throwable's message reads as a complete diagnostic on its own:
	 * Covia's own exceptions and the standard argument/state/unsupported
	 * families are written for humans; everything else is named by type too.
	 */
	public static boolean isSelfDescribing(Throwable t) {
		return t instanceof covia.exception.CoviaException
			|| t.getClass() == RuntimeException.class      // used as a plain message carrier throughout
			|| t instanceof IllegalArgumentException
			|| t instanceof IllegalStateException
			|| t instanceof UnsupportedOperationException;
	}

	/**
	 * Render bounded, single-line detail suitable for a Job error. This is
	 * intentionally cheap: external response bodies and exception text must not
	 * turn a useful diagnostic into an unbounded prompt payload.
	 */
	protected static String conciseDetail(Object value, int maxChars) {
		if (value == null) return "no detail";
		String text = String.valueOf(value).trim();
		if (text.isEmpty()) return "no detail";
		if (text.length() > maxChars) text = text.substring(0, maxChars) + "…";
		return text.replace('\r', ' ').replace('\n', ' ');
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
