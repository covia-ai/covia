package covia.adapter.speech;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.messaging.MessagingMedia;
import covia.api.Fields;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Speech against a stand-in OpenAI-compatible API: a voice note is read with the
 * caller's own authority and transcribed; the operation keeps the media-hook contract
 * (anything but audio returns {@code {}}, silence returns no text); raw PCM travels as
 * WAV; speech is stored as Ogg/Opus in the caller's private drive; keys come from the
 * caller, the operator or nowhere; settings are refused when unsafe. The tests share
 * one stand-in and one adapter configuration, so they run one at a time.
 */
@Execution(ExecutionMode.SAME_THREAD)
class SpeechAdapterTest {

	static FakeSpeechApi api;
	static Engine engine;
	static SpeechAdapter adapter;
	static final AtomicInteger users = new AtomicInteger();
	static final byte[] VOICE = "OggS-a-voice-note".getBytes(StandardCharsets.UTF_8);

	AString owner;

	@BeforeAll static void start() throws Exception {
		api = new FakeSpeechApi();
		engine = Engine.createTemp(Maps.of(Config.USERS, Maps.of(Config.AUTO_CREATE, true)));
		Engine.addDemoAssets(engine);
		adapter = new SpeechAdapter();
		engine.registerAdapter(adapter);
	}

	@AfterAll static void stop() {
		engine.close();
		api.close();
	}

	@BeforeEach void reset() {
		owner = Strings.create("did:test:speech:user" + users.incrementAndGet());
		api.requests.clear();
		api.requireKey = false;
		api.transcript = "{\"text\":\" Hello world. \",\"language\":\"english\",\"duration\":1.5}";
		engine.configureAdapter(SpeechAdapter.NAME, Maps.of("baseUrl", api.baseUrl()));
	}

	ACell run(AString caller, String op, ACell input) throws Exception {
		return engine.jobs().invokeOperation("v/ops/speech/" + op, input, RequestContext.of(caller)).awaitResult(10_000);
	}

	String save(String name, byte[] bytes, String type) throws Exception {
		String ref = owner + "/dlfs/media/" + name;
		assertTrue(engine.createContent(Strings.create(ref), new ByteArrayInputStream(bytes), type, RequestContext.of(owner)));
		return ref;
	}

	@Test void aVoiceNoteIsTranscribedWithTheCallersOwnKeyAndAuthority() throws Exception {
		String ref = save("note.ogg", VOICE, "audio/ogg");
		engine.jobs().invokeOperation("v/ops/secret/set", Maps.of("name", "OPENAI_API_KEY", "value", "caller-key"),
			RequestContext.of(owner)).awaitResult(10_000);
		ACell result = run(owner, "transcribe", Maps.of("ref", ref, "language", "en"));
		assertEquals("Hello world.", RT.getIn(result, "text").toString(), "trimmed");
		assertEquals("english", RT.getIn(result, "language").toString());
		assertEquals(1.5, ((convex.core.data.prim.ANumeric) RT.getIn(result, "seconds")).doubleValue());
		assertEquals(SpeechAdapter.DEFAULT_TRANSCRIBE_MODEL, RT.getIn(result, "model").toString());
		assertEquals(OpenAiSpeechEngine.NAME, RT.getIn(result, "engine").toString());

		FakeSpeechApi.Request request = api.take();
		assertEquals("/v1/audio/transcriptions", request.path());
		assertEquals("Bearer caller-key", request.authorization());
		assertTrue(request.contentType().startsWith("multipart/form-data; boundary="), request.contentType());
		assertTrue(request.text().contains("filename=\"audio.ogg\""), "the extension names the format");
		assertTrue(request.text().contains("name=\"language\"\r\n\r\nen\r\n"));
		assertTrue(request.text().contains("OggS-a-voice-note"));

		AString stranger = Strings.create("did:test:speech:stranger" + users.incrementAndGet());
		assertThrows(Exception.class, () -> run(stranger, "transcribe", Maps.of("ref", ref)),
			"another user cannot have someone else's audio transcribed");
	}

