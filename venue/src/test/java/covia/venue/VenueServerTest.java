package covia.venue;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.Result;
import covia.api.Covia;

@TestInstance(Lifecycle.PER_CLASS)
public class VenueServerTest {
	
	private VenueServer venueServer;

	@BeforeAll
	public void setupServer() {
		venueServer=VenueServer.create(null);
		venueServer.start(8080);
	}
	
	@Test public void testAddAsset() throws InterruptedException, ExecutionException {
		Covia covia=Covia.create(URI.create("http://localhost:8080"));
		Future<Result> r=covia.addAsset("{}");
		
		Result result=r.get();
		assertFalse(result.isError(),()->"Bad Result: "+result);
	}

	
	@AfterAll
	public void shutDown() {
		venueServer.close();
	}
}
