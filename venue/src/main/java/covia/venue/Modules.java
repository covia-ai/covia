package covia.venue;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ServiceLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.lang.RT;
import covia.adapter.AAdapter;

/**
 * Boot-time loading of venue modules — adapters packaged as external jars,
 * so heavyweight or optional adapters need not live in covia.jar.
 *
 * <p>A module is a self-contained jar compiled against {@code covia-venue}
 * (provided scope) that declares its adapters via
 * {@code META-INF/services/covia.adapter.AAdapter}. Each module gets its own
 * {@link ModuleClassLoader} (split delegation — see there); its adapters are
 * ordinary adapters: catalog materialisation, {@code /v/info/adapters},
 * capabilities, gates and argument defaults all apply.</p>
 *
 * <p>Config:</p>
 * <pre>
 * { "modules": [
 *     "modules/covia-sql-module.jar",
 *     { "path": "modules/other.jar", "sha256": "9f2a..." }
 * ] }
 * </pre>
 *
 * <p>Loading a module is an OPERATOR act — config plus filesystem. There is
 * deliberately no runtime module-load operation: in-process code is total
 * compromise. The optional {@code sha256} pins the jar content; a mismatch
 * refuses to load. Loading is fail-fast — an unreadable jar, a bad hash or a
 * module declaring no adapters is a boot error, because explicit config is
 * explicit intent. There is no hot-unload (classloader unloading is
 * unreliable on the JVM); removing a module means removing it from config
 * and restarting.</p>
 */
public class Modules {

	private static final Logger log = LoggerFactory.getLogger(Modules.class);

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

	static void loadModule(Engine engine, ACell entry) {
		String path;
		String sha256 = null;
		if (entry instanceof AString s) {
			path = s.toString();
		} else {
			AString p = RT.ensureString(RT.getIn(entry, "path"));
			if (p == null) throw new IllegalStateException(
				"Module entry must be a path string or {path, sha256?}: " + entry);
			path = p.toString();
			AString h = RT.ensureString(RT.getIn(entry, "sha256"));
			if (h != null) sha256 = h.toString();
		}

		File jar = new File(path);
		if (!jar.isFile()) throw new IllegalStateException(
			"Module jar not found: " + jar.getAbsolutePath());
		if (sha256 != null) verifySha256(jar, sha256);

		String name = jar.getName().replaceAll("\\.jar$", "");
		try {
			URL url = jar.toURI().toURL();
			ModuleClassLoader loader = new ModuleClassLoader(name, url, Engine.class.getClassLoader());
			engine.moduleLoaders.add(loader);
			int count = 0;
			for (AAdapter adapter : ServiceLoader.load(AAdapter.class, loader)) {
				engine.registerAdapter(adapter);
				count++;
				log.info("Loaded adapter '{}' from module {}", adapter.getName(), jar.getName());
			}
			if (count == 0) throw new IllegalStateException(
				"Module declares no adapters (missing META-INF/services/covia.adapter.AAdapter?): " + path);
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("Failed to load module: " + path, e);
		}
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
}
