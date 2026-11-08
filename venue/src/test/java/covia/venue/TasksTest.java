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
import convex.lattice.NoSuchPathException;
import covia.api.Fields;

/** An agent's queue of tasks and each task's own operations. */
public class TasksTest {

	private static final Blob TASK = Blob.fromHex("aa01");
	private static final Blob SESSION = Blob.fromHex("bb02");
	private static final Blob OTHER_SESSION = Blob.fromHex("bb03");
	private static final AString SESSION_HEX = Strings.create(SESSION.toHexString());
	private static final AString MESSAGE_ID = Strings.create("message-1");

	private AgentState agent;
	private Tasks tasks;
	private Task task;

	@BeforeEach
	public void setup(TestInfo info) {
		AString owner = TestEngine.uniqueDID(info);
		agent = TestEngine.ENGINE.getVenueState().users().ensure(owner)
			.ensureAgent(Strings.create("tasks-agent"), Maps.empty(), null);
		agent.ensureSession(SESSION, owner);
		tasks = agent.tasks();
		task = tasks.task(TASK);
	}

	private void queue() {
		assertTrue(tasks.add(TASK, Maps.of(Fields.INPUT, "work", Fields.SESSION_ID, SESSION_HEX)));
	}

	private static ACell envelope(String message) {
		return Maps.of(Fields.MESSAGE, message);
	}

	@Test
	public void testAddRefusesAnIdAlreadyQueued() {
		queue();
		assertFalse(tasks.add(TASK, Maps.of(Fields.INPUT, "other")));
		assertEquals(Strings.create("work"), task.nextInput());
	}

	@Test
	public void testATaskThatIsNotQueuedAcceptsNothing() {
		assertNull(task.getRecord());
		assertFalse(task.appendContinuation(SESSION, envelope("m"), null));
		assertFalse(task.park(0));
		assertFalse(task.setWakeTime(5));
		assertFalse(task.present());
		assertNull(tasks.take(TASK, -1));
		assertNull(task.getRecord(), "nothing may bring it into being");
	}

	@Test
	public void testInputsAreShownInOrderAndOnce() {
		queue();
		assertTrue(task.appendContinuation(SESSION, envelope("second"), null));
		assertEquals(1, task.revision());

		assertEquals(Strings.create("work"), task.nextInput());
		assertEquals(task.getRecord(), task.nextInputSource(), "the original input comes from the row");
		assertTrue(task.present());
		assertEquals(Strings.create("second"), task.nextInput());
		assertEquals(envelope("second"), task.nextInputSource());
		assertTrue(task.present());
		assertFalse(task.hasUnshownInput());
		assertNull(task.nextInput());
		assertTrue(task.present(), "presenting with nothing unshown still runs the task");
		assertFalse(task.hasUnshownInput());
	}

	@Test
	public void testAContinuationBelongsToItsTasksSession() {
		queue();
		assertFalse(task.appendContinuation(OTHER_SESSION, envelope("m"), null));
		assertEquals(0, task.revision());
	}

	@Test
	public void testARepeatedMessageIdIsAcceptedOnce() {
		queue();
		assertTrue(task.appendContinuation(SESSION, envelope("m"), MESSAGE_ID));
		assertTrue(task.appendContinuation(SESSION, envelope("m"), MESSAGE_ID));
		assertEquals(1, task.revision());
		assertTrue(task.present());
		assertTrue(task.present());
		assertFalse(task.hasUnshownInput(), "only one continuation was queued");
	}

	@Test
	public void testAParkedTaskWaitsForSomethingNew() {
		queue();
		task.present();
		assertTrue(task.park(0));
		assertTrue(task.isParked());
		assertFalse(task.isReady(1000));

		assertTrue(task.setWakeTime(500));
		assertFalse(task.isReady(499));
		assertTrue(task.isReady(500), "a wake time that has come is new");
		assertTrue(task.setWakeTime(0));
		assertFalse(task.isReady(1000), "zero clears the wake time");

		task.appendContinuation(SESSION, envelope("m"), null);
		assertTrue(task.isReady(1000), "a continuation is new");
		assertTrue(task.present());
		assertFalse(task.isParked(), "running un-parks it");
	}

	@Test
	public void testParkingAtAStaleRevisionDoesNothing() {
		queue();
		task.appendContinuation(SESSION, envelope("m"), null);
		assertFalse(task.park(0));
		assertFalse(task.isParked());
	}

	@Test
	public void testTakeNeedsTheCurrentRevisionWithEveryInputShown() {
		queue();
		assertNull(tasks.take(TASK, 0), "its input has not been shown");
		task.present();
		task.appendContinuation(SESSION, envelope("m"), null);
		assertNull(tasks.take(TASK, 0), "the revision moved");
		assertNull(tasks.take(TASK, 1), "the continuation has not been shown");
		assertNotNull(task.getRecord());
		task.present();

		AMap<AString, ACell> row = task.getRecord();
		assertEquals(row, tasks.take(TASK, 1));
		assertNull(task.getRecord());
		assertNull(tasks.take(TASK, 1), "only one caller takes it");
	}

	@Test
	public void testTakeWithoutARevisionTakesRegardless() {
		queue();
		assertNotNull(tasks.take(TASK, -1));
		assertEquals(0, tasks.all().count());
	}

	@Test
	public void testATaskAddressesItselfAndItsFields() {
		queue();
		AString[] toInput = {Strings.create(TASK.toHexString()), Fields.INPUT};
		assertEquals(Strings.create("work"), tasks.resolve(toInput, 0, 2).cursor().get());
		assertEquals(task.getRecord(), tasks.resolve(toInput, 0, 1).cursor().get());
		assertSame(tasks, tasks.resolve(toInput, 0, 0));
	}

	@Test
	public void testAnIdThatIsNotHexNamesNothing() {
		AString[] path = {Strings.create("not-hex")};
		assertThrows(NoSuchPathException.class, () -> tasks.resolve(path, 0, 1));
	}

	@Test
	public void testAContinuationNeedsTheSessionToExist() {
		assertTrue(tasks.add(TASK, Maps.of(Fields.INPUT, "work", Fields.SESSION_ID, Strings.create(OTHER_SESSION.toHexString()))));
		assertFalse(agent.appendTaskContinuation(TASK, OTHER_SESSION, envelope("m"), null));
		assertEquals(0, task.revision());
	}
}
