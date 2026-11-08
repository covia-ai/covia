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

	private boolean covers(RequestContext ctx, String resource, AString ability) {
		return engine.authorityCovers(ctx, Strings.create(resource), ability);
	}

	private RequestContext as(String id) {
		return engine.context(alice, agentDID(id));
	}

	@Test
	public void testAnAgentsAuthorityIsItsControllersNarrowedByItsCaps() {
		RequestContext parent = parent();
		assertComplete(create("uncapped", null, parent));
		assertTrue(covers(as("uncapped"), "w/shared/doc", READ), "within its creator's caps");
		assertFalse(covers(as("uncapped"), "w/private", READ), "no caps of its own give it nothing more than its creator");

		assertComplete(create("wider", Vectors.of(cap("w/", Capability.CRUD)), parent));
		assertTrue(covers(as("wider"), "w/shared/doc", READ));
		assertFalse(covers(as("wider"), "w/shared/doc", Capability.CRUD_WRITE), "caps cannot exceed its controller's");
		assertFalse(covers(as("wider"), "w/private", READ));

		assertComplete(create("narrower", Vectors.of(cap("w/shared/sub/", READ)), parent));
		assertTrue(covers(as("narrower"), "w/shared/sub/doc", READ));
		assertFalse(covers(as("narrower"), "w/shared/other", READ), "its caps still narrow");

		AString uncapped = Strings.create("uncapped");
		RequestContext step = engine.stepContext(engine.harnessContext(alice, uncapped), uncapped);
		assertTrue(covers(step, "w/shared/doc", READ));
		assertFalse(covers(step, "w/private", READ), "its tool calls are bound the same way");

		RequestContext narrowed = engine.harnessContext(alice, Strings.create("wider"))
			.narrowedTo(Vectors.of(cap("w/shared/sub/", READ)));
		assertTrue(covers(narrowed, "w/shared/sub/doc", READ));
		assertFalse(covers(narrowed, "w/shared/other", READ), "a harness narrowed by caps stays within its controller's");
		assertFalse(covers(engine.harnessContext(alice, Strings.create("wider")).narrowedTo(Vectors.of(cap("w/", READ))),
			"w/private", READ), "caps cannot lift it past its controller's");
	}

	@Test
	public void testAChainOfControllersNarrowsAtEveryLink() {
		RequestContext parent = parent();
		assertComplete(create("middle", Vectors.of(cap("g/", Abilities.AGENT_CREATE), cap("w/shared/sub/", READ),
			Maps.of(Capability.CAN, Abilities.INVOKE)), parent));
		assertComplete(create("leaf", null, as("middle")));
		assertEquals(agentDID("middle"), agent("leaf").getController());

		assertTrue(covers(as("leaf"), "w/shared/sub/doc", READ));
		assertFalse(covers(as("leaf"), "w/shared/other", READ));
	}

	@Test
	public void testAnOwnersAgentKeepsItsCapsAsWritten() {
		AVector<ACell> caps = Vectors.of(cap("w/notes/", READ));
		assertComplete(create("own", caps, ALICE));
		assertEquals(caps, as("own").getCaps());
		assertFalse(as("own").hasSeparateAuthority());
		assertTrue(covers(as("own"), "w/notes/a", READ));

		assertComplete(create("free", null, ALICE));
		assertNull(as("free").getCaps(), "an agent its owner controls with no caps has its owner's authority");
	}

	@Test
	public void testAControllerBoundsItsAgentsAsItStands() {
		RequestContext parent = parent();
		assertComplete(create("child", null, parent));
		RequestContext child = as("child");
		assertTrue(covers(child, "w/shared/doc", READ));

		assertComplete(run("v/ops/agent/update", Maps.of(Fields.AGENT_ID, "parent", Fields.CONFIG, Maps.of("caps",
			Vectors.of(cap("w/other/", READ)))), ALICE));
		assertFalse(covers(child, "w/shared/doc", READ), "work already running is bound by the change");

		assertComplete(run("v/ops/agent/delete", Maps.of(Fields.AGENT_ID, "parent"), ALICE));
		assertFalse(covers(child, "w/other/doc", READ), "a controller that is gone gives nothing");
	}

	@Test
	public void testALoopOfControllersGrantsNothing() {
		assertComplete(create("x", null, ALICE));
		assertComplete(create("y", null, ALICE));
		assertComplete(run("v/ops/agent/update", Maps.of(Fields.AGENT_ID, "x", Fields.CONTROLLER, agentDID("y")), ALICE));
		assertComplete(run("v/ops/agent/update", Maps.of(Fields.AGENT_ID, "y", Fields.CONTROLLER, agentDID("x")), ALICE));

		assertFalse(covers(as("x"), "w/anything", READ));
		assertFalse(covers(as("y"), "w/anything", READ));
	}

	@Test
	public void testConfiguringAnOwnersAgentNeedsTheOwnersAuthority() {
		RequestContext parent = parent();
		assertComplete(create("own", null, ALICE));
		AString address = Strings.create(alice + "/g/own");

		assertRefused(update("own", parent), Engine.NOT_CONTROLLERS.formatted(address, alice));
		assertRefused(run("v/ops/agent/update", Maps.of(Fields.AGENT_ID, "parent", Fields.CONFIG, Maps.of("caps",
			Vectors.empty())), parent), Engine.NOT_CONTROLLERS.formatted(alice + "/g/parent", alice));
		assertRefused(update(address.toString(), bobWith(cap(address.toString(), Abilities.AGENT_WRITE))),
			Engine.NOT_CONTROLLERS.formatted(address, alice));

		assertComplete(run("v/ops/agent/update", Maps.of(Fields.AGENT_ID, "own", "state", Maps.of("step", 1)), parent));
		assertComplete(run("v/ops/agent/suspend", Maps.of(Fields.AGENT_ID, "own"), parent));
		assertComplete(run("v/ops/agent/resume", Maps.of(Fields.AGENT_ID, "own"), parent));

		assertComplete(create("uncapped", null, ALICE));
		assertComplete(update("own", as("uncapped")));
		assertComplete(update("own", ALICE));
	}

	@Test
	public void testAnAgentAnotherNamespaceControlsHoldsOnlyItsOwnAuthority() {
		String address = alice + "/g/bobs";
		assertComplete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, address,
			Fields.CONFIG, config(Vectors.of(cap(bob + "/w/notes/", READ)))),
			bobWith(cap(alice + "/g/", Abilities.AGENT_CREATE))));

		RequestContext bobs = as("bobs");
		assertTrue(bobs.hasSeparateAuthority());
		assertEquals(agentDID("bobs"), bobs.getCallerDID(), "it is still the agent acting");
		assertTrue(covers(bobs, bob + "/w/notes/a", READ));
		assertFalse(covers(bobs, bob + "/w/other", READ), "its caps narrow its controller's authority");
		assertFalse(covers(bobs, "w/notes/a", READ), "Alice's data is not Bob's");

		assertEquals(agentDID("bobs"), Engine.actingPrincipal(bobs), "what it creates stays within it");
		assertFalse(engine.holdsAuthorityOf(bobs, bob), "narrowed, it does not hold Bob's whole authority");
		assertTrue(engine.holdsAuthorityOf(bobs, agentDID("bobs")));
	}

	private String notes(String id) {
		return alice + "/g/" + id + "/n/x";
	}

	private AVector<ACell> capsOver(String with) {
		return Vectors.of(cap(with, READ));
	}

	/** Alice's agent {@code id}, handed to {@code controller}. */
	private void createUnder(String id, AVector<ACell> caps, AString controller) {
		assertComplete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id, Fields.CONFIG, config(caps),
			Fields.CONTROLLER, controller), ALICE));
	}

	@Test
	public void testNInCapsNamesTheAgentsOwnNotes() {
		assertComplete(create("mine", capsOver("n/"), ALICE));
		assertComplete(create("other", null, ALICE));
		assertTrue(covers(as("mine"), notes("mine"), READ));
		assertFalse(covers(as("mine"), notes("other"), READ), "n/ is its own, not another agent's");
		assertFalse(covers(as("mine"), "w/x", READ));

		assertComplete(create("silent", capsOver("w/"), ALICE));
		assertFalse(covers(as("silent"), notes("silent"), READ), "caps that leave n/ out leave it out");

		assertComplete(create("owner-path", capsOver(alice + "/n/"), ALICE));
		assertFalse(covers(as("owner-path"), notes("owner-path"), READ), "n/ after a DID names no place");
	}

	@Test
	public void testAControllerHasItsAgentsNamespaces() {
		RequestContext parent = parent();
		assertComplete(create("child", null, parent));
		assertTrue(covers(parent, notes("child"), READ), "whatever its own caps say");
		assertTrue(covers(parent, notes("child"), Capability.CRUD_WRITE));
		assertTrue(covers(as("child"), notes("child"), READ), "the agent has it by acting under its controller");
		assertFalse(covers(as("child"), "w/private", READ), "and nothing more for it");
		assertFalse(covers(parent, "w/private", READ), "control reaches only its agents");

		assertComplete(create("grandchild", capsOver("n/"), as("child")));
		assertTrue(covers(parent, notes("grandchild"), READ), "through the agents it controls");
		assertTrue(covers(as("grandchild"), notes("grandchild"), READ));

		assertComplete(create("silent", capsOver("w/shared/"), parent));
		assertFalse(covers(as("silent"), notes("silent"), READ), "caps that leave n/ out still narrow");
		assertFalse(covers(as("child"), notes("parent"), READ), "control runs down the chain, not up");
	}

	@Test
	public void testAControllerInAnotherNamespaceHasItsAgentsNamespaces() {
		String address = alice + "/g/bobs";
		assertComplete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, address, Fields.CONFIG, config(null)),
			bobWith(cap(alice + "/g/", Abilities.AGENT_CREATE))));
		assertTrue(covers(RequestContext.of(bob), notes("bobs"), READ), "Bob reaches it himself, with no grant");
		assertTrue(covers(as("bobs"), notes("bobs"), READ));

		assertComplete(create("alices", null, ALICE));
		assertFalse(covers(RequestContext.of(bob), notes("alices"), READ), "only the agents he controls");
		assertFalse(covers(as("bobs"), notes("alices"), READ));
	}

	@Test
	public void testAFullyNamedAgentPathDependsOnTheController() {
		assertComplete(create("target", null, ALICE));
		assertTrue(covers(ALICE, notes("target"), READ));

		// Owner-controlled: its caps may name any agent of the owner's.
		assertComplete(create("byOwner", capsOver(alice + "/g/target/n/"), ALICE));
		assertTrue(covers(as("byOwner"), notes("target"), READ));
		assertComplete(create("byOwnerBare", capsOver("g/target/n/"), ALICE));
		assertTrue(covers(as("byOwnerBare"), notes("target"), READ));

		// Under a capped controller, the name alone does not reach what the controller cannot.
		RequestContext parent = parent();
		assertComplete(create("underParent", capsOver(alice + "/g/target/n/"), parent));
		assertFalse(covers(as("underParent"), notes("target"), READ), "its controller has no authority there");

		// A controller that controls the target passes that on, within the caps.
		createUnder("controlled", null, agentDID("parent"));
		assertComplete(create("sibling", capsOver(alice + "/g/controlled/n/"), parent));
		assertTrue(covers(as("sibling"), notes("controlled"), READ));
		assertComplete(create("uncappedSibling", null, parent));
		assertTrue(covers(as("uncappedSibling"), notes("controlled"), READ), "it has everything its controller has");
		assertFalse(covers(as("sibling"), notes("target"), READ));

		// A controller's cap over the target reaches it too.
		assertComplete(create("wide", Vectors.of(cap("g/", Abilities.AGENT_CREATE), cap("g/target/n/", READ),
			Maps.of(Capability.CAN, Abilities.INVOKE)), ALICE));
		assertComplete(create("underWide", capsOver(alice + "/g/target/n/"), as("wide")));
		assertTrue(covers(as("underWide"), notes("target"), READ));
	}

	@Test
	public void testAFullyNamedForeignAgentPathDependsOnTheController() {
		AString bobsId = Strings.create("z");
		assertComplete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "z", Fields.CONFIG, config(null)), RequestContext.of(bob)));
		String bobsNotes = bob + "/g/" + bobsId + "/n/x";

		assertComplete(create("aliceOwn", capsOver(bobsNotes), ALICE));
		assertFalse(covers(as("aliceOwn"), bobsNotes, READ), "a cap reaches nothing of another identity's");

		assertComplete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, alice + "/g/bobs", Fields.CONFIG,
			config(capsOver(bob + "/g/z/n/"))), bobWith(cap(alice + "/g/", Abilities.AGENT_CREATE))));
		assertTrue(covers(as("bobs"), bobsNotes, READ), "Bob's authority, narrowed by the caps");
		assertFalse(covers(as("bobs"), notes("bobs"), READ), "its caps leave its own notes out");
	}

	@Test
	public void testControlStopsAtTheAgentsOwnDID() {
		RequestContext parent = parent();
		assertComplete(create("child", null, parent));
		String keyring = agentDID("child") + "/x/crypto/keys/k";
		assertFalse(covers(parent, keyring, READ), "what is held under the agent's own DID is the agent's");
		assertFalse(covers(RequestContext.of(bob), keyring, READ));
	}

	@Test
	public void testASudoDelegateHoldsNoControllersAuthority() {
		RequestContext sudo = engine.sudoContext(bobWith(cap(alice.toString(), Abilities.USER_SUDO)), alice);
		assertEquals(alice, sudo.getUserDID());
		assertFalse(engine.holdsAuthorityOf(sudo, alice), "its scope is its presented grants, so it cannot configure her agents");
		assertEquals(alice, Engine.actingPrincipal(sudo), "what it creates, she controls");
	}

	@Test
	public void testTheCapabilityNoticeStatesWhatTheControllerLeaves() {
		RequestContext parent = parent();
		assertComplete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "told", Fields.CONFIG,
			config(null).assoc(Fields.TOOLS, Vectors.of((ACell) Strings.create("v/ops/covia/read")))), parent));
		Job context = run("v/ops/agent/context", Maps.of(Fields.AGENT_ID, "told"), ALICE);
		assertComplete(context);
		assertTrue(context.getOutput().toString().contains("w/shared/"), "the agent is told its creator's limits");
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
