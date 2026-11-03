package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.lattice.cursor.AForkableCursor;
import covia.adapter.CoviaAdapter;
import covia.exception.WrongScopeException;
import covia.venue.RequestContext;

/**
 * Resolves the {@code n/} virtual namespace to the running agent's private
 * workspace within its agent record.
 *
 * <p>{@code n/notes/foo} resolves to the workspace at {@code g/<agentId>/n}
 * with the keys {@code notes/foo}. The agent record stamps a change there, and
 * a change is refused while the record does not exist.</p>
 */
public class AgentNamespaceResolver implements NamespaceResolver {

	/** Agent-record field holding the agent's private workspace. */
	private static final AString K_N = Strings.intern("n");

	@Override
	public Position resolve(RequestContext ctx, CoviaAdapter adapter, ACell[] keys) {
		AString agentId = ctx.getAgentId();
		if (agentId == null) {
			throw new WrongScopeException("Cannot use 'n/' prefix outside agent scope");
		}

		AForkableCursor<ACell> agentCursor = adapter.ensureUser(ctx)
			.recordCursor(Namespace.G, agentId);
		return new Position(Workspace.inRecord(agentCursor, K_N), keys, 1);
	}

	@Override
	public boolean isWritable() {
		return true;
	}
}
