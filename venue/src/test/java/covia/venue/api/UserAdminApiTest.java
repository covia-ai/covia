package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.client.VenueHTTP;
import covia.grid.auth.VenueAuth;
import covia.venue.TestServer;
import covia.venue.User;

/**
 * The job-free user admin read API (#255): {@code GET /api/v1/users},
 * {@code GET /api/v1/users/{did}} and {@code GET /api/v1/users/{did}/authentications}
 * reuse {@code UserAdapter}'s own operator-authority checks, so a signed-in
 * non-operator caller gets 403 (a gated view), never a broken page. Runs
 * against the shared {@link TestServer} as a unique authenticated caller.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class UserAdminApiTest {

	private String jwt;
	private String callerDID;
	private VenueHTTP client;

	@BeforeAll
	public void setup() throws Exception {
		AKeyPair kp = AKeyPair.generate();
		callerDID = UCAN.toDIDKey(kp.getAccountKey()).toString();
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		UCAN token = UCAN.create(kp, TestServer.ENGINE.getAccountKey(), exp,
			Vectors.empty(), Vectors.empty());
		jwt = token.toJWT(kp).toString();
		client = VenueHTTP.create(URI.create(TestServer.BASE_URL), VenueAuth.bearer(jwt));
		client.setTimeout(5000);
		// users.autoCreate is on for TestServer, so authenticating registers us.
		client.invokeAndWait(Strings.create("v/ops/user/info"), Maps.empty());
	}

	private HttpResponse<String> get(String route, boolean auth) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder()
			.uri(URI.create(TestServer.BASE_URL + "/api/v1/" + route)).GET();
		if (auth) b.header("Authorization", "Bearer " + jwt);
		return covia.venue.TestHTTP.CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private long jobCount() {
		User u = TestServer.ENGINE.getVenueState().users().get(Strings.create(callerDID));
		return (u == null) ? 0 : u.getJobs().count();
	}

	@Test
	public void testListDeniesOrdinaryRegisteredCaller() throws Exception {
		// Registered, but not the venue itself and holding no venue-issued
		// delegation over <venueDID>/users — exactly "signed in, not an
		// operator", so this must be a 403, not a 401 or a 500.
		HttpResponse<String> r = get("users", true);
		assertEquals(403, r.statusCode(), r.body());
	}

	@Test
	public void testListDeniesAnonymousCaller() throws Exception {
		// TestServer runs unrestricted PUBLIC access, but that only covers
		// resources rooted at the public pseudo-identity — venue administration
		// (<venueDID>/users) is never satisfied by it, so anonymous is denied
		// exactly like an ordinary registered caller: 403, not a crash.
		HttpResponse<String> r = get("users", false);
		assertEquals(403, r.statusCode(), r.body());
	}

	@Test
	public void testSelfInfoAndAuthenticationsSucceedWithoutOperatorAuthority() throws Exception {
		HttpResponse<String> info = get("users/" + callerDID, true);
		assertEquals(200, info.statusCode(), info.body());
		assertEquals(Strings.create(callerDID), RT.getIn(JSON.parse(info.body()), "did"));

		HttpResponse<String> auths = get("users/" + callerDID + "/authentications", true);
		// A self-sovereign did:key caller is not a venue-managed named user, so
		// authentication-list rejects with a 400 (bad request), not 403/500 —
		// distinguishing "wrong kind of account" from "not authorised".
		assertEquals(400, auths.statusCode(), auths.body());
	}

	@Test
	public void testInfoDeniesReadingAnotherUsersRecord() throws Exception {
		String otherDID = UCAN.toDIDKey(AKeyPair.generate().getAccountKey()).toString();
		HttpResponse<String> r = get("users/" + otherDID, true);
		assertEquals(403, r.statusCode(), r.body());
	}

	@Test
	public void testReadsAreJobFree() throws Exception {
		long before = jobCount();
		get("users", true);
		get("users/" + callerDID, true);
		assertEquals(before, jobCount(), "GET /users and /users/{did} must not persist a job");
	}

	@Test
	public void testStatusReportsAdmissionPolicyTruthfully() throws Exception {
		HttpResponse<String> r = get("status", false);
		assertEquals(200, r.statusCode(), r.body());
		ACell access = RT.getIn(JSON.parse(r.body()), "access");
		assertEquals(CVMBool.TRUE, RT.getIn(access, "userAutoCreate"),
			"TestServer runs with users.autoCreate: true (Config.AUTO_CREATE)");
	}

	/** Sanity check that the shared test server actually exercises a real job
	 *  somewhere else, so testReadsAreJobFree isn't vacuously true. */
	@Test
	public void testJobCountBaselineIsMeaningful() throws Exception {
		long before = jobCount();
		Job job = client.invokeAndWait(Strings.create("v/test/ops/echo"),
			Maps.of(Strings.create("message"), Strings.create("hi")));
		assertEquals(Status.COMPLETE, job.getStatus());
		assertTrue(jobCount() > before, "an ordinary invoke does persist a job, for contrast");
	}
}
