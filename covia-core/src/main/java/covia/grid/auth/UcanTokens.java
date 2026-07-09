package covia.grid.auth;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.Vectors;

/**
 * Client-side UCAN minting for self-sovereign callers — the tokens a principal
 * signs with their <b>own</b> key. These deliberately have no venue-op
 * counterpart: a venue cannot sign as the caller without holding the caller's
 * key, so principals speak for themselves here and venues only verify.
 *
 * <p>All helpers return the JWT encoding — the transport form carried in the
 * {@code ucans} request array ({@link covia.grid.Venue#setUcans}) and relayed
 * across cross-venue hops.</p>
 *
 * <p>Three token shapes (see the venue UCAN spec §5.6):</p>
 * <ul>
 *   <li>{@link #identityToken} — proves <em>who you are</em> to one venue;</li>
 *   <li>{@link #grant} — delegates capability over your resources to someone;</li>
 *   <li>{@link #relayDelegation} — instructs+authorises a venue to relay a
 *       cross-venue hop as itself on your behalf.</li>
 * </ul>
 */
public final class UcanTokens {

	/** The ability that instructs a venue to relay a hop as itself. */
	public static final String VENUE_RELAY = "venue/relay";

	private UcanTokens() {}

	/** The did:key DID for a key pair. */
	public static String did(AKeyPair kp) {
		return UCAN.toDIDKey(kp.getAccountKey()).toString();
	}

	/**
	 * Mints an <b>identity token</b>: a UCAN with an empty attenuation list,
	 * audienced to {@code venueDID}. Pure proof of identity — it grants
	 * nothing, and being audience-bound it is unusable at any other venue.
	 * Present it in the {@code ucans} array; the venue accepts it as the
	 * caller identity on an otherwise-unauthenticated transport (the mechanism
	 * that carries identity across cross-venue relays).
	 *
	 * @param kp         the caller's key pair (signs; its did:key is the identity)
	 * @param venueDID   the DID of the venue this token authenticates to
	 * @param ttlSeconds validity window — keep short (e.g. 300)
	 * @return the identity token as a JWT string
	 */
	public static String identityToken(AKeyPair kp, String venueDID, long ttlSeconds) {
		long exp = (System.currentTimeMillis() / 1000) + ttlSeconds;
		UCAN token = UCAN.create(kp, UCAN.fromDIDKey(Strings.create(venueDID)), exp,
			Vectors.empty(), Vectors.empty());
		return token.toJWT(kp).toString();
	}

	/**
	 * Mints an owner-signed (<b>self-sovereign</b>) grant: delegates
	 * {@code (with, can)} to {@code audienceDID}, rooted by the signer. Because
	 * the root issuer is the resource owner, the grant verifies on <em>any</em>
	 * venue hosting the data — no venue involvement in issuance.
	 *
	 * @param ownerKP     the resource owner's key pair (signs the root)
	 * @param audienceDID who receives the capability
	 * @param with        the resource (owner-scoped, e.g. {@code did(ownerKP) + "/w/shared/"})
	 * @param can         the ability (e.g. {@code "crud/read"})
	 * @param ttlSeconds  validity window
	 * @return the grant as a JWT string
	 */
	public static String grant(AKeyPair ownerKP, String audienceDID,
			String with, String can, long ttlSeconds) {
		long exp = (System.currentTimeMillis() / 1000) + ttlSeconds;
		UCAN token = UCAN.create(ownerKP, UCAN.fromDIDKey(Strings.create(audienceDID)), exp,
			Vectors.of(Capability.create(Strings.create(with), Strings.create(can))),
			Vectors.empty());
		return token.toJWT(ownerKP).toString();
	}

	/**
	 * Mints a <b>relay delegation</b>: instructs and authorises {@code venueDID}
	 * to make a cross-venue hop authenticated as itself, exercising the caller's
	 * authority. Carries the {@code venue/relay} instruction plus the substantive
	 * capabilities the venue may exercise (each {@code {with, can}} pair from
	 * {@code caps}). The venue honours the instruction only when its issuer is
	 * the authenticated caller.
	 *
	 * @param kp         the caller's key pair
	 * @param venueDID   the relaying venue's DID (the token's audience)
	 * @param ttlSeconds validity window
	 * @param caps       alternating {@code with, can} pairs for the substantive
	 *                   grants (may be empty for a bare instruction)
	 * @return the delegation as a JWT string
	 */
	public static String relayDelegation(AKeyPair kp, String venueDID,
			long ttlSeconds, String... caps) {
		if (caps.length % 2 != 0) {
			throw new IllegalArgumentException("caps must be alternating with, can pairs");
		}
		long exp = (System.currentTimeMillis() / 1000) + ttlSeconds;
		AVector<ACell> att = Vectors.of(
			(ACell) Capability.create(Strings.create(did(kp)), Strings.create(VENUE_RELAY)));
		for (int i = 0; i < caps.length; i += 2) {
			att = att.conj(Capability.create(Strings.create(caps[i]), Strings.create(caps[i + 1])));
		}
		UCAN token = UCAN.create(kp, UCAN.fromDIDKey(Strings.create(venueDID)), exp,
			att, Vectors.empty());
		return token.toJWT(kp).toString();
	}
}
