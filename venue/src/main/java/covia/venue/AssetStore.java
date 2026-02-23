package covia.venue;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.lattice.cursor.ACursor;
import covia.grid.Assets;

/**
 * Cursor wrapper for the venue's asset store.
 *
 * <p>Wraps a lattice cursor at the {@code :assets} level
 * ({@code Index<AString, AVector<ACell>>} with content-addressed keys).
 * All writes propagate through the cursor chain and are automatically
 * signed at the {@code SignedCursor} boundary.</p>
 *
 * <p>Each asset record is a 3-element vector: [json, content, metaMap].</p>
 */
public class AssetStore {

	/** Position of JSON metadata string in asset record vector */
	public static final long POS_JSON = 0;

	/** Position of binary content in asset record vector */
	public static final long POS_CONTENT = 1;

	/** Position of parsed metadata map in asset record vector */
	public static final long POS_META = 2;

	private final ACursor<Index<AString, AVector<ACell>>> cursor;

	AssetStore(ACursor<Index<AString, AVector<ACell>>> cursor) {
		this.cursor = cursor;
	}

	/**
	 * Stores an asset with the given metadata and optional content.
	 *
	 * @param meta JSON metadata string
	 * @param content Binary content (may be null)
	 * @return Content-addressed Hash ID for the asset
	 * @throws IllegalArgumentException if meta is not valid JSON
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public Hash store(AString meta, ACell content) {
		AMap<AString, ACell> metaMap = RT.ensureMap(JSON.parse(meta));
		if (metaMap == null) {
			throw new IllegalArgumentException("Metadata is not a valid JSON object");
		}
		Hash id = Assets.calcID(meta);
		AVector<ACell> record = Vectors.create(meta, content, metaMap);
		cursor.updateAndGet(current -> {
			AMap m = RT.ensureMap(current);
			if (m == null) m = Index.none();
			return (Index) m.assoc(id, record);
		});
		return id;
	}

	/**
	 * Gets the raw record vector for an asset.
	 *
	 * @param id Asset Hash ID
	 * @return 3-element vector [json, content, metaMap], or null if not found
	 */
	public AVector<?> getRecord(Hash id) {
		AMap<ABlob, AVector<?>> all = getAll();
		return (all != null) ? all.get(id) : null;
	}

	/**
	 * Gets all assets as a map from Hash to record vector.
	 *
	 * @return Map of all assets, or null if uninitialised
	 */
	public AMap<ABlob, AVector<?>> getAll() {
		return RT.ensureMap(cursor.get());
	}

	/**
	 * Returns the number of stored assets.
	 *
	 * @return Asset count
	 */
	public long count() {
		AMap<?, ?> all = getAll();
		return (all != null) ? all.count() : 0;
	}

	/**
	 * Returns the underlying cursor for direct operations.
	 *
	 * @return Cursor at the assets level
	 */
	public ACursor<Index<AString, AVector<ACell>>> cursor() {
		return cursor;
	}
}
