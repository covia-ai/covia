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
import covia.grid.Status;
import covia.venue.Admission;
import covia.venue.Agent;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Configuring an agent acts with its owner's authority (UCAN.md §5.4): whoever
 * may create, update or fork an owner's agents sets their {@code caps} and
 * {@code accepts} as it chooses. Caps only narrow what the agent does in its
 * owner's namespace and reach nothing of anyone else's. A step runs under its
 * agent's own record, whatever its input says.
 */
public class AgentConfigAuthorityTest {

	private static final AString INVOKE = Strings.intern("invoke");
	private static final AString CAPS = Strings.intern("caps");

	final Engine engine = TestEngine.ENGINE;
	AKeyPair aliceKP, bobKP;
	AString alice, bob;
	RequestContext ALICE, BOB;

	@BeforeEach
	void setup() {
		aliceKP = AKeyPair.generate();
		bobKP = AKeyPair.generate();
		alice = UCAN.toDIDKey(aliceKP.getAccountKey());
		bob = UCAN.toDIDKey(bobKP.getAccountKey());
		ALICE = RequestContext.of(alice);
		BOB = RequestContext.of(bob);
	}

	private Job run(String op, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		try { job.awaitResult(10000); } catch (Exception ignored) {}
		return job;
	}

	private static void assertComplete(Job job) {
		assertEquals(Status.COMPLETE, job.getStatus(), job.getErrorMessage());
	}

	private static AMap<AString, ACell> config(AVector<ACell> caps) {
		AMap<AString, ACell> config = Maps.of(Fields.OPERATION, "v/ops/llmagent/chat",
			"llmOperation", "v/test/ops/toolllm");
		return (caps == null) ? config : config.assoc(CAPS, caps);
	}

	private static ACell cap(String with, AString can) {
		return Capability.create(Strings.create(with), can);
	}

	private Agent agent(AString owner, String id) {
		var user = engine.getVenueState().users().user(owner);
		return (user == null) ? null : user.agent(id);
	}

	private ACell capsOf(AString owner, String id) {
		return RT.getIn(agent(owner, id).getConfig(), CAPS);
	}

	/** Bob under Alice's hour-long grant of {@code caps}. */
	private RequestContext bobWith(ACell... caps) {
		return BOB.withProofs(Vectors.of(UCAN.create(aliceKP, bobKP.getAccountKey(),
			System.currentTimeMillis() / 1000 + 3600, Vectors.of((Object[]) caps), Vectors.empty()).toMap()));
	}

	/** A scoped agent of Alice's: may create sub-agents, use w/data/ and invoke. */
	private RequestContext parent() {
		return RequestContext.ofAgent(alice, Strings.create("parent")).withCaps(Vectors.of(
			cap("g/", Abilities.AGENT_CREATE),
			cap("w/data/", Capability.CRUD),
			Maps.of(Capability.CAN, INVOKE)));
	}

