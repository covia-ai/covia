package covia.grid.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.Hashing;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.ResponseException;
import covia.grid.AContent;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.impl.BlobContent;
import covia.venue.Config;
import covia.venue.TestServer;
import covia.venue.server.VenueServer;

/**
 * Contract tests for {@link VenueHTTP} — the canonical HTTP client used by
 * every grid integration (federation, asset upload/download, job polling,
 * agent-to-agent). Closes issue #97.
 *
 * <p><b>Design decision (real venues, not stubs):</b> these tests drive a
 * real {@link VenueServer} rather than a mock HTTP server. A stub encodes the
 * test author's assumption of the venue's wire contract — it passes while the
 * real integration is broken, which is exactly the client↔venue drift that
 * matters for a federation client. Most contracts are exercised against the
 * shared {@link TestServer}; auth contracts use a dedicated auth-required
 * venue launched here with public access disabled.</p>
 *
 * <p><b>Known residual gap (deliberately not covered):</b> a <em>misbehaving</em>
 * server — non-JSON / truncated body, or a forced HTTP 5xx from an intermediary
 * proxy — cannot be produced by a well-behaved venue. Covering "client survives
 * a garbage response without an NPE" is the one scenario that would require a
 * stub; left as a documented gap rather than reintroducing a fake server.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
public class VenueHTTPTest {

	private static final AString OP_ECHO  = Strings.create("v/test/ops/echo");
	private static final AString OP_NEVER = Strings.create("v/test/ops/never");
	private static final AString OP_DELAY = Strings.create("v/test/ops/delay");
	private static final AString OP_ERROR = Strings.create("v/test/ops/error");

	/** Shared client against the shared public test venue. */
	private VenueHTTP client;

	/** Dedicated venue with public access disabled — for auth contracts. */
	private VenueServer authServer;
	private String authBase;

	@BeforeAll
	public void setup() {
		client = TestServer.COVIA;

		authServer = VenueServer.launch(Maps.of(
			Strings.create("port"), 0, // ephemeral
			Config.AUTH, Maps.of(
				Config.PUBLIC, Maps.of(Config.ENABLED, false)
			)));
		authBase = "http://localhost:" + authServer.port();
	}

	@AfterAll
	public void teardown() {
		if (authServer != null) {
			try { authServer.close(); } catch (Exception ignored) {}
		}
	}

	// ============================== Sanity ==============================

	@Test
	public void getStatusReturnsVenueInfo() {
		AMap<AString, ACell> status = client.getStatus().join();
		assertNotNull(status, "status must be a non-null map");
		assertFalse(status.isEmpty(), "status map must carry venue info");
	}

	// ====================== Not-found / error codes =====================

	@Test
	public void nonexistentJobDataReturnsNull() {
		// 16-byte (6 ts + 2 counter + 8 random) all-zero id: well-formed, absent.
		Blob bogus = Blob.parse("00000000000000000000000000000000");
		assertNull(client.getJobData(bogus).join(),
			"404 on a well-formed-but-absent job id must surface as null, not an exception");
	}

	@Test
	public void cancelNonexistentJobThrowsResponseException() {
		CompletionException ex = assertThrows(CompletionException.class,
			() -> client.cancelJob("00000000000000000000000000000000").join());
		assertTrue(ex.getCause() instanceof ResponseException,
			"cancel of an absent job must be a typed ResponseException, got: " + ex.getCause());
		assertTrue(ex.getCause().getMessage().toLowerCase().contains("not found"),
			"error must name the cause: " + ex.getCause().getMessage());
	}

	@Test
	public void operationFailureSurfacesAsFailedJobWithError() throws Exception {
		Job job = client.invokeAndWait(OP_ERROR, Maps.of(
			Fields.MESSAGE, Strings.create("intentional-failure")));
		assertNotNull(job);
		assertEquals(Status.FAILED, job.getStatus(),
			"a failing operation must surface through the client as FAILED");
		AString err = RT.ensureString(RT.getIn(job.getData(), Fields.ERROR));
		assertNotNull(err, "failure must carry a non-null error message");
		assertFalse(err.toString().isBlank(), "error message must not be blank");
	}

	// ===================== Polling / timeout / backoff ==================

	@Test
	public void clientPollingTimeoutIsBoundedAndRemoteJobSurvives() throws Exception {
		// v/test/ops/never never completes — submit it, then poll with a short
		// client-side timeout. The poll must give up promptly; the remote job
		// must remain queryable (client timeout is client-side only).
		Job job = client.startJob(OP_NEVER, Maps.empty());
		assertNotNull(job.getID());

		long t0 = System.currentTimeMillis();
		assertThrows(ResponseException.class, () -> client.waitForFinish(job, 300),
			"polling past the deadline must throw ResponseException");
		long elapsed = System.currentTimeMillis() - t0;
		assertTrue(elapsed < 5000, "client-side poll timeout must be bounded; took " + elapsed + "ms");

		AMap<AString, ACell> remote = client.getJobData(job.getID()).join();
		assertNotNull(remote, "remote job must remain queryable by id after a client timeout");
		assertFalse(Job.isFinished(remote),
			"the remote never-op job keeps running — the client timeout did not cancel it");

		// Best-effort cleanup so the never-op vthread does not linger.
		try { client.cancelJob(job.getID().toHexString()).join(); } catch (Exception ignored) {}
	}

	@Test
	public void backoffPollLoopCompletesDelayedJob() throws Exception {
		// A non-instant job forces several poll iterations through the
		// exponential-backoff loop before COMPLETE is observed.
		Job job = client.invokeAndWait(OP_DELAY, Maps.of(
			Fields.DELAY, CVMLong.create(400),
			Fields.OPERATION, OP_ECHO,
			Fields.INPUT, Maps.of(Strings.create("k"), Strings.create("v"))));
		assertEquals(Status.COMPLETE, job.getStatus(),
			"the backoff poll loop must return the completed result for a delayed job");
		assertEquals("v", RT.getIn(job.getOutput(), "k").toString());
	}

	// ========================= Transport failure ========================

	@Test
	public void connectionRefusedFailsWithoutHanging() {
		// Nothing listens on port 1 — connection is refused. The client must
		// fail promptly, not hang until some long socket timeout.
		VenueHTTP dead = VenueHTTP.create(URI.create("http://localhost:1"));
		dead.setTimeout(2000);

		long t0 = System.currentTimeMillis();
		assertThrows(CompletionException.class, () -> dead.getStatus().join(),
			"a refused connection must surface as an exception");
		long elapsed = System.currentTimeMillis() - t0;
		assertTrue(elapsed < 12000, "refused connection must fail fast; took " + elapsed + "ms");
	}

	// ========================== Content streaming =======================

	@Test
	public void largeContentRoundTrips() throws Exception {
		final int size = 5 * 1024 * 1024; // 5 MB — under the 10 MB request cap
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) bytes[i] = (byte) (i * 31 + 7); // deterministic
		Blob original = Blob.wrap(bytes);
		Hash contentHash = Hashing.sha256(bytes);

		// Asset metadata must declare its content descriptor; the venue verifies
		// the uploaded bytes against this sha256 — so this also covers integrity.
		Hash assetId = client.addAsset(Maps.of(
			"name", Strings.create("large-content-roundtrip"),
			"content", Maps.of("sha256", Strings.create(contentHash.toHexString()))))
			.get(10, TimeUnit.SECONDS);
		assertNotNull(assetId);

		client.addContent(assetId.toHexString(), BlobContent.of(original)).get(15, TimeUnit.SECONDS);

		AContent got = client.getContent(assetId.toHexString()).get(15, TimeUnit.SECONDS);
		assertNotNull(got, "uploaded content must be retrievable");
		Blob retrieved = got.getBlob().toFlatBlob();
		assertEquals(size, retrieved.count(), "round-tripped content must not be truncated");
		assertEquals(original, retrieved, "round-tripped bytes must match exactly");
	}

	// ============================ Concurrency ===========================

	@Test
	public void concurrentRequestsOnSharedClient() {
		final int n = 16;
		Map<Long, String> results = new ConcurrentHashMap<>();
		IntStream.range(0, n).parallel().forEach(i -> {
			try {
				Job job = client.invokeSync("v/test/ops/echo",
					Maps.of(Strings.create("i"), Strings.create(Integer.toString(i))));
				results.put((long) i, RT.getIn(job.getOutput(), "i").toString());
			} catch (Exception e) {
				throw new RuntimeException("request " + i + " failed", e);
			}
		});
		assertEquals(n, results.size(), "every concurrent request on the shared client must complete");
		for (int i = 0; i < n; i++) {
			assertEquals(Integer.toString(i), results.get((long) i),
				"concurrent responses must not be cross-wired between requests");
		}
	}

	// ============================= Timeout API ==========================

	@Test
	public void setTimeoutGetterRoundTrips() {
		VenueHTTP c = VenueHTTP.create(URI.create(TestServer.BASE_URL));
		c.setTimeout(1234);
		assertEquals(1234, c.getTimeout());
	}

	// =============================== Auth ===============================

	@Test
	public void unauthenticatedRequestRejected() {
		// Public access is disabled on authServer — an anonymous request to a
		// gated /api/* path must be refused, not silently allowed.
		VenueHTTP none = VenueHTTP.create(URI.create(authBase));
		none.setTimeout(3000);
		CompletionException ex = assertThrows(CompletionException.class,
			() -> none.getStatus().join());
		assertTrue(ex.getCause() instanceof ResponseException,
			"401 must surface as a typed ResponseException, got: " + ex.getCause());
	}

	@Test
	public void validBearerAuthenticates() throws Exception {
		// A self-signed UCAN (issuer == caller) with a declared audience and in
		// date authenticates its issuer as the caller (AuthMiddleware.tryVerifyUCAN
		// returns the issuer DID). The Authorization header must actually carry
		// identity through to a gated venue, not merely be present.
		AKeyPair callerKP = AKeyPair.generate();
		AString callerDID = UCAN.toDIDKey(callerKP.getAccountKey());
		AString audienceDID = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		long exp = (System.currentTimeMillis() / 1000) + 3600;

		UCAN token = UCAN.create(callerKP,
			UCAN.fromDIDKey(audienceDID), exp,
			Vectors.of(Capability.create(Strings.create(callerDID + "/w/"), Capability.CRUD_READ)),
			Vectors.empty());
		String jwt = token.toJWT(callerKP).toString();

		VenueHTTP authed = VenueHTTP.create(URI.create(authBase), VenueAuth.bearer(jwt));
		authed.setTimeout(5000);

		Job job = authed.invokeAndWait(OP_ECHO,
			Maps.of(Strings.create("hi"), Strings.create("there")));
		assertEquals(Status.COMPLETE, job.getStatus(),
			"a valid bearer must authenticate the caller through to a gated venue");
		assertEquals("there", RT.getIn(job.getOutput(), "hi").toString());
	}
}
