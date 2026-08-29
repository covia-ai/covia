package covia.adapter.agent;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * The task boundary, shared by both harnesses. The framework's contract
 * (AGENT_SESSIONS.md §6.3) is that a picked task completes only when the
 * agent says so — {@code complete_task} / {@code fail_task}, resolved at
 * tool time through the venue op — and otherwise yields. This class owns
	 * what that takes: two stable harness definitions; the outstanding-task
	 * message rendered last on every
 * inference; and the resolution itself, judged by {@link Completion} against
 * the requester's strict schema (#376). The harness supplies the cycle's
 * tasks and the turn's text, and decides what a resolution means for its own
 * loop — llmagent carries on to a closing reply, goaltree ends the frame.
 */
final class TaskTools {

	static final String COMPLETE = "complete_task";
	static final String FAIL = "fail_task";
	static final Set<String> NAMES = Set.of(COMPLETE, FAIL);

	/** Stands in for the task job id a live cycle carries ({@code agent:context}, {@code agent:step}). */
	static final AString PREVIEW_JOB_ID = Strings.intern("preview");

	private static final String BASE = "/adapters/agent/";

	private static final AMap<AString, ACell> DEF_COMPLETE =
		HarnessTools.definition(BASE + "completeTask.json", COMPLETE);

	private static final AMap<AString, ACell> DEF_FAIL =
		HarnessTools.definition(BASE + "failTask.json", FAIL);

	/** Both tools. They remain in the immutable harness palette; without an
	 * outstanding task their handlers return a scoped error. */
	static final AVector<ACell> DEFINITIONS = Vectors.of((ACell) DEF_COMPLETE, (ACell) DEF_FAIL);

	private TaskTools() {}

	/**
	 * One cycle's tasks and what has been resolved so far. In {@code preview}
	 * a resolution is judged and recorded exactly as live but never reaches a
	 * task job ({@code agent:step}).
	 */
	static final class Tasks {
		private final Engine engine;
		private final RequestContext ctx;
		private final AVector<ACell> tasks;
		private final long timeoutMs;
		private final boolean preview;
		/** jobId → {status, output | error}, as each task is resolved. */
		private AMap<AString, ACell> results;

		Tasks(Engine engine, RequestContext ctx, AVector<ACell> tasks, long timeoutMs, boolean preview) {
			this.engine = engine;
			this.ctx = ctx;
			this.tasks = tasks;
			this.timeoutMs = timeoutMs;
			this.preview = preview;
		}

		/** Whether any task is still unresolved this cycle. */
		boolean outstanding() {
			return firstOutstanding() != null;
		}

		/** Whether a task was resolved this cycle. */
		boolean resolved() {
			return results != null && !results.isEmpty();
		}

		/** What was resolved this cycle: jobId → {status, output | error}; null when nothing. */
		AMap<AString, ACell> results() {
			return results;
		}

		/** The stable task-tool definitions. */
		AVector<ACell> tools() {
			return DEFINITIONS;
		}

		/**
		 * The outstanding tasks as the message rendered last on every
		 * inference — only those still unresolved, with the requester's
		 * response schema where one was declared — or null when none remain.
		 */
		AMap<AString, ACell> message() {
			if (tasks == null || tasks.count() == 0) return null;
			StringBuilder sb = new StringBuilder();
			int outstanding = 0;
			for (long i = 0; i < tasks.count(); i++) {
				ACell task = tasks.get(i);
				AString jobId = RT.ensureString(RT.getIn(task, Fields.JOB_ID));
				if (jobId != null && results != null && results.get(jobId) != null) continue;
				if (outstanding == 0) sb.append("[Tasks assigned to you]\n");
				outstanding++;
				ACell caller = RT.getIn(task, Fields.CALLER);
				sb.append("- Task ").append(jobId);
				if (caller != null) sb.append(" (from: ").append(caller).append(")");
				sb.append(": ").append(renderTaskText(RT.getIn(task, Fields.INPUT))).append("\n");
				// The requester's declared result shape (#376) — advisory unless
				// the requester opted into enforcement.
				ACell schema = RT.getIn(task, Fields.RESPONSE_SCHEMA);
				if (schema != null) {
					boolean strict = CVMBool.TRUE.equals(RT.getIn(task, Fields.STRICT));
					sb.append("  Response schema").append(strict
							? " (enforced — complete_task results must conform)"
							: " (guidance)")
						.append(": ").append(JSON.print(schema)).append("\n");
				}
			}
			if (outstanding == 0) return null;
			sb.append("Use complete_task or fail_task to resolve each task.");
			return Maps.of(AbstractLLMAdapter.K_ROLE, AbstractLLMAdapter.ROLE_USER,
				AbstractLLMAdapter.K_CONTENT, Strings.create(sb.toString()));
		}

		/** {@code complete_task}: the result judged, the task job completed, the batch terminal. */
		ToolCycleEngine.ToolOutcome complete(ToolCycleEngine.ToolCall call, AString turnText) {
			return resolve(call, turnText, false);
		}

