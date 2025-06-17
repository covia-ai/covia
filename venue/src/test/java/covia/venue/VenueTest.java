package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.crypto.Hashing;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSONUtils;
import convex.core.util.Utils;
import covia.adapter.TestAdapter;

@TestInstance(Lifecycle.PER_CLASS)
public class VenueTest {

	public Venue v;
	
	AString EMPTY_META=Strings.create("{}");
	
	@BeforeAll
	public void setupVenue() throws IOException {
		v=new Venue();
		Venue.addDemoAssets(v);
		
		// Register test adapter
		v.registerAdapter(new TestAdapter());
	}

	
	@Test
	public void testTempVenue() throws IOException {
		Blob content=Blob.EMPTY;
		Hash id=v.storeAsset(EMPTY_META,content);
		assertEquals(id,Hashing.sha256(EMPTY_META));
		ACell md=v.getMetadata(id);
		assertEquals(EMPTY_META,md);
		
	}
	
	@Test public void testDemoAssets() throws IOException {
		AString emptyMeta=v.getMetadata(Hash.parse("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"));
		assertEquals(EMPTY_META,emptyMeta);
	}
	
	@Test public void testOp() throws IOException {
		String ms=Utils.readResourceAsString("/asset-examples/randomop.json");
		assertNotNull(ms);
		
		ACell md=JSONUtils.parse(ms);
		assertEquals("Random numbers",RT.getIn(md, "name").toString());
	}
	
	/**
	 * Poll for job completion with timeout
	 * @param jobID The job ID to poll
	 * @param timeoutMillis Maximum time to wait in milliseconds
	 * @return The final job status
	 * @throws InterruptedException if polling is interrupted
	 */
	private ACell waitForJobCompletion(AString jobID, long timeoutMillis) throws InterruptedException {
		long startTime = System.currentTimeMillis();
		long pollInterval = 100; // Poll every 100ms
		
		while (true) {
			ACell status = v.getJobStatus(jobID);
			String jobStatus = RT.getIn(status, "status").toString();
			
			if (!"PENDING".equals(jobStatus)) {
				return status;
			}
			
			if (System.currentTimeMillis() - startTime > timeoutMillis) {
				throw new RuntimeException("Job did not complete within " + timeoutMillis + "ms");
			}
			
			Thread.sleep(pollInterval);
		}
	}
	
	@Test public void testAdapterOperation() throws IOException, InterruptedException {
		// Create test operation metadata
		String testOpMeta = """
			{
				"name": "Test Echo",
				"operation": {
					"adapter": "test:echo",
					"input": {
						"type": "object",
						"properties": {
							"message": {
								"type": "string"
							}
						}
					}
				}
			}
			""";
		
		// Store the operation
		Hash opID = v.storeAsset(Strings.create(testOpMeta), null);
		
		// Create test input
		ACell input = Maps.of("message", Strings.create("hello"));
		
		// Invoke the operation and get initial job status
		ACell initialStatus = v.invokeOperation(Strings.create(opID.toHexString()), input);
		AString jobID = RT.ensureString(RT.getIn(initialStatus, "id"));
		
		// Wait for completion
		ACell result = waitForJobCompletion(jobID, 5000); // 5 second timeout
		
		// Verify the result
		assertNotNull(result);
		assertEquals("COMPLETED", RT.getIn(result, "status").toString());
		assertEquals("hello", RT.getIn(result, "result", "message").toString());
	}
	
	@Test public void testAdapterError() throws IOException, InterruptedException {
		// Create test operation metadata
		String testOpMeta = """
			{
				"name": "Test Error",
				"operation": {
					"adapter": "test:error",
					"input": {
						"type": "object",
						"properties": {
							"message": {
								"type": "string"
							}
						}
					}
				}
			}
			""";
		
		// Store the operation
		Hash opID = v.storeAsset(Strings.create(testOpMeta), null);
		
		// Create test input
		ACell input = Maps.of("message", Strings.create("test error"));
		
		// Invoke the operation and get initial job status
		ACell initialStatus = v.invokeOperation(Strings.create(opID.toHexString()), input);
		AString jobID = RT.ensureString(RT.getIn(initialStatus, "id"));
		
		// Wait for completion
		ACell result = waitForJobCompletion(jobID, 5000); // 5 second timeout
		
		// Verify the error result
		assertNotNull(result);
		assertEquals("FAILED", RT.getIn(result, "status").toString());
		assertEquals("test error", RT.getIn(result, "error").toString());
	}
}
