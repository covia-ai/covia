package covia.venue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.cursor.ACursor;
import covia.venue.auth.LoginProviders;

/**
 * Authentication and user management for a Covia venue.
 *
 * <p>Owns the user database backed by a lattice cursor and manages OAuth
 * login providers. Users are stored as maps keyed by user ID (e.g.
 * "alice_gmail_com") with fields like "did", "email", "name", "provider",
 * "updated".
 *
 * <h2>Config format</h2>
 * <pre>
 * "auth": {
 *   "public": { "enabled": true },   // allow anonymous access (default: true)
 *   "tokenExpiry": 86400,             // JWT expiry in seconds (default 24h)
 *   "oauth": {
 *     "google": { "clientId": "...", "clientSecret": "..." },
 *     "microsoft": { "clientId": "...", "clientSecret": "..." },
 *     "github": { "clientId": "...", "clientSecret": "..." }
 *   }
 * }
 * </pre>
 *
 * <p>OAuth redirect URIs use the venue's base URL from
 * {@link Config#getBaseUrl(AMap)}.
 *
 * <p>Created and owned by {@link Engine}.
 */
public class Auth {

	private static final Logger log = LoggerFactory.getLogger(Auth.class);

	/** Default token expiry: 24 hours in seconds */
	public static final long DEFAULT_TOKEN_EXPIRY = 86400;

	private final ACursor<AMap<AString, AMap<AString, ACell>>> users;
	private final LoginProviders loginProviders;
	private final long tokenExpiry;
	private final boolean publicAccessEnabled;

	/**
	 * Create Auth from an Engine and its venue config.
	 * Reads the "auth" config section for public access, token expiry,
	 * and OAuth providers.
	 *
	 * @param engine The venue engine
	 * @param users Lattice cursor for the user database
	 * @param config The full venue config map
	 */
	public Auth(Engine engine, ACursor<AMap<AString, AMap<AString, ACell>>> users, AMap<AString, ACell> config) {
		this.users = users;

		AMap<AString, ACell> authConfig = RT.ensureMap(config.get(Config.AUTH));

		if (authConfig != null) {
			CVMLong expiry = RT.ensureLong(authConfig.get(Config.TOKEN_EXPIRY));
			this.tokenExpiry = (expiry != null) ? expiry.longValue() : DEFAULT_TOKEN_EXPIRY;

			// Read public access config: auth.public.enabled (default true)
			AMap<AString, ACell> publicConfig = RT.ensureMap(authConfig.get(Config.PUBLIC));
			if (publicConfig != null) {
				ACell enabledVal = publicConfig.get(Config.ENABLED);
				this.publicAccessEnabled = (enabledVal == null) || RT.bool(enabledVal);
			} else {
				this.publicAccessEnabled = true;
			}
		} else {
			this.tokenExpiry = DEFAULT_TOKEN_EXPIRY;
			this.publicAccessEnabled = true;
		}

		// Create login providers from auth config
		this.loginProviders = new LoginProviders(engine, authConfig);

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

	/**
	 * Get a user record by ID
	 * @param id User identifier (e.g. "alice_gmail_com")
	 * @return User record map, or null if not found
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getUser(String id) {
		AMap<AString, AMap<AString, ACell>> usersMap = getUsers();
		if (usersMap == null) return null;
		return (AMap<AString, ACell>) usersMap.get(Strings.create(id));
	}

	/**
	 * Store or update a user record. Adds an :updated timestamp automatically.
	 * @param id User identifier (e.g. "alice_gmail_com")
	 * @param record User record map (should contain "did" and any other fields)
	 */
	public synchronized void putUser(String id, AMap<AString, ACell> record) {
		record = record.assoc(Strings.create("updated"), CVMLong.create(Utils.getCurrentTimestamp()));
		AMap<AString, AMap<AString, ACell>> usersMap = getUsers();
		if (usersMap == null) usersMap = Maps.empty();
		setUsers(usersMap.assoc(Strings.create(id), record));
	}

	/**
	 * Get all users from the lattice cursor
	 * @return Map of user ID to user record
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, AMap<AString, ACell>> getUsers() {
		return (AMap<AString, AMap<AString, ACell>>) (AMap<?,?>) RT.ensureMap(this.users.get());
	}

	private void setUsers(AMap<AString, AMap<AString, ACell>> usersMap) {
		this.users.set(usersMap);
	}
}
