package covia.grid;

import convex.core.crypto.Hashing;
import convex.core.data.AString;
import convex.core.data.Hash;

public class Assets {

	/**
	 * Parse an asset ID from a String. Can extra assetID from a DID with as asset ID at the end of a path
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
		
		if (h==null) throw new IllegalArgumentException("Unable to parse asset ID");
		return h;
	}
	
	public static Hash calcID(String meta) {
		return Hashing.sha256(meta.getBytes());
	}
	
	public static Hash calcID(AString meta) {
		return Hashing.sha256(meta.toBlob().getBytes());
	}

}
