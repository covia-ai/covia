package covia.venue;

import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.lang.RT;
import convex.core.util.FileUtils;
import convex.core.util.Utils;
import covia.venue.server.VenueServer;

public class MainVenue {

	public static final Logger log=LoggerFactory.getLogger(MainVenue.class);

	public static void main(String[] args) throws Exception {
		configureLogging(null);
		
		VenueServer server=VenueServer.create(null);
		server.start();
		
		Venue.addDemoAssets(server.getVenue());
	}
	
	private static void configureLogging(ACell config) throws JoranException, IOException {
		JoranConfigurator configurator = new JoranConfigurator();
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		configurator.setContext(context);
		context.reset();
		
		// configure logging if specified
		ACell logFile=RT.getIn(config,"operations","log-config-file");
		if (logFile instanceof AString) {
			File logConfigFile=FileUtils.getFile(logFile.toString());
			if (logConfigFile.exists()) {
				configurator.doConfigure(logConfigFile);
				log.info("Logging configured from: ");
				return;
			} else {
				log.info("Logging config file does not exist at "+logConfigFile+", using logback defaults");
			}
		} else {
			log.info("No log config file specified, using logback defaults");
		}
		
		String resourcePath="/covia/logback-default.xml";
		configurator.doConfigure(Utils.getResourceAsStream(resourcePath));
		log.info("Logging configured from default resource: "+resourcePath);
	}
}
