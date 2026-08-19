package covia.adapter;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.auth.ucan.Capability;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.utils.MimeUtils;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Filesystem adapter — exposes the venue host's local filesystem to operations.
 *
 * <p>Access is restricted to a set of named roots configured by the venue
 * operator. Each root maps a name to a host path, temp directory, or DLFS view;
 * agents reference files by {@code root} + relative {@code path}. Without
 * configured roots the adapter creates an ephemeral temp root. A DLFS root may
 * select a relative {@code subpath}; this becomes the root's logical boundary,
 * so callers never include the physical subtree in operation paths. Access is
 * still authorised against the canonical DID-scoped DLFS resource.
 *
 * <h3>Configuration</h3>
 * <pre>
 * {
 *   "file": {
 *     "roots": {
 *       "workspace": "/srv/agent-workspace",
 *       "data":      { "path": "/srv/data", "readOnly": true },
 *       "documents": { "dlfs": "vault", "subpath": "agent-output" }
 *     }
 *   }
 * }
 * </pre>
 *
 * <h3>Operations</h3>
 * <ul>
 *   <li>{@code file:roots}  — list configured roots</li>
 *   <li>{@code file:list}   — list directory entries</li>
 *   <li>{@code file:read}   — read a file (text/bytes/json)</li>
 *   <li>{@code file:create} — create a new file from a content descriptor</li>
 *   <li>{@code file:write}  — write a file (text/value/bytes)</li>
 *   <li>{@code file:append} — append text to a file</li>
 *   <li>{@code file:move}   — rename or relocate within one root</li>
 *   <li>{@code file:copy}   — copy within one root</li>
 *   <li>{@code file:delete} — delete a file or empty directory</li>
 *   <li>{@code file:mkdir}  — create a directory</li>
 *   <li>{@code file:stat}   — file metadata</li>
 * </ul>
 *
 * <h3>Path safety</h3>
 * <p>User-provided paths are resolved against the root, normalised, and
 * verified to be inside the root's canonical path. Symlinks that escape the
 * root are rejected when the target exists; for missing targets (e.g. write,
 * mkdir) the parent's real path is checked.
 */
public class FileAdapter extends AAdapter implements covia.venue.storage.ContentProvider {

	private static final Logger log = LoggerFactory.getLogger(FileAdapter.class);

	private static final String ASSETS_PATH = "/adapters/file/";

	private static final AString FIELD_ROOT = Strings.intern("root");
	private static final AString FIELD_TO_ROOT = Strings.intern("toRoot");
	private static final AString FIELD_PATH = Strings.intern("path");
	private static final AString FIELD_FROM = Strings.intern("from");
	private static final AString FIELD_TO = Strings.intern("to");
	private static final AString FIELD_MODE = Strings.intern("mode");
	private static final AString FIELD_READ_ONLY = Strings.intern("readOnly");
	private static final AString FIELD_TEMP = Strings.intern("temp");
	private static final AString FIELD_PREFIX = Strings.intern("prefix");
	private static final AString FIELD_DLFS = Strings.intern("dlfs");
	private static final AString FIELD_SUBPATH = Strings.intern("subpath");
	private static final AString FIELD_DESCRIPTION = Strings.intern("description");

	/**
	 * A configured root. Subclasses dispatch path resolution to the appropriate
	 * backend — a host-filesystem directory, an ephemeral temp dir, or a DLFS
	 * drive. The base path may be per-request (DLFS roots resolve against the
	 * caller's signed drive view), so callers fetch it via {@link #baseFor}
	 * rather than reading a cached field.
	 */
	private static abstract class Root {
		final boolean readOnly;
		/** Operator-supplied human-readable description as an AString, or null if absent.
		 *  Kept as AString since it originates from the config map and flows
		 *  back out via {@code Maps.of} unchanged. */
		final AString description;
		Root(boolean readOnly, AString description) {
			this.readOnly = readOnly;
			this.description = description;
		}
		abstract Path baseFor(RequestContext ctx) throws IOException;
		abstract String displayPath();
		abstract String kind();
	}

	private static final class HostRoot extends Root {
		final Path canonical;
		final boolean temp;
		HostRoot(Path canonical, boolean readOnly, boolean temp, AString description) {
			super(readOnly, description);
			this.canonical = canonical;
			this.temp = temp;
		}
		@Override Path baseFor(RequestContext ctx) { return canonical; }
		@Override String displayPath() { return canonical.toString(); }
		@Override String kind() { return temp ? "temp" : "host"; }
	}

