package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

	// ========== did:web alias derivation (covia#167) ==========

	@Test
	public void testWebDIDNullByDefault() {
		// No hostname configured → default "localhost" → no did:web alias.
		assertNull(new Config(null).getWebDID());
	}

	@Test
	public void testWebDIDForPublicDomain() {
		Config c = new Config(Maps.of(Config.HOSTNAME, Strings.create("venue-1.covia.ai")));
		assertEquals("did:web:venue-1.covia.ai", c.getWebDID().toString());
	}

	@Test
	public void testWebDIDRejectsNonPublicHosts() {
		// The alias is only served for a genuine public DNS name.
		assertNull(webDIDFor("localhost"), "bare localhost");
		assertNull(webDIDFor("myhost"), "dotless bare name");
		assertNull(webDIDFor("192.168.1.10"), "IPv4 literal");
		assertNull(webDIDFor("venue.example.com:8080"), "host:port form");
		assertNull(webDIDFor("[::1]"), "IPv6 literal");
	}

	private static Object webDIDFor(String host) {
		return new Config(Maps.of(Config.HOSTNAME, Strings.create(host))).getWebDID();
	}
}
