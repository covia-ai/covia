package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.exception.CoviaException;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestLogs;
import covia.venue.TestServer;

/**
 * An unexpected failure inside an A2A method reaches the caller only as
 * {@code INTERNAL} naming its type, never its text (venue/docs/ERRORS.md). A
 * continuation is delivered to a task whose adapter fails as a venue bug
 * would, with text that must not leak.
 *
 * <p>Registers a probe adapter on the shared venue for the test's duration,
 * and the venue logs the failure at ERROR, as it must in production; the class
 * runs alone so neither touches another test.</p>
 */
@Isolated
public class A2AFaultTest {

	/** Text the probe's failure carries, which must never reach the caller. */
	static final String SECRET = "probe-secret-detail";

	static final String PROBE = "a2afault";

	/** A multi-turn adapter whose message handling fails with a JDK exception. */
	static final class FaultyChat extends AAdapter {
		@Override
		public String getName() {
			return PROBE;
		}

		@Override
		public String getDescription() {
			return "A2A fault probe";
		}

		@Override
		public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			job.setStatus(Status.INPUT_REQUIRED);
		}

		@Override
		public boolean supportsMultiTurn() {
			return true;
		}

		@Override
		public void handleMessage(Job job, AMap<AString, ACell> messageRecord) {
			throw new IllegalStateException(SECRET);
		}
	}

	@Test
	public void unexpectedFailureNamesOnlyItsType() throws Exception {
		Engine engine = TestServer.ENGINE;
		AKeyPair kp = AKeyPair.generate();
		AString did = UCAN.toDIDKey(kp.getAccountKey());
		String jwt = bearerFor(kp);

		engine.registerAdapter(new FaultyChat());
		Job job = null;
		try {
			job = engine.jobs().invokeOperation(
				Maps.of(Fields.OPERATION, Maps.of(Fields.ADAPTER, Strings.create(PROBE + ":chat"))),
				Maps.empty(), engine.context(did));
			for (String method : List.of("SendMessage", "SendStreamingMessage")) {
				Message continuation = Message.builder()
					.role(Message.Role.ROLE_USER)
					.parts(List.<Part<?>>of(new TextPart("hello", null)))
					.messageId("msg-" + UUID.randomUUID())
					.taskId(job.getID().toHexString())
					.build();
				Map<String, Object> envelope = new LinkedHashMap<>();
				envelope.put("jsonrpc", "2.0");
				envelope.put("id", method);
				envelope.put("method", method);
				envelope.put("params", new MessageSendParams(continuation, null, null));

				AtomicReference<HttpResponse<String>> response = new AtomicReference<>();
				TestLogs.quiet(A2A.class, () -> response.set(post(envelope, jwt)));

				String body = response.get().body();
				assertEquals(200, response.get().statusCode(), body);
				assertFalse(body.contains(SECRET), "the failure's text reached the caller: " + body);
				Map<String, Object> error = cast(cast(JsonUtil.OBJECT_MAPPER.fromJson(body, Map.class)).get("error"));
				assertNotNull(error, body);
				assertEquals(A2AErrorCodes.INTERNAL.code(), ((Number) error.get("code")).intValue(), body);
				assertEquals(CoviaException.UNEXPECTED.formatted("IllegalStateException"), error.get("message"));
			}
		} finally {
			if (job != null) job.cancel();
			engine.removeAdapter(PROBE);
		}
	}

	/** A bearer token audienced to the shared venue: authenticates as the key's DID. */
	private static String bearerFor(AKeyPair kp) {
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		return UCAN.create(kp, TestServer.ENGINE.getAccountKey(), exp, Vectors.empty(), Vectors.empty())
				.toJWT(kp).toString();
	}

	private static HttpResponse<String> post(Object body, String jwt) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(TestServer.BASE_URL + "/a2a"))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + jwt)
				.POST(HttpRequest.BodyPublishers.ofString(JsonUtil.OBJECT_MAPPER.toJson(body)))
				.timeout(Duration.ofSeconds(10)).build();
		return covia.venue.TestHTTP.CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> cast(Object o) {
		return (Map<String, Object>) o;
	}
}
