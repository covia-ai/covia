package covia.venue.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.http.UriCompliance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.data.Blob;
import convex.core.lang.RT;
import convex.core.util.FileUtils;
import convex.core.store.AStore;
import convex.etch.EtchConfig;
import convex.etch.EtchStore;
import convex.core.util.Utils;
import convex.lattice.LatticeContext;
import convex.node.NodeConfig;
import convex.node.NodeServer;
import covia.api.Fields;
import covia.lattice.Covia;
import covia.venue.Config;
import covia.venue.CoviaApplication;
import covia.venue.Engine;
import covia.venue.LocalVenue;
import convex.dlfs.DLFSDriveManager;
import convex.dlfs.DLFSDrives;
import convex.dlfs.DLFSWebDAV;
import covia.adapter.DLFSAdapter;
import covia.venue.api.A2A;
import covia.venue.api.CoviaAPI;
import covia.venue.api.MCP;
import covia.venue.api.UserAPI;
import covia.venue.auth.LoginProviders;
import covia.venue.auth.VenueAuthenticator;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.staticfiles.Location;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.redoc.ReDocPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import io.javalin.router.exception.HttpResponseExceptionMapper;

/**
 * Covia Venue Server
 * 
 * Contains:
 * - Endpoints for REST API
 * - Javalin HTTP server
 * - Connection to Convex (for CNS etc.)
 * 
 */
public class VenueServer {
	
	public static Logger log=LoggerFactory.getLogger(VenueServer.class);;

	/** POSIX mode for the raw venue seed: readable and writable only by its owner. */
	private static final Set<PosixFilePermission> OWNER_ONLY_KEY_PERMISSIONS =
		PosixFilePermissions.fromString("rw-------");
	
	protected final Config config;

	protected Convex convex;
	protected AStore store;
	protected Javalin javalin;

	/** NodeServer manages lattice persistence and (future) replication */
	protected NodeServer<Index<Keyword, ACell>> nodeServer;

	protected CoviaWebApp webApp;
	protected Engine engine;

	/** Guards {@link #close()} so a double-invocation (explicit close + JVM
	 *  shutdown, or repeated calls) is a safe no-op. */
	private final java.util.concurrent.atomic.AtomicBoolean closed =
			new java.util.concurrent.atomic.AtomicBoolean(false);

	protected CoviaAPI api;
	protected MCP mcp;
	protected A2A a2a;
	protected UserAPI userApi;
	protected LoginProviders loginProviders;
	protected VenueAuthenticator authenticator;

	/**
	 * Extra Javalin route registrars contributed by an embedder — e.g. a service
	 * that embeds this venue and exposes additional endpoints alongside the venue
	 * API. Contributed routes are raw Javalin routes by default, regardless of
	 * path. An embedder may opt each endpoint into venue services with
	 * {@link VenueRouteFeature}. Populated only by launch overloads that accept
	 * route registrars; empty by default.
	 */
	protected final List<Consumer<RoutesConfig>> extraRouteRegistrars = new ArrayList<>();

	/** Per-caller request rate limiter; null when rate limiting is disabled. */
	private RateLimiter rateLimiter;

	public VenueServer(AMap<AString,ACell> config) {
		this(config, null);
	}

	/**
	 * Constructs a venue around an optional caller-opened store.
	 *
	 * <p>When {@code adoptedStore} is non-null, ownership transfers immediately:
	 * this constructor closes it if construction fails, and {@link #close()} closes
	 * it after a successful launch.</p>
	 */
	private VenueServer(AMap<AString,ACell> config, AStore adoptedStore) {
		this.config=new Config(config);
		this.convex=null; // TODO:

		// Create NodeServer with Covia lattice (local-only, no network port)
		// Launch immediately so restore happens before Engine reads the cursor.
		try {
			boolean storePreexisted;
			if (adoptedStore != null) {
				this.store = adoptedStore;
				// An open store may not be file-backed or correspond to Config.STORE.
				// Persisted root data is the reliable signal that this is a relaunch.
				storePreexisted = this.store.getRootData() != null;
			} else {
				// Whether the persistent store file predates this launch must be
				// captured BEFORE createStore opens (and thereby creates) it —
				// resolveKeyPair uses it to tell a first launch from a relaunch
				// that has lost its identity (#232).
				storePreexisted = storeFileExists(this.config);
				this.store = createStore(this.config);
			}
			AKeyPair keyPair = resolveKeyPair(this.config, storePreexisted);
			this.nodeServer = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			nodeServer.setMergeContext(LatticeContext.create(null, keyPair));
			nodeServer.launch(); // restore from store BEFORE Engine init
			CoviaApplication application =
				CoviaApplication.connect(nodeServer.getRootComponent());
			engine = new Engine(this.config, application, keyPair);
			engine.start();
		} catch (Exception e) {
			// Engine construction is inert; start() owns and rolls back its active
			// resources. close() remains safe here for NEW or failed engines.
			if (engine != null) {
				try {
					engine.close();
				} catch (Exception closeFailure) {
					e.addSuppressed(closeFailure);
				}
				engine = null;
			}
			// Roll back the outer server/store resources so the store is not locked.
			if (nodeServer != null) {
				try {
					nodeServer.close();
				} catch (Exception closeFailure) {
					e.addSuppressed(closeFailure);
				}
				nodeServer = null;
			}
			if (store != null) {
				try {
					store.close();
				} catch (Exception closeFailure) {
					e.addSuppressed(closeFailure);
				}
				store = null;
			}
			throw new RuntimeException("Failed to create venue engine", e);
		}

		LocalVenue localVenue=new LocalVenue(engine);
		webApp=new CoviaWebApp(engine);
		api=new CoviaAPI(localVenue);
		userApi=new UserAPI(localVenue);
		loginProviders=engine.getAuth().getLoginProviders();
		authenticator=new VenueAuthenticator(engine);

		AMap<AString,ACell> mcpConfig=this.config.getMCPConfig();
		if (mcpConfig!=null) {
			mcp=new MCP(localVenue,mcpConfig);
		}

		AMap<AString,ACell> a2aConfig=this.config.getA2AConfig();
		if (a2aConfig!=null) {
			a2a=new A2A(localVenue,a2aConfig);
		}
	}

