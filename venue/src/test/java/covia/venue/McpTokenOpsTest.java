package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.MCPAdapter;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.Caps;

/**
 * The MCP token operations (MCP_TOKENS.md): what a token may be named and
 * offer, what the operations show of it, and that a user's tokens go with
 * the user. Who may use them is {@link McpTokenAuthorityTest}'s.
 */
public class McpTokenOpsTest {

	private final Engine engine = TestEngine.ENGINE;

	private Job run(String op, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		TestEngine.awaitJob(job, Job::isFinished, 10000, () -> op + " never finished");
		return job;
	}

	private static AMap<AString, ACell> complete(Job job) {
		assertEquals(Status.COMPLETE, job.getStatus(), job::getErrorMessage);
		return RT.ensureMap(job.getOutput());
	}

	private static String failure(Job job) {
		assertEquals(Status.FAILED, job.getStatus(), () -> "expected a refusal, got " + job.getData());
		return job.getErrorMessage();
	}

	private Job create(RequestContext owner, String name, AVector<ACell> tools) {
		return run("v/ops/mcp/create-token", Maps.of(Fields.NAME, name, Fields.TOOLS, tools), owner);
	}

	@Test
	public void aTokensNameAndToolsAreChecked(TestInfo info) {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		AVector<ACell> read = Vectors.of("v/ops/covia/read");

		assertEquals(MCPAdapter.TOKEN_NAME_INVALID, failure(create(owner, "Not A Name", read)));
		assertEquals(MCPAdapter.TOOLS_INVALID, failure(run("v/ops/mcp/create-token", Maps.of(Fields.NAME, "none"), owner)));
		assertEquals(MCPAdapter.TOOL_NOT_HELD.formatted(1, "v/ops/no/such-op"),
			failure(create(owner, "missing", Vectors.of("v/ops/covia/read", "v/ops/no/such-op"))));
		assertEquals(MCPAdapter.TOOL_NAMES_CLASH.formatted("covia_read"), failure(create(owner, "clash",
			Vectors.of("v/ops/covia/read", Maps.of(Fields.OPERATION, "v/ops/covia/write", Fields.NAME, "covia_read")))));
		assertEquals(MCPAdapter.TOOL_NAME_INVALID.formatted(0, "has space"), failure(create(owner, "spaced",
			Vectors.of(Maps.of(Fields.OPERATION, "v/ops/covia/read", Fields.NAME, "has space")))));

		// A name of its own settles a clash
		complete(create(owner, "renamed",
			Vectors.of("v/ops/covia/read", Maps.of(Fields.OPERATION, "v/ops/covia/write", Fields.NAME, "covia_put"))));
		complete(create(owner, "taken", read));
		assertEquals(MCPAdapter.TOKEN_EXISTS.formatted("taken"), failure(create(owner, "taken", read)));
		assertEquals(MCPAdapter.TOKEN_NOT_FOUND.formatted("absent"),
			failure(run("v/ops/mcp/update-token", Maps.of(Fields.NAME, "absent", Fields.TOOLS, read), owner)));
	}

	@Test
	public void theOperationsShowATokenButNeverItsBearerAgain(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(alice);
		AMap<AString, ACell> created = complete(create(owner, "shown", Vectors.of("v/ops/covia/read")));
		assertNotNull(created.get(Fields.TOKEN), "creating it hands out the bearer, once");
		assertEquals(Vectors.empty(), created.get(Fields.CAPS), "caps left out are none");
		assertEquals(alice, created.get(Fields.ISSUER));

		AMap<AString, ACell> updated = complete(run("v/ops/mcp/update-token", Maps.of(Fields.NAME, "shown",
			Fields.CAPS, Caps.UNRESTRICTED, Fields.TOOLS, Vectors.of("v/ops/covia/read", "v/ops/covia/list")), owner));
		assertNull(updated.get(Fields.TOKEN), "a change that keeps the bearer does not show it");
		assertEquals(Strings.create(Caps.UNRESTRICTED), updated.get(Fields.CAPS));

		AVector<ACell> listed = RT.ensureVector(complete(run("v/ops/mcp/list-tokens", Maps.empty(), owner)).get(Strings.create("tokens")));
		assertEquals(1, listed.count());
		assertEquals(Strings.create("shown"), RT.getIn(listed.get(0), Fields.NAME));
		assertEquals(2, RT.ensureVector(RT.getIn(listed.get(0), Fields.TOOLS)).count());
		assertNull(RT.getIn(listed.get(0), Fields.TOKEN));
		assertEquals(Vectors.empty(), complete(run("v/ops/mcp/list-tokens", Maps.empty(),
			RequestContext.of(TestEngine.uniqueDID("other-" + info.getDisplayName())))).get(Strings.create("tokens")),
			"each user lists their own");
	}

	@Test
	public void aUsersTokensGoWithTheUser(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		String bearer = RT.getIn(complete(create(RequestContext.of(alice), "doomed", Vectors.of("v/ops/covia/read"))),
			Fields.TOKEN).toString();
		assertNotNull(engine.mcpTokens().lookup(bearer));

		engine.deleteUser(engine.venueContext(), alice);
		assertNull(engine.mcpTokens().lookup(bearer));
		assertNull(engine.mcpTokens().token(alice, Strings.create("doomed")).record());
	}
}
