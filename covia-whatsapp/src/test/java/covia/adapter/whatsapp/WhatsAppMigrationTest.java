package covia.adapter.whatsapp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Path;
import convex.core.data.Strings;
import covia.adapter.messaging.ConversationSessions;
import covia.api.Fields;
import covia.lattice.CoviaPath;
import covia.lattice.Workspace;
import covia.venue.Engine;
import covia.venue.TestEngine;
import covia.venue.User;
import covia.test.IntegrationTest;

/**
 * WhatsApp's own state migration: state in the layout 0.9.11 wrote, at 0.9.11's
 * location, reaches the adapter through its own paths. In memory, on a
 * prepared engine, with no binding started. A webhook binding keeps its
 * conversation mappings under its identity and settings version.
 */
class WhatsAppMigrationTest {
	static final AString OWNER = Strings.create("did:key:z6MkWhatsAppMigrationOwner");
	static final AMap<AString, ACell> SETTINGS = Maps.of("token", "s/WHATSAPP_TOKEN", "operation", "v/test/ops/echo");

	/** A bot one of the owner's agents created, and that agent. */
	static final AMap<AString, ACell> HELPER = SETTINGS.assoc(Strings.create("operation"), Strings.create("v/test/ops/random"));
	static final AString AGENT = Strings.create(OWNER + ":g:helper");

	/** The sessions two conversations continue, and the chat job a delivery receipt names. */
	static final Blob SESSION_A = Blob.fromHex("a1".repeat(16)), SESSION_B = Blob.fromHex("b2".repeat(16)),
		CHAT_JOB = Blob.fromHex("c3".repeat(16));

	/** WhatsApp's state at every version, oldest first. Version 0 is the layout releases
	 *  before 0.11.0 wrote, with a bot an agent created naming it {@code principal} as later
	 *  releases wrote it; version 1 names it {@code caller}; version 2 keeps the session and
	 *  job ids of conversations and receipts as Blobs. */
	static final List<ACell> STATES = List.of(
		state(Strings.create("principal"), WhatsAppMigrationTest::hex),
		state(Fields.CALLER, WhatsAppMigrationTest::hex),
		state(Fields.CALLER, id -> id));

	private static ACell hex(Blob id) {
		return Strings.create(id.toHexString());
	}

	/** The state with a bot's creating agent under {@code creator}, and each id as {@code id} gives it. */
	private static ACell state(AString creator, Function<Blob, ACell> id) {
		return Maps.of(
			"users", Maps.of(OWNER, Maps.of(
				"bots", Maps.of("support", SETTINGS, "helper", HELPER.assoc(creator, AGENT)),
				"sessions", Maps.of("support", Maps.of("identity", Maps.of("version", Maps.of("447700900001", id.apply(SESSION_A))))),
				"inbox", Maps.of("support", Maps.of("identity", Maps.of("event-1", Maps.of(Fields.STATUS, "COMPLETE",
					Fields.SESSION_ID, id.apply(SESSION_A), Fields.JOB_ID, id.apply(CHAT_JOB))))))),
			"config", Maps.of("operator", Maps.of(
				"sessions", Maps.of("identity", Maps.of("version", Maps.of("447700900002", id.apply(SESSION_B)))),
				"inbox", Maps.of("identity", Maps.of("event-2", Maps.of(Fields.STATUS, "PENDING"))))));
	}

	@Test void migrationRulesCoverEveryVersion() {
		assertNull(new WhatsAppAdapter().checkStateMigration(STATES));
	}

	@IntegrationTest
	@Test void releaseStateReachesTheAdapter() throws Exception {
		AKeyPair key = AKeyPair.generate();
		Engine engine = TestEngine.application().mount(key, Maps.empty()).prepare();
		try {
			// 0.9.11 kept adapter state in the venue user's workspace.
			User host = engine.getVenueState().users().user(engine.getDIDString());
			Workspace workspace = host.workspace();
			Path legacy =CoviaPath.parse("adapters/whatsapp");
			workspace.createPath(CoviaPath.parent(legacy));
			workspace.write(legacy, STATES.get(0));
			engine.getVenueState().users().ensure(OWNER);

			WhatsAppAdapter adapter = new WhatsAppAdapter();
			engine.registerAdapter(adapter);
			assertEquals(STATES.get(STATES.size() - 1), adapter.state().get(), "moved exactly, and current");
			assertNull(workspace.read(legacy));

			// Read back through the adapter's own paths.
			assertEquals(SETTINGS, adapter.state().read(adapter.userStatePath(OWNER, "bots/support")));
			assertEquals(SESSION_A, new ConversationSessions(adapter.state(OWNER),
				adapter.userStatePath(OWNER, "sessions/support/identity/version")).get("447700900001"));
			assertEquals(SESSION_B, new ConversationSessions(adapter.state(),
				"config/operator/sessions/identity/version").get("447700900002"));

			// Its deletion hook understands the migrated layout.
			engine.deleteUser(engine.venueContext(), OWNER);
			assertNull(adapter.state().read(adapter.userStatePath(OWNER, "")));
		} finally {
			engine.close();
		}
	}
}
