package covia.grid.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;

/**
 * A challenge proves a DID at an address only with a proof signed by the
 * DID's own key that names the nonce sent and the address dialled, typed as a
 * challenge proof, short-lived, and naming neither a subject nor an audience.
 */
public class VenueChallengeTest {

	private static final AKeyPair VENUE = AKeyPair.createSeeded(1);
	private static final String DID = UCAN.toDIDKey(VENUE.getAccountKey()).toString();
	private static final String BASE = "https://venue.example";
	private static final long NOW = 1_793_386_800L;

	/** A JWT signed with {@code key} over exactly this header and these claims. */
	static String sign(AKeyPair key, AMap<AString, ACell> header, AMap<AString, ACell> claims) {
		AString signingInput = JWT.encode(JWT.claims(header)).append(".").append(JWT.encode(JWT.claims(claims)));
		return signingInput.append(".")
			.append(JWT.encode(key.sign(Blob.wrap(signingInput.getBytes())).getBytes())).toString();
	}

	private static AMap<AString, ACell> header() {
		return Maps.of(JWT.ALG, "EdDSA", JWT.TYP, VenueChallenge.TYP,
			JWT.KID, DID + "#" + DID.substring("did:key:".length()));
	}

	private static AMap<AString, ACell> claims(String nonce) {
		return Maps.of(JWT.ISS, DID, VenueChallenge.BASE, BASE, VenueChallenge.NONCE, nonce,
			JWT.IAT, NOW, JWT.EXP, NOW + 60);
	}

	private static void assertReason(String reason, String refusal) {
		assertTrue(refusal != null && refusal.startsWith(reason), "expected " + reason + ", got " + refusal);
	}

	@Test
	public void aProofForTheNonceAndAddressProvesTheDID() {
		String nonce = VenueChallenge.nonce();
		String proof = sign(VENUE, header(), claims(nonce));
		assertNull(VenueChallenge.verify(proof, DID, nonce, BASE, NOW));
		assertNull(VenueChallenge.verify(proof, DID, nonce, "HTTPS://Venue.Example:443/", NOW),
			"the address dialled is compared normalised");
	}

	@Test
	public void aProofNamingAnotherAddressIsRefused() {
		String nonce = VenueChallenge.nonce();
		String proof = sign(VENUE, header(), claims(nonce));
		for (String base : new String[] {"https://other.example", "http://venue.example", "https://venue.example:8443"}) {
			assertReason(VenueChallenge.Reason.OTHER_BASE, VenueChallenge.verify(proof, DID, nonce, base, NOW));
		}
		assertReason(VenueChallenge.Reason.NOT_A_BASE, VenueChallenge.verify(proof, DID, nonce, "ftp://venue.example", NOW));
	}

	@Test
	public void onlyTheDIDsOwnKeyProvesIt() {
		String nonce = VenueChallenge.nonce();
		// Signed by another key that the header names, while iss claims the DID
		AKeyPair other = AKeyPair.createSeeded(2);
		AString otherDID = UCAN.toDIDKey(other.getAccountKey());
		String byOther = sign(other, header().assoc(JWT.KID, otherDID), claims(nonce));
		assertReason(VenueChallenge.Reason.SIGNATURE_FAILS, VenueChallenge.verify(byOther, DID, nonce, BASE, NOW));

		String otherIssuer = sign(VENUE, header(), claims(nonce).assoc(JWT.ISS, otherDID));
		assertReason(VenueChallenge.Reason.OTHER_ISSUER, VenueChallenge.verify(otherIssuer, DID, nonce, BASE, NOW));

		String proof = sign(VENUE, header(), claims(nonce));
		assertReason(VenueChallenge.Reason.NO_KEY, VenueChallenge.verify(proof, "did:web:venue.example", nonce, BASE, NOW));
	}

	@Test
	public void aProofForAnotherNonceIsRefused() {
		String proof = sign(VENUE, header(), claims(VenueChallenge.nonce()));
		assertReason(VenueChallenge.Reason.OTHER_NONCE, VenueChallenge.verify(proof, DID, VenueChallenge.nonce(), BASE, NOW));
	}

