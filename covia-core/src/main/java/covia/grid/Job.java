package covia.grid;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.JobFailedException;

/**
 * Class representing a Covia Job
 * 
 * Does not access the grid itself: querying should be done via Covia Grid client
 */
public class Job {
	/** Job status data */
	private AMap<AString, ACell> data;
	
	protected boolean cancelled=false;
	protected CompletableFuture<ACell> resultFuture=null;
	
	protected Job(AMap<AString, ACell> status) {
		this.data=status;
	}

	public static boolean isFinished(AMap<AString, ACell> jobData) {
		AString status=RT.ensureString(jobData.get(Fields.JOB_STATUS_FIELD));	
		if (status==null) throw new Error("Job status should never be null");
		if (status.equals(Status.COMPLETE)) return true;
		if (status.equals(Status.FAILED)) return true;
		if (status.equals(Status.CANCELLED)) return true;
		if (status.equals(Status.REJECTED)) return true;
		return false;
	}

	public static AString parseID(Object a) {
		if (a instanceof String s) {
			return Blob.parse(s).toCVMHexString();
		} else if (a instanceof AString s) {
			long n=s.count();
			if ((n>=2)&&s.charAt(0)=='0'&&s.charAt(1)=='x') {
				s=s.slice(2);
			}
			return s;
		} 
		throw new IllegalArgumentException("Unable to convert to Job ID: "+a);
	}

	public static Job create(AMap<AString,ACell> status) {
		return new Job(status);
	}
	
	/**
	 * Gets the remote ID of the Job. Valid for the venue which is executing it.
	 * Typically a 32 char (16 byte) hex string
	 * @return Job ID
	 */
	public AString getID() {
		return (AString)RT.get(data, Fields.ID);
	}

	/**
	 * Checks if this job is finished (COMPLETE, FAILED or CANCELLED status)
	 * @return true if finished
	 */
	public boolean isFinished() {
		return isFinished(this.data);
	}

	/**
	 * Checks if this job is COMPLETE, i.e. has a valid result
	 * @return true if finished
	 */
	public synchronized boolean isComplete() {
		AString value=RT.ensureString(data.get(Fields.JOB_STATUS_FIELD));	
		return Status.COMPLETE.equals(value);
	}
	
	/**
	 * Checks if this job is paused (PAUSED, INPUT_REQUIRED, or AUTH_REQUIRED status)
	 * @return true if paused
	 */
	public synchronized boolean isPaused() {
		AString status = getStatus();
		return Status.PAUSED.equals(status) || 
		       Status.INPUT_REQUIRED.equals(status) || 
		       Status.AUTH_REQUIRED.equals(status);
	}

	/**
	 * Sets the Job data (e.g. after polling for a status result). No effect if Job is already finished.
	 * @param data Job status data
	 */
	public synchronized void updateData(AMap<AString, ACell> data) {
		if (isFinished()) return;
		data=processUpdate(data);
		if (data==null) return; // update cancelled
		this.data=data;
		onUpdate(data);
		if (isFinished()) {
			if (resultFuture!=null) resultFuture.complete(getOutput());
			onFinish(data);
		};
	}
	
	/**
	 * Method called to modify data before an update. Subclasses may override to 
	 * @param newData new status data of the Job
	 * @return updated version of the data to be stored. Returning null will cancel the update
	 */
	public AMap<AString, ACell> processUpdate(AMap<AString, ACell> newData) {
		return newData;
	}
	
	/**
	 * Method called after data is updated. Subclasses may override this to provide custom responses
	 * @param newData new status data of the Job
	 */
	public void onUpdate(AMap<AString, ACell> newData) {
		// Empty to allow overrides
	}
	
	/**
	 * Method called after job is finished. Subclasses may override this to provide custom responses
	 * @param finalData final status data of the Job
	 */
	public void onFinish(AMap<AString, ACell> finalData) {
		// Empty to allow overrides
	}

	/**
	 * Sets the future for the Job result. Can only be done once.
	 * @param future future to set
	 */
	public synchronized void setFuture(CompletableFuture<ACell> future) {
		if (resultFuture!=null) throw new IllegalStateException("Result future already set");
		this.resultFuture=future;
		
		// complete the future if the job is already finished
		if (isFinished()) {
			resultFuture.complete(getOutput());
		}
	}

	public AString getStatus() {
		return RT.ensureString(RT.get(data, Fields.JOB_STATUS_FIELD));
	}

