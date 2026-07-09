package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.data.Strings;

/**
 * Deterministic tests for the rate-limit configuration logic — the enable
 * defaults (on for a LAN/public bind, off for loopback), explicit overrides,
 * and the numeric knob defaults. Pure config parsing, no server or timing.
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
		assertEquals(100, c.getMaxConcurrentJobsPerUser());
		assertEquals(3000L, c.getRateLimitBlockMs());
	}

	@Test
	public void testNumericOverrides() {
		Config c = new Config(Maps.of(Config.RATE_LIMIT, Maps.of(
			Strings.create("rps"), 5L,
			Strings.create("burst"), 9L,
			Strings.create("maxConcurrentJobsPerUser"), 3L,
			Strings.create("blockMs"), 250L)));
		assertEquals(5.0, c.getRateLimitRps());
		assertEquals(9.0, c.getRateLimitBurst());
		assertEquals(3, c.getMaxConcurrentJobsPerUser());
		assertEquals(250L, c.getRateLimitBlockMs());
	}
}
