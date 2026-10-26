package covia.adapter.speech;

/**
 * What the speech adapter needs from an engine: audio to text, text to audio. The
 * adapter owns everything else (reading and storing content under the caller's
 * authority, limits, the media-hook contract, keys), so an engine is only the
 * provider call. {@link OpenAiSpeechEngine} speaks the OpenAI-compatible audio API,
 * served by cloud providers and by local servers alike; {@link LocalSpeechEngine}
 * runs sherpa-onnx in the venue's own process. The adapter may use a different engine
 * for each direction.
 */
interface SpeechEngine {

	/** Audio to transcribe; {@code fileName}'s extension tells the provider its format. */
	record Audio(byte[] bytes, String mediaType, String fileName) {}

	/** A transcription; {@code text} is empty for silence; other fields are null when unknown. */
	record Transcript(String text, String language, Double seconds) {}

	/**
	 * Synthesised speech in the requested format, and any words in it that were spelled
	 * out letter by letter because the voice had no other way to say them.
	 */
	record Speech(byte[] bytes, String mediaType, java.util.List<String> spelled) {
		Speech(byte[] bytes, String mediaType) {
			this(bytes, mediaType, java.util.List.of());
		}
	}

	/**
	 * One text-to-speech request; optional fields are null. {@code language} is for a
	 * model that must be told it, as the local voices are; others ignore it.
	 */
	record SpeakRequest(String text, String model, String voice, String format, Double speed, String instructions,
		String language) {}

	/** A short engine name for results and status, such as {@code openai-compatible}. */
	String name();

	/** Whether the engine runs on this machine, so audio never leaves it. */
	boolean local();

	/** The endpoint a key is sent to, for url-bound secret grants; null when none is. */
	String endpoint();

	/**
	 * @param key the API key, or null to send none (a local server needs none)
	 */
	Transcript transcribe(Audio audio, String model, String language, String prompt, String key);

	/**
	 * @param key the API key, or null to send none
	 */
	Speech speak(SpeakRequest request, String key);
}
