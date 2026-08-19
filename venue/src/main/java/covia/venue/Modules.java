package covia.venue;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.lang.RT;
import covia.adapter.AAdapter;

/**
 * Loading and unloading of venue modules — adapters packaged as external
 * jars, so heavyweight or optional adapters need not live in covia.jar.
 *
 * <p>A module is a self-contained jar compiled against {@code covia-venue}
 * (provided scope) that declares its adapters via
 * {@code META-INF/services/covia.adapter.AAdapter}. Each module gets its own
 * {@link ModuleClassLoader} (split delegation — see there); its adapters are
 * ordinary adapters: catalog materialisation, {@code /v/info/adapters},
 * capabilities, gates and argument defaults all apply.</p>
 *
 * <p>Boot config:</p>
 * <pre>
 * { "modules": [
 *     "modules/covia-sql-module.jar",
 *     { "path": "modules/other.jar", "sha256": "9f2a...", "config": { } }
 * ] }
 * </pre>
 *
 * <p>Boot loading is fail-fast — an unreadable jar, a bad hash or a module
 * declaring no adapters is a boot error, because explicit config is explicit
 * intent. The optional {@code sha256} pins the jar content; a mismatch
 * refuses to load.</p>
 *
 * <p><b>Runtime lifecycle.</b> With {@code dynamicModules.enabled} set, the
 * venue-only operations {@code v/ops/venue/module/load} and
 * {@code v/ops/venue/module/unload} drive {@link #load} and {@link #unload}
 * on a live venue. In-process code is total compromise, so the policy is
 * operator-owned: by default a runtime load may only name a jar inside the
 * staging directory {@code dynamicModules.dir} (relative name, no {@code ..},
	 * no symlink escape); {@code dynamicModules.anyPath} widens that to any
	 * filesystem path. Unload deregisters the module's adapters (live
	 * introspection retracted, {@link AutoCloseable} adapters closed) and closes
 * the classloader — but JVM class unloading is best-effort (JDBC
 * {@code DriverManager}, JNI, lingering job references can pin a loader), so
 * "unload" means <em>deregistered and released</em>, not <em>guaranteed
 * collected</em>. Runtime changes are not persisted: after a restart the
 * {@code modules} config is authoritative again.</p>
 */
public class Modules {

	private static final Logger log = LoggerFactory.getLogger(Modules.class);

	/**
	 * A module the engine has loaded: identity, provenance, its classloader
	 * and the names of the adapters it registered.
	 *
	 * @param name Module name (jar file name without {@code .jar})
	 * @param jar Resolved jar path
	 * @param sha256 Pinned digest, or null when unpinned
	 * @param config Module-level bootstrap settings passed to
	 *               {@link AAdapter#configureModule}
	 * @param loader The module's classloader
	 * @param adapterNames Names of the adapters registered from this module
	 */
	public record LoadedModule(String name, Path jar, String sha256,
			AMap<AString, ACell> config, ModuleClassLoader loader,
			List<String> adapterNames) {}

	/**
	 * Loads all config-declared modules into the engine. Called during boot
	 * after built-in adapters are registered and BEFORE catalog
	 * materialisation, so module ops materialise with everyone else's.
	 *
	 * @param engine The engine to load modules into
	 */
	public static void loadModules(Engine engine) {
		ACell modulesCell = engine.config().getModules();
		if (modulesCell == null) return;
		AVector<ACell> modules = RT.ensureVector(modulesCell);
		if (modules == null) {
			throw new IllegalStateException(
				"Config 'modules' must be an array of module entries (path string or {path, sha256})");
		}
		for (long i = 0; i < modules.count(); i++) {
			loadModule(engine, modules.get(i));
		}
	}

	/** Loads one boot config entry ({@code path} string or {@code {path, sha256?, config?}}). */
	static LoadedModule loadModule(Engine engine, ACell entry) {
		String path;
		String sha256 = null;
		AMap<AString, ACell> config = Maps.empty();
		if (entry instanceof AString s) {
			path = s.toString();
		} else {
			AString p = RT.ensureString(RT.getIn(entry, "path"));
			if (p == null) throw new IllegalStateException(
				"Module entry must be a path string or {path, sha256?}: " + entry);
			path = p.toString();
			AString h = RT.ensureString(RT.getIn(entry, "sha256"));
			if (h != null) sha256 = h.toString();
			AMap<ACell, ACell> configured = RT.ensureMap(RT.getIn(entry, "config"));
			if (configured != null) {
				@SuppressWarnings({ "rawtypes", "unchecked" })
				AMap<AString, ACell> typed = (AMap) configured;
				config = typed;
			}
		}
		return load(engine, Path.of(path), sha256, config);
	}