	/**
	 * Creates an AStore based on the "store" config value.
	 * <ul>
	 *   <li>{@code "temp"} (default) — temporary Etch store, deleted on exit</li>
	 *   <li>{@code "memory"} — in-memory store, no persistence</li>
	 *   <li>File path — persistent Etch store at that location</li>
	 * </ul>
	 */
	private static AStore createStore(Config config) throws IOException {
		EtchConfig etchConfig = config.getEtchConfig();
		if (etchConfig != null && etchConfig.getCipherMode() != EtchConfig.CipherMode.NONE
				&& "file".equals(String.valueOf(config.getStorageType()))) {
			log.warn("Encrypted Etch store with 'storage.content: file' — asset content bytes "
				+ "are written OUTSIDE the encrypted store as plaintext files. Use lattice "
				+ "content storage for an encrypted vault, or encrypt the content directory "
				+ "separately.");
		}
		if (!config.isStoreConfigured()) {
			log.warn("No 'store' configured — falling back to ephemeral temp Etch store; data will be deleted on JVM exit. Set 'store' to a file path for persistence, or to \"temp\"/\"memory\" to silence this warning.");
			return (etchConfig != null) ? EtchStore.createTemp(etchConfig) : EtchStore.createTemp();
		}
		String storePath = config.getStore();
		if ("memory".equals(storePath)) {
			if (etchConfig != null) {
				// An operator asking for encryption must never silently get an
				// unencrypted (or non-Etch) store.
				throw new IllegalArgumentException(
					"'etch' configuration requires an Etch store; 'store: memory' is not one");
			}
			log.info("Using in-memory store (no persistence)");
			return new convex.core.store.MemoryStore();
		}
		if ("temp".equals(storePath)) {
			log.info("Using temporary Etch store (deleted on exit)");
			return (etchConfig != null) ? EtchStore.createTemp(etchConfig) : EtchStore.createTemp();
		}
		// Persistent file store
		File f = new File(storePath).getAbsoluteFile();
		f.getParentFile().mkdirs();
		log.info("Using persistent Etch store: {}{}", f,
			(etchConfig != null) ? " (configured Etch policy)" : "");
		return (etchConfig != null) ? EtchStore.create(f, etchConfig) : EtchStore.create(f);
	}

	/**
	 * Resolves the venue identity keypair from config or key file.
	 * <ol>
	 *   <li>Config {@code "seed"} — explicit hex seed (32 bytes)</li>
	 *   <li>Config {@code "keystore"} — PKCS12 keystore in the Convex format (#208);
	 *       any load failure is fatal, never a silent fallback to a generated key</li>
	 *   <li>Key file next to store — auto-persisted on first run</li>
	 *   <li>Generate new — ephemeral (temp store) or saved to key file (persistent store)</li>
	 * </ol>
	 */
	/** True when the config names a persistent store whose file already exists. */
	private static boolean storeFileExists(Config config) {
		String storePath = config.getStore();
		if (storePath == null || "temp".equals(storePath) || "memory".equals(storePath)) return false;
		return Files.exists(Path.of(storePath));
	}

	private static AKeyPair resolveKeyPair(Config config, boolean storePreexisted) throws IOException {
		// 1. Explicit seed in config
		String seedHex = config.getSeed();
		if (seedHex != null) {
			AKeyPair kp = AKeyPair.create(Blob.fromHex(seedHex));
			log.info("Using venue identity from config seed: {}", kp.getAccountKey());
			return kp;
		}

		// 2. Keystore block in config
		AMap<AString, ACell> ksConfig = config.getKeystore();
		if (ksConfig != null) {
			AKeyPair kp = loadFromKeystore(ksConfig);
			log.info("Using venue identity from keystore: {}", kp.getAccountKey());
			return kp;
		}

		// 3. Key file next to store (only for persistent stores)
		String storePath = config.getStore();
		if (!"temp".equals(storePath) && !"memory".equals(storePath)) {
			Path keyFile = Path.of(storePath).resolveSibling("venue.key");
			if (Files.exists(keyFile)) {
				restrictVenueKeyPermissions(keyFile);
				warnIfPlaintextKeyBesideEncryptedStore(config, keyFile);
				String hex = Files.readString(keyFile).trim();
				AKeyPair kp = AKeyPair.create(Blob.fromHex(hex));
				log.info("Using venue identity from key file: {}", kp.getAccountKey());
				return kp;
			}

			// 4. Pre-existing store but no identity source — a CONFIG ERROR
			// (#232). Silently minting a fresh DID would orphan every client's
			// state: logins, agents and secrets all bind to the venue identity.
			if (storePreexisted) {
				throw new IllegalStateException(
					"Venue store " + storePath + " exists but no venue identity is configured: "
					+ "no 'seed', no 'keystore', and no venue.key beside the store. Restore "
					+ keyFile + " or configure the original seed/keystore. To deliberately "
					+ "start a fresh venue, delete or move the store file.");
			}

			// First launch of a new persistent store: generate and save.
			AKeyPair kp = AKeyPair.generate();
			writeVenueKey(keyFile, kp.getSeed().toHexString());
			warnIfPlaintextKeyBesideEncryptedStore(config, keyFile);
			log.info("Generated venue identity (saved to {}): {}", keyFile, kp.getAccountKey());
			return kp;
		}

		// Ephemeral store — generate without saving. Intended behaviour (#208):
		// a stable identity is something the operator pins explicitly (seed or
		// keystore); a throwaway venue gets a throwaway DID.
		AKeyPair kp = AKeyPair.generate();
		log.info("Generated ephemeral venue identity: {}", kp.getAccountKey());
		return kp;
	}

