package covia.venue.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import convex.core.crypto.Hashing;
import convex.core.data.Hash;
import covia.grid.AContent;
import covia.grid.impl.PathContent;

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
 * <p>Content streams in and out without being held in memory. A write is
 * staged and renamed onto its hash's name only when complete, so a crash or a
 * failed upload never leaves partial content under a hash.
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

	/** Name prefix of a write still being staged: dot-named, so never a hash. */
	static final String STAGING_PREFIX = ".staging-";

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

		try (InputStream is = content.getInputStream()) {
			write(hash, is, false);
		}
	}

	@Override
	public void store(Hash hash, InputStream inputStream) throws IOException {
		validateHash(hash);
		if (inputStream == null) {
			throw new IllegalArgumentException("InputStream cannot be null");
		}
		write(hash, inputStream, false);
	}

	/** Streams the content, checking its SHA-256 as it is written. */
	@Override
	public void storeVerified(Hash sha256, InputStream inputStream) throws IOException {
		validateHash(sha256);
		if (inputStream == null) {
			throw new IllegalArgumentException("InputStream cannot be null");
		}
		write(sha256, inputStream, true);
	}

	/**
	 * Write content to a staging file in the storage directory, then rename it
	 * onto the hash's name in one atomic step once it is complete and, when
	 * {@code verify} is set, once its SHA-256 has been checked against the hash.
	 * The hash's name therefore only ever holds complete content: a failed or
	 * refused write removes its staging file and leaves the name as it was.
	 *
	 * <p>A crash can leave a staging file behind. Nothing sweeps them, because a
	 * sweep cannot tell a stale one from a write still in progress; they are
	 * dot-named and left out of {@link #count()}.
	 *
	 * <p>On a DLFS drive (storage type {@code dlfs}) the staging file is visible
	 * in the drive while it is written. Convex's atomic conditional install
	 * (Convex-Dev/convex#790) would publish the content in one step with no
	 * staging name; covia#629.
	 */
	private void write(Hash hash, InputStream in, boolean verify) throws IOException {
		Path target = getFilePath(hash);
		Path staging = createStaging(hash);
		try {
			MessageDigest digest = verify ? Hashing.getSHA256Digest() : null;
			try (OutputStream os = Files.newOutputStream(staging, StandardOpenOption.WRITE)) {
				(verify ? new DigestInputStream(in, digest) : in).transferTo(os);
			}
			if (verify) checkHash(hash, Hash.wrap(digest.digest()));
			try {
				Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Throwable t) {
			try {
				Files.deleteIfExists(staging);
			} catch (IOException e) {
				t.addSuppressed(e);
			}
			throw t;
		}
	}

	/**
	 * Claim a new staging file beside the hash's name. Not
	 * {@code Files.createTempFile}, which passes POSIX permissions that a
	 * non-default filesystem such as a DLFS drive may refuse.
	 */
	private Path createStaging(Hash hash) throws IOException {
		for (int attempt = 0; ; attempt++) {
			Path candidate = baseDirectory.resolve(STAGING_PREFIX + hash.toHexString() + "."
				+ Long.toHexString(ThreadLocalRandom.current().nextLong()));
			try {
				Files.newOutputStream(candidate, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).close();
				return candidate;
			} catch (FileAlreadyExistsException e) {
				if (attempt >= 10) throw e;
			}
		}
	}

	/** Content is read lazily from its file, never loaded whole. */
	@Override
	public AContent getContent(Hash hash) throws IOException {
		validateHash(hash);

		Path filePath = getFilePath(hash);
		if (!Files.exists(filePath)) {
			return null;
		}
		return PathContent.of(filePath);
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
	 * Get the number of stored content files. Staging files are not counted.
	 *
	 * @return The count of content files in the storage directory
	 * @throws IOException if listing directory fails
	 */
	public long count() throws IOException {
		try (Stream<Path> files = Files.list(baseDirectory)) {
			return files.filter(Files::isRegularFile)
				.filter(p -> !p.getFileName().toString().startsWith(STAGING_PREFIX))
				.count();
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
