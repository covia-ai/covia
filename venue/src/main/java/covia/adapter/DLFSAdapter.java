package covia.adapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.auth.did.DIDURL;
import convex.auth.ucan.Capability;
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
import convex.core.lang.RT;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.fs.impl.DLFSLocal;
import covia.api.Fields;
import covia.lattice.CapabilityChecker;
import covia.lattice.Covia;
import covia.utils.MimeUtils;
import covia.venue.Config;
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
 *   <li>{@code dlfs:create} — create a new file from a content descriptor</li>
 *   <li>{@code dlfs:write} — write file content</li>
 *   <li>{@code dlfs:mkdir} — create directory</li>
 *   <li>{@code dlfs:delete} — delete file or directory</li>
 * </ul>
 */
public class DLFSAdapter extends AAdapter implements covia.venue.storage.ContentProvider {

	private static final Logger log = LoggerFactory.getLogger(DLFSAdapter.class);

	private static final String ASSETS_PATH = "/adapters/dlfs/";
	private static final String DLFS_KEY_SECRET = "DLFS_KEY";

	private static final AString FIELD_DRIVE = Strings.intern("drive");
	private static final AString FIELD_PATH = Strings.intern("path");
	private static final AString FIELD_NAME = Strings.intern("name");
	private static final AString FIELD_MODE = Strings.intern("mode");

	private static final AString K_WEBDAV = Strings.intern("webdav");
	private static final AString K_WINDOWS = Strings.intern("windows");

	/**
	 * DLFS's own facts for {@code v/info/adapters/dlfs}: whether the WebDAV
	 * mount is on and, if so, where — the URL, the mount path, and the UNC
	 * form the Windows WebDAV redirector wants ({@code \\host[@SSL][@port]\DavWWWRoot\dlfs\}).
	 * The venue base URL itself is venue-level ({@code v/info/url}).
	 */
	@Override
	public AMap<AString, ACell> info() {
		Config config = engine.config();
		boolean enabled = config.isWebDAVEnabled();
		AMap<AString, ACell> webdav = Maps.of(Config.ENABLED, CVMBool.of(enabled));
		if (enabled) {
			String baseUrl = config.getBaseUrl();
			webdav = webdav
				.assoc(Fields.URL, Strings.create(baseUrl + Config.WEBDAV_PATH))
				.assoc(Fields.PATH, Strings.create(Config.WEBDAV_PATH))
				.assoc(K_WINDOWS, Strings.create(windowsWebdavPath(baseUrl, Config.WEBDAV_PATH)));
		}
		return Maps.of(K_WEBDAV, webdav);
	}

	/**
	 * The UNC form the Windows WebDAV redirector wants for a WebDAV URL —
	 * {@code \\host[@SSL][@port]\DavWWWRoot\path\}: {@code @SSL} for https,
	 * {@code @port} for a non-default port, {@code DavWWWRoot} always. Append
	 * the drive name to mount one drive.
	 */
	public static String windowsWebdavPath(String baseUrl, String path) {
		java.net.URI uri = java.net.URI.create(baseUrl);
		boolean https = "https".equalsIgnoreCase(uri.getScheme());
		int port = uri.getPort();
		StringBuilder sb = new StringBuilder("\\\\").append(uri.getHost());
		if (https) sb.append("@SSL");
		if (port > 0 && port != (https ? 443 : 80)) sb.append('@').append(port);
		sb.append("\\DavWWWRoot");
		for (String seg : path.split("/")) {
			if (!seg.isEmpty()) sb.append('\\').append(seg);
		}
		return sb.append('\\').toString();
	}

	@Override
	public String getName() {
		return "dlfs";
	}

	@Override
	public String getDescription() {
		return "Decentralised Lattice File System — self-sovereign file storage with CRDT merge semantics. " +
			   "Manage per-user drives, read and write files, list directories. " +
			   "DLFS drives exist as an independent lattice region signed by the user's own key, " +
			   "enabling private, portable file vaults and document storage.";
	}

