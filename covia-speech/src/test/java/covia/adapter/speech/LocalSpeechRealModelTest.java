package covia.adapter.speech;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The local engine for real: sherpa-onnx's pinned libraries fetched for this platform,
 * the smallest model (Moonshine tiny, about 124 MB) fetched, and the model's own pinned
 * recordings transcribed, as WAV at 16 kHz and 8 kHz and re-encoded as an Ogg/Opus
 * voice note; and the pinned voice (Supertonic 3, about 145 MB) fetched, its speech
 * heard back. Nothing is played.
 *
 * <p><b>Opt-in</b>, like the venue's {@code @IntegrationTest}: it reaches the network and
 * loads native code, so it runs only with {@code -Dcovia.tests.integration=true}. The
 * files are kept under {@code target/} between runs.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "covia.tests.integration", matches = "true",
	disabledReason = "downloads a model and loads native code; enable with -Dcovia.tests.integration=true")
class LocalSpeechRealModelTest {

	static final String RECORDINGS = "https://huggingface.co/csukuangfj/sherpa-onnx-moonshine-tiny-en-int8/resolve/"
		+ "bf2b762c076d8ea61e2af0b3851c9564fb77552e/test_wavs/";

	static final Path CACHE = Path.of("target", "speech-cache").toAbsolutePath();

	static Path recording(String name, long bytes, String sha256) throws Exception {
		Path file = CACHE.resolve("recordings").resolve(name);
		new PinnedDownload().fetch(RECORDINGS + name, bytes, sha256, file, new AtomicLong());
		return file;
	}

	@Test void theOperationTranscribesThroughTheAdapter() throws Exception {
		Path wav = recording("0.wav", 212_044, "6bc58a4efdf20daac252b6b1502632601a71efe0308f6757dc1eda34891a7e4f");
		convex.core.data.AString owner = convex.core.data.Strings.create("did:test:speech:real");
		covia.venue.Engine venue = covia.venue.Engine.createTemp(convex.core.data.Maps.of(
			covia.venue.Config.USERS, convex.core.data.Maps.of(covia.venue.Config.AUTO_CREATE, true)));
		try {
			covia.venue.Engine.addDemoAssets(venue);
			venue.registerAdapter(new SpeechAdapter());
			venue.configureAdapter(SpeechAdapter.NAME, convex.core.data.Maps.of("engine", "local", "local", convex.core.data.Maps.of(
				"transcribeModel", "moonshine-tiny", "fetchNatives", true, "cacheDir", CACHE.toString())));
			String ref = owner + "/dlfs/media/0.wav";
			assertTrue(venue.createContent(convex.core.data.Strings.create(ref), Files.newInputStream(wav), "audio/wav",
				covia.venue.RequestContext.of(owner)));
			convex.core.data.ACell heard = venue.jobs().invokeOperation("v/ops/speech/transcribe", convex.core.data.Maps.of("ref", ref),
				covia.venue.RequestContext.of(owner)).awaitResult(300_000);
			assertTrue(String.valueOf(convex.core.lang.RT.getIn(heard, "text")).toLowerCase(Locale.ROOT).contains("yellow lamps"), String.valueOf(heard));
			convex.core.data.ACell status = venue.jobs().invokeOperation("v/ops/speech/engines", convex.core.data.Maps.empty(),
				covia.venue.RequestContext.of(owner)).awaitResult(10_000);
			assertEquals("ready", String.valueOf(convex.core.lang.RT.getIn(convex.core.lang.RT.ensureVector(
				convex.core.lang.RT.getIn(status, "engines")).get(0), "state")));
			assertEquals(convex.core.data.prim.CVMBool.TRUE, convex.core.lang.RT.getIn(convex.core.lang.RT.ensureVector(
				convex.core.lang.RT.getIn(status, "engines")).get(0), "nativeAccess"), "the build grants native access");
		} finally {
			venue.close();
		}
	}

