package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import covia.api.Fields;
import covia.grid.Job;

/**
 * Venue-hosted Job. Persists state transitions to the caller's per-user
 * lattice, evicts from {@link JobManager}'s active cache on terminal, and
 * fans each update out to the manager's cross-cutting listeners (REST SSE,
 * MCP notifications). Per-Job subscribers live on {@link Job} itself.
 */
public class VenueJob extends Job {

	private final JobManager manager;
	private final AMap<AString, ACell> meta;
	private final AString callerDID;
	/** Private (memory-only) job (#192): never persisted, gone on restart. */
	private final boolean privateJob;

	VenueJob(AMap<AString, ACell> record, AMap<AString, ACell> meta,
			AString callerDID, JobManager manager) {
		this(record, meta, callerDID, manager, false);
	}

	VenueJob(AMap<AString, ACell> record, AMap<AString, ACell> meta,
			AString callerDID, JobManager manager, boolean privateJob) {
		super(record);
		this.manager = manager;
		this.meta = meta;
		this.callerDID = callerDID;
		this.privateJob = privateJob;
	}

	/** True for a private (memory-only) job — {@link JobManager#persistJobRecord}
	 *  is a no-op for it (#192). */
	public boolean isPrivate() {
		return privateJob;
	}

	@Override
	public AMap<AString, ACell> processUpdate(AMap<AString, ACell> newData) {
		return newData.assoc(Fields.UPDATED, CVMLong.create(Utils.getCurrentTimestamp()));
	}

	@Override
	public void onUpdate(AMap<AString, ACell> newData) {
		if (!privateJob) {
			manager.persistJobRecord(getID(),
				JobManager.redactJobSecrets(newData, meta), callerDID);
		}
		manager.notifyGlobalListeners(this);
	}

	@Override
	public void onFinish(AMap<AString, ACell> finalData) {
		manager.evictActive(getID());
	}

	@Override
	public void completeWith(ACell result) {
		try {
			manager.validateOutput(meta, result);
		} catch (RuntimeException e) {
			fail(e.getMessage() != null ? e.getMessage() : e.toString());
			return;
		}
		super.completeWith(result);
	}
}
