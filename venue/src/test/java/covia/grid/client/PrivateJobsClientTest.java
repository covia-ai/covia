package covia.grid.client;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.grid.auth.UcanTokens;
import covia.venue.TestServer;

/**
 * Tests for the client's connection-level private-jobs mode (#192) and the
 * ucan verify / job-free read conveniences, against a real VenueServer
 * (TestServer enables {@code enablePrivateJobs}). Each test uses its own
 * VenueHTTP so flipping private mode never affects the shared client used by
 * the parallel suite.
 */
public class PrivateJobsClientTest {

	private VenueHTTP freshClient() {
		VenueHTTP client = VenueHTTP.create(URI.create(TestServer.BASE_URL));
		client.setTimeout(5000);
		return client;
	}

	@Test
	public void testPrivateRunReturnsOutputAndPersistsNothing() throws Exception {
		VenueHTTP client = freshClient();
		client.setPrivate(true);

		AMap<AString,ACell> input = Maps.of(Strings.create("greeting"), Strings.create("private-hello"));
		Job job = client.invokeAndWait(Strings.create("v/test/ops/echo"), input);
		assertTrue(job.isComplete(), "private run should return the finished record from the wait window");
		assertEquals(input, job.getOutput());

		// The completed private job is immediately forgotten — no persisted record.
		client.setPrivate(false);
		assertFalse(client.listJobs().contains(job.getID()),
			"a private job must not appear in the venue's job index");
	}

	@Test
	public void testPrivateRunFailureSurfacesAsJobFailure() throws Exception {
		VenueHTTP client = freshClient();
		client.setPrivate(true);

		Job job = client.invokeAndWait(Strings.create("v/test/ops/error"),
			Maps.of(Strings.create("message"), Strings.create("deliberate")));
		assertTrue(job.isFinished());
		assertFalse(job.isComplete());
		assertThrows(JobFailedException.class, job::getOutput);
	}

	@Test
	public void testPrivateSyncEntryPointWorks() throws Exception {
		VenueHTTP client = freshClient();
		client.setPrivate(true);

		// invokeSync goes through startJob — the submit timeout must cover the
		// server-side wait window under private mode.
		Job job = client.invokeSync("v/test/ops/echo",
			Maps.of(Strings.create("n"), Strings.create("42")));
		assertTrue(job.isComplete());
	}

	@Test
	public void testPollStyleInvokeRefusedUnderPrivateMode() {
		VenueHTTP client = freshClient();
		client.setPrivate(true);
		assertThrows(IllegalStateException.class,
			() -> client.invoke("v/test/ops/echo", Maps.empty()),
			"poll-style invoke cannot collect a private job's result and must fail loudly");
		// ...and setPrivate(false) restores it.
		client.setPrivate(false);
		assertDoesNotThrow(() -> client.invoke("v/test/ops/echo", Maps.empty()).join());
	}

	@Test
	public void testVerifyUcan() throws Exception {
		VenueHTTP client = freshClient();

		// Garbage token: valid=false with a diagnosable reason.
		AMap<AString,ACell> bad = client.verifyUcan("not-a-jwt-at-all");
		assertEquals(CVMBool.FALSE, RT.getIn(bad, "valid"));
		assertNotNull(RT.getIn(bad, "reason"));

		// Self-sovereign grant minted client-side (UcanTokens), verified via
		// the helper: owner-rooted, and authorises a covered request for the
		// audience but not an uncovered sibling.
		AKeyPair aliceKP = AKeyPair.generate();
		AString aliceDID = UCAN.toDIDKey(aliceKP.getAccountKey());
		AString bobDID = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		String token = UcanTokens.grant(aliceKP, bobDID.toString(),
			aliceDID + "/w/shared/", "crud/read", 3600);

		AMap<AString,ACell> good = client.verifyUcan(token,
			aliceDID + "/w/shared/doc", "crud/read", bobDID.toString());
		assertEquals(CVMBool.TRUE, RT.getIn(good, "valid"));
		assertEquals(aliceDID, RT.getIn(good, "rootIssuer"));
		assertEquals(CVMBool.TRUE, RT.getIn(good, "authorises"));

		AMap<AString,ACell> sibling = client.verifyUcan(token,
			aliceDID + "/w/private/doc", "crud/read", bobDID.toString());
		assertEquals(CVMBool.FALSE, RT.getIn(sibling, "authorises"));
	}

	@Test
	public void testJobFreeReads() {
		VenueHTTP client = freshClient();

		// Values read: the adapter registry is venue-owned and always present.
		// (That these GET routes persist no job is a venue-side property,
		// covered by the venue's read-API tests — asserting a global job count
		// here would race the parallel suite on the shared server.)
		AMap<AString,ACell> adapters = client.getValue("read", "v/info/adapters").join();
		assertNotNull(RT.getIn(adapters, "value"), "v/info/adapters should resolve to the registry");

		// Agent listing returns the agent:list payload shape.
		AMap<AString,ACell> agents = client.getAgents().join();
		assertNotNull(RT.getIn(agents, "agents"), "agent list result should carry an agents field");
	}
}
