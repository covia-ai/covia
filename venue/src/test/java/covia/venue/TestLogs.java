package covia.venue;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

/**
 * Silences one class's logger for the duration of a test body.
 *
 * <p>For tests that deliberately provoke something the product must log at
 * ERROR in production — an unhandled route exception, a failed store
 * collection — and would otherwise print a failure-shaped line and stack trace
 * into a passing build. The level change is global, so a test using this must
 * run alone: annotate the class {@code @Isolated}.</p>
 */
public final class TestLogs {

	@FunctionalInterface
	public interface Body {
		void run() throws Exception;
	}

	private TestLogs() {}

	public static void quiet(Class<?> loggerClass, Body body) throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
		Level before = logger.getLevel();
		logger.setLevel(Level.OFF);
		try {
			body.run();
		} finally {
			logger.setLevel(before);
		}
	}
}
