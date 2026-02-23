package covia.grid;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;

public class Assets {

	/**
	 * Parse an asset ID from a String. Can extract assetID from a DID with an asset ID at the end of a path
	 * @param asset
	 * @return Asset ID as a CVM hash
	 */
	public static Hash parseAssetID(String asset) {
		Hash h= Hash.parse(asset);
		if (h==null) {
			int b=asset.lastIndexOf("/");
			if (b>0) {
				return parseAssetID(asset.substring(b+1));
			}
		}

		if (h==null) throw new IllegalArgumentException("Unable to parse asset ID: "+asset);
		return h;
	}

	/**
	 * Computes the asset ID from a parsed metadata map.
	 * Uses the CAD3 value hash (SHA3-256 of canonical encoding),
	 * which is deterministic regardless of JSON formatting.
	 *
	 * @param metaMap Parsed metadata map
	 * @return CAD3 value hash of the metadata map
	 */
	public static Hash calcID(AMap<AString, ACell> metaMap) {
		return metaMap.getHash();
	}

	/**
	 * Computes the asset ID from a JSON metadata string.
	 * Parses to a map and returns the CAD3 value hash.
	 *
	 * @param meta JSON metadata string
	 * @return CAD3 value hash of the parsed metadata map
	 * @throws IllegalArgumentException if meta is not a valid JSON object
	 */
	public static Hash calcID(AString meta) {
		AMap<AString, ACell> metaMap = RT.ensureMap(JSON.parse(meta));
		if (metaMap == null) {
			throw new IllegalArgumentException("Metadata is not a valid JSON object");
		}
		return calcID(metaMap);
	}

	/**
	 * Computes the asset ID from a JSON metadata string.
	 * Convenience overload accepting a Java String.
	 *
	 * @param meta JSON metadata string
	 * @return CAD3 value hash of the parsed metadata map
	 * @throws IllegalArgumentException if meta is not a valid JSON object
	 */
	public static Hash calcID(String meta) {
		return calcID(Strings.create(meta));
	}

}
