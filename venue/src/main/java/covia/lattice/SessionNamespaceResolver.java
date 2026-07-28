package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.lattice.cursor.ALatticeCursor;
import covia.adapter.CoviaAdapter;
import covia.exception.WrongScopeException;
import covia.venue.RequestContext;

/**
 * Resolves the {@code c/} virtual namespace to the current session's
 * conversation-scoped slot within the running agent's record.
 *
 * <p>{@code c/draft/notes} selects the agent record plus its
 * {@code sessionId}; the adapter atomically reads or updates the embedded
 * session's {@code c} value using the remaining keys
 * {@code ["draft", "notes"]}. The session record's {@code c} field is a
 * free-form map for state that the agent (and collaborating parties) can
 * accumulate across turns within a single conversation.</p>
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
			throw new WrongScopeException(
				"Cannot use 'c/' prefix outside session scope (requires agentId and sessionId on RequestContext)");
		}

		// The agent is one atomic LWW record. Position at that record and carry
		// the session selector separately so reads/writes can update the embedded
		// sessions Index atomically.
		ALatticeCursor<ACell> userCursor = adapter.ensureUserCursor(ctx);
		ALatticeCursor<ACell> agentCursor = userCursor.path(Namespace.G, agentId);
		ACell[] remaining = new ACell[keys.length - 1];
		System.arraycopy(keys, 1, remaining, 0, remaining.length);
		return new ResolvedNamespace(agentCursor, remaining, null, agentId, sessionId);
	}

	@Override
	public boolean isWritable() {
		return true;
	}

	private static final AString K_SESSIONS = Strings.intern("sessions");
	private static final AString K_C = Strings.intern("c");
	private static final AString K_TS = Strings.intern("ts");

	/** Reads the user-scratch {@code c} value from one embedded session. */
	@SuppressWarnings("unchecked")
	public static ACell getSessionState(ALatticeCursor<ACell> agentCursor, Blob sessionId) {
		ACell record = agentCursor.get();
		if (!(record instanceof AMap<?, ?> rm)) return null;
		ACell sessionsCell = ((AMap<AString, ACell>) rm).get(K_SESSIONS);
		if (!(sessionsCell instanceof Index<?, ?>)) return null;
		ACell session = ((Index<Blob, ACell>) sessionsCell).get(sessionId);
		if (!(session instanceof AMap<?, ?> sm)) return null;
		return ((AMap<AString, ACell>) sm).get(K_C);
	}

	/**
	 * Atomically updates one session's {@code c} value inside the agent LWW
	 * record. Missing agents/sessions are rejected: framework lifecycle APIs
	 * create those records and scoped scratch must not mint phantom sessions.
	 */
	@SuppressWarnings("unchecked")
	public static void updateSessionState(ALatticeCursor<ACell> agentCursor, Blob sessionId,
			java.util.function.UnaryOperator<ACell> fn) {
		final boolean[] updated = {false};
		agentCursor.updateAndGet(current -> {
			if (!(current instanceof AMap<?, ?> cm)) return current;
			AMap<AString, ACell> record = (AMap<AString, ACell>) cm;
			ACell sessionsCell = record.get(K_SESSIONS);
			if (!(sessionsCell instanceof Index<?, ?>)) return current;
			Index<Blob, ACell> sessions = (Index<Blob, ACell>) sessionsCell;
			ACell sessionCell = sessions.get(sessionId);
			if (!(sessionCell instanceof AMap<?, ?> sm)) return current;
			AMap<AString, ACell> session = (AMap<AString, ACell>) sm;
			ACell next = fn.apply(session.get(K_C));
			updated[0] = true;
			return record
				.assoc(K_SESSIONS, sessions.assoc(sessionId, session.assoc(K_C, next)))
				.assoc(K_TS, CVMLong.create(Utils.getCurrentTimestamp()));
		});
		if (!updated[0]) {
			throw new IllegalArgumentException(
				"Session not found: " + sessionId.toHexString());
		}
	}
}
