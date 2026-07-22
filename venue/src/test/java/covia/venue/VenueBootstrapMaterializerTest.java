package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import covia.adapter.AAdapter;

/** Tests native, Job-free publication of venue-owned bootstrap state. */
public class VenueBootstrapMaterializerTest {

	@Test
	public void fullBootstrapPublishesCatalogAndInfoWithoutJobs() {
		Engine engine = Engine.createTemp(null);
		try {
			long jobsBefore = venueJobCount(engine);

			Engine.addDemoAssets(engine);

			assertEquals(jobsBefore, venueJobCount(engine),
				"catalog and v/info materialisation must not create bootstrap Jobs");
			assertNotNull(engine.resolvePath(
				Strings.create("v/ops/covia/write"), engine.venueContext()));
			assertEquals(engine.getDIDString(), engine.resolvePath(
				Strings.create("v/info/did"), engine.venueContext()));
		} finally {
			engine.close();
		}
	}

	@Test
	public void failedBootstrapDiscardsAllWritesMadeOnChildFork() {
		Engine engine = Engine.createTemp(null);
		try {
			engine.registerAdapter(new CatalogCollisionAdapter("collision-a"));
			engine.registerAdapter(new CatalogCollisionAdapter("collision-b"));
			long jobsBefore = venueJobCount(engine);

			IllegalStateException failure = assertThrows(IllegalStateException.class,
				engine::materialiseBootstrapState);

			assertTrue(failure.getMessage().contains("collision-a"), failure.getMessage());
			assertTrue(failure.getMessage().contains("collision-b"), failure.getMessage());
			assertNull(engine.resolvePath(
				Strings.create("v/ops/bootstrap-collision/echo"), engine.venueContext()),
				"the first adapter's child-fork write must not leak into live state");
			assertNull(engine.resolvePath(
				Strings.create("v/info/did"), engine.venueContext()),
				"venue information must not be partially published");
			assertEquals(jobsBefore, venueJobCount(engine),
				"a failed direct-lattice transaction must not create Jobs");
		} finally {
			engine.close();
		}
	}

	@Test
	public void failureDuringVenueInfoAlsoDiscardsCompletedCatalog() {
		Engine engine = Engine.createTemp(null);
		try {
			engine.registerAdapter(new FailingSummaryAdapter());
			long jobsBefore = venueJobCount(engine);

			IllegalStateException failure = assertThrows(IllegalStateException.class,
				engine::materialiseBootstrapState);

			assertTrue(failure.getMessage().contains("summary failure"), failure.getMessage());
			assertNull(engine.resolvePath(
				Strings.create("v/ops/bootstrap-summary/echo"), engine.venueContext()),
				"a completed catalog write must wait for venue-info validation");
			assertNull(engine.resolvePath(
				Strings.create("v/info/did"), engine.venueContext()),
				"venue-info fields written before the failure must remain private to the child fork");
			assertEquals(jobsBefore, venueJobCount(engine),
				"late bootstrap validation failure must not create Jobs");
		} finally {
			engine.close();
		}
	}

	private static long venueJobCount(Engine engine) {
		return engine.jobs().getJobs(engine.venueContext()).count();
	}

	private static final class CatalogCollisionAdapter extends AAdapter {

		private final String name;

		private CatalogCollisionAdapter(String name) {
			this.name = name;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public String getDescription() {
			return "Bootstrap collision test adapter";
		}

		@Override
		protected void installAssets() {
			installAsset("bootstrap-collision/echo", "/asset-examples/echoop.json");
		}

		@Override
		public CompletableFuture<ACell> invokeFuture(
				RequestContext context, AMap<AString, ACell> metadata, ACell input) {
			return CompletableFuture.completedFuture(input);
		}
	}

	private static final class FailingSummaryAdapter extends AAdapter {

		@Override
		public String getName() {
			return "failing-summary";
		}

		@Override
		public String getDescription() {
			throw new IllegalStateException("summary failure");
		}

		@Override
		protected void installAssets() {
			installAsset("bootstrap-summary/echo", "/asset-examples/echoop.json");
		}

		@Override
		public CompletableFuture<ACell> invokeFuture(
				RequestContext context, AMap<AString, ACell> metadata, ACell input) {
			return CompletableFuture.completedFuture(input);
		}
	}
}
