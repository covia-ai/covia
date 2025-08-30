package covia.venue;

import convex.core.data.AString;
import convex.core.data.Hash;
import covia.grid.Asset;
import covia.grid.impl.RemoteAsset;

public class LocalAsset extends RemoteAsset {

	protected LocalAsset(Hash id, AString metadata, LocalVenue venue) {
		super(id, metadata, venue);
	}

	public static LocalAsset create(Hash assetID, LocalVenue venue) {
		return new LocalAsset(assetID,null,venue);
	}

}
