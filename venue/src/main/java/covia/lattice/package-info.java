/**
 * Covia Lattice types for grid and venue state management.
 *
 * <p>This package provides lattice-based data structures for managing grid state
 * with CRDT (Conflict-free Replicated Data Type) semantics. These structures enable:
 * <ul>
 *   <li>Persistent, durable state that survives restarts</li>
 *   <li>Immutable audit trails for all operations</li>
 *   <li>Cross-venue state synchronization via lattice merging</li>
 *   <li>Cryptographic integrity through Convex's Merkle DAG</li>
 * </ul>
 *
 * <h2>Lattice Path Structure</h2>
 * <pre>
 * :grid  ->  GridLattice
 *   :venues
 *     &lt;venue-did-string&gt;  ->  VenueLattice value
 *       :assets  ->  Map&lt;Hash, AssetRecord&gt;  (references to :meta)
 *       :jobs    ->  Map&lt;AString, JobRecord&gt;
 *   :meta  ->  Index&lt;Hash, AString&gt;  (shared content-addressable metadata as JSON)
 * </pre>
 *
 * <p>The `:meta` field is at the grid level because metadata is content-addressable
 * (keyed by SHA256 hash) and immutable, making it safe to share across venues.
 * This enables deduplication and efficient cross-venue asset references.
 *
 * @see covia.lattice.GridLattice
 * @see covia.lattice.VenueLattice
 */
package covia.lattice;
