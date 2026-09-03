package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Fields;
import covia.venue.Config;
import covia.venue.TestHTTP;
import covia.venue.server.VenueServer;

/**
 * Closing a venue while an MCP SSE stream is open must end the stream and let
 * its handler unwind <em>before</em> the engine and store go away. The
 * handler blocks the request thread for the life of the stream; its route
 * after-hook then syncs lattice state, which against a closed store surfaced
 * as {@code StoreException: ... ClosedChannelException} plus a Javalin
 * {@code IllegalStateException: WRITER} on the console at shutdown.
 *
 * <p>One throwaway venue: the test is about closing it. Asserting "no ERROR
 * was logged" relies on the passing-build invariant that nothing else logs at
 * ERROR while the suite runs.</p>
 */
public class MCPShutdownTest {

	@Test
	public void closeEndsOpenStreamsBeforeReleasingTheEngine() throws Exception {
		VenueServer server = VenueServer.launch(Maps.of(
			Strings.create("port"), 0,
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Fields.MCP, Maps.of(
				Strings.create("includePathPrefixes"), Vectors.of(Strings.create("v/ops/")),
				Strings.create("includeAdapters"), Vectors.of(Strings.create("*"))),
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(
				Config.ENABLED, true, Config.CAPS, Strings.create("unrestricted")))));
		String base = "http://localhost:" + server.port();
		HttpClient http = TestHTTP.CLIENT;

		HttpResponse<String> init = http.send(HttpRequest.newBuilder(URI.create(base + "/mcp"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":1}"))
			.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, init.statusCode(), init.body());
		String sessionId = init.headers().firstValue("Mcp-Session-Id").orElseThrow();

		// Open the stream on another thread; it blocks until the venue ends it.
		CompletableFuture<Integer> firstByte = new CompletableFuture<>();
		CompletableFuture<Boolean> streamEnded = new CompletableFuture<>();
		Thread.ofVirtual().start(() -> {
			try {
				HttpResponse<InputStream> stream = http.send(HttpRequest.newBuilder(URI.create(base + "/mcp"))
					.header("Accept", "text/event-stream")
					.header("Mcp-Session-Id", sessionId)
					.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
				try (InputStream in = stream.body()) {
					firstByte.complete(in.read());
					while (in.read() != -1) { /* drain until the server ends the stream */ }
				}
			} catch (Exception e) {
				firstByte.completeExceptionally(e);
			} finally {
				streamEnded.complete(true);
			}
		});
		assertTrue(firstByte.get(10, TimeUnit.SECONDS) >= 0, "stream established (first keepalive byte)");
		assertEquals(1, server.getMcp().activeStreams(), "one stream handler is blocked in the venue");

		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> captured = new ListAppender<>();
		captured.start();
		root.addAppender(captured);
		long started = System.nanoTime();
		try {
			server.close();
		} finally {
			root.detachAppender(captured);
		}
		long closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

		assertEquals(0, server.getMcp().activeStreams(), "the stream handler unwound during close");
		assertTrue(streamEnded.get(5, TimeUnit.SECONDS), "the client saw the stream end");
		assertTrue(closeMillis < 5000, "close must not wait out the 30 s keepalive: " + closeMillis + " ms");
		List<ILoggingEvent> errors = captured.list.stream()
			.filter(e -> e.getLevel().isGreaterOrEqual(Level.ERROR)).toList();
		assertTrue(errors.isEmpty(), "shutdown logged at ERROR: " + errors);
	}
}
