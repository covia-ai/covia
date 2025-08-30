package covia.grid;

import convex.core.data.AString;
import convex.core.data.Hash;

/**
 * Class representing a Covia asset
 * 
 * An asset is normally associated with a Venue, which serves as the authoritative source for information regarding the asset
 * 
 */
public class Asset {

	/** The immutable asset ID */
	final Hash id;
	
	/** The asset metadata string */
	AString metadata;

	/** The venue from which the asset was obtained */
	Venue venue;
	
	public Asset(Hash id, Venue venue) {
		this.id=id;
		this.venue=venue;
	}
}
