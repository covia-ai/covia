package covia.grid.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import convex.core.data.ABlob;
import convex.core.data.Blob;
import covia.grid.AContent;

/**
 * Content backed by a filesystem {@link Path} (any {@code java.nio} filesystem,
 * including DLFS drives). Lazy: nothing is read until a consumer asks —
 * {@link #getInputStream()} streams directly from the file, {@link #getSize()}
 * stats it, and only {@link #getBlob()} materialises bytes in memory. Prefer
 * the stream for copies and transfers.
 */
public class PathContent extends AContent {

	private final Path path;

	public PathContent(Path path) {
		if (path == null) throw new IllegalArgumentException("path must not be null");
		this.path = path;
	}

	public static PathContent of(Path path) {
		return new PathContent(path);
	}

	@Override
	public ABlob getBlob() throws IOException {
		return Blob.wrap(Files.readAllBytes(path));
	}

	@Override
	public InputStream getInputStream() {
		try {
			return Files.newInputStream(path);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot open content stream: " + path, e);
		}
	}

	@Override
	public long getSize() {
		try {
			return Files.size(path);
		} catch (IOException e) {
			return -1;
		}
	}
}
