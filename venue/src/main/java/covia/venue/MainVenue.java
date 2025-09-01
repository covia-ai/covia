package covia.venue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

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
import convex.core.util.JSONUtils;
import covia.api.Fields;
import covia.venue.server.VenueServer;

public class MainVenue {

	public static Logger log=LoggerFactory.getLogger(MainVenue.class);;

	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception {
		configureLogging(null);
		
		AMap<AString,ACell> config=null;
		if (args.length>0) try {
			config =(AMap<AString, ACell>) JSONUtils.parseJSON5(FileUtils.loadFileAsString(args[0]));
		} catch (Exception ex) {
			log.warn("Error loading config, defaulting to test setup",ex);
		}
		
		if (config==null) {
			config = Maps.of(
					Fields.VENUES,Vectors.of(
							Maps.of(
									Fields.NAME,"Unconfigured Venue",
									Fields.HOSTNAME,"localhost")));
		}
		
		AVector<AMap<AString,ACell>> venues=RT.getIn(config, Fields.VENUES);
		for (AMap<AString,ACell> venueConfig: venues) {
			VenueServer server=VenueServer.launch(venueConfig);
		}
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
				log.info("Logging configured from: ");
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
