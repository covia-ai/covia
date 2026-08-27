package covia.venue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.FileUtils;
import convex.core.util.JSON;
import convex.core.util.Shutdown;
import covia.api.Fields;
import covia.venue.server.VenueServer;

/**
 * Main venue server entry point class.
 */
public class MainVenue {

	public static Logger log=LoggerFactory.getLogger(MainVenue.class);

	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception {
		configureLogging(null);
		
		AMap<AString,ACell> config=null;
		
		// First argument is config file path, if specified
		if (args.length>0) try {
			// Resolve the CLI argument the way a shell user expects: relative to the
			// working directory, ~ expanded. Resolved explicitly rather than via
			// Convex FileUtils.getPath — that treated bare relative names as
			// root-relative until Convex-Dev/convex#701 (fixed in 0.8.13); explicit
			// resolution also handles ~ expansion, which getPath does not.
			String configPath=args[0];
			Path cPath = configPath.startsWith("~")
				? Path.of(System.getProperty("user.home") + configPath.substring(1))
				: Path.of(configPath);
			cPath = cPath.toAbsolutePath().normalize();
			if (!Files.exists(cPath)) {
				log.error("Config file does not exist: "+cPath);
			}
			config =(AMap<AString, ACell>) JSON.parseJSON5(Files.readString(cPath));
			log.info("Server startup config loaded from "+cPath);
		} catch (Exception ex) {
			log.error("Error loading config",ex);
			System.exit(66); // terminate with EX_NOINPUT
		}
		
		// Default config if no config file is specified
		if (config==null) {
			config = Maps.of(
					Fields.VENUES,Vectors.of(
							Maps.of(
									Fields.NAME,"Local Test Venue",
									Fields.HOSTNAME,"localhost",
									Fields.MCP,Maps.of())));
		}
		
		List<AMap<AString, ACell>> venues = Config.validateServerConfig(config);
		List<VenueServer> servers=new ArrayList<>();
		VenueProcess process = VenueProcess.create(args);
		for (AMap<AString,ACell> venueConfig: venues) {
			VenueServer server;
			try {
				server = VenueServer.launch(venueConfig);
			} catch (RuntimeException | Error e) {
				// One line naming the venue and every cause beneath the failure,
				// before the stack trace: a start-up failure is usually a config
				// or packaging problem whose reason sits several exceptions down.
				log.error("Venue '{}' failed to start: {}",
					RT.getIn(venueConfig, Fields.NAME), describeStartupFailure(e), e);
				for (VenueServer started : servers) {
					try { started.close(); } catch (Exception ignored) { /* exiting anyway */ }
				}
				System.exit(70); // EX_SOFTWARE
				return;
			}
			process.manage(server);
			servers.add(server);
		}
		process.arm();

		// On JVM shutdown (e.g. `docker stop` → SIGTERM) flush each venue's
		// accumulated state before the process exits. Registered on Convex's
		// shared, priority-ordered Shutdown registry at a priority BELOW SERVER,
		// so the venue's high-level flush — the venueState fork merge + fsync via
		// the idempotent Engine.close() — runs before Convex's own NodeServer
		// persist (SERVER) and Etch flush (ETCH). Deliberately not a second
		// Runtime.addShutdownHook: that would run concurrently with Convex's
		// shutdown and race the store close/flush.
		Shutdown.addHook(Shutdown.SERVER - 10, () -> {
			log.info("Shutdown signal received — flushing {} venue(s)", servers.size());
			for (VenueServer server: servers) {
				try {
					server.getEngine().close();
				} catch (Exception e) {
					log.warn("Venue flush on shutdown failed", e);
				}
			}
			log.info("All venues flushed");
		});

		// Desktop presence: a tray icon per venue with Open / Close / Exit —
		// best-effort; headless or unsupported desktops (and COVIA_NO_TRAY=1)
		// run without one. Tray logs why when it installs nothing.
		Tray.install(servers);

		// A predecessor only considers this JVM started after all configured venues
		// are listening and process control is armed.
		VenueRelauncher.signalMainVenueReady();
	}
	
	private static void configureLogging(ACell config) throws JoranException, IOException {
		// Suppress Logback internal messages before any logging initialisation
		//ch.qos.logback.classic.Logger rootLogger = 
		//        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		//rootLogger.setLevel(ch.qos.logback.classic.Level.OFF);
		
		
		// configure logging if specified
		ACell logFile=RT.getIn(config,"operations","log-config-file");
		if (logFile instanceof AString) {
			File logConfigFile=FileUtils.getFile(logFile.toString());
			if (logConfigFile.exists()) {
				InputStream is=new FileInputStream(logConfigFile);
				configureLoggingInternal(is);
				log.info("Logging configured from: "+logConfigFile);
				return;
			} 
		} 
		
		String resourcePath="/covia/logback-default.xml";
		configureLoggingInternal(MainVenue.class.getResourceAsStream(resourcePath));
		log.info("Logging configured from default resource: "+resourcePath);
	}

	private static void configureLoggingInternal(InputStream is) throws JoranException {
		JoranConfigurator configurator = new JoranConfigurator();
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		configurator.setContext(context);
		context.reset();
		configurator.doConfigure(is);
	}

	/**
	 * The failure and every distinct cause beneath it, outermost first —
	 * {@code Failed to install adapter asset from /skills/http.json: ... —
	 * caused by: Unexpected character at line 7}. A cause whose message the
	 * layer above already carries is not repeated.
	 */
	static String describeStartupFailure(Throwable failure) {
		StringBuilder sb = new StringBuilder();
		String previous = null;
		for (Throwable t = failure; t != null; t = t.getCause()) {
			String message = (t.getMessage() != null && !t.getMessage().isBlank())
				? t.getMessage().trim() : t.getClass().getSimpleName();
			if (previous != null && previous.contains(message)) continue;
			if (sb.length() > 0) sb.append(" — caused by: ");
			sb.append(message);
			previous = message;
			if (t.getCause() == t) break;
		}
		return sb.toString();
	}
}
