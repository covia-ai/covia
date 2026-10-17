package covia.adapter.speech;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.ANumeric;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.adapter.messaging.MessagingMedia;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.storage.ContentProvider;

/**
 * Speech to text and text to speech, as venue operations:
 *
 * <ul>
 *   <li>{@code speech:transcribe {ref, mediaType?, language?, prompt?, model?}} reads
 *       audio under the caller's authority and returns {@code {text?, language?,
 *       seconds?, model, engine, ms}}. Its input may be a messaging attachment, so it
 *       serves unchanged as a Telegram or WhatsApp binding's {@code mediaOperation}:
 *       anything that is not audio returns {@code {}}, and silence returns no
 *       {@code text}.</li>
 *   <li>{@code speech:speak {text, voice?, model?, format?, speed?, instructions?,
 *       destination?, fileName?}} synthesises speech and stores it, by default as
 *       Ogg/Opus (what a voice note is) in the caller's private DLFS {@code speech}
 *       drive, returning {@code {ref, mediaType, size, fileName, ...}}.</li>
 *   <li>{@code speech:engines} reports the engine, its models and voice, and whether a
 *       key resolves for the caller, without endpoints or keys.</li>
 * </ul>
 *
 * <p>The engine is a {@link SpeechEngine}; today the OpenAI-compatible audio API at
 * {@code adapters.speech.baseUrl}, a cloud provider or a local server on loopback. A
 * key is the operator's {@code apiKey} reference when configured, resolved as the
 * venue; otherwise the caller's own {@code s/<keyName>} (default
 * {@code s/OPENAI_API_KEY}) when it exists, and none at all when it does not, which a
 * local server accepts. Keys are resolved for the engine's endpoint.
 */
public class SpeechAdapter extends AAdapter implements AutoCloseable {

	public static final String NAME = "speech";
	static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
	static final String DEFAULT_KEY_NAME = "OPENAI_API_KEY";
	static final String DEFAULT_TRANSCRIBE_MODEL = "whisper-1";
	static final String DEFAULT_SPEAK_MODEL = "tts-1";
	static final String DEFAULT_VOICE = "alloy";
	static final String DEFAULT_FORMAT = "opus";
	static final long DEFAULT_TIMEOUT_MILLIS = 120_000;
	/** The most characters one speech request takes. */
	public static final int MAX_TEXT = 4096;
	/** The most audio read for a transcription or accepted from synthesis. */
	public static final int MAX_BYTES = MessagingMedia.MAX_BYTES;
	/** The rate assumed for raw PCM when the caller names none. */
	public static final int DEFAULT_PCM_RATE = 16_000;
	/** The private drive synthesised speech goes to when no destination is named. */
	public static final String DRIVE = "speech";

	public static final String UNKNOWN_SETTING = "adapters.speech: unknown setting %s (known: %s)";
	public static final String INVALID_BASE_URL = "adapters.speech.baseUrl must be HTTPS (HTTP is allowed only on loopback), without credentials, query or fragment";
	public static final String INVALID_SECRET_REF = "%s must be an s/NAME secret reference, never a raw key";
	public static final String INVALID_KEY_NAME = "adapters.speech.keyName must be a secret name such as OPENAI_API_KEY";
	public static final String INVALID_STRING = "%s must be a non-empty string";
	public static final String INVALID_TIMEOUT = "adapters.speech.timeoutMillis must be between 1000 and 600000";
	public static final String REF_REQUIRED = "ref must name the audio to transcribe";
	public static final String NOT_FOUND = "No audio at %s";
	public static final String TOO_LARGE = "Audio exceeds the 20 MiB limit";
	public static final String TEXT_REQUIRED = "text must contain between 1 and 4096 characters";
	public static final String INVALID_FORMAT = "format must be one of opus, mp3, aac, flac or wav";
	public static final String INVALID_SPEED = "speed must be a number from 0.25 to 4";
	public static final String INVALID_RATE = "sampleRate must be an integer from 8000 to 48000";
	public static final String KEY_UNRESOLVED = "The speech key %s did not resolve";
	public static final String UNSUPPORTED = "Unsupported speech operation: %s";

