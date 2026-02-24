package covia.venue.server;

import java.io.IOException;

import org.eclipse.jetty.server.ServerConnector;
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
import covia.venue.api.A2A;
import covia.venue.api.CoviaAPI;
import covia.venue.api.MCP;
import covia.venue.api.UserAPI;
import covia.venue.auth.LoginProviders;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.HttpResponseException;
import io.javalin.http.staticfiles.Location;
import io.javalin.openapi.plugin.DefinitionConfiguration;
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

	public VenueServer(AMap<AString,ACell> config) {
		this.config=new Config(config);
		this.convex=null; // TODO:

		// Create NodeServer with Covia lattice (local-only, no network port)
		try {
			AStore store = EtchStore.createTemp();
			this.nodeServer = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			engine = new Engine(config, nodeServer.getCursor());
			// Set merge context so propagator can re-sign after OwnerLattice merge
			nodeServer.setMergeContext(LatticeContext.create(null, engine.getKeyPair()));
		} catch (IOException e) {
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
	 * Launch a Venue server with the specified config. 
	 * @param config Config, or null for default test config.
	 * @return Launched Venue Server instance
	 */
	public static VenueServer launch(AMap<AString,ACell> config) {
		if (config==null) {
			config=Maps.of(
					Fields.NAME,"Test Venue",
					Fields.DESCRIPTION,"Unconfigured test venue",
					Strings.create("port"),null, // This uses default (find a port)
					Fields.MCP,Maps.of(),
					Fields.A2A,Maps.of(),
					Config.AUTH,Maps.of(
						Config.PUBLIC,Maps.of(Config.ENABLED,true)
					)
			);
		}
		
		VenueServer server= new VenueServer(config);
		server.start();
		
		Engine.addDemoAssets(server.getEngine());
		server.getEngine().recoverJobs();
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
		close();

		// Launch NodeServer (restore from store, start propagator)
		try {
			nodeServer.launch();
		} catch (Exception e) {
			throw new RuntimeException("NodeServer launch failed", e);
		}

		javalin=buildApp();
		AuthMiddleware.register(javalin, engine.getAccountKey(), engine.getAuth(), engine.getDIDString());
		addLoginRoutes(javalin);
		addAPIRoutes(javalin);
		start(javalin,port);
		log.info("Venue server started on port: "+javalin.port());
	}
	
	/**
	 * Get the Engine instance for this venue server
	 * @return Engine instance
	 */
	public Engine getEngine() {
		return engine;
	}

	
	private void addAPIRoutes(Javalin javalin) {	
		api.addRoutes(javalin);
		userApi.addRoutes(javalin);
		webApp.addRoutes(javalin);
		if (mcp!=null) mcp.addRoutes(javalin);
		if (a2a!=null) a2a.addRoutes(javalin);
	}
	

	private void start(Javalin app, Integer port) {
		org.eclipse.jetty.server.Server jettyServer=app.jettyServer().server();
		setupJettyServer(jettyServer,port);
		app.start();

	}
	
	protected void setupJettyServer(org.eclipse.jetty.server.Server jettyServer, Integer port) {
		if (port==null) port=8080;
		ServerConnector connector = new ServerConnector(jettyServer);
		connector.setPort(port);
		jettyServer.addConnector(connector);
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
				staticFiles.precompress = false; // if the files should be pre-compressed and cached in memory
													// (optimization)
				staticFiles.aliasCheck = null; // you can configure this to enable symlinks (=
												// ContextHandler.ApproveAliases())
				staticFiles.skipFileFunction = req -> false; // you can use this to skip certain files in the dir, based
																// on the HttpServletRequest
			});
			
			config.useVirtualThreads=true;
		});
		
		
		app.exception(HttpResponseException.class, (e, ctx) -> {
			VenueServer.this.api.buildError(ctx,e.getStatus(),e.getMessage());
		});

		app.exception(Exception.class, (e, ctx) -> {
			e.printStackTrace();
			String message = "Unexpected error: " + e;
			ctx.result(message);
			ctx.status(500);
		});
		
		app.options("/*", ctx-> {
			ctx.status(204);
			ctx.removeHeader("Content-type");
			ctx.header("access-control-allow-headers", "content-type, authorization, x-covia-user");
			ctx.header("access-control-allow-methods", "GET,HEAD,PUT,PATCH,POST,DELETE");
			ctx.header("access-control-allow-origin", corsOrigins);
			ctx.header("vary","Origin, Access-Control-Request-Headers");
		});

		// Use app.after (not afterMatched) so headers are added to ALL responses,
		// including CORS preflights handled by the Javalin CORS plugin
		app.after(ctx->{
			ctx.header("access-control-allow-origin", corsOrigins);
			// Allow Private Network Access (PNA) so public origins like preview.covia.ai
			// can reach a locally-running venue on localhost
			ctx.header("access-control-allow-private-network", "true");
		});

		// Sync lattice state after API mutations to trigger persistence
		app.after("/api/*", ctx -> engine.syncState());


		return app;
	}
	
	private void addLoginRoutes(Javalin app) {
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
		//String docsPath="/openapi-plugin/openapi-covia-v1.json";
		
		config.registerPlugin(new SwaggerPlugin(swaggerConfiguration->{
			swaggerConfiguration.setDocumentationPath("/openapi");
			//swaggerConfiguration.setDocumentationPath(docsPath);
		}));

		config.registerPlugin(new OpenApiPlugin(pluginConfig -> {
            pluginConfig
            //.withDocumentationPath(docsPath)
            .withDefinitionConfiguration((version, definition) -> {
            	DefinitionConfiguration def=definition;
                def=def.withInfo(
                		info -> {
							info.setTitle("Covia API");
							info.setVersion("0.1.0");
		                });
            });
		}));

		//for (JsonSchemaResource generatedJsonSchema : new JsonSchemaLoader().loadGeneratedSchemes()) {
	    //    System.out.println(generatedJsonSchema.getName());
	    //}
	}

	public void close() {
		if (javalin!=null) {
			javalin.stop();
			javalin=null;
		}
		if (nodeServer!=null) {
			try {
				nodeServer.close(); // final sync + persist
			} catch (IOException e) {
				log.warn("NodeServer close failed", e);
			}
		}
	}
}
