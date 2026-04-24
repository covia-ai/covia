package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Job;

/**
 * Integration tests for UCAN transport via {@code Authorization: Bearer <ucan-jwt>}
 * on the REST API. Verifies:
 * <ul>
 *   <li>A valid UCAN bearer token sets callerDID to the UCAN's issuer (IETF UCAN-HTTP)</li>
 *   <li>Tampered or expired bearers fall through to the existing auth paths
 *       (anonymous, under public-access mode)</li>
 *   <li>Bearer and body {@code ucans} are merged through a single trust boundary</li>
 * </ul>
 */
@TestInstance(Lifecycle.PER_CLASS)
public class UCANBearerTransportTest {

	static final int PORT = TestServer.PORT;
	static final String BASE_URL = TestServer.BASE_URL;

	Engine engine;

	private static final AKeyPair ALICE_KP = AKeyPair.generate();
	private static final AKeyPair BOB_KP = AKeyPair.generate();
	private static final AString ALICE_DID = UCAN.toDIDKey(ALICE_KP.getAccountKey());
	private static final AString BOB_DID = UCAN.toDIDKey(BOB_KP.getAccountKey());

	@BeforeAll
	public void setup() {
		engine = TestServer.ENGINE;
		// Seed Alice's workspace via engine-direct calls (bypasses HTTP auth).
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/shared/doc", Fields.VALUE, Strings.create("shared content")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
	}

	private static HttpResponse<String> postInvoke(String body, String bearer) throws Exception {
		HttpClient client = HttpClient.newBuilder().build();
		HttpRequest.Builder rb = HttpRequest.newBuilder()
			.uri(new URI(BASE_URL + "/api/v1/invoke?wait=true"))
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.header("Content-Type", "application/json")
			.timeout(Duration.ofSeconds(10));
		if (bearer != null) rb.header("Authorization", "Bearer " + bearer);
		return client.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString())
			.get();
	}

	private static AString callerDIDOf(HttpResponse<String> resp) {
		ACell parsed = JSON.parse(resp.body());
		return RT.ensureString(RT.getIn(parsed, Fields.CALLER));
	}

	/**
	 * A valid UCAN bearer (invocation UCAN signed by the caller) causes the
	 * server to attribute the request to the UCAN's {@code iss}.
	 */
	@Test
	public void testBearerUCANAttributesCallerToIssuer() throws Exception {
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		AString invocation = UCAN.createJWT(ALICE_KP,
			engine.getAccountKey(),              // audience: venue
			exp, Vectors.empty(), null);

		HttpResponse<String> resp = postInvoke(
			"{\"operation\":\"v/test/ops/echo\",\"input\":{\"message\":\"hi\"}}",
			invocation.toString());
		assertTrue(resp.statusCode() == 200 || resp.statusCode() == 201,
			() -> "Unexpected: " + resp.statusCode() + " / " + resp.body());
		assertEquals(ALICE_DID, callerDIDOf(resp),
			"Bearer UCAN's iss must become the caller DID");
	}

	/**
	 * A tampered bearer token is not a valid UCAN. Under public-access mode
	 * the request still completes, but attribution falls through to the
	 * anonymous/public DID rather than being silently treated as Alice.
	 */
	@Test
	public void testTamperedBearerFallsThroughToAnonymous() throws Exception {
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		String jwt = UCAN.createJWT(ALICE_KP, engine.getAccountKey(),
			exp, Vectors.empty(), null).toString();
		int lastDot = jwt.lastIndexOf('.');
		char c = jwt.charAt(lastDot + 1);
		char flipped = (c == 'A') ? 'B' : 'A';
		String tampered = jwt.substring(0, lastDot + 1) + flipped + jwt.substring(lastDot + 2);

		HttpResponse<String> resp = postInvoke(
			"{\"operation\":\"v/test/ops/echo\",\"input\":{\"message\":\"hi\"}}",
			tampered);
		assertTrue(resp.statusCode() == 200 || resp.statusCode() == 201);
		AString caller = callerDIDOf(resp);
		assertNotNull(caller);
		assertNotEquals(ALICE_DID, caller,
			"Tampered bearer must not attribute the request to the purported issuer");
	}

	/**
	 * An expired bearer UCAN is rejected at the auth layer (fails temporal
	 * bounds check). Public-access mode means the request still runs, but
	 * attribution is anonymous.
	 */
	@Test
	public void testExpiredBearerFallsThroughToAnonymous() throws Exception {
		long expired = (System.currentTimeMillis() / 1000) - 60;
		AString invocation = UCAN.createJWT(ALICE_KP, engine.getAccountKey(),
			expired, Vectors.empty(), null);

		HttpResponse<String> resp = postInvoke(
			"{\"operation\":\"v/test/ops/echo\",\"input\":{\"message\":\"hi\"}}",
			invocation.toString());
		assertTrue(resp.statusCode() == 200 || resp.statusCode() == 201);
		assertNotEquals(ALICE_DID, callerDIDOf(resp));
	}

	/**
	 * Bearer UCAN and body {@code ucans} merge through the same trust boundary.
	 * Pattern: Bob bears his own invocation UCAN (iss=Bob — establishes
	 * caller identity), and accompanies it with a venue-issued delegation
	 * (iss=venue, aud=Bob, att=read Alice/w/) in the body. The venue
	 * delegation grants Bob cross-user read access.
	 */
	@Test
	public void testBearerAndBodyDelegationGrantCrossUserRead() throws Exception {
		long exp = (System.currentTimeMillis() / 1000) + 3600;

		// 1. Alice asks the venue (via engine-direct) to issue a delegation
		//    to Bob over her namespace.
		Job issueJob = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(
				UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(ALICE_DID + "/w/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(exp)),
			RequestContext.of(ALICE_DID));
		AString delegation = RT.ensureString(RT.getIn(issueJob.awaitResult(5000), "token"));
		assertNotNull(delegation);

		// 2. Bob creates his own invocation UCAN (signed by Bob's key).
		AString invocation = UCAN.createJWT(BOB_KP, engine.getAccountKey(),
			exp, Vectors.empty(), null);

		// 3. Request: bearer = invocation, body.ucans = [delegation]
		String body = "{"
			+ "\"operation\":\"v/ops/covia/read\","
			+ "\"input\":{\"path\":\"" + ALICE_DID + "/w/shared/doc\"},"
			+ "\"ucans\":[\"" + delegation + "\"]"
			+ "}";
		HttpResponse<String> resp = postInvoke(body, invocation.toString());

		assertTrue(resp.statusCode() == 200 || resp.statusCode() == 201,
			() -> "Cross-user read via bearer+body should succeed: "
				+ resp.statusCode() + " / " + resp.body());
		// Caller attributed to Bob (bearer iss), not to venue
		assertEquals(BOB_DID, callerDIDOf(resp));
		// Job result includes the read value
		assertTrue(resp.body().contains("shared content"),
			() -> "Read value missing from response: " + resp.body());
	}
}
