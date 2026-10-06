package covia.adapter.whatsapp;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
import covia.adapter.messaging.*;
import covia.adapter.webhook.*;
import covia.api.Fields;
import covia.venue.*;

final class TestSupport {
  static final AString OWNER = Strings.create("did:test:whatsapp:owner");
  static final AString OTHER = Strings.create("did:test:whatsapp:other");
  static final String TOKEN = "test-outbound-token", SECRET = "test-signing-secret";
  static final String NAME = "whatsapp";
  static ACell run(Engine engine, AString owner, String op, ACell input) throws Exception {
    return engine.jobs().invokeOperation(op, input, RequestContext.of(owner)).awaitResult(10000);
  }
  static void secret(Engine engine, String name, String value) throws Exception {
    run(engine, OWNER, "v/ops/secret/set", Maps.of("name", name, "value", value));
  }
  static WebhookRequest signed(ACell payload) { return signed(JSON.print(payload).toString().getBytes(StandardCharsets.UTF_8), Instant.now().getEpochSecond()); }
  static WebhookRequest signed(byte[] body, long time) {
    try {
      String prefix = NAME.equals("slack") ? "v0:" + time + ":" : "";
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update(prefix.getBytes(StandardCharsets.UTF_8));
      String hex = HexFormat.of().formatHex(mac.doFinal(body));
      Map<String,String> headers = NAME.equals("slack")
        ? Map.of("x-slack-signature", "v0=" + hex, "x-slack-request-timestamp", Long.toString(time))
        : Map.of("x-hub-signature-256", "sha256=" + hex);
      return new WebhookRequest("POST", headers, Map.of(), body);
    } catch (Exception e) { throw new AssertionError(e); }
  }
  static void awaitStatus(WebhookBot<?> bot, String id, AString status) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (System.nanoTime() < deadline) {
      var receipt = bot.inbox().get(id);
      if (receipt != null && status.equals(receipt.get(Fields.STATUS))) return;
      Thread.sleep(10);
    }
    fail("Receipt did not reach " + status + ": " + bot.inbox().get(id) + "; bot " + bot.status());
  }
  record Sent(String path, String authorization, AMap<AString,ACell> body) { }
  static final class FakeAPI implements AutoCloseable {
    final HttpServer server;
    final LinkedBlockingQueue<Sent> sends = new LinkedBlockingQueue<>();
    volatile int status = 200;
    volatile String response = NAME.equals("slack") ? "{\"ok\":true,\"ts\":\"1700000000.000001\"}" : "{\"messages\":[{\"id\":\"wamid.sent\"}]}";
    FakeAPI() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange -> {
        try {
          AMap<AString,ACell> body = RT.castMap(JSON.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
          sends.add(new Sent(exchange.getRequestURI().toString(), exchange.getRequestHeaders().getFirst("Authorization"), body));
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
        } finally { exchange.close(); }
      });
      server.start();
    }
    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    Sent take() throws Exception { Sent sent = sends.poll(8, TimeUnit.SECONDS); assertNotNull(sent); return sent; }
    @Override public void close() { server.stop(0); }
  }
  static final class Fixture implements AutoCloseable {
    final FakeAPI api = new FakeAPI();
    final Engine engine = Engine.createTemp(Maps.of(Config.USERS, Maps.of(Config.AUTO_CREATE, true)));
    final WhatsAppAdapter adapter = new WhatsAppAdapter();
    Fixture(AMap<AString,ACell> spec) throws Exception {
      Engine.addDemoAssets(engine);
      secret(engine, "TOKEN", TOKEN); secret(engine, "SIGNING", SECRET); secret(engine, "VERIFY", "test-verify");
      adapter.retryMillis = 25;
      engine.registerAdapter(adapter);
      configure(spec);
    }
    void configure(AMap<AString,ACell> spec) { engine.configureAdapter(NAME, Maps.of("apiUrl", api.url(), "bots", Maps.of("main", spec))); }
    WebhookResponse webhook(ACell payload) { return adapter.handleWebhook("c-main", signed(payload)); }
    WebhookBot<BotSpec> bot() { return adapter.runner("main"); }
    ACell run(String op, ACell input) throws Exception { return TestSupport.run(engine, OWNER, "v/ops/" + NAME + "/" + op, input); }
    @Override public void close() { engine.close(); api.close(); }
  }
}
