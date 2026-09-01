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
import covia.grid.Authority;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.CapabilityChecker;

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

	/** An adapter that publishes a fact derived from its effective configuration. */
	static final class FactAdapter extends AAdapter {
		private volatile String colour = "unset";
		@Override public String getName() { return "fact"; }
		@Override public String getDescription() { return "publishes a configured fact"; }
		@Override public boolean configure(AMap<AString, ACell> config, boolean strict) {
			AString c = RT.ensureString(config.get(Strings.create("colour")));
			colour = (c == null) ? "unset" : c.toString();
			return true;
		}
		@Override public AMap<AString, ACell> info() {
			// The reserved framework key is ignored; the adapter's own fact is published.
			return Maps.of("colour", colour, "name", "not-me");
		}
		@Override public AMap<AString, ACell> publicConfig() {
			return publicConfig("colour", "nested");   // an explicit allow-list: token is not on it
		}
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			return CompletableFuture.completedFuture(input);
		}
	}

	@Test
	public void testAdapterInfoIsPublishedAndFollowsReconfigure() throws Exception {
		Engine engine = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.ADAPTERS, Maps.of("fact", Maps.of("colour", "blue"))));
		try {
			engine.registerAdapter(new FactAdapter());
			Engine.addDemoAssets(engine);
			ACell rec = venueRead(engine, "v/info/adapters/fact");
			assertEquals(Strings.create("blue"), RT.getIn(rec, "colour"), "info() merged into the adapter record: " + rec);
			assertEquals(Strings.create("fact"), RT.getIn(rec, "name"), "framework keys win over info()");
			assertNotNull(RT.getIn(rec, "kernel"));

			engine.configureAdapter("fact", Maps.of("colour", "red"));
			assertEquals(Strings.create("red"), RT.getIn(venueRead(engine, "v/info/adapters/fact"), "colour"),
				"info() is republished after reconfigure");

			// DLFS publishes its WebDAV facts the same way; this engine has WebDAV off
			assertEquals(CVMBool.FALSE, RT.getIn(venueRead(engine, "v/info/adapters/dlfs"), "webdav", "enabled"));
		} finally {
			engine.close();
		}
	}

	@Test
	public void testAdapterSkillFollowsAdapterLifecycle() throws Exception {
		Engine engine = boot(null);
		try {
			// Skills live in skillsets: v/skills holds directories, never skills.
			assertNotNull(venueRead(engine, "v/skills/ops-tools/mcp"),
				"an active adapter's skill is published");
			engine.disableAdapter("mcp");
			assertNotNull(venueRead(engine, "v/skills/ops-tools/mcp"),
				"catalog metadata survives adapter removal");
			assertNotNull(venueRead(engine, "v/skills/root/covia"), "platform skills stay");
			engine.enableAdapter("mcp");
			assertNotNull(venueRead(engine, "v/skills/ops-tools/mcp"), "enabling republishes it");
		} finally {
			engine.close();
		}
	}

	@Test
	public void testConnectionCatalogOwnershipFollowsAdapterLifecycle() throws Exception {
		Engine engine = boot(null);
		try {
			assertNotNull(venueRead(engine, "v/adapters/connections/skills/notion"));
			assertNotNull(venueRead(engine, "v/adapters/connections/ops/list"));
			assertTrue(engine.disableAdapter("connections"));
			assertNull(venueRead(engine, "v/adapters/connections"),
				"disabling retracts the live owner surface");
			assertNotNull(venueRead(engine, "v/skills/connections/notion"),
				"canonical metadata remains stable for already-rendered session history");
			assertTrue(engine.enableAdapter("connections"));
			assertNotNull(venueRead(engine, "v/adapters/connections/skills/notion"));
		} finally {
			engine.close();
		}
	}

	@Test
	public void testAdapterOwnedSubtreeFollowsLifecycleAndRedactsConfig() throws Exception {
		Engine engine = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.ADAPTERS, Maps.of("fact", Maps.of(
				"colour", "blue", "token", "literal-secret", "ref", "s/MY_TOKEN",
				"nested", Maps.of("apiKey", "k123", "size", 3L)))));
		try {
			engine.registerAdapter(new FactAdapter());
			Engine.addDemoAssets(engine);
			ACell cfg = venueRead(engine, "v/adapters/fact/config");
			assertEquals(Strings.create("blue"), RT.getIn(cfg, "colour"));
			assertNull(RT.getIn(cfg, "token"), "only allow-listed keys are published — no guessing, no redaction");
			assertNull(RT.getIn(cfg, "ref"));
			assertEquals(convex.core.data.prim.CVMLong.create(3), RT.getIn(cfg, "nested", "size"),
				"an allow-listed key is published whole");
			assertNull(venueRead(engine, "v/adapters/mcp/config"), "adapters that publish nothing have no config record");
			assertNotNull(venueRead(engine, "v/adapters/orchestrator/config"), "documented public settings are published");
			assertEquals(venueRead(engine, "v/info/adapters/fact"), venueRead(engine, "v/adapters/fact/info"));

			engine.configureAdapter("fact", Maps.of("colour", "red"));
			assertEquals(Strings.create("red"), RT.getIn(venueRead(engine, "v/adapters/fact/config"), "colour"),
				"config record follows reconfigure");
			assertEquals(Strings.create("red"), RT.getIn(venueRead(engine, "v/adapters/fact/info"), "colour"));

			assertNotNull(venueRead(engine, "v/adapters/mcp/ops/tools-list"), "an adapter's ops under its subtree");
			engine.disableAdapter("mcp");
			assertNull(venueRead(engine, "v/adapters/mcp"), "disabling retracts the whole owned subtree");
			engine.enableAdapter("mcp");
			assertNotNull(venueRead(engine, "v/adapters/mcp/skills/mcp"), "enabling republishes it");
		} finally {
			engine.close();
		}
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
			assertNotNull(engine.resolveAsset(Strings.create(ECHO), ctx),
				"catalog metadata remains while dispatch is unavailable");
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
	public void testKernelMarkerDoesNotOverrideOperatorLifecycle() {
		Engine engine = boot(null);
		try {
			for (String kernel : Engine.KERNEL_ADAPTERS) {
				assertTrue(engine.isKernelAdapter(kernel));
			}
			assertTrue(engine.disableAdapter("grid"));
			assertNull(engine.getAdapter("grid"));
			assertTrue(engine.enableAdapter("grid"));
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
	public void testOperatorMayDisableKernelByConfig() {
		Engine engine = Engine.createTemp(Maps.of(Config.ADAPTERS,
			Maps.of(Strings.create("covia"), Maps.of(Config.ENABLED, false))));
		try {
			Engine.addDemoAssets(engine);
			assertNull(engine.getAdapter("covia"));
			assertTrue(engine.getDisabledAdapterNames().contains("covia"));
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
	public void testShowConfigIsCuratedEffectiveAndPublic() throws Exception {
		Engine engine = Engine.createTemp(Maps.of(
			Config.NAME, "Configured Venue",
			Config.HOSTNAME, "venue.example",
			Config.PORT, 443L,
			Config.STORE, "memory",
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, true)),
			Config.DEFAULT_LLM_OPERATION, "v/models/test/public-default",
			Config.DEFAULT_TRANSITION_OP, "v/ops/llmagent/test",
			Config.MAX_TOOL_ITERATIONS, 17L,
			Config.RECORD_READ_ONLY_OPERATIONS, true,
			Config.OUTPUT_VALIDATION, "strict",
			Config.ADAPTERS, Maps.of("fact", Maps.of(
				"colour", "blue",
				"token", "literal-secret-marker",
				"ref", "s/SECRET_REFERENCE"))));
		try {
			engine.registerAdapter(new FactAdapter());
			Engine.addDemoAssets(engine);

			AString publicDID = Strings.create(engine.getDIDString() + ":public");
			RequestContext publicCtx = RequestContext.ofAuthority(Authority.of(
				publicDID, CapabilityChecker.readOnlyScope(publicDID)));
			ACell shown = engine.jobs().invokeInternal(
				"v/ops/venue/show-config", Maps.empty(), publicCtx)
				.get(10, TimeUnit.SECONDS);

			assertEquals(Strings.create("Configured Venue"), RT.getIn(shown, "venue", "name"));
			assertEquals(Strings.create("https://venue.example"), RT.getIn(shown, "venue", "url"));
			assertEquals(Strings.create("v/models/test/public-default"),
				RT.getIn(shown, "agents", "defaultLlmOperation"));
			assertEquals(convex.core.data.prim.CVMLong.create(17),
				RT.getIn(shown, "agents", "maxToolIterations"));
			assertEquals(CVMBool.TRUE, RT.getIn(shown, "jobs", "recordReadOnlyOperations"));
			assertEquals(Strings.create("memory-only"), RT.getIn(shown, "storage", "statePersistence"));
			assertEquals(Strings.create("strict"), RT.getIn(shown, "validation", "output"));
			assertEquals(Strings.create("blue"),
				RT.getIn(shown, "adapters", "publicConfig", "fact", "colour"));

			String rendered = shown.toString();
			assertFalse(rendered.contains("literal-secret-marker"), rendered);
			assertFalse(rendered.contains("SECRET_REFERENCE"), rendered);
			assertNull(RT.getIn(shown, "adapters", "publicConfig", "fact", "token"));
			assertNull(RT.getIn(shown, "adapters", "publicConfig", "fact", "ref"));
		} finally {
			engine.close();
		}
	}

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
			assertNotNull(engine.resolveAsset(Strings.create(ECHO), ctx));
			assertTrue(hasAdapterEntry(asVenue(engine, "v/ops/venue/adapters", Maps.empty()), "test", false),
				"disabled adapters remain in the admin listing");

			// Catalog metadata remains; dispatch fails because the adapter is gone.
			IllegalStateException stale = assertThrows(IllegalStateException.class,
				() -> engine.jobs().invokeOperation("v/test/ops/echo",
					Maps.of(Strings.create("value"), Strings.create("x")), ctx));
			assertTrue(stale.getMessage().contains("Adapter not available"), stale.getMessage());
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

			// The kernel marker is informational; venue authority may still disable it.
			ACell kernel = asVenue(engine, "v/ops/venue/adapter/disable",
				Maps.of(Strings.create("name"), Strings.create("covia")));
			assertEquals(CVMBool.FALSE, RT.getIn(kernel, "enabled"));
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
					"v/ops/venue/module/load", "v/ops/venue/module/unload",
					"v/ops/venue/restart", "v/ops/venue/gc"}) {
				ExecutionException denied = assertThrows(ExecutionException.class,
					() -> engine.jobs().invokeInternal(op,
						Maps.of(Strings.create("name"), Strings.create("test"),
							Strings.create("module"), Strings.create("x.jar"),
							Strings.create("config"), Maps.empty()),
						RequestContext.of(caller)).get(10, TimeUnit.SECONDS), op);
				assertInstanceOf(AuthException.class, denied.getCause(), op);
				assertTrue(denied.getCause().getMessage().contains("venue-issued delegation"), op);
				if (op.endsWith("/restart")) {
					assertTrue(denied.getCause().getMessage().contains("venue/restart"), op);
					assertTrue(denied.getCause().getMessage().contains("/process"), op);
				} else if (op.endsWith("/gc")) {
					assertTrue(denied.getCause().getMessage().contains("venue/gc"), op);
					assertTrue(denied.getCause().getMessage().contains("/store"), op);
				} else {
					assertTrue(denied.getCause().getMessage().contains("adapter/manage"), op);
					assertTrue(denied.getCause().getMessage().contains("/adapters"), op);
				}
			}
			// Nothing changed
			assertNotNull(engine.getAdapter("test"));
		} finally {
			engine.close();
		}
	}

	@Test
	public void testRestartRequiresStandaloneMainVenueProcess() throws Exception {
		Engine engine = boot(null);
		try {
			ExecutionException unavailable = assertThrows(ExecutionException.class,
				() -> asVenue(engine, "v/ops/venue/restart", Maps.empty()));
			assertTrue(unavailable.getCause().getMessage().contains("not managed by MainVenue"),
				String.valueOf(unavailable.getCause()));
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
