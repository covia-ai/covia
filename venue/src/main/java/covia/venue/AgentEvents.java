package covia.venue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import covia.api.Fields;

/**
 * The live tap on agent run loops (#394): one ordered event per run-loop
 * transition — run and cycle boundaries, every inference and tool call the
 * transition makes, and every persisted status change — delivered
 * synchronously to in-process subscribers. The REST agent stream
 * ({@code GET /agents/{id}/sse}) is one such subscriber; an embedding host
 * that runs the venue in its own JVM subscribes directly and drops its
 * polling. Both see the same schema — see AGENT_LOOP.md §2.6.
 *
 * <p><b>Ordering.</b> Events for one agent carry a strictly increasing
 * {@code seq} (venue-process lifetime; a restart resets it) and are
 * delivered in that order: emission for an agent is serialised, so a tool
 * result emitted from a parallel worker thread cannot overtake the loop
 * thread's cycle end. Listeners run on the emitting thread and must not
 * block or throw — a throwing listener is isolated and logged, exactly as
 * a Job listener is.</p>
 *
 * <p><b>Correlation.</b> Every event names its agent ({@code agentId},
 * {@code address}); events inside a run carry the {@code run} number, those
 * inside a cycle the {@code cycle} number plus the {@code sessionId} and
 * task {@code jobId} the cycle runs on. A {@link Cycle} handle rides the
 * cycle's {@link RequestContext} into the transition adapter
 * ({@link RequestContext#getCycle()}), so activity is attributed to the
 * cycle that produced it and nothing else — a transition invoked outside a
 * run loop ({@code agent:step}, a direct {@code llmagent:chat}) has no
 * handle and emits nothing.</p>
 *
 * <p><b>Authority.</b> The tap is owner-level: the same data the completed
 * timeline entry exposes, live. Display-safe fields (names, counts, timing,
 * the assistant's explicitly emitted text) sit at the top level; tool
 * inputs, results and appended turns sit under {@code detail}, which
 * {@link Event#withoutDetail()} strips for a consumer that must not show
 * them. Hidden model reasoning is never carried — a reply's
 * {@code content} is what the provider adapter already surfaces as the
 * assistant's text.</p>
 */
public final class AgentEvents {

	private static final Logger log = LoggerFactory.getLogger(AgentEvents.class);

	// ========== Event types ==========

	/** A run loop launched for the agent. {@code {run}} */
	public static final AString RUN_START = Strings.intern("run:start");
	/** The run loop exited. {@code {run, status, cycles}} */
	public static final AString RUN_END = Strings.intern("run:end");
	/** A cycle picked its work and is invoking the transition.
	 *  {@code {run, cycle, op, sessionId?, jobId?, jobs?, tasks, messages, pending}} */
	public static final AString CYCLE_START = Strings.intern("cycle:start");
	/** The cycle's merge committed — timeline entry and session turns are
	 *  persisted. Generic transitions may include the turns appended by the
	 *  merge under {@code detail}; frame-owning runtimes do not duplicate their
	 *  already-live turns. {@code {run, cycle, ms, response? | error?, tokens?,
	 *  timeline?, detail?: {turns}}} */
	public static final AString CYCLE_END = Strings.intern("cycle:end");
	/** A model call is starting. {@code {op, model?, messages, tools, bytes, budget, depth?}} */
	public static final AString INFERENCE_START = Strings.intern("inference:start");
	/** A model call returned or failed.
	 *  {@code {ms, model?, content?, toolCalls?: [{id, name}], tokens?, depth?}} or {@code {ms, error, depth?}} */
	public static final AString INFERENCE_END = Strings.intern("inference:end");
	/** A tool call is being dispatched. {@code {id, name, depth?, detail: {input}}} */
	public static final AString TOOL_START = Strings.intern("tool:start");
	/** A tool call finished. {@code {id, name, ms, isError?, depth?, detail: {result}}} */
	public static final AString TOOL_RESULT = Strings.intern("tool:result");
	/** The agent's persisted status changed. {@code {status, error?}} */
	public static final AString STATUS = Strings.intern("status");

	// ========== Event ==========

