package covia.venue.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.core.crypto.Hashing;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.AContent;
import covia.grid.Asset;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.Venue;
import covia.venue.RequestContext;
import covia.venue.TwoVenueTestServer;

/**
 * Fetch primitives for content-addressed assets across venues — the
 * foundation under cross-venue definition resolution (see
 * {@link RemoteOperationTest} for the execution semantics built on top).
 *
 * <p><b>Assumptions under test</b></p>
 * <ul>
 *   <li>An asset is identified by the CAD3 value hash of its metadata.
 *       The same metadata held on any venue has the same id — location is
 *       not part of identity.</li>
 *   <li>{@code Venue.getAsset(hash)} over HTTP returns the published
 *       metadata for an asset the remote venue holds, and null for one it
 *       does not hold.</li>
 *   <li>{@code Engine.fetchRemoteAsset} verifies that fetched metadata
 *       hashes to the requested id. Content addressing is the trust
 *       boundary: a remote venue is purely an availability provider and
 *       cannot substitute a different definition, however it responds.</li>
 *   <li>A fetched definition carries no venue — holding a definition
 *       implies nothing about where it executes.</li>
 *   <li>did:web references resolve to the publishing venue's HTTP
 *       endpoint, including a percent-encoded port and plain http for
 *       localhost.</li>
 *   <li>Fetching is a read: it creates no job record on either venue.</li>
 * </ul>
 */
public class RemoteAssetFetchTest {

	/** Artifact metadata published ONLY on venue B (unique name → unique hash). */
	private static final AString ARTIFACT_META = Strings.create("""
		{
		  "name": "Fetch primitive test artifact (venue B)",
		  "description": "Plain data asset published on venue B for cross-venue fetch tests."
		}
		""");

	/** Different metadata, used to simulate a substitution attack. */
	private static final AString WRONG_META = Strings.create("""
		{
		  "name": "Substituted metadata",
		  "description": "Not what was asked for."
		}
		""");

	private static Hash publishOnB() {
		return TwoVenueTestServer.ENGINE_B.storeAsset(ARTIFACT_META, null);
	}

	// ============== Identity is the hash, not the location ==============

	@Test
	public void sameMetadataHasSameIdOnBothVenues() {
		// Location-independence: storing identical metadata on two venues
		// yields the same asset id, because the id IS the metadata hash.
		Hash onB = TwoVenueTestServer.ENGINE_B.storeAsset(ARTIFACT_META, null);
		Hash onA = TwoVenueTestServer.ENGINE_A.storeAsset(ARTIFACT_META, null);
		assertEquals(onB, onA,
			"Content addressing: identical metadata must have identical id everywhere");
	}

	// ============== Basic remote fetch ==============

	@Test
	public void fetchesPublishedMetadataFromRemoteVenue() {
		Hash id = publishOnB();
		Asset fetched = TwoVenueTestServer.ENGINE_A.fetchRemoteAsset(
			TwoVenueTestServer.BASE_URL_B, id);

		assertNotNull(fetched, "Venue B holds the asset, so the fetch must succeed");
		assertEquals(id, fetched.getID(), "Fetched asset id must be the requested hash");
		AMap<AString, ACell> meta = fetched.meta();
		assertEquals("Fetch primitive test artifact (venue B)",
			RT.getIn(meta, "name").toString(),
			"Fetched metadata must round-trip the published definition");
		assertNull(fetched.getVenue(),
			"A fetched DEFINITION carries no venue — it implies nothing about execution site");
	}

	@Test
	public void fetchesByDidWebReferenceWithPort() {
		// did:web encodes a port with a percent-encoded colon; localhost
		// resolves over http (no TLS for loopback).
		Hash id = publishOnB();
		String didWeb = "did:web:localhost%3A" + TwoVenueTestServer.PORT_B;
		Asset fetched = TwoVenueTestServer.ENGINE_A.fetchRemoteAsset(didWeb, id);

		assertNotNull(fetched, "did:web with encoded port must resolve to the venue's HTTP endpoint");
		assertEquals(id, fetched.getID());
	}

	// ============== Absence and failure are clean nulls ==============

	@Test
	public void fetchOfAbsentAssetReturnsNull() {
		// A hash venue B has never seen — fetch reports absence, not error.
		Hash absent = Hash.fromHex(
			"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");
		assertNull(TwoVenueTestServer.ENGINE_A.fetchRemoteAsset(
			TwoVenueTestServer.BASE_URL_B, absent));
	}

