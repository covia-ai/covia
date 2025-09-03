package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;

public class CoviaAdapter extends AAdapter {

	@Override
	public String getName() {
		return "covia";
	}
	
	@Override
	public String getDescription() {
		return "Provides native access to internal services and capabilities in this Covia venue. " +
			   "Enables direct integration with the Covia ecosystem, including grid operations, venue management, and platform-specific features. " +
			   "Essential for building advanced Covia-native applications and leveraging the full power of the decentralized AI grid.";
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input) {
		// TODO Auto-generated method stub
		return null;
	}

}
