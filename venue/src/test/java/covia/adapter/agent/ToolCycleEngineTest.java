package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.TestAdapter;
import covia.api.Fields;
import covia.exception.NotSupportedException;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * The tool batch protocol under concurrency: adjacent operation calls run at
 * the same time, harness tools are ordered barriers, results keep call order,
 * and the terminal fence still holds. Unit tests drive
 * {@link ToolCycleEngine#executeBatch} with a synthetic registry; one test
 * runs a real batch through {@code agent:step}.
 */
public class ToolCycleEngineTest {

	private static final Logger log = LoggerFactory.getLogger(ToolCycleEngineTest.class);
	private static final String COMPLETE = "complete";
	private static final String CONTROL = "context_load";

	private final Engine engine = TestEngine.ENGINE;
	private AString did;

	@BeforeEach
	public void setup(TestInfo info) {
		did = TestEngine.uniqueDID(info);
	}

	// ========== helpers ==========

	/** A recording sink: appended messages in order, plus recorded call ids. */
	private static final class Sink implements ToolCycleEngine.BatchSink {
		final List<AMap<AString, ACell>> messages = new ArrayList<>();
		final List<String> recorded = new ArrayList<>();
		@Override public void append(AMap<AString, ACell> message) { messages.add(message); }
		@Override public void recordCall(ToolCycleEngine.ToolCall call, ToolCycleEngine.ToolOutcome outcome, long millis) {
			recorded.add(call.id().toString());
		}
	}

	private static AMap<AString, ACell> call(String id, String name) {
		return Maps.of(AbstractLLMAdapter.K_ID, Strings.create(id),
			AbstractLLMAdapter.K_NAME, Strings.create(name),
			AbstractLLMAdapter.K_ARGUMENTS, Maps.of(Strings.create("id"), Strings.create(id)));
	}

	private static String id(AMap<AString, ACell> message) {
		return RT.ensureString(message.get(AbstractLLMAdapter.K_ID)).toString();
	}

	private static String content(AMap<AString, ACell> message) {
		ACell c = message.get(AbstractLLMAdapter.K_CONTENT);
		if (c == null) c = RT.getIn(message, "structuredContent");
		return (c != null) ? c.toString() : null;
	}

	private static ToolCycleEngine.ToolOutcome ok(String text) {
		return ToolCycleEngine.ToolOutcome.result(Strings.create(text));
	}

	// ========== unit: the wave model ==========

	@Test
	public void testAdjacentOperationCallsRunConcurrently() throws Exception {
		// Three operation calls each wait at a barrier for the other two. Run
		// one at a time, nobody ever arrives and every call times out; run
		// together, all three pass — the concurrency is the assertion.
		CyclicBarrier barrier = new CyclicBarrier(3);
		ToolCycleEngine.Registry<Object> registry = new ToolCycleEngine.Registry<Object>()
			.fallback((c, ctx) -> {
				barrier.await(5, TimeUnit.SECONDS);
				return ok("ran " + c.id());
			});
		Sink sink = new Sink();
		ToolCycleEngine.BatchResult batch = ToolCycleEngine.executeBatch(
			Vectors.of(call("a", "op"), call("b", "op"), call("c", "op")), 0, registry, new Object(), sink, engine::currentTimestamp, log);

		assertFalse(batch.isTerminal());
		assertFalse(batch.aborted());
		assertEquals(List.of("a", "b", "c"), sink.messages.stream().map(ToolCycleEngineTest::id).toList());
		for (AMap<AString, ACell> m : sink.messages) {
			assertEquals("ran " + id(m), content(m), "every call passed the barrier");
		}
	}

	@Test
	public void testResultsKeepCallOrderWhateverFinishesFirst() throws Exception {
		// Call a cannot finish until b has: b completes first, yet the
		// provider sees results in call order, each paired with its id.
		CountDownLatch bDone = new CountDownLatch(1);
		ToolCycleEngine.Registry<Object> registry = new ToolCycleEngine.Registry<Object>()
			.fallback((c, ctx) -> {
				if ("a".equals(c.id().toString())) {
					assertTrue(bDone.await(5, TimeUnit.SECONDS), "b must be able to finish while a waits");
					return ok("a after b");
				}
				bDone.countDown();
				return ok("b first");
			});
		Sink sink = new Sink();
		ToolCycleEngine.executeBatch(Vectors.of(call("a", "op"), call("b", "op")), 0, registry, new Object(), sink, engine::currentTimestamp, log);

		assertEquals(List.of("a", "b"), sink.messages.stream().map(ToolCycleEngineTest::id).toList());
		assertEquals("a after b", content(sink.messages.get(0)));
		assertEquals("b first", content(sink.messages.get(1)));
		assertEquals(List.of("a", "b"), sink.recorded, "cycle recording follows call order too");
	}

	@Test
	public void testHarnessToolIsAnOrderedBarrier() throws Exception {
		// [op1, op2, control, op3]: the operation wave before the control tool
		// finishes before it runs, and the one after it does not start until
		// it has returned — the control tool keeps its position. The test holds
		// the first wave open with both calls in flight, so a control tool that
		// did not wait for it would run while it is held.
		ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
		CountDownLatch waveStarted = new CountDownLatch(2);
		CountDownLatch releaseWave = new CountDownLatch(1);
		CompletableFuture<Thread> caller = new CompletableFuture<>();
		ToolCycleEngine.Registry<Object> registry = new ToolCycleEngine.Registry<Object>()
			.register(CONTROL, (c, ctx) -> {
				assertSame(caller.getNow(null), Thread.currentThread(), "harness tools run on the calling thread");
				events.add("control");
				return ToolCycleEngine.ToolOutcome.result(Strings.create("loaded"), Vectors.of(
					Maps.of(AbstractLLMAdapter.K_ROLE, AbstractLLMAdapter.ROLE_SYSTEM,
						AbstractLLMAdapter.K_CONTENT, Strings.create("loaded context event"))));
			})
			.fallback((c, ctx) -> {
				events.add(c.id() + ":start");
				if (!"3".equals(c.id().toString())) {
					waveStarted.countDown();
					assertTrue(releaseWave.await(10, TimeUnit.SECONDS), "the test releases the first wave");
				}
				events.add(c.id() + ":end");
				return ok("ran");
			});
		Sink sink = new Sink();
		CompletableFuture<ToolCycleEngine.BatchResult> batch = new CompletableFuture<>();
		Thread.ofVirtual().start(() -> {
			caller.complete(Thread.currentThread());
			try {
				batch.complete(ToolCycleEngine.executeBatch(
					Vectors.of(call("1", "op"), call("2", "op"), call("ctl", CONTROL), call("3", "op")),
					0, registry, new Object(), sink, engine::currentTimestamp, log));
			} catch (Throwable t) {
				batch.completeExceptionally(t);
			}
		});
		try {
			assertTrue(waveStarted.await(10, TimeUnit.SECONDS), "both calls of the first wave are in flight");
			assertFalse(events.contains("control"), "the control tool waits for the wave: " + events);
		} finally {
			releaseWave.countDown();
		}
		batch.get(10, TimeUnit.SECONDS);

		List<String> seq = new ArrayList<>(events);
		int control = seq.indexOf("control");
		assertTrue(seq.indexOf("1:end") < control && seq.indexOf("2:end") < control,
			"the wave before the barrier completes first: " + seq);
		assertTrue(control < seq.indexOf("3:start"), "the wave after waits: " + seq);
		assertEquals(5, sink.messages.size());
		assertEquals(List.of("1", "2", "ctl", "3"),
			sink.messages.subList(0, 4).stream().map(ToolCycleEngineTest::id).toList(),
			"every result in the assistant batch precedes load events");
		assertEquals(AbstractLLMAdapter.ROLE_SYSTEM,
			sink.messages.get(4).get(AbstractLLMAdapter.K_ROLE));
	}

	@Test
	public void testAnUnofferedHarnessToolGoesToTheFallbackInItsWave() throws Exception {
		// [op, control, op] with the control tool not offered: its handler
		// never runs, and it is no barrier — all three meet at the fallback's
		// barrier, which they can only do together.
		CyclicBarrier barrier = new CyclicBarrier(3);
		AtomicInteger handled = new AtomicInteger();
		ToolCycleEngine.Registry<Object> registry = new ToolCycleEngine.Registry<Object>()
			.offered((name, ctx) -> !CONTROL.equals(name))
			.register(CONTROL, (c, ctx) -> {
				handled.incrementAndGet();
				return ok("handled");
			})
			.fallback((c, ctx) -> {
				barrier.await(5, TimeUnit.SECONDS);
				return ok("answered " + c.name());
			});
		Sink sink = new Sink();
		ToolCycleEngine.executeBatch(
			Vectors.of(call("a", "op"), call("ctl", CONTROL), call("b", "op")), 0, registry, new Object(), sink, engine::currentTimestamp, log);

		assertEquals(0, handled.get(), "an unoffered harness tool's handler never runs");
		assertEquals(List.of("answered op", "answered " + CONTROL, "answered op"),
			sink.messages.stream().map(ToolCycleEngineTest::content).toList());
	}

	@Test
	public void testSingleCallRunsOnTheCallingThread() {
		// The common case pays no thread hop, so a nested harness still sees
		// this thread's CycleRecord.
		Thread caller = Thread.currentThread();
		List<Thread> seen = new ArrayList<>();
		ToolCycleEngine.Registry<Object> registry = new ToolCycleEngine.Registry<Object>()
			.fallback((c, ctx) -> {
				seen.add(Thread.currentThread());
				return ToolCycleEngine.ToolOutcome.result(Strings.create("ran"), Vectors.of(
					Maps.of(AbstractLLMAdapter.K_ROLE, AbstractLLMAdapter.ROLE_SYSTEM,
						AbstractLLMAdapter.K_CONTENT, Strings.create("event"))));
			});
		Sink sink = new Sink();
		ToolCycleEngine.executeBatch(Vectors.of(call("only", "op")), 0, registry, new Object(), sink, engine::currentTimestamp, log);
		assertEquals(1, seen.size());
		assertSame(caller, seen.get(0));
		assertEquals(2, sink.messages.size());
		assertEquals("only", id(sink.messages.get(0)), "the single result remains immediate");
		assertEquals(AbstractLLMAdapter.ROLE_SYSTEM,
			sink.messages.get(1).get(AbstractLLMAdapter.K_ROLE));
	}

	@Test
	public void testTerminalStillFencesEveryLaterCall() {
		// [op, complete, op, op]: the calls after a successful terminal call
		// are never dispatched and each still gets a result for its id.
		AtomicInteger dispatched = new AtomicInteger();
		ToolCycleEngine.Registry<Object> registry = new ToolCycleEngine.Registry<Object>()
			.register(COMPLETE, (c, ctx) -> ToolCycleEngine.ToolOutcome.terminal(
				Strings.create("done"), "completed", Strings.create("value")))
			.fallback((c, ctx) -> { dispatched.incrementAndGet(); return ok("ran"); });
		Sink sink = new Sink();
		ToolCycleEngine.BatchResult batch = ToolCycleEngine.executeBatch(
			Vectors.of(call("1", "op"), call("done", COMPLETE), call("2", "op"), call("3", "op")),
			0, registry, new Object(), sink, engine::currentTimestamp, log);

		assertTrue(batch.isTerminal());
		assertEquals("completed", batch.terminalStatus());
		assertEquals(1, dispatched.get(), "only the call before the terminal ran");
		assertEquals(List.of("1", "done", "2", "3"), sink.messages.stream().map(ToolCycleEngineTest::id).toList());
		assertEquals(ToolCycleEngine.NOT_EXECUTED.formatted("completed"), content(sink.messages.get(2)));
		assertEquals(ToolCycleEngine.NOT_EXECUTED.formatted("completed"), content(sink.messages.get(3)));
		assertEquals(List.of("1", "done"), sink.recorded, "fenced calls are never recorded as executed");
	}

	@Test
	public void testAbortStopsTheBatch() {
		AtomicInteger dispatched = new AtomicInteger();
		ToolCycleEngine.Registry<Object> registry = new ToolCycleEngine.Registry<Object>()
			.register(CONTROL, (c, ctx) -> ToolCycleEngine.ToolOutcome.abort())
			.fallback((c, ctx) -> { dispatched.incrementAndGet(); return ok("ran"); });
		Sink sink = new Sink();
		ToolCycleEngine.BatchResult batch = ToolCycleEngine.executeBatch(
			Vectors.of(call("1", "op"), call("stop", CONTROL), call("2", "op")),
			0, registry, new Object(), sink, engine::currentTimestamp, log);
		assertTrue(batch.aborted());
		assertEquals(1, dispatched.get());
		assertEquals(List.of("1"), sink.messages.stream().map(ToolCycleEngineTest::id).toList());
	}

	@Test
	public void testFailuresInAWaveStayWithTheirCall() {
		// A malformed argument string, a throwing handler, and a null outcome
		// each cost only their own call an error; the neighbours succeed and
		// order is kept.
		ToolCycleEngine.Registry<Object> registry = new ToolCycleEngine.Registry<Object>()
			.fallback((c, ctx) -> switch (c.id().toString()) {
				case "throws" -> throw new NotSupportedException("boom");
				case "nothing" -> null;
				default -> ok("ran " + c.id());
			});
		AMap<AString, ACell> malformed = Maps.of(AbstractLLMAdapter.K_ID, Strings.create("bad"),
			AbstractLLMAdapter.K_NAME, Strings.create("op"),
			AbstractLLMAdapter.K_ARGUMENTS, Strings.create("{not json"));
		Sink sink = new Sink();
		ToolCycleEngine.executeBatch(
			Vectors.of(call("a", "op"), malformed, call("throws", "op"), call("nothing", "op"), call("z", "op")),
			0, registry, new Object(), sink, engine::currentTimestamp, log);

		assertEquals(List.of("a", "bad", "throws", "nothing", "z"),
			sink.messages.stream().map(ToolCycleEngineTest::id).toList());
		assertEquals("ran a", content(sink.messages.get(0)));
		assertTrue(content(sink.messages.get(1)).startsWith("Error:"));
		assertTrue(content(sink.messages.get(2)).contains("boom"));
		assertTrue(content(sink.messages.get(3)).contains("no outcome"));
		assertEquals("ran z", content(sink.messages.get(4)));
	}

	// ========== end to end: a real batch through agent:step ==========

	@Test
	public void testStepRunsAParallelBatchConcurrently() throws Exception {
		// Two held calls requested in one reply, each at its own gate: both
		// reach their gates before either is released, which a batch run one
		// call at a time could never do. Each call's entry reports its time.
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "parallel-step-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/llm",
					"systemPrompt", "You step.",
					"caps", "unrestricted",
					Fields.TOOLS, Vectors.of(Strings.create("v/test/ops/hold")))),
			RequestContext.of(did)).awaitResult(5000);

		String[] gateNames = {"parallel-step-d1-" + did, "parallel-step-d2-" + did};
		AMap<AString, ACell> stepped;
		try (TestAdapter.TestGate d1 = TestAdapter.createGate(gateNames[0]);
				TestAdapter.TestGate d2 = TestAdapter.createGate(gateNames[1])) {
			// agent:step runs on the invoking thread, so it gets one of its own.
			CompletableFuture<ACell> step = CompletableFuture.supplyAsync(() -> engine.jobs().invokeOperation(
				"v/ops/agent/step",
				Maps.of(Fields.AGENT_ID, "parallel-step-agent", Fields.MESSAGE, "wait twice",
					"assistant", Maps.of("content", "Waiting.", "toolCalls", Vectors.of(
						Maps.of("id", "d1", "name", "test_hold", "arguments", held(gateNames[0])),
						Maps.of("id", "d2", "name", "test_hold", "arguments", held(gateNames[1]))))),
				RequestContext.of(did)).awaitResult(10000), r -> Thread.ofVirtual().start(r));
			assertTrue(d1.awaitEntered(10, TimeUnit.SECONDS), "d1 reaches its gate");
			assertTrue(d2.awaitEntered(10, TimeUnit.SECONDS), "d2 reaches its gate while d1 is held");
			d1.release();
			d2.release();
			stepped = RT.ensureMap(step.get(10, TimeUnit.SECONDS));
		}

		AVector<ACell> calls = RT.ensureVector(stepped.get(Strings.intern("calls")));
		assertNotNull(calls, stepped.toString());
		assertEquals(2, calls.count());
		for (long i = 0; i < calls.count(); i++) {
			assertNull(RT.getIn(calls.get(i), "isError"), calls.get(i).toString());
			assertEquals(CVMLong.create(1), RT.getIn(calls.get(i), "result", "x"));
			assertNotNull(RT.ensureLong(RT.getIn(calls.get(i), "ms")), "each call reports its time: " + calls.get(i));
		}
		assertEquals("d1", RT.getIn(calls.get(0), "id").toString());
		assertEquals("d2", RT.getIn(calls.get(1), "id").toString());
	}

	/** {@code v/test/ops/hold} arguments: wait at the gate, then return {@code {x: 1}}. */
	private static AMap<AString, ACell> held(String gate) {
		return Maps.of("gate", gate, Fields.RESULT, Maps.of(Strings.create("x"), CVMLong.create(1)));
	}
}
