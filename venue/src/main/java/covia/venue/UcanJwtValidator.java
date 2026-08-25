package covia.venue;

import java.nio.charset.StandardCharsets;

import convex.auth.did.DID;
import convex.auth.did.DIDVerifier;
import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.core.crypto.ASignature;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * Venue UCAN JWT compatibility boundary.
 *
 * <p>Current tokens must emit the Convex UCAN {@code 0.10.0} profile. For
 * compatibility with clients that predate that profile marker, verification
 * also accepts an absent {@code ucv} as the current version and an absent
 * {@code prf} as an empty proof chain. Explicit unsupported versions and
 * malformed claims remain failures.</p>
 *
 * <p>The signature is always checked over the original JWT signing input
 * before defaults are added to the in-memory payload. Compatibility therefore
 * cannot make a modified or unsigned token valid.</p>
 */
public final class UcanJwtValidator {

	private static final AString JWT_TYPE = Strings.intern("JWT");
	private static final int ED25519_SIGNATURE_LENGTH = 64;

	private UcanJwtValidator() {}

	/** Result of validation, including a safe diagnostic on failure. */
	public record Validation(UCAN token, String reason) {
		public boolean valid() {
			return token != null;
		}
	}

	/** Validate a UCAN JWT with the venue's narrow legacy-profile defaults. */
	public static Validation validate(AString jwtString, long nowSeconds, DIDVerifier verifier) {
		if (jwtString == null) return failure("missing token");
		if (verifier == null) return failure("no DID signature verifier is configured");

		JWT jwt = JWT.parse(jwtString);
		if (jwt == null) {
			return failure("unparseable JWT: expected three base64url-encoded segments");
		}
		if (!"EdDSA".equals(jwt.getAlgorithm())) {
			return failure("unsupported JWT algorithm: expected EdDSA, got "
				+ printable(jwt.getAlgorithm()));
		}
		if (jwt.getSignatureBytes().length != ED25519_SIGNATURE_LENGTH) {
			return failure("malformed EdDSA signature: expected 64 bytes, got "
				+ jwt.getSignatureBytes().length);
		}
		AString type = RT.ensureString(jwt.getHeader().get(JWT.TYP));
		if (!JWT_TYPE.equals(type)) {
			return failure("unsupported JWT type: expected \"JWT\", got " + printable(type));
		}

		AMap<AString, ACell> claims = jwt.getClaims();
		if (claims == null) return failure("missing JWT claims");

		ACell version = claims.get(UCAN.UCV);
		if (claims.containsKey(UCAN.UCV) && !UCAN.VERSION.equals(version)) {
			return failure("unsupported UCAN version: expected \"" + UCAN.VERSION
				+ "\", got " + printable(version));
		}

		AString issuer = RT.ensureString(claims.get(UCAN.ISS));
		if (!isDID(issuer)) return failure("missing or malformed required claim \"iss\": expected a DID");
		AString audience = RT.ensureString(claims.get(UCAN.AUD));
		if (!isDID(audience)) return failure("missing or malformed required claim \"aud\": expected a DID");

		if (!claims.containsKey(UCAN.EXP)) {
			return failure("missing required claim \"exp\" (use null for a non-expiring token)");
		}
		ACell expiry = claims.get(UCAN.EXP);
		if (expiry != null && !(expiry instanceof CVMLong)) {
			return failure("malformed claim \"exp\": expected an integer Unix timestamp or null");
		}
		if (claims.containsKey(UCAN.NBF) && !(claims.get(UCAN.NBF) instanceof CVMLong)) {
			return failure("malformed claim \"nbf\": expected an integer Unix timestamp");
		}
		if (!(claims.get(UCAN.ATT) instanceof AVector)) {
			return failure("missing or malformed required claim \"att\": expected an array");
		}
		ACell proofsCell = claims.get(UCAN.PRF);
		if (claims.containsKey(UCAN.PRF) && !(proofsCell instanceof AVector)) {
			return failure("malformed claim \"prf\": expected an array");
		}

		Blob signingInput = Blob.wrap(jwt.getSigningInput().getBytes(StandardCharsets.UTF_8));
		Blob signature = Blob.wrap(jwt.getSignatureBytes());
		boolean signatureValid;
		try {
			signatureValid = verifier.verifies(issuer, signingInput, signature);
		} catch (Throwable t) {
			signatureValid = false;
		}
		if (!signatureValid) return failure("bad signature for issuer " + issuer);

		// Normalise only after verifying the bytes the client actually signed.
		AMap<AString, ACell> normalised = claims;
		if (!normalised.containsKey(UCAN.UCV)) normalised = normalised.assoc(UCAN.UCV, UCAN.VERSION);
		if (!normalised.containsKey(UCAN.PRF)) normalised = normalised.assoc(UCAN.PRF, Vectors.empty());

		UCAN token;
		try {
			token = UCAN.fromPayload(normalised, ASignature.fromBlob(signature));
		} catch (RuntimeException e) {
			return failure("malformed UCAN payload or signature");
		}

		Long exp = token.getExpiry();
		if (exp != null && exp <= nowSeconds) {
			return failure("expired: exp " + exp + " is not after current time " + nowSeconds);
		}
		Long nbf = token.getNotBefore();
		if (nbf != null && nbf > nowSeconds) {
			return failure("not yet valid: nbf " + nbf + " is after current time " + nowSeconds);
		}

		@SuppressWarnings("unchecked")
		AVector<ACell> proofs = (AVector<ACell>) normalised.get(UCAN.PRF);
		for (long i = 0; i < proofs.count(); i++) {
			AString proofJwt = RT.ensureString(proofs.get(i));
			if (proofJwt == null) {
				return failure("invalid proof[" + i + "]: expected a UCAN JWT string");
			}
			Validation parent = validate(proofJwt, nowSeconds, verifier);
			if (!parent.valid()) {
				return failure("invalid proof[" + i + "]: " + parent.reason());
			}
			if (!issuer.equals(parent.token().getAudience())) {
				return failure("invalid proof[" + i + "]: audience does not match child issuer");
			}
		}

		return new Validation(token, null);
	}

	/** Validate and return only the token, for fail-closed enforcement paths. */
	public static UCAN validateJWT(AString jwtString, long nowSeconds, DIDVerifier verifier) {
		return validate(jwtString, nowSeconds, verifier).token();
	}

	/** Parse transport proof JWTs through the same compatibility boundary. */
	public static AVector<ACell> parseTransportUCANs(AVector<ACell> ucans,
			DIDVerifier verifier) {
		if (ucans == null || ucans.isEmpty() || verifier == null) return null;
		long now = System.currentTimeMillis() / 1000;
		AVector<ACell> result = Vectors.empty();
		for (long i = 0; i < ucans.count(); i++) {
			AString jwt = RT.ensureString(ucans.get(i));
			if (jwt == null) continue;
			UCAN token = validateJWT(jwt, now, verifier);
			if (token != null) result = result.conj(token.toMap());
		}
		return result.isEmpty() ? null : result;
	}

	private static Validation failure(String reason) {
		return new Validation(null, reason);
	}

	private static boolean isDID(AString value) {
		if (value == null || value.isEmpty()) return false;
		try {
			DID did = DID.fromString(value.toString());
			return did != null && !did.getMethod().isEmpty() && !did.getID().isEmpty();
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static String printable(Object value) {
		return (value == null) ? "none" : "\"" + value + "\"";
	}
}
