package covia.venue;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

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
import covia.grid.Job;
import covia.exception.AuthException;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.server.VenueServer;

/**
 * #149 — JWT audience ({@code aud}) validation at the HTTP transport boundary.
 *
 * <p>A bearer token presented to a venue must be intended for that venue
 * (RFC 7519 §4.1.3) — otherwise a token minted for venue A could be replayed
 * to venue B. Two policies:</p>
 * <ul>
 *   <li>{@code verify} (default) — if {@code aud} is present it must match;
 *       an absent {@code aud} is tolerated (migration-friendly);</li>
 *   <li>{@code require} — {@code aud} must be present AND match.</li>
 * </ul>
 * <p>A present-but-mismatched {@code aud} is rejected with 401 under BOTH
 * policies — never silently downgraded to the public identity. {@code aud} may
 * be a string or an array of strings.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
public class AudiencePolicyTest {

	private static final AString OP_ECHO = Strings.create("v/test/ops/echo");

	private VenueServer requireServer;  // audience = require (+ an accepted-audience allowlist)
	private AString verifyVenueDID;
	private AString requireVenueDID;
	/** Extra audience the requireServer is configured to also accept. */
	private AString extraDID;

	@BeforeAll
	public void setup() {
		extraDID = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());

		// Verify-policy cases run against the shared TestServer (its default policy
		// IS verify), so no dedicated venue is needed. Only the require policy needs
		// its own venue — which also carries the accepted-audiences allowlist, so a
		// single extra venue covers both the require and the allowlist cases.
		verifyVenueDID = TestServer.ENGINE.getDIDString();

		// The require venue also carries a public hostname, so it publishes a
		// did:web alias (covia#167) — covering the alias-audience cases too.
		requireServer = VenueServer.launch(Maps.of(
			Strings.create("port"), 0,
			Config.HOSTNAME, Strings.create("venue-req.example.com"),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.AUTH, Maps.of(
				Config.PUBLIC, Maps.of(Config.ENABLED, true),
				Config.AUDIENCE, Strings.create("require"),
				Config.ACCEPTED_AUDIENCES, Vectors.of(extraDID))));
		requireVenueDID = requireServer.getEngine().getDIDString();
	}

	@AfterAll
	public void teardown() {
		if (requireServer != null) try { requireServer.close(); } catch (Exception ignored) {}
	}

	private static VenueHTTP client(VenueServer server, String jwt) {
		return client("http://localhost:" + server.port(), jwt);
	}

	private static VenueHTTP client(String base, String jwt) {
		VenueHTTP c = VenueHTTP.create(URI.create(base), VenueAuth.bearer(jwt));
		c.setTimeout(5000);
		return c;
	}

	/** A self-issued JWT (sub = did:key, kid = that key) with the given aud
	 *  (null to omit) and a 1h expiry. */
	private static String selfIssued(AKeyPair kp, ACell aud) {
		AString did = UCAN.toDIDKey(kp.getAccountKey());
		AMap<AString, ACell> claims = Maps.of(
			Strings.create("sub"), did,
			Strings.create("exp"), CVMLong.create((System.currentTimeMillis() / 1000) + 3600));
		if (aud != null) claims = claims.assoc(Strings.create("aud"), aud);
		return JWT.signPublic(claims, kp).toString();
	}

	/** Venue-issued login/session JWT for a managed user. */
	private static String venueSigned(VenueServer server, AString sub, AString iss,
			ACell aud, long exp) {
		AMap<AString, ACell> claims = Maps.of(
			Strings.create("sub"), sub,
			Strings.create("iss"), iss,
			Strings.create("exp"), CVMLong.create(exp));
		if (aud != null) claims = claims.assoc(Strings.create("aud"), aud);
		return JWT.signPublic(claims, server.getEngine().getKeyPair()).toString();
	}

	/** A UCAN audienced to the given venue, in date, no caps. */
	private static String ucanFor(AKeyPair kp, AString venueDID) {
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		return UCAN.create(kp, UCAN.fromDIDKey(venueDID), exp, Vectors.empty(), Vectors.empty())
			.toJWT(kp).toString();
	}

	private static void assertAccepted(VenueHTTP client) throws Exception {
		// An accepted bearer produces a job (authenticated) — NOT a 401 at the door.
		Job job = client.invokeAndWait(OP_ECHO, Maps.of(Strings.create("hi"), Strings.create("there")));
		assertNotNull(job, "an accepted bearer must produce a job, not a 401 at the door");
	}

	private static void assertRejected401(VenueHTTP client) {
		Throwable t = assertThrows(Throwable.class, () ->
			client.invokeAndWait(OP_ECHO, Maps.of(Strings.create("hi"), Strings.create("there"))));
		StringBuilder chain = new StringBuilder();
		for (Throwable c = t; c != null; c = c.getCause()) chain.append(c.getMessage()).append(" | ");
		assertTrue(chain.toString().contains("401") || chain.toString().contains("audience"),
			"expected a 401 audience rejection, got: " + chain);
	}

	// =============================== require ===============================

	@Test
	public void requireRejectsMissingAudience() {
		// A presented token with no aud is rejected under require.
		assertRejected401(client(requireServer, selfIssued(AKeyPair.generate(), null)));
	}

	@Test
	public void requireAcceptsVenueAudience() throws Exception {
		assertAccepted(client(requireServer, ucanFor(AKeyPair.generate(), requireVenueDID)));
	}

	@Test
	public void managedUserSessionRequiresVenueSignatureAndValidClaims() throws Exception {
		Engine venue = requireServer.getEngine();
		AString managed = venue.managedUserDID(Strings.create("managed-session"));
		long now = System.currentTimeMillis() / 1000;

		assertAccepted(client(requireServer,
			venueSigned(requireServer, managed, requireVenueDID, requireVenueDID, now + 3600)));

		AKeyPair attacker = AKeyPair.generate();
		AMap<AString, ACell> forgedClaims = Maps.of(
			Strings.create("sub"), managed,
			Strings.create("iss"), requireVenueDID,
			Strings.create("aud"), requireVenueDID,
			Strings.create("exp"), CVMLong.create(now + 3600));
		assertRejected401(client(requireServer,
			JWT.signPublic(forgedClaims, attacker).toString()));

		assertRejected401(client(requireServer,
			venueSigned(requireServer, managed, requireVenueDID, requireVenueDID, now - 3600)));
		assertRejected401(client(requireServer,
			venueSigned(requireServer, managed, requireVenueDID,
				UCAN.toDIDKey(AKeyPair.generate().getAccountKey()), now + 3600)));
		assertRejected401(client(requireServer,
			venueSigned(requireServer, managed,
				Strings.create("did:web:attacker.example"), requireVenueDID, now + 3600)));
		assertRejected401(client(requireServer,
			venueSigned(requireServer, Strings.create("not-a-did"),
				requireVenueDID, requireVenueDID, now + 3600)));
	}

	@Test
	public void requireRejectsVenueSessionWithoutAudience() {
		Engine venue = requireServer.getEngine();
		AString managed = venue.managedUserDID(Strings.create("missing-session-aud"));
		assertRejected401(client(requireServer,
			venueSigned(requireServer, managed, requireVenueDID, null,
				(System.currentTimeMillis() / 1000) + 3600)));
	}

	// =============================== verify ================================

	@Test
	public void verifyToleratesMissingAudience() throws Exception {
		// Default policy: an absent aud is tolerated (the token still authenticates).
		assertAccepted(client(TestServer.BASE_URL, selfIssued(AKeyPair.generate(), null)));
	}

	@Test
	public void presentButWrongAudienceRejectedUnderVerify() {
		// RFC 7519 MUST: a present-but-mismatched aud is rejected even under verify.
		AString wrong = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		assertRejected401(client(TestServer.BASE_URL, selfIssued(AKeyPair.generate(), wrong)));
	}

	@Test
	public void arrayAudienceAcceptedWhenItIncludesVenue() throws Exception {
		// aud may be an array (StringOrURI[]); accepted if any element matches.
		AString other = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		ACell arrayAud = Vectors.of(other, verifyVenueDID);
		assertAccepted(client(TestServer.BASE_URL, selfIssued(AKeyPair.generate(), arrayAud)));
	}

	@Test
	public void arrayAudienceRejectedWhenItExcludesVenue() {
		AString o1 = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		AString o2 = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		assertRejected401(client(TestServer.BASE_URL, selfIssued(AKeyPair.generate(), Vectors.of(o1, o2))));
	}

	@Test
	public void configuredAllowlistAudienceAccepted() throws Exception {
		// auth.acceptedAudiences extends the allowlist beyond the venue's own DID
		// (exercised on the require venue, which carries the allowlist).
		assertAccepted(client(requireServer, selfIssued(AKeyPair.generate(), extraDID)));
	}

	// ==================== did:web alias audience (covia#167) ====================

	@Test
	public void webAliasAudienceAccepted() throws Exception {
		// A strictly-resolving client audiences its token to the DID it resolved
		// from /.well-known/did.json — the did:web alias when the venue has a
		// public hostname. The venue accepts the alias; its canonical identity
		// (and validation key) remains the did:key.
		assertAccepted(client(requireServer,
			selfIssued(AKeyPair.generate(), Strings.create("did:web:venue-req.example.com"))));
	}

	@Test
	public void publicAuthenticatorUsesVenueAudiencePolicy() {
		AKeyPair key = AKeyPair.generate();
		AString did = UCAN.toDIDKey(key.getAccountKey());
		AString webDID = Strings.create("did:web:venue-req.example.com");
		AString token = Strings.create(selfIssued(key, webDID));

		assertEquals(did, requireServer.authenticator().authenticate(token));
		assertTrue(requireServer.authenticator().acceptedAudiences()
			.contains(requireVenueDID));
		assertTrue(requireServer.authenticator().acceptedAudiences()
			.contains(webDID));
		assertTrue(requireServer.authenticator().acceptedAudiences()
			.contains(extraDID));

		AString wrongAudience = Strings.create("did:web:other.example.com");
		AuthException rejected = assertThrows(AuthException.class, () ->
			requireServer.authenticator().authenticate(
				Strings.create(selfIssued(AKeyPair.generate(), wrongAudience))));
		assertTrue(rejected.getMessage().contains("audience"));
	}

	@Test
	public void wrongWebAliasAudienceRejected() {
		// A did:web audience for a DIFFERENT domain is not this venue.
		assertRejected401(client(requireServer,
			selfIssued(AKeyPair.generate(), Strings.create("did:web:other.example.com"))));
	}
}
