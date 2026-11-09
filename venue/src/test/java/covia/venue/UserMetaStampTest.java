package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * The user record's framework-owned {@code meta} slot (see
 * GRID_LATTICE_DESIGN.md §"User meta record"): {@code meta.created} minted on
 * the record's first write, {@code meta.updated} auto-bumped by the user
 * record's stamped cursor ({@code Covia.stampUserMeta}) on EVERY deep write anywhere in the user's
 * subtree — the activity signal for future identity-lifecycle (TTL) policy.
 * No application code writes these fields; the lattice layer does.
 *
 * <p>The venue runs on a write clock that moves only when a test ticks it, so
 * whether a write stamps does not depend on the wall clock moving.</p>
 */
public class UserMetaStampTest {

	/** The venue's write clock. Tests may share it, since they only move it forward. */
	private static final DrivenClock CLOCK = DrivenClock.at(Instant.ofEpochMilli(1_000));
	private static Engine engine;

	private AString ALICE_DID;
	private User user;

	@BeforeAll
	public static void launch() throws IOException {
		AKeyPair key = AKeyPair.generate();
		CoviaApplication application = CoviaApplication.create(CLOCK.policy());
		engine = application.mount(key,
			new Config(Maps.of(Config.USERS, Maps.of(Config.AUTO_CREATE, true)))).start();
		Engine.addDemoAssets(engine);
	}

	@AfterAll
	public static void close() {
		engine.close();
	}

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
		user = engine.getVenueState().users().ensure(ALICE_DID);
	}

	private long metaField(String field) {
		ACell v = RT.getIn(user.cursor().get(), "meta", field);
		return (v instanceof CVMLong l) ? l.longValue() : -1;
	}

	/** Advances the write clock past the current stamp. The venue reads it at
	 *  the next logical write. */
	private void tick() {
		CLOCK.advance(1);
	}

	@Test
	public void testCreatedMintedOnFirstActivity() {
		// ensure() alone is not activity — only writes INTO the record pass
		// the stamping boundary. The first real write mints created == updated.
		assertEquals(-1, metaField("created"), "an ensured-but-unused identity has no meta");

		user.ensureAgent("first-activity-agent", Maps.empty(), null);
		long created = metaField("created");
		long updated = metaField("updated");
		assertTrue(created > 0, "meta.created minted on first activity");
		assertEquals(created, updated, "first activity: created == updated");
	}

	@Test
	public void testDeepAgentWriteBumpsUpdatedNotCreated() {
		Agent agent = user.ensureAgent("meta-agent", Maps.empty(), null);
		long created = metaField("created");
		long before = metaField("updated");
		assertTrue(created > 0);
		tick();

		// The existing long-lived wrapper shares the application's live policy;
		// it does not need to be reconstructed with a fresh context snapshot.
		agent.setStatus(Agent.SUSPENDED);

		assertTrue(metaField("updated") > before,
			"a deep agent write must bump meta.updated");
		assertEquals(created, metaField("created"),
			"meta.created is minted once and never changes");
	}

	@Test
	public void testDeepSessionFrameWriteBumps() {
		user.ensureAgent("frame-agent", Maps.empty(), null).ensureSession(
			Blob.fromHex("dd112233445566778899aabbccddee01"), ALICE_DID);
		Blob sid = Blob.fromHex("dd112233445566778899aabbccddee01");
		long before = metaField("updated");
		tick();

		// A frame write four levels down the subtree still refreshes the
		// user-level stamp (StampedCursor deep-write re-stamp). Fresh wrapper
		// after the clock advance, as real per-access derivation gives.
		Agent agent = engine.getVenueState().users().user(ALICE_DID).agent("frame-agent");
		assertTrue(agent.session(sid).updateFrames(null,
			frames -> frames.conj(Maps.of(Strings.create("description"), Strings.create("x")))));
		assertTrue(metaField("updated") > before,
			"a deep frame write must bump meta.updated");
	}

	@Test
	public void testReadDoesNotBump() {
		Agent agent = user.ensureAgent("read-agent", Maps.empty(), null);
		long before = metaField("updated");
		tick();

		agent.getRecord();
		agent.getTasks();
		user.getAgents();
		engine.getVenueState().users().user(ALICE_DID).cursor().get();

		assertEquals(before, metaField("updated"), "reads must not bump meta.updated");
	}

	@Test
	public void testUnchangedWriteDoesNotBump() {
		Agent agent = user.ensureAgent("noop-agent", Maps.empty(), null);
		agent.setStatus(Agent.SLEEPING);   // already SLEEPING at creation
		long before = metaField("updated");
		tick();

		agent.setStatus(Agent.SLEEPING);   // no-change write
		assertEquals(before, metaField("updated"),
			"an unchanged write must not bump meta.updated");
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testAgentTimestampNeverMovesBackward() {
		Agent agent = user.ensureAgent("ratchet-agent", Maps.empty(), null);
		AString ts = Strings.intern("ts");
		CVMLong future = CVMLong.create(CLOCK.millis() + 60_000);
		agent.cursor().updateAndGet(value ->
			((convex.core.data.AMap<AString, ACell>) value).assoc(ts, future));

		agent.setStatus(Agent.SUSPENDED);

		assertEquals(future, agent.getRecord().get(ts));
	}

	@Test
	public void testMetaIsNotUserData() {
		// meta is the venue's bookkeeping: no path reaches it.
		user.ensureAgent("readable-agent", Maps.empty(), null);
		assertTrue(metaField("updated") > 0, "the record has its meta");
		covia.grid.Job read = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("meta")),
			RequestContext.of(ALICE_DID));
		assertThrows(Exception.class, () -> read.awaitResult(5000));
		assertEquals(String.format(covia.lattice.CoviaPath.NO_SUCH_PATH, "/meta", "/meta"), read.getErrorMessage());
	}

	@Test
	public void testJobPersistenceBumps() {
		// A plain invoke persists a job record under the caller's j/ — even
		// "pure compute" activity refreshes the user's stamp.
		long before = metaField("updated");
		tick();

		engine.jobs().invokeOperation("v/test/ops/echo",
			Maps.of(Strings.create("data"), Strings.create("hi")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		assertTrue(metaField("updated") > before,
			"job persistence must bump meta.updated");
	}
}
