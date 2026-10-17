package covia.adapter.speech;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import convex.core.lang.RT;
import covia.exception.JobFailedException;

/**
 * The local engine without sherpa-onnx's natives: a long note is split on its pauses
 * into pieces of at most 30 seconds; the model's files are fetched once, pinned; the
 * libraries come from nativePath or a fetched pinned pack and load from a fresh copy,
 * ONNX Runtime first; anything unpinned, unconfigured or unspoken is refused.
 */
class LocalSpeechEngineTest {

	@TempDir Path temp;
	HttpServer server;
	final Map<String, byte[]> served = new ConcurrentHashMap<>();
	final List<String> loaded = new CopyOnWriteArrayList<>();

	@BeforeEach void serve() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			try (exchange) {
				byte[] body = served.get(exchange.getRequestURI().getPath());
				if (body == null) { exchange.sendResponseHeaders(404, -1); return; }
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
			}
		});
		server.start();
	}

	@AfterEach void stop() { server.stop(0); }

	String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }

	static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	/** A one-file model served here, pinned to what is served. */
	SpeechModels.Model model(byte[] content) throws Exception {
		served.put("/model/tokens.txt", content);
		return new SpeechModels.Model("test-model", "Test", "English", "MIT", SpeechModels.Kind.MOONSHINE,
			List.of(new SpeechModels.ModelFile("tokens.txt", url("/model/tokens.txt"), content.length, sha256(content))));
	}

	/** A recogniser that hears each piece's length, and finds a stretch every 10 seconds. */
	static final class Counting implements LocalSpeechEngine.Backend {
		final List<Integer> decoded = new CopyOnWriteArrayList<>();
		@Override public List<float[]> stretches(float[] samples) {
			List<float[]> out = new ArrayList<>();
			for (int at = 0; at < samples.length; at += 160_000) out.add(java.util.Arrays.copyOfRange(samples, at, Math.min(samples.length, at + 160_000)));
			return out;
		}
		@Override public String decode(float[] samples) {
			decoded.add(samples.length);
			return samples.length == 0 ? " " : "heard" + samples.length / AudioDecoder.RATE;
		}
		@Override public void close() {}
	}

	Path installedLibraries() throws Exception {
		Path dir = Files.createDirectories(temp.resolve("natives"));
		for (String file : NativeLibraries.files()) Files.writeString(dir.resolve(file), "library " + file);
		return dir;
	}

	LocalSpeechEngine engine(SpeechModels.Model model, Path nativePath, boolean fetch, Map<String, NativeLibraries.Pack> packs,
			LocalSpeechEngine.Backend backend) {
		return new LocalSpeechEngine(model, nativePath, fetch, () -> temp.resolve("cache"), 2,
			new NativeLibraries(packs, loaded::add), (m, d, t) -> backend);
	}

	static SpeechEngine.Audio wav(double seconds) {
		return new SpeechEngine.Audio(AudioDecoderTest.header(new byte[(int) (seconds * AudioDecoder.RATE) * 2], AudioDecoder.RATE, 1, 1, 16),
			"audio/wav", "audio.wav");
	}

	@Test void aLongNoteIsSplitOnItsPausesIntoPiecesOfAtMostThirtySeconds() throws Exception {
		Counting backend = new Counting();
		LocalSpeechEngine engine = engine(model("tokens".getBytes(StandardCharsets.UTF_8)), installedLibraries(), false, NativeLibraries.PACKS, backend);
		try {
			assertEquals("heard12", engine.transcribe(wav(12), "test-model", null, null, null).text(), "a short note is decoded whole");
			assertEquals(List.of(12 * AudioDecoder.RATE), backend.decoded);

			backend.decoded.clear();
			SpeechEngine.Transcript long75 = engine.transcribe(wav(75), "test-model", "en", "ignored", null);
			assertEquals(List.of(30, 30, 15), backend.decoded.stream().map(n -> n / AudioDecoder.RATE).toList(), "ten-second stretches merged to 30 s");
			assertEquals("heard30 heard30 heard15", long75.text());
			assertEquals(75.0, long75.seconds(), 0.01);
			assertNull(long75.language());
		} finally {
			engine.close();
		}
	}

	@Test void theModelIsFetchedPinnedAndTheLibrariesLoadFromAFreshCopyRuntimeFirst() throws Exception {
		byte[] tokens = "pinned tokens".getBytes(StandardCharsets.UTF_8);
		LocalSpeechEngine engine = engine(model(tokens), installedLibraries(), false, NativeLibraries.PACKS, new Counting());
		try {
			assertEquals("not downloaded", RT.getIn(engine.status(), "state").toString());
			engine.prepare();
			assertArrayEquals(tokens, Files.readAllBytes(temp.resolve("cache/models/test-model/tokens.txt")));
			assertEquals("ready", RT.getIn(engine.status(), "state").toString());
			assertEquals(2, loaded.size());
			assertTrue(loaded.get(0).endsWith(NativeLibraries.files().get(0)), "ONNX Runtime loads first");
			Path copy = Path.of(loaded.get(0)).getParent();
			assertTrue(copy.startsWith(temp.resolve("cache/native/" + NativeLibraries.VERSION + "/loads")), copy.toString());
			assertNotEquals(temp.resolve("natives"), copy, "never from the installed directory itself");
			engine.prepare();
			assertEquals(2, loaded.size(), "loaded once");
		} finally {
			engine.close();
		}
	}

	@Test void aFetchedPackIsPinnedAndUnpacked() throws Exception {
		String platform = NativeLibraries.platform();
		org.junit.jupiter.api.Assumptions.assumeTrue(platform != null, "sherpa-onnx has no libraries for this platform");
		ByteArrayOutputStream jar = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(jar)) {
			for (String file : NativeLibraries.files()) {
				zip.putNextEntry(new ZipEntry("sherpa-onnx/native/" + platform + "/" + file));
				zip.write(("fetched " + file).getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		served.put("/pack.jar", jar.toByteArray());
		Map<String, NativeLibraries.Pack> packs = Map.of(platform, new NativeLibraries.Pack(url("/pack.jar"), jar.size(), sha256(jar.toByteArray())));
		LocalSpeechEngine engine = engine(model("t".getBytes(StandardCharsets.UTF_8)), null, true, packs, new Counting());
		try {
			engine.prepare();
			assertEquals("fetched " + NativeLibraries.files().get(1), Files.readString(Path.of(loaded.get(1))));
		} finally {
			engine.close();
		}

		Map<String, NativeLibraries.Pack> tampered = Map.of(platform, new NativeLibraries.Pack(url("/pack.jar"), jar.size(), "0".repeat(64)));
		LocalSpeechEngine refused = new LocalSpeechEngine(model("t".getBytes(StandardCharsets.UTF_8)), null, true,
			() -> temp.resolve("other-cache"), 2, new NativeLibraries(tampered, loaded::add), (m, d, t) -> new Counting());
		try {
			JobFailedException e = assertThrows(JobFailedException.class, refused::prepare);
			assertTrue(e.getMessage().contains("not the file that was pinned"), e.getMessage());
			assertFalse(Files.exists(temp.resolve("other-cache/native/" + NativeLibraries.VERSION + "/" + platform + ".jar.part")), "nothing half-fetched stays");
		} finally {
			refused.close();
		}
	}

	@Test void unconfiguredLibrariesOtherModelsAndSpeechAreRefused() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(NativeLibraries.platform() != null, "sherpa-onnx has no libraries for this platform");
		LocalSpeechEngine engine = engine(model("t".getBytes(StandardCharsets.UTF_8)), null, false, NativeLibraries.PACKS, new Counting());
		try {
			JobFailedException missing = assertThrows(JobFailedException.class, () -> engine.transcribe(wav(1), "test-model", null, null, null));
			assertEquals(LocalSpeechEngine.NOT_READY.formatted(NativeLibraries.NOT_INSTALLED), missing.getMessage());
			assertEquals(NativeLibraries.NOT_INSTALLED, RT.getIn(engine.status(), "reason").toString());
			assertEquals(LocalSpeechEngine.OTHER_MODEL.formatted("test-model"), assertThrows(IllegalArgumentException.class,
				() -> engine.transcribe(wav(1), "parakeet-tdt-v3", null, null, null)).getMessage());
			assertEquals(LocalSpeechEngine.SPEAK_UNAVAILABLE, assertThrows(JobFailedException.class,
				() -> engine.speak(new SpeechEngine.SpeakRequest("hi", null, null, "opus", null, null), null)).getMessage());
		} finally {
			engine.close();
		}
		Path partial = Files.createDirectories(temp.resolve("partial"));
		Files.writeString(partial.resolve(NativeLibraries.files().get(0)), "only one");
		LocalSpeechEngine half = engine(model("t".getBytes(StandardCharsets.UTF_8)), partial, false, NativeLibraries.PACKS, new Counting());
		try {
			assertEquals(LocalSpeechEngine.NOT_READY.formatted(NativeLibraries.MISSING.formatted(NativeLibraries.files().get(1), partial)),
				assertThrows(JobFailedException.class, half::prepare).getMessage());
		} finally {
			half.close();
		}
	}
}
