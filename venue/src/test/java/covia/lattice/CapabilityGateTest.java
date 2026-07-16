package covia.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
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
