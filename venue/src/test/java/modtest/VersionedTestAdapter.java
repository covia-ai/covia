package modtest;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.TestAdapter;

/**
 * The built-in test adapter one state version on, packaged into a module jar
 * AT TEST RUNTIME by {@link covia.venue.ModuleLoaderTest}. It shares the
 * built-in's name, so the module replaces it. The steps to the built-in's
 * version are the built-in's own; the step past it marks each user's record
 * with the version it reaches.
 */
public class VersionedTestAdapter extends TestAdapter {
	public static final AString MIGRATED = Strings.intern("migrated");
	private static final AString USERS = Strings.intern("users");

	@Override public long stateVersion() {
		return super.stateVersion() + 1;
	}

	@Override public ACell migrateState(long version, ACell state) {
		if (version != super.stateVersion()) return super.migrateState(version, state);
		AMap<AString, ACell> users = RT.ensureMap(RT.getIn(state, USERS));
		if (users == null) return state;
		AMap<AString, ACell> marked = users;
		for (var entry : users.entrySet()) {
			AMap<AString, ACell> record = RT.ensureMap(entry.getValue());
			if (record != null) marked = marked.assoc(entry.getKey(), record.assoc(MIGRATED, CVMLong.create(stateVersion())));
		}
		return RT.ensureMap(state).assoc(USERS, marked);
	}
}
