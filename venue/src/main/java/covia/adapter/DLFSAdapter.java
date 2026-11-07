package covia.adapter;

import static convex.dlfs.DLFSDriveManager.isValidDriveName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
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
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.dlfs.DLFSDrive;
import convex.lattice.fs.impl.DLFSLocal;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.exception.NotSupportedException;
import covia.exception.UnavailableException;
import covia.lattice.CapabilityChecker;
import covia.lattice.Covia;
import covia.utils.MimeUtils;
import covia.venue.Config;
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
	public static final String INVALID_DRIVE_NAME = "Invalid DLFS drive name";

	static final String DLFS_KEY_SECRET = "DLFS_KEY";
	public static final String DRIVE_UNAVAILABLE = "Unable to open or create DLFS drive: ";
	/** A drive that does not exist or was deleted, named by an operation that does not create one. */
	public static final String DRIVE_NOT_FOUND = "No such DLFS drive: ";
	public static final String DRIVE_NAME_CONFLICT = "'drive' and 'name' name different drives";

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
		log.debug("DLFS adapter installed with {} operations", pendingCatalogEntries.size());
	}

	// ==================== Key Management ====================

	/**
	 * Gets the user's DLFS keypair from the secret store, generating it if the
	 * user has none. Only paths that may create a drive call this; the others
	 * use {@link #existingUserKeyPair}.
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
		AKeyPair existing = existingUserKeyPair(ctx);
		if (existing != null) return existing;

		AString callerDID = ctx.getUserDID();
		User user = engine.getVenueState().users().ensure(callerDID);
		SecretStore.Keys encKey = SecretStore.keys(engine.getKeyPair());
		SecretStore secrets = user.secrets();

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

	/**
	 * Gets the user's DLFS keypair, or null if the user has none and so has no
	 * drives. Never writes: neither the user record nor the key is created.
	 */
	private AKeyPair existingUserKeyPair(RequestContext ctx) {
		// Drives belong to the user. An agent shares its owner's DLFS keypair
		// rather than minting one of its own, which would orphan it from every
		// drive the owner has.
		AString callerDID = ctx.getUserDID();
		if (callerDID == null) throw new AuthException("Authentication required for DLFS access");

		User user = engine.getVenueState().users().get(callerDID);
		if (user == null) return null;
		AString existing = user.secrets().decrypt(DLFS_KEY_SECRET, SecretStore.keys(engine.getKeyPair()));
		return (existing == null) ? null : AKeyPair.create(Blob.fromHex(existing.toString()));
	}

	// ==================== Drive Access ====================

	/**
	 * Opens an existing drive for the given DID, or returns null if it is
	 * absent or deleted: the {@code DLFSDriveManager.getDrive} contract WebDAV
	 * relies on. Public so WebDAV can access drives by identity string.
	 */
	public DLFSLocal getDriveForIdentity(String didString, String driveName) {
		DLFSDrive drive = liveDrive(RequestContext.of(Strings.create(didString)), driveName);
		return (drive == null) ? null : (DLFSLocal) drive.fileSystem();
	}

	/**
	 * Creates a drive for the given DID: true if it is new, false if it
	 * already exists. This is the {@code DLFSDriveManager.createDrive}
	 * contract WebDAV relies on.
	 */
	public boolean createDriveForIdentity(String didString, String driveName) {
		requireValidDriveName(driveName);
		AKeyPair dlfsKey = ensureUserKeyPair(RequestContext.of(Strings.create(didString)));
		return engine.getDLFSDrives(dlfsKey).createDrive(driveName) != null;
	}

	/**
	 * Connects a view of one of the user's live drives. A drive that is absent
	 * or deleted fails with {@link #DRIVE_NOT_FOUND}, leaving the user's drives
	 * and keys as they were: only {@code dlfs:createDrive}, WebDAV
	 * {@code MKCOL} and a component that names its own drive
	 * (Vault, a DLFS-backed file root, {@link #ensureDrive}) create one. Cheap — just a cursor view, no
	 * caching. The view keeps the caller's signing key isolated while reading
	 * the shared venue write clock for every mutation.
	 *
	 * <p>Public so other adapters (e.g. {@code FileAdapter} routing a
	 * {@code dlfs}-backed root) can obtain the same drive view the DLFS
	 * adapter operations themselves use.</p>
	 */
	public DLFSLocal getDrive(RequestContext ctx, String driveName) {
		DLFSDrive drive = liveDrive(ctx, driveName);
		if (drive == null) throw new NotFoundException(DRIVE_NOT_FOUND + driveName, driveName);
		return (DLFSLocal) drive.fileSystem();
	}

	/**
	 * Makes sure the drive a component names is live before the component writes
	 * at {@code ref} ({@code [<owner>/]dlfs/<drive>[/<path>]}): a messaging
	 * provider's or speech's media drive. Like a user's namespaces, the
	 * component's drive counts as present, so the write is authorised as
	 * {@code dlfs:write} authorises it, and the drive is then created as its
	 * owner. An operation on a drive its caller names never creates it.
	 */
	public void ensureDrive(RequestContext ctx, String ref) {
		DlfsFileRef fr = parseDlfsFileRef(ref);
		if (fr == null) throw new InvalidInputException(INVALID_DRIVE_NAME);
		RequestContext driveCtx = authorise(ctx, fr, Capability.CRUD_WRITE);
		try {
			authoriseCreate(ctx, driveCtx, fr, "");
			materialise(driveCtx, new DlfsFileRef(fr.ownerDID(), fr.drive(), ""));
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	/**
	 * Whether a write at {@code fr}, already authorised there, may create it.
	 * Creating an entry changes the directory that holds it, so a write at a
	 * path that does not exist is also authorised at the nearest directory above
	 * it that does.
	 *
	 * @param root the path, within the drive, of a component's own root, which
	 *        counts as present even before it is created, drive included. Null
	 *        for a drive its caller names, which must be live.
	 */
	private boolean authoriseCreate(RequestContext ctx, RequestContext driveCtx, DlfsFileRef fr,
			String root) throws IOException {
		DLFSDrive drive = liveDrive(driveCtx, fr.drive());
		String at = root;
		if (drive != null) {
			FileSystem fs = drive.fileSystem();
			Path driveRoot = fs.getRootDirectories().iterator().next();
			Path nearest = FileOperations.writeAuthority(
				resolvePath(fs, root == null ? "" : root), resolvePath(fs, fr.path()));
			at = driveRoot.relativize(nearest).toString();
		} else if (root == null) {
			throw new NotFoundException(DRIVE_NOT_FOUND + fr.drive(), fr.drive());
		}
		if (at.equals(fr.path())) return false;
		authorise(ctx, new DlfsFileRef(fr.ownerDID(), fr.drive(), at), Capability.CRUD_WRITE);
		return true;
	}

	/**
	 * Creates a component's root as its owner: the drive, if it is not live,
	 * and the directories on the root's path. Callers have authorised the
	 * component's write there.
	 */
	private void materialise(RequestContext driveCtx, DlfsFileRef root) throws IOException {
		DLFSDrive drive = liveDrive(driveCtx, root.drive());
		FileSystem fs = (drive != null) ? drive.fileSystem() : getOrCreateDrive(driveCtx, root.drive());
		Path base = resolvePath(fs, root.path());
		if (!Files.exists(base)) Files.createDirectories(base);
	}

	/**
	 * Creating a drive adds it to its owner's drives, so a drive that is not
	 * live needs crud/write over {@code [<owner>/]dlfs} as well as over itself.
	 */
	private void authoriseNewDrive(RequestContext ctx, DriveTarget target) {
		if (target.crossUser()) {
			String resource = dlfsResource(target.ownerDID(), null, null);
			if (!engine.crossUserAllows(ctx, Strings.create(resource), Capability.CRUD_WRITE)) {
				throw new AuthException(
					"Access denied: no " + Capability.CRUD_WRITE + " capability for " + resource);
			}
			return;
		}
		engine.requireAuthority(ctx, Strings.create(dlfsResource(null, null, null)), Capability.CRUD_WRITE);
	}

	/**
	 * Opens the user's drive, creating it if absent or deleted. Callers have
	 * authorised crud/write over the drive.
	 */
	private DLFSLocal getOrCreateDrive(RequestContext ctx, String driveName) {
		requireValidDriveName(driveName);
		DLFSDrive drive = engine.getDLFSDrives(ensureUserKeyPair(ctx)).getOrCreateDrive(driveName);
		if (drive == null) throw new UnavailableException(DRIVE_UNAVAILABLE + driveName);
		return (DLFSLocal) drive.fileSystem();
	}

	/**
	 * Storage size, in bytes, of a user's DLFS drives as stored, the markers
	 * deleted drives leave included. Never writes.
	 *
	 * @param userDID The user
	 * @return Bytes, zero for a user with no drives
	 */
	public long storageSize(AString userDID) {
		AKeyPair dlfsKey = existingUserKeyPair(RequestContext.of(userDID));
		if (dlfsKey == null) return 0;
		ACell drives = engine.getDLFSDrives(dlfsKey).cursor().getOrDefault(null);
		return (drives == null) ? 0 : Cells.storageSize(drives);
	}

	/** The user's live drive, or null if it is absent or deleted. Never writes. */
	private DLFSDrive liveDrive(RequestContext ctx, String driveName) {
		requireValidDriveName(driveName);
		AKeyPair dlfsKey = existingUserKeyPair(ctx);
		return (dlfsKey == null) ? null : engine.getDLFSDrives(dlfsKey).drive(driveName);
	}

	private static void requireValidDriveName(String driveName) {
		if (!isValidDriveName(driveName)) throw new InvalidInputException(INVALID_DRIVE_NAME);
	}

	/**
	 * Lists live drives using Convex's registry lifecycle semantics.
	 */
	private AVector<ACell> listDriveNames(RequestContext ctx) {
		AVector<ACell> names = Vectors.empty();
		AKeyPair dlfsKey = existingUserKeyPair(ctx);
		if (dlfsKey == null) return names;
		for (String name : engine.getDLFSDrives(dlfsKey).driveNames()) {
			names = names.conj(Strings.create(name));
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
			return CompletableFuture.failedFuture(new NotSupportedException("No DLFS sub-operation specified"));
		}
		return invokeOperation(ctx, subOp, RT.castMap(input), false);
	}

	/**
	 * Typed delegation point for adapters such as Vault that bind a drive of
	 * their own. The bound drive counts as present: a write there is authorised
	 * as usual, and then creates the drive as its owner if it is not live.
	 */
	CompletableFuture<ACell> invokeBoundOperation(RequestContext ctx, String subOp,
			AMap<AString, ACell> input) {
		return invokeOperation(ctx, subOp, input, true);
	}

	private CompletableFuture<ACell> invokeOperation(RequestContext ctx, String subOp,
			AMap<AString, ACell> input, boolean boundDrive) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return dispatch(ctx, subOp, input, boundDrive);
			} catch (IOException e) {
				throw FileOperations.failure(e);
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
	 * The resource is the {@code dlfs/<drive>/<path>} path form, canonicalised to the
	 * caller by the grant-scope check; a null grant scope (authenticated/internal) is
	 * unrestricted (no-op).
	 */
	private void requireDlfsCap(RequestContext ctx, String subOp, AMap<AString, ACell> input) {
		AString ability = abilityFor(subOp);
		if (ability == null) return;
		AString drive = RT.ensureString(RT.getIn(input, FIELD_DRIVE));
		String resource = dlfsResource(null, drive, RT.ensureString(RT.getIn(input, FIELD_PATH)));
		engine.requireAuthority(ctx, Strings.create(resource), ability);
	}

	/**
	 * A parsed DID-scoped DLFS file reference: {@code [<ownerDID>/]dlfs/<drive>/<path>}
	 * (the legacy {@code dlfs://<drive>/<path>} own-drive shorthand is normalised).
	 * {@code ownerDID} is null for the bare own-drive form.
	 */
	record DlfsFileRef(AString ownerDID, String drive, String path) {}

	/**
	 * An authorised DLFS path, exposed package-locally to FileAdapter.
	 *
	 * @param mayCreate whether a write was authorised to create the path; one
	 *        that was not may only change it
	 */
	record AuthorisedPath(Path root, Path path, String resource, String binaryUrl,
			boolean mayCreate) {}

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
			// DIDURL exposes a raw URL path. Decode once before normalisation and
			// authority checks; unlike form decoding, URI paths preserve literal '+'.
			rest = didURL.getPath();
			if (rest != null) rest = java.net.URI.create(rest).getPath();
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

	private static String normaliseRelativePath(String path) {
		if (path == null || path.isEmpty()) return "";
		String p = path;
		while (p.startsWith("/")) p = p.substring(1);
		if (p.indexOf('\\') >= 0) {
			throw new InvalidInputException("DLFS paths must use '/' separators: " + path);
		}
		java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
		for (String part : p.split("/", -1)) {
			if (part.isEmpty() || ".".equals(part)) continue;
			if ("..".equals(part)) {
				if (parts.isEmpty()) throw new InvalidInputException("Path escapes DLFS drive: " + path);
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
		return resolveAuthorisedPath(ctx, ref, ability, null);
	}

	/**
	 * @param componentRoot for a component that names its own drive (a
	 *        DLFS-backed file root), its root {@code dlfs/<drive>[/<subpath>]}. It
	 *        counts as present, and a write under it creates it, drive included,
	 *        as its owner. Null for a reference its caller names.
	 */
	AuthorisedPath resolveAuthorisedPath(RequestContext ctx, String ref, AString ability,
			String componentRoot) throws IOException {
		DlfsFileRef fr = parseDlfsFileRef(ref);
		if (fr == null) return null;

		RequestContext driveCtx = authorise(ctx, fr, ability);
		boolean mayCreate = false;
		if (Capability.CRUD_WRITE.equals(ability)) {
			DlfsFileRef root = componentRoot(fr, componentRoot);
			mayCreate = authoriseCreate(ctx, driveCtx, fr, root == null ? null : root.path());
			if (root != null) materialise(driveCtx, root);
		}
		FileSystem fs = getDrive(driveCtx, fr.drive());
		Path root = fs.getRootDirectories().iterator().next();
		Path path = resolvePath(fs, fr.path());
		String resource = dlfsResource(fr.ownerDID(), Strings.create(fr.drive()),
			fr.path().isEmpty() ? null : Strings.create(fr.path()));
		String binaryUrl = isCrossUser(ctx, fr) ? null : buildWebDAVUrl(fr.drive(), fr.path());
		return new AuthorisedPath(root, path, resource, binaryUrl, mayCreate);
	}

	/** The component root {@code fr} lies under, or null if there is none or it lies outside. */
	private static DlfsFileRef componentRoot(DlfsFileRef fr, String componentRoot) {
		DlfsFileRef root = parseDlfsFileRef(componentRoot);
		if (root == null || !root.drive().equals(fr.drive())
				|| !java.util.Objects.equals(root.ownerDID(), fr.ownerDID())) {
			return null;
		}
		String r = root.path();
		boolean within = r.isEmpty() || fr.path().equals(r) || fr.path().startsWith(r + "/");
		return within ? root : null;
	}

	/**
	 * Authorises {@code ability} on a DLFS file reference and returns the
	 * context its drive opens under: the caller's for an own drive, checked
	 * against the caller's grant scope; the owner's for another user's drive,
	 * once the caller's proofs cover {@code <owner>/dlfs/<drive>/<path>}.
	 */
	private RequestContext authorise(RequestContext ctx, DlfsFileRef fr, AString ability) {
		if (isCrossUser(ctx, fr)) {
			String resource = dlfsResource(fr.ownerDID(), Strings.create(fr.drive()),
				Strings.create(fr.path()));
			boolean ok = engine.crossUserAllows(ctx, Strings.create(resource), ability);
			if (!ok) throw new AuthException(
				"Access denied: no " + ability + " capability for " + resource);
			return RequestContext.of(fr.ownerDID());
		}
		engine.requireAuthority(ctx, Strings.create(
			dlfsResource(null, Strings.create(fr.drive()), Strings.create(fr.path()))),
			ability);
		return ctx;
	}

	private static boolean isCrossUser(RequestContext ctx, DlfsFileRef fr) {
		return fr.ownerDID() != null && !fr.ownerDID().equals(ctx.getUserDID());
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
			throw new NotFoundException("No file at DLFS path: " + ref, ref.toString());
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
		return writeContent(ref, data, ctx, false);
	}

	@Override
	public boolean createContent(AString ref, java.io.InputStream data, String contentType,
			RequestContext ctx) throws IOException {
		return writeContent(ref, data, ctx, true);
	}

	private boolean writeContent(AString ref, java.io.InputStream data, RequestContext ctx,
			boolean createOnly) throws IOException {
		if (ref == null) return false;
		AuthorisedPath target = resolveAuthorisedPath(ctx, ref.toString(), Capability.CRUD_WRITE);
		if (target == null) return false;
		Path path = target.path();
		// Authorised only to change it, so it existed: create never replaces
		if (createOnly && !target.mayCreate()) throw new FileAlreadyExistsException(ref.toString());
		// Interim (covia#629): a DLFS stream write is visible as it goes, so a
		// failed or cancelled upload leaves a cut-off file under the real name.
		// Convex's atomic conditional install (Convex-Dev/convex#790) would
		// publish the file only on a clean end of stream, with createOnly
		// checked at that moment.
		try (java.io.OutputStream os = Files.newOutputStream(path, createOnly
				? FileOperations.createOptions(path)
				: FileOperations.writeOptions(path, StandardOpenOption.TRUNCATE_EXISTING,
					target.mayCreate()))) {
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
			throw new InvalidInputException(
				"DLFS DID-URL drive reference must name a drive, e.g. did:key:.../<drive>");
		}
		if (!isValidDriveName(drive)) {
			throw new InvalidInputException(
				"DLFS DID-URL drive reference must name exactly one valid drive");
		}
		boolean cross = !ownerDID.equals(ctx.getUserDID());
		return new DriveTarget(ownerDID, drive, cross);
	}

	/**
	 * Every operation names its drive in {@code drive}: a bare name, or
	 * {@code <ownerDID>/<drive>} for another user's. For {@code createDrive} and
	 * {@code deleteDrive}, {@code name} remains an alias for a bare drive name,
	 * so it never reaches another user's drives.
	 */
	private static AMap<AString, ACell> driveFromName(String subOp, AMap<AString, ACell> input) {
		if (!"createDrive".equals(subOp) && !"deleteDrive".equals(subOp)) return input;
		AString name = RT.ensureString(input.get(FIELD_NAME));
		if (name == null) return input;
		requireValidDriveName(name.toString());
		AString drive = RT.ensureString(input.get(FIELD_DRIVE));
		if (drive == null) return input.assoc(FIELD_DRIVE, name);
		if (!drive.equals(name)) throw new InvalidInputException(DRIVE_NAME_CONFLICT);
		return input;
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
	private void authoriseCrossUser(RequestContext ctx, String subOp, DriveTarget target,
			AMap<AString, ACell> input) {
		AString ability = abilityFor(subOp);
		if (ability == null) {
			throw new NotSupportedException(
				"Cross-user DLFS access is not permitted for '" + subOp + "' on another user's drive");
		}
		AString pathCell = RT.ensureString(RT.getIn(input, FIELD_PATH));
		String resource = dlfsResource(target.ownerDID(), Strings.create(target.driveName()), pathCell);
		boolean ok = engine.crossUserAllows(ctx, Strings.create(resource), ability);
		if (!ok) throw new AuthException(
			"Access denied: no " + ability + " capability for " + resource);
	}

	/** Job-free read with the same capability checks as {@code dlfs:listDrives}. */
	public ACell listDrives(RequestContext ctx) throws IOException {
		return dispatch(ctx, "listDrives", Maps.empty(), false);
	}

	/** Job-free read with the same capability checks as {@code dlfs:list}. */
	public ACell listDirectory(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		return dispatch(ctx, "list", input, false);
	}

	/**
	 * @param boundDrive the drive is the calling adapter's own (Vault), so it
	 *                   counts as present and an authorised write creates it
	 */
	private ACell dispatch(RequestContext ctx, String subOp, AMap<AString, ACell> input,
			boolean boundDrive) throws IOException {
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
		input = driveFromName(subOp, input);
		DriveTarget target = parseDriveRef(ctx, input);
		if (target.driveName() != null && !isValidDriveName(target.driveName())) {
			throw new InvalidInputException(
				INVALID_DRIVE_NAME);
		}
		RequestContext driveCtx = ctx;
		if (target.ownerDID() != null) {
			// Drive named as a DID-URL (did:key:.../<drive>). Rewrite to the bare
			// drive name for the handlers, then authorise.
			input = input.assoc(FIELD_DRIVE, Strings.create(target.driveName()));
			if (target.crossUser()) {
				// Another user's drive: authorise via presented UCAN proofs against
				// the owner-scoped resource, then open it under the owner's identity.
				authoriseCrossUser(ctx, subOp, target, input);
				driveCtx = RequestContext.of(target.ownerDID());
			} else {
				// Own drive addressed explicitly by DID — normal own-scope check.
				requireDlfsCap(ctx, subOp, input);
			}
		} else {
			requireDlfsCap(ctx, subOp, input);
		}
		boolean mayCreate = false;
		if (Capability.CRUD_WRITE.equals(abilityFor(subOp)) && target.driveName() != null) {
			if ("createDrive".equals(subOp)) {
				if (liveDrive(driveCtx, target.driveName()) == null) authoriseNewDrive(ctx, target);
			} else {
				AString path = RT.ensureString(input.get(FIELD_PATH));
				DlfsFileRef fr = new DlfsFileRef(target.ownerDID(), target.driveName(),
					path == null ? "" : path.toString());
				// A bound drive is the calling adapter's own, so it counts as present
				DlfsFileRef root = (boundDrive && !target.crossUser())
					? new DlfsFileRef(target.ownerDID(), target.driveName(), "") : null;
				mayCreate = authoriseCreate(ctx, driveCtx, fr, root == null ? null : root.path());
				if (root != null) materialise(driveCtx, root);
			}
		}

		return switch (subOp) {
			case "listDrives" -> handleListDrives(driveCtx);
			case "createDrive" -> handleCreateDrive(driveCtx, input);
			case "deleteDrive" -> handleDeleteDrive(driveCtx, input);
			case "list" -> handleList(driveCtx, input);
			case "tree" -> handleTree(driveCtx, input);
			case "read" -> handleRead(driveCtx, input, target.crossUser());
			case "create" -> handleCreate(driveCtx, ctx, input, target, mayCreate);
			// handleWrite takes BOTH contexts: the drive opens under driveCtx (the
			// owner, for a cross-user write), but a caller-supplied `asset` ref is
			// resolved under the CALLER's own context — resolving caller input under
			// owner authority would let a drive-scoped grant read the owner's other
			// namespaces (confused deputy).
			case "write" -> handleWrite(driveCtx, ctx, input, false, mayCreate);
			case "append" -> handleWrite(driveCtx, ctx, input, true, mayCreate);
			case "mkdir" -> handleMkdir(driveCtx, input, mayCreate);
			case "delete" -> handleDelete(driveCtx, input);
			case "stat" -> handleStat(driveCtx, input);
			default -> throw new NotSupportedException("Unknown DLFS operation: " + subOp);
		};
	}

	// ==================== Drive Management ====================

	private ACell handleListDrives(RequestContext ctx) {
		AVector<ACell> names = listDriveNames(ctx);
		return Maps.of("drives", names);
	}

	private ACell handleCreateDrive(RequestContext ctx, AMap<AString, ACell> input) {
		String drive = requireDriveName(input);
		// Convex's drive registry creates the drive, or recreates a deleted one.
		getOrCreateDrive(ctx, drive);
		return Maps.of(
			"created", CVMBool.TRUE,
			"name", drive
		);
	}

	private ACell handleDeleteDrive(RequestContext ctx, AMap<AString, ACell> input) {
		String drive = requireDriveName(input);
		requireValidDriveName(drive);

		// A user without a DLFS key has no drives to delete.
		AKeyPair dlfsKey = existingUserKeyPair(ctx);
		if (dlfsKey != null) engine.getDLFSDrives(dlfsKey).deleteDrive(drive);

		return Maps.of("deleted", CVMBool.TRUE);
	}

	/** The bare drive name an operation names in {@code drive}. */
	private static String requireDriveName(AMap<AString, ACell> input) {
		AString drive = RT.ensureString(input.get(FIELD_DRIVE));
		if (drive == null) throw new InvalidInputException("'drive' is required");
		return drive.toString();
	}

	// ==================== File Operations ====================

	private DLFSLocal requireDrive(RequestContext ctx, AMap<AString, ACell> input) {
		return getDrive(ctx, requireDriveName(input));
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
		String driveName = requireDriveName(input);
		FileSystem fs = getDrive(ctx, driveName);

		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new InvalidInputException("'path' is required");

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
			AMap<AString, ACell> input, boolean append, boolean mayCreate) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new InvalidInputException("'path' is required");

		Path path = resolvePath(fs, pathCell.toString());
		return FileOperations.write(path, input, engine, assetCtx, append, mayCreate);
	}

	private ACell handleCreate(RequestContext ctx, RequestContext contentCtx,
			AMap<AString, ACell> input, DriveTarget target, boolean mayCreate) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		String pathArg = FileOperations.createPath(input);
		Path path = resolvePath(fs, pathArg);
		AMap<AString, ACell> result = RT.ensureMap(
			FileOperations.create(path, input, engine, contentCtx, mayCreate));
		AString drive = RT.ensureString(input.get(FIELD_DRIVE));
		String ref = dlfsResource(target.ownerDID(), drive, Strings.create(pathArg));
		return result.assoc(Fields.REF, Strings.create(ref));
	}

	private ACell handleMkdir(RequestContext ctx, AMap<AString, ACell> input,
			boolean mayCreate) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new InvalidInputException("'path' is required");
		Path path = resolvePath(fs, pathCell.toString());
		return FileOperations.mkdir(path, input, mayCreate);
	}

	private ACell handleDelete(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new InvalidInputException("'path' is required");
		Path path = resolvePath(fs, pathCell.toString());
		Path root = fs.getRootDirectories().iterator().next();
		return FileOperations.delete(path, root, input);
	}

	private ACell handleStat(RequestContext ctx, AMap<AString, ACell> input) throws IOException {
		FileSystem fs = requireDrive(ctx, input);
		AString pathCell = RT.ensureString(input.get(FIELD_PATH));
		if (pathCell == null) throw new InvalidInputException("'path' is required");

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
