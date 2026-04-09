package covia.adapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.List;
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
import convex.dlfs.DLFSDriveManager;
import convex.lattice.fs.DLFS;
import covia.api.Fields;
import covia.venue.RequestContext;

/**
 * DLFS (Decentralised Lattice File System) adapter for the Covia venue.
 *
 * <p>Provides file-system operations on per-user DLFS drives that exist
 * as an independent lattice region — completely outside the venue lattice.
 * Drives have their own CRDT merge semantics (rsync-like, timestamp-wins)
 * and can be synced independently.</p>
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

	private static final AString FIELD_DRIVE = Strings.intern("drive");
	private static final AString FIELD_PATH = Strings.intern("path");
	private static final AString FIELD_NAME = Strings.intern("name");
	private static final AString FIELD_CONTENT = Strings.intern("content");

	private final DLFSDriveManager driveManager = new DLFSDriveManager();

	@Override
	public String getName() {
		return "dlfs";
	}

	@Override
	public String getDescription() {
		return "Decentralised Lattice File System — self-sovereign file storage with CRDT merge semantics. " +
			   "Manage per-user drives, read and write files, list directories. " +
			   "DLFS drives exist as an independent lattice region outside the venue, " +
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

	/**
	 * Gets the caller identity for drive ownership.
	 */
	private String getIdentity(RequestContext ctx) {
		AString did = ctx.getCallerDID();
		return did != null ? did.toString() : null;
	}

	/**
	 * Resolves a drive for the given caller. Returns null if not found.
	 */
	private FileSystem getDrive(RequestContext ctx, String driveName) {
		return driveManager.getDrive(getIdentity(ctx), driveName);
	}

	/**
	 * Resolves a path within a drive's filesystem.
	 */
	private Path resolvePath(FileSystem fs, String filePath) {
		if (filePath == null || filePath.isEmpty()) {
			return fs.getRootDirectories().iterator().next();
		}
		// Ensure leading slash for DLFS path resolution
		String p = filePath.startsWith("/") ? filePath : "/" + filePath;
		return fs.getPath(p);
	}

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
		List<String> drives = driveManager.listDrives(getIdentity(ctx));
		AVector<ACell> names = Vectors.empty();
		for (String name : drives) {
			names = names.conj(Strings.create(name));
		}
		return Maps.of("drives", names);
	}

	private ACell handleCreateDrive(RequestContext ctx, AMap<AString, ACell> input) {
		AString name = RT.ensureString(input.get(FIELD_NAME));
		if (name == null) name = RT.ensureString(input.get(FIELD_DRIVE));
		if (name == null) throw new IllegalArgumentException("'name' or 'drive' is required");

		boolean created = driveManager.createDrive(getIdentity(ctx), name.toString());
		return Maps.of(
			"created", created ? CVMBool.TRUE : CVMBool.FALSE,
			"name", name
		);
	}

	private ACell handleDeleteDrive(RequestContext ctx, AMap<AString, ACell> input) {
		AString name = RT.ensureString(input.get(FIELD_NAME));
		if (name == null) name = RT.ensureString(input.get(FIELD_DRIVE));
		if (name == null) throw new IllegalArgumentException("'name' or 'drive' is required");

		boolean deleted = driveManager.deleteDrive(getIdentity(ctx), name.toString());
		if (!deleted) throw new IllegalArgumentException("Drive not found: " + name);
		return Maps.of("deleted", CVMBool.TRUE);
	}

	// ==================== File Operations ====================

	private FileSystem requireDrive(RequestContext ctx, AMap<AString, ACell> input) {
		AString driveCell = RT.ensureString(input.get(FIELD_DRIVE));
		if (driveCell == null) throw new IllegalArgumentException("'drive' is required");
		FileSystem fs = getDrive(ctx, driveCell.toString());
		if (fs == null) throw new IllegalArgumentException("Drive not found: " + driveCell);
		return fs;
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
		if (contentCell == null) throw new IllegalArgumentException("'content' is required");

		Path path = resolvePath(fs, pathCell.toString());
		byte[] bytes = contentCell.toString().getBytes(StandardCharsets.UTF_8);
		boolean isNew = !Files.exists(path);
		Files.write(path, bytes,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE);

		return Maps.of(
			"written", CVMLong.create(bytes.length),
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
