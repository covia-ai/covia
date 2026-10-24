package covia.venue;

import java.util.Objects;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeContext;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.api.Fields;
import covia.adapter.agent.GoalTreeContext;
import static covia.venue.AgentState.*;

/**
 * One agent session: conversation frames, pending input and cycle fences.
 * Updates preserve the owning agent's timestamp/account policies. Missing
 * sessions are never created by this view; creation belongs to AgentState.
 * Transactions compose session changes without runtime services or external I/O.
 */
public final class SessionState extends ALatticeComponent<ACell>
		implements TransactionalComponent<ACell, SessionState> {
	private final LatticeContext context;
	private final Blob id;

	SessionState(LatticeContext context, AForkableCursor<ACell> cursor, Blob id) {
		super(cursor);
		this.context = context;
		this.id = id;
	}

	@Override public SessionState withCursor(AForkableCursor<ACell> draft) {
		return new SessionState(context, draft, id);
	}

	public Blob getID() { return id; }
	public AMap<AString, ACell> getRecord() { return RT.ensureMap(cursor().get()); }

	/** Null from the operation means rejection; unchanged records still count as accepted.
	 * Reset the result on every retry, including a concurrently removed session. */
	private boolean update(UnaryOperator<AMap<AString, ACell>> action) {
		boolean[] accepted = {false};
		cursor().updateAndGet(value -> {
			AMap<AString, ACell> record = RT.ensureMap(value);
			AMap<AString, ACell> next = record == null ? null : action.apply(record);
			accepted[0] = next != null;
			return next == null ? value : next;
		});
		return accepted[0];
	}

	public ACell cycleEpoch() { return RT.getIn(getRecord(), K_IN_CYCLE); }
	public void clearCycle() { update(record -> record.dissoc(K_IN_CYCLE)); }
	public AVector<ACell> pending() {
		AVector<ACell> pending = RT.ensureVector(RT.getIn(getRecord(), K_PENDING));
		return pending == null ? Vectors.empty() : pending;
	}
	private static final AString K_PENDING = Strings.intern("pending");

	static final AString K_C = Strings.intern("c");
	static final AString K_FRAMES   = Strings.intern("frames");
	/** Legacy session loads are migrated into the root frame on cycle admission. */
	static final AString K_LOADS = Strings.intern("loads");
	/** Attempt fence, not a durable executor checkpoint or a wake signal. */
	static final AString K_IN_CYCLE = Strings.intern("inCycle");
	static final AString K_META	 = Strings.intern("meta");
	static final AString K_PARTIES  = Strings.intern("parties");
	static final AString K_CREATED  = Strings.intern("created");
	static final AString K_TURNS	= Strings.intern("turns");
	static final AString K_DESCRIPTION  = Strings.intern("description");
	static final AString K_CONVERSATION = Strings.intern("conversation");

	/** Claims an attempt, migrates frames and records/drains input in one update.
	 * A later attempt may supersede an abandoned epoch after restart. */
	public boolean beginCycle(ACell epoch, AVector<ACell> turns, long drainCount) {
		return update(session -> {
			if (session.containsKey(K_FRAMES)) {
				AVector<ACell> frames = sessionFrames(session);
				session = session.assoc(K_FRAMES, GoalTreeContext.migrateFrames(frames));
			}
			session = migrateLegacySessionLoads(session);
			session = session.assoc(K_IN_CYCLE, epoch);
			if (turns != null && turns.count() > 0) {
				session = appendTurnsToRoot(session, turns);
			}
			if (drainCount > 0) {
				session = drainPendingPrefix(session, drainCount);
			}
			return session;
		});
	}

	/** Records input only while this attempt owns the session; drains the same
	 * pending prefix in that update, preserving messages received subsequently. */
	public boolean presentCycleInput(ACell expectedEpoch,
			AVector<ACell> turns, long drainCount) {
		return update(session -> {
			if (!Objects.equals(expectedEpoch, session.get(K_IN_CYCLE))) return null;

			if (turns != null && turns.count() > 0) {
				session = appendTurnsToRoot(session, turns);
			}
			if (drainCount > 0) {
				session = drainPendingPrefix(session, drainCount);
			}
			return session;
		});
	}

	/** Appends a format-aware root turn and its metadata under the attempt fence. */
	public boolean appendRootTurn(ACell expectedEpoch, ACell turn) {
		return update(session -> {
			if (expectedEpoch != null && !expectedEpoch.equals(session.get(K_IN_CYCLE))) return null;
			session = appendTurnsToRoot(session, Vectors.of(turn));
			return session;
		});
	}

	public boolean setTitle(AString title) {
		return update(session -> {
			AMap<AString, ACell> meta = (session.get(K_META) instanceof AMap m)
				? (AMap<AString, ACell>) m : Maps.empty();
			meta = (title != null) ? meta.assoc(Fields.TITLE, title) : meta.dissoc(Fields.TITLE);
			session = session.assoc(K_META, meta);
			return session;
		});
	}

	public void removePendingJob(Blob jobId) {
		AString jobIdHex = Strings.create(jobId.toHexString());
		update(session -> {
			AVector<ACell> pending = (session.get(K_PENDING) instanceof AVector<?> v)
				? (AVector<ACell>) v : Vectors.empty();
			AVector<ACell> kept = Vectors.empty();
			for (long i = 0; i < pending.count(); i++) {
				ACell envelope = pending.get(i);
				if (!jobIdHex.equals(RT.getIn(envelope, Fields.JOB_ID))) {
					kept = kept.conj(envelope);
				}
			}
			if (kept.count() == pending.count()) return session;
			return session.assoc(K_PENDING, kept);
		});
	}

	/** Atomic frame edit. The function must be pure: conflicts may retry it.
	 * A null epoch skips the fence for coordinated initialisation; owner edits
	 * should use updateQuiescentFrames. Null from fn rejects the edit. */
	public boolean updateFrames(ACell expectedEpoch, UnaryOperator<AVector<ACell>> fn) {
		return updateFrames(expectedEpoch, false, fn);
	}

	/** Owner edits require an idle session in the same atomic update. */
	public boolean updateQuiescentFrames(UnaryOperator<AVector<ACell>> fn) {
		return updateFrames(null, true, fn);
	}

	/** Converts only the idle, unchanged snapshot, without recounting old turns. */
	public boolean migrateHistory(AVector<ACell> expectedFrames) {
		if (expectedFrames == null) return false;
		return updateQuiescentFrames(current ->
			expectedFrames.equals(current) ? GoalTreeContext.migrateFrames(current) : null);
	}

	private boolean updateFrames(ACell expectedEpoch,
			boolean requireQuiescent, UnaryOperator<AVector<ACell>> fn) {
		return update(session -> {
			if (requireQuiescent && session.get(K_IN_CYCLE) != null) return null;
			if (expectedEpoch != null && !expectedEpoch.equals(session.get(K_IN_CYCLE))) {
				return null;   // fence: this cycle no longer owns the session
			}
			AVector<ACell> frames = sessionFrames(session);
			AVector<ACell> updatedFrames = fn.apply(frames);
			if (updatedFrames == null || updatedFrames == frames) {
				return updatedFrames == null ? null : session;   // no change — skip the write (and the K_TS bump)
			}
			GoalTreeContext.validateFramesUpdate(frames, updatedFrames);
			session = session.assoc(K_FRAMES, updatedFrames);
			// Compare retained suffixes for history frames. Compaction adds a
			// summary, not a turn; migration must not count flattened archives again.
			AVector<ACell> beforeRoot = rootHistory(frames);
			AVector<ACell> afterRoot = rootHistory(updatedFrames);
			if (afterRoot.count() > beforeRoot.count()) {
				AVector<ACell> appended = Vectors.empty();
				for (long i = beforeRoot.count(); i < afterRoot.count(); i++) {
					ACell entry = afterRoot.get(i);
					if (!GoalTreeContext.isSegment(entry)) appended = appended.conj(entry);
				}
				if (!appended.isEmpty()) session = bumpTurnMeta(session, appended);
			}
			return session;
		});
	}

	static AMap<AString, ACell> newSession(AString caller,
			AMap<AString, ACell> initialLoads, CVMLong created) {
		AMap<AString, ACell> meta = Maps.of(
			K_CREATED, created,
			Fields.UPDATED, created,
			K_TURNS,   CVMLong.create(0),
			K_PARTIES, (caller != null) ? Vectors.of(caller) : Vectors.empty());
		AMap<AString, ACell> rootFrame = GoalTreeContext.createFrame("");
		if (initialLoads != null && initialLoads.count() > 0) {
			rootFrame = rootFrame.assoc(K_LOADS, initialLoads);
		}
		return Maps.of(
			K_C,	   Maps.empty(),
			K_PENDING, Vectors.empty(),
			K_FRAMES,  Vectors.of(rootFrame),
			K_META,	meta);
	}

	/** Preserve inner frame precedence, including nil masks, when flattening loads. */
	static AMap<AString, ACell> migrateLegacySessionLoads(
			AMap<AString, ACell> session) {
		if (!session.containsKey(K_LOADS)) return session;
		AMap<AString, ACell> legacy = (session.get(K_LOADS) instanceof AMap lm)
			? (AMap<AString, ACell>) lm : Maps.empty();
		AVector<ACell> frames = sessionFrames(session);
		AMap<AString, ACell> root;
		if (frames.isEmpty() || !(frames.get(0) instanceof AMap)) {
			root = GoalTreeContext.createFrame("");
			frames = Vectors.of(root);
		} else {
			root = (AMap<AString, ACell>) frames.get(0);
		}
		AMap<AString, ACell> frameLoads = (root.get(K_LOADS) instanceof AMap fm)
			? (AMap<AString, ACell>) fm : Maps.empty();
		AMap<AString, ACell> merged = mergeLoadTiers(legacy, frameLoads);
		root = merged.isEmpty() ? root.dissoc(K_LOADS) : root.assoc(K_LOADS, merged);
		return session.assoc(K_FRAMES, frames.assoc(0, root)).dissoc(K_LOADS);
	}

	@SafeVarargs
	static AMap<AString, ACell> mergeLoadTiers(AMap<AString, ACell>... tiers) {
		AMap<AString, ACell> result = Maps.empty();
		for (AMap<AString, ACell> tier : tiers) {
			for (var entry : tier.entrySet()) {
				result = (entry.getValue() == null)
					? result.dissoc(entry.getKey())
					: result.assoc(entry.getKey(), entry.getValue());
			}
		}
		return result;
	}

	/** Shared with compound agent completion so both paths use the same format. */
	static AMap<AString, ACell> appendTurnsToRoot(AMap<AString, ACell> session,
			AVector<ACell> turnsToAppend) {
		AVector<ACell> frames = sessionFrames(session);
		AMap<AString, ACell> rootFrame;
		if (frames.count() == 0) {
			rootFrame = GoalTreeContext.createFrame("");
			frames = Vectors.of(rootFrame);
		} else {
			rootFrame = (AMap<AString, ACell>) frames.get(0);
		}
		rootFrame = GoalTreeContext.appendTurns(rootFrame, turnsToAppend);
		frames = frames.assoc(0, rootFrame);
		session = session.assoc(K_FRAMES, frames);
		return bumpTurnMeta(session, turnsToAppend);
	}

	static AMap<AString, ACell> bumpTurnMeta(AMap<AString, ACell> session,
			AVector<ACell> appended) {
		if (session.get(K_META) instanceof AMap) {
			AMap<AString, ACell> meta = (AMap<AString, ACell>) session.get(K_META);
			long current = (meta.get(K_TURNS) instanceof CVMLong cl) ? cl.longValue() : 0;
			meta = meta.assoc(K_TURNS, CVMLong.create(current + appended.count()));
			long updated = (meta.get(Fields.UPDATED) instanceof CVMLong cl) ? cl.longValue() : 0;
			for (long i = 0; i < appended.count(); i++) {
				ACell ts = RT.getIn(appended.get(i), K_TURN_TS);
				if (ts instanceof CVMLong cl) updated = Math.max(updated, cl.longValue());
			}
			if (updated > 0) meta = meta.assoc(Fields.UPDATED, CVMLong.create(updated));
			session = session.assoc(K_META, meta);
		}
		return session;
	}

	static AVector<ACell> sessionFrames(AMap<AString, ACell> session) {
		if (!session.containsKey(K_FRAMES)) return Vectors.empty();
		AVector<ACell> frames = RT.ensureVector(session.get(K_FRAMES));
		if (frames == null) throw new IllegalArgumentException(INVALID_SESSION_FRAMES);
		return frames;
	}

	static AVector<ACell> rootHistory(AVector<ACell> frames) {
		if (frames == null || frames.isEmpty() || !(frames.get(0) instanceof AMap root)) {
			return Vectors.empty();
		}
		AMap<AString, ACell> frame = (AMap<AString, ACell>) root;
		AVector<ACell> conversation = GoalTreeContext.history(frame.containsKey(K_CONVERSATION)
			? GoalTreeContext.migrateFrame(frame) : frame);
		return conversation != null ? conversation : Vectors.empty();
	}

	static AMap<AString, ACell> bumpMetaTokens(AMap<AString, ACell> session,
			AMap<AString, ACell> cycleTokens) {
		if (!(session.get(K_META) instanceof AMap)) return session;
		AMap<AString, ACell> meta = (AMap<AString, ACell>) session.get(K_META);
		AMap<AString, ACell> totals = (meta.get(Fields.TOKENS) instanceof AMap tm)
			? (AMap<AString, ACell>) tm : Maps.empty();
		for (AString k : new AString[] {Fields.INPUT, Fields.OUTPUT, Fields.TOTAL,
				Fields.CACHE_READ, Fields.CACHE_WRITE, Fields.CACHE_WRITE_5M, Fields.CACHE_WRITE_1H}) {
			if (!(cycleTokens.get(k) instanceof CVMLong measured)) continue;
			long add = measured.longValue();
			long current = (totals.get(k) instanceof CVMLong cl) ? cl.longValue() : 0;
			totals = totals.assoc(k, CVMLong.create(current + add));
		}
		if (totals.count() == 0) return session;
		return session.assoc(K_META, meta.assoc(Fields.TOKENS, totals));
	}

	static AMap<AString, ACell> drainPendingPrefix(AMap<AString, ACell> session,
			long drainCount) {
		AVector<ACell> pending = (session.get(K_PENDING) instanceof AVector pv)
			? (AVector<ACell>) pv : Vectors.empty();
		long drop = Math.min(drainCount, pending.count());
		AVector<ACell> remaining = Vectors.empty();
		for (long i = drop; i < pending.count(); i++) {
			remaining = remaining.conj(pending.get(i));
		}
		return session.assoc(K_PENDING, remaining);
	}

}
