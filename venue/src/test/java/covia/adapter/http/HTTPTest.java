package covia.adapter.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.client.Covia;
import covia.venue.TestServer;

public class HTTPTest {
	@Test public void testHTTPGet() throws InterruptedException, ExecutionException, TimeoutException {
		Covia covia=TestServer.COVIA;
		
		Job result=covia.invokeSync("http:get", Maps.of("url",TestServer.BASE_URL));
		assertTrue(result.isComplete());
		
		assertEquals(200,RT.ensureLong(RT.getIn(result.getOutput(),"status")).longValue());
	}
}