	@Test
	public void fetchFromUnreachableVenueReturnsNull() {
		// Resolution failure (venue down / wrong address) is "not found",
		// surfaced by the caller as a clean unresolvable-reference error.
		// Uses a hash unique to this test: a hash fetched by ANY other test
		// would be served from the definition cache regardless of the
		// connection string (see cachedDefinitionSurvivesPublisherOutage).
		AString meta = Strings.create("""
			{
			  "name": "Unreachable-venue test artifact",
			  "description": "Published on B but only ever requested from a dead address."
			}
			""");
		Hash id = TwoVenueTestServer.ENGINE_B.storeAsset(meta, null);
		assertNull(TwoVenueTestServer.ENGINE_A.fetchRemoteAsset("http://localhost:1", id));
	}

	// ============== Caching: hashes are identities, venues are hints ==============

	@Test
	public void cachedDefinitionSurvivesPublisherOutage() {
		// Definitions are immutable, so a fetched one can be cached by hash
		// forever — staleness is impossible. Consequence under test: once
		// ANY venue has supplied the definition, re-resolution succeeds
		// even when the reference's venue hint is unreachable. The cache is
		// transient plumbing, not adoption: the asset store stays empty.
		AString meta = Strings.create("""
			{
			  "name": "Cache test artifact (venue B)",
			  "description": "Fetched once from B, then re-resolved against a dead address."
			}
			""");
		Hash id = TwoVenueTestServer.ENGINE_B.storeAsset(meta, null);

		Asset first = TwoVenueTestServer.ENGINE_A.fetchRemoteAsset(
			TwoVenueTestServer.BASE_URL_B, id);
		assertNotNull(first, "First fetch goes to the publisher");

		Asset second = TwoVenueTestServer.ENGINE_A.fetchRemoteAsset("http://localhost:1", id);
		assertNotNull(second,
			"Cached definition must be served even when the venue hint is dead");
		assertEquals(id, second.getID());

		assertNull(TwoVenueTestServer.ENGINE_A.getAsset(id),
			"The cache is not adoption — nothing lands in the asset store");
	}

	// ============== Verification: content addressing is the trust boundary ==============

	@Test
	public void fetchRejectsMetadataThatDoesNotHashToRequestedId() {
		// A misbehaving venue returns SOMETHING for the requested hash —
		// but the metadata doesn't hash to it. An honest VenueServer cannot
		// be made to do this, so the misbehaviour is simulated directly.
		Hash requested = publishOnB();
		Venue tampering = new TamperingVenue();
		assertNull(TwoVenueTestServer.ENGINE_A.fetchRemoteAsset(tampering, requested),
			"Substituted metadata must be rejected: the hash is the identity");
	}

	// ============== Fetching is a read ==============

	@Test
	public void fetchCreatesNoJobRecords() {
		// A definition fetch is a plain read — the publishing venue records
		// no job for it. Asserted content-wise (no job on B references this
		// asset's hash), not count-wise: the venues are shared by parallel
		// tests, so ledger counts are not stable.
		Hash id = publishOnB();

		assertNotNull(TwoVenueTestServer.ENGINE_A.fetchRemoteAsset(
			TwoVenueTestServer.BASE_URL_B, id));

		RequestContext publicB = RequestContext.of(
			Strings.create(TwoVenueTestServer.DID_B + ":public"));
		Index<Blob, ACell> jobsOnB = TwoVenueTestServer.ENGINE_B.jobs().getJobs(publicB);
		for (long i = 0; i < jobsOnB.count(); i++) {
			ACell jobData = jobsOnB.entryAt(i).getValue();
			ACell opField = RT.getIn(jobData, Fields.OP);
			assertNotEquals(id.toHexString(),
				(opField == null) ? null : opField.toString(),
				"Serving a definition fetch must not create a job on the publishing venue");
		}
	}

	// ============== Content fetch (artifacts, not just metadata) ==============

	@Test
	public void fetchesArtifactContentFromRemoteVenue() throws IOException {
		// An artifact's content travels with its hash-addressed identity:
		// the metadata declares the content (sha256), the content is
		// uploaded separately, and a client of the publishing venue can
		// stream it back.
		Blob content = Blob.wrap("artifact content bytes".getBytes());
		Hash contentHash = Hashing.sha256(content.getBytes());
		AString meta = Strings.create("""
			{
			  "name": "Content-bearing artifact (venue B)",
			  "description": "Has binary content stored alongside its metadata.",
			  "content": {"sha256": "%s"}
			}
			""".formatted(contentHash.toHexString()));
		Hash id = TwoVenueTestServer.ENGINE_B.storeAsset(meta, null);
		TwoVenueTestServer.ENGINE_B.putContent(id, new ByteArrayInputStream(content.getBytes()));

		Asset fetched = TwoVenueTestServer.COVIA_B.getAsset(id);
		assertNotNull(fetched);
		AContent fetchedContent = fetched.getContent();
		assertNotNull(fetchedContent, "Stored content must be retrievable over HTTP");
		assertEquals(content, fetchedContent.getBlob().toFlatBlob(),
			"Content bytes must round-trip unchanged");
	}

