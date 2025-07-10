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
import convex.core.data.AString;
import convex.core.lang.RT;
import convex.core.util.FileUtils;
import covia.venue.server.VenueServer;

public class MainVenue {

	public static Logger log=LoggerFactory.getLogger(MainVenue.class);;

	public static void main(String[] args) throws Exception {
		configureLogging(null);
		
		VenueServer server=VenueServer.create(null);
		server.start();
		
		Venue.addDemoAssets(server.getVenue());
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
