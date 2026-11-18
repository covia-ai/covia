package covia.venue.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.MCPAdapter;
import covia.adapter.callable.CallableAdapter;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.Caps;
import covia.venue.Engine;
import covia.venue.JobManager;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.TestHTTP;
import covia.venue.TestServer;

/**
 * A callable offered as an MCP tool (CALLABLES.md, ACCESS_TOKENS.md): Alice
 * publishes a guestbook callable whose gate lists Bob; Bob offers it on an
 * MCP token of his own, and a client holding his bearer calls it over the
 * venue's MCP endpoint. The call writes only what the callable's caps allow
 * in Alice's namespace, and only callers its gate admits, within their
 * tokens' caps, reach it. A token offers it through {@code callable:call}, or
 * by its path, as a tool taking the target's input.
 */
public class CallableMcpTest {

	private static final String MCP_URL = TestServer.BASE_URL + "/mcp";
	private final Engine engine = TestServer.ENGINE;
	private final HttpClient http = TestHTTP.CLIENT;

	private Job run(String op, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		TestEngine.awaitJob(job, Job::isFinished, 10000, () -> op + " never finished");
		assertEquals(Status.COMPLETE, job.getStatus(), job::getErrorMessage);
		return job;
	}

	private static ACell cap(String with, AString can) {
		return Capability.create(Strings.create(with), can);
	}

	/** An MCP token of {@code owner}'s offering {@code tools} within {@code caps}, as its bearer. */
	private String token(AString owner, String name, AVector<ACell> tools, ACell caps) {
		return RT.getIn(run("v/ops/mcp/create-token", Maps.of(Fields.NAME, name, Fields.TOOLS, tools, Fields.CAPS, caps),
			RequestContext.of(owner)).getOutput(), Fields.TOKEN).toString();
	}

	/** Why {@code owner} may not make an MCP token offering {@code tool}. */
	private String tokenRefused(AString owner, String tool) {
		Job job = engine.jobs().invokeOperation("v/ops/mcp/create-token", Maps.of(Fields.NAME, "refused",
			Fields.TOOLS, Vectors.of(tool), Fields.CAPS, Caps.UNRESTRICTED), RequestContext.of(owner));
		TestEngine.awaitJob(job, Job::isFinished, 10000, () -> "mcp:create-token never finished");
		assertEquals(Status.FAILED, job.getStatus(), () -> "expected a refusal, got " + job.getData());
		return job.getErrorMessage();
	}

	/** The tool {@code name} calling callables, as a token offers it. */
	private static AMap<AString, ACell> callTool(String name) {
		return Maps.of(Fields.OPERATION, "v/ops/callable/call", Fields.NAME, name);
	}

	/** The result of MCP tool call {@code tool} with {@code arguments}, made with {@code bearer}. */
	private AMap<AString, ACell> callTool(String bearer, String tool, ACell arguments) throws Exception {
		return callTool(bearer, tool, JSON.toString(arguments));
	}

