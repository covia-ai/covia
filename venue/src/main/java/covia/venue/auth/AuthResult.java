package covia.venue.auth;

import java.util.Objects;
import java.util.Set;

import convex.core.data.AString;
import convex.core.data.Strings;

/**
 * Identity and evidence established by a trusted authentication verifier.
 *
 * <p>This is an internal contract, not a token response or an authorisation
 * grant. It contains no credential secret. Unknown evidence is absent, never
 * inferred from a signature or the time a bearer was presented. Times are Unix
 * seconds. The two identity strings preserve the existing embedder API;
 * method-specific identity provenance lives in typed {@link Details}.</p>
 *
 * <p>Only trusted host code may construct/bind results. Construction does not
 * verify evidence, admit a user or confer capabilities. Additional methods
 * must provide immutable, bounded details without credentials.</p>
 */
public record AuthResult(
		AString userDID,
		AString authenticatedIdentity,
		Credential credential,
		Authentication authentication,
		Proof proof,
		AString sessionId) {

	public static final AString KEY = Strings.intern("key");
	public static final AString UCAN = Strings.intern("ucan");
	public static final AString OIDC = Strings.intern("oidc");
	public static final AString KEY_JWT = Strings.intern("keyJwt");
	public static final AString UCAN_BEARER = Strings.intern("ucanBearer");
	public static final AString VENUE_JWT = Strings.intern("venueJwt");
	public static final AString PROVIDER_TOKEN = Strings.intern("providerToken");

	public AuthResult {
		Objects.requireNonNull(userDID, "userDID");
		Objects.requireNonNull(authenticatedIdentity, "authenticatedIdentity");
		Objects.requireNonNull(authentication, "authentication");
	}

	/** Presented credential metadata; null times have not been established. */
	public record Credential(AString type, Long issuedAt, Long expiresAt) {
		public Credential {
			Objects.requireNonNull(type, "type");
		}
	}

	/**
	 * Originating event, which may outlive a credential. A null method means
	 * unknown origin (legacy venue JWTs or identity-only embedder attribution).
	 * Properties are verified facts, not protocol claim names or assurance ranks.
	 */
	public record Authentication(AString method, AString binding,
			Long authenticatedAt, Set<String> properties, Details details) {
		public Authentication {
			properties = Set.copyOf(properties);
		}
	}

	/** Request-bound possession evidence; never copied from a bearer claim. */
	public record Proof(long verifiedAt, Set<String> properties) {
		public Proof {
			properties = Set.copyOf(properties);
		}
	}

	/** An immutable payload owned by a trusted method, not a generic claim map. */
	public interface Details {}

	/** The key/DID whose signature the key verifier accepted. */
	public record KeyDetails(AString signingIdentity) implements Details {}

	/** UCAN identity provenance, without retaining the bearer or proof tokens. */
	public record UcanDetails(AString iss, AString aud) implements Details {}

	/**
	 * Configured provider and signed subject. iss is present only when issuer
	 * validation ran. Legacy email mapping is unchanged; sub is not yet a
	 * stable local-account binding. No amr/acr/auth_time trust mapping exists
	 * in Phase 1, so those claims are deliberately not retained as evidence.
	 */
	public record ProviderDetails(String provider, AString iss, AString sub)
			implements Details {}

	/** Identity-only attribution has no credential, method, time or assurance. */
	public static AuthResult identity(AString authenticatedIdentity, AString userDID) {
		return new AuthResult(userDID, authenticatedIdentity, null,
			new Authentication(null, null, null, Set.of(), null), null, null);
	}
}
