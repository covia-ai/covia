package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Principals;
import covia.venue.Agent;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.SecretStore;
import covia.venue.TestEngine;
import covia.venue.User;

/**
 * Regression for #91 and the related cross-user run-loop keying, plus the
 * agent sub-principal split.
 *
 * <p>An agent's run loop executes as its <b>harness</b>: its controller's
 * frame, driving the agent's execution in its owner's account, and its steps
 * act as the agent — all derived from the agent's address ({@code ownerDID} +
 * {@code agentId}) and record, never from the caller that woke it.
 * {@link AgentAdapter#wakeAgent} is a pure mechanism keyed on that address —
 * there is no caller parameter for an identity to leak through — and the
 * in-memory run-loop registries key on the full address, so two users' agents
 * that share a name do not collide.</p>
 *
 * <p>Each test pins both halves: the harness acts as the agent's controller
 * (here its owner, whose secrets and workspace resolve), driving the agent's
 * execution, and a step of that run acts as the agent's own sub-principal DID
 * ({@link Engine#stepContext}).</p>
 *
 * <p>Deterministic — each test drives wakes and waits on the run-loop
 * completion futures; no timing assumptions.</p>
 */
public class AgentRunLoopIdentityTest {

	final Engine engine = TestEngine.ENGINE;
	private AString ALICE_DID;
	private AString BOB_DID;

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
		BOB_DID = Strings.create(ALICE_DID.toString() + "-bob");
		TestAdapter.CAPTURED_CTX.remove(ALICE_DID);
		TestAdapter.CAPTURED_CTX.remove(BOB_DID);
	}

	/** Creates an agent owned by {@code owner} whose transition records the
	 *  context it runs under, stores an owner-only secret, and queues a message
	 *  so the loop fires. Returns the agentId. */
	private AString prepareAgent(AString owner, String name, String secretName, String secretValue) {
		AString agentId = Strings.create(name);
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/capturectx")),
			RequestContext.of(owner)).awaitResult(5000);

		User user = engine.getVenueState().users().ensure(owner);
		SecretStore.Keys encKey = SecretStore.keys(engine.getKeyPair());
		user.secrets().store(secretName, secretValue, encKey);
		queueMessage(owner, name);
		return agentId;
	}

	/** Queues a message for agent {@code name} of {@code owner}, so its loop fires. */
	private void queueMessage(AString owner, String name) {
		Agent agent = engine.getVenueState().users().ensure(owner).agent(name);
		Blob sid = Blob.fromHex("12340001123400011234000112340001");
		agent.sessions().create(sid, owner, null);
		agent.session(sid).appendPending(Maps.of(Fields.MESSAGE, Maps.of("content", "hi")));
	}

	private AgentAdapter adapter() {
		return (AgentAdapter) engine.getAdapter("agent");
	}

	@Test
	public void testRunLoopRunsUnderOwnerContext() {
		AString agentId = prepareAgent(ALICE_DID, "own-ident", "RUNLOOP_KEY", "alice-secret");
		adapter().wakeAgent(ALICE_DID, agentId, true).join();

		RequestContext seen = TestAdapter.CAPTURED_CTX.get(ALICE_DID);
		assertNotNull(seen, "transition must have executed");
		assertEquals(ALICE_DID, seen.getAuthorityDID(), "the harness is its controller's frame, here its owner's");
		assertEquals(ALICE_DID, seen.getUserDID());
		assertSame(seen, engine.authorityFrame(seen), "with its own authority");
		assertNull(seen.getCaller(), "a cycle has no caller");
		assertNull(seen.getAuthenticatedDID(), "nobody is present in a cycle");
		assertEquals(new RequestContext.ExecutionScope(ALICE_DID, agentId, null, seen.getSessionId(), null, -1L,
			seen.getClaim()), seen.getExecutionScope(), "driving the agent's execution");
		assertNull(seen.getProofs(), "run context must carry no proofs");
		assertNull(seen.getCaps(), "its owner's whole authority");
		assertEquals("alice-secret", engine.resolveSecret("RUNLOOP_KEY", seen),
			"owner-scoped secret must resolve under the run-loop context");
		RequestContext step = engine.stepContext(seen, agentId);
		assertEquals(Principals.agentDID(ALICE_DID, agentId), step.getAuthorityDID(),
			"its steps act as the agent's own sub-principal DID");
		assertEquals(ALICE_DID, step.getUserDID());
		assertTrue(step.isSubPrincipal(), "an agent's step is a sub-principal context");
	}

	/**
	 * The transition operation is a harness step: it runs unrestricted though
	 * the agent has caps, and the caps bound what it switches to the agent's
	 * own context ({@link Engine#stepContext}, UCAN.md §5.4).
	 */
	/**
	 * An agent a capped agent created runs its transition in that controller's
	 * frame, bound by the controller's caps, driving its own execution; its
	 * steps act as itself (UCAN.md §5.4).
	 */
	@Test
	public void testATransitionActsWithItsControllersAuthority() {
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "controller",
			Fields.CONFIG, Maps.of("caps", Vectors.of(
				Capability.create(Strings.create("g/"), Abilities.AGENT_CREATE),
				Capability.create(Strings.create("w/shared/"), Capability.CRUD_READ),
				Maps.of("can", Abilities.INVOKE)))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		RequestContext controller = engine.context(ALICE_DID,
			Principals.agentDID(ALICE_DID, Strings.create("controller")));
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "controlled",
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/capturectx")), controller).awaitResult(5000);
		queueMessage(ALICE_DID, "controlled");
		adapter().wakeAgent(ALICE_DID, Strings.create("controlled"), true).join();

		RequestContext harness = TestAdapter.CAPTURED_CTX.get(ALICE_DID);
		assertNotNull(harness, "transition must have executed");
		assertEquals(Principals.agentDID(ALICE_DID, Strings.create("controller")), harness.getAuthorityDID(),
			"its controller's frame");
		assertEquals(ALICE_DID, harness.getUserDID());
		assertEquals(Strings.create("controlled"), harness.getExecutionScope().agentId(), "driving its own execution");
		assertTrue(engine.authorityCovers(harness, Strings.create("w/shared/doc"), Capability.CRUD_READ));
		assertFalse(engine.authorityCovers(harness, Strings.create("w/private"), Capability.CRUD_READ),
			"bound by its controller's caps");
		assertEquals(Principals.agentDID(ALICE_DID, Strings.create("controlled")),
			engine.stepContext(harness, Strings.create("controlled")).getAuthorityDID(), "its steps act as itself");
	}

	@Test
	public void testATransitionRunsAsTheHarnessAndSwitchesToTheAgentsCaps() {
		AString agentId = Strings.create("capped-harness");
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/capturectx",
					"caps", Vectors.of(Maps.of(
						"with", Strings.create("w/public/"),
						"can", Capability.CRUD_READ)))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		queueMessage(ALICE_DID, "capped-harness");
		adapter().wakeAgent(ALICE_DID, agentId, true).join();

		RequestContext harness = TestAdapter.CAPTURED_CTX.get(ALICE_DID);
		assertNotNull(harness, "transition must have executed");
		AString publicNote = Strings.create("w/public/note");
		AString privateNote = Strings.create("w/private-note");
		assertTrue(engine.authorityCovers(harness, privateNote, Capability.CRUD_READ),
			"the transition itself is not bounded by the agent's caps");

		RequestContext agent = engine.stepContext(harness, agentId);
		assertTrue(engine.authorityCovers(agent, publicNote, Capability.CRUD_READ),
			"switched to the agent, it reads what its caps cover");
		assertFalse(engine.authorityCovers(agent, privateNote, Capability.CRUD_READ),
			"and nothing else");
	}

	@Test
	public void testJobRecordsAttributeTheActingAgent() {
		// The point of giving an agent a DID: a job says who did it, not merely
		// whose account it happened in. Ownership stays with the user (the job
		// must stay in the owner's listing and be readable by them); the agent is
		// named alongside it.
		//
		// Note this covers TOP-LEVEL jobs only. An agent's own transitions and
		// tool calls dispatch through invokeInternal, which deliberately creates
		// no job (#85), so an agent is the `caller` when it causes a job — an
		// agent:request to another agent, say — not on every thing an agent does.
		AString agentId = Strings.create("attrib");
		RequestContext agentCtx = RequestContext.ofAgent(ALICE_DID, agentId);
		Blob jobId = engine.jobs()
			.invokeOperation("v/test/ops/echo", Maps.of(), agentCtx).getID();

		AMap<AString, ACell> data = engine.jobs()
			.getJobData(jobId, RequestContext.of(ALICE_DID));
		assertNotNull(data, "the owner must be able to read a job their agent caused");
		assertEquals(ALICE_DID, data.get(Fields.OWNER),
			"ownership stays with the user — listing and access control key on it");
		assertEquals(Principals.agentDID(ALICE_DID, agentId), data.get(Fields.CALLER),
			"the acting agent must be named on the record");
	}

	@Test
	public void testDirectUserJobsCarryNoOwner() {
		// The owner is written only when it differs from the caller, so a job
		// the user made itself names only its caller.
		engine.jobs().invokeOperation("v/test/ops/echo", Maps.of(),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		for (var e : engine.jobs().getJobs(RequestContext.of(ALICE_DID)).entrySet()) {
			AMap<AString, ACell> data = (AMap<AString, ACell>) e.getValue();
			if (ALICE_DID.equals(data.get(Fields.CALLER))
					&& data.get(Fields.OWNER) == null) return;
		}
		fail("a job invoked directly by the user must carry no owner");
	}

	/**
	 * A <b>fully empowered</b> agent — no {@code config.caps} — has its owner's
	 * own authority and nothing more: it grants what its owner could grant of
	 * its own right, its own resources and its owner's, and never what others
	 * granted its owner. Answering its owner's HITL inbox is acting as the
	 * owner, which no agent does.
	 */
	@Test
	public void testFullyEmpoweredAgentGrantsWhatItsOwnerCould() throws Exception {
		AString agentId = Strings.create("empowered");
		engine.getVenueState().users().ensure(ALICE_DID);
		engine.getVenueState().users().ensure(BOB_DID);
		RequestContext agentCtx = RequestContext.ofAgent(ALICE_DID, agentId);
		assertNull(agentCtx.getCaps(), "precondition: an unrestricted agent");
		AString agentDID = Principals.agentDID(ALICE_DID, agentId);

		// It can act freely inside its owner's namespace — that is what
		// "fully empowered" means, and it must keep working.
		assertNotNull(engine.jobs()
			.invokeOperation("v/test/ops/echo", Maps.of(), agentCtx).awaitResult(5000));

		// It grants its own resources and its owner's, by a bare path or named...
		for (AString path : new AString[] {Strings.create(agentDID + "/x/crypto/keys/"),
				Strings.create("w/"), Strings.create(ALICE_DID + "/w/")}) {
			ACell issued = engine.jobs().invokeInternal("v/ops/ucan/issue",
				issueInput(Strings.create("did:web:elsewhere.example:u:carol"), path, "crud/write"),
				agentCtx).get(5, TimeUnit.SECONDS);
			assertNotNull(convex.core.lang.RT.getIn(issued, "token"), path.toString());
		}

		// ...but not another user's...
		AuthException others = assertThrows(AuthException.class, () ->
			unwrap(() -> engine.jobs().invokeInternal("v/ops/ucan/issue",
				issueInput(ALICE_DID, Strings.create(BOB_DID + "/w/"), "crud/read"), agentCtx).get(5, TimeUnit.SECONDS)));
		assertTrue(others.getMessage().contains(Engine.NO_GRANTING_RIGHT), others.getMessage());

		// ...nor by answering its owner's HITL inbox, which would launder a
		// self-approved grant through the human-in-the-loop surface.
		AuthException viaHitl = assertThrows(AuthException.class, () ->
			unwrap(() -> engine.jobs().invokeInternal("v/ops/hitl/respond",
				Maps.of(Strings.create("id"), Strings.create("00"),
					Strings.create("outcome"), Strings.create("answer")),
				agentCtx).get(5, TimeUnit.SECONDS)));
		assertFalse(viaHitl.getMessage().isBlank());
	}

	/**
	 * An agent with caps grants only what they include the right to grant:
	 * using its own keys or reading its owner's workspace is not granting them.
	 * Its owner, whose agent it is, grants them as it likes.
	 */
	@Test
	public void testRestrictedAgentGrantsOnlyWhatItsCapsLetItGrant() throws Exception {
		AString agentId = Strings.create("restricted");
		engine.getVenueState().users().ensure(ALICE_DID);
		AString keyring = Strings.create(Principals.agentDID(ALICE_DID, agentId) + "/x/crypto/keys/");
		AMap<AString, ACell> invoke = Maps.of(Strings.create("with"), Strings.create("v/ops/ucan/issue"),
			Strings.create("can"), Strings.create("invoke"));
		RequestContext agentCtx = RequestContext.ofAgent(ALICE_DID, agentId).withCaps(Vectors.of(invoke,
			Maps.of(Strings.create("with"), keyring, Strings.create("can"), Strings.create("key/sign")),
			Maps.of(Strings.create("with"), Strings.create("w/"), Strings.create("can"), Strings.create("crud/read"))));
		for (AString path : new AString[] {keyring, Strings.create("w/")}) {
			AuthException denied = assertThrows(AuthException.class, () ->
				unwrap(() -> engine.jobs().invokeInternal("v/ops/ucan/issue",
					issueInput(BOB_DID, path, "crud/read"), agentCtx).get(5, TimeUnit.SECONDS)));
			assertTrue(denied.getMessage().contains(Engine.GRANT_BEYOND_SCOPE), denied.getMessage());
		}

		RequestContext granting = RequestContext.ofAgent(ALICE_DID, agentId).withCaps(Vectors.of(invoke,
			Maps.of(Strings.create("with"), keyring, Strings.create("can"), Strings.create("grant/key/sign"))));
		assertNotNull(convex.core.lang.RT.getIn(engine.jobs().invokeInternal("v/ops/ucan/issue",
			issueInput(BOB_DID, keyring, "key/sign"), granting).get(5, TimeUnit.SECONDS), "token"));

		ACell byOwner = engine.jobs().invokeInternal("v/ops/ucan/issue",
			issueInput(BOB_DID, keyring, "key/sign"), RequestContext.of(ALICE_DID)).get(5, TimeUnit.SECONDS);
		assertNotNull(convex.core.lang.RT.getIn(byOwner, "token"),
			"the owner grants on its agent's behalf");

		engine.getVenueState().users().ensure(BOB_DID);
		AuthException stranger = assertThrows(AuthException.class, () ->
			unwrap(() -> engine.jobs().invokeInternal("v/ops/ucan/issue",
				issueInput(BOB_DID, keyring, "key/sign"), RequestContext.of(BOB_DID)).get(5, TimeUnit.SECONDS)));
		assertTrue(stranger.getMessage().contains(Engine.NO_GRANTING_RIGHT), stranger.getMessage());
	}

	private static AMap<AString, ACell> issueInput(AString aud, AString with, String can) {
		return Maps.of(Strings.create("aud"), aud,
			Strings.create("att"), Vectors.of(Maps.of(Strings.create("with"), with,
				Strings.create("can"), Strings.create(can))),
			Strings.create("exp"), CVMLong.create(System.currentTimeMillis() / 1000 + 600));
	}

	/** Runs {@code body}, rethrowing the underlying cause so assertThrows sees the
	 *  adapter's exception rather than the future's ExecutionException wrapper. */
	private static void unwrap(Callable<?> body) throws Exception {
		try {
			body.call();
		} catch (ExecutionException e) {
			if (e.getCause() instanceof Exception cause) throw cause;
			throw e;
		}
	}

	@Test
	public void testAgentRelationsAreRecognised() {
		AString agentId = prepareAgent(ALICE_DID, "rel-ident", "REL_KEY", "alice-secret");
		adapter().wakeAgent(ALICE_DID, agentId, true).join();
		RequestContext harness = TestAdapter.CAPTURED_CTX.get(ALICE_DID);
		assertNotNull(harness, "transition must have executed");
		RequestContext seen = engine.stepContext(harness, agentId);

		AString self    = Principals.agentDID(ALICE_DID, agentId);
		AString sibling = Principals.agentDID(ALICE_DID, Strings.create("other"));
		AString foreign = Principals.agentDID(BOB_DID, agentId);

		assertEquals(Principals.Relation.SELF,      seen.relationTo(self));
		assertEquals(Principals.Relation.OWNER,     seen.relationTo(ALICE_DID));
		assertEquals(Principals.Relation.SAME_USER, seen.relationTo(sibling));
		assertEquals(Principals.Relation.FOREIGN,   seen.relationTo(BOB_DID));
		assertEquals(Principals.Relation.FOREIGN,   seen.relationTo(foreign),
			"a different user's agent is foreign, however similarly named");

		// Proximity is ordered, and everything inside one user's family is
		// nearer than anything outside it.
		assertTrue(seen.relationTo(sibling).isSameUser());
		assertFalse(seen.relationTo(foreign).isSameUser());
	}

	@Test
	public void testRunLoopIdentityFollowsOwnerNotCaller() {
		// The agent is owned by BOB. The loop must run as BOB and resolve BOB's
		// secret — identity follows the agent's address, not the surrounding
		// fixture's primary user (ALICE).
		AString agentId = prepareAgent(BOB_DID, "bob-ident", "BOB_KEY", "bob-secret");
		adapter().wakeAgent(BOB_DID, agentId, true).join();

		RequestContext seen = TestAdapter.CAPTURED_CTX.get(BOB_DID);
		assertNotNull(seen, "transition must have executed");
		assertEquals(BOB_DID, seen.getAuthorityDID(), "the harness is BOB's frame, as the agent's controller");
		assertEquals(BOB_DID, seen.getExecutionScope().ownerDID(), "driving BOB's agent");
		assertEquals(Principals.agentDID(BOB_DID, agentId), engine.stepContext(seen, agentId).getAuthorityDID(),
			"its steps act as BOB's agent, not BOB");
		assertEquals("bob-secret", engine.resolveSecret("BOB_KEY", seen),
			"BOB's owner-scoped secret must resolve under the run-loop context");
		assertNull(engine.resolveSecret("BOB_KEY", RequestContext.of(ALICE_DID)),
			"BOB's secret must not resolve under a different identity");
	}

	@Test
	public void testSameNameAgentsForDifferentOwnersDoNotCollide() {
		// Both users own an agent called "shared". testUserIsolation proves the
		// lattice records are isolated; this proves the in-memory run-loop
		// registries are too — each loop runs under its own identity and
		// resolves its own secret. Pre-fix, the registries keyed on the bare
		// agentId, so the two loops shared a slot.
		AString agentId = prepareAgent(ALICE_DID, "shared", "K", "alice-secret");
		prepareAgent(BOB_DID, "shared", "K", "bob-secret");

		// Drive both concurrently; both must run their own loop to completion.
		CompletableFuture<ACell> a = adapter().wakeAgent(ALICE_DID, agentId, true);
		CompletableFuture<ACell> b = adapter().wakeAgent(BOB_DID, agentId, true);
		assertNotNull(a, "Alice's wake should start a loop");
		assertNotNull(b, "Bob's wake should start a loop");
		a.join();
		b.join();

		RequestContext aSeen = TestAdapter.CAPTURED_CTX.get(ALICE_DID);
		RequestContext bSeen = TestAdapter.CAPTURED_CTX.get(BOB_DID);
		assertNotNull(aSeen, "Alice's agent must have run");
		assertNotNull(bSeen, "Bob's agent must have run");
		assertEquals(ALICE_DID, aSeen.getUserDID());
		assertEquals(BOB_DID, bSeen.getUserDID());
		// Same agent id, different owners — so distinct sub-principals.
		AString aAgent = engine.stepContext(aSeen, agentId).getAuthorityDID();
		AString bAgent = engine.stepContext(bSeen, agentId).getAuthorityDID();
		assertEquals(Principals.agentDID(ALICE_DID, agentId), aAgent);
		assertEquals(Principals.agentDID(BOB_DID, agentId), bAgent);
		assertNotEquals(aAgent, bAgent);
		assertEquals("alice-secret", engine.resolveSecret("K", aSeen),
			"Alice's loop resolves Alice's secret");
		assertEquals("bob-secret", engine.resolveSecret("K", bSeen),
			"Bob's loop resolves Bob's secret");
	}
}
