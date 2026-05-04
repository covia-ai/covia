package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * Venue configuration instance wrapping an immutable config map.
 *
 * <p>Each {@link Engine} owns a Config instance that provides typed access
 * to venue configuration values. Static key constants are retained for
 * direct use with CVM data structures.
 *
 * <p><b>SECURITY NOTE:</b> {@code Strings.intern()} should ONLY be used for static
 * compile-time constants as shown here. Never intern externally-obtained or user-provided
 * strings, as this could lead to memory exhaustion (DoS) since interned strings are
 * permanently cached. For dynamic strings, use {@code Strings.create()} instead.
 */
public class Config {

	// ========== Top-level config keys ==========

	/** Key for venues array */
	public static final AString VENUES = Strings.intern("venues");

	/** Key for Convex peer configuration */
	public static final AString CONVEX = Strings.intern("convex");

	// ========== Lattice store config keys ==========

	/**
	 * Key for lattice store path.
	 * <ul>
	 *   <li>{@code "temp"} (default) — temporary store, deleted on exit</li>
	 *   <li>{@code "memory"} — in-memory only, no persistence</li>
	 *   <li>File path — persistent Etch store at that location</li>
	 * </ul>
	 */
	public static final AString STORE = Strings.intern("store");

	/** Key for venue identity seed (Ed25519, 32-byte hex string). */
	public static final AString SEED = Strings.intern("seed");

	// ========== Venue config keys ==========

	/** Key for venue name */
	public static final AString NAME = Strings.intern("name");

	/** Key for venue DID */
	public static final AString DID = Strings.intern("did");

	/** Key for venue hostname */
	public static final AString HOSTNAME = Strings.intern("hostname");

	/** Key for venue port */
	public static final AString PORT = Strings.intern("port");

	/** Key for MCP configuration */
	public static final AString MCP = Strings.intern("mcp");

	/** Key for A2A configuration */
	public static final AString A2A = Strings.intern("a2a");

	/** Key for adapters configuration */
	public static final AString ADAPTERS = Strings.intern("adapters");

	// ========== Storage config keys ==========

	/** Key for storage configuration section */
	public static final AString STORAGE = Strings.intern("storage");

	/** Key for storage content type */
	public static final AString CONTENT = Strings.intern("content");

	/** Key for storage path (for file/dlfs storage) */
	public static final AString PATH = Strings.intern("path");

	/** Key for maximum content upload size in bytes (default 100MB) */
	public static final AString MAX_CONTENT_SIZE = Strings.intern("maxContentSize");

	/** Default maximum content upload size in bytes (100MB) */
	public static final long DEFAULT_MAX_CONTENT_SIZE = 100 * 1024 * 1024;

	// ========== Storage type values ==========

	/** Storage type: lattice (CRDT-backed, default) */
	public static final AString STORAGE_TYPE_LATTICE = Strings.intern("lattice");

	/** Storage type: memory (in-memory, non-persistent) */
	public static final AString STORAGE_TYPE_MEMORY = Strings.intern("memory");

	/** Storage type: file (filesystem-based) */
	public static final AString STORAGE_TYPE_FILE = Strings.intern("file");

	/** Storage type: dlfs (DLFS lattice-backed filesystem) */
	public static final AString STORAGE_TYPE_DLFS = Strings.intern("dlfs");

	// ========== Auth config keys ==========

	/** Key for auth configuration section */
	public static final AString AUTH = Strings.intern("auth");

	/** Key for JWT token expiry in seconds (default 86400 = 24 hours) */
	public static final AString TOKEN_EXPIRY = Strings.intern("tokenExpiry");

	/** Key for public (anonymous) access configuration */
	public static final AString PUBLIC = Strings.intern("public");

	// ========== OAuth config keys (nested under auth) ==========

	/** Key for OAuth providers configuration section */
	public static final AString OAUTH = Strings.intern("oauth");

	/** Key for provider client ID */
	public static final AString CLIENT_ID = Strings.intern("clientId");

	/** Key for provider client secret */
	public static final AString CLIENT_SECRET = Strings.intern("clientSecret");

	/** Key for base URL override (if not set, derived from hostname + port) */
	public static final AString BASE_URL = Strings.intern("baseUrl");

	// ========== DLFS config keys ==========

	/** Key for DLFS WebDAV configuration section */
	public static final AString WEBDAV = Strings.intern("webdav");

	// ========== File adapter config keys ==========

	/** Key for FileAdapter configuration section */
	public static final AString FILE = Strings.intern("file");

	/** Key for FileAdapter named-roots map (root name -&gt; absolute path string). */
	public static final AString ROOTS = Strings.intern("roots");

	/** Key for per-root read-only flag (boolean). */
	public static final AString READ_ONLY = Strings.intern("readOnly");

	// ========== Server config keys ==========

