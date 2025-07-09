package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;

/**
 * Adapter designed for pluggable operations for arbitrary JVM code
 */
public class JVMAdapter extends AAdapter {

	@Override
	public String getName() {
		return "jvm";
	}

	@Override
	public CompletableFuture<ACell> invoke(String operation, ACell meta, ACell input) {
		// TODO Auto-generated method stub
		return null;
	}

}
