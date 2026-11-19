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
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.MCPAdapter;
import covia.adapter.agent.GoalTreeContext;
import covia.adapter.token.AccessTokens;
import covia.venue.server.AuthMiddleware;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Agent;
import covia.venue.AgentWatch;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.TestHTTP;
import covia.venue.TestServer;

/**
 * The venue's MCP endpoint with an MCP token as its bearer (ACCESS_TOKENS.md):
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

		assertEquals(401, post(INITIALIZE, AccessTokens.BEARER_PREFIX + "1".repeat(40), null).statusCode(), "a token never issued");
		run("v/ops/mcp/delete-token", Maps.of(Fields.NAME, "short-lived"), RequestContext.of(alice));
		assertEquals(401, post(INITIALIZE, bearer, null).statusCode(), "a deleted token");
	}

	@Test
	public void aTokenIsAcceptedOnlyWhereItHasAnEntry(TestInfo info) throws Exception {
		AString alice = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(alice);
		String bearer = RT.getIn(run("v/ops/token/create", Maps.of(Fields.NAME, "elsewhere",
			Fields.ACCESS, Maps.of("test", Maps.empty())), owner).getOutput(), Fields.TOKEN).toString();

		HttpResponse<String> refused = post(INITIALIZE, bearer, null);
		assertEquals(401, refused.statusCode());
		assertTrue(refused.body().contains(AuthMiddleware.TOKEN_NOT_ACCEPTED.formatted(MCPAdapter.ACCESS_POINT)), refused.body());

		run("v/ops/token/update", Maps.of(Fields.NAME, "elsewhere", Fields.ACCESS, Maps.of("test", Maps.empty(),
			MCPAdapter.ACCESS_POINT, Maps.of(Fields.TOOLS, Vectors.of("v/ops/covia/read")))), owner);
		assertEquals(200, post(INITIALIZE, bearer, null).statusCode(), "accepted once it has an MCP entry");
	}

	@Test
	public void anAgentAskedThroughATokenIsToldWhichToken(TestInfo info) throws Exception {
		AString alice = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(alice);
		run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "helper",
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")), owner);
		String bearer = RT.getIn(run("v/ops/mcp/create-token", Maps.of(Fields.NAME, "outside-bot",
			Fields.TOOLS, Vectors.of("v/ops/agent/request"),
			Fields.CAPS, Vectors.of(Capability.create(Strings.create("g/helper"), Abilities.AGENT_REQUEST))),
			owner).getOutput(), Fields.TOKEN).toString();
		Agent agent = engine.getVenueState().users().user(alice).agent("helper");
		Blob sid = Blob.fromHex("88888888888888888888888888888888");
		agent.sessions().create(sid, alice, null);

		try (AgentWatch watch = AgentWatch.open(engine, alice, "helper")) {
			ACell asked = rpc(call(2, "agent_request", "{\"agentId\":\"helper\",\"sessionId\":\"" + sid.toHexString()
				+ "\",\"input\":{\"ask\":\"hello\"},\"wait\":5000}"), bearer);
			assertNotEquals(CVMBool.TRUE, RT.getIn(asked, "result", "isError"), asked.toString());
			watch.awaitRest(1);
		}

		AVector<ACell> frames = RT.ensureVector(agent.session(sid).getRecord().get(Agent.KEY_FRAMES));
		AMap<AString, ACell> turn = RT.ensureMap(GoalTreeContext.activeConversation(RT.ensureMap(frames.get(0))).get(0));
		assertEquals(alice, turn.get(Fields.CALLER), "the request is its issuer's");
		AVector<ACell> route = RT.ensureVector(turn.get(Fields.ROUTE));
		assertEquals(1, route.count(), "the request started with the token: " + route);
		AMap<AString, ACell> tokenRecord = RT.ensureMap(route.get(0));
		assertEquals(Strings.create("token"), tokenRecord.get(Fields.VIA), "and the agent is told it came through the token");
		assertEquals(alice, tokenRecord.get(Fields.PRINCIPAL), "which vouches for its issuer");
		assertEquals(engine.tokens().lookup(bearer)
			.assoc(Fields.TRANSPORT, Strings.create("http"))
			.assoc(Fields.PROTOCOL, Strings.create("mcp"))
			.assoc(Fields.CREDENTIAL_TYPE, Strings.create("accessToken")),
			tokenRecord.dissoc(Fields.VIA).dissoc(Fields.PRINCIPAL).dissoc(Fields.TEXT),
			"named by its reference, with verified MCP ingress facts");
		assertFalse(agent.session(sid).getRecord().toString().contains(bearer), "the bearer is never recorded");
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
