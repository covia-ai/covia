package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import convex.core.data.Maps;
import convex.core.data.Strings;

/**
 * Deterministic tests for the rate-limit configuration logic — the enable
 * defaults (on for a LAN/public bind, off for loopback), explicit overrides,
 * the numeric knob defaults, and the removed job cap's key. Pure config
 * parsing, no server or timing. Isolated because one test changes the
 * {@link Config} logger's level, which is global.
 */
@Isolated
public class ConfigRateLimitTest {

	@Test
	public void testEnabledDefaultsOnForPublicBind() {
		assertTrue(new Config(Maps.empty()).isRateLimitEnabled(),
			"no bindAddress (all interfaces) → rate limiting on by default");
	}

	@Test
	public void testEnabledDefaultsOffForLoopback() {
		assertFalse(new Config(Maps.of(Config.BIND_ADDRESS, Strings.create("127.0.0.1"))).isRateLimitEnabled());
		assertFalse(new Config(Maps.of(Config.BIND_ADDRESS, Strings.create("localhost"))).isRateLimitEnabled());
		assertFalse(new Config(Maps.of(Config.BIND_ADDRESS, Strings.create("::1"))).isRateLimitEnabled());
	}

	@Test
	public void testExplicitEnableOverridesLoopback() {
		Config c = new Config(Maps.of(
			Config.BIND_ADDRESS, Strings.create("127.0.0.1"),
			Config.RATE_LIMIT, Maps.of(Config.ENABLED, true)));
		assertTrue(c.isRateLimitEnabled(), "explicit enabled=true wins over the loopback default");
	}

	@Test
	public void testExplicitDisableOverridesPublicBind() {
		Config c = new Config(Maps.of(Config.RATE_LIMIT, Maps.of(Config.ENABLED, false)));
		assertFalse(c.isRateLimitEnabled(), "explicit enabled=false wins over the public default");
	}

	@Test
	public void testNumericDefaults() {
		Config c = new Config(Maps.empty());
		assertEquals(100.0, c.getRateLimitRps());
		assertEquals(300.0, c.getRateLimitBurst());
		assertEquals(3000L, c.getRateLimitBlockMs());
	}

	@Test
	public void testNumericOverrides() {
		Config c = new Config(Maps.of(Config.RATE_LIMIT, Maps.of(
			Strings.create("rps"), 5L,
			Strings.create("burst"), 9L,
			Strings.create("blockMs"), 250L)));
		assertEquals(5.0, c.getRateLimitRps());
		assertEquals(9.0, c.getRateLimitBurst());
		assertEquals(250L, c.getRateLimitBlockMs());
	}

	/**
	 * A config that still sets the removed job cap's key says, once, that it is
	 * ignored. The warning is captured, never printed: the logger stops passing
	 * events to the console before it lets warnings through, and takes them
	 * back before it passes events on again.
	 */
	@Test
	public void testRemovedJobCapIsReportedAsIgnored() {
		Logger logger = (Logger) LoggerFactory.getLogger(Config.class);
		ListAppender<ILoggingEvent> captured = new ListAppender<>();
		captured.setContext(logger.getLoggerContext());
		Level level = logger.getLevel();
		boolean additive = logger.isAdditive();
		captured.start();
		logger.addAppender(captured);
		logger.setAdditive(false);
		logger.setLevel(Level.WARN);
		try {
			new Config(Maps.of(Config.RATE_LIMIT, Maps.of(Config.ENABLED, true)));
			assertEquals(0, jobCapWarnings(captured), "a config without the key says nothing of it");
			new Config(Maps.of(Config.RATE_LIMIT, Maps.of(Config.MAX_CONCURRENT_JOBS_PER_USER, 10L)));
			assertEquals(1, jobCapWarnings(captured));
		} finally {
			logger.setLevel(level);
			logger.setAdditive(additive);
			logger.detachAppender(captured);
			captured.stop();
		}
	}

	private static long jobCapWarnings(ListAppender<ILoggingEvent> captured) {
		return List.copyOf(captured.list).stream()
			.filter(e -> Config.JOB_CAP_REMOVED.equals(e.getFormattedMessage()))
			.count();
	}

	@Test
	public void testRemovedJobCapIsAcceptedUnderStrictConfig() {
		// Ignored whatever its value: NoJobCapTest shows it limits nothing
		for (Object value : new Object[] {1L, 0L, "unlimited"}) {
			assertDoesNotThrow(() -> new Config(Maps.of(
				Config.STRICT_CONFIG, true,
				Config.RATE_LIMIT, Maps.of(Config.MAX_CONCURRENT_JOBS_PER_USER, value))));
		}
	}
}
