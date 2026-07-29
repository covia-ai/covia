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

	@Test
	public void testNamedUserClaimsAndIdentity() {
		AKeyPair kp = AKeyPair.generate();
		String subject = "did:web:venue.example:u:alice";
		String audience = "did:key:z6MkExampleVenueDID";
		VenueAuth auth = VenueAuth.namedKeyPair(kp, subject, audience);

		AMap<AString, ACell> claims = claimsFor(auth);
		assertEquals(Strings.create(subject), RT.getIn(claims, "sub"));
		assertEquals(Strings.create(subject), RT.getIn(claims, "iss"));
		assertEquals(Strings.create(audience), RT.getIn(claims, "aud"));
		assertEquals(subject, auth.getDID());

		AMap<AString, ACell> minted =
			JWT.verifyPublic(Strings.create(auth.mintToken()));
		assertNotNull(minted);
		assertEquals(Strings.create(subject), RT.getIn(minted, "sub"));
		assertEquals(Strings.create(subject), RT.getIn(minted, "iss"));
		assertEquals(Strings.create(audience), RT.getIn(minted, "aud"));
	}

	@Test
	public void testNamedUserRequiresSubjectAndAudienceDids() {
		AKeyPair kp = AKeyPair.generate();
		String subject = "did:web:venue.example:u:alice";
		String audience = "did:key:z6MkExampleVenueDID";

		assertThrows(IllegalArgumentException.class,
			() -> VenueAuth.namedKeyPair(kp, null, audience));
		assertThrows(IllegalArgumentException.class,
			() -> VenueAuth.namedKeyPair(kp, "alice", audience));
		assertThrows(IllegalArgumentException.class,
			() -> VenueAuth.namedKeyPair(kp, subject, null));
		assertThrows(IllegalArgumentException.class,
			() -> VenueAuth.namedKeyPair(kp, subject, "venue.example"));
		assertThrows(IllegalArgumentException.class,
			() -> VenueAuth.namedKeyPair(kp, subject, audience, 0));
	}

	// ========== mintToken (#219) ==========

	@Test
	public void testMintTokenClaims() {
		AKeyPair kp = AKeyPair.generate();
		String venueDID = "did:key:z6MkExampleVenueDID";
		long lifetime = 30 * 24 * 3600; // the getmine GETMINE_TOKEN shape
		VenueAuth auth = VenueAuth.keyPair(kp, venueDID, lifetime);

		String token = auth.mintToken();
		AMap<AString, ACell> claims = JWT.verifyPublic(Strings.create(token));
		assertNotNull(claims, "minted JWT must verify against its embedded key");
		assertEquals(Strings.create(auth.getDID()), RT.getIn(claims, "sub"));
		assertEquals(RT.getIn(claims, "iss"), RT.getIn(claims, "sub"), "self-issued: iss == sub");
		assertEquals(Strings.create(venueDID), RT.getIn(claims, "aud"));
		long iat = RT.ensureLong(RT.getIn(claims, "iat")).longValue();
		long exp = RT.ensureLong(RT.getIn(claims, "exp")).longValue();
		assertEquals(lifetime, exp - iat, "exp - iat must equal the configured lifetime");
	}

	@Test
	public void testMintTokenMatchesApply() {
		// The contract: a minted token is exactly what a request would carry.
		// iat/exp are clock-dependent, so compare identity claims + lifetime.
		AKeyPair kp = AKeyPair.generate();
		VenueAuth auth = VenueAuth.keyPair(kp, "did:key:z6MkVenue", 600);

		AMap<AString, ACell> minted = JWT.verifyPublic(Strings.create(auth.mintToken()));
		AMap<AString, ACell> applied = claimsFor(auth);
		for (String claim : new String[] {"sub", "iss", "aud"}) {
			assertEquals(RT.getIn(applied, claim), RT.getIn(minted, claim),
				"mintToken and apply must agree on " + claim);
		}
		long mintedLife = RT.ensureLong(RT.getIn(minted, "exp")).longValue()
			- RT.ensureLong(RT.getIn(minted, "iat")).longValue();
		long appliedLife = RT.ensureLong(RT.getIn(applied, "exp")).longValue()
			- RT.ensureLong(RT.getIn(applied, "iat")).longValue();
		assertEquals(appliedLife, mintedLife, "mintToken and apply must agree on lifetime");
	}

	@Test
	public void testNonMintingStrategiesThrow() {
		// A stored-credential mint must fail loudly at the point of use —
		// never return null that surfaces later as a 'Bearer null' 401.
		assertThrows(UnsupportedOperationException.class, () -> VenueAuth.none().mintToken());
		assertThrows(UnsupportedOperationException.class, () -> VenueAuth.bearer("tok").mintToken());
		assertThrows(UnsupportedOperationException.class, () -> VenueAuth.local("did:key:z6MkX").mintToken());
	}
}
