package covia.lattice;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.generic.OwnerLattice;

/**
 * Top-level lattice for the Covia Grid.
 *
 * <p>GridLattice manages the global grid state with the following structure:
 * <pre>
 * :grid
 *   :venues    ->  OwnerLattice&lt;VenueLattice&gt;  (per-owner signed venue state)
 *   :meta      ->  Index&lt;ABlob, AString&gt;        (shared content-addressable metadata as JSON)
 * </pre>
 *
 * <h2>Design Rationale</h2>
 * <ul>
 *   <li><b>:venues</b> - Per-venue state keyed by AccountKey (owner public key).
 *       Uses OwnerLattice for per-owner signed state — each venue's data is wrapped
 *       in SignedData, preventing forgery during replication. The DID is stored
 *       as a field inside the VenueLattice state.</li>
 *   <li><b>:meta</b> - Shared metadata at grid level. Since metadata is content-addressable
 *       (keyed by SHA256 hash), it is immutable and can be safely shared across venues.
 *       This enables deduplication and efficient cross-venue references.</li>
 * </ul>
 *
 * <h2>Merge Semantics</h2>
 * <ul>
 *   <li><b>:venues</b> - OwnerLattice merge with signature verification per owner</li>
 *   <li><b>:meta</b> - Union merge (content-addressed, same hash = same content)</li>
 * </ul>
 */
public class GridLattice extends ALattice<AMap<Keyword, ACell>> {

	/**
	 * Keyword for the grid root in the lattice path
	 */
	public static final Keyword GRID = Keyword.intern("grid");

	/**
	 * Keyword for venues map within grid state
	 */
	public static final Keyword VENUES = Keyword.intern("venues");

	/**
	 * Keyword for shared metadata at grid level
	 */
	public static final Keyword META = Keyword.intern("meta");

	/**
	 * Singleton instance
	 */
	public static final GridLattice INSTANCE = new GridLattice();

	/**
	 * Child lattice for venues (OwnerLattice keyed by AccountKey, wrapping VenueLattice)
	 */
	private final OwnerLattice<AMap<Keyword, ACell>> venuesLattice;

	/**
	 * Child lattice for shared metadata (content-addressed storage).
	 * Uses ABlob keys (not Hash) because Hash comes back as Blob after Etch round-trip.
	 */
	private final CASLattice<ABlob, AString> metaLattice;

	private GridLattice() {
		// Venues use OwnerLattice for per-owner signed state
		this.venuesLattice = OwnerLattice.create(VenueLattice.INSTANCE);

		// Meta uses CASLattice for content-addressed storage (same hash = same content)
		this.metaLattice = CASLattice.create();
	}

	/**
	 * Get the singleton GridLattice instance
	 * @return GridLattice instance
	 */
	public static GridLattice create() {
		return INSTANCE;
	}

	@Override
	public AMap<Keyword, ACell> merge(AMap<Keyword, ACell> ownValue, AMap<Keyword, ACell> otherValue) {
		return merge(LatticeContext.EMPTY, ownValue, otherValue);
	}

	@Override
	public AMap<Keyword, ACell> merge(LatticeContext context, AMap<Keyword, ACell> ownValue, AMap<Keyword, ACell> otherValue) {
		if (otherValue == null) return ownValue;
		if (ownValue == null) return otherValue;
		if (Utils.equals(ownValue, otherValue)) return ownValue;

		AMap<Keyword, ACell> result = ownValue;
		result = mergeVenuesField(context, result, otherValue);
		result = mergeMetaField(result, otherValue);
		return result;
	}

	@SuppressWarnings("unchecked")
	private AMap<Keyword, ACell> mergeVenuesField(
			LatticeContext context,
			AMap<Keyword, ACell> result,
			AMap<Keyword, ACell> other) {

		AHashMap<ACell, SignedData<AMap<Keyword, ACell>>> ownField =
			(AHashMap<ACell, SignedData<AMap<Keyword, ACell>>>) result.get(VENUES);
		AHashMap<ACell, SignedData<AMap<Keyword, ACell>>> otherField =
			(AHashMap<ACell, SignedData<AMap<Keyword, ACell>>>) other.get(VENUES);

		if (otherField == null) return result;

		AHashMap<ACell, SignedData<AMap<Keyword, ACell>>> merged =
			venuesLattice.merge(context, ownField, otherField);

		if (!Utils.equals(merged, ownField)) {
			result = result.assoc(VENUES, merged);
		}

		return result;
	}

	@SuppressWarnings("unchecked")
	private AMap<Keyword, ACell> mergeMetaField(
			AMap<Keyword, ACell> result,
			AMap<Keyword, ACell> other) {

		Index<ABlob, AString> ownField = (Index<ABlob, AString>) result.get(META);
		Index<ABlob, AString> otherField = (Index<ABlob, AString>) other.get(META);

		if (otherField == null) return result;

		Index<ABlob, AString> merged = metaLattice.merge(ownField, otherField);

		if (!Utils.equals(merged, ownField)) {
			result = result.assoc(META, merged);
		}

		return result;
	}

	@Override
	public AMap<Keyword, ACell> zero() {
		return Maps.of(
			VENUES, Maps.empty(),
			META, Index.none()
		);
	}

	@Override
	public boolean checkForeign(AMap<Keyword, ACell> value) {
		if (value == null) return false;
		if (!(value instanceof AMap)) return false;
		return true;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		if (VENUES.equals(childKey)) {
			return (ALattice<T>) venuesLattice;
		}
		if (META.equals(childKey)) {
			return (ALattice<T>) metaLattice;
		}
		return null;
	}

}