	@Test
	public void onlyAChallengeProofTypeIsAccepted() {
		String nonce = VenueChallenge.nonce();
		assertReason(VenueChallenge.Reason.WRONG_TYPE,
			VenueChallenge.verify(sign(VENUE, header().dissoc(JWT.TYP), claims(nonce)), DID, nonce, BASE, NOW));
		assertReason(VenueChallenge.Reason.WRONG_TYPE, VenueChallenge.verify(
			sign(VENUE, header().assoc(JWT.TYP, Strings.create("JWT")), claims(nonce)), DID, nonce, BASE, NOW));
		assertReason(VenueChallenge.Reason.WRONG_ALGORITHM, VenueChallenge.verify(
			sign(VENUE, header().assoc(JWT.ALG, Strings.create("HS256")), claims(nonce)), DID, nonce, BASE, NOW));
		assertReason(VenueChallenge.Reason.UNPARSEABLE, VenueChallenge.verify("not.a.jwt", DID, nonce, BASE, NOW));
		assertReason(VenueChallenge.Reason.NO_PROOF, VenueChallenge.verify(null, DID, nonce, BASE, NOW));
	}

	@Test
	public void aProofNamesNoSubjectAndNoAudience() {
		String nonce = VenueChallenge.nonce();
		assertReason(VenueChallenge.Reason.SUBJECT_OR_AUDIENCE, VenueChallenge.verify(
			sign(VENUE, header(), claims(nonce).assoc(JWT.SUB, Strings.create(DID))), DID, nonce, BASE, NOW));
		assertReason(VenueChallenge.Reason.SUBJECT_OR_AUDIENCE, VenueChallenge.verify(
			sign(VENUE, header(), claims(nonce).assoc(JWT.AUD, Strings.create(DID))), DID, nonce, BASE, NOW));
	}

	@Test
	public void aProofIsShortLivedAndCurrent() {
		String nonce = VenueChallenge.nonce();
		String longLived = sign(VENUE, header(),
			claims(nonce).assoc(JWT.EXP, CVMLong.create(NOW + VenueChallenge.MAX_LIFETIME + 1)));
		assertReason(VenueChallenge.Reason.LIFETIME_EXCEEDS, VenueChallenge.verify(longLived, DID, nonce, BASE, NOW));

		String proof = sign(VENUE, header(), claims(nonce));
		long lastValid = NOW + 60 + VenueChallenge.CLOCK_SKEW;
		assertNull(VenueChallenge.verify(proof, DID, nonce, BASE, lastValid));
		assertReason(VenueChallenge.Reason.EXPIRED, VenueChallenge.verify(proof, DID, nonce, BASE, lastValid + 1));
		assertReason(VenueChallenge.Reason.NOT_YET_VALID,
			VenueChallenge.verify(proof, DID, nonce, BASE, NOW - VenueChallenge.CLOCK_SKEW - 1));

		String undated = sign(VENUE, header(), claims(nonce).dissoc(JWT.IAT));
		assertReason(VenueChallenge.Reason.MALFORMED_TIMES, VenueChallenge.verify(undated, DID, nonce, BASE, NOW));
	}

	@Test
	public void aNonceIs32RandomBytesInBase64url() {
		String nonce = VenueChallenge.nonce();
		assertTrue(nonce.matches("[A-Za-z0-9_-]{43}"), nonce);
		assertNotEquals(nonce, VenueChallenge.nonce());
	}

	@Test
	public void baseURLsNormaliseSchemeHostPortAndTrailingSlash() {
		assertEquals("https://venue.example", VenueChallenge.normalise("HTTPS://Venue.EXAMPLE:443/"));
		assertEquals("http://127.0.0.1:8080", VenueChallenge.normalise("http://127.0.0.1:8080/"));
		assertEquals("http://venue.example", VenueChallenge.normalise("http://venue.example:80"));
		assertEquals("https://venue.example:8443/covia", VenueChallenge.normalise("https://venue.example:8443/covia/"));
		assertNull(VenueChallenge.normalise("https://user@venue.example"));
		assertNull(VenueChallenge.normalise("https://venue.example/?q=1"));
		assertNull(VenueChallenge.normalise("did:key:z6Mk"));
		assertNull(VenueChallenge.normalise(" https://venue.example"));
	}
}
