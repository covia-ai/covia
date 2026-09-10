package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.Hash;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import covia.api.Fields;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.TestEngine;

/**
 * Adapter asset installation is fail-loud (#89 sweep): a missing or
 * unreadable adapter resource is a packaging bug, and a venue booting with
 * silently missing ops is broken — same policy as
 * {@code Engine.materialiseVOps}. {@code strictAssets: false} downgrades to
 * a warning, for test/debug scaffolding only.
 */
public class AAdapterInstallTest {

	/** Minimal adapter exposing the protected installAsset for the test. */
	private static class ProbeAdapter extends AAdapter {
		@Override public String getName() { return "probe"; }
		@Override public String getDescription() { return "install probe"; }
		@Override public java.util.concurrent.CompletableFuture<convex.core.data.ACell> invokeFuture(
				covia.venue.RequestContext ctx,
				convex.core.data.AMap<convex.core.data.AString, convex.core.data.ACell> meta,
				convex.core.data.ACell input) {
			return java.util.concurrent.CompletableFuture.completedFuture(null);
		}
		Hash probeInstall(String resourcePath) { return installAsset(resourcePath); }
		Hash probeInstall(AMap<AString, ACell> meta) { return installAsset(meta); }
		Hash probeModel(String path) {
			AMap<AString, ACell> meta = Maps.of(
				Fields.NAME, Strings.create(path),
				Fields.OPERATION, Maps.of(
					Fields.ADAPTER, Strings.create("probe:model"),
					Fields.READ_ONLY, CVMBool.FALSE));
			return installModel(path, meta);
		}
		static String probeFailure(Throwable error) { return describeFailure(error); }
	}

	@Test
	public void testOperationReadOnlyClassificationIsOptionalAtPublication() {
		ProbeAdapter adapter = new ProbeAdapter();
		adapter.engine = TestEngine.ENGINE;
		AMap<AString, ACell> unclassified = Maps.of(
			Fields.NAME, Strings.create("Unclassified operation"),
			Fields.OPERATION, Maps.of(Fields.ADAPTER, Strings.create("probe:unclassified")));
		assertNotNull(adapter.probeInstall(unclassified),
			"readOnly is an optional execution hint, not an adapter publication requirement");

		assertNotNull(adapter.probeInstall(Maps.of(
			Fields.NAME, Strings.create("Read operation"),
			Fields.OPERATION, Maps.of(
				Fields.ADAPTER, Strings.create("probe:read"),
				Fields.READ_ONLY, CVMBool.TRUE))));
		assertNotNull(adapter.probeInstall(Maps.of(
			Fields.NAME, Strings.create("Mutating operation"),
			Fields.OPERATION, Maps.of(
				Fields.ADAPTER, Strings.create("probe:write"),
				Fields.READ_ONLY, CVMBool.FALSE))));
	}

