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
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.json.schema.JsonSchema;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.MapEntry;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.core.util.ThreadUtils;
import convex.core.util.Utils;
import covia.api.Fields;
import covia.grid.Asset;
import covia.grid.Grid;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.Venue;
import covia.venue.RequestContext;

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
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		// Orchestrations are job-worthy: each run is a tracked Job with
		// sub-jobs per step. The internal path (LLM tool loop, context
		// assemble ops — e.g. a skills-bundled pipeline invoked as a tool)
		// delegates to the Job-aware dispatch — same RequestContext, same
		// grant scope — instead of rejecting the call (#85 fall-out).
		Job job = engine.jobs().invokeOperation(meta, input, ctx);
		return job.future().thenApply(x -> x);
	}

	private static final AString K_STRICT = Strings.intern("strict");

	@Override
	public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		AMap<AString, ACell> operation = RT.getIn(meta, Fields.OPERATION);
		AVector<?> steps=RT.ensureVector(operation.get(Fields.STEPS));
		if (steps == null) {
			job.fail("Orchestration metadata requires an 'operation.steps' array");
			return;
		}
		if (steps.isEmpty()) {
			job.fail("Orchestration requires at least one step");
			return;
		}
		ACell resultSpec=operation.get(Fields.RESULT);
		boolean strict = CVMBool.TRUE.equals(operation.get(K_STRICT));
		Orchestration orch=new Orchestration(job,ctx,input,steps,resultSpec,strict);
		ThreadUtils.runVirtual("orchestrator thread", orch);
	}

	public class Orchestration implements Runnable {
		final Job job;
		final Blob jobID;
		final AVector<?> steps;
		final int n;
		final ArrayList<SubTask> subTasks;
		final ACell resultSpec;
		final BlockingQueue<SubTask> completionQueue;
		final ACell orchInput;
		final RequestContext ctx;
		final boolean strict;
		ACell orchOutput=null;

		public Orchestration(Job job, RequestContext ctx, ACell input, AVector<?> steps, ACell resultSpec, boolean strict) {
			this.job=job;
			this.jobID=job.getID();
			this.steps=steps;
			this.orchInput=input;
			this.ctx=ctx;
			this.strict=strict;
			this.n=Utils.checkedInt(steps.count());
			completionQueue=new ArrayBlockingQueue<>(n);
			this.resultSpec=resultSpec;
			subTasks=new ArrayList<>();
			for (int i=0; i<n; i++) {
				// instanceof — RT.ensureMap(null) returns an empty map, which
				// would swallow the error case and fail confusingly downstream.
				if (!(steps.get(i) instanceof AMap<?,?> sm)) {
					throw new IllegalArgumentException("Orchestration step " + i + " must be an object");
				}
				@SuppressWarnings("unchecked")
				AMap<AString, ACell> step = (AMap<AString, ACell>) sm;
				if (RT.ensureString(step.get(Fields.OP)) == null) {
					throw new IllegalArgumentException(
						"Orchestration step " + i + " requires string field 'op'");
				}
				SubTask task=new SubTask(i,step);
				subTasks.add(task);
			}
			// The result spec is evaluated only after every step has completed, so a
			// malformed one used to surface at the very end — the worst moment, with
			// all side effects already applied and no step to blame. Validate it here
			// instead. It may reference ANY step, hence the limit of n. Dependencies
			// are irrelevant (nothing runs after it), so they are discarded.
			scanSpec(new HashSet<Integer>(), resultSpec, n, Vectors.empty());
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
						ThreadUtils.runVirtual("subtask "+task.stepNum,task);
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
						if (DEBUG_ORCH) System.out.println("Job completed "+JSON.printPretty(t.subJob.getData()));
						newlyComplete.add(t);
						completionQueue.drainTo(newlyComplete);
					}
					
					// Handle completed subtasks
					for (SubTask task: newlyComplete) {
						stepsLeft-=1; // decrement number of steps left to complete
						
						if (!task.subJob.isComplete()) {
							// the subtask finished but was not complete, so the whole orchestration has failed
							ACell detail = task.subJob.getData().get(Fields.ERROR);
							String op = String.valueOf(task.step.get(Fields.OP));
							String reason = (detail == null)
								? "ended with status " + task.subJob.getStatus()
								: conciseDetail(detail, 512);
							// Report the FIRST failure: it is the cause, and later ones are
							// likely consequences. Terminal job states are sticky, so a
							// second fail() would be ignored regardless.
							if (!job.isFinished()) {
								job.fail("Orchestration step " + task.stepNum + " (" + op + ") failed: " + reason);
							}
							// A failed step NEVER satisfies a dependency (#281). Leaving its
							// index in its dependents' `deps` is precisely what stops them
							// running: a step whose input never arrived must not execute with
							// nulls and apply its side effects. Marking the orchestration
							// failed does not stop the scheduler, so containment has to come
							// from the dependency graph itself.
							continue;
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

					// Fail fast: once the orchestration has failed, start nothing further.
					// Not just an optimisation — without this the loop keeps launching
					// steps that are merely independent of the failure, doing more work
					// and applying more side effects on behalf of a run that can no
					// longer succeed. Steps ALREADY in flight are deliberately left to
					// finish: their effects are in motion and cannot be unwound here,
					// and cancelling a sibling unrelated to the failure would be its own
					// surprise. Step statuses are recorded above before returning.
					if (job.isFinished()) return;
				}

				// job already finished (cancelled or otherwise failed...)
				if (job.isFinished()) return;
				
				// All steps now complete, so can compute final result
				// this uses the spec from meta.operation.result
				orchOutput=computeInput(resultSpec,Vectors.empty());
				job.completeWith(orchOutput);
			} catch (Exception e) {
				job.fail(describeFailure(e));
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
					if (ix < 0 || ix >= subTasks.size()) {
						throw new IllegalArgumentException("Input spec at " + path + " references step " + ix
							+ "; valid range is 0.." + (subTasks.size() - 1));
					}
					SubTask source=subTasks.get(ix);
					ACell value=RT.getIn(source.output, v.subVector(1,n-1).toCellArray());
					return value;
				} else if (Fields.CONST.equals(code)) {
					if (n < 2) throw new IllegalArgumentException(
						"Constant input spec at " + path + " requires a value");
					ACell value=v.get(1);
					return value;
				} else if (Fields.INPUT.equals(code)) {
					ACell value=RT.getIn(orchInput, v.subVector(1,n-1).toCellArray());
					return value;
				} else if (Fields.CONCAT.equals(code)) {
					StringBuilder sb = new StringBuilder();
					for (long i = 1; i < n; i++) {
						ACell part = computeInput(v.get(i), path);
						if (part != null) sb.append(part.toString());
					}
					return Strings.create(sb.toString());
				} else if (Fields.ARRAY.equals(code)) {
					// An array literal whose ELEMENTS are each computed. Needed because
					// a vector is otherwise always an expression here, so there was no
					// way to build an array referencing prior steps — the exact shape
					// ops like v/ops/json/cond require for `cases`. ["const", …] is no
					// substitute: it freezes the whole subtree, leaving inner bindings
					// as inert vectors. ["array"] with no elements is the empty array.
					AVector<ACell> out = Vectors.empty();
					for (long i = 1; i < n; i++) {
						out = out.conj(computeInput(v.get(i), path));
					}
					return out;
				} else {
					throw new IllegalArgumentException("Unrecognised input source at " + path + ": "
						+ conciseDetail(v, 256));
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
				throw new IllegalArgumentException("Unrecognised input spec at " + path + ": "
					+ conciseDetail(inputSpec, 256));
			}
		}

		/**
		 * Walks a spec at CONSTRUCTION time, collecting the step indices it depends
		 * on and validating its structure.
		 *
		 * <p>This is {@link #computeInput} with evaluation removed and dependency
		 * collection added — the same grammar walked twice. The two <b>must agree
		 * on what is acceptable</b>: previously this walk had no {@code else}, so an
		 * unrecognised head or a bare scalar was silently ignored here and only
		 * thrown by {@code computeInput} when the step ran. A malformed spec
		 * therefore passed construction and failed mid-run, after earlier steps had
		 * already applied their side effects (covia#281). Rejecting here fails the
		 * whole orchestration before any step starts.</p>
		 *
		 * <p>Note the deliberate asymmetry: {@code ["const", v]} freezes its
		 * subtree, so it is neither recursed into for dependencies nor validated —
		 * a literal may contain anything, including something that merely looks
		 * like a step reference.</p>
		 *
		 * <p>This only moves failures earlier; it does not change which specs are
		 * valid. An <em>absent</em> ({@code null}) spec is left alone — absence is a
		 * different question from malformedness, and is still handled downstream.</p>
		 *
		 * @param stepLimit exclusive upper bound on referencable step index: a
		 *        step's own index (may reference only earlier steps), or the step
		 *        count for the {@code result} spec (may reference any step)
		 */
		private HashSet<Integer> scanSpec(HashSet<Integer> accDeps, ACell spec, int stepLimit, AVector<ACell> path) {
			if (spec == null) return accDeps;
			if (spec instanceof AVector v) {
				long vn=v.count();
				if (vn==0) throw new IllegalArgumentException("Empty vector in input spec at " + path);
				ACell code=v.get(0);
				if (code instanceof CVMLong cvmix) {
					int ix=Utils.checkedInt(cvmix.longValue());
					if ((ix<0)||(ix>=stepLimit)) {
						throw new IllegalArgumentException("Input spec at " + path + " references step " + ix
							+ "; only steps 0.." + (stepLimit-1) + " are available here"
							+ (stepLimit==0 ? " (the first step can reference no earlier step)" : ""));
					}
					accDeps.add(ix);
				} else if (Fields.CONST.equals(code)) {
					if (vn<2) throw new IllegalArgumentException(
						"Constant input spec at " + path + " requires a value");
					// Frozen subtree — intentionally not recursed into.
				} else if (Fields.INPUT.equals(code)) {
					// Orchestration input — no step dependency.
				} else if (Fields.CONCAT.equals(code) || Fields.ARRAY.equals(code)) {
					// Elements are computed, so references inside them are real
					// dependencies. Any future head that computes its elements MUST
					// be added here too, or its step would start before its input
					// exists and silently resolve to null.
					for (long i=1; i<vn; i++) {
						scanSpec(accDeps, v.get(i), stepLimit, path);
					}
				} else {
					throw new IllegalArgumentException("Unrecognised input source at " + path + ": "
						+ conciseDetail(v, 256));
				}
			} else if (spec instanceof AMap m) {
				int c=Utils.checkedInt(m.count());
				for (int i=0; i<c; i++) {
					@SuppressWarnings("unchecked")
					MapEntry<AString,ACell> me=(MapEntry<AString,ACell>)m.entryAt(i);
					scanSpec(accDeps, me.getValue(), stepLimit, path.append(me.getKey()));
				}
			} else {
				throw new IllegalArgumentException("Unrecognised input spec at " + path + ": "
					+ conciseDetail(spec, 256));
			}
			return accDeps;
		}

		/**
		 * Validates step output against the operation's declared output schema.
		 * Throws on validation failure with a clear error naming the step.
		 */
		private void validateStepOutput(AString opId, ACell output, int stepNum) {
			if (output == null) return;
			try {
				Asset asset = engine.resolveAsset(opId, ctx);
				if (asset == null) return;
				AMap<AString, ACell> outputSchema = getMap(RT.getIn(asset.meta(), Fields.OPERATION, Fields.OUTPUT));
				if (outputSchema == null || outputSchema.isEmpty()) return;
				String err = JsonSchema.validate(outputSchema, output);
				if (err != null) {
					throw new RuntimeException(
						"Step " + stepNum + " (" + opId + ") output schema violation: " + err);
				}
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				// Schema resolution failed — skip validation
			}
		}

		@SuppressWarnings("unchecked")
		private static AMap<AString, ACell> getMap(ACell cell) {
			return (cell instanceof AMap) ? (AMap<AString, ACell>) cell : null;
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
				// A step may reference only EARLIER steps, so the limit is its own index.
				this.deps=scanSpec(new HashSet<Integer>(),step.get(Fields.INPUT),i,Vectors.empty());
			}

			@Override
			public void run() {
				try {
					AString opId=RT.getIn(step, Fields.OP);
					input=computeInput(RT.get(step, Fields.INPUT),Vectors.empty());

					// Check for venue field (DID or URL) to route to remote venue
					AString venueSpec = RT.ensureString(step.get(Fields.VENUE));
					if (venueSpec != null) {
						Venue venue = Grid.connect(venueSpec.toString());
						subJob = venue.invoke(opId.toString(), input).join();
					} else {
						subJob = engine.jobs().invokeOperation(opId, input, ctx);
					}

					output=subJob.awaitResult();

					// Strict mode: validate step output against the operation's output schema
					if (strict || CVMBool.TRUE.equals(step.get(K_STRICT))) {
						validateStepOutput(opId, output, stepNum);
					}

					completionQueue.add(this);
				} catch (Exception e) {
					if (DEBUG_ORCH) System.err.println(e);
					if (subJob == null) {
						String op = String.valueOf(step.get(Fields.OP));
						subJob = Job.failure("Orchestration step " + stepNum + " (" + op
							+ ") could not start: " + describeFailure(e));
					} else {
						subJob.fail("Orchestration step " + stepNum + " failed: " + describeFailure(e));
					}
					completionQueue.add(this);
				}
			}


		}
	}
}
