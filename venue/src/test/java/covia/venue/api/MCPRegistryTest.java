package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.LocalVenue;

/**
 * Unit tests for MCP tool registry construction, config-driven filtering,
 * and tool-call routing. Constructs ephemeral engines via
 * {@link Engine#createTemp} so each test can vary its config independently.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class MCPRegistryTest {

	private static final AString K_INCLUDE_ADAPTERS = Strings.create("includeAdapters");
	private static final AString K_INCLUDE_PATH_PREFIXES = Strings.create("includePathPrefixes");

	/**
	 * Build a fresh engine with all default adapters registered and v/ops/
	 * materialised. Equivalent to a production venue's startup.
	 */
	private Engine freshEngine() {
		Engine engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
		return engine;
	}

	private MCP mcp(Engine engine, AMap<AString, ACell> mcpConfig) {
		return new MCP(new LocalVenue(engine), mcpConfig);
	}

	private Set<String> toolNames(MCP mcp) {
		AMap<AString, ACell> result = mcp.listTools();
		AVector<?> tools = (AVector<?>) result.get(Strings.create("tools"));
		return tools.stream()
			.map(t -> RT.getIn((ACell) t, Fields.NAME).toString())
			.collect(Collectors.toSet());
	}

	private Set<String> groupsOf(Set<String> toolNames) {
		return toolNames.stream()
			.map(n -> n.contains("_") ? n.substring(0, n.indexOf('_')) : n)
			.collect(Collectors.toSet());
	}

	// ==================== Default include set ====================

	@Test public void testDefaultExposesOnlyAllowlistedGroups() {
		MCP mcp = mcp(freshEngine(), Maps.empty());
		Set<String> groups = groupsOf(toolNames(mcp));
		assertEquals(Set.of("agent", "asset", "covia", "grid", "secret"), groups,
			"Default include set should expose exactly the 5 allowlisted groups");
	}

	@Test public void testDefaultHidesUtilityAdapters() {
		Set<String> names = toolNames(mcp(freshEngine(), Maps.empty()));
		// Utility adapters callers can substitute themselves
		assertFalse(names.contains("http_get"),    "http_* should be hidden by default");
		assertFalse(names.contains("json_merge"),  "json_* should be hidden by default");
		assertFalse(names.contains("schema_validate"), "schema_* should be hidden by default");
		assertFalse(names.contains("string_concat"),   "jvm string utilities should be hidden by default");
		assertFalse(names.contains("llmagent_chat"),   "llmagent should be hidden by default");
	}

	@Test public void testDefaultHidesTestAndAdminAdapters() {
		Set<String> names = toolNames(mcp(freshEngine(), Maps.empty()));
		assertFalse(names.stream().anyMatch(n -> n.startsWith("test_")),
			"test_* should be hidden (different namespace anyway)");
		assertFalse(names.contains("ucan_issue"),   "ucan should be hidden by default");
		assertFalse(names.stream().anyMatch(n -> n.startsWith("a2a_")),
			"a2a should be hidden by default");
	}

	// ==================== Wildcard include ====================

	@Test public void testWildcardExposesEverythingUnderVOps() {
		MCP mcp = mcp(freshEngine(),
			Maps.of(K_INCLUDE_ADAPTERS, Vectors.of(Strings.create("*"))));
		Set<String> names = toolNames(mcp);
		// Now everything under v/ops/ shows up
		assertTrue(names.contains("http_get"),    "wildcard should expose http_*");
		assertTrue(names.contains("json_merge"),  "wildcard should expose json_*");
		assertTrue(names.contains("ucan_issue"),  "wildcard should expose ucan_*");
		assertTrue(names.contains("agent_create"), "wildcard still includes default groups");
		// But test ops are at v/test/ops/, outside the default path prefix
		assertFalse(names.stream().anyMatch(n -> n.startsWith("test_")),
			"wildcard alone shouldn't reach v/test/ops/");
	}

	// ==================== Explicit include list ====================

	@Test public void testExplicitListReplacesDefault() {
		MCP mcp = mcp(freshEngine(),
			Maps.of(K_INCLUDE_ADAPTERS,
				Vectors.of(Strings.create("http"), Strings.create("json"))));
		Set<String> groups = groupsOf(toolNames(mcp));
		assertEquals(Set.of("http", "json"), groups,
			"Explicit list should replace the default allowlist verbatim");
	}

	@Test public void testEmptyExplicitListFallsBackToDefault() {
		// Empty vector should be treated as "not configured" → default allowlist
		MCP mcp = mcp(freshEngine(),
			Maps.of(K_INCLUDE_ADAPTERS, Vectors.empty()));
		Set<String> groups = groupsOf(toolNames(mcp));
		assertEquals(Set.of("agent", "asset", "covia", "grid", "secret"), groups);
	}

	// ==================== Path-prefix filter ====================

	@Test public void testTestPathPrefixHiddenByDefault() {
		MCP mcp = mcp(freshEngine(),
			Maps.of(K_INCLUDE_ADAPTERS, Vectors.of(Strings.create("*"))));
		Set<String> names = toolNames(mcp);
		assertFalse(names.contains("test_echo"),
			"v/test/ops/ should not be exposed without explicit prefix opt-in");
	}

	@Test public void testTestPathPrefixIncludedWhenConfigured() {
		MCP mcp = mcp(freshEngine(), Maps.of(
			K_INCLUDE_PATH_PREFIXES,
				Vectors.of(Strings.create("v/ops/"), Strings.create("v/test/ops/")),
			K_INCLUDE_ADAPTERS,
				Vectors.of(Strings.create("*"))));
		Set<String> names = toolNames(mcp);
		assertTrue(names.contains("test_echo"),
			"Adding v/test/ops/ to includePathPrefixes should expose test_echo");
	}

	// ==================== Tool call routing ====================

	@Test public void testToolCallRoutesByPath() {
		MCP mcp = mcp(freshEngine(),
			Maps.of(K_INCLUDE_ADAPTERS, Vectors.of(Strings.create("*"))));
		AMap<AString, ACell> params = Maps.of(
			Fields.NAME, Strings.create("json_merge"),
			Fields.ARGUMENTS, Maps.of(
				Strings.create("values"),
				Vectors.of(
					Maps.of(Strings.create("a"), Strings.create("1")),
					Maps.of(Strings.create("b"), Strings.create("2"))
				)));
		AMap<AString, ACell> response = mcp.toolCall(params);
		assertNotNull(response);
		// Successful tool call returns no top-level "error" key
		assertNull(response.get(Strings.create("error")),
			"Successful routed call shouldn't return a JSON-RPC error: " + response);
	}

	@Test public void testToolCallUnknownToolReturnsError() {
		MCP mcp = mcp(freshEngine(), Maps.empty());
		AMap<AString, ACell> params = Maps.of(
			Fields.NAME, Strings.create("does_not_exist"),
			Fields.ARGUMENTS, Maps.empty());
		AMap<AString, ACell> response = mcp.toolCall(params);
		ACell err = response.get(Strings.create("error"));
		assertNotNull(err, "Unknown tool should return a JSON-RPC error block: " + response);
		AMap<?,?> errMap = (AMap<?,?>) err;
		Object code = RT.jvm(errMap.get(Strings.create("code")));
		assertEquals(-32602L, code, "Unknown tool should use JSON-RPC -32602 (invalid params)");
	}

	@Test public void testToolCallExcludedToolNotReachable() {
		// Default config — http isn't in the registry, so http_get must be unreachable
		// even though the underlying op exists at v/ops/http/get.
		MCP mcp = mcp(freshEngine(), Maps.empty());
		AMap<AString, ACell> params = Maps.of(
			Fields.NAME, Strings.create("http_get"),
			Fields.ARGUMENTS, Maps.empty());
		AMap<AString, ACell> response = mcp.toolCall(params);
		assertNotNull(response.get(Strings.create("error")),
			"Excluded tool must not be reachable via MCP wire");
	}

	// ==================== Lazy initialisation ====================

	@Test public void testRegistryStableAcrossCalls() {
		MCP mcp = mcp(freshEngine(), Maps.empty());
		Set<String> first = toolNames(mcp);
		Set<String> second = toolNames(mcp);
		assertEquals(first, second, "Registry must be stable across listTools calls");
	}
}
