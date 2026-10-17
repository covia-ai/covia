package covia.adapter.speech;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;

/**
 * Audio as the local engine hears it: 16 kHz mono samples from -1 to 1. Reads WAV
 * (16-bit or 32-bit float PCM, any rate and channel count; raw PCM reaches the engine
 * already wrapped as WAV) and Ogg/Opus, the format of a Telegram or WhatsApp voice
 * note, decoded in pure Java by Concentus straight to 16 kHz. Anything else is refused.
 */
final class AudioDecoder {
	static final int RATE = 16_000;
	/** The longest audio decoded: past this, memory and waiting both grow too large. */
	static final int MAX_SECONDS = 600;
	static final String UNSUPPORTED = "The local speech engine reads WAV and Ogg/Opus audio only";
	static final String TOO_LONG = "Audio longer than 10 minutes cannot be transcribed locally";
	static final String MALFORMED = "The audio file is damaged or not what its type says";
	/** Opus decodes at most 120 ms per packet: 1,920 samples a channel at 16 kHz. */
	private static final int MAX_FRAME = RATE * 120 / 1000;

	private AudioDecoder() {}

	static float[] decode(byte[] audio) {
		if (startsWith(audio, 0, "RIFF") && startsWith(audio, 8, "WAVE")) return wav(audio);
		if (startsWith(audio, 0, "OggS")) return opus(audio);
		throw new IllegalArgumentException(UNSUPPORTED);
	}

	// ---- WAV ----

