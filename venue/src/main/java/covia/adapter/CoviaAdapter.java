package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;

public class CoviaAdapter extends AAdapter {

	@Override
	public String getName() {
		return "covia";
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input) {
		// TODO Auto-generated method stub
		return null;
	}

}
