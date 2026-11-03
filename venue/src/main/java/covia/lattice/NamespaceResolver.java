package covia.lattice;

import convex.core.data.ACell;
import covia.adapter.CoviaAdapter;
import covia.venue.RequestContext;

/**
 * Resolves a virtual namespace prefix to the {@link Position} it stands for.
 *
 * <p>Virtual namespaces (e.g. {@code n/} for agent workspace, {@code t/} for
 * goal-scoped temp) are not backed by a dedicated top-level lattice namespace.
 * Instead, a NamespaceResolver finds the component that holds the data, from
 * the {@link RequestContext}, and the keys the path names within it.</p>
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
	Position resolve(RequestContext ctx, CoviaAdapter adapter, ACell[] keys);

	/** Resolve for a particular CRUD ability; existing resolvers retain their policy. */
	default Position resolve(RequestContext ctx, CoviaAdapter adapter, ACell[] keys, convex.core.data.AString ability) {
		return resolve(ctx, adapter, keys);
	}

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
}
