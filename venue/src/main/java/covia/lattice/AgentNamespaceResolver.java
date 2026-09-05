package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.lattice.cursor.ALatticeCursor;
import covia.adapter.CoviaAdapter;
import covia.exception.WrongScopeException;
import covia.venue.RequestContext;

/**
 * Resolves the {@code n/} virtual namespace to the running agent's private
 * workspace within its agent record.
 *
 * <p>{@code n/notes/foo} resolves to the agent-record cursor at
 * {@code g/<agentId>} with remaining keys {@code ["n", "notes", "foo"]}.
 * Positioning at the containing record gives every virtual namespace the same
 * resolver contract and lets the agent record lattice stamp the deep write.</p>
 */
public class AgentNamespaceResolver implements NamespaceResolver {

	/** Agent-record field holding the agent's private workspace. */
	private static final AString K_N = Strings.intern("n");
	/** Agent-record last-modified field, ratcheted by a workspace write. */
	private static final AString K_TS = Strings.intern("ts");

	@Override
	public ResolvedNamespace resolve(RequestContext ctx, CoviaAdapter adapter, ACell[] keys) {
		AString agentId = ctx.getAgentId();
		if (agentId == null) {
			throw new WrongScopeException("Cannot use 'n/' prefix outside agent scope");
		}

		ALatticeCursor<ACell> agentCursor = adapter.ensureUserCursor(ctx)
			.path(Namespace.G, agentId);
		ACell[] rewritten = keys.clone();
		rewritten[0] = K_N;
		return new ResolvedNamespace(agentCursor, rewritten, true, K_TS);
	}

	@Override
	public boolean isWritable() {
		return true;
	}
}
