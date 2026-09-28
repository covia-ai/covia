package covia.venue;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The reverse proxies whose {@code X-Forwarded-For} this venue believes, and
 * the client address derived from them ({@code trustedProxies}, covia#539).
 *
 * <p>A venue behind Caddy or a load balancer sees every connection arrive from
 * the proxy, so anything keyed on the caller's address — the request rate
 * limiter, the authentication throttle — would put every client in one bucket.
 * With the proxy listed here, the client is the rightmost address in
 * {@code X-Forwarded-For} that is not itself a trusted proxy: the proxy
 * appended that hop, so it cannot be forged by the client, while anything
 * further left is client-supplied text and is ignored. With no trusted
 * proxies (the default) the header is ignored entirely and the connection's
 * own address is the client.</p>
 *
 * <p>Entries are IP literals ({@code 203.0.113.5}, {@code 2001:db8::1}), CIDR
 * ranges ({@code 10.0.0.0/8}, {@code fd00::/8}) or the sentinel
 * {@code "loopback"}. Hostnames are refused: trust must not depend on DNS.</p>
 */
public final class TrustedProxies {

	private static final Logger log = LoggerFactory.getLogger(TrustedProxies.class);

	/** Whether the "forwarded address but no trusted proxies" warning has been logged (once per instance). */
	private final AtomicBoolean warnedUnconfigured = new AtomicBoolean();

	/** Trust nothing: the connection address is always the client. */
	public static final TrustedProxies NONE = new TrustedProxies(List.of(), false);

	public static final String LOOPBACK = "loopback";

	private record Range(byte[] address, int prefixBits) {
		boolean contains(byte[] ip) {
			if (ip.length != address.length) return false;
			int fullBytes = prefixBits / 8;
			for (int i = 0; i < fullBytes; i++) {
				if (ip[i] != address[i]) return false;
			}
			int rest = prefixBits % 8;
			if (rest == 0) return true;
			int mask = (0xFF << (8 - rest)) & 0xFF;
			return (ip[fullBytes] & mask) == (address[fullBytes] & mask);
		}
	}

	private final List<Range> ranges;
	private final boolean loopback;
	private final List<String> entries;

	private TrustedProxies(List<Range> ranges, boolean loopback) {
		this(ranges, loopback, List.of());
	}

	private TrustedProxies(List<Range> ranges, boolean loopback, List<String> entries) {
		this.ranges = List.copyOf(ranges);
		this.loopback = loopback;
		this.entries = List.copyOf(entries);
	}

	/**
	 * Parses the configured entries.
	 *
	 * @throws IllegalArgumentException naming the offending entry
	 */
	public static TrustedProxies parse(List<String> entries) {
		if (entries == null || entries.isEmpty()) return NONE;
		List<Range> ranges = new ArrayList<>();
		boolean loopback = false;
		for (String raw : entries) {
			String entry = (raw == null) ? "" : raw.trim();
			if (entry.isEmpty()) throw new IllegalArgumentException("trustedProxies: empty entry");
			if (LOOPBACK.equalsIgnoreCase(entry)) {
				loopback = true;
				continue;
			}
			String host = entry;
			int prefix = -1;
			int slash = entry.indexOf('/');
			if (slash >= 0) {
				host = entry.substring(0, slash);
				try {
					prefix = Integer.parseInt(entry.substring(slash + 1));
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("trustedProxies: bad prefix length in " + entry);
				}
			}
			byte[] address = literal(host);
			if (address == null) {
				throw new IllegalArgumentException("trustedProxies: not an IP literal or CIDR range: " + entry
					+ " (hostnames are not accepted — trust must not depend on DNS)");
			}
			int bits = address.length * 8;
			if (prefix < 0) prefix = bits;
			if (prefix > bits) {
				throw new IllegalArgumentException("trustedProxies: prefix /" + prefix + " exceeds "
					+ bits + " bits in " + entry);
			}
			ranges.add(new Range(address, prefix));
		}
		return new TrustedProxies(ranges, loopback, entries);
	}

	/** Whether this address is one of the trusted proxies. */
	public boolean trusts(String ip) {
		if (ip == null) return false;
		byte[] address = literal(ip.trim());
		if (address == null) return false;
		if (loopback && isLoopback(address)) return true;
		for (Range r : ranges) {
			if (r.contains(address)) return true;
		}
		return false;
	}

	/**
	 * The client address for a request: {@code remote} unless it is a trusted
	 * proxy, in which case the rightmost hop of {@code forwardedFor} that is not
	 * a trusted proxy. When every hop is trusted (or the header is absent) the
	 * connection address stands — never a client-supplied value.
	 */
	public String clientIp(String remote, String forwardedFor) {
		if (remote == null) remote = "";
		if (forwardedFor == null || forwardedFor.isBlank()) return remote;
		if (isEmpty()) {
			// A forwarded address on a venue that trusts no proxy is the
			// signature of a proxy nobody told the venue about: say so once.
			if (warnedUnconfigured.compareAndSet(false, true)) {
				log.warn("A request from {} carries X-Forwarded-For but trustedProxies is unset, so that address "
					+ "counts as the client: every caller behind it shares one rate-limit bucket and one "
					+ "authentication budget. If {} is your reverse proxy, list it in trustedProxies "
					+ "(venue/docs/CONFIG.md, Trusted proxies).", remote, remote);
			}
			return remote;
		}
		if (!trusts(remote)) return remote;
		String[] hops = forwardedFor.split(",");
		for (int i = hops.length - 1; i >= 0; i--) {
			String hop = hops[i].trim();
			if (hop.isEmpty()) continue;
			if (!trusts(hop)) return hop;
		}
		return remote;
	}

	/** Whether any proxies are trusted at all. */
	public boolean isEmpty() {
		return !loopback && ranges.isEmpty();
	}

	/** The configured entries, for status output. */
	public List<String> entries() {
		return entries;
	}

	/** The bytes of an IP literal, or null for anything that is not one (never a DNS lookup). */
	private static byte[] literal(String host) {
		if (host == null || host.isEmpty()) return null;
		String h = host;
		if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
		// Only digits, dots, hex and colons can be a literal; anything else would
		// make InetAddress.getByName resolve a name.
		if (!h.matches("[0-9A-Fa-f.:]+")) return null;
		if (h.indexOf(':') < 0 && !h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return null;
		try {
			return InetAddress.getByName(h).getAddress();
		} catch (Exception e) {
			return null;
		}
	}

	private static boolean isLoopback(byte[] address) {
		try {
			return InetAddress.getByAddress(address).isLoopbackAddress();
		} catch (Exception e) {
			return false;
		}
	}

	@Override
	public String toString() {
		return isEmpty() ? "none" : String.join(", ", entries);
	}
}
