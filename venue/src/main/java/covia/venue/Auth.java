package covia.venue;

import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.lattice.CapabilityChecker;
import covia.venue.auth.LoginProviders;

/**
 * Authentication and user management for a Covia venue.
 *
 * <p>Delegates stored records to {@link ManagedUsers} and manages OAuth
 * login providers. Users are maps keyed by stable local account ID, with
 * core fields such as "did" and "updated". External-login details live in
 * the method-owned "externalIdentity" payload; email/name are profile metadata.
 *
 * <h2>Config format</h2>
 * <pre>
 * "auth": {
 *   "public": {
 *     "enabled": true,                // allow anonymous access (default: true)
 *     "caps": "unrestricted"          // public grant scope: absent = read-only default
 *   },                                //   (reads only); "unrestricted" = no scope;
 *                                     //   or an explicit capability array
 *   "tokenExpiry": 86400,             // JWT expiry in seconds (default 24h)
 *   "requireAud": true,               // a bearer must name this venue in aud
 *                                     //   (default); false also accepts one
 *                                     //   without aud. A present-but-wrong aud
 *                                     //   is always 401.
 *   "acceptedAudiences": ["did:..."], // extra accepted aud values beyond the
 *                                     //   venue's own DID (e.g. a did:key form
 *                                     //   for a did:web venue)
 *   "oauth": {
 *     "google": { "clientId": "...", "clientSecret": "..." },
 *     "microsoft": { "clientId": "...", "clientSecret": "...", "tenantId": "..." },
 *     "github": { "clientId": "...", "clientSecret": "..." }
 *   }
 * }
 * </pre>
 *
 * <p>OAuth redirect URIs use the venue's base URL from
 * {@link Config#getBaseUrl()}.
 *
 * <p>Created and owned by {@link Engine}.
 */
public class Auth {

	private static final Logger log = LoggerFactory.getLogger(Auth.class);
	public static final AString ACTIVE = ManagedUsers.ACTIVE;
	public static final AString REVOKED = ManagedUsers.REVOKED;
	public static final String CANNOT_REVOKE_FINAL_KEY = ManagedUsers.CANNOT_REVOKE_FINAL_KEY;
	public static final String KEY_ALREADY_BOUND = ManagedUsers.KEY_ALREADY_BOUND;

	/** Default token expiry: 24 hours in seconds */
	public static final long DEFAULT_TOKEN_EXPIRY = 86400;

	private final LoginProviders loginProviders;
	private final Engine engine;
	private final long tokenExpiry;
	private final boolean publicAccessEnabled;
	private final ACell publicCapsConfig;
	private final boolean requireAud;
	private final java.util.Set<String> configuredAudiences;
	private final AString webDID;

	/**
	 * Create Auth from an Engine and its venue state.
	 * Reads the "auth" config section for public access, token expiry,
	 * and OAuth providers.
	 *
	 * @param engine The venue engine
	 */
	Auth(Engine engine) {

		Config config = engine.config();
		this.engine = engine;
		this.tokenExpiry = config.getTokenExpiry();
		this.publicAccessEnabled = config.isPublicAccess();
		this.publicCapsConfig = config.getPublicCapsConfig();
		this.requireAud = config.isRequireAud();
		java.util.Set<String> aud = new java.util.HashSet<>();
		AVector<ACell> acc = config.getAcceptedAudiences();
		if (acc != null) {
			for (long i = 0; i < acc.count(); i++) {
				AString s = RT.ensureString(acc.get(i));
				if (s != null) aud.add(s.toString());
			}
		}
		this.configuredAudiences = aud;
		this.webDID = config.getWebDID();

		// Create login providers from auth config
		this.loginProviders = new LoginProviders(engine, config.getAuthConfig());

		log.info("Auth: public access {}, token expiry {}s, {} OAuth provider(s)",
			publicAccessEnabled ? "enabled" : "disabled", tokenExpiry,
			loginProviders.getProviders().size());
	}

	/**
	 * Get the configured login providers.
	 * @return LoginProviders instance
	 */
	public LoginProviders getLoginProviders() {
		return loginProviders;
	}

	/**
	 * Get the configured JWT token expiry in seconds.
	 * @return Token expiry in seconds
	 */
	public long getTokenExpiry() {
		return tokenExpiry;
	}

	/**
	 * Whether anonymous (unauthenticated) access is allowed.
	 * Controlled by {@code auth.public.enabled} in config (default false).
	 * @return true if public access is enabled
	 */
	public boolean isPublicAccessEnabled() {
		return publicAccessEnabled;
	}

	/** Public admission summary only: never disclose custom capability targets. */
	public AString getPublicCapsMode() {
		if (!publicAccessEnabled) return Strings.intern("disabled");
		if (Config.UNRESTRICTED.equals(publicCapsConfig)) return Config.UNRESTRICTED;
		return Strings.intern(publicCapsConfig instanceof AVector ? "custom" : "read-only");
	}

	/** Whether a bearer must name this venue in {@code aud} ({@code auth.requireAud}). */
	public boolean isRequireAud() {
		return requireAud;
	}

