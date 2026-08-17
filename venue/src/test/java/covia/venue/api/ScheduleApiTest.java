package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.client.VenueHTTP;
import covia.grid.auth.VenueAuth;
import covia.venue.TestServer;
import covia.venue.User;

/**
 * The job-free schedules read API (#369): {@code GET /api/v1/schedules} returns
 * the authenticated caller's pending scheduled events without persisting a Job,
 * so a Scheduler UI can page and refresh cheaply. Runs against the shared
 * {@link TestServer} as a unique authenticated caller.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class ScheduleApiTest {

	private String jwt;
	private String callerDID;
	private VenueHTTP client;

	@BeforeAll
	public void setup() throws Exception {
		AKeyPair kp = AKeyPair.generate();
		callerDID = UCAN.toDIDKey(kp.getAccountKey()).toString();
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		UCAN token = UCAN.create(kp, TestServer.ENGINE.getAccountKey(), exp,
			Vectors.of(Capability.create(Strings.create(callerDID + "/w/"), Capability.CRUD_READ)),
			Vectors.empty());
		jwt = token.toJWT(kp).toString();
		client = VenueHTTP.create(URI.create(TestServer.BASE_URL), VenueAuth.bearer(jwt));
		client.setTimeout(5000);
	}

	private HttpResponse<String> get(String route, boolean auth) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder()
			.uri(URI.create(TestServer.BASE_URL + "/api/v1/" + route)).GET();
		if (auth) b.header("Authorization", "Bearer " + jwt);
		return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private long jobCount() {
		User u = TestServer.ENGINE.getVenueState().users().get(Strings.create(callerDID));
		return (u == null) ? 0 : u.getJobs().count();
	}

	/** Schedules a far-future no-op event and returns its handle. */
	private String schedule() throws Exception {
		Job job = client.invokeAndWait(Strings.create("v/ops/scheduler/schedule"), Maps.of(
			Strings.create("operation"), Strings.create("v/test/ops/echo"),
			Strings.create("input"), Maps.of(Strings.create("message"), Strings.create("later")),
			Strings.create("after"), CVMLong.create(3_600_000L)));
		assertEquals(Status.COMPLETE, job.getStatus(), "schedule failed: " + job.getErrorMessage());
		return RT.getIn(job.getOutput(), "handle").toString();
	}

	@Test
	public void testSchedulesListReturnsCallerEvents() throws Exception {
		String handle = schedule();
		HttpResponse<String> r = get("schedules", true);
		assertEquals(200, r.statusCode(), r.body());
		AVector<?> events = (AVector<?>) RT.getIn(JSON.parse(r.body()), "events");
		assertNotNull(events, r.body());
		boolean found = false;
		for (long i = 0; i < events.count(); i++) {
			ACell e = events.get(i);
			if (handle.equals(String.valueOf(RT.getIn(e, "handle")))) {
				found = true;
				assertEquals(Strings.create("v/test/ops/echo"), RT.getIn(e, "op"), "event carries its op");
				assertNotNull(RT.getIn(e, "time"), "event carries its fire time");
			}
		}
		assertTrue(found, "scheduled event appears in the caller's list: " + r.body());
	}

	@Test
	public void testReadIsJobFree() throws Exception {
		schedule();
		long before = jobCount();
		assertEquals(200, get("schedules", true).statusCode());
		assertEquals(before, jobCount(), "GET /schedules must not persist a job");
	}

	@Test
	public void testAuthenticationRequired() throws Exception {
		// Public access is enabled on the test venue: an anonymous request reads
		// the (empty) public identity's schedules, never the caller's.
		HttpResponse<String> r = get("schedules", false);
		assertEquals(200, r.statusCode(), r.body());
		AVector<?> events = (AVector<?>) RT.getIn(JSON.parse(r.body()), "events");
		assertFalse(events != null && events.count() > 0 && anyOwnedByCaller(events),
			"anonymous must not see the authenticated caller's schedules");
	}

	private boolean anyOwnedByCaller(AVector<?> events) throws Exception {
		// The caller's own list, for comparison.
		AVector<?> mine = (AVector<?>) RT.getIn(JSON.parse(get("schedules", true).body()), "events");
		for (long i = 0; i < events.count(); i++) {
			for (long j = 0; mine != null && j < mine.count(); j++) {
				if (String.valueOf(RT.getIn(events.get(i), "handle"))
						.equals(String.valueOf(RT.getIn(mine.get(j), "handle")))) return true;
			}
		}
		return false;
	}
}
