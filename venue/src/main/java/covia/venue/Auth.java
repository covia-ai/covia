package covia.venue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;
import covia.api.Fields;
import covia.lattice.CapabilityChecker;
import covia.venue.auth.LoginProviders;

/**
 * Authentication and user management for a Covia venue.
 *
 * <p>Wraps a lattice cursor at the {@code :users} level and manages OAuth
 * login providers. Users are stored as maps keyed by user ID (e.g.
 * "alice_gmail_com") with fields like "did", "email", "name", "provider",
 * "updated".
 *
 * <h2>Config format</h2>
 * <pre>
 * "auth": {
 *   "public": {
 *     "enabled": true,                // allow anonymous access (default: true)
 *     "caps": "unrestricted"          // public ceiling: absent = read-only default
 *   },                                //   (reads only); "unrestricted" = no ceiling;
 *                                     //   or an explicit capability array
 *   "tokenExpiry": 86400,             // JWT expiry in seconds (default 24h)
 *   "audience": "verify",             // JWT aud policy: "verify" (default — if
 *                                     //   aud present it must match) or "require"
 *                                     //   (aud must be present AND match). A
 *                                     //   present-but-wrong aud is always 401.
 *   "acceptedAudiences": ["did:..."], // extra accepted aud values beyond the
 *                                     //   venue's own DID (e.g. a did:key form
 *                                     //   for a did:web venue)
 *   "oauth": {
 *     "google": { "clientId": "...", "clientSecret": "..." },
 *     "microsoft": { "clientId": "...", "clientSecret": "..." },
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
public class Auth extends ALatticeComponent<AMap<AString, AMap<AString, ACell>>> {

	private static final Logger log = LoggerFactory.getLogger(Auth.class);

	/** Default token expiry: 24 hours in seconds */
	public static final long DEFAULT_TOKEN_EXPIRY = 86400;

	private final LoginProviders loginProviders;
	private final long tokenExpiry;
	private final boolean publicAccessEnabled;
	private final ACell publicCapsConfig;
	private final String audiencePolicy;
	private final java.util.Set<String> configuredAudiences;
	private final AString webDID;

	/**
	 * Create Auth from an Engine and its venue state.
	 * Reads the "auth" config section for public access, token expiry,
	 * and OAuth providers.
	 *
	 * @param engine The venue engine
	 * @param cursor Lattice cursor at the :users level
	 */
	@SuppressWarnings("unchecked")
	Auth(Engine engine, ALatticeCursor<?> cursor) {
		super((ALatticeCursor<AMap<AString, AMap<AString, ACell>>>) cursor);

		Config config = engine.config();
		this.tokenExpiry = config.getTokenExpiry();
		this.publicAccessEnabled = config.isPublicAccess();
		this.publicCapsConfig = config.getPublicCapsConfig();
		this.audiencePolicy = config.getAudiencePolicy();
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

	/** JWT audience policy: {@code "require"} or {@code "verify"} (default). */
	public String getAudiencePolicy() {
		return audiencePolicy;
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
	 * The capability ceiling applied to unauthenticated (public) callers,
	 * scoped to {@code publicDID}. Operator policy via {@code auth.public.caps}:
	 * unconfigured → the secure read-only default
	 * ({@link CapabilityChecker#readOnlyCeiling}); the literal
	 * {@code "unrestricted"} → no ceiling (legacy full access); an explicit
	 * capability vector → that ceiling. Malformed config fails safe to read-only.
	 *
	 * @param publicDID the public caller's DID ({@code <venueDID>:public}),
	 *                  used to scope the default read grant
	 * @return the ceiling vector, or null for "unrestricted"
	 */
	public AVector<ACell> getPublicCeiling(AString publicDID) {
		if (publicCapsConfig == null) return CapabilityChecker.readOnlyCeiling(publicDID);
		if (publicCapsConfig instanceof AString s && "unrestricted".equals(s.toString())) return null;
		AVector<ACell> caps = RT.ensureVector(publicCapsConfig);
		if (caps != null) return caps;
		log.warn("auth.public.caps is malformed ({}); defaulting to read-only", publicCapsConfig);
		return CapabilityChecker.readOnlyCeiling(publicDID);
	}

	/**
	 * Get a user record by ID
	 * @param id User identifier as AString (e.g. "alice_gmail_com")
	 * @return User record map, or null if not found
	 */
	public AMap<AString, ACell> getUser(AString id) {
		AMap<AString, AMap<AString, ACell>> usersMap = getUsers();
		if (usersMap == null) return null;
		return (AMap<AString, ACell>) usersMap.get(id);
	}

	/**
	 * Store or update a user record. Adds an "updated" timestamp automatically.
	 * @param id User identifier as AString (e.g. "alice_gmail_com")
	 * @param record User record map (should contain "did" and any other fields)
	 */
	public void putUser(AString id, AMap<AString, ACell> record) {
		AMap<AString, ACell> stamped = record.assoc(
			Fields.UPDATED, CVMLong.create(Utils.getCurrentTimestamp()));
		cursor.updateAndGet(current -> {
			@SuppressWarnings("unchecked")
			AMap<AString, AMap<AString, ACell>> m = (AMap<AString, AMap<AString, ACell>>) (AMap<?,?>) RT.castMap(current);
			if (m == null) m = Maps.empty();
			return m.assoc(id, stamped);
		});
	}

	/**
	 * Get all users from the lattice cursor
	 * @return Map of user ID to user record
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, AMap<AString, ACell>> getUsers() {
		return (AMap<AString, AMap<AString, ACell>>) (AMap<?,?>) RT.castMap(cursor.get());
	}

}
