package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;

/**
 * Sharing the owner's namespace is proximity, not permission: a capability-scoped
 * agent lists, cancels and triggers the scheduled events it created, never every
 * event of its owner. Triggering fires with the event's captured authority, so
 * reaching the owner's events would let an agent run work beyond its own caps.
 */
public class ScheduledEventAccessTest {

	private static final AString INVOKE = Strings.intern("invoke");
	private static final AString HANDLE = Strings.intern("handle");
	private static final AString EVENTS = Strings.intern("events");
	private static final long HOUR = 3_600_000L;

	final Engine engine = TestEngine.ENGINE;
	AString alice, bob;
	RequestContext ALICE, BOB;

	@BeforeEach
	void setup() {
		alice = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		bob = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		ALICE = RequestContext.of(alice);
		BOB = RequestContext.of(bob);
	}

	private Job run(String op, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		try { job.awaitResult(10000); } catch (Exception ignored) {}
		return job;
	}

	private RequestContext agent(String id, AVector<ACell> caps) {
		return RequestContext.ofAgent(alice, Strings.create(id)).withCaps(caps);
	}

	private static AVector<ACell> invokeOnly() {
		return Vectors.of(Maps.of(Capability.CAN, INVOKE));
	}

	private ACell schedule(String op, ACell input, RequestContext ctx) {
		Job job = run("v/ops/scheduler/schedule", Maps.of(
			"operation", op, "input", input, "after", CVMLong.create(HOUR)), ctx);
		assertEquals(Status.COMPLETE, job.getStatus(), job.getErrorMessage());
		return RT.getIn(job.getOutput(), HANDLE);
	}

	private Job trigger(ACell handle, RequestContext ctx) {
		return run("v/ops/scheduler/trigger", Maps.of(HANDLE, handle), ctx);
	}

	private Job cancel(ACell handle, RequestContext ctx) {
		return run("v/ops/scheduler/cancel", Maps.of(HANDLE, handle), ctx);
	}

	private AVector<ACell> listedHandles(RequestContext ctx) {
		Job job = run("v/ops/scheduler/list", Maps.empty(), ctx);
		assertEquals(Status.COMPLETE, job.getStatus(), job.getErrorMessage());
		AVector<ACell> handles = Vectors.empty();
		for (ACell event : RT.ensureVector(RT.getIn(job.getOutput(), EVENTS))) {
			handles = handles.conj(RT.getIn(event, HANDLE));
		}
		return handles;
	}

	private static void assertDenied(Job job, String reason) {
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains(reason), job.getErrorMessage());
	}

	@Test
	public void testScopedAgentCannotTriggerOwnersEventToReadPastItsCaps() {
		run("v/ops/covia/write", Maps.of(Fields.PATH, "w/private", Fields.VALUE, "OWNER-ONLY"), ALICE);
		ACell handle = schedule("v/ops/covia/read", Maps.of(Fields.PATH, "w/private"), ALICE);
		RequestContext worker = agent("worker", invokeOnly());
		try {
			assertNotEquals(Status.COMPLETE, run("v/ops/covia/read",
				Maps.of(Fields.PATH, "w/private"), worker).getStatus(), "the agent cannot read it directly");

			assertDenied(trigger(handle, worker), Scheduler.EVENT_ACCESS_DENIED);
			assertTrue(listedHandles(ALICE).contains(handle), "a refused trigger leaves the event scheduled");
		} finally {
			cancel(handle, ALICE);
		}
	}

	@Test
	public void testScopedAgentCannotCancelOwnersEvent() {
		ACell handle = schedule("v/test/ops/echo", Maps.of("echo", "owner"), ALICE);
		try {
			assertDenied(cancel(handle, agent("worker", invokeOnly())), Scheduler.EVENT_ACCESS_DENIED);
			assertDenied(cancel(handle, BOB), ScheduleState.NOT_OWNER);
			assertTrue(listedHandles(ALICE).contains(handle));
		} finally {
			cancel(handle, ALICE);
		}
	}

	@Test
	public void testScopedAgentListsOnlyEventsItCreated() {
		RequestContext worker = agent("worker", invokeOnly());
		ACell owners = schedule("v/test/ops/echo", Maps.of("echo", "owner"), ALICE);
		ACell agents = schedule("v/test/ops/echo", Maps.of("echo", "agent"), worker);
		try {
			assertEquals(Vectors.of(agents), listedHandles(worker));
			AVector<ACell> ownerView = listedHandles(ALICE);
			assertTrue(ownerView.contains(owners) && ownerView.contains(agents),
				"the owner keeps sight of what its agents have queued");
		} finally {
			cancel(owners, ALICE);
			cancel(agents, ALICE);
		}
	}

	@Test
	public void testSiblingAgentCannotReachAnotherAgentsEvent() {
		RequestContext worker = agent("worker", invokeOnly());
		RequestContext sibling = agent("sibling", invokeOnly());
		ACell handle = schedule("v/test/ops/echo", Maps.of("echo", "worker"), worker);
		try {
			assertEquals(Vectors.empty(), listedHandles(sibling));
			assertDenied(trigger(handle, sibling), Scheduler.EVENT_ACCESS_DENIED);
			assertDenied(cancel(handle, sibling), Scheduler.EVENT_ACCESS_DENIED);
		} finally {
			cancel(handle, ALICE);
		}
	}

	@Test
	public void testScopedAgentManagesEventsItCreated() {
		RequestContext worker = agent("worker", invokeOnly());
		ACell fired = schedule("v/test/ops/echo", Maps.of("echo", "mine"), worker);
		Job result = trigger(fired, worker);
		assertEquals(Status.COMPLETE, result.getStatus(), result.getErrorMessage());
		assertEquals(Strings.create("mine"), RT.getIn(result.getOutput(), "result", "echo"));

		ACell dropped = schedule("v/test/ops/echo", Maps.of("echo", "later"), worker);
		Job cancelled = cancel(dropped, worker);
		assertEquals(Status.COMPLETE, cancelled.getStatus(), cancelled.getErrorMessage());
		assertEquals(CVMBool.TRUE, RT.getIn(cancelled.getOutput(), "cancelled"));
	}

	@Test
	public void testAgentWithoutCapsKeepsOwnersReach() {
		RequestContext helper = RequestContext.ofAgent(alice, Strings.create("helper"));
		ACell handle = schedule("v/test/ops/echo", Maps.of("echo", "owner"), ALICE);
		assertTrue(listedHandles(helper).contains(handle));
		Job cancelled = cancel(handle, helper);
		assertEquals(Status.COMPLETE, cancelled.getStatus(), cancelled.getErrorMessage());
		assertEquals(CVMBool.TRUE, RT.getIn(cancelled.getOutput(), "cancelled"));
	}
}
