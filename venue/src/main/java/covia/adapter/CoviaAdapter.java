package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import covia.venue.RequestContext;

public class CoviaAdapter extends AAdapter {

	@Override
	public String getName() {
		return "covia";
	}

	@Override
	public String getDescription() {
		return "Provides native access to internal services and capabilities in this Covia venue. " +
			   "Enables direct integration with the Covia ecosystem, including grid operations, venue management, and platform-specific features. " +
			   "Essential for building advanced Covia-native applications and leveraging the full power of the decentralised AI grid.";
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		// TODO: implement Covia-native operations
		return null;
	}

}
