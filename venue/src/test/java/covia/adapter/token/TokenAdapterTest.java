package covia.adapter.token;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.MCPAdapter;
import covia.adapter.TestAdapter;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * The capabilities on access tokens (ACCESS_TOKENS.md), with the test
 * adapter as a second access point beside MCP: working on a token directly
 * needs {@code admin/token/<p>} for every access point it carries, and the MCP
 * token operations reach only tokens accepted at MCP alone. Each refusal has
 * a control that differs from it in one thing and succeeds.
 */
public class TokenAdapterTest {

	private final Engine engine = TestEngine.ENGINE;

	private static final AString TEST = Strings.intern("test");
	private static final AMap<AString, ACell> MCP = Maps.of(MCPAdapter.ACCESS_POINT, Maps.of(Fields.TOOLS, Vectors.of("v/ops/covia/read")));
	private static final AMap<AString, ACell> BOTH = MCP.assoc(TEST, Maps.empty());

	private Job run(String op, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		TestEngine.awaitJob(job, Job::isFinished, 10000, () -> op + " never finished");
		return job;
	}

	private static ACell complete(Job job) {
		assertEquals(Status.COMPLETE, job.getStatus(), job::getErrorMessage);
		return job.getOutput();
	}

	private static String failure(Job job) {
		assertEquals(Status.FAILED, job.getStatus(), () -> "expected a refusal, got " + job.getData());
		return job.getErrorMessage();
	}

	private static ACell cap(String with, AString can) {
		return Capability.create(Strings.create(with), can);
	}

	/** Creates agent {@code id} of {@code owner} with {@code caps}, and returns the context it acts in. */
	private RequestContext agent(AString owner, String id, ACell caps) {
		complete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id,
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo", Fields.CAPS, caps)), RequestContext.of(owner)));
		return engine.context(owner, Principals.agentDID(owner, Strings.create(id)));
	}

	private Job create(RequestContext ctx, String name, AMap<AString, ACell> access) {
		return run("v/ops/token/create", Maps.of(Fields.NAME, name, Fields.ACCESS, access), ctx);
	}

	private Job named(String op, RequestContext ctx, String name) {
		return run(op, Maps.of(Fields.NAME, name), ctx);
	}

	private static Set<String> names(ACell listed) {
		Set<String> names = new HashSet<>();
		for (ACell token : RT.ensureVector(RT.getIn(listed, "tokens"))) names.add(RT.getIn(token, Fields.NAME).toString());
		return names;
	}

	@Test
	public void directTokenWorkNeedsTheAdminLeafOfEveryAccessPointCarried(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(alice);
		ACell invoke = cap("v/ops/", Abilities.INVOKE);
		ACell mcpLeaf = cap("x/tokens/", Abilities.ADMIN_TOKEN_MCP);
		ACell testLeaf = cap("x/tokens/", TokenAdapter.administers(TEST));

		// An owner holds every leaf in its own namespace
		complete(create(owner, "owned", BOTH));

		// Making a token needs the leaf of each access point it names, or their parent
		RequestContext mcpAdmin = agent(alice, "mcp-admin", Vectors.of(invoke, mcpLeaf));
		failure(create(mcpAdmin, "wider", BOTH));
		complete(create(mcpAdmin, "narrow", MCP));
		complete(create(agent(alice, "both-admin", Vectors.of(invoke, mcpLeaf, testLeaf)), "both", BOTH));
		complete(create(agent(alice, "parent", Vectors.of(invoke, cap("x/tokens/", Abilities.ADMIN_TOKEN))), "parented", BOTH));

		// Changing one needs the leaves of what it carries before and after
		failure(run("v/ops/token/update", Maps.of(Fields.NAME, "narrow", Fields.ACCESS, BOTH), mcpAdmin));
		complete(run("v/ops/token/update", Maps.of(Fields.NAME, "narrow", Fields.CAPS, Vectors.empty()), mcpAdmin));

		// Reading its bearer again: past the gate, this venue keeps none
		failure(named("v/ops/token/get", mcpAdmin, "owned"));
		assertEquals(TokenAdapter.RAW_NOT_KEPT, failure(named("v/ops/token/get", mcpAdmin, "narrow")));

		// Deleting one needs the leaves of what it carries
		failure(named("v/ops/token/delete", mcpAdmin, "owned"));
		complete(named("v/ops/token/delete", mcpAdmin, "narrow"));
		assertNull(engine.tokens().token(alice, Strings.create("narrow")).record());
		assertNotNull(engine.tokens().token(alice, Strings.create("owned")).record());
	}

	@Test
	public void theMcpTokenOperationsReachOnlyTokensAcceptedAtMcpAlone(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(alice);
		complete(create(owner, "both", BOTH));
		complete(run("v/ops/mcp/create-token", Maps.of(Fields.NAME, "plain", Fields.TOOLS, Vectors.of("v/ops/covia/read")), owner));

		String beyond = TokenAdapter.NOT_ONLY_AT.formatted("both", MCPAdapter.ACCESS_POINT);
		assertEquals(beyond, failure(run("v/ops/mcp/update-token", Maps.of(Fields.NAME, "both", Fields.CAPS, Vectors.empty()), owner)));
		assertEquals(beyond, failure(named("v/ops/mcp/delete-token", owner, "both")));
		assertEquals(beyond, failure(named("v/ops/mcp/get-token", owner, "both")));
		complete(run("v/ops/mcp/update-token", Maps.of(Fields.NAME, "plain", Fields.CAPS, Vectors.empty()), owner));

		assertEquals(Set.of("plain"), names(complete(run("v/ops/mcp/list-tokens", Maps.empty(), owner))));
		assertEquals(Set.of("both", "plain"), names(complete(run("v/ops/token/list", Maps.empty(), owner))));

		// Taking an MCP token beyond MCP takes it out of the MCP operations' reach
		complete(run("v/ops/token/update", Maps.of(Fields.NAME, "plain", Fields.ACCESS, BOTH), owner));
		assertEquals(TokenAdapter.NOT_ONLY_AT.formatted("plain", MCPAdapter.ACCESS_POINT),
			failure(named("v/ops/mcp/delete-token", owner, "plain")));
		complete(named("v/ops/token/delete", owner, "plain"));
	}

	@Test
	public void aTokensAccessNamesRunningAccessPointsWithEntriesTheyAccept(TestInfo info) {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		assertEquals(TokenAdapter.ACCESS_INVALID, failure(create(owner, "none", Maps.empty())));
		assertEquals(TokenAdapter.ACCESS_POINT_UNKNOWN.formatted("nowhere"),
			failure(create(owner, "nowhere", Maps.of("nowhere", Maps.empty()))));
		assertEquals(TokenAdapter.ACCESS_POINT_UNKNOWN.formatted("secret"),
			failure(create(owner, "not-one", Maps.of("secret", Maps.empty()))), "a running adapter that accepts no tokens");
		assertEquals(TestAdapter.TOKEN_ENTRY_INVALID, failure(create(owner, "bad", Maps.of(TEST, "x"))));
		assertEquals(MCPAdapter.TOOLS_INVALID, failure(create(owner, "no-tools", Maps.of(MCPAdapter.ACCESS_POINT, Maps.empty()))));
		AMap<AString, ACell> made = RT.ensureMap(complete(create(owner, "fine", Maps.of(TEST, Maps.of("x", 1)))));
		assertEquals(Maps.of(TEST, Maps.of("x", 1)), made.get(Fields.ACCESS), "an access point keeps the entry it accepted");
		assertTrue(AccessTokens.isBearer(RT.getIn(made, Fields.TOKEN).toString()));
	}
}
