package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Deterministic unit tests for the grid hop's token relay filter (covia#100
 * C3a): relay only what could be admissible at the target, drop the provably
 * inert (expired / unparseable / wrong audience), and keep identity credentials
 * out of the grant channel.
 */
public class GridForwardingFilterTest {

	private final Engine engine = TestEngine.ENGINE;
	private final AKeyPair aliceKP = AKeyPair.generate();
	private final AKeyPair bobKP = AKeyPair.generate();
	private final AKeyPair venueKP = AKeyPair.generate();
	private final AString ALICE = UCAN.toDIDKey(aliceKP.getAccountKey());
	private final AString BOB = UCAN.toDIDKey(bobKP.getAccountKey());
	private final AString VENUE = UCAN.toDIDKey(venueKP.getAccountKey());

	private String token(AKeyPair issuerKP, AString audience, String withRes, String ability, long ttl) {
		long exp = (System.currentTimeMillis() / 1000) + ttl;
		AVector<ACell> caps = (ability == null) ? Vectors.empty()
			: Vectors.of(Capability.create(Strings.create(withRes), Strings.create(ability)));
		return UCAN.create(issuerKP, UCAN.fromDIDKey(audience), exp, caps, Vectors.empty())
			.toJWT(issuerKP).toString();
	}

	private RequestContext ctxWith(AString caller, String... jwts) {
		AVector<ACell> raw = Vectors.empty();
		for (String j : jwts) raw = raw.conj(Strings.create(j));
		return RequestContext.of(caller).withRawUcans(raw);
	}

	@Test
	public void testCallerModeSeparatesIdentityCredentialFromGrants() {
		String grantToBob = token(aliceKP, BOB, ALICE + "/w/", "crud/read", 3600);
		String identityForTarget = token(bobKP, VENUE, null, null, 300);
		RequestContext ctx = ctxWith(BOB, grantToBob, identityForTarget);
		List<UCAN> parsed = VenueConnection.parsedRawUcans(engine, ctx);

		assertEquals(identityForTarget,
			VenueConnection.identityCredential(engine, ctx, parsed, BOB, VENUE));
		assertEquals(List.of(grantToBob), VenueConnection.admissibleGrants(engine, ctx, parsed, BOB),
			"the identity credential travels as authentication, never as a grant");
	}

	@Test
	public void testRelayAsSelfDropsCallerAudiencedTokens() {
		// Principal = the venue (relay-as-self): a token audienced to the CALLER
		// is provably inert at the target (the caller is not the principal there)
		// — dropped, so the caller's unrelated grants are not disclosed.
		String grantToBob = token(aliceKP, BOB, ALICE + "/w/", "crud/read", 3600);
		String chainToVenue = token(aliceKP, VENUE, ALICE + "/w/", "crud/read", 3600);
		RequestContext ctx = ctxWith(BOB, grantToBob, chainToVenue);

		List<String> out = VenueConnection.admissibleGrants(engine, ctx,
			VenueConnection.parsedRawUcans(engine, ctx), VENUE);
		assertNotNull(out);
		assertEquals(List.of(chainToVenue), out,
			"only the venue-audienced token is relayed when the venue is the principal");
	}

	@Test
	public void testExpiredAndGarbageDropped() {
		String expired = token(aliceKP, BOB, ALICE + "/w/", "crud/read", -3600);
		RequestContext ctx = ctxWith(BOB, expired, "not-a-jwt");
		assertNull(VenueConnection.admissibleGrants(engine, ctx,
			VenueConnection.parsedRawUcans(engine, ctx), BOB),
			"expired and unparseable tokens are provably inert — nothing to relay");
	}

	@Test
	public void callerIdentityMustBeSignedByCallerForThisDestinationAndCarryNoGrants() {
		String valid = token(bobKP, VENUE, null, null, 300);
		String[] parts = valid.split("\\.");
		String forged = parts[0] + "." + parts[1] + "." + (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
		var invalid = java.util.Map.of(
			"wrong issuer", token(aliceKP, VENUE, null, null, 300),
			"wrong destination", token(bobKP, ALICE, null, null, 300),
			"expired", token(bobKP, VENUE, null, null, -3600),
			"forged signature", forged,
			"capability grant is not identity", token(bobKP, VENUE, BOB + "/j/", "job", 300),
			"malformed", "not-a-jwt");
		for (var example : invalid.entrySet()) {
			RequestContext ctx = ctxWith(BOB, example.getValue());
			assertNull(VenueConnection.identityCredential(engine, ctx,
				VenueConnection.parsedRawUcans(engine, ctx), BOB, VENUE), example.getKey());
		}
		RequestContext legitimate = ctxWith(BOB, valid);
		assertEquals(valid, VenueConnection.identityCredential(engine, legitimate,
			VenueConnection.parsedRawUcans(engine, legitimate), BOB, VENUE));
	}

	@Test
	public void conflictingIdentityCredentialsAreRefusedRatherThanChosenByOrder() {
		String first = token(bobKP, VENUE, null, null, 300);
		String second = token(bobKP, VENUE, null, null, 600);
		for (String[] order : new String[][]{{first, second}, {second, first}}) {
			RequestContext ctx = ctxWith(BOB, order);
			assertThrows(covia.exception.InvalidInputException.class, () ->
				VenueConnection.identityCredential(engine, ctx, VenueConnection.parsedRawUcans(engine, ctx), BOB, VENUE));
		}
		RequestContext duplicates = ctxWith(BOB, first, first);
		assertEquals(first, VenueConnection.identityCredential(engine, duplicates,
			VenueConnection.parsedRawUcans(engine, duplicates), BOB, VENUE));
	}

}
