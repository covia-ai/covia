package covia.adapter.speech;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in for an OpenAI-compatible audio API: {@code /audio/transcriptions} answers
 * {@link #transcript}, {@code /audio/speech} answers {@link #SPEECH} as Ogg. With
 * {@link #requireKey}, a request without a bearer token is refused with 401.
 */
final class FakeSpeechApi implements AutoCloseable {
	static final byte[] SPEECH = "OggS-synthesised-speech".getBytes(StandardCharsets.UTF_8);

	record Request(String path, String authorization, String contentType, byte[] body) {
		String text() { return new String(body, StandardCharsets.ISO_8859_1); }
	}

	final HttpServer server;
	final LinkedBlockingQueue<Request> requests = new LinkedBlockingQueue<>();
	volatile String transcript = "{\"text\":\" Hello world. \",\"language\":\"english\",\"duration\":1.5}";
	volatile boolean requireKey;

	FakeSpeechApi() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/", this::handle);
		server.start();
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			String authorization = exchange.getRequestHeaders().getFirst("Authorization");
			requests.add(new Request(exchange.getRequestURI().getPath(), authorization,
				exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestBody().readAllBytes()));
			if (requireKey && authorization == null) {
				exchange.sendResponseHeaders(401, -1);
				return;
			}
			boolean speech = exchange.getRequestURI().getPath().endsWith("/audio/speech");
			byte[] body = speech ? SPEECH : transcript.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", speech ? "audio/ogg" : "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
		}
	}

	String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }

	Request take() throws InterruptedException {
		Request request = requests.poll(8, TimeUnit.SECONDS);
		if (request == null) throw new AssertionError("no request reached the speech API");
		return request;
	}

	@Override public void close() { server.stop(0); }
}
