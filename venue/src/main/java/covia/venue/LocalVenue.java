package covia.venue;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.did.DID;
import covia.grid.AContent;
import covia.grid.Asset;
import covia.grid.Job;
import covia.grid.Venue;

public class LocalVenue extends Venue {

	private Engine engine;

	public LocalVenue(Engine e) {
		this.engine=e;
	}

	public LocalVenue create(Engine e) {
		return new LocalVenue(e);
	}
	
	@Override
	public Asset getAsset(Hash assetID) {
		AString meta=engine.getMetadata(assetID);
		
		Asset asset= Asset.forString(meta);
		asset.setVenue(this);
		return asset;
	}
	
	public Engine getEngine() {
		return engine;
	}

	@Override
	public DID getDID() {
		return engine.getDID();
	}

	@Override
	public CompletableFuture<Job> invoke(Hash assetID, ACell input) {
		return CompletableFuture.completedFuture(engine.invokeOperation(assetID, input));
	}

	@Override
	public CompletableFuture<Job> invoke(String operation, ACell input) {
		if (operation == null) {
			throw new IllegalArgumentException("Operation must not be null");
		}
		Hash assetID = Hash.parse(operation);
		Job job;
		if (assetID != null) {
			job = engine.invokeOperation(assetID, input);
		} else {
			job = engine.invokeOperation(operation, input);
		}
		return CompletableFuture.completedFuture(job);
	}

	@Override
	protected AContent getAssetContent(Hash id) throws IOException {

		return engine.getContent(id);
	}

}
