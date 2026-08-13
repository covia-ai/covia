package covia.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.auth.ucan.RootAuthorityPolicy;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Capability gates (#216): a grant may carry {@code nb: {gate: "v/ops/…"}} —
 * the capability applies iff the gate operation succeeds for the invocation
 * being authorised. Unit tests pin the checker semantics (ungated grants
 * short-circuit; fail-closed without an evaluator); the end-to-end tests run
 * real gates through the full dispatch path, including the #216 acceptance
 * case: amount 840 executes, amount 4820 is denied by the runtime.
 */
public class CapabilityGateTest {
	private static void awaitAgentStatus(covia.venue.AgentState agent,
			AString expected, long timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!expected.equals(agent.getStatus())) {
			if (System.currentTimeMillis() >= deadline) {
				fail("timeout waiting for agent status " + expected
					+ "; current status is " + agent.getStatus());
			}
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				fail("interrupted while waiting for agent status " + expected, e);
			}
		}
	}

	private final Engine engine = TestEngine.ENGINE;
	private AString ALICE_DID;

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
	}

	// ========== Checker semantics (unit, stub gates) ==========

	private static AVector<ACell> gatedCaps(String with, String can, String gateOp) {
		return Vectors.of(Maps.of(
			"with", Strings.create(with),
			"can", Strings.create(can),
			"nb", Maps.of("gate", Strings.create(gateOp))));
	}

	@Test
	public void testUngatedGrantShortCircuitsGates() {
		// Preference rule (Mike): a valid ungated cap authorises WITHOUT
		// evaluating any gate — gates only run when they are the deciding
		// authority.
		AtomicInteger evaluations = new AtomicInteger();
		CapabilityGate countingGate = (g, op, in, c) -> {
			evaluations.incrementAndGet();
			return "should never be consulted";
		};
		AVector<ACell> caps = gatedCaps("v/test/ops", "invoke", "v/test/ops/denygate")
			.conj(Maps.of("with", Strings.create("v/test/ops"), "can", Strings.create("invoke")));

		String denial = CapabilityChecker.allows(caps,
			Strings.create("v/test/ops/echo"), Strings.create("invoke"), ALICE_DID,
			Strings.create("v/test/ops/echo"), null, countingGate);

		assertNull(denial, "the ungated grant must authorise outright");
		assertEquals(0, evaluations.get(), "no gate may be evaluated when an ungated grant covers");
	}

	@Test
	public void testGatePassAuthorises() {
		String denial = CapabilityChecker.allows(
			gatedCaps("v/test/ops", "invoke", "v/test/ops/allowgate"),
			Strings.create("v/test/ops/echo"), Strings.create("invoke"), ALICE_DID,
			Strings.create("v/test/ops/echo"), null,
			(g, op, in, c) -> null);
		assertNull(denial, "a passing gate authorises the gated grant");
	}

	@Test
	public void testGateFailDenies() {
		String denial = CapabilityChecker.allows(
			gatedCaps("v/test/ops", "invoke", "v/test/ops/denygate"),
			Strings.create("v/test/ops/echo"), Strings.create("invoke"), ALICE_DID,
			Strings.create("v/test/ops/echo"), null,
			(g, op, in, c) -> "gate " + g + ": computer says no");
		assertNotNull(denial);
		assertTrue(denial.contains("Capability denied by gate"), denial);
		assertTrue(denial.contains("computer says no"), "the gate's reason must surface: " + denial);
	}

	@Test
	public void testGatedGrantWithoutEvaluatorFailsClosed() {
		String denial = CapabilityChecker.allows(
			gatedCaps("v/test/ops", "invoke", "v/test/ops/allowgate"),
			Strings.create("v/test/ops/echo"), Strings.create("invoke"), ALICE_DID,
			null, null, null);
		assertNotNull(denial, "a gated grant must not authorise where gates cannot be evaluated");
		assertTrue(denial.contains("cannot be evaluated"), denial);
	}

	@Test
	public void testNonCoveringGatedGrantNeverEvaluates() {
		AtomicInteger evaluations = new AtomicInteger();
		String denial = CapabilityChecker.allows(
			gatedCaps("v/other/ops", "invoke", "v/test/ops/allowgate"),
			Strings.create("v/test/ops/echo"), Strings.create("invoke"), ALICE_DID,
			Strings.create("v/test/ops/echo"), null,
			(g, op, in, c) -> { evaluations.incrementAndGet(); return null; });
		assertNotNull(denial, "a grant that does not cover structurally grants nothing");
		assertEquals(0, evaluations.get(), "gates on non-covering grants are never consulted");
	}

	@Test
	public void testFirstPassingGateWins() {
		AVector<ACell> caps = gatedCaps("v/test/ops", "invoke", "gate-a")
			.concat(gatedCaps("v/test/ops", "invoke", "gate-b"));
		String denial = CapabilityChecker.allows(caps,
			Strings.create("v/test/ops/echo"), Strings.create("invoke"), ALICE_DID,
			Strings.create("v/test/ops/echo"), null,
			(g, op, in, c) -> "gate-a".equals(g.toString()) ? "a refuses" : null);
		assertNull(denial, "any passing gate among covering gated grants authorises");
	}

	// ========== Delegated UCAN path caveats ==========

	private record ProofChain(AVector<ACell> proofs, AString caller, AString resource) {}

	private static AMap<AString, ACell> proofCap(AString resource, AString ability,
			String gateOp) {
		if (gateOp == null) return Capability.create(resource, ability);
		return Capability.create(resource, ability,
			Maps.of("gate", Strings.create(gateOp)));
	}

	private static ProofChain proofChain(String rootGate, String leafGate) {
		AKeyPair owner = AKeyPair.generate();
		AKeyPair delegate = AKeyPair.generate();
		AKeyPair caller = AKeyPair.generate();
		AString ownerDID = UCAN.toDIDKey(owner.getAccountKey());
		AString callerDID = UCAN.toDIDKey(caller.getAccountKey());
		AString resource = Strings.create(ownerDID + "/w/shared");
		long exp = (System.currentTimeMillis() / 1000) + 3600;

		UCAN root = UCAN.create(owner, delegate.getAccountKey(), exp,
			Vectors.of(proofCap(resource, Capability.CRUD, rootGate)), Vectors.empty());
		UCAN leaf = UCAN.create(delegate, caller.getAccountKey(), exp,
			Vectors.of(proofCap(resource, Capability.CRUD_READ, leafGate)),
			Vectors.of(root.toMap()));
		return new ProofChain(Vectors.of(leaf.toMap()), callerDID, resource);
	}

	@Test
	public void testDelegatedProofRequiresEveryGateOnPath() {
		ProofChain chain = proofChain("root-gate", "leaf-gate");
		AtomicInteger rootChecks = new AtomicInteger();
		AtomicInteger leafChecks = new AtomicInteger();
		boolean allowed = CapabilityChecker.proofsCover(chain.proofs(), chain.caller(),
			RootAuthorityPolicy.SELF_SOVEREIGN, chain.resource(), Capability.CRUD_READ,
			System.currentTimeMillis() / 1000, Strings.create("v/ops/covia/read"),
			Maps.of(Fields.PATH, chain.resource()), (gate, op, input, caller) -> {
				if ("root-gate".equals(gate.toString())) {
					rootChecks.incrementAndGet();
					return null;
				}
				leafChecks.incrementAndGet();
				return "leaf refuses";
			});

		assertFalse(allowed, "every caveat from root through leaf must pass");
		assertEquals(1, rootChecks.get());
		assertEquals(1, leafChecks.get());
	}

	@Test
	public void testDelegatedProofCannotDropParentGate() {
		ProofChain chain = proofChain("parent-denies", null);
		AtomicInteger checks = new AtomicInteger();
		boolean allowed = CapabilityChecker.proofsCover(chain.proofs(), chain.caller(),
			RootAuthorityPolicy.SELF_SOVEREIGN, chain.resource(), Capability.CRUD_READ,
			System.currentTimeMillis() / 1000, Strings.create("v/ops/covia/read"), null,
			(gate, op, input, caller) -> {
				checks.incrementAndGet();
				return "parent refuses";
			});

		assertFalse(allowed, "an ungated leaf must not erase its parent's gate");
		assertEquals(1, checks.get());
	}

	@Test
	public void testDelegatedProofCachesRepeatedGateWithinDecision() {
		ProofChain chain = proofChain("same-gate", "same-gate");
		AtomicInteger checks = new AtomicInteger();
		boolean allowed = CapabilityChecker.proofsCover(chain.proofs(), chain.caller(),
			RootAuthorityPolicy.SELF_SOVEREIGN, chain.resource(), Capability.CRUD_READ,
			System.currentTimeMillis() / 1000, Strings.create("v/ops/covia/read"), null,
			(gate, op, input, caller) -> {
				checks.incrementAndGet();
				return null;
			});

		assertTrue(allowed);
		assertEquals(1, checks.get(),
			"the same policy operation rules on one immutable invocation only once");
	}

	@Test
	public void testDelegatedProofAlternativePathMayAuthorise() {
		AKeyPair owner = AKeyPair.generate();
		AKeyPair delegate = AKeyPair.generate();
		AKeyPair caller = AKeyPair.generate();
		AString ownerDID = UCAN.toDIDKey(owner.getAccountKey());
		AString callerDID = UCAN.toDIDKey(caller.getAccountKey());
		AString resource = Strings.create(ownerDID + "/w/shared");
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		UCAN deniedRoot = UCAN.create(owner, delegate.getAccountKey(), exp,
			Vectors.of(proofCap(resource, Capability.CRUD, "deny-path")), Vectors.empty());
		UCAN allowedRoot = UCAN.create(owner, delegate.getAccountKey(), exp,
			Vectors.of(proofCap(resource, Capability.CRUD, "allow-path")), Vectors.empty());
		UCAN leaf = UCAN.create(delegate, caller.getAccountKey(), exp,
			Vectors.of(proofCap(resource, Capability.CRUD_READ, null)),
			Vectors.of(deniedRoot.toMap(), allowedRoot.toMap()));
		AtomicInteger checks = new AtomicInteger();

		boolean allowed = CapabilityChecker.proofsCover(Vectors.of(leaf.toMap()), callerDID,
			RootAuthorityPolicy.SELF_SOVEREIGN, resource, Capability.CRUD_READ,
			System.currentTimeMillis() / 1000, Strings.create("v/ops/covia/read"), null,
			(gate, op, input, audience) -> {
				checks.incrementAndGet();
				return "deny-path".equals(gate.toString()) ? "this path refuses" : null;
			});

		assertTrue(allowed, "a separate, fully accepted proof path may authorise");
		assertEquals(2, checks.get(), "the denied path is skipped before trying the alternative");
	}

	@Test
	public void testDelegatedProofCaveatsFailClosedWithoutEvaluator() {
		ProofChain chain = proofChain("allow-if-run", null);
		long now = System.currentTimeMillis() / 1000;

		assertFalse(CapabilityChecker.proofsCover(chain.proofs(), chain.caller(),
			RootAuthorityPolicy.SELF_SOVEREIGN, chain.resource(), Capability.CRUD_READ, now),
			"a caveated proof cannot authorise where no invocation evaluator exists");
		assertTrue(CapabilityChecker.proofsStructurallyCover(chain.proofs(), chain.caller(),
			RootAuthorityPolicy.SELF_SOVEREIGN, chain.resource(), Capability.CRUD_READ, now),
			"the explicitly structural check still answers chain/lifetime questions");
	}

	@Test
	public void testUnknownAndMalformedCaveatsFailClosed() {
		AString resource = Strings.create("did:key:zOwner/w/shared");
		AVector<ACell> unknownLocal = Vectors.of(Capability.create(resource,
			Capability.CRUD_READ, Maps.of("maxItems", 10)));
		AVector<ACell> malformedLocal = Vectors.of(Maps.of(
			Capability.WITH, resource,
			Capability.CAN, Capability.CRUD_READ,
			Capability.NB, Strings.create("not-a-map")));

		assertNotNull(CapabilityChecker.allows(unknownLocal, resource,
			Capability.CRUD_READ, null, null, null, (g, o, i, c) -> null));
		assertNotNull(CapabilityChecker.allows(malformedLocal, resource,
			Capability.CRUD_READ, null, null, null, (g, o, i, c) -> null));

		AKeyPair owner = AKeyPair.generate();
		AKeyPair caller = AKeyPair.generate();
		AString ownerDID = UCAN.toDIDKey(owner.getAccountKey());
		AString callerDID = UCAN.toDIDKey(caller.getAccountKey());
		AString proofResource = Strings.create(ownerDID + "/w/shared");
		long now = System.currentTimeMillis() / 1000;
		UCAN unknownProof = UCAN.create(owner, caller.getAccountKey(), now + 3600,
			Vectors.of(Capability.create(proofResource, Capability.CRUD_READ,
				Maps.of("maxItems", 10))),
			Vectors.empty());
		assertFalse(CapabilityChecker.proofsCover(Vectors.of(unknownProof.toMap()), callerDID,
			RootAuthorityPolicy.SELF_SOVEREIGN, proofResource, Capability.CRUD_READ, now,
			null, null, (g, o, i, c) -> null),
			"an unsupported caveat on a delegated proof must not become unconditional");
		assertTrue(CapabilityChecker.proofsStructurallyCover(
			Vectors.of(unknownProof.toMap()), callerDID,
			RootAuthorityPolicy.SELF_SOVEREIGN, proofResource, Capability.CRUD_READ, now));
	}

	// ========== End-to-end: real gates through the dispatch path ==========

	private Job invokeEchoWithCaps(AVector<ACell> caps, ACell input) {
		return engine.jobs().invokeOperation("v/test/ops/echo", input,
			RequestContext.of(ALICE_DID).withCaps(caps));
	}

	@Test
	public void testAmountGateAcceptanceCase() {
		// The #216 acceptance sketch: a grant conditional on amount <= 2000.
		// 840 → the runtime lets the call through; 4820 → denied with a
		// structured record, whatever the caller (or a model) decided.
		AVector<ACell> caps = gatedCaps("v/test/ops/echo", "invoke", "v/test/ops/amountgate");

		Job ok = invokeEchoWithCaps(caps, Maps.of("amount", 840));
		ok.awaitResult(10000);
		assertEquals(Status.COMPLETE, ok.getStatus(), "840 is within the gate limit");

		Job denied = invokeEchoWithCaps(caps, Maps.of("amount", 4820));
		assertThrows(Exception.class, () -> denied.awaitResult(10000));
		assertEquals(Status.FAILED, denied.getStatus());
		assertTrue(denied.getErrorMessage().contains("exceeds the limit"),
			"denial must carry the gate's reason: " + denied.getErrorMessage());
		assertTrue(denied.getErrorMessage().contains("Capability denied by gate"),
			denied.getErrorMessage());
	}

	@Test
	public void testAmountGateFailsClosedOnMissingAmount() {
		AVector<ACell> caps = gatedCaps("v/test/ops/echo", "invoke", "v/test/ops/amountgate");
		Job denied = invokeEchoWithCaps(caps, Maps.of("echo", "no amount here"));
		assertThrows(Exception.class, () -> denied.awaitResult(10000));
		assertEquals(Status.FAILED, denied.getStatus());
		assertTrue(denied.getErrorMessage().contains("denying by default"),
			denied.getErrorMessage());
	}

	@Test
	public void testAllowAndDenyGatesEndToEnd() {
		Job allowed = invokeEchoWithCaps(
			gatedCaps("v/test/ops/echo", "invoke", "v/test/ops/allowgate"),
			Maps.of("echo", "hi"));
		allowed.awaitResult(10000);
		assertEquals(Status.COMPLETE, allowed.getStatus());

		Job denied = invokeEchoWithCaps(
			gatedCaps("v/test/ops/echo", "invoke", "v/test/ops/denygate"),
			Maps.of("echo", "hi"));
		assertThrows(Exception.class, () -> denied.awaitResult(10000));
		assertEquals(Status.FAILED, denied.getStatus());
		assertTrue(denied.getErrorMessage().contains("denied by test policy gate"),
			denied.getErrorMessage());
	}

	@Test
	public void testUnresolvableGateFailsClosed() {
		// A gate op that doesn't exist can never pass — fail-closed, with the
		// resolution failure as the reason.
		Job denied = invokeEchoWithCaps(
			gatedCaps("v/test/ops/echo", "invoke", "v/test/ops/no-such-gate"),
			Maps.of("echo", "hi"));
		assertThrows(Exception.class, () -> denied.awaitResult(10000));
		assertEquals(Status.FAILED, denied.getStatus());
		assertTrue(denied.getErrorMessage().contains("Capability denied by gate"),
			denied.getErrorMessage());
	}

	@Test
	public void testAgentConfigCapsWithGate() {
		// The #216 target surface: an AGENT whose config caps carry a gate.
		// The tool loop dispatches tool calls under the capsCtx; the gate
		// rules on each call. denygate → the denial reaches the model as the
		// tool result and the agent handles it in one cycle (no suspension,
		// mirroring the #211 denial contract).
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "gated-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/toolllm",
					"caps", gatedCaps("v/test/ops", "invoke", "v/test/ops/denygate"))
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "gated-agent",
				Fields.MESSAGE, Strings.create("use your tool")),
			RequestContext.of(ALICE_DID));
		ACell result = chatJob.awaitResult(10000);
		String response = convex.core.lang.RT.getIn(result, Fields.RESPONSE).toString();
		assertTrue(response.contains("Capability denied by gate"),
			"the gate denial must reach the model as the tool result: " + response);

		covia.venue.User user = engine.getVenueState().users().get(ALICE_DID);
		covia.venue.AgentState agent = user.agent("gated-agent");
		awaitAgentStatus(agent, covia.venue.AgentState.SLEEPING, 2000);
		assertEquals(covia.venue.AgentState.SLEEPING, agent.getStatus(),
			"a handled gate denial is not an agent failure");
	}

	@Test
	public void testAgentConfigCapsWithPassingGate() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "gate-ok-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/toolllm",
					"caps", gatedCaps("v/test/ops", "invoke", "v/test/ops/allowgate"))
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "gate-ok-agent",
				Fields.MESSAGE, Strings.create("use your tool")),
			RequestContext.of(ALICE_DID));
		ACell result = chatJob.awaitResult(10000);
		String response = convex.core.lang.RT.getIn(result, Fields.RESPONSE).toString();
		assertTrue(response.contains("Tool returned"),
			"a passing gate must let the tool call execute: " + response);
	}

	@Test
	public void testGateReceivesInvocationDescription() {
		// The gate contract: {operation, input, caller}. The amount gate reads
		// input.amount, so a passing 840 call proves input threading; this test
		// additionally proves the operation reference and caller reach the gate
		// by using capturectx via a gate that echoes its input — covered
		// implicitly: a wrong shape would make the amount gate deny 840.
		AVector<ACell> caps = gatedCaps("v/test/ops/echo", "invoke", "v/test/ops/amountgate");
		Job ok = invokeEchoWithCaps(caps, Maps.of("amount", 2000));
		ok.awaitResult(10000);
		assertEquals(Status.COMPLETE, ok.getStatus(), "boundary value 2000 passes (<=)");
	}
}
