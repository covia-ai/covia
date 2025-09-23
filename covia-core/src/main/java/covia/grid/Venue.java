package covia.grid;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.did.DID;

public abstract class Venue {

	/**
	 * Gets the asset from the connected Venue
	 * @param assetID
	 * @return Asset instance
	 * @throws IOException 
	 */
	public Asset getAsset(String assetID) throws IOException {
		Hash h=Assets.parseAssetID(assetID);
		return getAsset(h);
	}
	
	/**
	 * Gets an  asset from the connected Venue
	 * @param assetID The asset ID
	 * @return Asset instance, or null if does not exist at the target venue
	 * @throws IOException If fetching the asset metadata failed
	 */
	public abstract Asset getAsset(Hash assetID) throws IOException;
	
	/**
	 * Get the DID for this Venue, or null if the Venue has no public DID
	 */
	public abstract DID getDID();

	/**
	 * Invokes an operation on the connected venue, returning a Job
	 * 
	 * @param assetID The AssetID of the operation to invoke
	 * @param input The input parameters for the operation as an ACell
	 * @return Future for the finished Job
	 */
	public abstract CompletableFuture<Job> invoke(Hash assetID, ACell input);

	public DID getAssetDID(Hash id) {
		return getDID().withPath("/a/"+id.toHexString());
	}
}
