package covia.grid.impl;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Hash;
import covia.grid.Asset;
import covia.grid.Job;
import covia.grid.Venue;

public class RemoteAsset extends Asset {
	
	/** The venue from which the asset was obtained. May be null for a local asset */
	protected final Venue venue;


	public RemoteAsset(Hash id, AString metadata, Venue venue) {
		super(id, metadata);
		this.venue=venue;
	}

	
	@Override
	public CompletableFuture<Job> invoke(ACell input) {
		return venue.invoke(getID(), input);
	}
}
