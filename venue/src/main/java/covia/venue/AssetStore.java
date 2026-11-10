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
		AMap<AString, ACell> metaMap = RT.ensureMap(JSON.parse(meta));
		if (metaMap == null) {
			throw new IllegalArgumentException("Metadata is not a valid JSON object");
		}
		Hash id = metaMap.getHash();
		put(id, Vectors.create(meta, content, metaMap));
		return id;
	}

	/** The metadata of the asset with {@code id}, or null when this store holds none. */
	public AMap<AString, ACell> meta(Hash id) {
		AVector<ACell> record = get(id);
		if (record == null) return null;
		if (record.count() > POS_META) {
			AMap<AString, ACell> meta = RT.ensureMap(record.get(POS_META));
			if (meta != null) return meta;
		}
		AString json = RT.ensureString(record.get(POS_JSON));
		return (json == null) ? null : RT.ensureMap(JSON.parse(json));
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
