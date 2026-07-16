package modtest;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import covia.adapter.AAdapter;
import covia.venue.RequestContext;

/**
 * Minimal adapter packaged into a jar AT TEST RUNTIME by
 * {@link covia.venue.ModuleLoaderTest} to exercise the venue module loader
 * end-to-end. Deliberately OUTSIDE the covia.* package so the
 * split-delegation classloader loads it child-first from the module jar
 * (shared covia.* / convex.* prefixes stay parent-first).
 */
public class ModuleTestAdapter extends AAdapter {

	@Override
	public String getName() {
		return "modtest";
	}

	@Override
	public String getDescription() {
		return "Test adapter loaded from a venue module jar";
	}

	@Override
	protected void installAssets() {
		// This resource exists ONLY inside the module jar — proves adapter
		// resources resolve via the module classloader, not the venue's.
		installAsset("modtest/echo", "/modtest/echo.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		return CompletableFuture.completedFuture(input);
	}
}
