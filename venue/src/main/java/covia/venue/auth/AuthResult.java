package covia.venue.auth;

import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;

/**
 * Identity and evidence established by a trusted authentication verifier.
 *
 * <p>This is an internal contract, not a token response or an authorisation
 * grant. It contains no credential secret. Unknown evidence is absent, never
 * inferred from a signature or the time a bearer was presented. Times are Unix
 * seconds. The two identity strings preserve the existing embedder API;
 * method-specific identity provenance lives in a method-owned details map.</p>
 *
 * <p>Only trusted host code may construct/bind results. Construction does not
 * verify evidence, admit a user or confer capabilities. Additional methods
 * must provide bounded details without credentials. All payload values use
 * Convex's immutable data types; no Java payload class or codec registration
 * is needed to add a method.</p>
 *
 * <p>{@link #toData()} is a data projection, not a portable authentication
 * assertion. Reading the same shape from a job, client or lattice does not
 * verify it. Only the owning verifier may establish its trust.</p>
 */
public record AuthResult(
		AString userDID,
		AString authenticatedIdentity,
		Credential credential,
		Authentication authentication,
		Proof proof,
		AString sessionId) {

	public static final AString USER_DID = Strings.intern("userDID");
	public static final AString AUTHENTICATED_IDENTITY = Strings.intern("authenticatedIdentity");
	public static final AString CREDENTIAL = Strings.intern("credential");
	public static final AString AUTHENTICATION = Strings.intern("authentication");
	public static final AString PROOF = Strings.intern("proof");
	public static final AString SESSION_ID = Strings.intern("sessionId");
	public static final AString TYPE = Strings.intern("type");
	public static final AString ISSUED_AT = Strings.intern("issuedAt");
	public static final AString EXPIRES_AT = Strings.intern("expiresAt");
	public static final AString METHOD = Strings.intern("method");
	public static final AString BINDING = Strings.intern("binding");
	public static final AString AUTHENTICATED_AT = Strings.intern("authenticatedAt");
	public static final AString PROPERTIES = Strings.intern("properties");
	public static final AString DETAILS = Strings.intern("details");
	public static final AString VERIFIED_AT = Strings.intern("verifiedAt");

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

		public AMap<AString, ACell> toData() {
			return optional(optional(Maps.of(TYPE, type),
				ISSUED_AT, time(issuedAt)), EXPIRES_AT, time(expiresAt));
		}
	}

	/**
	 * Originating event, which may outlive a credential. A null method means
	 * unknown origin (legacy venue JWTs or identity-only embedder attribution).
	 * Properties are verified facts, not protocol claim names or assurance ranks.
	 */
	public record Authentication(AString method, AString binding,
			Long authenticatedAt, ASet<AString> properties, AMap<AString, ACell> details) {
		public Authentication {
			Objects.requireNonNull(properties, "properties");
		}

		public AMap<AString, ACell> toData() {
			AMap<AString, ACell> data = Maps.of(PROPERTIES, properties);
			data = optional(data, METHOD, method);
			data = optional(data, BINDING, binding);
			data = optional(data, AUTHENTICATED_AT, time(authenticatedAt));
			return optional(data, DETAILS, details);
		}
	}

	/** Request-bound possession evidence; never copied from a bearer claim. */
	public record Proof(long verifiedAt, ASet<AString> properties) {
		public Proof {
			Objects.requireNonNull(properties, "properties");
		}

		public AMap<AString, ACell> toData() {
			return Maps.of(VERIFIED_AT, CVMLong.create(verifiedAt), PROPERTIES, properties);
		}
	}

	/**
	 * Immutable lattice value using the common field names. Optional unknown
	 * fields are omitted. Method details are preserved without interpretation;
	 * their verifier owns their schema and any protocol-specific naming.
	 */
	public AMap<AString, ACell> toData() {
		AMap<AString, ACell> data = Maps.of(USER_DID, userDID,
			AUTHENTICATED_IDENTITY, authenticatedIdentity,
			AUTHENTICATION, authentication.toData());
		data = optional(data, CREDENTIAL, credential == null ? null : credential.toData());
		data = optional(data, PROOF, proof == null ? null : proof.toData());
		return optional(data, SESSION_ID, sessionId);
	}

	private static AMap<AString, ACell> optional(AMap<AString, ACell> data,
			AString key, ACell value) {
		return value == null ? data : data.assoc(key, value);
	}

	private static CVMLong time(Long seconds) {
		return seconds == null ? null : CVMLong.create(seconds);
	}

	/** Identity-only attribution has no credential, method, time or assurance. */
	public static AuthResult identity(AString authenticatedIdentity, AString userDID) {
		return new AuthResult(userDID, authenticatedIdentity, null,
			new Authentication(null, null, null, Sets.empty(), null), null, null);
	}
}
