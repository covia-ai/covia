package covia.venue;

import convex.auth.did.DIDURL;
import convex.core.data.AString;
import convex.core.data.Path;
import convex.core.data.Strings;
import covia.exception.InvalidInputException;
import covia.grid.Principals;
import covia.lattice.CapabilityChecker;
import covia.lattice.CoviaPath;
import covia.lattice.Namespace;

/**
 * Which agent a reference names: the user who owns it and its id there. A
 * reference is one of
 * <ul>
 * <li>{@code <agentId>}, {@code g/<agentId>} or {@code /g/<agentId>}: an agent
 * of the default owner, such as the user a request acts for;</li>
 * <li>{@code <ownerDID>/g/<agentId>}: an agent of that owner;</li>
 * <li>{@code <ownerDID>:g:<agentId>}: the agent's DID.</li>
 * </ul>
 *
 * <p>Reading a reference is syntax only: it says which agent is named, not
 * whether that agent exists or whether anyone may use it. Every route that
 * takes an agent reference reads it here (AGENT_LOOP.md, "Agent
 * references").</p>
 *
 * @param owner The owning user's DID
 * @param agentId The agent's id, a single path segment without {@code ':'}
 */
public record AgentAddress(AString owner, AString agentId) {

	/** Refusal for a reference that names no agent; the reference follows. */
	public static final String NOT_AN_AGENT = "An agent reference is an agent id, g/<agentId>, "
		+ "<ownerDID>/g/<agentId> or an agent DID <ownerDID>:g:<agentId>, not: ";

	/** Refusal for a relative reference with no user to read it against; the reference follows. */
	public static final String NO_OWNER = "A relative agent reference needs an authenticated caller: ";

	/**
	 * Reads {@code ref} as an agent reference.
	 *
	 * @param defaultOwner The user a relative reference names an agent of, or null for none
	 * @param ref The reference as given
	 * @throws InvalidInputException if {@code ref} names no agent, or is
	 *         relative and there is no default owner
	 */
	public static AgentAddress parse(AString defaultOwner, AString ref) {
		String value = (ref == null) ? "" : CapabilityChecker.canonicalResource(ref.toString(), null);
		if (value.isEmpty()) throw new InvalidInputException(NOT_AN_AGENT + ref);
		if (value.startsWith("did:")) {
			int slash = value.indexOf('/');
			if (slash < 0) {
				AString did = Strings.create(value);
				AString agentId = Principals.agentIdOf(did);
				if (agentId == null) throw new InvalidInputException(NOT_AN_AGENT + ref);
				return of(Principals.userOf(did), agentId, ref);
			}
			return of(Strings.create(value.substring(0, slash)), CoviaPath.parse(value.substring(slash)), ref);
		}
		if (defaultOwner == null) throw new InvalidInputException(NO_OWNER + ref);
		return of(defaultOwner, CoviaPath.parse(value.startsWith("g/") ? value : "g/" + value), ref);
	}

	private static AgentAddress of(AString owner, Path path, AString ref) {
		if (path.count() != 2 || !Namespace.G.equals(path.get(0))) throw new InvalidInputException(NOT_AN_AGENT + ref);
		return of(owner, (AString) path.get(1), ref);
	}

	private static AgentAddress of(AString owner, AString agentId, AString ref) {
		try {
			DIDURL.create(owner.toString());
			Principals.agentDID(owner, agentId); // a non-empty id without ':'
		} catch (IllegalArgumentException e) {
			throw new InvalidInputException(NOT_AN_AGENT + ref, e);
		}
		return new AgentAddress(owner, agentId);
	}

	/** The agent's DID, {@code <owner>:g:<agentId>}. */
	public AString did() {
		return Principals.agentDID(owner, agentId);
	}

	/** The agent's place, {@code <owner>/g/<agentId>}: the resource capabilities over it name. */
	public AString address() {
		return Strings.create(owner + CoviaPath.address(Path.of(Namespace.G, agentId)));
	}
}
