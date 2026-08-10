package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;

/**
 * Tests for venue {@link Config} default-resolution getters — in particular the
 * venue-level default LLM provider / transition op knobs that replace
 * hardcoded constants in {@code AgentAdapter}.
 */
public class ConfigTest {

	@Test
	public void testReadOnlyRecordingDefaultsOffAndCanBeForced() {
		assertFalse(new Config(null).isRecordReadOnlyOperations());
		assertTrue(new Config(Maps.of(
			Config.RECORD_READ_ONLY_OPERATIONS, CVMBool.TRUE))
			.isRecordReadOnlyOperations());
		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Config.RECORD_READ_ONLY_OPERATIONS, Strings.create("yes"))));
	}

	@Test
	public void testUserAutoCreateDefaultsOffAndCanBeEnabled() {
		assertFalse(new Config(null).isUserAutoCreate());
		assertTrue(new Config(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, CVMBool.TRUE)))
			.isUserAutoCreate());
	}

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
	public void testMaxToolIterationsDefaultAndOverride() {
		// Unset → 30; operator-configured value wins; malformed known values fail.
		assertEquals(30, new Config(null).getMaxToolIterations());
		assertEquals(100, new Config(Maps.of(
			Config.MAX_TOOL_ITERATIONS, convex.core.data.prim.CVMLong.create(100)))
			.getMaxToolIterations());
		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Config.MAX_TOOL_ITERATIONS, convex.core.data.prim.CVMLong.create(0))));
	}

	@Test
	public void testUnknownFieldsWarnUnlessStrict() {
		// Compatibility default: a field from a newer runtime is retained and ignored.
		Config compatible = new Config(Maps.of(
			Strings.create("futureFeature"), Strings.create("value")));
		assertEquals("value",
			compatible.getMap().get(Strings.create("futureFeature")).toString());

		IllegalArgumentException strict = assertThrows(IllegalArgumentException.class,
			() -> new Config(Maps.of(
				Config.STRICT_CONFIG, CVMBool.TRUE,
				Strings.create("futureFeature"), Strings.create("value"))));
		assertTrue(strict.getMessage().contains("venue.futureFeature"));

		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Config.STRICT_CONFIG, CVMBool.TRUE,
			Config.RATE_LIMIT, Maps.of(Strings.create("rsp"), 10L))));
	}

	@Test
	public void testKnownMalformedFieldsAlwaysFail() {
		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Config.PORT, Strings.create("8080"))));
		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Config.AUTH, Strings.create("public"))));
		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Config.AUTH, Maps.of(Config.AUDIENCE, Strings.create("optional")))));
		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Config.RATE_LIMIT, Maps.of(Strings.create("rps"), 0L))));
		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Config.MCP, Maps.of(
				Config.AUTH, Maps.of(
					Config.ALLOWED_DIDS, Vectors.of("did:not valid"))))));
	}

	@Test
	public void testSupportedMcpFieldsPassStrictValidation() {
		String allowedDid = "did:web:venue.example:u:admin";
		Config strict = new Config(Maps.of(
			Config.STRICT_CONFIG, true,
			Config.MCP, Maps.of(
				Config.ENABLED, true,
				"includeAdapters", Vectors.of("covia", "user"),
				"includePathPrefixes", Vectors.of("v/ops/"),
				"serverInfo", Maps.of("name", "strict-test"),
				"servers", Maps.empty(),
				Config.AUTH, Maps.of(
					Config.REQUIRED, true,
					Config.ALLOWED_DIDS, Vectors.of(allowedDid)))));
		assertTrue(strict.isMCPAuthRequired());
		assertTrue(strict.getMCPAllowedDids().contains(allowedDid));
	}

	@Test
	public void testExtensibleAdapterConfigAllowedInStrictMode() {
		Config c = new Config(Maps.of(
			Config.STRICT_CONFIG, CVMBool.TRUE,
			Config.ADAPTERS, Maps.of(
				Strings.create("future-adapter"),
				Maps.of(Strings.create("vendorOption"), Strings.create("ok")))));
		assertEquals("ok", c.getAdapterConfig("future-adapter")
			.get(Strings.create("vendorOption")).toString());
	}

	@Test
	public void testModuleConfigIsOpaqueButMustBeAnObject() {
		Config config = new Config(Maps.of(
			Config.STRICT_CONFIG, true,
			Config.MODULES, Vectors.of(Maps.of(
				"path", "modules/example.jar",
				"config", Maps.of("vendorOption", "ok")))));
		assertTrue(config.isStrictConfig());

		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Config.MODULES, Vectors.of(Maps.of(
				"path", "modules/example.jar", "config", "bad")))));
	}

	@Test
	public void testRootPagePolicyIsUnambiguousAndSafe() {
		Config redirect = new Config(Maps.of(
			Config.ROOT_PAGE, Maps.of(
				Config.REDIRECT, Strings.create("/operator"))));
		assertEquals("/operator", redirect.getRootPage().redirect());
		assertTrue(redirect.getRootPage().isRedirect());

		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Config.ROOT_PAGE, Maps.empty())));
		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Config.ROOT_PAGE, Maps.of(
				Config.REDIRECT, Strings.create("//evil.example")))));
		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Config.ROOT_PAGE, Maps.of(
				Config.FILE, Strings.create("definitely-not-a-real-file.html")))));
	}

	@Test
	public void testStandaloneStrictConfigIsInheritedByVenues() {
		var venues = Config.validateServerConfig(Maps.of(
			Config.STRICT_CONFIG, CVMBool.TRUE,
			Config.VENUES, Vectors.of(Maps.of(
				Config.NAME, Strings.create("Strict venue")))));
		assertEquals(1, venues.size());
		assertEquals(CVMBool.TRUE, venues.get(0).get(Config.STRICT_CONFIG));

		assertThrows(IllegalArgumentException.class, () -> {
			var inherited = Config.validateServerConfig(Maps.of(
				Config.STRICT_CONFIG, CVMBool.TRUE,
				Config.VENUES, Vectors.of(Maps.of(
					Strings.create("typoField"), Strings.create("value")))));
			new Config(inherited.get(0));
		});
		assertThrows(IllegalArgumentException.class,
			() -> Config.validateServerConfig(Maps.of(
				Config.VENUES, Strings.create("not-an-array"))));
	}

	@Test
	public void testDefaultTransitionOpOverride() {
		Config c = new Config(Maps.of(
			Config.DEFAULT_TRANSITION_OP, Strings.create("v/ops/goaltree/chat")));
		assertEquals("v/ops/goaltree/chat", c.getDefaultTransitionOp().toString());
	}

	@Test
	public void testGetAdapterConfigAbsent() {
		// No adapters section, or an adapter without a block → empty map
		assertEquals(0, new Config(null).getAdapterConfig("agent").count());
		Config c = new Config(Maps.of(Config.ADAPTERS,
			Maps.of(Strings.create("agent"), Maps.of(
				Strings.create("sessionDelete"), convex.core.data.prim.CVMBool.FALSE))));
		assertEquals(0, c.getAdapterConfig("covia").count());
	}

	@Test
	public void testGetAdapterConfigPresent() {
		Config c = new Config(Maps.of(Config.ADAPTERS,
			Maps.of(Strings.create("agent"), Maps.of(
				Strings.create("sessionDelete"), convex.core.data.prim.CVMBool.FALSE))));
		assertEquals(convex.core.data.prim.CVMBool.FALSE,
			c.getAdapterConfig("agent").get(Strings.create("sessionDelete")));
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

	// ========== CORS policy (covia#267) ==========

	@Test
	public void testCorsPolicyLegacyAndDefaultForms() {
		Config.CorsPolicy defaults = new Config(null).getCorsPolicy();
		assertTrue(defaults.enabled());
		assertTrue(defaults.anyOrigin());
		assertEquals("*", defaults.allowedOriginHeader("https://anything.example"));

		Config.CorsPolicy single = new Config(Maps.of(
			Config.CORS_ORIGINS, Strings.create("https://app.example"))).getCorsPolicy();
		assertEquals("https://app.example",
			single.allowedOriginHeader("https://app.example"));
		assertNull(single.allowedOriginHeader("https://other.example"));

		// Backwards compatibility with Javalin allowHost: a bare configured
		// host receives the legacy default HTTPS scheme.
		Config.CorsPolicy bare = new Config(Maps.of(
			Config.CORS_ORIGINS, Strings.create("app.example"))).getCorsPolicy();
		assertEquals("https://app.example",
			bare.allowedOriginHeader("https://app.example"));
		assertNull(bare.allowedOriginHeader("http://app.example"));
	}

	@Test
	public void testCorsPolicyListAndLoopbackSentinel() {
		Config.CorsPolicy policy = new Config(Maps.of(
			Config.CORS_ORIGINS, Vectors.of(
				Strings.create("https://app.example"),
				Strings.create("loopback")))).getCorsPolicy();

		assertEquals("https://app.example",
			policy.allowedOriginHeader("https://app.example"));
		assertEquals("http://localhost:3000",
			policy.allowedOriginHeader("http://localhost:3000"));
		assertEquals("https://127.0.0.1:9443",
			policy.allowedOriginHeader("https://127.0.0.1:9443"));
		assertEquals("http://[::1]:8080",
			policy.allowedOriginHeader("http://[::1]:8080"));
		assertNull(policy.allowedOriginHeader("http://localhost.evil:3000"));
		assertNull(policy.allowedOriginHeader("http://127.0.0.2:3000"));
	}

	@Test
	public void testCorsPolicyCanBeDisabled() {
		for (Object value : new Object[] {CVMBool.FALSE, Strings.create("none"), Vectors.empty()}) {
			Config.CorsPolicy policy = new Config(Maps.of(Config.CORS_ORIGINS, value))
				.getCorsPolicy();
			assertFalse(policy.enabled());
			assertNull(policy.allowedOriginHeader("https://app.example"));
		}
	}

	@Test
	public void testCorsPolicyRejectsAmbiguousOrMalformedConfig() {
		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Config.CORS_ORIGINS, Vectors.of(Strings.create("none"),
				Strings.create("https://app.example")))).getCorsPolicy());
		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Config.CORS_ORIGINS, Strings.create("https://app.example/path"))).getCorsPolicy());
		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Config.CORS_ORIGINS, CVMBool.TRUE)).getCorsPolicy());
	}
}
