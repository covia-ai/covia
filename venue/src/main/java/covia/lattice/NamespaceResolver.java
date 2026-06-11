package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.lattice.cursor.ALatticeCursor;
import covia.adapter.CoviaAdapter;
import covia.venue.RequestContext;

/**
 * Resolves a virtual namespace prefix to a lattice cursor and remaining path keys.
 *
 * <p>Virtual namespaces (e.g. {@code n/} for agent workspace, {@code t/} for
 * goal-scoped temp) are not backed by a dedicated top-level lattice namespace.
 * Instead, a NamespaceResolver navigates directly to the correct lattice
 * location based on the {@link RequestContext}, without path string rewriting
 * or temporary allocations.</p>
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
	 * @throws RuntimeException if the prefix is used outside its required scope
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
	 * Result of namespace resolution: a cursor positioned at the namespace root
	 * and the remaining path keys to navigate within it. For job-scoped
	 * namespaces (e.g. {@code t/}), includes the jobId for targeted access.
	 */
	record ResolvedNamespace(ALatticeCursor<ACell> cursor, ACell[] remainingKeys, Blob jobId) {
		/** Constructor for non-job-scoped namespaces. */
		ResolvedNamespace(ALatticeCursor<ACell> cursor, ACell[] remainingKeys) {
			this(cursor, remainingKeys, null);
		}
	}
}