	/**
	 * An encrypted Etch store with a plaintext {@code venue.key} beside it
	 * protects the data but hands the venue <b>identity</b> to anyone holding
	 * the disk — an inconsistent threat posture. Call it out so the operator
	 * moves the identity seed to config/env or a keystore.
	 */
	private static void warnIfPlaintextKeyBesideEncryptedStore(Config config, Path keyFile) {
		EtchConfig ec = config.getEtchConfig();
		if (ec != null && ec.getCipherMode() != EtchConfig.CipherMode.NONE) {
			log.warn("Venue identity seed sits in plaintext at {} beside an ENCRYPTED Etch store — "
				+ "disk theft still yields the venue identity. Prefer 'seed' or a 'keystore' "
				+ "in configuration, and remove the key file.", keyFile);
		}
	}

	/** Creates a raw venue seed with owner-only POSIX permissions from birth. */
	private static void writeVenueKey(Path keyFile, String seedHex) throws IOException {
		Path parent = keyFile.getParent();
		if (parent != null) Files.createDirectories(parent);
		try {
			Files.createFile(keyFile,
				PosixFilePermissions.asFileAttribute(OWNER_ONLY_KEY_PERMISSIONS));
		} catch (UnsupportedOperationException e) {
			// Windows and other non-POSIX filesystems use their inherited ACLs.
			Files.createFile(keyFile);
		}
		Files.writeString(keyFile, seedHex, StandardOpenOption.WRITE);
		restrictVenueKeyPermissions(keyFile);
	}

	/**
	 * Repairs existing raw key files on every launch. Permission repair is
	 * best-effort so an unsupported filesystem or ACL policy cannot strand an
	 * existing venue identity; failures are actionable in the operator log.
	 */
	private static void restrictVenueKeyPermissions(Path keyFile) {
		try {
			Files.setPosixFilePermissions(keyFile, OWNER_ONLY_KEY_PERMISSIONS);
		} catch (UnsupportedOperationException e) {
			// Expected on Windows and other non-POSIX filesystems.
		} catch (IOException | SecurityException e) {
			log.warn("Could not restrict venue identity key {} to owner-only access; "
				+ "secure this file manually: {}", keyFile, e.getMessage());
		}
	}

	/** Default keystore path — the Convex CLI keyring, so venue keys can be
	 *  managed with {@code convex key generate/list/export}. */
	static final String DEFAULT_KEYSTORE_PATH = "~/.convex/keystore.pfx";

	/**
	 * Loads the venue keypair from a PKCS12 keystore per the config block
	 * (see {@link Config#KEYSTORE}). Every failure is fatal with a message
	 * naming the missing piece — a venue must never silently boot with a
	 * different identity than the operator configured (#208).
	 */
	static AKeyPair loadFromKeystore(AMap<AString, ACell> ks) {
		String path = stringField(ks, "path");
		if (path == null) path = envOr("CONVEX_KEYSTORE", DEFAULT_KEYSTORE_PATH);
		String alias = stringField(ks, "alias");
		if (alias == null) throw new IllegalStateException(
			"keystore config requires an 'alias' naming the venue key entry"
			+ " (Convex convention: the hex public key — see 'convex key list')");
		String storepass = stringField(ks, "storepass");
		if (storepass == null) storepass = System.getenv("CONVEX_KEYSTORE_PASSWORD");
		if (storepass == null) throw new IllegalStateException(
			"keystore integrity password not found — set 'storepass' in the keystore"
			+ " config block or the CONVEX_KEYSTORE_PASSWORD environment variable");
		String keypass = stringField(ks, "keypass");
		if (keypass == null) keypass = System.getenv("CONVEX_KEY_PASSWORD");
		if (keypass == null) throw new IllegalStateException(
			"key entry password not found — set 'keypass' in the keystore config"
			+ " block or the CONVEX_KEY_PASSWORD environment variable");

		File file = FileUtils.getFile(path);
		if (!file.exists()) throw new IllegalStateException(
			"keystore file not found: " + file + " — create keys with 'convex key generate'"
			+ " or point 'path' (or CONVEX_KEYSTORE) at an existing PKCS12 keystore");
		try {
			java.security.KeyStore store = PFXTools.loadStore(file, storepass.toCharArray());
			AKeyPair kp = PFXTools.getKeyPair(store, alias, keypass.toCharArray());
			if (kp == null) throw new IllegalStateException(
				"keystore " + file + " has no key entry for alias '" + alias + "'");
			return kp;
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException(
				"failed to load venue key '" + alias + "' from keystore " + file
				+ ": " + e.getMessage(), e);
		}
	}

	/** Reads an optional string field from a config sub-map. */
	private static String stringField(AMap<AString, ACell> map, String key) {
		AString v = RT.ensureString(map.get(Strings.intern(key)));
		return (v != null) ? v.toString() : null;
	}

	/** Environment variable value, or the default when unset/blank. */
	private static String envOr(String name, String dflt) {
		String v = System.getenv(name);
		return (v != null && !v.isBlank()) ? v : dflt;
	}

	/**
	 * Launch a Venue server with the specified config.
	 * @param config Config, or null for default test config.
	 * @return Launched Venue Server instance
	 */
	public static VenueServer launch(AMap<AString,ACell> config) {
		return launchInternal(config, null, null);
	}

