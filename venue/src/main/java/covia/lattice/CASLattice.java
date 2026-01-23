package covia.lattice;

import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.util.Utils;
import convex.lattice.ALattice;

/**
 * Content-Addressed Storage (CAS) Lattice for Index types.
 *
 * <p>A generic lattice for content-addressed data where keys are cryptographic hashes
 * (or any blob-like type). Since the same key always refers to the same immutable content,
 * merge is simply a union of entries from both indexes.
 *
 * <h2>Type Parameters</h2>
 * <ul>
 *   <li><b>K</b> - Key type, typically {@code Hash} or {@code Blob} for content addressing</li>
 *   <li><b>V</b> - Value type, the content stored at each hash</li>
 * </ul>
 *
 * <h2>Merge Semantics</h2>
 * <p>Union merge: all entries from both indexes are included. When the same key exists
 * in both, the first value is kept (since content-addressed keys guarantee same key = same content).
 *
 * <h2>CRDT Properties</h2>
 * <ul>
 *   <li>Commutative: merge(a,b) == merge(b,a)</li>
 *   <li>Associative: merge(merge(a,b),c) == merge(a,merge(b,c))</li>
 *   <li>Idempotent: merge(a,a) == a</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>
 * // For SHA256-hashed JSON metadata
 * CASLattice&lt;Hash, AString&gt; metaLattice = CASLattice.create();
 *
 * // For blob-keyed binary content
 * CASLattice&lt;Blob, ABlob&gt; blobLattice = CASLattice.create();
 * </pre>
 *
 * @param <K> Key type extending ABlobLike (Hash, Blob, etc.)
 * @param <V> Value type extending ACell
 */
public class CASLattice<K extends ABlobLike<?>, V extends ACell> extends ALattice<Index<K, V>> {

	/**
	 * Singleton instance for common use cases.
	 * Safe to share since CASLattice is stateless.
	 */
	@SuppressWarnings("rawtypes")
	private static final CASLattice INSTANCE = new CASLattice();

	/**
	 * Private constructor - use {@link #create()} to obtain an instance.
	 */
	private CASLattice() {
	}

	/**
	 * Get a CASLattice instance.
	 *
	 * <p>Returns a shared singleton since CASLattice is stateless and
	 * parameterized only by type.
	 *
	 * @param <K> Key type extending ABlobLike
	 * @param <V> Value type extending ACell
	 * @return CASLattice instance
	 */
	@SuppressWarnings("unchecked")
	public static <K extends ABlobLike<?>, V extends ACell> CASLattice<K, V> create() {
		return INSTANCE;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Index<K, V> merge(Index<K, V> ownValue, Index<K, V> otherValue) {
		if (otherValue == null) return ownValue;
		if (ownValue == null) return otherValue;
		if (Utils.equals(ownValue, otherValue)) return ownValue;

		// Union: include all entries from both indexes
		// For content-addressed data, same key = same content
		// AMap.merge() adds all entries from otherValue (union semantics)
		return (Index<K, V>) ownValue.merge(otherValue);
	}

	@Override
	public Index<K, V> zero() {
		return Index.none();
	}

	@Override
	public boolean checkForeign(Index<K, V> value) {
		return value instanceof Index;
	}

	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		// Leaf lattice - no child navigation
		return null;
	}
}
