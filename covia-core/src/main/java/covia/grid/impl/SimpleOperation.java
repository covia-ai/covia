package covia.grid.impl;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AString;
import covia.grid.Job;
import covia.grid.Operation;

/**
 * Simple local operation implementation.
 */
public class SimpleOperation extends Operation {

	protected SimpleOperation(AString metadata) {
		super(metadata);
		// TODO Auto-generated constructor stub
	}

	@Override
	public CompletableFuture<Job> invoke(ACell input) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <T extends ACell> T run(ACell input) {
		// TODO Auto-generated method stub
		return null;
	}

}
