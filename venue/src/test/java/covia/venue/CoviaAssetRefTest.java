package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.data.ACell;
import convex.core.crypto.AKeyPair;
import convex.auth.ucan.UCAN;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.util.JSON;
import covia.grid.Asset;
import covia.grid.client.VenueHTTP;

/**
 * #150 — retrieve assets by lattice address, not just a hex hash.
 *
 * <p>{@code GET /api/v1/assets/<ref>} accepts any lattice address ({@code a/<hash>},
 * {@code w/…}, {@code o/…}, a bare hash) and resolves it the same way
 * {@code invoke} resolves operation references, returning the resolved asset's
 * canonical metadata plus its content-addressed id (in the ETag). The bare-hash
 * URL stays byte-identical, so existing hash fetches keep working.</p>
 *
 * <p>Uses the shared {@link TestServer} (unrestricted public) rather than its own
 * venue — separate venues are reserved for tests that genuinely need isolated
 * config or lifecycle, to keep the concurrent venue count (and thus HTTP
 * connector load) down under parallel execution.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
public class CoviaAssetRefTest {

	private static final String META =
		"{\"name\":\"Ref Test Asset\",\"description\":\"by-address fetch\"}";

	private final String base = TestServer.BASE_URL;
	private final VenueHTTP client = TestServer.COVIA;
	private final HttpClient http = HttpClient.newHttpClient();

	private HttpResponse<String> get(String path) throws Exception {
		return http.send(
			HttpRequest.newBuilder().uri(new URI(base + path)).GET().build(),
			HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path, String bearer) throws Exception {
		return http.send(
			HttpRequest.newBuilder().uri(new URI(base + path))
				.header("Authorization", "Bearer " + bearer).GET().build(),
			HttpResponse.BodyHandlers.ofString());
	}

	private static String identityToken(AKeyPair keyPair) {
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		UCAN token = UCAN.create(keyPair, TestServer.ENGINE.getAccountKey(), exp,
			Vectors.empty(), Vectors.empty());
		return token.toJWT(keyPair).toString();
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
			Strings.create("path"), Strings.create("w/asset-ref-test/foo"),
			Strings.create("value"), canonicalMeta));

		HttpResponse<String> r = get("/api/v1/assets/w/asset-ref-test/foo");
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
	public void bareHashContentIsScopedToCurrentUsersAssetStore() throws Exception {
		AKeyPair aliceKey = AKeyPair.generate();
		AKeyPair bobKey = AKeyPair.generate();
		var aliceDID = UCAN.toDIDKey(aliceKey.getAccountKey());
		String metadata = """
			{"name":"Private content","content":{"inline":"alice only","contentType":"text/plain"}}
			""";

		Hash id = TestServer.ENGINE.storeUserAsset(
			Strings.create(metadata), null, RequestContext.of(aliceDID));
		// A matching venue-catalog record makes this a strong regression test:
		// the old caller-aware lookup silently fell through to this shared store.
		assertEquals(id, TestServer.ENGINE.storeAsset(Strings.create(metadata), null));

		String path = "/api/v1/assets/" + id.toHexString();
		HttpResponse<String> aliceMeta = get(path, identityToken(aliceKey));
		assertEquals(200, aliceMeta.statusCode());
		HttpResponse<String> aliceContent = get(path + "/content", identityToken(aliceKey));
		assertEquals(200, aliceContent.statusCode());
		assertEquals("alice only", aliceContent.body());

		HttpResponse<String> bobMeta = get(path, identityToken(bobKey));
		assertEquals(404, bobMeta.statusCode(),
			"a bare hash must mean Bob's /a, not Alice's or the venue catalog");
		HttpResponse<String> bobContent = get(path + "/content", identityToken(bobKey));
		assertEquals(404, bobContent.statusCode(),
			"content lookup must not fall through to another asset namespace");
	}

	@Test
	public void unknownReferenceIs404() throws Exception {
		HttpResponse<String> r = get("/api/v1/assets/w/does/not/exist");
		assertEquals(404, r.statusCode());
	}

	@Test
	public void javaClientResolvesByLatticeAddress() throws Exception {
		// covia-core's VenueHTTP.resolveAsset(ref) passes the address straight to
		// the new endpoint. The client code is identical for any ref shape, so the
		// content-addressed forms exercise it deterministically; server-side
		// workspace-path resolution is covered by workspacePathResolvesToPinnedAsset.
		Hash h = client.addAsset(META).join();

		Asset byHash = client.resolveAsset("a/" + h.toHexString());
		assertNotNull(byHash, "client must resolve a/<hash>");
		assertEquals(h, byHash.getID(), "client must return the resolved content-addressed id");

		Asset byBareHash = client.resolveAsset(h.toHexString());
		assertNotNull(byBareHash, "client must resolve a bare hash");
		assertEquals(h, byBareHash.getID());
	}

	// ---------------------------------------------------------------- #368
	// Content by any reference: assets/content/<ref> puts the selector BEFORE
	// the ref, so the variable-length ref is always the tail wildcard and a ref
	// whose own final segment is "content" stays unambiguous.

	private static final String CONTENT_META =
		"{\"name\":\"Ref Content Asset\",\"content\":{\"inline\":\"ref content bytes\",\"contentType\":\"text/plain\"}}";

	/** Registers CONTENT_META and writes its canonical (as-served) metadata to
	 *  the given workspace path, so the path resolves to the pinned asset. */
	private Hash pinContentAssetAt(String wsPath) throws Exception {
		Hash h = client.addAsset(CONTENT_META).join();
		ACell canonicalMeta = JSON.parse(get("/api/v1/assets/" + h.toHexString()).body());
		client.invokeAndWait(Strings.create("v/ops/covia/write"), Maps.of(
			Strings.create("path"), Strings.create(wsPath),
			Strings.create("value"), canonicalMeta));
		return h;
	}

	@Test
	public void canonicalContentRouteAcceptsHashForms() throws Exception {
		Hash h = client.addAsset(CONTENT_META).join();
		String legacy = get("/api/v1/assets/" + h.toHexString() + "/content").body();
		assertEquals("ref content bytes", legacy, "legacy hash route must keep working");

		HttpResponse<String> bare = get("/api/v1/assets/content/" + h.toHexString());
		assertEquals(200, bare.statusCode());
		assertEquals(legacy, bare.body(), "canonical route must serve identical bytes for a bare hash");

		HttpResponse<String> aForm = get("/api/v1/assets/content/a/" + h.toHexString());
		assertEquals(200, aForm.statusCode());
		assertEquals(legacy, aForm.body(), "canonical route must serve identical bytes for a/<hash>");
	}

	@Test
	public void canonicalContentRouteResolvesWorkspacePath() throws Exception {
		pinContentAssetAt("w/asset-ref-test/inline-src");
		HttpResponse<String> r = get("/api/v1/assets/content/w/asset-ref-test/inline-src");
		assertEquals(200, r.statusCode());
		assertEquals("ref content bytes", r.body());
		assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"),
			"content.contentType must drive the media type");
	}

	@Test
	public void refEndingInContentServesMetadata() throws Exception {
		// /assets/w/content lands on the legacy {id}/content route with id="w";
		// a non-hash id there is really a metadata read for w/content (#368).
		Hash h = pinContentAssetAt("w/content");
		HttpResponse<String> meta = get("/api/v1/assets/w/content");
		assertEquals(200, meta.statusCode());
		assertTrue(meta.body().contains("Ref Content Asset"),
			"a ref whose final segment is 'content' must read as metadata, got: " + meta.body());
		assertTrue(etagHash(meta).contains(h.toHexString()));

		// The same ref's CONTENT is reachable only via the canonical route.
		HttpResponse<String> content = get("/api/v1/assets/content/w/content");
		assertEquals(200, content.statusCode());
		assertEquals("ref content bytes", content.body());
	}

	@Test
	public void deepRefEndingInContentServesMetadata() throws Exception {
		// A >=3-segment ref ending in "content" reaches the <id> metadata
		// wildcard directly — the canonical content route must not shadow it.
		Hash h = pinContentAssetAt("w/asset-ref-test/content");
		HttpResponse<String> meta = get("/api/v1/assets/w/asset-ref-test/content");
		assertEquals(200, meta.statusCode());
		assertTrue(meta.body().contains("Ref Content Asset"));
		assertTrue(etagHash(meta).contains(h.toHexString()));

		HttpResponse<String> content = get("/api/v1/assets/content/w/asset-ref-test/content");
		assertEquals(200, content.statusCode());
		assertEquals("ref content bytes", content.body());
	}

	@Test
	public void canonicalContentRouteUnknownRefIs404() throws Exception {
		HttpResponse<String> r = get("/api/v1/assets/content/w/does/not/exist");
		assertEquals(404, r.statusCode());
		assertTrue(r.body().contains("not found"), "unknown ref must be a clean 404, got: " + r.body());
	}
}
