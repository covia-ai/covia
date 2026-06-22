package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.util.JSON;
import covia.grid.client.VenueHTTP;
import covia.venue.server.VenueServer;

/**
 * #150 — retrieve assets by lattice address, not just a hex hash.
 *
 * <p>{@code GET /api/v1/assets/<ref>} accepts any lattice address ({@code a/<hash>},
 * {@code w/…}, {@code o/…}, a bare hash) and resolves it the same way
 * {@code invoke} resolves operation references, returning the resolved asset's
 * canonical metadata plus its content-addressed id (in the ETag). The bare-hash
 * URL stays byte-identical, so existing hash fetches keep working.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
public class CoviaAssetRefTest {

	private static final String META =
		"{\"name\":\"Ref Test Asset\",\"description\":\"by-address fetch\"}";

	private VenueServer server;
	private String base;
	private VenueHTTP client;
	private final HttpClient http = HttpClient.newHttpClient();

	@BeforeAll
	public void setup() {
		server = VenueServer.launch(Maps.of(
			Strings.create("port"), 0,
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(
				Config.ENABLED, true, Config.CAPS, Strings.create("unrestricted")))));
		base = "http://localhost:" + server.port();
		client = VenueHTTP.create(URI.create(base));
		client.setTimeout(5000);
	}

	@AfterAll
	public void teardown() {
		if (server != null) try { server.close(); } catch (Exception ignored) {}
	}

	private HttpResponse<String> get(String path) throws Exception {
		return http.send(
			HttpRequest.newBuilder().uri(new URI(base + path)).GET().build(),
			HttpResponse.BodyHandlers.ofString());
	}

	private static String etagHash(HttpResponse<String> r) {
		return r.headers().firstValue("ETag").orElse("");
	}

	@Test
	public void bareHashStillWorks() throws Exception {
		Hash h = client.addAsset(META).join();
		HttpResponse<String> r = get("/api/v1/assets/" + h.toHexString());
		assertEquals(200, r.statusCode());
		assertTrue(r.body().contains("Ref Test Asset"));
		assertTrue(etagHash(r).contains(h.toHexString()),
			"ETag must carry the resolved content-addressed id");
	}

	@Test
	public void aHashAddressWorks() throws Exception {
		Hash h = client.addAsset(META).join();
		HttpResponse<String> r = get("/api/v1/assets/a/" + h.toHexString());
		assertEquals(200, r.statusCode());
		assertTrue(r.body().contains("Ref Test Asset"));
		assertTrue(etagHash(r).contains(h.toHexString()));
	}

	@Test
	public void workspacePathResolvesToPinnedAsset() throws Exception {
		Hash h = client.addAsset(META).join();
		// Use the venue's canonical metadata (as the hash route serves it) so the
		// value written to the workspace path hashes back to the same id.
		ACell canonicalMeta = JSON.parse(get("/api/v1/assets/" + h.toHexString()).body());
		client.invokeAndWait(Strings.create("v/ops/covia/write"), Maps.of(
			Strings.create("path"), Strings.create("w/my-assets/foo"),
			Strings.create("value"), canonicalMeta));

		HttpResponse<String> r = get("/api/v1/assets/w/my-assets/foo");
		assertEquals(200, r.statusCode());
		assertTrue(r.body().contains("Ref Test Asset"));
		assertTrue(etagHash(r).contains(h.toHexString()),
			"a mutable-path fetch must resolve to the pinned asset's content-addressed id");
	}

	@Test
	public void contentRouteTakesPrecedenceOverWildcard() throws Exception {
		// assets/<hash>/content must reach getContent — NOT getAsset with
		// id="<hash>/content". The asset is content-less, so getContent returns
		// its own specific 404 ("does not specify any content"), whereas the
		// greedy wildcard would have said "Asset not found".
		Hash h = client.addAsset(META).join();
		HttpResponse<String> r = get("/api/v1/assets/" + h.toHexString() + "/content");
		assertEquals(404, r.statusCode());
		assertTrue(r.body().contains("does not specify any content"),
			"the /content route must take precedence over the <id> wildcard, got: " + r.body());
	}

	@Test
	public void unknownReferenceIs404() throws Exception {
		HttpResponse<String> r = get("/api/v1/assets/w/does/not/exist");
		assertEquals(404, r.statusCode());
	}
}
