package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.MCPAdapter;
import covia.adapter.McpToken;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Operation;
import covia.grid.Status;

/**
 * MCP tokens on a venue of its own: on a clock the test drives, so a token
 * expires when the test says, and configured to keep each token's bearer,
 * which the test then turns off and on again (MCP_TOKENS.md).
 */
@Execution(ExecutionMode.SAME_THREAD) // The tests change the clock and the adapter's configuration
public class McpTokenVenueTest {

	private static final DrivenClock CLOCK = DrivenClock.at(Instant.parse("2021-01-01T00:00:00Z"));
	private static final AMap<AString, ACell> KEEP = Maps.of("keepRawTokens", true);
	private static Engine engine;

	@BeforeAll
	static void launch() {
		engine = Engine.createTemp(CLOCK, Maps.of(Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.ADAPTERS, Maps.of("mcp", KEEP)));
		Engine.addDemoAssets(engine);
	}

	@AfterAll
	static void close() {
		engine.close();
	}

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

	private Job create(RequestContext issuer, AMap<AString, ACell> input) {
		return run("v/ops/mcp/create-token", input.assoc(Fields.TOOLS, Vectors.of("v/ops/covia/read")), issuer);
	}

	private static String bearer(ACell result) {
		return RT.getIn(result, Fields.TOKEN).toString();
	}

	private Job get(RequestContext ctx, String name) {
		return run("v/ops/mcp/get-token", Maps.of(Fields.NAME, name), ctx);
	}

	/** The request of the token {@code bearer} names calls tool {@code ref}, as the MCP endpoint would. */
	private Job tool(String bearer, String ref, ACell input) {
		AMap<AString, ACell> reference = engine.mcpTokens().lookup(bearer);
		McpToken token = engine.resolveToken(reference);
		Operation operation = engine.jobs().resolveOperation(Strings.create(ref), engine.context(token.owner(), token.issuer()));
		Job job = engine.jobs().startToolOperation(Strings.create(ref), operation, input, engine.tokenContext(reference)).job();
		TestEngine.awaitJob(job, Job::isFinished, 10000, () -> ref + " never finished");
		return job;
	}

	@Test
	public void aTokenStopsStandingWhenItExpires(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(alice);
		long now = engine.currentTimestamp().longValue();
		assertEquals(MCPAdapter.EXPIRES_INVALID,
			failure(create(owner, Maps.of(Fields.NAME, "past", Fields.EXPIRES, CVMLong.create(now)))));

		String bearer = bearer(complete(create(owner, Maps.of(Fields.NAME, "brief", Fields.EXPIRES, CVMLong.create(now + 60_000),
			Fields.CAPS, covia.lattice.Caps.UNRESTRICTED))));
		AMap<AString, ACell> reference = engine.mcpTokens().lookup(bearer);
		RequestContext through = engine.tokenContext(reference);
		AString doc = Strings.create(alice + "/w/doc");
		assertTrue(engine.authorityCovers(through, doc, Capability.CRUD_READ));

		CLOCK.advance(59_999);
		engine.application().update();
		assertNotNull(engine.resolveToken(reference), "it stands until its expiry");
		CLOCK.advance(1);
		engine.application().update();
		assertNull(engine.resolveToken(reference));
		assertThrows(AuthException.class, () -> engine.tokenContext(reference));
		assertFalse(engine.authorityCovers(through, doc, Capability.CRUD_READ), "a context built before holds nothing after");
	}

