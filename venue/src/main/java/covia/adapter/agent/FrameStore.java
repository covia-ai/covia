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
	 * Opens the one canonical frame store used by both LLM runtimes. A cycle
	 * the run loop began on a session carries its claim in the input's
	 * {@code session.inCycle}: the cycle's start has already closed dangling
	 * tool calls and moved its input into the conversation, so the store
	 * writes through to the session, fenced by that claim. Any other
	 * invocation (unsessioned, or a task whose session is gone) works on a
	 * local stack built from its input. A stack that still holds child frames
	 * was interrupted.
	 */
	static Opened open(Engine engine, RequestContext ctx, AString agentId,
			ACell input, AVector<ACell> messages, String rootDescription,
			long cycleTs) {
		Blob sid = ctx.getSessionId();
		ACell claim = RT.getIn(input, Fields.SESSION, AgentState.KEY_IN_CYCLE);
		if (sid != null && claim != null) {
			AgentState agent = resolveAgentState(engine, ctx, agentId);
			if (agent == null || agent.getSession(sid) == null) {
				return failed("Session vanished before cycle start: " + sid);
			}
			FrameStore store = new LatticeFrameStore(agent, sid, claim, ctx.getCancellation());
			return new Opened(store, store.frames().count() > 1, null);
		}

		AVector<ACell> frames = AgentAdapter.sessionFrames(input);
		if (frames == null || frames.isEmpty()) {
			frames = Vectors.of((ACell) GoalTreeContext.createFrame(rootDescription));
		}
		frames = GoalTreeContext.migrateFrames(frames);
		frames = GoalTreeContext.withRootLoads(frames,
			ContextChain.sessionRootLoads(RT.getIn(input, Fields.SESSION)));
		frames = appendCycleInputTurns(frames, messages, input, cycleTs);
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

	/** Appends a cycle's input turns ({@link AgentAdapter#cycleInputTurns})
	 * to the root conversation of a local stack. The task input's provenance
	 * is the run loop's {@code inputSource}, else the task's own. */
	@SuppressWarnings("unchecked")
	static AVector<ACell> appendCycleInputTurns(AVector<ACell> frames,
			AVector<ACell> messages, ACell input, long ts) {
		if (frames == null || frames.isEmpty()) return frames;
		AMap<AString, ACell> root = (AMap<AString, ACell>) frames.get(0);
		ACell inputSource = RT.getIn(input, AgentState.KEY_INPUT_SOURCE);
		if (inputSource == null) inputSource = RT.getIn(input, Fields.TASKS, CVMLong.ZERO);
		AVector<ACell> turns = AgentAdapter.cycleInputTurns(messages, RT.getIn(input, Fields.NEW_INPUT),
			RT.getIn(input, Fields.JOB_ID), inputSource, ts);
		for (long i = 0; i < turns.count(); i++) {
			root = GoalTreeContext.appendTurn(root, turns.get(i));
		}
		return frames.assoc(0, root);
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

	/** Replaces one frame through the store's ordinary atomic/fenced update. */
	default boolean replace(int frameIndex, ACell frame) {
		return update(frames -> {
			if (frameIndex < 0 || frameIndex >= frames.count()) return frames;
			return (frames.get(frameIndex) == frame)
				? frames : frames.assoc(frameIndex, frame);
		});
	}

	/**
	 * Applies already-materialised watched-context candidates to one frame in a
	 * single state update. All reads and operation calls must have completed
	 * before this method; the CAS transform itself is pure and non-blocking.
	 */
	@SuppressWarnings("unchecked")
	default boolean observe(int frameIndex, AVector<ACell> candidates, long timestamp) {
		return update(frames -> {
			if (frameIndex < 0 || frameIndex >= frames.count()) return frames;
			AMap<AString, ACell> frame = (AMap<AString, ACell>) frames.get(frameIndex);
			AMap<AString, ACell> observed = GoalTreeContext.applyObservations(
				frame, candidates, timestamp);
			return (observed == frame) ? frames : frames.assoc(frameIndex, observed);
		});
	}

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
			boolean ok = agent.session(sid).updateFrames(epoch, fn);
			if (!ok) aborted = true;
			return ok;
		}

		@Override
		public boolean appendRoot(ACell turn) {
			if (aborted()) return false;
			boolean ok = agent.session(sid).appendRootTurn(epoch, turn);
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
	 * Local stack, held in memory for one run. Used when no session is in
	 * scope (direct {@code processGoal} invocations,
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
			if (updated == null) return false;
			GoalTreeContext.validateFramesUpdate(frames, updated);
			frames = updated;
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
