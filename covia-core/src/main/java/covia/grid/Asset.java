package covia.grid;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import convex.core.crypto.Hashing;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSONUtils;
import covia.exception.JobFailedException;

/**
 * Class representing a Covia asset
 * 
 * An asset is typically associated with a Venue, which serves as the authoritative source for information regarding the asset
 * 
 */
public class Asset {

	/** The immutable asset ID */
	protected Hash id;
	
	/** The asset metadata string. May be null. */
	AString metaString;


	/** The asset metadata. May be null if not yet cached */
	private AMap<AString, ACell> meta;
	
	protected Asset(Hash id, AString metadata) {
		this.id=id;
		this.metaString=metadata;
	}
	
	protected Asset(AString metadata) {
		this(null,metadata);
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
	
	/**
	 * Create an asset from a metadata string
	 * @param metadata
	 * @return Asset instance
	 */
	public static Asset forString(String metadata) {
		return forString(Strings.create(metadata));
	}
	
	/**
	 * Create an asset from a metadata string
	 * @param metadata
	 * @return Asset instance
	 */
	public static Asset forString(AString metadata) {
		return new Asset(null,metadata);
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

	/**
	 * Get the Asset ID
	 * @return Asset ID as a Hash
	 */
	public Hash getID() {
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
		throw new IllegalStateException("No asset metadata available");
	}
	
	public String toString() {
		return getID().toString();
	}
	
	/**
	 * Get the content of this asset
	 * @return Asset content
	 */
	public AContent getContent() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Gets the Asset metadata as an immutable map
	 * @return Asset metadata map. Guaranteed not to be null
	 */
	public AMap<AString,ACell> meta() {
		if (meta!=null) return meta;
		AString metadata=getMetadata();
		try {
			AMap<AString,ACell> result=RT.ensureMap(JSONUtils.parse(metadata));
			if (result==null) throw new IllegalStateException("Bad asset metadata: "+metadata);
			meta=result;
			return result;
		} catch (Exception e) {
			throw new IllegalStateException("Bad asset metadata: "+metadata);
		}
	}
	
	/**
	 * Invokes this asset as an operation. Typically this will trigger an execution on the underlying venue.
	 * 
	 * @param input Input to the operation, typically a Map of parameters
	 * @return Job representing the execution
	 */
	public CompletableFuture<Job> invoke(ACell input) {
		throw new UnsupportedOperationException();
	}
	
	/**
	 * Runs this asset as an operation. Typically this will trigger an execution on the underlying venue.
	 * @param <T> Return type of operation, typically a Map of outputs
	 * @param input Input to the operation, typically a Map of parameters
	 * @return Output of the execution
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> T run(ACell input) {
		Job job;
		try {
			job = invoke(input).get();
		} catch (InterruptedException | ExecutionException e) {
			throw new JobFailedException(e);
		}
		return (T) job.getOutput();
	}
 }
