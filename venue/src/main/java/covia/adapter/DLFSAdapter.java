package covia.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import covia.grid.Asset;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFileSystem;
import convex.lattice.fs.impl.DLFSLocal;
import covia.api.Fields;
import covia.lattice.Covia;
import covia.utils.MimeUtils;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.SecretStore;
import covia.venue.User;

/**
 * DLFS (Decentralised Lattice File System) adapter for the Covia venue.
 *
 * <p>Provides file-system operations on per-user DLFS drives backed by the
 * lattice {@code :dlfs} region (sibling to {@code :grid} in {@link Covia#ROOT}).
 * Each user's drives are signed with their own Ed25519 key (stored as
 * {@code DLFS_KEY} in the venue's secret store).</p>
 *
 * <p>DLFS is an independent lattice region with its own CRDT merge semantics
 * (rsync-like, timestamp-wins) and can sync independently from venue state.</p>
 *
 * <h3>Operations</h3>
 * <ul>
 *   <li>{@code dlfs:listDrives} — list drives for caller</li>
 *   <li>{@code dlfs:createDrive} — create a named drive</li>
 *   <li>{@code dlfs:deleteDrive} — delete a drive</li>
 *   <li>{@code dlfs:list} — list directory contents</li>
 *   <li>{@code dlfs:read} — read file content</li>
 *   <li>{@code dlfs:write} — write file content</li>
 *   <li>{@code dlfs:mkdir} — create directory</li>
 *   <li>{@code dlfs:delete} — delete file or directory</li>
 * </ul>
 */
