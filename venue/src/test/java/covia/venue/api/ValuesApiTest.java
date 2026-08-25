package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.RequestContext;
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
	private AKeyPair delegatedOwnerKP;
	private AString delegatedOwnerDID;
	private final AString scopedAgentId = Strings.create("values-agent");
	private final Blob scopedSessionId = Blob.fromHex("00112233445566778899aabbccddeeff");
	private Blob scopedTaskId;

	@BeforeAll
	public void setup() throws Exception {
		// A fresh authenticated caller. Audience = the venue (passes validation);
		// a bearer carries identity, never a grant scope → an unrestricted authenticated
		// session that reads/writes its own workspace. The DID is unique to this
		// class, isolating its job count from every other test.
		AKeyPair kp = AKeyPair.generate();
		callerDID = UCAN.toDIDKey(kp.getAccountKey());
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		UCAN token = UCAN.create(kp, TestServer.ENGINE.getAccountKey(), exp,
			Vectors.empty(), Vectors.empty());
		jwt = token.toJWT(kp).toString();
		client = VenueHTTP.create(URI.create(TestServer.BASE_URL), VenueAuth.bearer(jwt));
		client.setTimeout(5000);
		delegatedOwnerKP = AKeyPair.generate();
		delegatedOwnerDID = UCAN.toDIDKey(delegatedOwnerKP.getAccountKey());

		// Seed known data (each write persists one job under callerDID — the
		// job-free test captures its baseline afterwards).
		Job taskJob = write("w/vGreeting", Strings.create("hello"));
		scopedTaskId = taskJob.getID();
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
		TestServer.ENGINE.jobs().invokeOperation(OP_WRITE, Maps.of(
			Strings.create("path"), Strings.create("w/delegated"),
			Strings.create("value"), Strings.create("owner content")),
			RequestContext.of(delegatedOwnerDID)).awaitResult(5000);

		// Seed all three execution-scoped stores without creating extra Jobs.
		// In particular, task scratch is backed by the existing task Job record.
		User scopedUser = TestServer.ENGINE.getVenueState().users().get(callerDID);
		scopedUser.ensureAgent(scopedAgentId, Maps.empty(), null)
			.ensureSession(scopedSessionId, callerDID);
		RequestContext base = RequestContext.of(callerDID).withAgentId(scopedAgentId);
		TestServer.ENGINE.jobs().invokeInternal(OP_WRITE, Maps.of(
			Strings.create("path"), Strings.create("n/note"),
			Strings.create("value"), Strings.create("agent-note")), base).join();
		TestServer.ENGINE.jobs().invokeInternal(OP_WRITE, Maps.of(
			Strings.create("path"), Strings.create("n/seq"),
			Strings.create("value"), Vectors.of(CVMLong.create(1), CVMLong.create(2))),
			base).join();
		TestServer.ENGINE.jobs().invokeInternal(OP_WRITE, Maps.of(
			Strings.create("path"), Strings.create("c/note"),
			Strings.create("value"), Strings.create("session-note")),
			base.withSessionId(scopedSessionId)).join();
		TestServer.ENGINE.jobs().invokeInternal(OP_WRITE, Maps.of(
			Strings.create("path"), Strings.create("t/note"),
			Strings.create("value"), Strings.create("task-note")),
			base.withTaskId(scopedTaskId)).join();
	}

	private Job write(String path, ACell value) throws Exception {
		Job job = client.invokeAndWait(OP_WRITE, Maps.of(
			Strings.create("path"), Strings.create(path),
			Strings.create("value"), value));
		assertEquals(Status.COMPLETE, job.getStatus(), "seed write failed: " + job.getErrorMessage());
		return job;
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

	private HttpResponse<String> getWithProof(String route, String path, AString proof) throws Exception {
		String uri = TestServer.BASE_URL + "/api/v1/values/" + route
			+ "?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
		return HttpClient.newHttpClient().send(
			HttpRequest.newBuilder().uri(URI.create(uri))
				.header("Authorization", "Bearer " + jwt)
				.header(VenueHTTP.UCANS_HEADER, proof.toString()).GET().build(),
			HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> getIfNoneMatch(String route, String path, String etag) throws Exception {
		String uri = TestServer.BASE_URL + "/api/v1/values/" + route
			+ "?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
		return HttpClient.newHttpClient().send(
			HttpRequest.newBuilder().uri(URI.create(uri))
				.header("Authorization", "Bearer " + jwt)
				.header("If-None-Match", etag).GET().build(),
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

	/** #78: slice caps the returned page by maxSize (CAD3 bytes) and FAILS on
	 *  overflow — exact values, never summarised, never unbounded (the old bug). */
	@Test
	public void testSliceMaxSizeFailsOnOverflow() throws Exception {
		// A vector with one large entry — unbounded before #78.
		write("w/vBig", Vectors.of(Strings.create("x".repeat(4000)),
			Strings.create("y".repeat(4000))));

		// Tight maxSize → the page overflows → 400 (not a truncated flag, not a summary).
		HttpResponse<String> tight = getQ("slice", "w/vBig", "&maxSize=100");
		assertEquals(400, tight.statusCode(), tight.body());
		assertTrue(tight.body().contains("maxSize") && tight.body().contains("reduce limit"),
			"error names the cap and the remedy: " + tight.body());

		// Reducing limit brings the page under the cap → exact values returned.
		HttpResponse<String> paged = getQ("slice", "w/vBig", "&maxSize=100000&limit=1");
		assertEquals(200, paged.statusCode(), paged.body());
		assertEquals(1, ((AVector<?>) RT.getIn(JSON.parse(paged.body()), "values")).count());

		// Generous default cap → whole small collection returns exactly (no regression).
		HttpResponse<String> ok = get("slice", "w/vSeq");
		assertEquals(200, ok.statusCode(), ok.body());
	}

	@Test
	public void testInspectRenders() throws Exception {
		HttpResponse<String> r = get("inspect", "w/vBox");
		assertEquals(200, r.statusCode(), r.body());
		assertNotNull(RT.getIn(JSON.parse(r.body()), "result"), "inspect must return a rendered result");
	}

	// ===================== conditional read (ETag) =====================

	@Test
	public void testReadEtagConditional() throws Exception {
		write("w/vEtag", Strings.create("v1"));

		HttpResponse<String> r1 = get("read", "w/vEtag");
		assertEquals(200, r1.statusCode(), r1.body());
		String etag = r1.headers().firstValue("ETag").orElse(null);
		assertNotNull(etag, "read must return an ETag");

		// Unchanged value + If-None-Match → 304, no body re-sent.
		HttpResponse<String> r2 = getIfNoneMatch("read", "w/vEtag", etag);
		assertEquals(304, r2.statusCode());
		assertTrue(r2.body().isEmpty(), "304 must not re-send the body");

		// After the value changes its hash differs → ETag miss → 200.
		write("w/vEtag", Strings.create("v2"));
		HttpResponse<String> r3 = getIfNoneMatch("read", "w/vEtag", etag);
		assertEquals(200, r3.statusCode(), "a changed value must not 304 against the old ETag");
	}

	@Test
	public void testAbsentReadHasNoEtag() throws Exception {
		// A genuinely absent path carries no value → no ETag.
		HttpResponse<String> r = get("read", "w/vNoSuchPath");
		assertEquals(200, r.statusCode(), r.body());
		assertEquals(CVMBool.FALSE, RT.getIn(JSON.parse(r.body()), "exists"));
		assertTrue(r.headers().firstValue("ETag").isEmpty(), "absent read must not carry an ETag");
	}

	@Test
	public void testPresentNullReadHasEtag() throws Exception {
		// A stored null is a present value (distinct from absent): exists:true,
		// value:null, and it carries an ETag (the canonical nil hash) that 304s.
		write("w/vNull", null);
		HttpResponse<String> r1 = get("read", "w/vNull");
		assertEquals(200, r1.statusCode(), r1.body());
		ACell m = JSON.parse(r1.body());
		assertEquals(CVMBool.TRUE, RT.getIn(m, "exists"), "a stored null is present");
		assertNull(RT.getIn(m, "value"));
		String etag = r1.headers().firstValue("ETag").orElse(null);
		assertNotNull(etag, "present null carries an ETag");
		assertEquals(304, getIfNoneMatch("read", "w/vNull", etag).statusCode(), "unchanged null → 304");
	}

	@Test
	public void testListHasNoEtag() throws Exception {
		// Only `read` is ETagged — computed/param-dependent bodies are not.
		HttpResponse<String> r = get("list", "w/vBox");
		assertEquals(200, r.statusCode(), r.body());
		assertTrue(r.headers().firstValue("ETag").isEmpty(), "list must not carry an ETag");
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

	/** #399: a GET has no body for delegation proofs, so job-free reads take
	 *  them from X-Covia-Ucans exactly like the job-status GET route. */
	@Test
	public void testDelegatedReadFromHeaderIsJobFree() throws Exception {
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		AString proof = UCAN.createJWT(delegatedOwnerKP, UCAN.fromDIDKey(callerDID), exp,
			Vectors.of(Capability.create(
				Strings.create(delegatedOwnerDID + "/w/"), Capability.CRUD_READ)),
			Vectors.empty());
		long before = jobCount();

		HttpResponse<String> r = getWithProof("read",
			delegatedOwnerDID + "/w/delegated", proof);

		assertEquals(200, r.statusCode(), r.body());
		assertEquals(Strings.create("owner content"), RT.getIn(JSON.parse(r.body()), "value"));
		assertEquals(before, jobCount(), "a delegated GET read must not persist a job");
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
	public void testExecutionScopedNamespacesRequireSelectors() throws Exception {
		assertEquals(400, get("read", "t/scratch").statusCode());
		assertEquals(400, get("read", "n/agentwork").statusCode());
		assertEquals(400, get("read", "c/session").statusCode());
	}

	@Test
	public void testReadAgentScopedValue() throws Exception {
		HttpResponse<String> r = getQ("read", "n/note", "&agent=" + scopedAgentId);
		assertEquals(200, r.statusCode(), r.body());
		assertEquals(Strings.create("agent-note"), RT.getIn(JSON.parse(r.body()), "value"));
		assertEquals("private, no-store",
			r.headers().firstValue("Cache-Control").orElse(null));
	}

	@Test
	public void testReadTaskScopedValueFromJobStore() throws Exception {
		HttpResponse<String> r = getQ("read", "t/note",
			"&agent=" + scopedAgentId + "&task=" + scopedTaskId.toHexString());
		assertEquals(200, r.statusCode(), r.body());
		assertEquals(Strings.create("task-note"), RT.getIn(JSON.parse(r.body()), "value"));

		// The shorthand and physical path are views of one value, not duplicated
		// task-row and Job-row stores.
		HttpResponse<String> physical = get("read",
			"j/" + scopedTaskId.toHexString() + "/temp/note");
		assertEquals(200, physical.statusCode(), physical.body());
		assertEquals(Strings.create("task-note"), RT.getIn(JSON.parse(physical.body()), "value"));
	}

	@Test
	public void testReadSessionScopedValue() throws Exception {
		HttpResponse<String> r = getQ("read", "c/note",
			"&agent=" + scopedAgentId + "&session=" + scopedSessionId.toHexString());
		assertEquals(200, r.statusCode(), r.body());
		assertEquals(Strings.create("session-note"), RT.getIn(JSON.parse(r.body()), "value"));
	}

	@Test
	public void testAllReadAccessorsAcceptExplicitScopes() throws Exception {
		String agent = "&agent=" + scopedAgentId;
		String task = agent + "&task=" + scopedTaskId.toHexString();
		String session = agent + "&session=" + scopedSessionId.toHexString();

		assertEquals(200, getQ("list", "t", task).statusCode());
		assertEquals(200, getQ("slice", "n/seq", agent).statusCode());
		assertEquals(200, getQ("inspect", "c/note", session).statusCode());
		assertEquals(200, getQ("aggregate", "c", session).statusCode());
		assertEquals(200, getQ("count", "t", task).statusCode());
	}

	@Test
	public void testFullAgentDIDSelectsOwnerNamespace() throws Exception {
		AString agentDID = Principals.agentDID(callerDID, scopedAgentId);
		HttpResponse<String> r = getQ("read", "n/note",
			"&agent=" + URLEncoder.encode(agentDID.toString(), StandardCharsets.UTF_8));
		assertEquals(200, r.statusCode(), r.body());
		assertEquals(Strings.create("agent-note"), RT.getIn(JSON.parse(r.body()), "value"));
	}

	@Test
	public void testForeignAgentDIDIsAuthorisedAsExpandedResource() throws Exception {
		AString foreignOwner = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		AString foreignAgent = Principals.agentDID(foreignOwner, Strings.create("other"));
		HttpResponse<String> r = getQ("read", "n/does-not-exist",
			"&agent=" + URLEncoder.encode(foreignAgent.toString(), StandardCharsets.UTF_8));
		assertEquals(403, r.statusCode(), r.body());
	}

	@Test
	public void testScopedSelectorsRejectContradictions() throws Exception {
		assertEquals(400, getQ("read", "n/note",
			"&agent=" + scopedAgentId + "&task=" + scopedTaskId.toHexString()).statusCode());
		assertEquals(400, getQ("read", "w/vGreeting",
			"&agent=" + scopedAgentId).statusCode());
		assertEquals(400, getQ("read", "t/note",
			"&agent=" + scopedAgentId + "&task=not-hex").statusCode());
	}

	// ===================== fields projection (#191) =====================

	/** Seeds a small session-list-shaped collection under a unique path. */
	private void seedSessions(String path) throws Exception {
		write(path, Maps.of(
			Strings.create("s1"), Maps.of(
				Strings.create("status"), Strings.create("ACTIVE"),
				Strings.create("meta"), Maps.of(Strings.create("updated"), CVMLong.create(111)),
				Strings.create("history"), Strings.create("a long payload the view never wants")),
			Strings.create("s2"), Maps.of(
				Strings.create("status"), Strings.create("DONE"),
				Strings.create("meta"), Maps.of(Strings.create("updated"), CVMLong.create(222)))));
	}

	@Test
	public void testListProjection() throws Exception {
		seedSessions("w/vProj");
		HttpResponse<String> r = getQ("list", "w/vProj", "&fields=status,meta/updated");
		assertEquals(200, r.statusCode(), r.body());
		ACell m = JSON.parse(r.body());
		// The list shape is unchanged — projection only adds `values`.
		assertEquals(CVMBool.TRUE, RT.getIn(m, "exists"));
		assertEquals(2, ((AVector<?>) RT.getIn(m, "keys")).count());
		// Each field carries single-read semantics: {exists, value}.
		assertEquals(CVMBool.TRUE, RT.getIn(m, "values", "s1", "status", "exists"));
		assertEquals(Strings.create("ACTIVE"), RT.getIn(m, "values", "s1", "status", "value"));
		assertEquals(CVMLong.create(111), RT.getIn(m, "values", "s1", "meta/updated", "value"));
		assertEquals(Strings.create("DONE"), RT.getIn(m, "values", "s2", "status", "value"));
		assertEquals(CVMLong.create(222), RT.getIn(m, "values", "s2", "meta/updated", "value"));
		// The payload field was not requested and is not in the response.
		assertNull(RT.getIn(m, "values", "s1", "history"));
	}

	@Test
	public void testListProjectionAbsentFieldIsExistsFalse() throws Exception {
		seedSessions("w/vProjAbsent");
		HttpResponse<String> r = getQ("list", "w/vProjAbsent", "&fields=history");
		assertEquals(200, r.statusCode(), r.body());
		ACell m = JSON.parse(r.body());
		assertEquals(CVMBool.TRUE, RT.getIn(m, "values", "s1", "history", "exists"));
		assertEquals(CVMBool.FALSE, RT.getIn(m, "values", "s2", "history", "exists"),
			"absent subpath must render exists:false");
	}

	@Test
	public void testListProjectionIsPageThenProject() throws Exception {
		seedSessions("w/vProjPage");
		HttpResponse<String> r = getQ("list", "w/vProjPage", "&fields=status&limit=1&offset=1");
		assertEquals(200, r.statusCode(), r.body());
		ACell m = JSON.parse(r.body());
		AVector<?> keys = (AVector<?>) RT.getIn(m, "keys");
		assertEquals(1, keys.count(), "page is one key");
		// values covers exactly the page — the paged-out key is not projected.
		AString pageKey = (AString) keys.get(0);
		assertNotNull(RT.getIn(m, "values", pageKey, "status", "value"));
		assertEquals(1, ((convex.core.data.AMap<?, ?>) RT.getIn(m, "values")).count());
	}

	@Test
	public void testListProjectionFieldInheritsMaxSize() throws Exception {
		seedSessions("w/vProjTrunc");
		// maxSize=2 truncates every projected field (single-read semantics per field).
		HttpResponse<String> r = getQ("list", "w/vProjTrunc", "&fields=status&maxSize=2");
		assertEquals(200, r.statusCode(), r.body());
		ACell m = JSON.parse(r.body());
		assertEquals(CVMBool.TRUE, RT.getIn(m, "values", "s1", "status", "exists"));
		assertEquals(CVMBool.TRUE, RT.getIn(m, "values", "s1", "status", "truncated"));
		assertNull(RT.getIn(m, "values", "s1", "status", "value"));
	}

	@Test
	public void testListProjectionOnNonKeyedNodeIs400() throws Exception {
		assertEquals(400, getQ("list", "w/vSeq", "&fields=status").statusCode(),
			"projection requires a keyed node");
		assertEquals(400, getQ("list", "w/vGreeting", "&fields=status").statusCode(),
			"projection on a scalar is a caller error");
	}

	@Test
	public void testListProjectionOverFieldCapIs400() throws Exception {
		StringBuilder many = new StringBuilder("f0");
		for (int i = 1; i < 17; i++) many.append(",f").append(i);
		assertEquals(400, getQ("list", "w/vBox", "&fields=" + many).statusCode(),
			"17 fields exceeds the cap and must be an explicit error");
	}

	@Test
	public void testListProjectionAbsentPathIsExistsFalse() throws Exception {
		HttpResponse<String> r = getQ("list", "w/vProjNoSuch", "&fields=status");
		assertEquals(200, r.statusCode(), r.body());
		assertEquals(CVMBool.FALSE, RT.getIn(JSON.parse(r.body()), "exists"));
	}

	@Test
	public void testListProjectionOpFormVectorFields() throws Exception {
		seedSessions("w/vProjOp");
		// The covia:list op shares the accessor: fields as a vector of strings.
		Job job = client.invokeAndWait(Strings.create("v/ops/covia/list"), Maps.of(
			Strings.create("path"), Strings.create("w/vProjOp"),
			Strings.create("fields"), Vectors.of(Strings.create("status"))));
		assertEquals(Status.COMPLETE, job.getStatus(), "list op failed: " + job.getErrorMessage());
		ACell out = job.getOutput();
		assertEquals(Strings.create("ACTIVE"), RT.getIn(out, "values", "s1", "status", "value"));
	}
}
