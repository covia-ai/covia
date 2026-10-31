package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.prim.CVMBool;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.auth.VenueDID;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.TestEngine;

/**
 * An authenticated connection sends a credential only to a venue proven to
 * hold the DID the credential is audienced to: a did:web venue at its own
 * address ({@code https://<host>}, or plain http on loopback). A target that
 * claims any other DID gets nothing but the discovery request, unless the
 * operator turns proof off with {@code auth.requireVenueProof}.
 */
public class VenueIdentityProofTest {

	private static final String GRID_RUN = "v/ops/grid/run";

	/** Proof on, the default. */
	private static final Engine ENGINE = TestEngine.ENGINE;

	/** An operator who turns proof off. */
	private static final Engine UNPROVEN = unprovenEngine();

	@AfterAll
	static void closeUnproven() {
		UNPROVEN.close();
	}

	@Test
	void didWebClaimedForAnotherHostIsRefused() throws IOException {
		try (Peer peer = new Peer()) {
			peer.claims = "did:web:venue.example.com";
			assertRefused(ENGINE, peer, peer.url());
		}
	}

	@Test
	void didKeyClaimIsRefused() throws IOException {
		try (Peer peer = new Peer()) {
			peer.claims = "did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
			assertRefused(ENGINE, peer, peer.url());
		}
	}

	@Test
	void didWebAtItsOwnLoopbackAddressIsProven() throws IOException {
		try (Peer peer = new Peer()) {
			peer.claims = peer.did();
			assertAuthenticated(ENGINE, peer, peer.url());
		}
	}

	@Test
	void venueNamedByItsDidWebIsProven() throws IOException {
		try (Peer peer = new Peer()) {
			peer.claims = peer.did();
			assertAuthenticated(ENGINE, peer, peer.did());
			assertEquals(1, peer.discoveries.get(),
				"the connection checks once that the venue presents the DID it was opened for");
		}
	}

	@Test
	void venueNamedByItsDidWebPresentingAnotherGetsNoCredential() throws IOException {
		try (Peer peer = new Peer()) {
			peer.claims = "did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
			String refusal;
			try {
				Job job = ENGINE.jobs().invokeOperation(GRID_RUN, venueInput(peer.did()), ENGINE.venueContext());
				assertThrows(Exception.class, () -> job.awaitResult(5000));
				refusal = job.getErrorMessage();
			} catch (RuntimeException e) {
				refusal = e.getMessage();
			}
			assertTrue(refusal != null && refusal.contains(VenueDID.PRESENTS_OTHER), refusal);
			assertEquals(0, peer.calls.get(), "a venue presenting another DID receives no call");
		}
	}

	@Test
	void proofOffTakesTheClaimedDid() throws IOException {
		try (Peer peer = new Peer()) {
			peer.claims = "did:web:venue.example.com";
			assertAuthenticated(UNPROVEN, peer, peer.url());
		}
	}

	@Test
	void proofMatchesSchemeHostAndPort() {
		assertTrue(VenueDID.provenAt("https://venue.example.com", "did:web:venue.example.com"));
		assertTrue(VenueDID.provenAt("https://Venue.Example.com:443/", "did:web:venue.example.com"));
		assertTrue(VenueDID.provenAt("https://venue.example.com:8443", "did:web:venue.example.com%3A8443"));
		assertTrue(VenueDID.provenAt("http://localhost:8080", "did:web:localhost%3A8080"));
		assertFalse(VenueDID.provenAt("http://venue.example.com", "did:web:venue.example.com"));
		assertFalse(VenueDID.provenAt("https://venue.example.com:8443", "did:web:venue.example.com"));
		assertFalse(VenueDID.provenAt("https://evil.example", "did:web:venue.example.com"));
		assertFalse(VenueDID.provenAt("https://venue.example.com", "did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK"));
	}

	private static AMap<AString, ACell> venueInput(String venue) {
		return Maps.of(Fields.VENUE, venue, Fields.OPERATION, "v/test/ops/echo",
			Fields.INPUT, Maps.of("message", "hello"), "authenticateAs", "venue");
	}

	/** The venue's own authenticated call is refused as unproven, and no request after discovery reached the peer. */
	private static void assertRefused(Engine engine, Peer peer, String venue) {
		String refusal;
		try {
			Job job = engine.jobs().invokeOperation(GRID_RUN, venueInput(venue), engine.venueContext());
			assertThrows(Exception.class, () -> job.awaitResult(5000));
			refusal = job.getErrorMessage();
		} catch (RuntimeException e) {
			refusal = e.getMessage();
		}
		assertTrue(refusal != null && refusal.contains(VenueConnection.VENUE_UNPROVEN), refusal);
		assertEquals(0, peer.calls.get(), "an unproven venue receives no call");
	}

	/** The venue's own authenticated call reaches the peer carrying a credential. */
	private static void assertAuthenticated(Engine engine, Peer peer, String venue) {
		Job job = engine.jobs().invokeOperation(GRID_RUN, venueInput(venue), engine.venueContext());
		// The peer is no venue, so the call itself fails once it has arrived
		assertThrows(Exception.class, () -> job.awaitResult(5000));
		assertTrue(peer.authenticated.get() > 0, "the credential reaches the venue: " + job.getErrorMessage());
	}

	private static Engine unprovenEngine() {
		Engine engine = Engine.createTemp(Maps.of(
			Config.AUTH, Maps.of(Config.REQUIRE_VENUE_PROOF, CVMBool.FALSE)));
		Engine.addDemoAssets(engine);
		return engine;
	}

	/**
	 * A loopback listener that answers discovery with the DID it is told to
	 * claim, and counts the calls that follow and those carrying a credential.
	 */
	private static final class Peer implements AutoCloseable {
		final HttpServer server;
		final AtomicInteger discoveries = new AtomicInteger();
		final AtomicInteger calls = new AtomicInteger();
		final AtomicInteger authenticated = new AtomicInteger();
		volatile String claims;

		Peer() throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				exchange.getRequestBody().readAllBytes();
				if (exchange.getRequestURI().getPath().equals("/.well-known/did.json")) {
					discoveries.incrementAndGet();
					byte[] body = ("{\"id\":\"" + claims + "\"}").getBytes(StandardCharsets.UTF_8);
					exchange.sendResponseHeaders(200, body.length);
					try (var out = exchange.getResponseBody()) { out.write(body); }
					return;
				}
				calls.incrementAndGet();
				if (exchange.getRequestHeaders().getFirst("Authorization") != null) authenticated.incrementAndGet();
				exchange.sendResponseHeaders(404, -1);
				exchange.close();
			});
			server.start();
		}

		String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

		/** The DID this listener can prove: did:web at its own loopback address. */
		String did() { return "did:web:127.0.0.1%3A" + server.getAddress().getPort(); }

		@Override public void close() { server.stop(0); }
	}
}