	private static final AString K_ENABLED = Strings.intern("enabled");
	private static final AString K_BASE_URL = Strings.intern("baseUrl");
	private static final AString K_API_KEY = Strings.intern("apiKey");
	private static final AString K_KEY_NAME = Strings.intern("keyName");
	private static final AString K_TRANSCRIBE_MODEL = Strings.intern("transcribeModel");
	private static final AString K_SPEAK_MODEL = Strings.intern("speakModel");
	private static final AString K_VOICE = Strings.intern("voice");
	private static final AString K_TIMEOUT = Strings.intern("timeoutMillis");
	private static final AString K_ENGINE = Strings.intern("engine");
	private static final AString K_LOCAL = Strings.intern("local");
	private static final Set<AString> CONFIG_KEYS = Set.of(K_ENABLED, K_BASE_URL, K_API_KEY, K_KEY_NAME,
		K_TRANSCRIBE_MODEL, K_SPEAK_MODEL, K_VOICE, K_TIMEOUT, K_ENGINE, K_LOCAL);
	private static final AString K_NATIVE_PATH = Strings.intern("nativePath");
	private static final AString K_FETCH_NATIVES = Strings.intern("fetchNatives");
	private static final AString K_CACHE_DIR = Strings.intern("cacheDir");
	private static final AString K_PREFETCH = Strings.intern("prefetch");
	private static final AString K_THREADS = Strings.intern("threads");
	private static final Set<AString> LOCAL_KEYS = Set.of(K_TRANSCRIBE_MODEL, K_NATIVE_PATH, K_FETCH_NATIVES, K_CACHE_DIR,
		K_PREFETCH, K_THREADS);
	public static final String INVALID_ENGINE = "adapters.speech.engine must be openai or local";
	public static final String UNKNOWN_LOCAL_SETTING = "adapters.speech.local: unknown setting %s (known: %s)";
	public static final String UNKNOWN_MODEL = "adapters.speech.local.transcribeModel must be one of %s";
	public static final String INVALID_THREADS = "adapters.speech.local.threads must be an integer from 1 to 16";
	public static final String INVALID_FLAG = "adapters.speech.local.%s must be true or false";

	/** Messaging attachment kinds that are speech. */
	private static final Set<String> AUDIO_KINDS = Set.of("audio", "voice");
	/** Extensions a provider recognises, beyond those {@link MessagingMedia} names. */
	private static final Map<String, String> AUDIO_EXTENSIONS = Map.of("audio/wav", ".wav", "audio/x-wav", ".wav",
		"audio/wave", ".wav", "audio/webm", ".webm", "audio/flac", ".flac", "audio/x-m4a", ".m4a", "audio/mp3", ".mp3",
		"video/mp4", ".mp4", "video/webm", ".webm");
	private static final Map<String, String> TYPES_BY_EXTENSION = Map.of("ogg", "audio/ogg", "oga", "audio/ogg",
		"opus", "audio/ogg", "mp3", "audio/mpeg", "m4a", "audio/mp4", "wav", "audio/wav", "flac", "audio/flac",
		"webm", "audio/webm", "aac", "audio/aac", "pcm", "audio/L16");

	private record Settings(SpeechEngine engine, String apiKeyRef, String keyName, String transcribeModel,
		String speakModel, String voice, boolean prefetch) {}

	/** This adapter instance's native libraries: loaded once, into its own class loader. */
	private final NativeLibraries natives = new NativeLibraries();
	private volatile Settings settings = settings(Maps.empty());

	@Override public String getName() { return NAME; }

	@Override public String getDescription() {
		return "Speech to text and text to speech over an OpenAI-compatible audio API, a cloud provider or a local server; "
			+ "transcribe also serves as a messaging binding's mediaOperation.";
	}

	@Override protected void installAssets() {
		installAsset("speech/transcribe", "/adapters/speech/transcribe.json");
		installAsset("speech/speak", "/adapters/speech/speak.json");
		installAsset("speech/engines", "/adapters/speech/engines.json");
		installSkill("adapters/speech", "/adapters/speech/skill.json");
	}

