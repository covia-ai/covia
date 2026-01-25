package covia.venue.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import convex.core.data.Hash;
import covia.grid.AContent;
import covia.grid.impl.BlobContent;

/**
 * File-based storage implementation that persists content to a filesystem directory.
 *
 * <p>FileStorage stores content as files in a configured base directory, using the
 * lowercase hex representation of the content hash as the filename. This provides:
 * <ul>
 *   <li>Persistent storage across application restarts</li>
 *   <li>Content-addressed file naming for deduplication</li>
 *   <li>Simple file-based backup and inspection</li>
 * </ul>
 *
 * <h2>File Layout</h2>
 * <pre>
 * base-directory/
 *   a1b2c3d4...ef01  (64 lowercase hex chars = SHA-256 hash)
 *   f0e1d2c3...4567
 *   ...
 * </pre>
 *
 * <h2>Example Usage</h2>
 * <pre>
 * FileStorage storage = new FileStorage(Paths.get("/data/content"));
 *
 * Hash hash = storage.store(contentHash, inputStream);
 * AContent content = storage.getContent(hash);
 * </pre>
 */
public class FileStorage extends AStorage {

	private final Path baseDirectory;

	/**
	 * Create a FileStorage with the specified base directory path.
	 * The directory must exist and be a directory.
	 *
	 * @param basePath Path to the directory where content files will be stored
	 * @throws IllegalArgumentException if basePath is null or not a directory
	 */
	public FileStorage(Path basePath) {
		if (basePath == null) {
			throw new IllegalArgumentException("Base path cannot be null");
		}
		if (!Files.isDirectory(basePath)) {
			throw new IllegalArgumentException("Base path is not a directory: " + basePath);
		}
		this.baseDirectory = basePath;
	}

	/**
	 * Create a FileStorage with the specified base directory path string.
	 * The directory must exist and be a directory.
	 *
	 * @param basePath String path to the directory where content files will be stored
	 * @throws IllegalArgumentException if basePath is not a directory
	 */
	public FileStorage(String basePath) {
		this(Paths.get(basePath));
	}

	@Override
	public void initialise() throws IOException {
		// No initialization needed - directory validated in constructor
	}

	@Override
	public void store(Hash hash, AContent content) throws IOException {
		validateHash(hash);
		if (content == null) {
			throw new IllegalArgumentException("Content cannot be null");
		}

		Path filePath = getFilePath(hash);
		try (InputStream is = content.getInputStream();
			 OutputStream os = Files.newOutputStream(filePath)) {
			is.transferTo(os);
		}
	}

	@Override
	public void store(Hash hash, InputStream inputStream) throws IOException {
		validateHash(hash);
		if (inputStream == null) {
			throw new IllegalArgumentException("InputStream cannot be null");
		}

		Path filePath = getFilePath(hash);
		try (OutputStream os = Files.newOutputStream(filePath)) {
			inputStream.transferTo(os);
		}
	}

	@Override
	public AContent getContent(Hash hash) throws IOException {
		validateHash(hash);

		Path filePath = getFilePath(hash);
		if (!Files.exists(filePath)) {
			return null;
		}

		try (InputStream is = Files.newInputStream(filePath)) {
			return BlobContent.from(is);
		}
	}

	@Override
	public boolean exists(Hash hash) {
		if (hash == null) {
			return false;
		}
		return Files.exists(getFilePath(hash));
	}

	@Override
	public boolean delete(Hash hash) throws IOException {
		validateHash(hash);

		Path filePath = getFilePath(hash);
		return Files.deleteIfExists(filePath);
	}

	@Override
	public long getSize(Hash hash) throws IllegalStateException {
		validateHash(hash);

		Path filePath = getFilePath(hash);
		if (!Files.exists(filePath)) {
			throw new IllegalStateException("Content does not exist for hash: " + hash);
		}

		try {
			return Files.size(filePath);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to get file size for hash: " + hash, e);
		}
	}

	@Override
	public void close() {
		// Nothing to close
	}

	@Override
	public boolean isInitialised() {
		return true;
	}

	/**
	 * Get the number of stored content files.
	 *
	 * @return The count of files in the storage directory
	 * @throws IOException if listing directory fails
	 */
	public long count() throws IOException {
		try (Stream<Path> files = Files.list(baseDirectory)) {
			return files.filter(Files::isRegularFile).count();
		}
	}

	/**
	 * Get the base directory path.
	 *
	 * @return The base directory path
	 */
	public Path getBaseDirectory() {
		return baseDirectory;
	}

	/**
	 * Get the file path for a given hash.
	 *
	 * @param hash The content hash
	 * @return Path to the file for this hash
	 */
	private Path getFilePath(Hash hash) {
		return baseDirectory.resolve(hash.toHexString());
	}

	private void validateHash(Hash hash) {
		if (hash == null) {
			throw new IllegalArgumentException("Hash cannot be null");
		}
	}

	@Override
	public String toString() {
		long count = 0;
		try {
			count = count();
		} catch (IOException e) {
			// ignore
		}
		return "FileStorage[" + baseDirectory + ", " + count + " files]";
	}
}
