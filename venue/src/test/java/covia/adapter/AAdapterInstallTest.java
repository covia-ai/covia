package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
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
	}

	@Test
	public void testBrokenResourceFailsLoudlyByDefault() {
		ProbeAdapter adapter = new ProbeAdapter();
		adapter.engine = TestEngine.ENGINE; // default config = strict
		IllegalStateException ex = assertThrows(IllegalStateException.class,
			() -> adapter.probeInstall("/no/such/resource.json"));
		assertTrue(ex.getMessage().contains("/no/such/resource.json"),
			"the failure must name the broken resource, got: " + ex.getMessage());
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
}
