package covia.lattice;

import convex.core.data.ABlobLike;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
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
 *   :assets   ->  Index&lt;ABlob, AssetRecord&gt;  (references to metadata in grid :meta)
 *   :jobs     ->  Index&lt;AString, JobRecord&gt;
 *   :users    ->  Index&lt;AString, UserRecord&gt;  (user database, keyed by user ID)
 *   :storage  ->  Index&lt;ABlob, ABlob&gt;        (content-addressed blob storage)
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
 *   <li><b>:users</b> - Per-user merge using timestamp (newer record wins)</li>
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
	 * Keyword for users index within venue state
	 */
	public static final Keyword USERS = Keyword.intern("users");

	/**
	 * Keyword for content-addressed storage within venue state
	 */
	public static final Keyword STORAGE = Keyword.intern("storage");

	/**
	 * Keyword for authorization state within venue state
	 */
	public static final Keyword AUTH = Keyword.intern("auth");

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
	 * Child lattice for jobs (timestamp-based merge - newer status wins).
	 * Uses Index for natural time-ordering (job IDs are timestamp-prefixed hex).
	 */
	private final TimestampIndexLattice<?, ?> jobsLattice;

	/**
	 * Child lattice for users (timestamp-based merge - newer record wins)
	 */
	private final ALattice<AMap<ACell, ACell>> usersLattice;

	/**
	 * Child lattice for content-addressed storage (CASLattice for blob storage).
	 * Uses ABlob keys (not Hash) because Hash comes back as Blob after Etch round-trip.
	 */
	private final CASLattice<ABlob, ABlob> storageLattice;

	/**
	 * Child lattice for authorization state (timestamp-based merge — venue is authoritative)
	 */
	private final ALattice<AMap<ACell, ACell>> authLattice;

	private VenueLattice() {
		// Assets use union merge - content-addressed, so same ID means same content
		this.assetsLattice = new UnionMapLattice<>();

		// Jobs use Index (timestamp-prefixed IDs give natural time-ordering)
		// Use AString key for timestamp lookup (job records use AString keys from Fields.UPDATED)
		this.jobsLattice = new TimestampIndexLattice<>(Strings.intern("updated"));

		// Users use timestamp-based merge per user entry
		// Use AString key for timestamp lookup (user records use AString keys)
		this.usersLattice = new TimestampMapLattice<>(Strings.intern("updated"));

		// Storage uses CASLattice - content-addressed blob storage
		this.storageLattice = CASLattice.create();

		// Auth uses timestamp-based merge (venue-authoritative)
		this.authLattice = new TimestampMapLattice<>(Strings.intern("updated"));
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

		// Merge jobs (Index-based)
		result = mergeIndexField(result, otherValue, JOBS, jobsLattice);

		// Merge users
		result = mergeField(result, otherValue, USERS, usersLattice);

		// Merge storage
		result = mergeStorageField(result, otherValue);

		// Merge auth
		result = mergeField(result, otherValue, AUTH, authLattice);

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
	private <K extends ABlobLike<?>, V extends ACell> AMap<Keyword, ACell> mergeIndexField(
			AMap<Keyword, ACell> result,
			AMap<Keyword, ACell> other,
			Keyword key,
			TimestampIndexLattice<K, V> lattice) {

		Index<K, V> ownField = (Index<K, V>) result.get(key);
		Index<K, V> otherField = (Index<K, V>) other.get(key);

		if (otherField == null) return result;

		Index<K, V> merged = lattice.merge(ownField, otherField);

		if (!Utils.equals(merged, ownField)) {
			result = result.assoc(key, merged);
		}

		return result;
	}

	@SuppressWarnings("unchecked")
	private AMap<Keyword, ACell> mergeStorageField(
			AMap<Keyword, ACell> result,
			AMap<Keyword, ACell> other) {

		Index<ABlob, ABlob> ownField = (Index<ABlob, ABlob>) result.get(STORAGE);
		Index<ABlob, ABlob> otherField = (Index<ABlob, ABlob>) other.get(STORAGE);

		if (otherField == null) return result;

		Index<ABlob, ABlob> merged = storageLattice.merge(ownField, otherField);

		if (!Utils.equals(merged, ownField)) {
			result = result.assoc(STORAGE, merged);
		}

		return result;
	}

	@Override
	public AMap<Keyword, ACell> zero() {
		return Maps.of(
			ASSETS, Index.none(),
			JOBS, Index.none(),
			USERS, Maps.empty(),
			STORAGE, Index.none(),
			AUTH, Maps.empty()
		);
	}

	@Override
	public boolean checkForeign(AMap<Keyword, ACell> value) {
		if (value == null) return false;
		if (!(value instanceof AMap)) return false;
		// Could add more validation here (check required keys, etc.)
		return true;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		if (ASSETS.equals(childKey)) {
			return (ALattice<T>) assetsLattice;
		}
		if (JOBS.equals(childKey)) {
			return (ALattice) jobsLattice;
		}
		if (USERS.equals(childKey)) {
			return (ALattice<T>) usersLattice;
		}
		if (STORAGE.equals(childKey)) {
			return (ALattice<T>) storageLattice;
		}
		if (AUTH.equals(childKey)) {
			return (ALattice<T>) authLattice;
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
	 * Used for users where we want "last writer wins" semantics.
	 *
	 * @param <K> Key type
	 * @param <V> Value type (expected to be a map containing the timestamp field)
	 */
	private static class TimestampMapLattice<K extends ACell, V extends ACell> extends ALattice<AMap<K, V>> {

		private final ACell timestampKey;
		private final MergeFunction<V> mergeFunction;

		public TimestampMapLattice(ACell timestampKey) {
			this.timestampKey = timestampKey;
			this.mergeFunction = this::mergeByTimestamp;
		}

		private V mergeByTimestamp(V a, V b) {
			return TimestampMerge.merge(a, b, timestampKey);
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

	/**
	 * Timestamp-based merge lattice for Index - entries with newer timestamps win.
	 * Used for jobs where Index provides natural time-ordering via timestamp-prefixed keys.
	 *
	 * @param <K> Key type (must be blob-like for Index)
	 * @param <V> Value type (expected to be a map containing the timestamp field)
	 */
	static class TimestampIndexLattice<K extends ABlobLike<?>, V extends ACell> extends ALattice<Index<K, V>> {

		private final ACell timestampKey;
		private final MergeFunction<V> mergeFunction;

		public TimestampIndexLattice(ACell timestampKey) {
			this.timestampKey = timestampKey;
			this.mergeFunction = this::mergeByTimestamp;
		}

		private V mergeByTimestamp(V a, V b) {
			return TimestampMerge.merge(a, b, timestampKey);
		}

		@Override
		public Index<K, V> merge(Index<K, V> ownValue, Index<K, V> otherValue) {
			if (otherValue == null) return ownValue;
			if (ownValue == null) return otherValue;
			if (Utils.equals(ownValue, otherValue)) return ownValue;

			return ownValue.mergeDifferences(otherValue, mergeFunction);
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
			return null; // Leaf lattice
		}
	}

	/**
	 * Shared timestamp merge logic - newer timestamp wins.
	 */
	private static class TimestampMerge {
		@SuppressWarnings("unchecked")
		static <V extends ACell> V merge(V a, V b, ACell timestampKey) {
			if (a == null) return b;
			if (b == null) return a;
			if (Utils.equals(a, b)) return a;

			Long tsA = extractTimestamp(a, timestampKey);
			Long tsB = extractTimestamp(b, timestampKey);

			if (tsA == null && tsB == null) return a;
			if (tsA == null) return b;
			if (tsB == null) return a;

			return (tsA >= tsB) ? a : b;
		}

		@SuppressWarnings("unchecked")
		private static <V extends ACell> Long extractTimestamp(V value, ACell timestampKey) {
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
	}
}