	@Test void itServesAsAMediaOperation() throws Exception {
		String ref = save("voice.ogg", VOICE, "audio/ogg");
		AMap<AString, ACell> photo = RT.castMap(Maps.of("ref", ref, "kind", "photo", "mediaType", "image/jpeg",
			"caption", "", "via", Maps.of("from", "alice")));
		assertEquals(Maps.empty(), run(owner, "transcribe", photo), "not audio: passed through");
		assertTrue(api.requests.isEmpty(), "and nothing was sent");

		AMap<AString, ACell> voice = RT.castMap(Maps.of("ref", ref, "kind", "voice", "mediaType", "audio/ogg; codecs=opus",
			"file_id", "AwACAg", "size", VOICE.length, "caption", "", "via", Maps.of("from", "alice")));
		ACell heard = run(owner, "transcribe", voice);
		AMap<AString, ACell> attachment = MessagingMedia.processed(voice, heard);
		assertEquals("Hello world.", RT.getIn(attachment, "text").toString(), "the transcript joins the attachment");
		assertEquals(ref, RT.getIn(attachment, "ref").toString());

		api.transcript = "{\"text\":\"  \"}";
		ACell silence = run(owner, "transcribe", voice);
		assertNull(RT.getIn(silence, "text"), "silence has no text");
		assertNull(RT.getIn(MessagingMedia.processed(voice, silence), "text"), "and the media hook accepts it");
	}

