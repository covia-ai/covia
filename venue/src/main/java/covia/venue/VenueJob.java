package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import java.util.function.UnaryOperator;
import java.util.concurrent.atomic.AtomicReference;
import covia.exception.AuthException;
import covia.exception.CoviaException;
import covia.exception.FaultException;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.grid.Job;

/**
 * Runtime handle over the caller's primary lattice job component.
 * Evicts from {@link JobManager}'s active cache on terminal, and
 * fans each update out to the manager's cross-cutting listeners (REST SSE,
 * MCP notifications). Per-Job subscribers live on {@link Job} itself.
 */
public class VenueJob extends Job {
	public static final String RECORDED_JOB_REQUIRED = "A transaction requires a recorded Job";

	private final JobManager manager;
	/** The adapter that runs this Job and ends it when it is cancelled; null when none does. */
	private final AAdapter adapter;
	private final OutputRules rules;
	private final JobRecord record;
	/** Last observed snapshot, used only if the primary record/account is erased. */
	private volatile AMap<AString, ACell> lastKnown;
	private final User account;
	/** Whether venue-wide observers (SSE/MCP telemetry) should see updates. */
	private final boolean observable;
	/** Set only by public top-level result dispatch, never from operation metadata. */
	private final boolean directRun;
	/** What the adapter keeps for a memory-only Job, which has no entry ({@link JobManager#adapterData}). */
	private final AtomicReference<AMap<AString, ACell>> heldData = new AtomicReference<>();

	/** An observable recorded Job over its existing record. */
	VenueJob(JobRecord record, AMap<AString, ACell> data, OutputRules rules,
			AString ownerDID, JobManager manager, AAdapter adapter) {
		this(record, data, rules, ownerDID, manager, true, false, adapter);
	}

	/**
	 * @param record The Job's record, or null for a memory-only Job
	 * @param data The Job's data when the handle is made
	 * @param rules What the Job's operation declared about its data when the Job was admitted
	 * @param adapter The adapter that runs the Job, which ends it when it is
	 *        cancelled; null when no adapter runs it
	 */
	VenueJob(JobRecord record, AMap<AString, ACell> data, OutputRules rules,
			AString ownerDID, JobManager manager, boolean observable, boolean directRun, AAdapter adapter) {
		super((record == null) ? data : null, record != null);
		this.adapter = adapter;
		this.manager = manager;
		this.rules = (rules == null) ? OutputRules.NONE : rules;
		this.account = manager.account(ownerDID);
		this.record = record;
		this.lastKnown = data;
		this.observable = observable;
		this.directRun = directRun;
	}

	/** The adapter that runs this Job, or null when none does. */
	public AAdapter adapter() { return adapter; }

	/** Direct result-only call with no durable Job or venue-wide telemetry. */
	public boolean isDirectRun() { return directRun && isMemoryOnly() && !observable; }

	/** True for a transient Job wrapper; persistence is a no-op for it. */
	public boolean isMemoryOnly() {
		return !isRecorded();
	}

	User account() { return account; }

	/** Fence nested work from a deleted lifecycle even after the DID is recreated. */
	void requireAccount() { account.get(); }

	/** What the Job's operation declared about its data when the Job was admitted. */
	OutputRules rules() {
		return rules;
	}

	/** What the adapter keeps for this memory-only Job; null once it has finished. */
	AMap<AString, ACell> adapterData() {
		return isFinished() ? null : heldData.get();
	}

	/** Changes what the adapter keeps for this memory-only Job, unless it has finished. */
	void updateAdapterData(UnaryOperator<AMap<AString, ACell>> change) {
		if (isFinished()) return;
		heldData.updateAndGet(kept -> change.apply((kept == null) ? convex.core.data.Maps.empty() : kept));
	}

	@Override
	public AMap<AString, ACell> processUpdate(AMap<AString, ACell> newData) {
		// Recorded jobs get their timestamp from the component's editing cursor.
		if (!hasLocalRecord()) return newData;
		CVMLong timestamp = account.getContext().currentTimestamp();
		ACell previous = newData.get(Fields.UPDATED);
		if (previous instanceof CVMLong old
				&& old.longValue() > timestamp.longValue()) timestamp = old;
		return newData.assoc(Fields.UPDATED, timestamp);
	}

	/** Read the primary row, never a separately updated in-memory copy. */
	@Override public AMap<AString, ACell> getData() {
		if (hasLocalRecord()) return super.getData();
		AMap<AString, ACell> current = primaryRecord();
		if (current != null) {
			lastKnown = current;
			return current;
		}
		detachRecord(lastKnown);
		return super.getData();
	}

