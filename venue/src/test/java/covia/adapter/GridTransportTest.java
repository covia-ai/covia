package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.exception.ResponseException;
import covia.exception.CoviaException;
import covia.grid.Grid;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.client.VenueHTTP;
import covia.test.IntegrationTest;
import covia.venue.TestEngine;

/** Grid dispatch through the SDK, with transport completion controlled by the test. */
@IntegrationTest
class GridTransportTest {

	private static final ConcurrentHashMap<String, Peer> PEERS = new ConcurrentHashMap<>();
	static {
		Grid.registerDIDResolver("gridtransporttest", (did, auth) -> PEERS.get(did.toString()));
	}

	private static final class Peer extends VenueHTTP implements AutoCloseable {
		final String did = "did:gridtransporttest:" + UUID.randomUUID();
		final Job remote = Job.create(Maps.of(Fields.ID, "00112233445566778899aabbccddeeff", Fields.STATUS, Status.STARTED));
		final CompletableFuture<Job> lookup = new CompletableFuture<>();
		final AtomicInteger submissions = new AtomicInteger();
		final AtomicInteger pollingSubmissions = new AtomicInteger();
		long completionAfterMs;

		Peer() {
			super(URI.create("https://grid-transport.test"));
			PEERS.put(did, this);
		}

		/** The DID this venue presents; a real venue proves it with its DID document. */
		@Override public String venueDID() { return did; }

		@Override public CompletableFuture<Job> startJobAsync(AString operation, ACell input) {
			submissions.incrementAndGet();
			return CompletableFuture.completedFuture(remote);
		}

		@Override public CompletableFuture<Job> invoke(AString operation, ACell input) {
			// The SDK starts background polling synchronously in this API before
			// returning the handle. Count its use without depending on thread timing.
			pollingSubmissions.incrementAndGet();
			return super.invoke(operation, input);
		}

		@Override public CompletableFuture<Job> getJob(Blob id) {
			assertEquals(remote.getID(), id);
			return lookup;
		}

		@Override public boolean waitForFinish(Job job, long timeoutMs) {
			// Model remote work completing after the SDK's default deadline,
			// without making the test sleep for tens of seconds.
			if (timeoutMs < completionAfterMs) throw new ResponseException("Test peer still running at polling deadline");
			job.completeWith(Strings.create("remote-output"));
			return true;
		}

		@Override public void close() { PEERS.remove(did, this); }
	}

	@Test void asyncSubmissionDoesNotStartSdkPolling() {
		try (Peer peer = new Peer()) {
			Job submitted = invoke("invoke", Maps.of(Fields.VENUE, peer.did,
				Fields.OPERATION, "v/test/ops/echo"));
			AMap<?, ?> snapshot = (AMap<?, ?>) submitted.awaitResult(5000);
			assertEquals(peer.remote.getID(), Job.parseID(snapshot.get(Fields.ID)));
			assertEquals(1, peer.submissions.get());
			assertEquals(0, peer.pollingSubmissions.get());
		}
	}

	@Test void remoteResultCanCompleteBeyondSdkDefaultTimeout() {
		try (Peer peer = new Peer()) {
			peer.completionAfterMs = peer.getTimeout() + 15_000;
			peer.lookup.complete(peer.remote);
			Job result = invoke("jobResult", Maps.of(Fields.VENUE, peer.did,
				Fields.ID, peer.remote.getID().toHexString(), Fields.TIMEOUT, 60_000));
			assertEquals(Strings.create("remote-output"), result.awaitResult(5000));
			assertEquals(0, peer.submissions.get(), "waiting must not submit new work");
		}
	}

	@Test void callerTimeoutDoesNotCancelTheRemoteJobOrItsFuture() {
		try (Peer peer = new Peer()) {
			Job result = invoke("jobResult", Maps.of(Fields.VENUE, peer.did,
				Fields.ID, peer.remote.getID().toHexString(), Fields.TIMEOUT, 10));
			assertThrows(Exception.class, () -> result.awaitResult(5000));
			assertEquals(Status.FAILED, result.getStatus());
			assertEquals(GridAdapter.RESULT_TIMEOUT.formatted(10, peer.remote.getID().toHexString()), result.getErrorMessage());
			assertFalse(peer.lookup.isDone());
			assertFalse(peer.remote.future().isDone());
			peer.remote.completeWith(Strings.create("late-output"));
			peer.lookup.complete(peer.remote);
			assertEquals(Strings.create("late-output"), peer.remote.awaitResult(5000));
		}
	}

	@Test void malformedVenueCannotSelectLocalExecutionOrMetadataFallback() {
		ACell[] invalid = { null, CVMLong.ONE, CVMBool.TRUE, Maps.empty(), Vectors.empty() };
		for (String operation : new String[] { "run", "invoke", "jobStatus", "jobResult" }) {
			for (ACell venue : invalid) {
				AMap<AString, ACell> input = Maps.of(Fields.VENUE, venue,
					Fields.OPERATION, "v/test/ops/echo", Fields.ID, "00112233445566778899aabbccddeeff");
				assertFailure(() -> invoke(operation, input), GridAdapter.INVALID_VENUE);
				AMap<AString, ACell> meta = Maps.of(Fields.OPERATION,
					Maps.of(Fields.ADAPTER, "grid:" + operation, Fields.VENUE, "https://unused.test"));
				assertFailure(() -> TestEngine.ENGINE.jobs().invokeOperation(meta, input,
					TestEngine.ENGINE.venueContext()), GridAdapter.INVALID_VENUE);
				AMap<AString, ACell> invalidMeta = Maps.of(Fields.OPERATION,
					Maps.of(Fields.ADAPTER, "grid:" + operation, Fields.VENUE, venue));
				assertFailure(() -> TestEngine.ENGINE.jobs().invokeOperation(invalidMeta, input.dissoc(Fields.VENUE),
					TestEngine.ENGINE.venueContext()), GridAdapter.INVALID_DEFAULT_VENUE);
			}
		}
	}

	@Test void inputVenueOverridesMetadataVenue() {
		try (Peer peer = new Peer()) {
			AMap<AString, ACell> meta = Maps.of(Fields.OPERATION,
				Maps.of(Fields.ADAPTER, "grid:invoke", Fields.VENUE, "https://unused.test"));
			Job result = TestEngine.ENGINE.jobs().invokeOperation(meta,
				Maps.of(Fields.VENUE, peer.did, Fields.OPERATION, "v/test/ops/echo"),
				TestEngine.ENGINE.venueContext());
			result.awaitResult(5000);
			assertEquals(1, peer.submissions.get());
		}
	}

	private static Job invoke(String operation, ACell input) {
		return TestEngine.ENGINE.jobs().invokeOperation(
			Maps.of(Fields.OPERATION, Maps.of(Fields.ADAPTER, "grid:" + operation)),
			input, TestEngine.ENGINE.venueContext());
	}

	private static void assertFailure(Supplier<Job> invocation, String reason) {
		Job job;
		try { job = invocation.get(); }
		catch (CoviaException e) {
			assertEquals(reason, e.getMessage());
			return;
		}
		assertThrows(Exception.class, () -> job.awaitResult(5000));
		assertEquals(Status.FAILED, job.getStatus());
		assertEquals(reason, job.getErrorMessage());
	}
}
