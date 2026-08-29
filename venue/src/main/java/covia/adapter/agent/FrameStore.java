package covia.adapter.agent;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.AgentAdapter;
import covia.api.Fields;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Durable frame storage shared by the flat and goal-tree LLM runtimes. Every
 * mutation goes through {@link #update}, so a stack can be lattice-resident
 * (sessioned runs use epoch-fenced CAS writes to
 * {@code sessions/<sid>/frames}) or a plain local value for direct invocation
 * and tests.
 *
 * <p>This seam carries no frame semantics: frame structure stays in the pure
 * {@link GoalTreeContext} functions; the store only moves whole stacks.
 * Package-private — not part of any public or adapter contract.</p>
 */
interface FrameStore {

	/** Result of opening the frame stack for one transition cycle. */
	record Opened(FrameStore store, boolean interrupted, AString error) {
		boolean failed() { return error != null; }
	}

	/**
	 * Opens the one canonical frame store used by both LLM runtimes. A real
	 * session is claimed with an epoch fence, any dangling tool calls are
	 * repaired, then the presented input is appended and drained atomically.
	 * Direct invocations use the same frame shape behind a local store.
	 */
	static Opened open(Engine engine, RequestContext ctx, AString agentId,
			ACell input, AVector<ACell> messages, String rootDescription,
			long cycleTs, boolean recordCaller, Logger log) {
		Blob sid = ctx.getSessionId();
		AgentState agent = resolveAgentState(engine, ctx, agentId);
		if (sid != null && agent != null && agent.getSession(sid) != null) {
			boolean interrupted = agent.getSessionCycleEpoch(sid) != null;
			ACell epoch = Blob.createRandom(new java.util.Random(), 8);
			if (!agent.beginSessionCycle(sid, epoch, null, 0)) {
				return failed("Session vanished before cycle start: " + sid);
			}
			FrameStore store = new LatticeFrameStore(
				agent, sid, epoch, ctx.getCancellation());
			interrupted |= store.frames().count() > 1;

			AString repair = convex.core.data.Strings.create(
				"Error: this tool call did not return — its effects may or may not "
				+ "have applied; verify before retrying.");
			if (!store.frames().isEmpty()) {
				if (!store.update(f -> GoalTreeContext.repairDanglingToolCalls(f, repair))) {
					return failed("Session cycle was superseded during interruption repair: " + sid);
				}
				AVector<ACell> check = store.frames();
				if (check.isEmpty() || GoalTreeContext.repairDanglingToolCalls(check, repair) != check) {
					log.warn("Interruption repair observed stale frames for agent {}, session {}; retrying",
						agentId, sid);
					if (!store.update(f -> GoalTreeContext.repairDanglingToolCalls(f, repair))) {
						return failed("Session cycle was superseded during interruption repair: " + sid);
					}
					check = store.frames();
					if (GoalTreeContext.repairDanglingToolCalls(check, repair) != check) {
						return failed("Interruption cleanup could not repair dangling tool calls "
							+ "for session " + sid);
					}
				}
			}

			AVector<ACell> turns = cycleInputTurns(messages, input, cycleTs, recordCaller);
			long drainCount = (messages != null) ? messages.count() : 0;
			if (!agent.presentSessionCycleInput(sid, epoch, turns, drainCount)) {
				return failed("Session cycle was superseded before input presentation: " + sid);
			}
			return new Opened(store, interrupted, null);
		}

		AVector<ACell> frames = AgentAdapter.sessionFrames(input);
		if (frames == null || frames.isEmpty()) {
			frames = Vectors.of((ACell) GoalTreeContext.createFrame(rootDescription));
		}
		frames = appendCycleInputTurns(frames, messages, input, cycleTs, recordCaller);
		return new Opened(new LocalFrameStore(frames), false, null);
	}

	private static Opened failed(String message) {
		return new Opened(null, false, convex.core.data.Strings.create(message));
	}

	private static AgentState resolveAgentState(Engine engine, RequestContext ctx, AString agentId) {
		if (agentId == null || ctx.getUserDID() == null) return null;
		covia.venue.User user = engine.getVenueState().users().get(ctx.getUserDID());
		return (user != null) ? user.agent(agentId.toString()) : null;
	}

	/** Appends the presented chat/request turns to the root conversation. */
	static AVector<ACell> appendCycleInputTurns(AVector<ACell> frames,
			AVector<ACell> messages, ACell input, long ts) {
		return appendCycleInputTurns(frames, messages, input, ts, false);
	}

	@SuppressWarnings("unchecked")
	private static AVector<ACell> appendCycleInputTurns(AVector<ACell> frames,
			AVector<ACell> messages, ACell input, long ts, boolean recordCaller) {
		if (frames == null || frames.isEmpty()) return frames;
		AMap<AString, ACell> root = (AMap<AString, ACell>) frames.get(0);
		AVector<ACell> turns = cycleInputTurns(messages, input, ts, recordCaller);
		for (long i = 0; i < turns.count(); i++) {
			root = GoalTreeContext.appendTurn(root, turns.get(i));
		}
		return frames.assoc(0, root);
	}

	/** Builds the durable user turns presented at the start of a cycle. */
	static AVector<ACell> cycleInputTurns(AVector<ACell> messages, ACell input, long ts) {
		return cycleInputTurns(messages, input, ts, false);
	}

	private static AVector<ACell> cycleInputTurns(
			AVector<ACell> messages, ACell input, long ts, boolean recordCaller) {
		AVector<ACell> turns = Vectors.empty();
		CVMLong tsCell = CVMLong.create(ts);
		for (long i = 0; messages != null && i < messages.count(); i++) {
			ACell envelope = messages.get(i);
			ACell content = RT.getIn(envelope, Fields.MESSAGE);
			if (content == null) content = RT.getIn(envelope, AgentState.K_CONTENT);
			if (content == null && envelope instanceof AString) content = envelope;
			if (content == null) continue;
			AMap<AString, ACell> turn = Maps.of(
				AgentState.K_ROLE, AgentState.ROLE_USER,
				AgentState.K_CONTENT, content,
				AgentState.K_TURN_TS, tsCell,
				AgentState.K_SOURCE, AgentState.SOURCE_CHAT);
			ACell jobId = RT.getIn(envelope, Fields.JOB_ID);
			if (jobId != null) turn = turn.assoc(Fields.JOB_ID, jobId);
			ACell caller = RT.getIn(envelope, Fields.CALLER);
			if (recordCaller && caller != null) turn = turn.assoc(Fields.CALLER, caller);
			turns = turns.conj(turn);
		}

		ACell request = RT.getIn(input, Fields.NEW_INPUT);
		if (request != null) {
			AMap<AString, ACell> turn = Maps.of(
				AgentState.K_ROLE, AgentState.ROLE_USER,
				AgentState.K_CONTENT, request,
				AgentState.K_TURN_TS, tsCell,
				AgentState.K_SOURCE, AgentState.SOURCE_REQUEST);
			ACell jobId = RT.getIn(input, Fields.JOB_ID);
			if (jobId != null) turn = turn.assoc(Fields.JOB_ID, jobId);
			ACell caller = RT.getIn(input, Fields.TASKS, CVMLong.ZERO, Fields.CALLER);
			if (recordCaller && caller != null) turn = turn.assoc(Fields.CALLER, caller);
			turns = turns.conj(turn);
		}
		return turns;
	}

	/** The current frame stack (never null; empty vector when unset). */
	AVector<ACell> frames();

	/**
	 * Applies one atomic mutation to the stack. {@code fn} must be pure —
	 * the lattice implementation may re-apply it on CAS contention.
	 *
	 * @return true if the mutation applied; false if this cycle no longer
	 *         owns the frames (fenced/cancelled/session gone) — the caller
	 *         must stop
	 */
	boolean update(UnaryOperator<AVector<ACell>> fn);

	/** Appends one durable root-conversation turn. The lattice store also
	 * updates the session's turn/timestamp metadata in the same CAS. */
	boolean appendRoot(ACell turn);

	/**
	 * True when this cycle should stop writing: the transition was cancelled
	 * (suspend/delete), the epoch fence rejected a write (a newer cycle or
	 * settle claimed the session), or the session vanished.
	 */
	boolean aborted();

	/**
	 * Lattice-resident stack: reads and epoch-fenced writes go straight to
	 * {@code sessions/<sid>/frames} on the agent record. A rejected write or
	 * a flipped cancellation token latches {@link #aborted()}.
	 */
	final class LatticeFrameStore implements FrameStore {
		private final AgentState agent;
		private final Blob sid;
		private final ACell epoch;
		private final AtomicBoolean cancelled;   // may be null (no token supplied)
		private boolean aborted = false;

		LatticeFrameStore(AgentState agent, Blob sid, ACell epoch, AtomicBoolean cancelled) {
			this.agent = agent;
			this.sid = sid;
			this.epoch = epoch;
			this.cancelled = cancelled;
		}

		@Override
		public AVector<ACell> frames() {
			var session = agent.getSession(sid);
			if (session == null) { aborted = true; return Vectors.empty(); }
			ACell fv = session.get(AgentState.KEY_FRAMES);
			return (fv instanceof AVector) ? (AVector<ACell>) fv : Vectors.empty();
		}

		@Override
		public boolean update(UnaryOperator<AVector<ACell>> fn) {
			if (aborted()) return false;
			boolean ok = agent.updateSessionFrames(sid, epoch, fn);
			if (!ok) aborted = true;
			return ok;
		}

		@Override
		public boolean appendRoot(ACell turn) {
			if (aborted()) return false;
			boolean ok = agent.appendSessionRootTurn(sid, epoch, turn);
			if (!ok) aborted = true;
			return ok;
		}

		@Override
		public boolean aborted() {
			if (aborted) return true;
			if (cancelled != null && cancelled.get()) { aborted = true; return true; }
			return false;
		}
	}

	/**
	 * Local stack: the pre-cutover behaviour behind the seam. Used when no
	 * session is in scope (direct {@code processGoal} invocations,
	 * unsessioned trigger cycles); never aborts. The final value is what
	 * gets emitted on the transition output.
	 */
	final class LocalFrameStore implements FrameStore {
		private AVector<ACell> frames;

		LocalFrameStore(AVector<ACell> initial) {
			this.frames = (initial != null) ? initial : Vectors.empty();
		}

		@Override
		public AVector<ACell> frames() { return frames; }

		@Override
		public boolean update(UnaryOperator<AVector<ACell>> fn) {
			AVector<ACell> updated = fn.apply(frames);
			if (updated != null) frames = updated;
			return true;
		}

		@Override
		@SuppressWarnings("unchecked")
		public boolean appendRoot(ACell turn) {
			if (frames.isEmpty()) return false;
			AMap<AString, ACell> root = (AMap<AString, ACell>) frames.get(0);
			frames = frames.assoc(0, GoalTreeContext.appendTurn(root, turn));
			return true;
		}

		@Override
		public boolean aborted() { return false; }
	}
}
