package covia.exception;

import covia.grid.Job;

@SuppressWarnings("serial")
public class JobFailedException extends CoviaException {

	private Job job;

	public JobFailedException(Job job) {
		super(job.getErrorMessage());
		this.job=job;
	}

	/** A job failed by {@code e}: its reason if expected, else its type, never its text. */
	public JobFailedException(Exception e) {
		this(Job.failure(CoviaException.describe(e)));
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