	@Test void theModelsOwnRecordingsAreHeard() throws Exception {
		Path cache = CACHE;
		Path wav16k = recording("0.wav", 212_044, "6bc58a4efdf20daac252b6b1502632601a71efe0308f6757dc1eda34891a7e4f");
		Path wav8k = recording("8k.wav", 77_244, "f6f3c8b33e2534cdc154fe773ad2750f1f6a2ca5096179cdf037ae782456613e");

		LocalSpeechEngine engine = new LocalSpeechEngine(SpeechModels.byId("moonshine-tiny"), null, true, () -> cache, 2,
			new NativeLibraries(), LocalSpeechEngine.Sherpa::new);
		try {
			String heard = text(engine, Files.readAllBytes(wav16k));
			assertTrue(heard.contains("yellow lamps") && heard.contains("nightfall"), heard);
			assertTrue(text(engine, Files.readAllBytes(wav8k)).contains("hester"), "8 kHz is resampled");

			float[] samples = AudioDecoder.decode(Files.readAllBytes(wav16k));
			String voiceNote = text(engine, AudioDecoderTest.oggOpus(samples, AudioDecoder.RATE));
			assertTrue(voiceNote.contains("yellow lamps"), "as a voice note: " + voiceNote);

			// Dictated half a second at a time, as an app sends a microphone's audio.
			LocalSpeechEngine.Dictation dictation = engine.dictate();
			for (int at = 0; at < samples.length; at += AudioDecoder.RATE / 2) {
				dictation.hear(java.util.Arrays.copyOfRange(samples, at, Math.min(samples.length, at + AudioDecoder.RATE / 2)), false);
			}
			String dictated = dictation.hear(new float[0], true).settled().toLowerCase(Locale.ROOT);
			assertTrue(dictated.contains("yellow lamps") && dictated.contains("nightfall"), "dictated: " + dictated);
		} finally {
			engine.close();
		}
	}

	@Test void eachVoiceSaysWhatIsHeardBack() throws Exception {
		for (SpeechModels.Model voice : SpeechModels.VOICES) {
			LocalSpeechEngine engine = new LocalSpeechEngine(SpeechModels.byId("moonshine-tiny"), voice,
				null, true, () -> CACHE, 2, new NativeLibraries(), LocalSpeechEngine.Sherpa::new, LocalSpeechEngine.SherpaVoice::new);
			try {
				long started = System.nanoTime();
				SpeechEngine.Speech speech = engine.speak(new SpeechEngine.SpeakRequest(
					"The yellow lamps would light up here and there, at 4 o'clock, for Brightside.",
					null, null, "wav", null, null, null), null);
				long ms = (System.nanoTime() - started) / 1_000_000;
				java.nio.ByteBuffer wav = java.nio.ByteBuffer.wrap(speech.bytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
				double seconds = wav.getInt(40) / 2.0 / wav.getInt(24);
				System.out.printf("speech: %s: %.2f s of audio at %d Hz in %d ms (first use, fetching and loading included); spelled %s%n",
					voice.id(), seconds, wav.getInt(24), ms, speech.spelled());
				assertTrue(seconds > 1 && seconds < 15, "a sentence's worth: " + seconds);
				started = System.nanoTime();
				engine.speak(new SpeechEngine.SpeakRequest("And again, now that the voice is loaded.", null, "0", "wav", null, null, null), null);
				System.out.printf("speech: %s: again in %d ms%n", voice.id(), (System.nanoTime() - started) / 1_000_000);
				String heard = text(engine, speech.bytes());
				System.out.printf("speech: %s: heard back \"%s\"%n", voice.id(), heard);
				assertTrue(heard.contains("yellow lamps"), voice.id() + " heard back: " + heard);
				assertTrue(heard.contains("four") || heard.contains("4"), voice.id() + " says the number: " + heard);
				assertEquals("ready", String.valueOf(convex.core.lang.RT.getIn(engine.status(), "voiceState")));
			} finally {
				engine.close();
			}
		}
	}

	private static String text(LocalSpeechEngine engine, byte[] audio) {
		return engine.transcribe(new SpeechEngine.Audio(audio, "audio/wav", "audio.wav"), "moonshine-tiny", null, null, null)
			.text().toLowerCase(Locale.ROOT);
	}
}
