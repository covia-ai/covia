package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.adapter.callable.CallableAdapter;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;
import covia.lattice.Caps;

/**
 * Caps given to an access token or callable lie within what the request
 * giving them may do itself ({@link Engine#capsDenial}), when they are set and
 * whenever they change: a capped agent's or a call's. Each refusal of caps
 * beyond the giver's has a control within them that succeeds.
 */
class GivenCapsTest {

	private static final Engine engine = TestEngine.ENGINE;

	private static final ACell INVOKE = cap("v/ops/", Abilities.INVOKE);

	private static ACell cap(String with, AString can) {
		return Capability.create(Strings.create(with), can);
	}

	private static Job run(String op, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		TestEngine.awaitJob(job, Job::isFinished, 10_000, () -> op + " never finished");
		return job;
	}

	private static void complete(Job job) {
		assertEquals(Status.COMPLETE, job.getStatus(), job::getErrorMessage);
	}

	/** Asserts {@code job} was refused for giving caps beyond its giver's. */
	private static void beyond(Job job, String why) {
		assertEquals(Status.FAILED, job.getStatus(), () -> why + ": expected a refusal, got " + job.getData());
		assertTrue(job.getErrorMessage().startsWith(Engine.CAPS_BEYOND_GIVER), () -> why + ": " + job.getErrorMessage());
	}

	private static Job createAgent(RequestContext ctx, String id, ACell caps) {
		return run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id,
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo", Fields.CAPS, caps)), ctx);
	}

	/** Agent {@code id} of {@code owner} with {@code caps}, made by its owner, as it acts. */
	private static RequestContext agent(AString owner, String id, ACell caps) {
		complete(createAgent(RequestContext.of(owner), id, caps));
		return engine.context(owner, Principals.agentDID(owner, Strings.create(id)));
	}

	@Test void aCappedAgentGivesACallableNoMoreThanItsOwnCaps(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		RequestContext maker = agent(alice, "maker", Vectors.of(INVOKE,
			cap("x/callable/", Abilities.CALLABLE_MANAGE), cap("w/guestbook", Capability.CRUD_WRITE)));
		AMap<AString, ACell> append = Maps.of(Fields.OPERATION, "v/ops/covia/append");

		beyond(run("v/ops/callable/create", append.assoc(Fields.NAME, Strings.create("wide"))
			.assoc(Fields.CAPS, Vectors.of(cap("w/", Capability.CRUD_WRITE))), maker), "a callable wider than its maker");
		beyond(run("v/ops/callable/create", append.assoc(Fields.NAME, Strings.create("free"))
			.assoc(Fields.CAPS, Caps.UNRESTRICTED), maker), "a callable with no limit");

		complete(run("v/ops/callable/create", append.assoc(Fields.NAME, Strings.create("book"))
			.assoc(Fields.CAPS, Vectors.of(cap("w/guestbook", Capability.CRUD_WRITE))), maker));
		beyond(run("v/ops/callable/update", Maps.of(Fields.NAME, "book", Fields.CAPS, Caps.UNRESTRICTED), maker),
			"widening it later");
		complete(run("v/ops/callable/update", Maps.of(Fields.NAME, "book", Fields.DESCRIPTION, "Sign here"), maker));
	}

	@Test void aCallGivesNoMoreThanItsCallablesCaps(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		RequestContext bob = RequestContext.of(TestEngine.uniqueDID("bob-" + info.getDisplayName()));
		complete(run("v/ops/callable/create", Maps.of(Fields.NAME, "maker", Fields.OPERATION, "v/ops/callable/create",
			Fields.GATE, "v/ops/callable/gates/anyone", Fields.CAPS, Vectors.of(INVOKE,
				cap("x/callable/", Abilities.CALLABLE_MANAGE), cap("w/guestbook", Capability.CRUD_WRITE))), RequestContext.of(alice)));
		AMap<AString, ACell> append = Maps.of(Fields.OPERATION, "v/ops/covia/append");

		beyond(call(bob, alice + "/x/callable/maker", append.assoc(Fields.NAME, Strings.create("wide"))
			.assoc(Fields.CAPS, Vectors.of(cap("w/", Capability.CRUD_WRITE)))), "a callable wider than the call");
		complete(call(bob, alice + "/x/callable/maker", append.assoc(Fields.NAME, Strings.create("book"))
			.assoc(Fields.CAPS, Vectors.of(cap("w/guestbook", Capability.CRUD_WRITE)))));
		assertEquals(alice, ((CallableAdapter) engine.getAdapter(CallableAdapter.NAME)).callables()
			.callable(alice, Strings.create("book")).issuer(),
			"what a call makes is its issuer's");
	}

	private static Job call(RequestContext ctx, String callable, ACell input) {
		return run("v/ops/callable/call", Maps.of(Fields.CALLABLE, callable, Fields.INPUT, input), ctx);
	}

	@Test void aCappedAgentGivesATokenNoMoreThanItsOwnCaps(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		RequestContext maker = agent(alice, "maker", Vectors.of(INVOKE,
			cap("x/token/", Abilities.MCP_TOKEN), cap("w/notes", Capability.CRUD_READ)));
		ACell tools = Vectors.of("v/ops/covia/read");

		beyond(run("v/ops/mcp/create-token", Maps.of(Fields.NAME, "wide", Fields.TOOLS, tools,
			Fields.CAPS, Vectors.of(cap("w/", Capability.CRUD_READ))), maker), "a token wider than its maker");
		complete(run("v/ops/mcp/create-token", Maps.of(Fields.NAME, "notes", Fields.TOOLS, tools,
			Fields.CAPS, Vectors.of(cap("w/notes", Capability.CRUD_READ))), maker));
		beyond(run("v/ops/mcp/update-token", Maps.of(Fields.NAME, "notes",
			Fields.CAPS, Vectors.of(cap("w/", Capability.CRUD_READ))), maker), "widening it later");
	}
}