	@Test
	public void testScopedAgentCreatesSubAgentsWithAnyCaps() {
		RequestContext parent = parent();
		AVector<ACell> wider = Vectors.of(cap("w/", Capability.CRUD));
		assertComplete(run("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "wider", Fields.CONFIG, config(wider)), parent));
		assertComplete(run("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "uncapped", Fields.CONFIG, config(null)), parent));
		assertComplete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "defaulted"), parent));
		assertEquals(wider, capsOf(alice, "wider"));
		assertNull(capsOf(alice, "uncapped"));
		assertNotNull(agent(alice, "defaulted"));
	}

	@Test
	public void testDelegateConfiguresAnOwnersAgent() {
		String address = alice + "/g/delegated";
		AString agentRef = Strings.create(address);
		assertComplete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, address,
			Fields.CONFIG, config(null)), bobWith(cap(alice + "/g/", Abilities.AGENT_CREATE))));
		assertNull(capsOf(alice, "delegated"));
		assertFalse(engine.crossUserAllows(BOB, agentRef, Abilities.AGENT_REQUEST),
			"creating an agent does not admit its creator");

		assertComplete(run("v/ops/agent/update", Maps.of(Fields.AGENT_ID, address,
			Fields.CONFIG, Maps.of(Fields.ACCEPTS, Vectors.of(bob))),
			bobWith(cap(address, Abilities.AGENT_WRITE))));
		assertTrue(engine.crossUserAllows(BOB, agentRef, Abilities.AGENT_REQUEST));
	}

	@Test
	public void testScopedAgentForksAnUncappedAgent() {
		assertComplete(run("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "source", Fields.CONFIG, config(null)), ALICE));
		RequestContext forker = RequestContext.ofAgent(alice, Strings.create("forker")).withCaps(Vectors.of(
			cap("g/", Abilities.AGENT_CREATE), cap("g/source", Abilities.AGENT_FORK),
			Maps.of(Capability.CAN, INVOKE)));
		assertComplete(run("v/ops/agent/fork",
			Maps.of("sourceId", "source", Fields.AGENT_ID, "copy"), forker));
		assertNull(capsOf(alice, "copy"));
	}

	@Test
	public void testOwnerOpensAnAgentToAnyone() {
		assertComplete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "open",
			Fields.CONFIG, config(null)
				.assoc(Fields.ACCEPTS, Admission.ANYONE)
				.assoc(Fields.A2A, Maps.of("public", true))), ALICE));
		assertTrue(engine.crossUserAllows(BOB, Strings.create(alice + "/g/open"), Abilities.AGENT_REQUEST));
	}

	@Test
	public void testCapsReachNothingOfAnothers() {
		assertComplete(run("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/shared/doc", Fields.VALUE, "Bob's"), BOB));
		AString doc = Strings.create(bob + "/w/shared/doc");
		assertComplete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "reader",
			Fields.CONFIG, config(Vectors.of(cap(bob + "/w/shared/", Capability.CRUD_READ)))), ALICE));
		RequestContext reader = engine.stepContext(ALICE, Strings.create("reader"));
		assertFalse(engine.authorityCovers(reader, doc, Capability.CRUD_READ),
			"caps over Bob's data are not a grant from Bob");
		assertTrue(engine.authorityCovers(BOB, doc, Capability.CRUD_READ));
	}

	@Test
	public void testOnlyAnAgentOrItsOwnerRunsItsStep() {
		AVector<ACell> everything = Vectors.of(Maps.of(Capability.CAN, Capability.TOP));
		assertComplete(run("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "wide", Fields.CONFIG, config(everything)), ALICE));
		RequestContext sibling = RequestContext.ofAgent(alice, Strings.create("sibling")).withCaps(Vectors.of(
			cap("v/ops/llmagent/chat", INVOKE), cap("v/test/ops/toolllm", INVOKE)));
		Job borrowed = run("v/ops/llmagent/chat", Maps.of(Fields.AGENT_ID, "wide",
			Agent.KEY_CONFIG, Maps.of("llmOperation", "v/test/ops/toolllm"),
			Fields.MESSAGES, Vectors.of(Maps.of("role", "user", "content", "issue-334-read"))), sibling);
		assertEquals(Status.FAILED, borrowed.getStatus());
		assertTrue(borrowed.getErrorMessage().contains(
			Engine.NOT_AGENTS_STEP.formatted(sibling.getCallerDID(), "wide")), borrowed.getErrorMessage());
	}

	@Test
	public void testAStepRunsUnderItsAgentsOwnCaps() {
		assertComplete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "narrow",
			Fields.CONFIG, config(Vectors.of(cap("v/test/ops/toolllm", INVOKE)))
				.assoc(Fields.TOOLS, Vectors.of("v/ops/covia/read"))), ALICE));
		// Whatever caps the step's input names, it runs under the agent's record.
		ACell widened = Maps.of(Fields.AGENT_ID, "narrow",
			Agent.KEY_CONFIG, Maps.of("llmOperation", "v/test/ops/toolllm",
				Fields.TOOLS, Vectors.of("v/ops/covia/read"),
				"caps", Vectors.of(Maps.of(Capability.CAN, Capability.TOP))),
			Fields.MESSAGES, Vectors.of(Maps.of("role", "user", "content", "issue-334-read")));
		Job chat = run("v/ops/llmagent/chat", widened, ALICE);
		assertComplete(chat);
		assertTrue(RT.getIn(chat.getOutput(), Fields.RESPONSE).toString().contains("crud/read"),
			"the resource stays outside the agent's caps");

		Job none = run("v/ops/llmagent/chat", Maps.of(Fields.AGENT_ID, "absent",
			Fields.MESSAGES, Vectors.of(Maps.of("content", "hi"))), ALICE);
		assertEquals(Status.FAILED, none.getStatus());
		assertTrue(none.getErrorMessage().contains(Engine.NO_SUCH_AGENT.formatted(
			covia.grid.Principals.agentDID(alice, Strings.create("absent")))), none.getErrorMessage());
	}
}