	/**
	 * Launches a venue using a caller-opened store instead of opening the store
	 * named by {@link Config#STORE}.
	 *
	 * <p>Ownership transfers at method entry. The venue closes the store on
	 * normal {@link #close()} and on every construction or launch failure. The
	 * remaining config, including venue identity resolution, is unchanged;
	 * embedders should normally provide the recovered identity via
	 * {@code seed} or {@code keystore}.</p>
	 *
	 * @param config Config, or null for default test config.
	 * @param store Open store to adopt; must not be null.
	 * @return Launched Venue Server instance.
	 */
	public static VenueServer launch(AMap<AString,ACell> config, AStore store) {
		return launchInternal(config, Objects.requireNonNull(store, "store"), null);
	}

	/**
	 * Launches a venue with both a caller-opened store and embedder routes.
	 * Store ownership follows {@link #launch(AMap, AStore)}.
	 *
	 * @param config Config, or null for default test config.
	 * @param store Open store to adopt; must not be null.
	 * @param extraRoutes Additional route registrars, or null/empty for none.
	 * @return Launched Venue Server instance.
	 */
	public static VenueServer launch(AMap<AString,ACell> config, AStore store,
			List<Consumer<RoutesConfig>> extraRoutes) {
		return launchInternal(config, Objects.requireNonNull(store, "store"), extraRoutes);
	}

	/**
	 * Launch a Venue server, optionally with extra Javalin route registrars
	 * contributed by an embedder. Each registrar is invoked at server-build time
	 * (Javalin 7 requires routes at create time). Contributed routes receive no
	 * implicit Covia policy based on their URL. Add {@link VenueRouteFeature}
	 * roles to an endpoint to opt into verified identity, venue-user admission,
	 * rate limiting, or post-request lattice sync.
	 *
	 * <pre>{@code
	 * VenueServer.launch(config, List.of(routes ->
	 *     routes.get("/api/product/me", handler,
	 *         VenueRouteFeature.AUTHENTICATED_IDENTITY)));
	 * }</pre>
	 *
	 * Adapter/module installation, catalog materialisation, secret provisioning,
	 * and recovery all complete before the HTTP listener is published. Any
	 * failure closes the partially assembled server in reverse ownership order.
	 *
	 * @param config Config, or null for default test config.
	 * @param extraRoutes Additional route registrars, or null/empty for none.
	 * @return Launched Venue Server instance
	 */
	public static VenueServer launch(AMap<AString,ACell> config, List<Consumer<RoutesConfig>> extraRoutes) {
		return launchInternal(config, null, extraRoutes);
	}

	private static VenueServer launchInternal(AMap<AString,ACell> config,
			AStore adoptedStore, List<Consumer<RoutesConfig>> extraRoutes) {
		if (config==null) {
			config=Maps.of(
					Fields.NAME,"Test Venue",
					Fields.DESCRIPTION,"Unconfigured test venue",
					Strings.create("port"),null, // This uses default (find a port)
					Config.STORE,Strings.create("temp"), // explicit temp — silences unconfigured-store warning
					Fields.MCP,Maps.of(),
					Fields.A2A,Maps.of(),
					Config.AUTH,Maps.of(
						Config.PUBLIC,Maps.of(Config.ENABLED,true)
					)
			);
		}

		VenueServer server = null;
		try {
			server = new VenueServer(config, adoptedStore);
			if (extraRoutes != null) server.extraRouteRegistrars.addAll(extraRoutes);

			// Complete every fallible bootstrap phase before publishing an HTTP
			// listener. A caller must never observe a half-populated venue.
			server.bootstrap();
			server.start();
			return server;
		} catch (RuntimeException | Error failure) {
			if (server != null) server.closeResources(failure);
			throw failure;
		}
	}

	/** Complete durable/runtime bootstrap before the server becomes reachable. */
	private void bootstrap() {
		Engine.addDemoAssets(engine);
		engine.provisionConfiguredSecrets();
		engine.jobs().recoverJobs();

		// Reconcile agent-owned intake after generic Job recovery: clear stale
		// executor markers/fences, remove intake for terminal Jobs, then wake only
		// remaining durable queued work. Internal execution is never resumed.
		if (engine.getAdapter("agent") instanceof covia.adapter.AgentAdapter agentAdapter) {
			agentAdapter.wakeAgentsWithWork();
		}

		// HITL expiry is durable and must be re-armed before requests are served.
		if (engine.getAdapter("hitl") instanceof covia.adapter.HITLAdapter hitlAdapter) {
			hitlAdapter.rearmExpiries();
		}
	}

	/**
	 * Start app with default port
	 */
	public void start() {
		int port = config.getPort();
		start(port);
	}

	
	/**
	 * Start app with specific port
	 */
	private synchronized void start(Integer port) {
		if (javalin!=null) {
			javalin.stop();
			javalin=null;
		}

		javalin=buildApp();
		start(javalin,port);
		log.info("Venue server started on port: "+javalin.port());
	}
	
	/**
	 * Get the actual port the server is listening on.
	 * Useful when launched with port 0 (ephemeral port).
	 * @return Bound port number
	 */
	public int port() {
		return javalin.port();
	}

	/**
	 * Get the Engine instance for this venue server
	 * @return Engine instance
	 */
	public Engine getEngine() {
		return engine;
	}

	/**
	 * Returns the venue's public credential authentication service. Embedders may
	 * use it from contributed Javalin routes, including routes carrying tokens in
	 * headers other than {@code Authorization}.
	 */
	public VenueAuthenticator authenticator() {
		return authenticator;
	}

