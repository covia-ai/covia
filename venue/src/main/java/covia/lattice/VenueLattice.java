package covia.lattice;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.util.MergeFunction;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * Lattice type for Venue state.
 *
 * <p>VenueLattice manages the state of a single venue within the Covia grid.
 * The state is a keyword-keyed map with the following structure:
 * <pre>
 * {
 *   :assets   ->  Index&lt;Hash, AssetRecord&gt;  (references to metadata in grid :meta)
 *   :jobs     ->  Index&lt;AString, JobRecord&gt;
 *   :storage  ->  Index&lt;Hash, ABlob&gt;         (content-addressed blob storage)
 * }
 * </pre>
 *
 * <p>Note: Shared metadata (asset definitions, etc.) is stored at the grid level
 * under [:grid :meta] since it is content-addressable and can be safely shared
 * across venues. Assets in this venue reference metadata by hash.
 *
 * <h2>Merge Semantics</h2>
 * <ul>
 *   <li><b>:assets</b> - Union merge (assets are immutable, identified by content hash)</li>
 *   <li><b>:jobs</b> - Per-job merge using timestamp (newer status wins)</li>
 *   <li><b>:storage</b> - Union merge via CASLattice (content-addressed, same hash = same content)</li>
 * </ul>
 *
 * <h2>CRDT Properties</h2>
 * The merge function satisfies CRDT requirements:
 * <ul>
 *   <li>Commutative: merge(a,b) == merge(b,a)</li>
 *   <li>Associative: merge(merge(a,b),c) == merge(a,merge(b,c))</li>
 *   <li>Idempotent: merge(a,a) == a</li>
 * </ul>
 *
 * @see GridLattice
 */
public class VenueLattice extends ALattice<AMap<Keyword, ACell>> {

	/**
	 * Keyword for assets index within venue state
	 */
	public static final Keyword ASSETS = Keyword.intern("assets");

	/**
	 * Keyword for jobs index within venue state
	 */
	public static final Keyword JOBS = Keyword.intern("jobs");

	/**
	 * Keyword for content-addressed storage within venue state
	 */
	public static final Keyword STORAGE = Keyword.intern("storage");

	/**
	 * Keyword for timestamp field (used in merge conflict resolution)
	 */
	public static final Keyword UPDATED = Keyword.intern("updated");

	/**
	 * Singleton instance
	 */
	public static final VenueLattice INSTANCE = new VenueLattice();

	/**
	 * Child lattice for assets (union merge - assets are content-addressed and immutable)
	 */
	private final ALattice<AMap<ACell, ACell>> assetsLattice;

	/**
	 * Child lattice for jobs (timestamp-based merge - newer status wins)
	 */
	private final ALattice<AMap<ACell, ACell>> jobsLattice;

	/**
	 * Child lattice for content-addressed storage (CASLattice for blob storage)
	 */
	private final CASLattice<Hash, ABlob> storageLattice;

	private VenueLattice() {
		// Assets use union merge - content-addressed, so same ID means same content
		this.assetsLattice = new UnionMapLattice<>();

		// Jobs use timestamp-based merge per job entry
		this.jobsLattice = new TimestampMapLattice<>(UPDATED);

		// Storage uses CASLattice - content-addressed blob storage
		this.storageLattice = CASLattice.create();
	}

	/**
	 * Get the singleton VenueLattice instance
	 * @return VenueLattice instance
	 */
	public static VenueLattice create() {
		return INSTANCE;
	}

	@Override
	public AMap<Keyword, ACell> merge(AMap<Keyword, ACell> ownValue, AMap<Keyword, ACell> otherValue) {
		// Handle null cases
		if (otherValue == null) return ownValue;
		if (ownValue == null) return otherValue;

		// Fast path for identical values
		if (Utils.equals(ownValue, otherValue)) return ownValue;

		// Merge each field using appropriate child lattice
		AMap<Keyword, ACell> result = ownValue;

		// Merge assets
		result = mergeField(result, otherValue, ASSETS, assetsLattice);

		// Merge jobs
		result = mergeField(result, otherValue, JOBS, jobsLattice);

		// Merge storage
		result = mergeStorageField(result, otherValue);

		return result;
	}

	@Override
	public AMap<Keyword, ACell> merge(LatticeContext context, AMap<Keyword, ACell> ownValue, AMap<Keyword, ACell> otherValue) {
		// For now, delegate to simple merge
		// Future: use context for signing merged values
		return merge(ownValue, otherValue);
	}

	@SuppressWarnings("unchecked")
	private AMap<Keyword, ACell> mergeField(
			AMap<Keyword, ACell> result,
			AMap<Keyword, ACell> other,
			Keyword key,
			ALattice<AMap<ACell, ACell>> lattice) {

		AMap<ACell, ACell> ownField = (AMap<ACell, ACell>) result.get(key);
		AMap<ACell, ACell> otherField = (AMap<ACell, ACell>) other.get(key);

		if (otherField == null) return result;

		AMap<ACell, ACell> merged = lattice.merge(ownField, otherField);

		if (!Utils.equals(merged, ownField)) {
			result = result.assoc(key, merged);
		}

		return result;
	}

