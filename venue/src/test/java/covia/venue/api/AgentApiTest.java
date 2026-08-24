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
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
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
 * The job-free agent read API (#180): {@code GET /api/v1/agents} and
 * {@code /agents/{id}}. These routes share {@link covia.adapter.AgentAdapter}'s
 * {@code listAgents}/{@code agentInfo} accessors with the {@code agent:list} /
 * {@code agent:info} ops but create <b>no Job</b> — the fix for the Agent
 * Explorer minting a permanent job record on every 3s poll.
 *
 * <p>Runs against the shared {@link TestServer} as a <b>unique authenticated
 * caller</b> (fresh keypair → isolated DID and agent namespace, so no sibling
 * test can touch its job count), and launches no new venue (avoiding the known
 * concurrent-launch {@code Shutdown.addHook} flake). {@code SAME_THREAD} keeps
 * the job-free delta assertion off any race with this class's other methods.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
public class AgentApiTest {

	private static final AString OP_CREATE = Strings.create("v/ops/agent/create");
	private static final AString OP_DELETE = Strings.create("v/ops/agent/delete");
	private static final AString OP_LIST   = Strings.create("v/ops/agent/list");

	private AString callerDID;
	private String jwt;
	private VenueHTTP client;
	private HttpClient http;

	@BeforeAll
	public void setup() throws Exception {
		// A fresh authenticated caller. Audience = the venue (passes validation);
		// a bearer carries identity, never a grant scope → an unrestricted authenticated
		// session that may create/list its own agents. The DID is unique to this
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
		http = HttpClient.newHttpClient();

		createAgent("AgentAlpha");
		createAgent("AgentBeta");
	}

	private void createAgent(String id) throws Exception {
		Job job = client.invokeAndWait(OP_CREATE, Maps.of(
			Strings.create("agentId"), Strings.create(id),
			Strings.create("config"), Maps.of(
				Strings.create("name"), Strings.create(id),
				Strings.create("description"), Strings.create("dummy test agent"),
				Strings.create("operation"), Strings.create("v/test/ops/echo"))));
		assertEquals(Status.COMPLETE, job.getStatus(), "agent create failed: " + job.getErrorMessage());
	}

	private HttpResponse<String> get(String route, boolean auth) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder()
			.uri(URI.create(TestServer.BASE_URL + "/api/v1/" + route)).GET();
		if (auth) b.header("Authorization", "Bearer " + jwt);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private long jobCount() {
		User u = TestServer.ENGINE.getVenueState().users().get(callerDID);
		return (u == null) ? 0 : u.getJobs().count();
	}

	/** True if the {@code {agents:[...]}} payload lists agentId (ids or annotated form). */
	private static boolean listHas(ACell body, String agentId) {
		AVector<?> agents = (AVector<?>) RT.getIn(body, "agents");
		AString target = Strings.create(agentId);
		for (long i = 0; i < agents.count(); i++) {
			ACell e = agents.get(i);
			ACell id = (e instanceof AMap) ? RT.getIn(e, "agentId") : e;
			if (target.equals(id)) return true;
		}
		return false;
	}

	// ===================== functional =====================

	@Test
	public void testListDefaultsToAnnotated() throws Exception {
		// One canonical shape across both transports (#233): the REST default
		// matches agent:list's enriched entries, so clients read .agentId,
		// .status and .tasks without a per-agent fan-out.
		HttpResponse<String> r = get("agents", true);
		assertEquals(200, r.statusCode(), r.body());
		ACell body = JSON.parse(r.body());
		assertTrue(listHas(body, "AgentAlpha"), r.body());
		assertTrue(listHas(body, "AgentBeta"), r.body());
		AVector<?> agents = (AVector<?>) RT.getIn(body, "agents");
		ACell first = agents.get(0);
		assertTrue(first instanceof AMap, "default list entries are annotated maps");
		assertNotNull(RT.getIn(first, "agentId"));
		assertNotNull(RT.getIn(first, "status"));
		assertNotNull(RT.getIn(first, "tasks"));

		// status=false opts into the lean bare-id form.
		AVector<?> lean = (AVector<?>) RT.getIn(JSON.parse(get("agents?status=false", true).body()), "agents");
		assertTrue(lean.get(0) instanceof AString, "status=false entries are bare ids");
	}

	@Test
	public void testListStatusAnnotated() throws Exception {
		HttpResponse<String> r = get("agents?status=true", true);
		assertEquals(200, r.statusCode(), r.body());
		AVector<?> agents = (AVector<?>) RT.getIn(JSON.parse(r.body()), "agents");
		ACell first = agents.get(0);
		assertTrue(first instanceof AMap, "?status=true entries are annotated maps");
		assertNotNull(RT.getIn(first, "agentId"));
		assertNotNull(RT.getIn(first, "status"));
	}

	@Test
	public void testGetAgentInfo() throws Exception {
		HttpResponse<String> r = get("agents/AgentAlpha", true);
		assertEquals(200, r.statusCode(), r.body());
		ACell m = JSON.parse(r.body());
		assertEquals(Strings.create("AgentAlpha"), RT.getIn(m, "agentId"));
		assertNotNull(RT.getIn(m, "status"));
		assertNotNull(RT.getIn(m, "config"));
	}

	@Test
	public void testUnknownAgentIs404() throws Exception {
		assertEquals(404, get("agents/NoSuchAgent", true).statusCode());
	}

	@Test
	public void testAnonymousDoesNotSeeCallerAgents() throws Exception {
		// With public access enabled an unauthenticated request is the venue's
		// public identity: it reads the (empty) public namespace, scoped exactly
		// like every other per-user read (cf. listSecrets) — never the
		// authenticated caller's agents.
		HttpResponse<String> list = get("agents", false);
		assertEquals(200, list.statusCode(), list.body());
		assertFalse(listHas(JSON.parse(list.body()), "AgentAlpha"),
			"anonymous must not see the authenticated caller's agents");
		// A direct fetch of the caller's agent resolves in the public namespace,
		// where it does not exist → 404 (never a cross-user read).
		assertEquals(404, get("agents/AgentAlpha", false).statusCode(),
			"anonymous cannot fetch the caller's agent by id");
	}

	@Test
	public void testIncludeTerminated() throws Exception {
		createAgent("AgentTerm");
		Job del = client.invokeAndWait(OP_DELETE, Maps.of(Strings.create("agentId"), Strings.create("AgentTerm")));
		assertEquals(Status.COMPLETE, del.getStatus(), del.getErrorMessage());

		assertFalse(listHas(JSON.parse(get("agents", true).body()), "AgentTerm"),
			"a terminated agent is hidden by default");
		assertTrue(listHas(JSON.parse(get("agents?includeTerminated=true", true).body()), "AgentTerm"),
			"a terminated agent is shown with includeTerminated=true");
	}

	// ===================== the point of #180 =====================

	@Test
	public void testReadsAreJobFree() throws Exception {
		long before = jobCount();

		assertEquals(200, get("agents", true).statusCode());
		assertEquals(200, get("agents/AgentAlpha", true).statusCode());
		assertEquals(before, jobCount(), "GET agent reads must not persist a job");

		// Contrast: the same listing via the invoke/op path persists exactly one
		// job — the cost #180 removes from the read path.
		Job job = client.invokeAndWait(OP_LIST, Maps.empty());
		assertEquals(Status.COMPLETE, job.getStatus(), job.getErrorMessage());
		assertEquals(before + 1, jobCount(), "agent:list via invoke persists exactly one job");
	}
}
