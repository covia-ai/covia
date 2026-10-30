package covia.adapter;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The JVM's name resolver wherever the venue is on the class path
 * ({@code META-INF/services/java.net.spi.InetAddressResolverProvider}), and the
 * connect-time half of the {@link HTTPAdapter} SSRF guard.
 *
 * <p>Every lookup goes to the JDK's built-in resolver. A name the guard has
 * passed is <i>held</i>: from then on, each lookup of it — by whichever client,
 * for whichever connection — must answer only addresses the guard would pass,
 * or it fails with {@link UnknownHostException}. The address a client connects
 * to is therefore one the guard checked. A held name stays held for the life of
 * the process; allow-listing a host releases it.</p>
 *
 * <p>The JDK instantiates this provider on the JVM's first name lookup, before
 * any venue exists, so it depends on nothing but the JDK.</p>
 */
public final class GuardedResolver extends InetAddressResolverProvider {

	/** Opening of the refusal for a host that resolves to a private or internal address. */
	public static final String PRIVATE_ADDRESS = "Host resolves to a private/internal address: ";

	private static final Set<String> held = ConcurrentHashMap.newKeySet();

	/** Set when the JDK takes this provider as the JVM's resolver. */
	private static volatile boolean installed;

	@Override
	public InetAddressResolver get(Configuration configuration) {
		installed = true;
		return new Resolver(configuration.builtinResolver());
	}

	@Override
	public String name() {
		return "covia-guarded";
	}

	/** Whether the JVM resolves names through this provider, so that held names are enforced. */
	public static boolean isActive() {
		return Active.ACTIVE;
	}

	private static final class Active {
		static final boolean ACTIVE = probe();

		/** A name lookup makes the JDK settle on its resolver, if it has not already. */
		private static boolean probe() {
			try {
				InetAddress.getAllByName("localhost");
			} catch (UnknownHostException e) {
				// The resolver is settled before the lookup fails
			}
			return installed;
		}
	}

	/** Holds a name the guard has passed to the address rule for every later lookup. */
	public static void hold(String host) {
		held.add(key(host));
	}

	/** Releases a held name: for a host an operator allow-lists. */
	public static void release(String host) {
		held.remove(key(host));
	}

	public static boolean isHeld(String host) {
		return held.contains(key(host));
	}

	/** Host names are case-insensitive, and a trailing dot names the same host. */
	private static String key(String host) {
		String k = host.toLowerCase(Locale.ROOT);
		return k.endsWith(".") ? k.substring(0, k.length() - 1) : k;
	}

	/**
	 * The address rule: true for a loopback, private, shared (100.64.0.0/10),
	 * link-local, unspecified or "this network" (0.0.0.0/8) address, an IPv6
	 * unique local address (fc00::/7), and an IPv6 address that embeds an IPv4
	 * address the rule refuses (IPv4-compatible, IPv4-mapped or NAT64).
	 */
	public static boolean isBlocked(InetAddress address) {
		byte[] b = address.getAddress();
		if (b.length == 4) return isBlockedV4(b, 0);
		if (address.isLoopbackAddress() || address.isAnyLocalAddress()
				|| address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
			return true;
		}
		if ((b[0] & 0xFE) == 0xFC) return true;
		return embedsV4(b) && isBlockedV4(b, 12);
	}

	private static boolean isBlockedV4(byte[] b, int at) {
		int a = b[at] & 0xFF;
		int c = b[at + 1] & 0xFF;
		return a == 0 || a == 10 || a == 127
			|| (a == 100 && (c & 0xC0) == 64)
			|| (a == 169 && c == 254)
			|| (a == 172 && (c & 0xF0) == 16)
			|| (a == 192 && c == 168);
	}

	/** ::a.b.c.d, ::ffff:a.b.c.d and 64:ff9b::a.b.c.d carry an IPv4 address in their last four bytes. */
	private static boolean embedsV4(byte[] b) {
		boolean zeroTen = true;
		for (int i = 0; i < 10; i++) zeroTen &= b[i] == 0;
		if (zeroTen) return (b[10] == 0 && b[11] == 0) || (b[10] == (byte) 0xFF && b[11] == (byte) 0xFF);
		if (b[0] != 0x00 || b[1] != 0x64 || b[2] != (byte) 0xFF || b[3] != (byte) 0x9B) return false;
		for (int i = 4; i < 12; i++) if (b[i] != 0) return false;
		return true;
	}

	/** A resolver over {@code upstream} that refuses a held name any address the rule blocks. */
	public static final class Resolver implements InetAddressResolver {
		private final InetAddressResolver upstream;

		public Resolver(InetAddressResolver upstream) {
			this.upstream = upstream;
		}

		@Override
		public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
			Stream<InetAddress> answer = upstream.lookupByName(host, policy);
			if (!isHeld(host)) return answer;
			List<InetAddress> addresses = answer.toList();
			for (InetAddress address : addresses) {
				if (isBlocked(address)) {
					throw new UnknownHostException(PRIVATE_ADDRESS + host + " (resolved to " + address.getHostAddress() + ")");
				}
			}
			return addresses.stream();
		}

		@Override
		public String lookupByAddress(byte[] addr) throws UnknownHostException {
			return upstream.lookupByAddress(addr);
		}
	}
}