	public AStore getStore() {
		return store;
	}

	
	/**
	 * Mounts DLFS WebDAV routes when WebDAV is enabled in config.
	 * Creates a DLFSDriveManager that delegates to the DLFS adapter's
	 * lattice-backed drives.
	 *
	 * <p>Routes are registered at server-create time (Javalin 7 requires this),
	 * but the {@code dlfs} adapter is registered later by {@code addDemoAssets}.
	 * The manager therefore resolves the adapter <em>lazily per request</em>; a
	 * request that arrives before the adapter exists simply yields no drive.</p>
	 */
	private void mountDLFSWebDAV(RoutesConfig routes) {
		if (!config.isWebDAVEnabled()) return;

		// Wrap the adapter's lattice drives as a DLFSDriveManager for WebDAV.
		// Unauthenticated requests use the venue's public DID (must match
		// AuthMiddleware's ":public" suffix so WebDAV and REST share a drive).
		String publicDID = engine.getDIDString().toString() + ":public";
		// Convex 0.8.13 removed the no-arg DLFSDriveManager (router) constructor;
		// give the base an empty in-memory registry — every drive access is
		// overridden below to delegate to the dlfs adapter, so the base store is unused.
		DLFSDriveManager webdavManager = new DLFSDriveManager(DLFSDrives.create()) {
			private String resolveIdentity(String identity) {
				return (identity != null) ? identity : publicDID;
			}

			@Override
			public java.nio.file.FileSystem getDrive(String identity, String driveName) {
				DLFSAdapter dlfs = (DLFSAdapter) engine.getAdapter("dlfs");
				if (dlfs == null) return null; // adapter not registered (yet)
				try {
					return dlfs.getDriveForIdentity(resolveIdentity(identity), driveName);
				} catch (Exception e) {
					// A real DLFS failure is not the same as "no such drive" — the
					// WebDAV FileSystem contract forces a null return either way,
					// but log at warn so the operator can tell them apart (#174).
					// Surfacing a 5xx to the WebDAV client needs convex-dlfs support.
					log.warn("WebDAV drive access failed for {}: {}", driveName, e.toString());
					return null;
				}
			}

			@Override
			public boolean createDrive(String identity, String driveName) {
				return getDrive(identity, driveName) != null; // auto-creates via DLFS.connect
			}
		};

		DLFSWebDAV webdav = new DLFSWebDAV(webdavManager);
		webdav.addRoutes(routes);

		log.info("DLFS WebDAV mounted at {}", Config.WEBDAV_PATH);
	}

	private void addAPIRoutes(RoutesConfig routes) {
		api.addRoutes(routes);
		userApi.addRoutes(routes);
		webApp.addRoutes(routes);
		if (mcp!=null) mcp.addRoutes(routes);
		if (a2a!=null) {
			a2a.addRoutes(routes);
		} else {
			// A2A is opt-in (it needs an `a2a` config block). When it's absent,
			// answer the two well-known A2A routes with a helpful hint instead of
			// the generic catch-all 404, so a developer knows the fix is a config
			// addition, not a wrong URL (#179).
			routes.get("/.well-known/agent-card.json", VenueServer::a2aNotConfigured);
			routes.post("/a2a", VenueServer::a2aNotConfigured,
				VenueRouteFeature.COVIA_A2A);
		}
		// Embedder-contributed routes (see extraRouteRegistrars). Registered last;
		// URL placement never opts a route into Covia middleware.
		for (Consumer<RoutesConfig> r : extraRouteRegistrars) r.accept(routes);
	}

	/**
	 * Fallback handler for the well-known A2A routes when no {@code a2a} config
	 * block is present. Returns a 501 with a hint pointing at the missing config,
	 * so the endpoints are self-describing rather than an indistinguishable 404 (#179).
	 */
	private static void a2aNotConfigured(io.javalin.http.Context ctx) {
		ctx.status(501);
		ctx.header("Content-Type", "application/json");
		ctx.result("{\"error\":\"A2A is not configured on this venue\","
				+ "\"hint\":\"Add an \\\"a2a\\\" block with \\\"defaultChatOp\\\" to the venue config "
				+ "to enable the A2A protocol endpoints (POST /a2a and GET /.well-known/agent-card.json).\"}");
	}
	

	private void start(Javalin app, Integer port) {
		org.eclipse.jetty.server.Server jettyServer=app.jettyServer().server();
		setupJettyServer(jettyServer,port);
		app.start();
		addSecondLoopbackConnector(jettyServer);
	}

	protected void setupJettyServer(org.eclipse.jetty.server.Server jettyServer, Integer port) {
		if (port==null) port=8080;
		ServerConnector connector = buildConnector(jettyServer);
		connector.setPort(port);
		// Restrict the listening interface when a bind address is configured.
		// When unset, Jetty binds the wildcard address (0.0.0.0 / all
		// interfaces) — the historical default.
		String bindAddress = config.getBindAddress();
		if (bindAddress != null) connector.setHost(bindAddress);
		jettyServer.addConnector(connector);
		log.info("Venue HTTP connector bound to {}:{}", (bindAddress != null) ? bindAddress : "0.0.0.0", port);
	}

	/**
	 * Builds an HTTP connector with the venue's connector policy.
	 *
	 * <p>URI compliance: encoded path separators (%2F) are allowed — and ONLY
	 * that (#153). Catalog operation names contain slashes (e.g.
	 * "v/ops/jvm/string-concat") and are percent-encoded into a single path
	 * segment by VenueHTTP.getOperationId — the GET /api/v1/operations/{name}
	 * contract. Jetty 12's default UriCompliance rejects %2F as an "ambiguous
	 * path separator" (400). AMBIGUOUS_PATH_ENCODING (encoded dots, %2e → the
	 * `../` path-traversal surface) is deliberately NOT enabled: no route
	 * needs it, so enabling it would only widen the attack surface.</p>
	 *
	 * <p>Acceptor/selector threads are sized explicitly. Jetty defaults
	 * selectors to cores/2, which is wrong here: handlers run on virtual
	 * threads, so the selectors only pump non-blocking I/O — the default
	 * exploded the platform-thread count when many venues share a JVM. See
	 * Config.DEFAULT_HTTP_SELECTORS. The accept queue is deeper than the
	 * Jetty default (50) so connection bursts queue rather than refuse.</p>
	 */
	private ServerConnector buildConnector(org.eclipse.jetty.server.Server jettyServer) {
		HttpConfiguration httpConfig = new HttpConfiguration();
		httpConfig.setUriCompliance(UriCompliance.from(
			"DEFAULT,AMBIGUOUS_PATH_SEPARATOR"));
		ServerConnector connector = new ServerConnector(jettyServer,
			config.getHttpAcceptors(), config.getHttpSelectors(),
			new HttpConnectionFactory(httpConfig));
		connector.setAcceptQueueSize(config.getAcceptQueueSize());
		return connector;
	}

