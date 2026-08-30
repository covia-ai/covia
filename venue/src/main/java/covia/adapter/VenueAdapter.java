package covia.adapter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.api.Fields;
import covia.venue.Config;
import covia.venue.Modules;
import covia.venue.RequestContext;
import covia.venue.StoreControl;

/**
 * Public venue configuration plus live adapter/module administration and
 * process restart.
 *
 * <p>{@code venue/show-config} is the deliberately curated public exception:
 * it requires only read access to {@code v/config} and reports effective
 * operational settings without reflecting the raw operator document. Every
 * lifecycle operation is venue-owned. Adapter/module lifecycle requires a
 * venue-rooted delegation over {@code <venue DID>/adapters} with
 * {@code adapter/manage}; process restart requires {@code venue/restart} over
 * {@code <venue DID>/process} ({@link covia.venue.Engine#requireVenueAuthority}).
 * Direct venue execution is allowed. A null capability scope is deliberately
 * not enough for an ordinary authenticated user.</p>
 *
 * <ul>
 *   <li>{@code venue/show-config} — curated public effective settings useful
 *       to agents and clients; never raw configuration.</li>
 *   <li>{@code venue/adapters} — full registry view: active and disabled
 *       adapters (kernel flag, owning module, operations) plus loaded modules.</li>
 *   <li>{@code venue/adapter/enable|disable} — retract or restore a non-kernel
 *       adapter's catalog, introspection and dispatch.</li>
 *   <li>{@code venue/adapter/configure} — apply a new effective configuration
 *       ({@link covia.venue.Engine#configureAdapter}).</li>
 *   <li>{@code venue/module/load|unload} — runtime module lifecycle, subject
 *       to the operator's {@code dynamicModules} policy ({@link Modules}).</li>
 *   <li>{@code venue/restart} — process-wide graceful successor handoff,
 *       separately guarded by {@code venue/restart} on
 *       {@code <venue DID>/process}.</li>
 *   <li>{@code venue/gc} — online garbage collection of the Etch store
 *       while the venue keeps serving (covia#452), separately guarded by
 *       {@code venue/gc} on {@code <venue DID>/store}.</li>
 * </ul>
 *
 * <p>Changes are not persisted: after a restart the venue config is
 * authoritative again. This is the runtime lever; a persisted live config is
 * a later step.</p>
 */
