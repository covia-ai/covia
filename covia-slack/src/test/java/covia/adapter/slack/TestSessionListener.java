package covia.adapter.slack;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.slf4j.LoggerFactory;

/**
 * Binds SLF4J once, before parallel tests start Javalin and engines whose
 * first log calls would otherwise race the binding and print SLF4J's replay
 * notice. Same reason as venue's {@code covia.venue.TestSessionListener}.
 */
public final class TestSessionListener implements LauncherSessionListener {
	@Override
	public void launcherSessionOpened(LauncherSession session) {
		LoggerFactory.getLogger(TestSessionListener.class).trace("SLF4J bound before tests run");
	}
}
