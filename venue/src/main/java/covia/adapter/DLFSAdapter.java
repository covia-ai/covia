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
	private static final AString FIELD_ASSET = Strings.intern("asset");
	private static final AString FIELD_MODE = Strings.intern("mode");
	private static final AString FIELD_MIME = Strings.intern("mime");

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
		installAsset("dlfs/read",         ASSETS_PATH + "read.json");
		installAsset("dlfs/write",        ASSETS_PATH + "write.json");
		installAsset("dlfs/mkdir",        ASSETS_PATH + "mkdir.json");
		installAsset("dlfs/delete",       ASSETS_PATH + "delete.json");
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
	 */
	private DLFSLocal getDrive(RequestContext ctx, String driveName) {
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
			case "read" -> handleRead(ctx, input);
			case "write" -> handleWrite(ctx, input);
			case "mkdir" -> handleMkdir(ctx, input);
			case "delete" -> handleDelete(ctx, input);
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
					"type", childAttrs.isDirectory() ? "directory" : "file"
				);
				if (childAttrs.isRegularFile()) {
					entry = entry.assoc(Strings.create("size"), CVMLong.create(childAttrs.size()));
				}
				entries = entries.conj(entry);
			}
		}
		return Maps.of("entries", entries);
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

	private ACell handleWrite(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new IllegalArgumentException("'path' is required");

		AString contentCell = RT.ensureString(input.get(FIELD_CONTENT));
		ACell valueCell = input.get(FIELD_VALUE);
		boolean hasValue = input.containsKey(FIELD_VALUE);
		AString assetRef = RT.ensureString(input.get(FIELD_ASSET));

		int supplied = (contentCell != null ? 1 : 0) + (hasValue ? 1 : 0) + (assetRef != null ? 1 : 0);
		if (supplied == 0) {
			throw new IllegalArgumentException("Exactly one of 'content' (UTF-8 text), 'value' (JSON), or 'asset' (reference) is required");
		}
		if (supplied > 1) {
			throw new IllegalArgumentException("Only one of 'content', 'value', or 'asset' may be supplied");
		}

		Path path = resolvePath(fs, pathCell.toString());
		boolean isNew = !Files.exists(path);
		long written;

		if (assetRef != null) {
			// Resolve asset reference and stream content to DLFS
			Asset asset = engine.resolveAsset(assetRef, ctx);
			if (asset == null) throw new IllegalArgumentException("Asset not found: " + assetRef);
			try (InputStream is = engine.getContentStream(asset);
			     OutputStream os = Files.newOutputStream(path,
			         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
				if (is == null) throw new IllegalArgumentException("Asset has no content: " + assetRef);
				written = is.transferTo(os);
			}
		} else if (hasValue) {
			// JSON-serialised value
			byte[] bytes = convex.core.util.JSON.print(valueCell).toString().getBytes(StandardCharsets.UTF_8);
			Files.write(path, bytes,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
			written = bytes.length;
		} else {
			// Inline UTF-8 text content
			byte[] bytes = contentCell.toString().getBytes(StandardCharsets.UTF_8);
			Files.write(path, bytes,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
			written = bytes.length;
		}

		return Maps.of(
			"written", CVMLong.create(written),
			"created", isNew ? CVMBool.TRUE : CVMBool.FALSE
		);
	}

	private ACell handleMkdir(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new IllegalArgumentException("'path' is required");

		Path path = resolvePath(fs, pathCell.toString());
		Files.createDirectory(path);
		return Maps.of("created", CVMBool.TRUE);
	}

	private ACell handleDelete(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new IllegalArgumentException("'path' is required");

		Path path = resolvePath(fs, pathCell.toString());
		Files.delete(path);
		return Maps.of("deleted", CVMBool.TRUE);
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
