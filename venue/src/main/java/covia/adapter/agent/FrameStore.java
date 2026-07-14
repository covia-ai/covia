package covia.adapter.agent;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Vectors;
import covia.venue.AgentState;

/**
 * Where a goal-tree run's frame stack lives. {@code runFrame} performs every
 * frame mutation through {@link #update}, so the stack can be lattice-resident
 * (sessioned runs — each mutation is an epoch-fenced CAS on
 * {@code sessions/<sid>/frames}, making mid-run work durable and observable)
 * or a plain local value (unsessioned / direct-invoke / test runs — exactly
 * the pre-cutover behaviour).
 *
 * <p>This seam carries no frame semantics: frame structure stays in the pure
 * {@link GoalTreeContext} functions; the store only moves whole stacks.
 * Package-private — not part of any public or adapter contract.</p>
 */
interface FrameStore {

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
		public boolean aborted() { return false; }
	}
}
