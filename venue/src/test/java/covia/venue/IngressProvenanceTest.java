package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.jwt.JWT;
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
import covia.adapter.TestAdapter;
import covia.adapter.agent.GoalTreeContext;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.HexId;
import covia.test.IntegrationTest;

/** Real HTTP ingress, stored agent turns and the input actually handed to a model. */
@IntegrationTest
public class IngressProvenanceTest {
	private final Engine engine = TestServer.ENGINE;

	@Test
	public void resumingAJobKeepsItsIngressWithoutAnotherRecord(TestInfo info) {
		AString owner = TestEngine.uniqueDID(info);
		RequestContext ingress = engine.requestContext(owner, null, Strings.create("http"),
			Strings.create("mcp"), Strings.create("venueJwt"));
		var job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), ingress);
		try {
			RequestContext resumed = engine.jobs().callerContext(job);
			assertEquals(ingress.getRoute(), resumed.getRoute());
			assertEquals(1, resumed.getRoute().count());
			assertNull(resumed.getOrigin(), "resumption is unattended, but preserves the original source");
		} finally {
			job.cancel();
		}
	}

	@Test
	public void ownerLoginsAndNamedTokensRetainTheirSource(TestInfo info) throws Exception {
		AString owner = Strings.create(engine.getDIDString() + ":u:"
			+ TestEngine.uniqueDID(info).toString().substring("did:key:".length()));
		RequestContext own = RequestContext.of(owner);
		long now = System.currentTimeMillis() / 1000;
		String login = JWT.signPublic(Maps.of("sub", owner, "iss", engine.getDIDString(),
			"aud", engine.getDIDString(), "iat", now, "exp", now + 300), engine.getKeyPair()).toString();
		for (String mode : new String[] {"direct", "mcp-login", "mcp-token", "rest-login"}) {
			AString id = Strings.create("ingress-" + mode);
			engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id,
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/llm")), own).awaitResult(10_000);
			try {
				boolean direct = mode.equals("direct");
				boolean mcp = mode.startsWith("mcp");
				boolean named = mode.equals("mcp-token");
				String bearer = login;
				if (named) {
					ACell issued = engine.jobs().invokeOperation("v/ops/mcp/create-token", Maps.of(
						Fields.NAME, "outside-client", Fields.TOOLS, Vectors.of("v/ops/agent/chat"),
						Fields.CAPS, Vectors.of(Maps.of("with", "g/" + id, "can", Abilities.AGENT_MESSAGE))), own)
						.awaitResult(10_000);
					bearer = RT.getIn(issued, Fields.TOKEN).toString();
				}
				AMap<AString, ACell> input = Maps.of(Fields.AGENT_ID, id, Fields.MESSAGE, "Hello from a client.",
					Fields.ROUTE, Vectors.of(Maps.of(Fields.VIA, "auth", Fields.PRINCIPAL, owner)));
				ACell reply;
				if (direct) {
					reply = engine.jobs().invokeOperation("v/ops/agent/chat", input, own).awaitResult(10_000);
				} else {
					ACell body = mcp ? Maps.of("jsonrpc", "2.0", "id", 1L, "method", "tools/call",
						"params", Maps.of("name", "agent_chat", "arguments", input))
						: Maps.of(Fields.OPERATION, "v/ops/agent/chat", Fields.INPUT, input);
					HttpResponse<String> response = TestHTTP.CLIENT.send(HttpRequest.newBuilder(
						URI.create(TestServer.BASE_URL + (mcp ? "/mcp" : "/api/v1/run")))
						.header("Content-Type", "application/json").header("Accept", "application/json")
						.header("Authorization", "Bearer " + bearer)
						.header("X-Covia-Transport", "direct")
						.timeout(Duration.ofSeconds(10))
						.POST(HttpRequest.BodyPublishers.ofString(JSON.toString(body))).build(),
						HttpResponse.BodyHandlers.ofString());
					assertEquals(200, response.statusCode(), response.body());
					ACell parsed = JSON.parse(response.body());
					assertNull(RT.getIn(parsed, "error"), response.body());
					if (mcp) {
						assertNotEquals(CVMBool.TRUE, RT.getIn(parsed, "result", "isError"), response.body());
						reply = RT.getIn(parsed, "result", "structuredContent");
					} else reply = parsed;
				}
				Blob sid = HexId.parse(RT.<ACell>getIn(reply, Fields.SESSION_ID));
				assertNotNull(sid, String.valueOf(reply));
				ACell frame = RT.getIn(engine.getAgent(owner, id).session(sid).getRecord(), Agent.KEY_FRAMES, 0);
				ACell turn = GoalTreeContext.activeConversation(RT.ensureMap(frame)).get(0);
				assertEquals(owner, RT.getIn(turn, Fields.CALLER));
				AVector<ACell> route = RT.ensureVector(RT.getIn(turn, Fields.ROUTE));
				assertEquals(1, route.count(), "transport decorates ingress rather than inventing a handover");
				assertEquals(Strings.create(named ? "token" : "auth"), RT.getIn(route, 0, Fields.VIA));
				assertEquals(direct ? null : Strings.create("http"), RT.getIn(route, 0, Fields.TRANSPORT));
				assertEquals(mcp ? Strings.create("mcp") : null, RT.getIn(route, 0, Fields.PROTOCOL));
				assertEquals(direct ? null : Strings.create(named ? "accessToken" : "venueJwt"),
					RT.getIn(route, 0, Fields.CREDENTIAL_TYPE));
				assertFalse(route.toString().contains(bearer), "no bearer is recorded");
				AVector<ACell> messages = RT.ensureVector(RT.getIn(TestAdapter.CAPTURED_LLM_INPUT.get(id), "messages"));
				assertNotNull(messages);
				String from = null;
				for (ACell message : messages) {
					String content = String.valueOf(RT.getIn(message, Fields.CONTENT));
					if (content.startsWith("[From")) from = content;
				}
				// The owner's first direct message carries no From line (AGENT_CONTEXT.md).
				if (direct) assertNull(from, from);
				else {
					assertNotNull(from, mode);
					assertTrue(from.contains("transport http"), from);
					assertFalse(from.contains("credential"), from);
					assertFalse(from.contains("venueJwt"), from);
					assertFalse(from.contains("accessToken"), from);
					if (named) assertTrue(from.contains("held by someone the venue does not know"), from);
				}
			} finally {
				TestAdapter.CAPTURED_LLM_INPUT.remove(id);
			}
		}
	}
}
