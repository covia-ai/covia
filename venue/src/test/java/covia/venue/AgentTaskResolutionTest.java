package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Fields;
import covia.grid.Status;

/**
 * Resolving a task ({@link Agent#resolveTask}): its row leaves the queue
 * and its completion is kept with the agent until the request settles, in one
 * transaction, so a resolved task can be neither taken nor resolved again.
 */
public class AgentTaskResolutionTest {

	private static final Blob SID = Blob.fromHex("5e55000000000000000000000000cafe");
	private static final Blob TASK = Blob.fromHex("7a5c000000000000000000000000cafe");
	private static final AMap<AString, ACell> DONE = Maps.of(Fields.ID, TASK, Fields.STATUS, Status.COMPLETE,
		Fields.OUTPUT, Strings.create("result"));

	private Agent agent;

	@BeforeEach
	public void setup(TestInfo info) {
		AString did = TestEngine.uniqueDID(info);
		agent = TestEngine.ENGINE.getVenueState().users().ensure(did).ensureAgent("resolver", Maps.empty(), null);
		agent.ensureSession(SID, did);
		agent.tasks().add(TASK, Maps.of(Fields.INPUT, Maps.of("ask", "something"),
			Fields.SESSION_ID, Strings.create(SID.toHexString())));
	}

	/** Shows the task its input, as a cycle's start does. */
	private long present() {
		ACell row = agent.getTasks().get(TASK);
		agent.beginCycle(TASK, row, null, Strings.create("claim"), Vectors.empty(), 0);
		return Task.revision(agent.getTasks().get(TASK));
	}

	@Test void aResolvedTaskLeavesTheQueueWithItsCompletionKept() {
		long revision = present();
		ACell row = agent.getTasks().get(TASK);
		assertEquals(row, agent.resolveTask(TASK, revision, DONE));
		assertNull(agent.getTasks().get(TASK), "no longer queued");
		assertEquals(DONE, agent.getResolved().get(TASK), "its completion is kept until the request settles");

		assertNull(agent.takeTask(TASK), "a resolved task cannot be cancelled");
		assertNull(agent.resolveTask(TASK, -1, DONE.assoc(Fields.STATUS, Status.FAILED)), "nor resolved again");
		assertEquals(DONE, agent.getResolved().get(TASK));

		agent.removeTask(TASK);
		assertNull(agent.getResolved().get(TASK), "removing the task clears its completion");
	}

	@Test void aContinuationArrivingFirstLeavesTheTaskQueued() {
		long revision = present();
		assertTrue(agent.appendTaskContinuation(TASK, SID, Maps.of(Fields.MESSAGE, "more"), null));
		assertNull(agent.resolveTask(TASK, revision, DONE), "the cycle has not shown the new input");
		assertNotNull(agent.getTasks().get(TASK));
		assertNull(agent.getResolved().get(TASK));
	}
}
