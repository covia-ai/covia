package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.server.VenueServer;

/** End-to-end authentication and rotation for venue-managed named users (#296). */
@TestInstance(Lifecycle.PER_CLASS)
public class NamedUserAuthTest {

	private static final AString OP_ECHO = Strings.create("v/test/ops/echo");

	private VenueServer server;
	private AKeyPair aliceKey;
	private AKeyPair bobKey;
	private AKeyPair rotationKey;
	private AString aliceKeyDID;
	private AString bobKeyDID;
	private AString rotationKeyDID;
	private AString aliceDID;
	private AString bobDID;
	private AString rotationDID;

	@BeforeAll
	void setup() {
		aliceKey = AKeyPair.generate();
		bobKey = AKeyPair.generate();
		rotationKey = AKeyPair.generate();
		aliceKeyDID = UCAN.toDIDKey(aliceKey.getAccountKey());
		bobKeyDID = UCAN.toDIDKey(bobKey.getAccountKey());
		rotationKeyDID = UCAN.toDIDKey(rotationKey.getAccountKey());
		server = VenueServer.launch(Maps.of(
			Config.PORT, 0,
			Config.HOSTNAME, "named-auth.example",
			Config.AUTH, Maps.of(
				Config.PUBLIC, Maps.of(Config.ENABLED, false),
				Config.AUDIENCE, "require"),
			Config.USERS, Maps.of(
				Config.AUTO_CREATE, false,
				Config.BOOTSTRAP, Maps.of(
					"alice", Maps.of(Fields.AUTHENTICATION_KEYS, Vectors.of(aliceKeyDID)),
					"bob", Maps.of(Fields.AUTHENTICATION_KEYS, Vectors.of(bobKeyDID)),
					"rotation", Maps.of(
						Fields.AUTHENTICATION_KEYS, Vectors.of(rotationKeyDID))))));
		aliceDID = server.getEngine().managedUserDID(Strings.create("alice"));
		bobDID = server.getEngine().managedUserDID(Strings.create("bob"));
		rotationDID = server.getEngine().managedUserDID(Strings.create("rotation"));
	}

	@AfterAll
	void close() {
		if (server != null) server.close();
	}

	@Test
	void bootstrapCreatesNamedAccountsAndAuthenticatorRecords() {
		Engine engine = server.getEngine();
		assertNotNull(engine.getVenueState().users().get(aliceDID));
		assertEquals(aliceDID, engine.getAuth().getUser(Strings.create("alice")).get(Fields.DID));
		assertTrue(engine.getAuth().isAuthenticationKeyActive(
			Strings.create("alice"), aliceKeyDID));
	}

