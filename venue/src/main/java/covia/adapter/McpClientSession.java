package covia.adapter;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import covia.exception.CoviaException;
import covia.exception.ResponseException;
import covia.exception.UnavailableException;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;

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

	// ========== Failures (ERRORS.md) ==========
	// A server is named by its origin only, since an MCP url often carries a
	// credential in its path or query, and never in its own words. Each takes
	// the server, then the request as one of the request names below.

	/** The request a failure names: opening the session. */
	public static final String HANDSHAKE = "the session handshake";
	/** The request a failure names: listing the server's tools. */
	public static final String TOOLS_LIST = "the tool list request";
	/** The request a failure names: calling one tool, by name. */
	public static final String TOOL_CALL = "the call to tool '%s'";

	public static final String UNREACHABLE = "Could not reach MCP server %s for %s, or it did not answer"
		+ " in time; check the server is running and retry";
	public static final String SESSION_LOST = "MCP server %s no longer holds this venue's session for %s;"
		+ " retry to open a new one";
	/** Takes the JSON-RPC error code, an integer the client requires of every error. */
	public static final String JSON_RPC_ERROR = "MCP server %s answered %s with JSON-RPC error %d";
	/** Takes a status note, " (HTTP 401)", or nothing when there is none. */
	public static final String CREDENTIAL_REFUSED = "MCP server %s refused the credential for %s%s;"
		+ " check the token or the secret it comes from";
	public static final String BAD_ANSWER = "MCP server %s answered %s with an HTTP error or a malformed"
		+ " response; check the url names the exact MCP endpoint (end it with / for a root endpoint)"
		+ " and that the server speaks a supported protocol version";

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
	 * A URL with no path implies {@code /mcp}; any explicit path is retained.
	 * A sole trailing slash therefore expresses an exact root endpoint, while
	 * trailing slashes on non-root paths are ignored. Query parameters are
	 * preserved.
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
		if (path == null || path.isEmpty()) {
			path = "/mcp";
		} else {
			while (path.length() > 1 && path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
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
	 * @throws CoviaException if the session cannot be opened ({@link #failure})
	 */
	public McpSyncClient getClient() {
		return connected(HANDSHAKE);
	}

	/**
	 * Runs one request over this session, connecting first if need be. A
	 * failure is reported as the Covia type for it ({@link #failure}) and
	 * drops the connection, so the next request reconnects.
	 *
	 * @param what the request, as a failure names it: {@link #TOOLS_LIST},
	 *        {@link #TOOL_CALL} with the tool's name
	 */
	public <T> T request(String what, Function<McpSyncClient, T> request) {
		McpSyncClient connectedClient = connected(what);
		try {
			return request.apply(connectedClient);
		} catch (RuntimeException e) {
			invalidate();
			throw failure(serverUrl, what, e);
		}
	}

	private McpSyncClient connected(String what) {
		lock.lock();
		try {
			if (client != null && connected) {
				lastActivity = System.currentTimeMillis();
				return client;
			}
			closeQuietly();
			try {
				return doConnect();
			} catch (RuntimeException e) {
				throw failure(serverUrl, what, e);
			}
		} finally {
			lock.unlock();
		}
	}

	/**
	 * What a failed request to the MCP server at {@code serverUrl} reports
	 * (ERRORS.md): a JSON-RPC error, an HTTP error or a malformed answer is
	 * {@link ResponseException}, quoting at most the JSON-RPC error code; an
	 * unreachable or silent server, or a lost session, is
	 * {@link UnavailableException}. The server's own text is never quoted.
	 * Anything else passes on as the venue fault it is.
	 *
	 * @param what the request, as the reason names it
	 */
	static RuntimeException failure(String serverUrl, String what, RuntimeException e) {
		if (e instanceof CoviaException expected) return expected;
		String origin = AAdapter.origin(serverUrl);
		String at = (origin != null) ? origin : "(no host)";
		McpError rpc = find(e, McpError.class);
		McpHttpClientTransportAuthorizationException refused =
			find(e, McpHttpClientTransportAuthorizationException.class);
		RuntimeException failure;
		if (rpc != null) {
			failure = new ResponseException(JSON_RPC_ERROR.formatted(at, what, rpc.getJsonRpcError().code()));
		} else if (refused != null) {
			String status = (refused.getResponseInfo() != null)
				? " (HTTP " + refused.getResponseInfo().statusCode() + ")" : "";
			failure = new ResponseException(CREDENTIAL_REFUSED.formatted(at, what, status));
		} else if (find(e, McpTransportSessionNotFoundException.class) != null
				|| find(e, McpTransportSessionClosedException.class) != null) {
			failure = new UnavailableException(SESSION_LOST.formatted(at, what));
		} else if (find(e, McpTransportException.class) != null) {
			failure = new ResponseException(BAD_ANSWER.formatted(at, what));
		} else if (find(e, java.io.IOException.class) != null
				|| find(e, java.util.concurrent.TimeoutException.class) != null
				|| find(e, java.nio.channels.UnresolvedAddressException.class) != null) {
			// Reactor and the client wrap these in bare RuntimeExceptions.
			failure = new UnavailableException(UNREACHABLE.formatted(at, what));
		} else {
			return e;
		}
		failure.initCause(e);
		return failure;
	}

	private static <T extends Throwable> T find(Throwable e, Class<T> type) {
		for (Throwable t = e; t != null; t = (t.getCause() != t) ? t.getCause() : null) {
			if (type.isInstance(t)) return type.cast(t);
		}
		return null;
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

	private McpSyncClient doConnect() {
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
