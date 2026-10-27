package covia.grid.impl;

import java.io.IOException;
import java.io.InputStream;

import convex.core.data.ABlob;
import convex.core.data.Blobs;
import covia.grid.AContent;

/**
 * Content that can be read only once from a stream, such as an HTTP response
 * body. The first consumer chooses: {@link #getInputStream()} hands over the
 * stream itself, while {@link #getBlob()} reads it into memory and keeps the
 * blob, so later calls of either kind are served from that blob.
 *
 * <p>Interim (covia#629): the general streaming content abstraction proposed
 * with Convex-Dev/convex#790 (size, stream, range reads, a blob on demand)
 * would replace this.
 */
public class StreamContent extends AContent {

	/** Refusal when the one-shot stream has already been handed over. */
	public static final String STREAM_TAKEN = "Content stream has already been taken";

	private InputStream stream;
	private ABlob blob;
	private final long size;

	private StreamContent(InputStream stream, long size) {
		this.stream = stream;
		this.size = size;
	}

	/**
	 * @param stream The content, read at most once
	 * @param size The content's size in bytes, or -1 if unknown
	 */
	public static StreamContent of(InputStream stream, long size) {
		if (stream == null) throw new IllegalArgumentException("stream must not be null");
		return new StreamContent(stream, size);
	}

	@Override
	public synchronized ABlob getBlob() throws IOException {
		if (blob != null) return blob;
		if (stream == null) throw new IllegalStateException(STREAM_TAKEN);
		try (InputStream in = stream) {
			stream = null;
			// Interim (covia#629): held in memory. Convex's streaming blob
			// writer (Convex-Dev/convex#790) would save chunks to a store as
			// they arrive.
			blob = Blobs.fromStream(in);
		}
		return blob;
	}

	@Override
	public synchronized InputStream getInputStream() {
		if (blob != null) return blob.getInputStream();
		if (stream == null) throw new IllegalStateException(STREAM_TAKEN);
		InputStream taken = stream;
		stream = null;
		return taken;
	}

	@Override
	public synchronized long getSize() {
		return (blob != null) ? blob.count() : size;
	}
}