	@SuppressWarnings("unchecked")
	private AMap<Keyword, ACell> mergeStorageField(
			AMap<Keyword, ACell> result,
			AMap<Keyword, ACell> other) {

		Index<Hash, ABlob> ownField = (Index<Hash, ABlob>) result.get(STORAGE);
		Index<Hash, ABlob> otherField = (Index<Hash, ABlob>) other.get(STORAGE);

		if (otherField == null) return result;

		Index<Hash, ABlob> merged = storageLattice.merge(ownField, otherField);

		if (!Utils.equals(merged, ownField)) {
			result = result.assoc(STORAGE, merged);
		}

		return result;
	}

	@Override
	public AMap<Keyword, ACell> zero() {
		return Maps.of(
			ASSETS, Maps.empty(),
			JOBS, Maps.empty(),
			STORAGE, Index.none()
		);
	}

	@Override
	public boolean checkForeign(AMap<Keyword, ACell> value) {
		if (value == null) return false;
		if (!(value instanceof AMap)) return false;
		// Could add more validation here (check required keys, etc.)
		return true;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		if (ASSETS.equals(childKey)) {
			return (ALattice<T>) assetsLattice;
		}
		if (JOBS.equals(childKey)) {
			return (ALattice<T>) jobsLattice;
		}
		if (STORAGE.equals(childKey)) {
			return (ALattice<T>) storageLattice;
		}
		return null;
	}

	/**
	 * Union merge lattice for maps - combines all entries from both maps.
	 * Used for assets where keys are content hashes (same key = same value).
	 */
	private static class UnionMapLattice<K extends ACell, V extends ACell> extends ALattice<AMap<K, V>> {

		@Override
		public AMap<K, V> merge(AMap<K, V> ownValue, AMap<K, V> otherValue) {
			if (otherValue == null) return ownValue;
			if (ownValue == null) return otherValue;
			if (Utils.equals(ownValue, otherValue)) return ownValue;

			// Union: include all entries from both maps
			// For content-addressed assets, same key means same value
			// AMap.merge() adds all entries from otherValue, overwriting duplicates
			return ownValue.merge(otherValue);
		}

		@Override
		public AMap<K, V> zero() {
			return Maps.empty();
		}

		@Override
		public boolean checkForeign(AMap<K, V> value) {
			return value instanceof AMap;
		}

		@Override
		public <T extends ACell> ALattice<T> path(ACell childKey) {
			return null; // Leaf lattice
		}
	}

	/**
	 * Timestamp-based merge lattice for maps - entries with newer timestamps win.
	 * Used for jobs and metadata where we want "last writer wins" semantics.
	 *
	 * @param <K> Key type
	 * @param <V> Value type (expected to be a map containing the timestamp field)
	 */
	private static class TimestampMapLattice<K extends ACell, V extends ACell> extends ALattice<AMap<K, V>> {

		private final Keyword timestampKey;
		private final MergeFunction<V> mergeFunction;

		public TimestampMapLattice(Keyword timestampKey) {
			this.timestampKey = timestampKey;
			this.mergeFunction = this::mergeByTimestamp;
		}

		@SuppressWarnings("unchecked")
		private V mergeByTimestamp(V a, V b) {
			if (a == null) return b;
			if (b == null) return a;
			if (Utils.equals(a, b)) return a;

			// Extract timestamps
			Long tsA = extractTimestamp(a);
			Long tsB = extractTimestamp(b);

			// Null timestamps treated as oldest
			if (tsA == null && tsB == null) return a;
			if (tsA == null) return b;
			if (tsB == null) return a;

			// Newer timestamp wins
			return (tsA >= tsB) ? a : b;
		}

		@SuppressWarnings("unchecked")
		private Long extractTimestamp(V value) {
			if (!(value instanceof AMap)) return null;
			AMap<ACell, ACell> map = (AMap<ACell, ACell>) value;
			ACell ts = map.get(timestampKey);
			if (ts == null) return null;
			try {
				return Long.parseLong(ts.toString());
			} catch (NumberFormatException e) {
				return null;
			}
		}

		@Override
		public AMap<K, V> merge(AMap<K, V> ownValue, AMap<K, V> otherValue) {
			if (otherValue == null) return ownValue;
			if (ownValue == null) return otherValue;
			if (Utils.equals(ownValue, otherValue)) return ownValue;

			return ownValue.mergeDifferences(otherValue, mergeFunction);
		}

		@Override
		public AMap<K, V> zero() {
			return Maps.empty();
		}

		@Override
		public boolean checkForeign(AMap<K, V> value) {
			return value instanceof AMap;
		}

		@Override
		public <T extends ACell> ALattice<T> path(ACell childKey) {
			return null; // Leaf lattice
		}
	}
}
