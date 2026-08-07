package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;

import org.junit.jupiter.api.Test;

import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import covia.api.Fields;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.auth.VenueDID;
import covia.grid.client.VenueHTTP;

/**
 * End-to-end tests for audience-bound self-issued auth (covia#199) against a
 * real venue: a token audienced to THIS venue authenticates; a token audienced
 * to a DIFFERENT venue is a hard 401 (the capture-replay containment); and
 * {@link VenueDID#discover} resolves the DID to bind to from
 * {@code /.well-known/did.json}. Deterministic — the outcomes follow from the
 * audience policy, not timing.
 */
public class AudienceBoundAuthTest {

	@Test
	public void testDiscoverAndAudienceBoundAuth() throws Exception {
		// Discover the venue's published DID from its well-known document.
		String venueDID = VenueDID.discover(TestServer.BASE_URL);
		assertEquals(TestServer.ENGINE.getDIDString().toString(), venueDID,
			"discovery should return the venue's published DID");
		// Cached idempotently.
		assertEquals(venueDID, VenueDID.discover(TestServer.BASE_URL));

		// A token audienced to this venue authenticates normally.
		AKeyPair kp = AKeyPair.generate();
		VenueHTTP client = VenueHTTP.create(
			URI.create(TestServer.BASE_URL), VenueAuth.keyPair(kp, venueDID));
		Job job = client.invokeAndWait(Strings.create("v/test/ops/echo"),
			Maps.of(Fields.VALUE, Strings.create("bound")));
		assertEquals(Status.COMPLETE, job.getStatus());
	}

	@Test
	public void testWrongAudienceRejected() {
		// A token audienced to ANOTHER venue must be a hard 401 here — never a
		// silent downgrade to the public identity.
		AKeyPair kp = AKeyPair.generate();
		AKeyPair otherVenueKP = AKeyPair.generate();
		String otherVenueDID = "did:key:" +
			convex.core.crypto.util.Multikey.encodePublicKey(otherVenueKP.getAccountKey());

		VenueHTTP client = VenueHTTP.create(
			URI.create(TestServer.BASE_URL), VenueAuth.keyPair(kp, otherVenueDID));
		Throwable t = assertThrows(Throwable.class, () ->
			client.invokeAndWait(Strings.create("v/test/ops/echo"),
				Maps.of(Fields.VALUE, Strings.create("replayed"))));
		StringBuilder chain = new StringBuilder();
		for (Throwable c = t; c != null; c = c.getCause()) chain.append(c.getMessage()).append(" | ");
		assertTrue(chain.toString().contains("401") || chain.toString().toLowerCase().contains("audience"),
			"wrong-audience token must be rejected (401), got: " + chain);
	}

	@Test
	public void signingAnotherSubjectDoesNotGrantItsWorkspace() throws Exception {
		String venueDID = TestServer.ENGINE.getDIDString().toString();
		AKeyPair victimKey = AKeyPair.generate();
		AString victimDID = UCAN.toDIDKey(victimKey.getAccountKey());
		String path = "w/auth-subject-private";
		AString secret = Strings.create("victim-only");

		VenueHTTP victim = VenueHTTP.create(URI.create(TestServer.BASE_URL),
			VenueAuth.keyPair(victimKey, venueDID));
		victim.setTimeout(5000);
		victim.invokeAndWait(Strings.create("v/ops/covia/write"),
			Maps.of(Fields.PATH, path, Fields.VALUE, secret));
		ACell ownRead = victim.invokeAndWait(Strings.create("v/ops/covia/read"),
			Maps.of(Fields.PATH, path)).awaitResult(5000);
		assertEquals(secret, RT.getIn(ownRead, Fields.VALUE));

		// The attacker genuinely signs this JWT and accurately identifies itself
		// as issuer, but merely naming the victim as sub conveys no authority over
		// the victim or its resources.
		AKeyPair attackerKey = AKeyPair.generate();
		AString attackerDID = UCAN.toDIDKey(attackerKey.getAccountKey());
		long now = System.currentTimeMillis() / 1000;
		AMap<AString, ACell> claims = Maps.of(
			JWT.ISS, attackerDID,
			JWT.SUB, victimDID,
			JWT.AUD, Strings.create(venueDID),
			JWT.IAT, CVMLong.create(now),
			JWT.EXP, CVMLong.create(now + 300));
		String token = JWT.signPublic(claims, attackerKey).toString();
		VenueHTTP attacker = VenueHTTP.create(URI.create(TestServer.BASE_URL),
			VenueAuth.bearer(token));
		attacker.setTimeout(5000);

		assertThrows(Throwable.class, () -> attacker.invokeAndWait(
			Strings.create("v/ops/covia/read"),
			Maps.of(Fields.PATH, victimDID + "/" + path)),
			"a signed sub claim alone must not grant access to that subject's workspace");
	}
}
