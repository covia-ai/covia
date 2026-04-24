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

	VenueJob(AMap<AString, ACell> record, AMap<AString, ACell> meta,
			AString callerDID, JobManager manager) {
		super(record);
		this.manager = manager;
		this.meta = meta;
		this.callerDID = callerDID;
	}

	@Override
	public AMap<AString, ACell> processUpdate(AMap<AString, ACell> newData) {
		newData = newData.assoc(Fields.UPDATED, CVMLong.create(Utils.getCurrentTimestamp()));
		manager.persistJobRecord(getID(), JobManager.redactOutputSecrets(newData, meta), callerDID);
		if (Job.isFinished(newData)) {
			manager.evictActive(getID());
		}
		return newData;
	}

	@Override
	public void onUpdate(AMap<AString, ACell> newData) {
		manager.notifyGlobalListeners(this);
	}
}