	// ============== Named catalog fetch (bindings, not hashes) ==============

	@Test
	public void namedFetchResolvesBindingThenHashVerifiedDefinition() {
		// A catalog name is a mutable binding maintained by the publisher.
		// Fetching one is two steps: name → id on the publisher's word,
		// then the definition itself over the SAME hash-verified path as
		// any hash reference. Both venues install identical built-ins, so
		// B's binding must resolve to the id A knows for its own copy —
		// the binding crosses venues, the definition is location-free.
		Asset fetched = TwoVenueTestServer.ENGINE_A.fetchRemoteNamedAsset(
			TwoVenueTestServer.BASE_URL_B, "v/ops/jvm/string-concat");

		assertNotNull(fetched, "Venue B binds the name, so the fetch must succeed");
		Asset ownCopy = TwoVenueTestServer.ENGINE_A.resolveAsset(
			Strings.create("v/ops/jvm/string-concat"));
		assertNotNull(ownCopy);
		assertEquals(ownCopy.getID(), fetched.getID(),
			"Identical built-in definitions must have identical ids on both venues");
		assertNull(fetched.getVenue(),
			"A fetched definition carries no venue, named or not");
	}

	@Test
	public void namedFetchOfUnboundNameReturnsNull() {
		assertNull(TwoVenueTestServer.ENGINE_A.fetchRemoteNamedAsset(
			TwoVenueTestServer.BASE_URL_B, "v/ops/no/such/name"),
			"An unbound name is absence, not error");
	}

	// ============== Adoption: pin makes a remote definition durable ==============

	@Test
	public void pinAdoptsRemoteAssetByHashReference() {
		// Fetch is transient; PIN is the explicit act of adoption. Pinning a
		// remote hash reference fetches the definition (hash-verified) and
		// stores it durably in the caller's own /a/ namespace.
		AString meta = Strings.create("""
			{
			  "name": "Pin adoption test artifact (venue B)",
			  "description": "Published on B, adopted on A via asset:pin."
			}
			""");
		Hash id = TwoVenueTestServer.ENGINE_B.storeAsset(meta, null);
		AString callerDid = Strings.create("did:key:zPinAdopter");
		RequestContext caller = RequestContext.of(callerDid);
		String didRef = "did:web:localhost%3A" + TwoVenueTestServer.PORT_B
			+ "/a/" + id.toHexString();

		assertNull(TwoVenueTestServer.ENGINE_A.getAsset(id),
			"Test premise: the definition must not pre-exist on venue A");

		Job pin = TwoVenueTestServer.ENGINE_A.jobs().invokeOperation(
			"v/ops/asset/pin", Maps.of(Fields.PATH, didRef), caller);
		pin.awaitResult(10_000);
		assertEquals(Status.COMPLETE, pin.getStatus());

		// Adopted: the caller now holds the definition durably.
		assertNotNull(TwoVenueTestServer.ENGINE_A.getAssetRecord(id, callerDid),
			"Pin must store the fetched definition in the caller's namespace");
	}

	@Test
	public void pinAdoptsRemoteContentVerified() throws IOException {
		// An artifact's declared content is adopted along with its metadata,
		// and the bytes are verified against the declared sha256 — a venue
		// can withhold content, but not substitute it.
		Blob content = Blob.wrap("adoptable content bytes".getBytes());
		Hash contentHash = Hashing.sha256(content.getBytes());
		AString meta = Strings.create("""
			{
			  "name": "Pin adoption content artifact (venue B)",
			  "description": "Content-bearing artifact adopted across venues.",
			  "content": {"sha256": "%s"}
			}
			""".formatted(contentHash.toHexString()));
		Hash id = TwoVenueTestServer.ENGINE_B.storeAsset(meta, null);
		TwoVenueTestServer.ENGINE_B.putContent(id, new ByteArrayInputStream(content.getBytes()));

		AString callerDid = Strings.create("did:key:zPinContentAdopter");
		RequestContext caller = RequestContext.of(callerDid);
		String didRef = "did:web:localhost%3A" + TwoVenueTestServer.PORT_B
			+ "/a/" + id.toHexString();

		Job pin = TwoVenueTestServer.ENGINE_A.jobs().invokeOperation(
			"v/ops/asset/pin", Maps.of(Fields.PATH, didRef), caller);
		pin.awaitResult(10_000);
		assertEquals(Status.COMPLETE, pin.getStatus());

		AVector<?> record = TwoVenueTestServer.ENGINE_A.getAssetRecord(id, callerDid);
		assertNotNull(record);
		assertEquals(content, record.get(covia.venue.AssetStore.POS_CONTENT),
			"Adopted content must round-trip, verified against the declared sha256");
	}

