package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.UnavailableException;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.UcanTokens;
import covia.grid.auth.VenueChallenge;
import covia.grid.auth.VenueDID;
import covia.grid.client.VenueHTTP;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RemoteJobs;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.TwoVenueTestServer;
import covia.venue.auth.AuthMethods;
import covia.venue.auth.VenueAuthenticator;
import covia.venue.auth.VenueProofs;

/**
 * An authenticated connection sends a credential only to a venue proven to
 * hold the DID the credential is audienced to, by a mechanism the operator
 * lists in {@code auth.venueProof} — by default its address, then a
 * challenge. A did:web venue is proven at its own address; any venue whose
 * key the DID encodes is proven by answering a fresh challenge with a proof
 * that names the address dialled. A target that proves nothing receives only
 * discovery and the challenge, unless the operator turns proof off with
 * {@code auth.requireVenueProof}.
 */
public class VenueIdentityProofTest {

	private static final String GRID_RUN = "v/ops/grid/run";
	private static final String REMOTE_ID = "00112233445566778899aabbccddeeff";
	private static final String UNPROVABLE_DID = "did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";

	/** Proof by the default mechanisms. */
	private static final Engine ENGINE = TestEngine.ENGINE;

	/** An operator who turns proof off, and accepts bearer credentials without an audience. */
	private static Engine LAX;

	/** An operator who lists proof by address only. */
	private static Engine ADDRESS_ONLY;

	/** An operator who lists no proof mechanism. */
	private static Engine NO_PROOF;

	@BeforeAll
	static void startEngines() {
		LAX = engine(Maps.of(
			Config.REQUIRE_VENUE_PROOF, CVMBool.FALSE, Config.REQUIRE_AUD, CVMBool.FALSE));
		ADDRESS_ONLY = engine(Maps.of(VenueProofs.VENUE_PROOF,
			Vectors.of(Maps.of(AuthMethods.ADAPTER, "auth", AuthMethods.IMPLEMENTATION, AuthAdapter.ADDRESS_PROOF))));
		NO_PROOF = engine(Maps.of(VenueProofs.VENUE_PROOF, Vectors.empty()));
	}

	@AfterAll
	static void closeEngines() {
		LAX.close();
		ADDRESS_ONLY.close();
		NO_PROOF.close();
	}

	@Test
	void didWebClaimedForAnotherHostIsRefused() throws IOException {
		try (Peer peer = new Peer()) {
			peer.claims = "did:web:venue.example.com";
			assertRefused(ENGINE, peer, peer.url());
		}
	}

	@Test
	void aVenueReachedAwayFromItsDidWebIsSentNoCredential() {
		// Venue B states did:web:venue-b.test.covia.example but is reached on loopback
		String venue = TwoVenueTestServer.BASE_URL_B;
		String refused = refusal(ENGINE, input(venue, "venue"));
		assertTrue(refused != null && refused.contains(VenueConnection.VENUE_UNPROVEN), refused);
		assertTrue(refused.contains(VenueDID.NOT_ITS_ADDRESS), refused);
		assertTrue(jobsAt(TwoVenueTestServer.ENGINE_B, ENGINE).isEmpty(), "venue B holds no job in the caller's name");

		// With proof off, the credential is sent and venue B acts on it
		Job job = LAX.jobs().invokeOperation(GRID_RUN, input(venue, "venue"), LAX.venueContext());
		assertEquals(Strings.create("hello"), RT.getIn(job.awaitResult(5000), "message"));
		assertFalse(jobsAt(TwoVenueTestServer.ENGINE_B, LAX).isEmpty(), "venue B ran the job in the caller's name");
	}

	@Test
	void didKeyVenueAnsweringNoChallengeIsRefusedWithEveryReason() throws IOException {
		try (Peer peer = new Peer()) {
			peer.claims = UNPROVABLE_DID;
			String refusal = assertRefused(ENGINE, peer, peer.url());
			assertTrue(refusal.contains(VenueDID.NOT_ITS_ADDRESS), refusal);
			assertTrue(refusal.contains(VenueChallenge.Reason.NO_ANSWER), refusal);
		}
	}

