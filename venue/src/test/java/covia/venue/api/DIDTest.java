package covia.venue.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.core5.http.Method;
import convex.java.HTTPClients;
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
        SimpleHttpRequest req = SimpleHttpRequest.create(Method.GET, 
            new URI("http://localhost:" + PORT + "/.well-known/did.json"));
        
        CompletableFuture<SimpleHttpResponse> future = HTTPClients.execute(req);
        SimpleHttpResponse resp = future.get(10000, TimeUnit.MILLISECONDS);
        
        assertEquals(200, resp.getCode(), "Expected 200 OK response");
        assertNotNull(resp.getBody(), "Response body should not be null");
        
        // Verify the response contains expected DID document fields
        String body = resp.getBodyText();
        assertTrue(body.contains("\"@context\""), "Should contain @context field");
        assertTrue(body.contains("\"id\""), "Should contain id field");
        assertTrue(body.contains("\"verificationMethod\""), "Should contain verificationMethod field");
        assertTrue(body.contains("\"authentication\""), "Should contain authentication field");
        assertTrue(body.contains("\"assertionMethod\""), "Should contain assertionMethod field");
        assertTrue(body.contains("\"service\""), "Should contain service field");
        
        // Note: Content-Type header verification would require additional HTTP client setup
        // For now, we verify the response structure which is the most important aspect
    }
    
    @Test
    void testDIDDocumentStructure() throws Exception {
        SimpleHttpRequest req = SimpleHttpRequest.create(Method.GET, 
            new URI("http://localhost:" + PORT + "/.well-known/did.json"));
        
        CompletableFuture<SimpleHttpResponse> future = HTTPClients.execute(req);
        SimpleHttpResponse resp = future.get(10000, TimeUnit.MILLISECONDS);
        
        assertEquals(200, resp.getCode(), "Expected 200 OK response");
        
        // Parse the response to verify structure
        String body = resp.getBodyText();
        
        // Basic validation that it's a valid JSON structure
        assertTrue(body.startsWith("{"), "Response should start with {");
        assertTrue(body.endsWith("}"), "Response should end with }");
        
        // Check for required DID document fields
        assertTrue(body.contains("\"@context\":\"https://www.w3.org/ns/did/v1\""), 
            "Should contain correct @context");
        assertTrue(body.contains("\"type\":\"Ed25519VerificationKey2020\""), 
            "Should contain Ed25519VerificationKey2020 type");
        assertTrue(body.contains("\"type\":\"CoviaVenue\""), 
            "Should contain CoviaVenue service type");
        
        // Verify DID format
        assertTrue(body.contains("\"did:web:"), "Should contain did:web format");
    }
}
