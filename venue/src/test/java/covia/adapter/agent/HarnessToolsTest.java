package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

/**
 * {@link HarnessTools#invocation} — decoding an {@code invoke_tool} call.
 *
 * <p>The rule under test (covia#508): {@code input} is the target tool's
 * arguments. Tool arguments are an object under every tool protocol (MCP,
 * provider tool use), so a string has exactly one valid interpretation — the
 * model serialised the object it meant to send — and is parsed. That is the
 * same wire-boundary parse direct tool calls get, and it is permitted only
 * because this is a tool call: a Covia operation's input may be any JSON
 * value, so operations never parse a string into an object (see
 * {@code CoviaAdapterTest} and {@code GridAdapterTest} for the other side of
 * the line). Anything that is not an object after parsing is a typed error
 * naming what was received, so the model sees its own mistake instead of the
 * target operation's downstream failure ("Path must include namespace and
 * key" for a null path).</p>
 */
public class HarnessToolsTest {

	private static AMap<?, ?> args(String name, ACell input) {
		return (input == null) ? Maps.of("name", name) : Maps.of("name", name, "input", input);
	}

	@Test
	public void testObjectInputPassesThroughUnchanged() {
		ACell input = Maps.of("path", "w/projects/p", "value", Maps.of("meta", Maps.of("id", "p")));
		HarnessTools.Invocation inv = HarnessTools.invocation(args("covia_write", input));
		assertNull(inv.error());
		assertEquals("covia_write", inv.name());
		// Same instance: types are preserved end-to-end for structured input.
		assertTrue(inv.input() == input);
	}

	@Test
	public void testAbsentInputIsEmptyArguments() {
		// input is optional in the dispatcher schema; absent means "no arguments",
		// exactly as absent tool-call arguments do.
		HarnessTools.Invocation inv = HarnessTools.invocation(args("test_echo", null));
		assertNull(inv.error());
		assertEquals(Maps.empty(), inv.input());
	}

	@Test
	public void testStringifiedObjectIsParsedIdenticallyToObjectForm() {
		// The #508 case: a large nested payload the model emitted as a JSON
		// string. Because input can only be an object, parsing is the only
		// correct reading — keeping the string would send a null path to the
		// operation and a misleading error back to the model.
		ACell object = Maps.of("path", "w/projects/p",
			"value", Maps.of("meta", Maps.of("id", "p", "order", CVMLong.create(0)),
				"products", Vectors.of(Maps.of("id", "report"))));
		String json = "{\"path\": \"w/projects/p\", \"value\": {\"meta\": {\"id\": \"p\", \"order\": 0}, "
			+ "\"products\": [{\"id\": \"report\"}]}}";
		HarnessTools.Invocation fromString = HarnessTools.invocation(args("covia_write", Strings.create(json)));
		HarnessTools.Invocation fromObject = HarnessTools.invocation(args("covia_write", object));
		assertNull(fromString.error());
		assertEquals(fromObject.input(), fromString.input());
		assertEquals(fromObject.name(), fromString.name());
	}

	@Test
	public void testDoubleEncodedObjectGetsOneExtraPass() {
		// Same allowance as ToolCallArguments.parse for historically
		// double-encoded arguments: a JSON string whose content is itself a JSON
		// object is unwrapped once more.
		String json = "\"{\\\"path\\\": \\\"w/x\\\"}\"";
		HarnessTools.Invocation inv = HarnessTools.invocation(args("covia_read", Strings.create(json)));
		assertNull(inv.error(), String.valueOf(inv.error()));
		assertEquals(Maps.of("path", "w/x"), inv.input());
	}

	@Test
	public void testStringThatIsNotJsonIsATypedError() {
		// Garbage is never repaired into {} (that would hide the mistake and
		// dispatch an empty call); it is an error the model can act on, naming
		// the shape received so the fix is obvious.
		HarnessTools.Invocation inv = HarnessTools.invocation(args("covia_write", Strings.create("path=w/x value=1")));
		assertNull(inv.name());
		assertNotNull(inv.error());
		String error = inv.error().toString();
		assertTrue(error.startsWith("Error: invoke_tool input must be an object"), error);
		assertTrue(error.contains("string of 16 characters"), error);
		assertTrue(error.contains("not valid JSON"), error);
	}

	@Test
	public void testStringThatParsesToNonObjectIsATypedError() {
		// Valid JSON but not an object: a quoted string, a number, an array. A
		// tool's arguments cannot be any of these, so there is nothing to
		// dispatch and the error says what arrived.
		for (String json : new String[] {"\"just text\"", "42", "[\"w/x\"]"}) {
			HarnessTools.Invocation inv = HarnessTools.invocation(args("covia_read", Strings.create(json)));
			assertNull(inv.name(), json);
			assertNotNull(inv.error(), json);
			assertTrue(inv.error().toString().startsWith("Error: invoke_tool input must be an object"), json);
		}
		HarnessTools.Invocation array = HarnessTools.invocation(args("covia_read", Strings.create("[\"w/x\"]")));
		assertTrue(array.error().toString().contains("an array: [\"w/x\"]"), array.error().toString());
	}

	@Test
	public void testStructuredNonObjectIsATypedError() {
		// A model can also emit a bare array or scalar directly. Same rule.
		HarnessTools.Invocation inv = HarnessTools.invocation(args("covia_read", Vectors.of(Strings.create("w/x"))));
		assertNotNull(inv.error());
		assertTrue(inv.error().toString().contains("got an array"), inv.error().toString());
		HarnessTools.Invocation scalar = HarnessTools.invocation(args("covia_read", CVMLong.create(7)));
		assertTrue(scalar.error().toString().contains("got a scalar: 7"), scalar.error().toString());
	}

	@Test
	public void testMissingNameIsStillTheFirstError() {
		HarnessTools.Invocation inv = HarnessTools.invocation(Maps.of("input", Maps.of("path", "w/x")));
		assertNotNull(inv.error());
		assertTrue(inv.error().toString().contains("requires the exact added tool name"));
	}
}