	/**
	 * Loads a module jar into the engine: verifies the optional digest,
	 * creates the module classloader, discovers and registers its adapters,
	 * and records the module on the engine. When the venue catalog has
	 * already been published (runtime load), each registered adapter is
	 * materialised incrementally and the module's {@code v/info/modules}
	 * entry is written.
	 *
	 * <p>Callers are responsible for path policy — see {@link #resolveDynamicPath}
	 * for the runtime rules; boot loading trusts the config path as-is.</p>
	 *
	 * @param engine The engine
	 * @param jarPath Path of the module jar
	 * @param sha256 Optional pinned digest (hex), or null
	 * @param config Module-level settings for {@link AAdapter#configureModule}
	 * @return The loaded module record
	 * @throws IllegalStateException on any load failure (nothing is left registered)
	 */
	public static LoadedModule load(Engine engine, Path jarPath, String sha256,
			AMap<AString, ACell> config) {
		File jar = jarPath.toFile();
		if (!jar.isFile()) throw new IllegalStateException(
			"Module jar not found: " + jar.getAbsolutePath());
		if (sha256 != null) verifySha256(jar, sha256);
		if (config == null) config = Maps.empty();

		String name = moduleName(jarPath);
		LoadedModule previous = engine.getModule(name);

		ModuleClassLoader loader;
		try {
			URL url = jar.toURI().toURL();
			loader = new ModuleClassLoader(name, url, Engine.class.getClassLoader());
		} catch (Exception e) {
			throw new IllegalStateException("Failed to open module: " + jarPath, e);
		}

		LoadedModule module = null;
		List<String> registered = new ArrayList<>();
		try {
			// Discover and module-configure first, so the module record (and
			// its v/info/modules entry) exists before its adapters publish —
			// each adapter summary names its owning module.
			List<AAdapter> active = new ArrayList<>();
			int discovered = 0;
			for (AAdapter adapter : ServiceLoader.load(AAdapter.class, loader)) {
				discovered++;
				if (!adapter.configureModule(config, engine.config().isStrictConfig())) {
					log.info("Adapter '{}' from module {} is inactive",
						adapter.getName(), jar.getName());
					continue;
				}
				active.add(adapter);
			}
			if (discovered == 0) throw new IllegalStateException(
				"Module declares no adapters (missing META-INF/services/covia.adapter.AAdapter?): " + jarPath);
			List<String> names = new ArrayList<>();
			for (AAdapter adapter : active) names.add(adapter.getName());
			module = new LoadedModule(name, jarPath, sha256, config, loader, List.copyOf(names));
			engine.addModule(module);
			for (AAdapter adapter : active) {
				engine.registerAdapter(adapter);
				registered.add(adapter.getName());
				log.info("Loaded adapter '{}' from module {}", adapter.getName(), jar.getName());
			}
			if (previous != null) {
				for (String oldName : previous.adapterNames()) {
					if (names.contains(oldName)) continue;
					AAdapter old = engine.getRegisteredAdapter(oldName);
					if (old != null && old.getClass().getClassLoader() == previous.loader()) {
						engine.removeAdapter(oldName);
					}
				}
				closeQuietly(previous.loader(), null);
			}
			return module;
		} catch (RuntimeException | Error e) {
			// Remove live registrations from the failed load. Catalog metadata is
			// durable venue state and deliberately remains.
			for (String adapterName : registered) {
				try {
					engine.removeAdapter(adapterName);
				} catch (Exception suppressed) {
					e.addSuppressed(suppressed);
				}
			}
			if (module != null) {
				try {
					engine.dropModule(module);
				} catch (Exception suppressed) {
					e.addSuppressed(suppressed);
				}
			}
			if (previous != null && module != null) {
				for (String oldName : previous.adapterNames()) {
					AAdapter old = engine.getRegisteredAdapter(oldName);
					if (old != null && old.getClass().getClassLoader() == previous.loader()) {
						try {
							engine.removeAdapter(oldName);
						} catch (Exception suppressed) {
							e.addSuppressed(suppressed);
						}
					}
				}
				closeQuietly(previous.loader(), e);
			}
			closeQuietly(loader, e);
			if (e instanceof IllegalStateException ise) throw ise;
			throw new IllegalStateException("Failed to load module: " + jarPath, e);
		}
	}

