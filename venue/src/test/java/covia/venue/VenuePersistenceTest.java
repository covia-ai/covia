package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Refs;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.RT;
import covia.lattice.CoviaPath;
import convex.etch.EtchStore;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.hitl.Hitl;

/**
 * A venue's whole state survives close and reopen, and starting it again
 * changes none of it. Persistence keeps the entire state, so one venue holding
 * every kind of state checks it once: what the venue held at close is what the
 * reopened store holds, every cell of it can be read back, and a launch on the
 * same key, configuration and held clock leaves the state exactly as it was.
 *
 * <p>The venue closes without an explicit flush, so the final flush is close's
 * own. A second cycle writes to the reopened store, which appends to an
 * existing file. An adapter this build lacks keeps its state as an opaque
 * value, so a module adapter's state is held without loading the module.</p>
 */
class VenuePersistenceTest {
	private static final AKeyPair KEY = AKeyPair.create(Blob.fromHex("5e".repeat(32)));
	private static final AMap<AString, ACell> CONFIG = Maps.of(
		Config.HOSTNAME, Strings.create("persist.test.covia.example"),
		Config.USERS, Maps.of(Config.AUTO_CREATE, true));
	private static final AString ALICE = Strings.create("did:key:z6MkVenuePersistenceAlice");
	private static final AString BOB = Strings.create("did:key:z6MkVenuePersistenceBob");
	private static final AString CAROL = Strings.create("did:key:z6MkVenuePersistenceCarol");
	private static final AString DAVE = Strings.create("did:key:z6MkVenuePersistenceDave");
	/** An adapter this build does not load, as a module that is not configured. */
	private static final String UNLOADED = "unloaded-module";
	/** Longer than 137 bytes: a directory of such names lost entries on reload once (#469). */
	private static final String LONG_NAME = "n".repeat(150) + ".txt";

	@Test void wholeStateSurvivesReopenAndRelaunch() throws Exception {
		DrivenClock clock = DrivenClock.at(Instant.parse("2026-01-01T00:00:00Z"));
		EtchStore store = TestTemp.etch("venue-persistence");
		File file = store.getFile();

		CoviaApplication application = TestEngine.application(store, clock);
		Engine engine = application.mount(KEY, CONFIG).launch();
		holdEveryKindOfState(engine);
		ACell held = closeAll(engine, application, store);

		ACell relaunched = reopenAndLaunch(file, clock, engine2 -> {
			assertHoldsEveryKindOfState(engine2);
			run(engine2, ALICE, "covia/write", Maps.of(Fields.PATH, "w/second-cycle", Fields.VALUE, 2));
		}, held);

		reopenAndLaunch(file, clock, engine3 -> assertEquals(CVMLong.create(2),
			engine3.getVenueState().users().user(ALICE).workspace().read(CoviaPath.parse("second-cycle"))),
			relaunched);
	}

	/** The comparison is with a state that holds every kind: these say it does. */
	private static void assertHoldsEveryKindOfState(Engine engine) {
		Users users = engine.getVenueState().users();
		User alice = users.user(ALICE);
		RequestContext ctx = RequestContext.of(ALICE);
		assertEquals(Maps.of("n", 1), alice.workspace().read(CoviaPath.parse("notes/first")));
		assertTrue(alice.secrets().exists("API_KEY"));
		assertEquals("long", RT.getIn(run(engine, ALICE, "dlfs/read",
			Maps.of("drive", "kept", Fields.PATH, "dir/" + LONG_NAME)), Fields.CONTENT).toString());
		Set<ACell> statuses = new java.util.HashSet<>();
		engine.jobs().getJobs(ctx).values().forEach(job -> statuses.add(RT.getIn(job, Fields.STATUS)));
		assertTrue(statuses.containsAll(List.of(Status.COMPLETE, Status.FAILED, Status.CANCELLED, Status.PAUSED)),
			statuses.toString());
		assertNotNull(alice.agent(Strings.create("helper")).getTimeline().get(0));
		assertFalse(engine.gridScheduler().list(ctx).isEmpty());
		assertTrue(users.isDeleted(BOB));
		assertTrue(users.isDeleting(DAVE));
		assertEquals(CVMLong.ONE, engine.adapterState(UNLOADED).read("config/kept"));
	}