	private static final class DLFSRoot extends Root {
		final String driveName;
		final String subpath;
		final Engine engine;
		DLFSRoot(String driveName, String subpath, boolean readOnly, Engine engine,
				AString description) {
			super(readOnly, description);
			this.driveName = driveName;
			this.subpath = subpath;
			this.engine = engine;
		}
		@Override Path baseFor(RequestContext ctx) throws IOException {
			if (ctx == null || ctx.getCallerDID() == null) {
				throw new IllegalArgumentException(
					"DLFS-backed root '" + driveName + "' requires authenticated caller");
			}
			// Lazy adapter lookup — FileAdapter may register before DLFSAdapter.
			AAdapter raw = engine.getAdapter("dlfs");
			if (!(raw instanceof DLFSAdapter dlfs)) {
				throw new IllegalStateException(
					"DLFS-backed root '" + driveName + "' requires the DLFS adapter to be registered");
			}
			// Drives auto-create on first connect; cheap cursor view per call.
			Path driveRoot = dlfs.getDrive(ctx, driveName)
				.getRootDirectories().iterator().next();
			if (subpath == null) return driveRoot;

			// Resolve against the provider itself, then re-check confinement before
			// touching state. loadRoot performs the same lexical validation early,
			// but this keeps the security boundary local to path materialisation.
			Path relative = driveRoot.getFileSystem().getPath(subpath);
			if (relative.isAbsolute() || relative.getRoot() != null) {
				throw new IllegalArgumentException(
					"DLFS root subpath must be relative: " + subpath);
			}
			Path base = driveRoot.resolve(relative).normalize();
			if (!base.startsWith(driveRoot)) {
				throw new IllegalArgumentException(
					"DLFS root subpath escapes drive: " + subpath);
			}
			// The drive itself is lazy-created; its configured subtree follows the
			// same rule. This setup is independent of the root's API-level readOnly
			// policy, which still rejects caller-requested mutations.
			Files.createDirectories(base);
			if (!Files.isDirectory(base)) {
				throw new IOException("DLFS root subpath is not a directory: " + subpath);
			}
			return base;
		}
		@Override String displayPath() {
			return "dlfs:" + driveName + (subpath == null ? "" : "/" + subpath);
		}
		@Override String kind() { return "dlfs"; }
	}

	/** Resolved root configuration, populated on install. */
	private final Map<String, Root> roots = new LinkedHashMap<>();

	@Override
	public String getName() {
		return "file";
	}

	@Override
	public String getDescription() {
		return "Filesystem access for agents over a uniform tool surface. Reads, writes, lists, and manages "
			+ "files within operator-configured named roots. Each root can be backed by a host directory, "
			+ "an ephemeral temp dir (auto-cleaned on JVM exit), or a DLFS drive (lattice-backed, per-user). "
			+ "Agents address files by root name + relative path regardless of backend. With no roots "
			+ "configured the venue defaults to a single ephemeral 'tmp' root.";
	}

	@Override
	public void install(Engine engine) {
		super.install(engine);
		loadRoots();
	}

	@Override
	protected void installAssets() {
		// The adapter's own skill: v/skills/files lives and dies with this adapter.
		installSkill("files", "/skills/files.json");
		installAsset("file/roots",  ASSETS_PATH + "roots.json");
		installAsset("file/list",   ASSETS_PATH + "list.json");
		installAsset("file/tree",   ASSETS_PATH + "tree.json");
		installAsset("file/read",   ASSETS_PATH + "read.json");
		installAsset("file/create", ASSETS_PATH + "create.json");
		installAsset("file/write",  ASSETS_PATH + "write.json");
		installAsset("file/append", ASSETS_PATH + "append.json");
		installAsset("file/move",   ASSETS_PATH + "move.json");
		installAsset("file/copy",   ASSETS_PATH + "copy.json");
		installAsset("file/delete", ASSETS_PATH + "delete.json");
		installAsset("file/mkdir",  ASSETS_PATH + "mkdir.json");
		installAsset("file/stat",   ASSETS_PATH + "stat.json");
	}

	// ==================== Configuration ====================

