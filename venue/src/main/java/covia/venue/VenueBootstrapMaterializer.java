package covia.venue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.util.JSON;
import convex.lattice.cursor.ALatticeCursor;
import covia.adapter.AAdapter;
import covia.adapter.CoviaAdapter;
import covia.api.Fields;

/**
 * Materialises venue-owned bootstrap state as one native lattice transaction.
 *
 * <p>All catalog and {@code v/info} writes execute against a child
 * {@link VenueState} fork using the canonical cursor path writer. A successful
 * run synchronises that fork into the Engine's live fork once; a failure simply
 * discards it. No operation dispatch, Job records, or parallel catalog model is
 * involved.</p>
 */
final class VenueBootstrapMaterializer {

	private static final AString K_W = Strings.intern("w");
	private static final AString K_GLOBAL = Strings.intern("global");
	private static final AString K_OPERATIONS = Strings.intern("operations");

	private final Engine engine;
	private final VenueState bootstrapFork;
	private final ALatticeCursor<ACell> venueUserCursor;
	private final List<String> adapterNames;
	private final Map<String, String> catalogOwners = new HashMap<>();
	private final long startedAt;

	private VenueBootstrapMaterializer(Engine engine) {
		this.engine = engine;
		this.bootstrapFork = engine.getVenueState().fork();
		this.venueUserCursor = bootstrapFork.users().ensure(engine.getDIDString()).cursor();
		ArrayList<String> names = new ArrayList<>(engine.getAdapterNames());
		names.sort(String::compareTo);
		this.adapterNames = List.copyOf(names);
		this.startedAt = System.currentTimeMillis();
	}

	/** Materialises catalog and venue information, then commits them together. */
	static void materialiseBootstrapState(Engine engine) {
		VenueBootstrapMaterializer materializer = new VenueBootstrapMaterializer(engine);
		materializer.writeAdapterCatalog();
		materializer.writeVenueInformation();
		materializer.publish();
	}

	/** Backward-compatible catalog-only entry point, committed as one fork sync. */
	static void materialiseAdapterCatalog(Engine engine) {
		VenueBootstrapMaterializer materializer = new VenueBootstrapMaterializer(engine);
		materializer.writeAdapterCatalog();
		materializer.publish();
	}

	/** Backward-compatible venue-info-only entry point, committed as one fork sync. */
	static void materialiseVenueInformation(Engine engine) {
		VenueBootstrapMaterializer materializer = new VenueBootstrapMaterializer(engine);
		materializer.writeVenueInformation();
		materializer.publish();
	}

	/** Writes every adapter declaration through ordinary cursors on the child fork. */
	private void writeAdapterCatalog() {
		for (String adapterName : adapterNames) {
			AAdapter adapter = engine.getAdapter(adapterName);
			if (adapter == null) continue;
			for (var declaration : adapter.pendingCatalogEntries.entrySet()) {
				writeCatalogDeclaration(adapter, declaration.getKey(), declaration.getValue());
			}
		}
	}

	private void writeCatalogDeclaration(AAdapter adapter, String path, Hash assetHash) {
		validateCatalogPath(path);
		String previousOwner = catalogOwners.putIfAbsent(path, adapter.getName());
		if (previousOwner != null) {
			throw new IllegalStateException("Duplicate bootstrap catalog path /" + path
				+ " declared by adapters '" + previousOwner + "' and '" + adapter.getName() + "'");
		}

		AString metadataJson = adapter.getInstalledAssets().get(assetHash);
		if (metadataJson == null) {
			throw new IllegalStateException("Adapter '" + adapter.getName()
				+ "' catalog path /" + path + " references an uninstalled asset " + assetHash);
		}
		ACell metadata = JSON.parse(metadataJson);
		if (!(metadata instanceof AMap)) {
			throw new IllegalStateException("Adapter '" + adapter.getName()
				+ "' catalog metadata at /" + path + " is not a JSON object");
		}

		writeAndValidateVenuePath(path, metadata);

		// Reverse index: the catalog store (venueState.assets(), what GET
		// /api/v1/assets iterates) is keyed purely by content hash, with no
		// path recorded — so once a caller resolves one of these assets by
		// hash alone, its catalog name is otherwise unrecoverable. This is an
		// independent write of the same (path, hash) pair already computed
		// above, not derived from the hashed metadata itself — the metadata
		// body must stay exactly what its hash certifies, so this lives
		// outside it, the same way ETag does.
		writeAndValidateVenuePath("v/info/catalog/" + assetHash.toHexString(), Strings.create(path));
	}

