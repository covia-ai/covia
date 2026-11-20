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

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
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
		HttpClient client = covia.venue.TestHTTP.CLIENT;
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
		HttpClient client = covia.venue.TestHTTP.CLIENT;
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
		
		// Multikey methods come with the context that defines them, and every
		// service has an id, unique in the document (DID Core §5.4)
		AMap<AString, ACell> doc = RT.ensureMap(JSON.parse(body));
		assertEquals(CoviaAPI.MULTIKEY_DID_CONTEXT, doc.get(Strings.create("@context")));
		AVector<ACell> services = RT.ensureVector(doc.get(Strings.create("service")));
		java.util.Set<ACell> serviceIds = new java.util.HashSet<>();
		for (long i = 0; i < services.count(); i++) {
			ACell id = RT.getIn(services.get(i), "id");
			assertNotNull(id, "every service has an id");
			assertTrue(serviceIds.add(id), "service ids are unique");
		}
		assertEquals(1, serviceIds.size(), "the venue's API service");

		// Verify DID format
		assertTrue(body.contains("\"did:"), "Should contain did: format");
	}
}
