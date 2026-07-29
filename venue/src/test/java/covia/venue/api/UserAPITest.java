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
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.crypto.util.Multikey;
import convex.core.crypto.AKeyPair;
import convex.core.util.JSON;
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
		AString userKey = Strings.create("did:key:"
			+ Multikey.encodePublicKey(AKeyPair.generate().getAccountKey()));
		// Provision through the public user-management seam. Resolution must use
		// the authoritative :user-data account plus its venue-owned authenticator row.
		ACell created = engine.jobs().invokeInternal("v/ops/user/create",
			Maps.of("username", ALICE,
				Fields.AUTHENTICATION_KEYS, Vectors.of(userKey)),
			engine.venueContext()).get(10, TimeUnit.SECONDS);
		String userDID = RT.ensureString(RT.getIn(created, Fields.DID)).toString();
		assertEquals("did:web:test.covia.example:u:alice", userDID);
		assertEquals(Strings.create(userDID),
			engine.getAuth().getUser(ALICE).get(Fields.DID));

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
		AMap<AString, ACell> doc = RT.ensureMap(JSON.parse(resp.body()));
		assertNotNull(doc);

		AString did = Strings.create(userDID);
		AString venueDID = engine.getDIDString();
		AString keyID = Strings.create(userDID + "#venue-key");
		assertEquals(did, doc.get(Strings.create("id")));
		assertEquals(venueDID, doc.get(Strings.create("controller")));
		assertEquals(Strings.create("https://www.w3.org/ns/did/v1"),
			doc.get(Strings.create("@context")));

		AVector<ACell> methods = RT.ensureVector(doc.get(Strings.create("verificationMethod")));
		assertEquals(2L, methods.count(),
			"managed users expose the venue key and active user-held keys");
		AMap<AString, ACell> method = RT.ensureMap(methods.get(0));
		assertEquals(keyID, method.get(Strings.create("id")));
		assertEquals(venueDID, method.get(Strings.create("controller")));
		assertEquals(Multikey.encodePublicKey(engine.getAccountKey()),
			method.get(Strings.create("publicKeyMultibase")),
			"the DID document must expose the venue key, not a minted user key");
		assertEquals(keyID, RT.ensureVector(doc.get(Strings.create("authentication"))).get(0));
		AMap<AString, ACell> userMethod = RT.ensureMap(methods.get(1));
		AString multikey = Strings.create(userKey.toString().substring("did:key:".length()));
		assertEquals(Strings.create(userDID + "#" + multikey),
			userMethod.get(Strings.create("id")));
		assertEquals(Strings.create(userDID), userMethod.get(Strings.create("controller")));
		assertEquals(multikey, userMethod.get(Strings.create("publicKeyMultibase")));
		assertEquals(keyID, RT.ensureVector(doc.get(Strings.create("assertionMethod"))).get(0));
	}

	@Test
	void externalOrOAuthRecordsCannotClaimTheLocalDidWebRoute() throws Exception {
		AString alias = Strings.create("oauth_alias");
		AString external = Strings.create("did:web:identity.example:u:mallory");
		engine.getAuth().putUser(alias, Maps.of(Fields.DID, external));
		engine.getVenueState().users().ensure(external);

		HttpResponse<String> resp = HttpClient.newHttpClient().send(
			HttpRequest.newBuilder()
				.uri(new URI("http://localhost:" + port + "/u/oauth_alias/did.json"))
				.GET().timeout(Duration.ofSeconds(10)).build(),
			HttpResponse.BodyHandlers.ofString());

		assertEquals(404, resp.statusCode(),
			"an external DID and an OAuth alias must not publish a local managed DID document");
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
