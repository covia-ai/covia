package covia.venue.storage;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.ABlobLike;
import convex.core.data.Hash;
import convex.core.data.Index;
import covia.lattice.CASLattice;

/**
 * Content-addressed storage backed by a CASLattice.
 *
 * <p>LatticeStorage provides a simple key-value storage interface where:
 * <ul>
 *   <li>Keys are content hashes (or any blob-like type)</li>
 *   <li>Values are immutable content (any ACell)</li>
 *   <li>Storage is automatically content-addressed (hash of content = key)</li>
 *   <li>State can be merged with other storage instances (CRDT semantics)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is NOT thread-safe. External synchronization is required for
 * concurrent access. Consider using {@link java.util.concurrent.atomic.AtomicReference}
 * for the cursor if concurrent updates are needed.
 *
 * <h2>Example Usage</h2>
 * <pre>
 * // Create storage with empty initial state
 * LatticeStorage&lt;Hash, ABlob&gt; storage = LatticeStorage.create();
 *
 * // Store content (automatically hashed)
 * Blob content = Blob.fromHex("deadbeef");
 * Hash key = storage.store(content);
 *
 * // Retrieve content by hash
 * ABlob retrieved = storage.get(key);
 *
 * // Merge with another storage instance
 * storage.merge(otherStorage.getCursor());
 * </pre>
 *
 * @param <K> Key type extending ABlobLike (typically Hash)
 * @param <V> Value type extending ACell
 */
public class LatticeStorage<K extends ABlobLike<?>, V extends ACell> {

	/**
	 * The underlying CASLattice defining merge semantics
	 */
	private final CASLattice<K, V> lattice;

	/**
	 * Current storage state (cursor into the lattice)
	 */
	private Index<K, V> cursor;

	/**
	 * Private constructor - use factory methods
	 */
	private LatticeStorage(CASLattice<K, V> lattice, Index<K, V> cursor) {
		this.lattice = lattice;
		this.cursor = cursor;
	}

	/**
	 * Create a new empty LatticeStorage.
	 *
	 * @param <K> Key type extending ABlobLike
	 * @param <V> Value type extending ACell
	 * @return New empty LatticeStorage instance
	 */
	public static <K extends ABlobLike<?>, V extends ACell> LatticeStorage<K, V> create() {
		CASLattice<K, V> lattice = CASLattice.create();
		return new LatticeStorage<>(lattice, lattice.zero());
	}

	/**
	 * Create a LatticeStorage with an initial cursor state.
	 *
	 * @param <K> Key type extending ABlobLike
	 * @param <V> Value type extending ACell
	 * @param cursor Initial storage state
	 * @return New LatticeStorage instance with the given state
	 */
	public static <K extends ABlobLike<?>, V extends ACell> LatticeStorage<K, V> create(Index<K, V> cursor) {
		CASLattice<K, V> lattice = CASLattice.create();
		return new LatticeStorage<>(lattice, cursor != null ? cursor : lattice.zero());
	}

	/**
	 * Store a value with an explicit key.
	 *
	 * <p>For true content-addressed storage, the key should be derived from
	 * the value's hash. Use {@link #store(ACell)} for automatic hashing.
	 *
	 * @param key The key to store under
	 * @param value The value to store
	 * @return The key (for convenience)
	 */
	public K put(K key, V value) {
		cursor = cursor.assoc(key, value);
		return key;
	}

	/**
	 * Store a value using its content hash as the key.
	 *
	 * <p>This is the canonical content-addressed storage operation.
	 * The hash of the value becomes its key, ensuring that identical
	 * content always maps to the same key.
	 *
	 * @param value The value to store
	 * @return The content hash (key) of the stored value
	 */
	@SuppressWarnings("unchecked")
	public Hash store(V value) {
		Hash hash = value.getHash();
		cursor = (Index<K, V>) cursor.assoc(hash, value);
		return hash;
	}

	/**
	 * Store blob content using its content hash as the key.
	 *
	 * <p>Convenience method for storing blob content. The blob's
	 * content hash becomes its key.
	 *
	 * @param blob The blob to store
	 * @return The content hash of the stored blob
	 */
	@SuppressWarnings("unchecked")
	public Hash storeBlob(ABlob blob) {
		Hash hash = blob.getContentHash();
		cursor = (Index<K, V>) cursor.assoc(hash, blob);
		return hash;
	}

	/**
	 * Retrieve a value by key.
	 *
	 * @param key The key to look up
	 * @return The value, or null if not found
	 */
	public V get(K key) {
		return cursor.get(key);
	}

	/**
	 * Check if a key exists in storage.
	 *
	 * @param key The key to check
	 * @return true if the key exists, false otherwise
	 */
	public boolean containsKey(K key) {
		return cursor.containsKey(key);
	}

	/**
	 * Get the number of entries in storage.
	 *
	 * @return Number of stored entries
	 */
	public long count() {
		return cursor.count();
	}

	/**
	 * Check if storage is empty.
	 *
	 * @return true if no entries are stored
	 */
	public boolean isEmpty() {
		return cursor.isEmpty();
	}

	/**
	 * Get the current storage state (cursor).
	 *
	 * <p>The cursor is an immutable snapshot of the current state.
	 * It can be persisted, transmitted, or merged with other cursors.
	 *
	 * @return Current storage state as an Index
	 */
	public Index<K, V> getCursor() {
		return cursor;
	}

	/**
	 * Set the storage state directly.
	 *
	 * <p>This replaces the current state entirely. For CRDT-safe updates,
	 * use {@link #merge(Index)} instead.
	 *
	 * @param newCursor The new storage state
	 */
	public void setCursor(Index<K, V> newCursor) {
		this.cursor = newCursor != null ? newCursor : lattice.zero();
	}

	/**
	 * Merge another storage state into this one.
	 *
	 * <p>This is the core CRDT operation. The merge combines entries from
	 * both states using union semantics - all entries from both are included.
	 * Since keys are content hashes, the same key always maps to the same
	 * content, so there are no conflicts.
	 *
	 * @param other The other storage state to merge
	 * @return This storage instance (for chaining)
	 */
	public LatticeStorage<K, V> merge(Index<K, V> other) {
		cursor = lattice.merge(cursor, other);
		return this;
	}

	/**
	 * Merge another LatticeStorage into this one.
	 *
	 * @param other The other storage to merge
	 * @return This storage instance (for chaining)
	 */
	public LatticeStorage<K, V> merge(LatticeStorage<K, V> other) {
		return merge(other.getCursor());
	}

	/**
	 * Create a snapshot of the current state.
	 *
	 * <p>Returns a new LatticeStorage instance with the same state.
	 * Changes to the snapshot do not affect this instance.
	 *
	 * @return New LatticeStorage with same state
	 */
	public LatticeStorage<K, V> snapshot() {
		return new LatticeStorage<>(lattice, cursor);
	}

	/**
	 * Clear all entries from storage.
	 *
	 * <p>Note: This resets to the zero state. In a distributed system,
	 * this local clear will be overwritten on the next merge with any
	 * non-empty state (entries cannot be deleted in a grow-only CRDT).
	 */
	public void clear() {
		cursor = lattice.zero();
	}

	/**
	 * Get the underlying lattice.
	 *
	 * @return The CASLattice defining merge semantics
	 */
	public CASLattice<K, V> getLattice() {
		return lattice;
	}

	@Override
	public String toString() {
		return "LatticeStorage[" + cursor.count() + " entries]";
	}
}
