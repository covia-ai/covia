package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.auth.VenueAuthenticator;
import covia.venue.server.RequestLog;
import covia.venue.server.VenueServer;

/**
 * Logging of normal operational events is the operator's choice (covia#538):
 * a venue records its audit trail and access log only when its config says
 * so, every request gets an id either way, and nothing it logs ever carries a
 * credential, a secret value or an email.
 */
@Isolated("captures the covia, AUDIT and ACCESS loggers process-wide")
public class AuditLogTest {

	private static final String[] CAPTURED = {Audit.LOGGER, RequestLog.LOGGER, "covia"};

	private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
	private final Map<String, Level> savedLevels = new java.util.HashMap<>();
	private final Map<String, Boolean> savedAdditivity = new java.util.HashMap<>();

	@BeforeEach
	void capture() {
		captured.start();
		for (String name : CAPTURED) {
			Logger l = (Logger) LoggerFactory.getLogger(name);
			savedLevels.put(name, l.getLevel());
			savedAdditivity.put(name, l.isAdditive());
			// Everything, down to DEBUG, into the list only — none of it to the console.
			l.setLevel(Level.DEBUG);
			l.setAdditive(false);
			l.addAppender(captured);
		}
	}

	@AfterEach
	void release() {
		for (String name : CAPTURED) {
			Logger l = (Logger) LoggerFactory.getLogger(name);
			l.detachAppender(captured);
			l.setLevel(savedLevels.get(name));
			l.setAdditive(savedAdditivity.get(name));
		}
		captured.stop();
	}

	private static VenueServer launch(String hostname, boolean logging) {
		return VenueServer.launch(Maps.of(
			Config.PORT, 0,
			Config.HOSTNAME, Strings.create(hostname),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.RATE_LIMIT, Maps.of(Config.ENABLED, false),
			Config.LOGGING, logging
				? Maps.of(Config.AUDIT, true, Config.ACCESS, true)
				: Maps.empty()));
	}

	private static String token(AKeyPair key, AString venueDID) {
		String did = UCAN.toDIDKey(key.getAccountKey()).toString();
		long now = System.currentTimeMillis() / 1000;
		return JWT.signPublic(Maps.of(
			JWT.SUB, did, JWT.ISS, did, JWT.AUD, venueDID,
			JWT.IAT, CVMLong.create(now), JWT.EXP, CVMLong.create(now + 300)), key).toString();
	}

	private static HttpResponse<String> get(VenueServer server, String pathAndQuery, String bearer,
			String requestId) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(
				URI.create("http://127.0.0.1:" + server.port() + pathAndQuery))
			.timeout(Duration.ofSeconds(10)).GET();
		if (bearer != null) b.header("Authorization", "Bearer " + bearer);
		if (requestId != null) b.header(RequestLog.HEADER, requestId);
		return TestHTTP.CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static Map<String, String> kv(ILoggingEvent e) {
		List<KeyValuePair> pairs = e.getKeyValuePairs();
		if (pairs == null) return Map.of();
		return pairs.stream().collect(Collectors.toMap(p -> p.key, p -> String.valueOf(p.value), (a, b) -> b));
	}

	/** Events on one logger from one venue. */
	private List<Map<String, String>> events(String logger, String venue) {
		List<Map<String, String>> out = new ArrayList<>();
		for (ILoggingEvent e : List.copyOf(captured.list)) {
			if (!logger.equals(e.getLoggerName())) continue;
			Map<String, String> m = kv(e);
			if (venue.equals(m.get(Audit.K_VENUE))) out.add(m);
		}
		return out;
	}

	/** Every character of everything captured: message, key-values, MDC and throwable text. */
	private String allLogText() {
		StringBuilder sb = new StringBuilder();
		for (ILoggingEvent e : List.copyOf(captured.list)) {
			sb.append(e.getFormattedMessage()).append(' ').append(kv(e)).append(' ')
				.append(e.getMDCPropertyMap()).append('\n');
			if (e.getThrowableProxy() != null) sb.append(e.getThrowableProxy().getMessage()).append('\n');
		}
		return sb.toString();
	}

	@Test
	public void offByDefaultButEveryRequestGetsAnId() throws Exception {
		String venue = "audit-off.example";
		VenueServer server = launch(venue, false);
		try {
			HttpResponse<String> r = get(server, "/api/v1/status", "not.a.jwt", null);
			assertEquals(401, r.statusCode());
			assertTrue(r.headers().firstValue(RequestLog.HEADER).isPresent(), "a request id is returned regardless");
			assertTrue(events(Audit.LOGGER, venue).isEmpty(), "no audit trail unless the operator asks for one");
			assertTrue(events(RequestLog.LOGGER, venue).isEmpty(), "no access log unless the operator asks for one");
		} finally {
			server.close();
		}
	}