	private static void validateCatalogPath(String path) {
		if (path == null || !(path.startsWith("v/ops/")
				|| path.startsWith("v/test/ops/")
				|| path.startsWith("v/agents/templates/")
				|| path.startsWith("v/skills/"))) {
			throw new IllegalStateException("Unsupported bootstrap catalog path: " + path);
		}
	}

	/** Writes the complete introspection snapshot into the same child fork. */
	private void writeVenueInformation() {
		AString name = engine.config().getName();
		if (name != null) writeAndValidateVenuePath("v/info/name", name);
		writeAndValidateVenuePath("v/info/did", engine.getDIDString());
		writeAndValidateVenuePath("v/info/version", Strings.create(Engine.jarVersion()));
		writeAndValidateVenuePath("v/info/started", CVMLong.create(startedAt));
		writeAndValidateVenuePath("v/info/protocols", Vectors.of(
			(ACell) Strings.create("rest"),
			(ACell) Strings.create("mcp"),
			(ACell) Strings.create("a2a")));

		// Adapter summaries are a complete bootstrap-owned snapshot. Reset this
		// subtree on the transaction fork so adapters removed since the previous
		// boot cannot leave stale introspection entries.
		writeAndValidateVenuePath("v/info/adapters", Maps.empty());
		for (String adapterName : adapterNames) {
			AAdapter adapter = engine.getAdapter(adapterName);
			if (adapter == null) continue;
			writeAndValidateVenuePath(
				"v/info/adapters/" + adapterName, adapterSummary(adapter));
		}
	}

	private static AMap<AString, ACell> adapterSummary(AAdapter adapter) {
		List<String> paths = new ArrayList<>(adapter.getOperationPaths());
		paths.sort(String::compareTo);
		AVector<ACell> operations = Vectors.empty();
		for (String path : paths) operations = operations.conj(Strings.create(path));
		return Maps.of(
			Fields.NAME, Strings.create(adapter.getName()),
			Fields.DESCRIPTION, Strings.create(adapter.getDescription()),
			K_OPERATIONS, operations);
	}

	/** Writes through the child cursor, then verifies the child view immediately. */
	private void writeAndValidateVenuePath(String virtualPath, ACell value) {
		CoviaAdapter.writePathToCursor(venueUserCursor, toVenueUserPath(virtualPath), value);
		ACell actual = readVenuePath(virtualPath);
		if (!Objects.equals(value, actual)) {
			throw new IllegalStateException(
				"Bootstrap lattice write validation failed at /" + virtualPath);
		}
	}

	private ACell readVenuePath(String virtualPath) {
		return CoviaAdapter.readPath(venueUserCursor, toVenueUserPath(virtualPath));
	}

	/** Rewrites v/x to the venue user's physical w/global/x path. */
	private static ACell[] toVenueUserPath(String virtualPath) {
		ACell[] virtualKeys = CoviaAdapter.parseStringPath(virtualPath);
		if (virtualKeys.length < 2 || !"v".equals(virtualKeys[0].toString())) {
			throw new IllegalArgumentException("Venue bootstrap path must start with v/: " + virtualPath);
		}
		ACell[] physicalKeys = new ACell[virtualKeys.length + 1];
		physicalKeys[0] = K_W;
		physicalKeys[1] = K_GLOBAL;
		System.arraycopy(virtualKeys, 1, physicalKeys, 2, virtualKeys.length - 1);
		return physicalKeys;
	}

	/** The only publication point: one merge from child fork to live Engine fork. */
	private void publish() {
		bootstrapFork.sync();
	}

}
