package covia.exception;

import convex.core.data.Blob;

/**
 * Signals that a client-side polling thread stopped tracking a remote Job
 * before the Job reached a terminal state — typically because the polling
 * timeout elapsed or the transport failed. Distinct from
 * {@link JobFailedException}: the remote work is <b>not known to have
 * failed</b>; the client simply lost visibility.
 *
 * <p>Recovery: callers who care about the actual outcome should call
 * {@code client.getJobStatus(jobId)} to re-acquire the latest state. The
 * Job's last-known local status is preserved and accessible via
 * {@link #getLastKnownStatus()}.</p>
 */
@SuppressWarnings("serial")
public class JobPollingFailedException extends CoviaException {

	private final Blob jobId;
	private final String lastKnownStatus;

	public JobPollingFailedException(Blob jobId, String lastKnownStatus, Throwable cause) {
		super("Polling stopped for job " + jobId + " (last known status: " + lastKnownStatus
				+ "); the remote job may still be running — call getJobStatus("
				+ jobId + ") to re-acquire.", cause);
		this.jobId = jobId;
		this.lastKnownStatus = lastKnownStatus;
	}

	public Blob getJobId() {
		return jobId;
	}

	public String getLastKnownStatus() {
		return lastKnownStatus;
	}
}
