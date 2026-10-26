package covia.adapter.speech;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * sherpa-onnx's native libraries (ONNX Runtime and its JNI library) for the running
 * platform. By default they are installed by the operator or embedder in a directory
 * ({@code nativePath}), which lets them be signed copies; with {@code fetchNatives} the
 * platform's pinned jar of sherpa-onnx's own build is downloaded instead. The vendored
 * Java API must match them: both are sherpa-onnx {@value #VERSION}.
 *
 * <p>Each adapter instance loads its own copy of the files: a JNI library belongs to one
 * class loader, and a module can be unloaded and loaded again in the same process.
 */
final class NativeLibraries {
	static final String VERSION = "1.13.8";
	static final String UNSUPPORTED_PLATFORM = "sherpa-onnx has no native libraries for %s";
	static final String MUSL = "This system's C library is musl (as on Alpine); sherpa-onnx's libraries need glibc";
	static final String NOT_INSTALLED = "Native libraries are not installed: set adapters.speech.local.nativePath to a directory "
		+ "holding sherpa-onnx " + VERSION + "'s libraries, or fetchNatives: true";
	static final String MISSING = "%s is missing from %s";

	/** A platform's pinned jar of sherpa-onnx's build, and where its libraries sit inside. */
	record Pack(String url, long bytes, String sha256) {}

	private static String jitpack(String platform) {
		return "https://jitpack.io/com/github/k2-fsa/sherpa-onnx/sherpa-onnx-native-lib-" + platform + "/v" + VERSION
			+ "/sherpa-onnx-native-lib-" + platform + "-v" + VERSION + ".jar";
	}

	static final Map<String, Pack> PACKS = Map.of(
		"linux-x64", new Pack(jitpack("linux-x64"), 10_515_871L, "30c93b59381113f9c20aedbbf9fc1ad399158f6bc03dddc0f8934a6e28e069ba"),
		"linux-aarch64", new Pack(jitpack("linux-aarch64"), 13_224_859L, "5123d2e48ae1a7ce82ba89ce153906c651bf418a63db2f7dff82265e6d49c104"),
		"osx-x64", new Pack(jitpack("osx-x64"), 10_782_466L, "9190c28951d85efdbd376bae6b6dff12993311ad605945289e8459bf9c886a96"),
		"osx-aarch64", new Pack(jitpack("osx-aarch64"), 9_460_832L, "42e272180c8836127f024f3335d7afcdfb30fe0164b78330d5d449034e34ce34"),
		"win-x64", new Pack(jitpack("win-x64"), 8_277_046L, "33fbdbd5410e9ba9bdda94aa164ec8f7825bb49246420d8ce9bdd88219d97039"));

	/** The libraries, in the order they load: ONNX Runtime first, which the JNI library needs. */
	static List<String> files() {
		return List.of(System.mapLibraryName("onnxruntime"), System.mapLibraryName("sherpa-onnx-jni"));
	}

	/** Loads one library file. {@code System.load} binds it to the caller's class loader, this module's. */
	interface Loader { void load(String path); }

	private final Map<String, Pack> packs;
	private final Loader loader;
	private volatile boolean loaded;

	NativeLibraries() { this(PACKS, System::load); }

	/** For tests: packs served from elsewhere, and a loader that records rather than loads. */
	NativeLibraries(Map<String, Pack> packs, Loader loader) {
		this.packs = packs;
		this.loader = loader;
	}

	/** sherpa-onnx's name for this platform, or null when it has none. */
	static String platform() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
		boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
		if (os.startsWith("linux")) return x64 ? "linux-x64" : arm64 ? "linux-aarch64" : null;
		if (os.startsWith("mac") || os.startsWith("darwin")) return x64 ? "osx-x64" : arm64 ? "osx-aarch64" : null;
		if (os.startsWith("windows")) return x64 ? "win-x64" : null;
		return null;
	}

	/** Why this system cannot run the libraries, or null when it can. */
	String unsupported() {
		String platform = platform();
		if (platform == null || !packs.containsKey(platform)) {
			return UNSUPPORTED_PLATFORM.formatted(System.getProperty("os.name") + " " + System.getProperty("os.arch"));
		}
		if (platform.startsWith("linux")) {
			try (var lib = Files.list(Path.of("/lib"))) {
				if (lib.anyMatch(p -> p.getFileName().toString().startsWith("ld-musl"))) return MUSL;
			} catch (IOException e) {
				// no /lib to inspect: assume glibc
			}
		}
		return null;
	}

	/** Whether this JVM lets this module call native code without a warning (always, before Java 22). */
	static boolean nativeAccessEnabled() {
		try {
			Module module = NativeLibraries.class.getModule();
			return (Boolean) Module.class.getMethod("isNativeAccessEnabled").invoke(module);
		} catch (ReflectiveOperationException e) {
			return true;
		}
	}

	boolean loaded() { return loaded; }

	/**
	 * The directory holding the libraries: {@code nativePath} when given, else the
	 * platform's fetched pack unpacked in {@code cache} when fetching is allowed.
	 */
	Path install(Path nativePath, boolean fetch, Path cache, PinnedDownload download, AtomicLong progress) throws IOException {
		String reason = unsupported();
		if (reason != null) throw new IllegalStateException(reason);
		if (nativePath != null) {
			for (String file : files()) {
				if (!Files.isRegularFile(nativePath.resolve(file))) throw new IllegalStateException(MISSING.formatted(file, nativePath));
			}
			return nativePath;
		}
		if (!fetch) throw new IllegalStateException(NOT_INSTALLED);
		String platform = platform();
		Path dir = cache.resolve("native").resolve(VERSION).resolve(platform);
		if (files().stream().allMatch(f -> Files.isRegularFile(dir.resolve(f)))) return dir;
		Pack pack = packs.get(platform);
		Path jar = cache.resolve("native").resolve(VERSION).resolve(platform + ".jar");
		download.fetch(pack.url(), pack.bytes(), pack.sha256(), jar, progress);
		Files.createDirectories(dir);
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (String file : files()) {
				ZipEntry entry = zip.getEntry("sherpa-onnx/native/" + platform + "/" + file);
				if (entry == null) throw new IllegalStateException(MISSING.formatted(file, pack.url()));
				Path part = dir.resolve(file + ".part");
				try (InputStream in = zip.getInputStream(entry)) { Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING); }
				Files.move(part, dir.resolve(file), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
		}
		return dir;
	}

	/**
	 * Load the libraries in {@code dir} into this class loader, from a copy of their own
	 * under {@code loads}, once. sherpa-onnx's own loader is switched off first.
	 */
	synchronized void load(Path dir, Path loads) throws IOException {
		if (loaded) return;
		com.k2fsa.sherpa.onnx.LibraryLoader.setAutoLoadEnabled(false);
		Path copy = Files.createDirectories(loads.resolve(UUID.randomUUID().toString()));
		for (String file : files()) {
			Path target = copy.resolve(file);
			Files.copy(dir.resolve(file), target, StandardCopyOption.REPLACE_EXISTING);
			loader.load(target.toAbsolutePath().toString());
		}
		loaded = true;
	}

	/**
	 * Remove copies left by earlier loads, once they are an hour old, so another process
	 * sharing the cache is never caught between copying and loading. A copy still in use
	 * on Windows stays; elsewhere a loaded library survives its file's removal.
	 */
	static void sweep(Path loads) {
		if (!Files.isDirectory(loads)) return;
		long cutoff = System.currentTimeMillis() - 3_600_000L;
		try (var copies = Files.list(loads)) {
			copies.forEach(copy -> {
				try {
					if (Files.getLastModifiedTime(copy).toMillis() > cutoff) return;
				} catch (IOException e) {
					return;
				}
				try (var files = Files.list(copy)) {
					for (Path f : files.toList()) Files.deleteIfExists(f);
					Files.deleteIfExists(copy);
				} catch (IOException e) {
					// in use by a running JVM: left for a later sweep
				}
			});
		} catch (IOException e) {
			// nothing to sweep
		}
	}
}
