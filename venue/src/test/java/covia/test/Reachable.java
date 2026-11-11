package covia.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import org.junit.jupiter.api.Assumptions;

/**
 * Whether an outside service an {@link IntegrationTest} needs can be reached. Such a
 * test skips, rather than fails, when no connection to the service opens at all; once
 * connected, anything the service or our code gets wrong fails it as usual, so a skip
 * never hides a failure of our own.
 *
 * <p>Call {@link #assume} where the test is already enabled (a class's
 * {@code @BeforeAll}, or the first line of a test method), so a run without
 * {@code -Dcovia.tests.integration=true} reaches no outside host.
 */
public final class Reachable {
	/** How long a connection may take to open before the service counts as unreachable. */
	static final int CONNECT_TIMEOUT_MS = 5_000;

	private Reachable() {}

	/** Skips the calling test unless a TCP connection to {@code host:port} opens. */
	public static void assume(String host, int port) {
		Assumptions.assumeTrue(opens(host, port), () -> "cannot connect to " + host + ":" + port);
	}

	/** Whether a TCP connection to {@code host:port} opens within the timeout. */
	public static boolean opens(String host, int port) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
			return true;
		} catch (IOException e) {
			return false;
		}
	}
}