public class DLFSAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(DLFSAdapter.class);

	private static final String ASSETS_PATH = "/adapters/dlfs/";
	private static final String DLFS_KEY_SECRET = "DLFS_KEY";

	private static final AString FIELD_DRIVE = Strings.intern("drive");
	private static final AString FIELD_PATH = Strings.intern("path");
	private static final AString FIELD_NAME = Strings.intern("name");
	private static final AString FIELD_CONTENT = Strings.intern("content");
	private static final AString FIELD_VALUE = Strings.intern("value");
	private static final AString FIELD_BYTES = Strings.intern("bytes");
	private static final AString FIELD_ASSET = Strings.intern("asset");
	private static final AString FIELD_MODE = Strings.intern("mode");
	private static final AString FIELD_MIME = Strings.intern("mime");
	private static final AString FIELD_PARENTS = Strings.intern("parents");
	private static final AString FIELD_RECURSIVE = Strings.intern("recursive");

	@Override
	public String getName() {
		return "dlfs";
	}

	@Override
	public String getDescription() {
		return "Decentralised Lattice File System — self-sovereign file storage with CRDT merge semantics. " +
			   "Manage per-user drives, read and write files, list directories. " +
			   "DLFS drives exist as an independent lattice region signed by the user's own key, " +
			   "enabling private, portable health vaults and document storage.";
	}

	@Override
	protected void installAssets() {
		installAsset("dlfs/list-drives",  ASSETS_PATH + "listDrives.json");
		installAsset("dlfs/create-drive", ASSETS_PATH + "createDrive.json");
		installAsset("dlfs/delete-drive", ASSETS_PATH + "deleteDrive.json");
		installAsset("dlfs/list",         ASSETS_PATH + "list.json");
		installAsset("dlfs/tree",         ASSETS_PATH + "tree.json");
		installAsset("dlfs/read",         ASSETS_PATH + "read.json");
		installAsset("dlfs/write",        ASSETS_PATH + "write.json");
		installAsset("dlfs/append",       ASSETS_PATH + "append.json");
		installAsset("dlfs/mkdir",        ASSETS_PATH + "mkdir.json");
		installAsset("dlfs/delete",       ASSETS_PATH + "delete.json");
		installAsset("dlfs/stat",         ASSETS_PATH + "stat.json");
		log.info("DLFS adapter installed with {} operations", pendingCatalogEntries.size());
	}

	// ==================== Key Management ====================

	/**
	 * Gets the user's DLFS keypair from the secret store.
	 *
	 * <p>Concurrent callers race-safely: a candidate key is generated locally,
	 * then atomically published via {@link SecretStore#storeIfAbsent}. The
	 * winner is whichever value is in the store after the CAS — which may be
	 * another caller's candidate. Only the caller whose candidate won the race
	 * triggers a venue state sync.</p>
	 *
	 * @return User's DLFS keypair (never null)
	 */
	private AKeyPair ensureUserKeyPair(RequestContext ctx) {
		AString callerDID = ctx.getCallerDID();
		if (callerDID == null) throw new IllegalArgumentException("Authentication required for DLFS access");

		User user = engine.getVenueState().users().ensure(callerDID);
		byte[] encKey = SecretStore.deriveKey(engine.getKeyPair());
		SecretStore secrets = user.secrets();

		AString existing = secrets.decrypt(DLFS_KEY_SECRET, encKey);
		if (existing != null) {
			return AKeyPair.create(Blob.fromHex(existing.toString()));
		}

		AKeyPair candidate = AKeyPair.generate();
		AString candidateHex = Strings.create(candidate.getSeed().toHexString());
		boolean wonRace = secrets.storeIfAbsent(Strings.create(DLFS_KEY_SECRET), candidateHex, encKey);

		AString winner = secrets.decrypt(DLFS_KEY_SECRET, encKey);
		if (winner == null) {
			throw new IllegalStateException("DLFS key vanished after storeIfAbsent for " + callerDID);
		}

		if (wonRace) {
			engine.syncState();
			log.info("Generated DLFS key for user {}", callerDID);
		}

		return AKeyPair.create(Blob.fromHex(winner.toString()));
	}

	// ==================== Drive Access ====================

	/**
	 * Gets the DLFS cursor for a user's signed region in the :dlfs lattice.
	 * Navigates root → :dlfs → OwnerLattice(AccountKey) → :value (signed drives map).
	 *
	 * <p>The returned cursor carries a {@link LatticeContext} with the caller's
	 * DLFS signing key and a wall-clock timestamp. The timestamp is propagated
	 * to all DLFS node writes via {@code DLFSLocal.getTimestamp()} and used as
	 * the merge timestamp where applicable.</p>
	 */
	private ALatticeCursor<?> getUserDLFSCursor(AKeyPair dlfsKey) {
		ALatticeCursor<Index<Keyword, ACell>> rootCursor = engine.getRootCursor();
		ALatticeCursor<?> dlfsCursor = rootCursor.path(Covia.DLFS);

		LatticeContext lctx = LatticeContext.create(
			CVMLong.create(System.currentTimeMillis()), dlfsKey);
		dlfsCursor.withContext(lctx);

		AccountKey ak = dlfsKey.getAccountKey();
		return dlfsCursor.path(ak, convex.core.cvm.Keywords.VALUE);
	}

	/**
	 * Gets a connected DLFS drive for the given DID. Public so WebDAV can
	 * access drives by identity string.
	 */
	public DLFSLocal getDriveForIdentity(String didString, String driveName) {
		return getDrive(RequestContext.of(Strings.create(didString)), driveName);
	}

	/**
	 * Connects a DLFS drive view for the caller. Cheap — just a cursor view, no
	 * caching. A fresh {@link DLFSLocal} per request keeps the per-request
	 * {@link LatticeContext} (timestamp, signing key) isolated.
	 *
	 * <p>Public so other adapters (e.g. {@code FileAdapter} routing a
	 * {@code dlfs}-backed root) can obtain the same drive view the DLFS
	 * adapter operations themselves use.</p>
	 */
	public DLFSLocal getDrive(RequestContext ctx, String driveName) {
		AKeyPair dlfsKey = ensureUserKeyPair(ctx);
		ALatticeCursor<?> userCursor = getUserDLFSCursor(dlfsKey);
		return DLFS.connect(userCursor, Strings.create(driveName));
	}

	/**
	 * Lists drive names by inspecting the user's DLFS cursor.
	 */
	private AVector<ACell> listDriveNames(RequestContext ctx) {
		AKeyPair dlfsKey = ensureUserKeyPair(ctx);
		ALatticeCursor<?> userCursor = getUserDLFSCursor(dlfsKey);
		ACell value = userCursor.get();

		AVector<ACell> names = Vectors.empty();
		if (value instanceof AMap<?,?> map) {
			for (var entry : ((AMap<AString,ACell>) map).entrySet()) {
				names = names.conj(entry.getKey());
			}
		}
		return names;
	}

	/**
	 * Resolves a path within a drive's filesystem.
	 */
	private Path resolvePath(FileSystem fs, String filePath) {
		if (filePath == null || filePath.isEmpty()) {
			return fs.getRootDirectories().iterator().next();
		}
		String p = filePath.startsWith("/") ? filePath : "/" + filePath;
		return fs.getPath(p);
	}

	// ==================== Invocation ====================

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String subOp = getSubOperation(meta);
		if (subOp == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("No DLFS sub-operation specified"));
		}

		return CompletableFuture.supplyAsync(() -> {
			try {
				return dispatch(ctx, subOp, RT.ensureMap(input));
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}, VIRTUAL_EXECUTOR);
	}

	@SuppressWarnings("unchecked")
	private ACell dispatch(RequestContext ctx, String subOp, AMap<AString, ACell> input) throws IOException {
		if (input == null) input = Maps.empty();

		return switch (subOp) {
			case "listDrives" -> handleListDrives(ctx);
			case "createDrive" -> handleCreateDrive(ctx, input);
			case "deleteDrive" -> handleDeleteDrive(ctx, input);
			case "list" -> handleList(ctx, input);
			case "tree" -> handleTree(ctx, input);
			case "read" -> handleRead(ctx, input);
			case "write" -> handleWrite(ctx, input, false);
			case "append" -> handleWrite(ctx, input, true);
			case "mkdir" -> handleMkdir(ctx, input);
			case "delete" -> handleDelete(ctx, input);
			case "stat" -> handleStat(ctx, input);
			default -> throw new IllegalArgumentException("Unknown DLFS operation: " + subOp);
		};
	}

	// ==================== Drive Management ====================

	private ACell handleListDrives(RequestContext ctx) {
		AVector<ACell> names = listDriveNames(ctx);
		return Maps.of("drives", names);
	}

	private ACell handleCreateDrive(RequestContext ctx, AMap<AString, ACell> input) {
		AString name = RT.ensureString(input.get(FIELD_NAME));
		if (name == null) name = RT.ensureString(input.get(FIELD_DRIVE));
		if (name == null) throw new IllegalArgumentException("'name' or 'drive' is required");

		// getDrive auto-creates via DLFS.connect() (initialises empty tree if absent)
		getDrive(ctx, name.toString());
		return Maps.of(
			"created", CVMBool.TRUE,
			"name", name
		);
	}

	private ACell handleDeleteDrive(RequestContext ctx, AMap<AString, ACell> input) {
		AString name = RT.ensureString(input.get(FIELD_NAME));
		if (name == null) name = RT.ensureString(input.get(FIELD_DRIVE));
		if (name == null) throw new IllegalArgumentException("'name' or 'drive' is required");

		AKeyPair dlfsKey = ensureUserKeyPair(ctx);
		ALatticeCursor<?> userCursor = getUserDLFSCursor(dlfsKey);
		ALatticeCursor<?> driveCursor = userCursor.path(name);
		driveCursor.set(null);

		return Maps.of("deleted", CVMBool.TRUE);
	}

	// ==================== File Operations ====================

	private DLFSLocal requireDrive(RequestContext ctx, AMap<AString, ACell> input) {
		AString driveCell = RT.ensureString(input.get(FIELD_DRIVE));
		if (driveCell == null) throw new IllegalArgumentException("'drive' is required");
		return getDrive(ctx, driveCell.toString());
	}

	private ACell handleList(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		Path dir = resolvePath(fs, pathCell != null ? pathCell.toString() : null);

		BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class);
		if (!attrs.isDirectory()) {
			throw new IllegalArgumentException("Not a directory: " + pathCell);
		}

		AVector<ACell> entries = Vectors.empty();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path child : stream) {
				BasicFileAttributes childAttrs = Files.readAttributes(child, BasicFileAttributes.class);
				Path fileName = child.getFileName();
				String name = (fileName != null) ? fileName.toString() : child.toString();
				AMap<AString, ACell> entry = Maps.of(
					"name", name,
					"type", childAttrs.isDirectory() ? "directory" : "file",
					"size", CVMLong.create(childAttrs.size()),
					"modified", CVMLong.create(childAttrs.lastModifiedTime().toMillis())
				);
				entries = entries.conj(entry);
			}
		}
		return Maps.of("entries", entries);
	}

	/** Tree-walk state shared across the recursion (mirrors FileAdapter). */
	private static final class TreeState {
		final StringBuilder out = new StringBuilder();
		int entries = 0;
		boolean truncated = false;
	}

	private static final int MAX_DEPTH_CAP = 10;
	private static final int MAX_ENTRIES_CAP = 5000;

	private ACell handleTree(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		Path dir = resolvePath(fs, pathCell != null ? pathCell.toString() : null);

		BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class);
		if (!attrs.isDirectory()) {
			throw new IllegalArgumentException("Not a directory: " + pathCell);
		}

		int maxDepth = boundedInt(input, "maxDepth", 3, 1, MAX_DEPTH_CAP);
		int maxEntries = boundedInt(input, "maxEntries", 500, 1, MAX_ENTRIES_CAP);
		String info = stringField(input, "info");

		TreeState state = new TreeState();
		walkTree(dir, 0, maxDepth, maxEntries, info, state);

		return Maps.of(
			"tree", state.out.toString(),
			"truncated", CVMBool.create(state.truncated)
		);
	}

	private static void walkTree(Path dir, int depth, int maxDepth, int maxEntries,
			String info, TreeState state) throws IOException {
		// Snapshot children first — DLFS DirectoryStream isn't iteration-safe
		// against concurrent mutation and tombstone semantics make order
		// dependent on insertion. Sort for stable output.
		java.util.List<Path> children = new java.util.ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path c : stream) children.add(c);
		}
		children.sort((a, b) -> {
			Path af = a.getFileName();
			Path bf = b.getFileName();
			String an = (af != null) ? af.toString() : a.toString();
			String bn = (bf != null) ? bf.toString() : b.toString();
			return an.compareToIgnoreCase(bn);
		});

		for (Path child : children) {
			if (state.entries >= maxEntries) {
				state.truncated = true;
				return;
			}
			state.entries++;

			BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class);
			Path fname = child.getFileName();
			String name = (fname != null) ? fname.toString() : child.toString();
			for (int i = 0; i < depth; i++) state.out.append('\t');
			state.out.append(name);

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

	private static int boundedInt(AMap<AString, ACell> input, String key, int defaultVal, int min, int max) {
		ACell v = input.get(Strings.create(key));
		if (v instanceof CVMLong cl) {
			long l = cl.longValue();
			return (int) Math.max(min, Math.min(max, l));
		}
		return defaultVal;
	}

	private static String stringField(AMap<AString, ACell> input, String key) {
		AString v = RT.ensureString(input.get(Strings.create(key)));
		return (v == null) ? null : v.toString();
	}

	private static String humanSize(long bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
		if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
		return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
	}

	private ACell handleRead(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		AString driveCell = RT.ensureString(input.get(FIELD_DRIVE));
		if (driveCell == null) throw new IllegalArgumentException("'drive' is required");
		String driveName = driveCell.toString();
		FileSystem fs = getDrive(ctx, driveName);

		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new IllegalArgumentException("'path' is required");

		AString modeCell = RT.ensureString(input.get(FIELD_MODE));
		String mode = modeCell != null ? modeCell.toString() : "auto";

		Path path = resolvePath(fs, pathCell.toString());
		byte[] bytes = Files.readAllBytes(path);
		String mime = MimeUtils.guess(pathCell.toString(), bytes);
		CVMLong size = CVMLong.create(bytes.length);

		switch (mode) {
			case "auto": {
				if (isLikelyText(bytes)) {
					return Maps.of(
						"content", new String(bytes, StandardCharsets.UTF_8),
						"encoding", "utf-8",
						"size", size,
						"mime", mime
					);
				}
				// Binary: return a reference to the WebDAV URL — caller fetches bytes there
				return Maps.of(
					"encoding", "binary",
					"size", size,
					"mime", mime,
					"url", buildWebDAVUrl(driveName, pathCell.toString())
				);
			}
			case "text": {
				if (!isLikelyText(bytes)) {
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
				ACell value;
				try {
					value = convex.core.util.JSON.parse(new String(bytes, StandardCharsets.UTF_8));
				} catch (Exception e) {
					throw new IllegalArgumentException("File is not valid JSON: " + e.getMessage());
				}
				return Maps.of(
					"value", value,
					"size", size,
					"mime", mime
				);
			}
			default:
				throw new IllegalArgumentException("Unknown mode '" + mode + "'. Expected: auto, text, bytes, json");
		}
	}

	private ACell handleWrite(RequestContext ctx, AMap<AString, ACell> input, boolean append) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new IllegalArgumentException("'path' is required");

		AString contentCell = RT.ensureString(input.get(FIELD_CONTENT));
		boolean hasValue = input.containsKey(FIELD_VALUE);
		AString bytesB64 = RT.ensureString(input.get(FIELD_BYTES));
		AString assetRef = RT.ensureString(input.get(FIELD_ASSET));

		int supplied = (contentCell != null ? 1 : 0) + (hasValue ? 1 : 0)
			+ (bytesB64 != null ? 1 : 0) + (assetRef != null ? 1 : 0);
		if (supplied == 0) {
			throw new IllegalArgumentException(
				"Exactly one of 'content' (UTF-8 text), 'value' (JSON), 'bytes' (base64), or 'asset' (reference) is required");
		}
		if (supplied > 1) {
			throw new IllegalArgumentException(
				"Only one of 'content', 'value', 'bytes', or 'asset' may be supplied");
		}

		Path path = resolvePath(fs, pathCell.toString());
		boolean isNew = !Files.exists(path);
		StandardOpenOption mode = append
			? StandardOpenOption.APPEND
			: StandardOpenOption.TRUNCATE_EXISTING;
		long written;

		if (assetRef != null) {
			Asset asset = engine.resolveAsset(assetRef, ctx);
			if (asset == null) throw new IllegalArgumentException("Asset not found: " + assetRef);
			try (InputStream is = engine.getContentStream(asset);
			     OutputStream os = Files.newOutputStream(path,
			         StandardOpenOption.CREATE, mode, StandardOpenOption.WRITE)) {
				if (is == null) throw new IllegalArgumentException("Asset has no content: " + assetRef);
				written = is.transferTo(os);
			}
		} else {
			byte[] bytes;
			if (contentCell != null) bytes = contentCell.toString().getBytes(StandardCharsets.UTF_8);
			else if (bytesB64 != null) bytes = Base64.getDecoder().decode(bytesB64.toString());
			else bytes = convex.core.util.JSON.print(input.get(FIELD_VALUE)).toString().getBytes(StandardCharsets.UTF_8);
			Files.write(path, bytes, StandardOpenOption.CREATE, mode, StandardOpenOption.WRITE);
			written = bytes.length;
		}

		return Maps.of(
			append ? "appended" : "written", CVMLong.create(written),
			"created", isNew ? CVMBool.TRUE : CVMBool.FALSE
		);
	}

	private ACell handleMkdir(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new IllegalArgumentException("'path' is required");
		boolean parents = RT.bool(input.get(FIELD_PARENTS));

		Path path = resolvePath(fs, pathCell.toString());
		boolean existed = Files.exists(path);
		if (existed) {
			if (!Files.isDirectory(path)) {
				throw new IllegalArgumentException("Path exists and is not a directory: " + pathCell);
			}
		} else if (parents) {
			Files.createDirectories(path);
		} else {
			Files.createDirectory(path);
		}
		return Maps.of(
			"created", CVMBool.create(!existed),
			"path", path.toString()
		);
	}

	private ACell handleDelete(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new IllegalArgumentException("'path' is required");
		boolean recursive = RT.bool(input.get(FIELD_RECURSIVE));

		Path path = resolvePath(fs, pathCell.toString());
		if (!Files.exists(path)) {
			return Maps.of("deleted", CVMBool.FALSE, "existed", CVMBool.FALSE);
		}
		if (Files.isDirectory(path) && recursive) {
			deleteRecursive(path);
		} else {
			Files.delete(path);
		}
		return Maps.of("deleted", CVMBool.TRUE, "existed", CVMBool.TRUE);
	}

	private static void deleteRecursive(Path dir) throws IOException {
		// Snapshot children before mutating — DLFS's DirectoryStream skips
		// tombstones, but iterating while mutating is still risky.
		java.util.List<Path> children = new java.util.ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path child : stream) children.add(child);
		}
		for (Path child : children) {
			if (Files.isDirectory(child)) {
				deleteRecursive(child);
			} else {
				Files.delete(child);
			}
		}
		Files.delete(dir);
	}

	private ACell handleStat(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new IllegalArgumentException("'path' is required");

		Path path = resolvePath(fs, pathCell.toString());
		if (!Files.exists(path)) {
			return Maps.of("exists", CVMBool.FALSE);
		}
		BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
		String type = attrs.isDirectory() ? "directory"
			: attrs.isRegularFile() ? "file"
			: "other";
		AMap<AString, ACell> out = Maps.of(
			"exists", CVMBool.TRUE,
			"type", type,
			"size", CVMLong.create(attrs.size()),
			"modified", CVMLong.create(attrs.lastModifiedTime().toMillis())
		);
		if (attrs.isRegularFile()) {
			String mime = MimeUtils.guessByName(path.getFileName() != null
				? path.getFileName().toString() : "");
			out = out.assoc(FIELD_MIME, Strings.create(mime));
		}
		return out;
	}


	private static boolean isLikelyText(byte[] bytes) {
		for (byte b : bytes) {
			if (b == 0) return false;
		}
		return true;
	}

	/**
	 * Builds a URL-encoded WebDAV path: {@code /dlfs/{drive}/{path}}, with each
	 * path component encoded so spaces and other special characters survive.
	 */
	private static String buildWebDAVUrl(String drive, String path) {
		StringBuilder sb = new StringBuilder("/dlfs/");
		sb.append(java.net.URLEncoder.encode(drive, StandardCharsets.UTF_8).replace("+", "%20"));
		if (path != null && !path.isEmpty()) {
			// Strip leading slashes and encode each segment
			String p = path.startsWith("/") ? path.substring(1) : path;
			for (String seg : p.split("/", -1)) {
				sb.append('/');
				sb.append(java.net.URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
			}
		}
		return sb.toString();
	}
}
