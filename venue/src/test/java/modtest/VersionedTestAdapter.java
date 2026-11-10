package modtest;

import java.util.ArrayList;
import java.util.List;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.TestAdapter;
import covia.venue.migration.MigrationStep;

/**
 * The built-in test adapter one state version on, packaged into a module jar
 * AT TEST RUNTIME by {@link covia.venue.ModuleLoaderTest}. It shares the
 * built-in's name, so the module replaces it. The steps to the built-in's
 * version are the built-in's own; the step past it marks each user's record
 * with the version it reaches. The jar holds this one class, so the step is a
 * lambda rather than a class of its own.
 */
public class VersionedTestAdapter extends TestAdapter {
	public static final AString MIGRATED = Strings.intern("migrated");
	private static final AString USERS = Strings.intern("users");

	@Override public List<MigrationStep> stateSteps() {
		List<MigrationStep> steps = new ArrayList<>(super.stateSteps());
		CVMLong reached = CVMLong.create(steps.size() + 1);
		steps.add(state -> markUsers(state, reached));
		return List.copyOf(steps);
	}

	private static ACell markUsers(ACell state, CVMLong reached) {
		AMap<AString, ACell> users = RT.ensureMap(RT.getIn(state, USERS));
		if (users == null) return state;
		AMap<AString, ACell> marked = users;
		for (var entry : users.entrySet()) {
			AMap<AString, ACell> record = RT.ensureMap(entry.getValue());
			if (record != null) marked = marked.assoc(entry.getKey(), record.assoc(MIGRATED, reached));
		}
		return RT.ensureMap(state).assoc(USERS, marked);
	}
}
