package covia.adapter.speech;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.ANumeric;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.AAdapter;
import covia.exception.JobFailedException;

/**
 * The OpenAI-compatible audio API: {@code POST /audio/transcriptions} (multipart, the
 * file's extension naming its format) and {@code POST /audio/speech} (JSON in, audio
 * out). OpenAI serves it, and so do local servers such as speaches and LocalAI;
 * pointed at one of those on loopback, no audio leaves the machine. Responses are
 * bounded in size and time; requests are never retried.
 */
final class OpenAiSpeechEngine implements SpeechEngine {
	public static final String NAME = "openai-compatible";
	public static final String HTTP_ERROR = "Speech provider returned HTTP %d";
	public static final String KEY_REJECTED = "Speech provider refused the request (HTTP %d); store your key as s/%s, or have the operator configure adapters.speech.apiKey";
	public static final String IO_ERROR = "Speech provider request failed";
	public static final String INVALID_RESPONSE = "Speech provider returned an invalid response";
	public static final String TOO_LARGE = "Speech provider response exceeds the size limit";
	private static final int MAX_JSON = 1024 * 1024;

	/** Shared by every engine: each HttpClient holds a selector thread for its lifetime. */
	private static final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NEVER).executor(AAdapter.VIRTUAL_EXECUTOR).build();
	private final String baseUrl;
	private final Duration timeout;
	private final int maxAudio;
	private final String keyName;

	/**
	 * @param baseUrl  the API root, such as {@code https://api.openai.com/v1}, without a trailing slash
	 * @param maxAudio the most bytes of synthesised audio accepted
	 * @param keyName  the caller's secret name, for the hint when a provider refuses
	 */
	OpenAiSpeechEngine(String baseUrl, Duration timeout, int maxAudio, String keyName) {
		this.baseUrl = baseUrl;
		this.timeout = timeout;
		this.maxAudio = maxAudio;
		this.keyName = keyName;
	}

	@Override public String name() { return NAME; }

	@Override public boolean local() { return loopback(URI.create(baseUrl).getHost()); }

	@Override public String endpoint() { return baseUrl; }

	static boolean loopback(String host) {
		return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
	}

	@Override
	public Transcript transcribe(Audio audio, String model, String language, String prompt, String key) {
		String boundary = "covia-" + UUID.randomUUID();
		ByteArrayOutputStream body = new ByteArrayOutputStream(audio.bytes().length + 1024);
		field(body, boundary, "model", model);
		field(body, boundary, "response_format", "json");
		if (language != null) field(body, boundary, "language", language);
		if (prompt != null) field(body, boundary, "prompt", prompt);
		write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + audio.fileName()
			+ "\"\r\nContent-Type: " + audio.mediaType() + "\r\n\r\n");
		body.writeBytes(audio.bytes());
		write(body, "\r\n--" + boundary + "--\r\n");
		HttpRequest request = request("/audio/transcriptions", key)
			.header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
		AMap<AString, ACell> result = json(send(request, MAX_JSON, key).bytes());
		AString text = RT.ensureString(result.get(Strings.intern("text")));
		if (text == null) throw new JobFailedException(INVALID_RESPONSE);
		AString detected = RT.ensureString(result.get(Strings.intern("language")));
		Double seconds = result.get(Strings.intern("duration")) instanceof ANumeric n ? n.doubleValue() : null;
		return new Transcript(text.toString().strip(), detected == null ? null : detected.toString(), seconds);
	}

	@Override
	public Speech speak(SpeakRequest speak, String key) {
		AMap<AString, ACell> json = Maps.of("model", speak.model(), "input", speak.text(), "voice", speak.voice(),
			"response_format", speak.format());
		if (speak.speed() != null) json = json.assoc(Strings.intern("speed"), convex.core.data.prim.CVMDouble.create(speak.speed()));
		if (speak.instructions() != null) json = json.assoc(Strings.intern("instructions"), Strings.create(speak.instructions()));
		HttpRequest request = request("/audio/speech", key).header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(JSON.print(json).toString(), StandardCharsets.UTF_8)).build();
		Body response = send(request, maxAudio, key);
		if (response.bytes().length == 0) throw new JobFailedException(INVALID_RESPONSE);
		return new Speech(response.bytes(), response.contentType());
	}

	private HttpRequest.Builder request(String path, String key) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout);
		if (key != null) builder.header("Authorization", "Bearer " + key);
		return builder;
	}

	/** The whole exchange, body included, within the timeout and at most {@code max} bytes. */
	private Body send(HttpRequest request, int max, String key) {
		long deadline = System.nanoTime() + timeout.toNanos();
		CompletableFuture<HttpResponse<InputStream>> pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
		try {
			HttpResponse<InputStream> response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
			InputStream in = response.body();
			CompletableFuture<byte[]> read = CompletableFuture.supplyAsync(() -> {
				try { return in.readNBytes(max + 1); }
				catch (IOException e) { throw new UncheckedIOException(e); }
			}, AAdapter.VIRTUAL_EXECUTOR);
			byte[] bytes;
			try { bytes = read.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
			finally { in.close(); }
			int status = response.statusCode();
			if ((status == 401 || status == 403) && key == null) throw new JobFailedException(KEY_REJECTED.formatted(status, keyName));
			if (status < 200 || status >= 300) throw new JobFailedException(HTTP_ERROR.formatted(status));
			if (bytes.length > max) throw new JobFailedException(TOO_LARGE);
			return new Body(bytes, response.headers().firstValue("Content-Type").orElse(null));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new JobFailedException(IO_ERROR);
		} catch (ExecutionException | TimeoutException | IOException e) {
			throw new JobFailedException(IO_ERROR);
		} finally {
			if (!pending.isDone()) pending.cancel(true);
		}
	}

	private static AMap<AString, ACell> json(byte[] body) {
		try {
			ACell parsed = JSON.parse(new String(body, StandardCharsets.UTF_8));
			if (parsed instanceof AMap<?, ?> map) return RT.castMap(map);
		} catch (RuntimeException e) {
			// fall through to the constant failure: provider bodies are not echoed
		}
		throw new JobFailedException(INVALID_RESPONSE);
	}

	private static void field(ByteArrayOutputStream body, String boundary, String name, String value) {
		write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
	}

	private static void write(ByteArrayOutputStream body, String text) {
		body.writeBytes(text.getBytes(StandardCharsets.UTF_8));
	}

	/** A successful response's body, read within the bound, and its declared type. */
	private record Body(byte[] bytes, String contentType) {}

	/** Media types of the formats the speech endpoint returns. */
	static final Map<String, String> FORMATS = Map.of("opus", "audio/ogg", "mp3", "audio/mpeg", "aac", "audio/aac",
		"flac", "audio/flac", "wav", "audio/wav");
}
