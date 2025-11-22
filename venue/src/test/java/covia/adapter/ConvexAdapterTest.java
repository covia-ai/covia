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

	@Test
	void queryPublicPeerEndpoint() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("convex:query", Maps.of(
				Fields.PEER, "peer.convex.live:18888",
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
				Fields.PEER, "peer.convex.live:18888",
				Fields.SOURCE, "(def foo 1)",
				Fields.ADDRESS, "#13",
				Strings.create("seed"), "0xB5232CF710Aaa222F2C898105d06d58283f91173D668C313b72dD90f0175E622"));

		assertNotNull(job, "Job response should not be null");
		assertEquals(Status.COMPLETE, job.getStatus(), "Transaction should fail until implemented");
		
		// should fail because test key not valid for account #13
		assertEquals("SIGNATURE",RT.getIn(job.getOutput(), "errorCode").toString());
	}
}