	@Test
	void didKeyVenueAnsweringTheChallengeIsProven() throws IOException {
		try (Peer peer = Peer.withKey()) {
			assertAuthenticated(ENGINE, peer, peer.url());
			assertEquals(1, peer.challenges.get(), "its address proves nothing, so the challenge is tried");
		}
	}

	@Test
	void aChallengeProofIsReusedAndAFailureIsNot() throws IOException {
		try (Peer proven = Peer.withKey(); Peer unproven = new Peer()) {
			unproven.claims = UNPROVABLE_DID;
			assertAuthenticated(ENGINE, proven, proven.url());
			assertAuthenticated(ENGINE, proven, proven.url());
			assertEquals(1, proven.challenges.get());
			assertRefused(ENGINE, unproven, unproven.url());
			assertRefused(ENGINE, unproven, unproven.url());
			assertEquals(2, unproven.challenges.get());
		}
	}

	@Test
	void anAddressForwardingToTheVenueIsRefusedAndReceivesNoCredential() throws IOException {
		try (Peer venue = Peer.withKey(); Peer forwarder = new Peer()) {
			forwarder.forwardTo = venue;
			String refusal = assertRefused(ENGINE, forwarder, forwarder.url());
			assertTrue(refusal.contains(VenueChallenge.Reason.OTHER_BASE), refusal);
			assertEquals(1, venue.challenges.get(), "the venue answered the challenge sent to the forwarder");
			assertEquals(0, venue.credentialed.get());
		}
	}

	@Test
	void aProofGivenForAnEarlierChallengeIsRefused() throws IOException {
		try (Peer venue = Peer.withKey()) {
			// The address answers every challenge with a proof the venue gave earlier
			venue.replay = venue.proof(VenueChallenge.nonce());
			String refusal = assertRefused(ENGINE, venue, venue.url());
			assertTrue(refusal.contains(VenueChallenge.Reason.OTHER_NONCE), refusal);
		}
	}

	@Test
	void anUnlistedMechanismIsNeverConsulted() throws IOException {
		try (Peer peer = Peer.withKey()) {
			assertRefused(ADDRESS_ONLY, peer, peer.url());
			assertEquals(0, peer.challenges.get());
		}
	}

	@Test
	void withNoMechanismListedOnlyAnonymousHopsProceed() throws IOException {
		try (Peer peer = Peer.withKey()) {
			String refusal = assertRefused(NO_PROOF, peer, peer.url());
			assertTrue(refusal.contains(VenueProofs.NONE_LISTED), refusal);
			Job job = NO_PROOF.jobs().invokeOperation(GRID_RUN, input(peer.url(), null), NO_PROOF.venueContext());
			assertThrows(Exception.class, () -> job.awaitResult(5000));
			assertTrue(peer.calls.get() > 0, "the anonymous hop reaches the venue");
			assertEquals(0, peer.credentialed.get());
		}
	}

	@Test
	void proofDialsOnlyThroughTheOutboundGuard() throws IOException {
		try (Peer peer = Peer.withKey()) {
			RequestContext user = RequestContext.of(TestEngine.uniqueDID("venue-proof-guard"));
			String reason = ENGINE.venueProof(user, peer.url(), peer.claims);
			assertTrue(reason != null && reason.contains(VenueChallenge.Reason.UNREACHABLE), reason);
			assertEquals(0, peer.challenges.get(), "the guard refuses a loopback target to a user");
			assertNull(ENGINE.venueProof(ENGINE.venueContext(), peer.url(), peer.claims));
		}
	}

	@Test
	void didWebAtItsOwnLoopbackAddressIsProven() throws IOException {
		try (Peer peer = new Peer()) {
			peer.claims = peer.webDID();
			assertAuthenticated(ENGINE, peer, peer.url());
			assertEquals(0, peer.challenges.get(), "the address proves it, so no challenge is sent");
		}
	}

	@Test
	void venueNamedByItsDidWebIsProven() throws IOException {
		try (Peer peer = new Peer()) {
			peer.claims = peer.webDID();
			assertAuthenticated(ENGINE, peer, peer.webDID());
			assertEquals(1, peer.discoveries.get(),
				"the connection checks once that the venue presents the DID it was opened for");
		}
	}

