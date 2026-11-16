package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;

/** Agents for tests to work with. */
public final class TestAgents {

	private TestAgents() {}

	/**
	 * {@code user}'s agent {@code id}, created with {@code config} and
	 * {@code state} when it has no record yet.
	 */
	public static Agent ensure(User user, AString id, AMap<AString, ACell> config, ACell state) {
		Agents agents = user.agents();
		agents.create(id, config, state, null, null);
		return agents.at(id);
	}

	/** As {@link #ensure(User, AString, AMap, ACell)}. */
	public static Agent ensure(User user, String id, AMap<AString, ACell> config, ACell state) {
		return ensure(user, Strings.create(id), config, state);
	}
}
