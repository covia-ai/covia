package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.ConfigurationException;
import covia.exception.NotSupportedException;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.SecretStore;
import covia.venue.TestServer;
import covia.venue.User;
import covia.venue.server.VenueServer;

/**
 * Connected accounts end to end against a fake OAuth 2.0 provider: connect
 * (state + PKCE), the callback holding the approval under a completion code,
 * {@code oauth:complete} from the starting account exchanging the code and
 * storing the grant, {@code http:*} resolving {@code bearerSecret:
 * "oauth/<provider>"} to a token the model never sees, refresh on expiry,
 * and disconnect with revocation — plus every way the flow is refused.
 */
public class OAuthAdapterTest {

	/** A provider that speaks just enough OAuth 2.0 for the flow and records what it saw. */
	static final class FakeProvider implements AutoCloseable {
		final HttpServer server;
		volatile long expiresIn = 3600;
		volatile String expectedChallenge;
		final AtomicInteger issued = new AtomicInteger();
		final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
		final AtomicReference<Map<String, String>> revoked = new AtomicReference<>();
		final List<String> revokedTokens = new CopyOnWriteArrayList<>();
		/** When set, a refresh signals its arrival and waits for release before answering. */
		volatile CountDownLatch refreshArrived;
		volatile CountDownLatch refreshRelease;
		private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();

		FakeProvider() throws IOException {
			server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			// Concurrent handlers: a held refresh must not hold up a revocation
			server.setExecutor(handlers);
			server.createContext("/token", x -> {
				Map<String, String> form = form(x);
				tokenRequests.add(form);
				CountDownLatch arrived = refreshArrived, release = refreshRelease;
				if ("refresh_token".equals(form.get("grant_type")) && arrived != null && release != null) {
					arrived.countDown();
					try {
						release.await(10, TimeUnit.SECONDS);   // a backstop: the test releases it at once
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				int status = 200;
				String body;
				if ("authorization_code".equals(form.get("grant_type"))) {
					boolean ok = "code-1".equals(form.get("code"))
						&& "cid".equals(form.get("client_id"))
						&& "shh".equals(form.get("client_secret"))
						&& form.getOrDefault("redirect_uri", "").endsWith("/auth/connect/fake/callback")
						&& expectedChallenge != null
						&& expectedChallenge.equals(OAuthAdapter.challenge(form.getOrDefault("code_verifier", "")));
					if (ok) body = token(true);
					else { status = 400; body = "{\"error\":\"invalid_grant\",\"error_description\":\"bad exchange\"}"; }
				} else if ("refresh_token".equals(form.get("grant_type"))) {
					if ("refresh-1".equals(form.get("refresh_token")) && "shh".equals(form.get("client_secret"))) body = token(false);
					else { status = 400; body = "{\"error\":\"invalid_grant\"}"; }
				} else {
					status = 400;
					body = "{\"error\":\"unsupported_grant_type\"}";
				}
				respond(x, status, body, "application/json");
			});
			server.createContext("/revoke", x -> {
				revoked.set(form(x));
				revokedTokens.add(revoked.get().get("token"));
				respond(x, 200, "", "text/plain");
			});
			server.createContext("/api/me", x -> respond(x, 200,
				String.valueOf(x.getRequestHeaders().getFirst("Authorization")), "text/plain"));
			server.start();
		}

		String base() { return "http://localhost:" + server.getAddress().getPort(); }

		private String token(boolean withRefresh) {
			int n = issued.incrementAndGet();
			return "{\"access_token\":\"access-" + n + "\"" + (withRefresh ? ",\"refresh_token\":\"refresh-1\"" : "")
				+ ",\"expires_in\":" + expiresIn + ",\"token_type\":\"Bearer\",\"scope\":\"read\"}";
		}

		static Map<String, String> form(HttpExchange x) throws IOException {
			String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			Map<String, String> m = new HashMap<>();
			for (String pair : body.split("&")) {
				if (pair.isEmpty()) continue;
				int eq = pair.indexOf('=');
				String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
				String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
				m.put(k, v);
			}
			return m;
		}

		static void respond(HttpExchange x, int status, String body, String type) throws IOException {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			x.getResponseHeaders().set("Content-Type", type);
			if (bytes.length == 0) { x.sendResponseHeaders(status, -1); x.close(); return; }
			x.sendResponseHeaders(status, bytes.length);
			try (OutputStream os = x.getResponseBody()) { os.write(bytes); }
		}

		@Override public void close() {
			server.stop(0);
			handlers.shutdownNow();
		}
	}

	private static AMap<AString, ACell> oauthConfig(FakeProvider fake) {
		return Maps.of("oauth", Maps.of(
			"providers", Maps.of("fake", Maps.of(
				"clientId", "cid",
				"clientSecret", "s/FAKE_SECRET",
				"authorizationEndpoint", fake.base() + "/authorize",
				"tokenEndpoint", fake.base() + "/token",
				"revocationEndpoint", fake.base() + "/revoke",
				"scopes", Vectors.of("read"))),
			"returnTo", Vectors.of("app://done")));
	}

	private static Engine engine(FakeProvider fake) {
		Engine engine = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.ADAPTERS, oauthConfig(fake)));
		Engine.addDemoAssets(engine);
		((HTTPAdapter) engine.getAdapter("http")).addAllowedHost("localhost");
		storeClientSecret(engine);
		return engine;
	}

	private static void storeClientSecret(Engine engine) {
		User venueUser = engine.getVenueState().users().ensure(engine.getDIDString());
		venueUser.secrets().store("FAKE_SECRET", "shh", SecretStore.keys(engine.getKeyPair()));
	}

	private static Job run(Engine engine, String op, ACell input, RequestContext ctx) {
		Job job;
		try {
			job = engine.jobs().invokeOperation(op, input, ctx);
		} catch (RuntimeException refused) {
			return Job.failure(String.valueOf(refused.getMessage()));   // refused before a Job existed
		}
		try { job.awaitResult(10_000); } catch (Exception failed) { /* status tells */ }
		return job;
	}

