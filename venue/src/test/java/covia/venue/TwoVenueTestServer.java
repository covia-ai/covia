package covia.venue;

import java.net.URI;

import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.adapter.HTTPAdapter;
import covia.api.Fields;
import covia.grid.client.VenueHTTP;
import covia.venue.server.VenueServer;

/**
 * Two shared {@link VenueServer} instances on ephemeral ports for tests
 * that exercise true cross-venue federation (venue A invoking ops on
 * venue B over HTTP).
 *
 * <p>Each venue uses a distinct hex seed → distinct DID, so federation
 * tests can verify identity isolation, ownership semantics, and
 * cross-issuer UCAN flows. Both venues enable public auth by default;
 * tests that need auth-gated paths should construct their own
 * {@link VenueServer} via {@link VenueServer#launch}.</p>
 *
 * <p><b>Lifetime:</b> static-init lazy. Both servers close on JVM exit
 * via shutdown hook so Jetty/Javalin worker threads don't outlive the
 * test session.</p>
 *
 * <p>For single-venue tests, prefer {@link TestServer} (one shared
 * venue) or {@link TestEngine#ENGINE} (no HTTP layer needed).</p>
 */
public class TwoVenueTestServer {

	// Distinct 32-byte seeds → distinct ed25519 keypairs → distinct DIDs.
	// Hard-coded so DIDs are stable across runs (helps with debugging).
	private static final String SEED_A =
		"0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
	private static final String SEED_B =
		"2122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f40";

	public static final VenueServer SERVER_A;
	public static final VenueServer SERVER_B;
	public static final Engine ENGINE_A;
	public static final Engine ENGINE_B;
	public static final int PORT_A;
	public static final int PORT_B;
	public static final String BASE_URL_A;
	public static final String BASE_URL_B;
	public static final String DID_A;
	public static final String DID_B;
	public static final VenueHTTP COVIA_A;
	public static final VenueHTTP COVIA_B;

	static {
		SERVER_A = launchVenue(SEED_A);
		SERVER_B = launchVenue(SEED_B);

		ENGINE_A = SERVER_A.getEngine();
		ENGINE_B = SERVER_B.getEngine();
		PORT_A = SERVER_A.port();
		PORT_B = SERVER_B.port();
		BASE_URL_A = "http://localhost:" + PORT_A;
		BASE_URL_B = "http://localhost:" + PORT_B;
		DID_A = ENGINE_A.getDIDString().toString();
		DID_B = ENGINE_B.getDIDString().toString();

		// HTTPAdapter SSRF allowlist — needed only by http:get/http:post,
		// not by grid:* federation (VenueHTTP makes its own requests).
		// Allow both forms because some libs canonicalise differently.
		HTTPAdapter httpA = (HTTPAdapter) ENGINE_A.getAdapter("http");
		HTTPAdapter httpB = (HTTPAdapter) ENGINE_B.getAdapter("http");
		httpA.addAllowedHost("localhost");
		httpA.addAllowedHost("127.0.0.1");
		httpB.addAllowedHost("localhost");
		httpB.addAllowedHost("127.0.0.1");

		COVIA_A = VenueHTTP.create(URI.create(BASE_URL_A));
		COVIA_B = VenueHTTP.create(URI.create(BASE_URL_B));
		COVIA_A.setTimeout(5000);
		COVIA_B.setTimeout(5000);

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try { SERVER_A.close(); } catch (Exception ignored) {}
			try { SERVER_B.close(); } catch (Exception ignored) {}
		}, "two-venue-test-server-shutdown"));
	}

	private static VenueServer launchVenue(String seedHex) {
		return VenueServer.launch(Maps.of(
			Strings.create("port"), 0, // ephemeral
			Strings.create("seed"), Strings.create(seedHex),
			Fields.MCP, Maps.of(),
			Fields.A2A, Maps.of(),
			Config.AUTH, Maps.of(
				Config.PUBLIC, Maps.of(Config.ENABLED, true)
			)
		));
	}
}
