package covia.grid.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import convex.auth.jwt.JWT;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.exception.AuthException;

/**
 * Deterministic unit tests for {@link KeyPairAuth} (covia#199): the per-request
 * self-issued JWT names the venue the connection reaches as {@code aud}
 * (audience binding — capture-replay containment) and honours a configured
 * lifetime. Claims are read back by verifying the actual Authorization header
 * the auth applies.
 */
public class KeyPairAuthTest {

	private static final String VENUE_DID = "did:key:z6MkExampleVenueDID";

	/** Applies the auth for a request to {@code venueDID} and returns the raw bearer JWT. */
	private static AString tokenFor(VenueAuth auth, String venueDID) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:1/x")).GET();
		auth.apply(b, () -> venueDID);
		Optional<String> header = b.build().headers().firstValue("Authorization");
		assertTrue(header.isPresent(), "auth must set an Authorization header");
		return Strings.create(header.get().substring("Bearer ".length()));
	}

	/** Applies the auth to a request and returns the verified JWT claims. */
	private static AMap<AString, ACell> claimsFor(VenueAuth auth) {
		AMap<AString, ACell> claims = JWT.verifyPublic(tokenFor(auth, VENUE_DID));
		assertNotNull(claims, "self-issued JWT must verify against its embedded key");
		return claims;
	}

	private static AString kidFor(VenueAuth auth) {
		JWT jwt = JWT.parse(tokenFor(auth, VENUE_DID));
		assertNotNull(jwt);
		return RT.ensureString(jwt.getHeader().get(JWT.KID));
	}

	@Test
	public void testAudienceIsTheConnectionsVenue() {
		AKeyPair kp = AKeyPair.generate();
		VenueAuth auth = VenueAuth.keyPair(kp);
		AMap<AString, ACell> claims = claimsFor(auth);
		assertEquals(Strings.create(VENUE_DID), RT.getIn(claims, "aud"),
			"aud claim must carry the DID of the venue the connection reaches");
		assertEquals(RT.getIn(claims, "iss"), RT.getIn(claims, "sub"), "self-issued: iss == sub");
		assertEquals(Multikey.encodePublicKey(kp.getAccountKey()), kidFor(auth),
			"ordinary did:key auth keeps the bare Multikey kid");
	}

	@Test
	public void testNoTokenWithoutTheVenue() {
		// A connection that cannot establish its venue's DID sends no credential.
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:1/x")).GET();
		VenueAuth auth = VenueAuth.keyPair(AKeyPair.generate());
		assertThrows(AuthException.class, () -> auth.apply(b, () -> {
			throw new AuthException(VenueDID.UNPROVEN);
		}));
		assertTrue(b.build().headers().firstValue("Authorization").isEmpty());
	}

	@Test
	public void testConfigurableLifetime() {
		AKeyPair kp = AKeyPair.generate();
		long lifetime = 30 * 24 * 3600; // a long-lived minted credential
		AMap<AString, ACell> claims = claimsFor(VenueAuth.keyPair(kp, lifetime));
		long iat = RT.ensureLong(RT.getIn(claims, "iat")).longValue();
		long exp = RT.ensureLong(RT.getIn(claims, "exp")).longValue();
		assertEquals(lifetime, exp - iat, "exp - iat must equal the configured lifetime");
	}

	@Test
	public void testInvalidLifetimeRejected() {
		AKeyPair kp = AKeyPair.generate();
		assertThrows(IllegalArgumentException.class, () -> VenueAuth.keyPair(kp, 0));
		assertThrows(IllegalArgumentException.class, () -> VenueAuth.keyPair(kp, -5));
	}

	@Test
	public void testNamedUserClaimsAndIdentity() {
		AKeyPair kp = AKeyPair.generate();
		String subject = "did:web:venue.example:u:alice";
		VenueAuth auth = VenueAuth.namedKeyPair(kp, subject);

		AMap<AString, ACell> claims = claimsFor(auth);
		assertEquals(Strings.create(subject), RT.getIn(claims, "sub"));
		assertEquals(Strings.create(subject), RT.getIn(claims, "iss"));
		assertEquals(Strings.create(VENUE_DID), RT.getIn(claims, "aud"));
		assertEquals(subject, auth.getDID());
		AString multikey = Multikey.encodePublicKey(kp.getAccountKey());
		assertEquals(Strings.create(subject + "#" + multikey), kidFor(auth),
			"named auth kid must match the verification-method DID URL");

		AMap<AString, ACell> minted =
			JWT.verifyPublic(Strings.create(auth.mintToken(VENUE_DID)));
		assertNotNull(minted);
		assertEquals(Strings.create(subject), RT.getIn(minted, "sub"));
		assertEquals(Strings.create(subject), RT.getIn(minted, "iss"));
		assertEquals(Strings.create(VENUE_DID), RT.getIn(minted, "aud"));
	}

	@Test
	public void testNamedUserRequiresSubjectDid() {
		AKeyPair kp = AKeyPair.generate();
		String subject = "did:web:venue.example:u:alice";

		assertThrows(IllegalArgumentException.class, () -> VenueAuth.namedKeyPair(kp, null));
		assertThrows(IllegalArgumentException.class, () -> VenueAuth.namedKeyPair(kp, "alice"));
		assertThrows(IllegalArgumentException.class, () -> VenueAuth.namedKeyPair(kp, subject, 0));
	}

	// ========== mintToken (#219) ==========

	@Test
	public void testMintTokenClaims() {
		AKeyPair kp = AKeyPair.generate();
		long lifetime = 30 * 24 * 3600; // the getmine GETMINE_TOKEN shape
		VenueAuth auth = VenueAuth.keyPair(kp, lifetime);

		String token = auth.mintToken(VENUE_DID);
		AMap<AString, ACell> claims = JWT.verifyPublic(Strings.create(token));
		assertNotNull(claims, "minted JWT must verify against its embedded key");
		assertEquals(Strings.create(auth.getDID()), RT.getIn(claims, "sub"));
		assertEquals(RT.getIn(claims, "iss"), RT.getIn(claims, "sub"), "self-issued: iss == sub");
		assertEquals(Strings.create(VENUE_DID), RT.getIn(claims, "aud"));
		long iat = RT.ensureLong(RT.getIn(claims, "iat")).longValue();
		long exp = RT.ensureLong(RT.getIn(claims, "exp")).longValue();
		assertEquals(lifetime, exp - iat, "exp - iat must equal the configured lifetime");
	}

	@Test
	public void testMintTokenNamesAVenue() {
		VenueAuth auth = VenueAuth.keyPair(AKeyPair.generate());
		assertThrows(IllegalArgumentException.class, () -> auth.mintToken(null));
		assertThrows(IllegalArgumentException.class, () -> auth.mintToken("venue.example"));
	}

	@Test
	public void testMintTokenMatchesApply() {
		// The contract: a minted token is exactly what a request would carry.
		// iat/exp are clock-dependent, so compare identity claims + lifetime.
		AKeyPair kp = AKeyPair.generate();
		VenueAuth auth = VenueAuth.keyPair(kp, 600);

		AMap<AString, ACell> minted = JWT.verifyPublic(Strings.create(auth.mintToken(VENUE_DID)));
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
		assertThrows(UnsupportedOperationException.class, () -> VenueAuth.none().mintToken(VENUE_DID));
		assertThrows(UnsupportedOperationException.class, () -> VenueAuth.bearer("tok").mintToken(VENUE_DID));
		assertThrows(UnsupportedOperationException.class, () -> VenueAuth.local("did:key:z6MkX").mintToken(VENUE_DID));
	}
}
