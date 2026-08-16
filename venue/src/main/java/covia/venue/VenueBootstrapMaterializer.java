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
import convex.core.data.prim.CVMBool;
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
 *
 * <p>Besides the whole-venue bootstrap snapshot, the same machinery publishes
 * and retracts <em>single</em> adapters and modules for the runtime adapter
 * lifecycle ({@link Engine#enableAdapter}, {@link Engine#disableAdapter},
 * {@link Modules#load}, {@link Modules#unload}) — each change is one fork
 * sync, so the catalog never shows a half-applied adapter.</p>
 */
final class VenueBootstrapMaterializer {

	private static final AString K_W = Strings.intern("w");
	private static final AString K_GLOBAL = Strings.intern("global");
	private static final AString K_OPERATIONS = Strings.intern("operations");
	private static final AString K_KERNEL = Strings.intern("kernel");
	private static final AString K_MODULE = Strings.intern("module");
	private static final AString K_ADAPTERS = Strings.intern("adapters");
	private static final AString K_PATH = Strings.intern("path");
	private static final AString K_SHA256 = Strings.intern("sha256");

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

	/**
	 * Incrementally publishes ONE adapter after the bootstrap snapshot exists —
	 * the runtime {@code enable} / module-load path. Its catalog declarations
	 * and {@code v/info/adapters/<name>} summary land in one fork sync. A
	 * catalog path already occupied on the live catalog is a conflict and
	 * aborts the whole publication (nothing partial becomes visible).
	 */
	static void materialiseAdapter(Engine engine, AAdapter adapter) {
		VenueBootstrapMaterializer materializer = new VenueBootstrapMaterializer(engine);
		for (var declaration : adapter.pendingCatalogEntries.entrySet()) {
			String path = declaration.getKey();
			if (materializer.readVenuePath(path) != null) {
				throw new IllegalStateException("Catalog path /" + path
					+ " is already occupied; cannot enable adapter '" + adapter.getName() + "'");
			}
			materializer.writeCatalogDeclaration(adapter, path, declaration.getValue());
		}
		materializer.writeAndValidateVenuePath(
			"v/info/adapters/" + adapter.getName(), materializer.adapterSummary(adapter));
		materializer.publish();
	}

	/**
	 * Incrementally retracts ONE adapter — the runtime {@code disable} /
	 * module-unload path. Deletes exactly the catalog paths the adapter
	 * declared and its {@code v/info/adapters/<name>} summary, in one fork
	 * sync. Content-addressed assets stay in the venue CAS (they are inert
	 * without a dispatch target and may be re-materialised on enable).
	 */
	static void dematerialiseAdapter(Engine engine, AAdapter adapter) {
		VenueBootstrapMaterializer materializer = new VenueBootstrapMaterializer(engine);
		for (String path : adapter.pendingCatalogEntries.keySet()) {
			materializer.deleteVenuePath(path);
		}
		materializer.deleteVenuePath("v/info/adapters/" + adapter.getName());
		materializer.publish();
	}

	/** Publishes (or refreshes) one module's {@code v/info/modules/<name>} entry. */
	static void materialiseModule(Engine engine, Modules.LoadedModule module) {
		VenueBootstrapMaterializer materializer = new VenueBootstrapMaterializer(engine);
		materializer.writeAndValidateVenuePath(
			"v/info/modules/" + module.name(), moduleSummary(module));
		materializer.publish();
	}

	/** Retracts one module's {@code v/info/modules/<name>} entry. */
	static void dematerialiseModule(Engine engine, String moduleName) {
		VenueBootstrapMaterializer materializer = new VenueBootstrapMaterializer(engine);
		materializer.deleteVenuePath("v/info/modules/" + moduleName);
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

		// Loaded venue modules — same complete-snapshot discipline.
		writeAndValidateVenuePath("v/info/modules", Maps.empty());
		for (Modules.LoadedModule module : engine.getModules()) {
			writeAndValidateVenuePath("v/info/modules/" + module.name(), moduleSummary(module));
		}
	}

	private AMap<AString, ACell> adapterSummary(AAdapter adapter) {
		List<String> paths = new ArrayList<>(adapter.getOperationPaths());
		paths.sort(String::compareTo);
		AVector<ACell> operations = Vectors.empty();
		for (String path : paths) operations = operations.conj(Strings.create(path));
		AMap<AString, ACell> summary = Maps.of(
			Fields.NAME, Strings.create(adapter.getName()),
			Fields.DESCRIPTION, Strings.create(adapter.getDescription()),
			K_OPERATIONS, operations,
			K_KERNEL, CVMBool.of(engine.isKernelAdapter(adapter.getName())));
		Modules.LoadedModule module = engine.moduleOf(adapter.getName());
		if (module != null) summary = summary.assoc(K_MODULE, Strings.create(module.name()));
		return summary;
	}

	static AMap<AString, ACell> moduleSummary(Modules.LoadedModule module) {
		AVector<ACell> adapters = Vectors.empty();
		for (String name : module.adapterNames()) adapters = adapters.conj(Strings.create(name));
		AMap<AString, ACell> summary = Maps.of(
			Fields.NAME, Strings.create(module.name()),
			K_PATH, Strings.create(module.jar().toString()),
			K_ADAPTERS, adapters);
		if (module.sha256() != null) summary = summary.assoc(K_SHA256, Strings.create(module.sha256()));
		return summary;
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

	/** Deletes through the child cursor, then verifies the path is gone. */
	private void deleteVenuePath(String virtualPath) {
		CoviaAdapter.deletePathFromCursor(venueUserCursor, toVenueUserPath(virtualPath));
		if (readVenuePath(virtualPath) != null) {
			throw new IllegalStateException(
				"Bootstrap lattice delete validation failed at /" + virtualPath);
		}
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