	/** Query APIs must not expose the detached handle of an erased job. */
	AMap<AString, ACell> primaryRecord() {
		if (record == null) return null;
		try { return record.getRecord(); }
		catch (AuthException erased) { return null; }
	}

	@Override protected State updateState(UnaryOperator<AMap<AString, ACell>> updater, Throwable failure) {
		if (hasLocalRecord()) return super.updateState(updater, failure);
		var original = new AtomicReference<AMap<AString, ACell>>();
		AMap<AString, ACell> committed;
		try {
			committed = manager.venue().transact(draft -> {
				JobRecord current = recordIn(draft);
				if (current == null) throw new AuthException(Users.ACCOUNT_DELETED);
				return edit(draft, current, data -> {
					AMap<AString, ACell> next = updater.apply(data);
					original.set(next);
					return next == null ? null : rules.redactJob(next);
				});
			});
		} catch (AuthException erased) {
			if (primaryRecord() != null) throw erased; // an updater rejection is not erasure
			committed = null;
		}
		if (committed != null) {
			lastKnown = committed;
			// Only the successful attempt supplies an unredacted in-process result.
			AMap<AString, ACell> outcome = committed.containsKey(Fields.OUTPUT)
				? committed.assoc(Fields.OUTPUT, original.get().get(Fields.OUTPUT)) : committed;
			return new State(outcome, failure);
		}
		if (primaryRecord() != null) return null;
		detachRecord(lastKnown);
		return super.updateState(updater, failure);
	}

	@Override public void onUpdate(AMap<AString, ACell> newData) {
		if (observable) manager.notifyGlobalListeners(this);
	}

	/**
	 * This Job's record in a venue transaction's draft, in the account
	 * lifecycle the handle was made in.
	 *
	 * @return the record, or null when the owner's account or the row is gone
	 * @throws AuthException when the account was deleted and made again since
	 */
	private JobRecord recordIn(VenueState draft) {
		User owner = draft.users().user(account.getDID());
		if (owner == null) return null;
		Users.requireCurrent(owner.get(), convex.core.lang.RT.getIn(account.get(), Users.GENERATION));
		return owner.jobs().job(getID());
	}

	/**
	 * Edits the record in the draft; an edit that finishes the Job removes its
	 * entry with it, and one that cancels it ends what its adapter linked to it
	 * there too. A finished record takes no edit, so a cancellation seen here is
	 * the Job's one transition to CANCELLED.
	 */
	private AMap<AString, ACell> edit(VenueState draft, JobRecord job, UnaryOperator<AMap<AString, ACell>> edit) {
		AMap<AString, ACell> updated = job.update(edit);
		if (updated != null && Job.isFinished(updated)) {
			if (covia.grid.Status.CANCELLED.equals(updated.get(Fields.STATUS))) {
				endLinks(draft, draft.jobIndex().adapterData(getID()));
			}
			draft.jobIndex().remove(getID());
		}
		return updated;
	}

	/**
	 * Ends what the Job's adapter linked to it, in the cancelling transaction
	 * ({@link AAdapter#cancelIn}).
	 *
	 * @param adapterData What the adapter keeps for the Job, or null
	 */
	protected void endLinks(VenueState draft, AMap<AString, ACell> adapterData) {
		if (adapter != null) adapter.cancelIn(draft, this, adapterData);
	}

	/** Stops the Job's running work once its cancellation has committed ({@link AAdapter#stop}). */
	protected void stopWork() {
		if (adapter != null) adapter.stop(this);
	}

	/**
	 * A Job held only here (memory-only, or detached from an erased record)
	 * committed its cancellation alone, so its links end in a transaction of
	 * their own; a recorded Job's ended in the cancelling one ({@link #edit}).
	 */
	@Override
	protected void onCancelled() {
		if (hasLocalRecord()) manager.venue().execute(draft -> endLinks(draft, heldData.get()));
		stopWork();
	}

	/** Whether the Job's adapter can suspend its work ({@link AAdapter#canPause}). */
	@Override
	protected boolean supportsPause() { return adapter != null && adapter.canPause(this); }

	@Override
	protected void onPaused() { if (adapter != null) adapter.pause(this); }

	/** Whether the Job's adapter can restart its work ({@link AAdapter#canResume}). */
	@Override
	protected boolean supportsResume() { return adapter != null && adapter.canResume(this); }

	@Override
	protected void onResumed() { if (adapter != null) adapter.resume(this); }