	/** As above, with the arguments as the client writes them in JSON. */
	private AMap<AString, ACell> callTool(String bearer, String tool, String arguments) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
			+ "\",\"arguments\":" + arguments + "}}";
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(MCP_URL))
			.header("Content-Type", "application/json").header("Accept", "application/json")
			.header("Authorization", "Bearer " + bearer).timeout(Duration.ofSeconds(20))
			.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode(), response.body());
		AMap<AString, ACell> result = RT.ensureMap(RT.getIn(JSON.parse(response.body()), "result"));
		assertNotNull(result, response.body());
		return result;
	}

	/** The tools {@code bearer} is offered over MCP, by name. */
	private AMap<AString, ACell> listTools(String bearer) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(MCP_URL))
			.header("Content-Type", "application/json").header("Accept", "application/json")
			.header("Authorization", "Bearer " + bearer).timeout(Duration.ofSeconds(20))
			.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode(), response.body());
		AMap<AString, ACell> byName = Maps.empty();
		for (ACell tool : RT.ensureVector(RT.getIn(JSON.parse(response.body()), "result", "tools"))) {
			byName = byName.assoc(RT.ensureString(RT.getIn(tool, Fields.NAME)), tool);
		}
		return byName;
	}

	private static void succeeded(AMap<AString, ACell> result) {
		assertNotEquals(CVMBool.TRUE, result.get(Strings.create("isError")), result::toString);
	}

	/** Why the call was refused, as the client is told. */
	private static String refused(AMap<AString, ACell> result) {
		assertEquals(CVMBool.TRUE, result.get(Strings.create("isError")), result::toString);
		return result.toString();
	}

	/** Bob signs Alice's guestbook through callable {@code callable}. */
	private static AMap<AString, ACell> signing(String callable, String path, String message) {
		return Maps.of(Fields.CALLABLE, callable, Fields.INPUT, Maps.of(Fields.PATH, path, Fields.VALUE, message));
	}

	private ACell held(AString owner, String path) {
		return RT.getIn(run("v/ops/covia/read", Maps.of(Fields.PATH, path), RequestContext.of(owner)).getOutput(), Fields.VALUE);
	}

	@Test
	public void anotherUserCallsACallableThroughTheirMcpToken(TestInfo info) throws Exception {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		AString carol = TestEngine.uniqueDID("carol-" + info.getDisplayName());
		RequestContext owner = RequestContext.of(alice);

		// Alice's guestbook, a gate stored by content hash that lists Bob, and the callable
		run("v/ops/covia/write", Maps.of(Fields.PATH, "w/guestbook", Fields.VALUE, Vectors.empty()), owner);
		AMap<AString, ACell> gateOperation = Maps.of(Fields.NAME, "Guestbook gate", Fields.OPERATION, Maps.of(
			Fields.ADAPTER, "callable:gates/callers", Fields.DEFAULT, Maps.of(Fields.CALLERS, Vectors.of(bob))));
		String stored = RT.getIn(run("v/ops/asset/store", Maps.of(Fields.METADATA, gateOperation), owner).getOutput(), Fields.REF).toString();
		String gate = stored.substring(stored.indexOf("/a/") + 1);
		run("v/ops/callable/create", Maps.of(Fields.NAME, "guestbook", Fields.OPERATION, "v/ops/covia/append",
			Fields.CAPS, Vectors.of(cap("w/guestbook", Capability.CRUD_WRITE)), Fields.GATE, gate), owner);
		String guestbook = alice + "/x/callable/guestbook";

		// Bob's token offers the callable as a tool, and a tool of his own, within a cap to call the guestbook
		AVector<ACell> tools = Vectors.of(callTool("guestbook"), "v/ops/covia/append");
		String bobs = token(bob, "guestbook", tools, Vectors.of(cap(guestbook, Abilities.CALLABLE_CALL)));

		// It writes Alice's guestbook through the callable, never directly
		succeeded(callTool(bobs, "guestbook", signing(guestbook, "w/guestbook", "Hello from Bob")));
		refused(callTool(bobs, "covia_append", Maps.of(Fields.PATH, alice + "/w/guestbook", Fields.VALUE, "direct")));
		assertEquals(Vectors.of("Hello from Bob"), held(alice, "w/guestbook"));

		// The callable's caps bound what the call writes, in Alice's namespace
		refused(callTool(bobs, "guestbook", signing(guestbook, "w/private", "x")));
		assertNull(held(alice, "w/private"));

		// The gate refuses a caller it does not list, and a token reaches the gate only within its caps
		String carols = token(carol, "guestbook", Vectors.of(callTool("guestbook")), Caps.UNRESTRICTED);
		assertTrue(refused(callTool(carols, "guestbook", signing(guestbook, "w/guestbook", "Hello from Carol")))
			.contains(CallableAdapter.NOT_A_CALLER.formatted(carol)));
		String bobsOther = token(bob, "workspace", Vectors.of(callTool("guestbook")), Vectors.of(cap("w/", Capability.CRUD)));
		assertTrue(refused(callTool(bobsOther, "guestbook", signing(guestbook, "w/guestbook", "x")))
			.contains(CallableAdapter.NOT_CALLABLE.formatted(guestbook)));

		// A callable without a gate is reported as a missing one
		run("v/ops/callable/create", Maps.of(Fields.NAME, "private", Fields.OPERATION, "v/ops/covia/append",
			Fields.CAPS, Vectors.of(cap("w/guestbook", Capability.CRUD_WRITE))), owner);
		String hidden = alice + "/x/callable/private";
		String bobsAny = token(bob, "any", Vectors.of(callTool("guestbook")), Caps.UNRESTRICTED);
		assertTrue(refused(callTool(bobsAny, "guestbook", signing(hidden, "w/guestbook", "x")))
			.contains(CallableAdapter.NOT_CALLABLE.formatted(hidden)));
		assertEquals(Vectors.of("Hello from Bob"), held(alice, "w/guestbook"));

		// Narrowing the callable stops the next call; deleting it stops every call
		run("v/ops/callable/update", Maps.of(Fields.NAME, "guestbook", Fields.CAPS, Vectors.empty()), owner);
		refused(callTool(bobs, "guestbook", signing(guestbook, "w/guestbook", "narrowed")));
		run("v/ops/callable/update", Maps.of(Fields.NAME, "guestbook", Fields.CAPS, Vectors.of(cap("w/guestbook", Capability.CRUD_WRITE))), owner);
		succeeded(callTool(bobs, "guestbook", signing(guestbook, "w/guestbook", "Hello again")));
		run("v/ops/callable/delete", Maps.of(Fields.NAME, "guestbook"), owner);
		refused(callTool(bobs, "guestbook", signing(guestbook, "w/guestbook", "deleted")));
		assertEquals(Vectors.of("Hello from Bob", "Hello again"), held(alice, "w/guestbook"));
	}

	@Test
	public void aTokenOffersACallablesPathAsAToolTakingItsTargetsInput(TestInfo info) throws Exception {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		RequestContext owner = RequestContext.of(alice);
		run("v/ops/covia/write", Maps.of(Fields.PATH, "w/guestbook", Fields.VALUE, Vectors.empty()), owner);
		run("v/ops/callable/create", Maps.of(Fields.NAME, "guestbook", Fields.OPERATION, "v/ops/covia/append",
			Fields.CAPS, Vectors.of(cap("w/guestbook", Capability.CRUD_WRITE)), Fields.GATE, "v/ops/callable/gates/anyone"), owner);
		run("v/ops/callable/create", Maps.of(Fields.NAME, "private", Fields.OPERATION, "v/ops/covia/append",
			Fields.CAPS, Vectors.of(cap("w/guestbook", Capability.CRUD_WRITE))), owner);
		String guestbook = alice + "/x/callable/guestbook";
		String hidden = alice + "/x/callable/private";
		String missing = alice + "/x/callable/missing";
		AMap<AString, ACell> signing = Maps.of(Fields.PATH, "w/guestbook", Fields.VALUE, "Hello from Bob");

		// Bob's token lists the callable's path as a tool: named after the callable, with the target's schema
		AVector<ACell> tools = Vectors.of(guestbook, "v/ops/covia/append");
		String bobs = token(bob, "guestbook", tools, Vectors.of(cap(guestbook, Abilities.CALLABLE_CALL)));
		AMap<AString, ACell> offered = listTools(bobs);
		ACell tool = offered.get(Strings.create("guestbook"));
		assertNotNull(tool, offered::toString);
		AString schema = Strings.create("inputSchema");
		assertEquals(RT.getIn(offered.get(Strings.create("covia_append")), schema), RT.getIn(tool, schema));

		// A token may not list a callable Bob may not call, as it may not list a missing one
		assertEquals(MCPAdapter.TOOL_NOT_HELD.formatted(0, hidden), tokenRefused(bob, hidden));
		assertEquals(MCPAdapter.TOOL_NOT_HELD.formatted(0, missing), tokenRefused(bob, missing));

		// The client calls it with the target's input directly
		succeeded(callTool(bobs, "guestbook", signing));
		assertEquals(Vectors.of("Hello from Bob"), held(alice, "w/guestbook"));

		// A token whose caps do not cover calling it is refused as if there were no such operation
		String bobsOther = token(bob, "workspace", Vectors.of(guestbook), Vectors.of(cap("w/", Capability.CRUD)));
		assertTrue(refused(callTool(bobsOther, "guestbook", signing)).contains(JobManager.CANNOT_RESOLVE_OPERATION + guestbook));
		assertEquals(Vectors.of("Hello from Bob"), held(alice, "w/guestbook"));
	}

	@Test
	public void aGateRemovedWithJsonNullLeavesTheCallablePrivate(TestInfo info) throws Exception {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		RequestContext owner = RequestContext.of(alice);
		run("v/ops/covia/write", Maps.of(Fields.PATH, "w/guestbook", Fields.VALUE, Vectors.empty()), owner);
		run("v/ops/callable/create", Maps.of(Fields.NAME, "open", Fields.OPERATION, "v/ops/covia/append",
			Fields.CAPS, Vectors.of(cap("w/guestbook", Capability.CRUD_WRITE)), Fields.GATE, "v/ops/callable/gates/anyone"), owner);
		String open = alice + "/x/callable/open";
		String bobs = token(bob, "any", Vectors.of(callTool("guestbook")), Caps.UNRESTRICTED);
		succeeded(callTool(bobs, "guestbook", signing(open, "w/guestbook", "while open")));

		// Alice's client removes the gate, sending null as JSON
		String alices = token(alice, "admin", Vectors.of("v/ops/callable/update"), Caps.UNRESTRICTED);
		succeeded(callTool(alices, "callable_update", "{\"name\":\"open\",\"gate\":null}"));
		assertTrue(refused(callTool(bobs, "guestbook", signing(open, "w/guestbook", "once closed")))
			.contains(CallableAdapter.NOT_CALLABLE.formatted(open)));
		assertEquals(Vectors.of("while open"), held(alice, "w/guestbook"));
	}
}
