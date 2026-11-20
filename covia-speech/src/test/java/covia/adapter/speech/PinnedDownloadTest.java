package covia.adapter.speech;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/** Fetches of one pinned file that overlap each succeed, and leave only the file. */
class PinnedDownloadTest {

	@TempDir Path temp;
	HttpServer server;
	final byte[] content = "a pinned file, served in two halves".getBytes(StandardCharsets.UTF_8);
	final int half = content.length / 2;
	/** Each path's second half is held until its latch opens. */
	final Map<String, CountDownLatch> release = Map.of("/a", new CountDownLatch(1), "/b", new CountDownLatch(1));

	@BeforeEach void serve() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.createContext("/", exchange -> {
			try (exchange) {
				exchange.sendResponseHeaders(200, content.length);
				OutputStream out = exchange.getResponseBody();
				out.write(content, 0, half);
				out.flush();
				release.get(exchange.getRequestURI().getPath()).await(10, TimeUnit.SECONDS);
				out.write(content, half, content.length - half);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		server.start();
	}

	@AfterEach void stop() { server.stop(0); }

	String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }

	@Test void aFetchThatFinishesWhileAnotherIsWritingLeavesBothSucceeding() throws Exception {
		String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		Path target = temp.resolve("models/file.bin");
		PinnedDownload download = new PinnedDownload();
		AtomicLong progressA = new AtomicLong();
		AtomicLong progressB = new AtomicLong();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<?> a = executor.submit(() -> { download.fetch(url("/a"), content.length, sha256, target, progressA); return null; });
			awaitTrue(() -> progressA.get() == half); // a has its part file open, half written
			Future<?> b = executor.submit(() -> { download.fetch(url("/b"), content.length, sha256, target, progressB); return null; });
			awaitTrue(() -> progressB.get() == half); // so has b
			release.get("/a").countDown();
			a.get(10, TimeUnit.SECONDS); // a finishes and puts the file in place while b is still writing
			release.get("/b").countDown();
			b.get(10, TimeUnit.SECONDS);
		}
		assertTrue(PinnedDownload.matches(target, content.length, sha256));
		try (Stream<Path> files = Files.list(target.getParent())) {
			assertEquals(1, files.count(), "only the file stays, no part file");
		}
	}

	@Test void authorizesEveryRedirectBeforeSendingIt() throws Exception {
		var reached = new java.util.concurrent.atomic.AtomicInteger();
		server.createContext("/redirect", exchange -> {
			exchange.getResponseHeaders().set("Location", "/blocked");
			exchange.sendResponseHeaders(302, -1); exchange.close();
		});
		server.createContext("/blocked", exchange -> {
			reached.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close();
		});
		var checked = new java.util.ArrayList<String>();
		PinnedDownload download = new PinnedDownload(request -> {
			checked.add(request.uri().getPath());
			if ("/blocked".equals(request.uri().getPath())) throw new covia.exception.AuthException("test refusal");
		});
		Path target = temp.resolve("refused.bin");
		assertThrows(covia.exception.AuthException.class,
			() -> download.fetch(url("/redirect"), 1, "unused", target, new AtomicLong()));
		assertEquals(java.util.List.of("/redirect", "/blocked"), checked);
		assertEquals(0, reached.get());
		assertFalse(Files.exists(target));
	}

	private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) fail("timed out");
			Thread.sleep(5);
		}
	}
}
