package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Schema ops under the #89 boundary rule: the {@code value} is processed
 * exactly as given (a JSON-looking string IS a string — no silent reparse,
 * since a validation verdict must apply to what the caller actually sent),
 * and a malformed string-encoded {@code schema} is reported with its real
 * cause, never misreported as "'schema' is required".
 */
public class SchemaAdapterTest {

	private final Engine engine = TestEngine.ENGINE;
	private RequestContext CTX;

	@BeforeEach
	public void setup(TestInfo info) {
		CTX = RequestContext.of(TestEngine.uniqueDID(info));
	}

	private ACell invoke(String op, ACell input) {
		return engine.jobs().invokeOperation(op, input, CTX).awaitResult(5000);
	}

	// ========== value is validated exactly as given ==========

	@Test
	public void testJsonLookingStringValidatesAsString() {
		// A string that happens to look like JSON is still a string — it
		// passes {"type":"string"} and fails {"type":"object"}.
		AString jsonish = Strings.create("{\"a\":1}");

		ACell asString = invoke("v/ops/schema/validate", Maps.of(
			"schema", Maps.of("type", "string"), "value", jsonish));
		assertEquals(CVMBool.TRUE, RT.getIn(asString, "valid"),
			"a JSON-looking string is a string, and must validate as one");

		ACell asObject = invoke("v/ops/schema/validate", Maps.of(
			"schema", Maps.of("type", "object"), "value", jsonish));
		assertEquals(CVMBool.FALSE, RT.getIn(asObject, "valid"),
			"a string must NOT be silently reparsed into the object it resembles");
	}

	@Test
	public void testInferSeesTheStringNotTheStructure() {
		ACell result = invoke("v/ops/schema/infer", Maps.of(
			"value", Strings.create("[1,2,3]")));
		assertEquals(Strings.create("string"), RT.getIn(result, "schema", "type"),
			"infer must describe what the caller sent, not what it resembles");
	}

	// ========== schema parameter: string form parsed, garbage rejected ==========

	@Test
	public void testStringEncodedSchemaAccepted() {
		// LLM callers stringify schemas; a well-formed one parses.
		ACell result = invoke("v/ops/schema/validate", Maps.of(
			"schema", Strings.create("{\"type\":\"number\"}"),
			"value", convex.core.data.prim.CVMLong.create(5)));
		assertEquals(CVMBool.TRUE, RT.getIn(result, "valid"));
	}

	@Test
	public void testMalformedSchemaStringReportsRealCause() {
		Job job = engine.jobs().invokeOperation("v/ops/schema/validate", Maps.of(
			"schema", Strings.create("{broken json"),
			"value", Strings.create("x")), CTX);
		JobFailedException ex = assertThrows(JobFailedException.class,
			() -> job.awaitResult(5000));
		assertTrue(ex.getMessage().contains("not valid JSON"),
			"malformed schema must be reported as the parse error it is, got: " + ex.getMessage());
	}

	@Test
	public void testNonObjectSchemaStringRejected() {
		Job job = engine.jobs().invokeOperation("v/ops/schema/validate", Maps.of(
			"schema", Strings.create("[1,2]"),
			"value", Strings.create("x")), CTX);
		JobFailedException ex = assertThrows(JobFailedException.class,
			() -> job.awaitResult(5000));
		assertTrue(ex.getMessage().contains("must be a JSON object"),
			"a non-object schema must be rejected with the real reason, got: " + ex.getMessage());
	}
}
