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
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.client.VenueHTTP;
import covia.grid.auth.VenueAuth;
import covia.venue.TestServer;
import covia.venue.User;

/**
 * The job-free DLFS browsing read API (#253): {@code GET /api/v1/dlfs/drives}
 * and {@code GET /api/v1/dlfs/list} reuse {@code DLFSAdapter.dispatch}'s own
 * capability checks, so they behave identically to the operation form but
 * without persisting a Job per read. Runs against the shared {@link TestServer}
 * as a unique authenticated caller.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class DlfsApiTest {

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

	/** Creates a drive with one file, via the ordinary op form, and returns the drive name. */
	private String createDriveWithFile() throws Exception {
		String drive = "api-test-" + System.nanoTime();
		client.invokeAndWait(Strings.create("v/ops/dlfs/create-drive"), Maps.of(
			Strings.create("name"), Strings.create(drive)));
		client.invokeAndWait(Strings.create("v/ops/dlfs/write"), Maps.of(
			Strings.create("drive"), Strings.create(drive),
			Strings.create("path"), Strings.create("note.txt"),
			Strings.create("content"), Strings.create("hello")));
		return drive;
	}

	@Test
	public void testListDrivesIncludesACreatedDrive() throws Exception {
		String drive = createDriveWithFile();
		HttpResponse<String> r = get("dlfs/drives", true);
		assertEquals(200, r.statusCode(), r.body());
		AVector<?> drives = (AVector<?>) RT.getIn(JSON.parse(r.body()), "drives");
		boolean found = false;
		for (long i = 0; i < drives.count(); i++) {
			if (Strings.create(drive).equals(drives.get(i))) found = true;
		}
		assertTrue(found, "created drive appears in the caller's drive list: " + r.body());
	}

	@Test
	public void testListReturnsWrittenEntry() throws Exception {
		String drive = createDriveWithFile();
		HttpResponse<String> r = get("dlfs/list?drive=" + drive, true);
		assertEquals(200, r.statusCode(), r.body());
		AVector<?> entries = (AVector<?>) RT.getIn(JSON.parse(r.body()), "entries");
		assertEquals(1, entries.count(), r.body());
		assertEquals(Strings.create("note.txt"), RT.getIn(entries.get(0), "name"));
	}

	@Test
	public void testListRequiresDriveParam() throws Exception {
		HttpResponse<String> r = get("dlfs/list", true);
		assertEquals(400, r.statusCode(), r.body());
	}

	@Test
	public void testReadsAreJobFree() throws Exception {
		String drive = createDriveWithFile();
		long before = jobCount();
		get("dlfs/drives", true);
		get("dlfs/list?drive=" + drive, true);
		assertEquals(before, jobCount(), "GET /dlfs/drives and /dlfs/list must not persist a job");
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
