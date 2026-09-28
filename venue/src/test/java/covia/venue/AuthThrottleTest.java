package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import covia.venue.auth.VenueAuthenticator;
import covia.venue.auth.VenueDIDVerifier;
import covia.venue.server.AuthThrottle;
import covia.venue.server.VenueServer;

/**
 * Bad credentials are not free to send (covia#539): an address has a budget
 * of rejected credentials, after which its credentials are refused before any
 * verification, and one authentication in flight at a time, so it cannot fan
 * out outbound DID resolution. The client address comes through the trusted
 * proxy's X-Forwarded-For, which is how the tests speak from several addresses
 * over one loopback connection.
 */
public class AuthThrottleTest {

	private static final long FAILURE_BURST = 3;

	private static VenueServer server;
	private static AString venueDID;
	private static final AtomicInteger verifications = new AtomicInteger();
	private static final AtomicInteger inFlight = new AtomicInteger();
	private static final AtomicInteger maxInFlight = new AtomicInteger();

	@BeforeAll
	static void launch() {
		server = VenueServer.launch(Maps.of(
			Config.PORT, 0,
			Config.BIND_ADDRESS, Strings.create("127.0.0.1"),
			Config.TRUSTED_PROXIES, Vectors.of(Strings.create(TrustedProxies.LOOPBACK)),
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, true)),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.RATE_LIMIT, Maps.of(
				Config.ENABLED, true,
				// Request rate out of the way: only the authentication throttle can trip here.
				Strings.create("rps"), 100000L,
				Strings.create("burst"), 100000L,
				Strings.create("authFailuresPerMinute"), 60L,
				Strings.create("authFailureBurst"), FAILURE_BURST,
				Strings.create("authConcurrency"), 1L,
				Strings.create("blockMs"), 5000L)));
		venueDID = server.getEngine().getDIDString();
		VenueDIDVerifier verifier = server.getEngine().didVerifier();
		// Stand-ins for a remote DID method: one counts how often it is asked,
		// one is slow enough to observe overlap.
		verifier.registerMethod("counting", (did, message, signature) -> {
			verifications.incrementAndGet();
			return false;
		});
		verifier.registerMethod("slow", (did, message, signature) -> {
			int now = inFlight.incrementAndGet();
			maxInFlight.accumulateAndGet(now, Math::max);
			try {
				Thread.sleep(300);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				inFlight.decrementAndGet();
			}
			return false;
		});
	}

	@AfterAll
	static void shutdown() {
		if (server != null) server.close();
	}

	/** A self-issued token for a subject some DID method must resolve; signed by a throwaway key. */
	private static String remoteToken(String subject) {
		long now = System.currentTimeMillis() / 1000;
		return JWT.signPublic(Maps.of(
			JWT.SUB, subject, JWT.ISS, subject, JWT.AUD, venueDID,
			JWT.IAT, CVMLong.create(now), JWT.EXP, CVMLong.create(now + 300)), AKeyPair.generate()).toString();
	}

	/** A valid self-issued did:key token. */
	private static String validToken() {
		AKeyPair key = AKeyPair.generate();
		String did = UCAN.toDIDKey(key.getAccountKey()).toString();
		long now = System.currentTimeMillis() / 1000;
		return JWT.signPublic(Maps.of(
			JWT.SUB, did, JWT.ISS, did, JWT.AUD, venueDID,
			JWT.IAT, CVMLong.create(now), JWT.EXP, CVMLong.create(now + 300)), key).toString();
	}

	private static HttpRequest status(String fromIp, String bearer) {
		HttpRequest.Builder b = HttpRequest.newBuilder(
				URI.create("http://127.0.0.1:" + server.port() + "/api/v1/status"))
			.header("X-Forwarded-For", fromIp)
			.timeout(Duration.ofSeconds(15)).GET();
		if (bearer != null) b.header("Authorization", "Bearer " + bearer);
		return b.build();
	}

	private static HttpResponse<String> send(String fromIp, String bearer) throws Exception {
		return TestHTTP.CLIENT.send(status(fromIp, bearer), HttpResponse.BodyHandlers.ofString());
	}

	private static CompletableFuture<HttpResponse<String>> sendAsync(String fromIp, String bearer) {
		return TestHTTP.CLIENT.sendAsync(status(fromIp, bearer), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	public void failureBudgetRefusesFurtherCredentialsWithoutVerifying() throws Exception {
		String ip = "203.0.113.7";
		int before = verifications.get();
		for (int i = 0; i < FAILURE_BURST; i++) {
			HttpResponse<String> r = send(ip, remoteToken("did:counting:x"));
			assertEquals(401, r.statusCode(), r.body());
			assertTrue(r.body().contains(VenueAuthenticator.SELF_ISSUED_REJECTED_PREFIX), r.body());
		}
		assertEquals(before + FAILURE_BURST, verifications.get(), "each rejected credential was verified");

		// Over budget: 429 with Retry-After, and no verification at all — not
		// even of a credential that would have been accepted.
		HttpResponse<String> refused = send(ip, remoteToken("did:counting:x"));
		assertEquals(429, refused.statusCode(), refused.body());
		assertTrue(refused.headers().firstValue("Retry-After").isPresent(), "429 carries Retry-After");
		assertTrue(refused.body().contains(AuthThrottle.TOO_MANY_FAILURES), refused.body());
		String valid = validToken();
		assertEquals(429, send(ip, valid).statusCode(),
			"over budget, the address's credentials are not examined");
		assertEquals(before + FAILURE_BURST, verifications.get(), "the refused attempts cost no verification");

		// Another address is unaffected; so is the throttled address without a credential.
		assertEquals(401, send("203.0.113.8", remoteToken("did:counting:x")).statusCode());
		assertEquals(200, send(ip, null).statusCode(), "no credential presented, nothing charged");
		assertEquals(200, send("203.0.113.9", valid).statusCode(), "the valid credential works from a fresh address");
	}

	@Test
	public void oneAuthenticationInFlightPerAddress() throws Exception {
		maxInFlight.set(0);
		List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
		for (int i = 0; i < FAILURE_BURST; i++) sent.add(sendAsync("203.0.113.20", remoteToken("did:slow:a")));
		for (CompletableFuture<HttpResponse<String>> f : sent) {
			HttpResponse<String> r = f.get(20, TimeUnit.SECONDS);
			assertEquals(401, r.statusCode(), r.body());
		}
		assertEquals(1, maxInFlight.get(), "a further attempt from the same address waits for the one in flight");

		maxInFlight.set(0);
		CompletableFuture<HttpResponse<String>> a = sendAsync("203.0.113.21", remoteToken("did:slow:a"));
		CompletableFuture<HttpResponse<String>> b = sendAsync("203.0.113.22", remoteToken("did:slow:a"));
		assertEquals(401, a.get(20, TimeUnit.SECONDS).statusCode());
		assertEquals(401, b.get(20, TimeUnit.SECONDS).statusCode());
		assertEquals(2, maxInFlight.get(), "the gate is per address: two addresses verify in parallel");
	}
}
