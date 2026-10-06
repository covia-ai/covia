package covia.adapter.messaging;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.util.JSON;

class MessagingHttpTest {
	private static final String TOKEN = "fixture-token";
	private static final Duration TIMEOUT = Duration.ofSeconds(1);

	@Test void sendsUtf8JsonAndReadsTheReceipt() throws Exception {
		try (Server server = new Server(exchange -> {
			assertEquals("POST", exchange.getRequestMethod());
			assertEquals("Bearer " + TOKEN, exchange.getRequestHeaders().getFirst("Authorization"));
			assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
			assertEquals(Maps.of("text", "héllo 🐦"), JSON.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
			reply(exchange, 200, "{\"id\":\"réponse 🐦\"}");
		})) {
			assertEquals(Maps.of("id", "réponse 🐦"), MessagingHttp.post(server.url(), TOKEN, Maps.of("text", "héllo 🐦")));
			assertEquals(1, server.requests.get());
		}
	}

	@Test void rejectsMalformedAndNonObjectReceiptsWithoutLeakingTheirBody() throws Exception {
		for (String response : new String[] {"{private-provider-detail", "[]", "null"}) {
			try (Server server = new Server(exchange -> reply(exchange, 200, response))) {
				var error = assertThrows(IllegalStateException.class, () -> MessagingHttp.post(server.url(), TOKEN, Maps.empty()));
				assertEquals(MessagingHttp.RESPONSE_ERROR, error.getMessage());
				assertNull(error.getCause());
				assertEquals(1, server.requests.get());
			}
		}
	}

	@Test void refusesRedirectsAndDoesNotRetryProviderErrors() throws Exception {
		try (Server destination = new Server(exchange -> reply(exchange, 200, "{}"))) {
			for (int status : new int[] {302, 429, 503}) {
				try (Server server = new Server(exchange -> {
					exchange.getResponseHeaders().set("Location", destination.url());
					exchange.getResponseHeaders().set("Retry-After", "0");
					reply(exchange, status, "{\"private\":\"provider-detail\"}");
				})) {
					assertEquals(MessagingHttp.HTTP_ERROR.formatted(status), assertThrows(IllegalStateException.class,
						() -> MessagingHttp.post(server.url(), TOKEN, Maps.empty())).getMessage());
					assertEquals(1, server.requests.get());
				}
			}
			assertEquals(0, destination.requests.get(), "Redirects must not forward credentials");
		}
	}

	@Test void boundsChunkedResponsesAtOneMiB() throws Exception {
		int limit = 1024 * 1024;
		String atLimit = "{\"padding\":\"" + "x".repeat(limit - 14) + "\"}";
		assertEquals(limit, atLimit.length());
		try (Server server = new Server(exchange -> {
			exchange.sendResponseHeaders(200, 0); // Chunked: no Content-Length to trust.
			exchange.getResponseBody().write(atLimit.getBytes(StandardCharsets.UTF_8));
		})) {
			assertEquals(Strings.create("x".repeat(limit - 14)),
				convex.core.lang.RT.getIn(MessagingHttp.post(server.url(), TOKEN, Maps.empty()), Strings.intern("padding")));
		}
		try (Server server = new Server(exchange -> {
			exchange.sendResponseHeaders(200, 0);
			exchange.getResponseBody().write((atLimit + " ").getBytes(StandardCharsets.UTF_8));
		})) {
			assertEquals(MessagingHttp.IO_ERROR, assertThrows(IllegalStateException.class,
				() -> MessagingHttp.post(server.url(), TOKEN, Maps.empty())).getMessage());
			assertEquals(1, server.requests.get());
		}
	}

	@Test void timesOutWaitingForHeaders() throws Exception { assertTimeoutAt(false); }
	@Test void timesOutWhileReadingTheBody() throws Exception { assertTimeoutAt(true); }

	private static void assertTimeoutAt(boolean sendHeaders) throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		try (Server server = new Server(exchange -> {
			if (sendHeaders) {
				exchange.sendResponseHeaders(200, 0);
				exchange.getResponseBody().write('{');
				exchange.getResponseBody().flush();
			}
			try { release.await(10, TimeUnit.SECONDS); }
			catch (InterruptedException e) { Thread.currentThread().interrupt(); }
		})) {
			try {
				assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
					assertEquals(MessagingHttp.IO_ERROR, assertThrows(IllegalStateException.class,
						() -> MessagingHttp.post(server.url(), TOKEN, Maps.empty(), TIMEOUT)).getMessage());
				});
				assertEquals(1, server.requests.get());
			} finally { release.countDown(); }
		}
	}

	private static void reply(HttpExchange exchange, int status, String body) throws java.io.IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
	}
	private static final class Server implements AutoCloseable {
		final HttpServer server;
		final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		final AtomicInteger requests = new AtomicInteger();
		Server(HttpHandler handler) throws Exception {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.setExecutor(executor);
			server.createContext("/", exchange -> {
				requests.incrementAndGet();
				try { handler.handle(exchange); }
				finally { exchange.close(); }
			});
			server.start();
		}
		String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/messages"; }
		@Override public void close() { server.stop(0); executor.shutdownNow(); }
	}
}
