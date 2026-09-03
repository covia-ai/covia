package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

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
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.AgentEvents.Event;

/**
 * The live agent tap (#394): the run loop, both harness hooks and the status
 * sites emit one ordered, correlated event per transition, delivered to
 * in-process subscribers — the same stream {@code GET /agents/{id}/sse}
 * relays (see {@code AgentSseTest}).
 */
public class AgentEventsTest {

	private final Engine engine = TestEngine.ENGINE;
	private AString did;
	private RequestContext ctx;

	@BeforeEach
	public void setup(TestInfo info) {
		did = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(did);
	}

	// ========== helpers ==========

	/** Collects one agent's events in delivery order. */
	private static final class Capture implements Consumer<Event> {
		final List<Event> events = new CopyOnWriteArrayList<>();

		@Override public void accept(Event e) { events.add(e); }

		List<String> types() {
			List<String> out = new ArrayList<>();
			for (Event e : events) out.add(e.type().toString());
			return out;
		}

		List<Event> all(AString type) {
			List<Event> out = new ArrayList<>();
			for (Event e : events) if (type.equals(e.type())) out.add(e);
			return out;
		}

		Event first(AString type) {
			List<Event> all = all(type);
			return all.isEmpty() ? null : all.get(0);
		}

		/** Waits for an event of the type carrying the given status (or any, when null). */
		void await(AString type, AString status, long timeoutMs) {
			TestEngine.awaitCondition(() -> {
				for (Event e : all(type)) {
					if (status == null || status.equals(RT.getIn(e.data(), Fields.STATUS))) return true;
				}
				return false;
			}, timeoutMs, () -> "no " + type + (status != null ? " " + status : "")
				+ " event within " + timeoutMs + "ms; saw " + types());
		}
	}

	private static long num(Event e, AString key) {
		CVMLong v = RT.ensureLong(RT.getIn(e.data(), key));
		assertNotNull(v, key + " missing on " + e.type() + ": " + e.data());
		return v.longValue();
	}

	private static AString str(Event e, AString key) {
		return RT.ensureString(RT.getIn(e.data(), key));
	}

