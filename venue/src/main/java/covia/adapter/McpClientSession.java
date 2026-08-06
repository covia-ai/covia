package covia.adapter;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.spec.McpClientTransport;

/**
 * Persistent MCP client session that wraps a {@link McpSyncClient} for reuse
 * across multiple tool calls to the same server.
 *
 * <p>Sessions are lazily connected on first use and auto-reconnect on failure.
 * Uses {@link ReentrantLock} instead of {@code synchronized} to avoid pinning
 * virtual threads during network I/O (connection handshake).</p>
 */
public class McpClientSession implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(McpClientSession.class);

	private final String serverUrl;
	private final String endpointUrl;
	private final String accessToken;
	private final ReentrantLock lock = new ReentrantLock();

	private McpSyncClient client;
	private volatile boolean connected = false;
	private volatile long lastActivity = System.currentTimeMillis();

	public McpClientSession(String serverUrl, String accessToken) {
		this.serverUrl = serverUrl;
		this.endpointUrl = endpointUrl(serverUrl);
		this.accessToken = accessToken;
	}

	/**
	 * Normalises a configured MCP server URL to its streamable-HTTP endpoint.
	 * Base URLs imply {@code /mcp}; an explicit endpoint is retained. Trailing
	 * slashes are ignored and query parameters are preserved.
	 */
	static String endpointUrl(String serverUrl) {
		if (serverUrl == null || serverUrl.isBlank()) {
			throw new IllegalArgumentException("MCP server URL is required");
		}

		final URI uri;
		try {
			uri = new URI(serverUrl.trim());
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Invalid MCP server URL '" + serverUrl + "': "
				+ e.getMessage(), e);
		}

		String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
				|| uri.getHost() == null) {
			throw new IllegalArgumentException("MCP server must be an HTTP(S) URL, not '"
				+ serverUrl + "'. Resolve a venue DID to its HTTP URL before calling this tool");
		}
		if (uri.getFragment() != null) {
			throw new IllegalArgumentException("MCP server URL must not contain a fragment: " + serverUrl);
		}

		String path = uri.getPath();
		if (path == null || path.isEmpty()) path = "/";
		while (path.length() > 1 && path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		if (!path.toLowerCase(Locale.ROOT).endsWith("/mcp")) {
			path = path.equals("/") ? "/mcp" : path + "/mcp";
		}

		try {
			return new URI(scheme.toLowerCase(Locale.ROOT), uri.getUserInfo(), uri.getHost(),
				uri.getPort(), path, uri.getQuery(), null).toString();
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Invalid MCP server URL '" + serverUrl + "': "
				+ e.getMessage(), e);
		}
	}

	/**
	 * Get a connected MCP client, creating or reconnecting as needed.
	 * @return Connected McpSyncClient
	 * @throws Exception if connection fails
	 */
	public McpSyncClient getClient() throws Exception {
		lock.lock();
		try {
			if (client != null && connected) {
				lastActivity = System.currentTimeMillis();
				return client;
			}
			closeQuietly();
			return doConnect();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Per-request customizer that attaches {@code Authorization: Bearer <token>}
	 * to every outbound MCP HTTP request when a non-blank access token is
	 * configured, and leaves the request untouched otherwise.
	 *
	 * <p>mcp 2.0 replaced {@code customizeRequest(Consumer<HttpRequest.Builder>)}
	 * with {@link McpSyncHttpClientRequestCustomizer}, invoked per request.
	 * Factored out (package-private) so the auth contract can be unit-tested
	 * without opening a network connection — see {@code McpClientSessionTest}.</p>
	 *
	 * @param accessToken bearer token to attach; null/blank means no auth header
	 * @return a request customizer applying the above rule
	 */
	static McpSyncHttpClientRequestCustomizer bearerAuthCustomizer(String accessToken) {
		return (builder, method, endpoint, body, context) -> {
			if (accessToken != null && !accessToken.isEmpty()) {
				builder.header("Authorization", "Bearer " + accessToken);
			}
		};
	}

	private McpSyncClient doConnect() throws Exception {
		McpClientTransport transport = HttpClientStreamableHttpTransport.builder(endpointUrl)
				.httpRequestCustomizer(bearerAuthCustomizer(accessToken))
				.build();
		client = McpClient.sync(transport)
				.requestTimeout(Duration.ofSeconds(10))
				.build();
		client.initialize();
		connected = true;
		lastActivity = System.currentTimeMillis();
		log.debug("MCP client session connected to {}", serverUrl);
		return client;
	}

	/**
	 * Mark this session as disconnected (e.g. after an error), so next
	 * {@link #getClient()} will reconnect.
	 */
	public void invalidate() {
		lock.lock();
		try {
			connected = false;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Get time of last activity in millis since epoch.
	 */
	public long getLastActivity() {
		return lastActivity;
	}

	private void closeQuietly() {
		if (client != null) {
			try {
				client.close();
			} catch (Exception e) {
				log.debug("Error closing MCP client session", e);
			}
			client = null;
			connected = false;
		}
	}

	@Override
	public void close() {
		lock.lock();
		try {
			closeQuietly();
			log.debug("MCP client session closed for {}", serverUrl);
		} finally {
			lock.unlock();
		}
	}
}
