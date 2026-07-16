package covia.venue;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Classloader for a venue module jar, with split delegation:
 *
 * <ul>
 *   <li><b>Parent-first</b> for the covia/convex API surface, the JDK and
 *       logging — cell-type identity ({@code instanceof ACell}) and the
 *       adapter SPI must be shared with the venue, and module logs must flow
 *       through the venue's logback. Shared-prefix classes the venue does
 *       not carry (e.g. {@code convex.db.*} inside a module) fall back to
 *       the module jar.</li>
 *   <li><b>Child-first</b> for everything else — a module's dependencies
 *       are isolated from the venue's and from other modules', so two
 *       modules may carry conflicting versions of the same library.</li>
 * </ul>
 *
 * <p>Resources are child-first too: a module's own asset JSONs win over
 * anything of the same name on the venue classpath.</p>
 */
public class ModuleClassLoader extends URLClassLoader {

	static {
		registerAsParallelCapable();
	}

	/** Package prefixes that must resolve from the venue's own classloader. */
	private static final String[] SHARED_PREFIXES = {
		"java.", "javax.", "jdk.", "sun.", "com.sun.",
		"covia.", "convex.",
		"org.slf4j.", "ch.qos.logback."
	};

	private final String moduleName;

	public ModuleClassLoader(String moduleName, URL jarUrl, ClassLoader parent) {
		super("module:" + moduleName, new URL[] { jarUrl }, parent);
		this.moduleName = moduleName;
	}

	private static boolean isShared(String className) {
		for (String p : SHARED_PREFIXES) {
			if (className.startsWith(p)) return true;
		}
		return false;
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		synchronized (getClassLoadingLock(name)) {
			Class<?> c = findLoadedClass(name);
			if (c == null) {
				if (isShared(name)) {
					try {
						c = getParent().loadClass(name);
					} catch (ClassNotFoundException e) {
						c = findClass(name);
					}
				} else {
					try {
						c = findClass(name);
					} catch (ClassNotFoundException e) {
						c = getParent().loadClass(name);
					}
				}
			}
			if (resolve) resolveClass(c);
			return c;
		}
	}

	@Override
	public URL getResource(String name) {
		URL url = findResource(name);
		return (url != null) ? url : super.getResource(name);
	}

	public String getModuleName() {
		return moduleName;
	}
}
