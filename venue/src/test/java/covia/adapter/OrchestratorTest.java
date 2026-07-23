package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import org.junit.jupiter.api.TestInfo;

/**
 * Tests for the Orchestrator adapter: multi-step pipelines with dependency
 * management, input resolution, and result aggregation.
 */
public class OrchestratorTest {

	private final Engine engine = TestEngine.ENGINE;
	private AString ALICE_DID;

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
	}

	// ========== Internal invocation (the LLM tool-loop seam) ==========

	@Test
	public void testOrchestrationInvokableInternally() throws Exception {
		// Regression (#85 fall-out): an orchestration reached via the zero-Job
		// internal path (e.g. invoked as an LLM tool from a skills-bundled
		// pipeline) must delegate to the Job-aware dispatch, not throw
		// UnsupportedOperationException.
		AMap<AString, ACell> meta = Maps.of(
			Fields.NAME, Strings.create("inline-orch"),
			Fields.OPERATION, Maps.of(
				Fields.ADAPTER, Strings.create("orchestrator"),
				Fields.STEPS, Vectors.of(Maps.of(
					Strings.create("op"), Strings.create("v/test/ops/echo"),
					Strings.create("input"), Vectors.of(
						Strings.create("const"),
						Maps.of(Strings.create("echo"), Strings.create("orch-internal"))))),
				Fields.RESULT, Maps.of(
					Strings.create("out"), Vectors.of(CVMLong.create(0)))));

		ACell result = engine.jobs().invokeInternal(meta, Maps.empty(), RequestContext.of(ALICE_DID))
			.get(10, java.util.concurrent.TimeUnit.SECONDS);
		assertNotNull(result);
		assertEquals(Strings.create("orch-internal"),
			RT.getIn(result, Strings.create("out"), Strings.create("echo")));
	}

	// ========== Input resolution via JSON-stored orchestration ==========

	@Test
	public void testInputReference() {
		String hash = storeJsonOrchestration("""
			{
				"name": "Input Ref Test",
				"operation": {
					"adapter": "orchestrator",
					"steps": [{
						"op": "v/test/ops/echo",
						"input": { "echoed": ["input", "message"] }
					}],
					"result": { "result": [0, "echoed"] }
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.of(Strings.create("message"), "Hello from input"),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertEquals(Strings.create("Hello from input"), RT.getIn(result, Strings.create("result")));
	}

	@Test
	public void testConstReference() {
		String hash = storeJsonOrchestration("""
			{
				"name": "Const Ref Test",
				"operation": {
					"adapter": "orchestrator",
					"steps": [{
						"op": "v/test/ops/echo",
						"input": ["const", {"fixed": "value"}]
					}],
					"result": { "result": [0, "fixed"] }
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.empty(), RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertEquals(Strings.create("value"), RT.getIn(result, Strings.create("result")));
	}

	@Test
	public void testWholeInputReference() {
		String hash = storeJsonOrchestration("""
			{
				"name": "Whole Input Test",
				"operation": {
					"adapter": "orchestrator",
					"steps": [{
						"op": "v/test/ops/echo",
						"input": ["input"]
					}],
					"result": { "msg": [0, "message"] }
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.of(Strings.create("message"), "whole input"),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertEquals(Strings.create("whole input"), RT.getIn(result, Strings.create("msg")));
	}

	// ========== Step dependencies ==========

	@Test
	public void testStepDependency() {
		String hash = storeJsonOrchestration("""
			{
				"name": "Step Dep Test",
				"operation": {
					"adapter": "orchestrator",
					"steps": [
						{ "op": "v/test/ops/echo", "input": ["const", {"value": "first"}] },
						{ "op": "v/test/ops/echo", "input": {"prev": [0, "value"]} }
					],
					"result": {
						"step0": [0, "value"],
						"step1": [1, "prev"]
					}
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.empty(), RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertEquals(Strings.create("first"), RT.getIn(result, Strings.create("step0")));
		assertEquals(Strings.create("first"), RT.getIn(result, Strings.create("step1")));
	}

	@Test
	public void testThreeStepChain() {
		String hash = storeJsonOrchestration("""
			{
				"name": "Three Step Chain",
				"operation": {
					"adapter": "orchestrator",
					"steps": [
						{ "op": "v/test/ops/echo", "input": {"msg": ["input", "text"]} },
						{ "op": "v/test/ops/echo", "input": [0] },
						{ "op": "v/test/ops/echo", "input": [1] }
					],
					"result": { "final": [2, "msg"] }
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.of(Strings.create("text"), "pipeline"),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertEquals(Strings.create("pipeline"), RT.getIn(result, Strings.create("final")));
	}

	// ========== grid:run invocation path ==========

	@Test
	public void testGridRunOrchestration() {
		String hash = storeJsonOrchestration("""
			{
				"name": "Grid Run Test",
				"operation": {
					"adapter": "orchestrator",
					"steps": [{
						"op": "v/test/ops/echo",
						"input": { "val": ["input", "x"] }
					}],
					"result": { "answer": [0, "val"] }
				}
			}
		""");

		// Invoke via grid:run — the MCP client path
		Job job = engine.jobs().invokeOperation("v/ops/grid/run",
			Maps.of(Fields.OPERATION, hash, Fields.INPUT, Maps.of(Strings.create("x"), "via-grid")),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertEquals(Strings.create("via-grid"), RT.getIn(result, Strings.create("answer")));
	}

	@Test
	public void testGridRunThreeStepChain() {
		String hash = storeJsonOrchestration("""
			{
				"name": "Grid Three Step",
				"operation": {
					"adapter": "orchestrator",
					"steps": [
						{ "op": "v/test/ops/echo", "input": {"msg": ["input", "text"]} },
						{ "op": "v/test/ops/echo", "input": {"from_prev": [0, "msg"]} },
						{ "op": "v/test/ops/echo", "input": {"from_prev": [1, "from_prev"]} }
					],
					"result": { "final": [2, "from_prev"] }
				}
			}
		""");

		Job job = engine.jobs().invokeOperation("v/ops/grid/run",
			Maps.of(Fields.OPERATION, hash, Fields.INPUT, Maps.of(Strings.create("text"), "grid-chain")),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertEquals(Strings.create("grid-chain"), RT.getIn(result, Strings.create("final")));
	}

	// ========== AP pipeline pattern with agent:request ==========

	@Test
	public void testAgentRequestPipeline() {
		// Create echo agents (test:taskcomplete auto-completes tasks with the input as output)
		for (String name : new String[]{"PipeA", "PipeB", "PipeC"}) {
			engine.jobs().invokeOperation("v/ops/agent/create",
				Maps.of(Fields.AGENT_ID, name,
					Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
				RequestContext.of(ALICE_DID)).awaitResult(5000);
		}

		String hash = storeJsonOrchestration("""
			{
				"name": "Agent Pipeline",
				"operation": {
					"adapter": "orchestrator",
					"steps": [
						{
							"op": "v/ops/agent/request",
							"input": {
								"agentId": ["const", "PipeA"],
								"input": { "invoice": ["input", "invoice_text"] },
								"wait": ["const", true]
							}
						},
						{
							"op": "v/ops/agent/request",
							"input": {
								"agentId": ["const", "PipeB"],
								"input": { "data": [0, "output"] },
								"wait": ["const", true]
							}
						},
						{
							"op": "v/ops/agent/request",
							"input": {
								"agentId": ["const", "PipeC"],
								"input": { "data": [1, "output"] },
								"wait": ["const", true]
							}
						}
					],
					"result": {
						"step_a": [0, "output"],
						"step_b": [1, "output"],
						"step_c": [2, "output"]
					}
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.of(Strings.create("invoice_text"), "test invoice"),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(30000);

		assertNotNull(result, "Agent pipeline should complete");
		// PipeA echoes its input — output should contain the invoice
		assertNotNull(RT.getIn(result, Strings.create("step_a")), "Step A should have output");
	}

	@Test
	public void testAgentRequestPipelineViaGridRun() {
		// Same as above but invoked via grid:run — the full MCP path
		for (String name : new String[]{"GridA", "GridB"}) {
			engine.jobs().invokeOperation("v/ops/agent/create",
				Maps.of(Fields.AGENT_ID, name,
					Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
				RequestContext.of(ALICE_DID)).awaitResult(5000);
		}

		String hash = storeJsonOrchestration("""
			{
				"name": "Grid Agent Pipeline",
				"operation": {
					"adapter": "orchestrator",
					"steps": [
						{
							"op": "v/ops/agent/request",
							"input": {
								"agentId": ["const", "GridA"],
								"input": { "msg": ["input", "text"] },
								"wait": ["const", true]
							}
						},
						{
							"op": "v/ops/agent/request",
							"input": {
								"agentId": ["const", "GridB"],
								"input": { "prev": [0, "output"] },
								"wait": ["const", true]
							}
						}
					],
					"result": {
						"first": [0, "output"],
						"second": [1, "output"]
					}
				}
			}
		""");

		Job job = engine.jobs().invokeOperation("v/ops/grid/run",
			Maps.of(Fields.OPERATION, hash,
				Fields.INPUT, Maps.of(Strings.create("text"), "grid-agent-test")),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(30000);

		assertNotNull(result, "Grid agent pipeline should complete");
		assertNotNull(RT.getIn(result, Strings.create("first")), "First agent should have output");
		assertNotNull(RT.getIn(result, Strings.create("second")), "Second agent should have output");
	}

	// ========== asset_store round-trip (MCP path) ==========

	@Test
	public void testAssetStoreRoundTripOrchestration() {
		// Simulates the MCP asset_store path: JSON → printPretty → storeAsset → parse
		String hash = storeJsonOrchestration("""
			{
				"name": "MCP Round Trip",
				"operation": {
					"adapter": "orchestrator",
					"steps": [{
						"op": "v/test/ops/echo",
						"input": { "val": ["input", "x"] }
					}],
					"result": { "answer": [0, "val"] }
				}
			}
		""");

		// Now re-store through the printPretty path (what asset_store does)
		AMap<AString, ACell> meta = engine.getMetaValue(Hash.parse(hash));
		AString reprinted = JSON.printPretty(meta);
		Hash rehash = engine.storeAsset(reprinted, null);

		// Same hash — JSON round-trip is stable
		assertEquals(hash, rehash.toHexString(), "JSON round-trip should produce same asset hash");

		Job job = engine.jobs().invokeOperation("v/ops/grid/run",
			Maps.of(Fields.OPERATION, hash,
				Fields.INPUT, Maps.of(Strings.create("x"), "mcp-roundtrip")),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertEquals(Strings.create("mcp-roundtrip"), RT.getIn(result, Strings.create("answer")));
	}

	@Test
	public void testAssetStoreAgentPipelineRoundTrip() {
		for (String name : new String[]{"RtA", "RtB"}) {
			engine.jobs().invokeOperation("v/ops/agent/create",
				Maps.of(Fields.AGENT_ID, name,
					Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
				RequestContext.of(ALICE_DID)).awaitResult(5000);
		}

		String hash = storeJsonOrchestration("""
			{
				"name": "RT Agent Pipeline",
				"operation": {
					"adapter": "orchestrator",
					"steps": [
						{
							"op": "v/ops/agent/request",
							"input": {
								"agentId": ["const", "RtA"],
								"input": { "msg": ["input", "text"] },
								"wait": ["const", true]
							}
						},
						{
							"op": "v/ops/agent/request",
							"input": {
								"agentId": ["const", "RtB"],
								"input": { "prev": [0, "output"] },
								"wait": ["const", true]
							}
						}
					],
					"result": {
						"first": [0, "output"],
						"second": [1, "output"]
					}
				}
			}
		""");

		// Re-store through printPretty path
		AMap<AString, ACell> meta = engine.getMetaValue(Hash.parse(hash));
		AString reprinted = JSON.printPretty(meta);
		Hash rehash = engine.storeAsset(reprinted, null);
		assertEquals(hash, rehash.toHexString(), "JSON round-trip should be stable");

		Job job = engine.jobs().invokeOperation("v/ops/grid/run",
			Maps.of(Fields.OPERATION, hash,
				Fields.INPUT, Maps.of(Strings.create("text"), "roundtrip-test")),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(30000);

		assertNotNull(result);
		assertNotNull(RT.getIn(result, Strings.create("first")), "First agent should produce output");
		assertNotNull(RT.getIn(result, Strings.create("second")), "Second agent should produce output");
	}

	// ========== Error cases ==========

	@Test
	public void testStepFailurePropagates() {
		String hash = storeJsonOrchestration("""
			{
				"name": "Fail Test",
				"operation": {
					"adapter": "orchestrator",
					"steps": [{
						"op": "v/test/ops/error",
						"input": { "message": ["const", "boom"] }
					}],
					"result": { "result": [0] }
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.empty(), RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("Should fail when a step fails");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
			String error = job.getErrorMessage();
			assertTrue(error.contains("step 0"), error);
			assertTrue(error.contains("v/test/ops/error"), error);
			assertTrue(error.contains("boom"), error);
		}
	}

	@Test
	public void testEmptyStepsExplainRequirement() {
		String hash = storeJsonOrchestration("""
			{
				"name": "Empty Test",
				"operation": {
					"adapter": "orchestrator",
					"steps": [],
					"result": {}
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.empty(), RequestContext.of(ALICE_DID));
		assertThrows(Exception.class, () -> job.awaitResult(5000));
		assertTrue(job.getErrorMessage().contains("at least one step"), job.getErrorMessage());
	}

	// ========== Concat input spec ==========

	@Test
	public void testConcatInputSpec() {
		String hash = storeJsonOrchestration("""
			{
				"name": "Concat Test",
				"operation": {
					"adapter": "orchestrator",
					"steps": [{
						"op": "v/test/ops/echo",
						"input": { "echoed": ["concat", ["const", "w/enrichments/"], ["input", "invoiceId"]] }
					}],
					"result": { "path": [0, "echoed"] }
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.of(Strings.create("invoiceId"), "INV-2024-0891"),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);
		assertEquals("w/enrichments/INV-2024-0891", RT.getIn(result, "path").toString());
	}

	@Test
	public void testConcatWithStepOutput() {
		// Step 0 produces a value, step 1 uses concat to build a path from it
		String hash = storeJsonOrchestration("""
			{
				"name": "Concat Step Output Test",
				"operation": {
					"adapter": "orchestrator",
					"steps": [
						{
							"op": "v/test/ops/echo",
							"input": { "echoed": ["const", "doc-42"] }
						},
						{
							"op": "v/test/ops/echo",
							"input": { "echoed": ["concat", ["const", "docs/"], [0, "echoed"], ["const", ".txt"]] }
						}
					],
					"result": { "path": [1, "echoed"] }
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.empty(), RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);
		assertEquals("docs/doc-42.txt", RT.getIn(result, "path").toString());
	}

	@Test
	public void testConcatInResult() {
		// Concat can also be used in the result spec
		String hash = storeJsonOrchestration("""
			{
				"name": "Concat Result Test",
				"operation": {
					"adapter": "orchestrator",
					"steps": [{
						"op": "v/test/ops/echo",
						"input": { "echoed": ["const", "hello"] }
					}],
					"result": { "greeting": ["concat", [0, "echoed"], ["const", " world"]] }
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.empty(), RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);
		assertEquals("hello world", RT.getIn(result, "greeting").toString());
	}

	// ========== JSON round-trip (simulates MCP path) — issue #44 ==========

	@Test
	public void testGridRunViaJsonRoundTrip() {
		// Simulate the MCP path: build the grid:run input as a JSON string,
		// parse it back, then invoke. This catches any CVM type differences
		// between in-process Maps.of() and JSON-parsed values.
		String orchHash = storeJsonOrchestration("""
			{
				"name": "JSON Round Trip Test",
				"operation": {
					"adapter": "orchestrator",
					"steps": [{
						"op": "v/test/ops/echo",
						"input": { "msg": ["input", "text"] }
					}],
					"result": { "answer": [0, "msg"] }
				}
			}
		""");

		// Build the grid:run invocation as JSON (like MCP would)
		String gridRunInputJson = """
			{"operation": "%s", "input": {"text": "via-json"}}
		""".formatted(orchHash);

		// Parse from JSON — this is what MCP does
		ACell parsedInput = JSON.parse(gridRunInputJson);

		Job job = engine.jobs().invokeOperation("v/ops/grid/run", parsedInput,
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result, "grid:run via JSON round-trip should produce non-null result");
		assertEquals(Strings.create("via-json"), RT.getIn(result, Strings.create("answer")),
			"Step output should resolve correctly after JSON round-trip");
	}

	@Test
	public void testGridRunMultiStepViaJsonRoundTrip() {
		// Multi-step with step dependencies — the pattern that was failing
		String orchHash = storeJsonOrchestration("""
			{
				"name": "Multi-Step JSON Test",
				"operation": {
					"adapter": "orchestrator",
					"steps": [
						{ "op": "v/test/ops/echo", "input": {"data": ["input", "value"]} },
						{ "op": "v/test/ops/echo", "input": {"data": [0, "data"]} }
					],
					"result": { "step0": [0, "data"], "step1": [1, "data"] }
				}
			}
		""");

		String gridRunInputJson = """
			{"operation": "%s", "input": {"value": "chain-test"}}
		""".formatted(orchHash);

		ACell parsedInput = JSON.parse(gridRunInputJson);

		Job job = engine.jobs().invokeOperation("v/ops/grid/run", parsedInput,
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result, "Multi-step grid:run via JSON should produce result");
		assertEquals(Strings.create("chain-test"), RT.getIn(result, Strings.create("step0")),
			"Step 0 should resolve from JSON-parsed input");
		assertEquals(Strings.create("chain-test"), RT.getIn(result, Strings.create("step1")),
			"Step 1 should resolve from step 0 output after JSON round-trip");
	}

	@Test
	public void testGridRunWithConcatViaJsonRoundTrip() {
		// Concat with JSON-parsed input — the dynamic path pattern
		String orchHash = storeJsonOrchestration("""
			{
				"name": "Concat JSON Test",
				"operation": {
					"adapter": "orchestrator",
					"steps": [{
						"op": "v/test/ops/echo",
						"input": { "path": ["concat", ["const", "w/data/"], ["input", "id"]] }
					}],
					"result": { "path": [0, "path"] }
				}
			}
		""");

		String gridRunInputJson = """
			{"operation": "%s", "input": {"id": "INV-001"}}
		""".formatted(orchHash);

		ACell parsedInput = JSON.parse(gridRunInputJson);

		Job job = engine.jobs().invokeOperation("v/ops/grid/run", parsedInput,
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertEquals("w/data/INV-001", RT.getIn(result, Strings.create("path")).toString());
	}

	// ========== Strict mode validation ==========

	@Test
	public void testStrictModePassesValidOutput() {
		// test:echo echoes its input — output matches the declared output schema
		String hash = storeJsonOrchestration("""
			{
				"name": "Strict Valid Test",
				"operation": {
					"adapter": "orchestrator",
					"strict": true,
					"steps": [{
						"op": "v/test/ops/echo",
						"input": { "echoed": ["const", "hello"] }
					}],
					"result": { "value": [0, "echoed"] }
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.empty(), RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);
		assertNotNull(result, "Strict mode should pass when output is valid");
		assertEquals("hello", RT.getIn(result, "value").toString());
	}

	@Test
	public void testStrictModePerStep() {
		// Per-step strict flag
		String hash = storeJsonOrchestration("""
			{
				"name": "Per-Step Strict Test",
				"operation": {
					"adapter": "orchestrator",
					"steps": [{
						"op": "v/test/ops/echo",
						"strict": true,
						"input": { "echoed": ["const", "hello"] }
					}],
					"result": { "value": [0, "echoed"] }
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.empty(), RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);
		assertNotNull(result, "Per-step strict should pass for valid output");
	}

	@Test
	public void testStrictModeDisabledByDefault() {
		// Without strict: true, no validation — even bad output passes
		String hash = storeJsonOrchestration("""
			{
				"name": "Non-Strict Test",
				"operation": {
					"adapter": "orchestrator",
					"steps": [{
						"op": "v/test/ops/echo",
						"input": { "echoed": ["const", "hello"] }
					}],
					"result": { "value": [0, "echoed"] }
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.empty(), RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);
		assertNotNull(result, "Non-strict should always pass");
	}

	// ========== Array literals (#281) ==========

	@Test
	public void testArrayLiteralComputesItsElements() {
		// A vector is otherwise always an expression, so this is the only way to
		// build an array whose contents reference prior steps — the shape ops
		// like v/ops/json/cond require for `cases`. ["const", …] cannot do it:
		// it freezes the subtree and leaves inner bindings as inert vectors.
		String hash = storeJsonOrchestration("""
			{
				"name": "Array literal",
				"operation": {
					"adapter": "orchestrator",
					"steps": [
						{ "op": "v/test/ops/echo", "input": ["const", {"value": "from-step-0"}] },
						{ "op": "v/test/ops/echo", "input": {
							"cases": ["array",
								{ "when": [0, "value"], "then": ["input", "v"] },
								["const", "literal"]
							] } }
					],
					"result": { "cases": [1, "cases"] }
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.of(Strings.create("v"), Strings.create("payload")),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		AVector<ACell> cases = RT.ensureVector(RT.getIn(result, Strings.create("cases")));
		assertNotNull(cases, "array binding must produce a vector");
		assertEquals(2L, cases.count());
		// Element 0 is a map whose values were each computed...
		assertEquals(Strings.create("from-step-0"),
			RT.getIn(cases.get(0), Strings.create("when")));
		assertEquals(Strings.create("payload"),
			RT.getIn(cases.get(0), Strings.create("then")));
		// ...and elements are computed independently, so a const sits alongside.
		assertEquals(Strings.create("literal"), cases.get(1));
	}

	@Test
	public void testStepReferenceInsideArrayCreatesDependency() {
		// scanDeps must mirror computeInput. Step 0 is deliberately slow: were the
		// reference buried in the array not scanned as a dependency, step 1 would
		// start immediately and resolve [0,"value"] against a step that has
		// produced nothing — silently yielding null rather than failing.
		String hash = storeJsonOrchestration("""
			{
				"name": "Array dependency",
				"operation": {
					"adapter": "orchestrator",
					"steps": [
						{ "op": "v/test/ops/delay", "input": {
							"operation": ["const", "v/test/ops/echo"],
							"input":     ["const", {"value": "late"}],
							"delay":     ["const", 300] } },
						{ "op": "v/test/ops/echo", "input": {
							"items": ["array", [0, "value"]] } }
					],
					"result": { "items": [1, "items"] }
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.empty(), RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(10000);

		AVector<ACell> items = RT.ensureVector(RT.getIn(result, Strings.create("items")));
		assertNotNull(items);
		assertEquals(1L, items.count());
		assertEquals(Strings.create("late"), items.get(0),
			"step 1 must wait for step 0; null here means the dependency inside the array was missed");
	}

	@Test
	public void testEmptyArrayLiteral() {
		String hash = storeJsonOrchestration("""
			{
				"name": "Empty array",
				"operation": {
					"adapter": "orchestrator",
					"steps": [
						{ "op": "v/test/ops/echo", "input": { "items": ["array"] } }
					],
					"result": { "items": [0, "items"] }
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.empty(), RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		AVector<ACell> items = RT.ensureVector(RT.getIn(result, Strings.create("items")));
		assertNotNull(items, "[\"array\"] must produce an empty vector, not an error");
		assertEquals(0L, items.count());
	}

	// ========== Failure containment (#281) ==========

	@Test
	public void testFailedStepDoesNotRunItsDependents() {
		// The reported case: a write gated behind a step that failed. Before the
		// fix the completion loop released a failed step's dependencies
		// unconditionally, so the dependent became ready, ran with null inputs and
		// applied its side effect — turning a failed orchestration into a
		// partially applied one.
		String path = "w/orch281/gated";
		String hash = storeJsonOrchestration("""
			{
				"name": "Gated write",
				"operation": {
					"adapter": "orchestrator",
					"steps": [
						{ "op": "v/test/ops/error", "input": { "message": ["const", "denied"] } },
						{ "op": "v/ops/covia/write", "input": {
							"path":  ["input", "p"],
							"value": [0, "result"] } }
					],
					"result": { "written": [1] }
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.of(Strings.create("p"), Strings.create(path)),
			RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("Orchestration should fail when step 0 fails");
		} catch (Exception expected) {
			assertEquals(Status.FAILED, job.getStatus());
		}

		assertFalse(readExists(path),
			"step 1 depends on failed step 0, so it must not have written " + path);
	}

	@Test
	public void testFailureStopsLaterStepsFromStarting() {
		// Containment is not only about direct dependents. Once the run has
		// failed, no further step should start — otherwise a step whose own
		// dependency succeeded still applies side effects on behalf of an
		// orchestration that can no longer succeed. Step 2 depends on step 1
		// (which succeeds), not on the failing step 0.
		//
		// Step 1 is deliberately slow so the ordering is deterministic: step 0
		// fails immediately, while step 1 is still in flight, so step 2 can only
		// have become ready in a LATER iteration. Without the fail-fast return
		// the loop would wait for step 1, release step 2, and write. (With a fast
		// step 1 this is a genuine race — it may complete and release step 2
		// before any failure is observed — and no scheduler change can prevent
		// that, which is why steps already ready or in flight are documented as
		// running to completion.)
		String path = "w/orch281/independent";
		String hash = storeJsonOrchestration("""
			{
				"name": "Fail fast",
				"operation": {
					"adapter": "orchestrator",
					"steps": [
						{ "op": "v/test/ops/error", "input": { "message": ["const", "denied"] } },
						{ "op": "v/test/ops/delay", "input": {
							"operation": ["const", "v/test/ops/echo"],
							"input":     ["const", {"value": "ok"}],
							"delay":     ["const", 500] } },
						{ "op": "v/ops/covia/write", "input": {
							"path":  ["input", "p"],
							"value": [1, "value"] } }
					],
					"result": { "written": [2] }
				}
			}
		""");

		Job job = engine.jobs().invokeOperation(hash,
			Maps.of(Strings.create("p"), Strings.create(path)),
			RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("Orchestration should fail when step 0 fails");
		} catch (Exception expected) {
			assertEquals(Status.FAILED, job.getStatus());
		}

		// Proving a negative needs a bounded wait. The job fails almost at once,
		// but step 1 only finishes at ~500ms — and it is releasing step 2 that
		// would cause the write. Check well past that point, or the assertion
		// would pass even against the unfixed code.
		try {
			Thread.sleep(1500);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		assertFalse(readExists(path),
			"no step may start once the orchestration has failed; " + path + " was written");
	}

	// ========== Helper ==========

	/** True if {@code path} holds data in ALICE's namespace. */
	private boolean readExists(String path) {
		ACell read = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create(path)),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		return CVMBool.TRUE.equals(RT.getIn(read, Strings.create("exists")));
	}

	/**
	 * Stores a JSON orchestration string as an asset and returns the hex hash for invocation.
	 */
	private String storeJsonOrchestration(String json) {
		AString metaString = Strings.create(json);
		Hash hash = engine.storeAsset(metaString, null);
		return hash.toHexString();
	}
}
