package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.grid.Job;

/**
 * A prepared Job is recorded in its caller's venue transaction, under an id
 * taken there, and started once that transaction has committed.
 */
public class PreparedJobTest {
	private final Engine engine = TestEngine.ENGINE;
	private static final AString ECHO = Strings.create("v/test/ops/echo");
	private static final AString W = Strings.create("w");
	private static final AString FIRED = Strings.create("fired");
	private AString did;
	private RequestContext ctx;

	@BeforeEach void caller(TestInfo info) {
		did = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(did);
	}

	private JobManager.Prepared prepare() {
		return engine.jobs().prepareTracked(ECHO, Maps.of("message", "hi"), ctx);
	}

	private ACell workspace() {
		return engine.getVenueState().users().user(did).cursor().path(W).getOrDefault(null);
	}

	@Test void commitsWithTheCallersOwnRecordOfIt() throws Exception {
		JobManager.Prepared prepared = prepare();
		engine.getVenueState().execute(draft -> {
			Blob id = prepared.record(draft);
			draft.users().ensure(did).cursor().path(W).set(Maps.of(FIRED, id));
		});
		Job job = prepared.start();
		assertEquals(job.getID(), RT.getIn(workspace(), FIRED));
		assertNotNull(job.awaitResult(5000));
	}

	@Test void aFailedTransactionLeavesNoJob() {
		JobManager.Prepared prepared = prepare();
		AtomicReference<Blob> taken = new AtomicReference<>();
		assertThrows(RuntimeException.class, () -> engine.getVenueState().execute(draft -> {
			taken.set(prepared.record(draft));
			throw new IllegalStateException("The caller's own write was refused");
		}));
		prepared.abandon();
		Blob id = taken.get();
		assertNull(engine.getVenueState().users().ensure(did).jobs().job(id));
		assertFalse(engine.jobs().accepting.containsKey(id));
		assertThrows(IllegalStateException.class, prepared::start, "Nothing was recorded to start");
	}

	@Test void aTransactionThatRunsAgainKeepsTheJobItCommitted() throws Exception {
		JobManager.Prepared prepared = prepare();
		List<Blob> taken = new ArrayList<>();
		engine.getVenueState().execute(draft -> {
			taken.add(prepared.record(draft));
			// A write committed elsewhere while this run is open makes it run again
			if (taken.size() == 1) {
				engine.getVenueState().users().ensure(did).cursor().path(W).set(Maps.of(FIRED, "elsewhere"));
			}
		});
		assertTrue(taken.size() > 1, "The transaction ran again");
		Job job = prepared.start();
		assertEquals(taken.get(taken.size() - 1), job.getID());
		Jobs jobs = engine.getVenueState().users().user(did).jobs();
		for (Blob earlier : taken.subList(0, taken.size() - 1)) {
			assertNull(jobs.job(earlier));
			assertFalse(engine.jobs().accepting.containsKey(earlier));
		}
		assertNotNull(job.awaitResult(5000));
	}
}
