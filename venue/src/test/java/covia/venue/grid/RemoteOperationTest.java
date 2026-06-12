package covia.venue.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.RequestContext;
import covia.venue.TwoVenueTestServer;

/**
 * Execution semantics for operation definitions published by another venue.
 * Builds on the fetch primitives pinned by {@link RemoteAssetFetchTest};
 * the explicit-delegation side (grid:run / grid:invoke with a venue
 * argument) is covered in depth by {@link CrossVenueTest}.
 *
 * <p><b>Axioms under test</b></p>
 * <ul>
 *   <li><b>A1 — job locality.</b> An invoke accepted by a venue creates a
 *       job record on that venue, owned by that venue's view of the
 *       caller. A venue that merely served a definition fetch records no
 *       job at all. A venue that cannot resolve a reference REJECTS the
 *       invoke — no job anywhere.</li>
 *   <li><b>A2 — references denote definitions.</b> An operation reference
 *       identifies content-addressed metadata. A DID prefix says where the
 *       definition can be FETCHED from — never where it executes.</li>
 *   <li><b>A3 — execution site is explicit.</b> An operation runs on the
 *       venue that accepted the invoke, with that venue's adapters and
 *       context. Running on another venue requires explicit grid:run /
 *       grid:invoke with a venue argument — and then A1 applies at each
 *       hop: a job on the delegating venue AND a job on the executing
 *       venue.</li>
 *   <li><b>Fetch is transient.</b> Invoking a remote reference does not
 *       implant the definition in any local store. Durable adoption is a
 *       separate, explicit act (pin).</li>
 * </ul>
 *
 * <p><b>Determinism note.</b> The two venues are shared by the parallel
 * test suite, so assertions about "no job on venue B" are made
 * content-wise — scanning for jobs that reference a hash unique to one
 * test — never by ledger counts. Each test likewise uses its own caller
 * DID, so caller-scoped ledgers are test-exclusive.</p>
 */
public class RemoteOperationTest {

	/**
	 * Builds an operation definition with a test-unique name, so each test
	 * gets its own content hash — the basis for race-free ledger scans.
	 * The adapter (jvm:stringConcat) exists on both venues; execution-site
	 * claims rest on job ledgers, not adapter availability.
	 */
	private static AString concatOpMeta(String marker) {
		return Strings.create("""
			{
			  "name": "Concat — %s (published by venue B only)",
			  "description": "String concat definition for cross-venue fetch-and-run tests.",
			  "operation": {
			    "adapter": "jvm:stringConcat",
			    "input": {
			      "type": "object",
			      "properties": {
			        "first": {"type": "string"},
			        "second": {"type": "string"}
			      },
			      "required": ["first", "second"]
			    },
			    "output": {
			      "type": "object",
			      "properties": {"result": {"type": "string"}}
			    }
			  }
			}
			""".formatted(marker));
	}

	/** did:web reference to an asset as published by venue B. */
	private static String didWebRefB(Hash id) {
		return "did:web:localhost%3A" + TwoVenueTestServer.PORT_B + "/a/" + id.toHexString();
	}

	/** Whether any job on venue B's public ledger references the given op hash. */
	private static boolean jobOnBReferencesOp(Hash opId) {
		RequestContext publicB = RequestContext.of(
			Strings.create(TwoVenueTestServer.DID_B + ":public"));
		Index<Blob, ACell> jobs = TwoVenueTestServer.ENGINE_B.jobs().getJobs(publicB);
		String opHex = opId.toHexString();
		for (long i = 0; i < jobs.count(); i++) {
			ACell jobData = jobs.entryAt(i).getValue();
			ACell opField = RT.getIn(jobData, Fields.OP);
			if (opField != null && opHex.equals(opField.toString())) return true;
		}
		return false;
	}

	// ============== A2 + A3: remote reference → fetch, run HERE ==============

