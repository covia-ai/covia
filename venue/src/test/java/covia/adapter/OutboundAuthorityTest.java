package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.sun.net.httpserver.HttpServer;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;
import covia.lattice.Cap;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.TestServer;

/** Ordinary egress is independent of invocation, credentials and the harness. */
class OutboundAuthorityTest {

	private final Engine engine = TestServer.ENGINE; // explicitly allows localhost

	private RequestContext owner(TestInfo info) {
		AString did = TestEngine.uniqueDID(info);
		engine.getVenueState().users().ensure(did);
		return engine.context(did);
	}

	private static AMap<AString, ACell> egress(AString ability, String url) {
		return Capability.create(Abilities.OUTBOUND, ability, Maps.of("url", url));
	}

	private Job run(String op, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		try { job.awaitResult(10_000); } catch (Exception ignored) { }
		return job;
	}

	private static void complete(Job job) {
		assertEquals(Status.COMPLETE, job.getStatus(), job.getErrorMessage());
	}

	@Test void methodsPathsAndAdministrativeBypassAreIndependent(TestInfo info) {
		RequestContext owner = owner(info);
		RequestContext get = owner.withCaps(Vectors.of(egress(Abilities.OUTBOUND_HTTP_GET, "https://api.example.test/v1")));
		// A private test hostname need not resolve: test authority separately from address policy.
		assertTrue(engine.authorityCovers(get, Abilities.OUTBOUND, Abilities.OUTBOUND_HTTP_GET, Strings.create("https://api.example.test/v1/messages")));
		assertFalse(engine.authorityCovers(get, Abilities.OUTBOUND, Abilities.OUTBOUND_HTTP_POST, Strings.create("https://api.example.test/v1/messages")));
		assertFalse(engine.authorityCovers(get, Abilities.OUTBOUND, Abilities.OUTBOUND_HTTP_GET, Strings.create("https://api.example.test/v10")));
		assertFalse(engine.authorityCovers(get, Abilities.OUTBOUND, Abilities.OUTBOUND_HTTP_GET, Strings.create("https://other.example.test/v1")));
		AuthException refusal = assertThrows(AuthException.class, () -> engine.requireOutboundHttp(get, "GET", "https://api.example.test/v2?token=private-secret"));
		assertInstanceOf(covia.exception.CapabilityDeniedException.class, refusal);
		assertFalse(refusal.getMessage().contains("private-secret"));
		RequestContext all = engine.venueContext().withCaps(Vectors.of(Capability.create(Abilities.OUTBOUND, Abilities.OUTBOUND)));
		assertFalse(engine.outboundUnrestricted(all));
		Engine guarded = TestEngine.ENGINE;
		assertThrows(covia.exception.InvalidInputException.class, () -> guarded.requireOutboundHttp(
			guarded.venueContext().withCaps(Vectors.of(Capability.create(Abilities.OUTBOUND, Abilities.OUTBOUND))),
			"GET", "http://127.0.0.1:1/"));
		RequestContext bypassOnly = engine.venueContext().withCaps(Vectors.of(Capability.create(Abilities.OUTBOUND, Abilities.ADMIN_OUTBOUND_BYPASS)));
		assertTrue(engine.outboundUnrestricted(bypassOnly));
		assertThrows(AuthException.class, () -> engine.requireOutboundHttp(bypassOnly, "POST", "http://localhost:1/"));
		assertFalse(engine.outboundUnrestricted(engine.venueContext().withCaps(Vectors.of(Capability.create(Abilities.OUTBOUND, Strings.create("outbound/any"))))));
		assertTrue(Cap.urlCovers("https://venue.example/", "https://venue.example/api/asset/o%2Fname"));
		assertFalse(Cap.urlCovers("https://venue.example/api", "https://venue.example/api/asset/o%2Fname"));
	}