	@Test
	void venueNamedByItsDidWebPresentingAnotherGetsNoCredential() throws IOException {
		try (Peer peer = new Peer()) {
			peer.claims = UNPROVABLE_DID;
			String refusal = refusal(ENGINE, input(peer.webDID(), "venue"));
			assertTrue(refusal != null && refusal.contains(VenueDID.PRESENTS_OTHER), refusal);
			assertEquals(0, peer.calls.get(), "a venue presenting another DID receives no call");
		}
	}

	@Test
	void proofOffTakesTheClaimedDid() throws IOException {
		try (Peer peer = new Peer()) {
			peer.claims = "did:web:venue.example.com";
			assertAuthenticated(LAX, peer, peer.url());
		}
	}

	@Test
	void proofMatchesSchemeHostAndPort() {
		assertTrue(VenueDID.provenAt("https://venue.example.com", "did:web:venue.example.com"));
		assertTrue(VenueDID.provenAt("https://Venue.Example.com:443/", "did:web:venue.example.com"));
		assertTrue(VenueDID.provenAt("https://venue.example.com:8443", "did:web:venue.example.com%3A8443"));
		assertTrue(VenueDID.provenAt("http://localhost:8080", "did:web:localhost%3A8080"));
		assertFalse(VenueDID.provenAt("http://venue.example.com", "did:web:venue.example.com"));
		assertFalse(VenueDID.provenAt(" https://venue.example.com", "did:web:venue.example.com"));
		assertFalse(VenueDID.provenAt("https://venue.example.com:8443", "did:web:venue.example.com"));
		assertFalse(VenueDID.provenAt("https://evil.example", "did:web:venue.example.com"));
		assertFalse(VenueDID.provenAt("https://venue.example.com", UNPROVABLE_DID));
	}

	@Test
	void aProofIsNotABearerCredential() {
		// At the venue whose key signed it, and that requires an audience
		assertTrue(bearerRefusal(ENGINE, ENGINE).contains(VenueAuthenticator.Reason.AUDIENCE_REQUIRED));
		// At the venue whose key signed it, without an audience requirement
		assertTrue(bearerRefusal(LAX, LAX).contains(VenueAuthenticator.Reason.MISSING_SUBJECT));
		// At any other venue
		assertTrue(bearerRefusal(ENGINE, ADDRESS_ONLY).contains(VenueAuthenticator.Reason.UNRECOGNISED));
	}

	@Test
	void restoreSendsNoCredentialToAVenueNoLongerProven() throws IOException {
		try (Peer peer = new Peer()) {
			peer.claims = UNPROVABLE_DID;
			AMap<AString, ACell> saved = Maps.of("authenticateAs", "venue", Fields.VENUE, UNPROVABLE_DID,
				"outboundAny", CVMBool.TRUE);
			AuthException refused = assertThrows(AuthException.class,
				() -> VenueConnection.restore(ENGINE, Strings.create(peer.url()), saved));
			assertTrue(refused.getMessage().contains(VenueConnection.VENUE_UNPROVEN), refused.getMessage());
			AMap<AString, ACell> unnamed = Maps.of("authenticateAs", "venue", "outboundAny", CVMBool.TRUE);
			refused = assertThrows(AuthException.class,
				() -> VenueConnection.restore(ENGINE, Strings.create(peer.url()), unnamed));
			assertTrue(refused.getMessage().startsWith(VenueConnection.NO_SAVED_DID), refused.getMessage());
			assertEquals(0, peer.credentialed.get());
		}
	}

