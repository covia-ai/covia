package covia.lattice;

import convex.core.data.AString;
import covia.adapter.CoviaAdapter;
import covia.exception.WrongScopeException;
import covia.venue.RequestContext;

/**
 * Resolves the {@code n/} virtual namespace to the running agent's own
 * workspace ({@link covia.venue.AgentState#workspace()}).
 *
 * <p>{@code n/notes/foo} resolves to the workspace at {@code g/<agentId>/n}
 * with the segments {@code notes/foo}. The agent record stamps a change there,
 * and a change is refused while the record does not exist.</p>
 */
public class AgentNamespaceResolver implements NamespaceResolver {

	@Override
	public Position resolve(RequestContext ctx, CoviaAdapter adapter, CoviaPath path) {
		AString agentId = ctx.getAgentId();
		if (agentId == null) {
			throw new WrongScopeException("Cannot use 'n/' prefix outside agent scope");
		}
		return adapter.ensureUser(ctx).agentAt(agentId).workspace().resolve(path.rest());
	}

	@Override
	public boolean isWritable() {
		return true;
	}
}
