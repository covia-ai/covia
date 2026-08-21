package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/** The completion boundary: one judgement for tools and replies alike. */
public class CompletionTest {

	private static final AMap<AString, ACell> ANSWER_SCHEMA = Maps.of(
		"type", "object",
		"properties", Maps.of("answer", Maps.of("type", "string")),
		"required", Vectors.of(Strings.create("answer")));

	@Test
	public void testNothingDeliveredFallsBackToTheTurnText() {
		Completion c = Completion.of(null, Strings.create("Four."), null, "complete_task");
		assertTrue(c.accepted());
		assertEquals(Strings.create("Four."), c.value());
		// A blank string and an empty object are equally "nothing".
		assertEquals(Strings.create("Four."), Completion.of(Strings.create("  "), Strings.create("Four."), null, "complete").value());
		assertEquals(Strings.create("Four."), Completion.of(Maps.empty(), Strings.create("Four."), null, "complete").value());
		// Any other value was meant, however small.
		assertEquals(CVMLong.create(0), Completion.of(CVMLong.create(0), Strings.create("Four."), null, "complete").value());
	}

	@Test
	public void testNothingDeliveredAndNoTextIsRejectedNamingTheTool() {
		Completion c = Completion.of(Maps.empty(), null, null, "fail_task");
		assertFalse(c.accepted());
		assertNull(c.value());
		assertTrue(c.rejection().startsWith("fail_task was called with nothing to deliver"), c.rejection());
		assertTrue(c.toolError().toString().startsWith("Error: fail_task"), "tool-result convention");
		// A reply has no tool to name.
		assertEquals("The reply was empty. Respond again with the answer.",
			Completion.of(Strings.create(""), null, null, null).rejection());
	}

	@Test
	public void testSchemaInForceParsesJsonTextThenJudges() {
		Completion c = Completion.of(Strings.create("{\"answer\": \"42\"}"), null, ANSWER_SCHEMA, "complete");
		assertTrue(c.accepted(), c.rejection());
		assertEquals(Strings.create("42"), RT.getIn(c.value(), "answer"));
		// Prose against an object schema: rejected, the schema shown, the tool named.
		Completion prose = Completion.of(Strings.create("forty-two"), null, ANSWER_SCHEMA, "complete");
		assertFalse(prose.accepted());
		assertTrue(prose.rejection().contains("does not conform") && prose.rejection().contains("\"answer\"")
			&& prose.rejection().endsWith("call complete again."), prose.rejection());
		// The same judgement on a reply is phrased for a reply.
		assertTrue(Completion.of(Strings.create("forty-two"), null, ANSWER_SCHEMA, null)
			.rejection().endsWith("Respond again with valid JSON matching it."));
		// A structured payload is judged as it is.
		assertTrue(Completion.of(Maps.of("answer", "42"), null, ANSWER_SCHEMA, "complete").accepted());
		assertFalse(Completion.of(Maps.of("other", "42"), null, ANSWER_SCHEMA, "complete").accepted());
	}

	@Test
	public void testNoSchemaPassesAnyValueThrough() {
		ACell v = Maps.of("anything", Vectors.of(CVMLong.create(1)));
		assertSame(v, Completion.of(v, null, null, "complete").value());
		assertEquals(Strings.create("prose"), Completion.of(Strings.create("prose"), null, null, null).value());
	}
}