	@Override
	protected void installAssets() {
		installAsset("dlfs/list-drives",  ASSETS_PATH + "listDrives.json");
		installAsset("dlfs/create-drive", ASSETS_PATH + "createDrive.json");
		installAsset("dlfs/delete-drive", ASSETS_PATH + "deleteDrive.json");
		installAsset("dlfs/list",         ASSETS_PATH + "list.json");
		installAsset("dlfs/tree",         ASSETS_PATH + "tree.json");
		installAsset("dlfs/read",         ASSETS_PATH + "read.json");
		installAsset("dlfs/create",       ASSETS_PATH + "create.json");
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
		// Drives belong to the user. An agent shares its owner's DLFS keypair
		// rather than minting one of its own, which would orphan it from every
		// drive the owner has.
		AString callerDID = ctx.getUserDID();
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
	 * <p>The returned cursor carries only the caller's DLFS signing key. Its
	 * timestamp remains live; connected drive views obtain the venue's current
	 * write timestamp for every mutation.</p>
	 */
	private ALatticeCursor<?> getUserDLFSCursor(AKeyPair dlfsKey) {
		ALatticeCursor<Index<Keyword, ACell>> rootCursor = engine.getRootCursor();
		ALatticeCursor<?> dlfsCursor = rootCursor.path(Covia.DLFS);

		// Override only the signer. Delegating from the host policy preserves its
		// live clock, owner verifier and future-timestamp-skew limit (Convex
		// 0.8.14); constructing a fresh context here would silently discard them.
		LatticeContext lctx = dlfsCursor.getContext().withSigningKey(dlfsKey);
		dlfsCursor.setContext(lctx);

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
	 * caching. The view keeps the caller's signing key isolated while reading
	 * the shared venue write clock for every mutation.
	 *
	 * <p>Public so other adapters (e.g. {@code FileAdapter} routing a
	 * {@code dlfs}-backed root) can obtain the same drive view the DLFS
	 * adapter operations themselves use.</p>
	 */
	public DLFSLocal getDrive(RequestContext ctx, String driveName) {
		if (!isValidDriveName(driveName)) {
			throw new IllegalArgumentException(
				"DLFS drive name must be non-empty and contain no '/', '\\', or ':'");
		}
		AKeyPair dlfsKey = ensureUserKeyPair(ctx);
		ALatticeCursor<?> userCursor = getUserDLFSCursor(dlfsKey);
		// Convex 0.8.14 resolves each DLFS mutation timestamp through the cursor's
		// inherited live policy, so long-lived views see the current application
		// clock on every write (#387).
		return engine.connectDLFSDrive(userCursor, Strings.create(driveName));
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
	private Path resolvePath(FileSystem fs, String filePath) throws IOException {
		Path root = fs.getRootDirectories().iterator().next();
		return FileOperations.resolve(root, filePath, "DLFS drive", false);
	}

	// ==================== Invocation ====================

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String subOp = getSubOperation(meta);
		if (subOp == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("No DLFS sub-operation specified"));
		}
		return invokeBoundOperation(ctx, subOp, RT.castMap(input));
	}

	/** Typed delegation point for adapters such as Vault that bind a drive. */
	CompletableFuture<ACell> invokeBoundOperation(RequestContext ctx, String subOp,
			AMap<AString, ACell> input) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return dispatch(ctx, subOp, input);
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}, VIRTUAL_EXECUTOR);
	}

	/** The capability ability a DLFS sub-operation requests. It is matched against
	 *  the caller's grants generically via {@link Capability#covers} — a broader
	 *  grant ({@code crud}, {@code *}) covers these automatically; we never compare
	 *  {@code can} values by equality. */
	private static AString abilityFor(String subOp) {
		return switch (subOp) {
			case "list", "tree", "read", "stat", "listDrives" -> Capability.CRUD_READ;
			case "create", "write", "append", "mkdir", "createDrive" -> Capability.CRUD_WRITE;
			case "delete", "deleteDrive" -> Capability.CRUD_DELETE;
			default -> null;
		};
	}

	/**
	 * Builds a DLFS capability resource in plain DID-URL path form:
	 * {@code [<ownerDID>/]dlfs/<drive>[/<path>]}. A null {@code ownerDID} yields the
	 * bare own form ({@code dlfs/<drive>/…}, canonicalised to the caller by the
	 * grant-scope check); a non-null owner yields the cross-user form. This is a single
	 * well-formed DID URL (CAD038 DID-scoped path) — {@code /dlfs/} is a namespace
	 * segment alongside {@code /w/} and {@code /j/} — so {@code RootAuthorityPolicy}
	 * derives the owner with no special cases, unlike the old {@code dlfs://} scheme
	 * form whose embedded {@code ://} normalisers can collapse.
	 */
	private static String dlfsResource(AString ownerDID, AString drive, AString pathCell) {
		StringBuilder sb = new StringBuilder();
		if (ownerDID != null) sb.append(ownerDID).append('/');
		sb.append("dlfs");
		if (drive != null) sb.append('/').append(drive);
		if (pathCell != null) {
			String p = pathCell.toString();
			if (p.startsWith("/")) p = p.substring(1);
			if (!p.isEmpty()) sb.append('/').append(p);
		}
		return sb.toString();
	}

	/**
	 * Own-namespace capability enforcement co-located with the DLFS op dispatch.
	 * The resource is the {@code dlfs/<drive>/<path>} path form (drive named via
	 * {@code drive}, or {@code name} for drive-level ops), canonicalised to the
	 * caller by the grant-scope check; a null grant scope (authenticated/internal) is
	 * unrestricted (no-op).
	 */
	private void requireDlfsCap(RequestContext ctx, String subOp, AMap<AString, ACell> input) {
		AString ability = abilityFor(subOp);
		if (ability == null) return;
		AString drive = RT.ensureString(RT.getIn(input, FIELD_DRIVE));
		if (drive == null) drive = RT.ensureString(RT.getIn(input, FIELD_NAME));
		String resource = dlfsResource(null, drive, RT.ensureString(RT.getIn(input, FIELD_PATH)));
		engine.requireAuthority(ctx, Strings.create(resource), ability);
	}

	/**
	 * A parsed DID-scoped DLFS file reference: {@code [<ownerDID>/]dlfs/<drive>/<path>}
	 * (the legacy {@code dlfs://<drive>/<path>} own-drive shorthand is normalised).
	 * {@code ownerDID} is null for the bare own-drive form.
	 */
	record DlfsFileRef(AString ownerDID, String drive, String path) {}

	/** An authorised DLFS path, exposed package-locally to FileAdapter. */
	record AuthorisedPath(Path root, Path path, String resource, String binaryUrl) {}

	/**
	 * Parses a DID-scoped DLFS file reference, or returns null when {@code ref}
	 * is not DLFS-shaped (callers fall through to other resolution).
	 */
	static DlfsFileRef parseDlfsFileRef(String ref) {
		if (ref == null) return null;
		AString owner = null;
		String rest = ref;
		if (ref.startsWith("did:")) {
			if (!ref.contains("/dlfs/")) return null;
			DIDURL didURL = DIDURL.create(ref);
			owner = Strings.create(didURL.getDID().toString());
			rest = didURL.getPath();
			if (rest != null && rest.startsWith("/")) rest = rest.substring(1);
		}
		if (rest == null) return null;
		if (rest.startsWith("dlfs://")) rest = "dlfs/" + rest.substring("dlfs://".length());
		if (!rest.startsWith("dlfs/")) return null;
		rest = rest.substring("dlfs/".length());
		int slash = rest.indexOf('/');
		String drive = (slash < 0) ? rest : rest.substring(0, slash);
		String path = (slash < 0 || slash == rest.length() - 1)
			? "" : rest.substring(slash + 1);
		if (!isValidDriveName(drive)) return null;
		return new DlfsFileRef(owner, drive, normaliseRelativePath(path));
	}

	private static boolean isValidDriveName(String name) {
		return name != null && !name.isBlank()
			&& name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf(':') < 0;
	}

	private static String normaliseRelativePath(String path) {
		if (path == null || path.isEmpty()) return "";
		String p = path;
		while (p.startsWith("/")) p = p.substring(1);
		if (p.indexOf('\\') >= 0) {
			throw new IllegalArgumentException("DLFS paths must use '/' separators: " + path);
		}
		java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
		for (String part : p.split("/", -1)) {
			if (part.isEmpty() || ".".equals(part)) continue;
			if ("..".equals(part)) {
				if (parts.isEmpty()) throw new IllegalArgumentException("Path escapes DLFS drive: " + path);
				parts.removeLast();
			} else {
				parts.addLast(part);
			}
		}
		return String.join("/", parts);
	}

	/**
	 * Resolves a DID-scoped DLFS file reference to a drive {@link Path},
	 * enforcing exactly the checks the corresponding op enforces for
	 * {@code ability}: the caller's own grant scope for an own drive; the
	 * cross-user proof gate ({@link CapabilityChecker#proofsCover} on
	 * {@code <owner>/dlfs/<drive>/<path>}) for another user's drive. Returns
	 * null when {@code ref} is not DLFS-shaped; throws (never degrades) on
	 * denial.
	 */
	AuthorisedPath resolveAuthorisedPath(RequestContext ctx, String ref, AString ability)
			throws IOException {
		DlfsFileRef fr = parseDlfsFileRef(ref);
		if (fr == null) return null;

		RequestContext driveCtx = ctx;
		boolean cross = fr.ownerDID() != null && !fr.ownerDID().equals(ctx.getUserDID());
		if (cross) {
			String resource = dlfsResource(fr.ownerDID(), Strings.create(fr.drive()),
				Strings.create(fr.path()));
			boolean ok = engine.crossUserAllows(ctx, Strings.create(resource), ability);
			if (!ok) throw new IllegalStateException(
				"Access denied: no " + ability + " capability for " + resource);
			driveCtx = RequestContext.of(fr.ownerDID());
		} else {
			engine.requireAuthority(ctx, Strings.create(
				dlfsResource(null, Strings.create(fr.drive()), Strings.create(fr.path()))),
				ability);
		}

		FileSystem fs = getDrive(driveCtx, fr.drive());
		Path root = fs.getRootDirectories().iterator().next();
		Path path = resolvePath(fs, fr.path());
		String resource = dlfsResource(fr.ownerDID(), Strings.create(fr.drive()),
			fr.path().isEmpty() ? null : Strings.create(fr.path()));
		String binaryUrl = cross ? null : buildWebDAVUrl(fr.drive(), fr.path());
		return new AuthorisedPath(root, path, resource, binaryUrl);
	}

	// ========== ContentProvider: DLFS as reference-addressed content storage ==========

	/**
	 * {@link covia.venue.storage.ContentProvider} read: a DID-scoped DLFS path
	 * resolves to <b>lazy</b> content over the drive file (streamed on demand,
	 * never materialised here — see {@link covia.grid.impl.PathContent}), under
	 * the same checks as {@code dlfs:read}. Content type comes from the file
	 * name (no I/O); consumers that need byte-sniffing do it after choosing to
	 * materialise. Null for non-DLFS reference shapes; throws on denial or a
	 * missing file.
	 */
	@Override
	public covia.venue.storage.ContentProvider.Resolved getContent(AString ref,
			RequestContext ctx) throws IOException {
		if (ref == null) return null;
		AuthorisedPath target = resolveAuthorisedPath(ctx, ref.toString(), Capability.CRUD_READ);
		if (target == null) return null;
		Path path = target.path();
		if (!Files.exists(path) || Files.isDirectory(path)) {
			throw new IllegalArgumentException("No file at DLFS path: " + ref);
		}
		return new covia.venue.storage.ContentProvider.Resolved(
			covia.grid.impl.PathContent.of(path), MimeUtils.guessByName(ref.toString()));
	}

	/**
	 * {@link covia.venue.storage.ContentProvider} write: stores bytes at a
	 * DID-scoped DLFS path under the same checks as {@code dlfs:write} — the
	 * caller's own grant scope ({@code crud/write}) for an own drive, the
	 * cross-user proof gate for another user's (the mutation lands under the
	 * owner's key, custodial). False for non-DLFS reference shapes.
	 */
	@Override
	public boolean putContent(AString ref, java.io.InputStream data, String contentType,
			RequestContext ctx) throws IOException {
		if (ref == null) return false;
		AuthorisedPath target = resolveAuthorisedPath(ctx, ref.toString(), Capability.CRUD_WRITE);
		if (target == null) return false;
		Path path = target.path();
		try (java.io.OutputStream os = Files.newOutputStream(path,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE)) {
			data.transferTo(os);
		}
		return true;
	}

	/**
	 * The resolved target of a DLFS file op: either the caller's own drive
	 * ({@code crossUser == false}) or another user's drive addressed via a
	 * DID-URL {@code drive} reference (e.g. {@code did:key:zAlice/docs}).
	 */
	private record DriveTarget(AString ownerDID, String driveName, boolean crossUser) {}

	/**
	 * Parses the {@code drive} field. A DID-URL value ({@code did:...}) targets
	 * another user's drive: the DID identifies the owner and the DID-URL path
	 * component names the drive (the file path stays in {@code path}). Anything
	 * else is the caller's own drive.
	 */
	private static DriveTarget parseDriveRef(RequestContext ctx, AMap<AString, ACell> input) {
		AString driveCell = RT.ensureString(input.get(FIELD_DRIVE));
		if (driveCell == null) return new DriveTarget(null, null, false);
		String s = driveCell.toString();
		if (!s.startsWith("did:")) return new DriveTarget(null, s, false);
		DIDURL didURL = DIDURL.create(s);
		AString ownerDID = Strings.create(didURL.getDID().toString());
		String drive = didURL.getPath();
		if (drive != null && drive.startsWith("/")) drive = drive.substring(1);
		if (drive == null || drive.isEmpty()) {
			throw new IllegalArgumentException(
				"DLFS DID-URL drive reference must name a drive, e.g. did:key:.../<drive>");
		}
		if (!isValidDriveName(drive)) {
			throw new IllegalArgumentException(
				"DLFS DID-URL drive reference must name exactly one valid drive");
		}
		boolean cross = !ownerDID.equals(ctx.getUserDID());
		return new DriveTarget(ownerDID, drive, cross);
	}

	/**
	 * Authorises a cross-user DLFS access: the caller must present UCAN proofs
	 * covering the owner-scoped resource {@code <ownerDID>/dlfs/<drive>/<path>}
	 * for the ability the op requires ({@code crud/read} for reads,
	 * {@code crud/write} for writes, {@code crud/delete} for deletes). Reads,
	 * writes and deletes are all permitted when the proof authorises them — a
	 * mutation is applied to the owner's drive under the owner's key (custodial;
	 * caller identity is recorded on the job). Mirrors
	 * {@code CoviaAdapter.resolveDIDURL} for {@code /w/} cross-user access — the
	 * shared {@link CapabilityChecker#proofsCover} check is the single grant
	 * gate. Signatures/chains were already verified at transport ingress.
	 */
	private void authorizeCrossUser(RequestContext ctx, String subOp, DriveTarget target,
			AMap<AString, ACell> input) {
		AString ability = abilityFor(subOp);
		if (ability == null) {
			throw new IllegalStateException(
				"Cross-user DLFS access is not permitted for '" + subOp + "' on another user's drive");
		}
		AString pathCell = RT.ensureString(RT.getIn(input, FIELD_PATH));
		String resource = dlfsResource(target.ownerDID(), Strings.create(target.driveName()), pathCell);
		boolean ok = engine.crossUserAllows(ctx, Strings.create(resource), ability);
		if (!ok) throw new IllegalStateException(
			"Access denied: no " + ability + " capability for " + resource);
	}

	/** Job-free REST reads (#253) call this directly for listDrives/list,
	 *  reusing the same capability checks as the operation form. */
	public ACell dispatch(RequestContext ctx, String subOp, AMap<AString, ACell> input) throws IOException {
		if (input == null) input = Maps.empty();
		// content.fileName is a destination shorthand for create. Materialise it
		// before capability resolution so the authorised resource is the actual
		// file, not the drive root.
		if ("create".equals(subOp) && input.get(FIELD_PATH) == null) {
			input = input.assoc(FIELD_PATH, Strings.create(FileOperations.createPath(input)));
		}
		AString rawPath = RT.ensureString(input.get(FIELD_PATH));
		if (rawPath != null) {
			input = input.assoc(FIELD_PATH, Strings.create(normaliseRelativePath(rawPath.toString())));
		}
		DriveTarget target = parseDriveRef(ctx, input);
		if (target.driveName() != null && !isValidDriveName(target.driveName())) {
			throw new IllegalArgumentException(
				"DLFS drive name must be non-empty and contain no '/', '\\', or ':'");
		}
		RequestContext driveCtx = ctx;
		if (target.ownerDID() != null) {
			// Drive named as a DID-URL (did:key:.../<drive>). Rewrite to the bare
			// drive name for the handlers, then authorise.
			input = input.assoc(FIELD_DRIVE, Strings.create(target.driveName()));
			if (target.crossUser()) {
				// Another user's drive: authorise via presented UCAN proofs against
				// the owner-scoped resource, then open it under the owner's identity.
				authorizeCrossUser(ctx, subOp, target, input);
				driveCtx = RequestContext.of(target.ownerDID());
			} else {
				// Own drive addressed explicitly by DID — normal own-scope check.
				requireDlfsCap(ctx, subOp, input);
			}
		} else {
			requireDlfsCap(ctx, subOp, input);
		}

		return switch (subOp) {
			case "listDrives" -> handleListDrives(driveCtx);
			case "createDrive" -> handleCreateDrive(driveCtx, input);
			case "deleteDrive" -> handleDeleteDrive(driveCtx, input);
			case "list" -> handleList(driveCtx, input);
			case "tree" -> handleTree(driveCtx, input);
			case "read" -> handleRead(driveCtx, input, target.crossUser());
			case "create" -> handleCreate(driveCtx, ctx, input, target);
			// handleWrite takes BOTH contexts: the drive opens under driveCtx (the
			// owner, for a cross-user write), but a caller-supplied `asset` ref is
			// resolved under the CALLER's own context — resolving caller input under
			// owner authority would let a drive-scoped grant read the owner's other
			// namespaces (confused deputy).
			case "write" -> handleWrite(driveCtx, ctx, input, false);
			case "append" -> handleWrite(driveCtx, ctx, input, true);
			case "mkdir" -> handleMkdir(driveCtx, input);
			case "delete" -> handleDelete(driveCtx, input);
			case "stat" -> handleStat(driveCtx, input);
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
		if (!isValidDriveName(name.toString())) {
			throw new IllegalArgumentException(
				"DLFS drive name must be non-empty and contain no '/', '\\', or ':'");
		}

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
		return FileOperations.list(dir);
	}

	private ACell handleTree(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		Path dir = resolvePath(fs, pathCell != null ? pathCell.toString() : null);

		return FileOperations.tree(dir, input);
	}

	private ACell handleRead(RequestContext ctx, AMap<AString, ACell> input,
			boolean crossUser) throws IOException {
		AString driveCell = RT.ensureString(input.get(FIELD_DRIVE));
		if (driveCell == null) throw new IllegalArgumentException("'drive' is required");
		String driveName = driveCell.toString();
		FileSystem fs = getDrive(ctx, driveName);

		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new IllegalArgumentException("'path' is required");

		AString modeCell = RT.ensureString(input.get(FIELD_MODE));
		String mode = modeCell != null ? modeCell.toString() : "auto";

		Path path = resolvePath(fs, pathCell.toString());
		return FileOperations.read(path, mode,
			crossUser ? null : buildWebDAVUrl(driveName, pathCell.toString()), engine, input);
	}

	/**
	 * @param ctx      drive context — the identity whose drive is written (the
	 *                 owner for an authorised cross-user write)
	 * @param assetCtx the CALLER's context, used to resolve a caller-supplied
	 *                 {@code asset} reference under the caller's own authority
	 */
	private ACell handleWrite(RequestContext ctx, RequestContext assetCtx,
			AMap<AString, ACell> input, boolean append) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new IllegalArgumentException("'path' is required");

		Path path = resolvePath(fs, pathCell.toString());
		return FileOperations.write(path, input, engine, assetCtx, append);
	}

	private ACell handleCreate(RequestContext ctx, RequestContext contentCtx,
			AMap<AString, ACell> input, DriveTarget target) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		String pathArg = FileOperations.createPath(input);
		Path path = resolvePath(fs, pathArg);
		AMap<AString, ACell> result = RT.ensureMap(
			FileOperations.create(path, input, engine, contentCtx));
		AString drive = RT.ensureString(input.get(FIELD_DRIVE));
		String ref = dlfsResource(target.ownerDID(), drive, Strings.create(pathArg));
		return result.assoc(Fields.REF, Strings.create(ref));
	}

	private ACell handleMkdir(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new IllegalArgumentException("'path' is required");
		Path path = resolvePath(fs, pathCell.toString());
		return FileOperations.mkdir(path, input);
	}

	private ACell handleDelete(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new IllegalArgumentException("'path' is required");
		Path path = resolvePath(fs, pathCell.toString());
		Path root = fs.getRootDirectories().iterator().next();
		return FileOperations.delete(path, root, input);
	}

	private ACell handleStat(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new IllegalArgumentException("'path' is required");

		Path path = resolvePath(fs, pathCell.toString());
		return FileOperations.stat(path, null);
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