	/**
	 * A Covia operation may take any JSON value, but MCP and provider tool
	 * schemas require an object. An operation declaring a non-object input is
	 * still installed and published (best-effort as a tool), and the author is
	 * warned at install so the mismatch is visible where it can be fixed rather
	 * than discovered by a tool client.
	 */
	@Test
	public void testNonObjectInputSchemaWarnsAtInstallButStillPublishes() {
		ProbeAdapter adapter = new ProbeAdapter();
		adapter.engine = TestEngine.ENGINE;
		ch.qos.logback.classic.Logger log =
			(ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AAdapter.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured =
			new ch.qos.logback.core.read.ListAppender<>();
		captured.start();
		log.addAppender(captured);
		// The test logback config keeps the root at ERROR; the adapter logger
		// must admit WARN for the appender to see the event.
		ch.qos.logback.classic.Level previous = log.getLevel();
		log.setLevel(ch.qos.logback.classic.Level.WARN);
		try {
			// A string input: warned, but published — an operation is not a tool.
			assertNotNull(adapter.installAsset("probe/stringy", op("probe:stringy",
				Maps.of(Fields.TYPE, Strings.create("string")))));
			assertTrue(adapter.pendingCatalogEntries.containsKey("v/ops/probe/stringy"),
				"a non-object input is still published");
			assertEquals(1, warnings(captured).size(), warnings(captured).toString());
			assertTrue(warnings(captured).get(0).contains("v/ops/probe/stringy"), warnings(captured).get(0));
			assertTrue(warnings(captured).get(0).contains("\"string\""), warnings(captured).get(0));

			// Object, multi-type including object, and no declared input: silent.
			adapter.installAsset("probe/objecty", op("probe:objecty", Maps.of(Fields.TYPE, Fields.OBJECT)));
			adapter.installAsset("probe/either", op("probe:either", Maps.of(Fields.TYPE,
				convex.core.data.Vectors.of(Strings.create("string"), Fields.OBJECT))));
			adapter.installAsset("probe/untyped", op("probe:untyped", null));
			assertEquals(1, warnings(captured).size(), "only the string input warned: " + warnings(captured));

			// A type list that excludes object warns too.
			adapter.installAsset("probe/listy", op("probe:listy", Maps.of(Fields.TYPE,
				convex.core.data.Vectors.of(Strings.create("string"), Strings.create("array")))));
			assertEquals(2, warnings(captured).size(), warnings(captured).toString());
		} finally {
			log.detachAppender(captured);
			log.setLevel(previous);
		}
	}

	private static AMap<AString, ACell> op(String adapter, AMap<AString, ACell> input) {
		AMap<AString, ACell> operation = Maps.of(Fields.ADAPTER, Strings.create(adapter));
		if (input != null) operation = operation.assoc(Fields.INPUT, input);
		return Maps.of(Fields.NAME, Strings.create(adapter), Fields.OPERATION, operation);
	}

	private static java.util.List<String> warnings(
			ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured) {
		java.util.List<String> out = new java.util.ArrayList<>();
		for (ch.qos.logback.classic.spi.ILoggingEvent e : captured.list) {
			if (e.getLevel() == ch.qos.logback.classic.Level.WARN
					&& e.getFormattedMessage().contains("rather than an object")) {
				out.add(e.getFormattedMessage());
			}
		}
		return out;
	}

	@Test
	public void testFailureDescriptionIsNonBlankSingleLineAndBounded() {
		assertEquals("RuntimeException (no detail)",
			ProbeAdapter.probeFailure(new RuntimeException()));
		String detail = ProbeAdapter.probeFailure(
			new RuntimeException("first line\n" + "x".repeat(2000)));
		assertTrue(!detail.contains("\n"), detail);
		assertTrue(detail.startsWith("first line "), detail);
		assertTrue(detail.length() <= 1025, "bounded diagnostic was " + detail.length() + " chars");
	}

	@Test
	public void testBrokenResourceFailsLoudlyByDefault() {
		ProbeAdapter adapter = new ProbeAdapter();
		adapter.engine = TestEngine.ENGINE; // default config = strict
		IllegalStateException ex = assertThrows(IllegalStateException.class,
			() -> adapter.probeInstall("/no/such/resource.json"));
		assertTrue(ex.getMessage().contains("/no/such/resource.json"),
			"the failure must name the broken resource, got: " + ex.getMessage());
		assertTrue(ex.getMessage().contains(AAdapter.describeFailure(ex.getCause())),
			"and say why: " + ex.getMessage());
	}

	@Test
	public void testMalformedResourceFailureSaysWhy() {
		// The venue refused to start on a skill JSON with an unescaped control
		// character, and the only message was the resource path. The failure
		// must carry the parser's reason too, so a one-line log tells the
		// operator what to fix.
		ProbeAdapter adapter = new ProbeAdapter();
		adapter.engine = TestEngine.ENGINE;
		IllegalStateException ex = assertThrows(IllegalStateException.class,
			() -> adapter.probeInstall("/broken-asset.json"));
		assertTrue(ex.getMessage().startsWith("Failed to install adapter asset from /broken-asset.json: "),
			ex.getMessage());
		assertTrue(ex.getMessage().contains(AAdapter.describeFailure(ex.getCause())),
			"the cause's detail rides in the message: " + ex.getMessage());
		assertTrue(ex.getMessage().length() > "Failed to install adapter asset from /broken-asset.json: ".length() + 5,
			"the reason is not blank: " + ex.getMessage());
	}

	@Test
	public void testStrictAssetsFalseDowngradesToWarning() {
		Engine lenient = Engine.createTemp(Maps.of(
			Config.STRICT_ASSETS, convex.core.data.prim.CVMBool.FALSE,
			Config.NAME, Strings.create("lenient-probe")));
		try {
			ProbeAdapter adapter = new ProbeAdapter();
			adapter.engine = lenient;
			assertNull(adapter.probeInstall("/no/such/resource.json"),
				"strictAssets=false tolerates the broken resource (warn + null)");
		} finally {
			lenient.close();
		}
	}

	@Test
	public void testModelCatalogPathsAllowVendorIdsAndRejectTreeCollisions() {
		ProbeAdapter adapter = new ProbeAdapter();
		adapter.engine = TestEngine.ENGINE;
		assertNotNull(adapter.probeModel("openrouter/anthropic/claude-sonnet-5"));
		assertTrue(adapter.pendingCatalogEntries.containsKey(
			"v/models/openrouter/anthropic/claude-sonnet-5"));
		assertThrows(IllegalArgumentException.class,
			() -> adapter.probeModel("OpenRouter/model"));
		assertThrows(IllegalArgumentException.class,
			() -> adapter.probeModel("openrouter/model/"));
		adapter.probeModel("openai/gpt-5");
		assertThrows(IllegalArgumentException.class,
			() -> adapter.probeModel("openai/gpt-5/mini"));
	}
}
