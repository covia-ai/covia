package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.data.Strings;

/**
 * Deterministic tests for the rate-limit configuration logic — the enable
 * defaults (on for a LAN/public bind, off for loopback), explicit overrides,
 * the numeric knob defaults, and the removed job cap's key. Pure config
 * parsing, no server or timing.
 */
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

	/** A config that still sets the removed job cap's key says, once, that it is ignored. */
	@Test
	public void testRemovedJobCapIsReportedAsIgnored() {
		ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(Config.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured =
			new ch.qos.logback.core.read.ListAppender<>();
		ch.qos.logback.classic.Level level = logger.getLevel();
		boolean additive = logger.isAdditive();
		captured.start();
		logger.setLevel(ch.qos.logback.classic.Level.WARN);
		logger.setAdditive(false);
		logger.addAppender(captured);
		try {
			new Config(Maps.of(Config.RATE_LIMIT, Maps.of(Config.ENABLED, true)));
			assertEquals(0, warningsHere(captured), "a config without the key says nothing of it");
			new Config(Maps.of(Config.RATE_LIMIT, Maps.of(Config.MAX_CONCURRENT_JOBS_PER_USER, 10L)));
			assertEquals(1, warningsHere(captured));
		} finally {
			logger.detachAppender(captured);
			logger.setLevel(level);
			logger.setAdditive(additive);
			captured.stop();
		}
	}

	/** The job cap warnings this test's thread logged, apart from any other test's. */
	private static long warningsHere(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured) {
		String thread = Thread.currentThread().getName();
		return java.util.List.copyOf(captured.list).stream()
			.filter(e -> thread.equals(e.getThreadName()) && Config.JOB_CAP_REMOVED.equals(e.getFormattedMessage()))
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