public class VenueAdapter extends AAdapter {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VenueAdapter.class);

	static final String RESOURCE = "adapters";
	static final String PROCESS_RESOURCE = "process";
	static final String STORE_RESOURCE = "store";

	private static final AString K_ENABLED = Strings.intern("enabled");
	private static final AString K_KERNEL = Strings.intern("kernel");
	private static final AString K_MODULE = Strings.intern("module");
	private static final AString K_MODULES = Strings.intern("modules");
	private static final AString K_ADAPTERS = Strings.intern("adapters");
	private static final AString K_OPERATIONS = Strings.intern("operations");
	private static final AString K_CONFIG = Strings.intern("config");
	private static final AString K_MERGE = Strings.intern("merge");
	private static final AString K_CHANGED = Strings.intern("changed");
	private static final AString K_PATH = Strings.intern("path");
	private static final AString K_SHA256 = Strings.intern("sha256");
	private static final AString K_UNLOADED = Strings.intern("unloaded");
	private static final AString K_ACCEPTED = Strings.intern("accepted");
	private static final AString K_JAR = Strings.intern("jar");
	private static final AString K_FALLBACK = Strings.intern("fallback");
	private static final AString K_STARTUP_TIMEOUT = Strings.intern("startupTimeout");
	private static final AString K_VENUE = Strings.intern("venue");
	private static final AString K_AGENTS = Strings.intern("agents");
	private static final AString K_JOBS = Strings.intern("jobs");
	private static final AString K_STORAGE = Strings.intern("storage");
	private static final AString K_ACCESS = Strings.intern("access");
	private static final AString K_PROTOCOLS = Strings.intern("protocols");
	private static final AString K_LIMITS = Strings.intern("limits");
	private static final AString K_VALIDATION = Strings.intern("validation");
	private static final AString K_PUBLIC_CONFIG = Strings.intern("publicConfig");
	private static final AString K_NOTICE = Strings.intern("notice");
	private static final AString K_STATUS = Strings.intern("status");
	private static final AString K_CANCEL = Strings.intern("cancel");
	private static final AString K_RESTART = Strings.intern("restart");
	private static final AString K_FILE = Strings.intern("file");
	private static final AString K_BYTES = Strings.intern("bytes");
	private static final AString K_BYTES_BEFORE = Strings.intern("bytesBefore");
	private static final AString K_BYTES_AFTER = Strings.intern("bytesAfter");
	private static final AString K_RECLAIMED = Strings.intern("reclaimed");
	private static final AString K_RECLAIMED_AT = Strings.intern("reclaimedAt");
	private static final AString K_ELAPSED = Strings.intern("elapsedMillis");
	private static final AString K_COLLECTED_FILE = Strings.intern("collectedFile");
	private static final AString K_COLLECTED_BYTES = Strings.intern("collectedBytes");
	private static final AString K_IN_PROGRESS = Strings.intern("inProgress");
	private static final AString K_SWEEP_COMPLETE = Strings.intern("sweepComplete");
	private static final AString K_COMPLETED = Strings.intern("completed");

	@Override
	public String getName() {
		return "venue";
	}

	@Override
	public String getDescription() {
		return "Public effective venue settings, plus venue-owned administration to enable, disable "
			+ "and reconfigure adapters, load or unload modules, garbage-collect the store, "
			+ "or restart the standalone process.";
	}

	@Override
	protected void installAssets() {
		installAsset("venue/show-config", "/adapters/venue/showConfig.json");
		installAsset("venue/adapters", "/adapters/venue/adapters.json");
		installAsset("venue/adapter/enable", "/adapters/venue/adapterEnable.json");
		installAsset("venue/adapter/disable", "/adapters/venue/adapterDisable.json");
		installAsset("venue/adapter/configure", "/adapters/venue/adapterConfigure.json");
		installAsset("venue/module/load", "/adapters/venue/moduleLoad.json");
		installAsset("venue/module/unload", "/adapters/venue/moduleUnload.json");
		installAsset("venue/restart", "/adapters/venue/restart.json");
		installAsset("venue/gc", "/adapters/venue/gc.json");
		installSkill("adapters/adapters", "/skills/adapters.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx,
			AMap<AString, ACell> meta, ACell input) {
		try {
			String subOperation = getSubOperation(meta);
			if ("show-config".equals(subOperation)) {
				engine.requireResourceAccess(ctx, Strings.create("v/config"), Capability.CRUD_READ);
				return CompletableFuture.completedFuture(showConfig());
			}
			requireInvoke(ctx);
			if ("gc".equals(subOperation)) {
				engine.requireVenueAuthority(ctx, STORE_RESOURCE, Abilities.VENUE_GC);
				return gc(ctx, input);
			}
			if ("restart".equals(subOperation)) {
				engine.requireVenueAuthority(ctx, PROCESS_RESOURCE, Abilities.VENUE_RESTART);
			} else {
				engine.requireVenueAuthority(ctx, RESOURCE, Abilities.ADAPTER_MANAGE);
			}
			return CompletableFuture.completedFuture(switch (subOperation) {
				case "adapters" -> adapters();
				case "adapter-enable" -> adapterEnable(input);
				case "adapter-disable" -> adapterDisable(input);
				case "adapter-configure" -> adapterConfigure(input);
				case "module-load" -> moduleLoad(input);
				case "module-unload" -> moduleUnload(input);
				case "restart" -> restart(ctx, input);
				default -> throw new IllegalArgumentException(
					"Unknown venue operation: " + getSubOperation(meta));
			});
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	// ========== public effective configuration ==========

	/**
	 * A stable allow-list of effective settings that affect how callers and
	 * resident agents use this venue. This must never become a filtered copy of
	 * the raw operator document: additions are explicit security decisions. Adapter
	 * settings come only from each adapter's equally-public
	 * {@link AAdapter#publicConfig()} allow-list.
	 */
	ACell showConfig() {
		Config config = engine.config();

		AMap<AString, ACell> venue = Maps.of(
			Fields.DID, engine.getDIDString(),
			Fields.URL, Strings.create(config.getBaseUrl()));
		if (engine.getName() != null) venue = venue.assoc(Fields.NAME, engine.getName());

		AMap<AString, ACell> agentDefaults = Maps.of(
			"defaultLlmOperation", config.getDefaultLlmOperation(),
			"defaultTransitionOp", config.getDefaultTransitionOp(),
			"maxToolIterations", config.getMaxToolIterations());
		AMap<AString, ACell> jobs = Maps.of(
			"recordReadOnlyOperations", config.isRecordReadOnlyOperations(),
			"privateJobsEnabled", config.isPrivateJobsEnabled(),
			"scheduledJobsTrackedByDefault", config.isTrackScheduledJobs(),
			"scheduledJobTrackingForced", config.isForceTrackScheduledJobs());

		String store = config.getStore();
		String persistence = "persistent";
		if ("temp".equalsIgnoreCase(store)) persistence = "temporary";
		else if ("memory".equalsIgnoreCase(store)) persistence = "memory-only";
		AMap<AString, ACell> storage = Maps.of(
			"statePersistence", persistence,
			"contentBackend", config.getStorageType(),
			"stateEncrypted", config.hasEncryptedEtchPolicy(),
			"maxContentSize", config.getMaxContentSize());

		AMap<AString, ACell> access = Maps.of(
			"public", config.isPublicAccess(),
			"userAutoCreate", config.isUserAutoCreate());
		AMap<AString, ACell> protocols = Maps.of(
			"rest", true,
			"mcp", Maps.of("enabled", config.hasMCP(), "authRequired", config.isMCPAuthRequired()),
			"a2a", Maps.of("enabled", config.hasA2A()));
		AMap<AString, ACell> limits = Maps.of(
			"rateLimitEnabled", config.isRateLimitEnabled(),
			"requestsPerSecond", RT.cvm(config.getRateLimitRps()),
			"requestBurst", RT.cvm(config.getRateLimitBurst()),
			"maxConcurrentJobsPerUser", config.getMaxConcurrentJobsPerUser(),
			"admissionBlockMs", config.getRateLimitBlockMs());

		List<String> names = new ArrayList<>(engine.getAdapterNames());
		names.sort(String::compareTo);
		AVector<ACell> active = Vectors.empty();
		AMap<AString, ACell> publicConfig = Maps.empty();
		for (String name : names) {
			active = active.conj(Strings.create(name));
			AMap<AString, ACell> published = engine.getAdapter(name).publicConfig();
			if (published != null && !published.isEmpty()) {
				publicConfig = publicConfig.assoc(Strings.create(name), published);
			}
		}

		return Maps.of(
			K_VENUE, venue,
			K_AGENTS, agentDefaults,
			K_JOBS, jobs,
			K_STORAGE, storage,
			K_ACCESS, access,
			K_PROTOCOLS, protocols,
			K_LIMITS, limits,
			K_VALIDATION, Maps.of("output", config.getOutputValidation()),
			K_ADAPTERS, Maps.of("active", active, K_PUBLIC_CONFIG, publicConfig),
			K_NOTICE, Strings.create("Curated effective settings only; private paths, allow-lists, "
				+ "module details, credentials and secret references are omitted."));
	}

	// ========== adapters ==========

	ACell adapters() {
		List<String> names = new ArrayList<>(engine.getAdapterNames());
		names.addAll(engine.getDisabledAdapterNames());
		names.sort(String::compareTo);
		AVector<ACell> adapters = Vectors.empty();
		for (String name : names) {
			boolean enabled = engine.hasAdapter(name);
			AAdapter adapter = engine.getAdapter(name);
			adapters = adapters.conj(adapterEntry(name, adapter, enabled));
		}
		AVector<ACell> modules = Vectors.empty();
		for (Modules.LoadedModule module : engine.getModules()) {
			modules = modules.conj(moduleEntry(module));
		}
		return Maps.of(K_ADAPTERS, adapters, K_MODULES, modules);
	}

	private AMap<AString, ACell> adapterEntry(String name, AAdapter adapter, boolean enabled) {
		AMap<AString, ACell> entry = Maps.of(
			Fields.NAME, Strings.create(name),
			K_ENABLED, CVMBool.of(enabled),
			K_KERNEL, CVMBool.of(engine.isKernelAdapter(name)),
			K_CONFIG, engine.adapterConfig(name));
		if (adapter != null) {
			entry = entry.assoc(Fields.DESCRIPTION, Strings.create(adapter.getDescription()));
			AVector<ACell> ops = Vectors.empty();
			List<String> paths = new ArrayList<>(adapter.getOperationPaths());
			paths.sort(String::compareTo);
			for (String path : paths) ops = ops.conj(Strings.create(path));
			entry = entry.assoc(K_OPERATIONS, ops);
		}
		Modules.LoadedModule module = engine.moduleOf(name);
		if (module != null) entry = entry.assoc(K_MODULE, Strings.create(module.name()));
		return entry;
	}

	private static AMap<AString, ACell> moduleEntry(Modules.LoadedModule module) {
		AVector<ACell> adapters = Vectors.empty();
		for (String name : module.adapterNames()) adapters = adapters.conj(Strings.create(name));
		AMap<AString, ACell> entry = Maps.of(
			Fields.NAME, Strings.create(module.name()),
			K_PATH, Strings.create(module.jar().toString()),
			K_ADAPTERS, adapters);
		if (module.sha256() != null) entry = entry.assoc(K_SHA256, Strings.create(module.sha256()));
		return entry;
	}

	// ========== adapter enable / disable / configure ==========

	ACell adapterEnable(ACell input) {
		String name = requireName(input, "name");
		boolean changed = engine.enableAdapter(name);
		return Maps.of(Fields.NAME, Strings.create(name),
			K_ENABLED, CVMBool.TRUE, K_CHANGED, CVMBool.of(changed));
	}

	ACell adapterDisable(ACell input) {
		String name = requireName(input, "name");
		boolean changed = engine.disableAdapter(name);
		return Maps.of(Fields.NAME, Strings.create(name),
			K_ENABLED, CVMBool.FALSE, K_CHANGED, CVMBool.of(changed));
	}

	@SuppressWarnings("unchecked")
	ACell adapterConfigure(ACell input) {
		String name = requireName(input, "name");
		ACell cfgCell = RT.getIn(input, K_CONFIG);
		if (!(cfgCell instanceof AMap)) {
			throw new IllegalArgumentException("config is required and must be an object");
		}
		AMap<AString, ACell> cfg = (AMap<AString, ACell>) cfgCell;
		if (RT.bool(RT.getIn(input, K_MERGE))) {
			cfg = engine.adapterConfig(name).merge(cfg);
		}
		engine.configureAdapter(name, cfg);
		return Maps.of(Fields.NAME, Strings.create(name), K_CONFIG, engine.adapterConfig(name));
	}

	// ========== module load / unload ==========

	@SuppressWarnings("unchecked")
	ACell moduleLoad(ACell input) {
		String ref = requireName(input, "module");
		Path jar = Modules.resolveDynamicPath(engine.config(), ref);
		AString sha = RT.ensureString(RT.getIn(input, K_SHA256));
		ACell cfgCell = RT.getIn(input, K_CONFIG);
		AMap<AString, ACell> cfg = (cfgCell instanceof AMap) ? (AMap<AString, ACell>) cfgCell : Maps.empty();
		Modules.LoadedModule module = Modules.load(engine, jar,
			(sha != null) ? sha.toString() : null, cfg);
		return moduleEntry(module);
	}

	ACell moduleUnload(ACell input) {
		String name = requireName(input, "name");
		if (!engine.config().isDynamicModulesEnabled()) {
			throw new IllegalStateException(
				"Dynamic module loading is disabled on this venue (set dynamicModules.enabled)");
		}
		Modules.LoadedModule module = Modules.unload(engine, name);
		return moduleEntry(module).assoc(K_UNLOADED, CVMBool.TRUE);
	}

	// ========== store garbage collection ==========

	/**
	 * {@code venue/gc} (covia#452): {@code status}/{@code cancel} answer at
	 * once; otherwise one online cycle runs on its own thread with the Job
	 * {@code STARTED} throughout — cancelling the Job cancels the cycle, which
	 * rolls the store back to its original file. With {@code restart}, both
	 * authorities and restart availability are checked <em>before</em>
	 * collecting, so a cycle never runs for a restart that cannot follow.
	 */
	private CompletableFuture<ACell> gc(RequestContext ctx, ACell input) throws IOException {
		StoreControl control = engine.storeControl();
		if (CVMBool.TRUE.equals(RT.getIn(input, K_STATUS))) {
			return CompletableFuture.completedFuture(status(control.status()));
		}
		if (CVMBool.TRUE.equals(RT.getIn(input, K_CANCEL))) {
			control.cancel();
			return CompletableFuture.completedFuture(status(control.status()));
		}
		boolean restart = CVMBool.TRUE.equals(RT.getIn(input, K_RESTART));
		long startupTimeout = 60_000;
		if (restart) {
			engine.requireVenueAuthority(ctx, PROCESS_RESOURCE, Abilities.VENUE_RESTART);
			if (!engine.hasProcessControl()) {
				throw new IllegalStateException(
					"Process restart is unavailable: this venue is not managed by MainVenue");
			}
			ACell timeoutCell = RT.getIn(input, K_STARTUP_TIMEOUT);
			if (timeoutCell != null) {
				convex.core.data.prim.CVMLong value = RT.ensureLong(timeoutCell);
				if (value == null) throw new IllegalArgumentException("startupTimeout must be an integer");
				startupTimeout = value.longValue();
			}
		}
		final long timeout = startupTimeout;
		final covia.grid.Job job = ctx.getJob();
		CompletableFuture<ACell> result = new CompletableFuture<>();
		// The standard Job bridge cancels this future: turn that into a cycle
		// cancel, which makes the sweep stop and the store roll back
		result.whenComplete((ignored, failure) -> {
			if (!result.isCancelled()) return;
			try {
				control.cancel();
			} catch (IOException | RuntimeException e) {
				log.warn("Cancelling the store collection failed", e);
			}
		});
		Thread.ofVirtual().name("covia-store-gc").start(() -> {
			try {
				StoreControl.Result r = control.collect();
				AMap<AString, ACell> out = Maps.of(
					K_FILE, Strings.create(r.file()),
					K_BYTES_BEFORE, convex.core.data.prim.CVMLong.create(r.bytesBefore()),
					K_BYTES_AFTER, convex.core.data.prim.CVMLong.create(r.bytesAfter()),
					K_RECLAIMED, convex.core.data.prim.CVMLong.create(Math.max(0, r.bytesBefore() - r.bytesAfter())),
					K_ELAPSED, convex.core.data.prim.CVMLong.create(r.elapsedMillis()),
					K_COLLECTED_FILE, Strings.create(r.collectedFile()),
					K_RECLAIMED_AT, Strings.create("shutdown"));
				if (restart) {
					// Registered before this Job completes: the handoff follows its
					// successful, persisted result, exactly as venue/restart does
					var plan = engine.requestProcessRestart(null, null, timeout, job);
					out = out.assoc(K_RECLAIMED_AT, Strings.create("restart")).assoc(K_RESTART, Maps.of(
						K_ACCEPTED, CVMBool.TRUE,
						K_JAR, Strings.create(plan.successorJar().toString()),
						K_SHA256, Strings.create(plan.successorSha256()),
						K_FALLBACK, Strings.create(plan.fallbackJar().toString()),
						K_STARTUP_TIMEOUT, convex.core.data.prim.CVMLong.create(plan.startupTimeoutMillis())));
				}
				result.complete(out);
			} catch (Throwable t) {
				result.completeExceptionally(t);
			}
		});
		return result;
	}

	private static ACell status(StoreControl.Status s) {
		AMap<AString, ACell> out = Maps.of(
			K_FILE, Strings.create(s.file()),
			K_BYTES, convex.core.data.prim.CVMLong.create(s.bytes()),
			K_IN_PROGRESS, CVMBool.create(s.inProgress()),
			K_SWEEP_COMPLETE, CVMBool.create(s.sweepComplete()),
			K_COMPLETED, CVMBool.create(s.completed()));
		if (s.collectedFile() != null) {
			out = out.assoc(K_COLLECTED_FILE, Strings.create(s.collectedFile()))
				.assoc(K_COLLECTED_BYTES, convex.core.data.prim.CVMLong.create(s.collectedBytes()));
		}
		return out;
	}

	// ========== process restart ==========

	ACell restart(RequestContext ctx, ACell input) {
		AString jar = RT.ensureString(RT.getIn(input, K_JAR));
		AString sha = RT.ensureString(RT.getIn(input, K_SHA256));
		long timeout = 60_000;
		ACell timeoutCell = RT.getIn(input, K_STARTUP_TIMEOUT);
		if (timeoutCell != null) {
			convex.core.data.prim.CVMLong value = RT.ensureLong(timeoutCell);
			if (value == null) throw new IllegalArgumentException("startupTimeout must be an integer");
			timeout = value.longValue();
		}
		var plan = engine.requestProcessRestart(
			(jar != null) ? jar.toString() : null,
			(sha != null) ? sha.toString() : null,
			timeout, ctx.getJob());
		return Maps.of(
			K_ACCEPTED, CVMBool.TRUE,
			K_JAR, Strings.create(plan.successorJar().toString()),
			K_SHA256, Strings.create(plan.successorSha256()),
			K_FALLBACK, Strings.create(plan.fallbackJar().toString()),
			K_STARTUP_TIMEOUT, convex.core.data.prim.CVMLong.create(plan.startupTimeoutMillis()));
	}

	private static String requireName(ACell input, String field) {
		AString v = RT.ensureString(RT.getIn(input, field));
		if (v == null || v.isEmpty()) throw new IllegalArgumentException(field + " is required");
		return v.toString();
	}
}
