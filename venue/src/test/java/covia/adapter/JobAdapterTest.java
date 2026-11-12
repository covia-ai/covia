package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.api.Abilities;
import covia.grid.Job;
import covia.grid.Status;
import covia.test.IntegrationTest;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

class JobAdapterTest {

	private final Engine engine = TestEngine.ENGINE;
	private RequestContext ctx;

	@BeforeEach void caller(TestInfo info) { ctx = RequestContext.of(TestEngine.uniqueDID(info)); }

	private Job task(String name, ACell input) {
		return engine.jobs().invokeOperation("v/test/ops/" + name, input, ctx);
	}
	private Job invoke(String op, AMap<AString, ACell> input) {
		return engine.jobs().invokeOperation("v/ops/job/" + op, input, ctx);
	}
	private AMap<AString, ACell> target(Job job) { return Maps.of(Fields.ID, job.getID().toHexString()); }
	private ACell run(String op, Job job) { return invoke(op, target(job)).awaitResult(5000); }

	@Test void readsAnyAdapterJobAndReturnsItsExactResult() {
		for (ACell output : new ACell[]{Strings.create("answer"), Maps.of("nested", Vectors.of(1, 2)), CVMLong.ZERO, null}) {
			Job job = task("echo", output);
			assertEquals(output, job.awaitResult(5000));
			assertEquals(Status.COMPLETE, RT.getIn(run("status", job), Fields.STATUS));
			assertEquals(output, run("result", job));
			assertEquals(output, engine.jobs().invokeOperation("v/ops/grid/job-result", target(job), ctx).awaitResult(5000));
			assertEquals(run("status", job), engine.jobs().invokeOperation("v/ops/grid/job-status", target(job), ctx).awaitResult(5000));
		}
	}