	/** Every kind of state a venue keeps, made as its users make it. */
	private static void holdEveryKindOfState(Engine engine) {
		engine.getVenueState().users().ensure(ALICE);
		engine.getVenueState().users().ensure(BOB);

		// Workspace, agent memory, secrets and preferences
		run(engine, ALICE, "covia/write", Maps.of(Fields.PATH, "w/notes/first", Fields.VALUE, Maps.of("n", 1)));
		run(engine, ALICE, "memory", Maps.of("command", "remember", "text", "noted"));
		run(engine, ALICE, "secret/set", Maps.of(Fields.NAME, "API_KEY", Fields.VALUE, "test-only"));
		run(engine, ALICE, "preferences", Maps.of("command", "set", "path", "units", "value", "metric"));

		// An asset of the user's own, with content in the venue's content storage
		run(engine, ALICE, "asset/store", Maps.of(
			"metadata", Maps.of("name", "Persisted document", "type", "document"),
			"contentText", "Section 1: kept across restarts."));

		// DLFS: a drive with files, among them a long name, and a deleted drive
		run(engine, ALICE, "dlfs/create-drive", Maps.of("drive", "kept"));
		run(engine, ALICE, "dlfs/mkdir", Maps.of("drive", "kept", Fields.PATH, "dir"));
		run(engine, ALICE, "dlfs/write", Maps.of("drive", "kept", Fields.PATH, "dir/short.txt", Fields.CONTENT, "short"));
		run(engine, ALICE, "dlfs/write", Maps.of("drive", "kept", Fields.PATH, "dir/" + LONG_NAME, Fields.CONTENT, "long"));
		run(engine, ALICE, "dlfs/create-drive", Maps.of("drive", "gone"));
		run(engine, ALICE, "dlfs/delete-drive", Maps.of("drive", "gone"));

		// Jobs that completed, failed and were cancelled, and one left paused
		RequestContext alice = RequestContext.of(ALICE);
		engine.jobs().invokeOperation("v/test/ops/echo", Maps.of("n", 1), alice).awaitResult(5000);
		Job failed = engine.jobs().invokeOperation("v/test/ops/error", Maps.of("message", "on purpose"), alice);
		TestEngine.awaitJob(failed, Job::isFinished, 5000, () -> "the failing job did not finish");
		Job cancelled = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), alice);
		engine.jobs().cancelJob(cancelled.getID(), alice);
		Job paused = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), alice);
		TestEngine.awaitJob(paused, j -> Status.STARTED.equals(j.getStatus()), 5000, () -> "the job did not start");
		engine.jobs().pauseJob(paused.getID(), alice);
		assertEquals(Status.PAUSED, paused.getStatus());

		// An agent with a conversation
		run(engine, ALICE, "agent/create", Maps.of(Fields.AGENT_ID, "helper", Fields.CONFIG, Maps.of(
			"operation", "v/ops/llmagent/chat", "llmOperation", "v/test/ops/llm",
			"systemPrompt", "Venue persistence test")));
		run(engine, ALICE, "agent/chat", Maps.of(Fields.AGENT_ID, "helper", Fields.MESSAGE, "hello"));

		// A human request, answered, and an operation scheduled for later
		Job asked = engine.jobs().invokeOperation("v/ops/hitl/request",
			Hitl.request("Keep this?").ask(Hitl.approval("ok", "OK?")).build(), alice);
		run(engine, ALICE, "hitl/respond", Hitl.answer(asked.getID().toHexString()).answer("ok", true).build());
		run(engine, ALICE, "scheduler/schedule", Maps.of(
			"operation", "v/test/ops/echo", "input", Maps.of("later", true), "after", CVMLong.create(3_600_000L)));

		// Users: one created, one deleted, and one whose deletion waits on an
		// adapter this build lacks, which keeps its state as an opaque value
		engine.jobs().invokeInternal("v/ops/user/create", Maps.of(Fields.DID, CAROL), engine.venueContext()).join();
		engine.getVenueState().users().ensure(DAVE);
		engine.deleteUser(engine.venueContext(), BOB);
		AdapterState unloaded = engine.adapterState(UNLOADED);
		unloaded.write(unloaded.userPath(DAVE, "bots/one"), Maps.of("token", "s/bot"));
		unloaded.write("config/kept", CVMLong.ONE);
		assertThrows(covia.exception.UnavailableException.class,
			() -> engine.deleteUser(engine.venueContext(), DAVE));
	}

	/** Closes the venue as a process ends, and returns the venue state it closed with. */
	private static ACell closeAll(Engine engine, CoviaApplication application, EtchStore store) throws Exception {
		engine.close();
		ACell state = engine.getVenueState().get();
		application.host().close();
		store.close();
		return state;
	}

	/**
	 * Reopens the store, reads every cell of it back, launches the venue on it
	 * and checks the state is {@code expected}. Then makes {@code change}, if
	 * any, and closes, returning the state it closed with.
	 */
	private static ACell reopenAndLaunch(File file, DrivenClock clock,
			java.util.function.Consumer<Engine> change, ACell expected) throws Exception {
		EtchStore store = EtchStore.create(file);
		ACell root = store.getRootData();
		try {
			Refs.visitAllRefs(root.getRef(), r -> { });
		} catch (MissingDataException e) {
			fail("the store lacks part of what it saved: " + Refs.printMissingTree(root));
		}
		CoviaApplication application = TestEngine.application(store, clock);
		Engine engine = application.mount(KEY, CONFIG).launch();
		ACell state = engine.getVenueState().get();
		List<String> differences = new ArrayList<>();
		diff("", expected, state, differences);
		assertTrue(differences.isEmpty(), "launching again changed the venue state: " + differences);
		if (change != null) change.accept(engine);
		return closeAll(engine, application, store);
	}

	private static ACell run(Engine engine, AString caller, String op, ACell input) {
		return engine.jobs().invokeOperation("v/ops/" + op, input, RequestContext.of(caller)).awaitResult(10_000);
	}

	/** Collects the paths at which two states differ, for a failure message that says where. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void diff(String path, ACell a, ACell b, List<String> out) {
		if (Objects.equals(a, b) || out.size() >= 20) return;
		if (a instanceof AMap ma && b instanceof AMap mb) {
			Set<ACell> keys = new LinkedHashSet<>();
			for (long i = 0; i < ma.count(); i++) keys.add((ACell) ma.entryAt(i).getKey());
			for (long i = 0; i < mb.count(); i++) keys.add((ACell) mb.entryAt(i).getKey());
			for (ACell k : keys) diff(path + "/" + k, (ACell) ma.get(k), (ACell) mb.get(k), out);
		} else if (a instanceof AVector va && b instanceof AVector vb && va.count() == vb.count()) {
			for (long i = 0; i < va.count(); i++) diff(path + "[" + i + "]", (ACell) va.get(i), (ACell) vb.get(i), out);
		} else {
			out.add(path + ": " + a + " -> " + b);
		}
	}
}