	@Test void rawPcmTravelsAsWav() throws Exception {
		byte[] pcm = new byte[3200];
		String ref = save("dictation.pcm", pcm, "application/octet-stream");
		run(owner, "transcribe", Maps.of("ref", ref, "mediaType", "audio/L16; rate=8000"));
		byte[] body = api.take().body();
		String text = new String(body, StandardCharsets.ISO_8859_1);
		assertTrue(text.contains("filename=\"audio.wav\""));
		int riff = text.indexOf("RIFF");
		assertTrue(riff > 0, "a WAV header is added");
		assertEquals(8000, ByteBuffer.wrap(body, riff + 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(), "at the declared rate");
		assertEquals(36 + pcm.length, ByteBuffer.wrap(body, riff + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
		assertTrue(failure(() -> run(owner, "transcribe", Maps.of("ref", ref, "mediaType", "audio/L16", "sampleRate", 4000)))
			.contains(SpeechAdapter.INVALID_RATE));
	}

	@Test void speechIsStoredAsAVoiceNoteInTheCallersDrive() throws Exception {
		ACell spoken = run(owner, "speak", Maps.of("text", "Good morning", "voice", "nova", "speed", 1.25));
		String ref = RT.getIn(spoken, "ref").toString();
		assertTrue(ref.startsWith(MessagingMedia.ownerDirectory(owner, SpeechAdapter.DRIVE)), ref);
		assertTrue(ref.endsWith(".ogg"), ref);
		assertEquals("audio/ogg", RT.getIn(spoken, "mediaType").toString());
		assertEquals("nova", RT.getIn(spoken, "voice").toString());
		assertArrayEquals(FakeSpeechApi.SPEECH, engine.resolveContent(Strings.create(ref), RequestContext.of(owner))
			.content().getInputStream().readAllBytes());
		AMap<AString, ACell> sent = RT.castMap(JSON.parse(new String(api.take().body(), StandardCharsets.UTF_8)));
		assertEquals("Good morning", RT.getIn(sent, "input").toString());
		assertEquals("opus", RT.getIn(sent, "response_format").toString());
		assertEquals(SpeechAdapter.DEFAULT_SPEAK_MODEL, RT.getIn(sent, "model").toString());
		assertEquals(1.25, ((convex.core.data.prim.ANumeric) RT.getIn(sent, "speed")).doubleValue());

		ACell named = run(owner, "speak", Maps.of("text", "Again", "format", "wav", "fileName", "greeting.wav"));
		assertEquals("greeting.wav", RT.getIn(named, Fields.FILE_NAME).toString());
		assertEquals("audio/wav", RT.getIn(named, "mediaType").toString());
		assertEquals("greeting(1).wav", RT.getIn(run(owner, "speak", Maps.of("text", "Again", "format", "wav",
			"fileName", "greeting.wav")), Fields.FILE_NAME).toString(), "nothing is overwritten");

		assertTrue(failure(() -> run(owner, "speak", Maps.of("text", "x".repeat(SpeechAdapter.MAX_TEXT + 1)))).contains(SpeechAdapter.TEXT_REQUIRED));
		assertTrue(failure(() -> run(owner, "speak", Maps.of("text", "x", "fileName", "../escape.ogg"))).contains(MessagingMedia.INVALID_FILE_NAME));
	}

	@Test void keysComeFromTheCallerTheOperatorOrNowhere() throws Exception {
		String ref = save("keys.ogg", VOICE, "audio/ogg");
		run(owner, "transcribe", Maps.of("ref", ref));
		assertNull(api.take().authorization(), "a local server is called without a key");
		AMap<AString, ACell> engines = RT.castMap(RT.ensureVector(RT.getIn(run(owner, "engines", Maps.empty()), "engines")).get(0));
		assertEquals(CVMBool.TRUE, engines.get(Strings.create("local")));
		assertEquals(CVMBool.FALSE, engines.get(Strings.create("keyed")));
		assertFalse(String.valueOf(engines).contains(api.baseUrl()), "no endpoint is disclosed");

		api.requireKey = true;
		assertTrue(failure(() -> run(owner, "transcribe", Maps.of("ref", ref)))
			.contains(OpenAiSpeechEngine.KEY_REJECTED.formatted(401, SpeechAdapter.DEFAULT_KEY_NAME)));
		assertTrue(failure(() -> run(owner, "transcribe", Maps.of("ref", ref, "apiKey", "s/MISSING")))
			.contains(SpeechAdapter.KEY_UNRESOLVED.formatted("s/MISSING")));

		engine.jobs().invokeOperation("v/ops/secret/set", Maps.of("name", "SPEECH_KEY", "value", "operator-key"),
			engine.venueContext()).awaitResult(10_000);
		engine.configureAdapter(SpeechAdapter.NAME, Maps.of("baseUrl", api.baseUrl(), "apiKey", "s/SPEECH_KEY"));
		api.requests.clear();
		run(owner, "transcribe", Maps.of("ref", ref));
		assertEquals("Bearer operator-key", api.take().authorization(), "the operator's key serves every caller");
	}

	@Test void theLocalEngineIsChosenByConfigAndReportsWhyItIsNotReady(@org.junit.jupiter.api.io.TempDir java.nio.file.Path cache) throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(NativeLibraries.platform() != null, "sherpa-onnx has no libraries for this platform");
		engine.configureAdapter(SpeechAdapter.NAME, Maps.of("engine", "local",
			"local", Maps.of("transcribeModel", "moonshine-tiny", "cacheDir", cache.toString())));
		AMap<AString, ACell> status = RT.castMap(RT.ensureVector(RT.getIn(run(owner, "engines", Maps.empty()), "engines")).get(0));
		assertEquals(LocalSpeechEngine.NAME, RT.getIn(status, "name").toString());
		assertEquals(CVMBool.TRUE, status.get(Strings.create("local")));
		assertEquals("moonshine-tiny", RT.getIn(status, "model", "id").toString());
		assertEquals("MIT, Useful Sensors", RT.getIn(status, "model", "licence").toString(), "the model is credited");
		assertEquals("not downloaded", RT.getIn(status, "state").toString());
		assertNotNull(status.get(Strings.create("nativeAccess")));
		assertFalse(String.valueOf(status).contains(cache.toString()), "no paths are disclosed");

		String ref = save("local.wav", SpeechAdapter.wav(new byte[3200], 16_000), "audio/wav");
		assertTrue(failure(() -> run(owner, "transcribe", Maps.of("ref", ref)))
			.contains(LocalSpeechEngine.NOT_READY.formatted(NativeLibraries.NOT_INSTALLED)), "the libraries are named before any model is fetched");
		assertFalse(java.nio.file.Files.exists(cache.resolve("models")), "nothing was downloaded");
		assertTrue(api.requests.isEmpty(), "and no audio left for a provider");
		assertTrue(failure(() -> run(owner, "speak", Maps.of("text", "hello"))).contains(LocalSpeechEngine.SPEAK_UNAVAILABLE));
		assertTrue(failure(() -> run(owner, "transcribe", Maps.of("ref", ref, "model", "parakeet-tdt-v3")))
			.contains(LocalSpeechEngine.OTHER_MODEL.formatted("moonshine-tiny")), "a caller cannot make the venue fetch another model");

		for (var bad : new Object[][] {
				{Maps.of("engine", "whisper"), SpeechAdapter.INVALID_ENGINE},
				{Maps.of("engine", "local", "local", Maps.of("transcribeModel", "large")), SpeechAdapter.UNKNOWN_MODEL.formatted(
					SpeechModels.ALL.stream().map(SpeechModels.Model::id).toList())},
				{Maps.of("engine", "local", "local", Maps.of("threads", 0)), SpeechAdapter.INVALID_THREADS},
				{Maps.of("engine", "local", "local", Maps.of("prefetch", "yes")), SpeechAdapter.INVALID_FLAG.formatted("prefetch")}}) {
			AMap<AString, ACell> config = RT.castMap((ACell) bad[0]);
			assertEquals(bad[1], assertThrows(IllegalArgumentException.class, () -> new SpeechAdapter().configure(config, true)).getMessage());
		}
		assertTrue(assertThrows(IllegalArgumentException.class, () -> new SpeechAdapter().configure(
			RT.castMap(Maps.of("engine", "local", "local", Maps.of("mystery", 1))), true)).getMessage().startsWith("adapters.speech.local: unknown setting"));
	}

	@Test void unsafeSettingsAreRefused() {
		for (var bad : new Object[][] {
				{Maps.of("baseUrl", "http://speech.example/v1"), SpeechAdapter.INVALID_BASE_URL},
				{Maps.of("baseUrl", "https://user@speech.example/v1"), SpeechAdapter.INVALID_BASE_URL},
				{Maps.of("apiKey", "sk-raw-key"), SpeechAdapter.INVALID_SECRET_REF.formatted("adapters.speech.apiKey")},
				{Maps.of("keyName", "../X"), SpeechAdapter.INVALID_KEY_NAME},
				{Maps.of("timeoutMillis", 5), SpeechAdapter.INVALID_TIMEOUT}}) {
			AMap<AString, ACell> config = RT.castMap((ACell) bad[0]);
			assertEquals(bad[1], assertThrows(IllegalArgumentException.class, () -> new SpeechAdapter().configure(config, true)).getMessage());
		}
		assertTrue(assertThrows(IllegalArgumentException.class, () -> new SpeechAdapter().configure(
			RT.castMap(Maps.of("mystery", 1)), true)).getMessage().startsWith("adapters.speech: unknown setting"));
		assertTrue(new SpeechAdapter().configure(RT.castMap(Maps.of("baseUrl", "http://localhost:8000/v1/")), true),
			"a local server over plain HTTP is fine");
	}

	private static String failure(org.junit.jupiter.api.function.Executable action) {
		Exception e = assertThrows(Exception.class, action);
		StringBuilder messages = new StringBuilder();
		for (Throwable t = e; t != null; t = t.getCause()) messages.append(t.getMessage()).append(" | ");
		return messages.toString();
	}
}
