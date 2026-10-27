package covia.grid.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;

public class StreamContentTest {

	private static final byte[] DATA = "one-shot content".getBytes();

	@Test
	public void streamIsHandedOverOnce() throws IOException {
		StreamContent content = StreamContent.of(new ByteArrayInputStream(DATA), DATA.length);
		assertEquals(DATA.length, content.getSize());

		try (InputStream in = content.getInputStream()) {
			assertArrayEquals(DATA, in.readAllBytes());
		}
		IllegalStateException again = assertThrows(IllegalStateException.class, content::getInputStream);
		assertEquals(StreamContent.STREAM_TAKEN, again.getMessage());
		IllegalStateException blob = assertThrows(IllegalStateException.class, content::getBlob);
		assertEquals(StreamContent.STREAM_TAKEN, blob.getMessage());
	}

	@Test
	public void blobIsKeptForLaterReads() throws IOException {
		StreamContent content = StreamContent.of(new ByteArrayInputStream(DATA), -1);
		assertEquals(-1, content.getSize(), "size unknown until read");

		assertEquals(Blob.wrap(DATA), content.getBlob().toFlatBlob());
		assertEquals(DATA.length, content.getSize());
		try (InputStream in = content.getInputStream()) {
			assertArrayEquals(DATA, in.readAllBytes(), "served from the kept blob");
		}
	}
}
