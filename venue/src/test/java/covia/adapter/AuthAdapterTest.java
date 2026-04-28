package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

import convex.auth.jwt.JWT;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.Engine;
import covia.venue.SecretStore;
import covia.venue.TestServer;
import covia.venue.User;

class AuthAdapterTest {

	@Test
	void whoami_returnsCallerOnAnonymousPublicAccess() throws Exception {
		VenueHTTP covia = TestServer.COVIA;
		Job job = covia.invokeSync("v/ops/auth/whoami", Maps.of(), 10_000);

		assertEquals(Status.COMPLETE, job.getStatus(), job.getErrorMessage());

		// TestServer enables public access, so anonymous requests are attributed
		// to the venue's public DID (e.g. did:web:host:public).
		Object caller = RT.getIn(job.getOutput(), "caller");
		assertNotNull(caller, "caller should be set when public access is enabled");
		assertTrue(caller.toString().endsWith(":public"),
			"public anonymous caller should end with ':public', got: " + caller);

		assertEquals(CVMBool.TRUE, RT.getIn(job.getOutput(), "authenticated"));
	}

	/**
	 * End-to-end round-trip for the {@code bearerSecret} field on http:post.
	 * Outer caller (DID O) stores a self-signed JWT issued by a different
	 * keypair (DID I), then invokes http:post against the venue's own
	 * /api/v1/invoke endpoint to call auth:whoami, passing bearerSecret to
	 * inject the JWT as Authorization. The inner request authenticates as I,
	 * not O — proving that the bearer header is being constructed from the
	 * resolved secret and that the venue's auth middleware verifies it.
	 */
	@Test
	void bearerSecret_injectsSelfSignedJWT_innerCallerIsJWTSubject() throws Exception {
		Engine engine = TestServer.ENGINE;

		// Outer identity: stores the secret and submits the http:post job.
		AKeyPair outerKp = AKeyPair.generate();
		String outerDid = "did:key:" + Multikey.encodePublicKey(outerKp.getAccountKey());

		// Inner identity: signs the JWT injected via bearerSecret. Must differ
		// from the outer identity to make the assertion meaningful.
		AKeyPair innerKp = AKeyPair.generate();
		AString innerDidStr = Strings.create(
			"did:key:" + Multikey.encodePublicKey(innerKp.getAccountKey()));

		// Stash a fresh self-signed JWT in the outer user's secret store.
		User outerUser = engine.getVenueState().users().ensure(Strings.create(outerDid));
		long now = System.currentTimeMillis() / 1000;
		AString jwt = JWT.signPublic(Maps.of(
			Strings.intern("sub"), innerDidStr,
			Strings.intern("iss"), innerDidStr,
			Strings.intern("iat"), now,
			Strings.intern("exp"), now + 300
		), innerKp);
		byte[] encKey = SecretStore.deriveKey(engine.getKeyPair());
		outerUser.secrets().store("MY_JWT", jwt.toString(), encKey);

		// Authed client so the outer invocation runs as outerDid (the secret owner).
		VenueHTTP authed = VenueHTTP.create(URI.create(TestServer.BASE_URL), VenueAuth.keyPair(outerKp));

		// Body for the inner /api/v1/invoke call: ?wait=true so the response
		// includes the completed job's output.
		ACell invokeBody = Maps.of(
			Strings.create("operation"), Strings.create("v/ops/auth/whoami"),
			Strings.create("input"), Maps.empty(),
			Strings.create("wait"), CVMBool.TRUE
		);

		Job outer = authed.invokeSync("v/ops/http/post", Maps.of(
			"url", TestServer.BASE_URL + "/api/v1/invoke",
			"headers", Maps.of("Content-Type", "application/json"),
			"body", invokeBody,
			"bearerSecret", "MY_JWT"
		), 30_000);

		assertEquals(Status.COMPLETE, outer.getStatus(), outer.getErrorMessage());
		long httpStatus = RT.ensureLong(RT.getIn(outer.getOutput(), "status")).longValue();
		assertEquals(200, httpStatus, "inner /invoke should return 200 for completed job");

		// Inner response body is JSON-encoded job data. Parse it and assert
		// that the inner caller (resolved by AuthMiddleware from the bearer)
		// matches the JWT subject — NOT the outer caller's DID.
		String innerBodyJson = RT.getIn(outer.getOutput(), "body").toString();
		ACell innerJob = JSON.parse(innerBodyJson);
		assertNotNull(innerJob, "inner response body should be parseable JSON");

		Object innerCaller = RT.getIn(innerJob, "output", "caller");
		assertNotNull(innerCaller, "inner job output should include caller");
		assertEquals(innerDidStr.toString(), innerCaller.toString(),
			"inner caller should be the JWT subject (inner DID), not the outer DID");
		assertTrue(!innerCaller.toString().equals(outerDid),
			"inner caller must differ from outer DID — otherwise the bearer wasn't verified");
	}
}
