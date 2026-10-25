package covia.adapter.speech;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import covia.exception.JobFailedException;

/**
 * Speech recognised and spoken inside the venue's JVM by sherpa-onnx: no audio or text
 * leaves the machine. Each model (one to transcribe, one voice to speak; either may be
 * absent) is fetched once (pinned), loaded when first needed and released after a
 * quiet spell; a note longer than 30 seconds is split on its pauses, and a
 * {@linkplain Dictation dictation} is heard as it is spoken. Speech comes out as WAV.
 * Native work runs on one platform thread of its own, one decode or synthesis at a
 * time, so a long one never holds a virtual thread's carrier.
 */
final class LocalSpeechEngine implements SpeechEngine {
	private static final Logger log = LoggerFactory.getLogger(LocalSpeechEngine.class);

	public static final String NAME = "local";
	public static final String SPEAK_UNAVAILABLE = "The local speech engine is not set to speak; set adapters.speech.speakEngine to local";
	public static final String TRANSCRIBE_UNAVAILABLE = "The local speech engine is not set to transcribe; set adapters.speech.engine to local";
	public static final String OTHER_MODEL = "The local speech engine runs only its configured model, %s";
	public static final String WAV_ONLY = "The local speech engine speaks only wav";
	public static final String VOICE_NUMBER = "voice must be the number of one of the model's voices, such as 0";
	public static final String NO_SUCH_VOICE = "%s has voices 0 to %d";
	public static final String NOTHING_SAID = "The local speech engine made no audio of that text; check its language";
	public static final String ENGLISH_ONLY = "%s speaks English only";
	public static final String SPEECH_TOO_LARGE = "The speech exceeds the 20 MiB limit; say less at once";
	/** The language a voice speaks when the caller names none. */
	public static final String DEFAULT_LANGUAGE = "en";
	public static final String NOT_READY = "The local speech engine is not ready: %s";
	/** Pieces longer than this are decoded apart: memory grows with what is decoded at once. */
	static final int WHOLE_SECONDS = 30;
	static final long IDLE_MILLIS = 5 * 60_000L;

	/** What the engine needs from a speech toolkit; sherpa-onnx in use, a fake in tests. */
	interface Backend extends AutoCloseable {
		/** The stretches of speech in long audio, as voice-activity detection finds them. */
		List<float[]> stretches(float[] samples);
		/** The words in up to {@value #WHOLE_SECONDS} seconds of 16 kHz audio. */
		String decode(float[] samples);
		/** A pause finder fed as audio arrives, closing a stretch at a pause or at {@code maxSeconds}. */
		Pauses pauses(int maxSeconds);
		@Override void close();
	}

	/** Voice-activity detection over audio as it arrives, {@link #WINDOW} samples at a time. */
	interface Pauses {
		void accept(float[] window);
		/** Where each stretch closed since last asked ends, in samples from the start. */
		List<Integer> closed();
		/** Whether speech is under way in the stretch not yet closed. */
		boolean speaking();
		/** Close the stretch under way, if any: the audio has ended. */
		void flush();
		void release();
	}

	/** The samples a pause finder takes at once. */
	static final int WINDOW = 512;
	/** The longest stretch a dictation decodes at once, which bounds each look at the unfinished one. */
	static final int DICTATION_STRETCH_SECONDS = 20;
	/** How much new audio warrants another look at the stretch still being spoken. */
	static final int LOOK_AGAIN_SAMPLES = AudioDecoder.RATE / 2;
	public static final String DICTATION_CLOSED = "The dictation has ended";

	/** Opens a backend over a ready model's directory. */
	interface Backends { Backend open(SpeechModels.Model model, Path directory, int threads); }

	/** What the engine needs from a speech synthesiser; sherpa-onnx in use, a fake in tests. */
	interface Speaker extends AutoCloseable {
		/** How many voices the model has, numbered from 0. */
		int voices();
		/** {@code text} said by voice {@code sid}, {@code speed} times as fast as usual, in {@code language}. */
		Said say(String text, int sid, float speed, String language);
		@Override void close();
	}