	/**
	 * Gets the full Job data
	 * @return Job data as a JSON-compatible Map
	 */
	public AMap<AString, ACell> getData() {
		return data;
	}

	/**
	 * Gets the output of this Job, assuming it is COMPLETE
	 * @return Job output, or throw if not finished
	 */
	public ACell getOutput() {
		if (!isFinished()) throw new IllegalStateException("Job has no output, status is "+getStatus());
		if (!isComplete()) throw new JobFailedException(this);
		return RT.get(data, Fields.OUTPUT);
	}
	
	/**
	 * Gets the error message of this Job, if it has FAILED or REJECTED
	 * @return Error message as a string, or null if no message or job is not failed/rejected
	 */
	public String getErrorMessage() {
		AString status = getStatus();
		if (status != Status.FAILED && status != Status.REJECTED) return null;
		ACell errorField = RT.get(data, Fields.JOB_ERROR_FIELD);
		return errorField != null ? errorField.toString() : null;
	}

	/**
	 * Cancel this Job, if it is not already complete
	 */
	public synchronized void cancel() {
		cancelled=true;
		if (resultFuture!=null) {
			resultFuture.completeExceptionally(new CancellationException("Job cancelled: "+getID()));
		}
	}

	public synchronized void setStatus(AString newStatus) {
		if (isFinished()) throw new IllegalStateException("Job already finished");
		updateData(data.assoc(Fields.STATUS, newStatus));
	}

	public synchronized void completeWith(ACell result) {
		if (isFinished()) throw new IllegalStateException("Job already finished");
		AMap<AString, ACell> newData = getData();
		newData=newData.assoc(Fields.JOB_STATUS_FIELD, Status.COMPLETE);
		newData=newData.assoc(Fields.OUTPUT, result);
		updateData(newData);
	}

	/**
	 * Waits for the job to complete and resturns the result. 
	 * @param <T> Expected type of ACell result, for convenience. Usually an AMap<String,ACell>
	 * @return Result of job
	 * @throws JobFailedException if job failed
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> T  awaitResult() {
		try {
			return (T) getFuture().join();
		} catch (CompletionException  e) {
	        Throwable cause = e.getCause();
	        if (cause instanceof JobFailedException) {
	            throw (JobFailedException) cause;
	        }
	        // Mark the job as failed
	        fail(cause.getMessage());
	        throw new JobFailedException(this);
	    }
	}

	private CompletableFuture<ACell> getFuture() {
		if (resultFuture!=null) return resultFuture;
		synchronized(this) {
			if (resultFuture!=null) return resultFuture;
			resultFuture=new CompletableFuture<ACell>();
			if (isFinished()) resultFuture.complete(getOutput());
			return resultFuture;
		}
	}

	/**
	 * Causes the job to fail, if it is not already finished
	 * @param message
	 */
	public synchronized void fail(String message) {
		if (isFinished()) return;
		update(job->{
			job=job.assoc(Fields.JOB_STATUS_FIELD, Status.FAILED);
			job=job.assoc(Fields.JOB_ERROR_FIELD, Strings.create(message));
			return job;
		});
	}

	/**
	 * Updates the job data atomically using the given update function
	 * @param updater
	 */
	public synchronized void update(UnaryOperator<AMap<AString,ACell>> updater) {
		AMap<AString, ACell> oldData = getData();
		AMap<AString, ACell> newData=updater.apply(oldData);
		updateData(newData);
	}

	/**
	 * Create a job with a specific failure message
	 * @param message
	 * @return Failed Job
	 */
	public static Job failure(String message) {
		return Job.create(Maps.of(
			Fields.JOB_STATUS_FIELD, Status.FAILED,
			Fields.JOB_ERROR_FIELD, Strings.create(message)
		));
	}
	
	/**
	 * Create a job with a specific rejection message
	 * @param message
	 * @return Rejected Job
	 */
	public static Job rejected(String message) {
		return Job.create(Maps.of(
			Fields.JOB_STATUS_FIELD, Status.REJECTED,
			Fields.JOB_ERROR_FIELD, Strings.create(message)
		));
	}
	
	/**
	 * Create a job with PAUSED status
	 * @param message Optional message explaining why the job was paused
	 * @return Paused Job
	 */
	public static Job paused(String message) {
		AMap<AString, ACell> jobData = Maps.of(Fields.JOB_STATUS_FIELD, Status.PAUSED);
		if (message != null && !message.isEmpty()) {
			jobData = jobData.assoc(Fields.MESSAGE, Strings.create(message));
		}
		return Job.create(jobData);
	}
}
