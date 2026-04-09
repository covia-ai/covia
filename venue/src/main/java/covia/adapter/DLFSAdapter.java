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
import java.util.concurrent.ConcurrentHashMap;

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
	private static final AString FIELD_ASSET = Strings.intern("asset");

	/** Cache of connected DLFS filesystems: "accountKey:driveName" → DLFSLocal */
	private final ConcurrentHashMap<String, DLFSLocal> driveCache = new ConcurrentHashMap<>();

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
		installAsset(ASSETS_PATH + "listDrives.json");
		installAsset(ASSETS_PATH + "createDrive.json");
		installAsset(ASSETS_PATH + "deleteDrive.json");
		installAsset(ASSETS_PATH + "list.json");
		installAsset(ASSETS_PATH + "read.json");
		installAsset(ASSETS_PATH + "write.json");
		installAsset(ASSETS_PATH + "mkdir.json");
		installAsset(ASSETS_PATH + "delete.json");
		log.info("DLFS adapter installed with {} operations", operationNames.count());
	}

	// ==================== Key Management ====================

	/**
	 * Gets or creates the user's DLFS keypair from the secret store.
	 * The Ed25519 seed is stored as hex in the DLFS_KEY secret.
	 *
	 * @return User's DLFS keypair
	 * @throws IllegalStateException if secret store is unavailable
	 */
	private AKeyPair getUserKeyPair(RequestContext ctx) {
		AString callerDID = ctx.getCallerDID();
		if (callerDID == null) throw new IllegalArgumentException("Authentication required for DLFS access");

		User user = engine.getVenueState().users().ensure(callerDID);
		byte[] encKey = SecretStore.deriveKey(engine.getKeyPair());
		SecretStore secrets = user.secrets();

		// Check for existing key
		AString existing = secrets.decrypt(DLFS_KEY_SECRET, encKey);
		if (existing != null) {
			Blob seed = Blob.fromHex(existing.toString());
			return AKeyPair.create(seed);
		}

		// Auto-generate a new DLFS key
		AKeyPair newKey = AKeyPair.generate();
		String seedHex = newKey.getSeed().toHexString();
		secrets.store(DLFS_KEY_SECRET, seedHex, encKey);
		log.info("Generated DLFS key for user {}", callerDID);
		return newKey;
	}

	// ==================== Drive Access ====================

	/**
	 * Gets the DLFS cursor for a user's signed region in the :dlfs lattice.
	 * Navigates root → :dlfs → OwnerLattice(AccountKey) → :value (signed drives map).
	 * The user's DLFS key is set as signing context before entering the OwnerLattice.
	 */
	private ALatticeCursor<?> getUserDLFSCursor(AKeyPair dlfsKey) {
		// Navigate: root → :dlfs
		ALatticeCursor<Index<Keyword, ACell>> rootCursor = engine.getRootCursor();
		ALatticeCursor<?> dlfsCursor = rootCursor.path(Covia.DLFS);

		// Set the USER's signing context (not the venue's) for writes through OwnerLattice
		LatticeContext lctx = LatticeContext.create(null, dlfsKey);
		dlfsCursor.withContext(lctx);

		// Navigate into user's signed slot: OwnerLattice → AccountKey → :value
		AccountKey ak = dlfsKey.getAccountKey();
		return dlfsCursor.path(ak, convex.core.cvm.Keywords.VALUE);
	}

	/**
	 * Gets or creates a connected DLFS drive for the caller.
	 */
	private DLFSLocal getDrive(RequestContext ctx, String driveName) {
		AKeyPair dlfsKey = getUserKeyPair(ctx);
		String cacheKey = dlfsKey.getAccountKey().toHexString() + ":" + driveName;

		return driveCache.computeIfAbsent(cacheKey, k -> {
			ALatticeCursor<?> userCursor = getUserDLFSCursor(dlfsKey);
			AString driveNameStr = Strings.create(driveName);
			return DLFS.connect(userCursor, driveNameStr);
		});
	}

	/**
	 * Lists drive names by inspecting the user's DLFS cursor.
	 */
	private AVector<ACell> listDriveNames(RequestContext ctx) {
		AKeyPair dlfsKey = getUserKeyPair(ctx);
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

		// Remove from cache and set null on the lattice cursor to tombstone it
		AKeyPair dlfsKey = getUserKeyPair(ctx);
		String cacheKey = dlfsKey.getAccountKey().toHexString() + ":" + name;
		driveCache.remove(cacheKey);

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
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new IllegalArgumentException("'path' is required");

		Path path = resolvePath(fs, pathCell.toString());
		byte[] bytes = Files.readAllBytes(path);

		if (isLikelyText(bytes)) {
			return Maps.of(
				"content", new String(bytes, StandardCharsets.UTF_8),
				"encoding", "utf-8",
				"size", CVMLong.create(bytes.length)
			);
		} else {
			return Maps.of(
				"content", Base64.getEncoder().encodeToString(bytes),
				"encoding", "base64",
				"size", CVMLong.create(bytes.length)
			);
		}
	}

	private ACell handleWrite(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new IllegalArgumentException("'path' is required");

		AString contentCell = RT.ensureString(input.get(FIELD_CONTENT));
		AString assetRef = RT.ensureString(input.get(FIELD_ASSET));

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
		} else if (contentCell != null) {
			// Inline text content
			byte[] bytes = contentCell.toString().getBytes(StandardCharsets.UTF_8);
			Files.write(path, bytes,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
			written = bytes.length;
		} else {
			throw new IllegalArgumentException("Either 'content' (text) or 'asset' (reference) is required");
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
}
