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
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;
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
	 * Store or update a user record. Adds an "updated" timestamp automatically.
	 * @param id User identifier (e.g. "alice_gmail_com")
	 * @param record User record map (should contain "did" and any other fields)
	 */
	public void putUser(String id, AMap<AString, ACell> record) {
		AString key = Strings.create(id);
		AMap<AString, ACell> stamped = record.assoc(
			Strings.create("updated"), CVMLong.create(Utils.getCurrentTimestamp()));
		cursor.updateAndGet(current -> {
			@SuppressWarnings("unchecked")
			AMap<AString, AMap<AString, ACell>> m = (AMap<AString, AMap<AString, ACell>>) (AMap<?,?>) RT.ensureMap(current);
			if (m == null) m = Maps.empty();
			return m.assoc(key, stamped);
		});
	}

	/**
	 * Get all users from the lattice cursor
	 * @return Map of user ID to user record
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, AMap<AString, ACell>> getUsers() {
		return (AMap<AString, AMap<AString, ACell>>) (AMap<?,?>) RT.ensureMap(cursor.get());
	}

}
