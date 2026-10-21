package covia.venue.auth;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;

/**
 * Requirements on a single trusted authentication result, independent of method
 * and transport. All requirements are conjunctive; properties are exact names,
 * with no implied hierarchy or interpretation of method-owned details.
 *
 * <p>This checks evidence, not authority or current verifier/session validity.
 * The caller must obtain a result through the current trusted verification path
 * for this request. It must not reuse request proof or treat stored/client data
 * as verified evidence. Admission and capability checks still apply.</p>
 *
 * @param allowedMethods null leaves the origin unrestricted; an empty set allows none
 * @param requiredProperties properties required on the originating authentication event
 * @param requiredProofProperties properties required on this request's proof
 * @param maxAuthenticationAgeSeconds optional inclusive age limit on the event
 * @param maxProofAgeSeconds optional inclusive age limit on the request proof
 */
public record EvidencePolicy(ASet<AString> allowedMethods,
		ASet<AString> requiredProperties, ASet<AString> requiredProofProperties,
		Long maxAuthenticationAgeSeconds, Long maxProofAgeSeconds) {

	public static final AString ALLOWED_METHODS = Strings.intern("allowedMethods");
	public static final AString REQUIRED_PROPERTIES = Strings.intern("requiredProperties");
	public static final AString REQUIRED_PROOF_PROPERTIES = Strings.intern("requiredProofProperties");
	public static final AString MAX_AUTHENTICATION_AGE_SECONDS = Strings.intern("maxAuthenticationAgeSeconds");
	public static final AString MAX_PROOF_AGE_SECONDS = Strings.intern("maxProofAgeSeconds");

	public static final String INVALID_NAMES = "Evidence policy names must be non-blank strings";
	public static final String INVALID_AGE = "Evidence policy age limits must be non-negative seconds";
	public static final String INVALID_NOW = "Evaluation time must be non-negative Unix seconds";
	public static final String MISSING_RESULT = "Verified authentication evidence is required";
	public static final String CREDENTIAL_EXPIRED = "Authentication credential has expired";
	public static final String METHOD_NOT_ALLOWED = "Originating authentication method is not allowed";
	public static final String MISSING_PROPERTIES = "Required authentication properties are not established";
	public static final String AUTHENTICATION_NOT_FRESH = "Authentication time is missing, invalid or too old";
	public static final String MISSING_PROOF_PROPERTIES = "Required request proof properties are not established";
	public static final String PROOF_NOT_FRESH = "Request proof time is missing, invalid or too old";

	public EvidencePolicy {
		if (allowedMethods != null) validateNames(allowedMethods);
		validateNames(requiredProperties);
		validateNames(requiredProofProperties);
		if ((maxAuthenticationAgeSeconds != null && maxAuthenticationAgeSeconds < 0)
				|| (maxProofAgeSeconds != null && maxProofAgeSeconds < 0)) {
			throw new IllegalArgumentException(INVALID_AGE);
		}
	}

	private static void validateNames(ASet<?> names) {
		if (names == null) throw new IllegalArgumentException(INVALID_NAMES);
		for (ACell name : names) {
			if (!(name instanceof AString s) || s.toString().isBlank()) {
				throw new IllegalArgumentException(INVALID_NAMES);
			}
		}
	}

	/**
	 * Returns null on success, otherwise a refusal reason. Time is supplied by
	 * trusted host code in Unix seconds. No clock skew is inferred: missing,
	 * negative or future times fail any requested freshness check. Known credential
	 * expiry is exclusive; expiry metadata is not required for direct methods.
	 * An unrestricted policy makes no additional evidence demand, so identity-only
	 * results can pass it. Passing is never itself proof of authentication.
	 */
	public String allows(AuthResult result, long now) {
		if (now < 0) throw new IllegalArgumentException(INVALID_NOW);
		if (result == null) return MISSING_RESULT;
		var credential = result.credential();
		if (credential != null && credential.expiresAt() != null && now >= credential.expiresAt()) {
			return CREDENTIAL_EXPIRED;
		}
		var event = result.authentication();
		if (allowedMethods != null && (event.method() == null || !allowedMethods.contains(event.method()))) {
			return METHOD_NOT_ALLOWED;
		}
		if (!event.properties().containsAll(requiredProperties)) return MISSING_PROPERTIES;
		if (maxAuthenticationAgeSeconds != null && !fresh(event.authenticatedAt(), now, maxAuthenticationAgeSeconds)) {
			return AUTHENTICATION_NOT_FRESH;
		}
		var proof = result.proof();
		if (!requiredProofProperties.isEmpty() && (proof == null || !proof.properties().containsAll(requiredProofProperties))) {
			return MISSING_PROOF_PROPERTIES;
		}
		if (maxProofAgeSeconds != null && (proof == null || !fresh(proof.verifiedAt(), now, maxProofAgeSeconds))) {
			return PROOF_NOT_FRESH;
		}
		return null;
	}

	private static boolean fresh(Long time, long now, long maxAge) {
		// Bounds make subtraction safe even at Long.MAX_VALUE.
		return time != null && time >= 0 && time <= now && now - time <= maxAge;
	}

	/** Immutable policy value; serialising requirements does not confer trust on evidence. */
	public AMap<AString, ACell> toData() {
		AMap<AString, ACell> data = Maps.of(REQUIRED_PROPERTIES, requiredProperties,
			REQUIRED_PROOF_PROPERTIES, requiredProofProperties);
		if (allowedMethods != null) data = data.assoc(ALLOWED_METHODS, allowedMethods);
		if (maxAuthenticationAgeSeconds != null) data = data.assoc(MAX_AUTHENTICATION_AGE_SECONDS, CVMLong.create(maxAuthenticationAgeSeconds));
		if (maxProofAgeSeconds != null) data = data.assoc(MAX_PROOF_AGE_SECONDS, CVMLong.create(maxProofAgeSeconds));
		return data;
	}
}
