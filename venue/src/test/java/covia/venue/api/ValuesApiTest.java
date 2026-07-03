package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.TestServer;
import covia.venue.User;

/**
 * The job-free lattice value read API (#177): {@code GET /api/v1/values/{read,
 * list,slice,inspect}}. These routes share {@link covia.adapter.CoviaAdapter}'s
 * read accessors with the {@code covia:*} ops but create <b>no Job</b> — the fix
 * for the etch-bloat problem where every read persisted a permanent job record.
 *
 * <p>Runs against the shared {@link TestServer} as a <b>unique authenticated
 * caller</b> (fresh keypair → isolated DID), so the per-caller job count no
 * sibling test can touch — and launches no new venue (avoiding the known
 * concurrent-launch {@code Shutdown.addHook} flake). {@code SAME_THREAD} keeps
 * the job-free delta assertion off any race with this class's other methods.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
public class ValuesApiTest {

	private static final AString OP_WRITE = Strings.create("v/ops/covia/write");
	private static final AString OP_READ  = Strings.create("v/ops/covia/read");

	private AString callerDID;
	private String jwt;
	private VenueHTTP client;

	@BeforeAll
	public void setup() throws Exception {
		// A fresh authenticated caller. Audience = the venue (passes validation);
		// aud != iss, so no self-attenuation → an unrestricted authenticated
		// session that reads/writes its own workspace. The DID is unique to this
		// class, isolating its job count from every other test.
		AKeyPair kp = AKeyPair.generate();
		callerDID = UCAN.toDIDKey(kp.getAccountKey());
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		UCAN token = UCAN.create(kp, TestServer.ENGINE.getAccountKey(), exp,
			Vectors.of(Capability.create(Strings.create(callerDID + "/w/"), Capability.CRUD_READ)),
			Vectors.empty());
		jwt = token.toJWT(kp).toString();
		client = VenueHTTP.create(URI.create(TestServer.BASE_URL), VenueAuth.bearer(jwt));
		client.setTimeout(5000);

		// Seed known data (each write persists one job under callerDID — the
		// job-free test captures its baseline afterwards).
		write("w/vGreeting", Strings.create("hello"));
		write("w/vBox", Maps.of(Strings.create("a"), CVMLong.create(1),
								 Strings.create("b"), CVMLong.create(2)));
		write("w/vSeq", Vectors.of(CVMLong.create(10), CVMLong.create(20), CVMLong.create(30)));
		// Nested: {a:{x,y}, b:{z}} — depth 1 = 2 buckets, depth 2 = 3 leaves.
		write("w/vNest", Maps.of(
			Strings.create("a"), Maps.of(Strings.create("x"), CVMLong.create(1), Strings.create("y"), CVMLong.create(2)),
			Strings.create("b"), Maps.of(Strings.create("z"), CVMLong.create(3))));
		// Records with a source field, for groupBy.
		write("w/vOrders", Maps.of(
			Strings.create("o1"), Maps.of(Strings.create("source"), Strings.create("nhs")),
			Strings.create("o2"), Maps.of(Strings.create("source"), Strings.create("nhs")),
			Strings.create("o3"), Maps.of(Strings.create("source"), Strings.create("gp"))));
	}

	private void write(String path, ACell value) throws Exception {
		Job job = client.invokeAndWait(OP_WRITE, Maps.of(
			Strings.create("path"), Strings.create(path),
			Strings.create("value"), value));
		assertEquals(Status.COMPLETE, job.getStatus(), "seed write failed: " + job.getErrorMessage());
	}

	private HttpResponse<String> get(String route, String path) throws Exception {
		String uri = TestServer.BASE_URL + "/api/v1/values/" + route
			+ (path == null ? "" : "?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8));
		return HttpClient.newHttpClient().send(
			HttpRequest.newBuilder().uri(URI.create(uri))
				.header("Authorization", "Bearer " + jwt).GET().build(),
			HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> getQ(String route, String path, String extraQuery) throws Exception {
		String uri = TestServer.BASE_URL + "/api/v1/values/" + route
			+ "?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8) + extraQuery;
		return HttpClient.newHttpClient().send(
			HttpRequest.newBuilder().uri(URI.create(uri))
				.header("Authorization", "Bearer " + jwt).GET().build(),
			HttpResponse.BodyHandlers.ofString());
	}

	private long jobCount() {
		User u = TestServer.ENGINE.getVenueState().users().get(callerDID);
		return (u == null) ? 0 : u.getJobs().count();
	}

	// ===================== functional =====================

	@Test
	public void testReadValue() throws Exception {
		HttpResponse<String> r = get("read", "w/vGreeting");
		assertEquals(200, r.statusCode(), r.body());
		ACell m = JSON.parse(r.body());
		assertEquals(CVMBool.TRUE, RT.getIn(m, "exists"));
		assertEquals(Strings.create("hello"), RT.getIn(m, "value"));
	}

	@Test
	public void testReadAbsentIsExistsFalse() throws Exception {
		HttpResponse<String> r = get("read", "w/vDoesNotExist");
		assertEquals(200, r.statusCode(), r.body());
		assertEquals(CVMBool.FALSE, RT.getIn(JSON.parse(r.body()), "exists"));
	}

	@Test
	public void testListKeys() throws Exception {
		HttpResponse<String> r = get("list", "w/vBox");
		assertEquals(200, r.statusCode(), r.body());
		ACell m = JSON.parse(r.body());
		assertEquals(CVMBool.TRUE, RT.getIn(m, "exists"));
		AVector<?> keys = (AVector<?>) RT.getIn(m, "keys");
		assertEquals(2, keys.count(), "expected two keys, got: " + keys);
	}

	@Test
	public void testSliceElements() throws Exception {
		HttpResponse<String> r = get("slice", "w/vSeq");
		assertEquals(200, r.statusCode(), r.body());
		AVector<?> values = (AVector<?>) RT.getIn(JSON.parse(r.body()), "values");
		assertEquals(3, values.count(), "expected three elements, got: " + values);
	}

	@Test
	public void testInspectRenders() throws Exception {
		HttpResponse<String> r = get("inspect", "w/vBox");
		assertEquals(200, r.statusCode(), r.body());
		assertNotNull(RT.getIn(JSON.parse(r.body()), "result"), "inspect must return a rendered result");
	}

	// ===================== the point of #177 =====================

	@Test
	public void testReadIsJobFree() throws Exception {
		long before = jobCount();

		HttpResponse<String> r = get("read", "w/vGreeting");
		assertEquals(200, r.statusCode(), r.body());
		assertEquals(before, jobCount(), "a GET read must not persist a job");

		// Contrast: the SAME accessor via the invoke/op path persists exactly one
		// job — the cost #177 removes from the read path.
		Job job = client.invokeAndWait(OP_READ, Maps.of(Strings.create("path"), Strings.create("w/vGreeting")));
		assertEquals(Status.COMPLETE, job.getStatus());
		assertEquals(before + 1, jobCount(), "covia:read via invoke persists exactly one job");
	}

	// ===================== aggregate / count =====================

	@Test
	public void testCountDefaultDepth() throws Exception {
		HttpResponse<String> r = get("count", "w/vBox");
		assertEquals(200, r.statusCode(), r.body());
		ACell m = JSON.parse(r.body());
		assertEquals(CVMBool.TRUE, RT.getIn(m, "exists"));
		assertEquals(CVMLong.create(2), RT.getIn(m, "count"), "two direct children");
	}

	@Test
	public void testAggregateDepth2() throws Exception {
		HttpResponse<String> r = get("aggregate", "w/vNest") ;
		// depth defaults to 1 → 2 buckets
		assertEquals(CVMLong.create(2), RT.getIn(JSON.parse(r.body()), "count"));
		// depth=2 → 3 leaves
		HttpResponse<String> r2 = getQ("aggregate", "w/vNest", "&depth=2");
		assertEquals(200, r2.statusCode(), r2.body());
		assertEquals(CVMLong.create(3), RT.getIn(JSON.parse(r2.body()), "count"), "three leaves at depth 2");
	}

	@Test
	public void testAggregateGroupBy() throws Exception {
		HttpResponse<String> r = getQ("aggregate", "w/vOrders", "&depth=1&groupBy=source");
		assertEquals(200, r.statusCode(), r.body());
		ACell m = JSON.parse(r.body());
		assertEquals(CVMLong.create(3), RT.getIn(m, "count"));
		ACell groups = RT.getIn(m, "groups");
		assertEquals(CVMLong.create(2), RT.getIn(RT.getIn(groups, "nhs"), "count"), "nhs group");
		assertEquals(CVMLong.create(1), RT.getIn(RT.getIn(groups, "gp"), "count"), "gp group");
	}

	@Test
	public void testAggregateScalarIsExistsFalse() throws Exception {
		// A scalar has nothing to descend into → exists:false (no count).
		HttpResponse<String> r = get("aggregate", "w/vGreeting");
		assertEquals(200, r.statusCode(), r.body());
		ACell m = JSON.parse(r.body());
		assertEquals(CVMBool.FALSE, RT.getIn(m, "exists"));
		assertNull(RT.getIn(m, "count"), "no count when there is no collection to count");
	}

	@Test
	public void testAggregateAbsentIsExistsFalse() throws Exception {
		HttpResponse<String> r = get("aggregate", "w/vNothingHere");
		assertEquals(200, r.statusCode(), r.body());
		assertEquals(CVMBool.FALSE, RT.getIn(JSON.parse(r.body()), "exists"));
	}

	// ===================== guards =====================

	@Test
	public void testMissingPathIs400() throws Exception {
		assertEquals(400, get("read", null).statusCode());
	}

	@Test
	public void testExecutionScopedNamespacesRejected() throws Exception {
		assertEquals(400, get("read", "t/scratch").statusCode(), "t/ is job-scoped");
		assertEquals(400, get("read", "n/agentwork").statusCode(), "n/ is agent-run scoped");
		assertEquals(400, get("read", "c/session").statusCode(), "c/ is session scoped");
	}
}
