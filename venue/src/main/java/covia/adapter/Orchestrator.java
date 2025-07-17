package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;

public class Orchestrator extends AAdapter {

	@Override
	public String getName() {
		return "orchestrator";
	}

	@Override
	public CompletableFuture<ACell> invoke(String operation, ACell meta, ACell input) {
		// TODO Auto-generated method stub
		return null;
	}

}