	@Override public boolean configure(AMap<AString, ACell> config, boolean strict) {
		if (config == null) config = Maps.empty();
		if (strict) {
			for (var entry : config.entrySet()) {
				if (!CONFIG_KEYS.contains(entry.getKey())) {
					throw new IllegalArgumentException(UNKNOWN_SETTING.formatted(entry.getKey(), CONFIG_KEYS));
				}
			}
		}
		Settings previous = settings;
		settings = settings(config);
		if (previous.engine() instanceof LocalSpeechEngine old) old.close();
		if (engine != null && engine.isStarted()) prefetch();
		return true;
	}

	/** Fetch the local engine's model and libraries in the background when configured to. */
	@Override public void start() { prefetch(); }

	private void prefetch() {
		Settings s = settings;
		if (s.prefetch() && s.engine() instanceof LocalSpeechEngine local) {
			VIRTUAL_EXECUTOR.execute(() -> {
				try { local.prepare(); }
				catch (RuntimeException e) { org.slf4j.LoggerFactory.getLogger(SpeechAdapter.class).warn("speech: prefetch failed: {}", e.getMessage()); }
			});
		}
	}

	/** Release the local engine; its native libraries stay loaded until its class loader goes. */
	public void close() {
		if (settings.engine() instanceof LocalSpeechEngine local) local.close();
	}

	private Settings settings(AMap<AString, ACell> config) {
		String engineName = setting(config, K_ENGINE, "openai");
		if (!engineName.equals("openai") && !engineName.equals(LocalSpeechEngine.NAME)) throw new IllegalArgumentException(INVALID_ENGINE);
		if (engineName.equals(LocalSpeechEngine.NAME)) return local(config);
		String baseUrl = setting(config, K_BASE_URL, DEFAULT_BASE_URL);
		try {
			URI uri = URI.create(baseUrl);
			String host = uri.getHost();
			if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
				|| !("https".equals(uri.getScheme()) || (OpenAiSpeechEngine.loopback(host) && "http".equals(uri.getScheme())))) {
				throw new IllegalArgumentException(INVALID_BASE_URL);
			}
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(INVALID_BASE_URL);
		}
		baseUrl = baseUrl.replaceAll("/+$", "");
		String apiKeyRef = setting(config, K_API_KEY, null);
		if (apiKeyRef != null && (!Engine.isSecretRef(apiKeyRef) || apiKeyRef.endsWith("/"))) {
			throw new IllegalArgumentException(INVALID_SECRET_REF.formatted("adapters.speech.apiKey"));
		}
		String keyName = setting(config, K_KEY_NAME, DEFAULT_KEY_NAME);
		if (!keyName.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException(INVALID_KEY_NAME);
		CVMLong timeout = RT.ensureLong(config.get(K_TIMEOUT));
		long millis = config.get(K_TIMEOUT) == null ? DEFAULT_TIMEOUT_MILLIS : timeout == null ? -1 : timeout.longValue();
		if (millis < 1000 || millis > 600_000) throw new IllegalArgumentException(INVALID_TIMEOUT);
		SpeechEngine engine = new OpenAiSpeechEngine(baseUrl, Duration.ofMillis(millis), MAX_BYTES, keyName);
		return new Settings(engine, apiKeyRef, keyName, setting(config, K_TRANSCRIBE_MODEL, DEFAULT_TRANSCRIBE_MODEL),
			setting(config, K_SPEAK_MODEL, DEFAULT_SPEAK_MODEL), setting(config, K_VOICE, DEFAULT_VOICE), false);
	}

