package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.exceptions.TODOException;

/**
 * Adapter designed for pluggable operations for arbitrary JVM code
 */
public class JVMAdapter extends AAdapter {

	@Override
	public String getName() {
		return "jvm";
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input) {
		throw new TODOException();
		//failJobResult(null,Status.TODO);
	}

}