	/** Key for CORS allowed origins (default: "*" = all) */
	public static final AString CORS_ORIGINS = Strings.intern("corsOrigins");

	// ========== MCP config keys ==========

	/** Key for MCP enabled flag */
	public static final AString ENABLED = Strings.intern("enabled");

	/**
	 * Key for the "Fix MCP Strings" workaround flag (top-level, default true).
	 * Some MCP clients serialise nested object/array arguments as JSON strings
	 * instead of the structured types declared in the tool schema. When true,
	 * the venue defensively re-parses such string values into their declared
	 * shape at the MCP boundary and at {@code grid:run}/{@code grid:invoke}
	 * dispatch. Set to false to disable the workaround.
	 */
	public static final AString FIX_MCP_STRINGS = Strings.intern("fixMcpStrings");

	// ========== Instance fields ==========

	private final AMap<AString, ACell> config;

	/**
	 * Create a Config wrapping the given venue config map.
	 * @param config Venue config map, or null for empty defaults
	 */
	public Config(AMap<AString, ACell> config) {
		this.config = (config == null) ? Maps.empty() : config;
	}

	/**
	 * Get the raw config map.
	 * @return Underlying config map (never null)
	 */
	public AMap<AString, ACell> getMap() {
		return config;
	}

	// ========== Typed accessors ==========

	/**
	 * Get the venue name.
	 * @return Venue name, or null if not configured
	 */
	public AString getName() {
		return RT.ensureString(config.get(NAME));
	}

	/**
	 * Get the venue DID string from config.
	 * @return DID string, or null if not configured (Engine generates one from keypair)
	 */
	public AString getDID() {
		return RT.ensureString(config.get(DID));
	}

	/**
	 * Get the configured hostname.
	 * @return Hostname string, defaults to "localhost"
	 */
	public String getHostname() {
		AString hostname = RT.ensureString(config.get(HOSTNAME));
		return (hostname != null) ? hostname.toString() : "localhost";
	}

	/**
	 * Get the configured port.
	 * @return Port number, defaults to 8080
	 */
	public int getPort() {
		CVMLong portVal = RT.ensureLong(config.get(PORT));
		return (portVal != null) ? (int) portVal.longValue() : 8080;
	}

