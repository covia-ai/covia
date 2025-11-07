package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.lang.RT;

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
	}
}