	private void loadRoots() {
		roots.clear();
		AMap<AString, ACell> fileCfg = engine.config().getFileConfig();
		AMap<AString, ACell> rootsCfg = (fileCfg != null) ? RT.ensureMap(fileCfg.get(Config.ROOTS)) : null;

		if (rootsCfg != null) {
			for (var entry : rootsCfg.entrySet()) {
				loadRoot(entry.getKey().toString(), entry.getValue());
			}
		}

		// Default: if the operator configured nothing usable, give agents a
		// fresh ephemeral 'tmp' root. Auto-cleaned on JVM exit. Operators can
		// suppress this by configuring at least one explicit root.
		if (roots.isEmpty()) {
			try {
				addTempRoot("tmp", null, null, false, null);
				log.info("FileAdapter: no roots configured — defaulted to ephemeral 'tmp' root");
			} catch (IOException e) {
				log.warn("FileAdapter: could not create default temp root: {}", e.getMessage());
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void loadRoot(String name, ACell raw) {
		try {
			if (raw instanceof AString s) {
				addPathRoot(name, s.toString(), false, null);
			} else if (raw instanceof AMap<?,?> m) {
				AMap<AString, ACell> rm = (AMap<AString, ACell>) m;
				boolean isTemp = RT.bool(rm.get(FIELD_TEMP));
				AString dlfsCell = RT.ensureString(rm.get(FIELD_DLFS));
				ACell subpathRaw = rm.get(FIELD_SUBPATH);
				AString subpathCell = RT.ensureString(subpathRaw);
				boolean readOnly = RT.bool(rm.get(Config.READ_ONLY));
				AString description = RT.ensureString(rm.get(FIELD_DESCRIPTION));
				if (subpathRaw != null && subpathCell == null) {
					throw new IllegalArgumentException("'subpath' must be a string");
				}
				if (subpathCell != null && dlfsCell == null) {
					throw new IllegalArgumentException(
						"'subpath' is only valid for a DLFS-backed root");
				}
				int variants = (isTemp ? 1 : 0) + (dlfsCell != null ? 1 : 0)
					+ (rm.containsKey(FIELD_PATH) && !isTemp ? 1 : 0);
				if (variants > 1) {
					log.warn("FileAdapter: root '{}' specifies more than one of 'path'/'temp'/'dlfs' — skipped", name);
					return;
				}
				if (isTemp) {
					AString prefixCell = RT.ensureString(rm.get(FIELD_PREFIX));
					AString parentCell = RT.ensureString(rm.get(FIELD_PATH));
					Path parent = (parentCell != null) ? Path.of(parentCell.toString()) : null;
					String prefix = (prefixCell != null) ? prefixCell.toString() : null;
					addTempRoot(name, prefix, parent, readOnly, description);
				} else if (dlfsCell != null) {
					String subpath = (subpathCell == null) ? null
						: normaliseDLFSSubpath(subpathCell.toString());
					addDLFSRoot(name, dlfsCell.toString(), subpath, readOnly, description);
				} else {
					AString p = RT.ensureString(rm.get(FIELD_PATH));
					if (p == null) {
						log.warn("FileAdapter: root '{}' missing 'path', 'temp', or 'dlfs' — skipped", name);
						return;
					}
					addPathRoot(name, p.toString(), readOnly, description);
				}
			} else {
				log.warn("FileAdapter: root '{}' must be string or map — skipped", name);
			}
		} catch (IOException | IllegalArgumentException e) {
			log.warn("FileAdapter: root '{}' failed: {}", name, e.getMessage());
		}
	}

	/** Normalises a portable provider-relative DLFS subtree path. */
	private static String normaliseDLFSSubpath(String configured) {
		if (configured == null || configured.isBlank()) {
			throw new IllegalArgumentException("'subpath' must not be empty");
		}
		String portable = configured.replace('\\', '/');
		if (portable.startsWith("/") || portable.matches("^[A-Za-z]:.*")) {
			throw new IllegalArgumentException("'subpath' must be relative: " + configured);
		}
		java.util.ArrayList<String> segments = new java.util.ArrayList<>();
		for (String segment : portable.split("/")) {
			if (segment.isEmpty() || ".".equals(segment)) continue;
			if ("..".equals(segment)) {
				throw new IllegalArgumentException(
					"'subpath' must not contain '..': " + configured);
			}
			segments.add(segment);
		}
		if (segments.isEmpty()) {
			throw new IllegalArgumentException("'subpath' must name a directory");
		}
		return String.join("/", segments);
	}

	private void addPathRoot(String name, String pathStr, boolean readOnly, AString description) throws IOException {
		Path canonical = Path.of(pathStr).toAbsolutePath().normalize();
		if (!Files.isDirectory(canonical)) {
			log.warn("FileAdapter: root '{}' path '{}' is not an existing directory — skipped",
				name, canonical);
			return;
		}
		// Real path so symlink-rooted configs are normalised once.
		Path real = canonical.toRealPath();
		roots.put(name, new HostRoot(real, readOnly, false, description));
		log.info("FileAdapter: root '{}' -> {}{}", name, real, readOnly ? " (read-only)" : "");
	}

	private void addTempRoot(String name, String prefix, Path parent, boolean readOnly, AString description) throws IOException {
		String effPrefix = (prefix != null) ? prefix : ("covia-" + name + "-");
		Path tempDir = (parent != null)
			? Files.createTempDirectory(parent, effPrefix)
			: Files.createTempDirectory(effPrefix);
		Path real = tempDir.toRealPath();

		// Recursive cleanup on JVM exit. File.deleteOnExit() can't handle
		// non-empty directories, so we register our own hook.
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				if (Files.exists(real)) deleteRecursive(real);
			} catch (IOException e) {
				// Logger may already be shut down — fall back to stderr.
				System.err.println("FileAdapter: failed to clean up temp root '" + name
					+ "' at " + real + ": " + e.getMessage());
			}
		}, "FileAdapter-tempCleanup-" + name));

