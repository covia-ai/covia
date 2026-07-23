package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Principals;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.SecretStore;
import covia.venue.TestEngine;
import covia.venue.User;

/**
 * Regression for #91 and the related cross-user run-loop keying, plus the
 * agent sub-principal split.
 *
 * <p>An agent's run loop executes <b>as the agent, within its owner's
 * namespace</b> — both derived from the agent's address ({@code ownerDID} +
 * {@code agentId}), never from the caller that woke it.
 * {@link AgentAdapter#wakeAgent} is a pure mechanism keyed on that address —
 * there is no caller parameter for an identity to leak through — and the
 * in-memory run-loop registries key on the full address, so two users' agents
 * that share a name do not collide.</p>
 *
 * <p>Each test pins <em>both</em> halves of the split: {@code getCallerDID()}
 * is the agent's own sub-principal DID (who acted), while {@code getUserDID()}
 * is the owner (whose namespace it acted in, and therefore whose secrets and
 * workspace resolve).</p>
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
		byte[] encKey = SecretStore.deriveKey(engine.getKeyPair());
		user.secrets().store(secretName, secretValue, encKey);

		AgentState agent = user.agent(name);
		Blob sid = Blob.fromHex("12340001123400011234000112340001");
		agent.ensureSession(sid, owner);
		agent.appendSessionPending(sid, Maps.of(
			Fields.SESSION_ID, Strings.create(sid.toHexString()),
			Fields.MESSAGE, Maps.of("content", "hi")));
		return agentId;
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
		assertEquals(Principals.agentDID(ALICE_DID, agentId), seen.getCallerDID(),
			"run loop must act as the agent's own sub-principal DID");
		assertEquals(ALICE_DID, seen.getUserDID(),
			"run loop must execute within the agent owner's namespace");
		assertTrue(seen.isSubPrincipal(), "an agent run is a sub-principal context");
		assertNull(seen.getProofs(), "run context must carry no proofs");
		assertNull(seen.getCaps(),   "run context must carry no caps");
		assertEquals("alice-secret", engine.resolveSecret("RUNLOOP_KEY", seen),
			"owner-scoped secret must resolve under the run-loop context");
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
		// no job (#85), so `actor` appears when an agent causes a job — an
		// agent:request to another agent, say — not on every thing an agent does.
		AString agentId = Strings.create("attrib");
		RequestContext agentCtx = RequestContext.ofAgent(ALICE_DID, agentId);
		Blob jobId = engine.jobs()
			.invokeOperation("v/test/ops/echo", Maps.of(), agentCtx).getID();

		AMap<AString, ACell> data = engine.jobs()
			.getJobData(jobId, RequestContext.of(ALICE_DID));
		assertNotNull(data, "the owner must be able to read a job their agent caused");
		assertEquals(ALICE_DID, data.get(Fields.CALLER),
			"ownership stays with the user — listing and access control key on it");
		assertEquals(Principals.agentDID(ALICE_DID, agentId), data.get(Fields.ACTOR),
			"the acting agent must be named on the record");
	}

	@Test
	public void testDirectUserJobsCarryNoActor() {
		// Absent actor means "the owner acted": ordinary records are untouched,
		// so nothing needs migrating and `actor` stays meaningful.
		engine.jobs().invokeOperation("v/test/ops/echo", Maps.of(),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		for (var e : engine.jobs().getJobs(RequestContext.of(ALICE_DID)).entrySet()) {
			AMap<AString, ACell> data = (AMap<AString, ACell>) e.getValue();
			if (ALICE_DID.equals(data.get(Fields.CALLER))
					&& data.get(Fields.ACTOR) == null) return;
		}
		fail("a job invoked directly by the user must carry no actor");
	}

	/**
	 * A <b>fully empowered</b> agent — no {@code config.caps}, so the null-scope
	 * fast path in {@code authorityCovers} short-circuits every scope check — is
	 * still not its owner. The guards that matter are pinned to <em>identity</em>,
	 * not to scope, so an unrestricted scope cannot reach past them.
	 */
	@Test
	public void testFullyEmpoweredAgentStillCannotGrant() {
		AString agentId = Strings.create("empowered");
		RequestContext agentCtx = RequestContext.ofAgent(ALICE_DID, agentId);
		assertNull(agentCtx.getCaps(), "precondition: an unrestricted agent");

		// It can act freely inside its owner's namespace — that is what
		// "fully empowered" means, and it must keep working.
		assertNotNull(engine.jobs()
			.invokeOperation("v/test/ops/echo", Maps.of(), agentCtx).awaitResult(5000));

		// But it cannot mint capability grants, directly...
		AuthException direct = assertThrows(AuthException.class, () ->
			unwrap(() -> engine.jobs().invokeInternal("v/ops/ucan/issue",
				Maps.of(Strings.create("aud"), ALICE_DID,
					Strings.create("att"), Vectors.of(
						Maps.of(Strings.create("with"), Strings.create("w/"),
							Strings.create("can"), Strings.create("crud/write"))),
					Strings.create("exp"),
					CVMLong.create(System.currentTimeMillis() / 1000 + 600)),
				agentCtx).get(5, TimeUnit.SECONDS)));
		assertTrue(direct.getMessage().contains("Agents cannot issue capability grants"));

		// ...nor by answering its owner's HITL inbox, which would launder a
		// self-approved grant through the human-in-the-loop surface.
		AuthException viaHitl = assertThrows(AuthException.class, () ->
			unwrap(() -> engine.jobs().invokeInternal("v/ops/hitl/respond",
				Maps.of(Strings.create("id"), Strings.create("00"),
					Strings.create("outcome"), Strings.create("answer")),
				agentCtx).get(5, TimeUnit.SECONDS)));
		assertTrue(viaHitl.getMessage().contains("Agents cannot answer HITL requests"));
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
		RequestContext seen = TestAdapter.CAPTURED_CTX.get(ALICE_DID);
		assertNotNull(seen, "transition must have executed");

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
		assertEquals(Principals.agentDID(BOB_DID, agentId), seen.getCallerDID(),
			"run loop must act as BOB's agent, not BOB");
		assertEquals(BOB_DID, seen.getUserDID(),
			"run loop must execute in the owner's (BOB's) namespace");
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
		assertEquals(Principals.agentDID(ALICE_DID, agentId), aSeen.getCallerDID());
		assertEquals(Principals.agentDID(BOB_DID, agentId), bSeen.getCallerDID());
		assertNotEquals(aSeen.getCallerDID(), bSeen.getCallerDID());
		assertEquals("alice-secret", engine.resolveSecret("K", aSeen),
			"Alice's loop resolves Alice's secret");
		assertEquals("bob-secret", engine.resolveSecret("K", bSeen),
			"Bob's loop resolves Bob's secret");
	}
}
