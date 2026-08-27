package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.SecretStore;
import covia.venue.TestServer;
import covia.venue.User;

/**
 * Connected accounts end to end against a fake OAuth 2.0 provider: connect
 * (state + PKCE), the callback exchanging the code and storing the grant,
 * {@code http:*} resolving {@code bearerSecret: "oauth/<provider>"} to a
 * token the model never sees, refresh on expiry, and disconnect with
 * revocation — plus every way the flow is refused.
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

		FakeProvider() throws IOException {
			server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/token", x -> {
				Map<String, String> form = form(x);
				tokenRequests.add(form);
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

		@Override public void close() { server.stop(0); }
	}

	private static Engine engine(FakeProvider fake) {
		Engine engine = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.ADAPTERS, Maps.of("oauth", Maps.of(
				"providers", Maps.of("fake", Maps.of(
					"clientId", "cid",
					"clientSecret", "s/FAKE_SECRET",
					"authorizationEndpoint", fake.base() + "/authorize",
					"tokenEndpoint", fake.base() + "/token",
					"revocationEndpoint", fake.base() + "/revoke",
					"scopes", Vectors.of("read"))),
				"returnTo", Vectors.of("app://done")))));
		Engine.addDemoAssets(engine);
		((HTTPAdapter) engine.getAdapter("http")).addAllowedHost("localhost");
		User venueUser = engine.getVenueState().users().ensure(engine.getDIDString());
		venueUser.secrets().store("FAKE_SECRET", "shh", SecretStore.deriveKey(engine.getKeyPair()));
		return engine;
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
					Maps.of("provider", "fake", "returnTo", "/settings"), alice));
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

				// The provider calls back: the code is exchanged, the grant stored, the state consumed.
				OAuthAdapter.Completion done = adapter.complete("fake", q.get("state"), "code-1", null, null);
				assertTrue(done.ok(), done.message());
				assertEquals("/settings", done.returnTo());
				assertEquals("shh", fake.tokenRequests.get(0).get("client_secret"), "the client secret came from the venue secret store");
				assertFalse(adapter.complete("fake", q.get("state"), "code-1", null, null).ok(), "a state is one-time");

				AVector<ACell> after = connections(engine, alice);
				assertEquals(1, after.count());
				assertEquals(Strings.create("fake"), RT.getIn(after.get(0), "provider"));
				assertEquals(Strings.create("read"), RT.getIn(after.get(0), "scope"));
				assertEquals(CVMBool.TRUE, RT.getIn(after.get(0), "refreshable"));
				assertTrue(RT.ensureLong(RT.getIn(after.get(0), "expiresAt")).longValue() > System.currentTimeMillis());
				assertNull(RT.getIn(after.get(0), "accessToken"), "status never carries tokens");

				// http:* attaches the token; the job record never carries it.
				Job call = run(engine, "v/ops/http/get",
					Maps.of("url", fake.base() + "/api/me", "bearerSecret", "oauth/fake"), alice);
				assertEquals("Bearer access-1", RT.getIn(ok(call), "body").toString());
				String record = call.getData().toString();
				assertEquals(Fields.HIDDEN, RT.getIn(call.getData(), "input", "bearerSecret"), "the reference is redacted in the record");
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
				assertTrue(adapter.complete("fake", q2.get("state"), "code-1", null, null).ok());   // access-2, expires now
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

	@Test
	public void testCallbackRefusals() throws Exception {
		try (FakeProvider fake = new FakeProvider()) {
			Engine engine = engine(fake);
			try {
				RequestContext alice = RequestContext.of(Strings.create("did:test:oauth:alice2"));
				OAuthAdapter adapter = engine.findAdapter(OAuthAdapter.class);

				OAuthAdapter.Completion unknown = adapter.complete("fake", "no-such-state", "code-1", null, null);
				assertFalse(unknown.ok());
				assertTrue(unknown.message().contains("unknown or has expired"), unknown.message());
				assertFalse(adapter.complete("fake", null, "code-1", null, null).ok());

				// Provider mismatch: the state was minted for fake.
				String state = query(RT.getIn(ok(run(engine, "v/ops/oauth/connect",
					Maps.of("provider", "fake"), alice)), "url").toString()).get("state");
				OAuthAdapter.Completion mismatch = adapter.complete("other", state, "code-1", null, null);
				assertFalse(mismatch.ok());
				assertTrue(mismatch.message().contains("another provider"), mismatch.message());

				// The user declined at the provider: reported, and the return address kept.
				state = query(RT.getIn(ok(run(engine, "v/ops/oauth/connect",
					Maps.of("provider", "fake", "returnTo", "app://done/x"), alice)), "url").toString()).get("state");
				OAuthAdapter.Completion denied = adapter.complete("fake", state, null, "access_denied", "user said no");
				assertFalse(denied.ok());
				assertTrue(denied.message().contains("access_denied") && denied.message().contains("user said no"), denied.message());
				assertEquals("app://done/x", denied.returnTo());

				// A code the provider rejects fails with the provider's reason.
				Map<String, String> q = query(RT.getIn(ok(run(engine, "v/ops/oauth/connect",
					Maps.of("provider", "fake"), alice)), "url").toString());
				fake.expectedChallenge = q.get("code_challenge");
				OAuthAdapter.Completion rejected = adapter.complete("fake", q.get("state"), "wrong-code", null, null);
				assertFalse(rejected.ok());
				assertTrue(rejected.message().contains("invalid_grant") && rejected.message().contains("bad exchange"), rejected.message());
				assertEquals(0, connections(engine, alice).count(), "nothing stored on failure");
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
				String badReturn = failed(run(engine, "v/ops/oauth/connect",
					Maps.of("provider", "fake", "returnTo", "https://evil.example/steal"), alice));
				assertTrue(badReturn.contains("returnTo"), badReturn);
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

		IllegalArgumentException literal = assertThrows(IllegalArgumentException.class, () -> adapter.configure(
			Maps.of("providers", Maps.of("google", Maps.of("clientId", "g", "clientSecret", "plaintext"))), false));
		assertTrue(literal.getMessage().contains("s/NAME"), literal.getMessage());
		assertThrows(IllegalArgumentException.class, () -> adapter.configure(
			Maps.of("providers", Maps.of("custom", Maps.of("clientId", "c"))), false), "unknown providers need endpoints");
		assertThrows(IllegalArgumentException.class, () -> adapter.configure(
			Maps.of("providers", Maps.of("custom", Maps.of("clientId", "c",
				"authorizationEndpoint", "http://idp.example/authorize", "tokenEndpoint", "https://idp.example/token"))), false),
			"plain http is only for loopback");
		assertThrows(IllegalArgumentException.class, () -> adapter.configure(
			Maps.of("providers", Maps.of("Bad Name", Maps.of("clientId", "c"))), false));
		assertThrows(IllegalArgumentException.class, () -> adapter.configure(Maps.of("pendingTtlSecs", 5), false));

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
		assertTrue(r.body().contains("unknown or has expired"), r.body());
		assertTrue(r.headers().firstValue("Content-Type").orElse("").contains("text/html"));
	}
}
