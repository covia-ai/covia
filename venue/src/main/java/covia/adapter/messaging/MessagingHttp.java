package covia.adapter.messaging;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.core.data.ACell;
import convex.core.util.JSON;

/** Bounded JSON transport. Sends are never automatically retried after uncertain delivery. */
public final class MessagingHttp {
	public static final String INVALID_URL = "apiUrl must be HTTPS (HTTP is allowed only on loopback), without credentials, query or fragment";
	public static final String UNAVAILABLE = "Messaging credential is unavailable";
	public static final String HTTP_ERROR = "Messaging provider returned HTTP %d";
	public static final String RESPONSE_ERROR = "Messaging provider returned an invalid or oversized response";
	public static final String IO_ERROR = "Messaging request failed; delivery may be uncertain";
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NEVER).build();
	private static final int MAX_RESPONSE = 1024 * 1024;
	private MessagingHttp() { }
	public static String endpoint(String value) {
		try {
			URI uri = URI.create(value);
			String host = uri.getHost();
			boolean local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
			if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
				|| !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))) throw new IllegalArgumentException();
			return value.replaceAll("/+$", "");
		} catch (IllegalArgumentException e) { throw new IllegalArgumentException(INVALID_URL); }
	}
	public static ACell post(String url, String token, ACell body) {
		return post(url, token, body, Duration.ofSeconds(30));
	}
	static ACell post(String url, String token, ACell body, Duration timeout) {
		if (token == null || token.isBlank() || token.contains("\r") || token.contains("\n")) throw new IllegalStateException(UNAVAILABLE);
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
				.header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(JSON.print(body).toString(), StandardCharsets.UTF_8)).build();
			// HttpRequest.timeout does not bound a stalled response body. Bound the
			// complete exchange and cancel unfinished I/O on timeout or interruption.
			var pending = CLIENT.sendAsync(request, info -> new LimitedBodySubscriber(MAX_RESPONSE));
			HttpResponse<byte[]> response;
			try { response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS); }
			finally { if (!pending.isDone()) pending.cancel(true); }
			if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException(HTTP_ERROR.formatted(response.statusCode()));
			try { return MessagingSettings.object(JSON.parse(new String(response.body(), StandardCharsets.UTF_8))); }
			catch (RuntimeException e) { throw new IllegalStateException(RESPONSE_ERROR); }
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt(); throw new IllegalStateException(IO_ERROR);
		} catch (ExecutionException | TimeoutException e) { throw new IllegalStateException(IO_ERROR); }
	}
	private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
		private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
		private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		private final int max;
		private java.util.concurrent.Flow.Subscription subscription;
		LimitedBodySubscriber(int max) { this.max = max; }
		@Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
		@Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) { subscription = s; s.request(1); }
		@Override public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
			for (var buffer : buffers) {
				if (buffer.remaining() > max - bytes.size()) {
					subscription.cancel(); result.completeExceptionally(new IllegalStateException(RESPONSE_ERROR)); return;
				}
				byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
			}
			subscription.request(1);
		}
		@Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
		@Override public void onComplete() { result.complete(bytes.toByteArray()); }
	}
}
