package covia.venue.server;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import covia.adapter.AAdapter;
import covia.adapter.relay.RelayHandler;
import covia.adapter.webhook.WebhookRequest;
import covia.adapter.webhook.WebhookResponse;
import covia.grid.auth.VenueAuth;
import covia.venue.Config;
import covia.venue.RequestContext;

/**
 * {@code /relay/{adapter}/{binding}/{action}}: every venue answers it; an anonymous
 * caller is refused before any adapter is consulted; an authenticated DID reaches a live
 * relay adapter with its own identity, which follows runtime disable and removal; an
 * adapter's future may hold the response; bodies are capped.
 */
class RelayRoutesTest {

	@Test void routesTrackLiveRelayAdaptersAndCarryTheCallersIdentity() throws Exception {
		VenueServer server = VenueServer.launch(Maps.of(Config.STORE, "memory", Config.PORT, 0,
			Config.BIND_ADDRESS, "127.0.0.1", Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, false))));
		try (HttpClient client = HttpClient.newHttpClient()) {
			URI endpoint = URI.create("http://127.0.0.1:" + server.port() + "/relay/test-relay/c-main/echo");
			AKeyPair key = AKeyPair.generate();
			VenueAuth auth = VenueAuth.keyPair(key);

			assertEquals(401, send(client, endpoint, "{}", null).statusCode(), "anonymous callers are refused");
			assertEquals(404, send(client, endpoint, "{}", auth).statusCode(), "no relay adapter yet");

			Echo echo = new Echo();
			server.getEngine().registerAdapter(echo);
			HttpResponse<String> answered = send(client, endpoint, "{}", auth);
			assertEquals(200, answered.statusCode(), answered.body());
			assertEquals(auth.getDID(), answered.body().split("\\|")[3], "the adapter sees the caller's own DID");
			assertTrue(answered.body().startsWith("c-main|echo|POST"));

			String agentDID = auth.getDID() + ":u:alice:g:worker";
			VenueAuth agentAuth = VenueAuth.namedKeyPair(key, agentDID,
				server.getEngine().getDIDString().toString(), 300);
			HttpResponse<String> agentReply = send(client, endpoint, "{}", agentAuth);
			assertEquals(200, agentReply.statusCode(), agentReply.body());
			assertEquals(agentDID, agentReply.body().split("\\|")[3],
				"relay policy receives the exact agent identity, never its owner or signing key");

			CompletableFuture<HttpResponse<String>> held = client.sendAsync(request(
				URI.create(endpoint.toString().replace("/echo", "/hold")), "{}", auth), HttpResponse.BodyHandlers.ofString());
			Thread.sleep(200);
			assertFalse(held.isDone(), "an action may hold the response");
			echo.release.complete(WebhookResponse.text(200, "released"));
			assertEquals("released", held.get(5, TimeUnit.SECONDS).body());

			assertTrue(server.getEngine().disableAdapter(echo.getName()));
			assertEquals(404, send(client, endpoint, "{}", auth).statusCode());
			assertTrue(server.getEngine().enableAdapter(echo.getName()));
			assertEquals(200, send(client, endpoint, "{}", auth).statusCode());
			server.getEngine().removeAdapter(echo.getName());
			assertEquals(404, send(client, endpoint, "{}", auth).statusCode());

			server.getEngine().registerAdapter(new Echo());
			String large = "x".repeat(RelayRoutes.MAX_BODY_BYTES + 1);
			assertEquals(413, send(client, endpoint, large, auth).statusCode());
		} finally {
			server.close();
		}
	}

	private static HttpRequest request(URI endpoint, String body, VenueAuth auth) {
		HttpRequest.Builder b = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		if (auth != null) auth.apply(b);
		return b.build();
	}

	private static HttpResponse<String> send(HttpClient client, URI endpoint, String body, VenueAuth auth) throws Exception {
		return client.send(request(endpoint, body, auth), HttpResponse.BodyHandlers.ofString());
	}

	/** Answers "binding|action|method|caller", or holds a {@code hold} action until released. */
	private static final class Echo extends AAdapter implements RelayHandler {
		final CompletableFuture<WebhookResponse> release = new CompletableFuture<>();
		@Override public String getName() { return "test-relay"; }
		@Override public String getDescription() { return "Test relay"; }
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			return CompletableFuture.failedFuture(new UnsupportedOperationException());
		}
		@Override public CompletableFuture<WebhookResponse> handleRelay(String binding, String action, AString caller, WebhookRequest request) {
			if ("hold".equals(action)) return release;
			return CompletableFuture.completedFuture(new WebhookResponse(200, "text/plain", Map.of(),
				(binding + "|" + action + "|" + request.method() + "|" + caller).getBytes(StandardCharsets.UTF_8)));
		}
	}
}
