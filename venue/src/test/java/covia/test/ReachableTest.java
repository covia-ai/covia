package covia.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

class ReachableTest {
	@Test void opensOnlyWhileSomethingListens() throws Exception {
		String host = InetAddress.getLoopbackAddress().getHostAddress();
		int port;
		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			port = server.getLocalPort();
			assertTrue(Reachable.opens(host, port));
		}
		assertFalse(Reachable.opens(host, port));
	}
}
