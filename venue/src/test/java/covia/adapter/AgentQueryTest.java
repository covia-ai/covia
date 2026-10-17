package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.agent.GoalTreeContext;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;

/** Queries and cached summaries use real dispatch and LLM runtimes, with a deterministic provider. */
public class AgentQueryTest {

	private static final AString AGENT = Strings.create("queried");
	private static final String FAILURE = "injected query failure";
	private static final Blob SUMMARY_SESSION = Blob.fromHex("1234abcd");
	private Engine engine;
	private RequestContext owner;
	private QueryModel model;

	private static class QueryModel extends AAdapter {
		volatile RequestContext seenContext;
		volatile ACell seenInput;
		final CountDownLatch entered = new CountDownLatch(1);
		final java.util.concurrent.atomic.AtomicInteger invocations = new java.util.concurrent.atomic.AtomicInteger();
		Function<ACell, CompletableFuture<ACell>> reply = input ->
			CompletableFuture.completedFuture(Maps.of("role", "assistant", "content", "answer"));

		@Override public String getName() { return "query-test"; }
		@Override public String getDescription() { return "Query test provider"; }
		@Override protected void installAssets() {
			installAsset("query-test/model", Maps.of(Fields.NAME, "Query test model",
				Fields.OPERATION, Maps.of(Fields.ADAPTER, "query-test:model")));
		}
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx,
				AMap<AString, ACell> meta, ACell input) {
			requireInvoke(ctx);
			seenContext = ctx;
			seenInput = input;
			invocations.incrementAndGet();
			CompletableFuture<ACell> result = reply.apply(input);
			entered.countDown();
			return result;
		}
	}

	@BeforeEach
	public void setup() {
		engine = Engine.createTemp(Maps.empty());
		Engine.addDemoAssets(engine);
		owner = engine.venueContext();
		model = new QueryModel();
		engine.registerAdapter(model);
	}

	@AfterEach
	public void close() { engine.close(); }

	private AgentState create(String runtime, AMap<AString, ACell> extra) {
		AMap<AString, ACell> config = Maps.of(
			Fields.OPERATION, "v/ops/" + runtime + "/chat",
			"llmOperation", "v/ops/query-test/model", "systemPrompt", "query persona");
		for (var e : extra.entrySet()) config = config.assoc(e.getKey(), e.getValue());
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, AGENT, Fields.CONFIG, config), owner).awaitResult(5000);
		return engine.getVenueState().users().get(owner.getUserDID()).agent(AGENT);
	}

	private Job query(Object message) {
		return engine.jobs().invokeOperation("v/ops/agent/query",
			Maps.of(Fields.AGENT_ID, AGENT, Fields.MESSAGE, message), owner);
	}

	private AMap<AString, ACell> queryWithSteps(Object message) {
		return RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/query",
			Maps.of(Fields.AGENT_ID, AGENT, Fields.MESSAGE, message, "includeSteps", true), owner)
			.awaitResult(10000));
	}

	private AgentState createSummarisable(String runtime) {
		AgentState agent = create(runtime, Maps.empty());
		agent.ensureSession(SUMMARY_SESSION, owner.getCallerDID());
		addSummaryTurn(agent, "initial discussion");
		return agent;
	}

	private void addSummaryTurn(AgentState agent, String content) {
		assertTrue(agent.updateQuiescentSessionFrames(SUMMARY_SESSION, frames ->
			frames.assoc(0, GoalTreeContext.appendTurn(RT.ensureMap(frames.get(0)),
				Maps.of("role", "user", "content", content, Fields.TS, 7)))));
	}

	private AMap<AString, ACell> summaryInput(boolean includeSteps) {
		return Maps.of(Fields.AGENT_ID, AGENT, Fields.SESSION_ID, SUMMARY_SESSION.toHexString(),
			"includeSteps", includeSteps);
	}

	private Job summarise(boolean includeSteps) {
		return engine.jobs().invokeOperation("v/ops/agent/summarise", summaryInput(includeSteps), owner);
	}

	private AMap<AString, ACell> savedSummary(AgentState agent) {
		return AgentState.sessionSummary(agent.getSession(SUMMARY_SESSION),
			Strings.create(AgentAdapter.DEFAULT_SUMMARISE_INSTRUCTION));
	}

	private AMap<AString, ACell> summariseMetadata(ACell instruction) {
		AMap<AString, ACell> meta = engine.resolveAsset(Strings.create("v/ops/agent/summarise"), owner).meta();
		AMap<AString, ACell> operation = RT.ensureMap(meta.get(Fields.OPERATION));
		return meta.assoc(Fields.OPERATION, operation.assoc(Strings.intern("instruction"), instruction));
	}

	@Test public void freshFlatQuery() { freshQueryUsesAgentConfigWithoutChangingItsRecord("llmagent"); }
	@Test public void freshGoalTreeQuery() { freshQueryUsesAgentConfigWithoutChangingItsRecord("goaltree"); }

	private void freshQueryUsesAgentConfigWithoutChangingItsRecord(String runtime) {
		AgentState agent = create(runtime, Maps.empty());
		ACell before = agent.getRecord();
		assertEquals(Strings.create("answer"), query("one-off question").awaitResult(5000));
		assertEquals(before, agent.getRecord());
		assertTrue(agent.getSessions().isEmpty());
		String prompt = model.seenInput.toString();
		assertTrue(prompt.contains("query persona"), prompt);
		assertTrue(prompt.contains("one-off question"), prompt);
		query("independent question").awaitResult(5000);
		assertFalse(model.seenInput.toString().contains("one-off question"));
		assertEquals(before, agent.getRecord());
	}

	@Test public void flatSessionSnapshot() { sessionQuerySnapshotsRootHistoryAndLeavesLiveWorkAlone("llmagent"); }
	@Test public void goalTreeSessionSnapshot() { sessionQuerySnapshotsRootHistoryAndLeavesLiveWorkAlone("goaltree"); }

	private void sessionQuerySnapshotsRootHistoryAndLeavesLiveWorkAlone(String runtime) {
		AgentState agent = create(runtime, Maps.empty());
		Blob sid = Blob.fromHex("0123456789abcdef");
		AMap<AString, ACell> session = agent.ensureSession(sid, owner.getCallerDID(),
			Maps.of("note", Maps.of("text", "session-only context")));
		AMap<AString, ACell> root = GoalTreeContext.createFrame("root question");
		root = GoalTreeContext.appendTurn(root, Maps.of("role", "user", "content", "previous question"));
		root = GoalTreeContext.appendTurn(root, Maps.of("role", "assistant", "content", "previous answer"));
		root = GoalTreeContext.appendTurn(root, Maps.of("role", "assistant", "toolCalls",
			Vectors.of(Maps.of("id", "in-flight", "name", "subgoal", "arguments", Maps.empty()))));
		var child = GoalTreeContext.createFrame("unfinished private child").assoc(
			Strings.intern("callId"), Strings.create("in-flight"));
		// Preserve the seeded loads when replacing the empty root for this fixture.
		root = root.assoc(Fields.LOADS, Maps.of("note", Maps.of("text", "session-only context")));
		session = session.assoc(Fields.FRAMES, Vectors.of(root, child));
		agent.putRecord(agent.getRecord().assoc(AgentState.KEY_SESSIONS,
			agent.getSessions().assoc(sid, session)));
		agent.appendSessionPending(sid, owner.getCallerDID(), null,
			Maps.of(Fields.MESSAGE, "queued live message"), false);
		assertTrue(agent.beginSessionCycle(sid, Blob.fromHex("1234"), null, 0));
		ACell before = agent.getRecord();
		ACell result = engine.jobs().invokeOperation("v/ops/agent/query",
			Maps.of(Fields.AGENT_ID, AGENT, Fields.MESSAGE, "query-only question",
				Fields.SESSION_ID, "0x" + sid.toHexString(), "includeSteps", true), owner).awaitResult(5000);
		assertEquals(Strings.create("answer"), RT.getIn(result, Fields.RESPONSE));
		AVector<ACell> steps = RT.ensureVector(RT.getIn(result, Fields.STEPS));
		assertEquals(1, steps.count());
		assertFalse(steps.toString().contains("previous question"));
		assertFalse(steps.toString().contains("previous answer"));
		assertFalse(steps.toString().contains("session-only context"));
		String prompt = model.seenInput.toString();
		assertTrue(prompt.contains("previous question"), prompt);
		assertTrue(prompt.contains("previous answer"), prompt);
		assertTrue(prompt.contains("session-only context"), prompt);
		assertTrue(prompt.contains("query-only question"), prompt);
		assertTrue(prompt.contains(AgentAdapter.QUERY_PENDING_TOOL), prompt);
		assertFalse(prompt.contains("queued live message"), prompt);
		assertFalse(prompt.contains("unfinished private child"), prompt);
		assertEquals(before, agent.getRecord(), "query must not claim, drain, or publish into the live session");
		assertNull(model.seenContext.getSessionId());
		assertNull(model.seenContext.getTaskId());
		assertNull(model.seenContext.getCycle());
	}

	@Test public void flatQueryTools() { queryRunsToolsAndKeepsDynamicLoadsLocal("llmagent"); }
	@Test public void goalTreeQueryTools() { queryRunsToolsAndKeepsDynamicLoadsLocal("goaltree"); }

	private void queryRunsToolsAndKeepsDynamicLoadsLocal(String runtime) {
		AgentState agent = create(runtime, Maps.of(
			"llmOperation", "v/test/ops/moretoolsllm",
			Fields.TOOLS, Vectors.of(Strings.create("more_tools")),
			"caps", Vectors.of(
				Capability.create(Strings.create("v/test/ops/echo"), covia.api.Abilities.TOOL_LOAD),
				Capability.create(Strings.create("v/test/ops"), Strings.create("invoke")))));
		ACell before = agent.getRecord();
		Job job = query("extend yourself");
		assertTrue(job.awaitResult(10000).toString().startsWith("MORE_TOOLS_RESULT:"));
		assertNotNull(job.getData().get(Fields.TOKENS));
		assertEquals(before, agent.getRecord());
	}

	@Test
	public void queryHonoursAdmissionAndUsesFreshAgentAuthority() {
		RequestContext other = RequestContext.of(Strings.create("did:key:query-caller"));
		engine.getVenueState().users().ensure(other.getUserDID());
		AgentState agent = create("llmagent", Maps.empty());
		AString address = Strings.create(owner.getUserDID() + "/g/" + AGENT);
		AMap<AString, ACell> input = Maps.of(Fields.AGENT_ID, address, Fields.MESSAGE, "question");
		Job denied = engine.jobs().invokeOperation("v/ops/agent/query", input, other);
		assertThrows(JobFailedException.class, () -> denied.awaitResult(5000));
		assertNull(model.seenInput, "denial precedes the model call");
		agent.putRecord(agent.getRecord().assoc(AgentState.KEY_CONFIG,
			agent.getConfig().assoc(Fields.ACCEPTS, Vectors.of(other.getCallerDID()))));
		ACell before = agent.getRecord();
		engine.jobs().invokeOperation("v/ops/agent/query", input,
			other.withSessionId(Blob.fromHex("abcd")).withTaskId(Blob.fromHex("5678")))
			.awaitResult(5000);
		assertEquals(Principals.agentDID(owner.getUserDID(), AGENT), model.seenContext.getCallerDID());
		assertEquals(owner.getUserDID(), model.seenContext.getUserDID());
		assertNull(model.seenContext.getSessionId());
		assertNull(model.seenContext.getTaskId());
		assertNull(model.seenContext.getProofs());
		assertEquals(before, agent.getRecord());
	}

	@Test
	public void badSessionIsRejectedBeforeInference() {
		AgentState agent = create("llmagent", Maps.empty());
		ACell before = agent.getRecord();
		for (String sid : new String[] {"not-hex", "0123456789abcdef"}) {
			Job job = engine.jobs().invokeOperation("v/ops/agent/query",
				Maps.of(Fields.AGENT_ID, AGENT, Fields.MESSAGE, "question", Fields.SESSION_ID, sid), owner);
			assertThrows(JobFailedException.class, () -> job.awaitResult(5000));
			assertEquals(sid.equals("not-hex") ? AgentAdapter.QUERY_INVALID_SESSION
				: AgentAdapter.QUERY_UNKNOWN_SESSION, job.getErrorMessage());
		}
		assertNull(model.seenInput);
		assertEquals(before, agent.getRecord());
	}

	@Test
	public void providerFailureDoesNotSuspendAgent() {
		AgentState agent = create("llmagent", Maps.empty());
		ACell before = agent.getRecord();
		model.reply = input -> CompletableFuture.failedFuture(new IllegalStateException(FAILURE));
		Job job = query("question");
		assertThrows(JobFailedException.class, () -> job.awaitResult(5000));
		assertTrue(job.getErrorMessage().contains(FAILURE));
		assertEquals(before, agent.getRecord());
	}

	@Test
	public void structuredAnswerIsReturnedWithoutMergingTransitionState() {
		AgentState agent = create("llmagent", Maps.of(Fields.OPERATION, "v/ops/query-test/model"));
		ACell before = agent.getRecord();
		ACell answer = Maps.of("answer", 42);
		model.reply = input -> CompletableFuture.completedFuture(Maps.of(
			Fields.RESPONSE, answer, AgentState.KEY_STATE, Maps.of("changed", true)));
		assertEquals(answer, query(Maps.of("question", "structured input")).awaitResult(5000));
		AMap<AString, ACell> detailed = queryWithSteps("custom transition");
		assertEquals(answer, detailed.get(Fields.RESPONSE));
		assertEquals(Vectors.empty(), detailed.get(Fields.STEPS), "custom transition has no cycle trace");
		assertNull(detailed.get(Fields.TOKENS), "unmeasured usage must not be invented");
		assertEquals(before, agent.getRecord());
		model.reply = input -> CompletableFuture.completedFuture(Maps.of(Fields.ERROR, FAILURE));
		Job failed = query("error value");
		assertThrows(JobFailedException.class, () -> failed.awaitResult(5000));
		assertTrue(failed.getErrorMessage().contains(FAILURE));
		model.reply = input -> CompletableFuture.completedFuture(Maps.of(AgentState.KEY_STATE, Maps.empty()));
		Job yielded = query("no answer");
		assertThrows(JobFailedException.class, () -> yielded.awaitResult(5000));
		assertTrue(yielded.getErrorMessage().contains(AgentAdapter.QUERY_NO_RESPONSE));
		assertEquals(before, agent.getRecord());
	}

	@Test
	public void queryRequiresMessageAuthority() {
		create("llmagent", Maps.empty());
		RequestContext readOnly = owner.withCaps(Vectors.of(
			Capability.create(Strings.create("v/ops/agent/query"), Strings.create("invoke")),
			Capability.create(Strings.create("g/queried"), Capability.CRUD_READ)));
		Job denied = engine.jobs().invokeOperation("v/ops/agent/query",
			Maps.of(Fields.AGENT_ID, AGENT, Fields.MESSAGE, "question"), readOnly);
		assertThrows(JobFailedException.class, () -> denied.awaitResult(5000));
		assertNull(model.seenInput);
	}

	@Test
	public void queryCannotLoadToolsBeyondAgentCaps() {
		AgentState agent = create("llmagent", Maps.of(
			"llmOperation", "v/test/ops/moretoolsllm",
			Fields.TOOLS, Vectors.of(Strings.create("more_tools")), "caps", Vectors.empty()));
		ACell before = agent.getRecord();
		assertEquals(Strings.create("MORE_TOOLS_MISSING"), query("extend yourself").awaitResult(5000));
		assertEquals(before, agent.getRecord());
	}

	@Test
	public void concurrentSessionChangesSurviveQueryCompletion() throws Exception {
		AgentState agent = create("llmagent", Maps.empty());
		Blob sid = Blob.fromHex("12345678");
		agent.ensureSession(sid, owner.getCallerDID());
		CompletableFuture<ACell> held = new CompletableFuture<>();
		model.reply = input -> held;
		Job job = engine.jobs().invokeOperation("v/ops/agent/query",
			Maps.of(Fields.AGENT_ID, AGENT, Fields.MESSAGE, "query question",
				Fields.SESSION_ID, sid.toHexString()), owner);
		assertTrue(model.entered.await(5, TimeUnit.SECONDS));
		agent.appendSessionPending(sid, owner.getCallerDID(), null,
			Maps.of(Fields.MESSAGE, "arrived during query"), false);
		ACell afterConcurrentWrite = agent.getRecord();
		held.complete(Maps.of("role", "assistant", "content", "answer"));
		assertEquals(Strings.create("answer"), job.awaitResult(5000));
		assertEquals(afterConcurrentWrite, agent.getRecord());
		assertFalse(model.seenInput.toString().contains("arrived during query"));
	}

	@Test
	public void cancellationIsIndependentOfAgentRunLoop() throws Exception {
		AgentState agent = create("llmagent", Maps.empty());
		ACell before = agent.getRecord();
		CompletableFuture<ACell> held = new CompletableFuture<>();
		model.reply = input -> held;
		Job job = query("question");
		assertTrue(model.entered.await(5, TimeUnit.SECONDS));
		job.cancel();
		assertTrue(model.seenContext.getCancellation().get());
		held.complete(Maps.of("role", "assistant", "content", "late answer"));
		assertEquals(Status.CANCELLED, job.getStatus());
		assertEquals(before, agent.getRecord());
	}

	@Test public void flatQueryIncludesGeneratedSteps() { queryIncludesGeneratedSteps("llmagent"); }
	@Test public void goalTreeQueryIncludesGeneratedSteps() { queryIncludesGeneratedSteps("goaltree"); }

	private void queryIncludesGeneratedSteps(String runtime) {
		AgentState agent = create(runtime, Maps.of(
			Fields.TOOLS, Vectors.of(Strings.create("v/test/ops/echo"))));
		ACell before = agent.getRecord();
		java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
		ACell thinking = Maps.of("summary", "provider-supplied thinking");
		ACell arguments = Maps.of("echo", "tool input");
		model.reply = input -> {
			AMap<AString, ACell> reply = Maps.of("role", "assistant",
				"think", thinking, Fields.TOKENS, Maps.of(Fields.INPUT, 3, Fields.OUTPUT, 2));
			if (calls.getAndIncrement() == 0) {
				reply = reply.assoc(Strings.intern("content"), Strings.create("Checking the tool"))
					.assoc(Strings.intern("toolCalls"), Vectors.of(Maps.of(
						"id", "query-call", "name", "v/test/ops/echo", "arguments", arguments)));
			} else {
				reply = reply.assoc(Strings.intern("content"), Strings.create("final answer"));
			}
			return CompletableFuture.completedFuture(reply);
		};
		AMap<AString, ACell> result = queryWithSteps("question");
		assertEquals(Strings.create("final answer"), result.get(Fields.RESPONSE));
		AVector<ACell> steps = RT.ensureVector(result.get(Fields.STEPS));
		assertEquals(2, steps.count());
		assertEquals(thinking, RT.getIn(steps.get(0), Fields.REPLY, "think"));
		assertEquals(Strings.create("Checking the tool"), RT.getIn(steps.get(0), Fields.REPLY, "content"));
		assertEquals(arguments, RT.getIn(steps.get(0), Fields.REPLY, "toolCalls", CVMLong.ZERO, "arguments"));
		assertEquals(arguments, RT.getIn(steps.get(0), Fields.CALLS, CVMLong.ZERO, Fields.RESULT));
		assertEquals(Strings.create("final answer"), RT.getIn(steps.get(1), Fields.REPLY, "content"));
		assertEquals(CVMLong.create(10), RT.getIn(result, Fields.TOKENS, Fields.TOTAL));
		assertNull(RT.getIn(steps.get(0), Fields.SENT));
		assertFalse(steps.toString().contains("query persona"));
		assertEquals(before, agent.getRecord());
	}

	@Test
	public void queryIncludesChildGoalStepsAfterTheirFramesArePopped() {
		AgentState agent = create("goaltree", Maps.of(
			"llmOperation", "v/test/ops/subgoalechollm",
			Fields.TOOLS, Vectors.of(Strings.create("subgoal"))));
		ACell before = agent.getRecord();
		AMap<AString, ACell> result = queryWithSteps("decompose this");
		assertEquals(Strings.create("root done"), result.get(Fields.RESPONSE));
		AVector<ACell> steps = RT.ensureVector(result.get(Fields.STEPS));
		assertEquals(2, steps.count());
		ACell child = RT.getIn(steps.get(0), Fields.CALLS, CVMLong.ZERO, Fields.FRAME);
		assertNotNull(child);
		assertNull(RT.getIn(child, Fields.CONTEXT));
		AVector<ACell> childSteps = RT.ensureVector(RT.getIn(child, Fields.INFERENCES));
		assertEquals(2, childSteps.count());
		assertNull(RT.getIn(childSteps.get(0), Fields.SENT));
		assertEquals(Strings.create("sub done"), RT.getIn(childSteps.get(1), Fields.REPLY, "content"));
		assertFalse(steps.toString().contains("query persona"));
		assertEquals(before, agent.getRecord());
	}

	@Test public void flatSummaryIsPersistedAndReused() { summaryIsPersistedAndReused("llmagent"); }
	@Test public void goalTreeSummaryIsPersistedAndReused() { summaryIsPersistedAndReused("goaltree"); }

	private void summaryIsPersistedAndReused(String runtime) {
		AgentState agent = createSummarisable(runtime);
		ACell sessionBefore = agent.getSession(SUMMARY_SESSION);
		ACell stateBefore = agent.getRecord().get(AgentState.KEY_STATE);
		ACell timelineBefore = agent.getTimeline();
		Job job = engine.jobs().invokeOperation("v/ops/agent/summarise",
			summaryInput(false).dissoc(Strings.intern("includeSteps")), owner);
		AMap<AString, ACell> first = RT.ensureMap(job.awaitResult(5000));
		assertEquals(Strings.create("answer"), first.get(Fields.RESPONSE));
		assertTrue(RT.ensureLong(first.get(Fields.TS)).longValue() > 0);
		assertNull(first.get(Fields.STEPS));
		assertTrue(model.seenInput.toString().contains(AgentAdapter.DEFAULT_SUMMARISE_INSTRUCTION));
		assertTrue(model.seenInput.toString().contains("initial discussion"));
		assertEquals(sessionBefore, agent.getSession(SUMMARY_SESSION).dissoc(AgentState.KEY_SUMMARIES));
		assertEquals(stateBefore, agent.getRecord().get(AgentState.KEY_STATE));
		assertEquals(timelineBefore, agent.getTimeline());
		AMap<AString, ACell> saved = savedSummary(agent);
		assertEquals(first, saved, "summary memory is exactly the returned Job result");
		assertEquals(saved, engine.jobs().getJob(job.getID(), owner).getOutput());
		assertFalse(saved.containsKey(Fields.STEPS), "steps are not retained by default");
		assertEquals(CVMLong.ONE, saved.get(Fields.TURNS));
		assertFalse(saved.containsKey(Fields.SOURCE));
		assertFalse(saved.containsKey(Strings.intern("instruction")), "the instruction is the map key");
		ACell afterFirst = agent.getRecord();
		assertEquals(first, summarise(false).awaitResult(5000));
		assertEquals(first, summarise(true).awaitResult(5000),
			"requesting steps cannot reconstruct an unsaved trace or change the cached result");
		assertEquals(1, model.invocations.get(), "cache hits do not invoke the model");
		assertEquals(afterFirst, agent.getRecord(), "cache hits do not update any timestamp");
		agent.ensureSession(Blob.fromHex("9876"), owner.getCallerDID());
		ACell withOtherSession = agent.getRecord();
		assertEquals(first, summarise(false).awaitResult(5000));
		assertEquals(1, model.invocations.get(), "unrelated sessions do not invalidate the summary");
		assertEquals(withOtherSession, agent.getRecord());
	}

	@Test public void flatSummaryStepsAreOptIn() { summaryStepsAreOptIn("llmagent"); }
	@Test public void goalTreeSummaryStepsAreOptIn() { summaryStepsAreOptIn("goaltree"); }

	private void summaryStepsAreOptIn(String runtime) {
		AgentState agent = createSummarisable(runtime);
		Job job = summarise(true);
		AMap<AString, ACell> first = RT.ensureMap(job.awaitResult(5000));
		assertEquals(1, RT.ensureVector(first.get(Fields.STEPS)).count());
		assertEquals(first, savedSummary(agent));
		assertEquals(first, engine.jobs().getJob(job.getID(), owner).getOutput());
		ACell afterFirst = agent.getRecord();
		assertEquals(first, summarise(true).awaitResult(5000));
		assertEquals(first, summarise(false).awaitResult(5000), "cached output is returned exactly as saved");
		assertEquals(afterFirst, agent.getRecord());
		assertEquals(1, model.invocations.get());
		addSummaryTurn(agent, "a later turn");
		AMap<AString, ACell> refreshed = RT.ensureMap(summarise(false).awaitResult(5000));
		assertFalse(refreshed.containsKey(Fields.STEPS), "refresh uses this invocation's requested detail level");
		assertEquals(refreshed, savedSummary(agent));
		assertEquals(2, model.invocations.get());
	}

	@Test
	public void newTurnsRefreshSummaryEvenWithTheSameTurnTimestamp() {
		AgentState agent = createSummarisable("llmagent");
		summarise(false).awaitResult(5000);
		ACell first = savedSummary(agent);
		ACell updated = RT.getIn(agent.getSession(SUMMARY_SESSION), "meta", "updated");
		addSummaryTurn(agent, "new fact at the same timestamp");
		assertEquals(updated, RT.getIn(agent.getSession(SUMMARY_SESSION), "meta", "updated"));
		assertEquals(first, savedSummary(agent), "old summaries remain readable after conversation advances");
		summarise(false).awaitResult(5000);
		assertEquals(2, model.invocations.get());
		assertTrue(model.seenInput.toString().contains("new fact at the same timestamp"));
		assertEquals(CVMLong.create(2), savedSummary(agent).get(Fields.TURNS));
	}

	@Test
	public void summariseRetainsIndependentResultsByExactMetadataInstruction() {
		AgentState agent = createSummarisable("llmagent");
		AMap<AString, ACell> concise = summariseMetadata(Strings.create("Summarise as three bullets."));
		ACell first = engine.jobs().invokeOperation(concise, summaryInput(false), owner).awaitResult(5000);
		assertTrue(model.seenInput.toString().contains("Summarise as three bullets."));
		assertEquals(first, engine.jobs().invokeOperation(concise, summaryInput(false), owner).awaitResult(5000));
		assertEquals(1, model.invocations.get());
		AMap<AString, ACell> actions = summariseMetadata(Strings.create("Summarise the agreed actions."));
		ACell actionResult = engine.jobs().invokeOperation(actions, summaryInput(false), owner).awaitResult(5000);
		assertEquals(2, model.invocations.get());
		assertTrue(model.seenInput.toString().contains("Summarise the agreed actions."));
		assertEquals(first, engine.jobs().invokeOperation(concise, summaryInput(false), owner).awaitResult(5000));
		assertEquals(actionResult, engine.jobs().invokeOperation(actions, summaryInput(false), owner).awaitResult(5000));
		assertEquals(2, RT.ensureMap(agent.getSession(SUMMARY_SESSION).get(AgentState.KEY_SUMMARIES)).count());
		ACell beforeInvalid = agent.getRecord();
		Job bad = engine.jobs().invokeOperation(summariseMetadata(CVMLong.ONE), summaryInput(false), owner);
		assertThrows(JobFailedException.class, () -> bad.awaitResult(5000));
		assertEquals(AgentAdapter.SUMMARISE_INVALID_INSTRUCTION, bad.getErrorMessage());
		assertEquals(2, model.invocations.get());
		assertEquals(beforeInvalid, agent.getRecord());
		addSummaryTurn(agent, "newly agreed action");
		engine.jobs().invokeOperation(actions, summaryInput(false), owner).awaitResult(5000);
		assertEquals(3, model.invocations.get());
		assertEquals(first, AgentState.sessionSummary(agent.getSession(SUMMARY_SESSION),
			Strings.create("Summarise as three bullets.")),
			"refreshing one instruction preserves the other result and its timestamp");
		assertEquals(CVMLong.create(2), AgentState.sessionSummary(agent.getSession(SUMMARY_SESSION),
			Strings.create("Summarise the agreed actions.")).get(Fields.TURNS));
	}

	@Test
	public void compactionPreservesCoveredTurnCountAndNewTurnsStillRefresh() {
		AgentState agent = createSummarisable("llmagent");
		addSummaryTurn(agent, "second discussion turn");
		ACell first = summarise(false).awaitResult(5000);
		assertTrue(agent.updateQuiescentSessionFrames(SUMMARY_SESSION, frames ->
			frames.assoc(0, GoalTreeContext.compactFrame(RT.ensureMap(frames.get(0)), "earlier discussion"))));
		assertEquals(2, AgentState.sessionTurnCount(agent.getSession(SUMMARY_SESSION)));
		assertEquals(first, summarise(false).awaitResult(5000));
		assertEquals(1, model.invocations.get());
		addSummaryTurn(agent, "discussion after compaction");
		AMap<AString, ACell> refreshed = RT.ensureMap(summarise(false).awaitResult(5000));
		assertEquals(CVMLong.create(3), refreshed.get(Fields.TURNS));
		assertEquals(2, model.invocations.get());
	}

	@Test
	public void conversationCanAdvanceWhileSummaryIsGenerated() throws Exception {
		AgentState agent = createSummarisable("llmagent");
		summarise(false).awaitResult(5000);
		addSummaryTurn(agent, "before the second summary");
		CompletableFuture<ACell> held = new CompletableFuture<>();
		CompletableFuture<ACell> started = new CompletableFuture<>();
		model.reply = input -> { started.complete(input); return held; };
		Job job = summarise(false);
		started.get(5, TimeUnit.SECONDS);
		agent.appendSessionPending(SUMMARY_SESSION, owner.getCallerDID(), null,
			Maps.of(Fields.MESSAGE, "arrived during summarisation"), false);
		addSummaryTurn(agent, "new conversation during summarisation");
		ACell afterConcurrentWrite = agent.getSession(SUMMARY_SESSION).dissoc(AgentState.KEY_SUMMARIES);
		held.complete(Maps.of("role", "assistant", "content", "summary through the second turn"));
		AMap<AString, ACell> result = RT.ensureMap(job.awaitResult(5000));
		assertEquals(CVMLong.create(2), result.get(Fields.TURNS));
		assertEquals(result, savedSummary(agent));
		assertEquals(afterConcurrentWrite, agent.getSession(SUMMARY_SESSION).dissoc(AgentState.KEY_SUMMARIES));
		assertEquals(3, AgentState.sessionTurnCount(agent.getSession(SUMMARY_SESSION)));
		model.reply = input -> CompletableFuture.completedFuture(Maps.of("role", "assistant", "content", "refreshed"));
		AMap<AString, ACell> refreshed = RT.ensureMap(summarise(false).awaitResult(5000));
		assertEquals(CVMLong.create(3), refreshed.get(Fields.TURNS));
		assertEquals(Strings.create("refreshed"), refreshed.get(Fields.RESPONSE));
		assertEquals(3, model.invocations.get());
	}

	@Test
	public void concurrentEquivalentSummariesPublishOneStableResult() throws Exception {
		AgentState agent = createSummarisable("llmagent");
		CompletableFuture<ACell> firstReply = new CompletableFuture<>();
		CompletableFuture<ACell> secondReply = new CompletableFuture<>();
		CountDownLatch entered = new CountDownLatch(2);
		java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
		model.reply = input -> {
			CompletableFuture<ACell> reply = calls.getAndIncrement() == 0 ? firstReply : secondReply;
			entered.countDown();
			return reply;
		};
		Job first = summarise(false);
		assertTrue(model.entered.await(5, TimeUnit.SECONDS));
		Job second = summarise(true);
		assertTrue(entered.await(5, TimeUnit.SECONDS));
		firstReply.complete(Maps.of("role", "assistant", "content", "first published summary"));
		ACell result = first.awaitResult(5000);
		ACell afterFirst = agent.getRecord();
		secondReply.complete(Maps.of("role", "assistant", "content", "alternative summary"));
		assertEquals(result, second.awaitResult(5000));
		assertEquals(result, savedSummary(agent), "the first publication fixes the saved and returned detail level");
		assertEquals(afterFirst, agent.getRecord());
		assertEquals(result, summarise(false).awaitResult(5000));
		assertEquals(2, model.invocations.get());
	}

	@Test
	public void deletedSessionIsNotRecreatedByLateSummary() throws Exception {
		AgentState agent = createSummarisable("llmagent");
		CompletableFuture<ACell> held = new CompletableFuture<>();
		model.reply = input -> held;
		Job job = summarise(false);
		assertTrue(model.entered.await(5, TimeUnit.SECONDS));
		agent.removeSession(SUMMARY_SESSION);
		held.complete(Maps.of("role", "assistant", "content", "late summary"));
		assertThrows(JobFailedException.class, () -> job.awaitResult(5000));
		assertTrue(job.getErrorMessage().contains(AgentAdapter.SUMMARISE_UNAVAILABLE));
		assertNull(agent.getSession(SUMMARY_SESSION));
	}

	@Test
	public void summariseFailureOrCancellationDoesNotPublish() throws Exception {
		AgentState agent = createSummarisable("llmagent");
		ACell before = agent.getRecord();
		model.reply = input -> CompletableFuture.failedFuture(new IllegalStateException(FAILURE));
		Job failed = summarise(false);
		assertThrows(JobFailedException.class, () -> failed.awaitResult(5000));
		assertEquals(before, agent.getRecord());
		CompletableFuture<ACell> held = new CompletableFuture<>();
		CompletableFuture<ACell> started = new CompletableFuture<>();
		model.reply = input -> { started.complete(input); return held; };
		Job cancelled = summarise(false);
		started.get(5, TimeUnit.SECONDS);
		cancelled.cancel();
		held.complete(Maps.of("role", "assistant", "content", "late summary"));
		assertEquals(Status.CANCELLED, cancelled.getStatus());
		assertEquals(before, agent.getRecord());
	}

	@Test
	public void summariseRequiresWriteAuthorityEvenForCachedResults() {
		createSummarisable("llmagent");
		summarise(false).awaitResult(5000);
		RequestContext messageOnly = owner.withCaps(Vectors.of(
			Capability.create(Strings.create("v/ops/agent/summarise"), Strings.create("invoke")),
			Capability.create(Strings.create("g/queried"), covia.api.Abilities.AGENT_MESSAGE)));
		Job denied = engine.jobs().invokeOperation("v/ops/agent/summarise", summaryInput(false), messageOnly);
		assertThrows(JobFailedException.class, () -> denied.awaitResult(5000));
		assertEquals(1, model.invocations.get());
	}

	@Test
	public void concurrentDifferentInstructionsBothPersist() throws Exception {
		AgentState agent = createSummarisable("llmagent");
		CompletableFuture<ACell> held = new CompletableFuture<>();
		model.reply = input -> input.toString().contains("slow instruction") ? held
			: CompletableFuture.completedFuture(Maps.of("role", "assistant", "content", "current summary"));
		Job slow = engine.jobs().invokeOperation(summariseMetadata(Strings.create("slow instruction")),
			summaryInput(false), owner);
		assertTrue(model.entered.await(5, TimeUnit.SECONDS));
		ACell fastResult = engine.jobs().invokeOperation(summariseMetadata(Strings.create("different instruction")),
			summaryInput(false), owner).awaitResult(5000);
		held.complete(Maps.of("role", "assistant", "content", "slow summary"));
		ACell slowResult = slow.awaitResult(5000);
		assertEquals(slowResult, AgentState.sessionSummary(agent.getSession(SUMMARY_SESSION),
			Strings.create("slow instruction")));
		assertEquals(fastResult, AgentState.sessionSummary(agent.getSession(SUMMARY_SESSION),
			Strings.create("different instruction")));
		assertEquals(2, RT.ensureMap(agent.getSession(SUMMARY_SESSION).get(AgentState.KEY_SUMMARIES)).count());
	}

	@Test
	public void olderGenerationCannotReplaceSummaryOfNewerConversation() throws Exception {
		AgentState agent = createSummarisable("llmagent");
		CompletableFuture<ACell> held = new CompletableFuture<>();
		model.reply = input -> held;
		Job slow = summarise(false);
		assertTrue(model.entered.await(5, TimeUnit.SECONDS));
		addSummaryTurn(agent, "newer conversation");
		model.reply = input -> CompletableFuture.completedFuture(Maps.of("role", "assistant", "content", "newer summary"));
		ACell newer = summarise(false).awaitResult(5000);
		ACell afterNewer = agent.getRecord();
		held.complete(Maps.of("role", "assistant", "content", "older summary"));
		assertEquals(newer, slow.awaitResult(5000));
		assertEquals(afterNewer, agent.getRecord());
		assertEquals(CVMLong.create(2), savedSummary(agent).get(Fields.TURNS));
	}

	@Test
	public void agentChangesAndQueuedMessagesLeaveSavedResultsUsable() {
		AgentState agent = createSummarisable("llmagent");
		ACell first = summarise(false).awaitResult(5000);
		agent.putRecord(agent.getRecord()
			.assoc(AgentState.KEY_STATE, Maps.of("progress", "changed"))
			.assoc(AgentState.KEY_CONFIG, RT.ensureMap(agent.getRecord().get(AgentState.KEY_CONFIG))
				.assoc(Strings.intern("systemPrompt"), Strings.create("updated persona"))));
		agent.appendSessionPending(SUMMARY_SESSION, Maps.of(Fields.MESSAGE, "not yet presented"));
		ACell before = agent.getRecord();
		assertEquals(first, summarise(false).awaitResult(5000));
		assertEquals(before, agent.getRecord());
		assertEquals(1, model.invocations.get());
	}

	@Test public void defaultSummaryAndItsCacheSurviveStoreReopen() throws Exception { summarySurvivesStoreReopen(false); }
	@Test public void detailedSummaryAndItsCacheSurviveStoreReopen() throws Exception { summarySurvivesStoreReopen(true); }

	private void summarySurvivesStoreReopen(boolean includeSteps) throws Exception {
		var key = engine.getKeyPair();
		engine.close();
		java.io.File file;
		ACell expected;
		Blob jobId;
		try (convex.etch.EtchStore store = convex.etch.EtchStore.createTemp("summarise-persist")) {
			file = store.getFile();
			engine = new Engine(Maps.empty(), covia.venue.CoviaApplication.open(store, key), key).start();
			try {
				Engine.addDemoAssets(engine);
				owner = engine.venueContext();
				model = new QueryModel();
				engine.registerAdapter(model);
				createSummarisable("llmagent");
				Job job = summarise(includeSteps);
				expected = job.awaitResult(5000);
				jobId = job.getID();
			} finally { engine.close(); }
		}
		try (convex.etch.EtchStore store = convex.etch.EtchStore.create(file)) {
			engine = new Engine(Maps.empty(), covia.venue.CoviaApplication.open(store, key), key).start();
			try {
				Engine.addDemoAssets(engine);
				owner = engine.venueContext();
				model = new QueryModel();
				engine.registerAdapter(model);
				AgentState agent = engine.getVenueState().users().get(owner.getUserDID()).agent(AGENT);
				assertEquals(expected, savedSummary(agent));
				assertEquals(expected, engine.jobs().getJob(jobId, owner).getOutput());
				assertEquals(includeSteps, savedSummary(agent).containsKey(Fields.STEPS));
				assertEquals(expected, summarise(true).awaitResult(5000));
				assertEquals(expected, summarise(false).awaitResult(5000));
				assertEquals(0, model.invocations.get(), "the cache is durable, not an in-memory optimisation");
			} finally { engine.close(); }
		}
	}
}