	/**
	 * One event. {@code data} is the type-specific payload; {@link #toCell()}
	 * is the wire form (the payload stamped with the envelope fields).
	 */
	public record Event(AString ownerDID, AString agentId, long seq, long ts, AString type,
			AMap<AString, ACell> data) {

		/** The agent's grid address, {@code <ownerDID>/g/<agentId>}. */
		public AString address() {
			return AgentEvents.address(ownerDID, agentId);
		}

		/** The wire form: {@code {seq, ts, type, agentId, address, ...data}}. */
		public AMap<AString, ACell> toCell() {
			AMap<AString, ACell> m = (data != null) ? data : Maps.empty();
			return m.assoc(Fields.SEQ, CVMLong.create(seq))
				.assoc(Fields.TS, CVMLong.create(ts))
				.assoc(Fields.TYPE, type)
				.assoc(Fields.AGENT_ID, agentId)
				.assoc(Fields.ADDRESS, address());
		}

		/** This event without its owner-authorised {@code detail}. */
		public Event withoutDetail() {
			if (data == null || !data.containsKey(Fields.DETAIL)) return this;
			return new Event(ownerDID, agentId, seq, ts, type, data.dissoc(Fields.DETAIL));
		}

		/**
		 * True when a consumer narrowed to one session should see this event:
		 * the cycle-scoped events of that session, plus every {@code status}
		 * event — a suspension or termination is the answer to why a
		 * conversation stopped. Run boundaries and other sessions' cycles are
		 * not its concern. {@code sessionId} is the bare hex the events carry;
		 * null means the whole agent.
		 */
		public boolean concerns(AString sessionId) {
			if (sessionId == null || STATUS.equals(type)) return true;
			return sessionId.equals(RT.getIn(data, Fields.SESSION_ID));
		}

		/** True for the event that ends an agent's stream: status TERMINATED. */
		public boolean isTerminal() {
			return STATUS.equals(type) && AgentState.TERMINATED.equals(RT.getIn(data, Fields.STATUS));
		}
	}

	/** A registered listener; {@link #close()} unregisters it. */
	@FunctionalInterface
	public interface Subscription extends AutoCloseable {
		@Override void close();
	}

	// ========== Registry ==========

	/** Per-agent state: the sequence, run counter and listeners. Emission
	 *  synchronises on the slot so seq order is delivery order. */
	private static final class Slot {
		long seq;
		long runs;
		final CopyOnWriteArrayList<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
	}

	private final ConcurrentHashMap<AString, Slot> slots = new ConcurrentHashMap<>();
	private final CopyOnWriteArrayList<Consumer<Event>> global = new CopyOnWriteArrayList<>();

	/** The grid address events are keyed by: {@code <ownerDID>/g/<agentId>}. */
	public static AString address(AString ownerDID, AString agentId) {
		return Strings.create(ownerDID + "/g/" + agentId);
	}

	private Slot slot(AString ownerDID, AString agentId) {
		return slots.computeIfAbsent(address(ownerDID, agentId), k -> new Slot());
	}

	/** Subscribes to one agent's events. */
	public Subscription subscribe(AString ownerDID, AString agentId, Consumer<Event> listener) {
		java.util.Objects.requireNonNull(listener, "listener");
		Slot s = slot(ownerDID, agentId);
		s.listeners.add(listener);
		return () -> s.listeners.remove(listener);
	}

	/**
	 * Subscribes to one session of an agent: that session's cycle-scoped
	 * events and the agent's status events — see {@link Event#concerns}. The
	 * agent's {@code seq} is unchanged, so a session view sees gaps.
	 */
	public Subscription subscribe(AString ownerDID, AString agentId, AString sessionId,
			Consumer<Event> listener) {
		java.util.Objects.requireNonNull(listener, "listener");
		if (sessionId == null) return subscribe(ownerDID, agentId, listener);
		return subscribe(ownerDID, agentId, e -> { if (e.concerns(sessionId)) listener.accept(e); });
	}

	/** Subscribes to every agent's events on this venue (the SSE fan-out). */
	public Subscription subscribe(Consumer<Event> listener) {
		java.util.Objects.requireNonNull(listener, "listener");
		global.add(listener);
		return () -> global.remove(listener);
	}

	/** The last sequence number emitted for an agent (0 when none yet). */
	public long lastSeq(AString ownerDID, AString agentId) {
		return lastSeq(address(ownerDID, agentId));
	}

	/** As {@link #lastSeq(AString, AString)}, keyed by grid address. */
	public long lastSeq(AString address) {
		Slot s = slots.get(address);
		if (s == null) return 0;
		synchronized (s) { return s.seq; }
	}

