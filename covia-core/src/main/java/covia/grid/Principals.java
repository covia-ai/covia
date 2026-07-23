package covia.grid;

import convex.core.data.AString;
import convex.core.data.Strings;

/**
 * Principal naming: the syntax of agent sub-principals and the trust relation
 * between two principals.
 *
 * <p><b>An agent is a sub-principal of its owning user.</b> Its DID is the
 * owner's DID suffixed with {@code :g:<agentId>} — the same {@code g} namespace
 * the agent's state lives in ({@code <ownerDID>/g/<agentId>}), so one vocabulary
 * names the agent as a principal and as a lattice location. Suffixing a DID is
 * an established convention here: the anonymous principal is already
 * {@code <venueDID>:public}. The form is DID-method-agnostic — it composes over
 * {@code did:key} and {@code did:web} owners alike:</p>
 *
 * <pre>
 *   did:web:venue.example:u:alice           the user
 *   did:web:venue.example:u:alice:g:carol   Carol, an agent Alice owns
 *   did:key:z6Mk…:g:carol                   same, under a self-sovereign owner
 * </pre>
 *
 * <p><b>Identity versus namespace.</b> An agent DID answers <em>who acted</em> —
 * it is the principal for attribution, for a delegation audience, and for
 * granting authority. It does <em>not</em> answer <em>whose namespace a bare
 * lattice path names</em>: {@code w/foo} in an agent's {@code config.caps} still
 * means the <em>owner's</em> workspace, because that is the namespace the agent
 * lives and works in. {@link #userOf} is that projection, and
 * {@code Authority.getUserDID()} carries it authoritatively rather than
 * re-deriving it by parsing.</p>
 *
 * <p>This class is deliberately syntax-only: it mints and destructures names and
 * compares them. It grants nothing and checks no capability — an agent is bounded
 * by its capability scope, never by its name.</p>
 */
public final class Principals {

	private Principals() {}

	/**
	 * Separator introducing the agent segment of a sub-principal DID. Matches the
	 * {@code g} lattice namespace agents are stored under.
	 */
	public static final String AGENT_SEP = ":g:";

	/**
	 * Mints the DID of an agent owned by {@code userDID}.
	 *
	 * <p>The agent id must not contain {@code ':'}. Agent ids are single-segment
	 * identifiers everywhere else (a slash cannot be smuggled into one), and
	 * barring a colon likewise keeps exactly one {@link #AGENT_SEP} after a
	 * well-formed owner DID, so {@link #userOf} always destructures back to the
	 * owner it was minted from.</p>
	 *
	 * @param userDID the owning user's DID
	 * @param agentId the agent identifier, unique within that user's namespace
	 * @return the agent's DID, {@code <userDID>:g:<agentId>}
	 * @throws IllegalArgumentException if either argument is null/empty, or the
	 *         agent id contains a colon
	 */
	public static AString agentDID(AString userDID, AString agentId) {
		if (userDID == null || userDID.count() == 0) {
			throw new IllegalArgumentException("Agent DID requires an owning user DID");
		}
		if (agentId == null || agentId.count() == 0) {
			throw new IllegalArgumentException("Agent DID requires an agent id");
		}
		String id = agentId.toString();
		if (id.indexOf(':') >= 0) {
			throw new IllegalArgumentException(
				"Agent id must not contain ':' (would make the agent DID ambiguous): " + id);
		}
		return Strings.create(userDID.toString() + AGENT_SEP + id);
	}

	/** True if {@code did} names an agent sub-principal. */
	public static boolean isAgentDID(AString did) {
		return agentSepIndex(did) >= 0;
	}

	/**
	 * The user whose namespace this principal lives in: the owner for an agent
	 * DID, the principal itself otherwise.
	 *
	 * <p>This is the projection used to resolve bare lattice paths, per-user
	 * state (secrets, workspace, jobs, inbox) and same-user trust. A DID that is
	 * not an agent DID is its own user, so this is safe to apply unconditionally.</p>
	 *
	 * @param did any principal DID (may be null)
	 * @return the owning user's DID, or {@code did} unchanged when it names no agent
	 */
	public static AString userOf(AString did) {
		int i = agentSepIndex(did);
		if (i < 0) return did;
		return Strings.create(did.toString().substring(0, i));
	}

	/**
	 * The agent id of an agent DID, or null when {@code did} names no agent.
	 */
	public static AString agentIdOf(AString did) {
		int i = agentSepIndex(did);
		if (i < 0) return null;
		return Strings.create(did.toString().substring(i + AGENT_SEP.length()));
	}

	/**
	 * Index of the separator that splits owner from agent id, or -1. Uses the
	 * <em>last</em> occurrence: an agent id can carry no colon, so the final
	 * {@code :g:} is always the one this DID was minted with, even where the
	 * owner's own DID ends in a {@code g} segment.
	 */
	private static int agentSepIndex(AString did) {
		if (did == null) return -1;
		String s = did.toString();
		int i = s.lastIndexOf(AGENT_SEP);
		// Must leave a non-empty owner and a non-empty agent id either side.
		if (i <= 0 || i + AGENT_SEP.length() >= s.length()) return -1;
		return i;
	}

	/**
	 * How a target principal stands relative to a caller, ordered from nearest to
	 * furthest. Callers that treat proximity as trust should compare ordinals or
	 * use {@link #isSameUser()} rather than enumerating cases, so a later, finer
	 * relation slots in without reopening every decision.
	 */
	public enum Relation {
		/** The caller itself. */
		SELF,
		/** The user that owns the calling agent (only reachable from an agent). */
		OWNER,
		/** A different principal under the same user — a sibling agent, or an
		 *  agent of the calling user. */
		SAME_USER,
		/** A principal under a different user entirely. */
		FOREIGN;

		/** True for everything within one user's family of principals. */
		public boolean isSameUser() {
			return this != FOREIGN;
		}
	}

	/**
	 * Classifies {@code target} relative to a caller, given the caller's
	 * authoritative user DID.
	 *
	 * <p>Prefer the {@code callerUser} form over {@link #relate(AString, AString)}
	 * wherever the caller's user is carried on its authority: an agent whose DID
	 * is not syntactically nested (a self-sovereign agent holding its own
	 * {@code did:key}) has an owner that cannot be recovered by parsing.</p>
	 *
	 * <p><b>Same-user is proximity, not permission.</b> A {@code SAME_USER}
	 * target is a reasonable place to relax <em>friction</em> — defaulting an
	 * agent's question to its own owner, say — but it is not a grant. Capability
	 * checks stay exactly where they are.</p>
	 *
	 * @param callerDID  the acting principal (may be null → FOREIGN)
	 * @param callerUser the caller's owning user; null falls back to parsing
	 * @param target     the principal being reached for (may be null → FOREIGN)
	 */
	public static Relation relate(AString callerDID, AString callerUser, AString target) {
		if (callerDID == null || target == null) return Relation.FOREIGN;
		if (callerDID.equals(target)) return Relation.SELF;
		AString cu = (callerUser != null) ? callerUser : userOf(callerDID);
		if (cu == null) return Relation.FOREIGN;
		if (cu.equals(target)) return Relation.OWNER;
		return cu.equals(userOf(target)) ? Relation.SAME_USER : Relation.FOREIGN;
	}

	/**
	 * Classifies {@code target} relative to {@code callerDID}, deriving the
	 * caller's user by parsing. See {@link #relate(AString, AString, AString)}.
	 */
	public static Relation relate(AString callerDID, AString target) {
		return relate(callerDID, null, target);
	}
}
