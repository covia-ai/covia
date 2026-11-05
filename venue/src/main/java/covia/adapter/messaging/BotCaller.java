package covia.adapter.messaging;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.migration.MigrationStep;

/**
 * Messaging adapter state version 0 to 1: a created bot that one of its
 * user's agents made names that agent as {@code caller}, as every record of a
 * call does, where it named it {@code principal}. A bot its user made names no
 * caller, as before.
 *
 * <p>The state is {@code {users: {<owner>: {bots: {<name>: settings}}}, ...}};
 * nothing else in it changes.</p>
 */
public final class BotCaller implements MigrationStep {

	static final AString PRINCIPAL = Strings.intern("principal");
	private static final AString USERS = Strings.intern("users");
	private static final AString BOTS = Strings.intern("bots");

	@Override
	public ACell apply(ACell state) {
		AMap<ACell, ACell> users = RT.ensureMap(RT.getIn(state, USERS));
		if (users == null) return state;
		AMap<ACell, ACell> converted = users;
		for (var user : users.entrySet()) {
			AMap<ACell, ACell> bots = RT.ensureMap(RT.getIn(user.getValue(), BOTS));
			if (bots == null) continue;
			AMap<ACell, ACell> renamed = bots;
			for (var bot : bots.entrySet()) {
				AMap<ACell, ACell> settings = RT.ensureMap(bot.getValue());
				if (settings == null || !settings.containsKey(PRINCIPAL)) continue;
				renamed = renamed.assoc(bot.getKey(),
					settings.dissoc(PRINCIPAL).assoc(Fields.CALLER, settings.get(PRINCIPAL)));
			}
			if (renamed != bots) {
				converted = converted.assoc(user.getKey(), RT.ensureMap(user.getValue()).assoc(BOTS, renamed));
			}
		}
		return (converted == users) ? state : RT.ensureMap(state).assoc(USERS, converted);
	}
}
