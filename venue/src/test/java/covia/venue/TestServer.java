package covia.venue;

import java.net.URI;

import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.adapter.HTTPAdapter;
import covia.grid.client.VenueHTTP;
import covia.venue.server.VenueServer;

public class TestServer {

	public static final int PORT;
	public static final String BASE_URL;

	public static final VenueServer SERVER;
	public static final Engine ENGINE;
	public static final VenueHTTP COVIA;

	static {
		SERVER=VenueServer.launch(Maps.of(
				Strings.create("port"),0, // ephemeral port
				Fields.MCP,Maps.of(
					Strings.create("includePathPrefixes"),
					convex.core.data.Vectors.of(Strings.create("v/ops/"), Strings.create("v/test/ops/")),
					Strings.create("includeAdapters"),
					convex.core.data.Vectors.of(Strings.create("*"))
				),
				Fields.A2A,Maps.of(
					Strings.create("defaultChatOp"), Strings.create("v/test/ops/chat")
				),
				Config.WEBDAV,Maps.of(Config.ENABLED,true),
				Config.AUTH,Maps.of(
					Config.PUBLIC,Maps.of(Config.ENABLED,true)
				)));
		PORT=SERVER.port();
		BASE_URL="http://localhost:"+PORT;
		ENGINE=SERVER.getEngine();

		// Allow localhost for HTTP adapter tests (SSRF protection blocks it by default)
		((HTTPAdapter) ENGINE.getAdapter("http")).addAllowedHost("localhost");

		COVIA = VenueHTTP.create(URI.create(BASE_URL));
		// Tests should not be waiting 30s on background polls. Anything
		// taking longer than this either has a bug or needs an explicit
		// invokeAndWait(opID, input, timeoutMs) override.
		COVIA.setTimeout(5000);

		// Close the shared server cleanly on JVM exit so Jetty/Javalin
		// non-daemon worker threads don't outlive the test session.
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try { SERVER.close(); } catch (Exception ignored) {}
		}, "test-server-shutdown"));
	}
}
