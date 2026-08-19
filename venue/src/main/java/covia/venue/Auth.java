package covia.venue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.auth.did.DID;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
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
 *     "caps": "unrestricted"          // public grant scope: absent = read-only default
 *   },                                //   (reads only); "unrestricted" = no scope;
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
	public static final AString ACTIVE = Strings.intern("active");
	public static final AString REVOKED = Strings.intern("revoked");

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
	Auth(Engine engine, ALatticeComponent<?> parent, ALatticeCursor<?> cursor) {
		super(parent,
			(ALatticeCursor<AMap<AString, AMap<AString, ACell>>>) cursor);

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
		if (publicCapsConfig instanceof AString s && "unrestricted".equals(s.toString())) return null;
		AVector<ACell> caps = RT.ensureVector(publicCapsConfig);
		if (caps != null) return caps;
		log.warn("auth.public.caps is malformed ({}); defaulting to read-only", publicCapsConfig);
		return CapabilityChecker.readOnlyScope(publicDID);
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
	 * Creates the venue-owned authentication-directory row for a managed user,
	 * or verifies the existing row still names the same stable DID.
	 *
	 * @return true when the row was created
	 */
	public synchronized boolean ensureManagedUser(AString id, AString did) {
		AMap<AString, ACell> existing = getUser(id);
		if (existing != null) {
			AString stored = RT.ensureString(existing.get(Fields.DID));
			if (!did.equals(stored)) {
				throw new IllegalStateException("Named user " + id
					+ " is already bound to a different DID: " + stored);
			}
			return false;
		}
		putUser(id, Maps.of(Fields.DID, did, Fields.NAME, id));
		return true;
	}

	/** Public authenticator lifecycle records for one named user. */
	public AMap<AString, ACell> getAuthenticationKeys(AString id) {
		AMap<AString, ACell> record = getUser(id);
		if (record == null) return Maps.empty();
		AMap<AString, ACell> keys = RT.ensureMap(record.get(Fields.AUTHENTICATION_KEYS));
		return (keys != null) ? keys : Maps.empty();
	}

	/** True when the named user's key is currently admitted for authentication. */
	public boolean isAuthenticationKeyActive(AString id, AString keyDID) {
		AMap<AString, ACell> entry =
			RT.ensureMap(getAuthenticationKeys(id).get(keyDID));
		return entry != null && ACTIVE.equals(entry.get(Fields.STATUS));
	}

	/** Active public key DIDs, in deterministic lattice-map order. */
	public AVector<ACell> getActiveAuthenticationKeys(AString id) {
		AVector<ACell> result = Vectors.empty();
		for (var entry : getAuthenticationKeys(id).entrySet()) {
			AMap<AString, ACell> state = RT.ensureMap(entry.getValue());
			if (state != null && ACTIVE.equals(state.get(Fields.STATUS))) {
				result = result.conj(entry.getKey());
			}
		}
		return result;
	}

	/**
	 * Adds one public {@code did:key} authenticator. A key may have history under
	 * only one named user on this venue, and a revoked key is never silently
	 * reactivated.
	 *
	 * @return true when a new active binding was added; false when already active
	 */
	public synchronized boolean addAuthenticationKey(AString id, AString keyDID,
			AString actorDID, AString label) {
		requireValidAuthenticationKey(keyDID);
		requireActorDID(actorDID);
		AMap<AString, ACell> user = getUser(id);
		if (user == null) throw new IllegalArgumentException("Unknown named user: " + id);
		requireKeyNotBoundElsewhere(id, keyDID);

		AMap<AString, ACell> keys = getAuthenticationKeys(id);
		AMap<AString, ACell> existing = RT.ensureMap(keys.get(keyDID));
		if (existing != null) {
			if (ACTIVE.equals(existing.get(Fields.STATUS))) return false;
			throw new IllegalArgumentException(
				"Revoked authentication keys cannot be reactivated");
		}

		long now = Utils.getCurrentTimestamp();
		AMap<AString, ACell> state = Maps.of(
			Fields.STATUS, ACTIVE,
			Fields.ADDED_AT, CVMLong.create(now),
			Fields.ADDED_BY, actorDID);
		if (label != null && !label.isEmpty()) state = state.assoc(Fields.LABEL, label);
		putUser(id, user.assoc(Fields.AUTHENTICATION_KEYS, keys.assoc(keyDID, state)));
		return true;
	}

	/**
	 * Atomically installs an initial set of public authenticators. The complete
	 * set is validated before the user record is changed.
	 *
	 * @return number of newly installed keys
	 */
	public synchronized long addAuthenticationKeys(AString id,
			AVector<ACell> keyDIDs, AString actorDID) {
		if (keyDIDs == null || keyDIDs.isEmpty()) {
			throw new IllegalArgumentException("At least one authentication key is required");
		}
		requireActorDID(actorDID);
		AMap<AString, ACell> user = getUser(id);
		if (user == null) throw new IllegalArgumentException("Unknown named user: " + id);

		AMap<AString, ACell> keys = getAuthenticationKeys(id);
		java.util.HashSet<AString> requested = new java.util.HashSet<>();
		for (long i = 0; i < keyDIDs.count(); i++) {
			AString keyDID = RT.ensureString(keyDIDs.get(i));
			requireValidAuthenticationKey(keyDID);
			if (!requested.add(keyDID)) {
				throw new IllegalArgumentException("Duplicate authentication key");
			}
			requireKeyNotBoundElsewhere(id, keyDID);
			AMap<AString, ACell> existing = RT.ensureMap(keys.get(keyDID));
			if (existing != null && !ACTIVE.equals(existing.get(Fields.STATUS))) {
				throw new IllegalArgumentException(
					"Revoked authentication keys cannot be reactivated");
			}
		}

		long added = 0;
		long now = Utils.getCurrentTimestamp();
		for (AString keyDID : requested) {
			if (keys.containsKey(keyDID)) continue;
			keys = keys.assoc(keyDID, Maps.of(
				Fields.STATUS, ACTIVE,
				Fields.ADDED_AT, CVMLong.create(now),
				Fields.ADDED_BY, actorDID));
			added++;
		}
		if (added > 0) {
			putUser(id, user.assoc(Fields.AUTHENTICATION_KEYS, keys));
		}
		return added;
	}

	/**
	 * Revokes one authenticator while retaining its audit tombstone.
	 *
	 * @param allowLast true only for direct venue recovery authority
	 * @return true when an active key was revoked; false when absent/already revoked
	 */
	public synchronized boolean revokeAuthenticationKey(AString id, AString keyDID,
			AString actorDID, boolean allowLast) {
		requireActorDID(actorDID);
		AMap<AString, ACell> user = getUser(id);
		if (user == null) throw new IllegalArgumentException("Unknown named user: " + id);
		AMap<AString, ACell> keys = getAuthenticationKeys(id);
		AMap<AString, ACell> existing = RT.ensureMap(keys.get(keyDID));
		if (existing == null || REVOKED.equals(existing.get(Fields.STATUS))) return false;
		if (!ACTIVE.equals(existing.get(Fields.STATUS))) {
			throw new IllegalStateException("Malformed authentication key status");
		}
		if (!allowLast && getActiveAuthenticationKeys(id).count() <= 1) {
			throw new IllegalArgumentException(
				"Cannot revoke the final active authentication key");
		}
		AMap<AString, ACell> revoked = existing
			.assoc(Fields.STATUS, REVOKED)
			.assoc(Fields.REVOKED_AT, CVMLong.create(Utils.getCurrentTimestamp()))
			.assoc(Fields.REVOKED_BY, actorDID);
		putUser(id, user.assoc(Fields.AUTHENTICATION_KEYS, keys.assoc(keyDID, revoked)));
		return true;
	}

	private void requireKeyNotBoundElsewhere(AString id, AString keyDID) {
		AMap<AString, AMap<AString, ACell>> users = getUsers();
		if (users == null) return;
		for (var other : users.entrySet()) {
			if (other.getKey().equals(id)) continue;
			AMap<AString, ACell> otherKeys =
				RT.ensureMap(other.getValue().get(Fields.AUTHENTICATION_KEYS));
			if (otherKeys != null && otherKeys.containsKey(keyDID)) {
				throw new IllegalArgumentException(
					"Authentication key is already bound to named user " + other.getKey());
			}
		}
	}

	private static void requireActorDID(AString actorDID) {
		if (actorDID == null) {
			throw new IllegalArgumentException("Authentication key actor DID is required");
		}
	}

	static void requireValidAuthenticationKey(AString keyDID) {
		if (keyDID == null || !keyDID.toString().startsWith("did:key:")) {
			throw new IllegalArgumentException("Authentication key must be a did:key");
		}
		String multikey = keyDID.toString().substring("did:key:".length());
		try {
			if (DID.fromString(keyDID.toString()) == null
					|| Multikey.decodePublicKey(multikey) == null) {
				throw new IllegalArgumentException("Invalid did:key authentication key");
			}
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Invalid did:key authentication key", e);
		}
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
