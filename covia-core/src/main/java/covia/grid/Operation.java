package covia.grid;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.lang.RT;

/**
 * An Operation is an Asset that is known to be invocable.
 *
 * Operations have an "operation" field in their metadata specifying
 * the adapter and execution details.
 */
public class Operation extends Asset {

	protected Operation(Hash id, AString metadata) {
		super(id, metadata);
	}

	/**
	 * Create an Operation with a known ID and metadata string.
	 * @param id The asset ID (SHA256 hash of metadata)
	 * @param metadata The JSON metadata string
	 * @return Operation instance
	 */
	public static Operation create(Hash id, AString metadata) {
		return new Operation(id, metadata);
	}

	/**
	 * Create an Operation from a resolved Asset, if the asset is an operation.
	 *
	 * For local assets, checks that the metadata contains an "operation" field.
	 * For remote assets (venue set, no local metadata), assumes the reference
	 * is an operation since it's being invoked.
	 *
	 * @param asset The asset to convert
	 * @return Operation instance, or null if the asset is not an operation
	 */
	public static Operation from(Asset asset) {
		if (asset == null) return null;
		if (asset instanceof Operation op) return op;

		// Remote asset without local metadata — trust that it's an operation
		if (asset.venue != null && asset.metaString == null) {
			Operation op = new Operation(asset.id, null);
			op.venue = asset.venue;
			return op;
		}

		// Local asset — verify it has an operation field
		AMap<AString, ACell> meta = asset.meta();
		ACell opField = RT.getIn(meta, "operation");
		if (opField == null) return null;
		Operation op = new Operation(asset.id, asset.metaString);
		op.meta = meta;
		op.venue = asset.venue;
		return op;
	}
}
