package covia.venue;

import org.eclipse.jetty.server.ServerConnector;

import convex.api.Convex;
import covia.venue.auth.LoginProviders;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.staticfiles.Location;
import io.javalin.openapi.JsonSchemaLoader;
import io.javalin.openapi.JsonSchemaResource;
import io.javalin.openapi.plugin.DefinitionConfiguration;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;

public class VenueServer {

	
	
	protected Convex convex;
	protected Javalin javalin;

	protected CoviaWebApp webApp;
	protected Venue venue;

	protected CoviaAPI api;

	public VenueServer(Convex convex) {
		this.convex=convex;
		webApp=new CoviaWebApp();
		venue=Venue.createTemp();
		api=new CoviaAPI(venue);
	}

	public static VenueServer create(Object object) {
		return new VenueServer(null);
	}

	/**
	 * Start app with default port
	 */
	public void start() {
		start(null);
	}

	public Venue getVenue() {
		return venue;
	}
	
	/**
	 * Start app with specific port
	 */
	public synchronized void start(Integer port) {
		close();
		javalin=buildApp();
		start(javalin,port);
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

		adddLoginRoutes(app);
		addAPIRoutes(app);	
		return app;
	}
	
	private void adddLoginRoutes(Javalin app) {
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

		for (JsonSchemaResource generatedJsonSchema : new JsonSchemaLoader().loadGeneratedSchemes()) {
	        System.out.println(generatedJsonSchema.getName());
	    }
	}
	

	private void addAPIRoutes(Javalin app) {
		
		api.addRoutes(app);
		webApp.addRoutes(app);
	}
	

	


	public void close() {
		if (javalin!=null) {
			javalin.stop();
			javalin=null;
		}
	}
}
