package covia.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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
import covia.api.Fields;
import covia.utils.MimeUtils;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Backend-neutral implementation of the filesystem operations shared by the
 * {@code file}, {@code dlfs}, and {@code vault} adapters.
 *
 * <p>This class deliberately contains no capability or ownership policy. An
 * adapter first authorises and resolves a target, then calls these methods on
 * the resulting {@link Path}. This keeps host-root policy, DLFS DID ownership,
 * and the Vault drive binding at their proper adapter boundaries while making
 * the observable file semantics identical.</p>
 */
final class FileOperations {

	private static final AString FIELD_CONTENT = Strings.intern("content");
	private static final AString FIELD_VALUE = Strings.intern("value");
	private static final AString FIELD_BYTES = Strings.intern("bytes");
	private static final AString FIELD_ASSET = Strings.intern("asset");
	private static final AString FIELD_CONTENT_REF = Strings.intern("contentRef");
	private static final AString FIELD_PARENTS = Strings.intern("parents");
	private static final AString FIELD_RECURSIVE = Strings.intern("recursive");

	private static final int MAX_DEPTH_CAP = 10;
	private static final int MAX_ENTRIES_CAP = 5000;

	private FileOperations() {}

	/**
	 * Resolve a caller path inside a filesystem root. Leading separators mean
	 * "from this logical root", never the provider root. Absolute paths, lexical
	 * escapes, and host-filesystem symlink escapes are rejected.
	 */
	static Path resolve(Path base, String userPath, String rootLabel, boolean mustExist)
			throws IOException {
		if (userPath == null || userPath.isEmpty()) return base;

		String stripped = userPath;
		while (!stripped.isEmpty()
				&& (stripped.charAt(0) == '/' || stripped.charAt(0) == '\\')) {
			stripped = stripped.substring(1);
		}
		if (stripped.isEmpty()) return base;

		Path relative;
		try {
			relative = base.getFileSystem().getPath(stripped);
		} catch (InvalidPathException e) {
			throw new IllegalArgumentException(
				"Invalid path '" + userPath + "': " + e.getReason());
		}
		if (relative.isAbsolute() || relative.getRoot() != null) {
			throw new IllegalArgumentException(
				"Path must be relative to '" + rootLabel + "': " + userPath);
		}

		Path target = base.resolve(relative).normalize();
		if (!target.startsWith(base)) {
			throw new IllegalArgumentException(
				"Path escapes '" + rootLabel + "': " + userPath);
		}

		if (base.getFileSystem() == java.nio.file.FileSystems.getDefault()) {
			Path probe = target;
			while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
				probe = probe.getParent();
			}
			if (probe == null) {
				throw new IOException("No accessible ancestor for path: " + userPath);
			}
			Path realBase = base.toRealPath();
			Path realProbe = probe.toRealPath();
			if (!realProbe.startsWith(realBase)) {
				throw new IllegalArgumentException(
					"Path escapes '" + rootLabel + "' through a symbolic link: " + userPath);
			}
		}

