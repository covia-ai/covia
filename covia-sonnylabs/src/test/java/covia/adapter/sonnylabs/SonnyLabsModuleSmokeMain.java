package covia.adapter.sonnylabs;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.ModuleClassLoader;
import covia.venue.SecretStore;
import covia.venue.User;

/** Child-process entry point for {@link SonnyLabsModuleIT}. */
public final class SonnyLabsModuleSmokeMain {
	private SonnyLabsModuleSmokeMain() {}

	public static void main(String[] args) throws Exception {
		AtomicReference<String> authorization = new AtomicReference<>();
		HttpServer fake = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		fake.createContext("/v1/scans", exchange -> {
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			exchange.getRequestBody().readAllBytes();
			byte[] response = ("{\"id\":\"scan_smoke\",\"kind\":\"content\","
				+ "\"surface\":\"user_message\",\"findings\":[],"
				+ "\"decision\":{\"action\":\"allowed\",\"reason\":\"clean\"},"
				+ "\"content_stored\":false}").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		fake.start();

		Engine engine = Engine.createTemp(Maps.of(
			Config.MODULES, Vectors.of(Maps.of("path", args[0])),
			Config.ADAPTERS, Maps.of("sonnylabs", Maps.of(
				"baseUrl", "http://localhost:" + fake.getAddress().getPort(),
				"apiKey", "s/SONNYLABS_API_KEY"))));
		try {
			Engine.addDemoAssets(engine);
			AAdapter adapter = engine.getAdapter("sonnylabs");
			if (adapter == null) throw new AssertionError("SonnyLabs adapter did not load");
			if (!(adapter.getClass().getClassLoader() instanceof ModuleClassLoader)) {
				throw new AssertionError("SonnyLabs adapter was not loaded as a module");
			}
			if (engine.resolvePath(Strings.create("v/skills/security/sonnylabs"),
					engine.venueContext()) == null) {
				throw new AssertionError("SonnyLabs module skill was not installed");
			}

			User venueUser = engine.getVenueState().users().ensure(engine.getDIDString());
			venueUser.secrets().store("SONNYLABS_API_KEY", "smoke-token",
				SecretStore.deriveKey(engine.getKeyPair()));
			Job job = engine.jobs().invokeOperation("v/ops/sonnylabs/scan",
				Maps.of("prompt", "hello"), engine.venueContext());
			ACell output = job.awaitResult(10_000);
			if (job.getStatus() != Status.COMPLETE) {
				throw new AssertionError("SonnyLabs smoke scan failed: " + job.getErrorMessage());
			}
			if (!"allowed".equals(RT.getIn(output, "decision", "action").toString())) {
				throw new AssertionError("Unexpected SonnyLabs result: " + output);
			}
			if (!"Bearer smoke-token".equals(authorization.get())) {
				throw new AssertionError("Module did not send the configured credential");
			}
			System.out.println("SONNYLABS_MODULE_SMOKE_OK");
		} finally {
			engine.close();
			fake.stop(0);
		}
	}
}