	/**
	 * Unloads a module: removes every adapter it registered (live introspection
	 * retracted, durable catalog metadata retained, {@link AutoCloseable} adapters closed),
	 * retracts its {@code v/info/modules} entry, closes its classloader and
	 * forgets it. In-flight jobs on its adapters fail at their next point of
	 * use; class unloading itself is best-effort (see class doc).
	 *
	 * @param engine The engine
	 * @param name Module name
	 * @return The unloaded module record
	 * @throws IllegalArgumentException if no such module is loaded
	 */
	public static LoadedModule unload(Engine engine, String name) {
		LoadedModule module = engine.getModule(name);
		if (module == null) throw new IllegalArgumentException("Module not loaded: " + name);
		for (String adapterName : module.adapterNames()) {
			if (engine.moduleOf(adapterName) == module) engine.removeAdapter(adapterName);
		}
		engine.dropModule(module);
		try {
			module.loader().close();
		} catch (IOException e) {
			log.warn("Failed to close classloader for module {}", name, e);
		}
		log.info("Unloaded module {} ({} adapters)", name, module.adapterNames().size());
		return module;
	}

	/**
	 * Resolves a runtime module reference against the operator's dynamic
	 * module policy:
	 * <ul>
	 *   <li>{@code dynamicModules.enabled} must be true, else refused.</li>
	 *   <li>By default the reference must be a relative name inside
	 *       {@code dynamicModules.dir} — no absolute paths, no {@code ..}
	 *       segments, and the real (symlink-resolved) path must stay inside
	 *       the real staging directory.</li>
	 *   <li>With {@code dynamicModules.anyPath} the reference may be any
	 *       path; a relative one still resolves against the staging directory.</li>
	 * </ul>
	 *
	 * @param config Venue config
	 * @param ref Caller-supplied module reference (file name or path)
	 * @return The resolved jar path
	 * @throws IllegalStateException when dynamic loading is disabled
	 * @throws IllegalArgumentException when the reference violates the policy
	 */
	public static Path resolveDynamicPath(Config config, String ref) {
		if (!config.isDynamicModulesEnabled()) {
			throw new IllegalStateException(
				"Dynamic module loading is disabled on this venue (set dynamicModules.enabled)");
		}
		if (ref == null || ref.isBlank()) throw new IllegalArgumentException("module is required");
		Path dir = Path.of(config.getDynamicModulesDir());
		Path requested = Path.of(ref);
		if (config.isDynamicModulesAnyPath()) {
			return requested.isAbsolute() ? requested.normalize() : dir.resolve(requested).normalize();
		}
		if (requested.isAbsolute()) {
			throw new IllegalArgumentException("module must be a name inside the staging directory "
				+ dir + " (absolute paths need dynamicModules.anyPath)");
		}
		for (Path segment : requested) {
			if ("..".equals(segment.toString())) {
				throw new IllegalArgumentException("module must not contain '..' segments");
			}
		}
		Path resolved = dir.resolve(requested).normalize();
		try {
			Path realDir = dir.toRealPath();
			Path realJar = resolved.toRealPath();
			if (!realJar.startsWith(realDir)) {
				throw new IllegalArgumentException("module resolves outside the staging directory " + dir);
			}
			return realJar;
		} catch (IOException e) {
			throw new IllegalArgumentException("Module jar not found in staging directory "
				+ dir + ": " + ref);
		}
	}

	/** Module name derived from the jar file name ({@code foo-module.jar} → {@code foo-module}). */
	public static String moduleName(Path jarPath) {
		return jarPath.getFileName().toString().replaceAll("\\.jar$", "");
	}

	/** Content-addressed integrity check: the jar's SHA-256 must match the
	 *  config-pinned value, else the module refuses to load. */
	static void verifySha256(File jar, String expected) {
		String actual;
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			actual = Blob.wrap(md.digest(Files.readAllBytes(jar.toPath()))).toHexString();
		} catch (Exception e) {
			throw new IllegalStateException("Cannot compute module hash: " + jar, e);
		}
		if (!actual.equalsIgnoreCase(expected)) {
			throw new IllegalStateException("Module integrity check FAILED for " + jar
				+ ": expected sha256 " + expected + ", got " + actual);
		}
	}

	private static void closeQuietly(ModuleClassLoader loader, Throwable primary) {
		try {
			loader.close();
		} catch (IOException e) {
			if (primary != null) primary.addSuppressed(e);
			else log.warn("Failed to close replaced module classloader", e);
		}
	}
}
