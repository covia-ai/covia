package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.lattice.cursor.AForkableCursor;
import covia.adapter.CoviaAdapter;
import covia.exception.WrongScopeException;
import covia.venue.RequestContext;

/**
 * Resolves the {@code c/} virtual namespace to the current session's
 * conversation-scoped slot within the running agent's record.
 *
 * <p>{@code c/draft/notes} resolves to the workspace at
 * {@code g/<agent>/sessions/<sid>/c} with the keys {@code draft/notes}.
 * The session's {@code c} field is a free-form map for state that the agent
 * (and collaborating parties) can accumulate across turns within a single
 * conversation. Framework lifecycle APIs create session records; scoped
 * scratch must never mint a phantom one, so a change is refused while the
 * session record does not exist.</p>
 *
 * <p>Requires both {@code ctx.getAgentId()} and {@code ctx.getSessionId()}
 * to be set. Outside that scope the prefix errors helpfully rather than
 * silently resolving to something misleading.</p>
 */
public class SessionNamespaceResolver implements NamespaceResolver {

	private static final AString K_SESSIONS = Strings.intern("sessions");
	private static final AString K_C = Strings.intern("c");

	@Override
	public Position resolve(RequestContext ctx, CoviaAdapter adapter, ACell[] keys) {
		AString agentId = ctx.getAgentId();
		Blob sessionId = ctx.getSessionId();
		if (agentId == null || sessionId == null) {
			throw new WrongScopeException(
				"Cannot use 'c/' prefix outside session scope (requires agentId and sessionId on RequestContext)");
		}

		AForkableCursor<ACell> sessionCursor = adapter.ensureUser(ctx)
			.recordCursor(Namespace.G, agentId).path(K_SESSIONS, sessionId);
		return new Position(Workspace.inRecord(sessionCursor, K_C), keys, 1);
	}

	@Override
	public boolean isWritable() {
		return true;
	}
}
