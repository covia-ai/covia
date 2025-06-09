package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
		venueServer.start(8088);
	}
	
	@Test public void testAddAsset() throws InterruptedException, ExecutionException {
		Covia covia=Covia.create(URI.create("http://localhost:8088"));
		Future<Result> r=covia.addAsset("{}");
		
		Result result=r.get();
		assertFalse(result.isError(),()->"Bad Result: "+result);
		assertEquals("0x44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",result.getValue().toString());
		
		Future<String> r2=covia.getMeta("0x44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
		assertEquals("{}",r2.get());
	}
	
	@AfterAll
	public void shutDown() {
		venueServer.close();
	}
}
