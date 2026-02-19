package covia.adapter;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;

/**
 * Persistent MCP client session that wraps a {@link McpSyncClient} for reuse
 * across multiple tool calls to the same server.
 *
 * <p>Sessions are lazily connected on first use and auto-reconnect on failure.</p>
 */
public class McpClientSession implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(McpClientSession.class);

	private final String serverUrl;
	private final String accessToken;

	private McpSyncClient client;
	private volatile boolean connected = false;
	private volatile long lastActivity = System.currentTimeMillis();

	public McpClientSession(String serverUrl, String accessToken) {
		this.serverUrl = serverUrl;
		this.accessToken = accessToken;
	}

	/**
	 * Get a connected MCP client, creating or reconnecting as needed.
	 * @return Connected McpSyncClient
	 * @throws Exception if connection fails
	 */
	public synchronized McpSyncClient getClient() throws Exception {
		if (client != null && connected) {
			lastActivity = System.currentTimeMillis();
			return client;
		}
		// Close any stale client
		closeQuietly();
		return doConnect();
	}

	private McpSyncClient doConnect() throws Exception {
		String mcpUrl = serverUrl.endsWith("/mcp") ? serverUrl : serverUrl + "/mcp";
		McpClientTransport transport = HttpClientStreamableHttpTransport.builder(mcpUrl)
				.customizeRequest(b -> {
					if (accessToken != null && !accessToken.isEmpty()) {
						b.header("Authorization", "Bearer " + accessToken);
					}
				})
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
	public synchronized void invalidate() {
		connected = false;
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
	public synchronized void close() {
		closeQuietly();
		log.debug("MCP client session closed for {}", serverUrl);
	}
}