		if (mustExist && !Files.exists(target)) throw new NoSuchFileException(userPath);
		return target;
	}

	static ACell list(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) throw new IllegalArgumentException("Not a directory: " + dir);
		AVector<ACell> entries = Vectors.empty();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path child : stream) {
				BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class);
				String type = attrs.isDirectory() ? "directory"
					: attrs.isRegularFile() ? "file"
					: attrs.isSymbolicLink() ? "symlink" : "other";
				Path name = child.getFileName();
				entries = entries.conj(Maps.of(
					"name", name != null ? name.toString() : child.toString(),
					"type", type,
					"size", CVMLong.create(attrs.size()),
					"modified", CVMLong.create(attrs.lastModifiedTime().toMillis())
				));
			}
		}
		return Maps.of("entries", entries);
	}

	static ACell tree(Path dir, AMap<AString, ACell> input) throws IOException {
		if (!Files.isDirectory(dir)) throw new IllegalArgumentException("Not a directory: " + dir);
		int maxDepth = boundedInt(input, "maxDepth", 3, 1, MAX_DEPTH_CAP);
		int maxEntries = boundedInt(input, "maxEntries", 500, 1, MAX_ENTRIES_CAP);
		AString infoCell = RT.ensureString(input.get(Strings.create("info")));
		TreeState state = new TreeState();
		walkTree(dir, 0, maxDepth, maxEntries,
			infoCell != null ? infoCell.toString() : null, state);
		return Maps.of("tree", state.out.toString(),
			"truncated", CVMBool.create(state.truncated));
	}

	private static final class TreeState {
		final StringBuilder out = new StringBuilder();
		int entries;
		boolean truncated;
	}

	private static void walkTree(Path dir, int depth, int maxDepth, int maxEntries,
			String info, TreeState state) throws IOException {
		List<Path> children = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path child : stream) children.add(child);
		}
		children.sort((a, b) -> displayName(a).compareToIgnoreCase(displayName(b)));
		for (Path child : children) {
			if (state.entries >= maxEntries) {
				state.truncated = true;
				return;
			}
			state.entries++;
			BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class);
			for (int i = 0; i < depth; i++) state.out.append('\t');
			state.out.append(displayName(child));
			if (attrs.isDirectory()) {
				state.out.append("/\n");
				if (depth + 1 < maxDepth) {
					walkTree(child, depth + 1, maxDepth, maxEntries, info, state);
					if (state.truncated) return;
				}
			} else {
				if ("size".equals(info) && attrs.isRegularFile()) {
					state.out.append(" (").append(humanSize(attrs.size())).append(')');
				}
				state.out.append('\n');
			}
		}
	}

	private static String displayName(Path path) {
		Path name = path.getFileName();
		return name != null ? name.toString() : path.toString();
	}

	static ACell read(Path file, String mode, String binaryUrl) throws IOException {
		if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Not a regular file: " + file);
		byte[] bytes = Files.readAllBytes(file);
		String mime = MimeUtils.guess(displayName(file), bytes);
		CVMLong size = CVMLong.create(bytes.length);
		String effectiveMode = (mode == null || mode.isEmpty()) ? "auto" : mode;

		return switch (effectiveMode) {
			case "text" -> {
				if (!looksLikeText(bytes)) throw new IllegalArgumentException("File is not valid UTF-8 text");
				yield Maps.of("content", new String(bytes, StandardCharsets.UTF_8),
					"encoding", "utf-8", "size", size, "mime", mime);
			}
			case "bytes" -> Maps.of("content", Base64.getEncoder().encodeToString(bytes),
				"encoding", "base64", "size", size, "mime", mime);
			case "json" -> {
				try {
					yield Maps.of("value", JSON.parse(new String(bytes, StandardCharsets.UTF_8)),
						"size", size, "mime", mime);
				} catch (Exception e) {
					throw new IllegalArgumentException("File is not valid JSON: " + e.getMessage());
				}
			}
			case "auto" -> {
				if (looksLikeText(bytes)) {
					yield Maps.of("content", new String(bytes, StandardCharsets.UTF_8),
						"encoding", "utf-8", "size", size, "mime", mime);
				}
				if (binaryUrl != null) {
					yield Maps.of("encoding", "binary", "size", size,
						"mime", mime, "url", binaryUrl);
				}
				yield Maps.of("content", Base64.getEncoder().encodeToString(bytes),
					"encoding", "base64", "size", size, "mime", mime);
			}
			default -> throw new IllegalArgumentException(
				"Unknown mode '" + effectiveMode + "'. Expected: auto, text, bytes, json");
		};
	}

	static ACell write(Path file, AMap<AString, ACell> input, Engine engine,
			RequestContext assetCtx, boolean append) throws IOException {
		Path parent = file.getParent();
		if (parent != null && !Files.isDirectory(parent)) {
			throw new NoSuchFileException("Parent directory does not exist: " + parent);
		}
		boolean existed = exists(file);
		long count = writeInput(file, input, engine, assetCtx, append);
		return Maps.of(append ? "appended" : "written", CVMLong.create(count),
			"created", CVMBool.create(!existed));
	}

	/**
	 * Creates a new file from the standard asset content descriptor. Unlike
	 * {@link #write}, this is atomic create-only: an existing target is never
	 * replaced. The descriptor is resolved by {@link Engine#resolveContentBlock}
	 * so inline, content-addressed and referenced content behave exactly as they
	 * do on asset metadata.
	 */
	static ACell create(Path file, AMap<AString, ACell> input, Engine engine,
			RequestContext ctx) throws IOException {
		Path parent = file.getParent();
		if (parent != null && !Files.isDirectory(parent)) {
			throw new NoSuchFileException("Parent directory does not exist: " + parent);
		}
		ACell block = input.get(FIELD_CONTENT);
		covia.venue.storage.ContentProvider.Resolved resolved =
			engine.resolveContentBlock(block, ctx);
		if (resolved == null && contentBlockHasLocator(block)) {
			throw new IllegalArgumentException("Content descriptor did not resolve any data");
		}

		java.nio.file.OpenOption[] options = createOptions(file);
		long count;
		try (InputStream is = resolved != null
				? resolved.content().getInputStream() : InputStream.nullInputStream();
			 OutputStream os = Files.newOutputStream(file, options)) {
			count = is.transferTo(os);
		}
		AMap<AString, ACell> result = Maps.of(
			"written", CVMLong.create(count), "created", CVMBool.TRUE);
		String contentType = resolved != null ? resolved.contentType()
			: stringAt(block, Fields.CONTENT_TYPE);
		if (contentType != null) {
			result = result.assoc(Fields.CONTENT_TYPE,
				Strings.create(contentType));
		}
		return result;
	}

	private static boolean contentBlockHasLocator(ACell block) {
		if (block == null) return false;
		return RT.getIn(block, Fields.INLINE) != null
			|| RT.getIn(block, Fields.REF) != null
			|| RT.getIn(block, Strings.intern("dlfs")) != null
			|| RT.getIn(block, Fields.SHA256) != null;
	}

	private static String stringAt(ACell value, AString field) {
		if (value == null) return null;
		AString string = RT.ensureString(RT.getIn(value, field));
		return string != null ? string.toString() : null;
	}

	/** Explicit path wins; otherwise content.fileName supplies the target name. */
	static String createPath(AMap<AString, ACell> input) {
		ACell pathCell = input.get(Fields.PATH);
		if (pathCell != null) {
			AString path = RT.ensureString(pathCell);
			if (path == null) throw new IllegalArgumentException("path must be a string");
			return path.toString();
		}
		AString fileName = RT.ensureString(RT.getIn(input, Fields.CONTENT, Fields.FILE_NAME));
		if (fileName == null || fileName.isEmpty()) {
			throw new IllegalArgumentException(
				"path is required unless content.fileName supplies it");
		}
		return fileName.toString();
	}

	private static long writeInput(Path target, AMap<AString, ACell> input,
			Engine engine, RequestContext assetCtx, boolean append) throws IOException {
		AString content = RT.ensureString(input.get(FIELD_CONTENT));
		boolean hasValue = input.containsKey(FIELD_VALUE);
		AString bytesB64 = RT.ensureString(input.get(FIELD_BYTES));
		AString contentRef = RT.ensureString(input.get(FIELD_CONTENT_REF));
		AString legacyAssetRef = RT.ensureString(input.get(FIELD_ASSET));
		if (contentRef != null && legacyAssetRef != null && !contentRef.equals(legacyAssetRef)) {
			throw new IllegalArgumentException("contentRef conflicts with legacy 'asset'");
		}
		if (contentRef == null) contentRef = legacyAssetRef;
		int supplied = (content != null ? 1 : 0) + (hasValue ? 1 : 0)
			+ (bytesB64 != null ? 1 : 0) + (contentRef != null ? 1 : 0);
		if (supplied != 1) {
			throw new IllegalArgumentException(supplied == 0
				? "Exactly one of 'content' (UTF-8 text), 'value' (JSON), 'bytes' (base64), or 'contentRef' is required"
				: "Only one of 'content', 'value', 'bytes', or 'contentRef' may be supplied");
		}

		StandardOpenOption mode = append
			? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING;
		java.nio.file.OpenOption[] options = writeOptions(target, mode);
		if (contentRef != null) {
			covia.venue.storage.ContentProvider.Resolved resolved =
				engine.resolveContent(contentRef, assetCtx);
			if (resolved == null) throw new IllegalArgumentException("No content at ref: " + contentRef);
			try (InputStream is = resolved.content().getInputStream();
				 OutputStream os = Files.newOutputStream(target, options)) {
				return is.transferTo(os);
			}
		}

		byte[] data;
		if (content != null) data = content.toString().getBytes(StandardCharsets.UTF_8);
		else if (bytesB64 != null) data = Base64.getDecoder().decode(bytesB64.toString());
		else data = JSON.print(input.get(FIELD_VALUE)).toString().getBytes(StandardCharsets.UTF_8);
		Files.write(target, data, options);
		return data.length;
	}

	private static java.nio.file.OpenOption[] writeOptions(Path target,
			StandardOpenOption mode) {
		if (target.getFileSystem() == java.nio.file.FileSystems.getDefault()) {
			return new java.nio.file.OpenOption[] {
				StandardOpenOption.CREATE, mode, StandardOpenOption.WRITE,
				LinkOption.NOFOLLOW_LINKS
			};
		}
		return new java.nio.file.OpenOption[] {
			StandardOpenOption.CREATE, mode, StandardOpenOption.WRITE
		};
	}

	private static java.nio.file.OpenOption[] createOptions(Path target) {
		if (target.getFileSystem() == java.nio.file.FileSystems.getDefault()) {
			return new java.nio.file.OpenOption[] {
				StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
				LinkOption.NOFOLLOW_LINKS
			};
		}
		return new java.nio.file.OpenOption[] {
			StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
		};
	}

	static ACell mkdir(Path target, AMap<AString, ACell> input) throws IOException {
		boolean existed = Files.exists(target);
		if (existed) {
			if (!Files.isDirectory(target)) {
				throw new IllegalArgumentException("Path exists and is not a directory: " + target);
			}
		} else if (RT.bool(input.get(FIELD_PARENTS))) {
			Files.createDirectories(target);
		} else {
			Files.createDirectory(target);
		}
		return Maps.of("created", CVMBool.create(!existed), "path", target.toString());
	}

	static ACell delete(Path target, Path protectedRoot, AMap<AString, ACell> input)
			throws IOException {
		if (!Files.exists(target)) return Maps.of("deleted", CVMBool.FALSE, "existed", CVMBool.FALSE);
		if (protectedRoot != null && target.equals(protectedRoot)) {
			throw new IllegalArgumentException("Refusing to delete the root itself");
		}
		if (Files.isDirectory(target) && RT.bool(input.get(FIELD_RECURSIVE))) {
			deleteRecursive(target);
		} else {
			Files.delete(target);
		}
		return Maps.of("deleted", CVMBool.TRUE, "existed", CVMBool.TRUE);
	}

	static ACell stat(Path target, Boolean readOnly) throws IOException {
		if (!Files.exists(target)) return Maps.of("exists", CVMBool.FALSE);
		BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class);
		String type = attrs.isDirectory() ? "directory"
			: attrs.isRegularFile() ? "file"
			: attrs.isSymbolicLink() ? "symlink" : "other";
		AMap<AString, ACell> out = Maps.of(
			"exists", CVMBool.TRUE,
			"type", type,
			"size", CVMLong.create(attrs.size()),
			"modified", CVMLong.create(attrs.lastModifiedTime().toMillis())
		);
		if (readOnly != null) out = out.assoc(Strings.create("readOnly"), CVMBool.create(readOnly));
		if (attrs.isRegularFile()) {
			out = out.assoc(Strings.create("mime"),
				Strings.create(MimeUtils.guessByName(displayName(target))));
		}
		return out;
	}

	private static void deleteRecursive(Path dir) throws IOException {
		List<Path> children = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path child : stream) children.add(child);
		}
		for (Path child : children) {
			if (Files.isDirectory(child) && !Files.isSymbolicLink(child)) deleteRecursive(child);
			else Files.delete(child);
		}
		Files.delete(dir);
	}

	private static int boundedInt(AMap<AString, ACell> input, String key,
			int defaultValue, int min, int max) {
		ACell value = input.get(Strings.create(key));
		if (!(value instanceof CVMLong number)) return defaultValue;
		return (int) Math.max(min, Math.min(max, number.longValue()));
	}

	private static String humanSize(long bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
		if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
		return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
	}

	private static boolean looksLikeText(byte[] bytes) {
		if (bytes.length == 0) return true;
		String value = new String(bytes, StandardCharsets.UTF_8);
		if (value.getBytes(StandardCharsets.UTF_8).length != bytes.length) return false;
		int suspicious = 0;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == 0) return false;
			if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') suspicious++;
		}
		return suspicious * 32 < bytes.length;
	}

	private static boolean exists(Path path) {
		FileSystem fs = path.getFileSystem();
		return fs == java.nio.file.FileSystems.getDefault()
			? Files.exists(path, LinkOption.NOFOLLOW_LINKS) : Files.exists(path);
	}
}
