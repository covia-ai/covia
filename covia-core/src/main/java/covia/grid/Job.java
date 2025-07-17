package covia.grid;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.lang.RT;
import covia.api.Fields;

public class Job {

	public static boolean isComplete(AMap<AString, ACell> job) {
		AString status=RT.ensureString(job.get(Fields.JOB_STATUS_FIELD));
		
		if (status==null) throw new Error("Job status should never be null");
		if (status.equals(Status.COMPLETE)) return true;
		if (status.equals(Status.FAILED)) return true;
		return false;
	}

}
