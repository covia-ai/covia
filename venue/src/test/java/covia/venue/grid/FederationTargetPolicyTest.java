package covia.venue.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.sun.net.httpserver.HttpServer;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.adapter.HTTPAdapter;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Every venue this venue dials on a caller's behalf passes the outbound guard
 * ({@link Engine#connectVenue}): grid operations, target discovery for
 * {@code authenticateAs=caller}, remote agents, orchestration steps and the
 * publishers of did:web asset references. A loopback target is refused before
 * any request reaches it unless the operator allows its host, and the block
 * list wins over the allow list.
 */
public class FederationTargetPolicyTest {

	private static final String GRID_RUN = "v/ops/grid/run";

	/** The guard's defaults: no host allowed or blocked. */
	private static final Engine ENGINE = TestEngine.ENGINE;

	/** An operator who allows both loopback names and blocks one of them. */
	private static final Engine LISTED = listedEngine();

	private RequestContext ctx;

	@BeforeEach
	void setUp(TestInfo info) {
		ctx = RequestContext.of(TestEngine.uniqueDID(info));
	}

	@AfterAll
	static void closeListed() {
		LISTED.close();
	}

	@Test
	void gridTargetOnLoopbackIsRefused() throws IOException {
		try (Peer peer = new Peer()) {
			assertRefused(peer, HTTPAdapter.PRIVATE_ADDRESS, ENGINE, GRID_RUN, gridInput(peer.url("127.0.0.1")));
		}
	}

	@Test
	void gridTargetNamedByDidWebIsCheckedAtItsAddress() throws IOException {
		try (Peer peer = new Peer()) {
			String did = "did:web:127.0.0.1%3A" + peer.port();
			assertRefused(peer, HTTPAdapter.PRIVATE_ADDRESS, ENGINE, GRID_RUN, gridInput(did));
		}
	}

	@Test
	void callerAuthenticationDiscoversOnlyAnAllowedTarget() throws IOException {
		try (Peer peer = new Peer()) {
			ACell input = gridInput(peer.url("127.0.0.1")).assoc(Strings.create("authenticateAs"), Strings.create("caller"));
			assertRefused(peer, HTTPAdapter.PRIVATE_ADDRESS, ENGINE, GRID_RUN, input);
		}
	}

	@Test
	void remoteAgentOnLoopbackIsRefused() throws IOException {
		try (Peer peer = new Peer()) {
			ACell input = Maps.of(Fields.VENUE, peer.url("127.0.0.1"),
				Fields.AGENT_ID, "did:test:owner/g/worker", Fields.INPUT, Maps.of("task", "work"));
			assertRefused(peer, HTTPAdapter.PRIVATE_ADDRESS, ENGINE, "v/ops/remoteagent/request", input);
		}
	}

	@Test
	void orchestrationStepOnLoopbackIsRefused() throws IOException {
		try (Peer peer = new Peer()) {
			String orchestration = """
				{
					"name": "Step on a loopback venue",
					"operation": {
						"adapter": "orchestrator",
						"steps": [{
							"venue": "%s",
							"op": "v/test/ops/echo",
							"input": ["const", {"message": "hello"}]
						}],
						"result": [0]
					}
				}
				""".formatted(peer.url("127.0.0.1"));
			String op = ENGINE.storeAsset(Strings.create(orchestration), null).toHexString();
			assertRefused(peer, HTTPAdapter.PRIVATE_ADDRESS, ENGINE, op, Maps.empty());
		}
	}

	@Test
	void didWebAssetPublisherOnLoopbackIsRefused() throws IOException {
		try (Peer peer = new Peer()) {
			String ref = "did:web:127.0.0.1%3A" + peer.port() + "/a/" + "0123456789abcdef".repeat(4);
			assertRefused(peer, HTTPAdapter.PRIVATE_ADDRESS, ENGINE, ref, Maps.empty());
		}
	}

	@Test
	void allowedLoopbackTargetIsReached() throws IOException {
		try (Peer peer = new Peer()) {
			Job job = LISTED.jobs().invokeOperation(GRID_RUN, gridInput(peer.url("127.0.0.1")), ctx);
			// The peer is no venue, so the call itself fails once it has arrived
			assertThrows(Exception.class, () -> job.awaitResult(5000));
			assertTrue(peer.requests.get() > 0, "an allowed host is dialled");
		}
	}

	@Test
	void blockedTargetIsRefusedThoughAllowed() throws IOException {
		try (Peer peer = new Peer()) {
			assertRefused(peer, HTTPAdapter.BLOCKED_HOST, LISTED, GRID_RUN, gridInput(peer.url("localhost")));
		}
	}

	private static AMap<AString, ACell> gridInput(String venue) {
		return Maps.of(Fields.VENUE, venue, Fields.OPERATION, "v/test/ops/echo",
			Fields.INPUT, Maps.of("message", "hello"));
	}

	/** The call is refused with the given rule, at submission or as its job's failure, and nothing reached the peer. */
	private void assertRefused(Peer peer, String rule, Engine engine, String op, ACell input) {
		String refusal;
		try {
			Job job = engine.jobs().invokeOperation(op, input, ctx);
			assertThrows(Exception.class, () -> job.awaitResult(5000));
			refusal = job.getErrorMessage();
		} catch (RuntimeException e) {
			refusal = e.getMessage();
		}
		assertTrue(refusal != null && refusal.contains(rule), refusal);
		assertEquals(0, peer.requests.get(), "a refused target is never dialled");
	}

	private static Engine listedEngine() {
		Engine engine = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.ADAPTERS, Maps.of("http", Maps.of(
				"allowedHosts", Vectors.of("127.0.0.1", "localhost"),
				"blockedHosts", Vectors.of("localhost")))));
		Engine.addDemoAssets(engine);
		return engine;
	}

	/** A loopback listener that counts what reaches it and answers nothing a venue would. */
	private static final class Peer implements AutoCloseable {
		final HttpServer server;
		final AtomicInteger requests = new AtomicInteger();

		Peer() throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				requests.incrementAndGet();
				exchange.getRequestBody().readAllBytes();
				exchange.sendResponseHeaders(404, -1);
				exchange.close();
			});
			server.start();
		}

		int port() { return server.getAddress().getPort(); }

		String url(String host) { return "http://" + host + ":" + port(); }

		@Override public void close() { server.stop(0); }
	}
}
