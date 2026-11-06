package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.prim.CVMLong;
import java.util.function.UnaryOperator;
import java.util.concurrent.atomic.AtomicReference;
import covia.exception.AuthException;
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
	private final OutputRules rules;
	private final JobState record;
	/** Last observed snapshot, used only if the primary record/account is erased. */
	private volatile AMap<AString, ACell> lastKnown;
	private final User account;
	/** Whether venue-wide observers (SSE/MCP telemetry) should see updates. */
	private final boolean observable;
	/** Set only by public top-level result dispatch, never from operation metadata. */
	private final boolean directRun;

	/** An observable recorded Job over its existing record. */
	VenueJob(JobState record, AMap<AString, ACell> data, OutputRules rules,
			AString ownerDID, JobManager manager) {
		this(record, data, rules, ownerDID, manager, true, false);
	}

	/**
	 * @param record The Job's record, or null for a memory-only Job
	 * @param data The Job's data when the handle is made
	 * @param rules What the Job's operation declared about its data when the Job was admitted
	 */
	VenueJob(JobState record, AMap<AString, ACell> data, OutputRules rules,
			AString ownerDID, JobManager manager, boolean observable, boolean directRun) {
		super((record == null) ? data : null, record != null);
		this.manager = manager;
		this.rules = (rules == null) ? OutputRules.NONE : rules;
		this.account = manager.account(ownerDID);
		this.record = record;
		this.lastKnown = data;
		this.observable = observable;
		this.directRun = directRun;
	}

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
		var result = new AtomicReference<AMap<AString, ACell>>();
		AMap<AString, ACell> committed;
		try {
			manager.venue().transact(draft -> {
				JobState current = recordIn(draft);
				if (current == null) throw new AuthException(Users.ACCOUNT_DELETED);
				result.set(edit(draft, current, data -> {
					AMap<AString, ACell> next = updater.apply(data);
					original.set(next);
					return next == null ? null : rules.redactJob(next);
				}));
			});
			committed = result.get();
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
	private JobState recordIn(VenueState draft) {
		User owner = draft.users().get(account.getDID());
		if (owner == null) return null;
		Users.requireCurrent(owner.get(), convex.core.lang.RT.getIn(account.get(), Users.GENERATION));
		return owner.jobs().get(getID());
	}

	/** Edits the record in the draft; an edit that finishes the Job removes its entry with it. */
	private AMap<AString, ACell> edit(VenueState draft, JobState job, UnaryOperator<AMap<AString, ACell>> edit) {
		AMap<AString, ACell> updated = job.update(edit);
		if (updated != null && Job.isFinished(updated)) draft.jobIndex().remove(getID());
		return updated;
	}

	/** Edit this recorded Job in a caller-owned venue transaction. The updater must
	 * be pure. Run only the successful draft's returned notification after commit;
	 * null means the row was missing, terminal, or the update was rejected. */
	public Runnable updateIn(VenueState draft, UnaryOperator<AMap<AString, ACell>> updater) {
		if (isMemoryOnly()) throw new IllegalStateException(RECORDED_JOB_REQUIRED);
		JobState job = recordIn(draft);
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
			fail(e, decorate);
			return;
		}
		super.completeWith(result, decorate);
	}
}
