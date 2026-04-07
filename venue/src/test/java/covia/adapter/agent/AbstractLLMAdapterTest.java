package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;

/**
 * Tests for AbstractLLMAdapter — the shared base for LLM-backed agent adapters.
 * Covers the static utility contracts that both LLMAgentAdapter and
 * GoalTreeAdapter depend on.
 */
public class AbstractLLMAdapterTest {

	// ========== hasToolCalls ==========

	@Test
	public void testHasToolCallsTrue() {
		ACell result = Maps.of(
			"role", Strings.create("assistant"),
			"toolCalls", Vectors.of(Maps.of(
				"id", Strings.create("call_1"),
				"name", Strings.create("test"),
				"arguments", Strings.create("{}"))));
		assertTrue(AbstractLLMAdapter.hasToolCalls(result));
	}

	@Test
	public void testHasToolCallsFalseNoField() {
		ACell result = Maps.of(
			"role", Strings.create("assistant"),
			"content", Strings.create("Hello"));
		assertFalse(AbstractLLMAdapter.hasToolCalls(result));
	}

	@Test
	public void testHasToolCallsFalseEmptyVector() {
		ACell result = Maps.of(
			"role", Strings.create("assistant"),
			"toolCalls", Vectors.empty());
		assertFalse(AbstractLLMAdapter.hasToolCalls(result));
	}

	@Test
	public void testHasToolCallsFalseNull() {
		assertFalse(AbstractLLMAdapter.hasToolCalls(null));
	}

	// ========== getToolCalls ==========

	@Test
	public void testGetToolCallsPresent() {
		ACell tc = Maps.of("id", Strings.create("call_1"));
		ACell result = Maps.of("toolCalls", Vectors.of(tc));
		AVector<ACell> calls = AbstractLLMAdapter.getToolCalls(result);
		assertEquals(1, calls.count());
	}

	@Test
	public void testGetToolCallsAbsent() {
		ACell result = Maps.of("content", Strings.create("text"));
		AVector<ACell> calls = AbstractLLMAdapter.getToolCalls(result);
		assertEquals(0, calls.count());
	}

	@Test
	public void testGetToolCallsNull() {
		AVector<ACell> calls = AbstractLLMAdapter.getToolCalls(null);
		assertEquals(0, calls.count());
	}

	// ========== ensureParsedInput ==========

	@Test
	public void testEnsureParsedInputNull() {
		ACell result = AbstractLLMAdapter.ensureParsedInput(null);
		assertNotNull(result);
		// Should return empty map
		assertTrue(result instanceof AMap);
		assertEquals(0, ((AMap<?, ?>) result).count());
	}

	@Test
	public void testEnsureParsedInputMap() {
		AMap<AString, ACell> map = Maps.of(Strings.create("key"), Strings.create("value"));
		ACell result = AbstractLLMAdapter.ensureParsedInput(map);
		assertSame(map, result); // Should pass through unchanged
	}

	@Test
	public void testEnsureParsedInputJsonString() {
		AString json = Strings.create("{\"key\":\"value\"}");
		ACell result = AbstractLLMAdapter.ensureParsedInput(json);
		// Should parse into a map
		assertTrue(result instanceof AMap);
		AString val = RT.ensureString(RT.getIn(result, "key"));
		assertNotNull(val);
		assertEquals("value", val.toString());
	}

	@Test
	public void testEnsureParsedInputNonJsonString() {
		AString plain = Strings.create("not json");
		ACell result = AbstractLLMAdapter.ensureParsedInput(plain);
		// Should return as-is (not parseable)
		assertSame(plain, result);
	}

	@Test
	public void testEnsureParsedInputVector() {
		AVector<ACell> vec = Vectors.of(Strings.create("a"));
		ACell result = AbstractLLMAdapter.ensureParsedInput(vec);
		assertSame(vec, result); // Vectors pass through unchanged
	}

	// ========== toolResultMessage ==========

