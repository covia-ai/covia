package covia.venue;

import java.util.function.Consumer;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import covia.api.Fields;
import covia.grid.Job;

/**
 * Venue-hosted Job. Persists state transitions to the caller's per-user
 * lattice, evicts from {@link JobManager}'s active cache on terminal,
 * and fans out each update to a listener (SSE / MCP notifications).
 *
 * <p>Replaces the anonymous {@code Job} subclasses that JobManager
 * previously constructed. Base {@link Job} is a thinner client-facing
 * handle — venue-specific machinery lives here.</p>
 */
public class VenueJob extends Job {

	private final JobManager manager;
	private final AMap<AString, ACell> meta;
	private final AString callerDID;

	private volatile Consumer<Job> updateListener;

	VenueJob(AMap<AString, ACell> record, AMap<AString, ACell> meta,
			AString callerDID, JobManager manager) {
		super(record);
		this.manager = manager;
		this.meta = meta;
		this.callerDID = callerDID;
	}

	/**
	 * Sets a listener notified after each state update. Used by SSE / MCP
	 * notification fan-out.
	 */
	public void setUpdateListener(Consumer<Job> listener) {
		this.updateListener = listener;
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
		Consumer<Job> listener = this.updateListener;
		if (listener != null) listener.accept(this);
	}
}
