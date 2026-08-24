package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.agent.ContextAssembler;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.User;
import covia.venue.VenueState;

/**
 * Tests for the AgentAdapter: create, message, trigger, request, query, and list operations.
 *
 * <p>Uses the shared {@link TestEngine#ENGINE}; each test gets unique
 * ALICE_DID / BOB_DID via {@link TestEngine#uniqueDID(TestInfo)} so agent
 * names and user state don't collide across tests.</p>
 */
public class AgentAdapterTest {

	private final Engine engine = TestEngine.ENGINE;
	// ALICE_DID / BOB_DID are per-test (not static) so each test sees a fresh
	// user namespace within the shared engine.
	private AString ALICE_DID;
	private AString BOB_DID;

	@Test
	public void testAwaitLoopExitIsBoundedAndAcceptsExceptionalExit() throws Exception {
		CompletableFuture<ACell> wedged = new CompletableFuture<>();
		assertThrows(TimeoutException.class,
			() -> AgentAdapter.awaitLoopExit(wedged, 25));

		CompletableFuture<ACell> failed = new CompletableFuture<>();
		failed.completeExceptionally(new IllegalStateException("loop failed"));
		assertDoesNotThrow(() -> AgentAdapter.awaitLoopExit(failed, 25),
			"an exceptional completion is still a completed shutdown");
	}

