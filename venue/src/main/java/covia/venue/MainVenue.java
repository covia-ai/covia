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
import convex.core.data.AVector;
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
			String configPath=args[0];
			Path cPath=FileUtils.getPath(configPath);
			if (!Files.exists(cPath)) {
				log.error("Config file does not exist: "+cPath);
			}
			config =(AMap<AString, ACell>) JSON.parseJSON5(FileUtils.loadFileAsString(configPath));
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
		
		AVector<AMap<AString,ACell>> venues=RT.getIn(config, Fields.VENUES);
		List<VenueServer> servers=new ArrayList<>();
		for (AMap<AString,ACell> venueConfig: venues) {
			servers.add(VenueServer.launch(venueConfig));
		}

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
}
