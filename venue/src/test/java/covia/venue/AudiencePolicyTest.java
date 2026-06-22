package covia.venue;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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

	private VenueServer verifyServer;   // default policy + an extra accepted audience
	private VenueServer requireServer;  // audience = require
	private AString verifyVenueDID;
	private AString requireVenueDID;
	/** Extra audience the verifyServer is configured to accept. */
	private AString extraDID;

	@BeforeAll
	public void setup() {
		extraDID = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());

		verifyServer = VenueServer.launch(Maps.of(
			Strings.create("port"), 0,
			Config.AUTH, Maps.of(
				Config.PUBLIC, Maps.of(Config.ENABLED, true),
				Config.ACCEPTED_AUDIENCES, Vectors.of(extraDID))));
		verifyVenueDID = verifyServer.getEngine().getDIDString();

		requireServer = VenueServer.launch(Maps.of(
			Strings.create("port"), 0,
			Config.AUTH, Maps.of(
				Config.PUBLIC, Maps.of(Config.ENABLED, true),
				Config.AUDIENCE, Strings.create("require"))));
		requireVenueDID = requireServer.getEngine().getDIDString();
	}

	@AfterAll
	public void teardown() {
		if (verifyServer != null) try { verifyServer.close(); } catch (Exception ignored) {}
		if (requireServer != null) try { requireServer.close(); } catch (Exception ignored) {}
	}

	private static VenueHTTP client(VenueServer server, String jwt) {
		VenueHTTP c = VenueHTTP.create(URI.create("http://localhost:" + server.port()), VenueAuth.bearer(jwt));
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

	// =============================== verify ================================

	@Test
	public void verifyToleratesMissingAudience() throws Exception {
		// Default policy: an absent aud is tolerated (the token still authenticates).
		assertAccepted(client(verifyServer, selfIssued(AKeyPair.generate(), null)));
	}

	@Test
	public void presentButWrongAudienceRejectedUnderVerify() {
		// RFC 7519 MUST: a present-but-mismatched aud is rejected even under verify.
		AString wrong = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		assertRejected401(client(verifyServer, selfIssued(AKeyPair.generate(), wrong)));
	}

	@Test
	public void arrayAudienceAcceptedWhenItIncludesVenue() throws Exception {
		// aud may be an array (StringOrURI[]); accepted if any element matches.
		AString other = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		ACell arrayAud = Vectors.of(other, verifyVenueDID);
		assertAccepted(client(verifyServer, selfIssued(AKeyPair.generate(), arrayAud)));
	}

	@Test
	public void arrayAudienceRejectedWhenItExcludesVenue() {
		AString o1 = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		AString o2 = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		assertRejected401(client(verifyServer, selfIssued(AKeyPair.generate(), Vectors.of(o1, o2))));
	}

	@Test
	public void configuredAllowlistAudienceAccepted() throws Exception {
		// auth.acceptedAudiences extends the allowlist beyond the venue's own DID.
		assertAccepted(client(verifyServer, selfIssued(AKeyPair.generate(), extraDID)));
	}
}
