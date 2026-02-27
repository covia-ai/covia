package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.TestServer;

@TestInstance(Lifecycle.PER_CLASS)
public class UserAPITest {

	static final int PORT = TestServer.PORT;
	static final AString ALICE = Strings.intern("alice");
	static final AString BOB = Strings.intern("bob");
	Engine engine;

	@BeforeAll
	public void setup() throws Exception {
		assertNotNull(TestServer.SERVER, "Test server should be running");
		engine = TestServer.ENGINE;
	}

	@Test
	void testUserDIDDocument() throws Exception {
		// Add a user to the database
		String userDID = "did:web:example.com:u:alice";
		engine.getAuth().putUser(ALICE, Maps.of(
			Fields.DID, Strings.create(userDID)
		));

		// Fetch the DID document via HTTP
		HttpClient client = HttpClient.newBuilder().build();
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI("http://localhost:" + PORT + "/u/alice/did.json"))
			.GET()
			.timeout(Duration.ofSeconds(10))
			.build();

		CompletableFuture<HttpResponse<String>> future = client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		HttpResponse<String> resp = future.get(10000, TimeUnit.MILLISECONDS);

		assertEquals(200, resp.statusCode(), "Expected 200 OK response");
		String body = resp.body();
		assertNotNull(body);

		// Verify the DID document contains the user's DID
		assertTrue(body.contains(userDID), "Should contain user DID: " + body);

		// Verify the venue is the controller
		String venueDID = engine.getDIDString().toString();
		assertTrue(body.contains(venueDID), "Should contain venue DID as controller: " + body);

		// Verify standard DID document fields
		assertTrue(body.contains("\"@context\""), "Should contain @context");
		assertTrue(body.contains("\"verificationMethod\""), "Should contain verificationMethod");
	}

	@Test
	void testUnknownUserReturns404() throws Exception {
		HttpClient client = HttpClient.newBuilder().build();
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI("http://localhost:" + PORT + "/u/unknown_user_xyz/did.json"))
			.GET()
			.timeout(Duration.ofSeconds(10))
			.build();

		CompletableFuture<HttpResponse<String>> future = client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		HttpResponse<String> resp = future.get(10000, TimeUnit.MILLISECONDS);

		assertEquals(404, resp.statusCode(), "Expected 404 for unknown user");
	}

	@Test
	void testUserGetPut() throws Exception {
		// Verify user doesn't exist yet
		AMap<AString, ACell> record = engine.getAuth().getUser(BOB);
		assertEquals(null, record);

		// Add a user
		engine.getAuth().putUser(BOB, Maps.of(
			Fields.DID, Strings.create("did:key:z6Mktest123")
		));

		// Verify user exists
		record = engine.getAuth().getUser(BOB);
		assertNotNull(record);
		assertEquals("did:key:z6Mktest123", record.get(Fields.DID).toString());

		// Verify updated timestamp was added
		assertNotNull(record.get(Fields.UPDATED), "Should have updated timestamp");
	}
}
