package covia.adapter;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.lattice.cursor.ALatticeCursor;
import covia.venue.RequestContext;

/**
 * Resolves the {@code n/} virtual namespace to the running agent's private
 * workspace within its agent record.
 *
 * <p>{@code n/notes/foo} resolves to the user cursor with rewritten keys
 * {@code ["g", agentId, "n", "notes", "foo"]}. This preserves compatibility
 * with the existing cursor-based write/read logic in CoviaAdapter.</p>
 */
class AgentNamespaceResolver implements NamespaceResolver {

	@Override
	public ResolvedNamespace resolve(RequestContext ctx, CoviaAdapter adapter, ACell[] keys) {
		AString agentId = ctx.getAgentId();
		if (agentId == null) {
			throw new RuntimeException("Cannot use 'n/' prefix outside agent scope");
		}

		// Return user cursor with rewritten keys: n/foo/bar → g/{agentId}/n/foo/bar
		ALatticeCursor<ACell> userCursor = adapter.ensureUserCursor(ctx);
		ACell[] rewritten = new ACell[keys.length + 2];
		rewritten[0] = Strings.create("g");
		rewritten[1] = agentId;
		System.arraycopy(keys, 0, rewritten, 2, keys.length);
		return new ResolvedNamespace(userCursor, rewritten);
	}

	@Override
	public boolean isWritable() {
		return true;
	}
}
