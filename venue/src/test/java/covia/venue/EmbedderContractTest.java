package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.etch.EtchStore;
import covia.adapter.AAdapter;
import covia.adapter.agent.GoalTreeContext;
import covia.lattice.WorkspaceRoot;
import covia.test.DurabilityTest;
import covia.venue.server.VenueServer;

/**
 * The surface an embedding application uses, exercised the way GetMine and
 * Brightside use it (venue/docs/EMBEDDING.md). A change that breaks one of
 * these calls fails here before it reaches an embedder.
 */
@DurabilityTest
class EmbedderContractTest {

	static final AString ALICE = Strings.create("did:key:z6MkEmbedderContractAlice");
	static final AString BOB = Strings.create("did:key:z6MkEmbedderContractBob");
	static final String SEED = "7a".repeat(32);
	static final String ADAPTER = "embedder";
	static final ACell[] RECORD_PATH = WorkspaceRoot.parseStringPath("w/embedder/record");
	static final ACell RECORD = Maps.of("name", "embedded", "count", 3);

	@TempDir Path dir;

	/** An embedder's own adapter, keeping per-user state as GetMine's and Brightside's do. */
	static final class EmbedderAdapter extends AAdapter {
		@Override public String getName() { return ADAPTER; }
		@Override public String getDescription() { return "Embedder contract test"; }
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			return CompletableFuture.completedFuture(input);
		}
		@Override public void deleteUserState(AString did, AdapterState state) {
			state.delete(state.userPath(did, ""));
		}
	}

	static AMap<AString, ACell> config() {
		return Maps.of(Config.PORT, 0, Config.BIND_ADDRESS, "127.0.0.1", Config.SEED, SEED,
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, false)));
	}

	static ACell run(Engine engine, AString caller, String op, ACell input) {
		return engine.jobs().invokeOperation("v/ops/" + op, input, RequestContext.of(caller)).awaitResult(20_000);
	}

	@Test void embedderRunsAVenueOnItsOwnStoreAndReopensIt() throws Exception {
		File file = dir.resolve("embedder.etch").toFile();
		EtchStore store = EtchStore.create(file);
		assertTrue(VenueState.peekVenueKeys(store).isEmpty(), "a fresh store holds no venue");
		AccountKey venueKey;

		// The venue adopts the caller-opened store and closes it with itself.
		VenueServer server = VenueServer.launch(config(), store);
		try {
			Engine engine = server.getEngine();
			venueKey = engine.getAccountKey();
			engine.registerAdapter(new EmbedderAdapter());

			// Writes that must land together go in one user transaction.
			User alice = engine.getVenueState().users().ensure(ALICE);
			alice.transact(draft -> {
				draft.workspaceRoot().write(RECORD_PATH, RECORD);
				draft.workspaceRoot().write(WorkspaceRoot.parseStringPath("w/embedder/stamp"), CVMLong.ONE);
			});
			assertEquals(RECORD, alice.workspaceRoot().read(RECORD_PATH));

			// Secrets under the venue's current keys.
			SecretStore.Keys keys = SecretStore.keys(engine.getKeyPair());
			alice.secrets().store("EMBEDDER_TOKEN", "test-only", keys);
			assertEquals("test-only", alice.secrets().decrypt("EMBEDDER_TOKEN", keys).toString());

			// An agent conversation, read through the format-independent accessor.
			run(engine, ALICE, "agent/create", Maps.of("agentId", "helper", "config", Maps.of(
				"operation", "v/ops/llmagent/chat", "llmOperation", "v/test/ops/llm",
				"systemPrompt", "Embedder contract test")));
			ACell chat = run(engine, ALICE, "agent/chat", Maps.of("agentId", "helper", "message", "hello from the embedder"));
			Blob sid = Blob.fromHex(RT.ensureString(RT.getIn(chat, "sessionId")).toString());
			AMap<AString, ACell> session = alice.agent("helper").getSession(sid);
			AMap<AString, ACell> frame = RT.ensureMap(RT.ensureVector(session.get(AgentState.KEY_FRAMES)).get(0));
			AVector<ACell> conversation = GoalTreeContext.archivedConversation(frame);
			assertTrue(conversation.toString().contains("hello from the embedder"), conversation.toString());

			// A switched-off adapter still deletes its state for a deleted user.
			AdapterState state = engine.adapterState(ADAPTER);
			engine.getVenueState().users().ensure(BOB);
			state.write(state.userPath(BOB, "bots/one"), CVMLong.ONE);
			assertTrue(engine.disableAdapter(ADAPTER));
			engine.deleteUser(engine.venueContext(), BOB);
			assertTrue(engine.getVenueState().users().isDeleted(BOB));
			assertNull(state.read(state.userPath(BOB, "bots/one")));

			server.getHost().checkpoint();
		} finally {
			server.close();
		}

		// A stopped store names its venue without starting it, and reopens with its data.
		EtchStore reopened = EtchStore.create(file);
		assertEquals(java.util.Set.of(venueKey), VenueState.peekVenueKeys(reopened));
		VenueServer again = VenueServer.launch(config(), reopened);
		try {
			Engine engine = again.getEngine();
			assertEquals(venueKey, engine.getAccountKey());
			assertEquals(RECORD, engine.getVenueState().users().get(ALICE).workspaceRoot().read(RECORD_PATH));
			assertTrue(engine.getVenueState().users().isDeleted(BOB));
		} finally {
			again.close();
		}
	}

	@Test void anEngineOnAnEmbeddersHostLeavesClosingItToTheEmbedder() throws Exception {
		try (EtchStore store = EtchStore.create(dir.resolve("own-host.etch").toFile())) {
			AKeyPair key = AKeyPair.create(Blob.fromHex(SEED));
			CoviaApplication application = CoviaApplication.open(store, key);
			var host = application.host();
			Engine engine = new Engine(Maps.of(Config.USERS, Maps.of(Config.AUTO_CREATE, true)), application, key).start();
			engine.getVenueState().users().ensure(ALICE).workspaceRoot().write(RECORD_PATH, RECORD);
			engine.close(); // its final flush is the engine's last save

			host.checkpoint(); // still open: whoever opened the host closes it
			host.close();
			assertThrows(IllegalStateException.class, host::sync);
			host.close(); // closing again changes nothing
		}
	}
}
