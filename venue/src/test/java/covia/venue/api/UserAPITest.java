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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.Config;
import covia.venue.server.VenueServer;

@TestInstance(Lifecycle.PER_CLASS)
public class UserAPITest {

	static final AString ALICE = Strings.intern("alice");
	static final AString BOB = Strings.intern("bob");
	Engine engine;
	VenueServer server;
	int port;

	@BeforeAll
	public void setup() throws Exception {
		server = VenueServer.launch(Maps.of(
			Strings.create("port"), 0,
			Config.HOSTNAME, Strings.create("test.covia.example")));
		engine = server.getEngine();
		port = server.port();
	}

	@AfterAll
	public void close() {
		if (server != null) server.close();
	}

	@Test
	void testUserDIDDocument() throws Exception {
		// Provision through the public user-management seam. Resolution must use
		// the resulting authoritative :user-data record, not require an OAuth row.
		ACell created = engine.jobs().invokeInternal("v/ops/user/create",
			Maps.of("username", ALICE), engine.venueContext()).get(10, TimeUnit.SECONDS);
		String userDID = RT.ensureString(RT.getIn(created, Fields.DID)).toString();
		assertEquals("did:web:test.covia.example:u:alice", userDID);
		assertEquals(null, engine.getAuth().getUser(ALICE),
			"DID resolution must not depend on the OAuth login directory");

		// Fetch the DID document via HTTP
		HttpClient client = HttpClient.newBuilder().build();
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI("http://localhost:" + port + "/u/alice/did.json"))
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
			.uri(new URI("http://localhost:" + port + "/u/unknown_user_xyz/did.json"))
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