	/**
	 * A loopback bind serves BOTH loopback protocols (#231): a venue bound to
	 * 127.0.0.1 answers ::1 too (and vice versa), so browsers that resolve
	 * {@code localhost} to the other family don't hang on connect (no
	 * listener, no RST). Added AFTER start on the actual bound port (so
	 * ephemeral port 0 mirrors correctly) and strictly best-effort — a
	 * machine without the second protocol keeps its venue.
	 */
	private void addSecondLoopbackConnector(org.eclipse.jetty.server.Server jettyServer) {
		String bindAddress = config.getBindAddress();
		if (bindAddress == null || !isLoopback(bindAddress)) return;
		String other = bindAddress.contains(":") ? "127.0.0.1" : "::1";
		try {
			int actualPort = ((ServerConnector) jettyServer.getConnectors()[0]).getLocalPort();
			ServerConnector second = buildConnector(jettyServer);
			second.setPort(actualPort);
			second.setHost(other);
			jettyServer.addConnector(second);
			second.start();
			log.info("Loopback bind also listening on {}:{}", other, actualPort);
		} catch (Exception e) {
			log.warn("Second loopback connector ({}) unavailable: {}", other, e.getMessage());
		}
	}

	private static boolean isLoopback(String host) {
		try {
			return java.net.InetAddress.getByName(host).isLoopbackAddress();
		} catch (Exception e) {
			return false;
		}
	}

	private Javalin buildApp() {
		Javalin app = Javalin.create(config -> {
			addOpenApiPlugins(config);

			config.staticFiles.add(staticFiles -> {
				staticFiles.hostedPath = "/";
				staticFiles.location = Location.CLASSPATH; // Specify resources from classpath
				staticFiles.directory = "/covia/pub"; // Resource location in classpath
				staticFiles.aliasCheck = null; // you can configure this to enable symlinks (=
												// ContextHandler.ApproveAliases())
				staticFiles.skipFileFunction = req -> false; // you can use this to skip certain files in the dir, based
																// on the HttpServletRequest
			});
			
			config.concurrency.useVirtualThreads=true;

			// Raise HTTP body size limit (default 1 MB is too low for vault uploads).
			config.http.maxRequestSize = 10_000_000L;

			// Disable Jetty's session housekeeper thread. Covia is fully
			// stateless — auth is JWT bearer token per request, no code
			// anywhere calls getSession() or stores session attributes. By
			// default Jetty installs a Session-HouseKeeper thread (non-daemon)
			// to scavenge an empty session store, which kept the test JVM
			// alive for ~5s after the last test completed.
			//
			// We can't simply setSessionHandler(null) — Jetty NPEs. Instead
			// we install a custom SessionIdManager whose HouseKeeper has
			// interval=0, which disables scheduling so no thread is created.
			// Jetty 12 dropped Server.setSessionIdManager: register it as a
			// server bean instead — the SessionHandler resolves its manager
			// from the server beans at startup, so ours (no scavenge thread)
			// wins over the lazily-created default.
			config.jetty.modifyServer(server -> {
				try {
					org.eclipse.jetty.session.DefaultSessionIdManager idMgr =
						new org.eclipse.jetty.session.DefaultSessionIdManager(server);
					org.eclipse.jetty.session.HouseKeeper hk =
						new org.eclipse.jetty.session.HouseKeeper();
					hk.setIntervalSec(0); // disabled — no scavenge thread
					idMgr.setSessionHouseKeeper(hk);
					server.addBean(idMgr);
				} catch (Exception e) {
					log.warn("Failed to disable Jetty session housekeeper", e);
				}
			});

			// Javalin 7: every handler (routes, before/after filters, exception
			// mappers) must be registered via config.routes at create time —
			// the Javalin instance no longer exposes per-verb registration.
			addHandlers(config.routes);
		});

		return app;
	}

	/**
	 * Registers all HTTP handlers on the routes configuration: exception
	 * mappers, CORS preflight/after filters, the lattice-sync after filters,
	 * auth middleware, and the login / API / WebDAV routes.
	 */
	private void addHandlers(RoutesConfig routes) {
		final Config.CorsPolicy corsPolicy = this.config.getCorsPolicy();
		final boolean allowPrivateNetwork = this.config.isAllowPrivateNetwork();

		routes.exception(HttpResponseException.class,
			(e, ctx) -> renderHttpError(e, ctx));

		routes.exception(Exception.class, (e, ctx) -> {
			log.error("Unhandled exception in {} {}", ctx.method(), ctx.path(), e);
			String message = "Unexpected error: " + e.getClass().getSimpleName();
			if (e.getMessage() != null && !e.getMessage().isBlank()) {
				message += ": " + e.getMessage();
			}
			renderHttpError(new InternalServerErrorResponse(message), ctx);
		});

		// One owner for CORS admission and response headers. The old combination
		// of Javalin's plugin plus an unconditional after-filter could reject an
		// origin with 400 and then add an allow header anyway. A parsed policy also
		// lets the loopback sentinel match literal hosts on any port without DNS.
		routes.before(ctx -> applyCorsPolicy(ctx, corsPolicy, allowPrivateNetwork));

		// Native protocol routes and explicitly opted-in embedder routes sync
		// lattice state after handling. Matching by endpoint role prevents an
		// unrelated /api/* route from acquiring Covia persistence semantics.
		routes.afterMatched(ctx -> {
			if (VenueRouteFeature.syncsLattice(ctx.routeRoles())) {
				engine.syncState();
			}
		});

		// Auth middleware: endpoint roles, not URL prefixes, select policy.
		AuthMiddleware.register(routes, engine, authenticator);

		// Rate limiting: per-caller token bucket, keyed on the identity the auth
		// middleware just resolved (all anonymous callers share the venue :public
		// DID → one bucket). Raw opted-in routes fall back to the connection IP.
		// Registered AFTER auth so the caller DID is set. A denied request
		// short-circuits with 429 + Retry-After before any handler runs.
		if (config.isRateLimitEnabled()) {
			rateLimiter = new RateLimiter(config.getRateLimitBurst(), config.getRateLimitRps());
			routes.beforeMatched(ctx -> {
				if (VenueRouteFeature.usesRateLimit(ctx.routeRoles())) {
					enforceRateLimit(ctx);
				}
			});
			log.info("Rate limiting enabled: {} req/s, burst {} per caller",
				(long) config.getRateLimitRps(), (long) config.getRateLimitBurst());
		}

		addLoginRoutes(routes);
		addAPIRoutes(routes);
		mountDLFSWebDAV(routes);
	}

