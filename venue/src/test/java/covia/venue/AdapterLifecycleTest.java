package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.auth.ucan.UCAN;
import covia.adapter.AAdapter;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Status;

/**
 * Runtime adapter lifecycle: enable / disable / reconfigure on a live venue,
 * boot-time disabling via {@code adapters.<name>.enabled}, the kernel set,
 * and the venue-owned {@code v/ops/venue/*} operations that drive it.
 *
 * <p>Uses throwaway engines — disabling adapters on the shared TestEngine
 * would race every other test.</p>
 */
public class AdapterLifecycleTest {

	private static final String ECHO = "v/test/ops/echo";

	private static Engine boot(AMap<AString, ACell> config) {
		AMap<AString, ACell> users = Maps.of(Config.USERS, Maps.of(Config.AUTO_CREATE, true));
		Engine engine = Engine.createTemp(config == null ? users : users.merge(config));
		Engine.addDemoAssets(engine);
		return engine;
	}

	private static ACell venueRead(Engine engine, String path) {
		return engine.resolvePath(Strings.create(path), engine.venueContext());
	}

	private static ACell asVenue(Engine engine, String op, AMap<AString, ACell> input) throws Exception {
		return engine.jobs().invokeInternal(op, input, engine.venueContext()).get(10, TimeUnit.SECONDS);
	}

	// ========== Engine-level ==========

	@Test
	public void testDisableEnableRoundTrip() throws Exception {
		Engine engine = boot(null);
		try {
			RequestContext ctx = RequestContext.of(Strings.create("did:test:lifecycle"));
			assertNotNull(engine.resolveAsset(Strings.create(ECHO), ctx));
			assertNotNull(venueRead(engine, "v/info/adapters/test"));
			assertEquals(CVMBool.FALSE, RT.getIn(venueRead(engine, "v/info/adapters/test"), "kernel"));
			assertEquals(CVMBool.TRUE, RT.getIn(venueRead(engine, "v/info/adapters/covia"), "kernel"));

			assertTrue(engine.disableAdapter("test"));
			assertFalse(engine.disableAdapter("test"), "second disable is a no-op");
			assertNull(engine.getAdapter("test"));
			assertTrue(engine.getDisabledAdapterNames().contains("test"));
			assertNull(engine.resolveAsset(Strings.create(ECHO), ctx),
				"catalog path must be retracted while disabled");
			assertNull(venueRead(engine, "v/info/adapters/test"),
				"introspection entry must be retracted while disabled");
			// Other adapters' catalog entries are untouched
			assertNotNull(engine.resolveAsset(Strings.create("v/ops/covia/read"), ctx));

			assertTrue(engine.enableAdapter("test"));
			assertFalse(engine.enableAdapter("test"), "second enable is a no-op");
			assertNotNull(engine.getAdapter("test"));
			assertNotNull(engine.resolveAsset(Strings.create(ECHO), ctx));
			assertNotNull(venueRead(engine, "v/info/adapters/test"));

			Job job = engine.jobs().invokeOperation(ECHO,
				Maps.of(Strings.create("value"), Strings.create("back")), ctx);
			job.awaitResult(10000);
			assertEquals(Status.COMPLETE, job.getStatus(), String.valueOf(job.getErrorMessage()));
		} finally {
			engine.close();
		}
	}

	@Test
	public void testKernelAdaptersAreProtected() {
		Engine engine = boot(null);
		try {
			for (String kernel : Engine.KERNEL_ADAPTERS) {
				assertTrue(engine.isKernelAdapter(kernel));
				assertThrows(IllegalArgumentException.class, () -> engine.disableAdapter(kernel), kernel);
				assertThrows(IllegalArgumentException.class, () -> engine.removeAdapter(kernel), kernel);
				assertNotNull(engine.getAdapter(kernel), kernel + " must still be registered");
			}
			assertThrows(IllegalArgumentException.class, () -> engine.disableAdapter("no-such-adapter"));
			assertThrows(IllegalArgumentException.class, () -> engine.enableAdapter("no-such-adapter"));
		} finally {
			engine.close();
		}
	}

	@Test
	public void testBootDisabledByConfigAndLazyEnable() throws Exception {
		Engine engine = boot(Maps.of(Config.ADAPTERS,
			Maps.of(Strings.create("test"), Maps.of(Config.ENABLED, false))));
		try {
			RequestContext ctx = RequestContext.of(Strings.create("did:test:bootdisabled"));
			assertNull(engine.getAdapter("test"));
			assertTrue(engine.getDisabledAdapterNames().contains("test"));
			assertNull(engine.resolveAsset(Strings.create(ECHO), ctx));
			assertNull(venueRead(engine, "v/info/adapters/test"));

			// Enable installs lazily and publishes into the live catalog
			assertTrue(engine.enableAdapter("test"));
			assertNotNull(engine.resolveAsset(Strings.create(ECHO), ctx));
			assertNotNull(venueRead(engine, "v/info/adapters/test"));
			Job job = engine.jobs().invokeOperation(ECHO,
				Maps.of(Strings.create("value"), Strings.create("lazy")), ctx);
			job.awaitResult(10000);
			assertEquals(Status.COMPLETE, job.getStatus(), String.valueOf(job.getErrorMessage()));
		} finally {
			engine.close();
		}
	}

