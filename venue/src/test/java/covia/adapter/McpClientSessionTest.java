package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;

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
}
