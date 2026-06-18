package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.SecretStore;
import covia.venue.TestEngine;
import covia.venue.User;

/**
 * Regression for #91 and the related cross-user run-loop keying.
 *
 * <p>An agent's run loop executes within the agent owner's identity, derived
 * from the agent's address ({@code ownerDID} + {@code agentId}), never from the
 * caller that woke it. {@link AgentAdapter#wakeAgent} is a pure mechanism keyed
 * on that address — there is no caller parameter for an identity to leak
 * through — and the in-memory run-loop registries key on the full address, so
 * two users' agents that share a name do not collide.</p>
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
		assertEquals(ALICE_DID, seen.getCallerDID(),
			"run loop must execute under the agent owner's DID");
		assertNull(seen.getProofs(), "run context must carry no proofs");
		assertNull(seen.getCaps(),   "run context must carry no caps");
		assertEquals("alice-secret", engine.resolveSecret("RUNLOOP_KEY", seen),
			"owner-scoped secret must resolve under the run-loop context");
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
		assertEquals(BOB_DID, seen.getCallerDID(),
			"run loop must execute under the owner (BOB)");
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
		assertEquals(ALICE_DID, aSeen.getCallerDID());
		assertEquals(BOB_DID, bSeen.getCallerDID());
		assertEquals("alice-secret", engine.resolveSecret("K", aSeen),
			"Alice's loop resolves Alice's secret");
		assertEquals("bob-secret", engine.resolveSecret("K", bSeen),
			"Bob's loop resolves Bob's secret");
	}
}
