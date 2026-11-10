package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;

/**
 * An agent acts with its controller's authority, narrowed by its caps, and is
 * unrestricted only when its owner says {@code caps: "unrestricted"}: no way of
 * making or changing an agent leaves it unrestricted otherwise. An agent with
 * no authority of its own still runs, and the venue's own agents administer
 * the venue as far as their caps reach, by any route.
 */
public class UnrestrictedAgentTest {

	private static final ACell INVOKE_ONLY = Vectors.of(Maps.of(Capability.CAN, "invoke"));

	private final Engine engine = TestEngine.ENGINE;

	private Job run(String op, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		try { job.awaitResult(10000); } catch (Exception ignored) {}
		return job;
	}

	private void complete(String op, ACell input, RequestContext ctx) {
		Job job = run(op, input, ctx);
		assertEquals(Status.COMPLETE, job.getStatus(), job.getErrorMessage());
	}

	private RequestContext owner(TestInfo info) {
		AString did = TestEngine.uniqueDID(info);
		engine.getVenueState().users().create(did);
		return RequestContext.of(did);
	}

	/** Whether {@code owner}'s agent {@code id} exists and acts with no bound on its controller's authority. */
	private boolean unrestricted(AString owner, String id) {
		AString agentId = Strings.create(id);
		return engine.getAgent(owner, agentId) != null
			&& engine.context(owner, Principals.agentDID(owner, agentId)).getCaps() == null;
	}

	/** The caps {@code agent:info} shows for {@code owner}'s agent {@code id}. */
	private ACell shownCaps(RequestContext owner, String id) {
		Job info = run("v/ops/agent/info", Maps.of(Fields.AGENT_ID, id), owner);
		assertEquals(Status.COMPLETE, info.getStatus(), info.getErrorMessage());
		return RT.getIn(info.getOutput(), Fields.CONFIG, Config.CAPS);
	}

	// ========== Unrestricted only by saying so ==========

	@Test
	public void creatingAnAgentWithoutCapsGivesItNoAuthority(TestInfo info) {
		RequestContext alice = owner(info);
		complete("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "plain", Fields.CONFIG, Maps.empty()), alice);
		assertFalse(unrestricted(alice.getUserDID(), "plain"));
		assertEquals(Vectors.empty(), shownCaps(alice, "plain"));
	}

	@Test
	public void creatingAnAgentFromATemplateWithoutCapsGivesItNoAuthority(TestInfo info) {
		RequestContext alice = owner(info);
		complete("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "templated",
			Fields.CONFIG, "v/agents/templates/worker"), alice);
		assertFalse(unrestricted(alice.getUserDID(), "templated"));
	}

	@Test
	public void anOwnerMakesAnAgentUnrestrictedBySayingSo(TestInfo info) {
		RequestContext alice = owner(info);
		complete("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "trusted",
			Fields.CONFIG, Maps.of(Config.CAPS, Config.UNRESTRICTED)), alice);
		assertTrue(unrestricted(alice.getUserDID(), "trusted"));
		assertEquals(Config.UNRESTRICTED, shownCaps(alice, "trusted"));

		// A change that names no caps leaves them as they are.
		complete("v/ops/agent/update", Maps.of(Fields.AGENT_ID, "trusted",
			Fields.CONFIG, Maps.of("systemPrompt", "Be brief.")), alice);
		assertTrue(unrestricted(alice.getUserDID(), "trusted"));

		complete("v/ops/agent/update", Maps.of(Fields.AGENT_ID, "trusted",
			Fields.CONFIG, Maps.of(Config.CAPS, INVOKE_ONLY)), alice);
		assertFalse(unrestricted(alice.getUserDID(), "trusted"));
		assertEquals(INVOKE_ONLY, shownCaps(alice, "trusted"));

