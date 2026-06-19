package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.data.Strings;

/**
 * Tests for venue {@link Config} default-resolution getters — in particular the
 * venue-level default LLM provider / transition op knobs that replace
 * hardcoded constants in {@code AgentAdapter}.
 */
public class ConfigTest {

	@Test
	public void testDefaultLlmOperationFallback() {
		// Unset → the built-in default provider op.
		assertEquals("v/ops/langchain/openai",
			new Config(null).getDefaultLlmOperation().toString());
	}

	@Test
	public void testDefaultLlmOperationOverride() {
		// Operator sets a different default provider for the whole venue.
		Config c = new Config(Maps.of(
			Config.DEFAULT_LLM_OPERATION, Strings.create("v/ops/langchain/anthropic")));
		assertEquals("v/ops/langchain/anthropic", c.getDefaultLlmOperation().toString());
	}

	@Test
	public void testDefaultTransitionOpFallback() {
		assertEquals("v/ops/llmagent/chat",
			new Config(null).getDefaultTransitionOp().toString());
	}

	@Test
	public void testDefaultTransitionOpOverride() {
		Config c = new Config(Maps.of(
			Config.DEFAULT_TRANSITION_OP, Strings.create("v/ops/goaltree/chat")));
		assertEquals("v/ops/goaltree/chat", c.getDefaultTransitionOp().toString());
	}
}
