package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.TestServer;
import covia.venue.User;

/**
 * The job-free own-asset read API (#382): {@code GET /api/v1/assets?scope=own}
 * lists the authenticated caller's own {@code a/} assets (populated by
 * {@code asset:store} / {@code asset:pin}) without persisting a Job — distinct
 * from the default listing, which returns the venue-level asset catalog.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class OwnAssetsApiTest {

	private String jwt;
	private String callerDID;
	private VenueHTTP client;
	private String storedName;

	@BeforeAll
	public void setup() throws Exception {
		AKeyPair kp = AKeyPair.generate();
		callerDID = UCAN.toDIDKey(kp.getAccountKey()).toString();
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		UCAN token = UCAN.create(kp, TestServer.ENGINE.getAccountKey(), exp,
			Vectors.empty(), Vectors.empty());
		jwt = token.toJWT(kp).toString();
		client = VenueHTTP.create(URI.create(TestServer.BASE_URL), VenueAuth.bearer(jwt));
		client.setTimeout(5000);

		storedName = "OwnAsset-" + Long.toHexString(exp);
		Job job = client.invokeAndWait(Strings.create("v/ops/asset/store"), Maps.of(
			Strings.create("metadata"), Maps.of(
				Strings.create("name"), Strings.create(storedName),
				Strings.create("type"), Strings.create("dataset"),
				Strings.create("description"), Strings.create("a test asset"))));
		assertEquals(Status.COMPLETE, job.getStatus(), "asset store failed: " + job.getErrorMessage());
	}

	private HttpResponse<String> get(String route, boolean auth) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder()
			.uri(URI.create(TestServer.BASE_URL + "/api/v1/" + route)).GET();
		if (auth) b.header("Authorization", "Bearer " + jwt);
		return covia.venue.TestHTTP.CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private long jobCount() {
		User u = TestServer.ENGINE.getVenueState().users().get(Strings.create(callerDID));
		return (u == null) ? 0 : u.getJobs().count();
	}

	private static boolean listHasName(ACell body, String name) {
		AVector<?> items = (AVector<?>) RT.getIn(body, "items");
		if (items == null) return false;
		AString target = Strings.create(name);
		for (long i = 0; i < items.count(); i++) {
			if (target.equals(RT.getIn(items.get(i), "name"))) return true;
		}
		return false;
	}

	@Test
	public void testScopeOwnListsCallerAssets() throws Exception {
		HttpResponse<String> r = get("assets?scope=own", true);
		assertEquals(200, r.statusCode(), r.body());
		ACell body = JSON.parse(r.body());
		assertNotNull(RT.getIn(body, "items"), r.body());
		assertNotNull(RT.getIn(body, "total"), "own listing carries a total");
		assertTrue(listHasName(body, storedName), "own listing includes the stored asset: " + r.body());
		AVector<?> items = (AVector<?>) RT.getIn(body, "items");
		for (long i = 0; i < items.count(); i++) {
			ACell item = items.get(i);
			if (!Strings.create(storedName).equals(RT.getIn(item, "name"))) continue;
			AString ref = RT.ensureString(RT.getIn(item, "ref"));
			assertNotNull(ref, item.toString());
			assertTrue(ref.toString().startsWith(callerDID + "/a/"), ref.toString());
			return;
		}
		throw new AssertionError("stored asset summary not found");
	}

	@Test
	public void testDefaultListingIsVenueCatalogNotOwnAssets() throws Exception {
		// The default /assets remains the venue catalog (bare hash ids), which does
		// not carry the caller's own asset under a 'name' — the two are distinct.
		HttpResponse<String> r = get("assets", true);
		assertEquals(200, r.statusCode(), r.body());
		assertFalse(listHasName(JSON.parse(r.body()), storedName),
			"the default catalog listing is not the caller's own a/ assets");
	}

	@Test
	public void testVenueListingCanExpandMetadataInOnePage() throws Exception {
		HttpResponse<String> r = get("assets?expand=metadata&limit=5", true);
		assertEquals(200, r.statusCode(), r.body());
		AVector<?> items = (AVector<?>) RT.getIn(JSON.parse(r.body()), "items");
		assertNotNull(items, r.body());
		assertTrue(items.count() > 0, "the venue catalog should not be empty");
		for (long i = 0; i < items.count(); i++) {
			ACell item = items.get(i);
			AString id = RT.ensureString(RT.getIn(item, "id"));
			ACell metadata = RT.getIn(item, "metadata");
			assertNotNull(id, item.toString());
			assertNotNull(metadata, item.toString());
			assertTrue(id.toString().endsWith("/a/" + metadata.getHash().toHexString()),
				"expanded metadata must hash to its listed id: " + item);
		}
	}

	@Test
	public void testVenueListingRejectsUnknownExpansion() throws Exception {
		assertEquals(400, get("assets?expand=everything", true).statusCode());
	}

	// ---- kind filter (covia-ai/frontend#420) --------------------------------
	// A client that wants artifacts had to download every operation definition
	// and throw it away: on venue-3 that is 75% of a 1.6 MB payload.

	@Test
	public void testKindOperationListsOnlyAssetsCarryingAnOperation() throws Exception {
		HttpResponse<String> r = get("assets?kind=operation&expand=metadata&limit=50", true);
		assertEquals(200, r.statusCode(), r.body());
		AVector<?> items = (AVector<?>) RT.getIn(JSON.parse(r.body()), "items");
		assertNotNull(items, r.body());
		assertTrue(items.count() > 0, "the venue publishes operations");
		for (long i = 0; i < items.count(); i++) {
			assertNotNull(RT.getIn(items.get(i), "metadata", "operation"),
				"kind=operation must not list a non-operation: " + items.get(i));
		}
	}

	@Test
	public void testKindDataListsOnlyAssetsWithoutAnOperation() throws Exception {
		HttpResponse<String> r = get("assets?kind=data&expand=metadata&limit=50", true);
		assertEquals(200, r.statusCode(), r.body());
		AVector<?> items = (AVector<?>) RT.getIn(JSON.parse(r.body()), "items");
		assertNotNull(items, r.body());
		for (long i = 0; i < items.count(); i++) {
			assertNull(RT.getIn(items.get(i), "metadata", "operation"),
				"kind=data must not list an operation: " + items.get(i));
		}
	}

	@Test
	public void testKindTotalsPartitionTheCatalogue() throws Exception {
		long all = listTotal("assets");
		long operations = listTotal("assets?kind=operation");
		long data = listTotal("assets?kind=data");
		assertEquals(all, operations + data,
			"every asset is either an operation or it is not");
		assertTrue(operations > 0 && data > 0, "the fixture venue has both kinds");
	}

	@Test
	public void testKindTotalDescribesTheFilteredListingNotTheCatalogue() throws Exception {
		// The trap this filter has to avoid: paging first and filtering after
		// would leave `total` describing the catalogue while `items` describe
		// something smaller, so a caller could never page it correctly.
		long data = listTotal("assets?kind=data");
		HttpResponse<String> r = get("assets?kind=data&limit=1000", true);
		AVector<?> items = (AVector<?>) RT.getIn(JSON.parse(r.body()), "items");
		assertEquals(data, items.count(),
			"a single page at the cap must return exactly the filtered total");
	}

	@Test
	public void testKindOffsetsCountFilteredEntries() throws Exception {
		AVector<?> firstTwo = (AVector<?>) RT.getIn(
			JSON.parse(get("assets?kind=data&limit=2", true).body()), "items");
		assertEquals(2, firstTwo.count());
		AVector<?> secondOnly = (AVector<?>) RT.getIn(
			JSON.parse(get("assets?kind=data&offset=1&limit=1", true).body()), "items");
		assertEquals(1, secondOnly.count());
		assertEquals(firstTwo.get(1), secondOnly.get(0),
			"offset 1 of the filtered listing is its second entry");
	}

	@Test
	public void testUnfilteredListingIsUnchanged() throws Exception {
		HttpResponse<String> r = get("assets?limit=5", true);
		assertEquals(200, r.statusCode(), r.body());
		AVector<?> items = (AVector<?>) RT.getIn(JSON.parse(r.body()), "items");
		assertEquals(5, items.count());
		assertEquals(listTotal("assets?kind=operation") + listTotal("assets?kind=data"),
			RT.ensureLong(RT.getIn(JSON.parse(r.body()), "total")).longValue(),
			"an unfiltered listing still totals the whole catalogue");
	}

	@Test
	public void testRejectsUnknownKind() throws Exception {
		assertEquals(400, get("assets?kind=everything", true).statusCode());
	}

	private long listTotal(String path) throws Exception {
		HttpResponse<String> r = get(path, true);
		assertEquals(200, r.statusCode(), r.body());
		return RT.ensureLong(RT.getIn(JSON.parse(r.body()), "total")).longValue();
	}

	@Test
	public void testScopeOwnIsJobFree() throws Exception {
		long before = jobCount();
		assertEquals(200, get("assets?scope=own", true).statusCode());
		assertEquals(before, jobCount(), "GET /assets?scope=own must not persist a job");
	}

	@Test
	public void testAnonymousDoesNotSeeCallerAssets() throws Exception {
		// Public access is on: an anonymous request reads the public identity's
		// (empty) a/ namespace, never the authenticated caller's.
		HttpResponse<String> r = get("assets?scope=own", false);
		assertEquals(200, r.statusCode(), r.body());
		assertFalse(listHasName(JSON.parse(r.body()), storedName),
			"anonymous must not see the caller's own assets");
	}
}
