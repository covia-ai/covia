package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.TestAdapter;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;

/**
 * An agent's work acts under its caps as they stand at each point of action,
 * not as they stood when the work started: narrowing an agent bounds work it
 * already has running, and a deleted agent holds nothing.
 */
class CurrentCapsTest {

	private static final Engine engine = TestEngine.ENGINE;

	private static final AMap<AString, ACell> INVOKE_ANY = Maps.of(Capability.CAN, Strings.create("invoke"));
	private static final AMap<AString, ACell> WRITE_OUT = Capability.create(Strings.create("w/out/"), Capability.CRUD_WRITE);

	private static void agentOp(AString owner, String op, AMap<AString, ACell> input) {
		engine.jobs().invokeOperation(op, input, RequestContext.of(owner)).awaitResult(10_000);
	}

	/** Agent {@code id} of {@code owner} with {@code caps}, as it acts now. */
	private static RequestContext agent(AString owner, String id, AVector<ACell> caps) {
		agentOp(owner, "v/ops/agent/create", Maps.of(Fields.AGENT_ID, id, Fields.CONFIG, Maps.of(Config.CAPS, caps)));
		return engine.context(owner, Principals.agentDID(owner, Strings.create(id)));
	}

	private static void setCaps(AString owner, String id, AVector<ACell> caps) {
		agentOp(owner, "v/ops/agent/update", Maps.of(Fields.AGENT_ID, id, Fields.CONFIG, Maps.of(Config.CAPS, caps)));
	}

	@Test void anAgentsContextReadsItsCapsAsTheyStandNow() {
		AString owner = TestEngine.uniqueDID("CurrentCapsOwner");
		RequestContext agent = agent(owner, "worker", Vectors.of(INVOKE_ANY, WRITE_OUT));
		RequestContext carried = agent.withJobId(Blob.fromHex("0c")).withProofs(Vectors.empty());
		RequestContext step = engine.stepContext(RequestContext.of(owner), Strings.create("worker"));
		AString out = Strings.create(owner + "/w/out/x");
		for (RequestContext ctx : new RequestContext[] {agent, carried, step}) {
			assertTrue(engine.authorityCovers(ctx, out, Capability.CRUD_WRITE));
		}

		setCaps(owner, "worker", Vectors.of(INVOKE_ANY));
		for (RequestContext ctx : new RequestContext[] {agent, carried, step}) {
			assertEquals(Vectors.of(INVOKE_ANY), ctx.getCaps());
			assertFalse(engine.authorityCovers(ctx, out, Capability.CRUD_WRITE), "narrowed for work already holding it");
			assertThrows(AuthException.class, () -> engine.requireAuthority(ctx, out, Capability.CRUD_WRITE));
		}

		agentOp(owner, "v/ops/agent/delete", Maps.of(Fields.AGENT_ID, "worker"));
		for (RequestContext ctx : new RequestContext[] {agent, carried, step}) {
			assertEquals(Vectors.empty(), ctx.getCaps(), "a deleted agent holds nothing");
			assertFalse(engine.authorityCovers(ctx, Strings.create("v/test/ops/echo"), Strings.create("invoke")));
		}
	}

	@Test void anExplicitScopeReplacesTheAgentsOwn() {
		AString owner = TestEngine.uniqueDID("CurrentCapsExplicit");
		RequestContext agent = agent(owner, "scoped", Vectors.of(INVOKE_ANY, WRITE_OUT));
		RequestContext fixed = agent.withCaps(Vectors.of(INVOKE_ANY));
		setCaps(owner, "scoped", Vectors.of(INVOKE_ANY, WRITE_OUT, Capability.create(Strings.create("w/more/"), Capability.CRUD_WRITE)));
		assertEquals(Vectors.of(INVOKE_ANY), fixed.getCaps());
	}

	/** An orchestration the agent started runs each later step under the caps the agent has then. */
	@Test void anOrchestrationStepActsUnderTheAgentsCapsWhenItRuns() throws Exception {
		AString owner = TestEngine.uniqueDID("CurrentCapsOrchestration");
		RequestContext agent = agent(owner, "pipeline", Vectors.of(INVOKE_ANY, WRITE_OUT));
		String gateName = "current-caps-" + owner;
		String orchestration = engine.storeAsset(Strings.create("""
			{
				"name": "Hold then write",
				"operation": {
					"adapter": "orchestrator",
					"steps": [
						{ "op": "v/test/ops/hold", "input": {"gate": ["const", "%s"]} },
						{ "op": "v/ops/covia/write", "input": {"path": ["const", "w/out/x"], "value": [0]} }
					],
					"result": [1]
				}
			}
			""".formatted(gateName)), null).toHexString();

		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Job job = engine.jobs().invokeOperation(orchestration, Maps.empty(), agent);
			assertTrue(gate.awaitEntered(10, TimeUnit.SECONDS), "the first step holds");
			setCaps(owner, "pipeline", Vectors.of(INVOKE_ANY));
			gate.release();
			assertThrows(RuntimeException.class, () -> job.awaitResult(10_000));
			assertEquals(Status.FAILED, job.getStatus(), "the write step runs under the narrowed caps");
		}
		ACell read = engine.jobs().invokeOperation("v/ops/covia/read", Maps.of(Fields.PATH, "w/out/x"),
			RequestContext.of(owner)).awaitResult(10_000);
		assertNull(RT.getIn(read, Fields.VALUE), "nothing was written");
	}
}
