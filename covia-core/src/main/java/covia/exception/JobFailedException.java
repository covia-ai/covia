package covia.exception;

import covia.grid.Job;

@SuppressWarnings("serial")
public class JobFailedException extends CoviaException {

	private Job job;

	public JobFailedException(Job job) {
		super(job.getErrorMessage());
		this.job=job;
	}

	public JobFailedException(Exception e) {
		this(Job.failure(e.toString()));
	}
	
	public JobFailedException(String message) {
		this(Job.failure(message));
	}

	public Job getJob() {
		return job;
	}
}
