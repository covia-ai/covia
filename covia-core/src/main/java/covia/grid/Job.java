package covia.grid;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.lang.RT;
import covia.api.Fields;

/**
 * Class representing a Covia Job
 * 
 * Does not access the grid itself: querying should be done via Covia Grid client
 */
public class Job {
	// Job status data
	private AMap<AString, ACell> data;
	protected boolean cancelled=false;
	protected CompletableFuture<ACell> resultFuture=null;
	
	Job(AMap<AString, ACell> status) {
		this.data=status;
	}

	public static boolean isFinished(AMap<AString, ACell> job) {
		AString status=RT.ensureString(job.get(Fields.JOB_STATUS_FIELD));	
		if (status==null) throw new Error("Job status should never be null");
		if (status.equals(Status.COMPLETE)) return true;
		if (status.equals(Status.FAILED)) return true;
		if (status.equals(Status.CANCELLED)) return true;
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
	 * Sets the Job data (e.g. after polling for a status result). No effect if Job is already finished.
	 * @param data
	 */
	public synchronized void setData(AMap<AString, ACell> data) {
		if (isFinished()) return;
		this.data=data;
	}
	
	/**
	 * Sets the future for the Job result. Can only be done once.
	 * @param future future to set
	 */
	public synchronized void setFuture(CompletableFuture<ACell> future) {
		if (resultFuture!=null) throw new IllegalStateException("Result future already set");
		this.resultFuture=future;
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
		if (!isComplete()) throw new IllegalStateException("Job has no output, status is "+getStatus());
		return RT.get(data, Fields.OUTPUT);
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
}
