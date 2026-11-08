package covia.venue;

import java.io.IOException;
import java.io.InputStream;

import convex.core.crypto.Hashing;
import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.lattice.IndexComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.grid.AContent;
import covia.grid.impl.BlobContent;
import covia.venue.storage.ContentStorage;

/**
 * The venue's content ({@code :storage}): the bytes of each piece of content,
 * keyed by their SHA-256. It is the {@link ContentStorage} that
 * {@code storage.content: "lattice"} chooses, the default.
 *
 * <p>A content-addressed entry must match its identity, so every store refuses
 * bytes whose SHA-256 is not the hash they are offered under
 * ({@link ContentStorage#HASH_MISMATCH}). The store adds no merge of its own:
 * it travels inside the venue's record. Being a component, it is ready as soon
 * as it is built, and {@link #initialise()} and {@link #close()} change
 * nothing.</p>
 */
public final class ContentStore extends IndexComponent<ABlob, ABlob>
		implements TransactionalComponent<Index<ABlob, ABlob>, ContentStore>, ContentStorage {

	/** Refusal of a store, read or delete without a hash. */
	public static final String NO_HASH = "Hash cannot be null";

	/** Refusal of a store without content. */
	public static final String NO_CONTENT = "Content cannot be null";

	/** Refusal of a size read for a hash with no content. */
	public static final String NOT_STORED = "No content is stored under hash ";

	ContentStore(AForkableCursor<Index<ABlob, ABlob>> cursor) {
		super(cursor);
	}

	@Override
	public ContentStore withCursor(AForkableCursor<Index<ABlob, ABlob>> draft) {
		return new ContentStore(draft);
	}

	/**
	 * Stores {@code blob} under {@code sha256}.
	 *
	 * @throws IllegalArgumentException starting with {@link ContentStorage#HASH_MISMATCH}
	 *         unless the blob's SHA-256 is {@code sha256}
	 */
	public void store(Hash sha256, ABlob blob) {
		requireHash(sha256);
		if (blob == null) throw new IllegalArgumentException(NO_CONTENT);
		ContentStorage.checkHash(sha256, Hashing.sha256(blob));
		put(sha256, blob);
	}

	@Override
	public void store(Hash hash, AContent content) throws IOException {
		if (content == null) throw new IllegalArgumentException(NO_CONTENT);
		store(hash, content.getBlob());
	}

	@Override
	public void store(Hash hash, InputStream inputStream) throws IOException {
		requireHash(hash);
		if (inputStream == null) throw new IllegalArgumentException(NO_CONTENT);
		// Interim (covia#629): the whole content is held in memory. Convex's
		// streaming blob writer (Convex-Dev/convex#790) would save chunks to the
		// store as they arrive, leaving the put as the single publishing step.
		store(hash, Blob.wrap(inputStream.readAllBytes()));
	}

	/** Every store checks the content, so a verified store is a store. */
	@Override
	public void storeVerified(Hash sha256, InputStream inputStream) throws IOException {
		store(sha256, inputStream);
	}

	@Override
	public AContent getContent(Hash hash) {
		requireHash(hash);
		ABlob blob = get(hash);
		return (blob == null) ? null : BlobContent.of(blob);
	}

	@Override
	public boolean exists(Hash hash) {
		return hash != null && containsKey(hash);
	}

	@Override
	public boolean delete(Hash hash) {
		requireHash(hash);
		return remove(hash) != null;
	}

	@Override
	public long getSize(Hash hash) {
		requireHash(hash);
		ABlob blob = get(hash);
		if (blob == null) throw new IllegalStateException(NOT_STORED + hash.toHexString());
		return blob.count();
	}

	@Override
	public void initialise() {
		// A component is ready when built
	}

	@Override
	public void close() {
		// The store belongs to the venue's record, which outlives this view
	}

	@Override
	public boolean isInitialised() {
		return true;
	}

	private static void requireHash(Hash hash) {
		if (hash == null) throw new IllegalArgumentException(NO_HASH);
	}

	@Override
	public String toString() {
		return "ContentStore[" + size() + " entries]";
	}
}
