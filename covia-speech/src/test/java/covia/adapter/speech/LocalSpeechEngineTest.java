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
 * ONNX Runtime first; a voice is fetched the same way and speaks 16-bit WAV at its own
 * rate; anything unpinned, unconfigured or unspoken is refused.
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

	/**
	 * A recogniser that hears each piece's length, and finds a stretch every 10 seconds
	 * in a note and a pause every 2 seconds in a dictation; unless told otherwise, the
	 * end of a dictation closes the stretch under way.
	 */
	static final class Counting implements LocalSpeechEngine.Backend {
		static final int DICTATED_STRETCH = 2 * AudioDecoder.RATE;
		final List<Integer> decoded = new CopyOnWriteArrayList<>();
		volatile boolean pausesReleased;
		volatile boolean flushCloses = true;
		volatile boolean speaking = true;
		@Override public List<float[]> stretches(float[] samples) {
			List<float[]> out = new ArrayList<>();
			for (int at = 0; at < samples.length; at += 160_000) out.add(java.util.Arrays.copyOfRange(samples, at, Math.min(samples.length, at + 160_000)));
			return out;
		}
		@Override public String decode(float[] samples) {
			decoded.add(samples.length);
			return samples.length == 0 ? " " : "heard" + samples.length / AudioDecoder.RATE;
		}
		@Override public LocalSpeechEngine.Pauses pauses(int maxSeconds) {
			return new LocalSpeechEngine.Pauses() {
				int fed;
				final List<Integer> closed = new ArrayList<>();
				@Override public void accept(float[] window) {
					int before = fed;
					fed += window.length;
					if (before / DICTATED_STRETCH != fed / DICTATED_STRETCH) closed.add(fed / DICTATED_STRETCH * DICTATED_STRETCH);
				}
				@Override public List<Integer> closed() {
					List<Integer> out = List.copyOf(closed);
					closed.clear();
					return out;
				}
				@Override public boolean speaking() { return speaking; }
				@Override public void flush() { if (flushCloses) closed.add(fed); }
				@Override public void release() { pausesReleased = true; }
			};
		}
		@Override public void close() {}
	}

	static float[] seconds(double s) {
		return new float[(int) (s * AudioDecoder.RATE)];
	}

	/** Whole windows of the pause finder, so a dictation's end leaves nothing it was not given. */
	static float[] windows(int n) {
		return new float[n * LocalSpeechEngine.WINDOW];
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

	/** A one-file voice model served here, pinned to what is served. */
	SpeechModels.Model voice(byte[] content) throws Exception {
		served.put("/voice/voice.bin", content);
		return new SpeechModels.Model("test-voice", "Test voice", "English", "MIT", SpeechModels.Kind.SUPERTONIC,
			List.of(new SpeechModels.ModelFile("voice.bin", url("/voice/voice.bin"), content.length, sha256(content))));
	}

	/** A synthesiser with three voices that says a hundred samples a character, at 24 kHz, and remembers how it was asked. */
	static final class Saying implements LocalSpeechEngine.Speaker {
		final List<String> asked = new CopyOnWriteArrayList<>();
		volatile float level = 0.5f;
		@Override public int voices() { return 3; }
		@Override public LocalSpeechEngine.Said say(String text, int sid, float speed, String language) {
			asked.add(text + "|" + sid + "|" + speed + "|" + language);
			float[] samples = new float[text.length() * 100];
			java.util.Arrays.fill(samples, level);
			return new LocalSpeechEngine.Said(samples, 24_000);
		}
		@Override public void close() {}
	}

	/** An engine that only speaks, with {@code speaker} for its voice. */
	LocalSpeechEngine speaking(SpeechModels.Model voice, LocalSpeechEngine.Speaker speaker) throws Exception {
		return new LocalSpeechEngine(null, voice, installedLibraries(), false, () -> temp.resolve("cache"), 2,
			new NativeLibraries(NativeLibraries.PACKS, loaded::add), (m, d, t) -> new Counting(), (m, d, t) -> speaker);
	}

	static SpeechEngine.SpeakRequest wavOf(String text, String voice, Double speed, String language) {
		return new SpeechEngine.SpeakRequest(text, null, voice, "wav", speed, null, language);
	}

	@Test void aVoiceIsFetchedPinnedAndSpeaksWavAtItsOwnRate() throws Exception {
		byte[] style = "pinned voice".getBytes(StandardCharsets.UTF_8);
		Saying speaker = new Saying();
		LocalSpeechEngine engine = speaking(voice(style), speaker);
		try {
			assertEquals(convex.core.data.prim.CVMBool.TRUE, RT.getIn(engine.status(), "speaks"));
			assertEquals(convex.core.data.prim.CVMBool.FALSE, RT.getIn(engine.status(), "transcribes"));
			assertEquals("not downloaded", RT.getIn(engine.status(), "voiceState").toString());
			assertNull(RT.getIn(engine.status(), "state"), "nothing to transcribe with, so no readiness for it");
			assertEquals("test-voice", RT.getIn(engine.status(), "voiceModel", "id").toString());

			SpeechEngine.Speech speech = engine.speak(wavOf("Hello", "2", 1.5, null), null);
			assertEquals("audio/wav", speech.mediaType());
			java.nio.ByteBuffer wav = java.nio.ByteBuffer.wrap(speech.bytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
			assertEquals("RIFF", new String(speech.bytes(), 0, 4, StandardCharsets.US_ASCII));
			assertEquals(24_000, wav.getInt(24), "at the voice's own rate");
			assertEquals(1, wav.getShort(22), "mono");
			assertEquals(16, wav.getShort(34), "16-bit");
			assertEquals(500 * 2, wav.getInt(40), "every sample");
			assertEquals(Math.round(0.5f * Short.MAX_VALUE), wav.getShort(44));
			assertEquals(List.of("Hello|2|1.5|" + LocalSpeechEngine.DEFAULT_LANGUAGE), speaker.asked, "voice, speed and the default language");

			assertArrayEquals(style, Files.readAllBytes(temp.resolve("cache/models/test-voice/voice.bin")));
			assertEquals("ready", RT.getIn(engine.status(), "voiceState").toString());
			engine.speak(new SpeechEngine.SpeakRequest("Bonjour", "test-voice", null, "wav", null, "ignored", "fr"), null);
			assertEquals("Bonjour|0|1.0|fr", speaker.asked.get(1), "voice 0 when none is named; instructions ignored");

			speaker.level = 3f;
			java.nio.ByteBuffer loud = java.nio.ByteBuffer.wrap(engine.speak(wavOf("x", null, null, null), null).bytes())
				.order(java.nio.ByteOrder.LITTLE_ENDIAN);
			assertEquals(Short.MAX_VALUE, loud.getShort(44), "clipped, not wrapped");
		} finally {
			engine.close();
		}
	}

	@Test void aLexiconVoiceIsGivenOnlyWordsItHasAndSaysWhatItSpelled() throws Exception {
		byte[] lexicon = String.join("\n", "the t ə", "meeting m i t ɪ ŋ", "is ɪ z", "at æ t", "four f ɔ ɹ", "a e ɪ", "p p i",
			"i a ɪ", "").getBytes(StandardCharsets.UTF_8);
		served.put("/lexicon/lexicon.txt", lexicon);
		SpeechModels.Model vits = new SpeechModels.Model("test-lexicon", "Test lexicon voice", "English", "Apache-2.0",
			SpeechModels.Kind.VITS, List.of(new SpeechModels.ModelFile("lexicon.txt", url("/lexicon/lexicon.txt"), lexicon.length, sha256(lexicon))));
		Saying speaker = new Saying();
		LocalSpeechEngine engine = speaking(vits, speaker);
		try {
			SpeechEngine.Speech speech = engine.speak(wavOf("The meeting is at 4. API", null, null, null), null);
			assertEquals(List.of("the meeting is at four. a p i|0|1.0|en"), speaker.asked, "every word one the voice has");
			assertEquals(List.of("API"), speech.spelled(), "and what was spelled is said to have been");
			assertEquals(Lexicon.UNSAYABLE.formatted("東京"), assertThrows(JobFailedException.class,
				() -> engine.speak(wavOf("The meeting is at 東京", null, null, null), null)).getMessage(), "fails loudly");
			assertEquals(LocalSpeechEngine.ENGLISH_ONLY.formatted("test-lexicon"), assertThrows(IllegalArgumentException.class,
				() -> engine.speak(wavOf("the", null, null, "fr"), null)).getMessage());
			assertEquals(1, speaker.asked.size(), "nothing it could not say reached the voice");
		} finally {
			engine.close();
		}
	}

	@Test void aVoiceRefusesWhatItCannotSay() throws Exception {
		Saying speaker = new Saying();
		LocalSpeechEngine engine = speaking(voice("v".getBytes(StandardCharsets.UTF_8)), speaker);
		try {
			assertEquals(LocalSpeechEngine.WAV_ONLY, assertThrows(IllegalArgumentException.class,
				() -> engine.speak(new SpeechEngine.SpeakRequest("hi", null, null, "opus", null, null, null), null)).getMessage());
			assertEquals(LocalSpeechEngine.OTHER_MODEL.formatted("test-voice"), assertThrows(IllegalArgumentException.class,
				() -> engine.speak(new SpeechEngine.SpeakRequest("hi", "supertonic-3", null, "wav", null, null, null), null)).getMessage(),
				"a caller cannot make the venue fetch another voice");
			assertEquals(LocalSpeechEngine.VOICE_NUMBER, assertThrows(IllegalArgumentException.class,
				() -> engine.speak(wavOf("hi", "alloy", null, null), null)).getMessage());
			assertEquals(LocalSpeechEngine.NO_SUCH_VOICE.formatted("test-voice", 2), assertThrows(IllegalArgumentException.class,
				() -> engine.speak(wavOf("hi", "3", null, null), null)).getMessage());
			assertEquals(LocalSpeechEngine.NOTHING_SAID, assertThrows(JobFailedException.class,
				() -> engine.speak(wavOf("", null, null, null), null)).getMessage(), "no audio is not speech");
			assertEquals(1, speaker.asked.size(), "only what reached the voice was said");

			assertEquals(LocalSpeechEngine.TRANSCRIBE_UNAVAILABLE, assertThrows(JobFailedException.class,
				() -> engine.transcribe(wav(1), "test-model", null, null, null)).getMessage(), "it only speaks");
			assertEquals(LocalSpeechEngine.TRANSCRIBE_UNAVAILABLE, assertThrows(JobFailedException.class, engine::prepare).getMessage());
			assertEquals(LocalSpeechEngine.TRANSCRIBE_UNAVAILABLE, assertThrows(JobFailedException.class, engine::dictate).getMessage());
		} finally {
			engine.close();
		}
	}

	@Test void oneEngineTranscribesAndSpeaksWithAModelForEach() throws Exception {
		Saying speaker = new Saying();
		LocalSpeechEngine engine = new LocalSpeechEngine(model("tokens".getBytes(StandardCharsets.UTF_8)), voice("v".getBytes(StandardCharsets.UTF_8)),
			installedLibraries(), false, () -> temp.resolve("cache"), 2, new NativeLibraries(NativeLibraries.PACKS, loaded::add),
			(m, d, t) -> new Counting(), (m, d, t) -> speaker);
		try {
			engine.prepareVoice();
			assertEquals("ready", RT.getIn(engine.status(), "voiceState").toString());
			assertEquals("not downloaded", RT.getIn(engine.status(), "state").toString(), "getting the voice ready fetches no transcription model");
			engine.prepare();
			assertEquals("ready", RT.getIn(engine.status(), "state").toString());
			assertEquals(2, loaded.size(), "the libraries load once for both");
			assertEquals("heard1", engine.transcribe(wav(1), "test-model", null, null, null).text());
			assertEquals("audio/wav", engine.speak(wavOf("hi", null, null, null), null).mediaType());
		} finally {
			engine.close();
		}
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

	@Test void aDictationSettlesAtEachPauseAndLooksAgainAtWhatIsStillBeingSpoken() throws Exception {
		Counting backend = new Counting();
		LocalSpeechEngine engine = engine(model("tokens".getBytes(StandardCharsets.UTF_8)), installedLibraries(), false, NativeLibraries.PACKS, backend);
		try {
			engine.prepare();
			LocalSpeechEngine.Dictation dictation = engine.dictate();
			LocalSpeechEngine.Heard first = dictation.hear(windows(32), false);
			assertEquals(new LocalSpeechEngine.Heard("", "heard1", 0), first, "the words so far, before any pause");
			assertEquals(first, dictation.hear(windows(8), false), "a quarter second more is not worth another look");

			LocalSpeechEngine.Heard paused = dictation.hear(windows(48), false);
			assertEquals("heard2", paused.settled(), "the stretch up to the pause settles, decoded once");
			assertEquals("heard0", paused.partial(), "and the next is looked at from the pause");
			assertEquals("heard2 heard0", paused.text());

			backend.decoded.clear();
			LocalSpeechEngine.Heard ended = dictation.hear(new float[0], true);
			assertEquals(new LocalSpeechEngine.Heard("heard2 heard0", "", 0), ended, "at the end the rest settles");
			assertEquals(1, backend.decoded.size(), "the stretch under way is decoded once more, and nothing else");
			assertTrue(backend.pausesReleased, "and the pause finder is let go");
			assertThrows(IllegalStateException.class, () -> dictation.hear(seconds(1), false), "an ended dictation hears no more");
		} finally {
			engine.close();
		}
	}

	@Test void aShortLastWordIsHeardWithTheStretchBeforeIt() throws Exception {
		Counting backend = new Counting();
		backend.flushCloses = false;
		LocalSpeechEngine engine = engine(model("tokens".getBytes(StandardCharsets.UTF_8)), installedLibraries(), false, NativeLibraries.PACKS, backend);
		try {
			engine.prepare();
			LocalSpeechEngine.Dictation dictation = engine.dictate();
			assertEquals("heard2", dictation.hear(windows(70), false).settled(), "a stretch closed at a pause");

			// After it, a word too short for the pause finder — a lone "go" — that it never closes.
			backend.decoded.clear();
			LocalSpeechEngine.Heard ended = dictation.hear(new float[0], true);
			assertEquals(List.of(70 * LocalSpeechEngine.WINDOW), backend.decoded,
				"the last stretch is heard again with what followed it, as one piece");
			assertEquals(new LocalSpeechEngine.Heard("heard2", "", 0), ended, "its words replace the stretch's own");
		} finally {
			engine.close();
		}
	}

	@Test void theSpeakerIsQuietFromWhereTheirLastStretchEnded() throws Exception {
		Counting backend = new Counting();
		LocalSpeechEngine engine = engine(model("tokens".getBytes(StandardCharsets.UTF_8)), installedLibraries(), false, NativeLibraries.PACKS, backend);
		try {
			engine.prepare();
			backend.speaking = false;
			assertTrue(engine.dictate().hear(windows(10), false).quiet() < 0, "nothing has been said yet");

			backend.speaking = true;
			LocalSpeechEngine.Dictation dictation = engine.dictate();
			LocalSpeechEngine.Heard speaking = dictation.hear(windows(70), false);
			assertEquals(0, speaking.quiet(), "speech is under way");
			assertEquals("heard0", speaking.partial());

			backend.speaking = false;
			LocalSpeechEngine.Heard lull = dictation.hear(windows(32), false);
			assertEquals((102 * LocalSpeechEngine.WINDOW - Counting.DICTATED_STRETCH) / (double) AudioDecoder.RATE, lull.quiet(), 1e-9,
				"counted from where the last stretch ended");
			assertEquals("heard0", lull.partial(), "and the words heard since stay in view through the lull");
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
				() -> engine.speak(new SpeechEngine.SpeakRequest("hi", null, null, "wav", null, null, null), null)).getMessage(),
				"an engine with no voice does not speak");
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