	/** Operator-configured extra accepted JWT audiences (beyond the venue's own
	 *  DID(s)). Never null; empty if none configured. */
	public java.util.Set<String> getConfiguredAudiences() {
		return configuredAudiences;
	}

	/** The venue's did:web alias ({@link Config#getWebDID()}), or null when no
	 *  public hostname is configured. Discovery only — see covia#167. */
	public AString getWebDID() {
		return webDID;
	}

	/**
	 * The capability grant scope applied to unauthenticated (public) callers,
	 * scoped to {@code publicDID}. Operator policy via {@code auth.public.caps}:
	 * unconfigured → the secure read-only default
	 * ({@link CapabilityChecker#readOnlyScope}); the literal
	 * {@code "unrestricted"} → no scope (legacy full access); an explicit
	 * capability vector → that scope. Malformed config fails safe to read-only.
	 *
	 * @param publicDID the public caller's DID ({@code <venueDID>:public}),
	 *                  used to scope the default read grant
	 * @return the grant-scope vector, or null for "unrestricted"
	 */
	public AVector<ACell> getPublicScope(AString publicDID) {
		if (publicCapsConfig == null) return CapabilityChecker.readOnlyScope(publicDID);
		if (Config.UNRESTRICTED.equals(publicCapsConfig)) return null;
		AVector<ACell> caps = RT.ensureVector(publicCapsConfig);
		if (caps != null) return caps;
		log.warn("auth.public.caps is malformed ({}); defaulting to read-only", publicCapsConfig);
		return CapabilityChecker.readOnlyScope(publicDID);
	}

	private ManagedUsers state() { return engine.getVenueState().managedUsers(); }

	public AMap<AString, ACell> getUser(AString id) { return state().get(id); }
	public AMap<AString, AMap<AString, ACell>> getUsers() { return state().getUsers(); }
	public AMap<AString, ACell> getAuthenticationKeys(AString id) { return state().getAuthenticationKeys(id); }
	public boolean isAuthenticationKeyActive(AString id, AString key) { return state().isAuthenticationKeyActive(id, key); }
	public AVector<ACell> getActiveAuthenticationKeys(AString id) { return state().getActiveAuthenticationKeys(id); }

	// Each write below checks the account in the transaction that writes, so an
	// account deleted meanwhile refuses the write rather than regaining a record.
	public void putUser(AString id, AMap<AString, ACell> record) {
		engine.getVenueState().execute(draft -> {
			draft.users().requireNotDeleted(RT.ensureString(record.get(Fields.DID)));
			draft.managedUsers().putUser(id, record);
		});
	}

	/** @param update Run again if the transaction conflicts, so it must be pure */
	public AMap<AString, ACell> updateUser(AString id, UnaryOperator<AMap<AString, ACell>> update) {
		return engine.getVenueState().transact(draft -> {
			return draft.managedUsers().updateUser(id, previous -> {
				var record = update.apply(previous);
				if (record != null) draft.users().requireNotDeleted(RT.ensureString(record.get(Fields.DID)));
				return record;
			});
		});
	}

	public AMap<AString, ACell> provisionLogin(AString id, AMap<AString, ACell> profile) {
		return provisionLogin(engine.getVenueState(), id, profile);
	}

	/** As {@link #provisionLogin(AString, AMap)}, in {@code venue}, which may be a transaction's draft. */
	public AMap<AString, ACell> provisionLogin(VenueState venue, AString id, AMap<AString, ACell> profile) {
		AString did = RT.ensureString(RT.getIn(venue.managedUsers().get(id), Fields.DID));
		if (did == null) did = engine.managedUserDID(id);
		return venue.provisionLogin(id, did, profile);
	}

	public boolean ensureManagedUser(AString id, AString did) {
		return engine.getVenueState().transact(draft -> {
			draft.users().requireNotDeleted(did);
			return draft.managedUsers().ensureManagedUser(id, did);
		});
	}

	/** Runs {@code change} on the directory in the transaction that checks user {@code id}'s account is live. */
	private <T> T forLiveUser(AString id, Function<ManagedUsers, T> change) {
		return engine.getVenueState().transact(draft -> {
			draft.users().requireNotDeleted(RT.ensureString(RT.getIn(draft.managedUsers().get(id), Fields.DID)));
			return change.apply(draft.managedUsers());
		});
	}

	public boolean addAuthenticationKey(AString id, AString key, AString actor, AString label) {
		return forLiveUser(id, auth -> auth.addAuthenticationKey(id, key, actor, label));
	}

	public long addAuthenticationKeys(AString id, AVector<ACell> keys, AString actor) {
		return forLiveUser(id, auth -> auth.addAuthenticationKeys(id, keys, actor));
	}

	/** As {@link ManagedUsers#installFirstAuthenticationKeys}, for a live user. */
	public boolean installFirstAuthenticationKeys(AString id, AVector<ACell> keys, AString actor) {
		return forLiveUser(id, auth -> auth.installFirstAuthenticationKeys(id, keys, actor));
	}

	public boolean revokeAuthenticationKey(AString id, AString key, AString actor, boolean allowLast) {
		return forLiveUser(id, auth -> auth.revokeAuthenticationKey(id, key, actor, allowLast));
	}
}
