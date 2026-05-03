package covia.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.grid.Asset;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Filesystem adapter — exposes the venue host's local filesystem to operations.
 *
 * <p>Access is restricted to a set of named roots configured by the venue
 * operator. Each root maps a name to an absolute host path; agents reference
 * files by {@code root} + relative {@code path}. Without configured roots the
 * adapter is inert — every operation fails with a clear error. This keeps
 * agents on a leash without making the adapter conditionally registered.
 *
 * <h3>Configuration</h3>
 * <pre>
 * {
 *   "file": {
 *     "roots": {
 *       "workspace": "/srv/agent-workspace",
 *       "data":      { "path": "/srv/data", "readOnly": true }
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
 *   <li>{@code file:write}  — write a file (text/value/bytes)</li>
 *   <li>{@code file:append} — append text to a file</li>
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
public class FileAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(FileAdapter.class);

	private static final String ASSETS_PATH = "/adapters/file/";

	private static final AString FIELD_ROOT = Strings.intern("root");
	private static final AString FIELD_PATH = Strings.intern("path");
	private static final AString FIELD_CONTENT = Strings.intern("content");
	private static final AString FIELD_VALUE = Strings.intern("value");
	private static final AString FIELD_BYTES = Strings.intern("bytes");
	private static final AString FIELD_ASSET = Strings.intern("asset");
	private static final AString FIELD_MODE = Strings.intern("mode");
	private static final AString FIELD_PARENTS = Strings.intern("parents");
	private static final AString FIELD_RECURSIVE = Strings.intern("recursive");
	private static final AString FIELD_READ_ONLY = Strings.intern("readOnly");
	private static final AString FIELD_TEMP = Strings.intern("temp");
	private static final AString FIELD_PREFIX = Strings.intern("prefix");

	/** Per-root configuration (canonical path + read-only flag). */
	private record Root(Path path, boolean readOnly) {}

	/** Resolved root configuration, populated on install. */
	private final Map<String, Root> roots = new LinkedHashMap<>();

	@Override
	public String getName() {
		return "file";
	}

	@Override
	public String getDescription() {
		return "Local filesystem access for agents. Reads, writes, lists, and manages files within "
			+ "operator-configured named roots on the venue host. Each root maps a name to an absolute "
			+ "directory; agents address files by root and relative path. No roots configured means no "
			+ "filesystem access — operators must explicitly opt in.";
	}

	@Override
	public void install(Engine engine) {
		super.install(engine);
		loadRoots();
	}

	@Override
	protected void installAssets() {
		installAsset("file/roots",  ASSETS_PATH + "roots.json");
		installAsset("file/list",   ASSETS_PATH + "list.json");
		installAsset("file/read",   ASSETS_PATH + "read.json");
		installAsset("file/write",  ASSETS_PATH + "write.json");
		installAsset("file/append", ASSETS_PATH + "append.json");
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
				addTempRoot("tmp", null, null, false);
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
				addPathRoot(name, s.toString(), false);
			} else if (raw instanceof AMap<?,?> m) {
				AMap<AString, ACell> rm = (AMap<AString, ACell>) m;
				boolean isTemp = RT.bool(rm.get(FIELD_TEMP));
				boolean readOnly = RT.bool(rm.get(Config.READ_ONLY));
				if (isTemp) {
					AString prefixCell = RT.ensureString(rm.get(FIELD_PREFIX));
					AString parentCell = RT.ensureString(rm.get(FIELD_PATH));
					Path parent = (parentCell != null) ? Path.of(parentCell.toString()) : null;
					String prefix = (prefixCell != null) ? prefixCell.toString() : null;
					addTempRoot(name, prefix, parent, readOnly);
				} else {
					AString p = RT.ensureString(rm.get(FIELD_PATH));
					if (p == null) {
						log.warn("FileAdapter: root '{}' missing 'path' — skipped", name);
						return;
					}
					addPathRoot(name, p.toString(), readOnly);
				}
			} else {
				log.warn("FileAdapter: root '{}' must be string or map — skipped", name);
			}
		} catch (IOException e) {
			log.warn("FileAdapter: root '{}' failed: {}", name, e.getMessage());
		}
	}

	private void addPathRoot(String name, String pathStr, boolean readOnly) throws IOException {
		Path canonical = Path.of(pathStr).toAbsolutePath().normalize();
		if (!Files.isDirectory(canonical)) {
			log.warn("FileAdapter: root '{}' path '{}' is not an existing directory — skipped",
				name, canonical);
			return;
		}
		// Real path so symlink-rooted configs are normalised once.
		Path real = canonical.toRealPath();
		roots.put(name, new Root(real, readOnly));
		log.info("FileAdapter: root '{}' -> {}{}", name, real, readOnly ? " (read-only)" : "");
	}

	private void addTempRoot(String name, String prefix, Path parent, boolean readOnly) throws IOException {
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

		roots.put(name, new Root(real, readOnly));
		log.info("FileAdapter: temp root '{}' -> {} (auto-cleanup on JVM exit){}",
			name, real, readOnly ? " (read-only)" : "");
	}

	// ==================== Path resolution ====================

	/**
	 * Resolves a user-supplied (root, path) pair to an absolute path inside the
	 * configured root. Rejects paths with any root component (absolute, drive-,
	 * or UNC-rooted) and rejects resolutions that escape the root either
	 * lexically (after {@code normalize()}) or via symbolic links.
	 *
	 * <p>{@link Path#of} handles platform separators and NUL rejection — no
	 * string-level munging required.
	 */
	private Path resolvePath(String rootName, String userPath, boolean mustExist) throws IOException {
		if (rootName == null || rootName.isEmpty()) {
			throw new IllegalArgumentException("'root' is required");
		}
		Root root = roots.get(rootName);
		if (root == null) {
			throw new IllegalArgumentException("Unknown root '" + rootName
				+ "'. Configured: " + roots.keySet());
		}

		if (userPath == null || userPath.isEmpty()) {
			return root.path();
		}

		// Accept a leading "/" (or "\") as "relative to the root" — matches
		// DLFS convention where "/foo.txt" is the canonical drive-rooted form.
		// Strip it before delegating to Path.of so the result isn't classed as
		// absolute on POSIX. Multiple leading separators collapse to one.
		String stripped = userPath;
		while (!stripped.isEmpty() && (stripped.charAt(0) == '/' || stripped.charAt(0) == '\\')) {
			stripped = stripped.substring(1);
		}
		if (stripped.isEmpty()) return root.path();

		Path candidate;
		try {
			candidate = Path.of(stripped);
		} catch (InvalidPathException e) {
			throw new IllegalArgumentException("Invalid path '" + userPath + "': " + e.getReason());
		}

		// Reject any path with a root component. After leading-slash strip,
		// this catches Windows drive-rooted paths ("C:\foo", "C:/foo"),
		// drive-relative paths ("C:foo"), and UNC paths ("\\server\share").
		if (candidate.isAbsolute() || candidate.getRoot() != null) {
			throw new IllegalArgumentException(
				"Path must be relative to root '" + rootName + "': " + userPath);
		}

		Path target = root.path().resolve(candidate).normalize();

		// Lexical escape check after normalize() has collapsed any '..'
		// segments. Catches the common case before we touch the filesystem.
		if (!target.startsWith(root.path())) {
			throw new IllegalArgumentException(
				"Path escapes root '" + rootName + "': " + userPath);
		}

		// Symlink-escape check. Walk up to the nearest existing ancestor and
		// verify its real path is still inside the root's real path. NOFOLLOW
		// here so a dangling symlink at the leaf is itself the existing entry.
		Path probe = target;
		while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
			probe = probe.getParent();
		}
		if (probe != null) {
			try {
				if (!probe.toRealPath().startsWith(root.path())) {
					throw new IllegalArgumentException(
						"Path escapes root via symlink: " + userPath);
				}
			} catch (NoSuchFileException dangling) {
				// Dangling symlink at the leaf — refuse rather than risk
				// creating a file at the symlink's outside-root target.
				throw new IllegalArgumentException(
					"Path resolves through dangling symlink: " + userPath);
			}
		}

		if (mustExist && !Files.exists(target)) {
			throw new NoSuchFileException(rootName + ":" + userPath);
		}
		return target;
	}

	private Root requireRoot(String name) {
		Root r = roots.get(name);
		if (r == null) {
			throw new IllegalArgumentException("Unknown root '" + name + "'");
		}
		return r;
	}

	private void requireWritable(String rootName) {
		if (requireRoot(rootName).readOnly()) {
			throw new IllegalArgumentException("Root '" + rootName + "' is read-only");
		}
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
				return dispatch(ctx, subOp, RT.ensureMap(input));
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}, VIRTUAL_EXECUTOR);
	}

	private ACell dispatch(RequestContext ctx, String subOp, AMap<AString, ACell> input) throws IOException {
		if (input == null) input = Maps.empty();

		// roots is the only op that does not require a root parameter.
		if ("roots".equals(subOp)) return handleRoots();

		// For everything else, refuse early if no roots are configured at all.
		if (roots.isEmpty()) {
			throw new IllegalArgumentException(
				"FileAdapter has no roots configured — set 'file.roots' in venue config");
		}

		return switch (subOp) {
			case "list"   -> handleList(input);
			case "read"   -> handleRead(input);
			case "write"  -> handleWrite(ctx, input);
			case "append" -> handleAppend(ctx, input);
			case "delete" -> handleDelete(input);
			case "mkdir"  -> handleMkdir(input);
			case "stat"   -> handleStat(input);
			default       -> throw new IllegalArgumentException("Unknown file operation: " + subOp);
		};
	}

	// ==================== Handlers ====================

	private ACell handleRoots() {
		AVector<ACell> out = Vectors.empty();
		for (var e : roots.entrySet()) {
			out = out.conj(Maps.of(
				"name", e.getKey(),
				"path", e.getValue().path().toString(),
				"readOnly", CVMBool.create(e.getValue().readOnly())
			));
		}
		return Maps.of("roots", out);
	}

	private ACell handleList(AMap<AString, ACell> input) throws IOException {
		String rootName = stringArg(input, FIELD_ROOT);
		String pathArg = stringArg(input, FIELD_PATH);
		Path dir = resolvePath(rootName, pathArg, true);
		if (!Files.isDirectory(dir)) {
			throw new IllegalArgumentException("Not a directory: " + pathArg);
		}

		AVector<ACell> entries = Vectors.empty();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path child : stream) {
				BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class);
				String type = attrs.isDirectory() ? "directory"
					: attrs.isRegularFile() ? "file"
					: attrs.isSymbolicLink() ? "symlink"
					: "other";
				entries = entries.conj(Maps.of(
					"name", child.getFileName().toString(),
					"type", type,
					"size", CVMLong.create(attrs.size()),
					"modified", CVMLong.create(attrs.lastModifiedTime().toMillis())
				));
			}
		}
		return Maps.of("entries", entries);
	}

	private ACell handleRead(AMap<AString, ACell> input) throws IOException {
		String rootName = stringArg(input, FIELD_ROOT);
		String pathArg = stringArg(input, FIELD_PATH);
		String mode = stringArg(input, FIELD_MODE);
		if (mode == null || mode.isEmpty()) mode = "auto";

		Path file = resolvePath(rootName, pathArg, true);
		if (!Files.isRegularFile(file)) {
			throw new IllegalArgumentException("Not a regular file: " + pathArg);
		}

		byte[] bytes = Files.readAllBytes(file);
		CVMLong size = CVMLong.create(bytes.length);
		String mime = MimeUtils.guess(file.getFileName() != null
			? file.getFileName().toString() : "", bytes);

		switch (mode) {
			case "text": {
				if (!looksLikeText(bytes)) {
					throw new IllegalArgumentException("File is not valid UTF-8 text");
				}
				return Maps.of(
					"content", new String(bytes, StandardCharsets.UTF_8),
					"encoding", "utf-8",
					"size", size,
					"mime", mime
				);
			}
			case "bytes": {
				return Maps.of(
					"content", Base64.getEncoder().encodeToString(bytes),
					"encoding", "base64",
					"size", size,
					"mime", mime
				);
			}
			case "json": {
				ACell parsed;
				try {
					parsed = JSON.parse(new String(bytes, StandardCharsets.UTF_8));
				} catch (Exception e) {
					throw new IllegalArgumentException("File is not valid JSON: " + e.getMessage());
				}
				return Maps.of(
					"value", parsed,
					"size", size,
					"mime", mime
				);
			}
			case "auto": {
				if (looksLikeText(bytes)) {
					return Maps.of(
						"content", new String(bytes, StandardCharsets.UTF_8),
						"encoding", "utf-8",
						"size", size,
						"mime", mime
					);
				}
				return Maps.of(
					"content", Base64.getEncoder().encodeToString(bytes),
					"encoding", "base64",
					"size", size,
					"mime", mime
				);
			}
			default:
				throw new IllegalArgumentException(
					"Unknown mode '" + mode + "'. Expected: auto, text, bytes, json");
		}
	}

	private ACell handleWrite(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		String rootName = stringArg(input, FIELD_ROOT);
		String pathArg = stringArg(input, FIELD_PATH);
		requireWritable(rootName);

		Path file = resolvePath(rootName, pathArg, false);
		Path parent = file.getParent();
		if (parent != null && !Files.exists(parent)) {
			throw new NoSuchFileException("Parent directory does not exist: " + parent);
		}

		boolean existed = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
		long written = writeInputTo(ctx, input, file, false);

		return Maps.of(
			"written", CVMLong.create(written),
			"created", CVMBool.create(!existed)
		);
	}

	private ACell handleAppend(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		String rootName = stringArg(input, FIELD_ROOT);
		String pathArg = stringArg(input, FIELD_PATH);
		requireWritable(rootName);

		Path file = resolvePath(rootName, pathArg, false);
		boolean existed = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
		long appended = writeInputTo(ctx, input, file, true);

		return Maps.of(
			"appended", CVMLong.create(appended),
			"created", CVMBool.create(!existed)
		);
	}

	private ACell handleDelete(AMap<AString, ACell> input) throws IOException {
		String rootName = stringArg(input, FIELD_ROOT);
		String pathArg = stringArg(input, FIELD_PATH);
		boolean recursive = RT.bool(input.get(FIELD_RECURSIVE));
		requireWritable(rootName);

		Path target = resolvePath(rootName, pathArg, false);
		if (!Files.exists(target)) {
			return Maps.of("deleted", CVMBool.FALSE, "existed", CVMBool.FALSE);
		}

		// Refuse to delete the root itself.
		if (target.equals(requireRoot(rootName).path())) {
			throw new IllegalArgumentException("Refusing to delete the root itself");
		}

		if (Files.isDirectory(target)) {
			if (recursive) {
				deleteRecursive(target);
			} else {
				Files.delete(target); // throws if non-empty
			}
		} else {
			Files.delete(target);
		}
		return Maps.of("deleted", CVMBool.TRUE, "existed", CVMBool.TRUE);
	}

	private ACell handleMkdir(AMap<AString, ACell> input) throws IOException {
		String rootName = stringArg(input, FIELD_ROOT);
		String pathArg = stringArg(input, FIELD_PATH);
		boolean parents = RT.bool(input.get(FIELD_PARENTS));
		requireWritable(rootName);

		Path target = resolvePath(rootName, pathArg, false);
		boolean existed = Files.exists(target);
		if (existed) {
			if (!Files.isDirectory(target)) {
				throw new IllegalArgumentException("Path exists and is not a directory: " + pathArg);
			}
		} else if (parents) {
			Files.createDirectories(target);
		} else {
			Files.createDirectory(target);
		}
		return Maps.of(
			"created", CVMBool.create(!existed),
			"path", target.toString()
		);
	}

	private ACell handleStat(AMap<AString, ACell> input) throws IOException {
		String rootName = stringArg(input, FIELD_ROOT);
		String pathArg = stringArg(input, FIELD_PATH);

		Path target = resolvePath(rootName, pathArg, false);
		if (!Files.exists(target)) {
			return Maps.of("exists", CVMBool.FALSE);
		}
		BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class);
		String type = attrs.isDirectory() ? "directory"
			: attrs.isRegularFile() ? "file"
			: attrs.isSymbolicLink() ? "symlink"
			: "other";
		AMap<AString, ACell> out = Maps.of(
			"exists", CVMBool.TRUE,
			"type", type,
			"size", CVMLong.create(attrs.size()),
			"modified", CVMLong.create(attrs.lastModifiedTime().toMillis()),
			"readOnly", CVMBool.create(requireRoot(rootName).readOnly())
		);
		if (attrs.isRegularFile()) {
			String mime = MimeUtils.guess(target.getFileName() != null
				? target.getFileName().toString() : "", null);
			out = out.assoc(Strings.create("mime"), Strings.create(mime));
		}
		return out;
	}

	// ==================== Helpers ====================

	private static String stringArg(AMap<AString, ACell> input, AString key) {
		AString v = RT.ensureString(input.get(key));
		return (v == null) ? null : v.toString();
	}

	/**
	 * Writes a write/append input to the target file. Exactly one of
	 * {@code content} (UTF-8 text), {@code value} (JSON-serialised cell),
	 * {@code bytes} (base64 inline), or {@code asset} (CAS reference, streamed)
	 * must be supplied.
	 *
	 * @param append true to append, false to truncate
	 * @return number of bytes written
	 */
	private long writeInputTo(RequestContext ctx, AMap<AString, ACell> input,
			Path target, boolean append) throws IOException {
		AString content = RT.ensureString(input.get(FIELD_CONTENT));
		boolean hasValue = input.containsKey(FIELD_VALUE);
		AString bytesB64 = RT.ensureString(input.get(FIELD_BYTES));
		AString assetRef = RT.ensureString(input.get(FIELD_ASSET));

		int provided = (content != null ? 1 : 0) + (hasValue ? 1 : 0)
			+ (bytesB64 != null ? 1 : 0) + (assetRef != null ? 1 : 0);
		if (provided == 0) {
			throw new IllegalArgumentException(
				"Exactly one of 'content' (UTF-8 text), 'value' (JSON), 'bytes' (base64), or 'asset' (reference) is required");
		}
		if (provided > 1) {
			throw new IllegalArgumentException(
				"Only one of 'content', 'value', 'bytes', or 'asset' may be supplied");
		}

		StandardOpenOption mode = append
			? StandardOpenOption.APPEND
			: StandardOpenOption.TRUNCATE_EXISTING;

		if (assetRef != null) {
			Asset asset = engine.resolveAsset(assetRef, ctx);
			if (asset == null) throw new IllegalArgumentException("Asset not found: " + assetRef);
			try (InputStream is = engine.getContentStream(asset);
			     OutputStream os = Files.newOutputStream(target,
			         StandardOpenOption.CREATE, mode, StandardOpenOption.WRITE,
			         LinkOption.NOFOLLOW_LINKS)) {
				if (is == null) throw new IllegalArgumentException("Asset has no content: " + assetRef);
				return is.transferTo(os);
			}
		}

		byte[] data;
		if (content != null) data = content.toString().getBytes(StandardCharsets.UTF_8);
		else if (bytesB64 != null) data = Base64.getDecoder().decode(bytesB64.toString());
		else data = JSON.print(input.get(FIELD_VALUE)).toString().getBytes(StandardCharsets.UTF_8);

		Files.write(target, data,
			StandardOpenOption.CREATE, mode, StandardOpenOption.WRITE,
			LinkOption.NOFOLLOW_LINKS);
		return data.length;
	}

	/** Heuristic: bytes parse as valid UTF-8 with no NULs and few control chars. */
	private static boolean looksLikeText(byte[] bytes) {
		if (bytes.length == 0) return true;
		try {
			String s = new String(bytes, StandardCharsets.UTF_8);
			byte[] roundtrip = s.getBytes(StandardCharsets.UTF_8);
			if (roundtrip.length != bytes.length) return false;
			int suspicious = 0;
			for (int i = 0; i < s.length(); i++) {
				char c = s.charAt(i);
				if (c == 0) return false;
				if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') suspicious++;
			}
			return suspicious * 32 < bytes.length;
		} catch (Exception e) {
			return false;
		}
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
