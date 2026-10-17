package covia.adapter.speech;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;

/**
 * Audio reaches the local engine as 16 kHz mono: WAV at other rates and channel counts
 * is mixed down and resampled; an Ogg/Opus voice note (encoded here with Concentus's own
 * encoder) decodes back to the same length and loudness; anything else is refused.
 */
class AudioDecoderTest {

	static float[] tone(int rate, double seconds) {
		float[] out = new float[(int) (rate * seconds)];
		for (int i = 0; i < out.length; i++) out[i] = (float) (0.5 * Math.sin(2 * Math.PI * 440 * i / rate));
		return out;
	}

	static double rms(float[] samples) {
		double sum = 0;
		for (float s : samples) sum += s * s;
		return Math.sqrt(sum / Math.max(1, samples.length));
	}

	static byte[] wav16(float[] mono, int rate, int channels) {
		ByteBuffer data = ByteBuffer.allocate(mono.length * channels * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (float s : mono) for (int c = 0; c < channels; c++) data.putShort((short) (s * 32767));
		return header(data.array(), rate, channels, 1, 16);
	}

	static byte[] header(byte[] data, int rate, int channels, int format, int bits) {
		ByteBuffer out = ByteBuffer.allocate(44 + data.length).order(ByteOrder.LITTLE_ENDIAN);
		out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + data.length).put("WAVE".getBytes(StandardCharsets.US_ASCII))
			.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) format).putShort((short) channels)
			.putInt(rate).putInt(rate * channels * bits / 8).putShort((short) (channels * bits / 8)).putShort((short) bits)
			.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(data.length).put(data);
		return out.array();
	}

	@Test void wavAtAnyRateAndChannelCountBecomes16kMono() {
		float[] decoded = AudioDecoder.decode(wav16(tone(48_000, 1.0), 48_000, 2));
		assertEquals(16_000, decoded.length, 1);
		assertEquals(rms(tone(16_000, 1.0)), rms(decoded), 0.03, "loudness survives the mix-down and resampling");

		ByteBuffer floats = ByteBuffer.allocate(8_000 * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (float s : tone(8_000, 1.0)) floats.putFloat(s);
		assertEquals(16_000, AudioDecoder.decode(header(floats.array(), 8_000, 1, 3, 32)).length, 1, "float WAV at 8 kHz is raised");
	}

	@Test void anOggOpusVoiceNoteDecodes() throws Exception {
		float[] original = tone(16_000, 2.0);
		float[] decoded = AudioDecoder.decode(oggOpus(original, 16_000));
		assertEquals(original.length, decoded.length, 400, "the pre-skip is dropped and the length kept");
		assertEquals(rms(original), rms(decoded), 0.08);
	}

	@Test void otherFormatsTooLongAndDamagedAudioAreRefused() throws Exception {
		assertEquals(AudioDecoder.UNSUPPORTED, assertThrows(IllegalArgumentException.class,
			() -> AudioDecoder.decode("ID3\u0003mp3-ish".getBytes(StandardCharsets.ISO_8859_1))).getMessage());
		assertEquals(AudioDecoder.UNSUPPORTED, assertThrows(IllegalArgumentException.class,
			() -> AudioDecoder.decode(header(new byte[1600], 8_000, 1, 1, 8))).getMessage(), "8-bit WAV is not read");
		byte[] long8k = new byte[(AudioDecoder.MAX_SECONDS + 1) * 8_000 * 2];
		assertEquals(AudioDecoder.TOO_LONG, assertThrows(IllegalArgumentException.class,
			() -> AudioDecoder.decode(header(long8k, 8_000, 1, 1, 16))).getMessage());
		byte[] ogg = oggOpus(tone(16_000, 0.5), 16_000);
		assertEquals(AudioDecoder.MALFORMED, assertThrows(IllegalArgumentException.class,
			() -> AudioDecoder.decode(java.util.Arrays.copyOf(ogg, ogg.length - 10))).getMessage());
	}

	/** An Ogg/Opus file as a phone writes one: OpusHead, OpusTags, then 20 ms packets. */
	static byte[] oggOpus(float[] mono, int rate) throws Exception {
		OpusEncoder encoder = new OpusEncoder(rate, 1, OpusApplication.OPUS_APPLICATION_VOIP);
		encoder.setBitrate(24_000);
		int frame = rate / 50;
		List<byte[]> packets = new ArrayList<>();
		ByteBuffer head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
		head.put("OpusHead".getBytes(StandardCharsets.US_ASCII)).put((byte) 1).put((byte) 1).putShort((short) 312)
			.putInt(rate).putShort((short) 0).put((byte) 0);
		packets.add(head.array());
		packets.add("OpusTags\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1));
		short[] pcm = new short[frame];
		byte[] out = new byte[1275];
		for (int at = 0; at + frame <= mono.length; at += frame) {
			for (int i = 0; i < frame; i++) pcm[i] = (short) (mono[at + i] * 32767);
			int n = encoder.encode(pcm, 0, frame, out, 0, out.length);
			packets.add(java.util.Arrays.copyOf(out, n));
		}
		ByteArrayOutputStream ogg = new ByteArrayOutputStream();
		int sequence = 0;
		for (byte[] packet : packets) {
			ByteBuffer page = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN);
			page.put("OggS".getBytes(StandardCharsets.US_ASCII)).put((byte) 0).put((byte) (sequence == 0 ? 2 : 0))
				.putLong(0).putInt(7).putInt(sequence++).putInt(0);
			int laces = packet.length / 255 + 1;
			page.put((byte) laces);
			ogg.writeBytes(page.array());
			for (int i = 0; i < laces - 1; i++) ogg.write(255);
			ogg.write(packet.length % 255);
			ogg.writeBytes(packet);
		}
		return ogg.toByteArray();
	}
}
