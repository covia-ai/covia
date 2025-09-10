package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.core5.http.Method;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.java.HTTPClients;
import covia.venue.TestServer;

/**
 * Test class for A2A functionality
 */
@TestInstance(Lifecycle.PER_CLASS)
public class A2ATest {
    
    static final int PORT = TestServer.PORT;
    static final String BASE_URL = TestServer.BASE_URL;
    
    @BeforeAll
    public void setupServer() throws Exception {
        // The TestServer is already running from the static initializer
        // We just need to ensure it's accessible
        assertNotNull(TestServer.SERVER, "Test server should be running");
    }
    
    @Test
    public void testA2AAgentCardEndpoint() throws URISyntaxException, InterruptedException, ExecutionException, TimeoutException {
        // Test the A2A agent card endpoint using HTTP client
        SimpleHttpRequest req = SimpleHttpRequest.create(Method.GET, 
            new URI(BASE_URL + "/.well-known/agent-card.json"));
        
        CompletableFuture<SimpleHttpResponse> future = HTTPClients.execute(req);
        SimpleHttpResponse resp = future.get(10000, TimeUnit.MILLISECONDS);
        
        assertEquals(200, resp.getCode(), "Expected 200 OK response");
        assertNotNull(resp.getBody(), "Response body should not be null");
        
        // Verify the response contains expected A2A agent card fields
        String body = resp.getBodyText();
        assertTrue(body.contains("\"agentProvider\""), "Should contain agentProvider field");
        assertTrue(body.contains("\"agentCapabilities\""), "Should contain agentCapabilities field");
        assertTrue(body.contains("\"agentSkills\""), "Should contain agentSkills field");
        assertTrue(body.contains("\"agentInterfaces\""), "Should contain agentInterfaces field");
        assertTrue(body.contains("\"preferredTransport\""), "Should contain preferredTransport field");
    }
    
    @Test
    public void testA2AAgentCardStructure() throws URISyntaxException, InterruptedException, ExecutionException, TimeoutException {
        // Test the A2A agent card endpoint and verify JSON structure
        SimpleHttpRequest req = SimpleHttpRequest.create(Method.GET, 
            new URI(BASE_URL + "/.well-known/agent-card.json"));
        
        CompletableFuture<SimpleHttpResponse> future = HTTPClients.execute(req);
        SimpleHttpResponse resp = future.get(10000, TimeUnit.MILLISECONDS);
        
        assertEquals(200, resp.getCode(), "Expected 200 OK response");
        
        String body = resp.getBodyText();
        
        // Verify it's valid JSON by checking for proper structure
        assertTrue(body.startsWith("{"), "Should start with JSON object");
        assertTrue(body.endsWith("}"), "Should end with JSON object");
        
        // Verify required A2A fields are present
        assertTrue(body.contains("\"agentProvider\""), "Should contain agentProvider");
        assertTrue(body.contains("\"agentCapabilities\""), "Should contain agentCapabilities");
        assertTrue(body.contains("\"agentSkills\""), "Should contain agentSkills");
        assertTrue(body.contains("\"agentInterfaces\""), "Should contain agentInterfaces");
        assertTrue(body.contains("\"preferredTransport\""), "Should contain preferredTransport");
        
        // Verify transport information
        assertTrue(body.contains("\"http+json\""), "Should support http+json transport");
        assertTrue(body.contains("\"json-rpc\""), "Should support json-rpc transport");
    }
}
