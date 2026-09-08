package covia.venue.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.auth.jwt.JWT;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import covia.exception.AuthException;
import covia.venue.Engine;

/**
 * A UCAN-shaped bearer credential that fails validation must surface the
 * validator's specific reason rather than one opaque message (covia#503).
 *
 * The reasons describe only the presented token's own bytes and claims, so
 * they leak nothing about venue state to an unauthenticated caller. Checks
 * that depend on venue policy (audience, empty att) are only reached once the
 * signature has verified.
 */
public class UcanBearerRejectionTest {

	private static Engine engine;
	private static VenueAuthenticator authenticator;
	private static AKeyPair caller;
	private static AString callerDID;

	@BeforeAll
	static void setUp() {
		engine = Engine.createTemp(Maps.empty());
		authenticator = new VenueAuthenticator(engine);
		caller = AKeyPair.generate();
		callerDID = UCAN.toDIDKey(caller.getAccountKey());
	}

	@AfterAll
	static void tearDown() {
		if (engine != null) engine.close();
	}

	private static AMap<AString, ACell> bearerClaims(ACell exp) {
		return Maps.of(
			UCAN.ISS, callerDID,
			UCAN.AUD, engine.getDIDString(),
			UCAN.EXP, exp,
			UCAN.ATT, Vectors.empty());
	}

	private static long inAnHour() {
		return System.currentTimeMillis() / 1000 + 3600;
	}

	private static String reject(String jwt) {
		AuthException e = assertThrows(AuthException.class,
			() -> authenticator.authenticate(Strings.create(jwt)));
		return e.getMessage();
	}

	@Test
	public void validEmptyAttBearerAuthenticates() {
		String jwt = JWT.signPublic(bearerClaims(CVMLong.create(inAnHour())), caller).toString();
		assertEquals(callerDID, authenticator.authenticate(Strings.create(jwt)));
	}

	@Test
	public void malformedExpClaimIsNamed() {
		// The covia-sdk#46 shape: a float exp instead of an integer timestamp.
		String jwt = JWT.signPublic(bearerClaims(CVMDouble.create(inAnHour() + 0.5)), caller)
			.toString();
		String message = reject(jwt);
		assertTrue(message.startsWith(VenueAuthenticator.UCAN_REJECTED_PREFIX), message);
		assertTrue(message.contains("malformed claim \"exp\""), message);
	}

	@Test
	public void expiredTokenIsNamed() {
		long past = System.currentTimeMillis() / 1000 - 3600;
		String jwt = JWT.signPublic(bearerClaims(CVMLong.create(past)), caller).toString();
		String message = reject(jwt);
		assertTrue(message.startsWith(VenueAuthenticator.UCAN_REJECTED_PREFIX), message);
		assertTrue(message.contains("expired"), message);
	}

	@Test
	public void badSignatureIsNamedWithoutRevealingMore() {
		String jwt = JWT.signPublic(bearerClaims(CVMLong.create(inAnHour())), caller).toString();
		// Re-sign the same claims with a different key so the issuer no longer matches.
		AMap<AString, ACell> claims = JWT.parse(Strings.create(jwt)).getClaims();
		String forged = JWT.signPublic(claims, AKeyPair.generate()).toString();
		String message = reject(forged);
		assertTrue(message.startsWith(VenueAuthenticator.UCAN_REJECTED_PREFIX), message);
		assertTrue(message.contains("bad signature"), message);
		// Post-signature detail (audience / att policy) is never reached.
		assertFalse(message.contains("audience"), message);
		assertFalse(message.contains("att"), message);
	}

	@Test
	public void nonEmptyAttIsRejectedOnlyAfterSignatureVerifies() {
		AMap<AString, ACell> claims = bearerClaims(CVMLong.create(inAnHour())).assoc(
			UCAN.ATT, Vectors.of(Capability.create(
				Strings.create(callerDID + "/w/"), Strings.create("crud/read"))));
		String jwt = JWT.signPublic(claims, caller).toString();
		String message = reject(jwt);
		assertTrue(message.startsWith(VenueAuthenticator.UCAN_REJECTED_PREFIX), message);
		assertTrue(message.contains("empty att"), message);
	}

	@Test
	public void wrongAudienceStaysGeneric() {
		AString otherVenue = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		AMap<AString, ACell> claims = bearerClaims(CVMLong.create(inAnHour()))
			.assoc(UCAN.AUD, otherVenue);
		String jwt = JWT.signPublic(claims, caller).toString();
		String message = reject(jwt);
		assertEquals("Token audience not accepted by this venue", message);
	}

	@Test
	public void nonUcanGarbageStaysGeneric() {
		assertEquals("Invalid or expired token", reject("not.a.jwt"));
	}
}
