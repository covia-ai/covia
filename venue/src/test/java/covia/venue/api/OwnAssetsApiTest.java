package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.auth.ucan.Capability;
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
			Vectors.of(Capability.create(Strings.create(callerDID + "/w/"), Capability.CRUD_READ)),
			Vectors.empty());
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
		return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
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
