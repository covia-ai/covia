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

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.util.JSONUtils;
import convex.java.HTTPClients;
import covia.api.Fields;
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
        
        // Parse the JSON response using Convex JSONUtils
        String body = resp.getBodyText();
        ACell parsedResponse = JSONUtils.parse(body);
        assertNotNull(parsedResponse, "Response should be valid JSON");
        
        // Verify it's a map (JSON object)
        assertTrue(parsedResponse instanceof AMap, "Response should be a JSON object");
        @SuppressWarnings("unchecked")
        AMap<AString, ACell> agentCard = (AMap<AString, ACell>) parsedResponse;
        
        // Verify the response contains expected A2A agent card fields using Fields constants
        assertTrue(agentCard.containsKey(Fields.AGENT_PROVIDER), "Should contain agentProvider field");
        assertTrue(agentCard.containsKey(Fields.AGENT_CAPABILITIES), "Should contain agentCapabilities field");
        assertTrue(agentCard.containsKey(Fields.AGENT_SKILLS), "Should contain agentSkills field");
        assertTrue(agentCard.containsKey(Fields.AGENT_INTERFACES), "Should contain agentInterfaces field");
        assertTrue(agentCard.containsKey(Fields.PREFERRED_TRANSPORT), "Should contain preferredTransport field");
    }
    
    @Test
    public void testA2AAgentCardStructure() throws URISyntaxException, InterruptedException, ExecutionException, TimeoutException {
        // Test the A2A agent card endpoint and verify JSON structure
        SimpleHttpRequest req = SimpleHttpRequest.create(Method.GET, 
            new URI(BASE_URL + "/.well-known/agent-card.json"));
        
        CompletableFuture<SimpleHttpResponse> future = HTTPClients.execute(req);
        SimpleHttpResponse resp = future.get(10000, TimeUnit.MILLISECONDS);
        
        assertEquals(200, resp.getCode(), "Expected 200 OK response");
        
        // Parse the JSON response using Convex JSONUtils
        String body = resp.getBodyText();
        ACell parsedResponse = JSONUtils.parse(body);
        assertNotNull(parsedResponse, "Response should be valid JSON");
        
        // Verify it's a map (JSON object)
        assertTrue(parsedResponse instanceof AMap, "Response should be a JSON object");
        @SuppressWarnings("unchecked")
        AMap<AString, ACell> agentCard = (AMap<AString, ACell>) parsedResponse;
        
        // Verify required A2A fields are present using Fields constants
        assertTrue(agentCard.containsKey(Fields.AGENT_PROVIDER), "Should contain agentProvider");
        assertTrue(agentCard.containsKey(Fields.AGENT_CAPABILITIES), "Should contain agentCapabilities");
        assertTrue(agentCard.containsKey(Fields.AGENT_SKILLS), "Should contain agentSkills");
        assertTrue(agentCard.containsKey(Fields.AGENT_INTERFACES), "Should contain agentInterfaces");
        assertTrue(agentCard.containsKey(Fields.PREFERRED_TRANSPORT), "Should contain preferredTransport");
        
        // Verify agent provider structure
        ACell agentProvider = agentCard.get(Fields.AGENT_PROVIDER);
        assertNotNull(agentProvider, "agentProvider should not be null");
        assertTrue(agentProvider instanceof AMap, "agentProvider should be an object");
        @SuppressWarnings("unchecked")
        AMap<AString, ACell> providerMap = (AMap<AString, ACell>) agentProvider;
        assertTrue(providerMap.containsKey(Fields.NAME), "agentProvider should have name");
        assertTrue(providerMap.containsKey(Fields.TITLE), "agentProvider should have title");
        
        // Verify agent capabilities structure
        ACell agentCapabilities = agentCard.get(Fields.AGENT_CAPABILITIES);
        assertNotNull(agentCapabilities, "agentCapabilities should not be null");
        assertTrue(agentCapabilities instanceof AMap, "agentCapabilities should be an object");
        
        // Verify agent interfaces structure
        ACell agentInterfaces = agentCard.get(Fields.AGENT_INTERFACES);
        assertNotNull(agentInterfaces, "agentInterfaces should not be null");
        assertTrue(agentInterfaces instanceof AVector, "agentInterfaces should be an array");
        @SuppressWarnings("unchecked")
        AVector<ACell> interfacesVector = (AVector<ACell>) agentInterfaces;
        assertTrue(interfacesVector.count() > 0, "agentInterfaces should not be empty");
        
        // Verify transport information in interfaces
        boolean foundHttpJson = false;
        boolean foundJsonRpc = false;
        for (long i = 0; i < interfacesVector.count(); i++) {
            ACell interfaceObj = interfacesVector.get(i);
            if (interfaceObj instanceof AMap) {
                @SuppressWarnings("unchecked")
                AMap<AString, ACell> interfaceMap = (AMap<AString, ACell>) interfaceObj;
                ACell transport = interfaceMap.get(Fields.TRANSPORT);
                if (transport != null) {
                    String transportStr = transport.toString();
                    if ("http+json".equals(transportStr)) foundHttpJson = true;
                    if ("json-rpc".equals(transportStr)) foundJsonRpc = true;
                }
            }
        }
        assertTrue(foundHttpJson, "Should support http+json transport");
        assertTrue(foundJsonRpc, "Should support json-rpc transport");
    }
}
