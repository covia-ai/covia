package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.McpTokens;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;
import covia.lattice.Caps;

/**
 * One agent builds a set of tools for another and hands it a token, and the
 * other agent uses them over the venue's MCP endpoint (MCP_TOKENS.md): the
 * token offers exactly its tools, acts within its creator's authority and its
 * own caps, and stops working when revoked. Each refusal has a control that
 * differs from it in one thing and succeeds.
 */
public class McpTokenAgentsTest {

	private final Engine engine = TestServer.ENGINE;
	private static final String MCP_URL = TestServer.BASE_URL + "/mcp";

	private Job run(String op, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		TestEngine.awaitJob(job, Job::isFinished, 10000, () -> op + " never finished");
		return job;
	}

	private static ACell complete(Job job) {
		assertEquals(Status.COMPLETE, job.getStatus(), job::getErrorMessage);
		return job.getOutput();
	}

	private static void failed(Job job) {
		assertEquals(Status.FAILED, job.getStatus(), () -> "expected a refusal, got " + job.getData());
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

	private String createToken(RequestContext issuer, String name, AVector<ACell> tools, ACell caps) {
		return RT.getIn(complete(run("v/ops/mcp/create-token",
			Maps.of(Fields.NAME, name, Fields.TOOLS, tools, Fields.CAPS, caps), issuer)), Fields.TOKEN).toString();
	}

	/** {@code worker} calls tool {@code tool} on the venue's MCP endpoint with {@code token}. */
	private Job call(RequestContext worker, String token, String tool, AMap<AString, ACell> arguments) {
		return run("v/ops/mcp/tools-call", Maps.of(Fields.SERVER, MCP_URL, Fields.TOOL_NAME, tool,
			Fields.ARGUMENTS, arguments, Fields.TOKEN, token), worker);
	}

	private Job list(RequestContext worker, String token) {
		return run("v/ops/mcp/tools-list", Maps.of(Fields.SERVER, MCP_URL, Fields.TOKEN, token), worker);
	}

	private Set<String> toolNames(RequestContext worker, String token) {
		Set<String> names = new HashSet<>();
		for (ACell tool : RT.ensureVector(RT.getIn(complete(list(worker, token)), "tools"))) {
			names.add(RT.getIn(tool, Fields.NAME).toString());
		}
		return names;
	}

	private static AMap<AString, ACell> write(String path, Object value) {
		return Maps.of(Fields.PATH, path, Fields.VALUE, value);
	}

	private static AMap<AString, ACell> read(String path) {
		return Maps.of(Fields.PATH, path);
	}

	@Test
	public void anAgentHandsAnotherAgentATokenForItsTools(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());

		// Alice's builder may run operations, make tokens and use w/shared/, and nothing else
		RequestContext builder = agent(alice, "builder", Vectors.of(
			cap("v/ops/", Abilities.INVOKE),
			cap("x/mcp/tokens/", Abilities.MCP_TOKEN),
			cap("w/shared/", Capability.CRUD)));
		// Bob's worker holds no authority of Alice's at all
		RequestContext worker = agent(bob, "worker", Caps.UNRESTRICTED);
		// Alice sets up the shared area: creating it is a change to w/, which the caps do not reach
		complete(run("v/ops/covia/write", write("w/shared/readme", "shared"), RequestContext.of(alice)));

		Job created = run("v/ops/mcp/create-token", Maps.of(Fields.NAME, "for-worker",
			Fields.TOOLS, Vectors.of("v/ops/covia/read", "v/ops/covia/write"),
			Fields.CAPS, Vectors.of(cap("w/shared/", Capability.CRUD))), builder);
		AMap<AString, ACell> result = RT.ensureMap(complete(created));
		String token = RT.getIn(result, Fields.TOKEN).toString();
		assertTrue(McpTokens.isBearer(token), token);
		assertEquals(builder.getCallerDID(), RT.getIn(result, Fields.ISSUER));
		assertEquals(Fields.HIDDEN, RT.getIn(engine.jobs().getJobData(created.getID(), builder), Fields.OUTPUT, Fields.TOKEN),
			"the job record never keeps the bearer");

		// The worker sees exactly the tools it was given
		assertEquals(Set.of("covia_read", "covia_write"), toolNames(worker, token));

		// It acts in Alice's namespace, within the token's caps
		Job wrote = call(worker, token, "covia_write", write("w/shared/report", "done"));
		complete(wrote);
		assertEquals(Fields.HIDDEN, RT.getIn(engine.jobs().getJobData(wrote.getID(), worker), Fields.INPUT, Fields.TOKEN),
			"the caller's job record never keeps the bearer either");
		assertEquals(Strings.create("done"), RT.getIn(complete(run("v/ops/covia/read",
			read("w/shared/report"), RequestContext.of(alice))), Fields.VALUE));
		assertEquals(Strings.create("done"), RT.getIn(complete(call(worker, token, "covia_read", read("w/shared/report"))), Fields.VALUE));
		failed(call(worker, token, "covia_write", write("w/private/x", 1)));
		failed(call(worker, token, "covia_delete", read("w/shared/report")));

		// A token never exceeds its creator: unrestricted caps are the builder's own
		String wide = createToken(builder, "wide", Vectors.of("v/ops/covia/write"), Caps.UNRESTRICTED);
		assertEquals(Set.of("covia_write"), toolNames(worker, wide), "each bearer offers its own tools");
		failed(call(worker, wide, "covia_read", read("w/shared/report")));
		failed(call(worker, wide, "covia_write", write("w/private/x", 1)));
		complete(call(worker, wide, "covia_write", write("w/shared/wide", 1)));

		// Emptying the caps revokes what the token may do; deleting it, the token itself
		complete(run("v/ops/mcp/update-token", Maps.of(Fields.NAME, "for-worker", Fields.CAPS, Vectors.empty()), builder));
		failed(call(worker, token, "covia_write", write("w/shared/report", "again")));
		complete(call(worker, wide, "covia_write", write("w/shared/report", "again")));
		complete(run("v/ops/mcp/delete-token", Maps.of(Fields.NAME, "for-worker"), builder));
		failed(list(worker, token));
		complete(list(worker, wide));
	}

	@Test
	public void makingATokenNeedsTheTokenAbility(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		ACell invoke = cap("v/ops/", Abilities.INVOKE);
		ACell shared = cap("w/shared/", Capability.CRUD);
		AMap<AString, ACell> input = Maps.of(Fields.NAME, "made", Fields.TOOLS, Vectors.of("v/ops/covia/read"));

		failed(run("v/ops/mcp/create-token", input, agent(alice, "plain", Vectors.of(invoke, shared))));
		complete(run("v/ops/mcp/create-token", input,
			agent(alice, "maker", Vectors.of(invoke, shared, cap("x/mcp/tokens/", Abilities.MCP_TOKEN)))));
	}
}
