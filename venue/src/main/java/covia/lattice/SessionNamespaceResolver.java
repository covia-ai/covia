package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.lattice.cursor.ALatticeCursor;
import covia.adapter.CoviaAdapter;
import covia.venue.RequestContext;

/**
 * Resolves the {@code c/} virtual namespace to the current session's
 * conversation-scoped slot within the running agent's record.
 *
 * <p>{@code c/draft/notes} resolves to the caller's cursor with rewritten
 * keys {@code ["g", agentId, "sessions", sessionId, "c", "draft", "notes"]}.
 * The session record's {@code c} field is a free-form map for session-scoped
 * values that the agent (and collaborating parties) can accumulate across
 * turns within a single conversation.</p>
 *
 * <p>Requires both {@code ctx.getAgentId()} and {@code ctx.getSessionId()}
 * to be set. Outside that scope the prefix errors helpfully rather than
 * silently resolving to something misleading.</p>
 */
public class SessionNamespaceResolver implements NamespaceResolver {

	@Override
	public ResolvedNamespace resolve(RequestContext ctx, CoviaAdapter adapter, ACell[] keys) {
		AString agentId = ctx.getAgentId();
		Blob sessionId = ctx.getSessionId();
		if (agentId == null || sessionId == null) {
			throw new RuntimeException(
				"Cannot use 'c/' prefix outside session scope (requires agentId and sessionId on RequestContext)");
		}

		// Rewrite c/<rest> → g/{agentId}/sessions/{sessionId}/c/<rest>
		ALatticeCursor<ACell> userCursor = adapter.ensureUserCursor(ctx);
		ACell[] rewritten = new ACell[keys.length + 4];
		rewritten[0] = Strings.create("g");
		rewritten[1] = agentId;
		rewritten[2] = Strings.create("sessions");
		rewritten[3] = sessionId;
		rewritten[4] = Strings.create("c");
		// Skip the "c" prefix in the input keys — replaced by the explicit
		// "c" literal at index 4 above.
		System.arraycopy(keys, 1, rewritten, 5, keys.length - 1);
		return new ResolvedNamespace(userCursor, rewritten);
	}

	@Override
	public boolean isWritable() {
		return true;
	}
}