	@Test
	public void testTerminatedAgentCancelsLateRegisteredTransition() {
		AgentState agent = engine.getVenueState().users().ensure(ALICE_DID)
			.ensureAgent(Strings.create("late-transition"), Maps.empty(), null);
		assertFalse(AgentAdapter.shouldCancelRegisteredTransition(agent));

		agent.setStatus(AgentState.TERMINATED);
		assertTrue(AgentAdapter.shouldCancelRegisteredTransition(agent),
			"a transition registered after deletion must be cancelled");
		assertTrue(AgentAdapter.shouldCancelRegisteredTransition(null),
			"a transition registered after physical removal must be cancelled");
	}

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
		BOB_DID = Strings.create(ALICE_DID.toString() + "-bob");
	}

	/**
	 * Waits — signal-based, via the run loop's completion future — for the agent
	 * to reach a rest state (SLEEPING/SUSPENDED/TERMINATED) and returns it; fails
	 * the test if it is still RUNNING after 10s.
	 */
	private AString awaitFinished(AgentState agent) {
		try {
			return ((AgentAdapter) engine.getAdapter("agent"))
				.awaitRunFinished(agent.getAgentId(), RequestContext.of(ALICE_DID), 10_000);
		} catch (java.util.concurrent.TimeoutException e) {
			throw new AssertionError("Agent '" + agent.getAgentId()
				+ "' did not reach a rest state in 10s", e);
		}
	}

	/** Current API-visible status, including the live executor overlay. */
	private AString observableStatus(AgentState agent) {
		AMap<AString, ACell> info = ((AgentAdapter) engine.getAdapter("agent"))
			.agentInfo(RequestContext.of(ALICE_DID), agent.getAgentId());
		return RT.ensureString(info.get(Fields.STATUS));
	}

	@Test
	public void testQualifiedAgentReferencesUseNormalGridPaths() {
		AString address = Strings.create(ALICE_DID + "/g/qualified-agent");
		ACell created = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, address),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		assertEquals(Strings.create("qualified-agent"), RT.getIn(created, Fields.AGENT_ID));
		assertEquals(address, RT.getIn(created, Fields.ADDRESS));
		assertNotNull(engine.resolvePath(address, RequestContext.of(ALICE_DID)),
			"qualified g/ paths should resolve through the universal grid resolver");

		AgentAdapter adapter = (AgentAdapter) engine.getAdapter("agent");
		for (AString ref : new AString[] {
			Strings.create("qualified-agent"),
			Strings.create("g/qualified-agent"),
			Strings.create("/g/qualified-agent"),
			address
		}) {
			AMap<AString, ACell> info = adapter.agentInfo(RequestContext.of(ALICE_DID), ref);
			assertNotNull(info, "reference should resolve: " + ref);
			assertEquals(Strings.create("qualified-agent"), info.get(Fields.AGENT_ID));
			assertEquals(address, info.get(Fields.ADDRESS));
		}

		ACell updated = engine.jobs().invokeOperation(
			"v/ops/agent/update",
			Maps.of(Fields.AGENT_ID, address, AgentState.KEY_STATE, Maps.of("qualified", true)),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		assertEquals(address, RT.getIn(updated, Fields.ADDRESS));
		assertEquals(CVMBool.TRUE, RT.getIn(
			engine.getVenueState().users().get(ALICE_DID)
				.agent("qualified-agent").getState(), "qualified"));
	}

	@Test
	public void testCrossUserQualifiedAgentOperationsWithOwnerProof() {
		AKeyPair ownerKP = AKeyPair.generate();
		AKeyPair delegateKP = AKeyPair.generate();
		AString ownerDID = UCAN.toDIDKey(ownerKP.getAccountKey());
		AString delegateDID = UCAN.toDIDKey(delegateKP.getAccountKey());
		AString address = Strings.create(ownerDID + "/g/delegated-agent");

		long exp = (System.currentTimeMillis() / 1000) + 3600;
		UCAN grant = UCAN.create(ownerKP, delegateKP.getAccountKey(), exp,
			Vectors.of(
				Capability.create(address, Abilities.AGENT_CREATE),
				Capability.create(address, Capability.CRUD_READ),
				Capability.create(address, Abilities.AGENT_REQUEST),
				Capability.create(address, Abilities.AGENT_WRITE)),
			Vectors.empty());
		RequestContext delegated = RequestContext.of(delegateDID)
			.withProofs(Vectors.of(grant.toMap()));

		ACell created = engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, address,
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			delegated).awaitResult(5000);
		assertEquals(address, RT.getIn(created, Fields.ADDRESS));

		ACell info = engine.jobs().invokeOperation("v/ops/agent/info",
			Maps.of(Fields.AGENT_ID, address), delegated).awaitResult(5000);
		assertEquals(address, RT.getIn(info, Fields.ADDRESS));

		ACell result = engine.jobs().invokeOperation("v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, address, Fields.INPUT, Maps.of("delegated", true)),
			delegated).awaitResult(5000);
		assertNotNull(result);
		ACell suspended = engine.jobs().invokeOperation("v/ops/agent/suspend",
			Maps.of(Fields.AGENT_ID, address), delegated).awaitResult(5000);
		assertEquals(AgentState.SUSPENDED, RT.getIn(suspended, Fields.STATUS));
		assertNotNull(engine.getVenueState().users().get(ownerDID).agent("delegated-agent"));
		User delegate = engine.getVenueState().users().get(delegateDID);
		assertTrue(delegate == null || delegate.agent("delegated-agent") == null,
			"the delegated request must run the owner's agent, not a same-named delegate agent");
	}

	@Test
	public void testCrossUserQualifiedAgentDeniedWithoutMatchingProof() {
		AKeyPair ownerKP = AKeyPair.generate();
		AKeyPair delegateKP = AKeyPair.generate();
		AString ownerDID = UCAN.toDIDKey(ownerKP.getAccountKey());
		AString delegateDID = UCAN.toDIDKey(delegateKP.getAccountKey());
		AString address = Strings.create(ownerDID + "/g/private-agent");
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "private-agent"), RequestContext.of(ownerDID))
			.awaitResult(5000);

		Job denied = engine.jobs().invokeOperation("v/ops/agent/info",
			Maps.of(Fields.AGENT_ID, address), RequestContext.of(delegateDID));
		assertThrows(covia.exception.JobFailedException.class, () -> denied.awaitResult(5000));
		assertTrue(denied.getErrorMessage().contains("crud/read"));
	}

	@Test
	public void testCrossUserQualifiedAgentRequiresTheActionAbility() {
		AKeyPair ownerKP = AKeyPair.generate();
		AKeyPair delegateKP = AKeyPair.generate();
		AString ownerDID = UCAN.toDIDKey(ownerKP.getAccountKey());
		AString delegateDID = UCAN.toDIDKey(delegateKP.getAccountKey());
		AString address = Strings.create(ownerDID + "/g/read-only-agent");
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "read-only-agent"), RequestContext.of(ownerDID))
			.awaitResult(5000);

		long exp = (System.currentTimeMillis() / 1000) + 3600;
		UCAN readGrant = UCAN.create(ownerKP, delegateKP.getAccountKey(), exp,
			Vectors.of(Capability.create(address, Capability.CRUD_READ)), Vectors.empty());
		RequestContext delegated = RequestContext.of(delegateDID)
			.withProofs(Vectors.of(readGrant.toMap()));
		assertNotNull(engine.jobs().invokeOperation("v/ops/agent/info",
			Maps.of(Fields.AGENT_ID, address), delegated).awaitResult(5000));

		Job denied = engine.jobs().invokeOperation("v/ops/agent/suspend",
			Maps.of(Fields.AGENT_ID, address), delegated);
		assertThrows(covia.exception.JobFailedException.class, () -> denied.awaitResult(5000));
		assertTrue(denied.getErrorMessage().contains("agent/write"));
		assertEquals(AgentState.SLEEPING,
			engine.getVenueState().users().get(ownerDID).agent("read-only-agent").getStatus());
	}

	@Test
	public void testAuthenticatedCallerCanReadPublicQualifiedAgent() {
		AString publicDID = Strings.create(engine.getDIDString() + ":public");
		AString id = Strings.create("public-qualified-" + Math.abs(ALICE_DID.hashCode()));
		engine.getVenueState().users().ensure(publicDID).ensureAgent(id, Maps.empty(), null);

		AMap<AString, ACell> info = ((AgentAdapter) engine.getAdapter("agent"))
			.agentInfo(RequestContext.of(BOB_DID), Strings.create(publicDID + "/g/" + id));
		assertNotNull(info);
		assertEquals(Strings.create(publicDID + "/g/" + id), info.get(Fields.ADDRESS));
	}

	// ========== agent:create ==========

	/**
	 * Regression for #67 and #69: timeline entries must not snapshot the
	 * full agent config, and empty collection fields (messages/inbox/etc)
	 * must be elided. Confirms that the Sessions migration's "only include
	 * non-empty collections" rule (AgentAdapter.java:1509) holds.
	 */
	@Test
	public void testTimelineEntryOmitsConfigAndEmptyCollections() {
		// Sizable config that would bloat the timeline if snapshotted per transition
		AMap<AString, ACell> bigConfig = Maps.of(
			Fields.OPERATION, Strings.create("v/test/ops/echo"),
			Strings.create("systemPrompt"), Strings.create(
				"A relatively long system prompt that we want to confirm is NOT "
				+ "duplicated across every timeline entry. Repeat repeat repeat."),
			Strings.create("responseFormat"), Maps.of(
				Strings.create("name"), Strings.create("Report"),
				Strings.create("schema"), Maps.of("type", "object"))
		);

		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "timeline-shape-agent", Fields.CONFIG, bigConfig),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User u = engine.getVenueState().users().get(ALICE_DID);
		AgentState a = u.agent("timeline-shape-agent");
		Blob sid = Blob.fromHex("eeee0001eeee0001eeee0001eeee0001");
		a.ensureSession(sid, ALICE_DID);
		a.appendSessionPending(sid, Maps.of(
			Fields.SESSION_ID, Strings.create(sid.toHexString()),
			Fields.MESSAGE, Maps.of("content", "hi")));

		engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "timeline-shape-agent",
				Fields.WAIT, CVMLong.create(5000)),
			RequestContext.of(ALICE_DID)).awaitResult(6000);

		AVector<ACell> timeline = u.agent("timeline-shape-agent").getTimeline();
		assertNotNull(timeline, "Timeline should exist after trigger");
		assertTrue(timeline.count() >= 1, "At least one timeline entry expected");

		for (ACell entryCell : timeline) {
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> entry = (AMap<AString, ACell>) entryCell;

			// #67: per-entry config snapshot must not appear
			assertNull(entry.get(AgentState.KEY_CONFIG),
				"#67: timeline entry must not snapshot full config; got: " + entry);
			assertNull(entry.get(AgentState.KEY_STATE),
				"#67: timeline entry must not snapshot full state; got: " + entry);

			// #69: empty collections must be elided, not stored as empty.
			// RT.ensureVector returns null on a non-vector — guard before
			// reading count so a regression that stored e.g. an empty map
			// fails with a clean assertion, not NPE.
			ACell messages = entry.get(Fields.MESSAGES);
			if (messages != null) {
				AVector<?> messagesVec = RT.ensureVector(messages);
				assertNotNull(messagesVec,
					"Fields.MESSAGES must be a vector when present; got: " + messages);
				assertTrue(messagesVec.count() > 0,
					"#69: empty 'messages' must be omitted, not stored as empty vector");
			}
			ACell tasks = entry.get(Fields.TASKS);
			if (tasks != null) {
				AVector<?> tasksVec = RT.ensureVector(tasks);
				assertNotNull(tasksVec,
					"Fields.TASKS must be a vector when present; got: " + tasks);
				assertTrue(tasksVec.count() > 0,
					"#69: empty 'tasks' must be omitted, not stored as empty vector");
			}
		}
	}

	@Test
	public void testCreateAgent() {
		ACell input = Maps.of(Fields.AGENT_ID, "my-assistant");
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result, "Create should return a result");
		assertEquals(Strings.create("my-assistant"), RT.getIn(result, Fields.AGENT_ID));
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		AgentState agent = engine.getVenueState().users().get(ALICE_DID)
			.agent("my-assistant");
		AMap<AString, ACell> config = agent.getConfig();
		assertEquals(engine.config().getDefaultTransitionOp(), config.get(Fields.OPERATION));
		assertEquals(engine.config().getDefaultLlmOperation(),
			config.get(Strings.intern("llmOperation")));
		assertEquals(3, RT.ensureVector(config.get(Strings.intern("tools"))).count(),
			"no-config agent should start with skilled inspect/read/list tools");
		assertEquals(2, RT.ensureVector(config.get(Strings.intern("skillsets"))).count(),
			"no-config agent should discover workspace and venue skills");
		assertNull(config.get(Strings.intern("model")),
			"venue/provider should choose the default model");
		AMap<AString, ACell> context = RT.ensureMap(engine.jobs().invokeOperation(
			"v/ops/agent/context", Maps.of(Fields.AGENT_ID, "my-assistant"),
			RequestContext.of(ALICE_DID)).awaitResult(5000));
		assertEquals(Strings.create("claude-sonnet-5"), context.get(Strings.intern("model")),
			"inspection should expose the effective model supplied by the model operation");
	}

	@Test
	public void testCreateAgentWithConfig() {
		ACell input = Maps.of(
			Fields.AGENT_ID, "configured-agent",
			Fields.CONFIG, Maps.of("model", "gpt-4", "temperature", "0.7")
		);
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID));
		job.awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		assertNotNull(user);
		AgentState agent = user.agent("configured-agent");
		assertNotNull(agent);
		AMap<AString, ACell> storedConfig = agent.getConfig();
		assertNotNull(storedConfig, "Config should be stored");
	}

	// ========== #205 — tool-capability advisory on create ==========

	@Test
	public void testToolWarningForDecision() {
		// null caps (couldn't determine — unreachable / not installed) → warn
		AString unknown = AgentAdapter.toolWarningFor("qwen2.5", "http://localhost:11434", null);
		assertNotNull(unknown);
		assertTrue(unknown.toString().contains("qwen2.5"));

		// caps without "tools" → warn
		AString noTools = AgentAdapter.toolWarningFor(
			"gemma3", "http://localhost:11434", java.util.List.of("completion", "vision"));
		assertNotNull(noTools);
		assertTrue(noTools.toString().contains("does not advertise tool-calling"));

		// caps including "tools" → no warning
		// Declared data: a resolved profile that says toolCalling: false warns without a probe.
		assertNull(AgentAdapter.declaredNoToolCalling(Maps.empty(), null, Strings.create("v/ops/x")));
		AString declared = AgentAdapter.declaredNoToolCalling(
			Maps.of("options", Maps.of("toolCalling", CVMBool.FALSE)),
			Strings.create("tiny"), Strings.create("v/ops/langchain/ollama"));
		assertTrue(declared.toString().contains("'tiny'") && declared.toString().contains("tool calling is off"), declared.toString());
		assertNull(AgentAdapter.toolWarningFor(
			"qwen2.5", "http://localhost:11434", java.util.List.of("completion", "tools")));
	}

	@Test
	public void testCreateOllamaWithToolsWarnsWhenUnverifiable() {
		// url points at a dead port → probe fails fast → "couldn't confirm" advisory.
		// Deterministic across environments (nothing serves Ollama on port 1).
		ACell input = Maps.of(
			Fields.AGENT_ID, "ollama-tooler",
			Fields.CONFIG, Maps.of(
				"operation", "v/ops/llmagent/chat",
				"llmOperation", "v/ops/langchain/ollama",
				"model", "qwen2.5",
				"url", "http://localhost:1",
				"tools", Vectors.of(Strings.create("v/ops/covia/read"))));
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		AVector<ACell> warnings = RT.ensureVector(RT.getIn(result, Fields.WARNINGS));
		assertNotNull(warnings, "Ollama + tools with an unverifiable model should carry a warning");
		assertEquals(1, warnings.count());
		assertTrue(RT.ensureString(warnings.get(0)).toString().contains("qwen2.5"));
	}

	@Test
	public void testCreateOllamaWithoutToolsNoWarning() {
		// No tools declared → nothing to check, even for Ollama.
		ACell input = Maps.of(
			Fields.AGENT_ID, "ollama-plain",
			Fields.CONFIG, Maps.of(
				"operation", "v/ops/llmagent/chat",
				"llmOperation", "v/ops/langchain/ollama",
				"model", "qwen2.5",
				"url", "http://localhost:1"));
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNull(RT.getIn(result, Fields.WARNINGS), "No tools → no advisory");
	}

	@Test
	public void testCreateNonOllamaWithToolsNoWarning() {
		// Hosted providers don't expose model capabilities in advance — silence
		// beats a guess, so no probe and no warning.
		ACell input = Maps.of(
			Fields.AGENT_ID, "openai-tooler",
			Fields.CONFIG, Maps.of(
				"operation", "v/ops/llmagent/chat",
				"llmOperation", "v/ops/langchain/openai",
				"model", "gpt-5.4-mini",
				"tools", Vectors.of(Strings.create("v/ops/covia/read"))));
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNull(RT.getIn(result, Fields.WARNINGS), "Non-Ollama provider → no advisory");
	}

	@Test
	public void testCreateWarnsOnRawApiKey() {
		// A raw credential in config persists unredacted on the lattice —
		// the supported pattern is a secret-store reference.
		ACell input = Maps.of(
			Fields.AGENT_ID, "raw-key-agent",
			Fields.CONFIG, Maps.of(
				"operation", "v/ops/llmagent/chat",
				"llmOperation", "v/ops/langchain/openai",
				"model", "gpt-5.4-mini",
				"apiKey", "sk-live-abcdef123456"));
		ACell result = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID)).awaitResult(5000);

		AVector<ACell> warnings = RT.ensureVector(RT.getIn(result, Fields.WARNINGS));
		assertNotNull(warnings, "raw apiKey in config should carry a warning");
		assertEquals(1, warnings.count());
		String w = RT.ensureString(warnings.get(0)).toString();
		assertTrue(w.contains("v/ops/secret/set"),
			"warning should name the remedy as an invocable catalog path: " + w);
		assertTrue(w.contains("s/<name>"), "warning should show the reference form: " + w);
	}

	@Test
	public void testCreateSecretRefApiKeyNoWarning() {
		// Secret references are the supported pattern — both accepted prefixes.
		for (String ref : new String[] {"s/OPENAI_API_KEY", "/s/OPENAI_API_KEY"}) {
			ACell input = Maps.of(
				Fields.AGENT_ID, "ref-key-agent-" + ref.length(),
				Fields.CONFIG, Maps.of(
					"operation", "v/ops/llmagent/chat",
					"llmOperation", "v/ops/langchain/openai",
					"model", "gpt-5.4-mini",
					"apiKey", ref));
			ACell result = engine.jobs().invokeOperation(
				"v/ops/agent/create", input, RequestContext.of(ALICE_DID)).awaitResult(5000);
			assertNull(RT.getIn(result, Fields.WARNINGS),
				"secret reference " + ref + " → no advisory");
		}
	}

	// ========== Agent ops via the internal path (the LLM tool-loop seam) ==========

	@Test
	public void testAgentLifecycleOpsInvokableInternally() throws Exception {
		// Regression (#85 fall-out, caught live by an agent calling agent_create
		// as a tool): lifecycle ops reached via the transient-Job internal path must
		// delegate to the Job-aware dispatch — a real, owner-attributed Job —
		// not throw UnsupportedOperationException.
		ACell created = engine.jobs().invokeInternal("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "tool-made",
				Fields.CONFIG, Maps.of("llmOperation", "v/test/ops/llm")),
			RequestContext.of(ALICE_DID)).get(5, java.util.concurrent.TimeUnit.SECONDS);
		assertEquals(Strings.create("tool-made"), RT.getIn(created, Fields.AGENT_ID));
		assertNull(RT.getIn(created, Fields.CREATED),
			"successful create needs no redundant created flag");

		// Job-worthy: the create is on the record as a persisted Job.
		boolean createJobFound = false;
		for (var e : engine.jobs().getJobs(RequestContext.of(ALICE_DID)).entrySet()) {
			ACell in = RT.getIn(e.getValue(), Fields.INPUT);
			if (Strings.create("tool-made").equals(RT.getIn(in, Fields.AGENT_ID))) {
				createJobFound = true;
				break;
			}
		}
		assertTrue(createJobFound, "internal agent:create must persist an audit Job");

		ACell updated = engine.jobs().invokeInternal("v/ops/agent/update",
			Maps.of(Fields.AGENT_ID, "tool-made",
				Fields.CONFIG, Maps.of("model", "gpt-5.4-mini")),
			RequestContext.of(ALICE_DID)).get(5, java.util.concurrent.TimeUnit.SECONDS);
		assertNotNull(updated);
	}

	@Test
	public void testAgentReadsStayJobFreeInternally() throws Exception {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "readable",
				Fields.CONFIG, Maps.of("llmOperation", "v/test/ops/llm")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		long jobsBefore = engine.jobs().getJobs(RequestContext.of(ALICE_DID)).count();

		ACell list = engine.jobs().invokeInternal("v/ops/agent/list", Maps.empty(),
			RequestContext.of(ALICE_DID)).get(5, java.util.concurrent.TimeUnit.SECONDS);
		AVector<ACell> agents = RT.ensureVector(RT.getIn(list, Strings.create("agents")));
		assertNotNull(agents);

		ACell info = engine.jobs().invokeInternal("v/ops/agent/info",
			Maps.of(Fields.AGENT_ID, "readable"),
			RequestContext.of(ALICE_DID)).get(5, java.util.concurrent.TimeUnit.SECONDS);
		assertEquals(Strings.create("readable"), RT.getIn(info, Fields.AGENT_ID));

		// Reads share the #180 job-free accessors: no new Jobs minted.
		assertEquals(jobsBefore, engine.jobs().getJobs(RequestContext.of(ALICE_DID)).count(),
			"agent list/info via the internal path must not create Jobs");
	}

	@Test
	public void testCreateNotesEmptyOwnSkillsSpace() {
		// Not a problem report: w/skills ships in every standard template
		// precisely so an agent has somewhere to author personal skills, and it
		// is empty until one does. The note is a prompt to use it.
		ACell input = Maps.of(
			Fields.AGENT_ID, "skills-nothing-agent",
			Fields.CONFIG, Maps.of(
				"operation", "v/ops/llmagent/chat",
				"llmOperation", "v/ops/langchain/openai",
				"model", "gpt-5.4-mini",
				"skillsets", Vectors.of(Strings.create("w/skills"))));
		ACell result = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell warnings = RT.getIn(result, Fields.WARNINGS);
		assertNotNull(warnings, "expected the empty-own-skills note: " + result);
		assertTrue(warnings.toString().contains("skillset empty: w/skills"), warnings.toString());
		// A fact, nothing more — what to do about it lives in the skills skill.
		assertFalse(warnings.toString().contains("load skill"), warnings.toString());
	}

	/**
	 * A venue source that does not resolve IS reported: these advisories are
	 * read by agents, and a v/ path is published at boot or by a module, so one
	 * resolving to nothing is a name that will most likely never resolve.
	 */
	@Test
	public void testCreateWarnsOnMissingVenueSkillsSource() {
		ACell input = Maps.of(
			Fields.AGENT_ID, "skills-venue-typo-agent",
			Fields.CONFIG, Maps.of(
				"operation", "v/ops/llmagent/chat",
				"llmOperation", "v/ops/langchain/openai",
				"model", "gpt-5.4-mini",
				"skillsets", Vectors.of(Strings.create("v/skills/definitely-not-real"))));
		ACell result = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell warnings = RT.getIn(result, Fields.WARNINGS);
		assertNotNull(warnings, "a missing venue skill source should be flagged: " + result);
		assertTrue(warnings.toString().contains(
			"skillset missing: v/skills/definitely-not-real"), warnings.toString());
		assertFalse(warnings.toString().contains("load skill"), warnings.toString());
	}

	@Test
	public void testCreateWarnsOnMalformedSkills() {
		// A malformed config.skills THROWS at transition time — flag it at
		// create, when it's fixable.
		ACell input = Maps.of(
			Fields.AGENT_ID, "skills-malformed-agent",
			Fields.CONFIG, Maps.of(
				"operation", "v/ops/llmagent/chat",
				"llmOperation", "v/ops/langchain/openai",
				"model", "gpt-5.4-mini",
				"skillsets", "w/skills"));
		ACell result = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID)).awaitResult(5000);

		AVector<ACell> warnings = RT.ensureVector(RT.getIn(result, Fields.WARNINGS));
		assertNotNull(warnings, "malformed config.skills should carry a warning");
		String w = RT.ensureString(warnings.get(0)).toString();
		assertTrue(w.contains("config.skills"), w);
		assertTrue(warnings.toString().contains("skills config invalid"), warnings.toString());
	}

	@Test
	public void testCreateResolvableSkillsNoWarning() {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of("path", "w/skills/demo",
				"value", Maps.of("description", "A demo skill")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell input = Maps.of(
			Fields.AGENT_ID, "skills-ok-agent",
			Fields.CONFIG, Maps.of(
				"operation", "v/ops/llmagent/chat",
				"llmOperation", "v/ops/langchain/openai",
				"model", "gpt-5.4-mini",
				"skillsets", Vectors.of(Strings.create("w/skills"))));
		ACell result = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID)).awaitResult(5000);

		assertNull(RT.getIn(result, Fields.WARNINGS), "resolvable skills source → no advisory");
	}

	@Test
	public void testCreateWarnsOnUnresolvableTool() {
		// OpenAI provider (no capability probe) so the only advisory in play is
		// tool resolution. One tool resolves, one doesn't → warn about the latter.
		ACell input = Maps.of(
			Fields.AGENT_ID, "bad-tools",
			Fields.CONFIG, Maps.of(
				"operation", "v/ops/llmagent/chat",
				"llmOperation", "v/ops/langchain/openai",
				"tools", Vectors.of(
					Strings.create("v/ops/covia/list"),   // resolves (distinct from the message's example paths)
					Strings.create("v/ops/nope"))));       // does not resolve
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		AVector<ACell> warnings = RT.ensureVector(RT.getIn(result, Fields.WARNINGS));
		assertNotNull(warnings, "unresolvable tool should carry a warning");
		assertEquals(1, warnings.count());
		String w = RT.ensureString(warnings.get(0)).toString();
		assertTrue(w.contains("v/ops/nope"), "warning names the unresolved op");
		assertFalse(w.contains("v/ops/covia/list"), "warning omits the op that resolves");
	}

	@Test
	public void testConfiguredToolWithoutMetadataReadIsVisibleEverywhere() {
		String toolPath = "w/ops/risk/issue-limit";
		AMap<AString, ACell> operation = Maps.of(
			Fields.NAME, Strings.create("Issue risk limit"),
			Fields.OPERATION, Maps.of(
				Fields.ADAPTER, Strings.create("test:echo"),
				Fields.INPUT, Maps.of("type", "object")));
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, toolPath, Fields.VALUE, operation),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		AMap<AString, ACell> config = Maps.of(
			Fields.OPERATION, Strings.create("v/ops/llmagent/chat"),
			"llmOperation", Strings.create("v/ops/langchain/openai"),
			Fields.TOOLS, Vectors.of(Strings.create(toolPath)),
			"caps", Vectors.of(Capability.create(
				Strings.create(toolPath), Strings.create("invoke"))));

		ACell created = engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "metadata-blind", Fields.CONFIG, config),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		AVector<ACell> warnings = RT.ensureVector(RT.getIn(created, Fields.WARNINGS));
		assertNotNull(warnings, "create must warn when configured caps cannot read tool metadata");
		assertTrue(warnings.toString().contains(toolPath), warnings.toString());
		assertTrue(warnings.toString().contains("crud/read"), warnings.toString());

		ACell info = engine.jobs().invokeOperation("v/ops/agent/info",
			Maps.of(Fields.AGENT_ID, "metadata-blind"), RequestContext.of(ALICE_DID))
			.awaitResult(5000);
		AVector<ACell> unavailable = RT.ensureVector(
			RT.getIn(info, Fields.UNAVAILABLE_TOOLS));
		assertNotNull(unavailable, "agent:info must expose configured tools omitted at runtime");
		assertEquals(1, unavailable.count());
		assertEquals(Strings.create(toolPath),
			RT.getIn(unavailable.get(0), Fields.OPERATION));
		assertTrue(RT.getIn(unavailable.get(0), Fields.REASON).toString().contains("crud/read"));

		ACell context = engine.jobs().invokeOperation("v/ops/agent/context",
			Maps.of(Fields.AGENT_ID, "metadata-blind"), RequestContext.of(ALICE_DID))
			.awaitResult(5000);
		String rendered = context.toString();
		assertTrue(rendered.contains("Configured tools unavailable"), rendered);
		assertTrue(rendered.contains(toolPath), rendered);
		assertTrue(rendered.contains("Do not claim"), rendered);
	}

	@Test
	public void testConfiguredToolWithMetadataReadIsOffered() {
		String toolPath = "w/ops/risk/readable-limit";
		AMap<AString, ACell> operation = Maps.of(
			Fields.NAME, Strings.create("Readable risk limit"),
			Fields.OPERATION, Maps.of(
				Fields.ADAPTER, Strings.create("test:echo"),
				Fields.INPUT, Maps.of("type", "object")));
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, toolPath, Fields.VALUE, operation),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		AMap<AString, ACell> config = Maps.of(
			Fields.OPERATION, Strings.create("v/ops/llmagent/chat"),
			"llmOperation", Strings.create("v/ops/langchain/openai"),
			Fields.TOOLS, Vectors.of(Strings.create(toolPath)),
			"caps", Vectors.of(
				Capability.create(Strings.create(toolPath), Strings.create("invoke")),
				Capability.create(Strings.create(toolPath), Strings.create("crud/read"))));

		ACell created = engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "metadata-reader", Fields.CONFIG, config),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		assertNull(RT.getIn(created, Fields.WARNINGS));

		ACell info = engine.jobs().invokeOperation("v/ops/agent/info",
			Maps.of(Fields.AGENT_ID, "metadata-reader"), RequestContext.of(ALICE_DID))
			.awaitResult(5000);
		assertNull(RT.getIn(info, Fields.UNAVAILABLE_TOOLS));

		String context = engine.jobs().invokeOperation("v/ops/agent/context",
			Maps.of(Fields.AGENT_ID, "metadata-reader"), RequestContext.of(ALICE_DID))
			.awaitResult(5000).toString();
		assertTrue(context.contains("Operation: " + toolPath), context);
		assertFalse(context.contains("Configured tools unavailable"), context);
	}

	/** agent:context simulates one call: the inbox, a task, or nothing at all. */
	@Test
	public void testContextSimulatesASpecificCall() {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "sim-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/llm",
					"systemPrompt", "You simulate.")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// An inbox message renders as the current input, right before the tail.
		AMap<AString, ACell> withMessage = RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/context",
			Maps.of(Fields.AGENT_ID, "sim-agent", Fields.MESSAGE, "What is 2+2?"),
			RequestContext.of(ALICE_DID)).awaitResult(5000));
		AVector<ACell> messages = RT.ensureVector(withMessage.get(Fields.MESSAGES));
		assertEquals("user", RT.getIn(messages.get(messages.count() - 2), "role").toString());
		assertEquals("What is 2+2?", RT.getIn(messages.get(messages.count() - 2), "content").toString());
		assertTrue(RT.getIn(messages.get(0), "content").toString().startsWith("You simulate."));
		assertNotNull(RT.getIn(withMessage, "budget", "used"));
		assertEquals(CVMLong.create(1), RT.getIn(withMessage, "marks", "head"));
		assertEquals(Strings.create("bracket"), withMessage.get(Strings.intern("labels")));
		assertNotNull(withMessage.get(Strings.intern("cacheMarks")), "the input is a cache breakpoint");

		// A task renders exactly as the tool loop renders it, task tools offered.
		AMap<AString, ACell> withTask = RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/context",
			Maps.of(Fields.AGENT_ID, "sim-agent", "task", "Summarise w/report"),
			RequestContext.of(ALICE_DID)).awaitResult(5000));
		AVector<ACell> taskMessages = RT.ensureVector(withTask.get(Fields.MESSAGES));
		String last = RT.getIn(taskMessages.get(taskMessages.count() - 1), "content").toString();
		assertTrue(last.startsWith("[Tasks assigned to you]") && last.contains("Summarise w/report"), last);
		java.util.Set<String> toolNames = new java.util.HashSet<>();
		AVector<ACell> tools = RT.ensureVector(withTask.get(Fields.TOOLS));
		for (long i = 0; i < tools.count(); i++) toolNames.add(RT.getIn(tools.get(i), "name").toString());
		assertTrue(toolNames.contains("complete_task") && toolNames.contains("fail_task"), toolNames.toString());

		// Nothing to act on: the empty-state signal a wake-up would see.
		AMap<AString, ACell> wakeUp = RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/context",
			Maps.of(Fields.AGENT_ID, "sim-agent"), RequestContext.of(ALICE_DID)).awaitResult(5000));
		assertTrue(wakeUp.toString().contains("[No input]"), wakeUp.toString());
	}

	/** Harness tools are opt-in on every runtime (HarnessTools.offered): an agent
	 *  with nothing declared has no tools at all; declared skills imply
	 *  skill_load and context_unload; anything else is listed by name. */
	@Test
	public void testHarnessToolsAreOptInOnLlmagent() {
		java.util.function.Function<AMap<AString, ACell>, java.util.Set<String>> toolsOf = config -> {
			String id = "optin-" + config.hashCode();
			engine.jobs().invokeOperation("v/ops/agent/create",
				Maps.of(Fields.AGENT_ID, id, Fields.CONFIG, config.assoc(Fields.OPERATION, Strings.create("v/ops/llmagent/chat"))
					.assoc(Strings.create("llmOperation"), Strings.create("v/test/ops/llm"))),
				RequestContext.of(ALICE_DID)).awaitResult(5000);
			AMap<AString, ACell> context = RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/context",
				Maps.of(Fields.AGENT_ID, id), RequestContext.of(ALICE_DID)).awaitResult(5000));
			java.util.Set<String> names = new java.util.HashSet<>();
			AVector<ACell> tools = RT.ensureVector(context.get(Fields.TOOLS));
			for (long i = 0; tools != null && i < tools.count(); i++) names.add(RT.getIn(tools.get(i), "name").toString());
			return names;
		};
		assertTrue(toolsOf.apply(Maps.of("systemPrompt", "bare")).isEmpty(), "nothing declared: no tools");
		java.util.Set<String> skilled = toolsOf.apply(Maps.of("skillsets", Vectors.of(Strings.create("w/skills"))));
		assertTrue(skilled.contains("skill_load") && skilled.contains("context_unload"), skilled.toString());
		assertFalse(skilled.contains("context_load"), "not implied: " + skilled);
		java.util.Set<String> listed = toolsOf.apply(Maps.of(Fields.TOOLS,
			Vectors.of(Strings.create("context_load"), Strings.create("more_tools"), Strings.create("subgoal"))));
		assertTrue(listed.contains("context_load") && listed.contains("more_tools"), listed.toString());
		assertFalse(listed.contains("subgoal"), "a goal-tree frame tool is not this runtime's: " + listed);
	}

	/** toolCalling: false — declared by the provider, the model, or the agent's own
	 *  config.modelProfile — is honoured by the assembler: no tool is presented,
	 *  no capability notice, no skills index; agent:create says so. */
	@Test
	public void testToolCallingOffPresentsNoTools() {
		AMap<AString, ACell> config = Maps.of(
			Fields.OPERATION, "v/ops/llmagent/chat",
			"llmOperation", "v/test/ops/llm",
			"caps", Vectors.of(Maps.of("with", "v/ops/covia", "can", "invoke")),
			"skillsets", Vectors.of(Strings.create("w/skills")),
			Fields.TOOLS, Vectors.of(Strings.create("v/ops/covia/read"), Strings.create("more_tools")),
			"modelProfile", Maps.of("options", Maps.of("toolCalling", CVMBool.FALSE)));
		ACell created = engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "no-tool-calling", Fields.CONFIG, config),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		assertTrue(RT.getIn(created, Fields.WARNINGS).toString().contains("tool calling is off"),
			"create advises: " + RT.getIn(created, Fields.WARNINGS));

		AMap<AString, ACell> context = RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/context",
			Maps.of(Fields.AGENT_ID, "no-tool-calling", Fields.MESSAGE, "hi"),
			RequestContext.of(ALICE_DID)).awaitResult(5000));
		AVector<ACell> presented = RT.ensureVector(context.get(Fields.TOOLS));
		assertTrue(presented == null || presented.isEmpty(), "no tool presented: " + presented);
		assertEquals(CVMBool.FALSE, context.get(Strings.intern("toolCalling")));
		String rendered = context.get(Fields.MESSAGES).toString();
		assertFalse(rendered.contains("[Skills]"), "no index the model could not load from");
		assertFalse(rendered.contains("Capabilities"), "no capability notice without tools");

		// The same configuration without the override: tools, index and notice all return.
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "tool-calling-on", Fields.CONFIG, config.dissoc(Strings.intern("modelProfile"))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		AMap<AString, ACell> on = RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/context",
			Maps.of(Fields.AGENT_ID, "tool-calling-on", Fields.MESSAGE, "hi"),
			RequestContext.of(ALICE_DID)).awaitResult(5000));
		assertFalse(RT.ensureVector(on.get(Fields.TOOLS)).isEmpty());
		assertNull(on.get(Strings.intern("toolCalling")), "absent means the norm");
	}

	/** more_tools on llmagent: operations added mid-run are offered from the next
	 *  inference and dispatch through the added routes, exactly as on goaltree. */
	@Test
	public void testMoreToolsOnLlmagent() {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "more-tools-llm",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/moretoolsllm",
					Fields.TOOLS, Vectors.of(Strings.create("more_tools")))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		ACell chat = engine.jobs().invokeOperation("v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "more-tools-llm", Fields.MESSAGE, "extend yourself"),
			RequestContext.of(ALICE_DID)).awaitResult(15000);
		String response = RT.getIn(chat, Fields.RESPONSE).toString();
		assertTrue(response.startsWith("MORE_TOOLS_RESULT:"), response);

		// The record shows the palette changing between inferences.
		AgentState agent = engine.getVenueState().users().get(ALICE_DID).agent("more-tools-llm");
		TestEngine.awaitTimelineCount(agent, 1, 10000);
		AVector<ACell> inferences = RT.ensureVector(RT.getIn(agent.getTimeline().get(0), Fields.INFERENCES));
		assertEquals(3, inferences.count(), "more_tools, then the added tool, then the answer: " + inferences);
		assertTrue(RT.getIn(inferences.get(1), Fields.TOOLS).toString().contains("test_echo"),
			"the second inference is offered the added tool");
	}

	/** agent:step runs one harness iteration on a supplied reply: tools dispatched as live, the agent untouched. */
	@Test
	public void testStepRunsOneHarnessIteration() {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "step-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/llm",
					"systemPrompt", "You step.",
					Fields.TOOLS, Vectors.of(Strings.create("v/test/ops/echo")))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// A tool call: dispatched through the agent's routes, its result
		// rendered, the next prompt carrying this iteration in the tool-loop band.
		AMap<AString, ACell> stepped = RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/step",
			Maps.of(Fields.AGENT_ID, "step-agent", Fields.MESSAGE, "Echo this",
				"assistant", Maps.of("content", "Echoing.", "toolCalls", Vectors.of(
					Maps.of("name", "test_echo", "arguments", Maps.of("x", 1L))))),
			RequestContext.of(ALICE_DID)).awaitResult(5000));
		assertEquals(CVMBool.FALSE, stepped.get(Strings.intern("done")));
		AVector<ACell> calls = RT.ensureVector(stepped.get(Strings.intern("calls")));
		assertEquals(1, calls.count());
		assertEquals("test_echo", RT.getIn(calls.get(0), "name").toString());
		assertEquals(CVMLong.create(1), RT.getIn(calls.get(0), "result", "x"));
		assertEquals(CVMLong.create(1), RT.getIn(calls.get(0), "arguments", "x"));
		assertNotNull(RT.getIn(calls.get(0), "ms"));
		assertNull(RT.getIn(calls.get(0), "isError"));
		AVector<ACell> turns = RT.ensureVector(stepped.get(Fields.TURNS));
		assertEquals(2, turns.count());
		assertEquals("tool", RT.getIn(turns.get(1), "role").toString());
		AMap<AString, ACell> next = RT.ensureMap(stepped.get(Strings.intern("next")));
		AVector<ACell> messages = RT.ensureVector(next.get(Fields.MESSAGES));
		long conv = RT.ensureLong(RT.getIn(next, "marks", "conversation")).longValue();
		assertEquals("assistant", RT.getIn(messages.get(conv), "role").toString());
		assertEquals("tool", RT.getIn(messages.get(conv + 1), "role").toString());
		assertEquals(CVMLong.create(conv + 2), RT.getIn(next, "marks", "toolLoop"));

		// A text reply ends the cycle: the response, no next prompt.
		AMap<AString, ACell> text = RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/step",
			Maps.of(Fields.AGENT_ID, "step-agent", Fields.MESSAGE, "hi", "assistant", "Four."),
			RequestContext.of(ALICE_DID)).awaitResult(5000));
		assertEquals(CVMBool.TRUE, text.get(Strings.intern("done")));
		assertEquals(Strings.create("Four."), text.get(Fields.RESPONSE));
		assertNull(text.get(Strings.intern("next")));

		// complete_task on a task: validated and reported as the terminal
		// outcome, the task gone from the next prompt — no task job touched.
		AMap<AString, ACell> completed = RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/step",
			Maps.of(Fields.AGENT_ID, "step-agent", "task", "Add 2 and 2",
				"assistant", Maps.of("toolCalls", Vectors.of(
					Maps.of("name", "complete_task", "arguments", Maps.of("result", "4"))))),
			RequestContext.of(ALICE_DID)).awaitResult(5000));
		assertEquals("complete", RT.getIn(completed, "terminal", "status").toString());
		assertEquals(Strings.create("4"), RT.getIn(completed, "terminal", "value"));
		assertFalse(RT.getIn(completed, "next").toString().contains("[Tasks assigned to you]"));

		// Text spelling a control tool is honoured exactly as the loop honours it (#215).
		AMap<AString, ACell> textual = RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/step",
			Maps.of(Fields.AGENT_ID, "step-agent", "task", "Add 2 and 2",
				"assistant", "complete_task {\"result\": \"4\"}"),
			RequestContext.of(ALICE_DID)).awaitResult(5000));
		assertEquals("complete", RT.getIn(textual, "terminal", "status").toString());

		// An unknown tool fails as live: an Error result, recorded as a tool failure.
		AMap<AString, ACell> unknown = RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/step",
			Maps.of(Fields.AGENT_ID, "step-agent", Fields.MESSAGE, "hi",
				"assistant", Maps.of("toolCalls", Vectors.of(Maps.of("name", "no_such_tool")))),
			RequestContext.of(ALICE_DID)).awaitResult(5000));
		assertEquals(CVMBool.TRUE,
			RT.getIn(RT.ensureVector(unknown.get(Strings.intern("calls"))).get(0), "isError"));

		// The agent itself is untouched: no cycle ran.
		AMap<AString, ACell> info = RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/info",
			Maps.of(Fields.AGENT_ID, "step-agent"), RequestContext.of(ALICE_DID)).awaitResult(5000));
		ACell timeline = RT.getIn(info, "timelineLength");
		assertTrue(timeline == null || CVMLong.create(0).equals(timeline), "no cycle ran: " + timeline);
	}

	/** The timeline entry records every inference of a cycle (#392): the
	 *  standing context once, each call's reply and tool batch, nothing twice. */
	@Test
	public void testTimelineEntryRecordsEveryInference() {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "record-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/toolllm",
					"systemPrompt", "You record.")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		ACell chat = engine.jobs().invokeOperation("v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "record-agent", Fields.MESSAGE, "use your tool"),
			RequestContext.of(ALICE_DID)).awaitResult(10000);
		AgentState agent = engine.getVenueState().users().get(ALICE_DID).agent("record-agent");
		TestEngine.awaitTimelineCount(agent, 1, 10000);
		AMap<AString, ACell> entry = RT.ensureMap(agent.getTimeline().get(0));

		// The cycle's standing context: the head, once, on the entry — not per inference.
		AVector<ACell> context = RT.ensureVector(entry.get(Fields.CONTEXT));
		assertEquals("system", RT.getIn(context.get(0), "role").toString());
		assertTrue(RT.getIn(context.get(0), "content").toString().startsWith("You record."));
		assertNotNull(entry.get(Fields.TOOLS), "tools offered to the first inference");
		assertEquals(RT.getIn(chat, Fields.SESSION_ID), entry.get(Fields.SESSION_ID));

		// toolllm: one inference that calls echo, one that answers.
		AVector<ACell> inferences = RT.ensureVector(entry.get(Fields.INFERENCES));
		assertEquals(2, inferences.count(), "one record per model call: " + inferences);
		AMap<AString, ACell> first = RT.ensureMap(inferences.get(0));
		assertEquals(Strings.create("v/test/ops/toolllm"), first.get(Fields.OP));
		assertNotNull(first.get(Fields.MS));
		assertNotNull(first.get(Fields.SENT), "the first inference sends the tail");
		assertNotNull(RT.getIn(first, Fields.REPLY, "toolCalls"), "the reply verbatim");
		AVector<ACell> calls = RT.ensureVector(first.get(Fields.CALLS));
		assertEquals(1, calls.count());
		assertEquals("v/test/ops/echo", RT.getIn(calls.get(0), "name").toString());
		assertNotNull(RT.getIn(calls.get(0), Fields.MS));
		assertNotNull(RT.getIn(calls.get(0), Fields.RESULT, "echo"), "the result verbatim");
		AMap<AString, ACell> second = RT.ensureMap(inferences.get(1));
		assertNull(second.get(Fields.SENT), "nothing new was sent: the reply and result are recorded above");
		assertNull(second.get(Fields.TOOLS), "same tools as before");
		assertTrue(RT.getIn(second, Fields.REPLY, "content").toString().startsWith("Tool returned:"));

		// Derivable data is not stored: no cycle tokens, no toolFailures on the entry.
		assertNull(entry.get(Fields.TOKENS));
		assertNull(entry.get(Strings.intern("toolFailures")));

		// The final assistant turn carries its own inference's usage, not the cycle total.
		AMap<AString, ACell> session = agent.getSession(
			Blob.fromHex(RT.getIn(chat, Fields.SESSION_ID).toString()));
		AVector<ACell> frames = RT.ensureVector(RT.getIn(session, Fields.FRAMES));
		AVector<ACell> conversation = RT.ensureVector(RT.getIn(frames.get(0), "conversation"));
		ACell last = conversation.get(conversation.count() - 1);
		assertEquals("assistant", RT.getIn(last, "role").toString());
		assertEquals(RT.getIn(second, Fields.REPLY, Fields.TOKENS), RT.getIn(last, Fields.TOKENS));
	}

	/** A cycle whose transition throws still writes its entry, with the error
	 *  and the inferences that ran before it (#392). */
	@Test
	public void testFailedCycleKeepsItsInferences() {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "failing-record-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/error")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		Job chat = engine.jobs().invokeOperation("v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "failing-record-agent", Fields.MESSAGE, "hello"),
			RequestContext.of(ALICE_DID));
		assertThrows(Exception.class, () -> chat.awaitResult(10000));
		AgentState agent = engine.getVenueState().users().get(ALICE_DID).agent("failing-record-agent");
		TestEngine.awaitTimelineCount(agent, 1, 10000);
		AMap<AString, ACell> entry = RT.ensureMap(agent.getTimeline().get(0));
		assertTrue(entry.get(Fields.RESULT).toString().contains("Transition failed"),
			entry.get(Fields.RESULT).toString());
		AVector<ACell> inferences = RT.ensureVector(entry.get(Fields.INFERENCES));
		assertEquals(1, inferences.count(), "the call that failed is recorded: " + entry);
		assertNotNull(RT.getIn(inferences.get(0), Fields.ERROR));
		assertNull(RT.getIn(inferences.get(0), Fields.REPLY));
		assertFalse(RT.ensureVector(entry.get(Fields.CONTEXT)).isEmpty(), "the context it was sent");
	}

	@Test
	public void testContextInspectionUsesAgentPrivateNamespace() {
		AString agentId = Strings.create("private-context-inspection");
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/llm",
					Fields.LOADS, Maps.of("n/inspection-note", Maps.of("budget", 500L)))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		RequestContext agentCtx = RequestContext.of(ALICE_DID).withAgentId(agentId);
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "n/inspection-note", Fields.VALUE, "PRIVATE_LOAD_VISIBLE"),
			agentCtx).awaitResult(5000);

		String rendered = engine.jobs().invokeOperation("v/ops/agent/context",
			Maps.of(Fields.AGENT_ID, agentId), RequestContext.of(ALICE_DID))
			.awaitResult(5000).toString();
		assertTrue(rendered.contains("PRIVATE_LOAD_VISIBLE"), rendered);
	}

	@Test
	public void testCreateHarnessToolsNotFlagged() {
		// Bare harness names (subgoal, complete, …) aren't operation paths — they
		// must not be reported as unresolvable.
		ACell input = Maps.of(
			Fields.AGENT_ID, "harness-tools",
			Fields.CONFIG, Maps.of(
				"operation", "v/ops/goaltree/chat",
				"llmOperation", "v/ops/langchain/openai",
				"tools", Vectors.of(
					Strings.create("subgoal"),
					Strings.create("complete"),
					Strings.create("v/ops/covia/read"))));
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNull(RT.getIn(result, Fields.WARNINGS), "harness tools + resolvable op → no advisory");
	}

	@Test
	public void testCreateAgentWithConfigFromWorkspacePath() {
		// Store a template map in the caller's workspace
		AMap<AString, ACell> template = Maps.of(
			Strings.create("systemPrompt"), Strings.create("You read data."),
			Strings.create("model"), Strings.create("gpt-4"));

		engine.jobs().invokeOperation(
			"v/ops/covia/write",
			Maps.of(
				Fields.PATH, Strings.create("w/templates/reader"),
				Fields.VALUE, template),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Create an agent from a workspace-path reference
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "reader-from-template",
				Fields.CONFIG, Strings.create("w/templates/reader")),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(Strings.create("reader-from-template"), RT.getIn(result, Fields.AGENT_ID));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("reader-from-template");
		assertNotNull(agent);
		AMap<AString, ACell> storedConfig = agent.getConfig();
		assertNotNull(storedConfig);
		assertEquals(Strings.create("You read data."),
			storedConfig.get(Strings.create("systemPrompt")));
		assertEquals(Strings.create("gpt-4"),
			storedConfig.get(Strings.create("model")));
	}

	@Test
	public void testCreateAgentFromStandardTemplateReader() {
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "reader-bot",
				Fields.CONFIG, Strings.create("v/agents/templates/reader")),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(Strings.create("reader-bot"), RT.getIn(result, Fields.AGENT_ID));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("reader-bot");
		assertNotNull(agent);
		AMap<AString, ACell> config = agent.getConfig();
		assertNotNull(config);
		// Template supplies a systemPrompt and a tools vector
		assertNotNull(config.get(Strings.create("systemPrompt")));
		assertNotNull(config.get(Strings.create("tools")));
		// Reader template has defaultTools=false
		assertEquals(CVMBool.FALSE, config.get(Strings.create("defaultTools")));
	}

	@Test
	public void testCreateAgentFromStandardTemplateWorker() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "data-worker",
				Fields.CONFIG, Strings.create("v/agents/templates/worker")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("data-worker");
		assertNotNull(agent);
		AMap<AString, ACell> config = agent.getConfig();
		// Worker template includes covia:write in tools
		AVector<ACell> tools = RT.ensureVector(config.get(Strings.create("tools")));
		assertNotNull(tools);
		boolean hasWrite = false;
		for (long i = 0; i < tools.count(); i++) {
			if (Strings.create("v/ops/covia/write").equals(tools.get(i))) {
				hasWrite = true;
				break;
			}
		}
		assertTrue(hasWrite, "worker template should include covia:write");
	}

	@Test
	public void testCreateAgentFromOrderedConfigLayers() {
		RequestContext alice = RequestContext.of(ALICE_DID);

		// Flat maps remain valid layers (useful for small workspace selectors).
		engine.jobs().invokeOperation("v/ops/covia/write", Maps.of(
			Fields.PATH, "w/agent-config/providers/anthropic",
			Fields.VALUE, Maps.of(
				"llmOperation", "v/ops/langchain/anthropic",
				"providerOptions", Maps.of(
					"thinking", Maps.of("enabled", CVMBool.TRUE, "budget", 1000L)))),
			alice).awaitResult(5000);

		// Canonical functional asset shape: metadata + agent.config facet.
		engine.jobs().invokeOperation("v/ops/covia/write", Maps.of(
			Fields.PATH, "w/agent-config/prompts/invoice-review",
			Fields.VALUE, Maps.of(
				"name", "Invoice Review Prompt",
				"description", "Reusable invoice review behaviour",
				"agent", Maps.of("config", Maps.of(
					"systemPrompt", "You review invoices using supplied evidence.")))),
			alice).awaitResult(5000);

		AVector<ACell> layers = Vectors.of(
			Strings.create("v/agents/templates/worker"),
			Strings.create("w/agent-config/providers/anthropic"),
			Strings.create("w/agent-config/prompts/invoice-review"),
			Maps.of(
				"model", "claude-test-model",
				"providerOptions", Maps.of(
					"thinking", Maps.of("budget", 2000L))));

		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(
			Fields.AGENT_ID, "layered-agent",
			Fields.CONFIG, layers), alice).awaitResult(5000);

		AMap<AString, ACell> config = engine.getVenueState().users().get(ALICE_DID)
			.agent("layered-agent").getConfig();
		assertEquals(Strings.create("v/ops/langchain/anthropic"),
			config.get(Strings.create("llmOperation")));
		assertEquals(Strings.create("claude-test-model"), config.get(Strings.create("model")));
		assertEquals(Strings.create("You review invoices using supplied evidence."),
			config.get(Strings.create("systemPrompt")));
		assertEquals(CVMBool.TRUE,
			RT.getIn(config, Strings.create("providerOptions"), Strings.create("thinking"), Strings.create("enabled")));
		assertEquals(CVMLong.create(2000),
			RT.getIn(config, Strings.create("providerOptions"), Strings.create("thinking"), Strings.create("budget")));

		// Later layers did not mention tools, so the worker selector survives.
		AVector<ACell> tools = RT.ensureVector(config.get(Strings.create("tools")));
		assertTrue(tools.contains(Strings.create("v/ops/covia/write")));
	}

	@Test
	public void testLaterConfigVectorReplacesEarlierVector() {
		AVector<ACell> layers = Vectors.of(
			Strings.create("v/agents/templates/reader"),
			Maps.of("caps", Vectors.empty(), "tools", Vectors.of(
				Strings.create("v/ops/covia/list"))));

		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(
			Fields.AGENT_ID, "layer-vector-override",
			Fields.CONFIG, layers), RequestContext.of(ALICE_DID)).awaitResult(5000);

		AMap<AString, ACell> config = engine.getVenueState().users().get(ALICE_DID)
			.agent("layer-vector-override").getConfig();
		assertTrue(RT.ensureVector(config.get(Strings.create("caps"))).isEmpty());
		assertEquals(Vectors.of(Strings.create("v/ops/covia/list")),
			RT.ensureVector(config.get(Strings.create("tools"))));
	}

	@Test
	public void testCreateAgentWithConfigRefFailsIfMissing() {
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "ghost-template",
				Fields.CONFIG, Strings.create("w/templates/does-not-exist")),
			RequestContext.of(ALICE_DID));

		try {
			job.awaitResult(5000);
			fail("Should fail when config reference cannot be resolved");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
			assertTrue(job.getErrorMessage().contains("config references 'w/templates/does-not-exist'"),
				job.getErrorMessage());
			assertTrue(job.getErrorMessage().contains("String config layers are references"),
				job.getErrorMessage());
		}
	}

	@Test
	public void testConfigLayerShapeErrorIdentifiesArrayIndex() {
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "bad-layer-shape",
				Fields.CONFIG, Vectors.of(Maps.of("model", "test"), CVMLong.ONE)),
			RequestContext.of(ALICE_DID));

		try {
			job.awaitResult(5000);
			fail("Should reject a non-map, non-reference config layer");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
			assertTrue(job.getErrorMessage().contains("config[1]"), job.getErrorMessage());
			assertTrue(job.getErrorMessage().contains("config map or a string reference"),
				job.getErrorMessage());
		}
	}

	@Test
	public void testMalformedAgentFacetHasActionableError() {
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "bad-agent-facet",
				Fields.CONFIG, Maps.of(
					"name", "Broken agent asset",
					"agent", "this should be a map")),
			RequestContext.of(ALICE_DID));

		try {
			job.awaitResult(5000);
			fail("Should reject a malformed canonical agent facet");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
			assertTrue(job.getErrorMessage().contains("config.agent must be a map"),
				job.getErrorMessage());
			assertTrue(job.getErrorMessage().contains("config"), job.getErrorMessage());
		}
	}

	@Test
	public void testComposedConfigRejectsMalformedToolSelector() {
		AVector<ACell> layers = Vectors.of(
			Strings.create("v/agents/templates/reader"),
			Maps.of("tools", Vectors.of(
				Maps.of("name", "missing-operation"))));
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "bad-tool-selector", Fields.CONFIG, layers),
			RequestContext.of(ALICE_DID));

		try {
			job.awaitResult(5000);
			fail("Should reject a tool selector map without operation");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
			assertTrue(job.getErrorMessage().contains("config.tools[0].operation is required"),
				job.getErrorMessage());
			assertTrue(job.getErrorMessage().contains("string operation path"),
				job.getErrorMessage());
		}
	}

	@Test
	public void testComposedConfigRejectsMalformedProviderSelector() {
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "bad-provider-selector",
				Fields.CONFIG, Maps.of("llmOperation", Maps.of("provider", "anthropic"))),
			RequestContext.of(ALICE_DID));

		try {
			job.awaitResult(5000);
			fail("Should reject a non-string LLM operation selector");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
			assertTrue(job.getErrorMessage().contains(
				"config.llmOperation must be a string LLM operation path"),
				job.getErrorMessage());
		}
	}

	@Test
	public void testJsonEncodedLayersKeepResolutionErrorInsteadOfReportingInvalidJson() {
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "json-layer-missing-ref",
				Fields.CONFIG, Strings.create(
					"[{\"model\":\"test\"},\"w/templates/json-missing\"]")),
			RequestContext.of(ALICE_DID));

		try {
			job.awaitResult(5000);
			fail("Should fail on the unresolved reference in parsed JSON layers");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
			assertTrue(job.getErrorMessage().contains("config[1] references 'w/templates/json-missing'"),
				job.getErrorMessage());
			assertFalse(job.getErrorMessage().contains("could not be parsed"), job.getErrorMessage());
		}
	}

	@Test
	public void testCyclicConfigReferencesReportReferenceChain() {
		RequestContext alice = RequestContext.of(ALICE_DID);
		engine.jobs().invokeOperation("v/ops/covia/write", Maps.of(
			Fields.PATH, "w/templates/cycle-a",
			Fields.VALUE, Maps.of("agent", Maps.of("config", Vectors.of(
				Strings.create("w/templates/cycle-b"))))), alice).awaitResult(5000);
		engine.jobs().invokeOperation("v/ops/covia/write", Maps.of(
			Fields.PATH, "w/templates/cycle-b",
			Fields.VALUE, Maps.of("agent", Maps.of("config", Vectors.of(
				Strings.create("w/templates/cycle-a"))))), alice).awaitResult(5000);

		Job job = engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(
			Fields.AGENT_ID, "cyclic-config",
			Fields.CONFIG, Strings.create("w/templates/cycle-a")), alice);
		try {
			job.awaitResult(5000);
			fail("Should reject cyclic config references");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
			assertTrue(job.getErrorMessage().contains(
				"w/templates/cycle-a -> w/templates/cycle-b -> w/templates/cycle-a"),
				job.getErrorMessage());
		}
	}

	@Test
	public void testCreateAgentExtractsEmbeddedState() {
		// Template with embedded state field — should be extracted as initial state
		AMap<AString, ACell> template = Maps.of(
			Strings.create("systemPrompt"), Strings.create("You have memory."),
			AgentState.KEY_STATE, Maps.of(Strings.create("memory"), Strings.create("pre-loaded")));

		engine.jobs().invokeOperation(
			"v/ops/covia/write",
			Maps.of(
				Fields.PATH, Strings.create("w/templates/stateful"),
				Fields.VALUE, template),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "stateful-agent",
				Fields.CONFIG, Strings.create("w/templates/stateful")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("stateful-agent");
		assertNotNull(agent);

		// systemPrompt lives in config, embedded state is not in config
		AMap<AString, ACell> storedConfig = agent.getConfig();
		assertEquals(Strings.create("You have memory."),
			storedConfig.get(Strings.create("systemPrompt")));
		assertNull(storedConfig.get(AgentState.KEY_STATE),
			"state field should be extracted out of config");

		// Embedded state is used as initial state
		ACell state = agent.getState();
		assertNotNull(state);
		assertEquals(Strings.create("pre-loaded"), RT.getIn(state, Strings.create("memory")));
	}

	@Test
	public void testCreateMissingAgentId() {
		ACell input = Maps.of("foo", "bar");
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID));

		try {
			job.awaitResult(5000);
			fail("Should have thrown due to missing agentId");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	@Test
	public void testCreateRejectsExistingAgent() {
		ACell input = Maps.of(Fields.AGENT_ID, "exclusive-agent");

		Job job1 = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID));
		job1.awaitResult(5000);

		Job job2 = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID));
		try {
			job2.awaitResult(5000);
			fail("agent:create must fail when the name is already occupied");
		} catch (covia.exception.JobFailedException expected) {
			assertTrue(job2.getErrorMessage().contains("already exists"));
		}
	}

	@Test
	public void testCreateRejectsRemovedOverwriteParameter() {
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "legacy-overwrite",
				Fields.OVERWRITE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("removed overwrite parameter must fail loudly");
		} catch (covia.exception.JobFailedException expected) {
			assertTrue(job.getErrorMessage().contains("no longer supports overwrite"));
		}
		assertNull(engine.getVenueState().users().get(ALICE_DID).agent("legacy-overwrite"));
	}

	// ========== agent:fork ==========

	@Test
	public void testForkAgentBasic() {
		// Create source agent with config and some state
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "source-agent",
				Fields.CONFIG, Maps.of(
					Strings.create("systemPrompt"), Strings.create("You are source."),
					Strings.create("model"), Strings.create("gpt-4")),
				AgentState.KEY_STATE, Maps.of(Strings.create("memory"), Strings.create("original"))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Fork it
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/fork",
			Maps.of(
				Strings.create("sourceId"), Strings.create("source-agent"),
				Fields.AGENT_ID, "fork-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(Strings.create("fork-agent"), RT.getIn(result, Fields.AGENT_ID));
		assertNull(RT.getIn(result, Fields.CREATED));
		assertEquals(Strings.create("source-agent"), RT.getIn(result, Strings.create("forkedFrom")));
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		// Fork should have the same config and state as source
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState fork = user.agent("fork-agent");
		assertNotNull(fork);
		AMap<AString, ACell> forkConfig = fork.getConfig();
		assertEquals(Strings.create("You are source."),
			forkConfig.get(Strings.create("systemPrompt")));
		assertEquals(Strings.create("gpt-4"),
			forkConfig.get(Strings.create("model")));
		assertEquals(Strings.create("original"),
			RT.getIn(fork.getState(), Strings.create("memory")));
	}

	@Test
	public void testForkAgentWithConfigOverride() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "base",
				Fields.CONFIG, Maps.of(
					Strings.create("systemPrompt"), Strings.create("Original prompt"),
					Strings.create("model"), Strings.create("gpt-4"))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Fork with config override — systemPrompt changes, model stays
		engine.jobs().invokeOperation(
			"v/ops/agent/fork",
			Maps.of(
				Strings.create("sourceId"), Strings.create("base"),
				Fields.AGENT_ID, "variant",
				Fields.CONFIG, Maps.of(
					Strings.create("systemPrompt"), Strings.create("Override prompt"))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState variant = user.agent("variant");
		AMap<AString, ACell> vc = variant.getConfig();
		assertEquals(Strings.create("Override prompt"),
			vc.get(Strings.create("systemPrompt")));
		assertEquals(Strings.create("gpt-4"), vc.get(Strings.create("model")),
			"Non-overridden fields should come from source");
	}

	@Test
	public void testForkAgentFreshCollections() {
		// Create source with messages and tasks
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "busy-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Suspend the source before delivering the message. agent:message
		// auto-wakes the agent (handleMessage -> wakeAgent); a running loop
		// would drain session.pending before the assertion below, which made
		// this test race under parallel load. A suspended agent still queues
		// the message (appendSessionPending runs before wakeAgent) but its loop
		// won't consume it — so the source's pending is deterministically present.
		engine.jobs().invokeOperation(
			"v/ops/agent/suspend",
			Maps.of(Fields.AGENT_ID, "busy-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Deliver a message to source
		engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(
				Fields.AGENT_ID, "busy-agent",
				Fields.MESSAGE, Maps.of("content", "hello")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Fork
		engine.jobs().invokeOperation(
			"v/ops/agent/fork",
			Maps.of(
				Strings.create("sourceId"), Strings.create("busy-agent"),
				Fields.AGENT_ID, "busy-fork"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState fork = user.agent("busy-fork");
		assertFalse(fork.hasSessionPending(), "Fork should have no pending messages");
		assertEquals(0, fork.getTasks().count(), "Fork should have no tasks");

		// Source still has its messages
		AgentState source = user.agent("busy-agent");
		assertTrue(source.hasSessionPending(), "Source session pending is not touched");
	}

	@Test
	public void testForkAgentIncludeTimeline() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "timeline-source"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Fork WITHOUT timeline
		engine.jobs().invokeOperation(
			"v/ops/agent/fork",
			Maps.of(
				Strings.create("sourceId"), Strings.create("timeline-source"),
				Fields.AGENT_ID, "no-timeline-fork"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Fork WITH timeline
		engine.jobs().invokeOperation(
			"v/ops/agent/fork",
			Maps.of(
				Strings.create("sourceId"), Strings.create("timeline-source"),
				Fields.AGENT_ID, "with-timeline-fork",
				Strings.create("includeTimeline"), CVMBool.TRUE),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		// Both forks exist
		assertNotNull(user.agent("no-timeline-fork"));
		assertNotNull(user.agent("with-timeline-fork"));
		// Both have empty timelines (source had none), and status SLEEPING
		assertEquals(AgentState.SLEEPING, user.agent("no-timeline-fork").getStatus());
		assertEquals(AgentState.SLEEPING, user.agent("with-timeline-fork").getStatus());
	}

	@Test
	public void testForkMissingSource() {
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/fork",
			Maps.of(
				Strings.create("sourceId"), Strings.create("ghost"),
				Fields.AGENT_ID, "fork"),
			RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("Should fail when source doesn't exist");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	@Test
	public void testForkTargetAlreadyExists() {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "a1"), RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "a2"), RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/fork",
			Maps.of(
				Strings.create("sourceId"), Strings.create("a1"),
				Fields.AGENT_ID, "a2"),
			RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("Should fail when target already exists");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	@Test
	public void testForkRejectsRemovedOverwriteParameter() {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "fork-source"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/fork",
			Maps.of(Strings.create("sourceId"), "fork-source",
				Fields.AGENT_ID, "fork-target",
				Fields.OVERWRITE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("removed overwrite parameter must fail loudly");
		} catch (covia.exception.JobFailedException expected) {
			assertTrue(job.getErrorMessage().contains("no longer supports overwrite"));
		}
	}

	// ========== agent:message ==========

	@Test
	public void testMessageAgent() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "msg-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell msgInput = Maps.of(
			Fields.AGENT_ID, "msg-agent",
			Fields.MESSAGE, Maps.of("content", "hello")
		);
		Job msgJob = engine.jobs().invokeOperation(
			"v/ops/agent/message", msgInput, RequestContext.of(ALICE_DID));
		ACell result = msgJob.awaitResult(5000);

		// agent:message reports delivery through its result envelope — that is
		// the contract a caller relies on, and the op appends the message to the
		// agent's session before completing. Whether the agent's run loop has
		// since consumed the message is timing-dependent and not part of the
		// delivery contract, so we verify the API result rather than internal state.
		assertNotNull(result);
		assertEquals(Status.COMPLETE, msgJob.getStatus());
		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.DELIVERED));
	}

	@Test
	public void testMessageNonExistentAgent() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "other-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell msgInput = Maps.of(
			Fields.AGENT_ID, "ghost-agent",
			Fields.MESSAGE, Maps.of("content", "hello")
		);
		Job msgJob = engine.jobs().invokeOperation(
			"v/ops/agent/message", msgInput, RequestContext.of(ALICE_DID));

		try {
			msgJob.awaitResult(5000);
			fail("Should have thrown for non-existent agent");
		} catch (Exception e) {
			assertEquals(Status.FAILED, msgJob.getStatus());
		}
	}

	@Test
	public void testMessageTerminatedAgent() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "term-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("term-agent");
		agent.setStatus(AgentState.TERMINATED);

		ACell msgInput = Maps.of(
			Fields.AGENT_ID, "term-agent",
			Fields.MESSAGE, Maps.of("content", "hello")
		);
		Job msgJob = engine.jobs().invokeOperation(
			"v/ops/agent/message", msgInput, RequestContext.of(ALICE_DID));

		try {
			msgJob.awaitResult(5000);
			fail("Should have thrown for terminated agent");
		} catch (Exception e) {
			assertEquals(Status.FAILED, msgJob.getStatus());
		}
	}

	// ========== agent:chat ==========

	/**
	 * Standard LLM-backed chat agent for chat tests. Uses {@code v/test/ops/llm}
	 * which echoes the last user message as the assistant content; the
	 * {@code llmagent:chat} transition surfaces that as the {@code response}
	 * value the framework completes the chat Job with.
	 */
	private void createChatAgent(String agentId) {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/llm",
					"systemPrompt", "Echo the user."
				)
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
	}

	@Test
	public void testChatMintsSessionAndReturnsResponse() {
		createChatAgent("chat-agent");

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "chat-agent", Fields.MESSAGE, Strings.create("hello")),
			RequestContext.of(ALICE_DID));

		ACell result = chatJob.awaitResult(5000);
		assertNotNull(result, "Chat must complete with a response");
		assertEquals(Strings.create("chat-agent"), RT.getIn(result, Fields.AGENT_ID));
		AString sidHex = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		assertNotNull(sidHex, "Chat response must include the minted sessionId");
		assertNotNull(RT.getIn(result, Fields.RESPONSE), "Chat response must include the agent's response");

		// Chat slot is now in-memory (not on the lattice). The follow-up
		// regression is covered by testChatRejectsConcurrentOnSameSession
		// and testCancelChatReleasesSlot, so no direct slot assertion here.
	}

	/**
	 * Regression guard for issue #85 — internal adapter-to-adapter calls
	 * (transition dispatch, LLM sub-invocation, tool calls) must use
	 * {@code JobManager.invokeInternal} which returns a plain
	 * {@link java.util.concurrent.CompletableFuture} and creates zero Jobs.
	 * Pre-refactor a single {@code agent:chat} spawned 3 Jobs:
	 * the chat Job, a transition Job ({@code llmagent:chat}), and an LLM
	 * sub-invocation Job ({@code test:llm}). Post-refactor only the
	 * caller's chat Job should exist.
	 */
	@Test
	public void testChatProducesExactlyOneJob() {
		createChatAgent("chat-count-agent");
		RequestContext ctx = RequestContext.of(ALICE_DID);

		long before = engine.jobs().getJobs(ctx).count();

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "chat-count-agent", Fields.MESSAGE, Strings.create("hi")),
			ctx);
		chatJob.awaitResult(5000);

		long after = engine.jobs().getJobs(ctx).count();
		assertEquals(1, after - before,
			"agent:chat must produce exactly 1 Job — pre-refactor created 3 "
			+ "(chat + transition + llm sub-invocation)");
	}

	@Test
	public void testChatContinuesKnownSession() {
		createChatAgent("chat-cont-agent");

		// First chat — mint session
		Job first = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "chat-cont-agent", Fields.MESSAGE, Strings.create("first")),
			RequestContext.of(ALICE_DID));
		ACell firstResult = first.awaitResult(5000);
		AString sidHex = RT.ensureString(RT.getIn(firstResult, Fields.SESSION_ID));
		assertNotNull(sidHex);

		// Second chat — echo session id
		Job second = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(
				Fields.AGENT_ID,   "chat-cont-agent",
				Fields.SESSION_ID, sidHex,
				Fields.MESSAGE,    Strings.create("second")),
			RequestContext.of(ALICE_DID));
		ACell secondResult = second.awaitResult(5000);
		assertNotNull(secondResult);
		assertEquals(sidHex, RT.getIn(secondResult, Fields.SESSION_ID),
			"Second chat must echo the same session id");
	}

	@Test
	public void testChatRejectsUnknownSession() {
		createChatAgent("chat-reject-agent");

		// Random non-existent sid (well-formed hex, never minted)
		String fakeSid = "00000000000000000000000000000000";

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(
				Fields.AGENT_ID,   "chat-reject-agent",
				Fields.SESSION_ID, Strings.create(fakeSid),
				Fields.MESSAGE,    Strings.create("hi")),
			RequestContext.of(ALICE_DID));

		try {
			chatJob.awaitResult(5000);
			fail("Chat with unknown sessionId must fail");
		} catch (Exception e) {
			assertEquals(Status.FAILED, chatJob.getStatus());
		}
	}

	@Test
	public void testChatRejectsConcurrentOnSameSession() throws InterruptedException {
		// Use a long-running LLM op so the first chat stays in flight while
		// the second arrives. v/test/ops/delay holds the transition open.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "chat-busy-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					// Wrap delay around the L3 call by using the standard llm op
					// but with a slow llm. Easiest: use test:delay-llm if it exists,
					// otherwise just attempt a second call quickly.
					"llmOperation", "v/test/ops/llm",
					"systemPrompt", "Echo the user."
				)
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("chat-busy-agent");

		// Pre-create a session and reserve its in-memory chat slot with a
		// never-finished Job to force the second-chat-on-busy-session error
		// path deterministically.
		Blob sid = Blob.fromHex("11111111111111111111111111111111");
		agent.ensureSession(sid, ALICE_DID);
		AgentAdapter agentAdapter = (AgentAdapter) engine.getAdapter("agent");
		// A never-completing placeholder Job holds the slot, and its envelope is
		// still queued on the session — a genuinely busy session (a chat that
		// was accepted and not yet drained), which intake must refuse.
		Job placeholder = Job.create(Maps.of(Fields.STATUS, Status.STARTED));
		agentAdapter.reserveChatSlotForTest(
			ALICE_DID, Strings.create("chat-busy-agent"), sid, placeholder);
		agent.appendSessionPending(sid, Maps.of(
			Fields.CALLER, ALICE_DID, Fields.MESSAGE, Strings.create("first, still queued")));

		// Now an agent_chat on the same session must fail fast
		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(
				Fields.AGENT_ID,   "chat-busy-agent",
				Fields.SESSION_ID, Strings.create(sid.toHexString()),
				Fields.MESSAGE,    Strings.create("hi")),
			RequestContext.of(ALICE_DID));

		try {
			chatJob.awaitResult(5000);
			fail("Concurrent chat on same session must be rejected");
		} catch (Exception e) {
			assertEquals(Status.FAILED, chatJob.getStatus());
			assertTrue(String.valueOf(chatJob.getErrorMessage()).contains("already has an in-flight chat"),
				chatJob.getErrorMessage());
		}
		assertFalse(placeholder.isFinished(), "a busy holder is left alone");
	}

	/**
	 * Regression for #377: a chat whose cycle never completed (a transition
	 * that died, a lost wake) used to hold the session's slot for ever — every
	 * later chat was rejected as "already in flight" although nothing could
	 * ever finish the holder. Intake now recognises that state (agent idle,
	 * nothing pending) and self-heals: the stale holder is failed with the
	 * reason and the new chat proceeds.
	 */
	@Test
	public void testChatSelfHealsAWedgedSession() {
		createChatAgent("chat-wedged-agent");
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("chat-wedged-agent");
		Blob sid = Blob.fromHex("22222222222222222222222222222222");
		agent.ensureSession(sid, ALICE_DID);
		AgentAdapter agentAdapter = (AgentAdapter) engine.getAdapter("agent");
		// The wedge: an unfinished holder, agent idle, session pending empty.
		Job stale = Job.create(Maps.of(Fields.STATUS, Status.STARTED));
		agentAdapter.reserveChatSlotForTest(ALICE_DID, Strings.create("chat-wedged-agent"), sid, stale);

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(
				Fields.AGENT_ID,   "chat-wedged-agent",
				Fields.SESSION_ID, Strings.create(sid.toHexString()),
				Fields.MESSAGE,    Strings.create("are you there?")),
			RequestContext.of(ALICE_DID));
		ACell result = chatJob.awaitResult(10000);
		assertEquals(Status.COMPLETE, chatJob.getStatus(), "the new chat proceeds: " + chatJob.getErrorMessage());
		assertNotNull(RT.getIn(result, Fields.RESPONSE));
		assertTrue(stale.isFinished() && Status.FAILED.equals(stale.getStatus()), "the stale holder is failed, not left dangling");
		assertTrue(String.valueOf(stale.getErrorMessage()).contains("did not complete"), stale.getErrorMessage());
		assertNull(agentAdapter.getActiveChatForTest(ALICE_DID, Strings.create("chat-wedged-agent"), sid),
			"slot released after the healed chat completed");
	}

	@Test
	public void testChatRequiresMessage() {
		createChatAgent("chat-msg-agent");

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "chat-msg-agent"),
			RequestContext.of(ALICE_DID));

		try {
			chatJob.awaitResult(5000);
			fail("Chat without message must fail");
		} catch (Exception e) {
			assertEquals(Status.FAILED, chatJob.getStatus());
		}
	}

	// ========== S3a — session.history append ==========

	/**
	 * After a successful chat, the framework must have appended a user turn
	 * (the chat message) and an assistant turn (the response) to the
	 * picked session's history vector. Each turn carries
	 * {role, content, ts, source}; meta.turns increments accordingly.
	 *
	 * <p>Note: S3a only appends turns derived from picked-task input or
	 * leanResponse. Chat messages go via inbox in S3a and are NOT yet
	 * surfaced as user turns from history (that's S3b). So we expect
	 * exactly one assistant turn, sourced from the transition.</p>
	 */
	@Test
	public void testTransitionAppendsResponseToSessionHistory() {
		createChatAgent("hist-resp-agent");

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "hist-resp-agent", Fields.MESSAGE, Strings.create("hello")),
			RequestContext.of(ALICE_DID));
		ACell result = chatJob.awaitResult(5000);
		AString sidHex = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		assertNotNull(sidHex);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("hist-resp-agent");
		AMap<AString, ACell> session = agent.getSession(Blob.fromHex(sidHex.toString()));
		assertNotNull(session, "Session record must exist");

		AVector<ACell> frames = (AVector<ACell>) session.get(AgentState.KEY_FRAMES);
		assertNotNull(frames, "Session must have frames");
		AMap<AString, ACell> rootFrame = (AMap<AString, ACell>) frames.get(0);
		AVector<ACell> history = (AVector<ACell>) rootFrame.get(AgentState.KEY_CONVERSATION);
		assertNotNull(history, "Root frame must have a conversation vector");
		assertEquals(2, history.count(),
			"Chat cycle appends user turn (chat message) + assistant turn");

		AMap<AString, ACell> userTurn = (AMap<AString, ACell>) history.get(0);
		assertEquals(AgentState.ROLE_USER, userTurn.get(AgentState.K_ROLE));
		assertEquals(AgentState.SOURCE_CHAT, userTurn.get(AgentState.K_SOURCE));
		assertEquals(Strings.create("hello"), userTurn.get(AgentState.K_CONTENT));

		AMap<AString, ACell> assistantTurn = (AMap<AString, ACell>) history.get(1);
		assertEquals(AgentState.ROLE_ASSISTANT, assistantTurn.get(AgentState.K_ROLE));
		assertEquals(AgentState.SOURCE_TRANSITION, assistantTurn.get(AgentState.K_SOURCE));
		assertNotNull(assistantTurn.get(AgentState.K_CONTENT), "Turn must carry content");
		assertNotNull(assistantTurn.get(AgentState.K_TURN_TS), "Turn must carry timestamp");
		assertTrue(assistantTurn.get(AgentState.K_TURN_TS) instanceof CVMLong, "ts must be CVMLong");

		// meta.turns should be 2 (user + assistant)
		AMap<AString, ACell> meta = (AMap<AString, ACell>) session.get(Strings.intern("meta"));
		assertEquals(CVMLong.create(2), meta.get(Strings.intern("turns")),
			"meta.turns must reflect appended turn count");
		assertEquals(assistantTurn.get(AgentState.K_TURN_TS), meta.get(Fields.UPDATED),
			"meta.updated must ratchet to the newest persisted turn timestamp");

		// #84 opt-in off by default: no per-turn caller attribution.
		assertNull(userTurn.get(Fields.CALLER),
			"recordCaller off (default) → user turns carry no caller");
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testLlmToolTrailPersistedOnceInSessionHistory() {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "tool-history-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/toolllm")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job chat = engine.jobs().invokeOperation("v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "tool-history-agent",
				Fields.MESSAGE, "audit this tool"),
			RequestContext.of(ALICE_DID));
		AString sid = RT.ensureString(RT.getIn(chat.awaitResult(5000), Fields.SESSION_ID));

		AgentState agent = engine.getVenueState().users().get(ALICE_DID)
			.agent("tool-history-agent");
		AMap<AString, ACell> session = agent.getSession(Blob.fromHex(sid.toString()));
		AVector<ACell> frames = RT.ensureVector(session.get(Fields.FRAMES));
		AVector<ACell> conversation = RT.ensureVector(
			RT.getIn(frames.get(0), AgentState.KEY_CONVERSATION));
		assertEquals(4, conversation.count(),
			"user + assistant(tool call) + tool result + final assistant");
		assertEquals("user", RT.getIn(conversation.get(0), "role").toString());
		assertEquals("assistant", RT.getIn(conversation.get(1), "role").toString());
		assertNotNull(RT.getIn(conversation.get(1), "toolCalls"));
		assertEquals("tool", RT.getIn(conversation.get(2), "role").toString());
		assertEquals("assistant", RT.getIn(conversation.get(3), "role").toString());
		assertNull(RT.getIn(conversation.get(3), "toolCalls"));
		assertEquals(CVMLong.create(4), RT.getIn(session, "meta", "turns"));
	}

	/** #84: with config.recordCaller, user turns record the sender's DID. */
	@Test
	@SuppressWarnings("unchecked")
	public void testRecordCallerStampsUserTurns() {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "caller-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					Strings.create("llmOperation"), Strings.create("v/test/ops/llm"),
					Strings.create("recordCaller"), CVMBool.TRUE)),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job chatJob = engine.jobs().invokeOperation("v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "caller-agent", Fields.MESSAGE, Strings.create("hi")),
			RequestContext.of(ALICE_DID));
		AString sidHex = RT.ensureString(RT.getIn(chatJob.awaitResult(5000), Fields.SESSION_ID));

		AgentState agent = engine.getVenueState().users().get(ALICE_DID).agent("caller-agent");
		AVector<ACell> frames = (AVector<ACell>) agent.getSession(Blob.fromHex(sidHex.toString()))
			.get(AgentState.KEY_FRAMES);
		AVector<ACell> history = (AVector<ACell>) ((AMap<AString, ACell>) frames.get(0))
			.get(AgentState.KEY_CONVERSATION);

		AMap<AString, ACell> userTurn = (AMap<AString, ACell>) history.get(0);
		assertEquals(ALICE_DID, userTurn.get(Fields.CALLER),
			"user turn records the sender DID when recordCaller is on");
		// Assistant turns are the agent's own output — never caller-stamped.
		AMap<AString, ACell> assistantTurn = (AMap<AString, ACell>) history.get(1);
		assertNull(assistantTurn.get(Fields.CALLER),
			"assistant turns carry no caller");
	}

	/**
	 * When a task is picked, its input becomes a user/request turn appended
	 * to the picked session's history alongside the assistant response.
	 */
	@Test
	public void testTransitionAppendsTaskInputAsUserTurn() {
		// Use v/test/ops/taskcomplete — it completes the task in a single
		// cycle and returns a `response`, giving us both user and assistant
		// turns deterministically.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "hist-task-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Blob sid = Blob.fromHex("22222222222222222222222222222222");
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("hist-task-agent");
		agent.ensureSession(sid, ALICE_DID);

		Job reqJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(
				Fields.AGENT_ID, "hist-task-agent",
				Fields.SESSION_ID, Strings.create(sid.toHexString()),
				Fields.INPUT, Strings.create("do thing"),
				Fields.WAIT, CVMLong.create(5000)),
			RequestContext.of(ALICE_DID));
		reqJob.awaitResult(5000);

		assertEquals(AgentState.SLEEPING, awaitFinished(agent));

		AMap<AString, ACell> session = agent.getSession(sid);
		AVector<ACell> frames = (AVector<ACell>) session.get(AgentState.KEY_FRAMES);
		AMap<AString, ACell> rootFrame = (AMap<AString, ACell>) frames.get(0);
		AVector<ACell> history = (AVector<ACell>) rootFrame.get(AgentState.KEY_CONVERSATION);
		assertEquals(2, history.count(),
			"Picked task + response cycle appends two turns (user, assistant)");

		AMap<AString, ACell> userTurn = (AMap<AString, ACell>) history.get(0);
		assertEquals(AgentState.ROLE_USER, userTurn.get(AgentState.K_ROLE));
		assertEquals(AgentState.SOURCE_REQUEST, userTurn.get(AgentState.K_SOURCE));
		assertEquals(Strings.create("do thing"), userTurn.get(AgentState.K_CONTENT));
		// The request turn records the delegating agent:request Job id, so the
		// target agent's session ties back to the calling job as chat turns do (#378).
		assertEquals(Strings.create(reqJob.getID().toHexString()), userTurn.get(Fields.JOB_ID),
			"request turn records the calling agent:request job id");

		AMap<AString, ACell> assistantTurn = (AMap<AString, ACell>) history.get(1);
		assertEquals(AgentState.ROLE_ASSISTANT, assistantTurn.get(AgentState.K_ROLE));
		assertEquals(AgentState.SOURCE_TRANSITION, assistantTurn.get(AgentState.K_SOURCE));
	}

	/**
	 * Errored cycles append no turns. {@code leanError != null} must skip
	 * history population entirely so the audit trail doesn't claim a
	 * conversation turn that the agent failed to produce.
	 */
	@Test
	public void testErrorResponseDoesNotAppendTurn() {
		// Use the test echo-llm agent but break the L3 op to force an error path.
		// Simplest: create an agent whose transition op returns an error.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "hist-err-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/error",
					"systemPrompt", "x"
				)
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "hist-err-agent", Fields.MESSAGE, Strings.create("x")),
			RequestContext.of(ALICE_DID));
		try { chatJob.awaitResult(5000); } catch (Exception ignored) {}

		// Find the minted session via agent state — chat slot should be cleared
		// either way, but we want any session that exists to have empty history.
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("hist-err-agent");
		var sessions = agent.getSessions();
		if (sessions.count() == 0) return; // No session minted — nothing to assert
		for (var entry : sessions.entrySet()) {
			AMap<AString, ACell> session = (AMap<AString, ACell>) entry.getValue();
			AVector<ACell> frames = (AVector<ACell>) session.get(AgentState.KEY_FRAMES);
			AMap<AString, ACell> rootFrame = (AMap<AString, ACell>) frames.get(0);
			AVector<ACell> history = (AVector<ACell>) rootFrame.get(AgentState.KEY_CONVERSATION);
			assertEquals(0, history.count(),
				"Errored cycle must not append any turns to history");
		}
	}

	/**
	 * History must accumulate across multiple chat turns on the same session,
	 * with order preserved (oldest first) and meta.turns reflecting the
	 * cumulative count.
	 */
	@Test
	public void testHistoryCarriesAcrossMultipleCycles() {
		createChatAgent("hist-multi-agent");

		Job first = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "hist-multi-agent", Fields.MESSAGE, Strings.create("one")),
			RequestContext.of(ALICE_DID));
		ACell firstResult = first.awaitResult(5000);
		AString sidHex = RT.ensureString(RT.getIn(firstResult, Fields.SESSION_ID));

		Job second = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(
				Fields.AGENT_ID,   "hist-multi-agent",
				Fields.SESSION_ID, sidHex,
				Fields.MESSAGE,    Strings.create("two")),
			RequestContext.of(ALICE_DID));
		second.awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("hist-multi-agent");
		AMap<AString, ACell> session = agent.getSession(Blob.fromHex(sidHex.toString()));
		AVector<ACell> frames = (AVector<ACell>) session.get(AgentState.KEY_FRAMES);
		AMap<AString, ACell> rootFrame = (AMap<AString, ACell>) frames.get(0);
		AVector<ACell> history = (AVector<ACell>) rootFrame.get(AgentState.KEY_CONVERSATION);
		assertEquals(4, history.count(),
			"Two chat cycles: each appends user+assistant = 4 turns total");

		// Turn order: [user1, assistant1, user2, assistant2]
		assertEquals(AgentState.ROLE_USER, RT.getIn(history.get(0), AgentState.K_ROLE));
		assertEquals(Strings.create("one"), RT.getIn(history.get(0), AgentState.K_CONTENT));
		assertEquals(AgentState.ROLE_ASSISTANT, RT.getIn(history.get(1), AgentState.K_ROLE));
		assertEquals(AgentState.ROLE_USER, RT.getIn(history.get(2), AgentState.K_ROLE));
		assertEquals(Strings.create("two"), RT.getIn(history.get(2), AgentState.K_CONTENT));
		assertEquals(AgentState.ROLE_ASSISTANT, RT.getIn(history.get(3), AgentState.K_ROLE));

		AMap<AString, ACell> meta = (AMap<AString, ACell>) session.get(Strings.intern("meta"));
		assertEquals(CVMLong.create(4), meta.get(Strings.intern("turns")));

		// Order preserved: ts of all turns must be non-decreasing
		long prev = 0;
		for (long i = 0; i < history.count(); i++) {
			long ts = ((CVMLong) RT.getIn(history.get(i), AgentState.K_TURN_TS)).longValue();
			assertTrue(ts >= prev, "History order must be chronological at index " + i);
			prev = ts;
		}
	}

	// ========== Cutover step 2a — frames[0].conversation dual-write ==========

	/**
	 * {@code ensureSession} creates a session with a {@code frames} vector
	 * holding exactly one root frame ({@code {description: "", conversation: []}}).
	 * This is the shape GoalTreeAdapter relies on post-cutover: a root frame
	 * always exists from session creation onward.
	 */
	@Test
	public void testEnsureSessionCreatesRootFrame() {
		createChatAgent("frames-init-agent");

		Blob sid = Blob.fromHex("33333333333333333333333333333333");
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("frames-init-agent");
		agent.ensureSession(sid, ALICE_DID);

		AMap<AString, ACell> session = agent.getSession(sid);
		AVector<ACell> frames = (AVector<ACell>) session.get(AgentState.KEY_FRAMES);
		assertNotNull(frames, "Session must carry a frames vector");
		assertEquals(1, frames.count(), "Fresh session has exactly one root frame");

		AMap<AString, ACell> root = (AMap<AString, ACell>) frames.get(0);
		assertEquals(Strings.EMPTY, root.get(AgentState.KEY_DESCRIPTION),
			"Root frame description starts empty");
		AVector<ACell> conv = (AVector<ACell>) root.get(AgentState.KEY_CONVERSATION);
		assertNotNull(conv, "Root frame must carry a conversation vector");
		assertEquals(0, conv.count(), "Fresh root frame conversation is empty");
	}

	/**
	 * Writer-path dual-write (cutover step 2a): turns appended by a transition
	 * must land in {@code session.frames[0].conversation} in the same order
	 * and with the same content as {@code session.history}. Once readers swap
	 * to frames (step 2b/3) and history is dropped (step 2c), the history
	 * half of this assertion goes away — the frames-side check stays.
	 */
	@Test
	public void testTransitionAppendsToRootFrameConversation() {
		createChatAgent("frames-write-agent");

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "frames-write-agent", Fields.MESSAGE, Strings.create("hi")),
			RequestContext.of(ALICE_DID));
		ACell result = chatJob.awaitResult(5000);
		AString sidHex = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("frames-write-agent");
		AMap<AString, ACell> session = agent.getSession(Blob.fromHex(sidHex.toString()));

		AVector<ACell> frames = (AVector<ACell>) session.get(AgentState.KEY_FRAMES);
		assertEquals(1, frames.count(), "No subgoal — stack depth is 1");
		AMap<AString, ACell> root = (AMap<AString, ACell>) frames.get(0);
		AVector<ACell> conv = (AVector<ACell>) root.get(AgentState.KEY_CONVERSATION);

		assertEquals(2, conv.count(),
			"Chat cycle appends user turn + assistant turn to root frame conversation");

		AMap<AString, ACell> userTurn = (AMap<AString, ACell>) conv.get(0);
		assertEquals(AgentState.ROLE_USER, userTurn.get(AgentState.K_ROLE));
		assertEquals(AgentState.SOURCE_CHAT, userTurn.get(AgentState.K_SOURCE));
		assertEquals(Strings.create("hi"), userTurn.get(AgentState.K_CONTENT));

		AMap<AString, ACell> assistantTurn = (AMap<AString, ACell>) conv.get(1);
		assertEquals(AgentState.ROLE_ASSISTANT, assistantTurn.get(AgentState.K_ROLE));
		assertEquals(AgentState.SOURCE_TRANSITION, assistantTurn.get(AgentState.K_SOURCE));
	}

	/**
	 * Turns must accumulate in {@code frames[0].conversation} across multiple
	 * cycles in the same session, preserving order (oldest first).
	 */
	@Test
	public void testRootFrameConversationAccumulatesAcrossCycles() {
		createChatAgent("frames-multi-agent");

		Job first = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "frames-multi-agent", Fields.MESSAGE, Strings.create("one")),
			RequestContext.of(ALICE_DID));
		AString sidHex = RT.ensureString(RT.getIn(first.awaitResult(5000), Fields.SESSION_ID));

		Job second = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(
				Fields.AGENT_ID,   "frames-multi-agent",
				Fields.SESSION_ID, sidHex,
				Fields.MESSAGE,    Strings.create("two")),
			RequestContext.of(ALICE_DID));
		second.awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("frames-multi-agent");
		AMap<AString, ACell> session = agent.getSession(Blob.fromHex(sidHex.toString()));

		AVector<ACell> frames = (AVector<ACell>) session.get(AgentState.KEY_FRAMES);
		AMap<AString, ACell> root = (AMap<AString, ACell>) frames.get(0);
		AVector<ACell> conv = (AVector<ACell>) root.get(AgentState.KEY_CONVERSATION);

		assertEquals(4, conv.count(), "Two chat cycles append 4 turns total");
		assertEquals(Strings.create("one"), RT.getIn(conv.get(0), AgentState.K_CONTENT));
		assertEquals(AgentState.ROLE_ASSISTANT, RT.getIn(conv.get(1), AgentState.K_ROLE));
		assertEquals(Strings.create("two"), RT.getIn(conv.get(2), AgentState.K_CONTENT));
		assertEquals(AgentState.ROLE_ASSISTANT, RT.getIn(conv.get(3), AgentState.K_ROLE));
	}

	// ========== S3b — session map in transition input + dual-write ==========

	/**
	 * S3b dual-write: a chat must land in both {@code agent.inbox} (legacy)
	 * and {@code session.pending} (new). After the cycle runs, both should
	 * be drained for the consumed session.
	 */
	@Test
	public void testChatDualWritesToSessionPending() throws Exception {
		// Create the agent, mint a session manually, then deliver a chat —
		// but block the run loop so we can observe the dual-write before the
		// cycle drains it. Simplest: pre-reserve the chat slot so the actual
		// agent_chat fails fast, leaving the manually-injected message visible.
		// Even simpler: use agent_message (no slot reservation) and observe.
		createChatAgent("s3b-dualwrite-agent");
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("s3b-dualwrite-agent");

		// Suspend agent so messages queue without being consumed
		engine.jobs().invokeOperation(
			"v/ops/agent/suspend",
			Maps.of(Fields.AGENT_ID, "s3b-dualwrite-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Blob sid = Blob.fromHex("33333333333333333333333333333333");
		agent.ensureSession(sid, ALICE_DID);

		Job msgJob = engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(
				Fields.AGENT_ID,   "s3b-dualwrite-agent",
				Fields.SESSION_ID, Strings.create(sid.toHexString()),
				Fields.MESSAGE,    Strings.create("hi there")),
			RequestContext.of(ALICE_DID));
		msgJob.awaitResult(5000);

		// Session pending should hold the message
		AVector<ACell> sessionPending = agent.getSessionPending(sid);
		assertEquals(1, sessionPending.count(),
			"session.pending must hold the message");
		// Envelope shape preserved on both sides
		AMap<AString, ACell> envelope = (AMap<AString, ACell>) sessionPending.get(0);
		assertEquals(Strings.create("hi there"), envelope.get(Fields.MESSAGE));
	}

	/**
	 * The session record carried into the transition under
	 * {@code Fields.SESSION} must have the full {parties, meta, c, history,
	 * pending} shape (id is associated by the run loop). We verify by
	 * driving a chat cycle and asserting:
	 *  (a) the session record on the lattice carries every expected key, and
	 *  (b) the cycle observed and drained {@code session.pending} (proves
	 *      the run loop snapshotted the record and passed the count to the
	 *      atomic merge).
	 */
	@Test
	public void testTransitionReceivesSessionMap() throws Exception {
		createChatAgent("s3b-shape-agent");
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("s3b-shape-agent");

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "s3b-shape-agent",
				Fields.MESSAGE, Strings.create("hi")),
			RequestContext.of(ALICE_DID));
		ACell result = chatJob.awaitResult(5000);
		AString sidHex = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		Blob sid = Blob.fromHex(sidHex.toString());

		assertEquals(AgentState.SLEEPING, awaitFinished(agent));

		AMap<AString, ACell> session = agent.getSession(sid);
		assertNotNull(session, "Session record must exist on lattice");
		// Keys the run loop assembles into the transition input map
		assertNotNull(session.get(Strings.intern("meta")),    "session must have meta");
		assertNotNull(session.get(Strings.intern("c")),       "session must have c");
		assertNotNull(session.get(AgentState.KEY_FRAMES),     "session must have frames");
		assertNotNull(session.get(Strings.intern("pending")), "session must have pending");
		// parties lives under meta
		AMap<AString, ACell> meta = (AMap<AString, ACell>) session.get(Strings.intern("meta"));
		assertNotNull(meta.get(Strings.intern("parties")), "meta.parties must exist");

		// Drain proof — the run loop did snapshot session.pending and pass
		// its count to mergeRunResult. (The chat-handler dual-write puts the
		// message in session.pending; if the merge didn't drain it, count > 0.)
		assertEquals(0, ((AVector<?>) session.get(Strings.intern("pending"))).count(),
			"session.pending must have been drained by the cycle");
	}

	/**
	 * After a cycle consumes the session's pending messages, the session
	 * pending vector must be drained. Tail messages arriving during the
	 * transition are preserved (tested implicitly via count semantics in
	 * the merge — here we just verify the basic drain).
	 */
	@Test
	public void testSessionPendingDrainsAfterCycle() throws Exception {
		createChatAgent("s3b-drain-agent");
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("s3b-drain-agent");

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "s3b-drain-agent",
				Fields.MESSAGE, Strings.create("hello")),
			RequestContext.of(ALICE_DID));
		ACell result = chatJob.awaitResult(5000);
		AString sidHex = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		Blob sid = Blob.fromHex(sidHex.toString());

		assertEquals(AgentState.SLEEPING, awaitFinished(agent));

		AVector<ACell> sessionPending = agent.getSessionPending(sid);
		assertEquals(0, sessionPending.count(),
			"session.pending must be drained after the cycle consumes its messages");
	}

	/**
	 * Symmetric to the chat dual-write test but for {@code agent_message}
	 * — confirms the dual-write happens regardless of which intake op
	 * delivered the message.
	 */
	@Test
	public void testMessageDualWritesToSessionPending() throws Exception {
		createChatAgent("s3b-msg-dualwrite-agent");
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("s3b-msg-dualwrite-agent");

		// Suspend so messages queue
		engine.jobs().invokeOperation(
			"v/ops/agent/suspend",
			Maps.of(Fields.AGENT_ID, "s3b-msg-dualwrite-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Blob sid = Blob.fromHex("55555555555555555555555555555555");
		agent.ensureSession(sid, ALICE_DID);

		engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(
				Fields.AGENT_ID,   "s3b-msg-dualwrite-agent",
				Fields.SESSION_ID, Strings.create(sid.toHexString()),
				Fields.MESSAGE,    Strings.create("a")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(
				Fields.AGENT_ID,   "s3b-msg-dualwrite-agent",
				Fields.SESSION_ID, Strings.create(sid.toHexString()),
				Fields.MESSAGE,    Strings.create("b")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		AVector<ACell> pending = agent.getSessionPending(sid);
		assertEquals(2, pending.count(), "Both messages must appear in session.pending");
		assertEquals(Strings.create("a"),
			((AMap<AString, ACell>) pending.get(0)).get(Fields.MESSAGE));
		assertEquals(Strings.create("b"),
			((AMap<AString, ACell>) pending.get(1)).get(Fields.MESSAGE));
	}

	// ========== S3c — adapters prefer session.history / session.pending ==========

	/**
	 * S3c read priority: when a transition input carries both
	 * {@code session.pending} (S3b dual-write) and the legacy
	 * {@code Fields.MESSAGES}, {@link AgentAdapter#effectiveMessages} must
	 * return {@code session.pending} — reading both would duplicate.
	 */
	@Test
	public void testEffectiveMessagesPrefersSessionPending() {
		AVector<ACell> sessionPending = Vectors.of(
			Maps.of(Fields.MESSAGE, Strings.create("from-session")));
		AVector<ACell> legacyMessages = Vectors.of(
			Maps.of(Fields.MESSAGE, Strings.create("from-legacy")));
		AMap<AString, ACell> rootFrame = Maps.of(
			AgentState.KEY_DESCRIPTION,  Strings.EMPTY,
			AgentState.KEY_CONVERSATION, Vectors.empty());
		AMap<AString, ACell> session = Maps.of(
			AgentState.KEY_PENDING, sessionPending,
			AgentState.KEY_FRAMES,  Vectors.of(rootFrame));
		AMap<AString, ACell> input = Maps.of(
			Fields.SESSION,  session,
			Fields.MESSAGES, legacyMessages);

		AVector<ACell> effective = AgentAdapter.effectiveMessages(input);
		assertEquals(1, effective.count(),
			"Must take session.pending only — not concatenate with messages");
		assertEquals(Strings.create("from-session"),
			RT.getIn(effective.get(0), Fields.MESSAGE),
			"Must be the session.pending entry, not the legacy messages entry");
	}

	/**
	 * S3c read priority fallback: with no session in the input,
	 * {@link AgentAdapter#effectiveMessages} returns {@code Fields.MESSAGES}.
	 * Returns empty (never null) when neither source has anything.
	 */
	@Test
	public void testEffectiveMessagesFallsBackToInputMessages() {
		AVector<ACell> legacyMessages = Vectors.of(
			Maps.of(Fields.MESSAGE, Strings.create("legacy-only")));
		AMap<AString, ACell> input = Maps.of(Fields.MESSAGES, legacyMessages);

		AVector<ACell> effective = AgentAdapter.effectiveMessages(input);
		assertEquals(1, effective.count());
		assertEquals(Strings.create("legacy-only"),
			RT.getIn(effective.get(0), Fields.MESSAGE));

		// Empty input → empty (not null)
		AVector<ACell> empty = AgentAdapter.effectiveMessages(Maps.empty());
		assertNotNull(empty, "Must return empty vector, not null");
		assertEquals(0, empty.count());
	}

	/**
	 * Frame-stack read helper: {@link AgentAdapter#sessionFrames} reads
	 * {@code input.session.frames} — the full frame stack. Returns null
	 * when no session is in scope or frames is absent/empty, so callers
	 * can fall back to their own state.
	 */
	@Test
	public void testSessionFramesHelper() {
		AVector<ACell> turns = Vectors.of(
			Maps.of(AgentState.K_ROLE, AgentState.ROLE_USER,
				AgentState.K_CONTENT, Strings.create("hi")));
		AMap<AString, ACell> rootFrame = Maps.of(
			AgentState.KEY_DESCRIPTION,  Strings.EMPTY,
			AgentState.KEY_CONVERSATION, turns);
		AMap<AString, ACell> session = Maps.of(
			AgentState.KEY_FRAMES,  Vectors.of(rootFrame),
			AgentState.KEY_PENDING, Vectors.empty());
		AMap<AString, ACell> withSession = Maps.of(Fields.SESSION, session);
		AVector<ACell> got = AgentAdapter.sessionFrames(withSession);
		assertNotNull(got);
		assertEquals(1, got.count(), "Single root frame");

		// No session → null sentinel (caller falls back to state)
		assertNull(AgentAdapter.sessionFrames(Maps.empty()),
			"Null when no session in scope");

		// Session present but frames missing → null
		AMap<AString, ACell> sessionNoFrames = Maps.of(
			AgentState.KEY_PENDING, Vectors.empty());
		assertNull(AgentAdapter.sessionFrames(Maps.of(Fields.SESSION, sessionNoFrames)),
			"Null when session has no frames");

		// Session with empty frames vector → null
		AMap<AString, ACell> sessionEmptyFrames = Maps.of(
			AgentState.KEY_FRAMES, Vectors.empty());
		assertNull(AgentAdapter.sessionFrames(Maps.of(Fields.SESSION, sessionEmptyFrames)),
			"Null when frames is empty vector");
	}

	/**
	 * The assembler's conversation section must convert the active frame's
	 * conversation turn envelopes {role, content, ts, source} into plain
	 * LLM messages {role, content}. Degenerate single-frame case: no ancestor
	 * summary, just the root's conversation. Tool-call interleaving from
	 * across-turn tool sequences is not preserved (documented contract).
	 */
	@Test
	public void testWithFrameStackConvertsTurnsToLLMMessages() {
		AVector<ACell> turns = Vectors.of(
			(ACell) Maps.of(
				AgentState.K_ROLE,    AgentState.ROLE_USER,
				AgentState.K_CONTENT, Strings.create("what's 2+2?"),
				AgentState.K_TURN_TS, CVMLong.create(100L),
				AgentState.K_SOURCE,  AgentState.SOURCE_REQUEST),
			(ACell) Maps.of(
				AgentState.K_ROLE,    AgentState.ROLE_ASSISTANT,
				AgentState.K_CONTENT, Strings.create("4"),
				AgentState.K_TURN_TS, CVMLong.create(200L),
				AgentState.K_SOURCE,  AgentState.SOURCE_TRANSITION));
		AMap<AString, ACell> rootFrame = Maps.of(
			AgentState.KEY_DESCRIPTION,  Strings.EMPTY,
			AgentState.KEY_CONVERSATION, turns);
		AVector<ACell> frames = Vectors.of(rootFrame);

		AVector<ACell> all = ContextAssembler.assemble(new ContextAssembler.Spec(
			engine, RequestContext.of(ALICE_DID), null, null, null, null, 0, null,
			null, null, null, frames, null, null, true, null, null, null, null, null)).messages();
		// Between the head and the tail notices lies the conversation.
		AVector<ACell> llmMessages = all.slice(1, all.count() - 1);
		assertEquals(2, llmMessages.count(), "Two turns → two LLM messages");

		AMap<AString, ACell> first = (AMap<AString, ACell>) llmMessages.get(0);
		assertEquals(AgentState.ROLE_USER, first.get(Strings.intern("role")));
		assertEquals(Strings.create("what's 2+2?"), first.get(Strings.intern("content")));
		// ts and source are dropped — vendor APIs require {role, content} only
		assertNull(first.get(AgentState.K_TURN_TS), "ts must be dropped");
		assertNull(first.get(AgentState.K_SOURCE),  "source must be dropped");

		AMap<AString, ACell> second = (AMap<AString, ACell>) llmMessages.get(1);
		assertEquals(AgentState.ROLE_ASSISTANT, second.get(Strings.intern("role")));
		assertEquals(Strings.create("4"), second.get(Strings.intern("content")));
	}

	// ========== agent:trigger ==========

	@Test
	public void testTriggerWithEcho() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "echo-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Deliver messages directly to avoid auto-wake race
		User echoUser = engine.getVenueState().users().get(ALICE_DID);
		AgentState echoAgent = echoUser.agent("echo-agent");
		Blob echoSid = Blob.fromHex("aaaa0001aaaa0001aaaa0001aaaa0001");
		echoAgent.ensureSession(echoSid, ALICE_DID);
		AString echoSidHex = Strings.create(echoSid.toHexString());
		for (int i = 0; i < 2; i++) {
			echoAgent.appendSessionPending(echoSid, Maps.of(
				Fields.SESSION_ID, echoSidHex,
				Fields.MESSAGE, Maps.of("content", "msg-" + i)));
		}

		Job runJob = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "echo-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("echo-agent");
		assertEquals(AgentState.SLEEPING, agent.getStatus(), "Status should be sleeping after run");

		assertFalse(agent.hasSessionPending(), "Session pending should be empty after run");

		AVector<ACell> timeline = agent.getTimeline();
		assertNotNull(timeline, "Timeline should not be null");
		assertEquals(1, timeline.count(), "Timeline should have 1 entry");

		assertNull(agent.getError(), "Error should be null after successful run");
	}

	@Test
	public void testTriggerDefaultBlocksUntilComplete() {
		// Backward compat: no `wait` field → block until run loop finishes (status SLEEPING).
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "block-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User u = engine.getVenueState().users().get(ALICE_DID);
		AgentState blockAgent = u.agent("block-agent");
		Blob blockSid = Blob.fromHex("bbbb0001bbbb0001bbbb0001bbbb0001");
		blockAgent.ensureSession(blockSid, ALICE_DID);
		blockAgent.appendSessionPending(blockSid, Maps.of(
			Fields.SESSION_ID, Strings.create(blockSid.toHexString()),
			Fields.MESSAGE, Maps.of("content", "hi")));

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "block-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS),
			"Default wait should block until run loop completes");
	}

	@Test
	public void testTriggerWaitFalseReturnsImmediately() {
		// wait=false → return immediately with status RUNNING. Run loop still
		// executes in the background; caller observes it via agent:info.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "async-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User u2 = engine.getVenueState().users().get(ALICE_DID);
		AgentState asyncAgent = u2.agent("async-agent");
		Blob asyncSid = Blob.fromHex("cccc0001cccc0001cccc0001cccc0001");
		asyncAgent.ensureSession(asyncSid, ALICE_DID);
		asyncAgent.appendSessionPending(asyncSid, Maps.of(
			Fields.SESSION_ID, Strings.create(asyncSid.toHexString()),
			Fields.MESSAGE, Maps.of("content", "hi")));

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "async-agent", Fields.WAIT, CVMBool.FALSE),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.RUNNING, RT.getIn(result, Fields.STATUS),
			"wait=false should return RUNNING without blocking");
		assertEquals(Strings.create("async-agent"), RT.getIn(result, Fields.AGENT_ID));
	}

	@Test
	public void testTriggerWaitIntegerTimeout() {
		// wait=<ms> → block up to that many ms, return running if timed out.
		// Using test:echo which is effectively instant — result should be SLEEPING
		// because the run loop finishes well within the timeout.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "to-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User u3 = engine.getVenueState().users().get(ALICE_DID);
		AgentState toAgent = u3.agent("to-agent");
		Blob toSid = Blob.fromHex("dddd0001dddd0001dddd0001dddd0001");
		toAgent.ensureSession(toSid, ALICE_DID);
		toAgent.appendSessionPending(toSid, Maps.of(
			Fields.SESSION_ID, Strings.create(toSid.toHexString()),
			Fields.MESSAGE, Maps.of("content", "hi")));

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "to-agent", Fields.WAIT, CVMLong.create(5000)),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(6000);

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS),
			"wait=5000 should wait for fast run loop to finish");
	}

	@Test
	public void testTriggerNoWork() {
		// Trigger with no messages/tasks — transition still runs (may act proactively)
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "empty-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job runJob = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "empty-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		// Transition was invoked even with no work
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("empty-agent");
		assertEquals(1, agent.getTimeline().count(), "Transition should have run once");
	}

	@Test
	public void testTriggerWithoutOperationNamesRecovery() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "unconfigured-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		// Simulate a legacy/corrupt record whose config lacks the transition op.
		AgentState unconfigured = engine.getVenueState().users().get(ALICE_DID)
			.agent("unconfigured-agent");
		unconfigured.putRecord(unconfigured.getRecord().assoc(AgentState.KEY_CONFIG, Maps.empty()));

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "unconfigured-agent"),
			RequestContext.of(ALICE_DID));
		assertThrows(covia.exception.JobFailedException.class, () -> job.awaitResult(5000));
		assertTrue(job.getErrorMessage().contains("config.operation"), job.getErrorMessage());
		assertTrue(job.getErrorMessage().contains("agent:update"), job.getErrorMessage());
	}

	/**
	 * Legacy persisted RUNNING is not liveness. The API reports SLEEPING when
	 * no executor exists, and the next wake performs the one-way data migration
	 * before starting normally.
	 *
	 * <p>Deterministic: force the old record shape while no run is live, observe
	 * it through agent:info, then issue a normal trigger.</p>
	 */
	@Test
	public void testPhantomRunningRecovery() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "phantom-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("phantom-agent");

		// Force the phantom: status=RUNNING with no runningLoops entry
		agent.setStatus(AgentState.RUNNING);
		assertEquals(AgentState.RUNNING, agent.getStatus()); // raw legacy record
		assertEquals(AgentState.SLEEPING, observableStatus(agent),
			"persisted RUNNING without a live executor must not report liveness");

		// Trigger must recover — not fail with "Cannot start agent"
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "phantom-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result, "Trigger should tolerate stale persisted RUNNING");
		assertEquals(Status.COMPLETE, job.getStatus(),
			"Job should complete, not fail with 'Cannot start agent'");
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));
		assertEquals(AgentState.SLEEPING, agent.getStatus(),
			"Agent should be SLEEPING after the recovered run");
	}

	// ========== User isolation ==========

	@Test
	public void testUserIsolation() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "shared-name"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "shared-name"),
			RequestContext.of(BOB_DID)).awaitResult(5000);

		User alice = engine.getVenueState().users().get(ALICE_DID);
		User bob = engine.getVenueState().users().get(BOB_DID);

		AgentState aliceAgent = alice.agent("shared-name");
		AgentState bobAgent = bob.agent("shared-name");

		// Deliver directly rather than via agent:message: the op auto-wakes the
		// agent, whose run loop can drain session.pending before the assertion
		// reads it (a no-config agent defaults to llmagent:chat, which fails fast
		// with no LLM and may clear pending first). Direct delivery is the
		// deterministic path used elsewhere in this file.
		Blob aliceSid = Blob.fromHex("a1a1000000000000a1a1000000000000");
		Blob bobSid   = Blob.fromHex("b0b0000000000000b0b0000000000000");
		aliceAgent.ensureSession(aliceSid, ALICE_DID);
		aliceAgent.appendSessionPending(aliceSid, Maps.of(
			Fields.SESSION_ID, Strings.create(aliceSid.toHexString()),
			Fields.MESSAGE, Maps.of("from", "alice")));
		bobAgent.ensureSession(bobSid, BOB_DID);
		bobAgent.appendSessionPending(bobSid, Maps.of(
			Fields.SESSION_ID, Strings.create(bobSid.toHexString()),
			Fields.MESSAGE, Maps.of("from", "bob")));

		assertTrue(aliceAgent.hasSessionPending(), "Alice's agent should have pending message");
		assertTrue(bobAgent.hasSessionPending(), "Bob's agent should have pending message");
	}

	// ========== Default transition op from config ==========

	@Test
	public void testTriggerWithDefaultOperation() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "default-op-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Deliver directly to avoid auto-wake race
		User user0 = engine.getVenueState().users().get(ALICE_DID);
		AgentState defAgent = user0.agent("default-op-agent");
		Blob defSid = Blob.fromHex("eeee0001eeee0001eeee0001eeee0001");
		defAgent.ensureSession(defSid, ALICE_DID);
		defAgent.appendSessionPending(defSid, Maps.of(
			Fields.SESSION_ID, Strings.create(defSid.toHexString()),
			Fields.MESSAGE, Maps.of("content", "hello")));

		Job runJob = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "default-op-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("default-op-agent");
		assertFalse(agent.hasSessionPending(), "Session pending should be cleared");
		assertEquals(1, agent.getTimeline().count(), "Timeline should have 1 entry");
	}

	@Test
	public void testInfoOmitsEmptyStateConfig() {
		// New agents (non-definition path) have state=null — info should not
		// include a spurious empty stateConfig field. Regression for RT.ensureMap
		// returning Maps.empty() for null input.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "no-state-agent",
				Fields.CONFIG, Strings.create("v/agents/templates/reader")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell info = engine.jobs().invokeOperation(
			"v/ops/agent/info",
			Maps.of(Fields.AGENT_ID, "no-state-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		assertNotNull(info);
		// stateConfig should be absent entirely (not present as empty map)
		assertNull(RT.getIn(info, Strings.create("stateConfig")),
			"Template-created agent should have no stateConfig field in info output");
	}

	@Test
	public void testCreateAutoDefaults() {
		// Agent created with no config gets sensible defaults
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "auto-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("auto-agent");
		assertNotNull(agent);

		// Should have auto-set operation: llmagent:chat
		AMap<AString, ACell> config = agent.getConfig();
		assertNotNull(config);
		assertEquals(Strings.create("v/ops/llmagent/chat"), config.get(Fields.OPERATION),
			"Auto-default should set operation to llmagent:chat");
	}

	// ========== Result in run output ==========

	@Test
	public void testTriggerOutputIncludesResult() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "result-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/llm",
					"systemPrompt", "You are helpful."
				)
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Deliver directly to avoid auto-wake race
		User resultUser = engine.getVenueState().users().get(ALICE_DID);
		AgentState resultAgent = resultUser.agent("result-agent");
		Blob resultSid = Blob.fromHex("ffff0001ffff0001ffff0001ffff0001");
		resultAgent.ensureSession(resultSid, ALICE_DID);
		resultAgent.appendSessionPending(resultSid, Maps.of(
			Strings.intern("content"), Strings.create("hello")));

		Job runJob = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "result-agent"),
			RequestContext.of(ALICE_DID));
		runJob.awaitResult(5000);
		covia.venue.TestEngine.awaitTimelineCount(resultAgent, 1, 5000);

		// Trigger only kicks the cycle — read the response off the
		// timeline entry written by the run loop.
		ACell timelineEntry = resultAgent.getTimeline().get(0);
		AString response = RT.ensureString(RT.getIn(timelineEntry, Fields.RESULT));
		assertNotNull(response, "Timeline result should include the transition response");
		assertTrue(response.toString().length() > 0, "Response should not be empty");
	}

	// ========== agent:request ==========

	@Test
	public void testRequestCreatesTask() {
		// Create agent with default operation
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "task-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Submit a request with wait — blocks until agent completes the task
		Job requestJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "task-agent", Fields.INPUT, Maps.of("question", "What is 2+2?"),
				Fields.WAIT, CVMLong.create(5000)),
			RequestContext.of(ALICE_DID));

		ACell result = requestJob.awaitResult(5000);
		assertNotNull(result, "Request should be completed by the agent");

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("task-agent");

		// Task should be removed from tasks after completion
		assertEquals(0, agent.getTasks().count(), "Tasks should be empty after completion");
	}

	@Test
	public void testRequestTaskCompletion() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "completing-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Submit a request with wait=true (indefinite)
		Job requestJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "completing-agent", Fields.INPUT, Maps.of("data", "hello"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));

		// Wait for completion
		ACell result = requestJob.awaitResult(5000);
		assertNotNull(result);

		// Result is the task job data; its output should contain what test:taskcomplete returns
		ACell completed = RT.getIn(result, Fields.OUTPUT, "completed");
		assertNotNull(completed, "Task output should contain 'completed' from test:taskcomplete");
	}

	@Test
	public void testRequestAsync() {
		// Use v/test/ops/never as the transition so the agent starts but
		// never completes. This makes the async-submit assertion
		// deterministic — the job genuinely cannot finish, so isFinished()
		// is reliably false regardless of timing.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "async-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Submit request — should return immediately with a non-finished Job.
		// Behavioural guarantee: agent_request is non-blocking; the caller
		// can poll or awaitResult(timeout) on the returned Job.
		Job requestJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "async-agent", Fields.INPUT, Maps.of("data", "async")),
			RequestContext.of(ALICE_DID));

		// Job must not be finished — the agent's transition is "never"
		assertFalse(requestJob.isFinished(),
			"Job should not be finished when the agent transition never completes");

		// A short caller-side wait should time out without failing the durable job.
		try {
			requestJob.awaitResult(100);
			fail("awaitResult should throw when the job cannot complete within the timeout");
		} catch (covia.exception.JobPollingFailedException e) {
			assertEquals(Status.STARTED, requestJob.getStatus(),
				"timing out locally must not fail the long-running request");
		}
	}

	@Test
	public void testRequestToNonExistentAgent() {
		Job requestJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "ghost-agent", Fields.INPUT, Maps.of("q", "hello")),
			RequestContext.of(ALICE_DID));

		try {
			requestJob.awaitResult(5000);
			fail("Should fail for non-existent agent");
		} catch (Exception e) {
			assertEquals(Status.FAILED, requestJob.getStatus());
		}
	}

	@Test
	public void testRequestToTerminatedAgent() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "dead-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		user.agent("dead-agent").setStatus(AgentState.TERMINATED);

		Job requestJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "dead-agent", Fields.INPUT, Maps.of("q", "hello")),
			RequestContext.of(ALICE_DID));

		try {
			requestJob.awaitResult(5000);
			fail("Should fail for terminated agent");
		} catch (Exception e) {
			assertEquals(Status.FAILED, requestJob.getStatus());
		}
	}

	// ========== #201 / #202 — suspended / deleted agents must not hang callers ==========

	/**
	 * A request to a SUSPENDED agent must fail fast with the suspension cause
	 * and the remedy in the message — not sit STARTED indefinitely (#201).
	 */
	@Test
	public void testRequestToSuspendedAgentFailsFast() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "susp-req"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.getVenueState().users().get(ALICE_DID)
			.agent("susp-req").suspend(Strings.create("simulated LLM outage"));

		Job requestJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "susp-req", Fields.INPUT, Maps.of("q", "hello")),
			RequestContext.of(ALICE_DID));

		try {
			requestJob.awaitResult(5000);
			fail("Request to a suspended agent must fail fast");
		} catch (covia.exception.JobFailedException expected) {}
		assertEquals(Status.FAILED, requestJob.getStatus(),
			"request to a suspended agent must FAIL, not hang STARTED");
		String err = requestJob.getErrorMessage();
		assertTrue(err.contains("suspended"), "error must name the state: " + err);
		assertTrue(err.contains("simulated LLM outage"),
			"error must carry the suspension cause: " + err);
		assertTrue(err.contains("agent:resume"), "error must name the remedy: " + err);
	}

	/** Chat counterpart of {@link #testRequestToSuspendedAgentFailsFast} (#201). */
	@Test
	public void testChatToSuspendedAgentFailsFast() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "susp-chat"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.getVenueState().users().get(ALICE_DID)
			.agent("susp-chat").suspend(Strings.create("simulated LLM outage"));

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "susp-chat", Fields.MESSAGE, Strings.create("hi")),
			RequestContext.of(ALICE_DID));

		try {
			chatJob.awaitResult(5000);
			fail("Chat to a suspended agent must fail fast");
		} catch (covia.exception.JobFailedException expected) {}
		assertEquals(Status.FAILED, chatJob.getStatus());
		String err = chatJob.getErrorMessage();
		assertTrue(err.contains("suspended") && err.contains("simulated LLM outage"),
			"error must name the state and cause: " + err);
	}

	/**
	 * agent:update on a SLEEPING agent must leave it runnable — regression
	 * guard for the "silently left SUSPENDED after a config update" report
	 * (#201).
	 */
	@Test
	public void testUpdateKeepsAgentRunnable() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "upd-run",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		engine.jobs().invokeOperation(
			"v/ops/agent/update",
			Maps.of(Fields.AGENT_ID, "upd-run",
				Fields.CONFIG, Maps.of(Strings.create("note"), Strings.create("updated"))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		AgentState agent = engine.getVenueState().users().get(ALICE_DID).agent("upd-run");
		assertEquals(AgentState.SLEEPING, agent.getStatus(),
			"a config update must not change a SLEEPING agent's status");

		// And it still processes work after the update
		Job requestJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "upd-run",
				Fields.INPUT, Maps.of("data", "post-update"),
				Fields.WAIT, CVMLong.create(5000)),
			RequestContext.of(ALICE_DID));
		requestJob.awaitResult(5000);
		assertEquals(Status.COMPLETE, requestJob.getStatus());
	}

	/**
	 * agent:delete must cancel an in-flight task and fail its caller's Job —
	 * a slow task must not wedge the agent slot until a venue restart (#202).
	 * The same agentId is then recreated and must process new work.
	 */
	@Test
	public void testDeleteCancelsInFlightTaskAndUnblocksRecreate() throws Exception {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "del-wedge",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job stuck = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "del-wedge", Fields.INPUT, Maps.of("data", "slow")),
			RequestContext.of(ALICE_DID));

		// Wait until the live executor reports the never-completing transition.
		AgentState agent = engine.getVenueState().users().get(ALICE_DID).agent("del-wedge");
		TestEngine.awaitCondition(() -> AgentState.RUNNING.equals(observableStatus(agent)), 5000,
			() -> "agent did not enter RUNNING (status=" + observableStatus(agent) + ")");
		assertEquals(AgentState.RUNNING, observableStatus(agent),
			"agent should be blocked in the never-completing transition");
		assertEquals(AgentState.RUNNING, agent.getStatus(),
			"RUNNING is persisted for lattice observers while the executor is live");

		engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "del-wedge", Fields.REMOVE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// The stuck caller is notified, not left on an indefinitely-STARTED Job
		try {
			stuck.awaitResult(5000);
			fail("The in-flight request must fail when the agent is deleted");
		} catch (covia.exception.JobFailedException expected) {}
		assertEquals(Status.FAILED, stuck.getStatus());
		assertTrue(stuck.getErrorMessage().contains("deleted"),
			"error should say the agent was deleted: " + stuck.getErrorMessage());

		// Recreate the same agentId with a completing transition — the slot
		// must be usable without a venue restart
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "del-wedge",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job fresh = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "del-wedge",
				Fields.INPUT, Maps.of("data", "post-recreate"),
				Fields.WAIT, CVMLong.create(10000)),
			RequestContext.of(ALICE_DID));
		fresh.awaitResult(10000);
		assertEquals(Status.COMPLETE, fresh.getStatus(),
			"recreated agent must process new work after deleting a wedged one");
	}

	// ========== #211 — tool failures recorded + visible in agent:context ==========

	/**
	 * A denied tool call must stay observable after the cycle: in the cycle
	 * record on the timeline entry (the call's {@code isError}), once as the
	 * provider-shaped tool result in the session conversation, and in
	 * {@code agent:context} when inspected with the sessionId. It must not also be copied into a synthetic
	 * system turn (#290).
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void testToolFailureRecordedAndVisibleInContext() {
		// toolllm calls v/test/ops/echo once, then reports the tool result as
		// text. The scope's invoke grant does not cover echo → denial.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "tool-fail-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					Strings.create("llmOperation"), Strings.create("v/test/ops/toolllm"),
					Strings.create("caps"), Vectors.of(Maps.of(
						"with", Strings.create("v/ops/schema"),
						"can", Strings.create("invoke"))))
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "tool-fail-agent",
				Fields.MESSAGE, Strings.create("use your tool")),
			RequestContext.of(ALICE_DID));
		ACell chatResult = chatJob.awaitResult(10000);
		AString sidHex = RT.ensureString(RT.getIn(chatResult, Fields.SESSION_ID));
		assertNotNull(sidHex, "chat result must carry the sessionId");

		AgentState agent = engine.getVenueState().users().get(ALICE_DID).agent("tool-fail-agent");

		// 1. The cycle record carries the failed call: isError, the denial as its result
		ACell timelineEntry = agent.getTimeline().get(agent.getTimeline().count() - 1);
		AVector<ACell> inferences = RT.ensureVector(RT.getIn(timelineEntry, Fields.INFERENCES));
		assertNotNull(inferences, "timeline entry must record the cycle's inferences");
		ACell denied = RT.ensureVector(RT.getIn(inferences.get(0), Fields.CALLS)).get(0);
		assertEquals(CVMBool.TRUE, RT.getIn(denied, "isError"), "the call is recorded as failed: " + denied);
		assertTrue(RT.getIn(denied, Fields.RESULT).toString().contains("Capability denied"),
			"the recorded failure must carry the denial: " + denied);
		// 2. Session conversation contains exactly one tool failure turn: the
		// provider-shaped result needed to keep the conversation valid.
		AMap<AString, ACell> session = agent.getSession(Blob.fromHex(sidHex.toString()));
		AVector<ACell> frames = RT.ensureVector(RT.getIn(session, Fields.FRAMES));
		assertNotNull(frames, "session must carry frames");
		AVector<ACell> conversation = RT.ensureVector(RT.getIn(frames.get(0), "conversation"));
		int failureTurns = 0;
		for (long i = 0; i < conversation.count(); i++) {
			ACell turn = conversation.get(i);
			if (AgentState.SOURCE_TOOL.equals(RT.getIn(turn, "source"))) {
				String content = String.valueOf(RT.getIn(turn, "content"));
				assertTrue(content.contains("Capability denied"),
					"the failure turn must carry the denial: " + content);
				assertEquals(Strings.intern("tool"), RT.getIn(turn, "role"),
					"tool failures must not be duplicated as synthetic system turns");
				failureTurns++;
			}
		}
		assertEquals(1, failureTurns,
			"conversation must retain one provider-shaped tool failure result");

		// 3. agent:context with the sessionId renders the provider-shaped failure.
		Job contextJob = engine.jobs().invokeOperation(
			"v/ops/agent/context",
			Maps.of(Fields.AGENT_ID, "tool-fail-agent",
				Fields.SESSION_ID, sidHex),
			RequestContext.of(ALICE_DID));
		String rendered = String.valueOf(contextJob.awaitResult(5000));
		assertTrue(rendered.contains("Capability denied"),
			"agent:context with sessionId must surface the failure turn: " + rendered);

		// Without a sessionId the synthetic fresh-transition render is
		// unchanged (no session data, no failure turn).
		Job freshContext = engine.jobs().invokeOperation(
			"v/ops/agent/context",
			Maps.of(Fields.AGENT_ID, "tool-fail-agent"),
			RequestContext.of(ALICE_DID));
		String freshRendered = String.valueOf(freshContext.awaitResult(5000));
		assertFalse(freshRendered.contains("Error: Capability denied"),
			"the sessionless render must stay the synthetic fresh context");
	}

	@Test
	public void testRequestTimelineIncludesTaskResults() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "timeline-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job requestJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "timeline-agent", Fields.INPUT, Maps.of("task", "audit"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		requestJob.awaitResult(5000);

		// Check the timeline
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("timeline-agent");
		AVector<ACell> timeline = agent.getTimeline();
		assertNotNull(timeline, "Timeline should exist");
		assertEquals(1, timeline.count(), "Should have one timeline entry");

		// Timeline entry should contain taskResults
		ACell entry = timeline.get(0);
		ACell taskResults = RT.getIn(entry, Fields.TASK_RESULTS);
		assertNotNull(taskResults, "Timeline entry should include taskResults");
	}

	@Test
	public void testMultipleRequestsProcessed() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "multi-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Submit two requests with wait
		Job req1 = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "multi-agent", Fields.INPUT, Maps.of("n", "1"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));

		Job req2 = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "multi-agent", Fields.INPUT, Maps.of("n", "2"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));

		// Both should complete eventually
		req1.awaitResult(5000);
		req2.awaitResult(5000);

		assertTrue(req1.isComplete(), "Request 1 should be complete");
		assertTrue(req2.isComplete(), "Request 2 should be complete");

		// All tasks should be cleared
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("multi-agent");
		assertEquals(0, agent.getTasks().count(), "All tasks should be cleared");
	}

	// ========== run loop — one task per cycle (Sub-stage 2.2) ==========

	/**
	 * Single-task case: the transition should receive a one-element tasks vector,
	 * recorded on the timeline entry.
	 */
	@SuppressWarnings("unchecked")
	@Test
	public void testSingleTaskTimelineHasOneTask() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "single-task-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job req = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "single-task-agent", Fields.INPUT, Maps.of("q", "one"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		req.awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("single-task-agent");
		AVector<ACell> timeline = agent.getTimeline();
		assertEquals(1, timeline.count(), "Should produce exactly one cycle");

		AVector<ACell> tasksOnEntry = (AVector<ACell>) RT.getIn(timeline.get(0), Fields.TASKS);
		assertNotNull(tasksOnEntry, "Timeline entry should record the picked task");
		assertEquals(1, tasksOnEntry.count(), "Cycle should pick exactly one task");
	}

	/**
	 * Multi-task case: the run loop should fan tasks out across cycles.
	 * Two queued tasks → two timeline entries, each with a one-element tasks vector.
	 */
	@SuppressWarnings("unchecked")
	@Test
	public void testMultiTaskFansOutAcrossCycles() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "fanout-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job req1 = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "fanout-agent", Fields.INPUT, Maps.of("n", "1"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		Job req2 = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "fanout-agent", Fields.INPUT, Maps.of("n", "2"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));

		req1.awaitResult(5000);
		req2.awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("fanout-agent");
		AVector<ACell> timeline = agent.getTimeline();

		// Each cycle that picks a task records a tasks vector. Cycles with no
		// task picked (e.g., a final inbox-only or wake-only cycle) omit it.
		long cyclesThatPickedATask = 0;
		for (long i = 0; i < timeline.count(); i++) {
			AVector<ACell> picked = (AVector<ACell>) RT.getIn(timeline.get(i), Fields.TASKS);
			if (picked == null || picked.count() == 0) continue;
			assertEquals(1, picked.count(),
				"Each cycle must pick at most one task — cycle " + i + " picked " + picked.count());
			cyclesThatPickedATask++;
		}
		assertEquals(2, cyclesThatPickedATask,
			"Two queued tasks must fan out across exactly two cycles");
		assertEquals(0, agent.getTasks().count(), "All tasks should be cleared");
	}

	// ========== lean transition contract (Sub-stage 3) ==========

	/**
	 * Lean transition returns {response, taskComplete}. Framework must
	 * synthesize a taskResults entry for the picked task so the calling Job
	 * receives the response as its output. Uses the in-suite taskcomplete op
	 * which is now itself written against the lean contract — this test
	 * verifies the full round trip including framework translation.
	 */
	@Test
	public void testLeanTransitionCompletesTask() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "lean-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell userPayload = Maps.of("q", "lean-please");
		Job req = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "lean-agent", Fields.INPUT, userPayload,
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		ACell envelope = req.awaitResult(5000);

		ACell output = RT.getIn(envelope, Fields.OUTPUT);
		assertNotNull(output, "Lean transition must produce output via framework synthesis");
		ACell completed = RT.getIn(output, Strings.create("completed"));
		assertEquals(userPayload, completed,
			"Lean transition's response.completed should echo newInput");
		assertEquals(Status.COMPLETE, RT.ensureString(RT.getIn(envelope, Fields.STATUS)));
	}

	// ========== Sub-stage 2.7c — agent:complete-task / agent:fail-task contract ==========

	/**
	 * Direct invocation of {@code agent:complete-task} without an enclosing
	 * cycle context (no agentId/taskId in RequestContext) must fail —
	 * callers cannot complete arbitrary tasks; the op only accepts the task
	 * the framework currently has in scope.
	 */
	@Test
	public void testCompleteTaskRejectsUnscopedCall() {
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/complete-task",
			Maps.of(Fields.RESULT, Strings.create("nope")),
			RequestContext.of(ALICE_DID));
		assertThrows(covia.exception.JobFailedException.class, () -> job.awaitResult(2000),
			"complete-task must reject calls without (agentId, taskId) scope");
		assertEquals(Status.FAILED, job.getStatus());
	}

	@Test
	public void testFailTaskRejectsUnscopedCall() {
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/fail-task",
			Maps.of(Fields.ERROR, Strings.create("oops")),
			RequestContext.of(ALICE_DID));
		assertThrows(covia.exception.JobFailedException.class, () -> job.awaitResult(2000),
			"fail-task must reject calls without (agentId, taskId) scope");
		assertEquals(Status.FAILED, job.getStatus());
	}

	/**
	 * Ordering invariant: a caller's {@code awaitResult} must observe the
	 * cycle's timeline write before returning. The venue op parks a deferred
	 * completion; the framework drains it after {@code mergeRunResult}, so
	 * the timeline is durable by the time the pending Job completes.
	 *
	 * <p>If this regresses, completion would race with the merge and the
	 * caller could see an empty timeline immediately after a successful
	 * task return — exactly the bug fixed in S2.7c‑2.</p>
	 */
	@SuppressWarnings("unchecked")
	@Test
	public void testCallerSeesTimelineAfterAwait() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "ordering-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job req = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "ordering-agent",
				Fields.INPUT, Maps.of("q", "ordering"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		req.awaitResult(5000);

		// awaitResult returned — timeline MUST be visible at this point.
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("ordering-agent");
		AVector<ACell> timeline = agent.getTimeline();
		assertNotNull(timeline);
		assertEquals(1, timeline.count(),
			"Timeline entry must be persisted before awaitResult returns");
		ACell taskResults = RT.getIn(timeline.get(0), Fields.TASK_RESULTS);
		assertNotNull(taskResults, "Cycle must record taskResults from the deferred completion");
	}

	/**
	 * Envelope shape: a successful task completion produces a Job envelope
	 * with id/status/output, and the agent's task Index is empty afterward.
	 * Verifies the venue op cleans up state and the framework forwards the
	 * envelope unchanged to the caller.
	 */
	@Test
	public void testCompleteTaskEnvelopeShape() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "envelope-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job req = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "envelope-agent",
				Fields.INPUT, Maps.of("q", "envelope"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		ACell envelope = req.awaitResult(5000);

		assertEquals(Status.COMPLETE, RT.ensureString(RT.getIn(envelope, Fields.STATUS)));
		assertNotNull(RT.getIn(envelope, Fields.ID), "Envelope must carry the task/job id");
		assertNotNull(RT.getIn(envelope, Fields.OUTPUT), "Envelope must carry the task output");

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("envelope-agent");
		assertEquals(0, agent.getTasks().count(),
			"Venue op must remove the task entry from the agent's Index");
	}

	// ========== issue #71 — structural outputPath handoff ==========

	@Test
	public void testRequestWithoutOutputPathRetainsDirectOutput() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "direct-output-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell payload = Maps.of("chapter", "direct");
		ACell envelope = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "direct-output-agent", Fields.INPUT, payload),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		assertEquals(Maps.of("completed", payload), RT.getIn(envelope, Fields.OUTPUT));
		assertNull(RT.getIn(envelope, Fields.OUTPUT_PATH));
		assertNull(RT.getIn(envelope, Fields.BYTES));
	}

	@Test
	public void testOutputPathWritesResultAndReturnsReceipt() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "handoff-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell payload = Maps.of("chapter", "lossless", "lines", Vectors.of("a", "b"));
		ACell expected = Maps.of("completed", payload);
		AString path = Strings.create("w/pipeline/run-71/stage-1");
		ACell receipt = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "handoff-agent", Fields.INPUT, payload,
				Fields.OUTPUT_PATH, path),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		assertEquals(Status.COMPLETE, RT.getIn(receipt, Fields.STATUS));
		assertEquals(path, RT.getIn(receipt, Fields.OUTPUT_PATH));
		assertEquals(CVMLong.create(Cells.storageSize(expected)), RT.getIn(receipt, Fields.BYTES));
		assertNull(RT.getIn(receipt, Fields.OUTPUT),
			"Receipt must not put the worker payload back in manager context");

		ACell read = engine.jobs().invokeOperation(
			"v/ops/covia/read", Maps.of(Fields.PATH, path),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(read, "exists"));
		assertEquals(expected, RT.getIn(read, Fields.VALUE),
			"Stored handoff must be byte-identical to the worker result");
	}

	@Test
	public void testThreeStageOutputPathPipelinePassesOnlyReceipts() {
		RequestContext owner = RequestContext.of(ALICE_DID);
		for (String id : new String[] {"pipeline-stage-1", "pipeline-stage-2", "pipeline-stage-3"}) {
			engine.jobs().invokeOperation(
				"v/ops/agent/create",
				Maps.of(Fields.AGENT_ID, id,
					Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
				owner).awaitResult(5000);
		}

		AString p1 = Strings.create("w/pipeline/run-71/three-stage/1");
		AString p2 = Strings.create("w/pipeline/run-71/three-stage/2");
		AString p3 = Strings.create("w/pipeline/run-71/three-stage/3");
		ACell seed = Maps.of("text", "verbatim \u2603 payload", "items", Vectors.of(1, 2, 3));

		ACell r1 = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "pipeline-stage-1", Fields.INPUT, seed,
				Fields.OUTPUT_PATH, p1), owner).awaitResult(5000);
		ACell r2 = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "pipeline-stage-2",
				Fields.INPUT, Maps.of("readPath", p1),
				Fields.OUTPUT_PATH, p2), owner).awaitResult(5000);
		ACell r3 = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "pipeline-stage-3",
				Fields.INPUT, Maps.of("readPath", p2),
				Fields.OUTPUT_PATH, p3), owner).awaitResult(5000);

		for (ACell receipt : new ACell[] {r1, r2, r3}) {
			assertNull(RT.getIn(receipt, Fields.OUTPUT),
				"Every manager-visible stage result must be a receipt only");
			assertNotNull(RT.getIn(receipt, Fields.OUTPUT_PATH));
		}

		ACell stage1 = RT.getIn(engine.jobs().invokeOperation(
			"v/ops/covia/read", Maps.of(Fields.PATH, p1), owner).awaitResult(5000), Fields.VALUE);
		ACell stage2 = RT.getIn(engine.jobs().invokeOperation(
			"v/ops/covia/read", Maps.of(Fields.PATH, p2), owner).awaitResult(5000), Fields.VALUE);
		ACell stage3 = RT.getIn(engine.jobs().invokeOperation(
			"v/ops/covia/read", Maps.of(Fields.PATH, p3), owner).awaitResult(5000), Fields.VALUE);
		assertEquals(Maps.of("completed", seed), stage1);
		assertEquals(Maps.of("completed", stage1), stage2,
			"Stage 2 must observe stage 1's exact stored cell");
		assertEquals(Maps.of("completed", stage2), stage3,
			"Stage 3 must observe stage 2's exact stored cell");
	}

	@Test
	public void testOutputPathDeferredCompletionPollsToReceipt() throws Exception {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "deferred-handoff-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell payload = Maps.of("stage", 2, Fields.DELAY, 150);
		AString path = Strings.create("w/pipeline/run-71/deferred");
		ACell snapshot = engine.jobs().invokeInternal(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "deferred-handoff-agent",
				Fields.INPUT, payload,
				Fields.OUTPUT_PATH, path,
				Fields.TIMEOUT, 0),
			RequestContext.of(ALICE_DID)).get(5000, java.util.concurrent.TimeUnit.MILLISECONDS);

		AString snapshotStatus = RT.ensureString(RT.getIn(snapshot, Fields.STATUS));
		assertTrue(Status.PENDING.equals(snapshotStatus) || Status.STARTED.equals(snapshotStatus),
			"accepted request is queued or already picked: " + snapshot);
		Blob taskId = Job.parseID(RT.getIn(snapshot, Fields.ID));
		assertNotNull(taskId);
		Job task = engine.jobs().getJob(taskId);
		ACell receipt = task.awaitResult(5000);
		assertEquals(path, RT.getIn(receipt, Fields.OUTPUT_PATH));
		assertNull(RT.getIn(receipt, Fields.OUTPUT));

		ACell read = engine.jobs().invokeOperation(
			"v/ops/covia/read", Maps.of(Fields.PATH, path),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		assertEquals(Maps.of("completed", payload), RT.getIn(read, Fields.VALUE));
	}

	@Test
	public void testOutputPathUsesRequestersExecutionScope() {
		Job parent = engine.jobs().invokeOperation(
			"v/test/ops/never", Maps.empty(), RequestContext.of(ALICE_DID));
		RequestContext scoped = RequestContext.of(ALICE_DID).withJobId(parent.getID());
		try {
			engine.jobs().invokeOperation(
				"v/ops/agent/create",
				Maps.of(Fields.AGENT_ID, "scoped-handoff-agent",
					Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
				RequestContext.of(ALICE_DID)).awaitResult(5000);

			ACell payload = Maps.of("scope", "parent-job");
			ACell receipt = engine.jobs().invokeOperation(
				"v/ops/agent/request",
				Maps.of(Fields.AGENT_ID, "scoped-handoff-agent",
					Fields.INPUT, payload,
					Fields.OUTPUT_PATH, "t/handoff"),
				scoped).awaitResult(5000);
			assertEquals(Strings.create("t/handoff"), RT.getIn(receipt, Fields.OUTPUT_PATH));

			ACell read = engine.jobs().invokeOperation(
				"v/ops/covia/read", Maps.of(Fields.PATH, "t/handoff"), scoped)
				.awaitResult(5000);
			assertEquals(Maps.of("completed", payload), RT.getIn(read, Fields.VALUE));
		} finally {
			parent.cancel();
		}
	}

	@Test
	public void testOutputPathDeniedByCapturedManagerCapsWritesNothing() {
		String agentId = "capped-handoff-agent";
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		RequestContext capped = RequestContext.of(ALICE_DID).withCaps(Vectors.of(
			Capability.create(Strings.create("v/ops/agent/request"), Strings.create("invoke")),
			Capability.create(Strings.create("g/" + agentId), Abilities.AGENT_REQUEST)));
		AString path = Strings.create("w/pipeline/run-71/denied");
		Job request = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, agentId,
				Fields.INPUT, Maps.of("q", "denied"),
				Fields.OUTPUT_PATH, path),
			capped);

		assertThrows(covia.exception.JobFailedException.class, () -> request.awaitResult(5000));
		assertTrue(request.getErrorMessage().contains("crud/write"),
			"Failure should identify the missing destination authority");

		ACell read = engine.jobs().invokeOperation(
			"v/ops/covia/read", Maps.of(Fields.PATH, path),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(read, "exists"));
	}

	@Test
	public void testHandoffConsumerNeedsReadAuthority() {
		AString path = Strings.create("w/pipeline/run-71/consumer-cap");
		ACell value = Maps.of("exact", Vectors.of("one", "two"));
		engine.jobs().invokeOperation(
			"v/ops/covia/write", Maps.of(Fields.PATH, path, Fields.VALUE, value),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell invokeRead = Capability.create(
			Strings.create("v/ops/covia/read"), Strings.create("invoke"));
		RequestContext denied = RequestContext.of(ALICE_DID).withCaps(Vectors.of(
			invokeRead,
			Capability.create(Strings.create("w/elsewhere"), Strings.create("crud/read"))));
		Job deniedRead = engine.jobs().invokeOperation(
			"v/ops/covia/read", Maps.of(Fields.PATH, path), denied);
		assertThrows(covia.exception.JobFailedException.class, () -> deniedRead.awaitResult(5000));

		RequestContext allowed = RequestContext.of(ALICE_DID).withCaps(Vectors.of(
			invokeRead,
			Capability.create(path, Strings.create("crud/read"))));
		ACell read = engine.jobs().invokeOperation(
			"v/ops/covia/read", Maps.of(Fields.PATH, path), allowed).awaitResult(5000);
		assertEquals(value, RT.getIn(read, Fields.VALUE));
	}

	@Test
	public void testOutputPathFailureWritesNothing() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "failed-handoff-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/error")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		AString path = Strings.create("w/pipeline/run-71/failed");
		Job request = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "failed-handoff-agent",
				Fields.INPUT, Maps.of("q", "fail"),
				Fields.OUTPUT_PATH, path),
			RequestContext.of(ALICE_DID));
		assertThrows(covia.exception.JobFailedException.class, () -> request.awaitResult(5000));

		ACell read = engine.jobs().invokeOperation(
			"v/ops/covia/read", Maps.of(Fields.PATH, path),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(read, "exists"));
	}

	@Test
	public void testOutputPathCancellationWritesNothing() {
		String agentId = "cancelled-handoff-agent";
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		AString path = Strings.create("w/pipeline/run-71/cancelled");
		Job request = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, agentId,
				Fields.INPUT, Maps.of("q", "cancel"),
				Fields.OUTPUT_PATH, path),
			RequestContext.of(ALICE_DID));

		engine.jobs().invokeOperation(
			"v/ops/agent/cancel-task",
			Maps.of(Fields.AGENT_ID, agentId,
				Fields.TASK_ID, request.getID().toHexString()),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		assertEquals(Status.CANCELLED, request.getStatus());

		ACell read = engine.jobs().invokeOperation(
			"v/ops/covia/read", Maps.of(Fields.PATH, path),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(read, "exists"));

		engine.jobs().invokeOperation(
			"v/ops/agent/suspend", Maps.of(Fields.AGENT_ID, agentId),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
	}

	// ========== agent:request — sync/async consistency ==========

	@Test
	public void testRequestSyncViaAwaitResult() {
		// The standard sync pattern: invokeOperation + awaitResult.
		// No 'wait' param needed — the Job lifecycle handles blocking.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "sync-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "sync-agent", Fields.INPUT, Maps.of("q", "test")),
			RequestContext.of(ALICE_DID));

		// The sync pattern: await the task Job and use its result. The request
		// returns a Job the agent completes asynchronously, so awaitResult is the
		// synchronisation point — it returns only once the job is COMPLETE,
		// regardless of how quickly the agent ran. Asserting an intermediate
		// "not finished" state here would be a timing assumption.
		ACell result = job.awaitResult(5000);
		assertNotNull(result);
		assertTrue(job.isComplete());

		// Output is the task result
		ACell output = RT.getIn(result, Fields.OUTPUT);
		assertNotNull(output, "Sync result should contain task output");
	}

	@Test
	public void testRequestAsyncViaPoll() {
		// The standard async pattern: invokeOperation, return immediately, poll later.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "poll-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "poll-agent", Fields.INPUT, Maps.of("q", "poll")),
			RequestContext.of(ALICE_DID));

		// The async pattern: the request returns immediately with a pollable task
		// Job. The agent runs asynchronously, so the job is legitimately either
		// PENDING (queued), STARTED (picked), or already COMPLETE — a caller must
		// handle all three rather than assume one. Assert only that we got a pollable
		// job in a valid state.
		AString status = job.getStatus();
		assertTrue(Status.PENDING.equals(status) || Status.STARTED.equals(status)
				|| Status.COMPLETE.equals(status),
			"Async request should return a queued, started or complete job, was: " + status);

		// Poll to completion, then read the output — the normal async retrieval path.
		job.awaitResult(5000);
		assertTrue(job.isComplete(), "Job should be complete after agent processes the task");

		// Can retrieve the result via getOutput after polling
		ACell output = job.getOutput();
		assertNotNull(output, "Polling should retrieve the output");
	}

	// ========== issue #57: async + poll delegation (tool-loop path) ==========

	/**
	 * agent:request via invokeInternal (LLM tool-loop path) — helper completes
	 * fast, result returned inline before the 5s default timeout elapses.
	 */
	@Test
	public void testRequestViaToolLoopReturnsResultWhenFast() throws Exception {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "fast-helper",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell result = engine.jobs().invokeInternal(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "fast-helper", Fields.INPUT, Maps.of("q", "fast")),
			RequestContext.of(ALICE_DID)).get(5000, java.util.concurrent.TimeUnit.MILLISECONDS);

		assertNotNull(result, "Fast helper should return a full result inline");
		// Full result is the taskcomplete adapter's echoed task output — not a STARTED snapshot
		assertNotEquals(Status.STARTED, RT.getIn(result, Fields.STATUS),
			"Fast completion should not surface as a STARTED snapshot");
	}

	/**
	 * agent:request via invokeInternal with short timeout against a helper that
	 * never completes — returns a STARTED snapshot (not error) so the LLM can
	 * poll via grid:jobResult.
	 */
	@Test
	public void testRequestViaToolLoopReturnsSnapshotOnTimeout() throws Exception {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "slow-helper",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell result = engine.jobs().invokeInternal(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "slow-helper",
				Fields.INPUT, Maps.of("q", "slow"),
				Fields.TIMEOUT, CVMLong.create(100)),
			RequestContext.of(ALICE_DID)).get(5000, java.util.concurrent.TimeUnit.MILLISECONDS);

		assertNotNull(result, "Should return a snapshot on timeout, not error");
		AString id = RT.ensureString(RT.getIn(result, Fields.ID));
		assertNotNull(id, "Snapshot should carry the task Job id for polling");
		assertEquals(Strings.create("slow-helper"), RT.getIn(result, Fields.AGENT_ID));
		assertEquals(Status.STARTED, RT.getIn(result, Fields.STATUS),
			"Timed-out delegation should expose STARTED status for polling");
	}

	/**
	 * agent:request with timeout=0 — pure async: snapshot returned immediately
	 * without waiting on the task.
	 */
	@Test
	public void testRequestViaToolLoopAsyncZeroTimeout() throws Exception {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "async-helper",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		long start = System.nanoTime();
		ACell result = engine.jobs().invokeInternal(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "async-helper",
				Fields.INPUT, Maps.of("q", "async"),
				Fields.TIMEOUT, CVMLong.create(0)),
			RequestContext.of(ALICE_DID)).get(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
		long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

		assertTrue(elapsedMs < 500, "timeout=0 should return immediately, took " + elapsedMs + "ms");
		AString status = RT.ensureString(RT.getIn(result, Fields.STATUS));
		assertTrue(Status.PENDING.equals(status) || Status.STARTED.equals(status),
			"accepted request is queued or already picked: " + result);
		assertNotNull(RT.getIn(result, Fields.ID), "Async response must carry Job id");
	}

	/**
	 * grid:jobResult with timeout on a never-completing task — the tool errors
	 * rather than returning a STARTED snapshot. Distinct semantics from
	 * agent:request: callers of grid:jobResult want a result.
	 */
	@Test
	public void testGridJobResultTimesOutWithError() throws Exception {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "grid-helper",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Submit async via agent:request (timeout=0 → snapshot immediately)
		ACell snap = engine.jobs().invokeInternal(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "grid-helper",
				Fields.INPUT, Maps.of("q", "x"),
				Fields.TIMEOUT, CVMLong.create(0)),
			RequestContext.of(ALICE_DID)).get(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
		AString taskId = RT.ensureString(RT.getIn(snap, Fields.ID));
		assertNotNull(taskId);

		// Poll via grid:jobResult with a short timeout — must error, not return snapshot
		java.util.concurrent.CompletableFuture<ACell> f = engine.jobs().invokeInternal(
			"v/ops/grid/job-result",
			Maps.of(Fields.ID, taskId, Fields.TIMEOUT, CVMLong.create(100)),
			RequestContext.of(ALICE_DID));

		try {
			f.get(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
			fail("grid:jobResult should error on timeout when caller wanted a result");
		} catch (java.util.concurrent.ExecutionException e) {
			Throwable cause = e.getCause();
			assertTrue(cause instanceof java.util.concurrent.TimeoutException
					|| (cause != null && cause.getMessage() != null && cause.getMessage().contains("timed out")),
				"Expected TimeoutException, got: " + cause);
		}
	}

	/**
	 * Full async+poll delegation pattern (issue #57 fix). Caller submits a
	 * request with a short timeout, gets a STARTED snapshot, then waits on
	 * the task via grid:jobResult and receives the agent's final result.
	 */
	@Test
	public void testAsyncDelegationPattern() throws Exception {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "delegate-helper",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Step 1: submit with timeout=0 to force async return
		ACell snap = engine.jobs().invokeInternal(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "delegate-helper",
				Fields.INPUT, Maps.of("task", "delegate"),
				Fields.TIMEOUT, CVMLong.create(0)),
			RequestContext.of(ALICE_DID)).get(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
		AString taskId = RT.ensureString(RT.getIn(snap, Fields.ID));
		assertNotNull(taskId, "Async submit returns a task id");

		// Step 2: poll via grid:jobResult with generous timeout
		ACell result = engine.jobs().invokeInternal(
			"v/ops/grid/job-result",
			Maps.of(Fields.ID, taskId, Fields.TIMEOUT, CVMLong.create(5000)),
			RequestContext.of(ALICE_DID)).get(10_000, java.util.concurrent.TimeUnit.MILLISECONDS);

		assertNotNull(result, "Poll should retrieve the delegated task's result");
	}

	// ========== session minting (Stage 1) ==========

	@Test
	public void testMessageMintsSession() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "session-msg"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "session-msg", Fields.MESSAGE, "hi"),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		AString sid = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		assertNotNull(sid, "Message response should carry a minted sessionId");
		assertEquals(32, sid.count(), "sessionId should be 16-byte hex (32 chars)");

		// Session record created lazily
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("session-msg");
		Blob sidBlob = Blob.fromHex(sid.toString());
		AMap<AString, ACell> session = agent.getSession(sidBlob);
		assertNotNull(session, "Session record should be created");
		assertNotNull(session.get(Strings.intern("c")));
		assertNotNull(session.get(AgentState.KEY_FRAMES));
		assertNotNull(session.get(Strings.intern("pending")));
		assertNotNull(session.get(Strings.intern("meta")));
	}

	@Test
	public void testMessageReusesProvidedSession() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "session-reuse"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// First call mints a sid
		Job first = engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "session-reuse", Fields.MESSAGE, "one"),
			RequestContext.of(ALICE_DID));
		AString sid = RT.ensureString(RT.getIn(first.awaitResult(5000), Fields.SESSION_ID));
		assertNotNull(sid);

		// Second call with same sid — echoed back, no new session created
		Job second = engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "session-reuse", Fields.MESSAGE, "two",
				Fields.SESSION_ID, sid),
			RequestContext.of(ALICE_DID));
		ACell result2 = second.awaitResult(5000);
		assertEquals(sid, RT.ensureString(RT.getIn(result2, Fields.SESSION_ID)));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("session-reuse");
		assertEquals(1, agent.getSessions().count(),
			"Reusing an existing sid must not create a second session");
	}

	@Test
	public void testRequestMintsSessionAndAttachesToTaskRow() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "session-req",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Submit async so we can inspect the task row while it's still pending
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "session-req", Fields.INPUT, Maps.of("q", "hello")),
			RequestContext.of(ALICE_DID));

		// Job is still pending (never completes) — inspect the task row
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("session-req");
		assertEquals(1, agent.getTasks().count(), "Task should be queued");
		// Take the only task row and assert it carries a sessionId
		var entry = agent.getTasks().entrySet().iterator().next();
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> row = (AMap<AString, ACell>) entry.getValue();
		AString taskSid = RT.ensureString(row.get(Fields.SESSION_ID));
		assertNotNull(taskSid, "Task row should record the session it belongs to");

		// And the session itself exists
		Blob sidBlob = Blob.fromHex(taskSid.toString());
		assertNotNull(agent.getSession(sidBlob), "Session record should be created");

		// Clean up: cancel the task so the test doesn't leave a running loop
		job.cancel();
	}

	@Test
	public void testRequestResponseEnvelopeCarriesSessionId() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "session-env",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "session-env", Fields.INPUT, Maps.of("q", "ping")),
			RequestContext.of(ALICE_DID));

		ACell result = job.awaitResult(5000);
		AString sid = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		assertNotNull(sid, "Completed request envelope must include sessionId");
	}

	/** Trigger never creates a session: with no sessionId supplied, none is
	 *  minted and the response carries no sessionId. */
	@Test
	public void testTriggerDoesNotMintSession() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "session-trig",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "session-trig", Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		AString sid = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		assertNull(sid, "Trigger must not mint or return a session when none was supplied");

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("session-trig");
		assertEquals(0, agent.getSessions().count(), "Trigger must create no session");
	}

	@Test
	public void testInvalidSessionIdFails() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "session-bad"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "session-bad", Fields.MESSAGE, "hi",
				Fields.SESSION_ID, "not-hex-zz"),
			RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("Should fail for malformed sessionId");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	// ========== agent:query ==========

	@Test
	public void testQueryAgent() {
		// Create an agent with config and state
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "query-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo"),
				AgentState.KEY_STATE, Maps.of("counter", 0)
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Query it
		Job queryJob = engine.jobs().invokeOperation(
			"v/ops/agent/info",
			Maps.of(Fields.AGENT_ID, "query-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = queryJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(Strings.create("query-agent"), RT.getIn(result, Fields.AGENT_ID));
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));
		assertNotNull(RT.getIn(result, AgentState.KEY_CONFIG));
		// Summary returns timelineLength and tasks count, not full state
		assertNotNull(RT.getIn(result, Strings.intern("timelineLength")));
	}

	@Test
	public void testQueryNonExistentAgent() {
		Job queryJob = engine.jobs().invokeOperation(
			"v/ops/agent/info",
			Maps.of(Fields.AGENT_ID, "ghost"),
			RequestContext.of(ALICE_DID));
		try {
			queryJob.awaitResult(5000);
			fail("Should fail for non-existent agent");
		} catch (Exception e) {
			assertEquals(Status.FAILED, queryJob.getStatus());
		}
	}

	@Test
	public void testQueryTerminatedAgent() {
		// Create and terminate an agent
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "term-query"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		user.agent("term-query").setStatus(AgentState.TERMINATED);

		// Query should still work — you can read terminated agents
		Job queryJob = engine.jobs().invokeOperation(
			"v/ops/agent/info",
			Maps.of(Fields.AGENT_ID, "term-query"),
			RequestContext.of(ALICE_DID));
		ACell result = queryJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.TERMINATED, RT.getIn(result, Fields.STATUS));
	}

	// ========== agent:list ==========

	@Test
	public void testListAgentsEmpty() {
		// New user with no agents
		Job listJob = engine.jobs().invokeOperation(
			"v/ops/agent/list", Maps.empty(), RequestContext.of(BOB_DID));
		ACell result = listJob.awaitResult(5000);

		assertNotNull(result);
		ACell agents = RT.getIn(result, "agents");
		assertNotNull(agents);
		assertTrue(agents instanceof AVector);
		assertEquals(0, ((AVector<?>) agents).count());
	}

	@Test
	public void testListAgents() {
		// Create two agents
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "agent-a"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "agent-b"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job listJob = engine.jobs().invokeOperation(
			"v/ops/agent/list", Maps.empty(), RequestContext.of(ALICE_DID));
		ACell result = listJob.awaitResult(5000);

		ACell agents = RT.getIn(result, "agents");
		assertTrue(agents instanceof AVector);
		assertEquals(2, ((AVector<?>) agents).count());

		// Each entry should have agentId, status, tasks count
		@SuppressWarnings("unchecked")
		AVector<ACell> agentList = (AVector<ACell>) agents;
		for (long i = 0; i < agentList.count(); i++) {
			ACell entry = agentList.get(i);
			assertNotNull(RT.getIn(entry, Fields.AGENT_ID));
			assertEquals(AgentState.SLEEPING, RT.getIn(entry, Fields.STATUS));
			assertNotNull(RT.getIn(entry, Fields.TASKS));
		}
	}

	@Test
	public void testListAgentsIsolation() {
		// Alice's agents should not appear in Bob's list
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "alice-only"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job bobList = engine.jobs().invokeOperation(
			"v/ops/agent/list", Maps.empty(), RequestContext.of(BOB_DID));
		ACell result = bobList.awaitResult(5000);

		ACell agents = RT.getIn(result, "agents");
		assertEquals(0, ((AVector<?>) agents).count());
	}

	// ========== agent:delete ==========

	@Test
	public void testDeleteAgent() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "del-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job delJob = engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "del-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = delJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.TERMINATED, RT.getIn(result, Fields.STATUS));

		// Record still exists with TERMINATED status
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("del-agent");
		assertNotNull(agent, "Agent record should still exist");
		assertEquals(AgentState.TERMINATED, agent.getStatus());
	}

	@Test
	public void testDeleteAgentWithRemove() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "rem-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job delJob = engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "rem-agent", Fields.REMOVE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		ACell result = delJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.REMOVED));

		// Record should be gone
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("rem-agent");
		assertNull(agent, "Agent record should be removed");
	}

	@Test
	public void testDeleteExactAgentListInOneJob() {
		for (String id : new String[] {"batch-a", "batch-b", "batch-c"}) {
			engine.jobs().invokeOperation("v/ops/agent/create",
				Maps.of(Fields.AGENT_ID, id), RequestContext.of(ALICE_DID)).awaitResult(5000);
		}

		ACell result = engine.jobs().invokeOperation("v/ops/agent/delete",
			Maps.of("agentIds", Vectors.of("batch-a", "batch-b", "batch-c")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		assertEquals(3L, RT.ensureLong(RT.getIn(result, Fields.TOTAL)).longValue());
		AVector<?> deleted = RT.ensureVector(RT.getIn(result, "agents"));
		assertEquals(3L, deleted.count());
		User user = engine.getVenueState().users().get(ALICE_DID);
		for (String id : new String[] {"batch-a", "batch-b", "batch-c"}) {
			assertEquals(AgentState.TERMINATED, user.agent(id).getStatus());
		}
	}

	@Test
	public void testBatchRemoveCleansAlreadyTerminatedAgentForEtchGc() {
		for (String id : new String[] {"batch-old", "batch-live"}) {
			engine.jobs().invokeOperation("v/ops/agent/create",
				Maps.of(Fields.AGENT_ID, id), RequestContext.of(ALICE_DID)).awaitResult(5000);
		}
		engine.jobs().invokeOperation("v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "batch-old"), RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell result = engine.jobs().invokeOperation("v/ops/agent/delete",
			Maps.of("agentIds", Vectors.of("batch-old", "batch-live"),
				Fields.REMOVE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		assertEquals(2L, RT.ensureLong(RT.getIn(result, Fields.TOTAL)).longValue());
		User user = engine.getVenueState().users().get(ALICE_DID);
		assertNull(user.agent("batch-old"));
		assertNull(user.agent("batch-live"));
	}

	@Test
	public void testBatchDeletePreflightPreventsPartialMutation() {
		for (String id : new String[] {"preflight-a", "preflight-b"}) {
			engine.jobs().invokeOperation("v/ops/agent/create",
				Maps.of(Fields.AGENT_ID, id), RequestContext.of(ALICE_DID)).awaitResult(5000);
		}

		Job delete = engine.jobs().invokeOperation("v/ops/agent/delete",
			Maps.of("agentIds", Vectors.of("preflight-a", "missing", "preflight-b"),
				Fields.REMOVE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		assertThrows(Exception.class, () -> delete.awaitResult(5000));

		User user = engine.getVenueState().users().get(ALICE_DID);
		assertEquals(AgentState.SLEEPING, user.agent("preflight-a").getStatus());
		assertEquals(AgentState.SLEEPING, user.agent("preflight-b").getStatus());
	}

	@Test
	public void testBatchDeleteRejectsAmbiguousAndDuplicateInputs() {
		Job both = engine.jobs().invokeOperation("v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "one", "agentIds", Vectors.of("two")),
			RequestContext.of(ALICE_DID));
		assertThrows(Exception.class, () -> both.awaitResult(5000));

		Job duplicate = engine.jobs().invokeOperation("v/ops/agent/delete",
			Maps.of("agentIds", Vectors.of("same", "same")),
			RequestContext.of(ALICE_DID));
		assertThrows(Exception.class, () -> duplicate.awaitResult(5000));

		Job empty = engine.jobs().invokeOperation("v/ops/agent/delete",
			Maps.of("agentIds", Vectors.empty()), RequestContext.of(ALICE_DID));
		assertThrows(Exception.class, () -> empty.awaitResult(5000));

		Job nonString = engine.jobs().invokeOperation("v/ops/agent/delete",
			Maps.of("agentIds", Vectors.of("valid", CVMLong.ONE)),
			RequestContext.of(ALICE_DID));
		assertThrows(Exception.class, () -> nonString.awaitResult(5000));
	}

	@Test
	public void testBatchDeleteRequiresAuthorityForEveryAgent() {
		for (String id : new String[] {"cap-batch-a", "cap-batch-b"}) {
			engine.jobs().invokeOperation("v/ops/agent/create",
				Maps.of(Fields.AGENT_ID, id), RequestContext.of(ALICE_DID)).awaitResult(5000);
		}
		RequestContext onlyA = RequestContext.of(ALICE_DID).withCaps(Vectors.of(
			Capability.create(Strings.create("g/cap-batch-a"), Abilities.AGENT_WRITE)));

		Job denied = engine.jobs().invokeOperation("v/ops/agent/delete",
			Maps.of("agentIds", Vectors.of("cap-batch-a", "cap-batch-b"),
				Fields.REMOVE, CVMBool.TRUE), onlyA);
		assertThrows(Exception.class, () -> denied.awaitResult(5000));

		User user = engine.getVenueState().users().get(ALICE_DID);
		assertNotNull(user.agent("cap-batch-a"), "capability preflight must prevent partial deletion");
		assertNotNull(user.agent("cap-batch-b"), "ungranted agent must remain untouched");
	}

	@Test
	public void testDeleteThenRecreate() {
		// Delete without remove — name is blocked
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "reuse-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "reuse-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Logical deletion preserves the record and therefore reserves the name.
		Job job2 = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "reuse-agent"),
			RequestContext.of(ALICE_DID));
		try {
			job2.awaitResult(5000);
			fail("a TERMINATED record must still block exclusive create");
		} catch (covia.exception.JobFailedException expected) {
			assertTrue(job2.getErrorMessage().contains("already exists"));
		}
	}

	@Test
	public void testDeleteWithRemoveThenRecreate() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "clean-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Delete with remove
		engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "clean-agent", Fields.REMOVE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Physical removal frees the name for a new create.
		Job job2 = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "clean-agent"),
			RequestContext.of(ALICE_DID));
		ACell result2 = job2.awaitResult(5000);
		assertNull(RT.getIn(result2, Fields.CREATED));
		assertEquals(AgentState.SLEEPING, RT.getIn(result2, Fields.STATUS));
	}

	// ========== explicit delete + recreate ==========

	@Test
	public void testRemoveTerminatedThenRecreate() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "ow-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Terminate it
		engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "ow-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// A logical delete reserves the name; remove it explicitly before reuse.
		engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "ow-agent", Fields.REMOVE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "ow-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNull(RT.getIn(result, Fields.CREATED));
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		// Config should be the new one
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("ow-agent");
		AMap<AString, ACell> config = agent.getConfig();
		assertEquals(Strings.create("v/test/ops/taskcomplete"), config.get(Fields.OPERATION));
	}

	@Test
	public void testRemoveSleepingThenRecreateClearsRecord() {
		// Create a SLEEPING agent with old-only config and runtime state. Explicit
		// removal must discard all of it; agent:update owns merge semantics.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "live-ow",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/test/ops/echo",
					"oldOnly", "must disappear")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Seed session state so replacement-vs-update is observable.
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState pre = user.agent("live-ow");
		Blob owSid = Blob.fromHex("abab0001abab0001abab0001abab0001");
		pre.ensureSession(owSid, ALICE_DID);
		pre.appendSessionPending(owSid, Strings.create("hello"));

		engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "live-ow", Fields.REMOVE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Create a fresh agent in the now-empty slot.
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "live-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNull(RT.getIn(result, Fields.CREATED));
		assertNull(RT.getIn(result, Fields.UPDATED));
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		// Config and runtime record are replacements, not shallow merges.
		AgentState post = user.agent("live-ow");
		assertEquals(Strings.create("v/test/ops/taskcomplete"), post.getConfig().get(Fields.OPERATION));
		assertNull(post.getConfig().get(Strings.create("oldOnly")));
		assertFalse(post.hasSessionPending(), "replacement must discard old session work");
		assertNull(post.getSession(owSid), "replacement must discard old sessions");
		assertEquals(0, post.getTimeline().count());
	}

	@Test
	public void testRemoveSuspendedThenRecreateWithNewConfig() {
		// Reproduce #237: the old transition fails and suspends the agent.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "susp-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/error")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		Job failedRequest = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "susp-ow",
				Fields.INPUT, Maps.of("q", "fail on old provider"),
				Fields.WAIT, CVMLong.create(5000)),
			RequestContext.of(ALICE_DID));
		try {
			failedRequest.awaitResult(5000);
			fail("old transition must fail");
		} catch (covia.exception.JobFailedException expected) {
			// expected
		}

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState suspended = user.agent("susp-ow");
		awaitFinished(suspended);
		assertEquals(AgentState.SUSPENDED, suspended.getStatus());
		assertNotNull(suspended.getError());
		long timelineBefore = suspended.getTimeline().count();
		assertEquals(1, timelineBefore, "failed transition should remain in history");

		// Remove the failed record, then create a clean replacement.
		engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "susp-ow", Fields.REMOVE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell result = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "susp-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		assertNull(RT.getIn(result, Fields.CREATED));
		assertNull(RT.getIn(result, Fields.UPDATED));
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		AgentState post = user.agent("susp-ow");
		assertEquals(Strings.create("v/test/ops/taskcomplete"), post.getConfig().get(Fields.OPERATION));
		assertEquals(AgentState.SLEEPING, post.getStatus());
		assertNull(post.getError(), "replacement must clear the stale provider error");
		assertEquals(0, post.getTimeline().count(),
			"delete + create must replace the old runtime record and timeline");

		// A new request must execute the replacement operation. Replaying the old
		// error transition here is the original #237 failure mode.
		ACell recovered = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "susp-ow",
				Fields.INPUT, Maps.of("q", "use new provider"),
				Fields.WAIT, CVMLong.create(5000)),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		assertNotNull(RT.getIn(recovered, Fields.OUTPUT, "completed"),
			"request must run through the replacement taskcomplete operation");
		awaitFinished(post);
		assertEquals(AgentState.SLEEPING, post.getStatus());
		assertEquals(1, post.getTimeline().count());
	}

	@Test
	public void testRemoveRunningThenRecreate() throws Exception {
		// A never-completing transition makes halt-before-replace observable.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "run-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		Job stuck = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "run-ow", Fields.INPUT, Maps.of("q", "never")),
			RequestContext.of(ALICE_DID));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState old = user.agent("run-ow");
		TestEngine.awaitCondition(() -> AgentState.RUNNING.equals(observableStatus(old)), 5000,
			() -> "agent did not enter RUNNING (status=" + observableStatus(old) + ")");
		assertEquals(AgentState.RUNNING, observableStatus(old));
		assertEquals(AgentState.RUNNING, old.getStatus());

		// History-preserving mutation cannot safely swap config under an active
		// transition; callers must either wait or choose full replacement.
		Job unsafeUpdate = engine.jobs().invokeOperation(
			"v/ops/agent/update",
			Maps.of(Fields.AGENT_ID, "run-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID));
		try {
			unsafeUpdate.awaitResult(5000);
			fail("agent:update must reject RUNNING config mutation");
		} catch (covia.exception.JobFailedException expected) {
			// expected
		}
		assertEquals(Strings.create("v/test/ops/never"),
			old.getConfig().get(Fields.OPERATION));

		// Delete halts and settles the old loop before a replacement is created.
		engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "run-ow", Fields.REMOVE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "run-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID));
		ACell replaced = job.awaitResult(5000);
		assertNull(RT.getIn(replaced, Fields.CREATED));
		assertNull(RT.getIn(replaced, Fields.UPDATED));
		assertEquals(AgentState.SLEEPING, RT.getIn(replaced, Fields.STATUS));
		try {
			stuck.awaitResult(5000);
			fail("overwritten agent's active request must fail");
		} catch (covia.exception.JobFailedException expected) {
			// expected
		}
		assertTrue(stuck.getErrorMessage().contains("deleted"));

		AgentState fresh = user.agent("run-ow");
		assertEquals(Strings.create("v/test/ops/taskcomplete"),
			fresh.getConfig().get(Fields.OPERATION));
		ACell result = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "run-ow", Fields.INPUT, Maps.of("q", "fresh"),
				Fields.WAIT, CVMLong.create(5000)),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		assertNotNull(RT.getIn(result, Fields.OUTPUT, "completed"));
	}

	@Test
	public void testCreateExistingDoesNotChangeConfig() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "no-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Exclusive create fails and leaves the original untouched.
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "no-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("duplicate create must fail");
		} catch (covia.exception.JobFailedException expected) {
			assertTrue(job.getErrorMessage().contains("already exists"));
		}

		// Config should still be original
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("no-ow");
		assertEquals(Strings.create("v/test/ops/echo"), agent.getConfig().get(Fields.OPERATION));
	}

	// ========== agent:list — filter TERMINATED ==========

	@Test
	public void testListAgentsHidesTerminated() {
		// Create two agents, delete one
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "alive"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "dead"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "dead"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Default list should hide terminated
		Job listJob = engine.jobs().invokeOperation(
			"v/ops/agent/list", Maps.empty(), RequestContext.of(ALICE_DID));
		ACell result = listJob.awaitResult(5000);

		@SuppressWarnings("unchecked")
		AVector<ACell> agents = (AVector<ACell>) RT.getIn(result, "agents");
		assertEquals(1, agents.count(), "Terminated agent should be hidden");
		assertEquals(Strings.create("alive"), RT.getIn(agents.get(0), Fields.AGENT_ID));
	}

	@Test
	public void testListAgentsIncludeTerminated() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "alive2"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "dead2"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "dead2"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// With includeTerminated=true
		Job listJob = engine.jobs().invokeOperation(
			"v/ops/agent/list",
			Maps.of(Fields.INCLUDE_TERMINATED, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		ACell result = listJob.awaitResult(5000);

		@SuppressWarnings("unchecked")
		AVector<ACell> agents = (AVector<ACell>) RT.getIn(result, "agents");
		assertEquals(2, agents.count(), "Should include both agents when includeTerminated=true");
	}

	@Test
	public void testListAgentsAllTerminated() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "doomed"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "doomed"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job listJob = engine.jobs().invokeOperation(
			"v/ops/agent/list", Maps.empty(), RequestContext.of(ALICE_DID));
		ACell result = listJob.awaitResult(5000);

		@SuppressWarnings("unchecked")
		AVector<ACell> agents = (AVector<ACell>) RT.getIn(result, "agents");
		assertEquals(0, agents.count(), "All terminated — list should be empty");
	}

	// ========== agent:cancelTask ==========

	@Test
	public void testCancelTask() {
		// Create agent and add a task
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "task-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("task-agent");

		Blob taskId = Blob.createRandom(new java.util.Random(42), 16);
		agent.addTask(taskId, Maps.of("question", "What is 2+2?"));
		assertEquals(1, agent.getTasks().count());

		// Cancel the task
		Job cancelJob = engine.jobs().invokeOperation(
			"v/ops/agent/cancel-task",
			Maps.of(Fields.AGENT_ID, "task-agent",
				Fields.TASK_ID, taskId.toHexString()),
			RequestContext.of(ALICE_DID));
		ACell result = cancelJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.CANCELLED));
		assertEquals(Strings.create("task-agent"), RT.getIn(result, Fields.AGENT_ID));

		// Task should be gone
		assertEquals(0, agent.getTasks().count());
	}

	@Test
	public void testCancelTaskNotFound() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "cancel-nf",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job cancelJob = engine.jobs().invokeOperation(
			"v/ops/agent/cancel-task",
			Maps.of(Fields.AGENT_ID, "cancel-nf",
				Fields.TASK_ID, "0000000000000000deadbeefdeadbeef"),
			RequestContext.of(ALICE_DID));

		try {
			cancelJob.awaitResult(5000);
			fail("Should fail — task does not exist");
		} catch (Exception e) {
			assertEquals(Status.FAILED, cancelJob.getStatus());
		}
	}

	@Test
	public void testCancelTaskMissingParams() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "cancel-mp",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Missing taskId
		Job job1 = engine.jobs().invokeOperation(
			"v/ops/agent/cancel-task",
			Maps.of(Fields.AGENT_ID, "cancel-mp"),
			RequestContext.of(ALICE_DID));
		try {
			job1.awaitResult(5000);
			fail("Should fail — taskId missing");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job1.getStatus());
		}

		// Missing agentId
		Job job2 = engine.jobs().invokeOperation(
			"v/ops/agent/cancel-task",
			Maps.of(Fields.TASK_ID, "abcd"),
			RequestContext.of(ALICE_DID));
		try {
			job2.awaitResult(5000);
			fail("Should fail — agentId missing");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job2.getStatus());
		}
	}

	@Test
	public void testCancelTaskMultiple() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "multi-task",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("multi-task");

		java.util.Random rng = new java.util.Random(123);
		Blob task1 = Blob.createRandom(rng, 16);
		Blob task2 = Blob.createRandom(rng, 16);
		Blob task3 = Blob.createRandom(rng, 16);
		agent.addTask(task1, Maps.of("q", "one"));
		agent.addTask(task2, Maps.of("q", "two"));
		agent.addTask(task3, Maps.of("q", "three"));
		assertEquals(3, agent.getTasks().count());

		// Cancel the middle one
		engine.jobs().invokeOperation(
			"v/ops/agent/cancel-task",
			Maps.of(Fields.AGENT_ID, "multi-task",
				Fields.TASK_ID, task2.toHexString()),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		assertEquals(2, agent.getTasks().count());
		assertNull(agent.getTasks().get(task2), "Cancelled task should be gone");
		assertNotNull(agent.getTasks().get(task1), "Other tasks should remain");
		assertNotNull(agent.getTasks().get(task3), "Other tasks should remain");
	}

	@Test
	public void testCancelTaskInvalidHex() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "cancel-hex",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/cancel-task",
			Maps.of(Fields.AGENT_ID, "cancel-hex",
				Fields.TASK_ID, "not-valid-hex!!!"),
			RequestContext.of(ALICE_DID));

		try {
			job.awaitResult(5000);
			fail("Should fail — invalid hex");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	// ========== AgentState.removeTask ==========

	@Test
	public void testAgentStateRemoveTask() {
		User user = engine.getVenueState().users().ensure(ALICE_DID);
		AgentState agent = user.ensureAgent("rm-task-agent", null, null);

		Blob taskId = Blob.createRandom(new java.util.Random(0), 16);
		agent.addTask(taskId, Maps.of("data", "test"));
		assertEquals(1, agent.getTasks().count());

		agent.removeTask(taskId);
		assertEquals(0, agent.getTasks().count());
	}

	@Test
	public void testAgentStateRemoveNonexistentTask() {
		User user = engine.getVenueState().users().ensure(ALICE_DID);
		AgentState agent = user.ensureAgent("rm-noop-agent", null, null);

		Blob taskId = Blob.createRandom(new java.util.Random(1), 16);
		// Removing a task that doesn't exist should be a no-op
		agent.removeTask(taskId);
		assertEquals(0, agent.getTasks().count());
	}

	// ========== AgentState lifecycle ==========

	@Test
	public void testAgentStateLifecycle() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		User user = vs.users().ensure("did:key:zTest");

		AgentState agent = user.ensureAgent("lifecycle-agent", null, null);
		assertTrue(agent.exists());
		assertEquals(AgentState.SLEEPING, agent.getStatus());
		assertTrue(agent.getTs() > 0, "Agent should have a ts after creation");
		assertEquals(0, agent.getTasks().count(), "New agent should have empty tasks");
		assertEquals(0, agent.getPending().count(), "New agent should have empty pending");

		agent.setStatus(AgentState.SUSPENDED);
		assertEquals(AgentState.SUSPENDED, agent.getStatus());

		agent.setStatus(AgentState.SLEEPING);
		assertEquals(AgentState.SLEEPING, agent.getStatus());

		assertNull(agent.getError());
		agent.setError(Strings.create("something went wrong"));
		assertEquals(Strings.create("something went wrong"), agent.getError());
		agent.clearError();
		assertNull(agent.getError());

		Blob mutSid = Blob.fromHex("cdcd0001cdcd0001cdcd0001cdcd0001");
		agent.ensureSession(mutSid, ALICE_DID);
		agent.appendSessionPending(mutSid, Maps.of("content", "hello"));
		AVector<ACell> pending = agent.getSessionPending(mutSid);
		assertNotNull(pending);
		assertEquals(1, pending.count());
		agent.appendSessionPending(mutSid, Maps.of("content", "world"));
		pending = agent.getSessionPending(mutSid);
		assertEquals(2, pending.count());
	}

	// ========== agent:update merge semantics ==========

	@Test
	public void testUpdateMergesConfig() {
		// Create agent with full config — record.config is the single slot (#144)
		ACell createInput = Maps.of(
			Fields.AGENT_ID, "merge-test",
			Fields.CONFIG, Maps.of(
				Strings.create("model"), Strings.create("gpt-4.1-mini"),
				Strings.create("systemPrompt"), Strings.create("You are a test agent"),
				Strings.create("tools"), Vectors.of(
					(ACell) Strings.create("v/ops/covia/read"),
					(ACell) Strings.create("v/ops/covia/write")),
				Strings.create("caps"), Vectors.of(
					(ACell) Maps.of(Strings.create("with"), Strings.create("w/"), Strings.create("can"), Strings.create("crud")))));
		engine.jobs().invokeOperation("v/ops/agent/create", createInput, RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Update just the model — other fields should survive
		ACell updateInput = Maps.of(
			Fields.AGENT_ID, "merge-test",
			Fields.CONFIG, Maps.of(
				Strings.create("model"), Strings.create("gpt-5.4-mini")));
		engine.jobs().invokeOperation("v/ops/agent/update", updateInput, RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Verify: model changed, everything else preserved
		ACell config = engine.resolvePath(Strings.create("g/merge-test/config"), RequestContext.of(ALICE_DID));
		assertEquals(Strings.create("gpt-5.4-mini"), RT.getIn(config, Strings.create("model")));
		assertEquals(Strings.create("You are a test agent"), RT.getIn(config, Strings.create("systemPrompt")));
		assertNotNull(RT.getIn(config, Strings.create("tools")), "tools should survive model update");
		assertNotNull(RT.getIn(config, Strings.create("caps")), "caps should survive model update");
	}

	@Test
	public void testUpdateAcceptsOrderedConfigLayersAndDeepMerges() {
		RequestContext alice = RequestContext.of(ALICE_DID);
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(
			Fields.AGENT_ID, "layer-update",
			Fields.CONFIG, Maps.of(
				"systemPrompt", "Original",
				"providerOptions", Maps.of(
					"thinking", Maps.of("enabled", CVMBool.TRUE, "budget", 1000L)))),
			alice).awaitResult(5000);

		engine.jobs().invokeOperation("v/ops/covia/write", Maps.of(
			Fields.PATH, "w/agent-config/update-provider",
			Fields.VALUE, Maps.of("llmOperation", "v/ops/langchain/anthropic")),
			alice).awaitResult(5000);

		engine.jobs().invokeOperation("v/ops/agent/update", Maps.of(
			Fields.AGENT_ID, "layer-update",
			Fields.CONFIG, Vectors.of(
				Strings.create("w/agent-config/update-provider"),
				Maps.of(
					"systemPrompt", "Updated",
					"providerOptions", Maps.of(
						"thinking", Maps.of("budget", 2000L))))),
			alice).awaitResult(5000);

		AMap<AString, ACell> config = engine.getVenueState().users().get(ALICE_DID)
			.agent("layer-update").getConfig();
		assertEquals(Strings.create("Updated"), config.get(Strings.create("systemPrompt")));
		assertEquals(Strings.create("v/ops/langchain/anthropic"),
			config.get(Strings.create("llmOperation")));
		assertEquals(CVMBool.TRUE,
			RT.getIn(config, Strings.create("providerOptions"), Strings.create("thinking"), Strings.create("enabled")));
		assertEquals(CVMLong.create(2000),
			RT.getIn(config, Strings.create("providerOptions"), Strings.create("thinking"), Strings.create("budget")));
	}

	/** Config has a single home (#144): agent:update rejects state.config loudly. */
	@Test
	public void testUpdateRejectsStateConfig() {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "no-state-config",
				Fields.CONFIG, Maps.of(Strings.create("model"), Strings.create("gpt-4o"))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job update = engine.jobs().invokeOperation("v/ops/agent/update",
			Maps.of(Fields.AGENT_ID, "no-state-config",
				AgentState.KEY_STATE, Maps.of(AgentState.KEY_CONFIG,
					Maps.of(Strings.create("model"), Strings.create("gpt-5.4-mini")))),
			RequestContext.of(ALICE_DID));
		try {
			update.awaitResult(5000);
			fail("agent:update must reject state.config");
		} catch (Exception e) {
			assertTrue(update.getErrorMessage().contains("state.config is not supported"),
				update.getErrorMessage());
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testUpdateNullValueDocumentsCurrentBehaviour() {
		// Document current behaviour: setting a config field to null via update
		// stores null at that key — it does NOT remove the key. To remove a field,
		// explicitly delete the agent with remove=true and create it again. This
		// test pins down the behaviour so any change shows up in the diff.
		ACell createInput = Maps.of(
			Fields.AGENT_ID, "null-test",
			Fields.CONFIG, Maps.of(
				Strings.create("model"), Strings.create("gpt-4o"),
				Strings.create("systemPrompt"), Strings.create("Original prompt")));
		engine.jobs().invokeOperation("v/ops/agent/create", createInput, RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Update systemPrompt to null
		ACell updateInput = Maps.of(
			Fields.AGENT_ID, "null-test",
			Fields.CONFIG, Maps.of(
				Strings.create("systemPrompt"), null));
		engine.jobs().invokeOperation("v/ops/agent/update", updateInput, RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Verify: model preserved, systemPrompt key still exists with null value
		ACell config = engine.resolvePath(Strings.create("g/null-test/config"), RequestContext.of(ALICE_DID));
		assertEquals(Strings.create("gpt-4o"), RT.getIn(config, Strings.create("model")));
		AMap<AString, ACell> configMap = (AMap<AString, ACell>) config;
		assertTrue(configMap.containsKey(Strings.create("systemPrompt")),
			"key should still exist after setting to null (current behaviour)");
		assertNull(configMap.get(Strings.create("systemPrompt")),
			"value should be null (current behaviour — delete + create to remove the key)");
	}

	// ========== Templates as lattice data (v/agents/templates/) ==========

	@Test
	@SuppressWarnings("unchecked")
	public void testTemplatesDiscoverableInLattice() {
		// covia_list path=v/agents/templates returns the 8 standard templates
		Job job = engine.jobs().invokeOperation(
			"v/ops/covia/list",
			Maps.of(Strings.create("path"), Strings.create("v/agents/templates")),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);
		assertNotNull(result);
		assertEquals(CVMLong.create(8), RT.getIn(result, Strings.create("count")));
		AVector<ACell> keys = RT.ensureVector(RT.getIn(result, Strings.create("keys")));
		java.util.Set<String> names = new java.util.HashSet<>();
		for (long i = 0; i < keys.count(); i++) names.add(keys.get(i).toString());
		assertTrue(names.containsAll(java.util.List.of(
			"minimal", "skilled", "reader", "worker", "manager", "analyst", "full", "goaltree")));

		// The catalog pin exposes ordinary metadata with a functional agent facet;
		// it is not merely a flat anonymous config map.
		ACell reader = engine.resolvePath(Strings.create("v/agents/templates/reader"),
			RequestContext.of(ALICE_DID));
		assertNotNull(RT.getIn(reader, Strings.create("agent"), Fields.CONFIG));
		assertTrue(reader instanceof AMap);
		var templateId = ((AMap<?,?>) reader).getHash();
		assertNotNull(engine.getAsset(templateId, RequestContext.of(ALICE_DID)),
			"catalog metadata hash should resolve to the installed Covia asset");
	}

	@Test
	public void testManagerTemplateRuntimeToolPalette() {
		// Create from v/agents/templates/manager and verify the runtime tool palette
		// includes both operation tools and harness tools (subgoal/compact/more_tools).
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "mgr-runtime",
					Fields.CONFIG, Strings.create("v/agents/templates/manager")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		java.util.Set<String> names = runtimeToolNames("mgr-runtime");

		// Operation tools resolved from paths
		assertTrue(names.contains("agent_create"), "manager should have agent_create");
		assertTrue(names.contains("agent_request"), "manager should have agent_request");
		assertTrue(names.contains("grid_run"), "manager should have grid_run");
		assertTrue(names.contains("grid_job_result"), "manager should retrieve async results");
		assertTrue(names.contains("covia_read"), "manager should have covia_read");
		// Harness tools resolved by name
		assertTrue(names.contains("subgoal"), "manager should have subgoal");
		assertTrue(names.contains("compact"), "manager should have compact");
		assertTrue(names.contains("more_tools"), "manager should have more_tools");
		// No leakage of operation paths as tool names
		assertFalse(names.contains("v/ops/agent/create"), "operation path should not appear as tool name");
	}

	@Test
	public void testGoaltreeTemplateGetsAllSevenHarnessTools() {
		// template:goaltree explicitly lists all 7 harness tools — verify all resolve
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "gt-runtime",
					Fields.CONFIG, Strings.create("v/agents/templates/goaltree")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		java.util.Set<String> names = runtimeToolNames("gt-runtime");
		for (String harness : new String[]{"subgoal", "complete", "fail", "compact",
		                                    "context_load", "context_unload", "more_tools"}) {
			assertTrue(names.contains(harness), "goaltree should have " + harness);
		}
	}

	@Test
	public void testReaderTemplateHasNoHarnessTools() {
		// template:reader is read-only data analysis — operations only, no harness tools
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "rdr-runtime",
					Fields.CONFIG, Strings.create("v/agents/templates/reader")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		java.util.Set<String> names = runtimeToolNames("rdr-runtime");
		// Operations: yes
		assertTrue(names.contains("covia_read"), "reader should have covia_read");
		assertTrue(names.contains("covia_list"), "reader should have covia_list");
		// Harness: no
		assertFalse(names.contains("subgoal"), "reader should NOT have subgoal");
		assertFalse(names.contains("compact"), "reader should NOT have compact");
		assertFalse(names.contains("more_tools"), "reader should NOT have more_tools");
	}

	// ========== B8.8 — transition-returned wakeTime wires into scheduler ==========

	/**
	 * When a transition returns a {@code wakeTime} in its result, the
	 * framework installs it on the picked thread via {@code setThreadWakeTime}:
	 * the lattice session record carries {@code wakeTime}, and the venue grid
	 * scheduler holds a single {@code agent:wake} event armed at that time.
	 */
	@Test
	public void testTransitionWakeTimeSchedulesSessionWake() {
		long farFuture = System.currentTimeMillis() + 60_000;

		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "wake-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/wakeresponse"),
				AgentState.KEY_STATE, Maps.of(
					Fields.WAKE_TIME, CVMLong.create(farFuture))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(
				Fields.AGENT_ID, "wake-agent",
				Fields.MESSAGE, Strings.create("hello")),
			RequestContext.of(ALICE_DID));
		chatJob.awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("wake-agent");
		assertEquals(AgentState.SLEEPING, awaitFinished(agent));

		// Session id: the chat response carries it back.
		AString sessionIdStr = (AString) RT.getIn(chatJob.getOutput(), Fields.SESSION_ID);
		assertNotNull(sessionIdStr, "chat should return a sessionId");
		Blob sid = Blob.parse(sessionIdStr.toString());

		// Lattice: session record carries the wakeTime.
		AMap<AString, ACell> session = agent.getSession(sid);
		ACell lattWake = session.get(Fields.WAKE_TIME);
		assertTrue(lattWake instanceof CVMLong,
			"session record should carry wakeTime after transition");
		assertEquals(farFuture, ((CVMLong) lattWake).longValue());

		// Scheduler: a single agent:wake event armed at the transition's wakeTime.
		convex.core.data.AVector<ACell> events =
			engine.gridScheduler().list(RequestContext.of(ALICE_DID));
		ACell wakeHandle = null;
		for (long i = 0; i < events.count(); i++) {
			AMap<AString, ACell> ev = (AMap<AString, ACell>) events.get(i);
			if (Strings.intern("v/ops/agent/trigger").equals(ev.get(Strings.intern("op")))
					&& ev.get(Strings.intern("time")) instanceof CVMLong t
					&& t.longValue() == farFuture) {
				wakeHandle = ev.get(Strings.intern("handle"));
				break;
			}
		}
		assertNotNull(wakeHandle,
			"scheduler should hold an agent:wake event at the transition's wakeTime");

		// Cleanup — cancel so we don't leak a pending fire into later tests.
		engine.gridScheduler().cancel((Blob) wakeHandle, RequestContext.of(ALICE_DID));
	}

	// ========== Issue #88: fail-fast on transition error ==========

	/**
	 * Transition that always fails must surface as a FAILED request Job, suspend
	 * the agent, drain its task queue, and not retry. Regression for the tight
	 * retry loop reported in issue #88 (e.g. missing OPENAI_API_KEY → ~280
	 * timeline entries in seconds).
	 */
	@Test
	public void testTransitionErrorSuspendsAgentAndFailsCaller() {
		// v/test/ops/error reads input.message; the framework's transition input
		// has no message field, so every invoke throws IllegalArgumentException.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "fail-fast-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/error")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job requestJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(
				Fields.AGENT_ID, "fail-fast-agent",
				Fields.INPUT,    Maps.of("q", "hello"),
				Fields.WAIT,     CVMLong.create(5000)),
			RequestContext.of(ALICE_DID));

		try {
			requestJob.awaitResult(5000);
			fail("Request must FAIL when the transition errors");
		} catch (covia.exception.JobFailedException expected) {
			// expected — caller sees the failure
		}
		assertEquals(Status.FAILED, requestJob.getStatus(),
			"caller's Job must be FAILED, not stuck PENDING");
		String err = requestJob.getErrorMessage();
		assertNotNull(err, "FAILED Job must carry an error message");
		assertTrue(err.contains("Transition failed"),
			"error should describe the transition failure: " + err);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("fail-fast-agent");

		awaitFinished(agent);

		assertEquals(AgentState.SUSPENDED, agent.getStatus(),
			"agent must be SUSPENDED after a transition error");
		assertNotNull(agent.getError(), "SUSPENDED agent must record the error");
		assertEquals(0, agent.getTasks().count(),
			"task queue must be drained on fail-fast (no retry on resume)");

		// Tight-loop guard: a single failed cycle must produce a single timeline
		// entry, not the ~20 that the pre-fix code emitted per run.
		AVector<ACell> timeline = (AVector<ACell>) agent.getRecord()
			.get(AgentState.KEY_TIMELINE);
		assertEquals(1, timeline.count(),
			"fail-fast must record exactly one error cycle in the timeline");
	}

	/**
	 * Resume after a fail-fast suspend must restore SLEEPING with the error
	 * cleared. Subsequent requests should proceed against the (now clean)
	 * agent — the framework does not retain stale state from the failed run.
	 */
	@Test
	public void testResumeAfterFailFast() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "resume-after-fail",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/error")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job firstReq = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(
				Fields.AGENT_ID, "resume-after-fail",
				Fields.INPUT,    Maps.of("q", "first"),
				Fields.WAIT,     CVMLong.create(5000)),
			RequestContext.of(ALICE_DID));
		try { firstReq.awaitResult(5000); } catch (Exception ignored) {}

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("resume-after-fail");
		assertEquals(AgentState.SUSPENDED, agent.getStatus());

		// Resume — operator action after fixing the underlying issue.
		assertTrue(agent.tryResume(), "resume must flip SUSPENDED → SLEEPING");
		assertEquals(AgentState.SLEEPING, agent.getStatus());
		assertNull(agent.getError(), "resume must clear the error");
		assertEquals(0, agent.getTasks().count(),
			"resumed agent must have a clean task queue");
	}

	// ========== agent:deleteSession ==========

	/**
	 * Happy path: deleting a session removes the session record and nothing
	 * else. Job records are the callers' own — they survive the session
	 * delete and are deletable separately via the jobs API.
	 */
	@Test
	public void testDeleteSessionErasesSession() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "del-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job msg1 = engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "del-agent",
				Fields.MESSAGE, Maps.of("content", "private hello")),
			RequestContext.of(ALICE_DID));
		AString sidHex = RT.ensureString(RT.getIn(msg1.awaitResult(5000), Fields.SESSION_ID));
		assertNotNull(sidHex, "message result should carry the sessionId");

		Job msg2 = engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "del-agent",
				Fields.SESSION_ID, sidHex,
				Fields.MESSAGE, Maps.of("content", "more private stuff")),
			RequestContext.of(ALICE_DID));
		msg2.awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("del-agent");
		Blob sid = Blob.fromHex(sidHex.toString());
		assertNotNull(agent.getSession(sid), "session should exist after messages");

		Job del = engine.jobs().invokeOperation(
			"v/ops/agent/delete-session",
			Maps.of(Fields.AGENT_ID, "del-agent", Fields.SESSION_ID, sidHex),
			RequestContext.of(ALICE_DID));
		ACell result = del.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.DELETED));

		assertNull(agent.getSession(sid), "session record should be gone");
		// Job records belong to their callers and are NOT touched — the
		// caller holds the IDs and may delete them via the jobs API (which
		// deletes permanently), or use content-free job variants (#192).
		assertNotNull(user.getJob(msg1.getID()), "job records survive a session delete");
		assertNotNull(user.getJob(msg2.getID()), "job records survive a session delete");
		assertTrue(engine.jobs().deleteJob(msg1.getID(), RequestContext.of(ALICE_DID)),
			"caller can still delete their own job separately");
		assertNull(user.getJob(msg1.getID()));
	}

	@Test
	public void testDeleteSessionUnknownSessionFails() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "del-unknown-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job del = engine.jobs().invokeOperation(
			"v/ops/agent/delete-session",
			Maps.of(Fields.AGENT_ID, "del-unknown-agent",
				Fields.SESSION_ID, Strings.create("eeee0002eeee0002eeee0002eeee0002")),
			RequestContext.of(ALICE_DID));
		try {
			del.awaitResult(5000);
			fail("deleteSession should fail for an unknown session");
		} catch (Exception e) {
			assertEquals(Status.FAILED, del.getStatus());
			assertTrue(String.valueOf(RT.getIn(del.getData(), Fields.ERROR)).contains("Session not found"));
		}
	}

	@Test
	public void testDeleteSessionRequiresSessionId() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "del-noarg-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job del = engine.jobs().invokeOperation(
			"v/ops/agent/delete-session",
			Maps.of(Fields.AGENT_ID, "del-noarg-agent"),
			RequestContext.of(ALICE_DID));
		try {
			del.awaitResult(5000);
			fail("deleteSession should fail without a sessionId");
		} catch (Exception e) {
			assertEquals(Status.FAILED, del.getStatus());
		}
	}

	/**
	 * Deleting a session with a chat in flight fails the awaiting chat Job
	 * ("Session deleted") so the blocked caller unblocks with a clean error,
	 * and the deletion proceeds. The agent's transition op is
	 * {@code v/test/ops/never}, so the chat can only end via the delete.
	 */
	@Test
	public void testDeleteSessionFailsInFlightChat() throws Exception {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "del-chat-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, Strings.create("v/test/ops/never"))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job chat = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "del-chat-agent",
				Fields.MESSAGE, Maps.of("content", "secret")),
			RequestContext.of(ALICE_DID));

		// Chat mints its session at intake; recover the sid from the agent
		// record (poll briefly — intake is synchronous but dispatch may not be)
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("del-chat-agent");
		TestEngine.awaitCondition(() -> agent.getSessions().count() > 0, 5000,
			() -> "chat did not mint a session");
		assertEquals(1, agent.getSessions().count(), "chat should have minted a session");
		Blob sid = agent.getSessions().entrySet().iterator().next().getKey();

		Job del = engine.jobs().invokeOperation(
			"v/ops/agent/delete-session",
			Maps.of(Fields.AGENT_ID, "del-chat-agent",
				Fields.SESSION_ID, Strings.create(sid.toHexString())),
			RequestContext.of(ALICE_DID));
		ACell result = del.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.DELETED));

		try {
			chat.awaitResult(5000);
			fail("in-flight chat should have been failed by deleteSession");
		} catch (Exception e) {
			assertEquals(Status.FAILED, chat.getStatus());
			assertTrue(String.valueOf(RT.getIn(chat.getData(), Fields.ERROR)).contains("Session deleted"),
				"chat failure should name the session deletion, got: "
					+ RT.getIn(chat.getData(), Fields.ERROR));
		}
		assertNull(agent.getSession(sid), "session record should be gone");
	}

	/** The read-only public scope denies deleteSession (agent/write). */
	@Test
	public void testDeleteSessionDeniedUnderReadOnlyScope() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "del-cap-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		Job msg = engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "del-cap-agent",
				Fields.MESSAGE, Maps.of("content", "hi")),
			RequestContext.of(ALICE_DID));
		AString sidHex = RT.ensureString(RT.getIn(msg.awaitResult(5000), Fields.SESSION_ID));

		RequestContext readOnly = RequestContext.of(ALICE_DID)
			.withCaps(covia.lattice.CapabilityChecker.readOnlyScope(ALICE_DID));
		Job del = engine.jobs().invokeOperation(
			"v/ops/agent/delete-session",
			Maps.of(Fields.AGENT_ID, "del-cap-agent", Fields.SESSION_ID, sidHex),
			readOnly);
		try {
			del.awaitResult(5000);
			fail("read-only scope should deny deleteSession");
		} catch (Exception e) {
			assertEquals(Status.FAILED, del.getStatus());
		}
		// Session survives the denied attempt
		User user = engine.getVenueState().users().get(ALICE_DID);
		assertNotNull(user.agent("del-cap-agent").getSession(Blob.fromHex(sidHex.toString())));
	}

	/**
	 * Operator kill-switch: {@code adapters.agent.sessionDelete: false}
	 * disables the op venue-wide; sessions are untouched by the failed call.
	 */
	@Test
	public void testDeleteSessionDisabledByConfig() {
		Engine disabled = Engine.createTemp(Maps.of(
			covia.venue.Config.USERS, Maps.of(covia.venue.Config.AUTO_CREATE, true),
			covia.venue.Config.ADAPTERS, Maps.of(
				Strings.create("agent"), Maps.of(
					Strings.create("sessionDelete"), CVMBool.FALSE)),
			covia.venue.Config.NAME, Strings.create("no-session-delete")));
		try {
			Engine.addDemoAssets(disabled);
			RequestContext ctx = RequestContext.of(ALICE_DID);
			disabled.jobs().invokeOperation(
				"v/ops/agent/create",
				Maps.of(Fields.AGENT_ID, "cfg-agent"), ctx).awaitResult(5000);
			Job msg = disabled.jobs().invokeOperation(
				"v/ops/agent/message",
				Maps.of(Fields.AGENT_ID, "cfg-agent",
					Fields.MESSAGE, Maps.of("content", "hi")), ctx);
			AString sidHex = RT.ensureString(RT.getIn(msg.awaitResult(5000), Fields.SESSION_ID));

			Job del = disabled.jobs().invokeOperation(
				"v/ops/agent/delete-session",
				Maps.of(Fields.AGENT_ID, "cfg-agent", Fields.SESSION_ID, sidHex), ctx);
			try {
				del.awaitResult(5000);
				fail("deleteSession should be disabled by config");
			} catch (Exception e) {
				assertEquals(Status.FAILED, del.getStatus());
				assertTrue(String.valueOf(RT.getIn(del.getData(), Fields.ERROR)).contains("disabled"),
					"failure should say the op is disabled, got: "
						+ RT.getIn(del.getData(), Fields.ERROR));
			}
			assertNotNull(disabled.getVenueState().users().get(ALICE_DID)
				.agent("cfg-agent").getSession(Blob.fromHex(sidHex.toString())),
				"session must survive a disabled deleteSession call");
		} finally {
			disabled.close();
		}
	}

	// ========== agent:renameSession ==========

	/**
	 * Happy path: renaming a session sets {@code meta.title}; renaming again
	 * with a blank title clears it back to unset.
	 */
	@Test
	public void testRenameSessionSetsAndClearsTitle() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "rename-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job msg = engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "rename-agent",
				Fields.MESSAGE, Maps.of("content", "hello")),
			RequestContext.of(ALICE_DID));
		AString sidHex = RT.ensureString(RT.getIn(msg.awaitResult(5000), Fields.SESSION_ID));

		Job rename = engine.jobs().invokeOperation(
			"v/ops/agent/rename-session",
			Maps.of(Fields.AGENT_ID, "rename-agent", Fields.SESSION_ID, sidHex,
				Fields.TITLE, "Planning the launch"),
			RequestContext.of(ALICE_DID));
		ACell result = rename.awaitResult(5000);
		assertEquals(Strings.create("Planning the launch"), RT.getIn(result, Fields.TITLE));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AMap<AString, ACell> session = user.agent("rename-agent").getSession(Blob.fromHex(sidHex.toString()));
		assertEquals(Strings.create("Planning the launch"), RT.getIn(session, "meta", "title"));

		Job clear = engine.jobs().invokeOperation(
			"v/ops/agent/rename-session",
			Maps.of(Fields.AGENT_ID, "rename-agent", Fields.SESSION_ID, sidHex, Fields.TITLE, ""),
			RequestContext.of(ALICE_DID));
		ACell clearResult = clear.awaitResult(5000);
		assertNull(RT.getIn(clearResult, Fields.TITLE), "blank title should not round-trip in the result");

		session = user.agent("rename-agent").getSession(Blob.fromHex(sidHex.toString()));
		assertNull(RT.getIn(session, "meta", "title"), "title should be cleared, not left blank");
	}

	@Test
	public void testRenameSessionRejectsNonStringWithoutClearingTitle() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "rename-type-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job msg = engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "rename-type-agent",
				Fields.MESSAGE, Maps.of("content", "hello")),
			RequestContext.of(ALICE_DID));
		AString sidHex = RT.ensureString(RT.getIn(msg.awaitResult(5000), Fields.SESSION_ID));

		engine.jobs().invokeOperation(
			"v/ops/agent/rename-session",
			Maps.of(Fields.AGENT_ID, "rename-type-agent", Fields.SESSION_ID, sidHex,
				Fields.TITLE, "Keep me"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job invalid = engine.jobs().invokeOperation(
			"v/ops/agent/rename-session",
			Maps.of(Fields.AGENT_ID, "rename-type-agent", Fields.SESSION_ID, sidHex,
				Fields.TITLE, CVMLong.create(42)),
			RequestContext.of(ALICE_DID));
		assertThrows(Exception.class, () -> invalid.awaitResult(5000));
		assertEquals(Status.FAILED, invalid.getStatus());
		assertTrue(String.valueOf(RT.getIn(invalid.getData(), Fields.ERROR))
			.contains("title must be a string"));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AMap<AString, ACell> session = user.agent("rename-type-agent")
			.getSession(Blob.fromHex(sidHex.toString()));
		assertEquals(Strings.create("Keep me"), RT.getIn(session, "meta", "title"),
			"invalid input must not clear an existing title");
	}

	@Test
	public void testRenameSessionUnknownSessionFails() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "rename-unknown-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job rename = engine.jobs().invokeOperation(
			"v/ops/agent/rename-session",
			Maps.of(Fields.AGENT_ID, "rename-unknown-agent",
				Fields.SESSION_ID, Strings.create("eeee0003eeee0003eeee0003eeee0003"),
				Fields.TITLE, "x"),
			RequestContext.of(ALICE_DID));
		try {
			rename.awaitResult(5000);
			fail("renameSession should fail for an unknown session");
		} catch (Exception e) {
			assertEquals(Status.FAILED, rename.getStatus());
			assertTrue(String.valueOf(RT.getIn(rename.getData(), Fields.ERROR)).contains("Session not found"));
		}
	}

	@Test
	public void testRenameSessionRequiresSessionId() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "rename-noarg-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job rename = engine.jobs().invokeOperation(
			"v/ops/agent/rename-session",
			Maps.of(Fields.AGENT_ID, "rename-noarg-agent", Fields.TITLE, "x"),
			RequestContext.of(ALICE_DID));
		try {
			rename.awaitResult(5000);
			fail("renameSession should fail without a sessionId");
		} catch (Exception e) {
			assertEquals(Status.FAILED, rename.getStatus());
		}
	}

	/** The read-only public scope denies renameSession (agent/write). */
	@Test
	public void testRenameSessionDeniedUnderReadOnlyScope() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "rename-cap-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		Job msg = engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "rename-cap-agent",
				Fields.MESSAGE, Maps.of("content", "hi")),
			RequestContext.of(ALICE_DID));
		AString sidHex = RT.ensureString(RT.getIn(msg.awaitResult(5000), Fields.SESSION_ID));

		RequestContext readOnly = RequestContext.of(ALICE_DID)
			.withCaps(covia.lattice.CapabilityChecker.readOnlyScope(ALICE_DID));
		Job rename = engine.jobs().invokeOperation(
			"v/ops/agent/rename-session",
			Maps.of(Fields.AGENT_ID, "rename-cap-agent", Fields.SESSION_ID, sidHex, Fields.TITLE, "nope"),
			readOnly);
		try {
			rename.awaitResult(5000);
			fail("read-only scope should deny renameSession");
		} catch (Exception e) {
			assertEquals(Status.FAILED, rename.getStatus());
		}
		User user = engine.getVenueState().users().get(ALICE_DID);
		AMap<AString, ACell> session = user.agent("rename-cap-agent").getSession(Blob.fromHex(sidHex.toString()));
		assertNull(RT.getIn(session, "meta", "title"), "title must not be set by a denied attempt");
	}

	/** Builds the L3 input via the same code path as agent:context and returns tool names. */
	@SuppressWarnings("unchecked")
	private java.util.Set<String> runtimeToolNames(String agentId) {
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent(agentId);
		assertNotNull(agent, "agent " + agentId + " should exist");
		covia.adapter.agent.GoalTreeAdapter adapter =
			(covia.adapter.agent.GoalTreeAdapter) engine.getAdapter("goaltree");
		AMap<AString, ACell> l3 = adapter.buildFirstIterationL3Input(
			agent.getConfig(), agent.getState(), null, RequestContext.of(ALICE_DID));
		AVector<ACell> tools = RT.ensureVector(l3.get(Strings.create("tools")));
		assertNotNull(tools, "L3 input should have a tools array");
		java.util.Set<String> names = new java.util.HashSet<>();
		for (long i = 0; i < tools.count(); i++) {
			ACell name = ((AMap<AString, ACell>) tools.get(i)).get(Strings.create("name"));
			if (name != null) names.add(name.toString());
		}
		return names;
	}

	// ========== Context scope chain (#142) ==========

	/** Mint-time loads seed the session tier; passing loads against an
	 *  existing session is an error, never a silent ignore. */
	@Test
	public void testChatMintLoadsSeedSessionTier() {
		createChatAgent("mint-loads-agent");
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/brief"), Maps.of(Strings.create("budget"), CVMLong.create(400)));

		Job chat = engine.jobs().invokeOperation("v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "mint-loads-agent",
				Fields.MESSAGE, Strings.create("hello"),
				Fields.LOADS, loads),
			RequestContext.of(ALICE_DID));
		ACell result = chat.awaitResult(5000);
		AString sidHex = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		Blob sid = Blob.fromHex(sidHex.toString());

		User u = engine.getVenueState().users().get(ALICE_DID);
		ACell sessionLoads = RT.getIn(u.agent("mint-loads-agent").getSession(sid), Fields.LOADS);
		assertEquals(400L, ((CVMLong) RT.getIn(sessionLoads, "w/brief", "budget")).longValue());
		assertNotNull(RT.getIn(sessionLoads, "w/brief", "ts"),
			"mint loads are normalised and timestamped once at persistence");

		// Same session again WITH loads → error (mint-only).
		Job again = engine.jobs().invokeOperation("v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "mint-loads-agent",
				Fields.MESSAGE, Strings.create("more"),
				Fields.SESSION_ID, sidHex,
				Fields.LOADS, loads),
			RequestContext.of(ALICE_DID));
		try {
			again.awaitResult(5000);
			fail("loads against an existing session must be rejected");
		} catch (Exception e) {
			assertTrue(again.getErrorMessage().contains("session is created"),
				again.getErrorMessage());
		}
	}

	/** The motivating #142 bug: one session's loads must not leak into another. */
	@Test
	public void testSessionLoadsAreIsolated() {
		createChatAgent("iso-agent");
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/acme"), Maps.of(Strings.create("budget"), CVMLong.create(400)));

		// Session A: minted with loads.
		Job chatA = engine.jobs().invokeOperation("v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "iso-agent",
				Fields.MESSAGE, Strings.create("A"), Fields.LOADS, loads),
			RequestContext.of(ALICE_DID));
		Blob sidA = Blob.fromHex(RT.ensureString(
			RT.getIn(chatA.awaitResult(5000), Fields.SESSION_ID)).toString());

		// Session B: fresh, no loads.
		Job chatB = engine.jobs().invokeOperation("v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "iso-agent", Fields.MESSAGE, Strings.create("B")),
			RequestContext.of(ALICE_DID));
		Blob sidB = Blob.fromHex(RT.ensureString(
			RT.getIn(chatB.awaitResult(5000), Fields.SESSION_ID)).toString());

		AgentState agent = engine.getVenueState().users().get(ALICE_DID).agent("iso-agent");
		ACell loadsA = RT.getIn(agent.getSession(sidA), Fields.LOADS);
		assertEquals(400L, ((CVMLong) RT.getIn(loadsA, "w/acme", "budget")).longValue(),
			"session A keeps its loads");
		assertNotNull(RT.getIn(loadsA, "w/acme", "ts"));
		ACell loadsB = RT.getIn(agent.getSession(sidB), Fields.LOADS);
		assertTrue(loadsB == null || ((AMap<?, ?>) loadsB).count() == 0,
			"session B must not see session A's loads, got: " + loadsB);
		// And nothing leaks to agent-level state.
		assertNull(RT.getIn(agent.getState(), Fields.LOADS),
			"agent-level state carries no loads");
	}

	/** Loads have no home in agent-level state (#142) — reject loudly, same
	 *  rule as state.config (#144). */
	@Test
	public void testCreateAndUpdateRejectStateLoads() {
		Job create = engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "no-state-loads",
				AgentState.KEY_STATE, Maps.of(Fields.LOADS, Maps.of(
					Strings.create("w/x"), Maps.of(Strings.create("budget"), CVMLong.create(100))))),
			RequestContext.of(ALICE_DID));
		try {
			create.awaitResult(5000);
			fail("agent:create must reject state.loads");
		} catch (Exception e) {
			assertTrue(create.getErrorMessage().contains("state.loads is not supported"),
				create.getErrorMessage());
		}

		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "no-state-loads"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		Job update = engine.jobs().invokeOperation("v/ops/agent/update",
			Maps.of(Fields.AGENT_ID, "no-state-loads",
				AgentState.KEY_STATE, Maps.of(Fields.LOADS, Maps.empty())),
			RequestContext.of(ALICE_DID));
		try {
			update.awaitResult(5000);
			fail("agent:update must reject state.loads");
		} catch (Exception e) {
			assertTrue(update.getErrorMessage().contains("state.loads is not supported"),
				update.getErrorMessage());
		}
	}

	/**
	 * A skill source the creator cannot read is warned about at create time:
	 * at runtime it contributes nothing and renders no diagnostic anywhere the
	 * operator would see, so it is indistinguishable from an empty source.
	 *
	 * <p>The check is the CREATOR's own access. An ordinary user is null-scope
	 * — unrestricted over their own namespace — so this fires for a
	 * capability-scoped creator (an agent creating an agent, or a UCAN-narrowed
	 * caller), which is also the case where the mistake is easiest to make.</p>
	 */
	@Test
	public void testCreateWarnsOnUnreadableSkillSource() {
		RequestContext scoped = RequestContext.of(ALICE_DID).withCaps(Vectors.of(
			Maps.of(Strings.create("with"), Strings.create(ALICE_DID + "/v/ops/agent/create"),
				Strings.create("can"), Strings.create("invoke")),
			Maps.of(Strings.create("with"), Strings.create(ALICE_DID + "/g/"),
				Strings.create("can"), Strings.create("agent/create")),
			Maps.of(Strings.create("with"), Strings.create(ALICE_DID + "/w/skills"),
				Strings.create("can"), convex.auth.ucan.Capability.CRUD_READ)));
		ACell input = Maps.of(
			Fields.AGENT_ID, "skills-unreadable-source-agent",
			Fields.CONFIG, Maps.of(
				"operation", "v/ops/llmagent/chat",
				"skillsets", Vectors.of(Strings.create("w/other-place"))));
		ACell result = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, scoped).awaitResult(5000);

		ACell warnings = RT.getIn(result, Fields.WARNINGS);
		assertNotNull(warnings, "expected a warning about the unreadable source: " + result);
		assertTrue(warnings.toString().contains("no access capability: w/other-place"),
			warnings.toString());
		assertFalse(warnings.toString().contains("load skill"), warnings.toString());
	}

	/** A source that exists and is readable raises nothing at all. */
	@Test
	public void testCreateSilentOnReadableSkillSource() {
		RequestContext scoped = RequestContext.of(ALICE_DID).withCaps(Vectors.of(
			Maps.of(Strings.create("with"), Strings.create(ALICE_DID + "/v/ops/agent/create"),
				Strings.create("can"), Strings.create("invoke")),
			Maps.of(Strings.create("with"), Strings.create(ALICE_DID + "/g/"),
				Strings.create("can"), Strings.create("agent/create")),
			Maps.of(Strings.create("with"), Strings.create(ALICE_DID + "/w/skills"),
				Strings.create("can"), convex.auth.ucan.Capability.CRUD_READ),
			Maps.of(Strings.create("with"), Strings.create("v/skills"),
				Strings.create("can"), convex.auth.ucan.Capability.CRUD_READ)));
		ACell input = Maps.of(
			Fields.AGENT_ID, "skills-readable-source-agent",
			Fields.CONFIG, Maps.of(
				"operation", "v/ops/llmagent/chat",
				"skillsets", Vectors.of(Strings.create("v/skills/root"))));
		ACell result = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, scoped).awaitResult(5000);
		assertNull(RT.getIn(result, Fields.WARNINGS), String.valueOf(result));
	}

	// ========== agent-safe past sessions (#403) ==========

	@Test
	public void testPastSessionsAreSelfScopedCurrentExcludedAndNeedNoRawReadCap() {
		AString agentId = Strings.create("past-session-agent");
		AgentState agent = engine.getVenueState().users().ensure(ALICE_DID)
			.ensureAgent(agentId, Maps.empty(), null);
		Blob older = Blob.fromHex("10000000000000000000000000000001");
		Blob newer = Blob.fromHex("10000000000000000000000000000002");
		Blob current = Blob.fromHex("10000000000000000000000000000003");
		setConversation(agent, older,
			turn("user", "  Older\n topic  ", 100),
			turn("assistant", "older answer", 110));
		setConversation(agent, newer,
			turn("user", "Newest topic", 200),
			turn("assistant", "newest answer", 210));
		setConversation(agent, current,
			turn("user", "current topic", 300),
			turn("assistant", "current answer", 310));

		RequestContext scoped = RequestContext.ofAgent(ALICE_DID, agentId)
			.withSessionId(current)
			.withCaps(Vectors.of(
				Capability.create(Strings.create("v/ops/agent/sessions"), Strings.create("invoke")),
				Capability.create(Strings.create("v/ops/agent/session-read"), Strings.create("invoke"))));
		ACell listed = engine.jobs().invokeOperation(
			"v/ops/agent/sessions", Maps.empty(), scoped).awaitResult(5000);
		AVector<ACell> sessions = RT.ensureVector(RT.getIn(listed, "sessions"));
		assertEquals(2, sessions.count());
		assertEquals(newer.toHexString(), RT.getIn(sessions.get(0), Fields.SESSION_ID).toString());
		assertEquals("Newest topic", RT.getIn(sessions.get(0), "title").toString());
		assertEquals("Older topic", RT.getIn(sessions.get(1), "title").toString());
		assertEquals(2L, ((CVMLong) RT.getIn(sessions.get(0), "turnCount")).longValue());

		ACell read = engine.jobs().invokeOperation(
			"v/ops/agent/session-read", Maps.empty(), scoped).awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(read, "found"));
		assertEquals(newer.toHexString(), RT.getIn(read, Fields.SESSION_ID).toString());
		assertEquals(2, RT.ensureVector(RT.getIn(read, Fields.MESSAGES)).count());
	}

	@Test
	public void testPastSessionMissingCurrentAndPolicyVetoAreIndistinguishable() {
		AString agentId = Strings.create("private-past-session-agent");
		AgentState agent = engine.getVenueState().users().ensure(ALICE_DID)
			.ensureAgent(agentId, Maps.empty(), null);
		Blob visible = Blob.fromHex("20000000000000000000000000000001");
		Blob hidden = Blob.fromHex("20000000000000000000000000000002");
		Blob current = Blob.fromHex("20000000000000000000000000000003");
		Blob missing = Blob.fromHex("20000000000000000000000000000004");
		for (Blob sid : new Blob[] {visible, hidden, current}) {
			setConversation(agent, sid,
				turn("user", "question " + sid.toHexString(), 100),
				turn("assistant", "answer", 110));
		}

		RequestContext scoped = RequestContext.ofAgent(ALICE_DID, agentId)
			.withSessionId(current)
			.withCaps(Vectors.of(
				Capability.create(Strings.create("v/ops/agent/session-read"), Strings.create("invoke")),
				Capability.create(Strings.create("v/ops/agent/sessions"), Strings.create("invoke"))));
		AgentAdapter adapter = (AgentAdapter) engine.getAdapter("agent");
		try (AgentAdapter.SessionVisibilityRegistration ignored =
				adapter.registerSessionVisibilityPolicy(candidate -> {
					if (!ALICE_DID.equals(candidate.ownerDID())
							|| !agentId.equals(candidate.agentId())) return true;
					if (hidden.equals(candidate.sessionId())) {
						throw new IllegalStateException("policy unavailable");
					}
					return true;
				})) {
			ACell absent = readPastSession(scoped, missing);
			ACell active = readPastSession(scoped, current);
			ACell vetoed = readPastSession(scoped, hidden);
			assertEquals(Maps.of("found", false), absent);
			assertEquals(absent, active);
				assertEquals(absent, vetoed);
				assertEquals(1, ((AMap<?, ?>) vetoed).count());
				assertEquals(CVMBool.TRUE, RT.getIn(readPastSession(scoped, visible), "found"));

				ACell listed = engine.jobs().invokeOperation(
					"v/ops/agent/sessions", Maps.empty(), scoped).awaitResult(5000);
				AVector<ACell> sessions = RT.ensureVector(RT.getIn(listed, "sessions"));
				assertEquals(1, sessions.count(), "current and policy-hidden sessions must be omitted");
				assertEquals(visible.toHexString(),
					RT.getIn(sessions.get(0), Fields.SESSION_ID).toString());
		}
	}

	private ACell readPastSession(RequestContext ctx, Blob sid) {
		return engine.jobs().invokeOperation("v/ops/agent/session-read",
			Maps.of(Fields.SESSION_ID, sid.toHexString()), ctx).awaitResult(5000);
	}

	private static ACell turn(String role, String content, long ts) {
		return Maps.of("role", role, "content", content, "ts", ts);
	}

	@SuppressWarnings("unchecked")
	private static void setConversation(AgentState agent, Blob sid, ACell... turns) {
		agent.ensureSession(sid, Strings.create("test-caller"));
		AVector<ACell> conversation = Vectors.empty();
		for (ACell turn : turns) conversation = conversation.conj(turn);
		final AVector<ACell> stored = conversation;
		assertTrue(agent.updateSessionFrames(sid, null, frames -> {
			AMap<AString, ACell> root = (AMap<AString, ACell>) frames.get(0);
			return frames.assoc(0, root.assoc(AgentState.KEY_CONVERSATION, stored));
		}));
	}
}
