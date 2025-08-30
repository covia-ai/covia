package covia.grid;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AString;

public abstract class Operation extends Asset {

	protected Operation(AString metadata) {
		super(metadata);
	}

	@Override
	public abstract CompletableFuture<Job> invoke(ACell input);
	
	
	@Override
	public abstract <T extends ACell> T run(ACell input);
}
