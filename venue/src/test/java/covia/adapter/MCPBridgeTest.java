package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.CapabilityChecker;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.TestServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * MCP server bridging (#80), exercised against the venue's OWN MCP endpoint —
 * self-federation: the shared TestServer venue exposes its ops as MCP tools,
 * we bridge it as an "external" server, its tools materialise as catalog
 * operations, and an invocation round-trips through the MCP transport back
 * into the same venue.
 */
public class MCPBridgeTest {

	private final Engine engine = TestServer.ENGINE;
	private AString ALICE_DID;

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
	}

	private Job addServer(String name, String url, String scope, RequestContext ctx) {
		ACell input = (scope == null)
			? Maps.of(Fields.NAME, name, "url", url)
			: Maps.of(Fields.NAME, name, "url", url, "scope", scope);
		return engine.jobs().invokeOperation("v/ops/mcp/add-server", input, ctx);
	}

	@Test
	public void testUserScopeBridgeAndInvoke() {
		RequestContext ctx = RequestContext.of(ALICE_DID);
		Job add = addServer("selfa", TestServer.BASE_URL, null, ctx);
		ACell result = add.awaitResult(15000);
		assertEquals(Status.COMPLETE, add.getStatus());
		assertEquals("user", RT.getIn(result, "scope").toString());
		AVector<ACell> tools = RT.ensureVector(RT.getIn(result, "tools"));
		assertNotNull(tools);
		assertTrue(tools.count() > 0, "the venue's own MCP endpoint must expose tools");
		assertTrue(vectorContains(tools, "test_echo"),
			"bridged tool list should include test_echo: " + tools);

		// The bridged op is an ordinary catalog asset in the caller's o/ namespace
		assertNotNull(engine.resolveAsset(Strings.create("o/mcp/selfa/test_echo"), ctx),
			"bridged tool must resolve as an operation");

		// Invoke it — the whole input is the tool arguments (bridged form),
		// and the call round-trips through MCP into the same venue's echo op.
		Job call = engine.jobs().invokeOperation("o/mcp/selfa/test_echo",
			Maps.of("value", "bridged-hello"), ctx);
		ACell out = call.awaitResult(15000);
		assertEquals(Status.COMPLETE, call.getStatus(),
			"bridged invocation must succeed: " + call.getErrorMessage());
		assertNotNull(out);
	}

	@Test
	public void testBridgedOpMetadataShape() {
		RequestContext ctx = RequestContext.of(ALICE_DID);
		addServer("selfb", TestServer.BASE_URL, null, ctx).awaitResult(15000);

		covia.grid.Asset asset = engine.resolveAsset(Strings.create("o/mcp/selfb/test_echo"), ctx);
		assertNotNull(asset);
		ACell meta = asset.meta();
		// Self-contained dispatch pins: adapter, remoteToolName, server
		assertEquals("mcp:tools:call", RT.getIn(meta, Fields.OPERATION, Fields.ADAPTER).toString());
		assertEquals("test_echo", RT.getIn(meta, Fields.OPERATION, Fields.REMOTE_TOOL_NAME).toString());
		assertEquals(TestServer.BASE_URL, RT.getIn(meta, Fields.OPERATION, Fields.SERVER).toString());
		// The op's declared input is the TOOL's own schema
		assertNotNull(RT.getIn(meta, Fields.OPERATION, Fields.INPUT),
			"bridged op must declare the tool's input schema");
		// Provenance in the description
		assertTrue(RT.getIn(meta, Fields.DESCRIPTION).toString().contains("Bridged from MCP server 'selfb'"));
	}

	@Test
	public void testVenueScopeRequiresManageAbility() {
		// A caller under the public read-only scope must be denied venue scope
		RequestContext capped = RequestContext.of(ALICE_DID)
			.withCaps(CapabilityChecker.readOnlyScope(ALICE_DID));
		Job denied = addServer("selfc", TestServer.BASE_URL, "venue", capped);
		assertThrows(Exception.class, () -> denied.awaitResult(15000));
		assertEquals(Status.FAILED, denied.getStatus());
		assertTrue(denied.getErrorMessage().contains("Capability denied"),
			denied.getErrorMessage());

		// The venue's own context may bridge at venue scope; the ops are then
		// visible in the shared catalog for everyone.
		Job add = addServer("selfd", TestServer.BASE_URL, "venue", engine.venueContext());
		add.awaitResult(15000);
		assertEquals(Status.COMPLETE, add.getStatus(), String.valueOf(add.getErrorMessage()));
		try {
			assertNotNull(engine.resolveAsset(Strings.create("v/ops/mcp/selfd/test_echo"), RequestContext.of(ALICE_DID)),
				"venue-scope bridged ops are in the shared catalog");
		} finally {
			// The shared TestServer catalog doubles as the venue's own MCP
			// tool list — leaving bridged entries behind changes what every
			// concurrently-running MCP test observes. Clean up.
			engine.jobs().invokeOperation("v/ops/mcp/remove-server",
				Maps.of(Fields.NAME, "selfd", "scope", "venue"),
				engine.venueContext()).awaitResult(15000);
		}
		assertNull(engine.resolveAsset(Strings.create("v/ops/mcp/selfd/test_echo"), RequestContext.of(ALICE_DID)),
			"venue-scope removal must clear the shared catalog");
	}

	@Test
	public void testRemoveServerDeletesCatalog() {
		RequestContext ctx = RequestContext.of(ALICE_DID);
		addServer("selfe", TestServer.BASE_URL, null, ctx).awaitResult(15000);
		assertNotNull(engine.resolveAsset(Strings.create("o/mcp/selfe/test_echo"), ctx));

		Job remove = engine.jobs().invokeOperation("v/ops/mcp/remove-server",
			Maps.of(Fields.NAME, "selfe"), ctx);
		remove.awaitResult(15000);
		assertEquals(Status.COMPLETE, remove.getStatus());

		assertNull(engine.resolveAsset(Strings.create("o/mcp/selfe/test_echo"), ctx),
			"removed server's ops must leave the catalog");
	}

	@Test
	public void testRefreshIsIdempotent() {
		RequestContext ctx = RequestContext.of(ALICE_DID);
		ACell added = addServer("selff", TestServer.BASE_URL, null, ctx).awaitResult(15000);
		long count = RT.ensureLong(RT.getIn(added, Fields.TOTAL)).longValue();

		Job refresh = engine.jobs().invokeOperation("v/ops/mcp/refresh",
			Maps.of(Fields.NAME, "selff"), ctx);
		ACell refreshed = refresh.awaitResult(15000);
		assertEquals(Status.COMPLETE, refresh.getStatus());
		assertEquals(count, RT.ensureLong(RT.getIn(refreshed, Fields.TOTAL)).longValue(),
			"refresh against an unchanged server must be a no-op");
		assertNotNull(engine.resolveAsset(Strings.create("o/mcp/selff/test_echo"), ctx));
	}

	@Test
	public void testSsrfBlockedUrl() {
		// Private/internal addresses are rejected exactly as the http adapter
		// rejects them — binding a server reaches nothing a direct call couldn't.
		RequestContext ctx = RequestContext.of(ALICE_DID);
		Job denied = addServer("selfg", "http://10.99.99.99:1234", null, ctx);
		assertThrows(Exception.class, () -> denied.awaitResult(15000));
		assertEquals(Status.FAILED, denied.getStatus());
		assertTrue(denied.getErrorMessage().contains("private/internal"),
			denied.getErrorMessage());
	}

	@Test
	public void testRawAuthTokenWarns() {
		// Pure check: secret references (bare or DID-qualified) are clean,
		// literal credentials warn with the catalog-path remedy. (A live add
		// with a bogus token can't reach the warning — the server rightly
		// rejects the connection first.)
		assertNull(MCPAdapter.rawAuthWarning(null));
		assertNull(MCPAdapter.rawAuthWarning(Strings.create("s/GITHUB_TOKEN")));
		assertNull(MCPAdapter.rawAuthWarning(Strings.create("/s/GITHUB_TOKEN")));
		assertNull(MCPAdapter.rawAuthWarning(Strings.create("did:key:z6MkX/s/GITHUB_TOKEN")));
		AString warn = MCPAdapter.rawAuthWarning(Strings.create("raw-token-value"));
		assertNotNull(warn, "a raw auth credential must carry a warning");
		assertTrue(warn.toString().contains("v/ops/secret/set"));
	}

	@Test
	public void testToolsListInitializationFailureIsActionable() throws Exception {
		HttpServer rejecting = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicReference<String> requestedPath = new AtomicReference<>();
		rejecting.createContext("/mcp", exchange -> {
			requestedPath.set(exchange.getRequestURI().getPath());
			exchange.sendResponseHeaders(401, -1);
			exchange.close();
		});
		rejecting.start();
		try {
			String server = "http://127.0.0.1:" + rejecting.getAddress().getPort() + "/mcp/";
			Job job = engine.jobs().invokeOperation("v/ops/mcp/tools-list",
				Maps.of(Fields.SERVER, server), RequestContext.of(ALICE_DID));
			assertThrows(Exception.class, () -> job.awaitResult(15000));
			assertEquals(Status.FAILED, job.getStatus());

			String error = job.getErrorMessage();
			assertTrue(error.contains("Cannot initialize MCP client for server " + server), error);
			assertTrue(error.contains("Authorization error"), error);
			assertTrue(error.contains("URL names the MCP endpoint"), error);
			assertTrue(error.contains("credentials") && error.contains("protocol version"), error);
			assertEquals("/mcp", requestedPath.get(),
				"a trailing slash on an explicit MCP endpoint must not append another /mcp");
		} finally {
			rejecting.stop(0);
		}
	}

	// ========== Tool-level curation (#80 — the tool is the entity) ==========

	private Job addTool(String path, String tool, RequestContext ctx) {
		return engine.jobs().invokeOperation("v/ops/mcp/add-tool",
			Maps.of(Fields.SERVER, TestServer.BASE_URL, Fields.TOOL, tool, Fields.PATH, path), ctx);
	}

	@Test
	public void testAddToolCuratedPath() {
		RequestContext ctx = RequestContext.of(ALICE_DID);
		// Curate one tool at a caller-chosen path, with a display-name override
		Job add = engine.jobs().invokeOperation("v/ops/mcp/add-tool",
			Maps.of(Fields.SERVER, TestServer.BASE_URL,
				Fields.TOOL, "test_echo",
				Fields.PATH, "o/research/echo",
				Fields.NAME, "Echo Probe"), ctx);
		ACell result = add.awaitResult(15000);
		assertEquals(Status.COMPLETE, add.getStatus(), String.valueOf(add.getErrorMessage()));
		assertEquals("o/research/echo", RT.getIn(result, Fields.PATH).toString());
		assertEquals("test_echo", RT.getIn(result, Fields.TOOL).toString());

		covia.grid.Asset asset = engine.resolveAsset(Strings.create("o/research/echo"), ctx);
		assertNotNull(asset, "curated tool must resolve at its chosen path");
		ACell meta = asset.meta();
		assertEquals("Echo Probe", RT.getIn(meta, Fields.NAME).toString());
		assertTrue(RT.getIn(meta, Fields.DESCRIPTION).toString().contains("Bridged from MCP server at "),
			"provenance names the server URL");
		// No registry entry — the asset is self-contained
		assertEquals("test_echo", RT.getIn(meta, Fields.OPERATION, Fields.REMOTE_TOOL_NAME).toString());

		// And it works: groups are just catalog paths
		Job call = engine.jobs().invokeOperation("o/research/echo",
			Maps.of("value", "curated-hello"), ctx);
		call.awaitResult(15000);
		assertEquals(Status.COMPLETE, call.getStatus(), String.valueOf(call.getErrorMessage()));
	}

	@Test
	public void testAddToolUnknownToolListsAvailable() {
		RequestContext ctx = RequestContext.of(ALICE_DID);
		Job miss = addTool("o/grpx/none", "no_such_tool_zz", ctx);
		assertThrows(Exception.class, () -> miss.awaitResult(15000));
		assertEquals(Status.FAILED, miss.getStatus());
		String err = miss.getErrorMessage();
		assertTrue(err.contains("not found"), err);
		// The error lists available tool names so a model can self-correct
		// (capped — the shared venue exposes >40 tools — with a pointer to
		// the full listing).
		assertTrue(err.contains("Available tools:"), err);
		assertTrue(err.contains("tools-list"), err);
	}

	@Test
	public void testAddToolPathValidation() {
		RequestContext ctx = RequestContext.of(ALICE_DID);
		Job bad = addTool("w/nope/echo", "test_echo", ctx);
		assertThrows(Exception.class, () -> bad.awaitResult(15000));
		assertEquals(Status.FAILED, bad.getStatus());
		assertTrue(bad.getErrorMessage().contains("path must be under o/"), bad.getErrorMessage());
	}

	@Test
	public void testAddToolVenuePathRequiresManage() {
		RequestContext capped = RequestContext.of(ALICE_DID)
			.withCaps(CapabilityChecker.readOnlyScope(ALICE_DID));
		Job denied = addTool("v/ops/mcptest/echo", "test_echo", capped);
		assertThrows(Exception.class, () -> denied.awaitResult(15000));
		assertEquals(Status.FAILED, denied.getStatus());
		assertTrue(denied.getErrorMessage().contains("Capability denied"), denied.getErrorMessage());

		Job add = addTool("v/ops/mcptest/echo", "test_echo", engine.venueContext());
		add.awaitResult(15000);
		assertEquals(Status.COMPLETE, add.getStatus(), String.valueOf(add.getErrorMessage()));
		try {
			assertNotNull(engine.resolveAsset(Strings.create("v/ops/mcptest/echo"), RequestContext.of(ALICE_DID)),
				"venue-path curated tools are in the shared catalog");
		} finally {
			// Shared TestServer catalog doubles as the venue's own MCP tool
			// list — always remove venue-scope test entries.
			engine.jobs().invokeOperation("v/ops/covia/delete",
				Maps.of(Fields.PATH, Strings.create("v/ops/mcptest")),
				engine.venueContext()).awaitResult(15000);
		}
		assertNull(engine.resolveAsset(Strings.create("v/ops/mcptest/echo"), RequestContext.of(ALICE_DID)));
	}

	@Test
	public void testRefreshPathCurated() {
		RequestContext ctx = RequestContext.of(ALICE_DID);
		addTool("o/grp/echo", "test_echo", ctx).awaitResult(15000);
		// A hand-authored bridged op whose remote tool doesn't exist — the
		// "server dropped it" case for a curated refresh.
		ACell ghostMeta = Maps.of(
			Fields.NAME, Strings.create("Ghost"),
			Fields.DESCRIPTION, Strings.create("vanished tool"),
			Fields.OPERATION, Maps.of(
				Fields.ADAPTER, Strings.create("mcp:tools:call"),
				Fields.REMOTE_TOOL_NAME, Strings.create("no_such_tool_zz"),
				Fields.SERVER, Strings.create(TestServer.BASE_URL),
				Fields.INPUT, Maps.of(Fields.TYPE, Strings.create("object"))));
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, Strings.create("o/grp/ghost"), Fields.VALUE, ghostMeta), ctx)
			.awaitResult(15000);

		Job refresh = engine.jobs().invokeOperation("v/ops/mcp/refresh",
			Maps.of(Fields.PATH, Strings.create("o/grp")), ctx);
		ACell out = refresh.awaitResult(15000);
		assertEquals(Status.COMPLETE, refresh.getStatus(), String.valueOf(refresh.getErrorMessage()));
		assertEquals(2, RT.ensureLong(RT.getIn(out, Fields.TOTAL)).longValue());
		assertEquals(0, RT.ensureLong(RT.getIn(out, "updated")).longValue(),
			"schemas are unchanged against the same server");
		assertEquals(1, RT.ensureLong(RT.getIn(out, "unchanged")).longValue());

		AVector<ACell> missing = RT.ensureVector(RT.getIn(out, "missing"));
		assertNotNull(missing);
		assertEquals(1, missing.count());
		assertEquals("no_such_tool_zz", RT.getIn(missing.get(0), Fields.TOOL).toString());
		assertNull(RT.getIn(out, "errors"), "the server itself was reachable");

		// Curated semantics: the vanished tool is REPORTED, never deleted
		assertNotNull(engine.resolveAsset(Strings.create("o/grp/ghost"), ctx),
			"curated refresh must not delete hand-picked tools");
		// And the live one still works
		Job call = engine.jobs().invokeOperation("o/grp/echo", Maps.of("value", "post-refresh"), ctx);
		call.awaitResult(15000);
		assertEquals(Status.COMPLETE, call.getStatus(), String.valueOf(call.getErrorMessage()));
	}

	@Test
	public void testAddToolWithDefaults() {
		RequestContext ctx = RequestContext.of(ALICE_DID);
		// test_random REQUIRES length — the default fills it, purpose-shaping
		// the generic tool into a no-argument one.
		Job add = engine.jobs().invokeOperation("v/ops/mcp/add-tool",
			Maps.of(Fields.SERVER, TestServer.BASE_URL,
				Fields.TOOL, "test_random",
				Fields.PATH, "o/shaped/random8",
				Fields.DEFAULT, Maps.of("length", "8")), ctx);
		add.awaitResult(15000);
		assertEquals(Status.COMPLETE, add.getStatus(), String.valueOf(add.getErrorMessage()));

		// The stored op carries the defaults; the defaulted key left required
		ACell meta = engine.resolveAsset(Strings.create("o/shaped/random8"), ctx).meta();
		assertEquals("8", RT.getIn(meta, Fields.OPERATION, Fields.DEFAULT, "length").toString());
		assertNull(RT.getIn(meta, Fields.OPERATION, Fields.INPUT, "required"),
			"the only required key was defaulted away");

		// A REQUIRED argument filled by default, through the full MCP round trip
		Job call = engine.jobs().invokeOperation("o/shaped/random8", Maps.empty(), ctx);
		ACell out = call.awaitResult(15000);
		assertEquals(Status.COMPLETE, call.getStatus(), String.valueOf(call.getErrorMessage()));
		assertEquals(16, RT.getIn(out, "bytes").toString().length(), "8 bytes hex-encoded");

		// Caller override wins — purpose-shaping, not policy
		Job call4 = engine.jobs().invokeOperation("o/shaped/random8",
			Maps.of("length", "4"), ctx);
		ACell out4 = call4.awaitResult(15000);
		assertEquals(Status.COMPLETE, call4.getStatus(), String.valueOf(call4.getErrorMessage()));
		assertEquals(8, RT.getIn(out4, "bytes").toString().length(), "4 bytes hex-encoded");

		// Curated refresh preserves defaults and re-subtracts required from
		// the freshly fetched schema — the op must be refresh-stable.
		Job refresh = engine.jobs().invokeOperation("v/ops/mcp/refresh",
			Maps.of(Fields.PATH, Strings.create("o/shaped")), ctx);
		ACell rout = refresh.awaitResult(15000);
		assertEquals(Status.COMPLETE, refresh.getStatus(), String.valueOf(refresh.getErrorMessage()));
		assertEquals(1, RT.ensureLong(RT.getIn(rout, "unchanged")).longValue());
		assertEquals(meta, engine.resolveAsset(Strings.create("o/shaped/random8"), ctx).meta(),
			"refresh against an unchanged server must not perturb a defaults-shaped op");
	}

	@Test
	public void testRefreshRejectsNameAndPathTogether() {
		RequestContext ctx = RequestContext.of(ALICE_DID);
		Job both = engine.jobs().invokeOperation("v/ops/mcp/refresh",
			Maps.of(Fields.NAME, Strings.create("x"), Fields.PATH, Strings.create("o/grp")), ctx);
		assertThrows(Exception.class, () -> both.awaitResult(15000));
		assertTrue(both.getErrorMessage().contains("not both"), both.getErrorMessage());
	}

	// ========== Error comprehensibility (LLM-facing failures) ==========

	@Test
	public void testBridgedToolErrorSurfaces() {
		// A remote tool-level error (isError: true per the MCP spec) must FAIL
		// the bridged job with the remote error text — never complete with an
		// error-shaped payload the agent has to guess about.
		RequestContext ctx = RequestContext.of(ALICE_DID);
		addTool("o/errgrp/fail", "test_error", ctx).awaitResult(15000);

		Job call = engine.jobs().invokeOperation("o/errgrp/fail",
			Maps.of("message", "boom-mcp-xyz"), ctx);
		assertThrows(Exception.class, () -> call.awaitResult(15000));
		assertEquals(Status.FAILED, call.getStatus());
		String err = call.getErrorMessage();
		assertTrue(err.contains("boom-mcp-xyz"), "remote error text must survive: " + err);
		assertTrue(err.contains("test_error"), "the failing tool is named: " + err);
	}

	@Test
	public void testResultExtraction() {
		// structuredContent wins when present
		CallToolResult r1 = new CallToolResult(List.of(TextContent.builder("ignored").build()),
			null, java.util.Map.of("a", 1L), null);
		ACell v1 = MCPAdapter.successValue(r1);
		assertEquals(RT.cvm(1L), RT.getIn(v1, "a"));
		// text-only results are preserved, not dropped
		CallToolResult r2 = new CallToolResult(List.of(TextContent.builder("hello").build()), null, null, null);
		assertEquals("hello", MCPAdapter.successValue(r2).toString());
		// multi-block → vector of strings
		CallToolResult r3 = new CallToolResult(List.of(
			TextContent.builder("a").build(),
			TextContent.builder("b").build()),
			null, null, null);
		assertEquals(2, RT.ensureVector(MCPAdapter.successValue(r3)).count());
		// nothing at all → null
		CallToolResult r4 = new CallToolResult(List.of(), false, null, null);
		assertNull(MCPAdapter.successValue(r4));
	}

	@Test
	public void testErrorTextExtraction() {
		// not an error (isError null or false) → null
		assertNull(MCPAdapter.errorText(new CallToolResult(
			List.of(TextContent.builder("x").build()), null, null, null)));
		assertNull(MCPAdapter.errorText(new CallToolResult(
			List.of(TextContent.builder("x").build()), false, null, null)));
		// structuredContent.message preferred
		assertEquals("nope", MCPAdapter.errorText(new CallToolResult(
			List.of(TextContent.builder("fallback").build()),
			true, java.util.Map.of("message", "nope"), null)));
		// text content fallback
		assertEquals("boom", MCPAdapter.errorText(new CallToolResult(
			List.of(TextContent.builder("boom").build()), true, null, null)));
		// a bare error still says something useful
		assertTrue(MCPAdapter.errorText(new CallToolResult(List.of(), true, null, null))
			.contains("no error detail"));
	}

	@Test
	public void testRootCauseMessage() {
		Exception inner = new java.io.IOException("Connection refused");
		Exception wrapped = new java.util.concurrent.CompletionException(new RuntimeException(inner));
		assertEquals("Connection refused", MCPAdapter.rootCauseMessage(wrapped));
		// message-less chain falls back to the class name
		assertEquals("IllegalStateException", MCPAdapter.rootCauseMessage(new IllegalStateException()));
		// a plain message passes through
		assertEquals("plain", MCPAdapter.rootCauseMessage(new RuntimeException("plain")));
	}

	private static boolean vectorContains(AVector<ACell> v, String s) {
		for (long i = 0; i < v.count(); i++) {
			if (s.equals(v.get(i).toString())) return true;
		}
		return false;
	}
}