	/**
	 * Delegate HTTP semantics to Javalin, replacing only its unsafe HTML body.
	 * Javalin's mapper is responsible for status, media selection, structured
	 * details, and protocol headers such as {@code Allow}. Its HTML branch emits
	 * the unescaped plain-text result as {@code text/html}, so render that one
	 * representation safely here.
	 */
	private static void renderHttpError(HttpResponseException error, Context ctx) {
		HttpResponseExceptionMapper.INSTANCE.handle(error, ctx);
		if (!acceptsHtml(ctx)) return;

		StringBuilder body = new StringBuilder()
			.append("<!doctype html><html><head><meta charset=\"utf-8\">")
			.append("<title>Error ").append(error.getStatus())
			.append("</title></head><body><h1>Error ")
			.append(error.getStatus()).append("</h1><p>")
			.append(escapeHtml(error.getMessage())).append("</p>");
		if (!error.getDetails().isEmpty()) {
			body.append("<dl>");
			error.getDetails().forEach((name, value) -> body
				.append("<dt>").append(escapeHtml(name)).append("</dt><dd>")
				.append(escapeHtml(value)).append("</dd>"));
			body.append("</dl>");
		}
		ctx.contentType("text/html; charset=utf-8")
			.result(body.append("</body></html>").toString());
	}

	private static boolean acceptsHtml(Context ctx) {
		String accept = ctx.header("Accept");
		return (accept != null && accept.contains("text/html"))
			|| "text/html".equals(ctx.res().getContentType());
	}

	private static String escapeHtml(String value) {
		if (value == null) return "";
		return value.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;")
			.replace("'", "&#39;");
	}

	/** Applies CORS before routing so a denied origin cannot reach a handler. */
	private void applyCorsPolicy(Context ctx, Config.CorsPolicy policy,
			boolean allowPrivateNetwork) {
		if (!policy.enabled()) return; // explicit opt-out: no CORS headers or rejection

		String origin = ctx.header("Origin");
		if (origin == null) {
			// Preserve the legacy wildcard behaviour: non-CORS responses also
			// advertise '*'. Specific policies cannot choose a value without Origin.
			if (policy.anyOrigin()) {
				ctx.header("Access-Control-Allow-Origin", "*");
				ctx.header("Access-Control-Expose-Headers", "X-Covia-User");
				if (allowPrivateNetwork) {
					ctx.header("Access-Control-Allow-Private-Network", "true");
				}
			}
			return;
		}

		String allowed = policy.allowedOriginHeader(origin);
		if (allowed == null) {
			ctx.status(403).result("CORS origin denied");
			ctx.skipRemainingHandlers();
			return;
		}

		ctx.header("Access-Control-Allow-Origin", allowed);
		ctx.header("Access-Control-Expose-Headers", "X-Covia-User");
		if (!"*".equals(allowed)) ctx.header("Vary", "Origin");
		// Private Network Access lets a public web origin reach a venue on a
		// private/loopback address from the browser. Off by default and emitted
		// only after the request origin passes the configured CORS policy.
		if (allowPrivateNetwork) {
			ctx.header("Access-Control-Allow-Private-Network", "true");
		}

		// Handle browser preflights here rather than on /api/* only. MCP and A2A
		// are also browser-facing HTTP surfaces and were covered by Javalin's
		// former global CORS plugin.
		if ("OPTIONS".equalsIgnoreCase(ctx.method().toString())
				&& ctx.header("Access-Control-Request-Method") != null) {
			ctx.status(204);
			ctx.removeHeader("Content-type");
			ctx.header("Access-Control-Allow-Headers",
				"content-type, authorization, x-covia-user");
			ctx.header("Access-Control-Allow-Methods", "GET,HEAD,PUT,PATCH,POST,DELETE");
			ctx.header("Vary", "Origin, Access-Control-Request-Headers");
			ctx.skipRemainingHandlers();
		}
	}

	/**
	 * Rate-limit {@code before} filter. Keys the token bucket on the caller
	 * identity resolved by {@link AuthMiddleware} (authenticated DID, or the
	 * shared {@code :public} DID for native anonymous callers). An opted-in raw
	 * route without identity is keyed on the connection IP. A denied request is
	 * answered 429 + {@code Retry-After} and short-circuits before any handler.
	 * CORS preflight ({@code OPTIONS}) is never throttled.
	 */
	private void enforceRateLimit(Context ctx) {
		if ("OPTIONS".equalsIgnoreCase(ctx.method().toString())) return;
		AString did = AuthMiddleware.getVenueUserDID(ctx);
		String key = (did != null) ? did.toString() : "ip:" + ctx.ip();
		if (!rateLimiter.tryAcquire(key)) {
			long retry = rateLimiter.retryAfterSeconds(key);
			ctx.header("Retry-After", Long.toString(retry));
			ctx.status(429).result("Rate limit exceeded. Retry after " + retry + "s.");
			ctx.skipRemainingHandlers();
		}
	}