	private static ACell ok(Job job) {
		assertEquals(Status.COMPLETE, job.getStatus(), String.valueOf(job.getErrorMessage()));
		return job.getOutput();
	}

	private static String failed(Job job) {
		assertEquals(Status.FAILED, job.getStatus(), "expected a failure, got " + job.getStatus());
		return String.valueOf(job.getErrorMessage());
	}

	private static Map<String, String> query(String url) {
		Map<String, String> m = new HashMap<>();
		for (String pair : URI.create(url).getRawQuery().split("&")) {
			int eq = pair.indexOf('=');
			m.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
				URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return m;
	}

	private static AVector<ACell> connections(Engine engine, RequestContext ctx) {
		return RT.ensureVector(RT.getIn(ok(run(engine, "v/ops/oauth/status", Maps.empty(), ctx)), "connections"));
	}

	// ========== the flow ==========

	@Test
	public void testConnectApproveCallRefreshAndDisconnect() throws Exception {
		try (FakeProvider fake = new FakeProvider()) {
			Engine engine = engine(fake);
			try {
				RequestContext alice = RequestContext.of(Strings.create("did:test:oauth:alice"));
				OAuthAdapter adapter = engine.findAdapter(OAuthAdapter.class);
				assertNotNull(adapter);

				// Nothing connected yet; a bearer reference says so, actionably.
				assertEquals(0, connections(engine, alice).count());
				String notYet = failed(run(engine, "v/ops/http/get",
					Maps.of("url", fake.base() + "/api/me", "bearerSecret", "oauth/fake"), alice));
				assertTrue(notYet.contains("Not connected to fake") && notYet.contains("oauth:connect"), notYet);

				// connect: a one-time state and a PKCE challenge, bound to the caller.
				ACell started = ok(run(engine, "v/ops/oauth/connect",
					Maps.of("provider", "fake", "returnTo", "app://done/settings"), alice));
				String url = RT.getIn(started, "url").toString();
				assertTrue(url.startsWith(fake.base() + "/authorize?"), url);
				Map<String, String> q = query(url);
				assertEquals("code", q.get("response_type"));
				assertEquals("cid", q.get("client_id"));
				assertEquals("read", q.get("scope"));
				assertEquals("S256", q.get("code_challenge_method"));
				assertTrue(q.get("redirect_uri").endsWith("/auth/connect/fake/callback"), q.get("redirect_uri"));
				assertEquals(RT.getIn(started, "state").toString(), q.get("state"));
				fake.expectedChallenge = q.get("code_challenge");

				// The provider calls back: the state is consumed and the approval held, nothing exchanged or stored.
				OAuthAdapter.Callback done = adapter.callback("fake", q.get("state"), "code-1", null);
				assertTrue(done.ok(), done.message());
				assertEquals("app://done/settings", done.returnTo());
				assertNotNull(done.code());
				assertEquals("app://done/settings?code=" + done.code(), done.redirect());
				assertFalse(adapter.callback("fake", q.get("state"), "code-1", null).ok(), "a state is one-time");
				assertTrue(fake.tokenRequests.isEmpty(), "the callback exchanges nothing");
				assertEquals(0, connections(engine, alice).count());

				// Alice completes: the code is exchanged and the grant stored; a completion code is one-time.
				ACell completed = ok(run(engine, "v/ops/oauth/complete", Maps.of("code", done.code()), alice));
				assertEquals(Strings.create("fake"), RT.getIn(completed, "provider"));
				assertEquals(CVMBool.TRUE, RT.getIn(completed, "refreshable"));
				assertNull(RT.getIn(completed, "tokens"), "a completion never carries tokens");
				assertEquals("shh", fake.tokenRequests.get(0).get("client_secret"), "the client secret came from the venue secret store");
				assertTrue(failed(run(engine, "v/ops/oauth/complete", Maps.of("code", done.code()), alice))
					.contains(OAuthAdapter.UNKNOWN_COMPLETION));

				AVector<ACell> after = connections(engine, alice);
				assertEquals(1, after.count());
				assertEquals(Strings.create("fake"), RT.getIn(after.get(0), "provider"));
				assertEquals(Strings.create("read"), RT.getIn(after.get(0), "scope"));
				assertEquals(CVMBool.TRUE, RT.getIn(after.get(0), "refreshable"));
				assertTrue(RT.ensureLong(RT.getIn(after.get(0), "expiresAt")).longValue() > System.currentTimeMillis());
				assertNull(RT.getIn(after.get(0), "accessToken"), "status never carries tokens");

				// http:* attaches the token without substituting it into job input.
				Job call = run(engine, "v/ops/http/get",
					Maps.of("url", fake.base() + "/api/me", "bearerSecret", "oauth/fake"), alice);
				assertEquals("Bearer access-1", RT.getIn(ok(call), "body").toString());
				String record = call.getData().toString();
				assertEquals(Strings.create("oauth/fake"), RT.getIn(call.getData(), "input", "bearerSecret"), "the record preserves the credential reference");
				assertFalse(RT.getIn(call.getData(), Fields.INPUT).toString().contains("access-1"));
				assertFalse(record.contains("refresh-1"), record);

				// Another user has no such connection.
				RequestContext bob = RequestContext.of(Strings.create("did:test:oauth:bob"));
				assertEquals(0, connections(engine, bob).count());
				assertTrue(failed(run(engine, "v/ops/http/get",
					Maps.of("url", fake.base() + "/api/me", "bearerSecret", "oauth/fake"), bob)).contains("Not connected"));

				// An expired grant refreshes itself, keeping the refresh token the provider omitted.
				fake.expiresIn = 0;
				ACell again = ok(run(engine, "v/ops/oauth/connect", Maps.of("provider", "fake"), alice));
				Map<String, String> q2 = query(RT.getIn(again, "url").toString());
				fake.expectedChallenge = q2.get("code_challenge");
				ok(run(engine, "v/ops/oauth/complete",
					Maps.of("code", adapter.callback("fake", q2.get("state"), "code-1", null).code()), alice));   // access-2, expires now
				assertEquals("Bearer access-3", RT.getIn(ok(run(engine, "v/ops/http/get",
					Maps.of("url", fake.base() + "/api/me", "bearerSecret", "oauth/fake"), alice)), "body").toString());
				Map<String, String> refresh = fake.tokenRequests.get(fake.tokenRequests.size() - 1);
				assertEquals("refresh_token", refresh.get("grant_type"));
				assertEquals("refresh-1", refresh.get("refresh_token"));
				assertEquals("Bearer access-4", RT.getIn(ok(run(engine, "v/ops/http/get",
					Maps.of("url", fake.base() + "/api/me", "bearerSecret", "oauth/fake"), alice)), "body").toString(),
					"still refreshable with the retained refresh token");

				// disconnect revokes at the provider and forgets the grant.
				ACell gone = ok(run(engine, "v/ops/oauth/disconnect", Maps.of("provider", "fake"), alice));
				assertEquals(CVMBool.TRUE, RT.getIn(gone, "revoked"));
				assertEquals("refresh-1", fake.revoked.get().get("token"));
				assertEquals(0, connections(engine, alice).count());
				assertTrue(failed(run(engine, "v/ops/http/get",
					Maps.of("url", fake.base() + "/api/me", "bearerSecret", "oauth/fake"), alice)).contains("Not connected"));
				assertTrue(failed(run(engine, "v/ops/oauth/disconnect", Maps.of("provider", "fake"), alice)).contains("Not connected"));

				// Published for discovery, without secrets.
				ACell info = engine.resolvePath(Strings.create("v/info/adapters/oauth"), engine.venueContext());
				assertNotNull(info);
				assertFalse(String.valueOf(info).contains("shh"));
				assertNotNull(engine.resolvePath(Strings.create("v/skills/auth/oauth"), engine.venueContext()));
			} finally {
				engine.close();
			}
		}
	}

	/** The completion code a provider's approval of a connect started by {@code ctx} yields. */
	private static String approve(Engine engine, FakeProvider fake, RequestContext ctx) {
		ACell started = ok(run(engine, "v/ops/oauth/connect", Maps.of("provider", "fake"), ctx));
		Map<String, String> q = query(RT.getIn(started, "url").toString());
		fake.expectedChallenge = q.get("code_challenge");
		OAuthAdapter.Callback approved = engine.findAdapter(OAuthAdapter.class).callback("fake", q.get("state"), "code-1", null);
		assertTrue(approved.ok(), approved.message());
		return approved.code();
	}

	private static void connectFake(Engine engine, FakeProvider fake, RequestContext ctx) {
		ok(run(engine, "v/ops/oauth/complete", Maps.of("code", approve(engine, fake, ctx)), ctx));
	}

	@Test
	public void testOnlyTheStartingAccountCompletes() throws Exception {
		try (FakeProvider fake = new FakeProvider()) {
			Engine engine = engine(fake);
			try {
				AString aliceDID = Strings.create("did:test:oauth:alice");
				RequestContext alice = RequestContext.of(aliceDID);
				RequestContext bob = RequestContext.of(Strings.create("did:test:oauth:bob"));

				// Alice starts a connect and someone approves it; Bob's completion stores nothing anywhere.
				String code = approve(engine, fake, alice);
				assertTrue(failed(run(engine, "v/ops/oauth/complete", Maps.of("code", code), bob))
					.contains(OAuthAdapter.ANOTHER_ACCOUNT));
				assertTrue(fake.tokenRequests.isEmpty(), "nothing was exchanged");
				assertEquals(0, connections(engine, alice).count());
				assertEquals(0, connections(engine, bob).count());
				// The attempt used the code up, for Alice too.
				assertTrue(failed(run(engine, "v/ops/oauth/complete", Maps.of("code", code), alice))
					.contains(OAuthAdapter.UNKNOWN_COMPLETION));
				assertEquals(0, connections(engine, alice).count());

				// Signed-out callers complete nothing.
				String another = approve(engine, fake, alice);
				assertTrue(failed(run(engine, "v/ops/oauth/complete", Maps.of("code", another), RequestContext.ANONYMOUS))
					.toLowerCase().contains("authenticat"));
				assertTrue(failed(run(engine, "v/ops/oauth/complete", Maps.of("code", another),
					RequestContext.of(Strings.create(engine.getDIDString() + ":public")))).contains(OAuthAdapter.PUBLIC_HAS_NO_CONNECTIONS));

				// Alice's own completion stores the grant for her, as connecting always has.
				ok(run(engine, "v/ops/oauth/complete", Maps.of("code", approve(engine, fake, alice)), alice));
				assertEquals(1, connections(engine, alice).count());
				assertEquals(0, connections(engine, bob).count());

				// An agent of Alice's completes for her only under oauth/connect.
				String agentCode = approve(engine, fake, alice);
				assertTrue(failed(run(engine, "v/ops/oauth/complete", Maps.of("code", agentCode), agent(aliceDID)))
					.contains(Abilities.OAUTH_CONNECT.toString()));
				RequestContext connects = agent(aliceDID, Capability.create(OAuthAdapter.resource("fake"), Abilities.OAUTH_CONNECT));
				ok(run(engine, "v/ops/oauth/complete", Maps.of("code", approve(engine, fake, alice)), connects));
				assertEquals(1, connections(engine, alice).count());

				// A connect her agent started, Alice completes in her own app.
				ok(run(engine, "v/ops/oauth/complete", Maps.of("code", approve(engine, fake, connects)), alice));
				assertEquals(1, connections(engine, alice).count());
			} finally {
				engine.close();
			}
		}
	}

	private static final AMap<AString, ACell> INVOKE_ANY = Maps.of(Capability.CAN, Strings.create("invoke"));

	/** An agent of {@code owner}'s that may invoke anything and holds {@code grants} besides. */
	private static RequestContext agent(AString owner, AMap<?, ?>... grants) {
		AVector<ACell> caps = Vectors.of(INVOKE_ANY);
		for (AMap<?, ?> grant : grants) caps = caps.conj(grant);
		return RequestContext.ofAgent(owner, Strings.create("worker")).withCaps(caps);
	}

	@Test
	public void testConnectionTokenIsAnOAuthUse() throws Exception {
		try (FakeProvider fake = new FakeProvider()) {
			Engine engine = engine(fake);
			try {
				AString aliceDID = Strings.create("did:test:oauth:alice");
				RequestContext alice = RequestContext.of(aliceDID);
				connectFake(engine, fake, alice);
				AMap<AString, ACell> me = Maps.of("url", fake.base() + "/api/me", "bearerSecret", "oauth/fake");

				// An agent of Alice's with only invoke cannot send her token anywhere.
				String refused = failed(run(engine, "v/ops/http/get", me, agent(aliceDID)));
				assertTrue(refused.contains(Abilities.OAUTH_USE.toString()), refused);

				// A grant on the secret the connection once was covers nothing now.
				String oldGrant = failed(run(engine, "v/ops/http/get", me, agent(aliceDID,
					Capability.create(Strings.create("s/oauth/fake"), Abilities.SECRET_USE))));
				assertTrue(oldGrant.contains(Abilities.OAUTH_USE.toString()), oldGrant);

				// An oauth/use grant bound to a url prefix releases it there only.
				RequestContext bound = agent(aliceDID, Capability.create(OAuthAdapter.resource("fake"),
					Abilities.OAUTH_USE, Maps.of("url", fake.base() + "/api/")));
				assertEquals("Bearer access-1", RT.getIn(ok(run(engine, "v/ops/http/get", me, bound)), "body").toString());
				String elsewhere = failed(run(engine, "v/ops/http/get",
					Maps.of("url", fake.base() + "/token", "bearerSecret", "oauth/fake"), bound));
				assertTrue(elsewhere.contains(Abilities.OAUTH_USE.toString()), elsewhere);

				// The owner uses their own connection as of right.
				assertEquals("Bearer access-1", RT.getIn(ok(run(engine, "v/ops/http/get", me, alice)), "body").toString());
			} finally {
				engine.close();
			}
		}
	}

	@Test
	public void testConnectionsAreTheAdaptersOwnState() throws Exception {
		try (FakeProvider fake = new FakeProvider()) {
			Engine engine = engine(fake);
			try {
				AString aliceDID = Strings.create("did:test:oauth:alice");
				RequestContext alice = RequestContext.of(aliceDID);
				OAuthAdapter adapter = engine.findAdapter(OAuthAdapter.class);
				assertFalse(adapter.connected(aliceDID, "fake"));
				connectFake(engine, fake, alice);

				// Not in the user's secret store, where secret operations would reach it.
				User user = engine.getVenueState().users().get(aliceDID);
				assertFalse(user.secrets().exists(OAuthAdapter.LEGACY_GRANT_PREFIX + "fake"));
				assertEquals(0, RT.ensureVector(RT.getIn(ok(run(engine, "v/ops/secret/list", Maps.empty(), alice)),
					"names")).count());

				// In the adapter's state, its tokens sealed.
				ACell stored = engine.adapterState("oauth").read("users/" + aliceDID + "/fake");
				assertNotNull(stored);
				assertEquals(Strings.create("read"), RT.getIn(stored, "scope"));
				assertFalse(stored.toString().contains("access-1"), stored.toString());
				assertFalse(stored.toString().contains("refresh-1"), stored.toString());
				assertTrue(adapter.connected(aliceDID, "fake"));
				assertFalse(adapter.connected(aliceDID, "other"));
				assertFalse(adapter.connected(aliceDID, "../fake"));

				// Deleting the user deletes their connections.
				engine.deleteUser(engine.venueContext(), aliceDID);
				assertNull(engine.adapterState("oauth").read("users/" + aliceDID));
			} finally {
				engine.close();
			}
		}
	}

	@Test
	public void testRestrictedCallersNeedOAuthAbilities() throws Exception {
		try (FakeProvider fake = new FakeProvider()) {
			Engine engine = engine(fake);
			try {
				AString aliceDID = Strings.create("did:test:oauth:alice");
				RequestContext alice = RequestContext.of(aliceDID);
				connectFake(engine, fake, alice);
				AMap<AString, ACell> fakeProvider = Maps.of("provider", "fake");

				// Invoke alone reaches no connection: not its status, not a new one, not a disconnect.
				RequestContext invokeOnly = agent(aliceDID);
				assertTrue(failed(run(engine, "v/ops/oauth/status", Maps.empty(), invokeOnly))
					.contains(Capability.CRUD_READ.toString()));
				assertTrue(failed(run(engine, "v/ops/oauth/status", fakeProvider, invokeOnly))
					.contains(Capability.CRUD_READ.toString()));
				assertTrue(failed(run(engine, "v/ops/oauth/connect", fakeProvider, invokeOnly))
					.contains(Abilities.OAUTH_CONNECT.toString()));
				assertTrue(failed(run(engine, "v/ops/oauth/disconnect", fakeProvider, invokeOnly))
					.contains(Abilities.OAUTH_DISCONNECT.toString()));
				assertNull(fake.revoked.get(), "a refused disconnect revokes nothing");
				assertEquals(1, connections(engine, alice).count());

				// All of Alice's data is still not her accounts: crud reads them but cannot connect or disconnect.
				RequestContext crud = agent(aliceDID, Capability.create(aliceDID, Strings.create("crud")));
				assertEquals(1, connections(engine, crud).count());
				assertTrue(failed(run(engine, "v/ops/oauth/connect", fakeProvider, crud))
					.contains(Abilities.OAUTH_CONNECT.toString()));
				assertTrue(failed(run(engine, "v/ops/oauth/disconnect", fakeProvider, crud))
					.contains(Abilities.OAUTH_DISCONNECT.toString()));

				// Reading one connection is not reading them all.
				RequestContext readsFake = agent(aliceDID, Capability.create(OAuthAdapter.resource("fake"), Capability.CRUD_READ));
				assertEquals(1, RT.ensureVector(RT.getIn(ok(run(engine, "v/ops/oauth/status", fakeProvider, readsFake)),
					"connections")).count());
				assertTrue(failed(run(engine, "v/ops/oauth/status", Maps.empty(), readsFake))
					.contains(Capability.CRUD_READ.toString()));

				// The abilities themselves: connect, then disconnect.
				RequestContext connects = agent(aliceDID, Capability.create(OAuthAdapter.resource("fake"), Abilities.OAUTH_CONNECT));
				assertNotNull(RT.getIn(ok(run(engine, "v/ops/oauth/connect", fakeProvider, connects)), "url"));
				RequestContext disconnects = agent(aliceDID, Capability.create(OAuthAdapter.resource("fake"), Abilities.OAUTH_DISCONNECT));
				assertEquals(CVMBool.TRUE, RT.getIn(ok(run(engine, "v/ops/oauth/disconnect", fakeProvider, disconnects)), "disconnected"));
				assertEquals(0, connections(engine, alice).count());

				// oauth proves every oauth ability.
				connectFake(engine, fake, agent(aliceDID, Capability.create(OAuthAdapter.ALL_CONNECTIONS, Strings.create("oauth"))));
				assertEquals(1, connections(engine, alice).count());

				// The public principal, which signed-out callers share, holds no connections.
				RequestContext signedOut = RequestContext.of(Strings.create(engine.getDIDString() + ":public"));
				assertTrue(failed(run(engine, "v/ops/oauth/connect", fakeProvider, signedOut))
					.contains(OAuthAdapter.PUBLIC_HAS_NO_CONNECTIONS));
				assertTrue(failed(run(engine, "v/ops/oauth/status", Maps.empty(), signedOut))
					.contains(OAuthAdapter.PUBLIC_HAS_NO_CONNECTIONS));
			} finally {
				engine.close();
			}
		}
	}

	/** A grant as a venue before connections moved to adapter state kept it: a secret named oauth/<provider>. */
	private static void storeLegacyGrant(Engine engine, AString did, String provider, String plaintext) {
		engine.getVenueState().users().ensure(did).secrets().store(OAuthAdapter.LEGACY_GRANT_PREFIX + provider,
			plaintext, SecretStore.keys(engine.getKeyPair()));
	}

	private static String legacyGrant(String accessToken) {
		return legacyGrant(accessToken, "refresh-1");
	}

	private static String legacyGrant(String accessToken, String refreshToken) {
		return "{\"provider\":\"fake\",\"accessToken\":\"" + accessToken + "\",\"refreshToken\":\"" + refreshToken + "\","
			+ "\"expiresAt\":" + (System.currentTimeMillis() + 3_600_000) + ",\"obtained\":1,\"scope\":\"read\",\"tokenType\":\"Bearer\"}";
	}

	@Test
	public void testLegacyGrantsMoveOnFirstAccess() throws Exception {
		try (FakeProvider fake = new FakeProvider()) {
			Engine engine = engine(fake);
			try {
				AString aliceDID = Strings.create("did:test:oauth:alice");
				RequestContext alice = RequestContext.of(aliceDID);
				storeLegacyGrant(engine, aliceDID, "fake", legacyGrant("legacy-access"));

				// The first access moves it: a connection, in the adapter's state, gone from the secret store.
				AVector<ACell> moved = connections(engine, alice);
				assertEquals(1, moved.count(), moved.toString());
				assertEquals(CVMBool.TRUE, RT.getIn(moved.get(0), "refreshable"));
				User user = engine.getVenueState().users().get(aliceDID);
				assertFalse(user.secrets().exists(OAuthAdapter.LEGACY_GRANT_PREFIX + "fake"));
				ACell stored = engine.adapterState("oauth").read("users/" + aliceDID + "/fake");
				assertFalse(stored.toString().contains("legacy-access"), stored.toString());
				assertEquals("Bearer legacy-access", RT.getIn(ok(run(engine, "v/ops/http/get",
					Maps.of("url", fake.base() + "/api/me", "bearerSecret", "oauth/fake"), alice)), "body").toString());

				// A connection made since is kept over an older grant.
				AString bobDID = Strings.create("did:test:oauth:bob");
				RequestContext bob = RequestContext.of(bobDID);
				connectFake(engine, fake, bob);
				storeLegacyGrant(engine, bobDID, "fake", legacyGrant("legacy-access"));
				String bobToken = RT.getIn(ok(run(engine, "v/ops/http/get",
					Maps.of("url", fake.base() + "/api/me", "bearerSecret", "oauth/fake"), bob)), "body").toString();
				assertNotEquals("Bearer legacy-access", bobToken);
				assertFalse(engine.getVenueState().users().get(bobDID).secrets().exists(OAuthAdapter.LEGACY_GRANT_PREFIX + "fake"));

				// A grant that cannot be read is refused with a reason, and left where it was.
				AString carolDID = Strings.create("did:test:oauth:carol");
				storeLegacyGrant(engine, carolDID, "fake", "not a grant");
				assertTrue(failed(run(engine, "v/ops/oauth/status", Maps.empty(), RequestContext.of(carolDID)))
					.contains(OAuthAdapter.UNREADABLE_LEGACY_GRANT));
				assertTrue(engine.getVenueState().users().get(carolDID).secrets().exists(OAuthAdapter.LEGACY_GRANT_PREFIX + "fake"));
			} finally {
				engine.close();
			}
		}
	}

	@Test
	public void testLegacyGrantMoveIsAPureMigration() {
		SecretStore.Keys keys = SecretStore.keys(convex.core.crypto.AKeyPair.generate());
		AMap<AString, ACell> other = Maps.of("API_KEY", SecretStore.seal(Strings.create("k"), keys));
		AString fake = Strings.create(OAuthAdapter.LEGACY_GRANT_PREFIX + "fake");

		// A current part comes back as the same cells.
		AMap<AString, ACell> none = Maps.empty();
		OAuthAdapter.LegacyMove current = OAuthAdapter.moveLegacyGrants(other, none, keys);
		assertSame(other, current.secrets());
		assertSame(none, current.connections());

		// An older part converts, and converting it again changes nothing.
		OAuthAdapter.LegacyMove moved = OAuthAdapter.moveLegacyGrants(
			other.assoc(fake, SecretStore.seal(Strings.create(legacyGrant("legacy-access")), keys)), null, keys);
		assertEquals(other, moved.secrets());
		assertEquals(Strings.create("read"), RT.getIn(moved.connections(), "fake", "scope"));
		assertFalse(moved.connections().toString().contains("legacy-access"), "the moved tokens are sealed");
		OAuthAdapter.LegacyMove again = OAuthAdapter.moveLegacyGrants(moved.secrets(), moved.connections(), keys);
		assertSame(moved.secrets(), again.secrets());
		assertSame(moved.connections(), again.connections());

		// A shape it does not recognise is refused.
		NotSupportedException refused = assertThrows(NotSupportedException.class, () -> OAuthAdapter.moveLegacyGrants(
			other.assoc(fake, SecretStore.seal(Strings.create("not a grant"), keys)), none, keys));
		assertTrue(refused.getMessage().startsWith(OAuthAdapter.UNREADABLE_LEGACY_GRANT), refused.getMessage());
	}

	@Test
	public void testRefreshNeverUndoesADisconnect() throws Exception {
		try (FakeProvider fake = new FakeProvider()) {
			Engine engine = engine(fake);
			try {
				AString aliceDID = Strings.create("did:test:oauth:alice");
				RequestContext alice = RequestContext.of(aliceDID);
				AMap<AString, ACell> me = Maps.of("url", fake.base() + "/api/me", "bearerSecret", "oauth/fake");
				fake.expiresIn = 0;   // every token is due a refresh
				connectFake(engine, fake, alice);

				// A disconnect lands while a refresh is at the provider: the refresh must not bring it back.
				// The call runs on its own thread: http resolves its bearer before the job is returned.
				fake.refreshArrived = new CountDownLatch(1);
				fake.refreshRelease = new CountDownLatch(1);
				AtomicReference<Job> call = new AtomicReference<>();
				Thread caller = Thread.startVirtualThread(() -> call.set(run(engine, "v/ops/http/get", me, alice)));
				assertTrue(fake.refreshArrived.await(10, TimeUnit.SECONDS));
				ok(run(engine, "v/ops/oauth/disconnect", Maps.of("provider", "fake"), alice));
				fake.refreshRelease.countDown();
				caller.join(10_000);
				assertTrue(failed(call.get()).contains(OAuthAdapter.NOT_CONNECTED + "fake"), call.get().getErrorMessage());
				assertEquals(0, connections(engine, alice).count());

				// A connect while a refresh is at the provider wins too, and the call gets its token.
				connectFake(engine, fake, alice);
				fake.refreshArrived = new CountDownLatch(1);
				fake.refreshRelease = new CountDownLatch(1);
				caller = Thread.startVirtualThread(() -> call.set(run(engine, "v/ops/http/get", me, alice)));
				assertTrue(fake.refreshArrived.await(10, TimeUnit.SECONDS));
				CountDownLatch held = fake.refreshRelease;
				fake.refreshArrived = null;
				fake.refreshRelease = null;
				connectFake(engine, fake, alice);
				String newest = "Bearer access-" + fake.issued.get();
				ACell stored = engine.adapterState("oauth").read("users/" + aliceDID + "/fake");
				held.countDown();
				caller.join(10_000);
				assertEquals(newest, RT.getIn(ok(call.get()), "body").toString());
				assertEquals(stored, engine.adapterState("oauth").read("users/" + aliceDID + "/fake"),
					"the refresh did not overwrite the newer connection");
			} finally {
				engine.close();
			}
		}
	}

	@Test
	public void testCompletionForADeletedAccountStoresNothing() throws Exception {
		try (FakeProvider fake = new FakeProvider()) {
			Engine engine = engine(fake);
			try {
				AString bobDID = Strings.create("did:test:oauth:bob");
				RequestContext bob = RequestContext.of(bobDID);
				String code = approve(engine, fake, bob);

				// Bob's account is deleted, and a new one made under his DID, before he completes.
				engine.deleteUser(engine.venueContext(), bobDID);
				engine.getVenueState().users().create(bobDID);

				assertTrue(failed(run(engine, "v/ops/oauth/complete", Maps.of("code", code), bob))
					.contains(OAuthAdapter.ACCOUNT_GONE));
				assertEquals("refresh-1", fake.revoked.get().get("token"), "the tokens issued for it are revoked");
				assertNull(engine.adapterState("oauth").read("users/" + bobDID), "nothing reaches the new account");
			} finally {
				engine.close();
			}
		}
	}

	@Test
	public void testDeletingAnAccountRevokesItsGrants() throws Exception {
		try (FakeProvider fake = new FakeProvider()) {
			Engine engine = engine(fake);
			try {
				AString aliceDID = Strings.create("did:test:oauth:alice");
				connectFake(engine, fake, RequestContext.of(aliceDID));
				engine.deleteUser(engine.venueContext(), aliceDID);
				assertEquals(List.of("refresh-1"), fake.revokedTokens);
				assertNull(engine.adapterState("oauth").read("users/" + aliceDID));
				assertNull(engine.adapterState("oauth").read(OAuthAdapter.REVOCATIONS), "its tokens are not kept once revoked");

				// A grant an earlier venue kept, never moved to the connections, is revoked too
				AString carolDID = Strings.create("did:test:oauth:carol");
				storeLegacyGrant(engine, carolDID, "fake", legacyGrant("legacy-access", "legacy-refresh"));
				engine.deleteUser(engine.venueContext(), carolDID);
				assertEquals(List.of("refresh-1", "legacy-refresh"), fake.revokedTokens);
			} finally {
				engine.close();
			}
		}
	}

	@Test
	public void testCallbackRefusals() throws Exception {
		try (FakeProvider fake = new FakeProvider()) {
			Engine engine = engine(fake);
			try {
				RequestContext alice = RequestContext.of(Strings.create("did:test:oauth:alice2"));
				OAuthAdapter adapter = engine.findAdapter(OAuthAdapter.class);

				OAuthAdapter.Callback unknown = adapter.callback("fake", "no-such-state", "code-1", null);
				assertFalse(unknown.ok());
				assertEquals(OAuthAdapter.UNKNOWN_REQUEST, unknown.message());
				assertNull(unknown.code());
				assertFalse(adapter.callback("fake", null, "code-1", null).ok());

				// Provider mismatch: the state was minted for fake.
				String state = query(RT.getIn(ok(run(engine, "v/ops/oauth/connect",
					Maps.of("provider", "fake"), alice)), "url").toString()).get("state");
				OAuthAdapter.Callback mismatch = adapter.callback("other", state, "code-1", null);
				assertFalse(mismatch.ok());
				assertTrue(mismatch.message().contains("another provider"), mismatch.message());

				// The user declined at the provider: reported, and the return address kept.
				state = query(RT.getIn(ok(run(engine, "v/ops/oauth/connect",
					Maps.of("provider", "fake", "returnTo", "app://done/x"), alice)), "url").toString()).get("state");
				OAuthAdapter.Callback denied = adapter.callback("fake", state, null, "access_denied");
				assertFalse(denied.ok());
				assertTrue(denied.message().contains("access_denied"), denied.message());
				OAuthAdapter.Callback crafted = adapter.callback("fake", query(RT.getIn(ok(run(engine, "v/ops/oauth/connect",
					Maps.of("provider", "fake"), alice)), "url").toString()).get("state"), null, "Call +1 555 0100 now");
				assertFalse(crafted.message().contains("555"), "free text in the redirect is never shown: " + crafted.message());
				assertEquals("app://done/x", denied.returnTo());
				assertNull(denied.code());
				assertNull(denied.redirect(), "a refusal is shown, not sent to the app");

				// An approval's code joins the returnTo query, ahead of any fragment.
				state = query(RT.getIn(ok(run(engine, "v/ops/oauth/connect",
					Maps.of("provider", "fake", "returnTo", "app://done/x?tab=1#top"), alice)), "url").toString()).get("state");
				OAuthAdapter.Callback fragment = adapter.callback("fake", state, "code-1", null);
				assertEquals("app://done/x?tab=1&code=" + fragment.code() + "#top", fragment.redirect());

				// A code the provider rejects fails the completion with the provider's error code,
				// never its free-text description.
				Map<String, String> q = query(RT.getIn(ok(run(engine, "v/ops/oauth/connect",
					Maps.of("provider", "fake"), alice)), "url").toString());
				fake.expectedChallenge = q.get("code_challenge");
				OAuthAdapter.Callback held = adapter.callback("fake", q.get("state"), "wrong-code", null);
				assertTrue(held.ok(), "the callback cannot tell a code the provider will reject");
				String rejected = failed(run(engine, "v/ops/oauth/complete", Maps.of("code", held.code()), alice));
				assertTrue(rejected.contains("invalid_grant") && !rejected.contains("bad exchange"), rejected);
				assertEquals(0, connections(engine, alice).count(), "nothing stored on failure");

				// A completion needs a code the callback issued.
				assertTrue(failed(run(engine, "v/ops/oauth/complete", Maps.empty(), alice)).contains("code is required"));
				assertTrue(failed(run(engine, "v/ops/oauth/complete", Maps.of("code", "no-such-code"), alice))
					.contains(OAuthAdapter.UNKNOWN_COMPLETION));
				String live = RT.getIn(ok(run(engine, "v/ops/oauth/connect", Maps.of("provider", "fake"), alice)), "state").toString();
				assertTrue(failed(run(engine, "v/ops/oauth/complete", Maps.of("code", live), alice))
					.contains(OAuthAdapter.UNKNOWN_COMPLETION), "a state is not a completion code");
			} finally {
				engine.close();
			}
		}
	}

	@Test
	public void testConnectRefusals() throws Exception {
		try (FakeProvider fake = new FakeProvider()) {
			Engine engine = engine(fake);
			try {
				RequestContext alice = RequestContext.of(Strings.create("did:test:oauth:alice3"));
				assertTrue(failed(run(engine, "v/ops/oauth/connect", Maps.of("provider", "fake"), RequestContext.ANONYMOUS))
					.toLowerCase().contains("authenticat"));
				String unknown = failed(run(engine, "v/ops/oauth/connect", Maps.of("provider", "nope"), alice));
				assertTrue(unknown.contains("No OAuth provider 'nope'") && unknown.contains("fake"), unknown);
				assertTrue(failed(run(engine, "v/ops/oauth/connect", Maps.empty(), alice)).contains("provider is required"));
				// returnTo is an app under an operator prefix: not elsewhere, not a lookalike, not a venue path.
				for (String refused : List.of("https://evil.example/steal", "app://done.evil.example/x", "app://donex", "/settings")) {
					assertTrue(failed(run(engine, "v/ops/oauth/connect", Maps.of("provider", "fake", "returnTo", refused), alice))
						.contains(OAuthAdapter.RETURN_TO_REFUSED), refused);
				}
				for (String allowed : List.of("app://done", "app://done/x", "app://done?tab=1", "app://done#x")) {
					ok(run(engine, "v/ops/oauth/connect", Maps.of("provider", "fake", "returnTo", allowed), alice));
				}
				assertTrue(OAuthAdapter.under("https://app.example/x", "https://app.example/"));
				assertFalse(OAuthAdapter.under("https://app.example.evil/x", "https://app.example"));
				// Caller-chosen scopes replace the defaults.
				String url = RT.getIn(ok(run(engine, "v/ops/oauth/connect",
					Maps.of("provider", "fake", "scopes", Vectors.of("read", "write")), alice)), "url").toString();
				assertEquals("read write", query(url).get("scope"));
			} finally {
				engine.close();
			}
		}
	}

	// ========== configuration ==========

	@Test
	public void testConfiguration() {
		OAuthAdapter adapter = new OAuthAdapter();
		assertTrue(adapter.configure(Maps.of("providers", Maps.of("google", Maps.of("clientId", "g-id"))), false));
		OAuthAdapter.Provider google = adapter.providers().get("google");
		assertEquals("https://oauth2.googleapis.com/token", google.tokenEndpoint());
		assertEquals("offline", google.params().get("access_type"));
		assertTrue(google.pkce());
		assertTrue(adapter.authorizationUrl(google, "st", "ver", List.of("a")).contains("prompt=consent"));
		assertEquals(Strings.create("google"), RT.getIn(RT.ensureVector(RT.getIn(adapter.info(), "providers")).get(0), "name"));

		ConfigurationException literal = assertThrows(ConfigurationException.class, () -> adapter.configure(
			Maps.of("providers", Maps.of("google", Maps.of("clientId", "g", "clientSecret", "plaintext"))), false));
		assertTrue(literal.getMessage().contains("s/NAME"), literal.getMessage());
		assertThrows(ConfigurationException.class, () -> adapter.configure(
			Maps.of("providers", Maps.of("custom", Maps.of("clientId", "c"))), false), "unknown providers need endpoints");
		assertThrows(ConfigurationException.class, () -> adapter.configure(
			Maps.of("providers", Maps.of("custom", Maps.of("clientId", "c",
				"authorizationEndpoint", "http://idp.example/authorize", "tokenEndpoint", "https://idp.example/token"))), false),
			"plain http is only for loopback");
		assertThrows(ConfigurationException.class, () -> adapter.configure(
			Maps.of("providers", Maps.of("Bad Name", Maps.of("clientId", "c"))), false));
		assertThrows(ConfigurationException.class, () -> adapter.configure(Maps.of("pendingTtlSecs", 5), false));

		assertTrue(adapter.configure(Maps.of("providers", Maps.of("custom", Maps.of("clientId", "c",
			"authorizationEndpoint", "https://idp.example/authorize", "tokenEndpoint", "https://idp.example/token",
			"pkce", false, "params", Maps.of("audience", "api")))), false));
		OAuthAdapter.Provider custom = adapter.providers().get("custom");
		assertFalse(custom.pkce());
		String url = adapter.authorizationUrl(custom, "st", null, List.of());
		assertTrue(url.contains("audience=api") && !url.contains("code_challenge"), url);
	}

	// ========== the route ==========

	@Test
	public void testCallbackRouteIsWired() throws Exception {
		HttpResponse<String> r = covia.venue.TestHTTP.CLIENT.send(HttpRequest.newBuilder()
			.uri(URI.create(TestServer.BASE_URL + "/auth/connect/fake/callback?state=nope&code=x")).GET().build(),
			HttpResponse.BodyHandlers.ofString());
		assertEquals(400, r.statusCode(), r.body());
		assertTrue(r.body().contains(OAuthAdapter.UNKNOWN_REQUEST), r.body());
		assertTrue(r.headers().firstValue("Content-Type").orElse("").contains("text/html"));
	}

	private static final Pattern PAGE_CODE = Pattern.compile("<code>([A-Za-z0-9_-]+)</code>");

	@Test
	public void testCallbackHandsTheCompletionCodeToTheBrowser() throws Exception {
		try (FakeProvider fake = new FakeProvider(); HttpClient browser = HttpClient.newHttpClient()) {
			VenueServer server = VenueServer.launch(Maps.of(Config.PORT, 0, Config.BIND_ADDRESS, "127.0.0.1",
				Config.STORE, "memory", Config.USERS, Maps.of(Config.AUTO_CREATE, true), Config.ADAPTERS, oauthConfig(fake)));
			try {
				Engine engine = server.getEngine();
				storeClientSecret(engine);
				AString aliceDID = Strings.create("did:test:oauth:alice");
				RequestContext alice = RequestContext.of(aliceDID);

				// With an app to return to, the browser takes the code there; the app completes as Alice.
				Map<String, String> q = query(RT.getIn(ok(run(engine, "v/ops/oauth/connect",
					Maps.of("provider", "fake", "returnTo", "app://done/x?tab=1"), alice)), "url").toString());
				fake.expectedChallenge = q.get("code_challenge");
				HttpResponse<String> back = browser.send(callback(server, q.get("state")), HttpResponse.BodyHandlers.ofString());
				assertEquals(302, back.statusCode(), back.body());
				String location = back.headers().firstValue("Location").orElseThrow();
				assertTrue(location.startsWith("app://done/x?tab=1&code="), location);
				assertEquals("no-store", back.headers().firstValue("Cache-Control").orElse(null));
				assertEquals(0, connections(engine, alice).count(), "the redirect connects nothing");
				ok(run(engine, "v/ops/oauth/complete", Maps.of("code", query(location).get("code")), alice));
				assertEquals(1, connections(engine, alice).count());

				// Without one, the page shows the code and the account it connects to; nobody else can use it.
				q = query(RT.getIn(ok(run(engine, "v/ops/oauth/connect", Maps.of("provider", "fake"), alice)), "url").toString());
				fake.expectedChallenge = q.get("code_challenge");
				HttpResponse<String> page = browser.send(callback(server, q.get("state")), HttpResponse.BodyHandlers.ofString());
				assertEquals(200, page.statusCode(), page.body());
				assertTrue(page.body().contains(aliceDID.toString()), page.body());
				assertEquals("no-store", page.headers().firstValue("Cache-Control").orElse(null));
				Matcher shown = PAGE_CODE.matcher(page.body());
				assertTrue(shown.find(), page.body());
				RequestContext bob = RequestContext.of(Strings.create("did:test:oauth:bob"));
				int tokenRequests = fake.tokenRequests.size();
				assertTrue(failed(run(engine, "v/ops/oauth/complete", Maps.of("code", shown.group(1)), bob))
					.contains(OAuthAdapter.ANOTHER_ACCOUNT));
				assertEquals(tokenRequests, fake.tokenRequests.size(), "nothing was exchanged");
				assertEquals(1, connections(engine, alice).count(), "Alice keeps only her earlier connection");
				assertEquals(0, connections(engine, bob).count());
				assertTrue(failed(run(engine, "v/ops/oauth/complete", Maps.of("code", shown.group(1)), alice))
					.contains(OAuthAdapter.UNKNOWN_COMPLETION), "Bob's attempt spent the code");
			} finally {
				server.close();
			}
		}
	}

	/** The provider's redirect back to the venue, approving with the fake's one good code. */
	private static HttpRequest callback(VenueServer server, String state) {
		return HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + server.port()
			+ "/auth/connect/fake/callback?state=" + URLEncoder.encode(state, StandardCharsets.UTF_8) + "&code=code-1")).GET().build();
	}
}
