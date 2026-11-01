package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import covia.exception.InvalidInputException;
import covia.exception.ResponseException;
import covia.exception.UnavailableException;
import io.modelcontextprotocol.client.transport.HttpRequestSnapshot;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;

/**
 * Unit tests for {@link McpClientSession#bearerAuthCustomizer} — pure function
 * tests, no network. They pin the outbound-auth contract: the customizer
 * attaches {@code Authorization: Bearer <token>} iff a non-blank token is
 * configured. This is the client-side half of MCP auth; the venue's server-side
 * bearer handling is covered by OAuthTest / UCANBearerTransportTest.
 */
public class McpClientSessionTest {

	private static final URI ENDPOINT = URI.create("http://venue.example/mcp");

	/** Run the customizer over a fresh request builder and return the built request. */
	private static HttpRequest customize(String token) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(ENDPOINT);
		McpSyncHttpClientRequestCustomizer customizer = McpClientSession.bearerAuthCustomizer(token);
		customizer.customize(builder, "POST", ENDPOINT, "{}", McpTransportContext.EMPTY);
		return builder.build();
	}

	@Test
	public void testNormalisesMcpEndpointUrls() {
		assertEquals("http://venue.example/mcp",
			McpClientSession.endpointUrl("http://venue.example"));
		assertEquals("http://venue.example/",
			McpClientSession.endpointUrl("http://venue.example/"));
		assertEquals("https://venue.example/mcp",
			McpClientSession.endpointUrl("https://venue.example/mcp/"));
		assertEquals("https://venue.example/api/mcp?tenant=alice",
			McpClientSession.endpointUrl("https://venue.example/api/mcp/?tenant=alice"));
		assertEquals("https://venue.example/gateway",
			McpClientSession.endpointUrl("https://venue.example/gateway/"));
		assertEquals("https://mcp.zapier.com/api/v1/connect",
			McpClientSession.endpointUrl("https://mcp.zapier.com/api/v1/connect"));
		assertEquals("https://mcp.stripe.com/",
			McpClientSession.endpointUrl("https://mcp.stripe.com/"));
	}

	@Test
	public void testRejectsNonHttpServerWithActionableError() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
			() -> McpClientSession.endpointUrl("did:web:venue.example"));
		assertTrue(error.getMessage().contains("HTTP(S) URL"), error.getMessage());
		assertTrue(error.getMessage().contains("Resolve a venue DID"), error.getMessage());
	}

	@Test
	public void testAttachesBearerWhenTokenPresent() {
		Optional<String> auth = customize("tok-123").headers().firstValue("Authorization");
		assertTrue(auth.isPresent(), "Authorization header should be set when a token is configured");
		assertEquals("Bearer tok-123", auth.get());
	}

	@Test
	public void testNoAuthHeaderWhenTokenNull() {
		assertFalse(customize(null).headers().firstValue("Authorization").isPresent(),
			"No Authorization header should be added when token is null");
	}

	@Test
	public void testNoAuthHeaderWhenTokenEmpty() {
		assertFalse(customize("").headers().firstValue("Authorization").isPresent(),
			"No Authorization header should be added when token is blank");
	}

	// ========== Failures are typed, never in the server's words ==========

	/** Server text a failure carries, which no message may quote. */
	private static final String SERVER_TEXT = "SERVER-SAYS-tok-leak-123";
	/** A server url carrying credentials, which no message may quote either. */
	private static final String URL = "https://user:pw@mcp.example.com/api/s/secret-path/mcp?key=k";
	private static final String ORIGIN = "https://mcp.example.com";

	private static <T extends Throwable> T assertMapped(Class<T> type, String message, RuntimeException cause) {
		RuntimeException failure = McpClientSession.failure(URL, McpClientSession.TOOLS_LIST, cause);
		T typed = assertInstanceOf(type, failure, "mapped type for " + cause);
		assertEquals(message, failure.getMessage());
		assertFalse(message.contains(SERVER_TEXT), message);
		assertFalse(message.contains("secret-path") || message.contains("key=k") || message.contains("pw"), message);
		assertSame(cause, failure.getCause(), "the client's failure stays as the cause");
		return typed;
	}

	@Test
	public void testFailuresAreTypedWithoutServerText() {
		String what = McpClientSession.TOOLS_LIST;
		// A JSON-RPC error quotes its code, never its message; the client wraps it on initialisation
		assertMapped(ResponseException.class, McpClientSession.JSON_RPC_ERROR.formatted(ORIGIN, what, -32601),
			new RuntimeException("Client failed to initialize by explicit API call",
				new McpError(new JSONRPCError(-32601, "Method not found " + SERVER_TEXT, SERVER_TEXT))));

		HttpRequestSnapshot request = new HttpRequestSnapshot(URI.create(URL), "POST",
			HttpHeaders.of(java.util.Map.of(), (a, b) -> true));
		HttpResponse.ResponseInfo unauthorised = new HttpResponse.ResponseInfo() {
			@Override public int statusCode() { return 401; }
			@Override public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
			@Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
		};
		assertMapped(ResponseException.class, McpClientSession.CREDENTIAL_REFUSED.formatted(ORIGIN, what, " (HTTP 401)"),
			new McpHttpClientTransportAuthorizationException(SERVER_TEXT, request, unauthorised));

		assertMapped(ResponseException.class, McpClientSession.BAD_ANSWER.formatted(ORIGIN, what),
			new McpTransportException("Received unexpected status code: 500, response body: " + SERVER_TEXT));
		assertMapped(ResponseException.class, McpClientSession.BAD_ANSWER.formatted(ORIGIN, what),
			new McpTransportException("Error deserializing JSON-RPC message: " + SERVER_TEXT,
				new java.io.IOException(SERVER_TEXT)));

		assertMapped(UnavailableException.class, McpClientSession.SESSION_LOST.formatted(ORIGIN, what),
			new McpTransportSessionNotFoundException(SERVER_TEXT));
		assertMapped(UnavailableException.class, McpClientSession.UNREACHABLE.formatted(ORIGIN, what),
			new RuntimeException(new java.util.concurrent.TimeoutException(SERVER_TEXT)));
		assertMapped(UnavailableException.class, McpClientSession.UNREACHABLE.formatted(ORIGIN, what),
			new RuntimeException("Client failed to initialize", new java.net.ConnectException(SERVER_TEXT)));
	}

	@Test
	public void testExpectedAndUnexpectedFailuresPassThrough() {
		InvalidInputException expected = new InvalidInputException("server is required");
		assertSame(expected, McpClientSession.failure(URL, McpClientSession.HANDSHAKE, expected));
		IllegalStateException bug = new IllegalStateException(SERVER_TEXT);
		assertSame(bug, McpClientSession.failure(URL, McpClientSession.HANDSHAKE, bug));
	}
}