		complete("v/ops/agent/update", Maps.of(Fields.AGENT_ID, "trusted",
			Fields.CONFIG, Maps.of(Config.CAPS, Config.UNRESTRICTED)), alice);
		assertTrue(unrestricted(alice.getUserDID(), "trusted"));
	}

	@Test
	public void clearingAnAgentsCapsGivesItNoAuthority(TestInfo info) {
		RequestContext alice = owner(info);
		complete("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "trusted",
			Fields.CONFIG, Maps.of(Config.CAPS, Config.UNRESTRICTED)), alice);
		complete("v/ops/agent/update", Maps.of(Fields.AGENT_ID, "trusted",
			Fields.CONFIG, Maps.of(Config.CAPS, null)), alice);
		assertFalse(unrestricted(alice.getUserDID(), "trusted"));
		assertEquals(Vectors.empty(), shownCaps(alice, "trusted"));
	}

	@Test
	public void aForkKeepsItsSourcesCapsUnlessItNamesOthers(TestInfo info) {
		RequestContext alice = owner(info);
		complete("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "capped",
			Fields.CONFIG, Maps.of(Config.CAPS, INVOKE_ONLY)), alice);
		complete("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "trusted",
			Fields.CONFIG, Maps.of(Config.CAPS, Config.UNRESTRICTED)), alice);

		complete("v/ops/agent/fork", Maps.of("sourceId", "capped", Fields.AGENT_ID, "capped-copy"), alice);
		assertEquals(INVOKE_ONLY, shownCaps(alice, "capped-copy"));
		complete("v/ops/agent/fork", Maps.of("sourceId", "trusted", Fields.AGENT_ID, "trusted-copy"), alice);
		assertTrue(unrestricted(alice.getUserDID(), "trusted-copy"));

		complete("v/ops/agent/fork", Maps.of("sourceId", "capped", Fields.AGENT_ID, "cleared",
			Fields.CONFIG, Maps.of(Config.CAPS, null)), alice);
		assertFalse(unrestricted(alice.getUserDID(), "cleared"));
		assertEquals(Vectors.empty(), shownCaps(alice, "cleared"));
	}

	@Test
	public void capsAreAListOrUnrestricted(TestInfo info) {
		RequestContext alice = owner(info);
		Job created = run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "vague",
			Fields.CONFIG, Maps.of(Config.CAPS, "everything")), alice);
		assertNotEquals(Status.COMPLETE, created.getStatus());
		assertFalse(unrestricted(alice.getUserDID(), "vague"));
	}

	// ========== No authority of its own ==========

	/**
	 * An agent holding no authority still runs: the harness answers and
	 * dispatches its tool calls, and each operation refuses what the agent may
	 * not do, as a result the agent sees rather than a failed cycle.
	 */
	@Test
	public void anAgentWithNoAuthorityStillRunsItsHarness(TestInfo info) {
		RequestContext alice = owner(info);
		complete("v/ops/covia/write", Maps.of(Fields.PATH, "w/private", Fields.VALUE, "OWNER-ONLY"), alice);
		complete("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "bare", Fields.CONFIG, Maps.of(
			Fields.OPERATION, "v/ops/llmagent/chat",
			"llmOperation", "v/test/ops/llm",
			"systemPrompt", "You answer questions.",
			Fields.TOOLS, Vectors.of(Strings.create("v/ops/covia/read")))), alice);

		Job reply = run("v/ops/agent/step", Maps.of(Fields.AGENT_ID, "bare", Fields.MESSAGE, "Hello",
			"assistant", "Hello back"), alice);
		assertEquals(Status.COMPLETE, reply.getStatus(), reply.getErrorMessage());

		Job read = run("v/ops/agent/step", Maps.of(Fields.AGENT_ID, "bare", Fields.MESSAGE, "Read it",
			"assistant", Maps.of("toolCalls", Vectors.of(Maps.of("name", "covia_read",
				"arguments", Maps.of(Fields.PATH, "w/private"))))), alice);
		assertEquals(Status.COMPLETE, read.getStatus(), read.getErrorMessage());
		ACell call = RT.ensureVector(RT.getIn(read.getOutput(), "calls")).get(0);
		assertEquals("covia_read", RT.getIn(call, "name").toString());
		assertNotNull(RT.getIn(call, "isError"), "the read is refused: " + call);
		assertFalse(String.valueOf(read.getOutput()).contains("OWNER-ONLY"));
	}

	// ========== The venue's agents ==========

	/** Runs {@code body} with a new agent of the venue's with {@code caps} (none when null), deleting it after. */
	private void withVenueAgent(TestInfo info, ACell caps, java.util.function.Consumer<RequestContext> body) {
		AString venueDID = engine.getDIDString();
		AString id = venueAgentId(info);
		RequestContext venue = engine.venueContext();
		complete("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id,
			Fields.CONFIG, (caps == null) ? Maps.empty() : Maps.of(Config.CAPS, caps)), venue);
		try {
			body.accept(engine.context(venueDID, Principals.agentDID(venueDID, id)));
		} finally {
			run("v/ops/agent/delete", Maps.of(Fields.AGENT_ID, id, "remove", true), venue);
		}
	}

	/** An id for the venue agent of this test and run, which no other test uses. */
	private static AString venueAgentId(TestInfo info) {
		String did = TestEngine.uniqueDID(info).toString();
		return Strings.create(did.substring(did.lastIndexOf("-test-") + "-test-".length()));
	}

	/** Whether {@code agent} holds {@code ability} on the venue's {@code resource}, directly or by a grant it has the venue issue to itself. */
	private boolean administers(RequestContext agent, String resource, AString ability) {
		if (engine.hasVenueAuthority(agent, resource, ability)) return true;
		Job issued = run("v/ops/ucan/issue", Maps.of(
			UCAN.AUD, agent.getCallerDID(),
			UCAN.ATT, Vectors.of(Maps.of(Capability.WITH, engine.getDIDString() + "/" + resource, Capability.CAN, ability)),
			UCAN.EXP, CVMLong.create(engine.currentTimestamp().longValue() / 1000 + 3600)), agent);
		AString token = (issued.getStatus() == Status.COMPLETE) ? RT.ensureString(RT.getIn(issued.getOutput(), "token")) : null;
		return token != null && engine.hasVenueAuthority(engine.withTransportGrants(agent, Vectors.of(token)), resource, ability);
	}

	@Test
	public void anUnrestrictedVenueAgentAdministersTheVenue(TestInfo info) {
		withVenueAgent(info, Config.UNRESTRICTED, agent -> {
			assertTrue(engine.hasVenueAuthority(agent, "users", Abilities.USER_CREATE));
			assertTrue(engine.hasVenueAuthority(agent, "adapters", Abilities.ADAPTER_MANAGE));
		});
	}

	@Test
	public void aCappedVenueAgentAdministersOnlyWhatItsCapsName(TestInfo info) {
		withVenueAgent(info, Vectors.of(Maps.of(Capability.WITH, "users", Capability.CAN, Abilities.USER_CREATE)), agent -> {
			assertTrue(engine.hasVenueAuthority(agent, "users", Abilities.USER_CREATE));
			assertFalse(administers(agent, "adapters", Abilities.ADAPTER_MANAGE));
		});
	}

	@Test
	public void aVenueAgentCreatedWithoutCapsCannotAdministerTheVenue(TestInfo info) {
		withVenueAgent(info, null, agent -> assertFalse(administers(agent, "users", Abilities.USER_CREATE)));
	}

	/**
	 * The venue's authority is its own and its agents': an ordinary user, one
	 * it hosts, and anonymous callers under unrestricted public access hold
	 * none of it.
	 */
	@Test
	public void noOtherPrincipalAdministersTheVenue(TestInfo info) {
		for (RequestContext caller : new RequestContext[] {
				RequestContext.of(TestEngine.uniqueDID(info)),
				RequestContext.of(TestEngine.uniqueManagedDID("admin-check")),
				RequestContext.of(engine.publicPrincipal()),   // no scope: unrestricted public access
				RequestContext.ofAgent(TestEngine.uniqueDID(info), Strings.create("helper"))}) {
			assertFalse(engine.hasVenueAuthority(caller, "users", Abilities.USER_CREATE), caller.getCallerDID().toString());
			assertFalse(engine.hasVenueAuthority(caller, Engine.OUTBOUND, Abilities.OUTBOUND_ANY), caller.getCallerDID().toString());
		}
	}

	/** A record an earlier release stored without caps is unrestricted, as before. */
	@Test
	public void aVenueAgentStoredWithoutCapsAdministersTheVenue(TestInfo info) {
		AString venueDID = engine.getDIDString();
		AString id = venueAgentId(info);
		engine.getVenueState().users().ensure(venueDID).ensureAgent(id, Maps.empty(), null);
		try {
			RequestContext agent = engine.context(venueDID, Principals.agentDID(venueDID, id));
			assertTrue(engine.hasVenueAuthority(agent, "users", Abilities.USER_CREATE));
			assertEquals(Config.UNRESTRICTED, shownCaps(engine.venueContext(), id.toString()));
		} finally {
			run("v/ops/agent/delete", Maps.of(Fields.AGENT_ID, id, "remove", true), engine.venueContext());
		}
	}
}
