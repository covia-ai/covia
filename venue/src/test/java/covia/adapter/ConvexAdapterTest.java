package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.lang.RT;
import convex.core.data.Strings;

import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.client.VenueHTTP;
import covia.venue.TestServer;

class ConvexAdapterTest {

	static final String PEER = "peer.convex.live:18888";

	@Test
	void queryPublicPeerEndpoint() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("convex:query", Maps.of(
				Fields.PEER, PEER,
                Fields.ADDRESS, "#13",
                Fields.SOURCE, "(* 2 3)"));

		assertNotNull(job, "Job response should not be null");
		assertEquals(Status.COMPLETE, job.getStatus());
		assertEquals("6", RT.getIn(job.getOutput(), "result").toString());
		assertTrue(covia.getDID()!=null);
	}

	@Test
	void transact() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("convex:transact", Maps.of(
				Fields.PEER, PEER,
				Fields.SOURCE, "(def foo 1)",
				Fields.ADDRESS, "#13",
				Strings.create("seed"), "0xB5232CF710Aaa222F2C898105d06d58283f91173D668C313b72dD90f0175E622"));

		assertNotNull(job, "Job response should not be null");
		assertEquals(Status.COMPLETE, job.getStatus(), "Transaction should fail until implemented");

		// should fail because test key not valid for account #13
		assertEquals("SIGNATURE",RT.getIn(job.getOutput(), "errorCode").toString());
	}

	// ===== Query: missing/invalid input =====

	@Test
	void queryMissingSource() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		// No "source" field at all — adapter should fail with a clear message
		Job job = covia.invokeSync("convex:query", Maps.of(
				Fields.PEER, PEER,
				Fields.ADDRESS, "#13"));

		assertNotNull(job);
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains("source"),
				"Error should mention missing source: " + job.getErrorMessage());
	}

	@Test
	void queryEmptySource() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		// Empty string source — Reader.read("") will throw
		Job job = covia.invokeSync("convex:query", Maps.of(
				Fields.PEER, PEER,
				Fields.ADDRESS, "#13",
				Fields.SOURCE, ""));

		assertNotNull(job);
		assertEquals(Status.FAILED, job.getStatus());
	}

	@Test
	void queryMalformedExpression() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		// Unmatched parentheses — Reader.read should fail
		Job job = covia.invokeSync("convex:query", Maps.of(
				Fields.PEER, PEER,
				Fields.ADDRESS, "#13",
				Fields.SOURCE, "(+ 1 2"));

		assertNotNull(job);
		assertEquals(Status.FAILED, job.getStatus());
	}

	// ===== Query: different return types =====

	@Test
	void queryReturnsNumber() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("convex:query", Maps.of(
				Fields.PEER, PEER,
				Fields.ADDRESS, "#13",
				Fields.SOURCE, "(+ 100 200)"));

		assertNotNull(job);
		assertEquals(Status.COMPLETE, job.getStatus());
		assertEquals("300", RT.getIn(job.getOutput(), "result").toString());
	}

	@Test
	void queryReturnsString() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("convex:query", Maps.of(
				Fields.PEER, PEER,
				Fields.ADDRESS, "#13",
				Fields.SOURCE, "(str \"hello\" \" \" \"world\")"));

		assertNotNull(job);
		assertEquals(Status.COMPLETE, job.getStatus());
		// result field uses Convex printed representation (strings are quoted)
		assertEquals("\"hello world\"", RT.getIn(job.getOutput(), "result").toString());
	}

	@Test
	void queryReturnsVector() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("convex:query", Maps.of(
				Fields.PEER, PEER,
				Fields.ADDRESS, "#13",
				Fields.SOURCE, "[1 2 3]"));

		assertNotNull(job);
		assertEquals(Status.COMPLETE, job.getStatus());
		// result field uses Convex printed representation (space-separated)
		assertEquals("[1 2 3]", RT.getIn(job.getOutput(), "result").toString());
	}

	@Test
	void queryReturnsMap() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("convex:query", Maps.of(
				Fields.PEER, PEER,
				Fields.ADDRESS, "#13",
				Fields.SOURCE, "{:a 1 :b 2}"));

		assertNotNull(job);
		assertEquals(Status.COMPLETE, job.getStatus());
		assertNotNull(RT.getIn(job.getOutput(), "result"),
				"Map result should be present");
	}

	@Test
	void queryReturnsNil() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("convex:query", Maps.of(
				Fields.PEER, PEER,
				Fields.ADDRESS, "#13",
				Fields.SOURCE, "nil"));

		assertNotNull(job);
		assertEquals(Status.COMPLETE, job.getStatus());
		// nil query should complete successfully; result may be null/absent
	}

	@Test
	void queryReturnsBoolean() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("convex:query", Maps.of(
				Fields.PEER, PEER,
				Fields.ADDRESS, "#13",
				Fields.SOURCE, "(= 1 1)"));

		assertNotNull(job);
		assertEquals(Status.COMPLETE, job.getStatus());
		assertEquals("true", RT.getIn(job.getOutput(), "result").toString());
	}

	// ===== Query: CVM errors (valid expressions that produce runtime errors) =====

	@Test
	void queryUndefinedSymbol() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		// Reference to an undefined symbol — CVM returns an error result
		Job job = covia.invokeSync("convex:query", Maps.of(
				Fields.PEER, PEER,
				Fields.ADDRESS, "#13",
				Fields.SOURCE, "this-symbol-does-not-exist-xyz"));

		assertNotNull(job);
		assertEquals(Status.COMPLETE, job.getStatus());
		// CVM error comes back as a completed result with errorCode set
		assertNotNull(RT.getIn(job.getOutput(), "errorCode"),
				"CVM error should include errorCode");
	}

	@Test
	void queryDivisionByZero() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("convex:query", Maps.of(
				Fields.PEER, PEER,
				Fields.ADDRESS, "#13",
				Fields.SOURCE, "(/ 1 0)"));

		assertNotNull(job);
		// Division by zero should produce an error result from the CVM
		assertEquals(Status.COMPLETE, job.getStatus());
	}

	// ===== Query: without explicit address =====

	@Test
	void queryWithoutAddress() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		// Omit the address — adapter should still work (uses null address for query)
		Job job = covia.invokeSync("convex:query", Maps.of(
				Fields.PEER, PEER,
				Fields.SOURCE, "(+ 1 1)"));

		assertNotNull(job);
		assertEquals(Status.COMPLETE, job.getStatus());
		assertEquals("2", RT.getIn(job.getOutput(), "result").toString());
	}

	// ===== Transact: missing required parameters =====

	@Test
	void transactMissingSource() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("convex:transact", Maps.of(
				Fields.PEER, PEER,
				Fields.ADDRESS, "#13",
				Strings.create("seed"), "0xB5232CF710Aaa222F2C898105d06d58283f91173D668C313b72dD90f0175E622"));

		assertNotNull(job);
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains("source"),
				"Error should mention missing source: " + job.getErrorMessage());
	}

	@Test
	void transactMissingAddress() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("convex:transact", Maps.of(
				Fields.PEER, PEER,
				Fields.SOURCE, "(def foo 1)",
				Strings.create("seed"), "0xB5232CF710Aaa222F2C898105d06d58283f91173D668C313b72dD90f0175E622"));

		assertNotNull(job);
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains("address"),
				"Error should mention missing address: " + job.getErrorMessage());
	}

	@Test
	void transactMissingSeed() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("convex:transact", Maps.of(
				Fields.PEER, PEER,
				Fields.SOURCE, "(def foo 1)",
				Fields.ADDRESS, "#13"));

		assertNotNull(job);
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().toLowerCase().contains("key") ||
				   job.getErrorMessage().toLowerCase().contains("seed"),
				"Error should mention missing key/seed: " + job.getErrorMessage());
	}

	@Test
	void transactEmptySource() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("convex:transact", Maps.of(
				Fields.PEER, PEER,
				Fields.ADDRESS, "#13",
				Fields.SOURCE, "",
				Strings.create("seed"), "0xB5232CF710Aaa222F2C898105d06d58283f91173D668C313b72dD90f0175E622"));

		assertNotNull(job);
		assertEquals(Status.FAILED, job.getStatus());
	}

	@Test
	void transactMalformedExpression() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("convex:transact", Maps.of(
				Fields.PEER, PEER,
				Fields.ADDRESS, "#13",
				Fields.SOURCE, "(def foo",
				Strings.create("seed"), "0xB5232CF710Aaa222F2C898105d06d58283f91173D668C313b72dD90f0175E622"));

		assertNotNull(job);
		assertEquals(Status.FAILED, job.getStatus());
	}

	// ===== Connection errors =====

	@Test
	void queryInvalidPeerEndpoint() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		// Use a non-existent peer endpoint — should fail to connect
		Job job = covia.invokeSync("convex:query", Maps.of(
				Fields.PEER, "localhost:19999",
				Fields.ADDRESS, "#13",
				Fields.SOURCE, "(+ 1 1)"));

		assertNotNull(job);
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains("connect") ||
				   job.getErrorMessage().contains("Connect") ||
				   job.getErrorMessage().contains("peer"),
				"Error should mention connection failure: " + job.getErrorMessage());
	}

	// ===== Unsupported sub-operation =====

	@Test
	void queryUsesDefaultPeerFromMetadata() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		// Omit the peer field — the query.json asset has a default peer in metadata
		Job job = covia.invokeSync("convex:query", Maps.of(
				Fields.ADDRESS, "#13",
				Fields.SOURCE, "(* 7 6)"));

		assertNotNull(job);
		assertEquals(Status.COMPLETE, job.getStatus());
		assertEquals("42", RT.getIn(job.getOutput(), "result").toString());
	}
}