	@Test
	void registeredKeyAuthenticatesAsStableNamedDid() throws Exception {
		AString value = Strings.create("registered-key-access");
		server.getEngine().jobs().invokeInternal("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/auth-subject-private", Fields.VALUE, value),
			RequestContext.of(aliceDID)).get(5, TimeUnit.SECONDS);
		VenueAuth auth = VenueAuth.namedKeyPair(
			aliceKey, aliceDID.toString(), server.getEngine().getDIDString().toString());
		assertEquals(aliceDID.toString(), auth.getDID());
		Job read = client(auth).invokeAndWait(Strings.create("v/ops/covia/read"),
			Maps.of(Fields.PATH, "w/auth-subject-private"));
		assertEquals(value, RT.getIn(read.awaitResult(5000), Fields.VALUE),
			"a registered authentication key may access its mapped subject's workspace");
	}

	@Test
	void wrongKeySubjectIssuerAndAudienceAreRejected() {
		AString venueDID = server.getEngine().getDIDString();
		AKeyPair attacker = AKeyPair.generate();
		assertRejected(namedToken(attacker, aliceDID, aliceDID, venueDID));
		assertRejected(namedToken(aliceKey, bobDID, bobDID, venueDID));
		assertRejected(namedToken(aliceKey, aliceDID, bobDID, venueDID));
		assertRejected(namedToken(aliceKey, aliceDID, aliceDID,
			UCAN.toDIDKey(AKeyPair.generate().getAccountKey())));
		assertRejected(namedToken(aliceKey,
			Strings.create("did:web:foreign.example:u:alice"),
			Strings.create("did:web:foreign.example:u:alice"), venueDID));
	}

	@Test
	void rotationAddsNewKeyAndRevocationTakesEffectImmediately() throws Exception {
		Engine engine = server.getEngine();
		AKeyPair replacement = AKeyPair.generate();
		AString replacementDID = UCAN.toDIDKey(replacement.getAccountKey());

		engine.jobs().invokeInternal("v/ops/user/authentication-add",
			Maps.of(Fields.KEY, replacementDID, Fields.LABEL, "replacement"),
			RequestContext.of(rotationDID)).get(5, TimeUnit.SECONDS);
		assertAccepted(namedToken(replacement, rotationDID, rotationDID, engine.getDIDString()));

		engine.jobs().invokeInternal("v/ops/user/authentication-revoke",
			Maps.of(Fields.KEY, rotationKeyDID),
			RequestContext.of(rotationDID)).get(5, TimeUnit.SECONDS);
		assertRejected(namedToken(rotationKey, rotationDID, rotationDID, engine.getDIDString()));
		assertAccepted(namedToken(replacement, rotationDID, rotationDID, engine.getDIDString()));

		AMap<AString, ACell> tombstone = convex.core.lang.RT.ensureMap(
			engine.getAuth().getAuthenticationKeys(Strings.create("rotation")).get(rotationKeyDID));
		assertEquals(Auth.REVOKED, tombstone.get(Fields.STATUS));
		assertEquals(rotationDID, tombstone.get(Fields.REVOKED_BY));

		ExecutionException last = assertThrows(ExecutionException.class, () ->
			engine.jobs().invokeInternal("v/ops/user/authentication-revoke",
				Maps.of(Fields.KEY, replacementDID), RequestContext.of(rotationDID))
				.get(5, TimeUnit.SECONDS));
		assertTrue(last.getCause().getMessage().contains("final active"));
	}

	@Test
	void namedUserCannotManageAnotherUsersKeys() {
		AString extra = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		ExecutionException denied = assertThrows(ExecutionException.class, () ->
			server.getEngine().jobs().invokeInternal("v/ops/user/authentication-add",
				Maps.of(Fields.DID, bobDID, Fields.KEY, extra),
				RequestContext.of(aliceDID)).get(5, TimeUnit.SECONDS));
		assertTrue(denied.getCause() instanceof AuthException);
	}

	@Test
	void oneKeyCannotBindTwoNamedUsers() {
		ExecutionException duplicate = assertThrows(ExecutionException.class, () ->
			server.getEngine().jobs().invokeInternal("v/ops/user/authentication-add",
				Maps.of(Fields.DID, bobDID, Fields.KEY, aliceKeyDID),
				server.getEngine().venueContext()).get(5, TimeUnit.SECONDS));
		assertTrue(duplicate.getCause().getMessage().contains("already bound"));
	}

	@Test
	void expiredAndNotYetValidTokensAreRejected() {
		AString venueDID = server.getEngine().getDIDString();
		long now = System.currentTimeMillis() / 1000;
		// Expired, beyond the clock-skew leeway.
		assertRejected(JWT.signPublic(Maps.of(
			JWT.SUB, aliceDID, JWT.ISS, aliceDID, JWT.AUD, venueDID,
			JWT.IAT, CVMLong.create(now - 900),
			JWT.EXP, CVMLong.create(now - 300)), aliceKey).toString());
		// Not yet valid: nbf in the future.
		assertRejected(JWT.signPublic(Maps.of(
			JWT.SUB, aliceDID, JWT.ISS, aliceDID, JWT.AUD, venueDID,
			JWT.NBF, CVMLong.create(now + 300),
			JWT.IAT, CVMLong.create(now),
			JWT.EXP, CVMLong.create(now + 600)), aliceKey).toString());
	}

	@Test
	void namedTokenWithoutIssuerIsRejected() {
		// A named-user self-issued token REQUIRES iss == sub (#296); iss is
		// optional only for did:key subjects.
		long now = System.currentTimeMillis() / 1000;
		assertRejected(JWT.signPublic(Maps.of(
			JWT.SUB, aliceDID,
			JWT.AUD, server.getEngine().getDIDString(),
			JWT.IAT, CVMLong.create(now),
			JWT.EXP, CVMLong.create(now + 300)), aliceKey).toString());
	}

	private String namedToken(AKeyPair key, AString sub, AString iss, AString audience) {
		long now = System.currentTimeMillis() / 1000;
		return JWT.signPublic(Maps.of(
			JWT.SUB, sub,
			JWT.ISS, iss,
			JWT.AUD, audience,
			JWT.IAT, CVMLong.create(now),
			JWT.EXP, CVMLong.create(now + 300)), key).toString();
	}

	private void assertAccepted(String token) throws Exception {
		Job job = client(token).invokeAndWait(OP_ECHO, Maps.of(Fields.VALUE, "ok"));
		assertNotNull(job);
	}

	private void assertAccepted(VenueHTTP client) throws Exception {
		Job job = client.invokeAndWait(OP_ECHO, Maps.of(Fields.VALUE, "ok"));
		assertNotNull(job);
	}

	private void assertRejected(String token) {
		Throwable failure = assertThrows(Throwable.class,
			() -> client(token).invokeAndWait(OP_ECHO, Maps.of(Fields.VALUE, "denied")));
		StringBuilder chain = new StringBuilder();
		for (Throwable c = failure; c != null; c = c.getCause()) {
			chain.append(c.getMessage()).append(" | ");
		}
		assertTrue(chain.toString().contains("401"), "expected 401, got " + chain);
	}

	private VenueHTTP client(String token) {
		return client(VenueAuth.bearer(token));
	}

	private VenueHTTP client(VenueAuth auth) {
		VenueHTTP client = VenueHTTP.create(
			URI.create("http://localhost:" + server.port()), auth);
		client.setTimeout(5000);
		return client;
	}
}
