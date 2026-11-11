package covia.venue.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.TestHTTP;
import covia.venue.TestServer;

/**
 * The venue's MCP endpoint with an MCP token as its bearer (MCP_TOKENS.md):
 * the token's tools are all the request sees or calls, it presents no UCANs,
 * a bearer that does not stand is refused before anything runs, and each
 * token has sessions of its own.
 */
public class MCPTokenTest {

	private static final String MCP_URL = TestServer.BASE_URL + "/mcp";
	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":0}";
	private final Engine engine = TestServer.ENGINE;
	private final HttpClient http = TestHTTP.CLIENT;

	private Job run(String op, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		TestEngine.awaitJob(job, Job::isFinished, 10000, () -> op + " never finished");
		assertEquals(Status.COMPLETE, job.getStatus(), job::getErrorMessage);
		return job;
	}

	private String createToken(AString owner, String name, AVector<ACell> tools) {
		return RT.getIn(run("v/ops/mcp/create-token", Maps.of(Fields.NAME, name, Fields.TOOLS, tools,
			Fields.CAPS, Vectors.of(Capability.create(Strings.create("w/"), Capability.CRUD))),
			RequestContext.of(owner)).getOutput(), Fields.TOKEN).toString();
	}

	private HttpResponse<String> send(HttpRequest.Builder request, String bearer, String session) throws Exception {
		request.header("Authorization", "Bearer " + bearer).timeout(Duration.ofSeconds(20));
		if (session != null) request.header("Mcp-Session-Id", session);
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String body, String bearer, String session) throws Exception {
		return send(HttpRequest.newBuilder(URI.create(MCP_URL))
			.header("Content-Type", "application/json").header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body)), bearer, session);
	}

	/** The response's JSON-RPC body, once the request was accepted. */
	private ACell rpc(String body, String bearer) throws Exception {
		HttpResponse<String> response = post(body, bearer, null);
		assertEquals(200, response.statusCode(), response.body());
		return JSON.parse(response.body());
	}

	private static String call(int id, String tool, String arguments) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
			+ tool + "\",\"arguments\":" + arguments + "}}";
	}

	private Set<String> toolNames(String bearer) throws Exception {
		Set<String> names = new HashSet<>();
		ACell listed = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", bearer);
		for (ACell tool : RT.ensureVector(RT.getIn(listed, "result", "tools"))) {
			names.add(RT.getIn(tool, Fields.NAME).toString());
		}
		return names;
	}

	@Test
	public void aTokenSeesAndCallsOnlyItsTools(TestInfo info) throws Exception {
		AString alice = TestEngine.uniqueDID(info);
		run("v/ops/covia/write", Maps.of(Fields.PATH, "w/notes/a", Fields.VALUE, "hello"), RequestContext.of(alice));
		String bearer = createToken(alice, "reader", Vectors.of("v/ops/covia/read"));

		assertEquals(Set.of("covia_read"), toolNames(bearer));
		ACell read = rpc(call(2, "covia_read", "{\"path\":\"w/notes/a\"}"), bearer);
		assertEquals("hello", RT.getIn(read, "result", "structuredContent", "value").toString(), read.toString());

		// A tool it does not offer is answered as one that does not exist
		ACell notOffered = rpc(call(3, "covia_write", "{\"path\":\"w/notes/a\",\"value\":1}"), bearer);
		ACell noSuchTool = rpc(call(4, "no_such_tool", "{}"), bearer);
		assertNotNull(RT.getIn(notOffered, "error"), notOffered.toString());
		assertEquals(RT.getIn(noSuchTool, "error", "code"), RT.getIn(notOffered, "error", "code"));

		// The token's caps are all it acts with
		ACell withUcans = rpc(call(5, "covia_read", "{\"path\":\"w/notes/a\",\"ucans\":[\"x.y.z\"]}"), bearer);
		assertEquals(CVMBool.TRUE, RT.getIn(withUcans, "result", "isError"), withUcans.toString());
		assertTrue(withUcans.toString().contains(MCP.UCANS_WITH_TOKEN), withUcans.toString());
	}

	@Test
	public void aBearerThatDoesNotStandIsRefused(TestInfo info) throws Exception {
		AString alice = TestEngine.uniqueDID(info);
		String bearer = createToken(alice, "short-lived", Vectors.of("v/ops/covia/read"));
		assertEquals(200, post(INITIALIZE, bearer, null).statusCode());

		assertEquals(401, post(INITIALIZE, "cvmcp_" + "1".repeat(40), null).statusCode(), "a token never issued");
		run("v/ops/mcp/delete-token", Maps.of(Fields.NAME, "short-lived"), RequestContext.of(alice));
		assertEquals(401, post(INITIALIZE, bearer, null).statusCode(), "a deleted token");
	}

	@Test
	public void eachTokenHasItsOwnSessions(TestInfo info) throws Exception {
		AString alice = TestEngine.uniqueDID(info);
		String first = createToken(alice, "first", Vectors.of("v/ops/covia/read"));
		String second = createToken(alice, "second", Vectors.of("v/ops/covia/read"));
		String session = post(INITIALIZE, first, null).headers().firstValue("Mcp-Session-Id").orElseThrow();

		HttpRequest.Builder close = HttpRequest.newBuilder(URI.create(MCP_URL)).DELETE();
		assertEquals(404, send(close, second, session).statusCode(), "another token of the same issuer");
		assertEquals(200, send(HttpRequest.newBuilder(URI.create(MCP_URL)).DELETE(), first, session).statusCode());
	}
}