	@Test
	public void theVenueKeepsBearersWhileConfiguredTo(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(alice);
		String first = bearer(complete(create(owner, Maps.of(Fields.NAME, "kept"))));
		assertEquals(first, bearer(complete(get(owner, "kept"))));

		String rotated = bearer(complete(run("v/ops/mcp/update-token", Maps.of(Fields.NAME, "kept", Fields.ROTATE, true), owner)));
		assertNotEquals(first, rotated);
		assertNull(engine.mcpTokens().lookup(first), "rotation retires the old bearer");
		assertEquals(rotated, bearer(complete(get(owner, "kept"))));

		try {
			engine.configureAdapter("mcp", Maps.empty());
			assertNull(engine.mcpTokens().token(alice, Strings.create("kept")).raw(), "turning it off drops kept bearers");
			assertEquals(MCPAdapter.RAW_NOT_KEPT, failure(get(owner, "kept")));
			assertNotNull(engine.mcpTokens().lookup(rotated), "the token itself still stands");

			engine.configureAdapter("mcp", KEEP);
			assertEquals(MCPAdapter.RAW_NOT_HELD.formatted("kept"), failure(get(owner, "kept")));
			String again = bearer(complete(run("v/ops/mcp/update-token", Maps.of(Fields.NAME, "kept", Fields.ROTATE, true), owner)));
			assertEquals(again, bearer(complete(get(owner, "kept"))));
		} finally {
			engine.configureAdapter("mcp", KEEP);
		}
	}

	@Test
	public void readingABearerAgainNeedsTheAdminAbilityAndTheTokensSource(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(alice);
		AMap<AString, ACell> tools = Maps.of(Fields.TOOLS, Vectors.of("v/ops/mcp/get-token", "v/ops/mcp/create-token"));
		ACell manage = cap("x/mcp/tokens/", Abilities.MCP_TOKEN);
		ACell admin = cap("x/mcp/tokens/", Abilities.ADMIN_TOKEN_MCP);
		String plain = bearer(complete(run("v/ops/mcp/create-token",
			tools.assoc(Fields.NAME, Strings.create("plain")).assoc(Fields.CAPS, Vectors.of(manage)), owner)));
		String administrator = bearer(complete(run("v/ops/mcp/create-token",
			tools.assoc(Fields.NAME, Strings.create("administrator")).assoc(Fields.CAPS, Vectors.of(manage, admin)), owner)));

		// Without the ability, not even a token it made
		bearer(complete(tool(plain, "v/ops/mcp/create-token", Maps.of(Fields.NAME, "plain-child", Fields.TOOLS, Vectors.of("v/ops/covia/read")))));
		failure(tool(plain, "v/ops/mcp/get-token", Maps.of(Fields.NAME, "plain-child")));

		// With it, a token it made, but never itself or a token its issuer made directly
		String child = bearer(complete(tool(administrator, "v/ops/mcp/create-token",
			Maps.of(Fields.NAME, "admin-child", Fields.TOOLS, Vectors.of("v/ops/covia/read")))));
		assertEquals(child, bearer(complete(tool(administrator, "v/ops/mcp/get-token", Maps.of(Fields.NAME, "admin-child")))));
		assertEquals(MCPAdapter.TOKEN_SOURCE_NOT_HELD.formatted("administrator"),
			failure(tool(administrator, "v/ops/mcp/get-token", Maps.of(Fields.NAME, "administrator"))));
		assertEquals(MCPAdapter.TOKEN_SOURCE_NOT_HELD.formatted("plain"),
			failure(tool(administrator, "v/ops/mcp/get-token", Maps.of(Fields.NAME, "plain"))));
	}

	@Test
	public void bearerRetrievalAcceptsMcpLeafAndParentsButNotOtherTokenKinds(TestInfo info) {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		String[] abilities = {"admin/token/mcp", "admin/token", "admin", "admin/token/other"};
		for (int i = 0; i < abilities.length; i++) {
			String source = bearer(complete(run("v/ops/mcp/create-token", Maps.of(
				Fields.NAME, "source-" + i,
				Fields.TOOLS, Vectors.of("v/ops/mcp/create-token", "v/ops/mcp/get-token"),
				Fields.CAPS, Vectors.of(cap("x/mcp/tokens/", Abilities.MCP_TOKEN),
					cap("x/mcp/tokens/", Strings.create(abilities[i])))), owner)));
			String name = "child-" + i;
			String child = bearer(complete(tool(source, "v/ops/mcp/create-token",
				Maps.of(Fields.NAME, name, Fields.TOOLS, Vectors.of("v/ops/covia/read")))));
			Job read = tool(source, "v/ops/mcp/get-token", Maps.of(Fields.NAME, name));
			if (i < 3) assertEquals(child, bearer(complete(read)), abilities[i]);
			else failure(read);
		}
	}
}
