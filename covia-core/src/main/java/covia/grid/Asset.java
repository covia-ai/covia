package covia.grid;

import convex.core.crypto.Hashing;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSONUtils;

/**
 * Class representing a Covia asset
 * 
 * An asset is normally associated with a Venue, which serves as the authoritative source for information regarding the asset
 * 
 */
public class Asset {

	/** The immutable asset ID */
	Hash id;
	
	/** The asset metadata string. May be null. */
	AString metaString;

	/** The venue from which the asset was obtained */
	Venue venue;

	/** The asset metadata. May be null if not yet cached */
	private AMap<AString, ACell> meta;
	
	private Asset(Hash id, AString metadata, Venue venue) {
		this.id=id;
		this.venue=venue;
		this.metaString=metadata;
	}
	
	public static Asset fromMeta(AMap<AString,ACell> meta) {
		Asset result= forString(JSONUtils.toJSONPretty(meta));
		result.meta=meta;
		return result;
	}
	
	public static Asset fromMeta(ACell metaCell) {
		AMap<AString,ACell> meta=RT.ensureMap(metaCell);
		if (meta==null) throw new IllegalArgumentException("Metadata for asset must me a map");
		return fromMeta(meta);
	}
	
	public static Asset forString(String metadata) {
		return forString(Strings.create(metadata));
	}
	
	public static Asset forString(AString metadata) {
		return new Asset(null,metadata,null);
	}
	
	@Override
	public boolean equals(Object other) {
		if (other instanceof Asset b) {
			return getID().equals(b.getID());
		}
		return false;
	}
	
	@Override
	public int hashCode() {
		// Any 32 bits from the hash will do
		return (int)(getID().longValue());
	}

	private Hash getID() {
		if (id!=null) return id;
		id=Hashing.sha256(getMetadata());
		return id;
	}

	/**
	 * Gets the asset metadata string
	 * @return Asset metadata
	 */
	public AString getMetadata() {
		if (metaString!=null) return metaString;
		return null;
	}
	
	public String toString() {
		return getID().toString();
	}

	/**
	 * Gets the Asset metadata as an immutable map
	 * @return Asset metadata map
	 */
	public AMap<AString,ACell> meta() {
		if (meta!=null) return meta;
		AMap<AString,ACell> result=RT.ensureMap(JSONUtils.parse(getMetadata()));
		if (result==null) throw new IllegalStateException("Cannot parse asset metadata: ");
		meta=result;
		return result;
	}
 }
