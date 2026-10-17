package covia.adapter.speech;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

import covia.adapter.AAdapter;

/**
 * A download that is the pinned file or nothing: fetched to a {@code .part} file,
 * checked against its size and sha256 as it arrives, and moved into place only when
 * both match. Redirects are followed (model hosts redirect to their storage), but only
 * over HTTPS, except to loopback.
 */
final class PinnedDownload {
	static final String NOT_PINNED = "%s is not the file that was pinned (%d bytes, sha256 %s)";
	static final String FAILED = "%s could not be downloaded (%s)";
	private static final Duration TIMEOUT = Duration.ofMinutes(30);

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
		.followRedirects(HttpClient.Redirect.NORMAL).executor(AAdapter.VIRTUAL_EXECUTOR).build();

	/**
	 * @param progress incremented by the bytes received, and given back if the file fails
	 */
	void fetch(String url, long bytes, String sha256, Path target, AtomicLong progress) throws IOException {
		if (matches(target, bytes, sha256)) return;
		URI uri = URI.create(url);
		if (!"https".equals(uri.getScheme()) && !OpenAiSpeechEngine.loopback(uri.getHost())) throw new IOException(FAILED.formatted(url, "not HTTPS"));
		Files.createDirectories(target.getParent());
		Path part = target.resolveSibling(target.getFileName() + ".part");
		long before = progress.get();
		try {
			HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build(),
				HttpResponse.BodyHandlers.ofInputStream());
			URI landed = response.uri();
			if (!"https".equals(landed.getScheme()) && !OpenAiSpeechEngine.loopback(landed.getHost())) {
				response.body().close();
				throw new IOException(FAILED.formatted(url, "redirected away from HTTPS"));
			}
			if (response.statusCode() != 200) {
				response.body().close();
				throw new IOException(FAILED.formatted(url, "HTTP " + response.statusCode()));
			}
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			long received = 0;
			byte[] buffer = new byte[1 << 16];
			try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(part)) {
				for (int r; (r = in.read(buffer)) > 0;) {
					received += r;
					if (received > bytes) throw new IOException(NOT_PINNED.formatted(url, received, "(still arriving)"));
					out.write(buffer, 0, r);
					sha.update(buffer, 0, r);
					progress.addAndGet(r);
				}
			}
			String got = HexFormat.of().formatHex(sha.digest());
			if (received != bytes || !got.equals(sha256)) throw new IOException(NOT_PINNED.formatted(url, received, got));
			Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException(FAILED.formatted(url, "interrupted"));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		} catch (IOException | RuntimeException e) {
			progress.set(before);
			throw e;
		} finally {
			Files.deleteIfExists(part);
		}
	}

	/** Whether a file is already in place with the pinned size and hash. */
	static boolean matches(Path file, long bytes, String sha256) throws IOException {
		if (!Files.isRegularFile(file) || Files.size(file) != bytes) return false;
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[1 << 16];
			for (int r; (r = in.read(buffer)) > 0;) sha.update(buffer, 0, r);
			return HexFormat.of().formatHex(sha.digest()).equals(sha256);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
