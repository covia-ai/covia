package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Which address a request is charged to (covia#539): the connection's, unless
 * it is a trusted proxy, in which case the rightmost hop of X-Forwarded-For
 * that is not one of ours — the one the proxy appended, which a client cannot
 * forge.
 */
public class TrustedProxiesTest {

	@Test
	public void noProxiesMeansTheConnectionIsTheClient() {
		TrustedProxies none = TrustedProxies.parse(List.of());
		assertTrue(none.isEmpty());
		assertFalse(none.trusts("127.0.0.1"));
		// The header is client-supplied text until a proxy we trust vouches for it.
		assertEquals("203.0.113.9", none.clientIp("203.0.113.9", "198.51.100.1"));
	}

	@Test
	public void parsesLiteralsRangesAndLoopback() {
		TrustedProxies t = TrustedProxies.parse(List.of(
			"loopback", "10.0.0.0/8", "203.0.113.5", "fd00::/8", "2001:db8::1"));
		assertTrue(t.trusts("127.0.0.1"));
		assertTrue(t.trusts("::1"));
		assertTrue(t.trusts("10.42.7.1"));
		assertFalse(t.trusts("11.0.0.1"));
		assertTrue(t.trusts("203.0.113.5"));
		assertFalse(t.trusts("203.0.113.6"));
		assertTrue(t.trusts("fd12::3"));
		assertFalse(t.trusts("fe80::1"));
		assertTrue(t.trusts("2001:db8::1"));
		assertFalse(t.trusts("not-an-ip"));
		assertFalse(t.trusts(null));
	}

	@Test
	public void clientIsTheRightmostUntrustedHop() {
		TrustedProxies t = TrustedProxies.parse(List.of("loopback", "10.0.0.0/8"));
		// Caddy on the host appends the client; whatever the client put in the
		// header itself sits further left and is ignored.
		assertEquals("203.0.113.9", t.clientIp("127.0.0.1", "203.0.113.9"));
		assertEquals("203.0.113.9", t.clientIp("127.0.0.1", "198.51.100.77, 203.0.113.9"));
		// Two of our proxies in the chain: skip both.
		assertEquals("203.0.113.9", t.clientIp("127.0.0.1", "203.0.113.9, 10.1.2.3"));
		// A connection that is not a trusted proxy: its header is ignored outright.
		assertEquals("198.51.100.1", t.clientIp("198.51.100.1", "203.0.113.9"));
		// Every hop trusted, or no usable header: the connection stands — never a
		// client-supplied value.
		assertEquals("127.0.0.1", t.clientIp("127.0.0.1", "10.0.0.5"));
		assertEquals("127.0.0.1", t.clientIp("127.0.0.1", null));
		assertEquals("127.0.0.1", t.clientIp("127.0.0.1", " , "));
	}

	@Test
	public void refusesHostnamesAndBadRanges() {
		assertThrows(IllegalArgumentException.class, () -> TrustedProxies.parse(List.of("proxy.internal")),
			"trust must not depend on DNS");
		assertThrows(IllegalArgumentException.class, () -> TrustedProxies.parse(List.of("10.0.0.0/33")));
		assertThrows(IllegalArgumentException.class, () -> TrustedProxies.parse(List.of("10.0.0.0/x")));
		assertThrows(IllegalArgumentException.class, () -> TrustedProxies.parse(List.of("")));
	}
}
