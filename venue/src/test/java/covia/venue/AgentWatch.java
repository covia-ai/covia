package covia.venue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;

/**
 * Records one agent's run-loop events ({@link AgentEvents}, AGENT_LOOP.md
 * §2.6) from the moment it is opened, so a test waits on the run loop's own
 * signals instead of polling agent state. Events are not replayed: open the
 * watch before starting the work it waits for, after creating the agent.
 *
 * <p>Each wait fails the test with the events seen so far when the signal has
 * not arrived within {@link #TIMEOUT_MS}, a bound that only a hung run loop
 * reaches.</p>
 */
public final class AgentWatch implements AutoCloseable {

	/** How long a wait lasts before it fails the test. */
	public static final long TIMEOUT_MS = 10_000;

	private final Engine engine;
	private final AString ownerDID;
	private final AString agentId;
	private final List<AgentEvents.Event> events = new ArrayList<>();
	private final AgentEvents.Subscription subscription;

	private AgentWatch(Engine engine, AString ownerDID, AString agentId) {
		this.engine = engine;
		this.ownerDID = ownerDID;
		this.agentId = agentId;
		this.subscription = engine.agentEvents().subscribe(ownerDID, agentId, this::record);
	}

	/** Starts recording the events of {@code ownerDID}'s agent {@code agentId}. */
	public static AgentWatch open(Engine engine, AString ownerDID, AString agentId) {
		return new AgentWatch(engine, ownerDID, agentId);
	}

	/** As {@link #open(Engine, AString, AString)}. */
	public static AgentWatch open(Engine engine, AString ownerDID, String agentId) {
		return open(engine, ownerDID, Strings.create(agentId));
	}

	private synchronized void record(AgentEvents.Event e) {
		events.add(e);
		notifyAll();
	}

	/**
	 * Waits for the {@code n}th {@code cycle:end} since the watch opened. It is
	 * emitted once the cycle's merge has committed, so the timeline then holds
	 * that cycle's entry.
	 */
	public AgentEvents.Event awaitCycles(int n) {
		return await(n + " cycle:end", seen -> nth(seen, AgentEvents.CYCLE_END, n));
	}

	/**
	 * Waits for the agent to rest after its {@code n}th cycle since the watch
	 * opened: the {@code run:end} of the run that made that cycle. The rest
	 * status is persisted before it is emitted.
	 */
	public AgentEvents.Event awaitRest(int cycles) {
		return await("run:end after " + cycles + " cycle:end", seen -> {
			int from = 0;
			if (cycles > 0) {
				AgentEvents.Event last = nth(seen, AgentEvents.CYCLE_END, cycles);
				if (last == null) return null;
				from = seen.indexOf(last) + 1;
			}
			for (int i = from; i < seen.size(); i++) {
				if (AgentEvents.RUN_END.equals(seen.get(i).type())) return seen.get(i);
			}
			return null;
		});
	}

	/**
	 * Waits until {@code state} holds: it is checked now and again as each
	 * event arrives. A merge is followed by its {@code cycle:end}, so a
	 * condition a cycle makes true is seen by then, however many cycles it
	 * takes, and one made true before the watch opened is seen at once.
	 */
	public void awaitState(String what, BooleanSupplier state) {
		await(what, seen -> state.getAsBoolean() ? MET : null);
	}

	/** Stands for a state found to hold rather than an event. */
	private static final AgentEvents.Event MET = new AgentEvents.Event(null, null, 0, 0, null, null);

	/**
	 * Waits until {@code ownerDID}'s agent satisfies {@code state}, checked now
	 * and as each of its events arrives ({@link #awaitState}). The work may
	 * already have happened: nothing needs to be opened before it.
	 */
	public static void awaitAgent(Engine engine, AString ownerDID, Agent agent, String what,
			java.util.function.Predicate<Agent> state) {
		try (AgentWatch watch = open(engine, ownerDID, agent.getAgentId())) {
			watch.awaitState(what, () -> state.test(agent));
		}
	}

	/** Waits until the agent's timeline holds at least {@code count} entries. */
	public static void awaitTimeline(Engine engine, AString ownerDID, Agent agent, int count) {
		awaitAgent(engine, ownerDID, agent, count + " timeline entries", a -> {
			AVector<?> timeline = a.getTimeline();
			return timeline != null && timeline.count() >= count;
		});
	}

	/** Waits until the agent's persisted status is {@code status}. */
	public static void awaitStatus(Engine engine, AString ownerDID, Agent agent, AString status) {
		awaitAgent(engine, ownerDID, agent, "status " + status, a -> status.equals(a.getStatus()));
	}

	/** Waits until the agent is idle: SLEEPING, with no session input pending and no tasks. */
	public static void awaitIdle(Engine engine, AString ownerDID, Agent agent) {
		awaitAgent(engine, ownerDID, agent, "idle", a -> Agent.SLEEPING.equals(a.getStatus())
			&& !a.hasSessionPending() && (a.getTasks() == null || a.getTasks().count() == 0));
	}

	/** Waits for the first {@code status} event since the watch opened that reports {@code status}. */
	public AgentEvents.Event awaitStatus(AString status) {
		return awaitEvent("status " + status, e -> AgentEvents.STATUS.equals(e.type())
			&& status.equals(RT.getIn(e.data(), Fields.STATUS)));
	}

	/** Waits for the first event since the watch opened that matches {@code condition}. */
	public AgentEvents.Event awaitEvent(String what, Predicate<AgentEvents.Event> condition) {
		return await(what, seen -> {
			for (AgentEvents.Event e : seen) if (condition.test(e)) return e;
			return null;
		});
	}

	/** The events seen so far, in order. */
	public synchronized List<AgentEvents.Event> events() {
		return List.copyOf(events);
	}

	private static AgentEvents.Event nth(List<AgentEvents.Event> seen, AString type, int n) {
		int count = 0;
		for (AgentEvents.Event e : seen) {
			if (type.equals(e.type()) && ++count == n) return e;
		}
		return null;
	}

	private synchronized AgentEvents.Event await(String what,
			Function<List<AgentEvents.Event>, AgentEvents.Event> find) {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
		while (true) {
			AgentEvents.Event found = find.apply(events);
			if (found != null) return found;
			long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
			if (remaining <= 0) throw new AssertionError(describe(what));
			try {
				wait(remaining);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted waiting for " + describe(what), e);
			}
		}
	}

	private String describe(String what) {
		StringBuilder sb = new StringBuilder("Agent ").append(agentId).append(" did not emit ")
			.append(what).append(" within ").append(TIMEOUT_MS).append("ms; saw [");
		for (int i = 0; i < events.size(); i++) {
			if (i > 0) sb.append(", ");
			AgentEvents.Event e = events.get(i);
			sb.append(e.type());
			if (AgentEvents.STATUS.equals(e.type()) || AgentEvents.RUN_END.equals(e.type())) {
				sb.append('=').append((Object) RT.getIn(e.data(), Fields.STATUS));
			}
		}
		sb.append(']');
		User user = engine.getVenueState().users().user(ownerDID);
		Agent agent = (user == null) ? null : user.agent(agentId);
		if (agent != null) {
			AVector<?> timeline = agent.getTimeline();
			sb.append("; status=").append(agent.getStatus())
				.append(", timeline=").append(timeline == null ? 0 : timeline.count())
				.append(", sessionPending=").append(agent.hasSessionPending())
				.append(", tasks=").append(agent.getTasks() == null ? 0 : agent.getTasks().count());
		}
		return sb.toString();
	}

	@Override
	public void close() {
		subscription.close();
	}
}
