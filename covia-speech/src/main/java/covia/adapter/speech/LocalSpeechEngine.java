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
 * Speech recognised inside the venue's JVM by sherpa-onnx: no audio leaves the machine.
 * The model is fetched once (pinned), loaded when first needed and released after a
 * quiet spell; a note longer than 30 seconds is split on its pauses. Native work runs on
 * one platform thread of its own, one decode at a time, so a long decode never holds a
 * virtual thread's carrier.
 */
final class LocalSpeechEngine implements SpeechEngine {
	private static final Logger log = LoggerFactory.getLogger(LocalSpeechEngine.class);

	public static final String NAME = "local";
	public static final String SPEAK_UNAVAILABLE = "The local speech engine does not speak yet; use the openai-compatible engine for speech";
	public static final String OTHER_MODEL = "The local speech engine runs only its configured model, %s";
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
		@Override void close();
	}

	/** Opens a backend over a ready model's directory. */
	interface Backends { Backend open(SpeechModels.Model model, Path directory, int threads); }

	private final SpeechModels.Model model;
	private final Path nativePath;
	private final boolean fetchNatives;
	private final Supplier<Path> cache;
	private final int threads;
	private final NativeLibraries natives;
	private final Backends backends;
	private final PinnedDownload download = new PinnedDownload();
	private final AtomicLong modelProgress = new AtomicLong();
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
	private volatile boolean modelReady;
	private volatile String failure;
	private Backend backend;
	private long lastUse;

	LocalSpeechEngine(SpeechModels.Model model, Path nativePath, boolean fetchNatives, Supplier<Path> cache, int threads,
			NativeLibraries natives, Backends backends) {
		this.model = model;
		this.nativePath = nativePath;
		this.fetchNatives = fetchNatives;
		this.cache = cache;
		this.threads = threads;
		this.natives = natives;
		this.backends = backends;
		idle.scheduleWithFixedDelay(this::releaseIfIdle, 60, 60, TimeUnit.SECONDS);
	}

	@Override public String name() { return NAME; }
	@Override public boolean local() { return true; }
	@Override public String endpoint() { return null; }
	String modelId() { return model.id(); }

	@Override
	public Transcript transcribe(Audio audio, String modelId, String language, String prompt, String key) {
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
		try {
			return new Transcript(heard.get(), null, samples.length / (double) AudioDecoder.RATE);
		} catch (InterruptedException e) {
			heard.cancel(false);
			Thread.currentThread().interrupt();
			throw new JobFailedException(NOT_READY.formatted("interrupted"));
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException r) throw r;
			throw new JobFailedException(NOT_READY.formatted(cause.getMessage()));
		}
	}

	@Override
	public Speech speak(SpeakRequest request, String key) {
		throw new JobFailedException(SPEAK_UNAVAILABLE);
	}

	/** Install the libraries and fetch the model, if not yet done; safe to call from many threads. */
	void prepare() {
		if (modelReady && natives.loaded()) return;
		synchronized (readiness) {
			try {
				Path root = cache.get();
				Path libraries = natives.install(nativePath, fetchNatives, root, download, nativeProgress);
				Path directory = modelDirectory(root);
				for (SpeechModels.ModelFile file : model.files()) {
					if (!modelReady) download.fetch(file.url(), file.bytes(), file.sha256(), directory.resolve(file.name()), modelProgress);
				}
				modelReady = true;
				if (!natives.loaded()) {
					Path loads = root.resolve("native").resolve(NativeLibraries.VERSION).resolve("loads");
					NativeLibraries.sweep(loads);
					natives.load(libraries, loads);
				}
				failure = null;
			} catch (IOException | RuntimeException | LinkageError e) {
				failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
				throw new JobFailedException(NOT_READY.formatted(failure));
			}
		}
	}

	private Path modelDirectory(Path root) {
		return root.resolve("models").resolve(model.id());
	}

	/** Runs on the worker thread only. */
	private Backend backend() {
		if (backend == null) {
			long started = System.nanoTime();
			backend = backends.open(model, modelDirectory(cache.get()), threads);
			log.info("speech: {} loaded in {} ms on {} threads", model.id(), (System.nanoTime() - started) / 1_000_000, threads);
		}
		return backend;
	}

	private void releaseIfIdle() {
		worker.execute(() -> {
			if (backend != null && System.nanoTime() - lastUse > TimeUnit.MILLISECONDS.toNanos(IDLE_MILLIS)) {
				backend.close();
				backend = null;
				log.info("speech: {} released after {} minutes unused", model.id(), IDLE_MILLIS / 60_000);
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

	/** What {@code speech:engines} reports: readiness and its reason, never paths. */
	AMap<AString, ACell> status() {
		String unsupported = natives.unsupported();
		boolean ready = modelReady && natives.loaded();
		String state = ready ? "ready" : modelProgress.get() > 0 && failure == null ? "downloading"
			: present() ? "downloaded" : "not downloaded";
		AMap<AString, ACell> status = Maps.of(
			"name", NAME,
			"local", CVMBool.TRUE,
			"transcribeModel", model.id(),
			"model", Maps.of("id", model.id(), "label", model.label(), "languages", model.languages(),
				"licence", model.licence(), "bytes", CVMLong.create(model.bytes()), "downloaded", CVMLong.create(Math.min(modelProgress.get(), model.bytes()))),
			"state", state,
			"nativeAccess", CVMBool.create(NativeLibraries.nativeAccessEnabled()),
			"speaks", CVMBool.FALSE,
			"keyed", CVMBool.FALSE);
		String reason = unsupported != null ? unsupported : failure;
		if (reason != null) status = status.assoc(Strings.intern("reason"), Strings.create(reason));
		return status;
	}

	/** Whether this engine's files are already in place, without fetching anything. */
	boolean present() {
		try {
			Path directory = modelDirectory(cache.get());
			for (SpeechModels.ModelFile f : model.files()) if (!Files.isRegularFile(directory.resolve(f.name()))) return false;
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	void close() {
		worker.execute(() -> {
			if (backend != null) backend.close();
			backend = null;
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

		@Override public List<float[]> stretches(float[] samples) {
			// Close a stretch at a half-second pause, or at the longest piece decoded whole.
			com.k2fsa.sherpa.onnx.Vad finder = new com.k2fsa.sherpa.onnx.Vad(com.k2fsa.sherpa.onnx.VadModelConfig.builder()
				.setSileroVadModelConfig(com.k2fsa.sherpa.onnx.SileroVadModelConfig.builder()
					.setModel(vad.toString()).setThreshold(0.5f).setMinSilenceDuration(0.5f).setMinSpeechDuration(0.25f)
					.setMaxSpeechDuration(WHOLE_SECONDS).setWindowSize(512).build())
				.setSampleRate(AudioDecoder.RATE).setNumThreads(1).setDebug(false).setProvider("cpu").build());
			List<float[]> out = new ArrayList<>();
			try {
				for (int i = 0; i + 512 <= samples.length; i += 512) {
					finder.acceptWaveform(java.util.Arrays.copyOfRange(samples, i, i + 512));
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
}