		/** {@code fail_task}: the reason judged, the task job failed, the batch terminal. */
		ToolCycleEngine.ToolOutcome fail(ToolCycleEngine.ToolCall call, AString turnText) {
			return resolve(call, turnText, true);
		}

		/**
		 * Resolves the in-scope task. The venue op ({@code agent:complete-task}
		 * / {@code agent:fail-task}) reads the agent and task from the request
		 * context, completes the caller's job and removes the task entry; the
		 * outcome is recorded here so the next prompt drops the task and the
		 * harness can promote the result. A rejected value stays retryable and
		 * does not fence later calls in the batch.
		 */
		private ToolCycleEngine.ToolOutcome resolve(ToolCycleEngine.ToolCall call, AString turnText, boolean failed) {
			String tool = failed ? FAIL : COMPLETE;
			AString jobId = scope();
			if (jobId == null) {
				return ToolCycleEngine.ToolOutcome.result(Strings.create(
					"Error: no task in scope — " + tool + " resolves the task this cycle was started for"));
			}
			Completion completion = failed
				? Completion.of(RT.getIn(call.input(), Fields.ERROR), turnText, null, tool)
				: Completion.of(RT.getIn(call.input(), Fields.RESULT), turnText, strictSchema(jobId), tool);
			if (!completion.accepted()) return ToolCycleEngine.ToolOutcome.result(completion.toolError());
			ACell value = completion.value();

			ACell opResult = Maps.of(Fields.STATUS, failed ? Status.FAILED : Status.COMPLETE);
			if (!preview) {
				try {
					ACell r = engine.jobs().invokeInternal(
						failed ? "v/ops/agent/fail-task" : "v/ops/agent/complete-task",
						Maps.of(failed ? Fields.ERROR : Fields.RESULT, value), ctx)
						.get(timeoutMs, TimeUnit.MILLISECONDS);
					if (r != null) opResult = r;
				} catch (Exception e) {
					return ToolCycleEngine.ToolOutcome.result(
						Strings.create("Error: " + AbstractLLMAdapter.unwrap(e).getMessage()));
				}
			}
			if (results == null) results = Maps.empty();
			results = results.assoc(jobId, failed
				? Maps.of(Fields.STATUS, Status.FAILED, Fields.ERROR, value)
				: Maps.of(Fields.STATUS, Status.COMPLETE, Fields.OUTPUT, value));
			return ToolCycleEngine.ToolOutcome.terminal(opResult, failed ? "failed" : "complete", value);
		}

		/**
		 * The transition output with a resolved task's outcome promoted into
		 * it: the structured output as the {@code response} (the authoritative
		 * answer, where the closing reply is only chat text), or the failure
		 * as the {@code error}. One task per cycle.
		 */
		AMap<AString, ACell> promote(AMap<AString, ACell> output) {
			if (!resolved()) return output;
			ACell outcome = results.entrySet().iterator().next().getValue();
			if (Status.FAILED.equals(RT.getIn(outcome, Fields.STATUS))) {
				ACell error = RT.getIn(outcome, Fields.ERROR);
				return (error != null) ? output.assoc(Fields.ERROR, error).dissoc(Fields.RESPONSE) : output;
			}
			ACell result = RT.getIn(outcome, Fields.OUTPUT);
			return (result != null) ? output.assoc(Fields.RESPONSE, result) : output;
		}

		/** The in-scope task's id: the one the cycle was started for, else the
		 *  first still outstanding (a preview, or a direct invocation). */
		private AString scope() {
			Blob id = (ctx != null) ? ctx.getTaskId() : null;
			return (id != null) ? Strings.create(id.toHexString()) : firstOutstanding();
		}

		private AString firstOutstanding() {
			for (long i = 0; tasks != null && i < tasks.count(); i++) {
				AString jobId = RT.ensureString(RT.getIn(tasks.get(i), Fields.JOB_ID));
				if (jobId != null && (results == null || results.get(jobId) == null)) return jobId;
			}
			return null;
		}

		/** The contract in force for a task: the requester's response schema
		 *  when it asked for strict enforcement (#376); otherwise none — a
		 *  non-strict schema is guidance, never judged. */
		private AMap<AString, ACell> strictSchema(AString jobId) {
			for (long i = 0; tasks != null && i < tasks.count(); i++) {
				ACell task = tasks.get(i);
				if (!jobId.equals(RT.getIn(task, Fields.JOB_ID))) continue;
				return CVMBool.TRUE.equals(RT.getIn(task, Fields.STRICT))
					? RT.ensureMap(RT.getIn(task, Fields.RESPONSE_SCHEMA)) : null;
			}
			return null;
		}
	}

	/**
	 * Renders a task input for the model: strings verbatim, anything else as
	 * JSON — never a CVM cell's EDN-style {@code toString()}, which models
	 * misread as noise (#215).
	 */
	static String renderTaskText(ACell input) {
		if (input == null) return "";
		if (input instanceof AString s) return s.toString();
		return JSON.print(input).toString();
	}
}
