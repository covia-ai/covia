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
	public String getDescription() {
		return "Enables distributed processing and resource sharing across the Covia network via grid operations. " +
			   "Provides access to remote venues, distributed job execution, and collaborative computing capabilities. " +
			   "Perfect for scaling computational tasks, leveraging distributed resources, and building resilient, distributed AI applications.";
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
