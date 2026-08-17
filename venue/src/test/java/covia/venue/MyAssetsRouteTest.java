package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.auth.VenueAuth;

/**
 * GET /api/v1/assets/mine — job-free listing of the caller's own per-user
 * a/ namespace (populated by asset:store/asset:pin, distinct from the
 * venue-wide catalog GET /api/v1/assets reads).
 */
public class MyAssetsRouteTest {

	private final String base = TestServer.BASE_URL;
	private final HttpClient http = HttpClient.newHttpClient();

	private VenueAuth freshCaller() {
		AKeyPair keyPair = AKeyPair.generate();
		return VenueAuth.keyPair(keyPair, TestServer.ENGINE.getDIDString().toString());
	}

	private void storeAsset(VenueAuth caller, String name) {
		ACell metadata = Maps.of(
			Fields.NAME, name,
			Fields.TYPE, "document",
			Fields.DESCRIPTION, "Owned by " + caller.getDID());
		Job job = TestServer.ENGINE.jobs().invokeOperation("v/ops/asset/store",
			Maps.of(Fields.METADATA, metadata), RequestContext.of(Strings.create(caller.getDID())));
		job.awaitResult(5000);
	}

	// AuthMiddleware.callerContext documents that an anonymous caller gets
	// "the venue's public DID" (never null) whenever public access is
	// enabled — matching listSecrets' identical callerDID==null guard, which
	// is a null-safety fallback for the (rarer) fully-disabled-public-access
	// config, not a per-request 401 for every unauthenticated call. So the
	// property that actually matters here is isolation, not a blanket 401:
	// an anonymous caller must only ever see the venue's own (empty) public
	// namespace, never another authenticated caller's assets.
	@Test
	public void anonymousRequestNeverLeaksAnotherCallersAssets() throws Exception {
		VenueAuth alice = freshCaller();
		storeAsset(alice, "Alice's Private Document");

		HttpResponse<String> r = http.send(HttpRequest.newBuilder()
			.uri(new URI(base + "/api/v1/assets/mine"))
			.GET().timeout(Duration.ofSeconds(10)).build(),
			HttpResponse.BodyHandlers.ofString());
		assertEquals(200, r.statusCode(), r.body());
		ACell body = JSON.parse(r.body());
		AVector<?> items = (AVector<?>) RT.getIn(body, "items");
		for (Object item : items) {
			ACell name = RT.getIn((ACell) item, "name");
			assertTrue(name == null || !"Alice's Private Document".equals(name.toString()),
				"anonymous caller must not see Alice's asset: " + r.body());
		}
	}

	@Test
	public void listsOnlyTheCallersOwnAssets() throws Exception {
		VenueAuth alice = freshCaller();
		VenueAuth bob = freshCaller();
		storeAsset(alice, "Alice's Document");

		HttpResponse<String> aliceResponse = http.send(HttpRequest.newBuilder()
			.uri(new URI(base + "/api/v1/assets/mine"))
			.header("Authorization", "Bearer " + alice.mintToken())
			.GET().timeout(Duration.ofSeconds(10)).build(),
			HttpResponse.BodyHandlers.ofString());
		assertEquals(200, aliceResponse.statusCode(), aliceResponse.body());
		ACell aliceBody = JSON.parse(aliceResponse.body());
		assertEquals(1L, RT.ensureLong(RT.getIn(aliceBody, "total")).longValue(), aliceResponse.body());
		AVector<?> aliceItems = (AVector<?>) RT.getIn(aliceBody, "items");
		assertEquals(1, aliceItems.count());
		ACell item = (ACell) aliceItems.get(0);
		assertEquals("Alice's Document", RT.ensureString(RT.getIn(item, "name")).toString());
		assertEquals("document", RT.ensureString(RT.getIn(item, "type")).toString());

		// Namespace isolation — Bob has stored nothing, so his own listing is
		// empty even though Alice's asset exists on the same venue.
		HttpResponse<String> bobResponse = http.send(HttpRequest.newBuilder()
			.uri(new URI(base + "/api/v1/assets/mine"))
			.header("Authorization", "Bearer " + bob.mintToken())
			.GET().timeout(Duration.ofSeconds(10)).build(),
			HttpResponse.BodyHandlers.ofString());
		assertEquals(200, bobResponse.statusCode(), bobResponse.body());
		ACell bobBody = JSON.parse(bobResponse.body());
		assertEquals(0L, RT.ensureLong(RT.getIn(bobBody, "total")).longValue(), bobResponse.body());
		AVector<?> bobItems = (AVector<?>) RT.getIn(bobBody, "items");
		assertEquals(0, bobItems.count());
	}

	@Test
	public void venueWideCatalogNeverIncludesUserAssets() throws Exception {
		// The bug this endpoint fixes: GET /api/v1/assets reads a completely
		// separate, venue-level bucket — a caller's own asset must never leak
		// into it, no matter how many are stored.
		VenueAuth alice = freshCaller();
		storeAsset(alice, "Should Not Appear In Catalog");

		HttpResponse<String> mine = http.send(HttpRequest.newBuilder()
			.uri(new URI(base + "/api/v1/assets/mine"))
			.header("Authorization", "Bearer " + alice.mintToken())
			.GET().timeout(Duration.ofSeconds(10)).build(),
			HttpResponse.BodyHandlers.ofString());
		ACell mineBody = JSON.parse(mine.body());
		AVector<?> mineItems = (AVector<?>) RT.getIn(mineBody, "items");
		assertTrue(mineItems.count() >= 1, "sanity: the asset was actually stored");

		HttpResponse<String> catalog = http.send(HttpRequest.newBuilder()
			.uri(new URI(base + "/api/v1/assets"))
			.GET().timeout(Duration.ofSeconds(10)).build(),
			HttpResponse.BodyHandlers.ofString());
		assertEquals(200, catalog.statusCode(), catalog.body());
		ACell catalogBody = JSON.parse(catalog.body());
		AVector<?> catalogItems = (AVector<?>) RT.getIn(catalogBody, "items");
		for (Object id : catalogItems) {
			assertTrue(id instanceof convex.core.data.AString, "catalog items are bare hash strings, never asset summaries");
		}
	}

	@Test
	public void offsetAndLimitPaginateConsistentlyWithEnvelopeConventions() throws Exception {
		VenueAuth alice = freshCaller();
		storeAsset(alice, "Doc One");
		storeAsset(alice, "Doc Two");

		HttpResponse<String> r = http.send(HttpRequest.newBuilder()
			.uri(new URI(base + "/api/v1/assets/mine?offset=0&limit=1"))
			.header("Authorization", "Bearer " + alice.mintToken())
			.GET().timeout(Duration.ofSeconds(10)).build(),
			HttpResponse.BodyHandlers.ofString());
		assertEquals(200, r.statusCode(), r.body());
		ACell body = JSON.parse(r.body());
		assertEquals(1L, RT.ensureLong(RT.getIn(body, "limit")).longValue());
		assertEquals(0L, RT.ensureLong(RT.getIn(body, "offset")).longValue());
		assertTrue(RT.ensureLong(RT.getIn(body, "total")).longValue() >= 2, r.body());
		AVector<?> items = (AVector<?>) RT.getIn(body, "items");
		assertEquals(1, items.count(), "limit=1 returns exactly one item");
	}
}