	/** The in-process engine: {@code local: {transcribeModel, nativePath, fetchNatives, cacheDir, prefetch, threads}}. */
	private Settings local(AMap<AString, ACell> config) {
		ACell localCell = config.get(K_LOCAL);
		AMap<AString, ACell> local = localCell instanceof AMap<?, ?> m ? RT.castMap(m) : Maps.empty();
		if (localCell != null && !(localCell instanceof AMap<?, ?>)) throw new IllegalArgumentException(INVALID_STRING.formatted("adapters.speech.local"));
		for (var entry : local.entrySet()) {
			if (!LOCAL_KEYS.contains(entry.getKey())) throw new IllegalArgumentException(UNKNOWN_LOCAL_SETTING.formatted(entry.getKey(), LOCAL_KEYS));
		}
		String modelId = setting(local, K_TRANSCRIBE_MODEL, SpeechModels.DEFAULT);
		SpeechModels.Model model = SpeechModels.byId(modelId);
		if (model == null) throw new IllegalArgumentException(UNKNOWN_MODEL.formatted(SpeechModels.ALL.stream().map(SpeechModels.Model::id).toList()));
		String nativePath = setting(local, K_NATIVE_PATH, null);
		String cacheDir = setting(local, K_CACHE_DIR, null);
		CVMLong threadsCell = RT.ensureLong(local.get(K_THREADS));
		long threads = local.get(K_THREADS) == null ? Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2))
			: threadsCell == null ? -1 : threadsCell.longValue();
		if (threads < 1 || threads > 16) throw new IllegalArgumentException(INVALID_THREADS);
		LocalSpeechEngine engine = new LocalSpeechEngine(model, nativePath == null ? null : java.nio.file.Path.of(nativePath),
			flag(local, K_FETCH_NATIVES), () -> cacheDirectory(cacheDir), (int) threads, natives, LocalSpeechEngine.Sherpa::new);
		return new Settings(engine, null, DEFAULT_KEY_NAME, model.id(), null, null, flag(local, K_PREFETCH));
	}

	private static boolean flag(AMap<AString, ACell> local, AString key) {
		ACell value = local.get(key);
		if (value == null) return false;
		if (!(value instanceof CVMBool b)) throw new IllegalArgumentException(INVALID_FLAG.formatted(key));
		return b.booleanValue();
	}

	/** Beside a persistent store (where venue.key lives), else the user's ~/.covia; or as configured. */
	private java.nio.file.Path cacheDirectory(String configured) {
		if (configured != null) return java.nio.file.Path.of(configured);
		String store = engine == null ? null : engine.config().getStore();
		if (store != null && !store.equals("temp") && !store.equals("memory")) {
			return java.nio.file.Path.of(store).toAbsolutePath().resolveSibling("cache").resolve(NAME);
		}
		return java.nio.file.Path.of(System.getProperty("user.home"), ".covia", "cache", NAME);
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String op = getSubOperation(meta);
		AMap<AString, ACell> in = input instanceof AMap<?, ?> map ? RT.castMap(map) : Maps.empty();
		Settings current = settings;
		return CompletableFuture.supplyAsync(() -> switch (op == null ? "" : op) {
			case "transcribe" -> transcribe(ctx, current, in);
			case "speak" -> speak(ctx, current, in);
			case "engines" -> engines(ctx, current);
			default -> throw new UnsupportedOperationException(UNSUPPORTED.formatted(op));
		}, VIRTUAL_EXECUTOR);
	}

	// ---- transcribe ----

	ACell transcribe(RequestContext ctx, Settings s, AMap<AString, ACell> in) {
		String kind = string(in, "kind");
		String declared = firstString(in, "mediaType", "contentType", "mime_type");
		// A messaging attachment that is not speech passes through, as a media hook should.
		if ((kind != null || declared != null) && !isAudio(kind, declared)) return Maps.empty();
		String ref = string(in, "ref");
		if (ref == null) throw new IllegalArgumentException(REF_REQUIRED);
		byte[] bytes;
		String mediaType = declared;
		try {
			ContentProvider.Resolved resolved = engine.resolveContent(Strings.create(ref), ctx);
			if (resolved == null) throw new IllegalArgumentException(NOT_FOUND.formatted(ref));
			if (resolved.content().getSize() > MAX_BYTES) throw new IllegalArgumentException(TOO_LARGE);
			try (InputStream stream = resolved.content().getInputStream()) { bytes = stream.readNBytes(MAX_BYTES + 1); }
			if (bytes.length > MAX_BYTES) throw new IllegalArgumentException(TOO_LARGE);
			if (mediaType == null) mediaType = resolved.contentType();
		} catch (IOException e) {
			throw new IllegalArgumentException(NOT_FOUND.formatted(ref));
		}
		String base = baseType(mediaType);
		// A store may not keep the type: the file's own extension is the next best witness.
		if (base.isEmpty() || base.equals("application/octet-stream")) {
			String inferred = typeOf(ref);
			if (inferred != null) { mediaType = inferred; base = inferred; }
		}
		if (base.equals("audio/l16") || base.equals("audio/pcm")) {
			bytes = wav(bytes, sampleRate(in, mediaType));
			mediaType = "audio/wav";
			base = mediaType;
		}
		if (mediaType == null) mediaType = "application/octet-stream";
		String extension = AUDIO_EXTENSIONS.getOrDefault(base, MessagingMedia.extension(mediaType));
		long started = System.nanoTime();
		String model = optional(in, "model", s.transcribeModel());
		SpeechEngine.Transcript transcript = s.engine().transcribe(new SpeechEngine.Audio(bytes, mediaType, "audio" + extension),
			model, string(in, "language"), string(in, "prompt"), key(ctx, s, in));
		AMap<AString, ACell> out = Maps.of("model", model, "engine", s.engine().name(),
			"ms", CVMLong.create((System.nanoTime() - started) / 1_000_000));
		// Silence has no text: a media hook's text must never be empty.
		if (!transcript.text().isEmpty()) out = out.assoc(Fields.TEXT, Strings.create(transcript.text()));
		if (transcript.language() != null) out = out.assoc(Strings.intern("language"), Strings.create(transcript.language()));
		if (transcript.seconds() != null) out = out.assoc(Strings.intern("seconds"), CVMDouble.create(transcript.seconds()));
		return out;
	}

	/** The audio type a reference's extension names, or null. */
	private static String typeOf(String ref) {
		int dot = ref.lastIndexOf('.');
		if (dot < 0 || dot < ref.lastIndexOf('/')) return null;
		return TYPES_BY_EXTENSION.get(ref.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	private static boolean isAudio(String kind, String mediaType) {
		if (kind != null && AUDIO_KINDS.contains(kind)) return true;
		return mediaType != null && baseType(mediaType).startsWith("audio/");
	}

	private static String baseType(String mediaType) {
		return mediaType == null ? "" : mediaType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
	}

	private static int sampleRate(AMap<AString, ACell> in, String mediaType) {
		ACell given = in.get(Strings.intern("sampleRate"));
		long rate = DEFAULT_PCM_RATE;
		if (given != null) {
			CVMLong n = RT.ensureLong(given);
			rate = n == null ? -1 : n.longValue();
		} else if (mediaType != null) {
			for (String parameter : mediaType.split(";")) {
				String p = parameter.strip().toLowerCase(Locale.ROOT);
				if (p.startsWith("rate=")) {
					try { rate = Long.parseLong(p.substring(5)); }
					catch (NumberFormatException e) { rate = -1; }
				}
			}
		}
		if (rate < 8000 || rate > 48_000) throw new IllegalArgumentException(INVALID_RATE);
		return (int) rate;
	}

	/** Raw 16-bit little-endian mono PCM as a WAV file, which every provider accepts. */
	static byte[] wav(byte[] pcm, int sampleRate) {
		ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
		header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + pcm.length)
			.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
			.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
			.putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16)
			.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(pcm.length);
		byte[] out = new byte[44 + pcm.length];
		System.arraycopy(header.array(), 0, out, 0, 44);
		System.arraycopy(pcm, 0, out, 44, pcm.length);
		return out;
	}

	// ---- speak ----

	ACell speak(RequestContext ctx, Settings s, AMap<AString, ACell> in) {
		String text = string(in, "text");
		if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > MAX_TEXT) throw new IllegalArgumentException(TEXT_REQUIRED);
		String format = optional(in, "format", DEFAULT_FORMAT);
		String mediaType = OpenAiSpeechEngine.FORMATS.get(format);
		if (mediaType == null) throw new IllegalArgumentException(INVALID_FORMAT);
		Double speed = null;
		ACell speedCell = in.get(Strings.intern("speed"));
		if (speedCell != null) {
			if (!(speedCell instanceof ANumeric n) || n.doubleValue() < 0.25 || n.doubleValue() > 4) throw new IllegalArgumentException(INVALID_SPEED);
			speed = n.doubleValue();
		}
		String fileName = string(in, "fileName");
		if (fileName != null && !MessagingMedia.safeFileName(fileName)) throw new IllegalArgumentException(MessagingMedia.INVALID_FILE_NAME);
		String voice = optional(in, "voice", s.voice());
		String model = optional(in, "model", s.speakModel());
		SpeechEngine.Speech speech = s.engine().speak(new SpeechEngine.SpeakRequest(text, model, voice, format, speed,
			string(in, "instructions")), key(ctx, s, in));
		String destination = string(in, "destination");
		String directory = destination != null ? destination : MessagingMedia.ownerDirectory(ctx.getUserDID(), DRIVE);
		String name = fileName != null ? fileName : MessagingMedia.fallbackName(DRIVE, UUID.randomUUID().toString(), mediaType);
		AMap<AString, ACell> stored;
		try {
			stored = MessagingMedia.store(engine, speech.bytes(), mediaType, directory, name, ctx);
		} catch (IOException e) {
			throw new JobFailedException(MessagingMedia.UNSUPPORTED_DESTINATION + ": " + directory);
		}
		return stored.assoc(MessagingMedia.K_MEDIA_TYPE, Strings.create(mediaType))
			.assoc(Strings.intern("format"), Strings.create(format))
			.assoc(K_VOICE, Strings.create(voice))
			.assoc(Strings.intern("model"), Strings.create(model))
			.assoc(Strings.intern("engine"), Strings.create(s.engine().name()));
	}

	// ---- engines ----

	ACell engines(RequestContext ctx, Settings s) {
		if (s.engine() instanceof LocalSpeechEngine local) return Maps.of("engines", Vectors.of(local.status()));
		boolean keyed;
		try { keyed = key(ctx, s, Maps.empty()) != null; }
		catch (RuntimeException e) { keyed = false; }
		return Maps.of("engines", Vectors.of(Maps.of(
			"name", s.engine().name(),
			"local", CVMBool.create(s.engine().local()),
			"transcribeModel", s.transcribeModel(),
			"speakModel", s.speakModel(),
			"voice", s.voice(),
			"formats", Vectors.of("opus", "mp3", "aac", "flac", "wav"),
			"keyed", CVMBool.create(keyed))));
	}

	// ---- keys and inputs ----

	/**
	 * The caller's {@code apiKey} reference, else the operator's, else the caller's own
	 * {@code s/<keyName>}; null when none exists, so a local server is called without one.
	 */
	private String key(RequestContext ctx, Settings s, AMap<AString, ACell> in) {
		String endpoint = s.engine().endpoint();
		// An engine that calls no endpoint needs no key, and none is resolved for it.
		if (endpoint == null) return null;
		String callerRef = string(in, "apiKey");
		if (callerRef != null) {
			if (!Engine.isSecretRef(callerRef) || callerRef.endsWith("/")) throw new IllegalArgumentException(INVALID_SECRET_REF.formatted("apiKey"));
			String value = engine.resolveSecret(callerRef, ctx, endpoint);
			if (value == null || value.isBlank()) throw new JobFailedException(KEY_UNRESOLVED.formatted(callerRef));
			return value;
		}
		if (s.apiKeyRef() != null) {
			String value = engine.resolveSecret(s.apiKeyRef(), engine.venueContext(), endpoint);
			if (value == null || value.isBlank()) throw new JobFailedException(KEY_UNRESOLVED.formatted(s.apiKeyRef()));
			return value;
		}
		String own = engine.resolveSecret("s/" + s.keyName(), ctx, endpoint);
		return own == null || own.isBlank() ? null : own;
	}

	private static String string(AMap<AString, ACell> in, String field) {
		ACell value = in.get(Strings.intern(field));
		if (value == null) return null;
		if (!(value instanceof AString s) || s.isBlank()) throw new IllegalArgumentException(INVALID_STRING.formatted(field));
		return s.toString();
	}

	private static String firstString(AMap<AString, ACell> in, String... fields) {
		for (String field : fields) {
			AString value = RT.ensureString(in.get(Strings.intern(field)));
			if (value != null && !value.isBlank()) return value.toString();
		}
		return null;
	}

	private static String optional(AMap<AString, ACell> in, String field, String fallback) {
		String value = string(in, field);
		return value == null ? fallback : value;
	}

	private static String setting(AMap<AString, ACell> config, AString key, String fallback) {
		ACell value = config.get(key);
		if (value == null) return fallback;
		if (!(value instanceof AString s) || s.isBlank()) throw new IllegalArgumentException(INVALID_STRING.formatted("adapters.speech." + key));
		return s.toString();
	}
}
