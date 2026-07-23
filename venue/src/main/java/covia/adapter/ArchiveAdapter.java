package covia.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Enumeration;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.crypto.Hashing;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.auth.ucan.Capability;
import covia.api.Abilities;
import covia.api.Fields;
import covia.venue.RequestContext;

/**
 * Archive adapter — lets agents work with common archive files (zip and jar).
 *
 * <p>Operations run over the {@link FileAdapter}'s configured roots (one source
 * of truth for the file jail) and, where useful, content-addressed assets.
 * Nothing here escapes a root: an archive source is a jailed root+path file (or
 * a CAS asset / inline bytes), and extraction targets a jailed directory with
 * per-entry zip-slip checks.</p>
 *
 * <h3>Operations</h3>
 * <ul>
 *   <li>{@code archive:list}    — list entries + metadata without extracting</li>
 *   <li>{@code archive:extract} — extract a zip/jar into a destination directory</li>
 *   <li>{@code archive:zip}     — build a zip from root+path files/dirs → a file or an asset</li>
 * </ul>
 */
public class ArchiveAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(ArchiveAdapter.class);

	private static final String ASSETS_PATH = "/adapters/archive/";
	private static final String ZIP_MEDIA_TYPE = "application/zip";

	private static final AString FIELD_ROOT = Strings.intern("root");
	private static final AString FIELD_PATH = Strings.intern("path");
	private static final AString FIELD_PATHS = Strings.intern("paths");
	private static final AString FIELD_ASSET = Strings.intern("asset");
	private static final AString FIELD_BYTES = Strings.intern("bytes");
	private static final AString FIELD_DEST_ROOT = Strings.intern("destRoot");
	private static final AString FIELD_DEST_PATH = Strings.intern("destPath");
	private static final AString FIELD_NAME = Strings.intern("name");

	/** Guards against runaway/zip-bomb extraction: caps on entry count and total
	 *  uncompressed bytes written. Generous for legitimate archives. */
	private static final int MAX_ENTRIES = 100_000;
	private static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024; // 2 GiB
	/** Cap on the entry list returned by extract/list, so a huge archive does not
	 *  produce an unbounded result (the operation still processes every entry). */
	private static final int MAX_LISTED = 5000;

	@Override
	public String getName() {
		return "archive";
	}

	@Override
	public String getDescription() {
		return "Work with archive files (zip and jar). List entries, extract archives into a "
			+ "filesystem root, or build a zip from files/directories — output to a root path or a "
			+ "content-addressed asset. Sources may be a root+path file, a CAS asset, or inline base64 "
			+ "bytes. All paths stay inside the FileAdapter's configured roots; extraction is zip-slip "
			+ "protected and capped against zip bombs.";
	}

	@Override
	protected void installAssets() {
		installAsset("archive/list",    ASSETS_PATH + "list.json");
		installAsset("archive/extract", ASSETS_PATH + "extract.json");
		installAsset("archive/zip",     ASSETS_PATH + "zip.json");
	}

	// ==================== Invocation ====================

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String subOp = getSubOperation(meta);
		if (subOp == null) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("No archive sub-operation specified"));
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				AMap<AString, ACell> in = RT.castMap(input);
				if (in == null) in = Maps.empty();
				return switch (subOp) {
					case "list"    -> handleList(ctx, in);
					case "extract" -> handleExtract(ctx, in);
					case "zip"     -> handleZip(ctx, in);
					default -> throw new IllegalArgumentException("Unknown archive operation: " + subOp);
				};
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}, VIRTUAL_EXECUTOR);
	}

	/** The FileAdapter that owns the roots, or a clear error if unavailable. */
	private FileAdapter files() {
		AAdapter raw = engine.getAdapter("file");
		if (!(raw instanceof FileAdapter fa)) {
			throw new IllegalStateException("Archive operations require the file adapter to be registered");
		}
		return fa;
	}

	// ==================== Source resolution ====================

	/** A readable archive source resolved to a local file; {@link #temp} ones are
	 *  deleted on {@link #close}. */
	private record Source(Path path, boolean temp) implements AutoCloseable {
		@Override public void close() {
			if (temp) {
				try { Files.deleteIfExists(path); } catch (IOException ignored) { }
			}
		}
	}

	/**
	 * Resolves the archive source — exactly one of {@code root}+{@code path} (a
	 * jailed file, read-checked), {@code asset} (a CAS reference), or {@code bytes}
	 * (inline base64). Asset/bytes sources are spooled to a temp file so every op
	 * reads via {@link ZipFile} (existing-only — a source is never created here).
	 */
	private Source resolveSource(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		AString root = RT.ensureString(input.get(FIELD_ROOT));
		AString path = RT.ensureString(input.get(FIELD_PATH));
		AString assetRef = RT.ensureString(input.get(FIELD_ASSET));
		AString bytesB64 = RT.ensureString(input.get(FIELD_BYTES));

		int provided = ((root != null && path != null) ? 1 : 0)
			+ (assetRef != null ? 1 : 0) + (bytesB64 != null ? 1 : 0);
		if (provided == 0) {
			throw new IllegalArgumentException(
				"Provide an archive source: 'root'+'path', 'asset', or 'bytes'");
		}
		if (provided > 1) {
			throw new IllegalArgumentException(
				"Provide only one archive source: 'root'+'path', 'asset', or 'bytes'");
		}

		if (root != null && path != null) {
			engine.requireAuthority(ctx, Strings.create(schemeResource("file", root, path)), Capability.CRUD_READ);
			Path file = files().resolve(ctx, root.toString(), path.toString(), true);
			if (!Files.isRegularFile(file)) {
				throw new IllegalArgumentException("Archive is not a regular file: " + path);
			}
			return new Source(file, false);
		}

		Path tmp = Files.createTempFile("covia-archive-", ".zip");
		try {
			if (assetRef != null) {
				// resolveContent spans every storage mechanism (CAS record, DLFS,
				// provider) under the caller's authority — unlike getContentStream,
				// which only serves metadata-declared content.
				covia.venue.storage.ContentProvider.Resolved resolved = engine.resolveContent(assetRef, ctx);
				if (resolved == null || resolved.content() == null) {
					throw new IllegalArgumentException("Asset not found or has no content: " + assetRef);
				}
				Files.write(tmp, resolved.content().getBlob().getBytes());
			} else {
				byte[] raw = Base64.getDecoder().decode(bytesB64.toString());
				Files.write(tmp, raw);
			}
			return new Source(tmp, true);
		} catch (RuntimeException | IOException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}
	}

	// ==================== list ====================

	private ACell handleList(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		try (Source src = resolveSource(ctx, input);
		     ZipFile zip = new ZipFile(src.path().toFile())) {
			AVector<ACell> entries = Vectors.empty();
			long count = 0;
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				ZipEntry ze = e.nextElement();
				count++;
				if (entries.count() < MAX_LISTED) {
					entries = entries.conj(Maps.of(
						"name", Strings.create(ze.getName()),
						"size", CVMLong.create(ze.getSize()),
						"compressedSize", CVMLong.create(ze.getCompressedSize()),
						"directory", CVMBool.create(ze.isDirectory()),
						"modified", CVMLong.create(ze.getTime())
					));
				}
			}
			return Maps.of(
				"entries", entries,
				"count", CVMLong.create(count),
				"truncated", CVMBool.create(count > entries.count())
			);
		}
	}

	// ==================== extract ====================

	private ACell handleExtract(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		AString destRoot = RT.ensureString(input.get(FIELD_DEST_ROOT));
		AString destPath = RT.ensureString(input.get(FIELD_DEST_PATH));
		if (destRoot == null) {
			throw new IllegalArgumentException("'destRoot' is required (the root to extract into)");
		}
		engine.requireAuthority(ctx, Strings.create(schemeResource("file", destRoot, destPath)), Capability.CRUD_WRITE);
		files().requireWritableRoot(destRoot.toString());
		Path destDir = files().resolve(ctx, destRoot.toString(), destPath != null ? destPath.toString() : null, false);
		Files.createDirectories(destDir);
		Path destReal = destDir.toRealPath();

		long entries = 0;
		long totalBytes = 0;
		AVector<ACell> files = Vectors.empty();

		try (Source src = resolveSource(ctx, input);
		     ZipFile zip = new ZipFile(src.path().toFile())) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				ZipEntry ze = e.nextElement();
				if (++entries > MAX_ENTRIES) {
					throw new IllegalArgumentException("Archive exceeds the entry cap (" + MAX_ENTRIES + ")");
				}
				// Zip-slip: resolve the entry inside the destination and verify it
				// cannot escape (normalize collapses any '..'), before touching disk.
				Path target = destReal.resolve(ze.getName()).normalize();
				if (!target.startsWith(destReal)) {
					throw new IllegalArgumentException("Archive entry escapes destination: " + ze.getName());
				}
				if (ze.isDirectory()) {
					Files.createDirectories(target);
					continue;
				}
				Path parent = target.getParent();
				if (parent != null) Files.createDirectories(parent);
				try (InputStream is = zip.getInputStream(ze);
				     OutputStream os = Files.newOutputStream(target,
						StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
					totalBytes += copyCapped(is, os, MAX_TOTAL_BYTES - totalBytes);
				}
				if (files.count() < MAX_LISTED) {
					files = files.conj(Strings.create(destReal.relativize(target).toString().replace('\\', '/')));
				}
			}
		}

		return Maps.of(
			"extracted", CVMLong.create(entries),
			"bytes", CVMLong.create(totalBytes),
			"destRoot", destRoot,
			"files", files,
			"truncated", CVMBool.create(entries > files.count())
		);
	}

	/** Copies at most {@code remaining} bytes; throws if the source would exceed it
	 *  (zip-bomb guard). Returns bytes copied. */
	private static long copyCapped(InputStream is, OutputStream os, long remaining) throws IOException {
		byte[] buf = new byte[8192];
		long copied = 0;
		int n;
		while ((n = is.read(buf)) != -1) {
			if (n > remaining) {
				throw new IllegalArgumentException("Archive exceeds the total-size cap (" + MAX_TOTAL_BYTES + " bytes)");
			}
			os.write(buf, 0, n);
			copied += n;
			remaining -= n;
		}
		return copied;
	}

	// ==================== zip ====================

	private ACell handleZip(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		AString srcRoot = RT.ensureString(input.get(FIELD_ROOT));
		if (srcRoot == null) {
			throw new IllegalArgumentException("'root' is required (the source root to zip from)");
		}
		// Sources: 'paths' (array) or a single 'path'.
		java.util.List<String> relPaths = new java.util.ArrayList<>();
		ACell pathsCell = input.get(FIELD_PATHS);
		if (pathsCell instanceof AVector<?> v) {
			for (long i = 0; i < v.count(); i++) {
				AString s = RT.ensureString(v.get(i));
				if (s != null) relPaths.add(s.toString());
			}
		}
		AString single = RT.ensureString(input.get(FIELD_PATH));
		if (single != null) relPaths.add(single.toString());
		if (relPaths.isEmpty()) {
			throw new IllegalArgumentException("Provide 'path' or 'paths' — the files/directories to zip");
		}

		AString destRoot = RT.ensureString(input.get(FIELD_DEST_ROOT));
		AString destPath = RT.ensureString(input.get(FIELD_DEST_PATH));
		boolean toFile = destRoot != null && destPath != null;

		// Read-check every source; collect (jailed path, entry name).
		java.util.List<Path> srcFiles = new java.util.ArrayList<>();
		java.util.List<String> srcEntryBases = new java.util.ArrayList<>();
		for (String rel : relPaths) {
			engine.requireAuthority(ctx,
				Strings.create(schemeResource("file", srcRoot, Strings.create(rel))), Capability.CRUD_READ);
			Path p = files().resolve(ctx, srcRoot.toString(), rel, true);
			srcFiles.add(p);
			// Entry name preserves the caller-supplied relative path (zip -r style).
			srcEntryBases.add(rel.replace('\\', '/').replaceAll("^/+", ""));
		}

		// Destination: a jailed file, or (default) a content-addressed asset.
		Path destFile = null;
		if (toFile) {
			engine.requireAuthority(ctx,
				Strings.create(schemeResource("file", destRoot, destPath)), Capability.CRUD_WRITE);
			files().requireWritableRoot(destRoot.toString());
			destFile = files().resolve(ctx, destRoot.toString(), destPath.toString(), false);
			Path parent = destFile.getParent();
			if (parent != null && !Files.exists(parent)) {
				throw new java.nio.file.NoSuchFileException("Parent directory does not exist: " + parent);
			}
		} else {
			engine.requireAuthority(ctx, Strings.create("a/"), Abilities.ASSET_STORE);
		}

		Path zipTmp = toFile ? null : Files.createTempFile("covia-zip-", ".zip");
		long[] entryCount = {0};
		try {
			Path out = toFile ? destFile : zipTmp;
			try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out,
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
				for (int i = 0; i < srcFiles.size(); i++) {
					addToZip(zos, srcFiles.get(i), srcEntryBases.get(i), entryCount);
				}
			}

			long size = Files.size(out);
			if (toFile) {
				return Maps.of(
					"entries", CVMLong.create(entryCount[0]),
					"bytes", CVMLong.create(size),
					"destRoot", destRoot,
					"destPath", destPath
				);
			}
			// Produce a content-addressed asset.
			byte[] zipBytes = Files.readAllBytes(zipTmp);
			ABlob content = Blob.wrap(zipBytes);
			AString nameCell = RT.ensureString(input.get(FIELD_NAME));
			String name = (nameCell != null) ? nameCell.toString() : "archive.zip";
			// The asset store keys on the metadata hash and requires content.sha256
			// to match the bytes — declare it, exactly as AssetAdapter.store does.
			AString sha = Strings.create(Hashing.sha256(zipBytes).toHexString());
			AMap<AString, ACell> meta = Maps.of(
				FIELD_NAME, Strings.create(name),
				Strings.create("mediaType"), Strings.create(ZIP_MEDIA_TYPE),
				Fields.CONTENT, Maps.of(
					Strings.create("mediaType"), Strings.create(ZIP_MEDIA_TYPE),
					Fields.SHA256, sha)
			);
			var hash = engine.storeUserAsset(JSON.printPretty(meta), content, ctx);
			// storeUserAsset writes into the user's /a/, so the URL must name the user.
			AString didUrl = ctx.getUserDID().append("/a/" + hash.toHexString());
			return Maps.of(
				"entries", CVMLong.create(entryCount[0]),
				"bytes", CVMLong.create(size),
				"asset", didUrl
			);
		} finally {
			if (zipTmp != null) Files.deleteIfExists(zipTmp);
		}
	}

	/** Adds a file or (recursively) a directory to the zip, entries named under
	 *  {@code entryBase}. */
	private void addToZip(ZipOutputStream zos, Path src, String entryBase, long[] count) throws IOException {
		if (Files.isDirectory(src)) {
			try (java.util.stream.Stream<Path> walk = Files.walk(src)) {
				java.util.List<Path> all = walk.sorted().toList();
				for (Path p : all) {
					String rel = src.relativize(p).toString().replace('\\', '/');
					String name = rel.isEmpty() ? entryBase : entryBase + "/" + rel;
					if (Files.isDirectory(p)) {
						if (!name.endsWith("/")) name = name + "/";
						writeEntry(zos, name, null, count);
					} else {
						writeEntry(zos, name, p, count);
					}
				}
			}
		} else {
			writeEntry(zos, entryBase, src, count);
		}
	}

	private void writeEntry(ZipOutputStream zos, String name, Path file, long[] count) throws IOException {
		if (++count[0] > MAX_ENTRIES) {
			throw new IllegalArgumentException("Zip exceeds the entry cap (" + MAX_ENTRIES + ")");
		}
		ZipEntry ze = new ZipEntry(name);
		zos.putNextEntry(ze);
		if (file != null) {
			try (InputStream is = Files.newInputStream(file)) {
				is.transferTo(zos);
			}
		}
		zos.closeEntry();
	}
}
