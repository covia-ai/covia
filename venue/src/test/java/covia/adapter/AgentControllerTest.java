package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.UserExports;

/**
 * An agent's controller is the principal whose authority its harness acts
 * with, and whose authority changing the agent needs (UCAN.md §5.4). It is the
 * agent's owner unless another is stored: whoever creates or forks an agent
 * controls it, or a principal whose authority the creator holds.
 */
public class AgentControllerTest {

	private static final AString READ = Capability.CRUD_READ;

	final Engine engine = TestEngine.ENGINE;
	AKeyPair aliceKP, bobKP;
	AString alice, bob;
	RequestContext ALICE;

	@BeforeEach
	void setup() {
		aliceKP = AKeyPair.generate();
		bobKP = AKeyPair.generate();
		alice = UCAN.toDIDKey(aliceKP.getAccountKey());
		bob = UCAN.toDIDKey(bobKP.getAccountKey());
		ALICE = RequestContext.of(alice);
	}

	private Job run(String op, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		try { job.awaitResult(10000); } catch (Exception ignored) {}
		return job;
	}

	private static void assertComplete(Job job) {
		assertEquals(Status.COMPLETE, job.getStatus(), job.getErrorMessage());
	}

	private static void assertRefused(Job job, String reason) {
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains(reason), job.getErrorMessage());
	}

	private static AMap<AString, ACell> config(AVector<ACell> caps) {
		AMap<AString, ACell> config = Maps.of(Fields.OPERATION, "v/ops/llmagent/chat",
			"llmOperation", "v/test/ops/toolllm");
		return (caps == null) ? config : config.assoc(Strings.create("caps"), caps);
	}

	private static ACell cap(String with, AString can) {
		return Capability.create(Strings.create(with), can);
	}

	private Job create(String id, AVector<ACell> caps, RequestContext ctx) {
		return run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id, Fields.CONFIG, config(caps)), ctx);
	}

	private Job update(String id, RequestContext ctx) {
		return run("v/ops/agent/update", Maps.of(Fields.AGENT_ID, id,
			Fields.CONFIG, Maps.of("systemPrompt", "be brief")), ctx);
	}

	private AString agentDID(String id) {
		return Principals.agentDID(alice, Strings.create(id));
	}

	private AgentState agent(AString owner, String id) {
		return engine.getVenueState().users().user(owner).agent(id);
	}

	private AgentState agent(String id) {
		return agent(alice, id);
	}

	/** Bob under Alice's hour-long grant of {@code caps}. */
	private RequestContext bobWith(ACell... caps) {
		return RequestContext.of(bob).withProofs(Vectors.of(UCAN.create(aliceKP, bobKP.getAccountKey(),
			System.currentTimeMillis() / 1000 + 3600, Vectors.of((Object[]) caps), Vectors.empty()).toMap()));
	}

	/** Alice's agent "parent", which may create, change and fork her agents, and read w/shared/. */
	private RequestContext parent() {
		assertComplete(create("parent", Vectors.of(
			cap("g/", Abilities.AGENT_CREATE), cap("g/", Abilities.AGENT_WRITE), cap("g/", Abilities.AGENT_FORK),
			cap("w/shared/", READ), Maps.of(Capability.CAN, Abilities.INVOKE)), ALICE));
		return engine.context(alice, agentDID("parent"));
	}

	@Test
	public void testAnOwnersAgentStoresNoController() {
		assertComplete(create("own", null, ALICE));
		assertNull(agent("own").getRecord().get(AgentState.KEY_CONTROLLER));
		assertEquals(alice, agent("own").getController());
		assertFalse(engine.harnessContext(alice, Strings.create("own")).hasSeparateAuthority());

		Job info = run("v/ops/agent/info", Maps.of(Fields.AGENT_ID, "own"), ALICE);
		assertComplete(info);
		assertEquals(alice, RT.getIn(info.getOutput(), Fields.CONTROLLER));
	}

	@Test
	public void testAnAgentControlsTheAgentsItCreatesAndForks() {
		RequestContext parent = parent();
		assertComplete(create("child", null, parent));
		assertEquals(agentDID("parent"), agent("child").getController());

		RequestContext harness = engine.harnessContext(alice, Strings.create("child"));
		assertTrue(harness.hasSeparateAuthority());
		assertTrue(engine.authorityCovers(harness, Strings.create("w/shared/doc"), READ));
		assertFalse(engine.authorityCovers(harness, Strings.create("w/private"), READ),
			"the uncapped child's harness is bound by its controller");

		assertComplete(run("v/ops/agent/fork", Maps.of("sourceId", "child", Fields.AGENT_ID, "copy"), parent));
		assertEquals(agentDID("parent"), agent("copy").getController());

		Job list = run("v/ops/agent/list", Maps.empty(), ALICE);
		assertComplete(list);
		assertTrue(list.getOutput().toString().contains(agentDID("parent").toString()), "listed with its controller");
	}

	@Test
	public void testOnlyTheControllersAuthorityChangesItsAgent() {
		RequestContext parent = parent();
		assertComplete(create("child", null, parent));
		assertComplete(update("child", parent));
		assertComplete(update("child", ALICE));

		assertComplete(create("uncapped", null, ALICE));
		assertComplete(update("child", engine.context(alice, agentDID("uncapped"))));

		assertComplete(create("sibling", Vectors.of(cap("g/", Abilities.AGENT_WRITE),
			Maps.of(Capability.CAN, Abilities.INVOKE)), ALICE));
		AString address = Strings.create(alice + "/g/child");
		assertRefused(update("child", engine.context(alice, agentDID("sibling"))),
			Engine.NOT_CONTROLLERS.formatted(address, agentDID("parent")));
	}

	@Test
	public void testADelegateControlsTheAgentsItCreates() {
		String address = alice + "/g/bobs";
		assertComplete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, address, Fields.CONFIG, config(null)),
			bobWith(cap(alice + "/g/", Abilities.AGENT_CREATE))));
		assertEquals(bob, agent("bobs").getController());

		RequestContext harness = engine.harnessContext(alice, Strings.create("bobs"));
		assertFalse(engine.authorityCovers(harness, Strings.create("w/notes"), READ), "Alice's data is not Bob's");
		assertTrue(engine.authorityCovers(harness, Strings.create(bob + "/w/notes"), READ));

		assertRefused(update("bobs", ALICE), Engine.NOT_CONTROLLERS.formatted(address, bob));
		assertComplete(update(address, bobWith(cap(address, Abilities.AGENT_WRITE))));
		assertComplete(run("v/ops/agent/delete", Maps.of(Fields.AGENT_ID, "bobs"), ALICE));
	}

	@Test
	public void testANamedControllerNeedsItsAuthority() {
		RequestContext parent = parent();
		assertComplete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "named", Fields.CONFIG, config(null),
			Fields.CONTROLLER, agentDID("parent")), ALICE));
		assertEquals(agentDID("parent"), agent("named").getController());

		assertRefused(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "escape", Fields.CONFIG, config(null),
			Fields.CONTROLLER, alice), parent), Engine.CONTROLLER_NOT_HELD.formatted(alice));
		assertRefused(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, alice + "/g/claimed",
			Fields.CONFIG, config(null), Fields.CONTROLLER, alice), bobWith(cap(alice + "/g/", Abilities.AGENT_CREATE))),
			Engine.CONTROLLER_NOT_HELD.formatted(alice));
		assertRefused(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "bad", Fields.CONFIG, config(null),
			Fields.CONTROLLER, "nobody"), ALICE), AgentAdapter.CONTROLLER_NOT_DID);
		assertNull(agent("escape"));
		assertNull(agent("claimed"));

		assertComplete(run("v/ops/agent/update", Maps.of(Fields.AGENT_ID, "named", Fields.CONTROLLER, alice), ALICE));
		assertNull(agent("named").getRecord().get(AgentState.KEY_CONTROLLER), "handed back to its owner");
	}

	@Test
	public void testACloneDropsControllersItsTargetDoesNotHold() {
		RequestContext parent = parent();
		assertComplete(create("child", null, parent));
		assertComplete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, alice + "/g/bobs", Fields.CONFIG, config(null)),
			bobWith(cap(alice + "/g/", Abilities.AGENT_CREATE))));

		AString carol = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		new UserExports(engine).cloneUser(alice, carol);
		assertEquals(carol, agent(carol, "child").getController(), "Alice's agent is not Carol's");
		assertEquals(carol, agent(carol, "bobs").getController(), "nor is Bob");
		assertEquals(agentDID("parent"), agent("child").getController(), "the source keeps its own");
	}
}