	@Test
	public void testKernelCannotBeDisabledByConfig() {
		Engine engine = Engine.createTemp(Maps.of(Config.ADAPTERS,
			Maps.of(Strings.create("covia"), Maps.of(Config.ENABLED, false))));
		try {
			IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> Engine.addDemoAssets(engine));
			assertTrue(e.getMessage().contains("Kernel adapter 'covia'"), e.getMessage());
		} finally {
			engine.close();
		}
	}

	/** Records every configure() call and declines configs with {@code reject: true}. */
	private static final class ConfigurableAdapter extends AAdapter {
		AMap<AString, ACell> seen;
		int calls;
		@Override public String getName() { return "configurable"; }
		@Override public String getDescription() { return "test"; }
		@Override public boolean configure(AMap<AString, ACell> config, boolean strict) {
			calls++;
			if (RT.bool(config.get(Strings.create("reject")))) return false;
			seen = config;
			return true;
		}
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx,
				AMap<AString, ACell> meta, ACell input) {
			return CompletableFuture.completedFuture(seen);
		}
	}

	@Test
	public void testConfigureOverlayAndHook() {
		AMap<AString, ACell> staticCfg = Maps.of(Strings.create("level"), Strings.create("boot"));
		Engine engine = boot(Maps.of(Config.ADAPTERS,
			Maps.of(Strings.create("configurable"), staticCfg)));
		try {
			ConfigurableAdapter adapter = new ConfigurableAdapter();
			engine.registerAdapter(adapter);
			assertEquals(1, adapter.calls, "configure runs once at registration");
			assertEquals(staticCfg, adapter.seen);
			assertEquals(staticCfg, engine.adapterConfig("configurable"));

			AMap<AString, ACell> runtime = Maps.of(Strings.create("level"), Strings.create("live"));
			engine.configureAdapter("configurable", runtime);
			assertEquals(2, adapter.calls);
			assertEquals(runtime, adapter.seen);
			assertEquals(runtime, engine.adapterConfig("configurable"),
				"runtime override overlays the static config");
			assertEquals(staticCfg, engine.config().getAdapterConfig("configurable"),
				"static config object is untouched");

			// A declined configuration changes nothing
			assertThrows(IllegalArgumentException.class, () -> engine.configureAdapter(
				"configurable", Maps.of(Strings.create("reject"), CVMBool.TRUE)));
			assertEquals(runtime, engine.adapterConfig("configurable"));
			assertEquals(runtime, adapter.seen);

			// Reconfiguration reaches a disabled adapter too
			engine.disableAdapter("configurable");
			AMap<AString, ACell> later = Maps.of(Strings.create("level"), Strings.create("parked"));
			engine.configureAdapter("configurable", later);
			assertEquals(later, adapter.seen);

			assertThrows(IllegalArgumentException.class,
				() -> engine.configureAdapter("no-such-adapter", Maps.empty()));
		} finally {
			engine.close();
		}
	}

	@Test
	public void testDeclinedConfigurationParksAdapterAtRegistration() {
		Engine engine = boot(Maps.of(Config.ADAPTERS, Maps.of(Strings.create("configurable"),
			Maps.of(Strings.create("reject"), CVMBool.TRUE))));
		try {
			ConfigurableAdapter adapter = new ConfigurableAdapter();
			engine.registerAdapter(adapter);
			assertNull(engine.getAdapter("configurable"));
			assertTrue(engine.getDisabledAdapterNames().contains("configurable"));
			// Still declining → enable refuses; accept → enable installs and activates
			assertThrows(IllegalStateException.class, () -> engine.enableAdapter("configurable"));
			engine.configureAdapter("configurable", Maps.empty());
			assertTrue(engine.enableAdapter("configurable"));
			assertNotNull(engine.getAdapter("configurable"));
		} finally {
			engine.close();
		}
	}

	// ========== v/ops/venue/* operations ==========

	@Test
	public void testVenueOpsDriveLifecycle() throws Exception {
		Engine engine = boot(null);
		try {
			RequestContext ctx = RequestContext.of(Strings.create("did:test:venueops"));

			ACell listing = asVenue(engine, "v/ops/venue/adapters", Maps.empty());
			assertNotNull(RT.getIn(listing, "modules"));
			assertTrue(hasAdapterEntry(listing, "test", true), "test listed as enabled");
			assertTrue(hasAdapterEntry(listing, "covia", true));

			ACell disabled = asVenue(engine, "v/ops/venue/adapter/disable",
				Maps.of(Strings.create("name"), Strings.create("test")));
			assertEquals(CVMBool.FALSE, RT.getIn(disabled, "enabled"));
			assertEquals(CVMBool.TRUE, RT.getIn(disabled, "changed"));
			assertNull(engine.resolveAsset(Strings.create(ECHO), ctx));
			assertTrue(hasAdapterEntry(asVenue(engine, "v/ops/venue/adapters", Maps.empty()), "test", false),
				"disabled adapters remain in the admin listing");

			// The retracted catalog path no longer resolves; direct metadata
			// dispatch to the disabled adapter fails at the point of use.
			assertThrows(IllegalArgumentException.class, () -> engine.jobs().invokeOperation(
				"v/test/ops/echo", Maps.of(Strings.create("value"), Strings.create("x")), ctx));
			AMap<AString, ACell> echoMeta = Maps.of(
				Strings.create("name"), Strings.create("direct echo"),
				Strings.create("operation"), Maps.of(Strings.create("adapter"), Strings.create("test:echo")));
			ExecutionException gone = assertThrows(ExecutionException.class,
				() -> engine.jobs().invokeInternal(echoMeta, Maps.empty(), ctx).get(10, TimeUnit.SECONDS));
			assertTrue(gone.getCause().getMessage().contains("Adapter not available"),
				gone.getCause().getMessage());

			ACell enabled = asVenue(engine, "v/ops/venue/adapter/enable",
				Maps.of(Strings.create("name"), Strings.create("test")));
			assertEquals(CVMBool.TRUE, RT.getIn(enabled, "enabled"));
			assertNotNull(engine.resolveAsset(Strings.create(ECHO), ctx));

			ACell configured = asVenue(engine, "v/ops/venue/adapter/configure", Maps.of(
				Strings.create("name"), Strings.create("langchain"),
				Strings.create("config"), Maps.of(Strings.create("ollamaUrl"), Strings.create("http://ollama:11434"))));
			assertEquals(Strings.create("http://ollama:11434"), RT.getIn(configured, "config", "ollamaUrl"));
			assertEquals(Strings.create("http://ollama:11434"),
				RT.getIn(engine.adapterConfig("langchain"), "ollamaUrl"));

			// merge keeps existing keys
			ACell merged = asVenue(engine, "v/ops/venue/adapter/configure", Maps.of(
				Strings.create("name"), Strings.create("langchain"),
				Strings.create("merge"), CVMBool.TRUE,
				Strings.create("config"), Maps.of(Strings.create("other"), Strings.create("y"))));
			assertEquals(Strings.create("http://ollama:11434"), RT.getIn(merged, "config", "ollamaUrl"));
			assertEquals(Strings.create("y"), RT.getIn(merged, "config", "other"));

			// Kernel refusal surfaces as a failed op
			ExecutionException kernel = assertThrows(ExecutionException.class,
				() -> asVenue(engine, "v/ops/venue/adapter/disable",
					Maps.of(Strings.create("name"), Strings.create("covia"))));
			assertTrue(kernel.getCause().getMessage().contains("Kernel adapter"), kernel.getCause().getMessage());
		} finally {
			engine.close();
		}
	}

	@Test
	public void testVenueOpsRequireVenueAuthority() throws Exception {
		Engine engine = boot(null);
		try {
			AString caller = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
			engine.getVenueState().users().create(caller);
			for (String op : new String[] {"v/ops/venue/adapters", "v/ops/venue/adapter/disable",
					"v/ops/venue/adapter/enable", "v/ops/venue/adapter/configure",
					"v/ops/venue/module/load", "v/ops/venue/module/unload"}) {
				ExecutionException denied = assertThrows(ExecutionException.class,
					() -> engine.jobs().invokeInternal(op,
						Maps.of(Strings.create("name"), Strings.create("test"),
							Strings.create("module"), Strings.create("x.jar"),
							Strings.create("config"), Maps.empty()),
						RequestContext.of(caller)).get(10, TimeUnit.SECONDS), op);
				assertInstanceOf(AuthException.class, denied.getCause(), op);
				assertTrue(denied.getCause().getMessage().contains("venue-issued delegation"), op);
			}
			// Nothing changed
			assertNotNull(engine.getAdapter("test"));
		} finally {
			engine.close();
		}
	}

	private static boolean hasAdapterEntry(ACell listing, String name, boolean enabled) {
		ACell adapters = RT.getIn(listing, "adapters");
		if (!(adapters instanceof convex.core.data.AVector<?> v)) return false;
		for (int i = 0; i < v.count(); i++) {
			ACell entry = v.get(i);
			if (Strings.create(name).equals(RT.getIn(entry, "name"))) {
				return CVMBool.of(enabled).equals(RT.getIn(entry, "enabled"));
			}
		}
		return false;
	}
}
