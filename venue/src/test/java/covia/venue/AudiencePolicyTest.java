package covia.venue;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.concurrent.CompletionException;

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
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import covia.grid.Job;
import covia.exception.AuthException;
import covia.exception.ResponseException;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.auth.VenueAuthenticator;
import covia.venue.server.VenueServer;

/**
 * #149 — JWT audience ({@code aud}) validation at the HTTP transport boundary.
 *
 * <p>A bearer token presented to a venue must be intended for that venue
 * (RFC 8725 §3.9) — otherwise a token minted for venue A could be replayed
 * to venue B, and a JWT its signer made for some other purpose could be
 * presented as a login. {@code auth.requireAud} (default true) refuses a
 * bearer without {@code aud}; off, an absent {@code aud} is accepted.</p>
 * <p>A present-but-mismatched {@code aud} is rejected with 401 either way —
 * never silently downgraded to the public identity. {@code aud} may be a
 * string or an array of strings.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
public class AudiencePolicyTest {

	private static final AString OP_ECHO = Strings.create("v/test/ops/echo");

	private VenueServer optionalServer;  // requireAud = false (+ an accepted-audience allowlist)
	private AString defaultVenueDID;
	private AString optionalVenueDID;
	/** Extra audience the optionalServer is configured to also accept. */
	private AString extraDID;

	@BeforeAll
	public void setup() {
		extraDID = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());

		// Default-policy cases run against the shared TestServer, so no dedicated
		// venue is needed for them. Only requireAud: false needs its own venue —
		// which also carries the accepted-audiences allowlist, so a single extra
		// venue covers both the optional-audience and the allowlist cases.
		defaultVenueDID = TestServer.ENGINE.getDIDString();

		// The optional venue also carries a public hostname, so it publishes a
		// did:web alias (covia#167) — covering the alias-audience cases too.
		optionalServer = VenueServer.launch(Maps.of(
			Strings.create("port"), 0, Config.STORE, "memory",
			Config.HOSTNAME, Strings.create("venue-opt.example.com"),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.AUTH, Maps.of(
				Config.PUBLIC, Maps.of(Config.ENABLED, true),
				Config.REQUIRE_AUD, CVMBool.FALSE,
				Config.ACCEPTED_AUDIENCES, Vectors.of(extraDID))));
		optionalVenueDID = optionalServer.getEngine().getDIDString();
	}

	@AfterAll
	public void teardown() {
		if (optionalServer != null) try { optionalServer.close(); } catch (Exception ignored) {}
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

	/** Venue-issued login/session JWT for a user. */
	private static String venueSigned(Engine venue, AString sub, AString iss,
			ACell aud, long exp) {
		AMap<AString, ACell> claims = Maps.of(
			Strings.create("sub"), sub,
			Strings.create("iss"), iss,
			Strings.create("exp"), CVMLong.create(exp));
		if (aud != null) claims = claims.assoc(Strings.create("aud"), aud);
		return JWT.signPublic(claims, venue.getKeyPair()).toString();
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
		CompletionException failure = assertThrows(CompletionException.class, () ->
			client.startJobAsync(OP_ECHO,
				Maps.of(Strings.create("hi"), Strings.create("there"))).join());
		assertInstanceOf(ResponseException.class, failure.getCause());
	}

	// ============================ default: required ============================

	@Test
	public void defaultRejectsMissingAudience() {
		// A presented token with no aud is rejected by default.
		assertRejected401(client(TestServer.BASE_URL, selfIssued(AKeyPair.generate(), null)));
		AuthException rejected = assertThrows(AuthException.class, () ->
			TestServer.SERVER.authenticator().authenticate(
				Strings.create(selfIssued(AKeyPair.generate(), null))));
		assertTrue(rejected.getMessage().contains(VenueAuthenticator.Reason.AUDIENCE_REQUIRED));
	}

	@Test
	public void defaultAcceptsVenueAudience() throws Exception {
		assertAccepted(client(TestServer.BASE_URL, ucanFor(AKeyPair.generate(), defaultVenueDID)));
		assertAccepted(client(TestServer.BASE_URL, selfIssued(AKeyPair.generate(), defaultVenueDID)));
	}

	@Test
	public void venueSessionRequiresVenueSignatureAndValidClaims() throws Exception {
		Engine venue = TestServer.ENGINE;
		AString user = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		long now = System.currentTimeMillis() / 1000;

		assertAccepted(client(TestServer.BASE_URL,
			venueSigned(venue, user, defaultVenueDID, defaultVenueDID, now + 3600)));

		AKeyPair attacker = AKeyPair.generate();
		AMap<AString, ACell> forgedClaims = Maps.of(
			Strings.create("sub"), user,
			Strings.create("iss"), defaultVenueDID,
			Strings.create("aud"), defaultVenueDID,
			Strings.create("exp"), CVMLong.create(now + 3600));
		assertRejected401(client(TestServer.BASE_URL,
			JWT.signPublic(forgedClaims, attacker).toString()));

		assertRejected401(client(TestServer.BASE_URL,
			venueSigned(venue, user, defaultVenueDID, defaultVenueDID, now - 3600)));
		assertRejected401(client(TestServer.BASE_URL,
			venueSigned(venue, user, defaultVenueDID,
				UCAN.toDIDKey(AKeyPair.generate().getAccountKey()), now + 3600)));
		assertRejected401(client(TestServer.BASE_URL,
			venueSigned(venue, user,
				Strings.create("did:web:attacker.example"), defaultVenueDID, now + 3600)));
		assertRejected401(client(TestServer.BASE_URL,
			venueSigned(venue, Strings.create("not-a-did"),
				defaultVenueDID, defaultVenueDID, now + 3600)));
	}

	@Test
	public void defaultRejectsVenueSignedTokenWithoutAudience() {
		Engine venue = TestServer.ENGINE;
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		AString user = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		assertRejected401(client(TestServer.BASE_URL,
			venueSigned(venue, user, defaultVenueDID, null, exp)));
		// Whatever the venue key signs without aud is no login, the venue's own DID included.
		assertRejected401(client(TestServer.BASE_URL,
			venueSigned(venue, defaultVenueDID, defaultVenueDID, null, exp)));
	}

	@Test
	public void arrayAudienceAcceptedWhenItIncludesVenue() throws Exception {
		// aud may be an array (StringOrURI[]); accepted if any element matches.
		AString other = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		ACell arrayAud = Vectors.of(other, defaultVenueDID);
		assertAccepted(client(TestServer.BASE_URL, selfIssued(AKeyPair.generate(), arrayAud)));
	}

	@Test
	public void arrayAudienceRejectedWhenItExcludesVenue() {
		AString o1 = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		AString o2 = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		assertRejected401(client(TestServer.BASE_URL, selfIssued(AKeyPair.generate(), Vectors.of(o1, o2))));
	}

	// ========================= requireAud: false ==========================

	@Test
	public void optionalAudienceAcceptsMissingAudience() throws Exception {
		assertAccepted(client(optionalServer, selfIssued(AKeyPair.generate(), null)));
	}

	@Test
	public void presentButWrongAudienceRejectedWhenOptional() {
		// RFC 7519 MUST: a present-but-mismatched aud is rejected even when aud is optional.
		AString wrong = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		assertRejected401(client(optionalServer, selfIssued(AKeyPair.generate(), wrong)));
	}

	@Test
	public void configuredAllowlistAudienceAccepted() throws Exception {
		// auth.acceptedAudiences extends the allowlist beyond the venue's own DID
		// (exercised on the optional venue, which carries the allowlist).
		assertAccepted(client(optionalServer, selfIssued(AKeyPair.generate(), extraDID)));
		assertAccepted(client(optionalServer, ucanFor(AKeyPair.generate(), optionalVenueDID)));
	}

	// ==================== did:web alias audience (covia#167) ====================

	@Test
	public void webAliasAudienceAccepted() throws Exception {
		// A strictly-resolving client audiences its token to the DID it resolved
		// from /.well-known/did.json — the did:web alias when the venue has a
		// public hostname. The venue accepts the alias; its canonical identity
		// (and validation key) remains the did:key.
		assertAccepted(client(optionalServer,
			selfIssued(AKeyPair.generate(), Strings.create("did:web:venue-opt.example.com"))));
	}

	@Test
	public void publicAuthenticatorUsesVenueAudiencePolicy() {
		AKeyPair key = AKeyPair.generate();
		AString did = UCAN.toDIDKey(key.getAccountKey());
		AString webDID = Strings.create("did:web:venue-opt.example.com");
		AString token = Strings.create(selfIssued(key, webDID));

		assertEquals(did, optionalServer.authenticator().authenticate(token));
		assertTrue(optionalServer.authenticator().acceptedAudiences()
			.contains(optionalVenueDID));
		assertTrue(optionalServer.authenticator().acceptedAudiences()
			.contains(webDID));
		assertTrue(optionalServer.authenticator().acceptedAudiences()
			.contains(extraDID));

		AString wrongAudience = Strings.create("did:web:other.example.com");
		AuthException rejected = assertThrows(AuthException.class, () ->
			optionalServer.authenticator().authenticate(
				Strings.create(selfIssued(AKeyPair.generate(), wrongAudience))));
		assertTrue(rejected.getMessage().contains(VenueAuthenticator.Reason.AUDIENCE_NOT_THIS_VENUE));
	}

	@Test
	public void wrongWebAliasAudienceRejected() {
		// A did:web audience for a DIFFERENT domain is not this venue.
		assertRejected401(client(optionalServer,
			selfIssued(AKeyPair.generate(), Strings.create("did:web:other.example.com"))));
	}
}
