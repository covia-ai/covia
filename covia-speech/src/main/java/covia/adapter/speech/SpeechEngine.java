package covia.adapter.speech;

/**
 * What the speech adapter needs from an engine: audio to text, text to audio. The
 * adapter owns everything else (reading and storing content under the caller's
 * authority, limits, the media-hook contract, keys), so an engine is only the
 * provider call. {@link OpenAiSpeechEngine} speaks the OpenAI-compatible audio API,
 * served by cloud providers and by local servers alike; an in-process engine would
 * implement this interface too.
 */
interface SpeechEngine {

	/** Audio to transcribe; {@code fileName}'s extension tells the provider its format. */
	record Audio(byte[] bytes, String mediaType, String fileName) {}

	/** A transcription; {@code text} is empty for silence; other fields are null when unknown. */
	record Transcript(String text, String language, Double seconds) {}

	/** Synthesised speech in the requested format. */
	record Speech(byte[] bytes, String mediaType) {}

	/** One text-to-speech request; optional fields are null. */
	record SpeakRequest(String text, String model, String voice, String format, Double speed, String instructions) {}

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