	private void emit(AString ownerDID, AString agentId, AString type, AMap<AString, ACell> data) {
		Slot s = slot(ownerDID, agentId);
		synchronized (s) {
			Event e = new Event(ownerDID, agentId, ++s.seq, Utils.getCurrentTimestamp(), type, data);
			deliver(s.listeners, e);
			deliver(global, e);
		}
	}

	private static void deliver(CopyOnWriteArrayList<Consumer<Event>> listeners, Event e) {
		for (Consumer<Event> l : listeners) {
			try {
				l.accept(e);
			} catch (Throwable t) {
				log.warn("Agent event listener threw on {} for {}: {}", e.type(), e.address(), t.getMessage());
			}
		}
	}

	// ========== Run-loop side ==========

	/** Emits a {@code status} event: the agent's persisted status changed. */
	public void status(AString ownerDID, AString agentId, AString status, AString error) {
		AMap<AString, ACell> data = Maps.of(Fields.STATUS, status);
		if (error != null) data = data.assoc(Fields.ERROR, error);
		emit(ownerDID, agentId, STATUS, data);
	}

	/** A run loop is launching: emits {@code run:start} and returns its handle. */
	public Run beginRun(AString ownerDID, AString agentId) {
		Slot s = slot(ownerDID, agentId);
		long number;
		synchronized (s) { number = ++s.runs; }
		Run run = new Run(ownerDID, agentId, number);
		emit(ownerDID, agentId, RUN_START, Maps.of(Fields.RUN, CVMLong.create(number)));
		return run;
	}

	/** One launch of an agent's run loop. */
	public final class Run {
		private final AString ownerDID;
		private final AString agentId;
		private final long number;
		private long cycles;

		private Run(AString ownerDID, AString agentId, long number) {
			this.ownerDID = ownerDID;
			this.agentId = agentId;
			this.number = number;
		}

		public long number() { return number; }

		/**
		 * A cycle is about to invoke its transition: emits {@code cycle:start}
		 * and returns the handle the cycle context carries.
		 *
		 * @param sessionId the picked session (hex), or null
		 * @param jobId the picked task's job id (hex), or null
		 * @param data the rest of the payload — op, presented job ids and counts
		 */
		public Cycle beginCycle(AString sessionId, AString jobId, AMap<AString, ACell> data) {
			Cycle cycle = new Cycle(this, ++cycles, sessionId, jobId);
			emit(ownerDID, agentId, CYCLE_START, cycle.stamp(data));
			return cycle;
		}

		/** The run loop exited: emits {@code run:end} with the rest status reached. */
		public void end(AString status) {
			AMap<AString, ACell> data = Maps.of(
				Fields.RUN, CVMLong.create(number),
				Fields.STATUS, status,
				Fields.CYCLES, CVMLong.create(cycles));
			emit(ownerDID, agentId, RUN_END, data);
		}
	}

	/**
	 * One cycle of a run: the handle the transition adapter emits through.
	 * Every event it emits is stamped with the run, cycle, session and task
	 * job it belongs to. Thread-safe — a parallel tool wave emits from its
	 * worker threads.
	 */
	public final class Cycle {
		private final Run run;
		private final long number;
		private final AString sessionId;
		private final AString jobId;
		private final long startedNanos = System.nanoTime();

		private Cycle(Run run, long number, AString sessionId, AString jobId) {
			this.run = run;
			this.number = number;
			this.sessionId = sessionId;
			this.jobId = jobId;
		}

		public long number() { return number; }
		public AString sessionId() { return sessionId; }
		public AString jobId() { return jobId; }

		private AMap<AString, ACell> stamp(AMap<AString, ACell> data) {
			AMap<AString, ACell> m = (data != null) ? data : Maps.empty();
			m = m.assoc(Fields.RUN, CVMLong.create(run.number))
				.assoc(Fields.CYCLE, CVMLong.create(number));
			if (sessionId != null) m = m.assoc(Fields.SESSION_ID, sessionId);
			if (jobId != null) m = m.assoc(Fields.JOB_ID, jobId);
			return m;
		}

		private static AMap<AString, ACell> depth(AMap<AString, ACell> m, int depth) {
			return (depth > 0) ? m.assoc(Fields.DEPTH, CVMLong.create(depth)) : m;
		}

