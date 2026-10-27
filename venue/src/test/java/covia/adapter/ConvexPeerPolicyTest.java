package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.Constants;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * A Convex call goes to the operator's peer. Naming another peer asks the
 * venue to relay: off by default, and when the operator turns it on the peer
 * passes the venue's outbound address guard. Nothing here leaves the machine:
 * the peers that are dialled are local sockets that accept and never answer.
 */
class ConvexPeerPolicyTest {

	private static final String CONNECT_FAILURE = "Failed to connect to Convex peer";
	private static final String QUERY = "v/ops/convex/query";

	/** This venue's own Convex peer on {@link #relayOff}. */
	private static ServerSocket ownPeer;
	/** An allow-listed peer {@link #relayOn} may relay to. */
	private static ServerSocket allowedPeer;
	/** Relaying off, as by default; its peer is {@link #ownPeer}. */
	private static Engine relayOff;
	/** Relaying on; localhost is allow-listed and blocked.invalid is blocked. */
	private static Engine relayOn;

	private RequestContext ctx;

	@BeforeAll
	static void setUp() throws IOException {
		// Bound where "localhost" resolves first, which is where the client dials
		InetAddress localhost = InetAddress.getByName("localhost");
		ownPeer = listen(localhost);
		allowedPeer = listen(localhost);
		relayOff = engine(Maps.of(
			"convex", Maps.of("peer", "localhost:" + ownPeer.getLocalPort())));
		relayOn = engine(Maps.of(
			"convex", Maps.of("anyPeer", CVMBool.TRUE),
			"http", Maps.of(
				"allowedHosts", Vectors.of("localhost"),
				"blockedHosts", Vectors.of("blocked.invalid"))));
	}