	@Test
	public void remoteReferenceExecutesLocally() {
		Hash opId = TwoVenueTestServer.ENGINE_B.storeAsset(concatOpMeta("fetch-and-run"), null);
		RequestContext caller = RequestContext.of(Strings.create("did:key:zCallerFetchAndRun"));

		// Premise: venue A does not hold the definition.
		assertNull(TwoVenueTestServer.ENGINE_A.getAsset(opId),
			"Test premise: the definition must not pre-exist on venue A");

		// Invoke ON VENUE A by the definition's did:web reference.
		Job job = TwoVenueTestServer.ENGINE_A.jobs().invokeOperation(
			didWebRefB(opId),
			Maps.of("first", "Fetch", "second", "Local"),
			caller);
		job.awaitResult(10_000);

		// The operation ran, with the fetched definition.
		assertEquals(Status.COMPLETE, job.getStatus());
		assertEquals("FetchLocal", RT.getIn(job.getOutput(), "result").toString());

		// A1 on venue A: the accepting venue holds the job, owned by the caller.
		assertNotNull(TwoVenueTestServer.ENGINE_A.jobs().getJobData(job.getID(), caller),
			"A1: the venue that accepted the invoke must hold the job record");

		// A1 on venue B: serving the definition fetch created NO job there.
		assertFalse(jobOnBReferencesOp(opId),
			"A1/A3: the publishing venue served a read — it must record no job");

		// Fetch is transient: the definition was used, not adopted.
		assertNull(TwoVenueTestServer.ENGINE_A.getAsset(opId),
			"Fetch must not implant the definition in venue A's store — pin is explicit");
	}

	// ============== A3 contrast: explicit delegation → job on BOTH hops ==============

	@Test
	public void explicitDelegationCreatesJobOnRemoteVenue() {
		Hash opId = TwoVenueTestServer.ENGINE_B.storeAsset(concatOpMeta("delegation"), null);
		RequestContext caller = RequestContext.of(Strings.create("did:key:zCallerDelegation"));

		// Same kind of definition, but execution on B is requested
		// EXPLICITLY: grid:run with a venue argument. B resolves the bare
		// hash locally and runs it as a job IT accepted.
		Job gridJob = TwoVenueTestServer.ENGINE_A.jobs().invokeOperation(
			"v/ops/grid/run",
			Maps.of(
				Fields.VENUE, TwoVenueTestServer.BASE_URL_B,
				Fields.OPERATION, opId.toHexString(),
				Fields.INPUT, Maps.of("first", "Run", "second", "There")),
			caller);
		gridJob.awaitResult(10_000);

		assertEquals(Status.COMPLETE, gridJob.getStatus());
		assertEquals("RunThere", RT.getIn(gridJob.getOutput(), "result").toString());

		// A1 at hop 1: the delegating venue records the grid:run job.
		assertNotNull(TwoVenueTestServer.ENGINE_A.jobs().getJobData(gridJob.getID(), caller));

		// A1 at hop 2: the executing venue records the invoke it accepted.
		assertTrue(jobOnBReferencesOp(opId),
			"A1/A3: explicit delegation must create a job on the executing venue");
	}

	// ============== A1: unresolvable references are rejected, not jobbed ==============

	@Test
	public void unresolvableRemoteReferenceIsRejectedBeforeAnyJob() {
		// A reference venue B cannot serve: rejection happens at
		// resolution, before any job exists. A1 governs ACCEPTED invokes.
		Hash absent = Hash.fromHex(
			"ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100");
		RequestContext caller = RequestContext.of(Strings.create("did:key:zCallerRejected"));

		assertThrows(IllegalArgumentException.class, () ->
			TwoVenueTestServer.ENGINE_A.jobs().invokeOperation(
				didWebRefB(absent), Maps.of("first", "x", "second", "y"), caller));

		assertEquals(0, TwoVenueTestServer.ENGINE_A.jobs().getJobs(caller).count(),
			"A rejected invoke must leave no job record behind (caller DID is test-exclusive)");
	}

	@Test
	public void didKeyReferencesCannotBeFetched() {
		// Fetch scope: only did:web names a fetchable endpoint. A did:key
		// reference that isn't locally resolvable is "not found" — never a
		// network call, never a guess.
		Hash opId = TwoVenueTestServer.ENGINE_B.storeAsset(concatOpMeta("did-key-scope"), null);
		RequestContext caller = RequestContext.of(Strings.create("did:key:zCallerKeyScope"));
		String didKeyRef = "did:key:z6MkNotAFetchableVenue/a/" + opId.toHexString();

		assertThrows(IllegalArgumentException.class, () ->
			TwoVenueTestServer.ENGINE_A.jobs().invokeOperation(
				didKeyRef, Maps.of("first", "x", "second", "y"), caller));
	}
}
