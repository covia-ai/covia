package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.etch.EtchStore;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.venue.server.VenueServer;

/**
 * Online garbage collection of a running venue's Etch store through
 * {@code venue:gc} (covia#452): venue-operator-only, the venue keeps serving
 * across the cutover, one collection per process, and a plain relaunch adopts
 * the collected file with everything intact.
 *
 * <p>Venue launches are the expensive part: the whole live story runs as one
 * scenario on one store (plus a relaunch), the authority matrix runs on the
 * shared {@link TestEngine} with no venue at all, and the temp-store refusal
 * uses the cheapest venue there is.</p>
 */
public class VenueGcOperationTest {

	private static final String SEED_HEX =
		"7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b";
	private static final AString USER = Strings.create("did:key:z6Mk-test-venue-gc");
	private static final String OP = "v/ops/venue/gc";
	private static final AMap<AString, ACell> STATUS = Maps.of("status", CVMBool.TRUE);

	private static AMap<AString, ACell> config(String store) {
		return Maps.of(
			Config.PORT, 0,
			Config.STORE, Strings.create(store),
			Config.SEED, Strings.create(SEED_HEX),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true));
	}

	private static void write(VenueServer v, String path, ACell value) throws Exception {
		v.getEngine().jobs().invokeInternal("v/ops/covia/write",
			Maps.of(Fields.PATH, path, Fields.VALUE, value), RequestContext.of(USER))
			.get(5, TimeUnit.SECONDS);
	}

	private static ACell read(VenueServer v, String path) throws Exception {
		ACell read = v.getEngine().jobs().invokeInternal("v/ops/covia/read",
			Maps.of(Fields.PATH, path), RequestContext.of(USER)).get(5, TimeUnit.SECONDS);
		return RT.getIn(read, Fields.VALUE);
	}

	private static ACell gc(Engine engine, RequestContext ctx, AMap<AString, ACell> input) {
		return engine.jobs().invokeOperation(OP, input, ctx).awaitResult(60_000);
	}

	private static String gcFails(Engine engine, RequestContext ctx, AMap<AString, ACell> input) {
		Job job = engine.jobs().invokeOperation(OP, input, ctx);
		assertThrows(JobFailedException.class, () -> job.awaitResult(60_000));
		return job.getErrorMessage();
	}

	private static long asLong(ACell map, String key) {
		CVMLong v = RT.ensureLong(RT.getIn(map, key));
		assertNotNull(v, key);
		return v.longValue();
	}

	@Test
	public void collectsWhileServingThenAdoptsOnRelaunch() throws Exception {
		File file = new File(TestTemp.dir("venue-gc-online").toFile(), "venue.etch");
		String storePath = file.getAbsolutePath().replace('\\', '/');
		long garbage = EtchGcOnStartTest.addGarbage(file);

		long before = 0;
		VenueServer v = VenueServer.launch(config(storePath));
		try {
			Engine engine = v.getEngine();
			RequestContext operator = engine.venueContext();
			write(v, "w/before-gc", Strings.create("before"));

			// Before any cycle: status reports the uncollected file; cancel is a
			// no-op; restart:true is refused up front (no MainVenue process
			// control), so no collection ran for a restart that could not follow.
			ACell status = gc(engine, operator, STATUS);
			assertEquals(CVMBool.FALSE, RT.getIn(status, "inProgress"));
			assertEquals(CVMBool.FALSE, RT.getIn(status, "completed"));
			assertTrue(asLong(status, "bytes") >= garbage);
			assertEquals(CVMBool.FALSE, RT.getIn(gc(engine, operator, Maps.of("cancel", CVMBool.TRUE)), "inProgress"));
			String noRestart = gcFails(engine, operator, Maps.of("restart", CVMBool.TRUE));
			assertTrue(noRestart.contains("restart"), noRestart);
			assertEquals(CVMBool.FALSE, RT.getIn(gc(engine, operator, STATUS), "completed"));

			// The collection itself.
			ACell result = gc(engine, operator, Maps.empty());
			before = asLong(result, "bytesBefore");
			long after = asLong(result, "bytesAfter");
			assertTrue(after < before, "collected file must be smaller: " + after + " vs " + before);
			assertEquals(before - after, asLong(result, "reclaimed"));
			assertTrue(before - after > garbage / 2,
				"the garbage must be what went: reclaimed " + (before - after) + " of " + garbage);
			assertEquals(Strings.create("shutdown"), RT.getIn(result, "reclaimedAt"));
			assertNotNull(RT.getIn(result, "collectedFile"));

			// The venue keeps serving on the old handle: cycle-era data and new
			// writes resolve, and durability barriers still work.
			assertEquals(Strings.create("before"), read(v, "w/before-gc"));
			write(v, "w/after-gc", Strings.create("after"));
			engine.flush();
			assertEquals(Strings.create("after"), read(v, "w/after-gc"));

			ACell done = gc(engine, operator, STATUS);
			assertEquals(CVMBool.TRUE, RT.getIn(done, "completed"));
			assertEquals(CVMBool.FALSE, RT.getIn(done, "inProgress"));
			assertTrue(asLong(done, "collectedBytes") >= after);

			// One collection per process: the successor cannot be threaded into
			// the running node, so a second cycle needs a restart first.
			String again = gcFails(engine, operator, Maps.empty());
			assertTrue(again.contains("restart"), again);
		} finally {
			v.close();
		}

		// Both handles closed cleanly; a plain relaunch adopts the collected
		// file (or opens it directly while the old one is pinned) with
		// everything written before and after the cycle intact.
		VenueServer relaunched = VenueServer.launch(config(storePath));
		try {
			assertEquals(Strings.create("before"), read(relaunched, "w/before-gc"));
			assertEquals(Strings.create("after"), read(relaunched, "w/after-gc"));
			EtchStore store = (EtchStore) relaunched.getStore();
			assertTrue(store.getEtch().getDataLength() < before,
				"the relaunched venue must run on the collected data");
			assertFalse(store.isGCInProgress());
		} finally {
			relaunched.close();
		}
	}

	@Test
	public void venueOperatorOnly() {
		// No venue needed: authority is decided before the store seam is touched.
		Engine engine = TestEngine.ENGINE;
		AString venue = engine.getDIDString();
		// An ordinary user, a user's agent, the venue's own agent and the public
		// principal are all refused: only the venue principal itself (or a
		// venue-issued venue/gc delegation) may collect.
		for (RequestContext caller : new RequestContext[] {
				RequestContext.of(USER),
				RequestContext.ofAgent(USER, Strings.create("helper")),
				RequestContext.ofAgent(venue, Strings.create("odin")),
				RequestContext.of(Strings.create(venue + ":public"))}) {
			String denied = gcFails(engine, caller, STATUS);
			assertTrue(denied.contains("venue/gc") || denied.contains("denied"), denied);
		}
		// The venue itself passes authority; this engine's host installed no
		// store seam, which is the next thing the operation reports.
		String noSeam = gcFails(engine, engine.venueContext(), STATUS);
		assertTrue(noSeam.contains("unavailable"), noSeam);
	}

	@Test
	public void refusedOnATemporaryStore() throws Exception {
		VenueServer v = VenueServer.launch(Maps.of(
			Config.PORT, 0, Config.STORE, Strings.create("temp"), Config.SEED, Strings.create(SEED_HEX)));
		try {
			String refused = gcFails(v.getEngine(), v.getEngine().venueContext(), Maps.empty());
			assertTrue(refused.contains("persistent file"), refused);
		} finally {
			v.close();
		}
	}
}
