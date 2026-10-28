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
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Conferring authority only narrows it: an agent's {@code caps}, {@code accepts}
 * and public A2A scope are written only by a caller that holds what they confer
 * ({@link Engine#requireConferrable}). Agents may create sub-agents, never more
 * capable ones, and a delegated create or update cannot plant authority the
 * delegation does not carry.
 */
public class AgentConferralTest {

	private static final AString INVOKE = Strings.intern("invoke");

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

	private static void assertNotConferrable(Job job) {
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains(Engine.CONFERRAL_DENIED), job.getErrorMessage());
	}

	private static AMap<AString, ACell> config(AVector<ACell> caps) {
		AMap<AString, ACell> config = Maps.of(Fields.OPERATION, "v/ops/llmagent/chat",
			"llmOperation", "v/test/ops/toolllm");
		return (caps == null) ? config : config.assoc(Strings.create("caps"), caps);
	}

	private static ACell cap(String with, AString can) {
		return Capability.create(Strings.create(with), can);
	}

	private AgentState agent(AString owner, String id) {
		var user = engine.getVenueState().users().get(owner);
		return (user == null) ? null : user.agent(id);
	}

	private RequestContext bobWith(ACell... caps) {
		long exp = System.currentTimeMillis() / 1000 + 3600;
		return BOB.withProofs(Vectors.of(UCAN.create(aliceKP, bobKP.getAccountKey(), exp,
			Vectors.of((Object[]) caps), Vectors.empty()).toMap()));
	}

	/** A scoped agent: may create sub-agents and read and write w/data/. */
	private RequestContext parent() {
		return RequestContext.ofAgent(alice, Strings.create("parent")).withCaps(Vectors.of(
			cap("g/", Abilities.AGENT_CREATE),
			cap("w/data/", Capability.CRUD),
			Maps.of(Capability.CAN, INVOKE)));
	}

	@Test
	public void testSubAgentWithinParentScope() {
		AVector<ACell> narrower = Vectors.of(
			cap("w/data/reports/", Capability.CRUD_READ),
			Maps.of(Capability.CAN, INVOKE));
		assertComplete(run("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "child", Fields.CONFIG, config(narrower)), parent()));
		assertEquals(narrower, RT.getIn(agent(alice, "child").getConfig(), "caps"));
	}

	@Test
	public void testSubAgentCannotExceedParent() {
		RequestContext parent = parent();
		assertNotConferrable(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "wider",
			Fields.CONFIG, config(Vectors.of(cap("w/", Capability.CRUD)))), parent));
		assertNotConferrable(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "everything",
			Fields.CONFIG, config(Vectors.of(Maps.of(Capability.CAN, Capability.TOP)))), parent));
		// No caps at all is unrestricted, the widest scope there is
		assertNotConferrable(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "uncapped",
			Fields.CONFIG, config(null)), parent));
		assertNotConferrable(run("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "defaulted"), parent));
		for (String id : new String[] {"wider", "everything", "uncapped", "defaulted"}) {
			assertNull(agent(alice, id), id + " must not be created");
		}
	}

	@Test
	public void testDelegatedCreateConfersOnlyTheDelegation() {
		String address = alice + "/g/evil";
		RequestContext delegated = bobWith(
			cap(alice + "/g/", Abilities.AGENT_CREATE),
			cap(alice + "/w/shared/", Capability.CRUD_READ));

		assertNotConferrable(run("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, address, Fields.CONFIG, config(null)), delegated));
		// Admitting himself would confer talking rights the delegation lacks
		assertNotConferrable(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, address,
			Fields.CONFIG, config(Vectors.of(cap("w/shared/", Capability.CRUD_READ)))
				.assoc(Fields.ACCEPTS, Vectors.of(bob))), delegated));
		assertNull(agent(alice, "evil"));

		// Bare caps name the owner's namespace, where the delegation applies
		assertComplete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, address,
			Fields.CONFIG, config(Vectors.of(cap("w/shared/", Capability.CRUD_READ)))), delegated));
		assertFalse(engine.crossUserAllows(BOB, Strings.create(address), Abilities.AGENT_REQUEST),
			"the delegate is not admitted to talk to the agent it created");
	}

	@Test
	public void testDelegatedUpdateCannotWidenAuthority() {
		assertComplete(run("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "big", Fields.CONFIG, config(null)), ALICE));
		RequestContext writer = bobWith(cap(alice + "/g/big", Abilities.AGENT_WRITE));
		String address = alice + "/g/big";

		// Leaving authority alone needs no more than agent/write
		assertComplete(run("v/ops/agent/update", Maps.of(Fields.AGENT_ID, address,
			Fields.CONFIG, Maps.of("systemPrompt", "be brief")), writer));

		assertNotConferrable(run("v/ops/agent/update", Maps.of(Fields.AGENT_ID, address,
			Fields.CONFIG, Maps.of(Fields.ACCEPTS, Vectors.of(bob))), writer));
		assertNotConferrable(run("v/ops/agent/update", Maps.of(Fields.AGENT_ID, address,
			Fields.CONFIG, Maps.of(Fields.A2A, Maps.of("public", true, "caps", "unrestricted"))), writer));
		AMap<AString, ACell> stored = agent(alice, "big").getConfig();
		assertNull(stored.get(Fields.ACCEPTS));
		assertNull(stored.get(Fields.A2A));
	}

	@Test
	public void testForkCannotWidenAuthority() {
		assertComplete(run("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "source", Fields.CONFIG, config(null)), ALICE));
		RequestContext forker = RequestContext.ofAgent(alice, Strings.create("forker")).withCaps(Vectors.of(
			cap("g/", Abilities.AGENT_CREATE), cap("g/source", Abilities.AGENT_FORK),
			Maps.of(Capability.CAN, INVOKE)));
		assertNotConferrable(run("v/ops/agent/fork",
			Maps.of("sourceId", "source", Fields.AGENT_ID, "copy"), forker));
		assertNull(agent(alice, "copy"));
	}

	@Test
	public void testOwnerConfersFreely() {
		assertComplete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "open",
			Fields.CONFIG, config(null)
				.assoc(Fields.ACCEPTS, Admission.ANYONE)
				.assoc(Fields.A2A, Maps.of("public", true))), ALICE));
		assertTrue(engine.crossUserAllows(BOB, Strings.create(alice + "/g/open"), Abilities.AGENT_REQUEST));
	}

	@Test
	public void testStoredCapsAreNotReCheckedOnRuns() {
		// Alice confers a cap over Bob's data that Bob granted her. The agent runs
		// under it without holding Bob's grant itself: conferral was checked once,
		// against Alice, when the caps were written.
		assertComplete(run("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/shared/doc", Fields.VALUE, "for Alice"), BOB));
		long exp = System.currentTimeMillis() / 1000 + 3600;
		RequestContext aliceWithGrant = ALICE.withProofs(Vectors.of(UCAN.create(bobKP,
			aliceKP.getAccountKey(), exp, Vectors.of(cap(bob + "/w/shared/", Capability.CRUD_READ)),
			Vectors.empty()).toMap()));
		assertComplete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "reader",
			Fields.CONFIG, config(Vectors.of(cap(bob + "/w/shared/", Capability.CRUD_READ),
				Maps.of(Capability.CAN, INVOKE)))), aliceWithGrant));

		Job chat = run("v/ops/agent/chat", Maps.of(Fields.AGENT_ID, "reader",
			Fields.MESSAGE, Strings.create("call echo")), ALICE);
		assertComplete(chat);
		assertTrue(RT.getIn(chat.getOutput(), Fields.RESPONSE).toString().contains("Tool returned"),
			String.valueOf(chat.getOutput()));
	}

	@Test
	public void testDirectChatCannotBorrowAnotherAgentsCaps() {
		AVector<ACell> everything = Vectors.of(Maps.of(Capability.CAN, Capability.TOP));
		assertComplete(run("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "wide", Fields.CONFIG, config(everything)), ALICE));
		RequestContext sibling = RequestContext.ofAgent(alice, Strings.create("sibling")).withCaps(Vectors.of(
			cap("v/ops/llmagent/chat", INVOKE), cap("v/test/ops/toolllm", INVOKE)));
		assertNotConferrable(run("v/ops/llmagent/chat", Maps.of(Fields.AGENT_ID, "wide",
			AgentState.KEY_CONFIG, Maps.of("llmOperation", "v/test/ops/toolllm", "caps", everything),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "call echo"))), sibling));
	}

	@Test
	public void testDirectChatCannotWidenCallerScope() {
		RequestContext scoped = RequestContext.ofAgent(alice, Strings.create("narrow")).withCaps(Vectors.of(
			cap("v/ops/llmagent/chat", INVOKE), cap("v/test/ops/toolllm", INVOKE)));
		ACell widened = Maps.of(Fields.AGENT_ID, "narrow",
			AgentState.KEY_CONFIG, Maps.of("llmOperation", "v/test/ops/toolllm",
				"caps", Vectors.of(Maps.of(Capability.CAN, Capability.TOP))),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "call echo")));
		assertNotConferrable(run("v/ops/llmagent/chat", widened, scoped));

		// Caps the caller holds may still be named
		ACell held = Maps.of(Fields.AGENT_ID, "narrow",
			AgentState.KEY_CONFIG, Maps.of("llmOperation", "v/test/ops/toolllm",
				"caps", Vectors.of(cap("v/test/ops/toolllm", INVOKE))),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "call echo")));
		Job chat = run("v/ops/llmagent/chat", held, scoped);
		assertComplete(chat);
		assertTrue(RT.getIn(chat.getOutput(), Fields.RESPONSE).toString().contains("Capability denied"),
			"the tool stays outside the caller's scope");
	}
}
