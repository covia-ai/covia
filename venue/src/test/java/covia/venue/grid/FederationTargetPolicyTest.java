package covia.venue.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.sun.net.httpserver.HttpServer;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.HTTPAdapter;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.RemoteFetchException;
import covia.grid.Job;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.TwoVenueTestServer;

/**
 * Every venue this venue dials on a caller's behalf passes the outbound guard
 * ({@link Engine#connectVenue(RequestContext, String, covia.grid.auth.VenueAuth)}):
 * grid operations, target discovery for {@code authenticateAs=caller}, remote
 * agents, orchestration steps and the publishers of did:web asset references.
 * A loopback target is refused before any request reaches it unless the
 * operator allows its host, and the block list wins over the allow list. The
 * venue itself, and a holder of a venue-issued {@code outbound/any} grant,
 * dial anywhere; an agent of the venue's whose caps grant no venue authority
 * is checked.
 */
public class FederationTargetPolicyTest {

	private static final String GRID_RUN = "v/ops/grid/run";

	/** The guard's defaults: no host allowed or blocked. */
	private static final Engine ENGINE = TestEngine.ENGINE;

	/** An operator who allows both loopback names and blocks one of them. */
	private static Engine LISTED;

	private RequestContext ctx;

	@BeforeAll
	static void startListed() {
		LISTED = listedEngine();
	}

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
	void aLoopbackVenueIsRefusedToAUserOnEveryGridRoute() {
		String venue = TwoVenueTestServer.BASE_URL_B;
		for (String op : List.of(GRID_RUN, "v/ops/grid/invoke")) {
			String refused = refusal(ENGINE, op, gridInput(venue));
			assertTrue(refused != null && refused.contains(HTTPAdapter.PRIVATE_ADDRESS), op + ": " + refused);
		}
		for (String op : List.of("v/ops/grid/job-status", "v/ops/grid/job-result")) {
			String refused = refusal(ENGINE, op,
				Maps.of(Fields.VENUE, venue, Fields.ID, "00112233445566778899aabbccddeeff"));
			assertTrue(refused != null && refused.contains(HTTPAdapter.PRIVATE_ADDRESS), op + ": " + refused);
		}

		// The same venue answers a user whose operator allows its host
		Job job = TwoVenueTestServer.ENGINE_A.jobs().invokeOperation(GRID_RUN, gridInput(venue), ctx);
		assertEquals(Strings.create("hello"), RT.getIn(job.awaitResult(5000), "message"));
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
	void withoutTheGuardNoPublisherIsDialled() {
		Engine bare = Engine.createTemp(null);   // no adapters, so no http adapter to guard with
		try {
			assertThrows(RemoteFetchException.class, () -> bare.fetchRemoteAsset(ctx, "did:web:venue.example.com",
				Hash.parse("0123456789abcdef".repeat(4))));
		} finally {
			bare.close();
		}
	}

	@Test
	void venueItselfDialsAnywhere() throws IOException {
		try (Peer peer = new Peer()) {
			assertReached(peer, ENGINE, ENGINE.venueContext());
		}
	}

	@Test
	void outboundGrantHolderDialsAnywhere() throws IOException {
		AKeyPair holder = AKeyPair.generate();
		UCAN grant = UCAN.create(ENGINE.getKeyPair(), holder.getAccountKey(),
			System.currentTimeMillis() / 1000 + 3600,
			Vectors.of(Capability.create(Strings.create(ENGINE.getDIDString() + "/" + Engine.OUTBOUND),
				Abilities.OUTBOUND_ANY)),
			Vectors.empty());
		RequestContext granted = RequestContext.of(UCAN.toDIDKey(holder.getAccountKey()))
			.withProofs(Vectors.of(grant.toMap()));
		try (Peer peer = new Peer()) {
			assertReached(peer, ENGINE, granted);
		}
	}

	@Test
	void venueAgentIsChecked() throws IOException {
		// An agent of the venue's whose caps grant no venue authority.
		ctx = ENGINE.venueContext().withAgentId(Strings.create("helper"))
			.withCaps(Vectors.of(Maps.of(Capability.CAN, Strings.create("invoke"))));
		try (Peer peer = new Peer()) {
			assertRefused(peer, HTTPAdapter.PRIVATE_ADDRESS, ENGINE, GRID_RUN, gridInput(peer.url("127.0.0.1")));
		}
	}

	@Test
	void allowedLoopbackTargetIsReached() throws IOException {
		try (Peer peer = new Peer()) {
			assertReached(peer, LISTED, ctx);
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

	/** A grid call to the peer on 127.0.0.1 from {@code caller} arrives there. */
	private static void assertReached(Peer peer, Engine engine, RequestContext caller) {
		Job job = engine.jobs().invokeOperation(GRID_RUN, gridInput(peer.url("127.0.0.1")), caller);
		// The peer is no venue, so the call itself fails once it has arrived
		assertThrows(Exception.class, () -> job.awaitResult(5000));
		assertTrue(peer.requests.get() > 0, "the target is dialled");
	}

	/** The call is refused with the given rule, at submission or as its job's failure, and nothing reached the peer. */
	private void assertRefused(Peer peer, String rule, Engine engine, String op, ACell input) {
		String refusal = refusal(engine, op, input);
		assertTrue(refusal != null && refusal.contains(rule), refusal);
		assertEquals(0, peer.requests.get(), "a refused target is never dialled");
	}

	/** Why the caller's call fails, at submission or as its job's failure. */
	private String refusal(Engine engine, String op, ACell input) {
		try {
			Job job = engine.jobs().invokeOperation(op, input, ctx);
			assertThrows(Exception.class, () -> job.awaitResult(5000));
			return job.getErrorMessage();
		} catch (RuntimeException e) {
			return e.getMessage();
		}
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
