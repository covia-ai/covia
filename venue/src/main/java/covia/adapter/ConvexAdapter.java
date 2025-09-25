package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;

public class ConvexAdapter extends AAdapter {

	@Override
	public String getName() {
		return "convex";
	}

	@Override
	public String getDescription() {
		return "Enables interactions with the Convex network, including on-chain CVM queries and transactions";
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input) {
		// TODO Auto-generated method stub
		return null;
	}

}
