package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;

/**
 * A scheduled event is a capability resource, {@code <account>/x/schedule/<id>}:
 * {@code crud/read} lists it and {@code schedule/manage} cancels or triggers it.
 * Having scheduled an event grants nothing, so a capped agent needs these caps
 * even for the events it scheduled. Triggering fires with the event's caller's
 * authority, so reaching an event lets a principal run that work.
 */
public class ScheduledEventAccessTest {

	private static final AString INVOKE = Strings.intern("invoke");
	private static final AString HANDLE = Strings.intern("handle");
	private static final AString EVENTS = Strings.intern("events");
	private static final long HOUR = 3_600_000L;
	private static final AtomicInteger TOKENS = new AtomicInteger();

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

	/** A real agent of alice's, acting as it does now: nothing fires as an agent that does not exist. */
	private RequestContext agent(String id, AVector<ACell> caps) {
		Job created = run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id,
			Fields.CONFIG, Maps.of(Config.CAPS, caps)), ALICE);
		assertEquals(Status.COMPLETE, created.getStatus(), created.getErrorMessage());
		return engine.context(alice, covia.grid.Principals.agentDID(alice, Strings.create(id)));
	}

	private static AVector<ACell> invokeOnly() {
		return Vectors.of(Maps.of(Capability.CAN, INVOKE));
	}

	/** Invoking anything, with {@code abilities} on every event of the account. */
	private static AVector<ACell> onEvents(AString... abilities) {
		AVector<ACell> caps = invokeOnly();
		for (AString ability : abilities) caps = caps.conj(Capability.create(Strings.create(Scheduler.RESOURCE_PATH), ability));
		return caps;
	}

	/** A request through an access token of alice's with {@code caps}. */
	private RequestContext token(AVector<ACell> caps) {
		Job made = run("v/ops/token/create", Maps.of(Fields.NAME, "events-" + TOKENS.incrementAndGet(),
			Fields.ACCESS, Maps.of("test", Maps.empty()), Fields.CAPS, caps), ALICE);
		assertEquals(Status.COMPLETE, made.getStatus(), made.getErrorMessage());
		return engine.tokenContext(engine.tokens().lookup(RT.getIn(made.getOutput(), Fields.TOKEN).toString()));
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

	private AVector<ACell> listed(RequestContext ctx) {
		Job job = run("v/ops/scheduler/list", Maps.empty(), ctx);
		assertEquals(Status.COMPLETE, job.getStatus(), job.getErrorMessage());
		return RT.ensureVector(RT.getIn(job.getOutput(), EVENTS));
	}

	private AVector<ACell> listedHandles(RequestContext ctx) {
		AVector<ACell> handles = Vectors.empty();
		for (ACell event : listed(ctx)) handles = handles.conj(RT.getIn(event, HANDLE));
		return handles;
	}

	/** The refusal for {@code ability} on alice's event at {@code handle}. */
	private String denial(ACell handle, AString ability) {
		return Scheduler.EVENT_ACCESS_DENIED.formatted(ability, Scheduler.resource(alice, Job.parseID(handle)));
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

			assertDenied(trigger(handle, worker), denial(handle, Abilities.SCHEDULE_MANAGE));
			assertTrue(listedHandles(ALICE).contains(handle), "a refused trigger leaves the event scheduled");
		} finally {
			cancel(handle, ALICE);
		}
	}

	@Test
	public void testNeitherAReaderNorAnotherUserCancels() {
		ACell handle = schedule("v/test/ops/echo", Maps.of("echo", "owner"), ALICE);
		try {
			assertDenied(cancel(handle, agent("reader", onEvents(Capability.CRUD_READ))), denial(handle, Abilities.SCHEDULE_MANAGE));
			assertDenied(cancel(handle, BOB), denial(handle, Abilities.SCHEDULE_MANAGE));
			assertTrue(listedHandles(ALICE).contains(handle));
		} finally {
			cancel(handle, ALICE);
		}
	}

	@Test
	public void testSchedulingAnEventGrantsNothing() {
		RequestContext worker = agent("worker", invokeOnly());
		ACell handle = schedule("v/test/ops/echo", Maps.of("echo", "worker"), worker);
		try {
			assertEquals(Vectors.empty(), listedHandles(worker), "its caps cover reading no event, its own included");
			assertDenied(trigger(handle, worker), denial(handle, Abilities.SCHEDULE_MANAGE));
			assertDenied(cancel(handle, worker), denial(handle, Abilities.SCHEDULE_MANAGE));
			assertTrue(listedHandles(ALICE).contains(handle), "the owner keeps sight of what its agents have queued");
		} finally {
			cancel(handle, ALICE);
		}
	}

	@Test
	public void testCapsOnTheAccountsEventsReachThemAll() {
		ACell owners = schedule("v/test/ops/echo", Maps.of("echo", "owner"), ALICE);
		RequestContext reader = agent("reader", onEvents(Capability.CRUD_READ));
		RequestContext manager = agent("manager", onEvents(Capability.CRUD_READ, Abilities.SCHEDULE_MANAGE));
		ACell managers = schedule("v/test/ops/echo", Maps.of("echo", "mine"), manager);
		try {
			AVector<ACell> handles = listedHandles(reader);
			assertTrue(handles.contains(owners) && handles.contains(managers), "reading covers every event of the account");
			assertEquals(Vectors.empty(), listedHandles(BOB), "never another account's");

			Job result = trigger(managers, manager);
			assertEquals(Status.COMPLETE, result.getStatus(), result.getErrorMessage());
			assertEquals(Strings.create("mine"), RT.getIn(result.getOutput(), "result", "echo"));
			Job cancelled = cancel(owners, manager);
			assertEquals(Status.COMPLETE, cancelled.getStatus(), cancelled.getErrorMessage());
			assertEquals(CVMBool.TRUE, RT.getIn(cancelled.getOutput(), "cancelled"), "the owner's events too");
		} finally {
			cancel(owners, ALICE);
		}
	}

	@Test
	public void testAnEventIsNamedByTheIdItIsListedWith() {
		ACell handle = schedule("v/test/ops/echo", Maps.of("echo", "one"), ALICE);
		ACell other = schedule("v/test/ops/echo", Maps.of("echo", "two"), ALICE);
		try {
			AMap<AString, ACell> event = RT.ensureMap(listed(ALICE).get(0));
			ACell id = event.get(Fields.ID);
			AString resource = Strings.create(alice + "/" + Scheduler.RESOURCE_PATH + ((Blob) id).toHexString());
			RequestContext exact = agent("exact", Vectors.of(Maps.of(Capability.CAN, INVOKE),
				Capability.create(resource, Capability.CRUD_READ)));
			assertEquals(Vectors.of(event.get(HANDLE)), listedHandles(exact), "a cap on one event's id reaches that event alone");
		} finally {
			cancel(handle, ALICE);
			cancel(other, ALICE);
		}
	}

	@Test
	public void testATokenManagesEventsOnlyWithTheAbility() {
		ACell handle = schedule("v/test/ops/echo", Maps.of("echo", "owner"), ALICE);
		try {
			assertDenied(cancel(handle, token(onEvents(Capability.CRUD_READ))), denial(handle, Abilities.SCHEDULE_MANAGE));
			Job cancelled = cancel(handle, token(onEvents(Abilities.SCHEDULE_MANAGE)));
			assertEquals(Status.COMPLETE, cancelled.getStatus(), cancelled.getErrorMessage());
			assertEquals(CVMBool.TRUE, RT.getIn(cancelled.getOutput(), "cancelled"));
		} finally {
			cancel(handle, ALICE);
		}
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
