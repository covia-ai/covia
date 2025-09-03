package covia.venue;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.did.DID;
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
		return LocalAsset.create(assetID,this);
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

}
