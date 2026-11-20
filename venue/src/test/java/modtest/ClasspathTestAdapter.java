package modtest;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import covia.adapter.AAdapter;
import covia.venue.RequestContext;

/**
 * An adapter the venue's own classpath declares as a service, in the test
 * resources' {@code META-INF/services/covia.adapter.AAdapter}, as an
 * embedder's adapter jars declare theirs. No module jar ever carries it, so
 * {@link covia.venue.ModuleLoaderTest} can check that a module never claims it.
 */
public class ClasspathTestAdapter extends AAdapter {
	public static final String NAME = "classpathtest";

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public String getDescription() {
		return "Test adapter declared on the venue's own classpath";
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		return CompletableFuture.completedFuture(input);
	}
}