	/**
	 * The OAuth <em>connection</em> callback (not login): the provider redirects
	 * the user's browser here after approval; the {@code oauth} adapter
	 * exchanges the code and stores the grant for the user who started the
	 * connect. Unauthenticated by nature — the one-time {@code state} is the
	 * capability — and every outcome is rendered, never thrown.
	 */
	private void handleOAuthConnectCallback(Context ctx) {
		covia.adapter.OAuthAdapter oauth = engine.findAdapter(covia.adapter.OAuthAdapter.class);
		if (oauth == null) {
			ctx.status(404).result("OAuth connections are not enabled on this venue");
			return;
		}
		covia.adapter.OAuthAdapter.Completion done = oauth.complete(ctx.pathParam("provider"),
			ctx.queryParam("state"), ctx.queryParam("code"),
			ctx.queryParam("error"), ctx.queryParam("error_description"));
		if (done.ok() && done.returnTo() != null) {
			ctx.redirect(done.returnTo());
			return;
		}
		String title = done.ok() ? "Connected" : "Not connected";
		String message = done.message().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		ctx.status(done.ok() ? 200 : 400).html("<!doctype html><html><head><meta charset=\"utf-8\"><title>" + title
			+ "</title></head><body style=\"font-family:system-ui,sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem\">"
			+ "<h1>" + title + "</h1><p>" + message + "</p>"
			+ (done.ok() ? "<p>You can close this window.</p>" : "") + "</body></html>");
	}

	private void addLoginRoutes(RoutesConfig app) {
		// The OAuth *connection* callback is independent of login providers.
		app.get("/auth/connect/{provider}/callback", this::handleOAuthConnectCallback);

		if (!loginProviders.hasProviders()) return;

        // Login route for any provider
        app.get("/auth/{provider}", loginProviders::handleLogin);

        // Callback route for any provider
        app.get("/auth/{provider}/callback", loginProviders::handleCallback);

        // Simple login page listing configured providers
        app.get("/login", ctx -> {
            ctx.html(loginProviders.renderLoginPage());
        });
	}

	/**
	 * OpenAPI document at {@code /openapi}, rendered at {@code /swagger} and
	 * {@code /redoc}. The document covers the REST surface ({@code /api/v1/*}
	 * plus the DID documents); the venue's other protocol endpoints — A2A
	 * ({@code /a2a*}, agent cards), MCP ({@code /mcp}), and the auth/login
	 * pages — are deliberately excluded: each has its own discovery mechanism
	 * (agent card, MCP initialize, login page).
	 */
	protected void addOpenApiPlugins(JavalinConfig config) {
		String docsPath = "/openapi";

		config.registerPlugin(new OpenApiPlugin(pluginConfig -> {
			pluginConfig
			.withDocumentationPath(docsPath)
			.withDefinitionConfiguration((version, definition) -> {
				definition.info(info -> {
					// The contract major is the /api/v1 path prefix; info.version
					// tracks the venue build, which is what fixes the set of
					// endpoints and parameters this document describes.
					info.title("Covia API v1");
					info.version(Utils.getVersion());
				});
			});
		}));

		config.registerPlugin(new SwaggerPlugin(swaggerConfiguration->{
			swaggerConfiguration.documentationPath = docsPath;
		}));

		config.registerPlugin(new ReDocPlugin(reDocConfiguration->{
			reDocConfiguration.documentationPath = docsPath;
		}));
	}

	/**
	 * Full shutdown: stops HTTP server, drains the engine's persistence sweep
	 * and runs a final flush, then closes NodeServer and store.
	 *
	 * <p>The engine.close() must run BEFORE nodeServer.close() so the
	 * venueState fork's writes are merged into the root before the
	 * propagator's shutdown drain reads from it. See
	 * {@code venue/docs/PERSISTENCE.md} §5.3.</p>
	 */
	public void close() {
		closeResources(null);
	}

	/**
	 * Releases server resources in reverse ownership order. During failed
	 * launch, cleanup failures are suppressed onto the initiating failure;
	 * during normal close they are logged and cleanup continues.
	 */
	private void closeResources(Throwable launchFailure) {
		if (!closed.compareAndSet(false, true)) return; // idempotent — already closed
		Javalin app = javalin;
		javalin = null;
		if (app != null) {
			try {
				app.stop();
			} catch (RuntimeException e) {
				recordCloseFailure(launchFailure, "HTTP server close failed", e);
			}
		}
		Engine ownedEngine = engine;
		if (ownedEngine != null) {
			try {
				ownedEngine.close(); // stops sweep daemon, runs final synchronous flush
			} catch (RuntimeException e) {
				recordCloseFailure(launchFailure, "Engine close failed", e);
			}
		}
		NodeServer<Index<Keyword, ACell>> ownedNode = nodeServer;
		if (ownedNode != null) {
			try {
				ownedNode.close(); // graceful drain — now sees the engine's final flush
			} catch (IOException e) {
				recordCloseFailure(launchFailure, "NodeServer close failed", e);
			}
		}
		AStore ownedStore = store;
		store = null;
		if (ownedStore != null) {
			try {
				ownedStore.close();
			} catch (RuntimeException e) {
				recordCloseFailure(launchFailure, "Store close failed", e);
			}
		}
	}

	private static void recordCloseFailure(Throwable launchFailure, String message, Throwable failure) {
		if (launchFailure != null) {
			launchFailure.addSuppressed(failure);
		} else {
			log.warn(message, failure);
		}
	}
}
