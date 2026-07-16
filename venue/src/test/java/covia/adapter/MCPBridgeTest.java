package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

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
		// A caller under the public read-only ceiling must be denied venue scope
		RequestContext capped = RequestContext.of(ALICE_DID)
			.withCaps(CapabilityChecker.readOnlyCeiling(ALICE_DID));
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

	private static boolean vectorContains(AVector<ACell> v, String s) {
		for (long i = 0; i < v.count(); i++) {
			if (s.equals(v.get(i).toString())) return true;
		}
		return false;
	}
}