	@Test void controlsUseTheExistingLifecycleAndPreserveTerminalOutcomes() {
		Job job = task("never", Maps.empty());
		try {
			assertEquals(CVMBool.TRUE, RT.getIn(run("pause", job), Fields.ACCEPTED));
			assertEquals(CVMBool.TRUE, RT.getIn(run("resume", job), Fields.ACCEPTED));
			assertEquals(CVMBool.TRUE, RT.getIn(run("cancel", job), Fields.ACCEPTED));
			assertEquals(CVMBool.TRUE, RT.getIn(run("cancel", job), Fields.ACCEPTED));
			assertEquals(Status.CANCELLED, job.getStatus());
		} finally { job.cancel(); }
		Job completed = task("echo", Strings.create("kept"));
		completed.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(run("cancel", completed), Fields.ACCEPTED));
		assertEquals(Strings.create("kept"), completed.getOutput());
	}

	@Test void messageIsAcknowledgedAndUnsupportedControlsRefuse() {
		Job chat = task("chat", Maps.empty());
		try {
			assertThrows(Exception.class, () -> invoke("pause", target(chat)).awaitResult(5000));
			assertEquals(Status.INPUT_REQUIRED, chat.getStatus());
			ACell accepted = invoke("message", target(chat).assoc(Fields.MESSAGE, Maps.of(Fields.CONTENT, "done"))).awaitResult(5000);
			assertEquals(CVMBool.TRUE, RT.getIn(accepted, Fields.DELIVERED));
			assertEquals(Status.COMPLETE, chat.getStatus());
			assertThrows(Exception.class, () -> invoke("message", target(chat)
				.assoc(Fields.MESSAGE, Maps.of(Fields.CONTENT, "again"))).awaitResult(5000));
		} finally { chat.cancel(); }
	}

	@Test void cancellingAResultWaitLeavesTheTargetAndItsFutureAlone() {
		Job job = task("never", Maps.empty());
		try {
			Job wait = invoke("result", target(job));
			run("cancel", wait);
			assertEquals(Status.CANCELLED, wait.getStatus());
			assertEquals(Status.STARTED, job.getStatus());
			assertFalse(job.future().isDone());
			job.completeWith(Strings.create("later"));
			assertEquals(Strings.create("later"), run("result", job));
		} finally { job.cancel(); }
	}

	@Test @IntegrationTest void timeoutEndsOnlyTheWait() {
		Job job = task("never", Maps.empty());
		try {
			Job wait = invoke("result", target(job).assoc(Fields.TIMEOUT, CVMLong.create(10)));
			assertThrows(Exception.class, () -> wait.awaitResult(5000));
			assertEquals(JobAdapter.RESULT_TIMEOUT.formatted(10, job.getID().toHexString()), wait.getErrorMessage());
			assertFalse(job.future().isDone());
			assertEquals(Status.STARTED, job.getStatus());
		} finally { job.cancel(); }
	}

	@Test void readAuthorityDoesNotGrantControls() {
		Job job = task("never", Maps.empty());
		try {
			RequestContext reader = ctx.withCaps(Vectors.of(
				Capability.create(Strings.create("v/ops/job"), covia.api.Abilities.INVOKE),
				Capability.create(Strings.create("j/" + job.getID().toHexString()), Abilities.JOB_READ)));
			ACell status = engine.jobs().invokeOperation("v/ops/job/status", target(job), reader).awaitResult(5000);
			assertEquals(Status.STARTED, RT.getIn(status, Fields.STATUS));
			for (String op : new String[]{"cancel", "pause", "resume", "message"}) {
				assertThrows(Exception.class, () -> engine.jobs().invokeOperation("v/ops/job/" + op,
					target(job).assoc(Fields.MESSAGE, Maps.of(Fields.CONTENT, "done")), reader).awaitResult(5000));
				assertEquals(Status.STARTED, job.getStatus());
			}
			RequestContext other = RequestContext.of(Strings.create(ctx.getCallerDID() + ":other"));
			assertThrows(Exception.class, () -> engine.jobs().invokeOperation("v/ops/job/status", target(job), other).awaitResult(5000));
		} finally { job.cancel(); }
	}

	@Test void malformedInputsNeverFallBackToLocalControl() {
		Job job = task("never", Maps.empty());
		try {
			for (ACell venue : new ACell[]{null, CVMLong.ZERO, Maps.empty()}) {
				Exception refused = assertThrows(Exception.class, () -> invoke("cancel", target(job).assoc(Fields.VENUE, venue)).awaitResult(5000));
				assertEquals(JobAdapter.INVALID_VENUE, refused.getMessage());
				assertEquals(Status.STARTED, job.getStatus());
			}
			for (ACell id : new ACell[]{null, Strings.create("not-hex"), CVMLong.ZERO}) {
				assertThrows(Exception.class, () -> invoke("status", Maps.of(Fields.ID, id)).awaitResult(5000));
			}
			for (ACell timeout : new ACell[]{CVMLong.create(-1), Strings.create("10")}) {
				Exception refused = assertThrows(Exception.class, () -> invoke("result", target(job).assoc(Fields.TIMEOUT, timeout)).awaitResult(5000));
				assertEquals(JobAdapter.INVALID_TIMEOUT, refused.getMessage());
			}
			Exception message = assertThrows(Exception.class, () -> invoke("message", target(job).assoc(Fields.MESSAGE, Strings.create("done"))).awaitResult(5000));
			assertEquals(JobAdapter.INVALID_MESSAGE, message.getMessage());
		} finally { job.cancel(); }
	}

	@Test void cancellationReachesAnExternalAgentBackend() {
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "worker",
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never")), ctx).awaitResult(5000);
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "proxy",
			"agent", Maps.of(Fields.ADAPTER, "remoteagent", Fields.AGENT_ID, "worker")), ctx).awaitResult(5000);
		Job request = engine.jobs().invokeOperation("v/ops/agent/request", Maps.of(
			Fields.AGENT_ID, "proxy", Fields.INPUT, Maps.of("task", "wait")), ctx);
		try {
			Job backend = new covia.adapter.agent.ExternalAgents(engine, new java.util.concurrent.ConcurrentHashMap<>()).child(request);
			assertNotNull(backend);
			assertEquals(CVMBool.TRUE, RT.getIn(run("cancel", request), Fields.ACCEPTED));
			assertEquals(Status.CANCELLED, backend.getStatus());
		} finally {
			request.cancel();
			engine.jobs().invokeOperation("v/ops/agent/delete", Maps.of("agentIds", Vectors.of("proxy", "worker")), ctx).awaitResult(5000);
		}
	}
}
