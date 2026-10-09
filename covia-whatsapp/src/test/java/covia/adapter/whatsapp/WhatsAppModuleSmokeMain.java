package covia.adapter.whatsapp;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.sun.net.httpserver.HttpServer;
import convex.core.data.*;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.AAdapter;
import covia.adapter.messaging.AWebhookMessagingAdapter;
import covia.adapter.webhook.*;
import covia.api.Fields;
import covia.venue.*;
import covia.venue.server.VenueServer;

/** Forked with only covia.jar and test classes: provider code comes from the module jar. */
public final class WhatsAppModuleSmokeMain {
	private static final String PROVIDER = "whatsapp";
	private static final AString OWNER = Strings.create("did:test:" + PROVIDER + ":module");
	private static final String TOKEN = "module-test-token", SECRET = "module-signing-secret";
	private record Sent(String path, String authorization, ACell body) { }

	public static void main(String[] args) throws Exception {
		LinkedBlockingQueue<Sent> sends = new LinkedBlockingQueue<>();
		HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		api.createContext("/", exchange -> {
			try {
				ACell body = JSON.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				sends.add(new Sent(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization"), body));
				byte[] response = JSON.print(Maps.of("messages", Vectors.of(Maps.of("id", "wamid.sent")))).toString().getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, response.length);
				exchange.getResponseBody().write(response);
			} finally { exchange.close(); }
		});
		api.start();
		try {
			AMap<AString, ACell> spec = Maps.of("user", OWNER, "token", "s/TOKEN", "operation", "v/test/ops/echo",
				"appSecret", "s/SIGNING", "verifyToken", "s/VERIFY", "phoneNumberId", "12345",
				"businessAccountId", "67890", "apiVersion", "v25.0", "allow", Vectors.of("441234567890"));
			AMap<AString, ACell> config = Maps.of(Config.STORE, "memory", Config.PORT, 0, Config.BIND_ADDRESS, "127.0.0.1",
				Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, false)), Config.USERS, Maps.of(Config.AUTO_CREATE, true),
				Config.MODULES, Vectors.of(Maps.of("path", args[0])), Config.ADAPTERS, Maps.of(PROVIDER,
					Maps.of("apiUrl", "http://127.0.0.1:" + api.getAddress().getPort(), "bots", Maps.of("main", spec))));
			VenueServer server = VenueServer.launch(config);
			try (HttpClient client = HttpClient.newHttpClient()) {
				Engine engine = server.getEngine();
				Engine.addDemoAssets(engine);
				for (var secret : Map.of("TOKEN", TOKEN, "SIGNING", SECRET, "VERIFY", "verify+token").entrySet()) {
					run(engine, "v/ops/secret/set", Maps.of("name", secret.getKey(), "value", secret.getValue()));
				}
				AAdapter adapter = engine.getAdapter(PROVIDER);
				check(adapter != null && adapter.getClass().getClassLoader() instanceof ModuleClassLoader, "Module did not load in isolation");
				check(adapter instanceof WebhookHandler, "Not a webhook receiver");
				for (String path : new String[]{"v/ops/" + PROVIDER + "/send", "v/ops/" + PROVIDER + "/create",
					"v/ops/" + PROVIDER + "/delete", "v/ops/" + PROVIDER + "/bots", "v/skills/adapters/" + PROVIDER, "v/adapters/" + PROVIDER + "/info"}) {
					check(engine.resolvePath(Strings.create(path), engine.venueContext()) != null, "Missing " + path);
				}
				URI endpoint = URI.create("http://127.0.0.1:" + server.port() + "/webhooks/" + PROVIDER + "/c-main");
				var challenge = client.send(HttpRequest.newBuilder(URI.create(endpoint + "?hub.mode=subscribe&hub.verify_token=verify%2Btoken&hub.challenge=challenge%2B%F0%9F%90%A6"))
					.timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				check(challenge.statusCode() == 200 && challenge.body().equals("challenge+🐦"), "Challenge failed: " + challenge);
				ACell message = Maps.of("id", "wamid.module", "type", "text", "from", "441234567890",
					"timestamp", Long.toString(Instant.now().getEpochSecond()), "text", Maps.of("body", "héllo 🐦"));
				ACell value = Maps.of("messaging_product", "whatsapp", "metadata", Maps.of("phone_number_id", "12345"), "messages", Vectors.of(message));
				ACell event = Maps.of("object", "whatsapp_business_account", "entry", Vectors.of(Maps.of("id", "67890",
					"changes", Vectors.of(Maps.of("field", "messages", "value", value)))));
				String id = "wamid.module";
				byte[] body = (" \n" + JSON.print(event) + "\n").getBytes(StandardCharsets.UTF_8);
				check(post(client, endpoint, body, null).statusCode() == 401, "Unsigned request accepted");
				check(post(client, endpoint, (new String(body, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8), body).statusCode() == 401, "Altered raw body accepted");
				byte[] malformed = "{".getBytes(StandardCharsets.UTF_8);
				check(post(client, endpoint, malformed, malformed).statusCode() == 400, "Malformed signed JSON accepted");
				check(sends.isEmpty(), "Rejected callback caused a send");
				check(post(client, endpoint, body, body).statusCode() == 200, "Callback failed");
				Sent reply = take(sends);
				check(reply.authorization().equals("Bearer " + TOKEN), "Wrong outbound authentication");
				check(reply.path().equals("/v25.0/12345/messages"), "Wrong outbound endpoint");
				check(Strings.create("441234567890").equals(RT.getIn(reply.body(), Strings.intern("to"))), "Wrong recipient");
				check(Strings.create(id).equals(RT.getIn(reply.body(), Strings.intern("context"), Strings.intern("message_id"))), "Lost reply context");
				check(reply.body().toString().contains("héllo 🐦"), "Lost inbound text");
				var bot = ((AWebhookMessagingAdapter<?>) adapter).runner("main");
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				while (!WebhookInbox.COMPLETE.equals(bot.inbox().get(id).get(Fields.STATUS)) && System.nanoTime() < deadline) Thread.sleep(10);
				check(WebhookInbox.COMPLETE.equals(bot.inbox().get(id).get(Fields.STATUS)), "Receipt not completed");
				check(post(client, endpoint, body, body).statusCode() == 200, "Duplicate not acknowledged");
				check(sends.poll(200, TimeUnit.MILLISECONDS) == null, "Duplicate callback caused another send");
				run(engine, "v/ops/" + PROVIDER + "/send", Maps.of("to", "441234567890", "text", "explicit send"));
				Sent explicit = take(sends);
				check(Strings.create("explicit send").equals(RT.getIn(explicit.body(), Strings.intern("text"), Strings.intern("body"))), "Explicit send failed");
				engine.disableAdapter(PROVIDER);
				check(post(client, endpoint, body, body).statusCode() == 404, "Disabled receiver remained active");
				engine.enableAdapter(PROVIDER);
				check(post(client, endpoint, body, body).statusCode() == 200, "Reenabled receiver unavailable");
				Modules.unload(engine, engine.moduleOf(PROVIDER).name());
				check(engine.getAdapter(PROVIDER) == null, "Adapter survived unload");
				check(engine.resolvePath(Strings.create("v/adapters/" + PROVIDER + "/info"), engine.venueContext()) == null, "Public info survived unload");
				check(post(client, endpoint, body, body).statusCode() == 404, "Unloaded HTTP route remained active");
				check(((WebhookHandler) adapter).handleWebhook("c-main", new WebhookRequest("POST", Map.of(), Map.of(), body)).status() == 404, "Held unloaded receiver remained active");
				check(sends.poll(200, TimeUnit.MILLISECONDS) == null, "Lifecycle change replayed a callback");
				System.out.println(PROVIDER + "_MODULE_SMOKE_OK");
			} finally { server.close(); }
		} finally { api.stop(0); }
	}

	private static HttpResponse<String> post(HttpClient client, URI endpoint, byte[] body, byte[] signedBody) throws Exception {
		var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
			.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
		if (signedBody != null) {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			request.header("X-Hub-Signature-256", "sha256=" + HexFormat.of().formatHex(mac.doFinal(signedBody)));
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}
	private static ACell run(Engine engine, String operation, ACell input) {
		return engine.jobs().invokeOperation(operation, input, RequestContext.of(OWNER)).awaitResult(10000);
	}
	private static Sent take(LinkedBlockingQueue<Sent> sends) throws Exception {
		Sent sent = sends.poll(10, TimeUnit.SECONDS);
		check(sent != null, "No outbound message");
		return sent;
	}
	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