	@Test
	public void auditAndAccessWhenEnabled() throws Exception {
		String venue = "audit-on.example";
		VenueServer server = launch(venue, true);
		try {
			AString venueDID = server.getEngine().getDIDString();

			// A refused credential: the reason from the authenticator, the client address.
			HttpResponse<String> refused = get(server, "/api/v1/status?secret=querystring-value", "not.a.jwt", null);
			assertEquals(401, refused.statusCode());
			String refusedId = refused.headers().firstValue(RequestLog.HEADER).orElseThrow();
			Map<String, String> failure = events(Audit.LOGGER, venue).stream()
				.filter(m -> Audit.AUTH_FAILURE.equals(m.get(Audit.K_EVENT))).findFirst().orElseThrow();
			assertTrue(failure.get(Audit.K_REASON).startsWith(VenueAuthenticator.TOKEN_REJECTED_PREFIX), failure.toString());
			assertEquals("127.0.0.1", failure.get(Audit.K_IP));
			assertEquals(refusedId, failure.get(Audit.K_REQUEST_ID), "the audit line carries the id the client was given");

			// The access line: path without the query string, status, the same id.
			Map<String, String> line = events(RequestLog.LOGGER, venue).stream()
				.filter(m -> refusedId.equals(m.get(RequestLog.MDC_KEY))).findFirst().orElseThrow();
			assertEquals("/api/v1/status", line.get("path"));
			assertEquals("401", line.get("status"));
			assertNotNull(line.get("ms"));

			// An accepted credential, then a secret written with it.
			AKeyPair key = AKeyPair.generate();
			String jwt = token(key, venueDID);
			String did = UCAN.toDIDKey(key.getAccountKey()).toString();
			VenueHTTP client = VenueHTTP.create(URI.create("http://127.0.0.1:" + server.port()), VenueAuth.bearer(jwt));
			client.setTimeout(5000);
			String secretValue = "sk-audit-test-" + System.nanoTime();
			Job set = client.invokeAndWait(Strings.create("v/ops/secret/set"),
				Maps.of(Fields.NAME, "AUDITED", Strings.create("value"), secretValue));
			assertEquals(Status.COMPLETE, set.getStatus(), String.valueOf(set.getErrorMessage()));

			assertTrue(events(Audit.LOGGER, venue).stream().anyMatch(m -> Audit.AUTH_SUCCESS.equals(m.get(Audit.K_EVENT))
				&& did.equals(m.get(Audit.K_DID))), "the sign-in is recorded with its DID");
			Map<String, String> write = events(Audit.LOGGER, venue).stream()
				.filter(m -> Audit.SECRET_WRITE.equals(m.get(Audit.K_EVENT))).findFirst().orElseThrow();
			assertEquals("AUDITED", write.get(Audit.K_NAME));

			// Redaction: nothing the venue logged, at any level, holds a credential,
			// a secret value, or a query string.
			String everything = allLogText();
			assertFalse(everything.contains(jwt), "no bearer token in any log line");
			assertFalse(everything.contains(secretValue), "no secret value in any log line");
			assertFalse(everything.contains("querystring-value"), "no query string in any log line");
		} finally {
			server.close();
		}
	}

	@Test
	public void inboundRequestIdOnlyFromATrustedProxyAndOnlyWhenPlain() throws Exception {
		VenueServer direct = launch("rid-direct.example", false);
		try {
			String id = get(direct, "/api/v1/status", null, "client-chosen").headers()
				.firstValue(RequestLog.HEADER).orElseThrow();
			assertNotEquals("client-chosen", id, "a client cannot choose the id without a trusted proxy");
		} finally {
			direct.close();
		}
		VenueServer proxied = VenueServer.launch(Maps.of(
			Config.PORT, 0, Config.HOSTNAME, Strings.create("rid-proxied.example"),
			Config.TRUSTED_PROXIES, Vectors.of(Strings.create(TrustedProxies.LOOPBACK))));
		try {
			assertEquals("edge-1234", get(proxied, "/api/v1/status", null, "edge-1234").headers()
				.firstValue(RequestLog.HEADER).orElseThrow(), "the trusted proxy's id is kept");
			assertNotEquals("bad id\nINFO forged", get(proxied, "/api/v1/status", null, "bad id INFO forged").headers()
				.firstValue(RequestLog.HEADER).orElseThrow(), "an id that is not a plain token is replaced");
		} finally {
			proxied.close();
		}
	}

	@Test
	public void loggingConfigIsValidated() {
		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Config.STRICT_CONFIG, true, Config.LOGGING, Maps.of(Strings.create("everything"), true))));
		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Config.LOGGING, Maps.of(Config.AUDIT, Strings.create("yes")))));
		assertThrows(IllegalArgumentException.class, () -> Config.validateServerConfig(Maps.of(
			Config.OPERATIONS, Maps.of(Config.LOG_FORMAT, Strings.create("xml")),
			Config.VENUES, Vectors.of(Maps.empty()))));
		assertFalse(new Config(Maps.empty()).isAuditLogging());
		assertFalse(new Config(Maps.empty()).isAccessLogging());
	}
}
