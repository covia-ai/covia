package covia.grid.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import convex.auth.jwt.JWT;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.lang.RT;

/**
 * Deterministic unit tests for {@link KeyPairAuth} (covia#199): the per-request
 * self-issued JWT carries the configured {@code aud} claim (audience binding —
 * capture-replay containment) and honours a configured lifetime. Claims are
 * read back by verifying the actual Authorization header the auth applies.
 */
public class KeyPairAuthTest {

	/** Applies the auth to a request and returns the verified JWT claims. */
	private static AMap<AString, ACell> claimsFor(VenueAuth auth) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:1/x")).GET();
		auth.apply(b);
		Optional<String> header = b.build().headers().firstValue("Authorization");
		assertTrue(header.isPresent(), "auth must set an Authorization header");
		String jwt = header.get().substring("Bearer ".length());
		AMap<AString, ACell> claims = JWT.verifyPublic(Strings.create(jwt));
		assertNotNull(claims, "self-issued JWT must verify against its embedded key");
		return claims;
	}

	@Test
	public void testDefaultHasNoAudience() {
		AKeyPair kp = AKeyPair.generate();
		AMap<AString, ACell> claims = claimsFor(VenueAuth.keyPair(kp));
		assertNull(claims.get(Strings.intern("aud")), "plain keyPair auth carries no aud");
		assertEquals(RT.getIn(claims, "iss"), RT.getIn(claims, "sub"), "self-issued: iss == sub");
	}

	@Test
	public void testAudienceBound() {
		AKeyPair kp = AKeyPair.generate();
		String venueDID = "did:key:z6MkExampleVenueDID";
		AMap<AString, ACell> claims = claimsFor(VenueAuth.keyPair(kp, venueDID));
		assertEquals(Strings.create(venueDID), RT.getIn(claims, "aud"),
			"aud claim must carry the target venue DID");
	}

	@Test
	public void testConfigurableLifetime() {
		AKeyPair kp = AKeyPair.generate();
		long lifetime = 30 * 24 * 3600; // a long-lived minted credential
		AMap<AString, ACell> claims = claimsFor(VenueAuth.keyPair(kp, null, lifetime));
		long iat = RT.ensureLong(RT.getIn(claims, "iat")).longValue();
		long exp = RT.ensureLong(RT.getIn(claims, "exp")).longValue();
		assertEquals(lifetime, exp - iat, "exp - iat must equal the configured lifetime");
	}

	@Test
	public void testInvalidLifetimeRejected() {
		AKeyPair kp = AKeyPair.generate();
		assertThrows(IllegalArgumentException.class, () -> VenueAuth.keyPair(kp, null, 0));
		assertThrows(IllegalArgumentException.class, () -> VenueAuth.keyPair(kp, null, -5));
	}
}
