package covia.grid;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.lang.RT;
import covia.api.Fields;

/**
 * Class representing a Covia Job
 * 
 * Does not access the grid itself: querying should be done via Covia Grid client
 */
public class Job {
	private AMap<AString, ACell> data;
	
	public Job(AMap<AString, ACell> status) {
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

	public boolean isComplete() {
		AString value=RT.ensureString(data.get(Fields.JOB_STATUS_FIELD));	
		return Status.COMPLETE.equals(value);
	}

	public void setData(AMap<AString, ACell> data) {
		this.data=data;
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
	 * @return Job output, or null is not finished
	 */
	public ACell getOutput() {
		return RT.get(data, Fields.OUTPUT);
	}


}
