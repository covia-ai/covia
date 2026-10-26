package modtest;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.TestAdapter;

/**
 * The built-in test adapter at state version 2, packaged into a module jar AT
 * TEST RUNTIME by {@link covia.venue.ModuleLoaderTest}. It shares the built-in's
 * name, so the module replaces it. Version 0 to 1 is the built-in's own step;
 * version 1 to 2 marks each user's record.
 */
public class VersionedTestAdapter extends TestAdapter {
	public static final AString MIGRATED = Strings.intern("migrated");
	private static final AString USERS = Strings.intern("users");

	@Override public long stateVersion() {
		return 2;
	}

	@Override public ACell migrateState(long version, ACell state) {
		if (version != 1) return super.migrateState(version, state);
		AMap<AString, ACell> users = RT.ensureMap(RT.getIn(state, USERS));
		if (users == null) return state;
		AMap<AString, ACell> marked = users;
		for (var entry : users.entrySet()) {
			AMap<AString, ACell> record = RT.ensureMap(entry.getValue());
			if (record != null) marked = marked.assoc(entry.getKey(), record.assoc(MIGRATED, CVMLong.create(2)));
		}
		return RT.ensureMap(state).assoc(USERS, marked);
	}
}