	private void createAgent(String id, AMap<AString, ACell> config) {
		Job job = engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, id, Fields.CONFIG, config), ctx);
		job.awaitResult(5000);
	}

	private static void assertSeqStrictlyIncreasing(List<Event> events) {
		long last = 0;
		for (Event e : events) {
			assertTrue(e.seq() > last, "seq must increase: " + e.seq() + " after " + last);
			last = e.seq();
		}
	}

	// ========== run loop lifecycle ==========

	@Test
	public void testRunLoopLifecycleIsObservable() {
		String id = "lifecycle";
		createAgent(id, Maps.of(Fields.OPERATION, "v/test/ops/echo"));
		Capture c = new Capture();
		try (AgentEvents.Subscription s = engine.agentEvents().subscribe(did, Strings.create(id), c)) {
			engine.jobs().invokeOperation("v/ops/agent/trigger",
				Maps.of(Fields.AGENT_ID, id, Fields.WAIT, true), ctx).awaitResult(10_000);
			c.await(AgentEvents.RUN_END, null, 5_000);
		}

		assertEquals(List.of("status", "run:start", "cycle:start", "cycle:end", "status", "run:end"),
			c.types());
		assertSeqStrictlyIncreasing(c.events);
		assertEquals(1, c.events.get(0).seq(), "the first event of an agent is seq 1");

		assertEquals(AgentState.RUNNING, str(c.events.get(0), Fields.STATUS));
		assertEquals(AgentState.SLEEPING, str(c.events.get(4), Fields.STATUS));

		Event runStart = c.first(AgentEvents.RUN_START);
		assertEquals(1, num(runStart, Fields.RUN));

		Event cycleStart = c.first(AgentEvents.CYCLE_START);
		assertEquals(1, num(cycleStart, Fields.RUN));
		assertEquals(1, num(cycleStart, Fields.CYCLE));
		assertEquals(Strings.create("v/test/ops/echo"), str(cycleStart, Fields.OP));
		assertEquals(0, num(cycleStart, Fields.TASKS));
		assertEquals(0, num(cycleStart, Fields.MESSAGES));
		assertNull(RT.getIn(cycleStart.data(), Fields.SESSION_ID), "an unsessioned trigger has no session");

		Event cycleEnd = c.first(AgentEvents.CYCLE_END);
		assertEquals(1, num(cycleEnd, Fields.CYCLE));
		assertTrue(num(cycleEnd, Fields.MS) >= 0);
		assertEquals(0, num(cycleEnd, AgentState.KEY_TIMELINE), "the first timeline entry was written");

		Event runEnd = c.first(AgentEvents.RUN_END);
		assertEquals(AgentState.SLEEPING, str(runEnd, Fields.STATUS));
		assertEquals(1, num(runEnd, Fields.CYCLES));

		// Wire form: the envelope names the agent by id and grid address.
		AMap<AString, ACell> cell = runEnd.toCell();
		assertEquals(Strings.create(id), cell.get(Fields.AGENT_ID));
		assertEquals(Strings.create(did + "/g/" + id), cell.get(Fields.ADDRESS));
		assertEquals(AgentEvents.RUN_END, cell.get(Fields.TYPE));
		assertEquals(runEnd.seq(), RT.ensureLong(cell.get(Fields.SEQ)).longValue());
		assertEquals(runEnd.seq(), engine.agentEvents().lastSeq(did, Strings.create(id)));
	}

	// ========== harness activity ==========

	@Test
	public void testLLMCycleEmitsInferenceAndToolActivity() {
		String id = "tooler";
		createAgent(id, Maps.of(
			Fields.OPERATION, "v/ops/llmagent/chat",
			"llmOperation", "v/test/ops/toolllm",
			Fields.TOOLS, Vectors.of(Maps.of(
				Fields.OPERATION, "v/test/ops/echo",
				Fields.NAME, "v/test/ops/echo"))));
		Capture c = new Capture();
		Job chat;
		try (AgentEvents.Subscription s = engine.agentEvents().subscribe(did, Strings.create(id), c)) {
			chat = engine.jobs().invokeOperation("v/ops/agent/chat",
				Maps.of(Fields.AGENT_ID, id, Fields.MESSAGE, "hello events"), ctx);
			chat.awaitResult(10_000);
			c.await(AgentEvents.RUN_END, null, 5_000);
		}
		AString sid = RT.ensureString(RT.getIn(chat.getOutput(), Fields.SESSION_ID));
		AString response = RT.ensureString(RT.getIn(chat.getOutput(), Fields.RESPONSE));
		assertNotNull(sid);
		assertTrue(response.toString().startsWith("Tool returned:"), response.toString());

		// The mock LLM requests one echo call, then answers from its result:
		// two inferences around one tool call, inside one cycle.
		assertEquals(List.of(
				"status", "run:start", "cycle:start",
				"inference:start", "inference:end",
				"tool:start", "tool:result",
				"inference:start", "inference:end",
				"cycle:end", "status", "run:end"),
			c.types());
		assertSeqStrictlyIncreasing(c.events);

		// Every cycle-scoped event is correlated to the run, the cycle and the session.
		AString chatJobId = Strings.create(chat.getID().toHexString());
		for (Event e : c.events) {
			if (e.type().equals(AgentEvents.STATUS) || e.type().toString().startsWith("run:")) continue;
			assertEquals(1, num(e, Fields.RUN), e.type().toString());
			assertEquals(1, num(e, Fields.CYCLE), e.type().toString());
			assertEquals(sid, str(e, Fields.SESSION_ID), e.type().toString());
			assertNull(RT.getIn(e.data(), Fields.JOB_ID), "a chat cycle has no task job");
			assertNull(RT.getIn(e.data(), Fields.DEPTH), "root-frame activity carries no depth");
		}

		Event cycleStart = c.first(AgentEvents.CYCLE_START);
		assertEquals(1, num(cycleStart, Fields.MESSAGES));
		assertEquals(Vectors.of(chatJobId), RT.getIn(cycleStart.data(), Fields.JOBS),
			"the chat job presented this cycle is named");
		assertEquals(Strings.create("v/ops/llmagent/chat"), str(cycleStart, Fields.OP));

		List<Event> starts = c.all(AgentEvents.INFERENCE_START);
		assertEquals(Strings.create("v/test/ops/toolllm"), str(starts.get(0), Fields.OP));
		assertTrue(num(starts.get(0), Fields.MESSAGES) >= 1);
		assertTrue(num(starts.get(0), Fields.BYTES) > 0);
		assertTrue(num(starts.get(0), Fields.BUDGET) > 0);
		assertTrue(num(starts.get(1), Fields.MESSAGES) > num(starts.get(0), Fields.MESSAGES),
			"the second inference sends the tool exchange too");

		List<Event> ends = c.all(AgentEvents.INFERENCE_END);
		AVector<ACell> requested = RT.ensureVector(RT.getIn(ends.get(0).data(), Fields.TOOL_CALLS));
		assertNotNull(requested, "the first reply requested a tool call");
		assertEquals(1, requested.count());
		assertEquals(Strings.create("call_1"), RT.getIn(requested.get(0), Fields.ID));
		assertEquals(Strings.create("v/test/ops/echo"), RT.getIn(requested.get(0), Fields.NAME));
		assertNull(RT.getIn(requested.get(0), "arguments"), "arguments ride on tool:start, not the reply summary");
		assertTrue(num(ends.get(0), Fields.MS) >= 0);
		assertNotNull(RT.getIn(ends.get(0).data(), Fields.TOKENS), "mock usage is reported");
		assertEquals(response, str(ends.get(1), Fields.CONTENT), "the final reply's text is the response");
		assertNull(RT.getIn(ends.get(1).data(), Fields.TOOL_CALLS));

		Event toolStart = c.first(AgentEvents.TOOL_START);
		assertEquals(Strings.create("call_1"), str(toolStart, Fields.ID));
		assertEquals(Strings.create("v/test/ops/echo"), str(toolStart, Fields.NAME));
		assertEquals(Strings.create("Echo Operation"), str(toolStart, Fields.ACTIVITY_LABEL),
			"the operation asset name is the default activity label");
		assertEquals(Strings.create("hello events"),
			RT.getIn(toolStart.data(), Fields.DETAIL, Fields.INPUT, "echo"),
			"the decoded input rides under detail");

		Event toolResult = c.first(AgentEvents.TOOL_RESULT);
		assertEquals(Strings.create("call_1"), str(toolResult, Fields.ID));
		assertTrue(num(toolResult, Fields.MS) >= 0);
		assertNull(RT.getIn(toolResult.data(), Fields.IS_ERROR));
		assertNotNull(RT.getIn(toolResult.data(), Fields.DETAIL, Fields.RESULT), "the result rides under detail");

		// The display-safe projection keeps names and timing, drops the payloads.
		Event safe = toolStart.withoutDetail();
		assertNull(safe.data().get(Fields.DETAIL));
		assertEquals(str(toolStart, Fields.NAME), str(safe, Fields.NAME));
		assertEquals(str(toolStart, Fields.ACTIVITY_LABEL), str(safe, Fields.ACTIVITY_LABEL));
		assertEquals(toolStart.seq(), safe.seq());
		assertTrue(cycleStart.withoutDetail() == cycleStart, "no detail: same event");

		Event cycleEnd = c.first(AgentEvents.CYCLE_END);
		assertEquals(response, str(cycleEnd, Fields.RESPONSE));
		assertNotNull(RT.getIn(cycleEnd.data(), Fields.TOKENS));
		assertEquals(0, num(cycleEnd, AgentState.KEY_TIMELINE));
		assertNull(cycleEnd.data().get(Fields.DETAIL),
			"frame-owning runtimes do not duplicate already-persisted turns in cycle:end");
		AgentState agent = engine.getVenueState().users().get(did).agent(id);
		AVector<ACell> turns = RT.ensureVector(RT.getIn(
			agent.getSession(Blob.fromHex(sid.toString())), Fields.FRAMES,
			CVMLong.ZERO, AgentState.KEY_CONVERSATION));
		assertTrue(turns.count() >= 4,
			"canonical session has user, tool-call reply, tool result and response: " + turns);
		assertNull(RT.getIn(cycleEnd.data(), Fields.ERROR));
	}

	@Test
	public void testTransitionOutsideRunLoopEmitsNothing() {
		// A direct harness invocation carries no cycle handle, so nothing is
		// attributed to the agent — the tap is a run-loop surface.
		Capture c = new Capture();
		try (AgentEvents.Subscription s = engine.agentEvents().subscribe(c)) {
			ACell out = engine.jobs().invokeOperation("v/ops/llmagent/chat", Maps.of(
				Fields.AGENT_ID, "direct-agent",
				AgentState.KEY_CONFIG, Maps.of("llmOperation", "v/test/ops/toolllm"),
				Fields.MESSAGES, Vectors.of(Maps.of("content", "no tap"))),
				ctx.withAgentId(Strings.create("direct-agent"))).awaitResult(10_000);
			assertNotNull(RT.getIn(out, Fields.RESPONSE));
		}
		for (Event e : c.events) {
			assertFalse(Strings.create("direct-agent").equals(e.agentId()),
				"direct invocation must not emit: " + e);
		}
	}

	// ========== status sites ==========

	@Test
	public void testAdministrativeStatusChangesAreAnnounced() {
		String id = "admin";
		createAgent(id, Maps.of(Fields.OPERATION, "v/test/ops/echo"));
		Capture c = new Capture();
		try (AgentEvents.Subscription s = engine.agentEvents().subscribe(did, Strings.create(id), c)) {
			engine.jobs().invokeOperation("v/ops/agent/suspend",
				Maps.of(Fields.AGENT_ID, id), ctx).awaitResult(5_000);
			engine.jobs().invokeOperation("v/ops/agent/resume",
				Maps.of(Fields.AGENT_ID, id, Fields.AUTO_WAKE, false), ctx).awaitResult(5_000);
			engine.jobs().invokeOperation("v/ops/agent/delete",
				Maps.of(Fields.AGENT_ID, id), ctx).awaitResult(5_000);
		}
		assertEquals(List.of("status", "status", "status"), c.types());
		assertEquals(AgentState.SUSPENDED, str(c.events.get(0), Fields.STATUS));
		assertEquals(AgentState.SLEEPING, str(c.events.get(1), Fields.STATUS));
		assertEquals(AgentState.TERMINATED, str(c.events.get(2), Fields.STATUS));
		assertFalse(c.events.get(0).isTerminal());
		assertTrue(c.events.get(2).isTerminal(), "TERMINATED ends the stream");
	}

	@Test
	public void testTransitionFailureSuspendsWithTheError() {
		String id = "failing";
		createAgent(id, Maps.of(Fields.OPERATION, "v/test/ops/error"));
		Capture c = new Capture();
		try (AgentEvents.Subscription s = engine.agentEvents().subscribe(did, Strings.create(id), c)) {
			engine.jobs().invokeOperation("v/ops/agent/trigger",
				Maps.of(Fields.AGENT_ID, id, Fields.WAIT, true), ctx).awaitResult(10_000);
			c.await(AgentEvents.RUN_END, null, 5_000);
		}
		Event cycleEnd = c.first(AgentEvents.CYCLE_END);
		assertNotNull(cycleEnd);
		assertNotNull(RT.getIn(cycleEnd.data(), Fields.ERROR), "a failed cycle ends with its error");
		assertNull(RT.getIn(cycleEnd.data(), Fields.RESPONSE));
		c.await(AgentEvents.STATUS, AgentState.SUSPENDED, 1_000);
		Event suspended = null;
		for (Event e : c.all(AgentEvents.STATUS)) {
			if (AgentState.SUSPENDED.equals(str(e, Fields.STATUS))) suspended = e;
		}
		assertNotNull(RT.getIn(suspended.data(), Fields.ERROR), "the suspension carries the error");
		assertEquals(AgentState.SUSPENDED, str(c.first(AgentEvents.RUN_END), Fields.STATUS));
	}

	// ========== subscriptions ==========

	@Test
	public void testSubscriptionScopeAndIsolation() {
		String id = "scoped";
		AString other = Strings.create("someone-else");
		createAgent(id, Maps.of(Fields.OPERATION, "v/test/ops/echo"));
		Capture mine = new Capture();
		Capture global = new Capture();
		Capture elsewhere = new Capture();
		int[] thrown = {0};
		Consumer<Event> throwing = e -> { thrown[0]++; throw new IllegalStateException("listener bug"); };

		AgentEvents.Subscription s1 = engine.agentEvents().subscribe(did, Strings.create(id), throwing);
		AgentEvents.Subscription s2 = engine.agentEvents().subscribe(did, Strings.create(id), mine);
		AgentEvents.Subscription s3 = engine.agentEvents().subscribe(global);
		AgentEvents.Subscription s4 = engine.agentEvents().subscribe(other, Strings.create(id), elsewhere);
		try {
			engine.jobs().invokeOperation("v/ops/agent/trigger",
				Maps.of(Fields.AGENT_ID, id, Fields.WAIT, true), ctx).awaitResult(10_000);
			mine.await(AgentEvents.RUN_END, null, 5_000);
		} finally {
			s1.close(); s2.close(); s3.close(); s4.close();
		}
		assertTrue(thrown[0] > 0, "the throwing listener was invoked");
		assertEquals(6, mine.events.size(), "a throwing listener does not starve the others: " + mine.types());
		assertTrue(elsewhere.events.isEmpty(), "another owner's agent of the same id sees nothing");
		long seen = 0;
		for (Event e : global.events) if (e.agentId().equals(Strings.create(id)) && e.ownerDID().equals(did)) seen++;
		assertEquals(6, seen, "the venue-wide subscription sees the agent's events");

		// Closed subscriptions receive nothing more.
		int before = mine.events.size();
		engine.jobs().invokeOperation("v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, id, Fields.WAIT, true), ctx).awaitResult(10_000);
		assertEquals(before, mine.events.size());
		assertTrue(engine.agentEvents().lastSeq(did, Strings.create(id)) >= 12, "the sequence keeps counting");
	}

	@Test
	public void testEventEnvelopeAndDetailProjection() {
		AString owner = Strings.create("did:key:z6Mk-test-owner");
		AMap<AString, ACell> detail = Maps.of(Fields.INPUT, Maps.of("secret", "value"));
		Event e = new Event(owner, Strings.create("a"), 7, 123L, AgentEvents.TOOL_START,
			Maps.of(Fields.NAME, Strings.create("op"), Fields.DETAIL, detail));
		assertEquals(Strings.create(owner + "/g/a"), e.address());
		AMap<AString, ACell> cell = e.toCell();
		assertEquals(CVMLong.create(7), cell.get(Fields.SEQ));
		assertEquals(CVMLong.create(123), cell.get(Fields.TS));
		assertEquals(detail, cell.get(Fields.DETAIL));
		assertNull(e.withoutDetail().toCell().get(Fields.DETAIL));
		assertFalse(e.isTerminal());
		Event terminated = new Event(owner, Strings.create("a"), 8, 124L, AgentEvents.STATUS,
			Maps.of(Fields.STATUS, AgentState.TERMINATED));
		assertTrue(terminated.isTerminal());
		assertEquals(CVMBool.TRUE, CVMBool.create(terminated.isTerminal()));
	}

	// ========== session-scoped subscription ==========

	@Test
	public void testSessionScopedSubscriptionSeesOnlyItsSession() {
		String id = "sessions";
		createAgent(id, Maps.of(
			Fields.OPERATION, "v/ops/llmagent/chat",
			"llmOperation", "v/test/ops/llm"));
		// Mint session A first, so its id is known before subscribing.
		Job mint = engine.jobs().invokeOperation("v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, id, Fields.MESSAGE, "mint A"), ctx);
		mint.awaitResult(10_000);
		AString sessionA = RT.ensureString(RT.getIn(mint.getOutput(), Fields.SESSION_ID));
		assertNotNull(sessionA);

		Capture whole = new Capture();
		Capture scoped = new Capture();
		Job other;
		try (AgentEvents.Subscription s1 = engine.agentEvents().subscribe(did, Strings.create(id), whole);
			 AgentEvents.Subscription s2 = engine.agentEvents().subscribe(did, Strings.create(id), sessionA, scoped)) {
			// One more turn on A, then a chat that mints session B.
			engine.jobs().invokeOperation("v/ops/agent/chat",
				Maps.of(Fields.AGENT_ID, id, Fields.SESSION_ID, sessionA, Fields.MESSAGE, "again A"), ctx)
				.awaitResult(10_000);
			other = engine.jobs().invokeOperation("v/ops/agent/chat",
				Maps.of(Fields.AGENT_ID, id, Fields.MESSAGE, "mint B"), ctx);
			other.awaitResult(10_000);
			// Both cycles committed and the last run has exited.
			TestEngine.awaitCondition(() -> whole.all(AgentEvents.CYCLE_END).size() >= 2
				&& AgentEvents.RUN_END.equals(whole.events.get(whole.events.size() - 1).type()),
				5_000, () -> "two cycles then a run end: " + whole.types());
		}
		AString sessionB = RT.ensureString(RT.getIn(other.getOutput(), Fields.SESSION_ID));
		assertNotEquals(sessionA, sessionB, "the second chat minted its own session");

		assertEquals(2, whole.all(AgentEvents.CYCLE_START).size(), "the whole-agent view saw both cycles");
		assertEquals(1, scoped.all(AgentEvents.CYCLE_START).size(), "the session view saw only its own");
		assertTrue(scoped.all(AgentEvents.RUN_START).isEmpty() && scoped.all(AgentEvents.RUN_END).isEmpty(),
			"run boundaries are not a session's concern: " + scoped.types());
		assertFalse(scoped.all(AgentEvents.STATUS).isEmpty(), "status events reach a session view");
		for (Event e : scoped.events) {
			if (AgentEvents.STATUS.equals(e.type())) continue;
			assertEquals(sessionA, str(e, Fields.SESSION_ID), e.type().toString());
		}
		assertSeqStrictlyIncreasing(scoped.events);

		// The rule itself, on the wire form a session view sees.
		Event status = scoped.first(AgentEvents.STATUS);
		assertTrue(status.concerns(sessionB), "status concerns every session");
		Event cycle = scoped.first(AgentEvents.CYCLE_START);
		assertTrue(cycle.concerns(sessionA) && !cycle.concerns(sessionB) && cycle.concerns(null));
	}
}
