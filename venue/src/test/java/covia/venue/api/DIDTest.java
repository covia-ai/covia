package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import covia.venue.TestServer;

@TestInstance(Lifecycle.PER_CLASS)
public class DIDTest {
    
    static final int PORT = TestServer.PORT;
    
    @BeforeAll
    public void setupServer() throws Exception {
        // The TestServer is already running from the static initializer
        // We just need to ensure it's accessible
        assertNotNull(TestServer.SERVER, "Test server should be running");
    }
    
	@Test
	void testDIDEndpoint() throws Exception {
		// Test the DID endpoint using HTTP client
		HttpClient client = HttpClient.newBuilder().build();
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI("http://localhost:" + PORT + "/.well-known/did.json"))
			.GET()
			.timeout(Duration.ofSeconds(10))
			.build();
		
		CompletableFuture<HttpResponse<String>> future = client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		HttpResponse<String> resp = future.get(10000, TimeUnit.MILLISECONDS);
		
		assertEquals(200, resp.statusCode(), "Expected 200 OK response");
		assertNotNull(resp.body(), "Response body should not be null");
		
		// Verify the response contains expected DID document fields
		String body = resp.body();
		assertTrue(body.contains("\"@context\""), "Should contain @context field");
		assertTrue(body.contains("\"id\""), "Should contain id field");

		
		// Note: Content-Type header verification would require additional HTTP client setup
		// For now, we verify the response structure which is the most important aspect
	}
    
	@Test
	void testDIDDocumentStructure() throws Exception {
		HttpClient client = HttpClient.newBuilder().build();
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI("http://localhost:" + PORT + "/.well-known/did.json"))
			.GET()
			.timeout(Duration.ofSeconds(10))
			.build();
		
		CompletableFuture<HttpResponse<String>> future = client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		HttpResponse<String> resp = future.get(10000, TimeUnit.MILLISECONDS);
		
		assertEquals(200, resp.statusCode(), "Expected 200 OK response");
		
		// Parse the response to verify structure
		String body = resp.body();
		
		// Basic validation that it's a valid JSON structure
		assertTrue(body.startsWith("{"), "Response should start with {");
		assertTrue(body.endsWith("}"), "Response should end with }");
		
		// Check for required DID document fields
		assertTrue(body.contains("\"@context\":\"https://www.w3.org/ns/did/v1\""), 
			"Should contain correct @context");

		// Verify DID format
		assertTrue(body.contains("\"did:"), "Should contain did: format");
	}
}