	private static Engine engine(AMap<AString, ACell> adapters) {
		Engine engine = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.ADAPTERS, adapters));
		Engine.addDemoAssets(engine);
		return engine;
	}

	private static ServerSocket listen(InetAddress address) throws IOException {
		ServerSocket socket = new ServerSocket(0, 50, address);
		// A backstop only: a passing test's connection is already waiting
		socket.setSoTimeout(10_000);
		return socket;
	}

	@AfterAll
	static void tearDown() throws IOException {
		if (relayOff != null) relayOff.close();
		if (relayOn != null) relayOn.close();
		if (ownPeer != null) ownPeer.close();
		if (allowedPeer != null) allowedPeer.close();
	}

	@BeforeEach
	void setup(TestInfo info) {
		ctx = RequestContext.of(TestEngine.uniqueDID(info));
	}

	private static ACell query(String peer) {
		return (peer == null)
			? Maps.of(Fields.SOURCE, "(+ 1 2)")
			: Maps.of(Fields.SOURCE, "(+ 1 2)", Fields.PEER, peer);
	}

	/** The refusal a call ends with; it never got as far as dialling. */
	private static String refusal(Job job) {
		assertThrows(Exception.class, () -> job.awaitResult(5000));
		String message = job.getErrorMessage();
		assertFalse(message.contains(CONNECT_FAILURE), "refused before any dial: " + message);
		return message;
	}

	/** Waits for the call to dial {@code peer}, then lets it go. */
	private static void assertDialled(ServerSocket peer, Job job) throws IOException {
		try (Socket dialled = peer.accept()) {
			assertTrue(dialled.isConnected());
		} finally {
			job.cancel();
		}
	}

	@Test
	void anotherPeerIsRefusedWhileRelayingIsOff() {
		for (String peer : new String[] {
				"127.0.0.1:1", "tcp://10.0.0.5:18888", "tls://peer.example:18888",
				// The shipped default is not this venue's peer, so it is another peer here
				ConvexAdapter.DEFAULT_PEER}) {
			String message = refusal(relayOff.jobs().invokeOperation(QUERY, query(peer), ctx));
			assertTrue(message.contains(ConvexAdapter.OTHER_PEER), peer + ": " + message);
		}
	}

	@Test
	void anotherPeerInOperationMetadataIsRefused() {
		// Operations are user-creatable assets: their metadata is the caller's choice too
		Job job = relayOff.jobs().invokeOperation(Maps.of(
			Fields.NAME, Strings.create("Internal query"),
			Fields.OPERATION, Maps.of(
				Fields.ADAPTER, Strings.create("convex:query"),
				Fields.PEER, Strings.create("10.0.0.5:18888"))), query(null), ctx);
		assertTrue(refusal(job).contains(ConvexAdapter.OTHER_PEER), job.getErrorMessage());
	}

	@Test
	void ownPeerIsDialledHoweverItIsNamed() throws IOException {
		int port = ownPeer.getLocalPort();
		for (String peer : new String[] {null, "localhost:" + port, "LOCALHOST:" + port, "tcp://localhost:" + port}) {
			assertDialled(ownPeer, relayOff.jobs().invokeOperation(QUERY, query(peer), ctx));
		}
	}

	@Test
	void relayedPeerPassesTheAddressGuard() {
		for (String peer : new String[] {"127.0.0.1:1", "[::1]:1", "10.0.0.5:18888", "tls://192.168.1.1:443"}) {
			String message = refusal(relayOn.jobs().invokeOperation(QUERY, query(peer), ctx));
			assertTrue(message.contains(HTTPAdapter.PRIVATE_ADDRESS), peer + ": " + message);
		}
		String blocked = refusal(relayOn.jobs().invokeOperation(QUERY, query("blocked.invalid:18888"), ctx));
		assertTrue(blocked.contains(HTTPAdapter.BLOCKED_HOST), blocked);
	}

	@Test
	void allowListedPeerIsRelayed() throws IOException {
		assertDialled(allowedPeer,
			relayOn.jobs().invokeOperation(QUERY, query("localhost:" + allowedPeer.getLocalPort()), ctx));
	}

	@Test
	void onlyPeerEndpointsAreDialled() {
		for (String peer : new String[] {"http://example.com:80", "file:///etc/passwd", "tcp://example.com:1/path", "tcp://user@example.com:1"}) {
			String message = refusal(relayOn.jobs().invokeOperation(QUERY, query(peer), ctx));
			assertTrue(message.contains(ConvexAdapter.NOT_A_PEER), peer + ": " + message);
		}
	}

	@Test
	void peerSpellingsCompareAsOnePeer() {
		URI configured = ConvexAdapter.peerEndpoint(Strings.create("peer.example"), "peer");
		for (String spelling : new String[] {"peer.example:" + Constants.DEFAULT_PEER_PORT,
				"tcp://peer.example:" + Constants.DEFAULT_PEER_PORT, "TCP://Peer.Example"}) {
			assertEquals(configured, ConvexAdapter.peerEndpoint(Strings.create(spelling), "peer"), spelling);
		}
		// A TLS peer authenticates itself; the same address over plain TCP is another peer
		assertNotEquals(configured, ConvexAdapter.peerEndpoint(Strings.create("tls://peer.example"), "peer"));
		assertNotEquals(configured, ConvexAdapter.peerEndpoint(Strings.create("peer.example:1"), "peer"));
	}

	@Test
	void peerSettingsAreValidated() {
		ConvexAdapter adapter = new ConvexAdapter();
		assertThrows(IllegalArgumentException.class,
			() -> adapter.configure(Maps.of("peers", "localhost:1"), true), "unknown setting under strict config");
		assertThrows(IllegalArgumentException.class,
			() -> adapter.configure(Maps.of("anyPeer", "yes"), false), "anyPeer is a boolean");
		IllegalArgumentException badPeer = assertThrows(IllegalArgumentException.class,
			() -> adapter.configure(Maps.of("peer", "https://peer.example"), false));
		assertTrue(badPeer.getMessage().contains(ConvexAdapter.NOT_A_PEER), badPeer.getMessage());
		assertTrue(adapter.configure(Maps.of("peer", "peer.example:18888", "anyPeer", CVMBool.FALSE), true));
	}
}
