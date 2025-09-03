package covia.venue.server;

import org.eclipse.jetty.server.ServerConnector;

import convex.api.Convex;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.api.CoviaAPI;
import covia.venue.api.MCP;
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
	
	protected final AMap<AString,ACell> config;
	
	protected Convex convex;
	protected Javalin javalin;

	protected CoviaWebApp webApp;
	protected Engine engine;

	protected CoviaAPI api;
	protected MCP mcp;

	public VenueServer(AMap<AString,ACell> config) {
		this.config=config;
		this.convex=null; // TODO:
		engine=Engine.createTemp(config);
		webApp=new CoviaWebApp(engine);
		api=new CoviaAPI(engine);
		
		AMap<AString,ACell> mcpConfig=RT.getIn(config, Fields.MCP);
		if (RT.bool(mcpConfig)) {
			mcp=new MCP(engine,mcpConfig);
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
					Fields.MCP,Maps.of()
			);
		}
		
		VenueServer server= new VenueServer(config);
		server.start();
		
		Engine.addDemoAssets(server.getEngine());
		return server;
	}

	/**
	 * Start app with default port
	 */
	public void start() {
		AInteger port=RT.ensureInteger(RT.getIn(config, Strings.create("port")));
		Integer iport=(port==null)?null:(int)port.longValue();
		start(iport);
	}

	
	/**
	 * Start app with specific port
	 */
	private synchronized void start(Integer port) {
		close();
		javalin=buildApp();
		addLoginRoutes(javalin);
		addAPIRoutes(javalin);	
		start(javalin,port);
	}
	
	/**
	 * Get the Engine instance for this venue server
	 * @return
	 */
	public Engine getEngine() {
		return engine;
	}

	
	private void addAPIRoutes(Javalin javalin) {	
		api.addRoutes(javalin);
		webApp.addRoutes(javalin);
		if (mcp!=null) mcp.addRoutes(javalin);
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
		Javalin app = Javalin.create(config -> {
			config.bundledPlugins.enableCors(cors -> {
				cors.addRule(corsConfig -> {
					// ?? corsConfig.allowCredentials=true;
					
					// replacement for enableCorsForAllOrigins()
					corsConfig.anyHost();
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
			ctx.status(204); // No context#
			ctx.removeHeader("Content-type");
			ctx.header("access-control-allow-headers", "content-type");
			ctx.header("access-control-allow-methods", "GET,HEAD,PUT,PATCH,POST,DELETE");
			ctx.header("access-control-allow-origin", "*");
			ctx.header("vary","Origin, Access-Control-Request-Headers");
		});
		
		// Header to every response
		app.afterMatched(ctx->{
			// Reflect CORS origin
			String origin = ctx.req().getHeader("Origin");
			if (origin!=null) {
				ctx.header("access-control-allow-origin", "*");
			} else {
				ctx.header("access-control-allow-origin", "*");
			}
		});


		return app;
	}
	
	private void addLoginRoutes(Javalin app) {
        // Login route for any provider
        app.get("/auth/{provider}", LoginProviders::handleLogin);
 
        // Callback route for any provider
        app.get("/auth/{provider}/callback", LoginProviders::handleCallback);
        
        // Simple login page to choose a provider
        app.get("/login", ctx -> {
            ctx.html("<h1>Login</h1>" +
                     "<a href='/auth/google'>Login with Google</a><br>" +
                     "<a href='/auth/facebook'>Login with Facebook</a>");
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
	}
}
