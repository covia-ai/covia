package covia.adapter.slack;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.adapter.messaging.ConversationSessions;
import covia.lattice.WorkspaceRoot;
import covia.venue.CoviaApplication;
import covia.venue.Engine;
import covia.venue.User;

/**
 * Slack's own state migration: state in the layout 0.9.11 wrote, at 0.9.11's
 * location, reaches the adapter through its own paths. In memory, on a
 * prepared engine, with no binding started. A webhook binding keeps its
 * conversation mappings under its identity and settings version.
 */
class SlackMigrationTest {
	static final AString OWNER = Strings.create("did:key:z6MkSlackMigrationOwner");
	static final AMap<AString, ACell> SETTINGS = Maps.of("token", "s/SLACK_BOT_TOKEN", "operation", "v/test/ops/echo");

	/** Slack's state at every version, oldest first: the one layout 0.9.11 wrote. */
	static final List<ACell> STATES = List.of(Maps.of(
		"users", Maps.of(OWNER, Maps.of(
			"bots", Maps.of("support", SETTINGS),
			"sessions", Maps.of("support", Maps.of("identity", Maps.of("version", Maps.of("C0123", "session-a")))))),
		"config", Maps.of("operator", Maps.of("sessions", Maps.of("identity", Maps.of("version", Maps.of("C0456", "session-b")))))));

	@Test void migrationRulesCoverEveryVersion() {
		assertNull(new SlackAdapter().checkStateMigration(STATES));
	}

	@Test void releaseStateReachesTheAdapter() throws Exception {
		AKeyPair key = AKeyPair.generate();
		Engine engine = new Engine(Maps.empty(), CoviaApplication.create(key), key).prepare();
		try {
			// 0.9.11 kept adapter state in the venue user's workspace.
			User host = engine.getVenueState().users().get(engine.getDIDString());
			ACell[] legacy = WorkspaceRoot.parseStringPath("w/adapters/slack");
			host.workspaceRoot().write(legacy, STATES.get(0));
			engine.getVenueState().users().ensure(OWNER);

			SlackAdapter adapter = new SlackAdapter();
			engine.registerAdapter(adapter);
			assertEquals(STATES.get(STATES.size() - 1), adapter.state().get(), "moved exactly, and current");
			assertNull(host.workspaceRoot().read(legacy));

			// Read back through the adapter's own paths.
			assertEquals(SETTINGS, adapter.state().read(adapter.userStatePath(OWNER, "bots/support")));
			assertEquals("session-a", new ConversationSessions(adapter.state(OWNER),
				adapter.userStatePath(OWNER, "sessions/support/identity/version")).get("C0123"));
			assertEquals("session-b", new ConversationSessions(adapter.state(),
				"config/operator/sessions/identity/version").get("C0456"));

			// Its deletion hook understands the migrated layout.
			engine.deleteUser(engine.venueContext(), OWNER);
			assertNull(adapter.state().read(adapter.userStatePath(OWNER, "")));
		} finally {
			engine.close();
		}
	}
}
