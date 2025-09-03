package covia.adapter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.MapEntry;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSONUtils;
import convex.core.util.ThreadUtils;
import convex.core.util.Utils;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;

public class Orchestrator extends AAdapter {

	@Override
	public String getName() {
		return "orchestrator";
	}
	
	@Override
	public String getDescription() {
		return "Enables complex multi-step orchestration operations with dependency management and parallel execution. " +
			   "Supports sophisticated job orchestration with step dependencies, result aggregation, and error handling across multiple operations. " +
			   "Perfect for building complex AI workflows, data processing pipelines, and multi-service integrations with intelligent task coordination.";
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input) {
		throw new UnsupportedOperationException("Invalid call to orchestrator");
	}

	
	@Override
	public void invoke(Job job, String operation, ACell meta, ACell input) {
		AVector<?> steps=RT.ensureVector(RT.getIn(meta, Fields.OPERATION, Fields.STEPS));
		ACell resultSpec=RT.getIn(meta, Fields.OPERATION, Fields.RESULT);
		Orchestration orch=new Orchestration(job,input,steps,resultSpec);
		ThreadUtils.runVirtual(orch);
	}
	
	public class Orchestration implements Runnable {
		final Job job;
		final AString jobID;
		final AVector<?> steps;
		final int n;
		final ArrayList<SubTask> subTasks;
		final ACell resultSpec;
		final BlockingQueue<SubTask> completionQueue;
		final ACell orchInput;
		ACell orchOutput=null;
		
		public Orchestration(Job job, ACell input, AVector<?> steps, ACell resultSpec) {
			this.job=job;
			this.jobID=job.getID();
			this.steps=steps;
			this.orchInput=input;
			this.n=Utils.checkedInt(steps.count());
			completionQueue=new ArrayBlockingQueue<>(n);
			this.resultSpec=resultSpec;
			subTasks=new ArrayList<>();
			for (int i=0; i<n; i++) {
				AMap<AString, ACell> step=RT.ensureMap(steps.get(i));
				if (step==null) throw new IllegalArgumentException("Step must be defined as a map object but was: "+steps.get(i));
				SubTask task=new SubTask(i,step);
				subTasks.add(task);
			}		
		}

		private static final boolean DEBUG_ORCH=false;
		
		@Override
		public void run() {
			try {
				job.setStatus(Status.STARTED);
				int n=Utils.checkedInt(steps.count());
				HashSet<SubTask> todo=new HashSet<>(subTasks);
				ArrayList<SubTask> newlyComplete=new ArrayList<>();
				
				int stepsLeft=n;
				HashSet<SubTask> ready=new HashSet<>();
				while (stepsLeft>0) {
					// clear todo and newlycomplete for each iteration
					newlyComplete.clear();
					ready.clear();
					
					// search for ready tasks (waiting on zero dependencies
					for (SubTask task:todo) {
						if (task.deps.size()==0) {
							ready.add(task);
						}
					}
					
					for (SubTask task:ready) {
						ThreadUtils.runVirtual(task);
						if (DEBUG_ORCH) System.err.println("Started subtask "+task.stepNum);
						todo.remove(task);
					}
					
					// Wait for at least one subtask to complete
					while (newlyComplete.isEmpty()) {
						SubTask t;
						try {
							t = completionQueue.poll(10,TimeUnit.SECONDS);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							job.setStatus(Status.CANCELLED);
							return;
						}
						if (t==null) {
							if (job.isFinished()) return; // this includes CANCELLED, either way we're all done :-)
							continue;
						}
						if (DEBUG_ORCH) System.out.println("Job completed "+JSONUtils.toJSONPretty(t.subJob.getData()));
						newlyComplete.add(t);
						completionQueue.drainTo(newlyComplete);
					}
					
					// Handle completed subtasks
					for (SubTask task: newlyComplete) {
						stepsLeft-=1; // decrement number of steps left to complete
						
						if (!task.subJob.isComplete()) {
							// the subtask finished but was not complete, so the whole orchestration has failed
							job.fail("Failed due to subtask "+task.stepNum+" ("+task.subJob.getStatus()+")");
						}
						
						// mark dependency as completed for any subsequent steps
						Integer completedIndex=task.stepNum;
						for (int i=task.stepNum+1; i<n; i++) {
							subTasks.get(i).deps.remove(completedIndex);
						}
					}
					
					// update step status
					job.update(jd->{
						AVector<AMap<AString,ACell>> srs=RT.ensureVector(jd.get(Fields.STEPS));
						if (srs==null) srs=Vectors.repeat(null, n);
						for (int i=0; i<n; i++) {
							Job subJob=subTasks.get(i).subJob;
							if (subJob==null) continue;
							AMap<AString,ACell> ssd=subJob.getData();
							ssd=ssd.dissoc(Fields.STEPS); // remove child steps if any
							srs=srs.assoc(i, ssd);
						}
						jd=jd.assoc(Fields.STEPS, srs);
						return jd;
					});
				}
				
				// job already finished (cancelled or otherwise failed...)
				if (job.isFinished()) return;
				
				// All steps now complete, so can compute final result
				// this uses the spec from meta.operation.result
				orchOutput=computeInput(resultSpec,Vectors.empty());
				job.completeWith(orchOutput);
			} catch (Exception e) {
				job.fail(e.getMessage());
			}
		}
		
