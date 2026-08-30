package covia.venue;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.slf4j.LoggerFactory;

/**
 * Runs once on the launcher thread before any test executes (registered in
 * {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}).
 *
 * <p>Initialises SLF4J up front. Test classes run in parallel; when the first
 * logging call happens on one thread while another thread is still binding the
 * logger factory, SLF4J parks the call on a substitute logger and later prints
 * "A number (1) of logging calls during the initialization phase have been
 * intercepted and are now being replayed" — timing-dependent noise in a passing
 * build. Binding here, single-threaded, means no test ever logs during
 * initialisation.</p>
 */
public final class TestSessionListener implements LauncherSessionListener {

	@Override
	public void launcherSessionOpened(LauncherSession session) {
		LoggerFactory.getLogger(TestSessionListener.class).trace("SLF4J bound before tests run");
	}
}
