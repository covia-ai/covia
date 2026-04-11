package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.RequestContext;

/**
 * Adapter for UCAN token operations.
 *
 * <p>Phase C1: venue-signed tokens. The venue is the issuer (resource owner
 * for all data it hosts). User-issued tokens (signed with user's own key)
 * require client-side signing — deferred to Phase C2.</p>
 */
public class UCANAdapter extends AAdapter {

	@Override
	public String getName() {
		return "ucan";
	}

	@Override
	public String getDescription() {
		return "UCAN token operations for capability-based authorisation.";
	}

	@Override
	protected void installAssets() {
		installAsset("ucan/issue", "/adapters/ucan/issue.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		if (ctx.getCallerDID() == null) {
			return CompletableFuture.failedFuture(new RuntimeException("Authentication required"));
		}
		try {
			return switch (getSubOperation(meta)) {
				case "issue" -> CompletableFuture.completedFuture(handleIssue(ctx, input));
				default -> CompletableFuture.failedFuture(
					new RuntimeException("Unknown ucan operation: " + getSubOperation(meta)));
			};
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/**
	 * Issues a venue-signed UCAN token.
	 *
	 * <p>The venue signs with its own key pair. The issuer DID is the venue's DID.
	 * The caller must be authenticated. The token grants capabilities on the
	 * venue's hosted resources.</p>
	 *
	 * @return The complete signed UCAN token as a CVM map
	 */
	private ACell handleIssue(RequestContext ctx, ACell input) {
		AString audDID = RT.ensureString(RT.getIn(input, UCAN.AUD));
		if (audDID == null) {
			throw new RuntimeException("aud (audience DID) is required");
		}

		@SuppressWarnings("unchecked")
		AVector<ACell> att = RT.getIn(input, UCAN.ATT);
		if (att == null || att.count() == 0) {
			throw new RuntimeException("att (attenuations) is required and must not be empty");
		}

		CVMLong expCell = RT.ensureLong(RT.getIn(input, UCAN.EXP));
		if (expCell == null) {
			throw new RuntimeException("exp (expiry unix seconds) is required");
		}
		long exp = expCell.longValue();

		// Validate: all 'with' fields must be DID URLs scoped to the caller's namespace
		AString callerDID = ctx.getCallerDID();
		String callerPrefix = callerDID.toString() + "/";
		for (long i = 0; i < att.count(); i++) {
			AString with = RT.ensureString(RT.getIn(att.get(i), Capability.WITH));
			if (with == null || !with.toString().startsWith(callerPrefix)) {
				throw new RuntimeException(
					"att[" + i + "].with must be a DID URL in your namespace (e.g. " + callerPrefix + "w/)");
			}
		}

		// Resolve audience public key from DID
		AccountKey audKey = UCAN.fromDIDKey(audDID);
		if (audKey == null) {
			throw new RuntimeException("Cannot resolve audience public key from DID: " + audDID);
		}

		// Sign with venue key pair — venue is the issuer, return as JWT
		AKeyPair venueKP = engine.getKeyPair();
		UCAN token = UCAN.create(venueKP, audKey, exp, att, Vectors.empty());

		return Maps.of("token", token.toJWT(venueKP));
	}
}