		@SuppressWarnings("unchecked")
		private ACell computeInput(ACell inputSpec, AVector<ACell> path) {
			if (inputSpec instanceof AVector v) {
				long n=v.count();
				if (n==0) throw new IllegalStateException("Empty vector in input spec");
				ACell code=v.get(0);
				if (code instanceof CVMLong cvmix) {
					int ix=Utils.checkedInt(cvmix.longValue());
					SubTask source=subTasks.get(ix);
					ACell value=RT.getIn(source.output, v.subVector(1,n-1).toCellArray());
					return value;
				} else if (Fields.CONST.equals(code)) {
					ACell value=v.get(1);
					return value;
				} else if (Fields.INPUT.equals(code)) {
					ACell value=RT.getIn(orchInput, v.subVector(1,n-1).toCellArray());
					return value;
				} else {
					throw new IllegalArgumentException("Unrecognised source type in: "+v);
				}
			} else if (inputSpec instanceof AMap m) {
				int mc=Utils.checkedInt(m.count());
				// Transform inputSpec into input values in key order
				for (int i=0; i<mc; i++) {
					MapEntry<AString,ACell> me=(MapEntry<AString,ACell>)m.entryAt(i);
					AString k=me.getKey();
					ACell spec=me.getValue();
					AVector<ACell> newPath=path.append(k);
					ACell value=computeInput(spec,newPath);
					m=m.assoc(k, value);
				}
				return m;
			} else {
				throw new IllegalArgumentException("Unrecognised input spec: "+inputSpec+" at path "+path);
			}
		}
		
		public class SubTask implements Runnable {
			AMap<AString, ACell> step;
			HashSet<Integer> deps;
			int stepNum;
			ACell input;
			ACell output;
			Job subJob=null;

			public SubTask(int i, AMap<AString, ACell> step) {
				this.step=step;
				this.stepNum=i;
				this.deps=scanDeps(new HashSet<Integer>(),step.get(Fields.INPUT));
			}

			private HashSet<Integer> scanDeps(HashSet<Integer> accDeps, ACell inputSpec) {
				// System.err.println("Scanning deps: "+inputSpec);
				if (inputSpec instanceof AVector v) {
					if (v.count()==0) throw new IllegalStateException("Empty vector in input value");
					ACell code=v.get(0);
					if (code instanceof CVMLong cvmix) {
						int ix=Utils.checkedInt(cvmix.longValue());
						if ((ix<0)||(ix>=stepNum)) {
							throw new IllegalArgumentException("Step can only refer to previous step(s) but was "+ix+" in step "+stepNum+" for spec "+inputSpec);
						}
						accDeps.add(ix);
					} 
				} else if (inputSpec instanceof AMap m) {
					int c=Utils.checkedInt(m.count());
					for (int i=0; i<c; i++) {
						scanDeps(accDeps,m.entryAt(i).getValue());
					}
				}
				return accDeps;
			}

			@Override
			public void run() {
				try {
					AString opId=RT.getIn(step, Fields.OP);
					input=computeInput(RT.get(step, Fields.INPUT),Vectors.empty());
					subJob =engine.invokeOperation(opId, input);
					output=subJob.awaitResult();
					completionQueue.add(this);
				} catch (Exception e) {
					if (DEBUG_ORCH) System.err.println(e);
					if (subJob!=null) subJob.fail("Failed to run orchestrator subtask: "+e.getMessage());
				} finally {
					if (subJob!=null) subJob.fail("Shouldn't see this, it means the SubJob did not complete properly! "+subJob);
				}
			}


		}
	}
}
