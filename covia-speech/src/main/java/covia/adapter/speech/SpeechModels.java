package covia.adapter.speech;

import java.util.List;

/**
 * The speech-to-text models the local engine runs, each file pinned: its address names
 * a fixed revision, and its size and sha256 are written here, so a download is the file
 * that was tested or nothing. The catalogue is GetMine's (docs/proposals/
 * VOICE_TRANSCRIPTION.md), from the copies sherpa-onnx's maintainer publishes on
 * Hugging Face, file by file, so nothing needs unpacking.
 */
final class SpeechModels {
	private SpeechModels() {}

	/** How sherpa-onnx is configured for a model. */
	enum Kind { NEMO_TRANSDUCER, MOONSHINE }

	/** One file of a model, where it comes from, and what it must be. */
	record ModelFile(String name, String url, long bytes, String sha256) {}

	/** A model, with the facts a status report shows. */
	record Model(String id, String label, String languages, String licence, Kind kind, List<ModelFile> files) {
		long bytes() { return files.stream().mapToLong(ModelFile::bytes).sum(); }
	}

	/** Silero VAD v5 (MIT): finds the pauses a long note is split on. Every model carries it. */
	static final ModelFile VAD = new ModelFile("silero_vad_v5.onnx",
		"https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad_v5.onnx",
		2_313_101L, "6b99cbfd39246b6706f98ec13c7c50c6b299181f2474fa05cbc8046acc274396");

	private static ModelFile hf(String repo, String revision, String name, long bytes, String sha256) {
		return new ModelFile(name, "https://huggingface.co/" + repo + "/resolve/" + revision + "/" + name, bytes, sha256);
	}

	private static final String PARAKEET_V3 = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8";
	private static final String PARAKEET_V3_REV = "2bda32ec70b097a55adaa07d9a7173915b43cc78";
	private static final String PARAKEET_V2 = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8";
	private static final String PARAKEET_V2_REV = "1ab9323565ddb038682214b292f588070a538ce2";
	private static final String MOONSHINE_BASE = "csukuangfj/sherpa-onnx-moonshine-base-en-int8";
	private static final String MOONSHINE_BASE_REV = "052b0798ad1bf046a140fdd4efcd9426530fa3f5";
	private static final String MOONSHINE_TINY = "csukuangfj/sherpa-onnx-moonshine-tiny-en-int8";
	private static final String MOONSHINE_TINY_REV = "bf2b762c076d8ea61e2af0b3851c9564fb77552e";
	private static final String MOONSHINE_TOKENS = "1165c2aeb9f72f457a83be2d459a09054f27490acd9b41bd43794dfd25e296ea";

	/** The default: Parakeet TDT v3, 25 European languages. */
	static final String DEFAULT = "parakeet-tdt-v3";

	/** The catalogue, the recommended model first. */
	static final List<Model> ALL = List.of(
		new Model(DEFAULT, "Parakeet TDT 0.6B v3", "25 European languages", "CC-BY-4.0, NVIDIA", Kind.NEMO_TRANSDUCER, List.of(
			hf(PARAKEET_V3, PARAKEET_V3_REV, "encoder.int8.onnx", 652_184_281L, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
			hf(PARAKEET_V3, PARAKEET_V3_REV, "decoder.int8.onnx", 11_845_275L, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
			hf(PARAKEET_V3, PARAKEET_V3_REV, "joiner.int8.onnx", 6_355_277L, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
			hf(PARAKEET_V3, PARAKEET_V3_REV, "tokens.txt", 93_939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
			VAD)),
		new Model("parakeet-tdt-v2", "Parakeet TDT 0.6B v2", "English", "CC-BY-4.0, NVIDIA", Kind.NEMO_TRANSDUCER, List.of(
			hf(PARAKEET_V2, PARAKEET_V2_REV, "encoder.int8.onnx", 652_184_296L, "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
			hf(PARAKEET_V2, PARAKEET_V2_REV, "decoder.int8.onnx", 7_257_753L, "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
			hf(PARAKEET_V2, PARAKEET_V2_REV, "joiner.int8.onnx", 1_739_080L, "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
			hf(PARAKEET_V2, PARAKEET_V2_REV, "tokens.txt", 9_384L, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
			VAD)),
		new Model("moonshine-base", "Moonshine base", "English", "MIT, Useful Sensors", Kind.MOONSHINE, List.of(
			hf(MOONSHINE_BASE, MOONSHINE_BASE_REV, "preprocess.onnx", 14_077_290L, "ffa630d395c5ccf76f5d4954be5b882df76aaf6491519ec01fd82ea7a3819fb2"),
			hf(MOONSHINE_BASE, MOONSHINE_BASE_REV, "encode.int8.onnx", 50_311_494L, "7e38770f776f2e5583a53b052936005df2ba5c833d7e09c2a5fd796b94bf73e2"),
			hf(MOONSHINE_BASE, MOONSHINE_BASE_REV, "uncached_decode.int8.onnx", 122_120_451L, "c01f4b35093bcac20d352d23a75a539e772964579f9d024a90e5e6f09cae9987"),
			hf(MOONSHINE_BASE, MOONSHINE_BASE_REV, "cached_decode.int8.onnx", 99_983_837L, "2db74e51cedf64a8b1be3c8192e0bb5e4923af0e90bd9e87f8e8771873f8ea03"),
			hf(MOONSHINE_BASE, MOONSHINE_BASE_REV, "tokens.txt", 436_688L, MOONSHINE_TOKENS),
			VAD)),
		new Model("moonshine-tiny", "Moonshine tiny", "English", "MIT, Useful Sensors", Kind.MOONSHINE, List.of(
			hf(MOONSHINE_TINY, MOONSHINE_TINY_REV, "preprocess.onnx", 6_800_738L, "f33addce61a143460fe753b5ee5b7db255e5140b5b779c065b94f6c83ff0bf4e"),
			hf(MOONSHINE_TINY, MOONSHINE_TINY_REV, "encode.int8.onnx", 18_249_187L, "8774dfba578de027ec6595c2c654a0836434489bc963a0db124a7f181f571acb"),
			hf(MOONSHINE_TINY, MOONSHINE_TINY_REV, "uncached_decode.int8.onnx", 53_216_096L, "216737000dd5881a17aa043f6bbd286add33e4c3b0ae257153e2ec15438bdc41"),
			hf(MOONSHINE_TINY, MOONSHINE_TINY_REV, "cached_decode.int8.onnx", 45_264_830L, "2aff28bba6a03d8dcf5c9feac45462629bae37317442299f28115ad09da773f6"),
			hf(MOONSHINE_TINY, MOONSHINE_TINY_REV, "tokens.txt", 436_688L, MOONSHINE_TOKENS),
			VAD)));

	/** The model with this id, or null. */
	static Model byId(String id) {
		for (Model m : ALL) if (m.id().equals(id)) return m;
		return null;
	}
}