	/** Synthesised speech: samples from -1 to 1 at {@code rate} per second. */
	record Said(float[] samples, int rate) {}

	/** Opens a speaker over a ready voice model's directory. */
	interface Speakers { Speaker open(SpeechModels.Model model, Path directory, int threads); }

	/** The model it transcribes with, or null when it only speaks. */
	private final SpeechModels.Model model;
	/** The model it speaks with, or null when it only transcribes. */
	private final SpeechModels.Model voice;
	private final Path nativePath;
	private final boolean fetchNatives;
	private final Supplier<Path> cache;
	private final int threads;
	private final NativeLibraries natives;
	private final Backends backends;
	private final Speakers speakers;
	private final PinnedDownload download = new PinnedDownload();
	private final AtomicLong modelProgress = new AtomicLong();
	private final AtomicLong voiceProgress = new AtomicLong();
	private final AtomicLong nativeProgress = new AtomicLong();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "speech-local");
		t.setDaemon(true);
		return t;
	});
	private final ScheduledExecutorService idle = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "speech-local-idle");
		t.setDaemon(true);
		return t;
	});
	private final Object readiness = new Object();
	private final AtomicBoolean modelReady = new AtomicBoolean();
	private final AtomicBoolean voiceReady = new AtomicBoolean();
	private volatile String failure;
	private volatile String voiceFailure;
	private Backend backend;
	private Speaker speaker;
	/** The voice's lexicon, once read, for a voice that has one. */
	private volatile Lexicon lexicon;
	private long lastUse;
	private long lastSpoke;

	/** An engine that transcribes with {@code model} and does not speak. */
	LocalSpeechEngine(SpeechModels.Model model, Path nativePath, boolean fetchNatives, Supplier<Path> cache, int threads,
			NativeLibraries natives, Backends backends) {
		this(model, null, nativePath, fetchNatives, cache, threads, natives, backends, null);
	}

	/**
	 * @param model the model to transcribe with, or null not to transcribe
	 * @param voice the model to speak with, or null not to speak
	 */
	LocalSpeechEngine(SpeechModels.Model model, SpeechModels.Model voice, Path nativePath, boolean fetchNatives,
			Supplier<Path> cache, int threads, NativeLibraries natives, Backends backends, Speakers speakers) {
		this.model = model;
		this.voice = voice;
		this.nativePath = nativePath;
		this.fetchNatives = fetchNatives;
		this.cache = cache;
		this.threads = threads;
		this.natives = natives;
		this.backends = backends;
		this.speakers = speakers;
		idle.scheduleWithFixedDelay(this::releaseIfIdle, 60, 60, TimeUnit.SECONDS);
	}

	@Override public String name() { return NAME; }
	@Override public boolean local() { return true; }
	@Override public String endpoint() { return null; }
	/** Whether it transcribes, and so hears a dictation. */
	boolean transcribes() { return model != null; }
	/** Whether it speaks. */
	boolean speaks() { return voice != null; }

	@Override
	public Transcript transcribe(Audio audio, String modelId, String language, String prompt, String key) {
		if (model == null) throw new JobFailedException(TRANSCRIBE_UNAVAILABLE);
		if (!model.id().equals(modelId)) throw new IllegalArgumentException(OTHER_MODEL.formatted(model.id()));
		float[] samples = AudioDecoder.decode(audio.bytes());
		prepare();
		Future<String> heard = worker.submit(() -> {
			Backend b = backend();
			List<float[]> pieces = samples.length <= WHOLE_SECONDS * AudioDecoder.RATE
				? List.of(samples) : merge(b.stretches(samples), WHOLE_SECONDS * AudioDecoder.RATE);
			StringBuilder text = new StringBuilder();
			for (float[] piece : pieces) {
				String said = b.decode(piece).strip();
				if (!said.isEmpty()) text.append(text.isEmpty() ? "" : " ").append(said);
			}
			lastUse = System.nanoTime();
			return text.toString();
		});
		return new Transcript(onWorker(heard), null, samples.length / (double) AudioDecoder.RATE);
	}

	/**
	 * Speech as 16-bit mono WAV at the voice's own rate. The voice is a number (null is
	 * voice 0); {@code instructions} are taken by no local voice and are ignored.
	 */
	@Override
	public Speech speak(SpeakRequest request, String key) {
		if (voice == null) throw new JobFailedException(SPEAK_UNAVAILABLE);
		if (request.model() != null && !voice.id().equals(request.model())) throw new IllegalArgumentException(OTHER_MODEL.formatted(voice.id()));
		if (!"wav".equals(request.format())) throw new IllegalArgumentException(WAV_ONLY);
		int sid = voiceNumber(request.voice());
		float speed = request.speed() == null ? 1f : request.speed().floatValue();
		String language = request.language() == null ? DEFAULT_LANGUAGE : request.language();
		if (voice.kind() == SpeechModels.Kind.VITS && !DEFAULT_LANGUAGE.equals(language)) {
			throw new IllegalArgumentException(ENGLISH_ONLY.formatted(voice.id()));
		}
		prepareVoice();
		// A lexicon voice drops any word it lacks without a sound: make every word sayable first.
		Lexicon.Sayable sayable = voice.kind() == SpeechModels.Kind.VITS ? lexicon().sayable(request.text())
			: new Lexicon.Sayable(request.text(), List.of());
		if (!sayable.spelled().isEmpty()) log.info("speech: spelled out {}", sayable.spelled());
		Said said = onWorker(worker.submit(() -> {
			Speaker s = speaker();
			if (sid >= s.voices()) throw new IllegalArgumentException(NO_SUCH_VOICE.formatted(voice.id(), s.voices() - 1));
			Said out = s.say(sayable.text(), sid, speed, language);
			lastSpoke = System.nanoTime();
			return out;
		}));
		if (said.samples().length == 0) throw new JobFailedException(NOTHING_SAID);
		// Two bytes a sample after a 44-byte header.
		if (said.samples().length > (SpeechAdapter.MAX_BYTES - 44) / 2) throw new JobFailedException(SPEECH_TOO_LARGE);
		return new Speech(SpeechAdapter.wav(pcm16(said.samples()), said.rate()), "audio/wav", sayable.spelled());
	}

	private Lexicon lexicon() {
		Lexicon l = lexicon;
		if (l != null) return l;
		try {
			l = Lexicon.load(modelDirectory(cache.get(), voice).resolve("lexicon.txt"));
		} catch (IOException e) {
			throw new JobFailedException(NOT_READY.formatted(reason(e)));
		}
		lexicon = l;
		return l;
	}

	private static int voiceNumber(String voice) {
		if (voice == null) return 0;
		try {
			int sid = Integer.parseInt(voice.strip());
			if (sid < 0) throw new IllegalArgumentException(VOICE_NUMBER);
			return sid;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(VOICE_NUMBER);
		}
	}

	/** Samples from -1 to 1 as 16-bit little-endian PCM, clipped. */
	static byte[] pcm16(float[] samples) {
		byte[] out = new byte[samples.length * 2];
		for (int i = 0; i < samples.length; i++) {
			int v = Math.round(Math.max(-1f, Math.min(1f, samples[i])) * Short.MAX_VALUE);
			out[2 * i] = (byte) v;
			out[2 * i + 1] = (byte) (v >> 8);
		}
		return out;
	}

	/**
	 * What a dictation has heard so far: {@code settled} up to its last pause, which
	 * stays, then {@code partial}, which may change; and how many seconds the speaker
	 * has been {@code quiet} since speech was last heard — 0 while it is under way,
	 * negative before any.
	 */
	record Heard(String settled, String partial, double quiet) {
		String text() {
			return settled.isEmpty() ? partial : partial.isEmpty() ? settled : settled + " " + partial;
		}
	}

	/** A dictation, ready for its audio; the engine must be {@linkplain #prepare() prepared}. */
	Dictation dictate() {
		if (model == null) throw new JobFailedException(TRANSCRIBE_UNAVAILABLE);
		return new Dictation();
	}

	/**
	 * The words while someone is still speaking. Each stretch up to a pause is decoded
	 * once and settles; the stretch still being spoken is decoded again once another
	 * half second has come. Every step runs on the engine's worker, as every decode does.
	 */
	final class Dictation {
		private float[] audio = new float[AudioDecoder.RATE * 10];
		private int received;
		private int fed;
		private int settledTo;
		/** Where the last settled stretch began, and where its words begin in {@link #settled}. */
		private int lastFrom;
		private int lastAt;
		private int lookedAt;
		/** Where the last stretch of speech ended, or -1 before any has. */
		private int spokeTo = -1;
		private final StringBuilder settled = new StringBuilder();
		private String partial = "";
		private Pauses pauses;
		private boolean ended;
		private volatile long touched = System.nanoTime();

		/**
		 * More audio (16 kHz, -1 to 1), and what has been heard so far. With {@code end}
		 * everything left settles and the dictation is over. Audio past
		 * {@link AudioDecoder#MAX_SECONDS} is not heard.
		 */
		Heard hear(float[] samples, boolean end) {
			touched = System.nanoTime();
			return onWorker(worker.submit(() -> step(samples, end)));
		}

		/** Ends the dictation without hearing any more. */
		void discard() {
			worker.execute(this::release);
		}

		/** Nanoseconds since it last heard anything. */
		long idle() {
			return System.nanoTime() - touched;
		}

		private Heard step(float[] samples, boolean end) {
			if (ended) throw new IllegalStateException(DICTATION_CLOSED);
			Backend b = backend();
			if (pauses == null) pauses = b.pauses(DICTATION_STRETCH_SECONDS);
			int n = Math.max(0, Math.min(AudioDecoder.MAX_SECONDS * AudioDecoder.RATE - received, samples.length));
			if (received + n > audio.length) audio = java.util.Arrays.copyOf(audio, Math.max(received + n, audio.length * 2));
			System.arraycopy(samples, 0, audio, received, n);
			received += n;
			for (; fed + WINDOW <= received; fed += WINDOW) {
				pauses.accept(java.util.Arrays.copyOfRange(audio, fed, fed + WINDOW));
				settle(b);
			}
			if (end) {
				pauses.flush();
				settle(b);
				double quiet = quiet();
				// What the finder judged too short to be speech is still a word: a lone "go"
				// after a pause. Mid-dictation the next stretch carries it, decoded from where
				// the last one ended; at the end none comes, and a short word decoded alone
				// is easily lost. So the last stretch is heard again with what followed it.
				if (settledTo < received) {
					int whole = WHOLE_SECONDS * AudioDecoder.RATE;
					int from = settledTo;
					if (received - lastFrom <= whole) {
						from = lastFrom;
						settled.setLength(lastAt);
					}
					for (; from < received; from += whole) {
						add(b.decode(java.util.Arrays.copyOfRange(audio, from, Math.min(received, from + whole))));
					}
					settledTo = received;
				}
				partial = "";
				release();
				lastUse = System.nanoTime();
				return new Heard(settled.toString(), partial, quiet);
			}
			// Words once heard stay in view through a lull, until their stretch settles.
			if (received - lookedAt >= LOOK_AGAIN_SAMPLES && pauses.speaking()) {
				lookedAt = received;
				partial = b.decode(java.util.Arrays.copyOfRange(audio, settledTo, received)).strip();
			}
			lastUse = System.nanoTime();
			return new Heard(settled.toString(), partial, quiet());
		}

		/** Seconds since speech was last heard: 0 while it is under way, -1 before any. */
		private double quiet() {
			if (pauses.speaking()) return 0;
			return (spokeTo < 0) ? -1 : (received - spokeTo) / (double) AudioDecoder.RATE;
		}

		/**
		 * Decode each closed stretch from where the last one ended, not as the finder
		 * trims it: its trimmed edges cut the starts and ends of words.
		 */
		private void settle(Backend b) {
			for (int end : pauses.closed()) {
				int to = Math.max(settledTo, Math.min(received, end));
				String said = b.decode(java.util.Arrays.copyOfRange(audio, settledTo, to));
				lastFrom = settledTo;
				lastAt = settled.length();
				settledTo = to;
				spokeTo = to;
				add(said);
				partial = "";
			}
		}

		/** Words that settle, after those before them. */
		private void add(String said) {
			said = said.strip();
			if (!said.isEmpty()) settled.append(settled.isEmpty() ? "" : " ").append(said);
		}

		private void release() {
			ended = true;
			if (pauses != null) pauses.release();
			pauses = null;
			audio = new float[0];
		}
	}

	/** A worker step's result, with its failures as the operation reports them. */
	private static <T> T onWorker(Future<T> step) {
		try {
			return step.get();
		} catch (InterruptedException e) {
			step.cancel(false);
			Thread.currentThread().interrupt();
			throw new JobFailedException(NOT_READY.formatted("interrupted"));
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException r) throw r;
			throw new JobFailedException(NOT_READY.formatted(cause.getMessage()));
		}
	}

	/**
	 * Install the libraries and fetch the model it transcribes with, if not yet done;
	 * safe to call from many threads.
	 */
	void prepare() {
		if (model == null) throw new JobFailedException(TRANSCRIBE_UNAVAILABLE);
		if (modelReady.get() && natives.loaded()) return;
		synchronized (readiness) {
			try {
				install(model, modelReady, modelProgress);
				failure = null;
			} catch (IOException | RuntimeException | LinkageError e) {
				failure = reason(e);
				throw new JobFailedException(NOT_READY.formatted(failure));
			}
		}
	}

	/** Install the libraries and fetch the voice it speaks with, if not yet done; safe to call from many threads. */
	void prepareVoice() {
		if (voice == null) throw new JobFailedException(SPEAK_UNAVAILABLE);
		if (voiceReady.get() && natives.loaded()) return;
		synchronized (readiness) {
			try {
				install(voice, voiceReady, voiceProgress);
				voiceFailure = null;
			} catch (IOException | RuntimeException | LinkageError e) {
				voiceFailure = reason(e);
				throw new JobFailedException(NOT_READY.formatted(voiceFailure));
			}
		}
	}

	/** The libraries in place, {@code m}'s files fetched unless {@code fetched} says they are, then the libraries loaded. */
	private void install(SpeechModels.Model m, AtomicBoolean fetched, AtomicLong progress) throws IOException {
		Path root = cache.get();
		Path libraries = natives.install(nativePath, fetchNatives, root, download, nativeProgress);
		Path directory = modelDirectory(root, m);
		for (SpeechModels.ModelFile file : m.files()) {
			if (!fetched.get()) download.fetch(file.url(), file.bytes(), file.sha256(), directory.resolve(file.name()), progress);
		}
		fetched.set(true);
		if (!natives.loaded()) {
			Path loads = root.resolve("native").resolve(NativeLibraries.VERSION).resolve("loads");
			NativeLibraries.sweep(loads);
			natives.load(libraries, loads);
		}
	}

	private static String reason(Throwable e) {
		return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
	}

	private static Path modelDirectory(Path root, SpeechModels.Model m) {
		return root.resolve("models").resolve(m.id());
	}

	/** Runs on the worker thread only. */
	private Backend backend() {
		if (backend == null) {
			long started = System.nanoTime();
			backend = backends.open(model, modelDirectory(cache.get(), model), threads);
			log.info("speech: {} loaded in {} ms on {} threads", model.id(), (System.nanoTime() - started) / 1_000_000, threads);
		}
		return backend;
	}

	/** Runs on the worker thread only. */
	private Speaker speaker() {
		if (speaker == null) {
			long started = System.nanoTime();
			speaker = speakers.open(voice, modelDirectory(cache.get(), voice), threads);
			log.info("speech: {} loaded in {} ms on {} threads", voice.id(), (System.nanoTime() - started) / 1_000_000, threads);
		}
		return speaker;
	}

	private void releaseIfIdle() {
		worker.execute(() -> {
			long idleNanos = TimeUnit.MILLISECONDS.toNanos(IDLE_MILLIS);
			if (backend != null && System.nanoTime() - lastUse > idleNanos) {
				backend.close();
				backend = null;
				log.info("speech: {} released after {} minutes unused", model.id(), IDLE_MILLIS / 60_000);
			}
			if (speaker != null && System.nanoTime() - lastSpoke > idleNanos) {
				speaker.close();
				speaker = null;
				log.info("speech: {} released after {} minutes unused", voice.id(), IDLE_MILLIS / 60_000);
			}
		});
	}

	/** Consecutive stretches joined into pieces of at most {@code max} samples; a longer stretch stays alone. */
	static List<float[]> merge(List<float[]> stretches, int max) {
		List<float[]> pieces = new ArrayList<>();
		List<float[]> current = new ArrayList<>();
		int length = 0;
		for (float[] s : stretches) {
			if (length > 0 && length + s.length > max) {
				pieces.add(join(current, length));
				current.clear();
				length = 0;
			}
			current.add(s);
			length += s.length;
		}
		if (length > 0) pieces.add(join(current, length));
		return pieces;
	}

	private static float[] join(List<float[]> parts, int length) {
		float[] out = new float[length];
		int at = 0;
		for (float[] p : parts) {
			System.arraycopy(p, 0, out, at, p.length);
			at += p.length;
		}
		return out;
	}

	/**
	 * What {@code speech:engines} reports: readiness and its reason, never paths. The
	 * model it transcribes with is {@code model}, ready as {@code state}; the one it
	 * speaks with is {@code voiceModel}, ready as {@code voiceState}.
	 */
	AMap<AString, ACell> status() {
		String unsupported = natives.unsupported();
		AMap<AString, ACell> status = Maps.of(
			"name", NAME,
			"local", CVMBool.TRUE,
			"nativeAccess", CVMBool.create(NativeLibraries.nativeAccessEnabled()),
			"transcribes", CVMBool.create(model != null),
			"speaks", CVMBool.create(voice != null),
			"keyed", CVMBool.FALSE);
		if (model != null) {
			status = status.assoc(Strings.intern("transcribeModel"), Strings.create(model.id()))
				.assoc(Strings.intern("model"), describe(model, modelProgress))
				.assoc(Strings.intern("state"), Strings.create(state(model, modelReady, modelProgress, failure)));
			String reason = unsupported != null ? unsupported : failure;
			if (reason != null) status = status.assoc(Strings.intern("reason"), Strings.create(reason));
		}
		if (voice != null) {
			status = status.assoc(Strings.intern("speakModel"), Strings.create(voice.id()))
				.assoc(Strings.intern("voiceModel"), describe(voice, voiceProgress))
				.assoc(Strings.intern("voiceState"), Strings.create(state(voice, voiceReady, voiceProgress, voiceFailure)));
			String reason = unsupported != null ? unsupported : voiceFailure;
			if (reason != null) status = status.assoc(Strings.intern("voiceReason"), Strings.create(reason));
		}
		return status;
	}

	private static AMap<AString, ACell> describe(SpeechModels.Model m, AtomicLong progress) {
		return Maps.of("id", m.id(), "label", m.label(), "languages", m.languages(), "licence", m.licence(),
			"bytes", CVMLong.create(m.bytes()), "downloaded", CVMLong.create(Math.min(progress.get(), m.bytes())));
	}

	private String state(SpeechModels.Model m, AtomicBoolean ready, AtomicLong progress, String failed) {
		if (ready.get() && natives.loaded()) return "ready";
		if (progress.get() > 0 && failed == null) return "downloading";
		return present(m) ? "downloaded" : "not downloaded";
	}

	/** Whether a model's files are already in place, without fetching anything. */
	boolean present(SpeechModels.Model m) {
		try {
			Path directory = modelDirectory(cache.get(), m);
			for (SpeechModels.ModelFile f : m.files()) if (!Files.isRegularFile(directory.resolve(f.name()))) return false;
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	void close() {
		worker.execute(() -> {
			if (backend != null) backend.close();
			backend = null;
			if (speaker != null) speaker.close();
			speaker = null;
		});
		worker.shutdown();
		idle.shutdownNow();
	}

	/** sherpa-onnx itself: an offline recogniser for the model, Silero VAD for the pauses. */
	static final class Sherpa implements Backend {
		private final com.k2fsa.sherpa.onnx.OfflineRecognizer recogniser;
		private final Path vad;

		Sherpa(SpeechModels.Model model, Path d, int threads) {
			com.k2fsa.sherpa.onnx.OfflineModelConfig.Builder config = com.k2fsa.sherpa.onnx.OfflineModelConfig.builder()
				.setTokens(d.resolve("tokens.txt").toString()).setNumThreads(threads).setDebug(false);
			switch (model.kind()) {
				case NEMO_TRANSDUCER -> config.setModelType("nemo_transducer")
					.setTransducer(com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig.builder()
						.setEncoder(d.resolve("encoder.int8.onnx").toString())
						.setDecoder(d.resolve("decoder.int8.onnx").toString())
						.setJoiner(d.resolve("joiner.int8.onnx").toString()).build());
				case MOONSHINE -> config.setMoonshine(com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig.builder()
					.setPreprocessor(d.resolve("preprocess.onnx").toString())
					.setEncoder(d.resolve("encode.int8.onnx").toString())
					.setUncachedDecoder(d.resolve("uncached_decode.int8.onnx").toString())
					.setCachedDecoder(d.resolve("cached_decode.int8.onnx").toString()).build());
			}
			recogniser = new com.k2fsa.sherpa.onnx.OfflineRecognizer(com.k2fsa.sherpa.onnx.OfflineRecognizerConfig.builder()
				.setOfflineModelConfig(config.build()).setDecodingMethod("greedy_search").build());
			vad = d.resolve(SpeechModels.VAD.name());
		}

		/** Silero VAD closing a stretch at a half-second pause, or at {@code maxSeconds}. */
		private com.k2fsa.sherpa.onnx.Vad finder(int maxSeconds) {
			return new com.k2fsa.sherpa.onnx.Vad(com.k2fsa.sherpa.onnx.VadModelConfig.builder()
				.setSileroVadModelConfig(com.k2fsa.sherpa.onnx.SileroVadModelConfig.builder()
					.setModel(vad.toString()).setThreshold(0.5f).setMinSilenceDuration(0.5f).setMinSpeechDuration(0.25f)
					.setMaxSpeechDuration(maxSeconds).setWindowSize(WINDOW).build())
				.setSampleRate(AudioDecoder.RATE).setNumThreads(1).setDebug(false).setProvider("cpu").build());
		}

		@Override public Pauses pauses(int maxSeconds) {
			com.k2fsa.sherpa.onnx.Vad finder = finder(maxSeconds);
			return new Pauses() {
				@Override public void accept(float[] window) { finder.acceptWaveform(window); }
				@Override public List<Integer> closed() {
					List<Integer> ends = new ArrayList<>();
					while (!finder.empty()) {
						com.k2fsa.sherpa.onnx.SpeechSegment s = finder.front();
						ends.add(s.getStart() + s.getSamples().length);
						finder.pop();
					}
					return ends;
				}
				@Override public boolean speaking() { return finder.isSpeechDetected(); }
				@Override public void flush() { finder.flush(); }
				@Override public void release() { finder.release(); }
			};
		}

		@Override public List<float[]> stretches(float[] samples) {
			// Close a stretch at a half-second pause, or at the longest piece decoded whole.
			com.k2fsa.sherpa.onnx.Vad finder = finder(WHOLE_SECONDS);
			List<float[]> out = new ArrayList<>();
			try {
				for (int i = 0; i + WINDOW <= samples.length; i += WINDOW) {
					finder.acceptWaveform(java.util.Arrays.copyOfRange(samples, i, i + WINDOW));
					while (!finder.empty()) { out.add(finder.front().getSamples()); finder.pop(); }
				}
				finder.flush();
				while (!finder.empty()) { out.add(finder.front().getSamples()); finder.pop(); }
			} finally {
				finder.release();
			}
			return out;
		}

		@Override public String decode(float[] samples) {
			com.k2fsa.sherpa.onnx.OfflineStream stream = recogniser.createStream();
			try {
				stream.acceptWaveform(samples, AudioDecoder.RATE);
				recogniser.decode(stream);
				return recogniser.getResult(stream).getText();
			} finally {
				stream.release();
			}
		}

		@Override public void close() { recogniser.release(); }
	}

	/** sherpa-onnx's offline synthesiser for a voice model. */
	static final class SherpaVoice implements Speaker {
		private final com.k2fsa.sherpa.onnx.OfflineTts tts;

		SherpaVoice(SpeechModels.Model model, Path d, int threads) {
			com.k2fsa.sherpa.onnx.OfflineTtsModelConfig.Builder config = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig.builder()
				.setNumThreads(threads).setDebug(false);
			switch (model.kind()) {
				case SUPERTONIC -> config.setSupertonic(com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig.builder()
					.setDurationPredictor(d.resolve("duration_predictor.int8.onnx").toString())
					.setTextEncoder(d.resolve("text_encoder.int8.onnx").toString())
					.setVectorEstimator(d.resolve("vector_estimator.int8.onnx").toString())
					.setVocoder(d.resolve("vocoder.int8.onnx").toString())
					.setTtsJson(d.resolve("tts.json").toString())
					.setUnicodeIndexer(d.resolve("unicode_indexer.bin").toString())
					.setVoiceStyle(d.resolve("voice.bin").toString()).build());
				case VITS -> config.setVits(com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig.builder()
					.setModel(d.resolve(model.files().get(0).name()).toString())
					.setLexicon(d.resolve("lexicon.txt").toString())
					.setTokens(d.resolve("tokens.txt").toString()).build());
				default -> throw new IllegalArgumentException(model.id() + " is not a voice");
			}
			tts = new com.k2fsa.sherpa.onnx.OfflineTts(com.k2fsa.sherpa.onnx.OfflineTtsConfig.builder().setModel(config.build()).build());
		}

		/** A single-voice model reports none: it has the one. */
		@Override public int voices() { return Math.max(1, tts.getNumSpeakers()); }

		@Override public Said say(String text, int sid, float speed, String language) {
			com.k2fsa.sherpa.onnx.GenerationConfig generation = new com.k2fsa.sherpa.onnx.GenerationConfig();
			generation.setSid(sid);
			generation.setSpeed(speed);
			generation.setExtra(java.util.Map.of("lang", language));
			com.k2fsa.sherpa.onnx.GeneratedAudio audio = tts.generateWithConfigAndCallback(text, generation,
				(com.k2fsa.sherpa.onnx.OfflineTtsCallback) samples -> 1);
			return new Said(audio.getSamples(), audio.getSampleRate());
		}

		@Override public void close() { tts.release(); }
	}
}
