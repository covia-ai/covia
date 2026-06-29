package covia.venue.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import convex.core.data.Blob;
import convex.core.store.AStore;
import convex.etch.EtchStore;
import convex.lattice.LatticeContext;
import convex.node.NodeConfig;
import convex.node.NodeServer;
import covia.api.Fields;
import covia.lattice.Covia;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.LocalVenue;
import convex.dlfs.DLFSDriveManager;
import convex.dlfs.DLFSWebDAV;
import covia.adapter.DLFSAdapter;
import covia.venue.api.A2A;
import covia.venue.api.CoviaAPI;
import covia.venue.api.MCP;
import covia.venue.api.UserAPI;
import covia.venue.auth.LoginProviders;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.config.RoutesConfig;
import io.javalin.http.HttpResponseException;
import io.javalin.http.staticfiles.Location;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;

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
	
	protected final Config config;

	protected Convex convex;
	protected AStore store;
	protected Javalin javalin;

	/** NodeServer manages lattice persistence and (future) replication */
	protected NodeServer<Index<Keyword, ACell>> nodeServer;

	protected CoviaWebApp webApp;
	protected Engine engine;

	protected CoviaAPI api;
	protected MCP mcp;
	protected A2A a2a;
	protected UserAPI userApi;
	protected LoginProviders loginProviders;

	/**
	 * Extra Javalin route registrars contributed by an embedder — e.g. a service
	 * that embeds this venue and exposes additional endpoints alongside the venue
	 * API. Invoked from {@link #addAPIRoutes} (after {@link AuthMiddleware} is
	 * registered and within the {@code /api/*} filters), so routes mounted under
	 * {@code /api/...} inherit caller-identity extraction and post-request lattice
	 * sync. Populated only via {@link #launch(AMap, List)}; empty by default, so
	 * the standalone venue behaves exactly as before.
	 */
	protected final List<Consumer<RoutesConfig>> extraRouteRegistrars = new ArrayList<>();

	public VenueServer(AMap<AString,ACell> config) {
		this.config=new Config(config);
		this.convex=null; // TODO:

		// Create NodeServer with Covia lattice (local-only, no network port)
		// Launch immediately so restore happens before Engine reads the cursor.
		try {
			this.store = createStore(this.config);
			AKeyPair keyPair = resolveKeyPair(this.config);
			this.nodeServer = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			nodeServer.setMergeContext(LatticeContext.create(null, keyPair));
			nodeServer.launch(); // restore from store BEFORE Engine init
			// Wire the synchronous persistence handler — used by Engine.flush(),
			// the periodic flush sweep, and the close-time final flush. See
			// venue/docs/PERSISTENCE.md §5.0.
			//
			// persist() pushes the snapshot through the propagator's
			// setRootData (mmap write); flush() forces fsync so the bytes are
			// actually on disk before the call returns. Only EtchStore has a
			// real fsync to call — for other store types (memory, etc.) the
			// flush is implicitly a no-op.
			final AStore wiredStore = this.store;
			covia.venue.PersistenceHandler persistHandler = new covia.venue.PersistenceHandler() {
				@Override
				public void persist(ACell value) {
					try {
						nodeServer.persistSnapshot(value);
					} catch (java.io.IOException e) {
						throw new RuntimeException("persistSnapshot failed", e);
					}
				}
				@Override
				public void flush() throws java.io.IOException {
					if (wiredStore instanceof EtchStore es) {
						es.flush();
					}
				}
			};
			engine = new Engine(config, nodeServer.getCursor(), keyPair, persistHandler);
		} catch (Exception e) {
			throw new RuntimeException("Failed to create venue engine", e);
		}

		LocalVenue localVenue=new LocalVenue(engine);
		webApp=new CoviaWebApp(engine);
		api=new CoviaAPI(localVenue);
		userApi=new UserAPI(localVenue);
		loginProviders=engine.getAuth().getLoginProviders();

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
		if (!config.isStoreConfigured()) {
			log.warn("No 'store' configured — falling back to ephemeral temp Etch store; data will be deleted on JVM exit. Set 'store' to a file path for persistence, or to \"temp\"/\"memory\" to silence this warning.");
			return EtchStore.createTemp();
		}
		String storePath = config.getStore();
		if ("memory".equals(storePath)) {
			log.info("Using in-memory store (no persistence)");
			return new convex.core.store.MemoryStore();
		}
		if ("temp".equals(storePath)) {
			log.info("Using temporary Etch store (deleted on exit)");
			return EtchStore.createTemp();
		}
		// Persistent file store
		File f = new File(storePath).getAbsoluteFile();
		f.getParentFile().mkdirs();
		log.info("Using persistent Etch store: {}", f);
		return EtchStore.create(f);
	}

	/**
	 * Resolves the venue identity keypair from config or key file.
	 * <ol>
	 *   <li>Config {@code "seed"} — explicit hex seed (32 bytes)</li>
	 *   <li>Key file next to store — auto-persisted on first run</li>
	 *   <li>Generate new — ephemeral (temp store) or saved to key file (persistent store)</li>
	 * </ol>
	 */
	private static AKeyPair resolveKeyPair(Config config) throws IOException {
		// 1. Explicit seed in config
		String seedHex = config.getSeed();
		if (seedHex != null) {
			AKeyPair kp = AKeyPair.create(Blob.fromHex(seedHex));
			log.info("Using venue identity from config seed: {}", kp.getAccountKey());
			return kp;
		}

		// 2. Key file next to store (only for persistent stores)
		String storePath = config.getStore();
		if (!"temp".equals(storePath) && !"memory".equals(storePath)) {
			Path keyFile = Path.of(storePath).resolveSibling("venue.key");
			if (Files.exists(keyFile)) {
				String hex = Files.readString(keyFile).trim();
				AKeyPair kp = AKeyPair.create(Blob.fromHex(hex));
				log.info("Using venue identity from key file: {}", kp.getAccountKey());
				return kp;
			}

			// 3. Generate and save to key file
			AKeyPair kp = AKeyPair.generate();
			keyFile.getParent().toFile().mkdirs();
			Files.writeString(keyFile, kp.getSeed().toHexString());
			log.info("Generated venue identity (saved to {}): {}", keyFile, kp.getAccountKey());
			return kp;
		}

		// Ephemeral store — generate without saving
		AKeyPair kp = AKeyPair.generate();
		log.info("Generated ephemeral venue identity: {}", kp.getAccountKey());
		return kp;
	}

	/**
	 * Launch a Venue server with the specified config.
	 * @param config Config, or null for default test config.
	 * @return Launched Venue Server instance
	 */
	public static VenueServer launch(AMap<AString,ACell> config) {
		return launch(config, null);
	}

	/**
	 * Launch a Venue server, optionally with extra Javalin route registrars
	 * contributed by an embedder. Each registrar is invoked at server-build time
	 * (Javalin 7 requires routes at create time), after the auth middleware and
	 * within the {@code /api/*} filters — so routes mounted under {@code /api/...}
	 * inherit caller-identity extraction and post-request lattice sync.
	 *
	 * @param config Config, or null for default test config.
	 * @param extraRoutes Additional route registrars, or null/empty for none.
	 * @return Launched Venue Server instance
	 */
	public static VenueServer launch(AMap<AString,ACell> config, List<Consumer<RoutesConfig>> extraRoutes) {
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

		VenueServer server= new VenueServer(config);
		if (extraRoutes!=null) server.extraRouteRegistrars.addAll(extraRoutes);
		server.start();

		Engine.addDemoAssets(server.getEngine());
		server.getEngine().provisionConfiguredSecrets();
		server.getEngine().jobs().recoverJobs();

		return server;
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
		DLFSDriveManager webdavManager = new DLFSDriveManager() {
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
					log.debug("WebDAV drive access failed for {}: {}", driveName, e.getMessage());
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

		log.info("DLFS WebDAV mounted at /dlfs/");
	}

	private void addAPIRoutes(RoutesConfig routes) {
		api.addRoutes(routes);
		userApi.addRoutes(routes);
		webApp.addRoutes(routes);
		if (mcp!=null) mcp.addRoutes(routes);
		if (a2a!=null) a2a.addRoutes(routes);
		// Embedder-contributed routes (see extraRouteRegistrars). Registered last,
		// after the auth middleware, so /api/... routes inherit caller identity + sync.
		for (Consumer<RoutesConfig> r : extraRouteRegistrars) r.accept(routes);
	}
	

	private void start(Javalin app, Integer port) {
		org.eclipse.jetty.server.Server jettyServer=app.jettyServer().server();
		setupJettyServer(jettyServer,port);
		app.start();

	}
	
	protected void setupJettyServer(org.eclipse.jetty.server.Server jettyServer, Integer port) {
		if (port==null) port=8080;
		// Allow encoded path separators (%2F) in URIs. Catalog operation names
		// contain slashes (e.g. "v/ops/jvm/string-concat") and are percent-encoded
		// into a single path segment by VenueHTTP.getOperationId — the GET
		// /api/v1/operations/{name} contract. Jetty 12's default UriCompliance
		// rejects %2F as an "ambiguous path separator" (400); Jetty 11 and
		// Javalin's own connector permit it. Since we build the connector
		// ourselves (below), we must opt back in here or named catalog lookups
		// — and cross-venue named references — break.
		HttpConfiguration httpConfig = new HttpConfiguration();
		httpConfig.setUriCompliance(UriCompliance.from(
			"DEFAULT,AMBIGUOUS_PATH_SEPARATOR,AMBIGUOUS_PATH_ENCODING"));

		// Size the connector's acceptor/selector threads explicitly. Jetty defaults
		// selectors to cores/2, which is wrong here: handlers run on virtual threads
		// (useVirtualThreads=true), so the selectors only pump non-blocking I/O. The
		// default exploded the platform-thread count when many venues share a JVM
		// (N×cores/2 selectors), starving the connectors. See Config.DEFAULT_HTTP_SELECTORS.
		ServerConnector connector = new ServerConnector(jettyServer,
			config.getHttpAcceptors(), config.getHttpSelectors(),
			new HttpConnectionFactory(httpConfig));
		connector.setPort(port);
		// Restrict the listening interface when a bind address is configured.
		// When unset, Jetty binds the wildcard address (0.0.0.0 / all
		// interfaces) — the historical default.
		String bindAddress = config.getBindAddress();
		if (bindAddress != null) connector.setHost(bindAddress);
		// Deeper accept queue than the JDK/Jetty default (50) so bursts of
		// concurrent connections queue rather than being refused under load.
		connector.setAcceptQueueSize(config.getAcceptQueueSize());
		jettyServer.addConnector(connector);
		log.info("Venue HTTP connector bound to {}:{}", (bindAddress != null) ? bindAddress : "0.0.0.0", port);
	}

	private Javalin buildApp() {
		final String corsOrigins = this.config.getCorsOrigins();
		Javalin app = Javalin.create(config -> {
			config.bundledPlugins.enableCors(cors -> {
				cors.addRule(corsConfig -> {
					if ("*".equals(corsOrigins)) {
						corsConfig.anyHost();
					} else {
						corsConfig.allowHost(corsOrigins);
					}
					corsConfig.exposeHeader("X-Covia-User");
				});
			});
			
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
		final String corsOrigins = this.config.getCorsOrigins();
		final boolean allowPrivateNetwork = this.config.isAllowPrivateNetwork();

		routes.exception(HttpResponseException.class, (e, ctx) -> {
			VenueServer.this.api.buildError(ctx,e.getStatus(),e.getMessage());
		});

		routes.exception(Exception.class, (e, ctx) -> {
			log.error("Unhandled exception in {} {}", ctx.method(), ctx.path(), e);
			String message = "Unexpected error: " + e;
			ctx.result(message);
			ctx.status(500);
		});

		routes.options("/api/*", ctx-> {
			ctx.status(204);
			ctx.removeHeader("Content-type");
			ctx.header("access-control-allow-headers", "content-type, authorization, x-covia-user");
			ctx.header("access-control-allow-methods", "GET,HEAD,PUT,PATCH,POST,DELETE");
			ctx.header("access-control-allow-origin", corsOrigins);
			ctx.header("vary","Origin, Access-Control-Request-Headers");
		});

		// Use after (not afterMatched) so headers are added to ALL responses,
		// including CORS preflights handled by the Javalin CORS plugin
		routes.after(ctx->{
			ctx.header("access-control-allow-origin", corsOrigins);
			// Private Network Access lets a public web origin reach a venue on a
			// private/loopback address from the browser. Off by default — it
			// undermines corsOrigins scoping (a malicious page could read a
			// localhost venue). Opt in via allowPrivateNetwork for the
			// preview-origin dev workflow that needs it.
			if (allowPrivateNetwork) {
				ctx.header("access-control-allow-private-network", "true");
			}
		});

		// Sync lattice state after every mutation-capable request so writes
		// are durable across restart. Covers REST (/api/*), MCP JSON-RPC
		// (/mcp), and A2A endpoints. Without this, MCP-driven writes
		// (agent_create, covia_write, asset_store, etc.) live only in
		// memory and are lost on shutdown — silently.
		routes.after("/api/*", ctx -> engine.syncState());
		routes.after("/mcp",   ctx -> engine.syncState());
		routes.after("/mcp/*", ctx -> engine.syncState());
		routes.after("/a2a",   ctx -> engine.syncState());
		routes.after("/a2a/*", ctx -> engine.syncState());

		// Auth middleware: before-handlers extracting caller identity.
		AuthMiddleware.register(routes, engine.getAccountKey(), engine.getAuth(), engine.getDIDString());

		addLoginRoutes(routes);
		addAPIRoutes(routes);
		mountDLFSWebDAV(routes);
	}

	private void addLoginRoutes(RoutesConfig app) {
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

	protected void addOpenApiPlugins(JavalinConfig config) {
		String docsPath = "/openapi";

		config.registerPlugin(new OpenApiPlugin(pluginConfig -> {
			pluginConfig
			.withDocumentationPath(docsPath)
			.withDefinitionConfiguration((version, definition) -> {
				definition.info(info -> {
					info.title("Covia API");
					info.version("0.1.0");
				});
			});
		}));

		config.registerPlugin(new SwaggerPlugin(swaggerConfiguration->{
			swaggerConfiguration.documentationPath = docsPath;
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
		if (javalin!=null) {
			javalin.stop();
			javalin=null;
		}
		if (engine!=null) {
			try {
				engine.close(); // stops sweep daemon, runs final synchronous flush
			} catch (Exception e) {
				log.warn("Engine close failed", e);
			}
		}
		if (nodeServer!=null) {
			try {
				nodeServer.close(); // graceful drain — now sees the engine's final flush
			} catch (IOException e) {
				log.warn("NodeServer close failed", e);
			}
		}
		if (store!=null) {
			store.close();
			store=null;
		}
	}
}