		private void emit(AString type, AMap<AString, ACell> data) {
			AgentEvents.this.emit(run.ownerDID, run.agentId, type, stamp(data));
		}

		/** A model call is starting on an assembled prompt. */
		public void inferenceStart(AString op, AString model, long messages, long tools,
				long bytes, long budget, int depth) {
			AMap<AString, ACell> m = Maps.of(
				Fields.OP, op,
				Fields.MESSAGES, CVMLong.create(messages),
				Fields.TOOLS, CVMLong.create(tools),
				Fields.BYTES, CVMLong.create(bytes),
				Fields.BUDGET, CVMLong.create(budget));
			if (model != null) m = m.assoc(Fields.MODEL, model);
			emit(INFERENCE_START, depth(m, depth));
		}

		/**
		 * A model call returned. Carries the assistant's explicit text and the
		 * ids and names of the tool calls it requested — arguments follow on
		 * each call's {@code tool:start} — plus the provider-reported usage.
		 */
		public void inferenceEnd(ACell reply, long ms, int depth) {
			AMap<AString, ACell> m = Maps.of(Fields.MS, CVMLong.create(ms));
			AString content = RT.ensureString(RT.getIn(reply, Fields.CONTENT));
			if (content != null && content.count() > 0) m = m.assoc(Fields.CONTENT, content);
			AVector<ACell> calls = RT.ensureVector(RT.getIn(reply, Fields.TOOL_CALLS));
			if (calls != null && !calls.isEmpty()) {
				AVector<ACell> names = Vectors.empty();
				for (long i = 0; i < calls.count(); i++) {
					AMap<AString, ACell> c = Maps.empty();
					ACell id = RT.getIn(calls.get(i), Fields.ID);
					ACell name = RT.getIn(calls.get(i), Fields.NAME);
					if (id != null) c = c.assoc(Fields.ID, id);
					if (name != null) c = c.assoc(Fields.NAME, name);
					names = names.conj(c);
				}
				m = m.assoc(Fields.TOOL_CALLS, names);
			}
			ACell model = RT.getIn(reply, Fields.MODEL);
			if (model != null) m = m.assoc(Fields.MODEL, model);
			ACell tokens = RT.getIn(reply, Fields.TOKENS);
			if (tokens instanceof AMap) m = m.assoc(Fields.TOKENS, tokens);
			emit(INFERENCE_END, depth(m, depth));
		}

		/** A model call produced no reply. */
		public void inferenceFailed(String error, long ms, int depth) {
			AMap<AString, ACell> m = Maps.of(
				Fields.MS, CVMLong.create(ms),
				Fields.ERROR, Strings.create(error));
			emit(INFERENCE_END, depth(m, depth));
		}

		/** A tool call is being dispatched; its decoded input rides under {@code detail}. */
		public void toolStart(AString id, String name, String activityLabel, ACell input, int depth) {
			AMap<AString, ACell> m = Maps.of(
				Fields.NAME, Strings.create(name),
				Fields.ACTIVITY_LABEL, Strings.create(activityLabel));
			if (id != null) m = m.assoc(Fields.ID, id);
			AMap<AString, ACell> detail = Maps.empty();
			if (input != null) detail = detail.assoc(Fields.INPUT, input);
			emit(TOOL_START, depth(m.assoc(Fields.DETAIL, detail), depth));
		}

		/** A tool call finished; its result rides under {@code detail}. */
		public void toolResult(AString id, String name, long ms, boolean isError, ACell result, int depth) {
			AMap<AString, ACell> m = Maps.of(
				Fields.NAME, Strings.create(name),
				Fields.MS, CVMLong.create(ms));
			if (id != null) m = m.assoc(Fields.ID, id);
			if (isError) m = m.assoc(Fields.IS_ERROR, CVMBool.TRUE);
			AMap<AString, ACell> detail = Maps.empty();
			if (result != null) detail = detail.assoc(Fields.RESULT, result);
			emit(TOOL_RESULT, depth(m.assoc(Fields.DETAIL, detail), depth));
		}

		/** The cycle's merge committed: emits {@code cycle:end} with the outcome. */
		public void end(AMap<AString, ACell> data) {
			AMap<AString, ACell> m = (data != null) ? data : Maps.empty();
			m = m.assoc(Fields.MS, CVMLong.create((System.nanoTime() - startedNanos) / 1_000_000));
			emit(CYCLE_END, m);
		}
	}
}
