package covia.venue;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
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

	private static final Logger log = LoggerFactory.getLogger(Config.class);

	// ========== Top-level config keys ==========

	/** Key for venues array */
	public static final AString VENUES = Strings.intern("venues");

	/** Key for Convex peer configuration */
	public static final AString CONVEX = Strings.intern("convex");

	/**
	 * Reject unknown core configuration fields instead of warning about them.
	 * Known fields are type/range checked regardless of this setting.
	 */
	public static final AString STRICT_CONFIG = Strings.intern("strictConfig");

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

	/**
	 * Key for the venue identity keystore block (#208): a PKCS12 keystore in the
	 * Convex format, so venue keys can be managed with the Convex CLI
	 * ({@code convex key generate} etc.). Fields:
	 * <ul>
	 *   <li>{@code path} — keystore file; default {@code ~/.convex/keystore.pfx}
	 *       (env {@code CONVEX_KEYSTORE} fills absence)</li>
	 *   <li>{@code alias} — key entry alias, required (Convex convention: the
	 *       hex public key)</li>
	 *   <li>{@code storepass} — keystore integrity password
	 *       (env {@code CONVEX_KEYSTORE_PASSWORD} fills absence)</li>
	 *   <li>{@code keypass} — key entry password
	 *       (env {@code CONVEX_KEY_PASSWORD} fills absence)</li>
	 * </ul>
	 */
	public static final AString KEYSTORE = Strings.intern("keystore");

	// ========== Venue config keys ==========

	/** Key for venue name */
	public static final AString NAME = Strings.intern("name");

	/** Key for venue DID */
	public static final AString DID = Strings.intern("did");

	/** Key for venue hostname */
	public static final AString HOSTNAME = Strings.intern("hostname");

	/**
	 * Root-page policy. The value is an object containing exactly one of:
	 * {@code {"redirect": "https://operator.example"}} or
	 * {@code {"file": "public/index.html"}}.
	 */
	public static final AString ROOT_PAGE = Strings.intern("rootPage");
	public static final AString REDIRECT = Strings.intern("redirect");

	/** Key for the venue default LLM provider operation used for new agents. */
	public static final AString DEFAULT_LLM_OPERATION = Strings.intern("defaultLlmOperation");

	/** Key for the venue default agent transition operation used for new agents. */
	public static final AString DEFAULT_TRANSITION_OP = Strings.intern("defaultTransitionOp");

	/** Key for the venue default tool-call iteration limit per agent transition. */
	public static final AString MAX_TOOL_ITERATIONS = Strings.intern("maxToolIterations");

	/** Key for venue port */
	public static final AString PORT = Strings.intern("port");

	/**
	 * Key for the HTTP connector's bind address (network interface to listen
	 * on). Distinct from {@link #HOSTNAME}, which is the venue's advertised
	 * public host. When unset the connector binds all interfaces (0.0.0.0);
	 * set to {@code "127.0.0.1"} to restrict to loopback.
	 */
	public static final AString BIND_ADDRESS = Strings.intern("bindAddress");

	/** Key for the request rate-limiting config block ({@code enabled}, {@code rps},
	 *  {@code burst}, {@code maxConcurrentJobsPerUser}). */
	public static final AString RATE_LIMIT = Strings.intern("rateLimit");

	/** Key for the HTTP connector's accept-queue (backlog) size */
	public static final AString ACCEPT_QUEUE_SIZE = Strings.intern("acceptQueueSize");

	/**
	 * Default accept-queue (backlog) depth. The JDK/Jetty default of 50 is too
	 * shallow for bursty connection load: when the queue overflows the OS
	 * refuses new connections (a RST → {@code ConnectException} on Windows)
	 * instead of queuing them. 1024 absorbs the bursts.
	 */
	public static final int DEFAULT_ACCEPT_QUEUE_SIZE = 1024;

	/** Key for the HTTP connector's NIO selector-thread count */
	public static final AString HTTP_SELECTORS = Strings.intern("httpSelectors");

	/** Key for the HTTP connector's acceptor-thread count */
	public static final AString HTTP_ACCEPTORS = Strings.intern("httpAcceptors");

	/**
	 * Default NIO selector threads per connector. Jetty's own default is
	 * {@code cores/2} — appropriate for a dedicated server that handles requests
	 * on its connector thread pool, but wrong for Covia: request handlers run on
	 * virtual threads ({@code useVirtualThreads=true}), so the selectors only pump
	 * non-blocking I/O and a couple suffice. The default mattered most when many
	 * venues run in one JVM (tests): {@code cores/2} each meant N×(cores/2) selector
	 * platform threads — e.g. 16 venues × 16 on a 32-core box = 256 threads fighting
	 * for 32 cores, starving the connectors. A small fixed count keeps the thread
	 * footprint flat in venue count. Operators with a single high-traffic venue can
	 * raise it.
	 */
	public static final int DEFAULT_HTTP_SELECTORS = 2;

	/** Default acceptor threads per connector — one is plenty with a deep accept
	 *  queue ({@link #DEFAULT_ACCEPT_QUEUE_SIZE}). */
	public static final int DEFAULT_HTTP_ACCEPTORS = 1;

	/** Key for MCP configuration */
	public static final AString MCP = Strings.intern("mcp");

	/** Key for A2A configuration */
	public static final AString A2A = Strings.intern("a2a");

	/** Key for adapters configuration */
	public static final AString ADAPTERS = Strings.intern("adapters");

	/** Key for venue modules — external adapter jars loaded at boot (see {@link Modules}) */
	public static final AString MODULES = Strings.intern("modules");

	/** User admission and registration configuration block. */
	public static final AString USERS = Strings.intern("users");
	public static final AString BOOTSTRAP = Strings.intern("bootstrap");

	/** Whether an authenticated, previously unknown DID is registered on first use. */
	public static final AString AUTO_CREATE = Strings.intern("autoCreate");

	/**
	 * Returns the configuration block for a named adapter from the top-level
	 * {@code adapters} section, or an empty map when absent. Example venue
	 * config: {@code {"adapters": {"agent": {"sessionDelete": false}}}} —
	 * {@code getAdapterConfig("agent")} returns {@code {"sessionDelete": false}}.
	 *
	 * @param name The adapter name (as returned by {@code AAdapter.getName()})
	 * @return The adapter's config map, empty if not configured
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getAdapterConfig(String name) {
		ACell adapters = config.get(ADAPTERS);
		if (adapters instanceof AMap) {
			ACell v = ((AMap<AString, ACell>) adapters).get(Strings.create(name));
			if (v instanceof AMap) return (AMap<AString, ACell>) v;
		}
		return Maps.empty();
	}

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

	/** Key for the public caller's capability grant scope, under {@code auth.public}.
	 *  Absent → secure default (read-only); {@code "unrestricted"} → no scope
	 *  (legacy permissive behaviour); an explicit cap array → that scope. */
	public static final AString CAPS = Strings.intern("caps");

	/** Key for the JWT audience policy, under {@code auth}: {@code "verify"}
	 *  (default — check aud if present) or {@code "require"} (aud must be present
	 *  and match). A mismatched aud is always rejected under both. */
	public static final AString AUDIENCE = Strings.intern("audience");

	/** Key for additional accepted JWT audiences, under {@code auth}: an array of
	 *  strings (e.g. a {@code did:key} form alongside the canonical DID). The
	 *  venue's own DID(s) are always accepted; this extends the allowlist. */
	public static final AString ACCEPTED_AUDIENCES = Strings.intern("acceptedAudiences");

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

	/** Key for CORS allowed origins. Accepts a string, vector of strings, or
	 * {@code false}; default is {@code "*"}. The {@code "loopback"} sentinel
	 * matches literal localhost/127.0.0.1/::1 origins on any port. */
	public static final AString CORS_ORIGINS = Strings.intern("corsOrigins");

	/** Key for the Private Network Access opt-in (default: false). */
	public static final AString ALLOW_PRIVATE_NETWORK = Strings.intern("allowPrivateNetwork");
	public static final AString ENABLE_PRIVATE_JOBS = Strings.intern("enablePrivateJobs");
	/** Force durable records for read-only run/internal invocations. */
	public static final AString RECORD_READ_ONLY_OPERATIONS = Strings.intern("recordReadOnlyOperations");

	// ========== MCP config keys ==========

	/** Key for MCP enabled flag */
	public static final AString ENABLED = Strings.intern("enabled");

	/** Key for an explicit authentication requirement. */
	public static final AString REQUIRED = Strings.intern("required");

	/** Key for an allowlist of authenticated caller DIDs. */
	public static final AString ALLOWED_DIDS = Strings.intern("allowedDids");

	/**
	 * Key for the "Fix MCP Strings" workaround flag (top-level, default true).
	 * Some MCP clients serialise nested object/array arguments as JSON strings
	 * instead of the structured types declared in the tool schema. When true,
	 * the venue defensively re-parses such string values into their declared
	 * shape at the MCP boundary and at {@code grid:run}/{@code grid:invoke}
	 * dispatch. Set to false to disable the workaround.
	 */
	public static final AString FIX_MCP_STRINGS = Strings.intern("fixMcpStrings");

	/** Key for the operator's output-schema validation mode (off/warn/strict). */
	public static final AString OUTPUT_VALIDATION = Strings.intern("outputValidation");

	/**
	 * Key for the per-venue secrets bootstrap map.
	 *
	 * <p>Structure is {@code {<userKey>: {<name>: <value>, ...}, ...}} where
	 * {@code userKey} is one of:</p>
	 * <ul>
	 *   <li>{@code "venue"} — resolves to the venue's own DID (intended for
	 *       venue-internal operations and self-issued requests).</li>
	 *   <li>{@code "public"} — resolves to {@code <venueDID>:public} (the
	 *       default identity for unauthenticated callers).</li>
	 *   <li>A literal DID string (e.g. {@code "did:key:z…"}) — assigned
	 *       verbatim to that user's secret store.</li>
	 * </ul>
	 *
	 * <p>Bootstrap runs at server start and overwrites any existing values
	 * for the listed names — config is the source of truth at launch. Names
	 * not listed in config are left untouched.</p>
	 *
	 * <p><b>Never commit production secrets here.</b> Intended for personal
	 * dev configs in gitignored locations (e.g. {@code dev/local.json}).</p>
	 */
	public static final AString SECRETS = Strings.intern("secrets");

	// ========== Instance fields ==========

	private final AMap<AString, ACell> config;

	private static final Set<String> KNOWN_FIELDS = Set.of(
		"name", "description", "did", "hostname", "rootPage",
		"defaultLlmOperation", "defaultTransitionOp", "maxToolIterations",
		"port", "bindAddress", "baseUrl", "rateLimit", "acceptQueueSize",
		"httpSelectors", "httpAcceptors", "mcp", "a2a", "adapters",
		"modules", "users", "store", "seed", "keystore", "storage",
		"maxContentSize", "auth", "webdav", "file", "corsOrigins",
		"allowPrivateNetwork", "enablePrivateJobs", "recordReadOnlyOperations", "fixMcpStrings",
		"outputValidation", "secrets", "strictAssets", "strictConfig");

	/**
	 * Create a Config wrapping the given venue config map.
	 * @param config Venue config map, or null for empty defaults
	 */
	public Config(AMap<AString, ACell> config) {
		this.config = (config == null) ? Maps.empty() : config;
		validate();
	}

	/**
	 * Validates the standalone server document and returns its venue maps.
	 * A server-level {@code strictConfig: true} is inherited by venues that do
	 * not set their own value.
	 *
	 * @param serverConfig complete config document containing {@code venues}
	 * @return validated venue config maps
	 */
	@SuppressWarnings("unchecked")
	public static List<AMap<AString, ACell>> validateServerConfig(
			AMap<AString, ACell> serverConfig) {
		if (serverConfig == null) {
			throw malformed("server", "must be an object");
		}
		boolean strict = optionalBoolean(
			serverConfig, STRICT_CONFIG, "strictConfig", false);
		validateUnknownFields(serverConfig,
			Set.of("venues", "convex", "operations", "strictConfig"),
			"server", strict);
		optionalMap(serverConfig, CONVEX, "convex");
		optionalMap(serverConfig, Strings.intern("operations"), "operations");

		ACell rawVenues = serverConfig.get(VENUES);
		if (!(rawVenues instanceof AVector<?> venues) || venues.count() == 0) {
			throw malformed("venues", "must be a non-empty array of venue objects");
		}
		ArrayList<AMap<AString, ACell>> result = new ArrayList<>();
		for (long i = 0; i < venues.count(); i++) {
			ACell entry = venues.get(i);
			if (!(entry instanceof AMap<?, ?>)) {
				throw malformed("venues[" + i + "]", "must be an object");
			}
			AMap<AString, ACell> venue = (AMap<AString, ACell>) entry;
			if (strict && venue.get(STRICT_CONFIG) == null) {
				venue = venue.assoc(STRICT_CONFIG, CVMBool.TRUE);
			}
			result.add(venue);
		}
		return List.copyOf(result);
	}

	/**
	 * Validates every core field eagerly. Unknown fields remain compatible by
	 * default: they produce an operator warning because modules and future Covia
	 * versions may introduce fields this runtime does not understand. Setting
	 * {@code strictConfig: true} upgrades those warnings to a startup failure.
	 */
	private void validate() {
		boolean strict = optionalBoolean(config, STRICT_CONFIG, "strictConfig", false);
		validateUnknownFields(config, KNOWN_FIELDS, "venue", strict);

		optionalString(config, NAME, "name");
		optionalString(config, Strings.intern("description"), "description");
		optionalString(config, DID, "did");
		optionalString(config, HOSTNAME, "hostname");
		optionalString(config, DEFAULT_LLM_OPERATION, "defaultLlmOperation");
		optionalString(config, DEFAULT_TRANSITION_OP, "defaultTransitionOp");
		optionalString(config, BIND_ADDRESS, "bindAddress");
		optionalString(config, STORE, "store");
		optionalString(config, SEED, "seed");

		optionalLong(config, MAX_TOOL_ITERATIONS, "maxToolIterations", 1, Integer.MAX_VALUE);
		optionalLong(config, PORT, "port", 0, 65_535);
		optionalLong(config, ACCEPT_QUEUE_SIZE, "acceptQueueSize", 1, Integer.MAX_VALUE);
		optionalLong(config, HTTP_SELECTORS, "httpSelectors", 1, Integer.MAX_VALUE);
		optionalLong(config, HTTP_ACCEPTORS, "httpAcceptors", 0, Integer.MAX_VALUE);
		optionalLong(config, MAX_CONTENT_SIZE, "maxContentSize", 1, Long.MAX_VALUE);

		optionalBoolean(config, STRICT_ASSETS, "strictAssets", true);
		optionalBoolean(config, FIX_MCP_STRINGS, "fixMcpStrings", true);
		optionalBoolean(config, ALLOW_PRIVATE_NETWORK, "allowPrivateNetwork", false);
		optionalBoolean(config, ENABLE_PRIVATE_JOBS, "enablePrivateJobs", false);
		optionalBoolean(config, RECORD_READ_ONLY_OPERATIONS, "recordReadOnlyOperations", false);

		validateBaseUrl();
		validateRootPage(strict);
		validateRateLimit(strict);
		validateKeystore(strict);
		validateStorage(strict);
		validateWebDav(strict);
		validateFile(strict);
		validateAuth(strict);
		validateUsers(strict);
		validateMcp(strict);
		validateA2a(strict);
		validateMapField(ADAPTERS, "adapters");
		validateMapField(SECRETS, "secrets");
		validateModules(strict);

		// Reuse the canonical parser so malformed CORS never silently widens.
		getCorsPolicy();

		AString outputValidation = optionalString(
			config, OUTPUT_VALIDATION, "outputValidation");
		if (outputValidation != null
				&& !Set.of("off", "warn", "strict").contains(outputValidation.toString())) {
			throw malformed("outputValidation", "must be one of off, warn, strict");
		}
	}

	private void validateBaseUrl() {
		AString raw = optionalString(config, BASE_URL, "baseUrl");
		if (raw == null) return;
		try {
			URI uri = new URI(raw.toString());
			if (!uri.isAbsolute()
				|| (!"http".equalsIgnoreCase(uri.getScheme())
					&& !"https".equalsIgnoreCase(uri.getScheme()))
				|| uri.getHost() == null || uri.getRawFragment() != null
				|| uri.getRawQuery() != null) {
				throw malformed("baseUrl", "must be an absolute HTTP(S) origin or base path");
			}
		} catch (URISyntaxException e) {
			throw malformed("baseUrl", "must be a valid absolute HTTP(S) URL");
		}
	}

	private void validateRootPage(boolean strict) {
		AMap<AString, ACell> root = optionalMap(config, ROOT_PAGE, "rootPage");
		if (root == null) return;
		validateUnknownFields(root, Set.of("redirect", "file"), "rootPage", strict);
		AString redirect = optionalString(root, REDIRECT, "rootPage.redirect");
		AString file = optionalString(root, FILE, "rootPage.file");
		if ((redirect == null) == (file == null)) {
			throw malformed("rootPage", "must contain exactly one of redirect or file");
		}
		if (redirect != null) validateRedirect(redirect.toString());
		if (file != null) validateRootFile(file.toString());
	}

	private static void validateRedirect(String target) {
		if (target.isBlank() || target.indexOf('\r') >= 0 || target.indexOf('\n') >= 0) {
			throw malformed("rootPage.redirect", "must be a non-empty URL or absolute path");
		}
		if (target.startsWith("/") && !target.startsWith("//")) {
			if ("/".equals(target)) {
				throw malformed("rootPage.redirect", "must not redirect / to itself");
			}
			return;
		}
		try {
			URI uri = new URI(target);
			if (!uri.isAbsolute() || uri.getHost() == null
				|| (!"http".equalsIgnoreCase(uri.getScheme())
					&& !"https".equalsIgnoreCase(uri.getScheme()))) {
				throw malformed("rootPage.redirect",
					"must be an absolute HTTP(S) URL or a path beginning with /");
			}
		} catch (URISyntaxException e) {
			throw malformed("rootPage.redirect",
				"must be a valid absolute HTTP(S) URL or absolute path");
		}
	}

	private static void validateRootFile(String configuredPath) {
		if (configuredPath.isBlank()) {
			throw malformed("rootPage.file", "must not be empty");
		}
		try {
			Path path = Path.of(configuredPath);
			if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
				throw malformed("rootPage.file",
					"must name a readable regular file: " + path.toAbsolutePath());
			}
		} catch (InvalidPathException e) {
			throw malformed("rootPage.file", "is not a valid filesystem path");
		}
	}

	private void validateRateLimit(boolean strict) {
		AMap<AString, ACell> rate = optionalMap(config, RATE_LIMIT, "rateLimit");
		if (rate == null) return;
		validateUnknownFields(rate,
			Set.of("enabled", "rps", "burst", "maxConcurrentJobsPerUser", "blockMs"),
			"rateLimit", strict);
		optionalBoolean(rate, ENABLED, "rateLimit.enabled", false);
		optionalLong(rate, Strings.intern("rps"), "rateLimit.rps", 1, Long.MAX_VALUE);
		optionalLong(rate, Strings.intern("burst"), "rateLimit.burst", 1, Long.MAX_VALUE);
		optionalLong(rate, Strings.intern("maxConcurrentJobsPerUser"),
			"rateLimit.maxConcurrentJobsPerUser", 0, Integer.MAX_VALUE);
		optionalLong(rate, Strings.intern("blockMs"), "rateLimit.blockMs", 0, Long.MAX_VALUE);
	}

	private void validateKeystore(boolean strict) {
		AMap<AString, ACell> keystore = optionalMap(config, KEYSTORE, "keystore");
		if (keystore == null) return;
		validateUnknownFields(keystore,
			Set.of("path", "alias", "storepass", "keypass"), "keystore", strict);
		for (String field : List.of("path", "alias", "storepass", "keypass")) {
			optionalString(keystore, Strings.intern(field), "keystore." + field);
		}
	}

	private void validateStorage(boolean strict) {
		AMap<AString, ACell> storage = optionalMap(config, STORAGE, "storage");
		if (storage == null) return;
		validateUnknownFields(storage, Set.of("content", "path"), "storage", strict);
		AString content = optionalString(storage, CONTENT, "storage.content");
		optionalString(storage, PATH, "storage.path");
		if (content != null && !Set.of("lattice", "memory", "file", "dlfs")
				.contains(content.toString())) {
			throw malformed("storage.content",
				"must be one of lattice, memory, file, dlfs");
		}
	}

	private void validateWebDav(boolean strict) {
		AMap<AString, ACell> webdav = optionalMap(config, WEBDAV, "webdav");
		if (webdav == null) return;
		validateUnknownFields(webdav, Set.of("enabled"), "webdav", strict);
		optionalBoolean(webdav, ENABLED, "webdav.enabled", false);
	}

	private void validateFile(boolean strict) {
		AMap<AString, ACell> file = optionalMap(config, FILE, "file");
		if (file == null) return;
		validateUnknownFields(file, Set.of("roots"), "file", strict);
		// File roots are intentionally extensible and are validated by FileAdapter.
		optionalMap(file, ROOTS, "file.roots");
	}

	private void validateAuth(boolean strict) {
		AMap<AString, ACell> auth = optionalMap(config, AUTH, "auth");
		if (auth == null) return;
		validateUnknownFields(auth,
			Set.of("tokenExpiry", "public", "audience", "acceptedAudiences", "oauth"),
			"auth", strict);
		optionalLong(auth, TOKEN_EXPIRY, "auth.tokenExpiry", 1, Long.MAX_VALUE);

		AString audience = optionalString(auth, AUDIENCE, "auth.audience");
		if (audience != null && !Set.of("verify", "require").contains(audience.toString())) {
			throw malformed("auth.audience", "must be verify or require");
		}
		optionalStringVector(auth, ACCEPTED_AUDIENCES, "auth.acceptedAudiences");

		AMap<AString, ACell> publicConfig = optionalMap(auth, PUBLIC, "auth.public");
		if (publicConfig != null) {
			validateUnknownFields(publicConfig, Set.of("enabled", "caps"),
				"auth.public", strict);
			optionalBoolean(publicConfig, ENABLED, "auth.public.enabled", true);
			ACell caps = publicConfig.get(CAPS);
			if (caps != null && !(caps instanceof AString) && !(caps instanceof AVector)) {
				throw malformed("auth.public.caps",
					"must be \"unrestricted\" or an array of capabilities");
			}
			if (caps instanceof AString s && !"unrestricted".equals(s.toString())) {
				throw malformed("auth.public.caps",
					"the only supported string value is unrestricted");
			}
		}

		AMap<AString, ACell> oauth = optionalMap(auth, OAUTH, "auth.oauth");
		if (oauth != null) {
			validateUnknownFields(oauth, Set.of("google", "microsoft", "github"),
				"auth.oauth", strict);
			for (String provider : List.of("google", "microsoft", "github")) {
				AMap<AString, ACell> providerConfig = optionalMap(
					oauth, Strings.intern(provider), "auth.oauth." + provider);
				if (providerConfig == null) continue;
				validateUnknownFields(providerConfig, Set.of("clientId", "clientSecret"),
					"auth.oauth." + provider, strict);
				AString clientId = optionalString(providerConfig, CLIENT_ID,
					"auth.oauth." + provider + ".clientId");
				AString clientSecret = optionalString(providerConfig, CLIENT_SECRET,
					"auth.oauth." + provider + ".clientSecret");
				if ((clientId == null) != (clientSecret == null)) {
					throw malformed("auth.oauth." + provider,
						"must provide both clientId and clientSecret");
				}
			}
		}
	}

	private void validateUsers(boolean strict) {
		AMap<AString, ACell> users = optionalMap(config, USERS, "users");
		if (users == null) return;
		validateUnknownFields(users, Set.of("autoCreate", "bootstrap"), "users", strict);
		optionalBoolean(users, AUTO_CREATE, "users.autoCreate", false);
		optionalMap(users, BOOTSTRAP, "users.bootstrap");
	}

	private void validateMcp(boolean strict) {
		AMap<AString, ACell> mcp = optionalMap(config, MCP, "mcp");
		if (mcp == null) return;
		validateUnknownFields(mcp, Set.of(
			"enabled", "auth", "includeAdapters", "includePathPrefixes",
			"serverInfo", "servers"), "mcp", strict);
		optionalBoolean(mcp, ENABLED, "mcp.enabled", true);
		optionalStringVector(mcp, Strings.intern("includeAdapters"),
			"mcp.includeAdapters");
		optionalStringVector(mcp, Strings.intern("includePathPrefixes"),
			"mcp.includePathPrefixes");
		optionalMap(mcp, Strings.intern("serverInfo"), "mcp.serverInfo");
		optionalMap(mcp, Strings.intern("servers"), "mcp.servers");
		AMap<AString, ACell> auth = optionalMap(mcp, AUTH, "mcp.auth");
		if (auth == null) return;
		validateUnknownFields(auth, Set.of("required", "allowedDids"), "mcp.auth", strict);
		optionalBoolean(auth, REQUIRED, "mcp.auth.required", false);
		optionalStringVector(auth, ALLOWED_DIDS, "mcp.auth.allowedDids");
		// Preserve the existing DID-shape validation, now at construction time.
		getMCPAllowedDids();
	}

	private void validateA2a(boolean strict) {
		AMap<AString, ACell> a2a = optionalMap(config, A2A, "a2a");
		if (a2a == null) return;
		validateUnknownFields(a2a, Set.of("defaultChatOp", "agentInfo"), "a2a", strict);
		optionalString(a2a, Strings.intern("defaultChatOp"), "a2a.defaultChatOp");
		AMap<AString, ACell> info = optionalMap(
			a2a, Strings.intern("agentInfo"), "a2a.agentInfo");
		if (info == null) return;
		validateUnknownFields(info,
			Set.of("name", "description", "version", "organization", "providerUrl"),
			"a2a.agentInfo", strict);
		for (String field :
			List.of("name", "description", "version", "organization", "providerUrl")) {
			optionalString(info, Strings.intern(field), "a2a.agentInfo." + field);
		}
	}

	private void validateModules(boolean strict) {
		ACell raw = config.get(MODULES);
		if (raw == null) return;
		if (!(raw instanceof AVector<?> modules)) {
			throw malformed("modules", "must be an array");
		}
		for (long i = 0; i < modules.count(); i++) {
			ACell entry = modules.get(i);
			if (entry instanceof AString) continue;
			if (!(entry instanceof AMap<?, ?>)) {
				throw malformed("modules[" + i + "]",
					"must be a path string or an object");
			}
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> module = (AMap<AString, ACell>) entry;
			validateUnknownFields(module, Set.of("path", "sha256", "config"),
				"modules[" + i + "]", strict);
			if (optionalString(module, PATH, "modules[" + i + "].path") == null) {
				throw malformed("modules[" + i + "].path", "is required");
			}
			optionalString(module, Strings.intern("sha256"), "modules[" + i + "].sha256");
			optionalMap(module, Strings.intern("config"), "modules[" + i + "].config");
		}
	}

	private void validateMapField(AString key, String path) {
		AMap<AString, ACell> map = optionalMap(config, key, path);
		if (map == null) return;
		for (var entry : map.entrySet()) {
			if (ADAPTERS.equals(key) && !(entry.getValue() instanceof AMap)) {
				throw malformed(path + "." + entry.getKey(), "must be an object");
			}
			if (SECRETS.equals(key) && !(entry.getValue() instanceof AMap)) {
				throw malformed(path + "." + entry.getKey(),
					"must be an object of secret names and values");
			}
		}
	}

	private static void validateUnknownFields(AMap<AString, ACell> map,
			Set<String> known, String path, boolean strict) {
		ArrayList<String> unknown = new ArrayList<>();
		for (var entry : map.entrySet()) {
			Object key = entry.getKey();
			String name = (key instanceof AString s) ? s.toString() : String.valueOf(key);
			if (!known.contains(name)) unknown.add(path + "." + name);
		}
		if (unknown.isEmpty()) return;
		String message = "Unknown configuration field" + (unknown.size() == 1 ? "" : "s")
			+ ": " + String.join(", ", unknown);
		if (strict) throw new IllegalArgumentException(message);
		log.warn("{} (ignored; set strictConfig=true to reject)", message);
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> optionalMap(AMap<AString, ACell> map,
			AString key, String path) {
		ACell value = map.get(key);
		if (value == null) return null;
		if (!(value instanceof AMap)) throw malformed(path, "must be an object");
		return (AMap<AString, ACell>) value;
	}

	private static AString optionalString(AMap<AString, ACell> map,
			AString key, String path) {
		ACell value = map.get(key);
		if (value == null) return null;
		if (!(value instanceof AString s)) throw malformed(path, "must be a string");
		return s;
	}

	private static boolean optionalBoolean(AMap<AString, ACell> map,
			AString key, String path, boolean defaultValue) {
		ACell value = map.get(key);
		if (value == null) return defaultValue;
		if (!(value instanceof CVMBool b)) throw malformed(path, "must be a boolean");
		return CVMBool.TRUE.equals(b);
	}

	private static long optionalLong(AMap<AString, ACell> map, AString key,
			String path, long minimum, long maximum) {
		ACell value = map.get(key);
		if (value == null) return minimum;
		if (!(value instanceof CVMLong number)) throw malformed(path, "must be an integer");
		long result = number.longValue();
		if (result < minimum || result > maximum) {
			throw malformed(path, "must be between " + minimum + " and " + maximum);
		}
		return result;
	}

	private static AVector<?> optionalStringVector(AMap<AString, ACell> map,
			AString key, String path) {
		ACell value = map.get(key);
		if (value == null) return null;
		if (!(value instanceof AVector<?> vector)) throw malformed(path, "must be an array");
		for (long i = 0; i < vector.count(); i++) {
			if (!(vector.get(i) instanceof AString)) {
				throw malformed(path + "[" + i + "]", "must be a string");
			}
		}
		return vector;
	}

	private static IllegalArgumentException malformed(String path, String requirement) {
		return new IllegalArgumentException("Invalid configuration field '" + path + "': "
			+ requirement);
	}

	/**
	 * Get the raw config map.
	 * @return Underlying config map (never null)
	 */
	public AMap<AString, ACell> getMap() {
		return config;
	}

	/** Whether unknown configuration fields are rejected rather than warned. */
	public boolean isStrictConfig() {
		return RT.bool(config.get(STRICT_CONFIG));
	}

	/** Resolved venue root-page policy, or null for the built-in Covia page. */
	public record RootPage(String redirect, Path file) {
		public boolean isRedirect() {
			return redirect != null;
		}
	}

	/**
	 * Returns the operator-owned root-page policy. A relative file path is
	 * resolved against the venue process working directory.
	 *
	 * @return redirect/file policy, or null to render Covia's built-in page
	 */
	public RootPage getRootPage() {
		AMap<AString, ACell> root = optionalMap(config, ROOT_PAGE, "rootPage");
		if (root == null) return null;
		AString redirect = optionalString(root, REDIRECT, "rootPage.redirect");
		if (redirect != null) return new RootPage(redirect.toString(), null);
		AString file = optionalString(root, FILE, "rootPage.file");
		return new RootPage(null, Path.of(file.toString()).toAbsolutePath().normalize());
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

	/** Config key: whether broken adapter asset resources fail startup (default true). */
	public static final AString STRICT_ASSETS = Strings.intern("strictAssets");

	/**
	 * Whether a broken or missing adapter asset resource fails venue startup.
	 * Default true — a venue booting with silently missing ops is broken
	 * (same policy as {@code Engine.materialiseVOps}). Set
	 * {@code "strictAssets": false} to downgrade to warnings — intended for
	 * test/debug scaffolding only, never production.
	 *
	 * @return true if asset installation failures are fatal
	 */
	public boolean isStrictAssets() {
		ACell v = config.get(STRICT_ASSETS);
		return (v == null) || RT.bool(v);
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
	 * The venue's did:web alias, derived from the configured {@code hostname}:
	 * {@code did:web:<hostname>} when the hostname is a genuine public domain,
	 * null otherwise (default/localhost, IP literals, host:port forms).
	 *
	 * <p>The alias is <b>discovery only</b> (covia#167): it makes the document
	 * at {@code /.well-known/did.json} strictly did:web-resolvable. The venue's
	 * canonical identity remains its did:key — nothing durable (lattice keys,
	 * UCAN issuer, signatures, asset DID URLs) ever references the did:web
	 * form, so a domain change or lapse cannot break stored state.</p>
	 *
	 * @return {@code did:web:<hostname>}, or null if the hostname is not public
	 */
	public AString getWebDID() {
		String host = getHostname();
		if (!isPublicHostname(host)) return null;
		return Strings.create("did:web:" + host);
	}

	/**
	 * True iff {@code host} is a plausible public DNS name: contains a dot,
	 * no port/IPv6 colon, not "localhost", and not an IPv4 literal.
	 */
	static boolean isPublicHostname(String host) {
		if (host == null || host.isEmpty()) return false;
		if (host.indexOf(':') >= 0) return false;        // port or IPv6 literal
		if (host.indexOf('.') < 0) return false;          // bare names (localhost, myhost)
		if (host.matches("[0-9.]+")) return false;        // IPv4 literal
		return true;
	}

	/**
	 * Venue default LLM provider operation for new agents that declare a
	 * systemPrompt but no explicit llmOperation. Operator-configurable so a
	 * venue can default to a different provider (e.g. Anthropic) without code
	 * changes. Per-provider default <em>models</em> remain in the provider
	 * adapter — a single venue model default cannot be right across providers.
	 * @return configured op, or {@code "v/ops/langchain/openai"} if unset
	 */
	public AString getDefaultLlmOperation() {
		AString v = RT.ensureString(config.get(DEFAULT_LLM_OPERATION));
		return (v != null) ? v : Strings.intern("v/ops/langchain/openai");
	}

	/**
	 * Venue default agent transition operation for new agents that don't
	 * declare one.
	 * @return configured op, or {@code "v/ops/llmagent/chat"} if unset
	 */
	public AString getDefaultTransitionOp() {
		AString v = RT.ensureString(config.get(DEFAULT_TRANSITION_OP));
		return (v != null) ? v : Strings.intern("v/ops/llmagent/chat");
	}

	/** Venue default tool-call iteration limit per agent transition — the
	 *  runaway-loop backstop, not a work quota. Overridable per agent via
	 *  {@code config.maxToolIterations}.
	 *  @return configured limit ({@code >= 1}), or 30 if unset */
	public int getMaxToolIterations() {
		ACell v = config.get(MAX_TOOL_ITERATIONS);
		if (v instanceof CVMLong l && l.longValue() >= 1) {
			return (int) Math.min(l.longValue(), Integer.MAX_VALUE);
		}
		return 30;
	}

	/**
	 * Get the configured bind address (network interface the HTTP connector
	 * listens on).
	 *
	 * <p>Unlike {@link #getHostname()} this is a socket bind address, not the
	 * advertised public host. When unset, returns {@code null} and the
	 * connector binds all interfaces (0.0.0.0) — preserving the historical
	 * default. Set to {@code "127.0.0.1"} to restrict the venue to loopback.</p>
	 *
	 * @return bind address string, or {@code null} to bind all interfaces
	 */
	public String getBindAddress() {
		AString bindAddress = RT.ensureString(config.get(BIND_ADDRESS));
		return (bindAddress != null) ? bindAddress.toString() : null;
	}

	/** True when the connector binds only the loopback interface. */
	private boolean isLoopbackBind() {
		String b = getBindAddress();
		return b != null && (b.equals("127.0.0.1") || b.equalsIgnoreCase("localhost") || b.equals("::1"));
	}

	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> rateLimitConfig() {
		ACell v = config.get(RATE_LIMIT);
		return (v instanceof AMap) ? (AMap<AString, ACell>) v : null;
	}

	private long rateLimitLong(String key, long dflt) {
		AMap<AString, ACell> rl = rateLimitConfig();
		if (rl == null) return dflt;
		CVMLong v = RT.ensureLong(rl.get(Strings.create(key)));
		return (v != null) ? v.longValue() : dflt;
	}

	/**
	 * Whether request rate limiting is enabled. Default: <b>on</b> for a
	 * LAN/public bind, <b>off</b> for a loopback bind (the embedded-venue case,
	 * where the only caller is a trusted local process). An explicit
	 * {@code rateLimit.enabled} always wins.
	 */
	public boolean isRateLimitEnabled() {
		AMap<AString, ACell> rl = rateLimitConfig();
		if (rl != null) {
			ACell e = rl.get(ENABLED);
			if (e != null) return RT.bool(e);
		}
		return !isLoopbackBind();
	}

	/** Sustained request rate per caller (tokens/second). Default 100 — a coarse
	 *  flood backstop, deliberately high: the concurrent-job cap is the precise
	 *  control, so the request rate should rarely bite a legitimate caller. */
	public double getRateLimitRps() {
		return rateLimitLong("rps", 100);
	}

	/** Burst capacity per caller (max tokens). Default 300 — absorbs normal
	 *  bursts, trips only on genuine floods. */
	public double getRateLimitBurst() {
		return rateLimitLong("burst", 300);
	}

	/** Maximum concurrent (non-terminal) jobs a single caller may hold. Default
	 *  100. Bounds accumulation of long-lived jobs. A top-level invoke over this
	 *  limit blocks (see {@link #getRateLimitBlockMs}) then sheds with 429. */
	public int getMaxConcurrentJobsPerUser() {
		return (int) rateLimitLong("maxConcurrentJobsPerUser", 100);
	}

	/** Max time (ms) a top-level invoke blocks waiting for a concurrency permit
	 *  before shedding with 429. Default 3000. Keep it under typical client read
	 *  timeouts so a saturated caller gets a clean 429, not a socket timeout. */
	public long getRateLimitBlockMs() {
		return rateLimitLong("blockMs", 3000);
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
	 * Get the HTTP connector accept-queue (backlog) size.
	 * @return configured value, or {@link #DEFAULT_ACCEPT_QUEUE_SIZE}
	 */
	public int getAcceptQueueSize() {
		CVMLong v = RT.ensureLong(config.get(ACCEPT_QUEUE_SIZE));
		return (v != null) ? (int) v.longValue() : DEFAULT_ACCEPT_QUEUE_SIZE;
	}

	/**
	 * Get the HTTP connector's NIO selector-thread count.
	 * @return configured value, or {@link #DEFAULT_HTTP_SELECTORS}
	 */
	public int getHttpSelectors() {
		CVMLong v = RT.ensureLong(config.get(HTTP_SELECTORS));
		return (v != null) ? (int) v.longValue() : DEFAULT_HTTP_SELECTORS;
	}

	/**
	 * Get the HTTP connector's acceptor-thread count.
	 * @return configured value, or {@link #DEFAULT_HTTP_ACCEPTORS}
	 */
	public int getHttpAcceptors() {
		CVMLong v = RT.ensureLong(config.get(HTTP_ACCEPTORS));
		return (v != null) ? (int) v.longValue() : DEFAULT_HTTP_ACCEPTORS;
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
	 * Whether the {@code store} key was explicitly set in config.
	 *
	 * <p>Distinguishes a deliberate {@code "store": "temp"} from a missing key
	 * (which {@link #getStore()} also resolves to {@code "temp"} as a default).
	 * Use this to warn operators that data will not survive a restart when the
	 * fallback fires unintentionally.
	 *
	 * @return true if {@code store} is present in the config map
	 */
	public boolean isStoreConfigured() {
		return config.get(STORE) != null;
	}

	/**
	 * Get the venue identity seed (Ed25519, 32-byte hex).
	 * @return Hex seed string, or null if not configured
	 */
	public String getSeed() {
		AString seedVal = RT.ensureString(config.get(SEED));
		return (seedVal != null) ? seedVal.toString() : null;
	}

	/**
	 * Get the venue identity keystore block, or null if not configured.
	 * See {@link #KEYSTORE} for the field contract.
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getKeystore() {
		ACell raw = config.get(KEYSTORE);
		return (raw instanceof AMap) ? (AMap<AString, ACell>) raw : null;
	}

	/**
	 * Get the configured secrets bootstrap map.
	 *
	 * <p>Returns the raw {@code secrets} map from config — the engine's
	 * bootstrap path is responsible for resolving each top-level key
	 * (e.g. {@code "venue"}, {@code "public"}, or a literal DID) into a
	 * concrete user DID and writing the inner {@code name → value} entries
	 * into that user's encrypted secret store.</p>
	 *
	 * @return Secrets map, or null if not configured
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getSecrets() {
		ACell raw = config.get(SECRETS);
		if (raw instanceof AMap) return (AMap<AString, ACell>) raw;
		return null;
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
	 * The raw {@code auth.public.caps} value, used to derive the capability
	 * grant scope applied to unauthenticated (public) callers. Returns {@code null}
	 * when unconfigured (the caller then applies the secure read-only default),
	 * the literal string {@code "unrestricted"} to opt out of any scope, or an
	 * explicit capability vector to use as the scope.
	 *
	 * @return the configured value, or null if {@code auth.public.caps} is absent
	 */
	public ACell getPublicCapsConfig() {
		AMap<AString, ACell> authConfig = getAuthConfig();
		if (authConfig == null) return null;
		AMap<AString, ACell> publicConfig = RT.ensureMap(authConfig.get(PUBLIC));
		if (publicConfig == null) return null;
		return publicConfig.get(CAPS);
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

	/**
	 * Whether authenticated DIDs are automatically registered on first use.
	 * Defaults to false: authenticating an identity is not itself authority to
	 * create an account at this venue. Public test venues can opt in with
	 * {@code "users": {"autoCreate": true}}.
	 */
	public boolean isUserAutoCreate() {
		AMap<AString, ACell> users = RT.ensureMap(config.get(USERS));
		if (users == null) return false;
		ACell value = users.get(AUTO_CREATE);
		return value != null && RT.bool(value);
	}

	/**
	 * Named venue users to provision on first boot. Each map entry is keyed by
	 * local username and may declare {@code authenticationKeys: [did:key:...]}.
	 * The engine applies these keys only when no authenticator history exists;
	 * subsequent startup never rotates or reactivates identity material.
	 */
	public AMap<AString, ACell> getUserBootstrapConfig() {
		AMap<AString, ACell> users = RT.ensureMap(config.get(USERS));
		return (users != null) ? RT.ensureMap(users.get(BOOTSTRAP)) : null;
	}

	/**
	 * JWT audience policy from {@code auth.audience}: {@code "require"} (aud must
	 * be present and match this venue) or {@code "verify"} (the default — if aud
	 * is present it must match; if absent, accept). A mismatched aud is always
	 * rejected; there is no "off".
	 * @return {@code "require"} or {@code "verify"} (default)
	 */
	public String getAudiencePolicy() {
		AMap<AString, ACell> authConfig = getAuthConfig();
		if (authConfig != null) {
			AString v = RT.ensureString(authConfig.get(AUDIENCE));
			if (v != null && "require".equals(v.toString())) return "require";
		}
		return "verify";
	}

	/**
	 * Additional accepted JWT audiences from {@code auth.acceptedAudiences} — an
	 * array of strings extending the allowlist beyond the venue's own DID(s).
	 * @return the configured array, or null if unset
	 */
	public AVector<ACell> getAcceptedAudiences() {
		AMap<AString, ACell> authConfig = getAuthConfig();
		return (authConfig != null) ? RT.ensureVector(authConfig.get(ACCEPTED_AUDIENCES)) : null;
	}

	// ========== Protocol config accessors ==========

	/**
	 * Get the configured venue module entries — external adapter jars loaded
	 * at boot. Each entry is a path string or a {@code {path, sha256?}} map.
	 * @return The modules config value, or null if none configured
	 */
	public ACell getModules() {
		return config.get(MODULES);
	}

	/**
	 * Get the MCP configuration section.
	 * @return MCP config map, or null if MCP is not configured
	 */
	public AMap<AString, ACell> getMCPConfig() {
		return RT.ensureMap(config.get(MCP));
	}

	private AMap<AString, ACell> getMCPAuthConfig() {
		AMap<AString, ACell> mcp = getMCPConfig();
		ACell raw = (mcp == null) ? null : mcp.get(AUTH);
		if (raw == null) return null;
		AMap<AString, ACell> auth = RT.ensureMap(raw);
		if (auth == null) {
			throw new IllegalArgumentException("mcp.auth must be an object");
		}
		return auth;
	}

	/**
	 * Whether the MCP transport requires a bearer-authenticated caller.
	 *
	 * <p>{@code mcp.auth.required} defaults to the inverse of venue-wide public
	 * access. An MCP DID allowlist also implies authentication. An explicit
	 * {@code false} never re-opens MCP when venue-wide public access is disabled.
	 * Discovery metadata remains public independently of this setting.</p>
	 *
	 * @return true when anonymous MCP transport requests must be rejected
	 */
	public boolean isMCPAuthRequired() {
		AMap<AString, ACell> auth = getMCPAuthConfig();
		ACell raw = (auth == null) ? null : auth.get(REQUIRED);
		if (raw != null && !(raw instanceof CVMBool)) {
			throw new IllegalArgumentException("mcp.auth.required must be a boolean");
		}
		boolean configuredRequired = (raw == null) ? !isPublicAccess() : CVMBool.TRUE.equals(raw);
		return configuredRequired || !isPublicAccess() || !getMCPAllowedDids().isEmpty();
	}

	/**
	 * Authenticated caller DIDs allowed to use the MCP transport. Empty means
	 * every admitted authenticated DID is allowed.
	 *
	 * @return immutable DID allowlist
	 */
	public Set<String> getMCPAllowedDids() {
		AMap<AString, ACell> auth = getMCPAuthConfig();
		ACell raw = (auth == null) ? null : auth.get(ALLOWED_DIDS);
		if (raw == null) return Set.of();
		if (!(raw instanceof AVector<?> vector)) {
			throw new IllegalArgumentException("mcp.auth.allowedDids must be an array of DID strings");
		}
		LinkedHashSet<String> result = new LinkedHashSet<>();
		for (long i = 0; i < vector.count(); i++) {
			AString entry = RT.ensureString(vector.get(i));
			String did = (entry == null) ? null : entry.toString().trim();
			if (did == null || !did.startsWith("did:") || did.length() <= 4) {
				throw new IllegalArgumentException(
					"mcp.auth.allowedDids entries must be non-empty DID strings");
			}
			try {
				if (convex.auth.did.DID.fromString(did) == null) {
					throw new IllegalArgumentException(
						"mcp.auth.allowedDids entry is not a valid DID: " + did);
				}
			} catch (RuntimeException e) {
				throw new IllegalArgumentException(
					"mcp.auth.allowedDids entry is not a valid DID: " + did, e);
			}
			result.add(did);
		}
		return Set.copyOf(result);
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

	/**
	 * Output-schema validation mode applied when a one-shot job completes:
	 * {@code "off"} (default — no validation, no logging), {@code "warn"} (log a
	 * warning if the result does not match the operation's output schema), or
	 * {@code "strict"} (fail the job). This is an operator decision (venue
	 * config), distinct from the per-operation {@code strict} flag that governs
	 * input validation — and off by default, so nothing is validated or logged
	 * unless an operator opts in.
	 */
	public String getOutputValidation() {
		AString v = RT.ensureString(config.get(OUTPUT_VALIDATION));
		return (v != null) ? v.toString() : "off";
	}

	// ========== Server config accessors ==========

	/**
	 * Parsed CORS policy. Exact origins use browser origin semantics (scheme,
	 * literal host, and effective port); the loopback sentinel never performs a
	 * DNS lookup, avoiding DNS-rebinding expansion of the allow-list.
	 */
	public static final class CorsPolicy {
		private final boolean enabled;
		private final boolean anyOrigin;
		private final boolean loopback;
		private final List<String> origins;
		private final List<ParsedOrigin> parsedOrigins;

		private CorsPolicy(boolean enabled, boolean anyOrigin, boolean loopback,
				List<String> origins) {
			this.enabled = enabled;
			this.anyOrigin = anyOrigin;
			this.loopback = loopback;
			this.origins = List.copyOf(origins);
			this.parsedOrigins = this.origins.stream()
				.map(origin -> ParsedOrigin.parseConfigured(origin, "corsOrigins entry"))
				.toList();
		}

		public boolean enabled() { return enabled; }
		public boolean anyOrigin() { return anyOrigin; }
		public boolean loopback() { return loopback; }
		public List<String> origins() { return origins; }

		/**
		 * Returns the value to emit in Access-Control-Allow-Origin, or null when
		 * the request origin is not allowed. The caller echoes the original,
		 * browser-serialised origin for specific policies and emits {@code *} only
		 * for the wildcard policy.
		 */
		public String allowedOriginHeader(String requestOrigin) {
			if (!enabled || requestOrigin == null) return null;
			ParsedOrigin candidate;
			try {
				candidate = ParsedOrigin.parse(requestOrigin, "Origin header");
			} catch (IllegalArgumentException e) {
				return null;
			}
			if (anyOrigin) return "*";
			if (parsedOrigins.contains(candidate)) return requestOrigin;
			if (loopback && candidate.isLiteralLoopback()) return requestOrigin;
			return null;
		}
	}

	/** Normalised RFC 6454-style HTTP(S) origin tuple. */
	private record ParsedOrigin(String scheme, String host, int port) {
		/** Javalin's legacy allowHost accepted a bare host and supplied HTTPS. */
		static ParsedOrigin parseConfigured(String raw, String label) {
			String value = (raw != null && !raw.contains("://")) ? "https://" + raw : raw;
			return parse(value, label);
		}

		static ParsedOrigin parse(String raw, String label) {
			if (raw == null || raw.isBlank()) {
				throw new IllegalArgumentException(label + " must not be empty");
			}
			String value = raw.trim();
			try {
				URI uri = new URI(value);
				String scheme = uri.getScheme();
				String host = uri.getHost();
				if (scheme == null || host == null || uri.isOpaque()
						|| uri.getRawUserInfo() != null || uri.getRawQuery() != null
						|| uri.getRawFragment() != null
						|| (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
					throw new IllegalArgumentException(label + " is not an origin: " + raw);
				}
				scheme = scheme.toLowerCase(Locale.ROOT);
				if (!"http".equals(scheme) && !"https".equals(scheme)) {
					throw new IllegalArgumentException(label + " must use http or https: " + raw);
				}
				host = host.toLowerCase(Locale.ROOT);
				if (host.startsWith("[") && host.endsWith("]")) {
					host = host.substring(1, host.length() - 1);
				}
				int port = uri.getPort();
				if (port > 65535) {
					throw new IllegalArgumentException(label + " has an invalid port: " + raw);
				}
				if (port < 0) port = "https".equals(scheme) ? 443 : 80;
				return new ParsedOrigin(scheme, host, port);
			} catch (URISyntaxException e) {
				throw new IllegalArgumentException(label + " is not a valid origin: " + raw, e);
			}
		}

		boolean isLiteralLoopback() {
			return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
		}
	}

	/**
	 * Parse {@code corsOrigins}. Supported forms:
	 * <ul>
	 *   <li>omitted or {@code "*"}: allow every valid HTTP(S) origin</li>
	 *   <li>one origin string: allow that origin</li>
	 *   <li>an array: allow all listed origins; may include {@code "loopback"}</li>
	 *   <li>{@code "loopback"}: literal localhost / IPv4 / IPv6 loopback, any port</li>
	 *   <li>{@code false}, {@code "none"}, or an empty array: disable CORS</li>
	 * </ul>
	 * Invalid or ambiguous forms fail at startup rather than widening to {@code *}.
	 */
	public CorsPolicy getCorsPolicy() {
		ACell raw = config.get(CORS_ORIGINS);
		if (raw == null) return new CorsPolicy(true, true, false, List.of());
		if (CVMBool.FALSE.equals(raw)) return new CorsPolicy(false, false, false, List.of());

		ArrayList<String> values = new ArrayList<>();
		if (raw instanceof AString s) {
			values.add(s.toString());
		} else if (raw instanceof AVector<?> vector) {
			for (long i = 0; i < vector.count(); i++) {
				AString value = RT.ensureString(vector.get(i));
				if (value == null) {
					throw new IllegalArgumentException("corsOrigins array entries must be strings");
				}
				values.add(value.toString());
			}
		} else {
			throw new IllegalArgumentException(
				"corsOrigins must be a string, an array of strings, or false");
		}

		if (values.isEmpty()) return new CorsPolicy(false, false, false, List.of());
		LinkedHashSet<String> exact = new LinkedHashSet<>();
		boolean any = false;
		boolean loopback = false;
		boolean none = false;
		for (String rawValue : values) {
			String value = rawValue.trim();
			switch (value) {
				case "*" -> any = true;
				case "loopback" -> loopback = true;
				case "none" -> none = true;
				default -> {
					ParsedOrigin.parseConfigured(value, "corsOrigins entry");
					exact.add(value);
				}
			}
		}
		if (none) {
			if (values.size() != 1) {
				throw new IllegalArgumentException(
					"corsOrigins 'none' cannot be combined with allowed origins");
			}
			return new CorsPolicy(false, false, false, List.of());
		}
		return new CorsPolicy(true, any, loopback, new ArrayList<>(exact));
	}

	/**
	 * Legacy scalar CORS accessor. New code should use {@link #getCorsPolicy()}.
	 * @return the scalar policy, or null when CORS is disabled
	 * @throws IllegalStateException when the configured policy cannot be
	 * represented by one string
	 */
	@Deprecated
	public String getCorsOrigins() {
		CorsPolicy policy = getCorsPolicy();
		if (!policy.enabled()) return null;
		if (policy.anyOrigin()) return "*";
		if (policy.loopback() && policy.origins().isEmpty()) return "loopback";
		if (!policy.loopback() && policy.origins().size() == 1) return policy.origins().get(0);
		throw new IllegalStateException("CORS policy has multiple origins; use getCorsPolicy()");
	}

	/**
	 * Whether to emit the {@code access-control-allow-private-network} response
	 * header, which lets a public web origin reach this venue on a
	 * private/loopback address from the browser (Chrome Private Network Access).
	 *
	 * <p><b>Default follows the bind (covia#286):</b> a loopback-bound venue
	 * answers PNA preflights, so a hosted https page can reach the user's own
	 * localhost venue — the venue is reachable only from its own machine, which
	 * is exactly the case PNA is meant to permit with server consent. A public-
	 * or LAN-bound venue keeps it off: PNA protects private networks from public
	 * origins, and a public-internet venue never receives a PNA preflight anyway.
	 * The same loopback signal already gates the rate-limit default.</p>
	 *
	 * <p>An explicit {@code allowPrivateNetwork} setting overrides the default in
	 * either direction — set {@code true} to answer PNA preflights on a non-
	 * loopback dev venue (accepting that a public origin may then reach it across
	 * the private network), or {@code false} to refuse them even on loopback.</p>
	 */
	public boolean isAllowPrivateNetwork() {
		ACell v = config.get(ALLOW_PRIVATE_NETWORK);
		if (v != null) return RT.bool(v);   // explicit override, either direction
		return isLoopbackBind();
	}

	/**
	 * Whether the venue accepts private (memory-only) jobs (#192): invoked with
	 * {@code private: true}, never persisted — no record in the caller's job
	 * index, no lattice write, no recovery, gone on restart. Off by default;
	 * a private request against a venue without this is an error, never a
	 * silent downgrade to a persisted job.
	 */
	public boolean isPrivateJobsEnabled() {
		ACell v = config.get(ENABLE_PRIVATE_JOBS);
		return (v != null) && RT.bool(v);
	}

	/**
	 * Whether read-only result-oriented invocations must still be recorded.
	 * Defaults to false: a declared read-only operation may use a transient Job
	 * for {@code run} and {@code invokeInternal}.
	 */
	public boolean isRecordReadOnlyOperations() {
		ACell v = config.get(RECORD_READ_ONLY_OPERATIONS);
		return (v != null) && RT.bool(v);
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