	@Test
	public void testToolResultMessageString() {
		AMap<AString, ACell> msg = AbstractLLMAdapter.toolResultMessage(
			Strings.create("call_1"), "test_echo", Strings.create("hello"));

		assertEquals("tool", RT.ensureString(msg.get(AbstractLLMAdapter.K_ROLE)).toString());
		assertEquals("call_1", RT.ensureString(msg.get(AbstractLLMAdapter.K_ID)).toString());
		assertEquals("test_echo", RT.ensureString(msg.get(AbstractLLMAdapter.K_NAME)).toString());
		assertEquals("hello", RT.ensureString(msg.get(AbstractLLMAdapter.K_CONTENT)).toString());
		// String result goes into content, not structuredContent
		assertNull(msg.get(AbstractLLMAdapter.K_STRUCTURED_CONTENT));
	}

	@Test
	public void testToolResultMessageMap() {
		AMap<AString, ACell> data = Maps.of(
			Strings.create("status"), Strings.create("ok"));
		AMap<AString, ACell> msg = AbstractLLMAdapter.toolResultMessage(
			Strings.create("call_2"), "covia_read", data);

		assertEquals("tool", RT.ensureString(msg.get(AbstractLLMAdapter.K_ROLE)).toString());
		// Map result goes into structuredContent
		assertNotNull(msg.get(AbstractLLMAdapter.K_STRUCTURED_CONTENT));
		assertNull(msg.get(AbstractLLMAdapter.K_CONTENT));
	}

	@Test
	public void testToolResultMessageVector() {
		AVector<ACell> data = Vectors.of(Strings.create("a"), Strings.create("b"));
		AMap<AString, ACell> msg = AbstractLLMAdapter.toolResultMessage(
			Strings.create("call_3"), "covia_list", data);

		// Vector result goes into structuredContent
		assertNotNull(msg.get(AbstractLLMAdapter.K_STRUCTURED_CONTENT));
		assertNull(msg.get(AbstractLLMAdapter.K_CONTENT));
	}

	// ========== getLLMOperation ==========

	@Test
	public void testGetLLMOperationFromConfig() {
		AMap<AString, ACell> config = Maps.of(
			AbstractLLMAdapter.K_LLM_OPERATION, Strings.create("langchain:ollama"));
		AString op = AbstractLLMAdapter.getLLMOperation(config);
		assertEquals("langchain:ollama", op.toString());
	}

	@Test
	public void testGetLLMOperationDefault() {
		AMap<AString, ACell> config = Maps.of(
			Strings.create("model"), Strings.create("gpt-4o"));
		AString op = AbstractLLMAdapter.getLLMOperation(config);
		assertEquals("langchain:openai", op.toString());
	}

	@Test
	public void testGetLLMOperationNullConfig() {
		AString op = AbstractLLMAdapter.getLLMOperation(null);
		assertEquals("langchain:openai", op.toString());
	}

	// ========== copyIfPresent ==========

	@Test
	public void testCopyIfPresentCopiesExisting() {
		AMap<AString, ACell> source = Maps.of(
			Strings.create("model"), Strings.create("gpt-4o"),
			Strings.create("url"), Strings.create("https://api.example.com"));
		AMap<AString, ACell> target = Maps.of(
			Strings.create("messages"), Strings.create("[]"));

		AMap<AString, ACell> result = AbstractLLMAdapter.copyIfPresent(
			source, target,
			Strings.create("model"), Strings.create("url"), Strings.create("apiKey"));

		assertEquals("gpt-4o", RT.ensureString(result.get(Strings.create("model"))).toString());
		assertEquals("https://api.example.com", RT.ensureString(result.get(Strings.create("url"))).toString());
		// apiKey not in source, should not be in result
		assertNull(result.get(Strings.create("apiKey")));
		// Original target field preserved
		assertNotNull(result.get(Strings.create("messages")));
	}

	@Test
	public void testCopyIfPresentNullSource() {
		AMap<AString, ACell> target = Maps.of(
			Strings.create("key"), Strings.create("val"));
		AMap<AString, ACell> result = AbstractLLMAdapter.copyIfPresent(
			null, target, Strings.create("model"));
		assertSame(target, result); // Should return target unchanged
	}

	@Test
	public void testCopyIfPresentNoMatch() {
		AMap<AString, ACell> source = Maps.of(
			Strings.create("other"), Strings.create("value"));
		AMap<AString, ACell> target = Maps.empty();

		AMap<AString, ACell> result = AbstractLLMAdapter.copyIfPresent(
			source, target, Strings.create("model"));
		assertEquals(0, result.count());
	}
}