	@Test
	void aRecoveredJobWhoseVenueIsNoLongerProvenAwaitsAuthorityWithoutResendingItsCredential() throws Exception {
		try (Peer peer = Peer.withKey()) {
			peer.acceptsJobs = true;
			// The first venue's status request goes unanswered until it has closed, with no
			// grace window, so it has nothing left to send when the credentials are counted
			peer.holdStatus = new CountDownLatch(1);
			AMap<AString, ACell> config = Maps.of(Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0));
			TestEngine.Restartable venue = TestEngine.restartable(config);
			Engine first = venue.start();
			Engine.addDemoAssets(first);
			peer.verifier = first;
			Job job;
			try {
				job = first.jobs().invokeOperation(GRID_RUN, input(peer.url(), "venue"), first.venueContext());
				assertTrue(peer.statusAsked.await(5, TimeUnit.SECONDS), "the venue observes the job it handed over");
			} finally { first.close(); }
			assertTrue(peer.credentialed.get() > 0, "the proven venue received the job with a credential");
			int credentialed = peer.credentialed.get();
			peer.holdStatus.countDown();
			// The address no longer answers challenges
			peer.key = null;
			Engine second = venue.start();
			try {
				Engine.addDemoAssets(second);
				second.jobs().recoverJobs();
				Job recovered = second.jobs().getJob(job.getID(), second.venueContext());
				assertEquals(Strings.create("auth-required"), second.remoteJobs().kept(recovered).get(RemoteJobs.OBSERVATION));
				assertEquals(Strings.create(AuthException.class.getSimpleName()),
					second.remoteJobs().kept(recovered).get(RemoteJobs.ERROR_TYPE));
				assertNotNull(convex.core.lang.RT.getIn(recovered.getData(), RemoteJobs.DELEGATION, RemoteJobs.NOT_FOLLOWED),
					"the record says the venue no longer follows the remote job");
				assertEquals(Status.STARTED, recovered.getStatus());
				assertEquals(credentialed, peer.credentialed.get(), "no credential is re-sent");
			} finally { second.close(); }
		}
	}

	@Test
	void aRecoveredJobThatSignedInAsItsCallerEndsNamingTheRemoteJob() throws Exception {
		try (Peer peer = Peer.withKey()) {
			peer.acceptsJobs = true;
			// Its status request goes unanswered until the first venue has closed, so
			// that venue has nothing left to send when the requests are counted
			peer.holdStatus = new CountDownLatch(1);
			AMap<AString, ACell> config = Maps.of(Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0));
			TestEngine.Restartable venue = TestEngine.restartable(config);
			Engine first = venue.start();
			Engine.addDemoAssets(first);
			peer.verifier = first;
			Job job;
			try {
				String credential = UcanTokens.identityToken(venue.key(), peer.claims, 300);
				RequestContext caller = first.venueContext().withRawUcans(Vectors.of(Strings.create(credential)));
				job = first.jobs().invokeOperation(GRID_RUN, input(peer.url(), "caller"), caller);
				assertTrue(peer.statusAsked.await(5, TimeUnit.SECONDS), "the venue observes the job it handed over");
				assertTrue(peer.authenticated.get() > 0, "the job signed in as its caller");
				assertFalse(JSON.toString(first.remoteJobs().connection(job)).contains(credential),
					"the sign-in credential is not kept for recovery");
			} finally { first.close(); }
			peer.holdStatus.countDown();
			int requests = peer.discoveries.get() + peer.challenges.get() + peer.calls.get();
			Engine second = venue.start();
			peer.verifier = second;
			try {
				Engine.addDemoAssets(second);
				second.jobs().recoverJobs();
				Job recovered = second.jobs().getJob(job.getID(), second.venueContext());
				assertEquals(Status.FAILED, recovered.getStatus());
				assertEquals(RemoteJobs.CREDENTIAL_NOT_KEPT.formatted(REMOTE_ID, peer.url()), recovered.getErrorMessage());
				assertThrows(UnavailableException.class, () -> second.remoteJobs().connection(recovered),
					"what was kept for recovery is removed once the job ends");
				assertEquals(requests, peer.discoveries.get() + peer.challenges.get() + peer.calls.get(),
					"nothing is sent to the remote venue");
			} finally { second.close(); }
		}
	}

	private static AMap<AString, ACell> input(String venue, String authenticateAs) {
		AMap<AString, ACell> input = Maps.of(Fields.VENUE, venue, Fields.OPERATION, "v/test/ops/echo",
			Fields.INPUT, Maps.of("message", "hello"));
		return authenticateAs == null ? input : input.assoc(Strings.create("authenticateAs"), Strings.create(authenticateAs));
	}

	/** The refusal of the venue's own call, at submission or as the job's error. */
	private static String refusal(Engine engine, AMap<AString, ACell> input) {
		try {
			Job job = engine.jobs().invokeOperation(GRID_RUN, input, engine.venueContext());
			assertThrows(Exception.class, () -> job.awaitResult(5000));
			return job.getErrorMessage();
		} catch (RuntimeException e) {
			return e.getMessage();
		}
	}

	/** The jobs {@code venue} holds in the name of {@code caller}'s venue identity. */
	private static Index<Blob, ACell> jobsAt(Engine venue, Engine caller) {
		return venue.jobs().getJobs(RequestContext.of(caller.getDIDString()));
	}

	/** The venue's own authenticated call is refused as unproven, and nothing but discovery and the challenge reached the peer. */
	private static String assertRefused(Engine engine, Peer peer, String venue) {
		String refusal = refusal(engine, input(venue, "venue"));
		assertTrue(refusal != null && refusal.contains(VenueConnection.VENUE_UNPROVEN), refusal);
		assertEquals(0, peer.calls.get(), "an unproven venue receives no call");
		assertEquals(0, peer.credentialed.get(), "an unproven venue receives no credential");
		return refusal;
	}

	/** The venue's own authenticated call reaches the peer carrying a credential. */
	private static void assertAuthenticated(Engine engine, Peer peer, String venue) {
		Job job = engine.jobs().invokeOperation(GRID_RUN, input(venue, "venue"), engine.venueContext());
		// The peer is no venue, so the call itself fails once it has arrived
		assertThrows(Exception.class, () -> job.awaitResult(5000));
		assertTrue(peer.authenticated.get() > 0, "the credential reaches the venue: " + job.getErrorMessage());
	}

	/** Why {@code verifier} refuses, as a bearer credential, a challenge proof signed by {@code signer}'s key. */
	private static String bearerRefusal(Engine signer, Engine verifier) {
		AKeyPair key = signer.getKeyPair();
		String did = UCAN.toDIDKey(key.getAccountKey()).toString();
		String proof = sign(key, header(did), claims(did, "http://127.0.0.1:8080", VenueChallenge.nonce(), verifier));
		AuthException refused = assertThrows(AuthException.class,
			() -> new VenueAuthenticator(verifier).authenticate(Strings.create(proof)));
		return refused.getMessage();
	}

	private static AMap<AString, ACell> header(String did) {
		return Maps.of(JWT.ALG, "EdDSA", JWT.TYP, VenueChallenge.TYP,
			JWT.KID, did + "#" + did.substring("did:key:".length()));
	}

	/**
	 * Proof claims issued now on {@code verifier}'s clock, as a venue whose
	 * clock agrees with the verifier's would: a test venue's clock is held, so
	 * the system clock drifts from it as the run goes on.
	 */
	private static AMap<AString, ACell> claims(String did, String base, String nonce, Engine verifier) {
		long now = verifier.currentTimestamp().longValue() / 1000;
		return Maps.of(JWT.ISS, did, VenueChallenge.BASE, base, VenueChallenge.NONCE, nonce,
			JWT.IAT, now, JWT.EXP, now + 60);
	}

	/** A JWT signed with {@code key} over exactly this header and these claims. */
	private static String sign(AKeyPair key, AMap<AString, ACell> header, AMap<AString, ACell> claims) {
		AString signingInput = JWT.encode(JWT.claims(header)).append(".").append(JWT.encode(JWT.claims(claims)));
		return signingInput.append(".")
			.append(JWT.encode(key.sign(Blob.wrap(signingInput.getBytes())).getBytes())).toString();
	}

	private static Engine engine(AMap<AString, ACell> auth) {
		Engine engine = Engine.createTemp(Maps.of(Config.AUTH, auth));
		Engine.addDemoAssets(engine);
		return engine;
	}

	/**
	 * A loopback listener that answers discovery with the DID it is told to
	 * claim and, holding a key, challenges with proofs signed by it. It counts
	 * discoveries, challenges, the calls that follow, and every request
	 * carrying a credential or grants. Told to, it answers each challenge with
	 * one fixed proof, accepts jobs and holds their status requests unanswered,
	 * or forwards every request to another peer.
	 */
	private static final class Peer implements AutoCloseable {
		private static final HttpClient CLIENT = HttpClient.newHttpClient();

		final HttpServer server;
		final AtomicInteger discoveries = new AtomicInteger();
		final AtomicInteger challenges = new AtomicInteger();
		final AtomicInteger calls = new AtomicInteger();
		final AtomicInteger authenticated = new AtomicInteger();
		final AtomicInteger credentialed = new AtomicInteger();
		final CountDownLatch statusAsked = new CountDownLatch(1);
		/** While set, each job status request waits for it before its answer. */
		volatile CountDownLatch holdStatus;
		volatile String claims;
		volatile AKeyPair key;
		/** The venue that checks this peer's proofs, whose clock they are issued on. */
		volatile Engine verifier = ENGINE;
		volatile String replay;
		volatile boolean acceptsJobs;
		volatile Peer forwardTo;

		/** A venue holding its own did:key. */
		static Peer withKey() throws IOException {
			Peer peer = new Peer();
			peer.key = AKeyPair.generate();
			peer.claims = UCAN.toDIDKey(peer.key.getAccountKey()).toString();
			return peer;
		}

		Peer() throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				byte[] body = exchange.getRequestBody().readAllBytes();
				String path = exchange.getRequestURI().getPath();
				String authorization = exchange.getRequestHeaders().getFirst("Authorization");
				if (authorization != null || exchange.getRequestHeaders().getFirst(VenueHTTP.UCANS_HEADER) != null
						|| new String(body, StandardCharsets.UTF_8).contains("\"ucans\"")) {
					credentialed.incrementAndGet();
				}
				boolean discovery = path.equals("/.well-known/did.json");
				boolean challenge = path.equals("/api/v1/" + VenueChallenge.PATH);
				if (discovery) discoveries.incrementAndGet();
				else if (challenge) challenges.incrementAndGet();
				else {
					calls.incrementAndGet();
					if (authorization != null) authenticated.incrementAndGet();
				}
				Peer target = forwardTo;
				if (target != null) forward(exchange, target, body);
				else if (discovery) respond(exchange, 200, "{\"id\":\"" + claims + "\"}");
				else if (challenge) answerChallenge(exchange, body);
				else if (acceptsJobs && (path.endsWith("/invoke") || path.endsWith("/jobs/" + REMOTE_ID))) {
					if (!path.endsWith("/invoke")) awaitStatusRelease();
					respond(exchange, path.endsWith("/invoke") ? 201 : 200,
						"{\"id\":\"" + REMOTE_ID + "\",\"status\":\"STARTED\"}");
				}
				else respond(exchange, 404, "");
			});
			server.start();
		}

		/** Counts a job status request, then holds it while {@link #holdStatus} is set. */
		private void awaitStatusRelease() throws IOException {
			statusAsked.countDown();
			CountDownLatch hold = holdStatus;
			if (hold == null) return;
			try {
				hold.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException(e);
			}
		}

		private void answerChallenge(HttpExchange exchange, byte[] body) throws IOException {
			String proof = replay;
			if (proof == null && key != null) {
				AString nonce = RT.ensureString(RT.getIn(JSON.parse(new String(body, StandardCharsets.UTF_8)), VenueChallenge.NONCE));
				proof = proof(nonce == null ? null : nonce.toString());
			}
			if (proof == null) respond(exchange, 404, "");
			else respond(exchange, 200, "{\"proof\":\"" + proof + "\"}");
		}

		/** A proof for {@code nonce}, signed by this peer's key and naming its own address. */
		String proof(String nonce) {
			return sign(key, header(claims), VenueIdentityProofTest.claims(claims, url(), nonce, verifier));
		}

		/** Passes the request on to {@code target}, which sees its own host as the request's. */
		private static void forward(HttpExchange exchange, Peer target, byte[] body) throws IOException {
			HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target.url() + exchange.getRequestURI()))
				.method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
			for (String header : List.of("Authorization", "Content-Type", VenueHTTP.UCANS_HEADER)) {
				String value = exchange.getRequestHeaders().getFirst(header);
				if (value != null) request.header(header, value);
			}
			try {
				HttpResponse<String> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
				respond(exchange, response.statusCode(), response.body());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respond(exchange, 502, "");
			}
		}

		private static void respond(HttpExchange exchange, int code, String text) throws IOException {
			byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
			try (var out = exchange.getResponseBody()) { out.write(bytes); }
		}

		String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

		/** The did:web this listener can prove by its address: its own loopback address. */
		String webDID() { return "did:web:127.0.0.1%3A" + server.getAddress().getPort(); }

		@Override public void close() {
			CountDownLatch hold = holdStatus;
			if (hold != null) hold.countDown();
			server.stop(0);
		}
	}
}
