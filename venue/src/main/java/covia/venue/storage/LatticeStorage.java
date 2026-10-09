package covia.venue.storage;

import java.io.IOException;
import java.io.InputStream;

import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.Cursors;
import covia.grid.AContent;
import covia.grid.impl.BlobContent;
import convex.lattice.generic.CASLattice;

/**
 * Content-addressed storage backed by a lattice cursor.
 *
 * <p>LatticeStorage extends {@link AStorage} to provide content storage that:
 * <ul>
 *   <li>Is backed by a lattice cursor into the venue state</li>
 *   <li>Uses content hashes as keys (content-addressed)</li>
 *   <li>Uses a {@link CASLattice} cursor when created standalone</li>
 *   <li>Participates in the enclosing venue snapshot when venue-backed</li>
 * </ul>
 *
 * <h2>Integration with Venue Lattice</h2>
 * <p>This storage is designed to use a cursor into the venue's :storage path.
 * Changes are reflected in that venue's lattice state. Merge policy belongs to
 * the cursor's enclosing lattice: venue-backed storage is part of the venue's
 * single-writer whole-value LWW snapshot, while detached storage uses the
 * standalone {@link CASLattice} exposed by {@link #getLattice()}.
 *
 * <h2>Example Usage</h2>
 * <pre>
 * // Create storage backed by a lattice cursor
 * ACursor&lt;Index&lt;ABlob, ABlob&gt;&gt; cursor = venueCursor.path(VenueLattice.STORAGE);
 * LatticeStorage storage = new LatticeStorage(cursor);
 *
 * // Store content
 * Hash hash = storage.store(contentHash, inputStream);
 *
 * // Retrieve content
 * AContent content = storage.getContent(hash);
 * </pre>
 */
public class LatticeStorage extends AStorage {

	/** CAS merge definition used by detached storage and retained by the public API. */
	private final CASLattice<ABlob, ABlob> lattice;
	/** Cursor holding the content-addressed index. Never null. */
	private final ACursor<Index<ABlob, ABlob>> cursor;

	private boolean initialised = false;

	/**
	 * Create a LatticeStorage backed by a lattice cursor.
	 *
	 * <p>Changes to storage will be reflected in the cursor's lattice state.
	 *
	 * @param cursor Cursor into the venue's :storage path, or null for a detached store
	 */
	public LatticeStorage(ACursor<Index<ABlob, ABlob>> cursor) {
		this.lattice = CASLattice.create();
		this.cursor = (cursor != null) ? cursor : Cursors.createLattice(lattice);
	}

	/**
	 * Create a standalone LatticeStorage over its own detached
	 * {@link CASLattice} cursor — useful for tests and for embedded use where
	 * the content index is not part of a venue's replicated state. The storage
	 * logic is identical: there is one write path, not a second local mode.
	 */
	public LatticeStorage() {
		this(null);
	}

	@Override
	public void initialise() throws IOException {
		initialised = true;
	}

	@Override
	public boolean isInitialised() {
		return initialised;
	}

	@Override
	public void store(Hash hash, AContent content) throws IOException {
		if (!initialised) {
			throw new IllegalStateException("Storage not initialized");
		}
		if (hash == null) {
			throw new IllegalArgumentException("Hash cannot be null");
		}
		if (content == null) {
			throw new IllegalArgumentException("Content cannot be null");
		}

		ABlob blob = content.getBlob();
		storeBlob(hash, blob);
	}

	@Override
	public void store(Hash hash, InputStream inputStream) throws IOException {
		if (!initialised) {
			throw new IllegalStateException("Storage not initialized");
		}
		if (hash == null) {
			throw new IllegalArgumentException("Hash cannot be null");
		}
		if (inputStream == null) {
			throw new IllegalArgumentException("InputStream cannot be null");
		}

		byte[] data = inputStream.readAllBytes();
		ABlob blob = Blob.wrap(data);
		storeBlob(hash, blob);
	}

	/**
	 * Store a blob with the given hash.
	 */
	private void storeBlob(Hash hash, ABlob blob) {
		cursor.updateAndGet(current -> {
			Index<ABlob, ABlob> idx = (current != null) ? current : Index.none();
			return idx.assoc(hash, blob);
		});
	}

	@Override
	public AContent getContent(Hash hash) throws IOException {
		if (!initialised) {
			throw new IllegalStateException("Storage not initialized");
		}
		if (hash == null) {
			throw new IllegalArgumentException("Hash cannot be null");
		}

		ABlob blob = getState().get(hash);
		if (blob == null) {
			return null;
		}
		return BlobContent.of(blob);
	}

	@Override
	public boolean exists(Hash hash) {
		if (!initialised) {
			return false;
		}
		return hash != null && getState().containsKey(hash);
	}

	@Override
	public boolean delete(Hash hash) throws IOException {
		if (!initialised) {
			throw new IllegalStateException("Storage not initialized");
		}
		if (hash == null) {
			throw new IllegalArgumentException("Hash cannot be null");
		}

		Index<ABlob, ABlob> old = cursor.getAndUpdate(current ->
			(current != null) ? current.dissoc(hash) : current);
		return old != null && old.containsKey(hash);
	}

	@Override
	public long getSize(Hash hash) throws IllegalStateException {
		if (!initialised) {
			throw new IllegalStateException("Storage not initialized");
		}

		ABlob blob = getState().get(hash);
		if (blob == null) {
			throw new IllegalStateException("Content does not exist for hash: " + hash);
		}
		return blob.count();
	}

	@Override
	public void close() {
		// Nothing to close for lattice storage
		initialised = false;
	}

	// ========== Lattice-specific methods ==========

	/**
	 * Get the current storage state.
	 *
	 * @return Current state as an Index
	 */
	public Index<ABlob, ABlob> getState() {
		Index<ABlob, ABlob> state = cursor.get();
		return (state != null) ? state : Index.none();
	}

	/**
	 * Get the number of stored items.
	 *
	 * @return Number of items in storage
	 */
	public long count() {
		return getState().count();
	}

	/**
	 * Check if storage is empty.
	 *
	 * @return true if no items are stored
	 */
	public boolean isEmpty() {
		return getState().isEmpty();
	}

	/** @return the CAS lattice defining storage merge semantics */
	public CASLattice<ABlob, ABlob> getLattice() {
		return lattice;
	}

	/** @return the non-null cursor holding this storage's state */
	public ACursor<Index<ABlob, ABlob>> getCursor() {
		return cursor;
	}

	@Override
	public String toString() {
		return "LatticeStorage[" + count() + " entries]";
	}
}
