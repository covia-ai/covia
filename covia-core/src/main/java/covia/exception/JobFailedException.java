package covia.exception;

import covia.grid.Job;

@SuppressWarnings("serial")
public class JobFailedException extends CoviaException {

	private Job job;

	public JobFailedException(Job job) {
		super(reasonOf(job));
		this.job=job;
	}

	/** Why the Job did not complete: its error, which a cancelled Job records as its cancellation's reason. */
	private static String reasonOf(Job job) {
		String error = job.getErrorMessage();
		if (error != null) return error;
		convex.core.data.ACell recorded = job.getData().get(covia.api.Fields.ERROR);
		return (recorded == null) ? null : recorded.toString();
	}

	public JobFailedException(String message) {
		this(Job.failure(message));
	}

	public Job getJob() {
		return job;
	}

	/** Optional adapter-specific code, or null for an unclassified failure. */
	public String getErrorCode() {
		return job.getErrorCode();
	}
}