	@Test void redirectsNeedTheNewDestinationAndMethod(TestInfo info) throws Exception {
		AtomicInteger first = new AtomicInteger(), next = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/first", exchange -> {
			first.incrementAndGet();
			exchange.getResponseHeaders().set("Location", "/next");
			exchange.sendResponseHeaders(303, -1); exchange.close();
		});
		server.createContext("/next", exchange -> {
			next.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close();
		});
		server.start();
		try {
			String base = "http://localhost:" + server.getAddress().getPort();
			ACell invoke = Maps.of(Capability.CAN, Abilities.INVOKE);
			RequestContext owner = owner(info);
			ACell input = Maps.of("url", base + "/first", "followRedirects", true);
			Job denied = run("v/ops/http/post", input, owner.withCaps(Vectors.of(invoke)));
			assertEquals(Status.FAILED, denied.getStatus());
			assertTrue(denied.getErrorMessage().contains(Abilities.OUTBOUND_HTTP_POST.toString()), denied.getErrorMessage());
			assertEquals(0, first.get());
			RequestContext post = owner.withCaps(Vectors.of(invoke, egress(Abilities.OUTBOUND_HTTP_POST, base)));
			denied = run("v/ops/http/post", input, post);
			assertEquals(Status.FAILED, denied.getStatus());
			assertTrue(denied.getErrorMessage().contains(Abilities.OUTBOUND_HTTP_GET.toString()), denied.getErrorMessage());
			assertEquals(1, first.get()); assertEquals(0, next.get());
			denied = run("v/ops/http/post", input, owner.withCaps(Vectors.of(invoke, egress(Abilities.OUTBOUND_HTTP, base + "/first"))));
			assertEquals(Status.FAILED, denied.getStatus()); assertEquals(0, next.get());
			complete(run("v/ops/http/post", input, owner.withCaps(Vectors.of(invoke, egress(Abilities.OUTBOUND_HTTP, base)))));
			assertEquals(1, next.get());
		} finally { server.stop(0); }
	}

	@Test void harnessCanCallTheModelWhileItsAgentCannot(TestInfo info) throws Exception {
		AtomicInteger requests = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/v1/chat/completions", exchange -> {
			requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
			byte[] response = """
				{"id":"reply","object":"chat.completion","created":1,"model":"test-model",
				 "choices":[{"index":0,"message":{"role":"assistant","content":"Hello"},"finish_reason":"stop"}],
				 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
				""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response); exchange.close();
		});
		server.start();
		try {
			String base = "http://localhost:" + server.getAddress().getPort() + "/v1";
			RequestContext owner = owner(info);
			ACell invoke = Maps.of(Capability.CAN, Abilities.INVOKE);
			AString id = Strings.create("worker");
			complete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id, Fields.CONFIG, Maps.of(
				Fields.OPERATION, "v/ops/llmagent/chat", "llmOperation", "v/ops/langchain/openai",
				"model", "test-model", "apiKey", "test-key", "url", base, "acceptTasks", true,
				Config.CAPS, Vectors.of(invoke))), owner));
			complete(run("v/ops/agent/request", Maps.of(Fields.AGENT_ID, id, Fields.INPUT, Maps.of("task", "Hello")), owner));
			assertEquals(1, requests.get(), "configured model ran under harness authority");
			RequestContext agent = engine.context(owner.getUserDID(), Principals.agentDID(owner.getUserDID(), id));
			ACell input = Maps.of("url", base, "apiKey", "test-key", "model", "test-model", "prompt", "Call myself");
			Job refused = run("v/ops/langchain/openai", input, agent);
			assertEquals(Status.FAILED, refused.getStatus());
			assertTrue(refused.getErrorMessage().contains(Abilities.OUTBOUND_HTTP_POST.toString()), refused.getErrorMessage());
			assertEquals(1, requests.get(), "even an inline API key grants no egress to the agent");
			// Deliberately grant only the exact wire endpoint, not the provider base URL.
			complete(run("v/ops/langchain/openai", input, owner.withCaps(Vectors.of(invoke,
				egress(Abilities.OUTBOUND_HTTP_POST, base + "/chat/completions")))));
			assertEquals(2, requests.get());
		} finally { server.stop(0); }
	}

	@Test void pooledConnectionsDoNotRetainAnAgentsRevokedGrant(TestInfo info) throws Exception {
		RequestContext owner = owner(info);
		AString id = Strings.create("network");
		complete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id, Fields.CONFIG,
			Maps.of(Config.CAPS, Vectors.of(egress(Abilities.OUTBOUND_HTTP, TestServer.BASE_URL)))), owner));
		RequestContext agent = engine.context(owner.getUserDID(), Principals.agentDID(owner.getUserDID(), id));
		var venue = (covia.grid.client.VenueHTTP) engine.connectVenue(agent, TestServer.BASE_URL, covia.grid.auth.VenueAuth.none());
		assertNotNull(venue.getStatus().get());
		MCPAdapter mcp = (MCPAdapter) engine.getAdapter("mcp");
		AString url = Strings.create(TestServer.BASE_URL + "/mcp");
		assertNotNull(mcp.listMCPTools(agent, url, null));
		complete(run("v/ops/agent/update", Maps.of(Fields.AGENT_ID, id, Fields.CONFIG,
			Maps.of(Config.CAPS, Vectors.empty())), owner));
		var denied = assertThrows(java.util.concurrent.ExecutionException.class, () -> venue.getStatus().get());
		assertInstanceOf(AuthException.class, denied.getCause());
		AuthException refused = assertThrows(AuthException.class, () -> mcp.listMCPTools(agent, url, null));
		assertTrue(refused.getMessage().contains(Abilities.OUTBOUND_HTTP_POST.toString()), refused.getMessage());
		// A subsequent authorized caller can still use the same transport pool.
		assertNotNull(mcp.listMCPTools(owner, url, null));
	}

	@Test void aRefusedCallerLeavesAPooledConnectionToOthersInFlight(TestInfo info) throws Exception {
		RequestContext owner = owner(info);
		AString id = Strings.create("offline");
		complete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id, Fields.CONFIG,
			Maps.of(Config.CAPS, Vectors.empty())), owner));
		RequestContext agent = engine.context(owner.getUserDID(), Principals.agentDID(owner.getUserDID(), id));
		MCPAdapter mcp = (MCPAdapter) engine.getAdapter("mcp");
		AString url = Strings.create(TestServer.BASE_URL + "/mcp");
		String gateName = "pooled-mcp-" + owner.getUserDID();
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			CompletableFuture<ACell> held = CompletableFuture.supplyAsync(() -> mcp.callMCPTool(owner, url,
				"test_hold", Maps.of("gate", gateName, Fields.RESULT, "released"), null));
			assertTrue(gate.awaitEntered(10, TimeUnit.SECONDS), "the owner's call is in flight");
			assertThrows(AuthException.class, () -> mcp.listMCPTools(agent, url, null));
			assertNotNull(mcp.listMCPTools(owner, url, null));
			gate.release();
			ACell result = held.get(10, TimeUnit.SECONDS);
			assertTrue(String.valueOf(result).contains("released"), String.valueOf(result));
		}
	}
}
