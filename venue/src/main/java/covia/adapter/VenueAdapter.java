package covia.adapter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
import covia.venue.Modules;
import covia.venue.RequestContext;

/**
 * Venue administration: adapter and module lifecycle on a live venue.
 *
 * <p>Every operation here is venue-owned — authorised only for direct
 * execution as the venue identity or a venue-rooted delegation over
 * {@code <venue DID>/adapters} with {@code adapter/manage}
 * ({@link covia.venue.Engine#requireVenueAuthority}). A null capability scope
 * is deliberately not enough: an ordinary authenticated user cannot switch
 * adapters off or load code.</p>
 *
 * <ul>
 *   <li>{@code venue/adapters} — full registry view: active and disabled
 *       adapters (kernel flag, owning module, operations) plus loaded modules.</li>
 *   <li>{@code venue/adapter/enable|disable} — retract or restore a non-kernel
 *       adapter's catalog, introspection and dispatch.</li>
 *   <li>{@code venue/adapter/configure} — apply a new effective configuration
 *       ({@link covia.venue.Engine#configureAdapter}).</li>
 *   <li>{@code venue/module/load|unload} — runtime module lifecycle, subject
 *       to the operator's {@code dynamicModules} policy ({@link Modules}).</li>
 * </ul>
 *
 * <p>Changes are not persisted: after a restart the venue config is
 * authoritative again. This is the runtime lever; a persisted live config is
 * a later step.</p>
 */
public class VenueAdapter extends AAdapter {

	static final String RESOURCE = "adapters";

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

	@Override
	public String getName() {
		return "venue";
	}

	@Override
	public String getDescription() {
		return "Venue administration: enable, disable and reconfigure adapters, and load or "
			+ "unload adapter modules on the running venue. Venue-owned operations.";
	}

	@Override
	protected void installAssets() {
		installAsset("venue/adapters", "/adapters/venue/adapters.json");
		installAsset("venue/adapter/enable", "/adapters/venue/adapterEnable.json");
		installAsset("venue/adapter/disable", "/adapters/venue/adapterDisable.json");
		installAsset("venue/adapter/configure", "/adapters/venue/adapterConfigure.json");
		installAsset("venue/module/load", "/adapters/venue/moduleLoad.json");
		installAsset("venue/module/unload", "/adapters/venue/moduleUnload.json");
		installSkill("adapters/adapters", "/skills/adapters.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx,
			AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		try {
			engine.requireVenueAuthority(ctx, RESOURCE, Abilities.ADAPTER_MANAGE);
			return CompletableFuture.completedFuture(switch (getSubOperation(meta)) {
				case "adapters" -> adapters();
				case "adapter-enable" -> adapterEnable(input);
				case "adapter-disable" -> adapterDisable(input);
				case "adapter-configure" -> adapterConfigure(input);
				case "module-load" -> moduleLoad(input);
				case "module-unload" -> moduleUnload(input);
				default -> throw new IllegalArgumentException(
					"Unknown venue operation: " + getSubOperation(meta));
			});
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
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

	private static String requireName(ACell input, String field) {
		AString v = RT.ensureString(RT.getIn(input, field));
		if (v == null || v.isEmpty()) throw new IllegalArgumentException(field + " is required");
		return v.toString();
	}
}
