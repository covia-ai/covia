package covia.adapter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
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
	private static final int DEFAULT_FOREACH_MAX_ITEMS = 50;
	private static final int DEFAULT_FOREACH_MAX_CONCURRENCY = 8;
	private static final AString K_MAX_ITEMS = Strings.intern("maxItems");

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

		/**
		 * Per-iteration bindings. Presence is separate from {@code item}: null is
		 * a valid collection element and must not look like "outside foreach".
		 */
		private record IterationContext(ACell item, CVMLong index) {}

		/** Completion handed from an iteration worker to its bounded scheduler. */
		private record IterationResult(int index, ACell output, Job job, String failure) {}

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
			scanSpec(new HashSet<Integer>(), resultSpec, n, Vectors.empty(), false);
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
						if (DEBUG_ORCH) System.out.println("Step completed "+JSON.printPretty(t.statusData));
						newlyComplete.add(t);
						completionQueue.drainTo(newlyComplete);
					}
					
					// Handle completed subtasks
					SubTask failedTask=null;
					for (SubTask task: newlyComplete) {
						stepsLeft-=1; // decrement number of steps left to complete
						
						if (!task.isSuccessful()) {
							// Remember the first observed failure, but persist all newly
							// completed step summaries before making the parent terminal:
							// Job terminal states are sticky, so updates after fail() are
							// intentionally ignored.
							if (failedTask == null) failedTask=task;
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
							AMap<AString,ACell> ssd=subTasks.get(i).statusData;
							if (ssd==null) continue;
							ssd=ssd.dissoc(Fields.STEPS); // remove child steps if any
							srs=srs.assoc(i, ssd);
						}
						jd=jd.assoc(Fields.STEPS, srs);
						return jd;
					});

					if (failedTask != null && !job.isFinished()) {
						String op=String.valueOf(failedTask.step.get(Fields.OP));
						job.fail("Orchestration step " + failedTask.stepNum + " (" + op
							+ ") failed: " + failedTask.failure);
					}

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
				orchOutput=computeInput(resultSpec,Vectors.empty(),null);
				job.completeWith(orchOutput);
			} catch (Exception e) {
				job.fail(describeFailure(e));
			}
		}
		
		@SuppressWarnings("unchecked")
		private ACell computeInput(ACell inputSpec, AVector<ACell> path, IterationContext iteration) {
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
						ACell part = computeInput(v.get(i), path, iteration);
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
						out = out.conj(computeInput(v.get(i), path, iteration));
					}
					return out;
				} else if (Fields.ITEM.equals(code)) {
					if (iteration == null) {
						throw new IllegalArgumentException(
							"Item binding at " + path + " is only valid inside a foreach step input");
					}
					if (n == 1) return iteration.item();
					return RT.getIn(iteration.item(), v.subVector(1,n-1).toCellArray());
				} else if (Fields.INDEX.equals(code)) {
					if (iteration == null) {
						throw new IllegalArgumentException(
							"Index binding at " + path + " is only valid inside a foreach step input");
					}
					if (n != 1) {
						throw new IllegalArgumentException(
							"Index binding at " + path + " does not accept a path");
					}
					return iteration.index();
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
					ACell value=computeInput(spec,newPath,iteration);
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
		private HashSet<Integer> scanSpec(HashSet<Integer> accDeps, ACell spec, int stepLimit,
				AVector<ACell> path, boolean allowIterationBindings) {
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
						scanSpec(accDeps, v.get(i), stepLimit, path, allowIterationBindings);
					}
				} else if (Fields.ITEM.equals(code)) {
					if (!allowIterationBindings) {
						throw new IllegalArgumentException(
							"Item binding at " + path + " is only valid inside a foreach step input");
					}
				} else if (Fields.INDEX.equals(code)) {
					if (!allowIterationBindings) {
						throw new IllegalArgumentException(
							"Index binding at " + path + " is only valid inside a foreach step input");
					}
					if (vn != 1) {
						throw new IllegalArgumentException(
							"Index binding at " + path + " does not accept a path");
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
					scanSpec(accDeps, me.getValue(), stepLimit, path.append(me.getKey()), allowIterationBindings);
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
			AMap<AString, ACell> statusData=null;
			String failure=null;
			AMap<AString, ACell> foreach=null;

			public SubTask(int i, AMap<AString, ACell> step) {
				this.step=step;
				this.stepNum=i;
				this.deps=new HashSet<>();

				ACell foreachCell=step.get(Fields.FOREACH);
				if (foreachCell == null) {
					// A step may reference only EARLIER steps, so the limit is its own index.
					scanSpec(deps,step.get(Fields.INPUT),i,Vectors.empty(),false);
					return;
				}
				if (!(foreachCell instanceof AMap<?,?> fm)) {
					throw new IllegalArgumentException(
						"Orchestration step " + i + " field 'foreach' must be an object");
				}
				@SuppressWarnings("unchecked")
				AMap<AString, ACell> foreachSpec=(AMap<AString, ACell>)fm;
				this.foreach=foreachSpec;

				ACell sourceSpec=foreachSpec.get(Fields.IN);
				if (sourceSpec == null) {
					throw new IllegalArgumentException(
						"Orchestration step " + i + " foreach requires field 'in'");
				}
				scanSpec(deps,sourceSpec,i,Vectors.of(Fields.FOREACH,Fields.IN),false);
				scanSpec(deps,step.get(Fields.INPUT),i,Vectors.empty(),true);

				ACell requestedCell=foreachSpec.get(Fields.MAX_CONCURRENCY);
				if (requestedCell != null) {
					if (!(requestedCell instanceof CVMLong requested)
							|| requested.longValue() < 1
							|| requested.longValue() > Integer.MAX_VALUE) {
						throw new IllegalArgumentException(
							"Orchestration step " + i
								+ " foreach.maxConcurrency must be a positive integer");
					}
					int configuredMax=configuredForeachLimit(
						Fields.MAX_CONCURRENCY,DEFAULT_FOREACH_MAX_CONCURRENCY);
					if (requested.longValue() > configuredMax) {
						throw new IllegalArgumentException(
							"Orchestration step " + i + " foreach.maxConcurrency "
								+ requested.longValue() + " exceeds the venue limit " + configuredMax);
					}
				}
			}

			boolean isSuccessful() {
				return failure == null && statusData != null
					&& Status.COMPLETE.equals(RT.ensureString(statusData.get(Fields.STATUS)));
			}

			@Override
			public void run() {
				try {
					if (foreach == null) {
						runSingle();
					} else {
						runForeach();
					}
				} catch (Exception e) {
					if (DEBUG_ORCH) System.err.println(e);
					recordFailure(describeFailure(e));
				} finally {
					completionQueue.add(this);
				}
			}

			private void runSingle() {
				AString opId=RT.getIn(step, Fields.OP);
				input=computeInput(RT.get(step, Fields.INPUT),Vectors.empty(),null);
				subJob=invokeChild(opId,input);
				output=subJob.awaitResult();

				if (strict || CVMBool.TRUE.equals(step.get(K_STRICT))) {
					validateStepOutput(opId,output,stepNum);
				}
				statusData=subJob.getData();
			}

			/**
			 * Executes an ordered, bounded map over any Convex data structure.
			 * ADataStructure is deliberately the only runtime gate: collections,
			 * maps and indexes all expose count()/get(long), and map/index get()
			 * returns a vector-compatible MapEntry [key value].
			 */
			private void runForeach() {
				AString opId=RT.getIn(step, Fields.OP);
				ACell source=computeInput(
					foreach.get(Fields.IN),Vectors.of(Fields.FOREACH,Fields.IN),null);
				if (!(source instanceof ADataStructure<?> data)) {
					String actual=(source == null) ? "null" : source.getClass().getSimpleName();
					throw new IllegalArgumentException(
						"foreach.in expected an ADataStructure, got " + actual);
				}

				Long maxItems=configuredForeachMaxItems();
				long count=data.count();
				if (maxItems != null && count > maxItems) {
					throw new IllegalArgumentException(
						"foreach.in contains " + count + " items; venue limit is " + maxItems);
				}
				int itemCount=Utils.checkedInt(count);
				if (itemCount == 0) {
					output=Vectors.empty();
					statusData=foreachStatus(opId,itemCount,Vectors.empty(),Status.COMPLETE,null,output);
					return;
				}

				// ADataStructure values are immutable, but taking one snapshot keeps
				// every iteration and the final ordering tied to exactly one view.
				ACell[] items=data.toCellArray();
				ACell[] outputs=new ACell[itemCount];
				IterationResult[] results=new IterationResult[itemCount];
				BlockingQueue<IterationResult> completed=new ArrayBlockingQueue<>(itemCount);

				int concurrency=foreachConcurrency();
				int next=0;
				int active=0;
				boolean stopStarting=false;

				while (next < itemCount && active < concurrency) {
					boolean issued=startIteration(next,items[next],opId,completed);
					next++;
					active++;
					if (!issued) {
						stopStarting=true;
						break;
					}
				}

				while (active > 0) {
					if (job.isFinished()) {
						// Parent cancellation/failure is terminal. Do not admit more
						// work; already-running child jobs retain existing semantics.
						failure="foreach stopped because the orchestration is " + job.getStatus();
						statusData=foreachStatus(opId,itemCount,iterationSummaries(results),
							job.getStatus(),failure,null);
						return;
					}

					IterationResult result;
					try {
						result=completed.poll(100,TimeUnit.MILLISECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException("foreach interrupted",e);
					}
					if (result == null) continue;

					active--;
					results[result.index()]=result;
					outputs[result.index()]=result.output();
					if (result.failure() != null) stopStarting=true;

					while (!stopStarting && next < itemCount && active < concurrency) {
						boolean issued=startIteration(next,items[next],opId,completed);
						next++;
						active++;
						if (!issued) stopStarting=true;
					}
				}

				IterationResult failed=firstFailure(results);
				AVector<ACell> summaries=iterationSummaries(results);
				if (failed != null) {
					failure="foreach item " + failed.index() + " failed: " + failed.failure();
					statusData=foreachStatus(
						opId,itemCount,summaries,Status.FAILED,failure,null);
					return;
				}

				output=Vectors.create(outputs);
				statusData=foreachStatus(
					opId,itemCount,summaries,Status.COMPLETE,null,output);
			}

			private boolean startIteration(int index, ACell item, AString opId,
					BlockingQueue<IterationResult> completed) {
				Job child=null;
				try {
					// Resolve and submit on the foreach scheduler thread. Admission,
					// authorization and request issuance are therefore serialized;
					// maxConcurrency counts child jobs in process, not concurrent
					// attempts to issue them.
					IterationContext iteration=
						new IterationContext(item,CVMLong.create(index));
					ACell iterationInput=computeInput(
						RT.get(step,Fields.INPUT),Vectors.empty(),iteration);
					child=invokeChild(opId,iterationInput);
					Job issuedChild=child;
					ThreadUtils.runVirtual(
						"foreach step " + stepNum + " item " + index,
						() -> completed.add(awaitIteration(index,opId,issuedChild)));
					return true;
				} catch (Exception e) {
					completed.add(new IterationResult(index,null,child,describeFailure(e)));
					return false;
				}
			}

			private IterationResult awaitIteration(int index, AString opId, Job child) {
				try {
					ACell iterationOutput=child.awaitResult();
					if (strict || CVMBool.TRUE.equals(step.get(K_STRICT))) {
						validateStepOutput(opId,iterationOutput,stepNum);
					}
					return new IterationResult(index,iterationOutput,child,null);
				} catch (Exception e) {
					return new IterationResult(index,null,child,describeFailure(e));
				}
			}

			private Job invokeChild(AString opId, ACell childInput) {
				AString venueSpec=RT.ensureString(step.get(Fields.VENUE));
				if (venueSpec != null) {
					Venue venue=Grid.connect(venueSpec.toString());
					return venue.invoke(opId.toString(),childInput).join();
				}
				// Deliberately use JobManager for every item. This preserves job
				// tracking and runs the common point-of-action authorization gate
				// against the actual per-item input.
				return engine.jobs().invokeOperation(opId,childInput,ctx);
			}

			private int foreachConcurrency() {
				int configured=configuredForeachLimit(
					Fields.MAX_CONCURRENCY,DEFAULT_FOREACH_MAX_CONCURRENCY);
				CVMLong requested=RT.ensureLong(foreach.get(Fields.MAX_CONCURRENCY));
				return (requested == null) ? configured : Utils.checkedInt(requested.longValue());
			}

			/**
			 * Returns the venue item cap, or null when the operator explicitly
			 * configured JSON null to remove the orchestrator-level cap.
			 */
			private Long configuredForeachMaxItems() {
				AMap<AString,ACell> config=engine.config().getAdapterConfig(getName());
				if (!config.containsKey(K_MAX_ITEMS)) {
					return (long)DEFAULT_FOREACH_MAX_ITEMS;
				}
				ACell configured=config.get(K_MAX_ITEMS);
				if (configured == null) return null;
				if (configured instanceof CVMLong l && l.longValue() >= 1) {
					return l.longValue();
				}
				return (long)DEFAULT_FOREACH_MAX_ITEMS;
			}

			private int configuredForeachLimit(AString key, int defaultValue) {
				ACell configured=engine.config().getAdapterConfig(getName()).get(key);
				if (configured instanceof CVMLong l
						&& l.longValue() >= 1
						&& l.longValue() <= Integer.MAX_VALUE) {
					return Utils.checkedInt(l.longValue());
				}
				return defaultValue;
			}

			private IterationResult firstFailure(IterationResult[] results) {
				for (IterationResult result:results) {
					if (result != null && result.failure() != null) return result;
				}
				return null;
			}

			private AVector<ACell> iterationSummaries(IterationResult[] results) {
				AVector<ACell> summaries=Vectors.empty();
				for (IterationResult result:results) {
					if (result == null) continue;
					AMap<AString,ACell> summary=Maps.of(
						Fields.INDEX,CVMLong.create(result.index()));
					Job child=result.job();
					if (child != null) {
						ACell id=child.getData().get(Fields.ID);
						if (id != null) summary=summary.assoc(Fields.ID,id);
						summary=summary.assoc(Fields.STATUS,child.getStatus());
					} else {
						summary=summary.assoc(Fields.STATUS,Status.FAILED);
					}
					if (result.failure() != null) {
						summary=summary
							.assoc(Fields.STATUS,Status.FAILED)
							.assoc(Fields.ERROR,Strings.create(result.failure()));
					}
					summaries=summaries.conj(summary);
				}
				return summaries;
			}

			private AMap<AString,ACell> foreachStatus(AString opId, int total,
					AVector<ACell> summaries, AString status, String error, ACell result) {
				AMap<AString,ACell> data=Maps.of(
					Fields.OPERATION,opId,
					Fields.STATUS,status,
					Fields.TOTAL,CVMLong.create(total),
					Fields.ITEMS,summaries);
				if (error != null) data=data.assoc(Fields.ERROR,Strings.create(error));
				if (result != null) data=data.assoc(Fields.OUTPUT,result);
				return data;
			}

			private void recordFailure(String message) {
				failure=message;
				AString opId=RT.ensureString(step.get(Fields.OP));
				if (subJob != null) {
					statusData=subJob.getData();
				} else {
					statusData=Maps.of(Fields.OPERATION,opId);
				}
				statusData=statusData
					.assoc(Fields.STATUS,Status.FAILED)
					.assoc(Fields.ERROR,Strings.create(message));
			}

		}
	}
}