	private static float[] wav(byte[] audio) {
		ByteBuffer in = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN);
		int format = -1, channels = 0, rate = 0, bits = 0, dataAt = -1, dataLength = 0;
		int at = 12;
		try {
			while (at + 8 <= audio.length) {
				String id = new String(audio, at, 4, StandardCharsets.US_ASCII);
				int size = in.getInt(at + 4);
				if (size < 0) throw new IllegalArgumentException(MALFORMED);
				if (id.equals("fmt ")) {
					format = in.getShort(at + 8) & 0xffff;
					channels = in.getShort(at + 10) & 0xffff;
					rate = in.getInt(at + 12);
					bits = in.getShort(at + 22) & 0xffff;
					// WAVE_FORMAT_EXTENSIBLE carries the real format in its sub-format GUID.
					if (format == 0xfffe && size >= 40) format = in.getShort(at + 32) & 0xffff;
				} else if (id.equals("data")) {
					dataAt = at + 8;
					dataLength = Math.min(size, audio.length - dataAt);
					break;
				}
				at += 8 + size + (size & 1);
			}
		} catch (IndexOutOfBoundsException e) {
			throw new IllegalArgumentException(MALFORMED);
		}
		boolean pcm16 = format == 1 && bits == 16, float32 = format == 3 && bits == 32;
		if (dataAt < 0 || channels < 1 || rate < 8000 || rate > 192_000 || !(pcm16 || float32)) throw new IllegalArgumentException(UNSUPPORTED);
		int frameBytes = channels * bits / 8;
		long frames = dataLength / frameBytes;
		if (frames / rate > MAX_SECONDS) throw new IllegalArgumentException(TOO_LONG);
		float[] mono = new float[(int) frames];
		for (int i = 0; i < mono.length; i++) {
			float sum = 0;
			for (int c = 0; c < channels; c++) {
				int p = dataAt + i * frameBytes + c * bits / 8;
				sum += pcm16 ? in.getShort(p) / 32768f : in.getFloat(p);
			}
			mono[i] = sum / channels;
		}
		return resample(mono, rate);
	}

	/** To 16 kHz: a box filter against aliasing when lowering the rate, then linear interpolation. */
	static float[] resample(float[] samples, int rate) {
		if (rate == RATE) return samples;
		float[] source = samples;
		int width = (int) Math.ceil((double) rate / RATE);
		if (width > 1) {
			source = new float[samples.length];
			float sum = 0;
			for (int i = 0; i < samples.length; i++) {
				sum += samples[i];
				if (i >= width) sum -= samples[i - width];
				source[i] = sum / Math.min(i + 1, width);
			}
		}
		int length = (int) ((long) samples.length * RATE / rate);
		float[] out = new float[length];
		double step = (double) rate / RATE;
		for (int i = 0; i < length; i++) {
			double position = i * step;
			int left = (int) position;
			int right = Math.min(left + 1, source.length - 1);
			double t = position - left;
			out[i] = (float) (source[left] * (1 - t) + source[right] * t);
		}
		return out;
	}

	// ---- Ogg/Opus ----

	private static float[] opus(byte[] audio) {
		List<byte[]> packets = OggReader.packets(audio);
		if (packets.size() < 2 || !startsWith(packets.get(0), 0, "OpusHead") || packets.get(0).length < 19) {
			throw new IllegalArgumentException(UNSUPPORTED);
		}
		byte[] head = packets.get(0);
		int channels = head[9] & 0xff;
		int preSkip = ((head[10] & 0xff) | (head[11] & 0xff) << 8) * RATE / 48_000;
		int mapping = head[18] & 0xff;
		if (channels < 1 || channels > 2 || mapping != 0) throw new IllegalArgumentException(UNSUPPORTED);
		OpusDecoder decoder;
		try { decoder = new OpusDecoder(RATE, channels); }
		catch (OpusException e) { throw new IllegalArgumentException(MALFORMED); }
		short[] frame = new short[MAX_FRAME * channels];
		float[] out = new float[RATE * 10];
		int length = 0;
		// Packet 1 is OpusTags; audio follows.
		for (int p = 2; p < packets.size(); p++) {
			byte[] packet = packets.get(p);
			int samples;
			try { samples = decoder.decode(packet, 0, packet.length, frame, 0, MAX_FRAME, false); }
			catch (OpusException | RuntimeException e) { throw new IllegalArgumentException(MALFORMED); }
			if (length + samples > MAX_SECONDS * RATE + preSkip) throw new IllegalArgumentException(TOO_LONG);
			if (length + samples > out.length) out = java.util.Arrays.copyOf(out, Math.max(out.length * 2, length + samples));
			for (int i = 0; i < samples; i++) {
				float sum = 0;
				for (int c = 0; c < channels; c++) sum += frame[i * channels + c] / 32768f;
				out[length++] = sum / channels;
			}
		}
		int start = Math.min(preSkip, length);
		return java.util.Arrays.copyOfRange(out, start, length);
	}

	/** The packets of the first logical stream of an Ogg file, in order. */
	static final class OggReader {
		private OggReader() {}

		static List<byte[]> packets(byte[] ogg) {
			List<byte[]> packets = new ArrayList<>();
			java.io.ByteArrayOutputStream packet = new java.io.ByteArrayOutputStream();
			int at = 0;
			Integer serial = null;
			try {
				while (at + 27 <= ogg.length) {
					if (!startsWith(ogg, at, "OggS") || ogg[at + 4] != 0) throw new IllegalArgumentException(MALFORMED);
					int pageSerial = ByteBuffer.wrap(ogg, at + 14, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
					int segments = ogg[at + 26] & 0xff;
					int body = at + 27 + segments;
					int bodyLength = 0;
					for (int s = 0; s < segments; s++) bodyLength += ogg[at + 27 + s] & 0xff;
					if (body + bodyLength > ogg.length) throw new IllegalArgumentException(MALFORMED);
					if (serial == null) serial = pageSerial;
					if (pageSerial == serial) {
						int offset = body;
						for (int s = 0; s < segments; s++) {
							int lace = ogg[at + 27 + s] & 0xff;
							packet.write(ogg, offset, lace);
							offset += lace;
							if (lace < 255) {
								packets.add(packet.toByteArray());
								packet.reset();
							}
						}
					}
					at = body + bodyLength;
				}
			} catch (IndexOutOfBoundsException e) {
				throw new IllegalArgumentException(MALFORMED);
			}
			return packets;
		}
	}

	private static boolean startsWith(byte[] bytes, int at, String magic) {
		if (bytes.length < at + magic.length()) return false;
		for (int i = 0; i < magic.length(); i++) if (bytes[at + i] != magic.charAt(i)) return false;
		return true;
	}
}
