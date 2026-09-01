package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.api.Fields;
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
			assertNotNull(engine.resolvePath(
				Strings.create("v/models/anthropic/claude-sonnet-5"), engine.venueContext()));
			assertNotNull(engine.resolvePath(Strings.create(
				"v/adapters/langchain/models/anthropic/claude-sonnet-5"), engine.venueContext()));
			assertEquals(engine.getDIDString(), engine.resolvePath(
				Strings.create("v/info/did"), engine.venueContext()));
		} finally {
			engine.close();
		}
	}

	@Test
	public void duplicateBootstrapCatalogPathsDoNotFail() {
		Engine engine = Engine.createTemp(null);
		try {
			engine.registerAdapter(new CatalogCollisionAdapter("collision-a"));
			engine.registerAdapter(new CatalogCollisionAdapter("collision-b"));
			long jobsBefore = venueJobCount(engine);

			engine.materialiseBootstrapState();

			assertNotNull(engine.getAdapter("collision-b"));
			assertEquals(jobsBefore, venueJobCount(engine),
				"direct-lattice publication must not create Jobs");
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

	@Test
	public void postBootstrapRegistrationReplacesOccupiedDeclaration() {
		Engine engine = Engine.createTemp(null);
		try {
			Engine.addDemoAssets(engine);
			engine.registerAdapter(new CatalogCollisionAdapter("late-module"));
			assertNotNull(engine.resolvePath(
				Strings.create("v/ops/bootstrap-collision/echo"), engine.venueContext()));

			assertDoesNotThrow(() ->
				engine.registerAdapter(new CatalogCollisionAdapter("late-module")));
			assertNotNull(engine.getAdapter("late-module"));
		} finally {
			engine.close();
		}
	}

	@Test
	public void modelLeafCannotAlsoBecomeNamespaceAcrossAdapters() {
		Engine engine = Engine.createTemp(null);
		try {
			engine.registerAdapter(new ModelCatalogAdapter("model-leaf", "shared/foo"));
			engine.registerAdapter(new ModelCatalogAdapter("model-child", "shared/foo/bar"));
			IllegalStateException failure = assertThrows(IllegalStateException.class,
				engine::materialiseBootstrapState);
			assertTrue(failure.getMessage().contains("existing leaf"), failure.getMessage());
		} finally {
			engine.close();
		}
	}

	private static long venueJobCount(Engine engine) {
		return engine.jobs().getJobs(engine.venueContext()).count();
	}

	private static final class ModelCatalogAdapter extends AAdapter {
		private final String name;
		private final String path;
		ModelCatalogAdapter(String name, String path) { this.name = name; this.path = path; }
		@Override public String getName() { return name; }
		@Override public String getDescription() { return "Model catalog collision test"; }
		@Override protected void installAssets() {
			installModel(path, Maps.of(
				Fields.NAME, Strings.create(path),
				Fields.OPERATION, Maps.of(
					Fields.ADAPTER, Strings.create("test:echo"),
					Fields.READ_ONLY, convex.core.data.prim.CVMBool.FALSE)));
		}
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx,
				AMap<AString, ACell> meta, ACell input) {
			return CompletableFuture.completedFuture(input);
		}
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
