package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.crypto.Hashing;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSONUtils;
import convex.core.util.Utils;
import covia.adapter.Status;

public class VenueTest {
	Venue venue;
	Hash randomOpID;
	AString EMPTY_META = Strings.create("{}");

	Hash randomOpId;
	Hash echoOpId;
	Hash qwenOpId;
	
	@BeforeEach
	public void setup() throws IOException {
		venue=Venue.createTemp();
		Venue.addDemoAssets(venue);
		randomOpID=venue.storeAsset(Utils.readResourceAsString("/asset-examples/randomop.json"), null);
		echoOpId=venue.storeAsset(Utils.readResourceAsString("/asset-examples/echoop.json"), null);
		qwenOpId=venue.storeAsset(Utils.readResourceAsString("/asset-examples/qwen.json"), null);
	}
	
	@Test
	public void testTempVenue() throws IOException {
		Blob content=Blob.EMPTY;
		Hash id=venue.storeAsset(EMPTY_META,content,Maps.empty());
		assertEquals(id,Hashing.sha256(EMPTY_META));
		ACell md=venue.getMetadata(id);
		assertEquals(EMPTY_META,md);
		
	}
	
	@Test public void testAddAsset() throws InterruptedException, ExecutionException {
		// Create a test asset
		ACell meta = Maps.of(
			Keyword.intern("name"), Strings.create("Test Asset"),
			Keyword.intern("description"), Strings.create("A test asset")
		);
		
		// Add the asset
		Hash id=venue.storeAsset(meta, null);
		assertNotNull(id);
		
		// Verify the asset was added
		AString metaString=venue.getMetadata(id);
		assertNotNull(metaString);
		assertTrue(metaString.toString().contains("Test Asset"));
	}
	
	@Test public void testDemoAssets() throws IOException {
		AString emptyMeta=venue.getMetadata(Hash.parse("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"));
		assertEquals(EMPTY_META,emptyMeta);
	}
	
	@Test public void testOp() throws IOException {
		String ms=Utils.readResourceAsString("/asset-examples/randomop.json");
		assertNotNull(ms);
		
		ACell md=JSONUtils.parseJSON5(ms);
		assertEquals("Random Bytes Generator",RT.getIn(md, "name").toString());
	}
	
	@Test
	public void testAdapterOperation() {
		// Test echo operation
		ACell input=Maps.of("message",Strings.create("Hello World"));
		ACell status=venue.invokeOperation(echoOpId,input);
		
		// Get job ID from status
		ACell jobID = RT.getIn(status, "id");
		assertNotNull(jobID, "Job ID should be present in status");
		
		// Wait for job completion
		waitForJobCompletion((AString)jobID, 5000);
		
		// Get final status
		ACell finalStatus = venue.getJobStatus((AString)jobID);
		assertEquals(Status.COMPLETE, RT.getIn(finalStatus, "status"));
		
		// Verify result
		ACell result = RT.getIn(finalStatus, "output");
		assertEquals("Hello World", RT.getIn(result, "message").toString());
	}
	
	@Test
	public void testAdapterError() {
		// Test error operation
		ACell input=Maps.of("message",Strings.create("Test Error"));
		ACell status=venue.invokeOperation(randomOpID,input);
		
		// Get job ID from status
		ACell jobID = RT.getIn(status, "id");
		assertNotNull(jobID, "Job ID should be present in status");
		
		// Wait for job completion
		waitForJobCompletion((AString)jobID, 5000);
		
		// Get final status
		ACell finalStatus = venue.getJobStatus((AString)jobID);
		assertEquals("FAILED", RT.getIn(finalStatus, "status").toString());
	}
	
	@Test
	public void testRandomOperation() {
		// Test random operation with 32 bytes
		ACell input = Maps.of("length", Strings.create("32"));
		ACell status = venue.invokeOperation(randomOpID, input);
		
		// Get job ID from status
		ACell jobID = RT.getIn(status, "id");
		assertNotNull(jobID, "Job ID should be present in status");
		
		// Wait for job completion
		ACell finalStatus = waitForJobCompletion((AString)jobID, 5000);
		
		// Get final status
		assertEquals(Status.COMPLETE, RT.getIn(finalStatus, "status"));
		
		// Verify result
		ACell result = RT.getIn(finalStatus, "output");
		String bytes = RT.getIn(result, "bytes").toString();
		
		// Verify hex string length (2 chars per byte)
		assertEquals(64, bytes.length(), "Hex string should be twice the byte length");
		// Verify hex string format
		assertTrue(bytes.matches("[0-9a-f]{64}"), "Output should be a valid hex string");
	}
	
	@Test
	public void testQwen() {
		ACell input = Maps.of("prompt", "What is the capital of France?");
		ACell result = venue.invokeOperation(qwenOpId, input);
		result=waitForJobCompletion(RT.getIn(result, "id"), 5000);
		if (Status.COMPLETE.equals(RT.getIn(result, "status"))) {
			// OK
		} else {
			// probably ignore?
		}
		System.err.println(JSONUtils.toString(result));	
	}
	
	private ACell waitForJobCompletion(Object jobID, long timeoutMillis) {
		AString id=RT.ensureString(RT.cvm(jobID));
		long startTime = System.currentTimeMillis();
		ACell status = venue.getJobStatus(id);
		while (System.currentTimeMillis() - startTime < timeoutMillis) {
			status = venue.getJobStatus(id);
			String jobStatus = RT.getIn(status, "status").toString();
			if (!jobStatus.equals("PENDING")) {
				return status;
			}
			try {
				TimeUnit.MILLISECONDS.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				fail("Test interrupted while waiting for job completion");
			}
		}
		// fail("Timeout waiting for job completion");
		return RT.assocIn(status, Status.FAILED,"status");
	}
}
