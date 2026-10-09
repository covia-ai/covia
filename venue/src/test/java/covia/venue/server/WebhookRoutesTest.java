package covia.venue.server;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import covia.adapter.AAdapter;
import covia.adapter.webhook.WebhookHandler;
import covia.adapter.webhook.WebhookRequest;
import covia.adapter.webhook.WebhookResponse;
import covia.venue.Config;
import covia.venue.RequestContext;

class WebhookRoutesTest {
	private static final byte[] SIGNED_BODY = " {\"event\":\"héllo\"}\n".getBytes(StandardCharsets.UTF_8);

	@Test void routesOnPrivateVenueTrackLiveAdaptersAndPreserveProviderAuthentication() throws Exception {
		VenueServer server = VenueServer.launch(Maps.of(Config.STORE, "memory", Config.PORT, 0,
			Config.BIND_ADDRESS, "127.0.0.1", Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, false))));
		try (HttpClient client = HttpClient.newHttpClient()) {
			URI endpoint = URI.create("http://127.0.0.1:" + server.port() + "/webhooks/test-webhook/binding");
			assertEquals(404, send(client, endpoint, SIGNED_BODY, false).statusCode());
			Receiver first = new Receiver();
			server.getEngine().registerAdapter(first);
			assertEquals(401, send(client, endpoint, SIGNED_BODY, false).statusCode());
			assertEquals(0, first.accepted.get());
			assertEquals(202, send(client, endpoint, SIGNED_BODY, true).statusCode());
			assertEquals(1, first.accepted.get());
			assertEquals(400, send(client, endpoint, "{}".getBytes(StandardCharsets.UTF_8), true).statusCode());
			HttpResponse<String> challenge = client.send(HttpRequest.newBuilder(URI.create(endpoint + "?hub.challenge=abc%2B123"))
				.GET().timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
			assertEquals(200, challenge.statusCode());
			assertEquals("abc+123", challenge.body());

			assertTrue(server.getEngine().disableAdapter(first.getName()));
			assertEquals(404, send(client, endpoint, SIGNED_BODY, true).statusCode());
			assertTrue(server.getEngine().enableAdapter(first.getName()));
			assertEquals(202, send(client, endpoint, SIGNED_BODY, true).statusCode());
			server.getEngine().removeAdapter(first.getName());
			assertEquals(404, send(client, endpoint, SIGNED_BODY, true).statusCode());
			Receiver replacement = new Receiver();
			server.getEngine().registerAdapter(replacement);
			assertEquals(202, send(client, endpoint, SIGNED_BODY, true).statusCode());
			assertEquals(2, first.accepted.get());
			assertEquals(1, replacement.accepted.get());

			byte[] large = new byte[WebhookRoutes.MAX_BODY_BYTES + 1];
			assertEquals(413, send(client, endpoint, large, true).statusCode());
			assertEquals(1, replacement.accepted.get());
			assertEquals(404, send(client, URI.create(endpoint.toString().replace("test-webhook", "test")), SIGNED_BODY, true).statusCode());
		} finally { server.close(); }
	}

	private static HttpResponse<String> send(HttpClient client, URI endpoint, byte[] body, boolean signed) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
			.POST(HttpRequest.BodyPublishers.ofByteArray(body));
		if (signed) request.header("X-Test-Signature", "test-signature");
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** Fake provider: production implementations must verify their actual HMAC/timestamp. */
	private static final class Receiver extends AAdapter implements WebhookHandler {
		final AtomicInteger accepted = new AtomicInteger();
		@Override public String getName() { return "test-webhook"; }
		@Override public String getDescription() { return "Test webhook receiver"; }
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			throw new UnsupportedOperationException();
		}
		@Override public WebhookResponse handleWebhook(String binding, WebhookRequest request) {
			assertEquals("binding", binding);
			if (request.method().equals("GET")) return WebhookResponse.text(200, request.queryParam("hub.challenge"));
			if (!"test-signature".equals(request.header("x-test-signature"))) return WebhookResponse.text(401, "test rejection");
			if (!Arrays.equals(SIGNED_BODY, request.body())) return WebhookResponse.text(400, "test body mismatch");
			byte[] copy = request.body();
			copy[0] = 0;
			assertArrayEquals(SIGNED_BODY, request.body());
			accepted.incrementAndGet();
			return WebhookResponse.text(202, "test acknowledgement");
		}
	}
}
