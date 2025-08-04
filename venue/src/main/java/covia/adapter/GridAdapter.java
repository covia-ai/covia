package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;

/**
 * Adapter to call the Covia grid as a proxy
 */
public class GridAdapter extends AAdapter {

	@Override
	public String getName() {
		return "grid";
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input) {
		String[] ss=operation.split(":");
		String gridOp=ss[1];
		switch(gridOp) {
		
		case "run":
			 //TDO: find right params?
			
		default: throw new IllegalArgumentException("Unrecognised grid operation: "+gridOp);
		}
	}

}