	/** Edit this recorded Job in a caller-owned venue transaction. The updater must
	 * be pure. Run only the successful draft's returned notification after commit;
	 * null means the row was missing, terminal, or the update was rejected. */
	public Runnable updateIn(VenueState draft, UnaryOperator<AMap<AString, ACell>> updater) {
		if (isMemoryOnly()) throw new IllegalStateException(RECORDED_JOB_REQUIRED);
		JobRecord job = recordIn(draft);
		if (job == null) return null;
		var original = new AtomicReference<AMap<AString, ACell>>();
		AMap<AString, ACell> updated = edit(draft, job, current -> {
			if (Job.isFinished(current)) return null;
			AMap<AString, ACell> next = updater.apply(current);
			if (next == null) return null;
			if (covia.grid.Status.COMPLETE.equals(next.get(Fields.STATUS))) manager.validateOutput(rules, next.get(Fields.OUTPUT));
			original.set(next);
			return rules.redactJob(next);
		});
		if (updated == null) return null;
		AMap<AString, ACell> outcome = updated.containsKey(Fields.OUTPUT)
			? updated.assoc(Fields.OUTPUT, original.get().get(Fields.OUTPUT)) : updated;
		return () -> {
			lastKnown = updated;
			publishUpdate(new State(outcome, null), null);
		};
	}

	/**
	 * Completes this recorded Job with {@code result} in a caller-owned venue
	 * transaction, as {@link #completeWith} does: a result its operation's
	 * output schema refuses fails the Job instead, so a refusal never aborts the
	 * transaction. Run only the successful draft's returned notification after
	 * commit; it settles the Job's waiters.
	 *
	 * @return the notification, or null when the record is missing or the Job has finished
	 */
	public Runnable completeIn(VenueState draft, ACell result) {
		try {
			manager.validateOutput(rules, result);
		} catch (RuntimeException | Error refused) {
			return failIn(draft, outputRefused(refused).getMessage());
		}
		return updateIn(draft, data -> data.assoc(Fields.STATUS, covia.grid.Status.COMPLETE).assoc(Fields.OUTPUT, result));
	}

	/**
	 * Fails this recorded Job with {@code message} in a caller-owned venue
	 * transaction, as {@link #fail(String)} does. Run only the successful
	 * draft's returned notification after commit.
	 *
	 * @return the notification, or null when the record is missing or the Job has finished
	 */
	public Runnable failIn(VenueState draft, String message) {
		return updateIn(draft, data -> data.assoc(Fields.STATUS, covia.grid.Status.FAILED)
			.assoc(Fields.ERROR, Strings.create(message)).dissoc(Fields.ERROR_CODE));
	}

	/**
	 * Cancels this recorded Job in a caller-owned venue transaction, as
	 * {@link #cancel(String)} does, ending what its adapter linked to it in the
	 * same transaction. Run only the successful draft's returned notification
	 * after commit; it stops the Job's running work.
	 *
	 * @return the notification, or null when the record is missing or the Job has finished
	 */
	public Runnable cancelIn(VenueState draft, String reason) {
		String message = cancellationMessage(reason);
		return updateIn(draft, data -> data.assoc(Fields.STATUS, covia.grid.Status.CANCELLED)
			.assoc(Fields.ERROR, Strings.create(message)));
	}

	/** Called after a direct component edit commits, without replaying lifecycle effects. */
	void historyUpdated() {
		try { onUpdate(getData()); }
		finally { notifyListeners(); }
	}

	/** Progress reaches the venue's job observers as an update does. */
	@Override
	protected void onProgress(AMap<AString, ACell> newData) {
		if (observable) manager.notifyGlobalListeners(this);
	}

	@Override
	public void onFinish(AMap<AString, ACell> finalData) {
		manager.evictActive(getID());
	}

	@Override
	public void completeWith(ACell result, java.util.function.UnaryOperator<AMap<AString, ACell>> decorate) {
		try {
			manager.validateOutput(rules, result);
		} catch (RuntimeException | Error e) {
			fail(outputRefused(e), decorate);
			return;
		}
		super.completeWith(result, decorate);
	}

	/** What failed, when the venue's check of an operation's output fails unexpectedly. */
	public static final String CHECKING_OUTPUT = "Checking the operation's output";

	/** An output refusal as reported: a broken contract is the adapter's fault, a failing check the venue's. */
	private static CoviaException outputRefused(Throwable refused) {
		return FaultException.within(CHECKING_OUTPUT, FaultException.VENUE, refused);
	}
}