		roots.put(name, new HostRoot(real, readOnly, true, description));
		log.info("FileAdapter: temp root '{}' -> {} (auto-cleanup on JVM exit){}",
			name, real, readOnly ? " (read-only)" : "");
	}

	private void addDLFSRoot(String name, String driveName, String subpath,
			boolean readOnly, AString description) {
		// Lookup is deferred — DLFSAdapter may register after FileAdapter.
		roots.put(name, new DLFSRoot(driveName, subpath, readOnly, engine, description));
		log.info("FileAdapter: root '{}' -> dlfs:{}{}{}", name, driveName,
			subpath == null ? "" : "/" + subpath,
			readOnly ? " (read-only)" : "");
	}

	// ==================== Path resolution ====================

	/**
	 * Resolves a user-supplied (root, path) pair to an absolute path inside the
	 * configured root. Rejects paths with any root component (absolute, drive-,
	 * or UNC-rooted) and rejects resolutions that escape the root either
	 * lexically (after {@code normalize()}) or via symbolic links.
	 *
	 * <p>The path is parsed against the root's own {@link java.nio.file.FileSystem}
	 * so that DLFS-, host-, and any future provider-backed roots all use that
	 * provider's separator conventions. Symlink walks only run on default-FS
	 * roots — DLFS and other lattice-backed providers don't have symlinks.
	 */
	private Path resolvePath(RequestContext ctx, String rootName, String userPath, boolean mustExist) throws IOException {
		if (rootName == null || rootName.isEmpty()) {
			throw new IllegalArgumentException("'root' is required");
		}
		Root root = roots.get(rootName);
		if (root == null) {
			throw new IllegalArgumentException("Unknown root '" + rootName
				+ "'. Configured: " + roots.keySet());
		}

		return resolvePath(rootName, userPath, mustExist, root.baseFor(ctx));
	}

	/**
	 * Resolves against an already-open root view. Multi-path operations use this
	 * overload so both endpoints belong to the exact same filesystem instance —
	 * important for provider-native DLFS move/copy once those operations land.
	 */
	private Path resolvePath(String rootName, String userPath, boolean mustExist,
			Path base) throws IOException {
		return FileOperations.resolve(base, userPath, "root '" + rootName + "'", mustExist);
	}

	/** A resolved and authorised operation target. */
	private record FileTarget(Path base, Path path, String resource,
			boolean readOnly, String binaryUrl, String rootName) {}

	private DLFSAdapter requireDLFSAdapter() {
		AAdapter raw = engine.getAdapter("dlfs");
		if (!(raw instanceof DLFSAdapter dlfs)) {
			throw new IllegalStateException("DLFS adapter is not available");
		}
		return dlfs;
	}

	/**
	 * Resolve and authorise either a configured root path or a canonical DLFS
	 * reference. DLFS-backed aliases retain their logical jail/read-only policy,
	 * but capabilities always name the underlying DID-scoped DLFS resource.
	 */
	private FileTarget resolveTarget(RequestContext ctx, String rootName, String userPath,
			AString ability, boolean mustExist) throws IOException {
		if (rootName == null || rootName.isEmpty()) {
			DLFSAdapter.AuthorisedPath target = requireDLFSAdapter()
				.resolveAuthorisedPath(ctx, userPath, ability);
			if (target == null) {
				throw new IllegalArgumentException(
					"'root' is required unless 'path' is a dlfs/<drive>/... or DID-scoped DLFS reference");
			}
			if (mustExist && !Files.exists(target.path())) throw new NoSuchFileException(userPath);
			return new FileTarget(target.root(), target.path(), target.resource(),
				false, target.binaryUrl(), null);
		}

		Root root = roots.get(rootName);
		if (root == null) {
			// Authorise before reporting root configuration. Besides keeping the
			// point-of-action contract uniform, this avoids exposing configured root
			// names to callers whose scope cannot address the requested file resource.
			String requested = schemeResource("file", Strings.create(rootName),
				userPath != null ? Strings.create(userPath) : null);
			engine.requireAuthority(ctx, Strings.create(requested), ability);
			throw new IllegalArgumentException("Unknown root '" + rootName + "'");
		}
		if (root instanceof DLFSRoot dr) {
			String relative = joinDLFSPath(dr.subpath, userPath);
			String ref = "dlfs/" + dr.driveName + (relative.isEmpty() ? "" : "/" + relative);
			DLFSAdapter.AuthorisedPath target = requireDLFSAdapter()
				.resolveAuthorisedPath(ctx, ref, ability);
			Path logicalBase = FileOperations.resolve(target.root(), dr.subpath,
				"DLFS root '" + rootName + "'", false);
			if (!Files.exists(logicalBase) && Capability.CRUD_WRITE.equals(ability)) {
				Files.createDirectories(logicalBase);
			}
			if (!target.path().startsWith(logicalBase)) {
				throw new IllegalArgumentException("Path escapes root '" + rootName + "': " + userPath);
			}
			if (mustExist && !Files.exists(target.path())) throw new NoSuchFileException(userPath);
			return new FileTarget(logicalBase, target.path(), target.resource(),
				root.readOnly, target.binaryUrl(), rootName);
		}

		Path base = root.baseFor(ctx);
		// Resolve the lexical/jail target without observing target existence, then
		// authorise the canonical resource. Existence errors must not precede the
		// capability gate (or leak whether an inaccessible path exists).
		Path path = resolvePath(rootName, userPath, false, base);
		String resource = fileResource(rootName, base, path);
		engine.requireAuthority(ctx, Strings.create(resource), ability);
		if (mustExist && !Files.exists(path)) throw new NoSuchFileException(userPath);
		return new FileTarget(base, path, resource, root.readOnly, null, rootName);
	}

	private static String joinDLFSPath(String prefix, String path) {
		String p = path == null ? "" : path;
		while (p.startsWith("/")) p = p.substring(1);
		if (prefix == null || prefix.isEmpty()) return p;
		return p.isEmpty() ? prefix : prefix + "/" + p;
	}

	private Root requireRoot(String name) {
		Root r = roots.get(name);
		if (r == null) {
			throw new IllegalArgumentException("Unknown root '" + name + "'");
		}
		return r;
	}

	// ==================== Archive read-through (jdk.zipfs) ====================

	/** A resolved target: a plain filesystem path, or an entry inside a mounted
	 *  archive. {@link #close} releases the archive filesystem and its per-archive
	 *  lock when present; a plain path closes to nothing. */
	private record Resolved(FileTarget target, Path entryPath, FileSystem archiveFs,
			java.util.concurrent.locks.ReentrantLock lock) implements java.io.Closeable {
		Path path() { return entryPath != null ? entryPath : target.path(); }
		@Override public void close() throws IOException {
			try { if (archiveFs != null) archiveFs.close(); }
			finally { if (lock != null) lock.unlock(); }
		}
	}

	/** Per-archive mount lock — {@code jdk.zipfs} keys a FileSystem by the archive
	 *  path and rejects a second concurrent mount, so reads of the SAME archive
	 *  serialise while reads of different archives run in parallel. */
	private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>
		archiveLocks = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Resolves a {@code (root, path)} pair, transparently descending into a
	 * zip/jar when the path carries a {@code !} archive-entry separator
	 * ({@code app.zip!/dir/file} — the standard jar-URL convention). The archive
	 * itself is resolved and jailed like any file and <b>must already exist</b>;
	 * it is mounted <b>existing-only</b> via {@code jdk.zipfs} (never created — a
	 * read of a missing archive fails, it does not fabricate one). The entry is
	 * jailed within the archive root. With no {@code !} this is a plain
	 * {@link #resolvePath} wrapped in a no-op {@link Resolved}.
	 *
	 * <p>Read-only: writing into an archive is not a {@code file:} side effect
	 * (see {@link #rejectArchiveEntry}); use the {@code archive} adapter.</p>
	 */
	private Resolved resolveEntry(RequestContext ctx, String rootName, String userPath,
			AString ability, boolean mustExist) throws IOException {
		int bang = archiveBang(userPath);
		if (bang < 0) {
			FileTarget target = resolveTarget(ctx, rootName, userPath, ability, mustExist);
			return new Resolved(target, null, null, null);
		}
		String archivePart = userPath.substring(0, bang);
		String entryPart = userPath.substring(bang + 1);

		// The archive must exist and be a regular file — never created here.
		FileTarget target = resolveTarget(ctx, rootName, archivePart, ability, true);
		Path archive = target.path();
		if (!Files.isRegularFile(archive)) {
			throw new IllegalArgumentException("Not an archive file: " + archivePart);
		}

		java.util.concurrent.locks.ReentrantLock lock = archiveLocks.computeIfAbsent(
			archive.toRealPath().toString(), k -> new java.util.concurrent.locks.ReentrantLock());
		lock.lock();
		FileSystem fs;
		try {
			// Existing-only mount: no {"create":"true"} env, so a non-zip or a
			// missing file fails here rather than fabricating an archive.
			fs = java.nio.file.FileSystems.newFileSystem(archive);
		} catch (Exception mountErr) {
			lock.unlock();
			throw new IllegalArgumentException("Not a readable zip/jar archive: " + archivePart
				+ " (" + mountErr.getClass().getSimpleName() + ")");
		}
		try {
			Path zipRoot = fs.getRootDirectories().iterator().next();
			String stripped = entryPart;
			while (!stripped.isEmpty() && (stripped.charAt(0) == '/' || stripped.charAt(0) == '\\')) {
				stripped = stripped.substring(1);
			}
			Path entry = stripped.isEmpty() ? zipRoot : zipRoot.resolve(stripped).normalize();
			if (!entry.startsWith(zipRoot)) {
				throw new IllegalArgumentException("Path escapes archive '" + archivePart + "': " + entryPart);
			}
			if (mustExist && !Files.exists(entry)) {
				throw new NoSuchFileException(rootName + ":" + userPath);
			}
			return new Resolved(target, entry, fs, lock);
		} catch (RuntimeException | IOException e) {
			try { fs.close(); } catch (IOException ignored) { }
			lock.unlock();
			throw e;
		}
	}

	/** Recognised archive extensions whose {@code !} is an entry separator. */
	private static final String[] ARCHIVE_EXTS = { ".zip", ".jar" };

	/**
	 * Index of the archive-entry separator {@code !} in a path, but only when it
	 * immediately follows a recognised archive extension ({@code .zip}/{@code .jar},
	 * case-insensitive) — so a literal {@code !} in an ordinary filename is not
	 * mistaken for an archive descent. {@code !} is a jar-URL construct, not a
	 * file-path one, so plain {@code file:} paths keep it literal. Returns -1 when
	 * the path does not reference an archive entry.
	 */
	private static int archiveBang(String path) {
		if (path == null) return -1;
		String lower = path.toLowerCase(java.util.Locale.ROOT);
		int best = -1;
		for (String ext : ARCHIVE_EXTS) {
			int idx = lower.indexOf(ext + "!");
			if (idx >= 0) {
				int bang = idx + ext.length(); // the '!' sits right after the extension
				if (best < 0 || bang < best) best = bang;
			}
		}
		return best;
	}

	/** Rejects an archive-entry path ({@code x.zip!/…}) on a write-class op —
	 *  {@code file:} sees into archives read-only; mutate them via the archive adapter. */
	private static void rejectArchiveEntry(String pathArg, String verb) {
		if (archiveBang(pathArg) >= 0) {
			throw new IllegalArgumentException("Cannot " + verb + " an archive entry ('" + pathArg
				+ "') — file access to archives is read-only; use archive:zip / archive:extract");
		}
	}

	private void requireWritable(String rootName) {
		if (requireRoot(rootName).readOnly) {
			throw new IllegalArgumentException("Root '" + rootName + "' is read-only");
		}
	}

	private static void requireWritable(FileTarget target) {
		if (target.readOnly()) {
			throw new IllegalArgumentException("Root '" + target.rootName() + "' is read-only");
		}
	}

	// ==================== Sibling-adapter API ====================
	// A single source of truth for the file roots and their jail. Sibling
	// adapters that operate on the same roots (e.g. ArchiveAdapter) resolve
	// through here rather than duplicating the root config or the escape checks.
	// These do NOT check capabilities — the caller enforces its own at the point
	// of action, naming the exact file:// resource and ability it requires.

	/**
	 * Resolves a {@code (root, path)} pair to a jailed absolute path inside the
	 * named root, for reuse by sibling adapters. Throws on an unknown root or a
	 * path that escapes the root (lexically or via a symlink).
	 *
	 * @param mustExist require the resolved path to exist (else {@link NoSuchFileException})
	 */
	public Path resolve(RequestContext ctx, String root, String path, boolean mustExist) throws IOException {
		return resolvePath(ctx, root, path, mustExist);
	}

	/** True if the named root is read-only; throws if the root is unknown. */
	public boolean isReadOnlyRoot(String root) {
		return requireRoot(root).readOnly;
	}

	/** Throws if the named root is read-only or unknown. */
	public void requireWritableRoot(String root) {
		requireWritable(root);
	}

	// ==================== Invocation ====================

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String subOp = getSubOperation(meta);
		if (subOp == null) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("No file sub-operation specified"));
		}

		return CompletableFuture.supplyAsync(() -> {
			try {
				return dispatch(ctx, subOp, RT.castMap(input));
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}, VIRTUAL_EXECUTOR);
	}

	private ACell dispatch(RequestContext ctx, String subOp, AMap<AString, ACell> input) throws IOException {
		if (input == null) input = Maps.empty();

		// roots is the only op that does not require a root parameter.
		if ("roots".equals(subOp)) {
			engine.requireAuthority(ctx, Strings.create("file://"), Capability.CRUD_READ);
			return handleRoots();
		}

		return switch (subOp) {
			case "list"   -> handleList(ctx, input);
			case "tree"   -> handleTree(ctx, input);
			case "read"   -> handleRead(ctx, input);
			case "create" -> handleCreate(ctx, input);
			case "write"  -> handleWrite(ctx, input);
			case "append" -> handleAppend(ctx, input);
			case "move"   -> handleMove(ctx, input);
			case "copy"   -> handleCopy(ctx, input);
			case "delete" -> handleDelete(ctx, input);
			case "mkdir"  -> handleMkdir(ctx, input);
			case "stat"   -> handleStat(ctx, input);
			default       -> throw new IllegalArgumentException("Unknown file operation: " + subOp);
		};
	}

	/** Canonical capability/result reference for a resolved file endpoint. */
	private static String fileResource(String rootName, Path base, Path target) {
		String relative = base.relativize(target).toString();
		String separator = base.getFileSystem().getSeparator();
		if (!"/".equals(separator)) relative = relative.replace(separator, "/");
		return schemeResource("file", Strings.create(rootName),
			Strings.create(relative));
	}

	// ==================== ContentProvider ====================

	/** Resolves the same canonical file://root/path reference emitted by file
	 * operations and used by capability resources. DLFS references deliberately
	 * remain owned by DLFSAdapter, including configured DLFS-backed aliases after
	 * this method resolves the alias through the normal file boundary. The compact
	 * file:/root/path spelling is an HTTP-safe input alias; results and capability
	 * resources remain canonical file:// references. */
	@Override
	public covia.venue.storage.ContentProvider.Resolved getContent(AString ref,
			RequestContext ctx) throws IOException {
		if (ref == null || !ref.toString().startsWith("file:/")) return null;
		FileEndpoint endpoint = parseEndpoint(ref.toString(), null, null, "root", "ref");
		try (Resolved resolved = resolveEntry(ctx, endpoint.rootName(), endpoint.path(),
				Capability.CRUD_READ, true)) {
			Path path = resolved.path();
			if (!Files.isRegularFile(path)) {
				throw new IllegalArgumentException("No file at reference: " + ref);
			}
			covia.grid.AContent content;
			if (resolved.entryPath() != null) {
				// A zipfs entry cannot outlive the mounted filesystem.
				content = covia.grid.impl.BlobContent.of(
					convex.core.data.Blob.wrap(Files.readAllBytes(path)));
			} else {
				content = covia.grid.impl.PathContent.of(path);
			}
			return new covia.venue.storage.ContentProvider.Resolved(
				content, MimeUtils.guessByName(endpoint.path()));
		}
	}

	@Override
	public boolean putContent(AString ref, java.io.InputStream data, String contentType,
			RequestContext ctx) throws IOException {
		if (ref == null || !ref.toString().startsWith("file:/")) return false;
		FileEndpoint endpoint = parseEndpoint(ref.toString(), null, null, "root", "ref");
		rejectArchiveEntry(endpoint.path(), "write");
		FileTarget target = resolveTarget(ctx, endpoint.rootName(), endpoint.path(),
			Capability.CRUD_WRITE, false);
		requireWritable(target);
		try (java.io.OutputStream out = Files.newOutputStream(target.path(),
				java.nio.file.StandardOpenOption.CREATE,
				java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
				java.nio.file.StandardOpenOption.WRITE)) {
			data.transferTo(out);
		}
		return true;
	}

	// ==================== Handlers ====================

	private ACell handleRoots() {
		AVector<ACell> out = Vectors.empty();
		for (var e : roots.entrySet()) {
			Root r = e.getValue();
			AMap<AString, ACell> entry = Maps.of(
				"name", e.getKey(),
				"path", r.displayPath(),
				"kind", r.kind(),
				"readOnly", CVMBool.create(r.readOnly)
			);
			if (r.description != null) {
				entry = entry.assoc(FIELD_DESCRIPTION, r.description);
			}
			out = out.conj(entry);
		}
		return Maps.of("roots", out);
	}

	private ACell handleList(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		String rootName = stringArg(input, FIELD_ROOT);
		String pathArg = stringArg(input, FIELD_PATH);
		try (Resolved r = resolveEntry(ctx, rootName, pathArg, Capability.CRUD_READ, true)) {
			return FileOperations.list(r.path());
		}
	}

	private ACell handleTree(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		String rootName = stringArg(input, FIELD_ROOT);
		String pathArg = stringArg(input, FIELD_PATH);
		try (Resolved r = resolveEntry(ctx, rootName, pathArg, Capability.CRUD_READ, true)) {
			return FileOperations.tree(r.path(), input);
		}
	}

	private ACell handleRead(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		String rootName = stringArg(input, FIELD_ROOT);
		String pathArg = stringArg(input, FIELD_PATH);
		String mode = stringArg(input, FIELD_MODE);
		if (mode == null || mode.isEmpty()) mode = "auto";

		try (Resolved r = resolveEntry(ctx, rootName, pathArg, Capability.CRUD_READ, true)) {
			String binaryUrl = r.entryPath() == null ? r.target().binaryUrl() : null;
			return FileOperations.read(r.path(), mode, binaryUrl);
		}
	}

	private ACell handleWrite(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		String rootName = stringArg(input, FIELD_ROOT);
		String pathArg = stringArg(input, FIELD_PATH);
		rejectArchiveEntry(pathArg, "write");
		FileTarget target = resolveTarget(ctx, rootName, pathArg, Capability.CRUD_WRITE, false);
		requireWritable(target);
		return FileOperations.write(target.path(), input, engine, ctx, false);
	}

	private ACell handleCreate(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		String rootName = stringArg(input, FIELD_ROOT);
		String pathArg = FileOperations.createPath(input);
		rejectArchiveEntry(pathArg, "create");
		FileTarget target = resolveTarget(ctx, rootName, pathArg, Capability.CRUD_WRITE, false);
		requireWritable(target);
		AMap<AString, ACell> result = RT.ensureMap(
			FileOperations.create(target.path(), input, engine, ctx));
		return result.assoc(Fields.REF, Strings.create(target.resource()));
	}

	private ACell handleAppend(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		String rootName = stringArg(input, FIELD_ROOT);
		String pathArg = stringArg(input, FIELD_PATH);
		rejectArchiveEntry(pathArg, "append to");
		FileTarget target = resolveTarget(ctx, rootName, pathArg, Capability.CRUD_WRITE, false);
		requireWritable(target);
		return FileOperations.write(target.path(), input, engine, ctx, true);
	}

	private ACell handleMove(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		String fromArg = requiredStringArg(input, FIELD_FROM);
		String toArg = requiredStringArg(input, FIELD_TO);
		FileEndpoint sourceRef = parseEndpoint(fromArg,
			stringArg(input, FIELD_ROOT), null, "root", "from");
		FileEndpoint targetRef = parseEndpoint(toArg,
			stringArg(input, FIELD_TO_ROOT), sourceRef.rootName(), "toRoot", "to");
		rejectArchiveEntry(fromArg, "move");
		rejectArchiveEntry(toArg, "move to");

		FileTarget sourceTarget = resolveTarget(ctx, sourceRef.rootName(), sourceRef.path(),
			Capability.CRUD_WRITE, false);
		FileTarget destinationTarget = resolveTarget(ctx, targetRef.rootName(), targetRef.path(),
			Capability.CRUD_WRITE, false);
		destinationTarget = rebindDestinationIfSameFilesystem(
			sourceRef, targetRef, sourceTarget, destinationTarget);
		requireWritable(sourceTarget);
		requireWritable(destinationTarget);
		Path source = sourceTarget.path();
		Path target = destinationTarget.path();
		requireRelocatableEndpoints(sourceTarget.base(), destinationTarget.base(),
			source, target, fromArg, toArg);

		// Direct NIO dispatch: same-provider implementations keep their native fast
		// path; cross-provider behavior (including atomicity) belongs to the providers.
		Files.move(source, target);
		return Maps.of(
			"moved", CVMBool.TRUE,
			"from", sourceTarget.resource(),
			"to", destinationTarget.resource()
		);
	}

	private ACell handleCopy(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		String fromArg = requiredStringArg(input, FIELD_FROM);
		String toArg = requiredStringArg(input, FIELD_TO);
		FileEndpoint sourceRef = parseEndpoint(fromArg,
			stringArg(input, FIELD_ROOT), null, "root", "from");
		FileEndpoint targetRef = parseEndpoint(toArg,
			stringArg(input, FIELD_TO_ROOT), sourceRef.rootName(), "toRoot", "to");
		rejectArchiveEntry(fromArg, "copy");
		rejectArchiveEntry(toArg, "copy to");

		FileTarget sourceTarget = resolveTarget(ctx, sourceRef.rootName(), sourceRef.path(),
			Capability.CRUD_READ, false);
		FileTarget destinationTarget = resolveTarget(ctx, targetRef.rootName(), targetRef.path(),
			Capability.CRUD_WRITE, false);
		destinationTarget = rebindDestinationIfSameFilesystem(
			sourceRef, targetRef, sourceTarget, destinationTarget);
		// A read-only source is valid for copy; only the destination is mutated.
		requireWritable(destinationTarget);
		Path source = sourceTarget.path();
		Path target = destinationTarget.path();
		requireRelocatableEndpoints(sourceTarget.base(), destinationTarget.base(),
			source, target, fromArg, toArg);

		// Direct NIO dispatch, never a Covia/model-context byte round-trip.
		Files.copy(source, target);
		return Maps.of(
			"copied", CVMBool.TRUE,
			"from", sourceTarget.resource(),
			"to", destinationTarget.resource()
		);
	}

	/** Shared validation for move/copy before invoking the filesystem provider. */
	private static void requireRelocatableEndpoints(Path sourceBase, Path targetBase,
			Path source, Path target,
			String fromArg, String toArg) throws IOException {
		if (source.equals(sourceBase) || target.equals(targetBase)) {
			throw new IllegalArgumentException("Refusing to move or copy the root itself");
		}
		if (source.equals(target)) {
			throw new IllegalArgumentException("Source and destination are the same path: " + fromArg);
		}
		if (!Files.exists(source)) {
			throw new NoSuchFileException(fromArg);
		}
		Path parent = target.getParent();
		if (parent == null || !Files.isDirectory(parent)) {
			throw new NoSuchFileException("Destination parent does not exist: " + toArg);
		}
		if (source.getFileSystem() == target.getFileSystem()
				&& Files.isDirectory(source) && target.startsWith(source)) {
			throw new IllegalArgumentException(
				"Cannot move or copy a directory into itself: " + toArg);
		}
	}

	/** A parsed endpoint: configured root name plus path relative to that root. */
	private record FileEndpoint(String rootName, String path) {}

	/** Keep same-root DLFS moves/copies on one connected provider view. */
	private static FileTarget rebindDestinationIfSameFilesystem(FileEndpoint sourceRef,
			FileEndpoint targetRef, FileTarget source, FileTarget target) throws IOException {
		if (sourceRef.rootName() != null
				&& sourceRef.rootName().equals(targetRef.rootName())) {
			Path rebound = FileOperations.resolve(source.base(), targetRef.path(),
				"root '" + sourceRef.rootName() + "'", false);
			return new FileTarget(source.base(), rebound, target.resource(),
				target.readOnly(), target.binaryUrl(), target.rootName());
		}
		DLFSAdapter.DlfsFileRef sourceDlfs = DLFSAdapter.parseDlfsFileRef(source.resource());
		DLFSAdapter.DlfsFileRef targetDlfs = DLFSAdapter.parseDlfsFileRef(target.resource());
		if (sourceDlfs != null && targetDlfs != null
				&& java.util.Objects.equals(sourceDlfs.ownerDID(), targetDlfs.ownerDID())
				&& sourceDlfs.drive().equals(targetDlfs.drive())) {
			Path rebound = FileOperations.resolve(source.base(), targetDlfs.path(),
				"DLFS drive '" + sourceDlfs.drive() + "'", false);
			return new FileTarget(source.base(), rebound, target.resource(),
				target.readOnly(), target.binaryUrl(), target.rootName());
		}
		return target;
	}

	/**
	 * Parses a relative endpoint or {@code file://<root>/<path>} reference. The
	 * compact {@code file:/<root>/<path>} HTTP transport spelling is equivalent.
	 * An explicitly supplied root must agree with a qualified reference; a
	 * fallback is used only for an unqualified value.
	 */
	private static FileEndpoint parseEndpoint(String value, String explicitRoot,
			String fallbackRoot, String rootField, String endpointField) {
		if (value.startsWith("file:/")) {
			int prefixLength = value.startsWith("file://")
				? "file://".length() : "file:/".length();
			String rest = value.substring(prefixLength);
			int slash = rest.indexOf('/');
			String qualifiedRoot = (slash >= 0) ? rest.substring(0, slash) : rest;
			String path = (slash >= 0) ? rest.substring(slash + 1) : "";
			if (qualifiedRoot.isEmpty()) {
				throw new IllegalArgumentException(
					"'" + endpointField + "' file reference is missing a root");
			}
			if (explicitRoot != null && !explicitRoot.equals(qualifiedRoot)) {
				throw new IllegalArgumentException("'" + rootField + "' (" + explicitRoot
					+ ") conflicts with '" + endpointField + "' root " + qualifiedRoot);
			}
			return new FileEndpoint(qualifiedRoot, path);
		}
		if (value.startsWith("dlfs/") || value.startsWith("dlfs://")
				|| (value.startsWith("did:") && value.contains("/dlfs/"))) {
			if (explicitRoot != null) {
				throw new IllegalArgumentException("'" + rootField
					+ "' cannot be combined with a canonical DLFS reference in '" + endpointField + "'");
			}
			return new FileEndpoint(null, value);
		}
		if (value.contains("://")) {
			throw new IllegalArgumentException(
				"'" + endpointField + "' must be relative or use file://<root>/<path>");
		}
		String root = (explicitRoot != null) ? explicitRoot : fallbackRoot;
		if (root == null || root.isEmpty()) {
			throw new IllegalArgumentException("'" + rootField + "' is required when '"
				+ endpointField + "' is not a file:// reference");
		}
		return new FileEndpoint(root, value);
	}

	private ACell handleDelete(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		String rootName = stringArg(input, FIELD_ROOT);
		String pathArg = stringArg(input, FIELD_PATH);
		rejectArchiveEntry(pathArg, "delete");
		FileTarget target = resolveTarget(ctx, rootName, pathArg, Capability.CRUD_DELETE, false);
		requireWritable(target);
		return FileOperations.delete(target.path(), target.base(), input);
	}

	private ACell handleMkdir(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		String rootName = stringArg(input, FIELD_ROOT);
		String pathArg = stringArg(input, FIELD_PATH);
		rejectArchiveEntry(pathArg, "create");
		FileTarget target = resolveTarget(ctx, rootName, pathArg, Capability.CRUD_WRITE, false);
		requireWritable(target);
		return FileOperations.mkdir(target.path(), input);
	}

	private ACell handleStat(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		String rootName = stringArg(input, FIELD_ROOT);
		String pathArg = stringArg(input, FIELD_PATH);

		try (Resolved r = resolveEntry(ctx, rootName, pathArg, Capability.CRUD_READ, false)) {
			return FileOperations.stat(r.path(), r.target().readOnly());
		}
	}

	// ==================== Helpers ====================

	private static String stringArg(AMap<AString, ACell> input, AString key) {
		AString v = RT.ensureString(input.get(key));
		return (v == null) ? null : v.toString();
	}

	private static String requiredStringArg(AMap<AString, ACell> input, AString key) {
		String value = stringArg(input, key);
		if (value == null || value.isEmpty()) {
			throw new IllegalArgumentException("'" + key + "' is required");
		}
		return value;
	}

	private static void deleteRecursive(Path dir) throws IOException {
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path child : stream) {
				if (Files.isDirectory(child) && !Files.isSymbolicLink(child)) {
					deleteRecursive(child);
				} else {
					Files.delete(child);
				}
			}
		}
		Files.delete(dir);
	}
}