	/**
	 * Get the base URL for this venue.
	 *
	 * <p>Checks for an explicit "baseUrl" first, then derives from "hostname"
	 * and "port". Port 443 implies https; standard ports (80/443) are omitted
	 * from the URL. Falls back to {@code http://localhost:8080} if unconfigured.
	 *
	 * @return Base URL string (no trailing slash)
	 */
	public String getBaseUrl() {
		// Explicit baseUrl takes priority
		AString explicit = RT.ensureString(config.get(BASE_URL));
		if (explicit != null) return explicit.toString();

		// Derive from hostname + port
		String host = getHostname();
		int port = getPort();

		String scheme = (port == 443) ? "https" : "http";
		if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
			return scheme + "://" + host;
		}
		return scheme + "://" + host + ":" + port;
	}

	// ========== Lattice store accessor ==========

	/**
	 * Get the lattice store path.
	 * @return Store path string: "temp" (default), "memory", or a file path
	 */
	public String getStore() {
		AString storeVal = RT.ensureString(config.get(STORE));
		return (storeVal != null) ? storeVal.toString() : "temp";
	}

	/**
	 * Get the venue identity seed (Ed25519, 32-byte hex).
	 * @return Hex seed string, or null if not configured
	 */
	public String getSeed() {
		AString seedVal = RT.ensureString(config.get(SEED));
		return (seedVal != null) ? seedVal.toString() : null;
	}

	// ========== Storage accessors ==========

	/**
	 * Get the storage type.
	 * @return Storage type string, defaults to "lattice"
	 */
	public AString getStorageType() {
		AMap<AString, ACell> storageConfig = RT.ensureMap(config.get(STORAGE));
		if (storageConfig != null) {
			AString contentValue = RT.ensureString(storageConfig.get(CONTENT));
			if (contentValue != null) return contentValue;
		}
		return STORAGE_TYPE_LATTICE;
	}

	/**
	 * Get the storage path (for file/dlfs storage).
	 * @return Storage path, or null if not configured
	 */
	public String getStoragePath() {
		AMap<AString, ACell> storageConfig = RT.ensureMap(config.get(STORAGE));
		if (storageConfig != null) {
			AString pathValue = RT.ensureString(storageConfig.get(PATH));
			if (pathValue != null) return pathValue.toString();
		}
		return null;
	}

	/**
	 * Get the maximum content upload size.
	 * @return Maximum size in bytes, defaults to 100MB
	 */
	public long getMaxContentSize() {
		CVMLong cv = RT.ensureLong(config.get(MAX_CONTENT_SIZE));
		if (cv != null) return cv.longValue();
		return DEFAULT_MAX_CONTENT_SIZE;
	}

	// ========== DLFS accessors ==========

	/**
	 * Check if DLFS WebDAV is enabled.
	 * Requires {"webdav": {"enabled": true}} in config.
	 * @return true if WebDAV should be mounted
	 */
	public boolean isWebDAVEnabled() {
		AMap<AString, ACell> webdavConfig = RT.ensureMap(config.get(WEBDAV));
		if (webdavConfig == null) return false;
		return RT.bool(webdavConfig.get(ENABLED));
	}

	// ========== File adapter accessors ==========

	/**
	 * Get the FileAdapter configuration section.
	 *
	 * <p>Expected shape:
	 * <pre>
	 * {
	 *   "file": {
	 *     "roots": {
	 *       "workspace": "/srv/agent-workspace",
	 *       "data":      { "path": "/srv/data", "readOnly": true }
	 *     }
	 *   }
	 * }
	 * </pre>
	 *
	 * <p>If the {@code roots} map is absent or empty, the FileAdapter
	 * defaults to creating a single ephemeral {@code tmp} root that is
	 * deleted recursively on JVM exit.
	 *
	 * <p>A per-root entry may also be {@code {"temp": true, "prefix": "...",
	 * "path": "..."}} to materialise a fresh temp directory at startup
	 * (with optional prefix and parent dir) that is cleaned up at exit.
	 *
	 * @return File config map, or null if not configured
	 */
	public AMap<AString, ACell> getFileConfig() {
		return RT.ensureMap(config.get(FILE));
	}

	// ========== Auth accessors ==========

	/**
	 * Get the auth configuration section.
	 * @return Auth config map, or null if not configured
	 */
	public AMap<AString, ACell> getAuthConfig() {
		return RT.ensureMap(config.get(AUTH));
	}

	/**
	 * Get the JWT token expiry in seconds.
	 * @return Token expiry in seconds, defaults to 86400 (24 hours)
	 */
	public long getTokenExpiry() {
		AMap<AString, ACell> authConfig = getAuthConfig();
		if (authConfig != null) {
			CVMLong expiry = RT.ensureLong(authConfig.get(TOKEN_EXPIRY));
			if (expiry != null) return expiry.longValue();
		}
		return Auth.DEFAULT_TOKEN_EXPIRY;
	}

	/**
	 * Whether public (anonymous) access is enabled.
	 * Reads auth.public.enabled, defaults to true.
	 * @return true if public access is enabled
	 */
	public boolean isPublicAccess() {
		AMap<AString, ACell> authConfig = getAuthConfig();
		if (authConfig != null) {
			AMap<AString, ACell> publicConfig = RT.ensureMap(authConfig.get(PUBLIC));
			if (publicConfig != null) {
				ACell enabledVal = publicConfig.get(ENABLED);
				return (enabledVal == null) || RT.bool(enabledVal);
			}
		}
		return true;
	}

	// ========== Protocol config accessors ==========

	/**
	 * Get the MCP configuration section.
	 * @return MCP config map, or null if MCP is not configured
	 */
	public AMap<AString, ACell> getMCPConfig() {
		return RT.ensureMap(config.get(MCP));
	}

	/**
	 * Get the A2A configuration section.
	 * @return A2A config map, or null if A2A is not configured
	 */
	public AMap<AString, ACell> getA2AConfig() {
		return RT.ensureMap(config.get(A2A));
	}

	/**
	 * Whether MCP is configured.
	 * @return true if MCP configuration is present
	 */
	public boolean hasMCP() {
		return getMCPConfig() != null;
	}

	/**
	 * Whether A2A is configured.
	 * @return true if A2A configuration is present
	 */
	public boolean hasA2A() {
		return getA2AConfig() != null;
	}

	/**
	 * Whether the "Fix MCP Strings" workaround is enabled.
	 * @return true if the venue should re-parse JSON-string arguments into
	 *         their declared object/array shape (defaults to true)
	 */
	public boolean isFixMcpStrings() {
		ACell v = config.get(FIX_MCP_STRINGS);
		return (v == null) || RT.bool(v);
	}

	// ========== Server config accessors ==========

	/**
	 * Get the CORS allowed origins.
	 * @return Allowed origins string, or "*" if not configured (allow all)
	 */
	public String getCorsOrigins() {
		AString origins = RT.ensureString(config.get(CORS_ORIGINS));
		return (origins != null) ? origins.toString() : "*";
	}

	// ========== Static compatibility ==========

	/**
	 * Get the base URL for a venue from a raw config map.
	 * @param config Venue config map
	 * @return Base URL string (no trailing slash)
	 * @deprecated Use instance method {@link #getBaseUrl()} instead
	 */
	@Deprecated
	public static String getBaseUrl(AMap<AString, ACell> config) {
		return new Config(config).getBaseUrl();
	}
}
