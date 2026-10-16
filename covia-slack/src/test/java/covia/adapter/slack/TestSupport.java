package covia.adapter.slack;

import static org.junit.jupiter.api.Assertions.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import convex.core.data.*;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.messaging.*;
import covia.adapter.webhook.*;
import covia.api.Fields;
import covia.venue.*;

final class TestSupport {
  static final AString OWNER = Strings.create("did:test:slack:owner");
  static final AString OTHER = Strings.create("did:test:slack:other");
  static final String TOKEN = "test-outbound-token", SECRET = "test-signing-secret", APP_TOKEN = "test-app-token";
  static final String NAME = "slack";
  static ACell run(Engine engine, AString owner, String op, ACell input) throws Exception {
    return engine.jobs().invokeOperation(op, input, RequestContext.of(owner)).awaitResult(10000);
  }
  static void secret(Engine engine, String name, String value) throws Exception { secret(engine, OWNER, name, value); }
  static void secret(Engine engine, AString owner, String name, String value) throws Exception {
    run(engine, owner, "v/ops/secret/set", Maps.of("name", name, "value", value));
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
    /** Socket Mode opens are kept apart from message sends. */
    final LinkedBlockingQueue<Sent> opens = new LinkedBlockingQueue<>();
    volatile int status = 200;
    volatile String response = NAME.equals("slack") ? "{\"ok\":true,\"ts\":\"1700000000.000001\"}" : "{\"messages\":[{\"id\":\"wamid.sent\"}]}";
    volatile String socketUrl;
    FakeAPI() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange -> {
        try {
          AMap<AString,ACell> body = RT.castMap(JSON.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
          Sent sent = new Sent(exchange.getRequestURI().toString(), exchange.getRequestHeaders().getFirst("Authorization"), body);
          boolean open = sent.path().equals("/apps.connections.open");
          (open ? opens : sends).add(sent);
          String answer = !open ? response : socketUrl == null ? "{\"ok\":false,\"error\":\"not_allowed\"}"
            : JSON.print(Maps.of("ok", true, "url", socketUrl)).toString();
          byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
        } finally { exchange.close(); }
      });
      server.start();
    }
    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    Sent take() throws Exception { Sent sent = sends.poll(8, TimeUnit.SECONDS); assertNotNull(sent); return sent; }
    Sent opened() throws Exception { Sent sent = opens.poll(8, TimeUnit.SECONDS); assertNotNull(sent); return sent; }
    @Override public void close() { server.stop(0); }
  }
  /** A Socket Mode endpoint: says hello on connect, records acknowledgements and closes. */
  static final class FakeSocket implements AutoCloseable {
    final Javalin app;
    final LinkedBlockingQueue<WsContext> connections = new LinkedBlockingQueue<>();
    final LinkedBlockingQueue<String> acks = new LinkedBlockingQueue<>();
    final LinkedBlockingQueue<String> closes = new LinkedBlockingQueue<>();
    FakeSocket() {
      app = Javalin.create(config -> {
        config.startup.showJavalinBanner = false;
        config.routes.ws("/link", ws -> {
          ws.onConnect(ctx -> { ctx.send("{\"type\":\"hello\",\"num_connections\":1}"); connections.add(ctx); });
          ws.onMessage(ctx -> acks.add(ctx.message()));
          ws.onClose(ctx -> closes.add(ctx.sessionId));
        });
      }).start("127.0.0.1", 0);
    }
    String url() { return "ws://127.0.0.1:" + app.port() + "/link?ticket=single-use"; }
    WsContext connected() throws Exception { WsContext ctx = connections.poll(8, TimeUnit.SECONDS); assertNotNull(ctx); return ctx; }
    String ack() throws Exception { String ack = acks.poll(8, TimeUnit.SECONDS); assertNotNull(ack); return ack; }
    @Override public void close() { app.stop(); }
  }
  /** Completes the WebSocket handshake, then never answers a frame. */
  static final class SilentSocket implements AutoCloseable {
    final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    final List<Socket> accepted = new CopyOnWriteArrayList<>();
    SilentSocket() throws Exception {
      Thread.ofVirtual().start(() -> {
        try {
          while (true) {
            Socket socket = server.accept(); accepted.add(socket);
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String key = null;
            for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
              if (line.regionMatches(true, 0, "Sec-WebSocket-Key:", 0, 18)) key = line.substring(18).strip();
            }
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
              .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
            socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
              + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
          }
        } catch (Exception closed) { }
      });
    }
    String url() { return "ws://127.0.0.1:" + server.getLocalPort() + "/link"; }
    @Override public void close() throws Exception { server.close(); for (Socket s : accepted) s.close(); }
  }
  static final class Fixture implements AutoCloseable {
    final FakeAPI api = new FakeAPI();
    final Engine engine = Engine.createTemp(Maps.of(Config.USERS, Maps.of(Config.AUTO_CREATE, true)));
    final SlackAdapter adapter = new SlackAdapter();
    Fixture(AMap<AString,ACell> spec) throws Exception { this(spec, null); }
    /** socketUrl is what apps.connections.open returns from the first attempt. */
    Fixture(AMap<AString,ACell> spec, String socketUrl) throws Exception {
      api.socketUrl = socketUrl;
      Engine.addDemoAssets(engine);
      secret(engine, "TOKEN", TOKEN); secret(engine, "SIGNING", SECRET); secret(engine, "VERIFY", "test-verify"); secret(engine, "APP", APP_TOKEN);
      adapter.retryMillis = 25;
      engine.registerAdapter(adapter);
      configure(spec);
    }
    void configure(AMap<AString,ACell> spec) { bots(Maps.of("main", spec)); }
    void bots(AMap<AString,ACell> bots) { engine.configureAdapter(NAME, Maps.of("apiUrl", api.url(), "bots", bots)); }
    WebhookResponse webhook(ACell payload) { return adapter.handleWebhook("c-main", signed(payload)); }
    WebhookBot<BotSpec> bot() { return adapter.runner("main"); }
    ACell run(String op, ACell input) throws Exception { return TestSupport.run(engine, OWNER, "v/ops/" + NAME + "/" + op, input); }
    @Override public void close() { engine.close(); api.close(); }
  }
}
