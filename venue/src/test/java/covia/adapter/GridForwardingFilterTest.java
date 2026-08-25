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
import covia.venue.RequestContext;

/**
 * Deterministic unit tests for the grid hop's token relay filter (covia#100
 * C3a): relay only what could be admissible at the target, drop the provably
 * inert (expired / unparseable / wrong audience), keep identity credentials
 * out of the grant channel, and validate {@code venue/relay} only as authority.
 */
public class GridForwardingFilterTest {

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
		List<UCAN> parsed = GridAdapter.parsedRawUcans(ctx,
			convex.auth.did.DIDVerifier.CONVEX);

		assertEquals(identityForTarget,
			GridAdapter.identityCredential(ctx, parsed, BOB, VENUE));
		assertEquals(List.of(grantToBob), GridAdapter.admissibleGrants(ctx, parsed, BOB),
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

		List<String> out = GridAdapter.admissibleGrants(ctx,
			GridAdapter.parsedRawUcans(ctx, convex.auth.did.DIDVerifier.CONVEX), VENUE);
		assertNotNull(out);
		assertEquals(List.of(chainToVenue), out,
			"only the venue-audienced token is relayed when the venue is the principal");
	}

	@Test
	public void testExpiredAndGarbageDropped() {
		String expired = token(aliceKP, BOB, ALICE + "/w/", "crud/read", -3600);
		RequestContext ctx = ctxWith(BOB, expired, "not-a-jwt");
		assertNull(GridAdapter.admissibleGrants(ctx,
			GridAdapter.parsedRawUcans(ctx, convex.auth.did.DIDVerifier.CONVEX), BOB),
			"expired and unparseable tokens are provably inert — nothing to relay");
	}

	@Test
	public void testRelayGrantRecognisedOnlyFromCaller() {
		String bobRelay = token(bobKP, VENUE, BOB.toString(), "venue/relay", 3600);
		// This answers only whether an explicit venue-mode request is authorised.
		RequestContext bobCtx = ctxWith(BOB, bobRelay);
		assertTrue(GridAdapter.hasRelayGrant(
			GridAdapter.parsedRawUcans(bobCtx, convex.auth.did.DIDVerifier.CONVEX), BOB, VENUE));
		// Carol cannot exercise Bob's grant.
		AString CAROL = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		RequestContext carolCtx = ctxWith(CAROL, bobRelay);
		assertFalse(GridAdapter.hasRelayGrant(
			GridAdapter.parsedRawUcans(carolCtx, convex.auth.did.DIDVerifier.CONVEX), CAROL, VENUE));
	}

	@Test
	public void testRelayGrantRequiresExactResourceAndAbility() {
		String grantOnly = token(bobKP, VENUE, ALICE + "/w/", "crud/read", 3600);
		RequestContext ctx = ctxWith(BOB, grantOnly);
		assertFalse(GridAdapter.hasRelayGrant(
			GridAdapter.parsedRawUcans(ctx, convex.auth.did.DIDVerifier.CONVEX), BOB, VENUE));

		String wrongResource = token(bobKP, VENUE, ALICE.toString(), "venue/relay", 3600);
		RequestContext wrong = ctxWith(BOB, wrongResource);
		assertFalse(GridAdapter.hasRelayGrant(
			GridAdapter.parsedRawUcans(wrong, convex.auth.did.DIDVerifier.CONVEX), BOB, VENUE));
	}
}
