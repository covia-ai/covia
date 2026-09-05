package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.lattice.cursor.ALatticeCursor;
import covia.adapter.CoviaAdapter;
import covia.venue.RequestContext;

/**
 * Resolves a virtual namespace prefix to a lattice cursor and remaining path keys.
 *
 * <p>Virtual namespaces (e.g. {@code n/} for agent workspace, {@code t/} for
 * goal-scoped temp) are not backed by a dedicated top-level lattice namespace.
 * Instead, a NamespaceResolver navigates directly to the correct lattice
 * location based on the {@link RequestContext} and returns the remaining
 * physical path from that cursor.</p>
 *
 * <p>Resolvers are registered on {@link CoviaAdapter} by prefix. Path resolution
 * checks registered resolvers first (O(1) lookup by prefix), then falls through
 * to the standard user lattice cursor for physical namespaces.</p>
 */
public interface NamespaceResolver {

	/**
	 * Resolves the virtual namespace to a target cursor and remaining path keys.
	 *
	 * @param ctx    request context (provides callerDID, agentId, jobId)
	 * @param keys   full parsed path keys (first element is the prefix, e.g. "n" or "t")
	 * @return resolved target, or null if the context lacks the required scope
	 * @throws covia.exception.WrongScopeException if the prefix is used outside its
	 *         required agent/session scope — a context mismatch that a read treats
	 *         as absence and a write surfaces as an error (distinct from an auth
	 *         failure or a malformed path)
	 */
	ResolvedNamespace resolve(RequestContext ctx, CoviaAdapter adapter, ACell[] keys);

	/** Whether this namespace accepts writes (static, applies to all callers). */
	boolean isWritable();

	/**
	 * Whether the given caller is permitted to write to this namespace. The
	 * default implementation defers to {@link #isWritable()} (the existing
	 * static yes/no semantics). Resolvers that need per-caller authorisation
	 * — e.g. {@code VenueGlobalsResolver}, where reads are universally
	 * allowed but writes require the venue identity — override this to
	 * inspect the {@link RequestContext}.
	 */
	default boolean canWrite(RequestContext ctx) {
		return isWritable();
	}

	/**
	 * Result of namespace resolution: a cursor positioned at the record that
	 * holds the namespace, and the keys to navigate from there. The first
	 * remaining key names the namespace container within that record (e.g.
	 * {@code n} for {@code n/}, {@code temp} for {@code t/}, {@code c} for
	 * {@code c/}), so every resolved path has the same shape as a physical
	 * {@code w/...} path and the adapter needs one navigation rule.
	 *
	 * @param requireExistingRecord the record at {@code cursor} must already exist — a
	 *        write never materialises it (a session's scratch must not mint a
	 *        phantom session); reads of a missing record are simply absent
	 * @param recordTimestampKey optional timestamp field to ratchet when the cursor
	 *        is positioned exactly at a stamped record boundary
	 */
	record ResolvedNamespace(ALatticeCursor<ACell> cursor, ACell[] remainingKeys,
			boolean requireExistingRecord, AString recordTimestampKey) {
		ResolvedNamespace(ALatticeCursor<ACell> cursor, ACell[] remainingKeys) {
			this(cursor, remainingKeys, false, null);
		}

		ResolvedNamespace(ALatticeCursor<ACell> cursor, ACell[] remainingKeys,
				boolean requireExistingRecord) {
			this(cursor, remainingKeys, requireExistingRecord, null);
		}
	}
}
