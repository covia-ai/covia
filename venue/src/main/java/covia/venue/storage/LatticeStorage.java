package covia.venue.storage;

import java.io.IOException;
import java.io.InputStream;

import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.lattice.cursor.ACursor;
import covia.grid.AContent;
import covia.grid.impl.BlobContent;
import covia.lattice.CASLattice;

/**
 * Content-addressed storage backed by a CASLattice and lattice cursor.
 *
 * <p>LatticeStorage extends {@link AStorage} to provide content storage that:
 * <ul>
 *   <li>Is backed by a lattice cursor into the venue state</li>
 *   <li>Uses content hashes as keys (content-addressed)</li>
 *   <li>Supports CRDT merge semantics via the underlying CASLattice</li>
 *   <li>Can sync state across distributed venues</li>
 * </ul>
 *
 * <h2>Integration with Venue Lattice</h2>
 * <p>This storage is designed to use a cursor into the venue's :storage path.
 * Changes to storage are reflected in the lattice state and can be merged
 * with other venues.
 *
 * <h2>Example Usage</h2>
 * <pre>
 * // Create storage backed by a lattice cursor
 * ACursor&lt;Index&lt;Hash, ABlob&gt;&gt; cursor = venueCursor.path(VenueLattice.STORAGE);
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

	/**
	 * The underlying CASLattice defining merge semantics
	 */
	private final CASLattice<Hash, ABlob> lattice;

	/**
	 * Cursor into the lattice for storage state.
	 * May be null if using standalone mode without a lattice cursor.
	 */
	private final ACursor<Index<Hash, ABlob>> cursor;

	/**
	 * Local storage state (used when cursor is null or for caching)
	 */
	private Index<Hash, ABlob> localState;

	private boolean initialised = false;

	/**
	 * Create a LatticeStorage backed by a lattice cursor.
	 *
	 * <p>Changes to storage will be reflected in the cursor's lattice state.
	 *
	 * @param cursor Cursor into the venue's :storage path
	 */
	public LatticeStorage(ACursor<Index<Hash, ABlob>> cursor) {
		this.lattice = CASLattice.create();
		this.cursor = cursor;
		this.localState = null; // Will use cursor
	}

	/**
	 * Create a standalone LatticeStorage without a lattice cursor.
	 *
	 * <p>This mode is useful for testing or when lattice integration
	 * is not needed. State is stored locally only.
	 */
	public LatticeStorage() {
		this.lattice = CASLattice.create();
		this.cursor = null;
		this.localState = lattice.zero();
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
		Index<Hash, ABlob> current = getState();
		Index<Hash, ABlob> updated = current.assoc(hash, blob);
		setState(updated);
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

		// Note: In a true CRDT, deletes are not supported (grow-only).
		// This implementation removes locally but may be restored on merge.
		Index<Hash, ABlob> current = getState();
		if (!current.containsKey(hash)) {
			return false;
		}
		Index<Hash, ABlob> updated = current.dissoc(hash);
		setState(updated);
		return true;
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
	public Index<Hash, ABlob> getState() {
		if (cursor != null) {
			Index<Hash, ABlob> cursorState = cursor.get();
			return cursorState != null ? cursorState : lattice.zero();
		}
		return localState;
	}

	/**
	 * Set the storage state.
	 *
	 * @param state New state
	 */
	private void setState(Index<Hash, ABlob> state) {
		if (cursor != null) {
			cursor.set(state);
		} else {
			localState = state;
		}
	}

	/**
	 * Merge another storage state into this one.
	 *
	 * <p>This is the core CRDT operation. The merge combines entries from
	 * both states using union semantics.
	 *
	 * @param other The other storage state to merge
	 */
	public void merge(Index<Hash, ABlob> other) {
		if (other == null) return;
		Index<Hash, ABlob> current = getState();
		Index<Hash, ABlob> merged = lattice.merge(current, other);
		setState(merged);
	}

	/**
	 * Merge another LatticeStorage into this one.
	 *
	 * @param other The other storage to merge
	 */
	public void merge(LatticeStorage other) {
		merge(other.getState());
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

	/**
	 * Get the underlying CASLattice.
	 *
	 * @return The CASLattice defining merge semantics
	 */
	public CASLattice<Hash, ABlob> getLattice() {
		return lattice;
	}

	/**
	 * Get the lattice cursor (may be null if standalone mode).
	 *
	 * @return The cursor, or null
	 */
	public ACursor<Index<Hash, ABlob>> getCursor() {
		return cursor;
	}

	@Override
	public String toString() {
		return "LatticeStorage[" + count() + " entries]";
	}
}
