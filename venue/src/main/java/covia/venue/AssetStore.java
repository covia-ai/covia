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
import convex.lattice.ALatticeComponent;
import convex.lattice.IndexComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.grid.Assets;
import covia.lattice.CoviaValue;

/**
 * A content-addressed asset store: the venue's ({@code :assets}) or a user's
 * ({@code a/}), an index of asset records keyed by each asset's metadata hash.
 *
 * <p>Each asset record is a 3-element vector: [json, content, metaMap].
 * {@code get(hash)} reads one, and {@link #store} adds one under its hash.</p>
 */
public class AssetStore extends IndexComponent<ABlob, AVector<ACell>> implements TransactionalComponent<Index<ABlob, AVector<ACell>>, AssetStore> {
	@Override
	public AssetStore rebind(AForkableCursor<Index<ABlob, AVector<ACell>>> cursor) {
		return new AssetStore(cursor);
	}

	@Override
	public AssetStore withCursor(AForkableCursor<Index<ABlob, AVector<ACell>>> cursor) {
		return rebind(cursor);
	}

	/** An asset's hash and the positions below it name data in its record, read-only. */
	@Override
	public ALatticeComponent<?> resolve(AString[] segments, int start, int end) {
		return CoviaValue.within(this, segments, start, end);
	}


	/** Position of JSON metadata string in asset record vector */
	public static final long POS_JSON = 0;

	/** Position of binary content in asset record vector */
	public static final long POS_CONTENT = 1;

	/** Position of parsed metadata map in asset record vector */
	public static final long POS_META = 2;

	AssetStore(AForkableCursor<Index<ABlob, AVector<ACell>>> cursor) {
		super(cursor);
	}

	/**
	 * Stores an asset with the given metadata and optional content.
	 *
	 * @param meta JSON metadata string
	 * @param content Binary content (may be null)
	 * @return Content-addressed Hash ID for the asset
	 * @throws IllegalArgumentException if meta is not valid JSON
	 */
	public Hash store(AString meta, ACell content) {
		return store(meta, RT.ensureMap(JSON.parse(meta)), content);
	}

	/**
	 * Stores an asset from its metadata text and that text's parse, for a
	 * caller that has already parsed it. The two must match: the record keeps
	 * the text as given, and the map's hash is the asset's id.
	 *
	 * @param meta JSON metadata string
	 * @param metaMap The parse of {@code meta}, or null if it is not a JSON object
	 * @param content Binary content (may be null)
	 * @return Content-addressed Hash ID for the asset
	 * @throws IllegalArgumentException if metaMap is null
	 */
	Hash store(AString meta, AMap<AString, ACell> metaMap, ACell content) {
		if (metaMap == null) throw new IllegalArgumentException(Assets.NOT_AN_OBJECT);
		Hash id = metaMap.getHash();
		put(id, Vectors.create(meta, content, metaMap));
		return id;
	}

	/**
	 * Gets all assets as a map from Hash to record vector.
	 *
	 * @return Map of all assets; empty when there are none
	 */
	public AMap<ABlob, AVector<?>> getAll() {
		return RT.castMap(get());
	}

}
