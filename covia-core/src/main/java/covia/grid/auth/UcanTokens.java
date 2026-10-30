package covia.grid.auth;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.Vectors;

/**
 * Client-side UCAN minting for self-sovereign callers — the tokens a principal
 * signs with their <b>own</b> key. These deliberately have no venue-op
 * counterpart: a venue cannot sign as the caller without holding the caller's
 * key, so principals speak for themselves here and venues only verify.
 *
 * <p>All helpers return JWT encoding. Capability grants travel through
 * {@link covia.grid.Venue#setUcans}; an identity token is instead an
 * authentication credential (for example {@code VenueAuth.bearer(token)}).
 * A caller may carry a target-audienced identity token to a relay in its raw
 * UCAN envelope, but it remains inert there and is installed as the target
 * request's bearer only after an explicit {@code authenticateAs=caller}.</p>
 *
 * <p>Two token shapes (see the venue UCAN spec §5.6):</p>
 * <ul>
 *   <li>{@link #identityToken} — proves <em>who you are</em> to one venue;</li>
 *   <li>{@link #grant} — delegates capability over your resources to someone.</li>
 * </ul>
 */
public final class UcanTokens {

	private UcanTokens() {}

	/** The did:key DID for a key pair. */
	public static String did(AKeyPair kp) {
		return UCAN.toDIDKey(kp.getAccountKey()).toString();
	}

	/**
	 * Mints an <b>identity token</b>: a UCAN with an empty attenuation list,
	 * audienced to {@code venueDID}. Pure proof of identity — it grants
	 * nothing, and being audience-bound it is unusable at any other venue.
	 * Use it as the target venue's bearer credential. For a grid relay, carry it
	 * in the incoming raw UCAN envelope and explicitly request
	 * {@code authenticateAs=caller}; its presence alone has no effect.
	 *
	 * @param kp         the caller's key pair (signs; its did:key is the identity)
	 * @param venueDID   the DID of the venue this token authenticates to
	 * @param ttlSeconds validity window — keep short (e.g. 300)
	 * @return the identity token as a JWT string
	 */
	public static String identityToken(AKeyPair kp, String venueDID, long ttlSeconds) {
		long exp = (System.currentTimeMillis() / 1000) + ttlSeconds;
		return mint(kp, venueDID, exp, Vectors.empty());
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
		return mint(ownerKP, audienceDID, exp,
			Vectors.of(Capability.create(Strings.create(with), Strings.create(can))));
	}

	/** Uses Convex's any-DID payload API; no helper assumes the audience method. */
	private static String mint(AKeyPair kp, String audienceDID, long exp,
			AVector<ACell> att) {
		if (kp == null) throw new IllegalArgumentException("key pair is required");
		try {
			if (audienceDID == null || audienceDID.isBlank()
					|| DID.fromString(audienceDID) == null) {
				throw new IllegalArgumentException();
			}
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("audienceDID must be a valid DID: "
				+ audienceDID, e);
		}
		AMap<convex.core.data.AString, ACell> payload = UCAN.buildPayload(
			kp.getAccountKey(), Strings.create(audienceDID), exp, null,
			att, Vectors.empty(), null);
		return UCAN.signJWT(payload, kp).toString();
	}
}
