package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.lattice.cursor.ALatticeCursor;
import covia.adapter.CoviaAdapter;
import covia.exception.WrongScopeException;
import covia.venue.RequestContext;

/**
 * Resolves the {@code c/} virtual namespace to the current session's
 * conversation-scoped slot within the running agent's record.
 *
 * <p>{@code c/draft/notes} resolves to the session record cursor at
 * {@code g/<agent>/sessions/<sid>} with keys {@code ["c", "draft", "notes"]}.
 * The session's {@code c} field is a free-form map for state that the agent
 * (and collaborating parties) can accumulate across turns within a single
 * conversation. Framework lifecycle APIs create session records; scoped
 * scratch must never mint a phantom one, so the target is marked
 * {@code requireExistingRecord}.</p>
 *
 * <p>Requires both {@code ctx.getAgentId()} and {@code ctx.getSessionId()}
 * to be set. Outside that scope the prefix errors helpfully rather than
 * silently resolving to something misleading.</p>
 */
public class SessionNamespaceResolver implements NamespaceResolver {

	private static final AString K_SESSIONS = Strings.intern("sessions");
	private static final AString K_C = Strings.intern("c");

	@Override
	public ResolvedNamespace resolve(RequestContext ctx, CoviaAdapter adapter, ACell[] keys) {
		AString agentId = ctx.getAgentId();
		Blob sessionId = ctx.getSessionId();
		if (agentId == null || sessionId == null) {
			throw new WrongScopeException(
				"Cannot use 'c/' prefix outside session scope (requires agentId and sessionId on RequestContext)");
		}

		ALatticeCursor<ACell> sessionCursor = adapter.ensureUserCursor(ctx)
			.path(Namespace.G, agentId, K_SESSIONS, sessionId);
		ACell[] rewritten = keys.clone();
		rewritten[0] = K_C;
		return new ResolvedNamespace(sessionCursor, rewritten, true);
	}

	@Override
	public boolean isWritable() {
		return true;
	}
}