	@Test
	public void pinAdoptsNamedRemoteReference() {
		// Pinning a named catalog reference adopts whatever definition the
		// name resolves to AT PIN TIME — the binding stays the publisher's;
		// the adopted copy is the caller's, frozen at the resolved hash.
		AString meta = Strings.create("""
			{
			  "name": "Pin-named adoption op (venue B)",
			  "description": "Catalog-named definition adopted across venues.",
			  "operation": {
			    "adapter": "jvm:stringConcat",
			    "input": {"type": "object"}
			  }
			}
			""");
		Hash id = TwoVenueTestServer.ENGINE_B.storeAsset(meta, null);
		Job bind = TwoVenueTestServer.ENGINE_B.jobs().invokeOperation(
			"v/ops/covia/write",
			Maps.of(Fields.PATH, "v/ops/remotetest/pin-named",
				Strings.intern("value"), convex.core.util.JSON.parse(meta)),
			TwoVenueTestServer.ENGINE_B.venueContext());
		bind.awaitResult(10_000);
		assertEquals(Status.COMPLETE, bind.getStatus(), "catalog binding write must succeed");

		AString callerDid = Strings.create("did:key:zPinNamedAdopter");
		RequestContext caller = RequestContext.of(callerDid);
		String namedRef = "did:web:localhost%3A" + TwoVenueTestServer.PORT_B
			+ "/v/ops/remotetest/pin-named";

		Job pin = TwoVenueTestServer.ENGINE_A.jobs().invokeOperation(
			"v/ops/asset/pin", Maps.of(Fields.PATH, namedRef), caller);
		pin.awaitResult(10_000);
		assertEquals(Status.COMPLETE, pin.getStatus());

		assertNotNull(TwoVenueTestServer.ENGINE_A.getAssetRecord(id, callerDid),
			"Pin must adopt the definition the name resolved to, keyed by its hash");
	}

	// ============== Misbehaving-venue stub ==============

	/**
	 * A venue that answers every asset request with the WRONG metadata.
	 * Exists only to exercise the verification path — an honest
	 * {@code VenueServer} can't be made to lie about a hash.
	 */
	private static class TamperingVenue extends Venue {
		@Override public Asset getAsset(Hash assetID) { return Asset.forString(WRONG_META); }
		@Override public DID getDID() { throw new UnsupportedOperationException(); }
		@Override public java.util.concurrent.CompletableFuture<Job> invoke(Hash assetID, ACell input) { throw new UnsupportedOperationException(); }
		@Override public java.util.concurrent.CompletableFuture<Job> getJob(Blob jobId) { throw new UnsupportedOperationException(); }
		@Override public java.util.concurrent.CompletableFuture<AMap<AString, ACell>> getJobStatus(Blob jobId) { throw new UnsupportedOperationException(); }
		@Override public java.util.concurrent.CompletableFuture<ACell> awaitJobResult(Blob jobId) { throw new UnsupportedOperationException(); }
		@Override protected AContent getAssetContent(Hash id) { throw new UnsupportedOperationException(); }
		@Override public Hash registerAsset(AString metadata) { throw new UnsupportedOperationException(); }
		@Override public long getAssetCount() { throw new UnsupportedOperationException(); }
		@Override public List<Hash> listAssetIDs(long offset, long limit) { throw new UnsupportedOperationException(); }
		@Override public Hash putAssetContent(Asset asset, InputStream content) { throw new UnsupportedOperationException(); }
		@Override public AMap<AString, ACell> cancelJob(Blob jobId) { throw new UnsupportedOperationException(); }
		@Override public AMap<AString, ACell> pauseJob(Blob jobId) { throw new UnsupportedOperationException(); }
		@Override public AMap<AString, ACell> resumeJob(Blob jobId) { throw new UnsupportedOperationException(); }
		@Override public boolean deleteJob(Blob jobId) { throw new UnsupportedOperationException(); }
		@Override public List<Blob> listJobs() { throw new UnsupportedOperationException(); }
	}
}
