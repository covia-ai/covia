package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

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
import covia.adapter.McpToken;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Operation;
import covia.grid.Principals;
import covia.grid.Status;
import covia.lattice.Caps;

/**
 * What a request through an MCP token may do (MCP_TOKENS.md): its issuer's
 * authority as it stands at each point of action, narrowed by the caps of the
 * token and of every token whose request created it; never the issuer's whole
 * authority while any of those is restricted; and nothing once one is gone.
 * Tools are called as the venue's MCP endpoint calls them: resolved as the
 * issuer, run as the token's request.
 */
public class McpTokenAuthorityTest {

	private final Engine engine = TestEngine.ENGINE;

	/** A token as its holder has it: the bearer, and the reference the venue resolves it to. */
	private record Issued(String bearer, AMap<AString, ACell> ref) {}

	private Job run(String op, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		TestEngine.awaitJob(job, Job::isFinished, 10000, () -> op + " never finished");
		return job;
	}

	private static ACell complete(Job job) {
		assertEquals(Status.COMPLETE, job.getStatus(), job::getErrorMessage);
		return job.getOutput();
	}

	private static ACell cap(String with, AString can) {
		return Capability.create(Strings.create(with), can);
	}

	private Issued issue(RequestContext issuer, String name, AVector<ACell> tools, ACell caps) {
		String bearer = RT.getIn(complete(run("v/ops/mcp/create-token",
			Maps.of(Fields.NAME, name, Fields.TOOLS, tools, Fields.CAPS, caps), issuer)), Fields.TOKEN).toString();
		return new Issued(bearer, engine.mcpTokens().lookup(bearer));
	}

	/** The token's request calls tool {@code ref}, as the MCP endpoint would. */
	private CompletableFuture<ACell> tool(Issued token, String ref, ACell input) {
		McpToken standing = engine.resolveToken(token.ref());
		Operation operation = engine.jobs().resolveOperation(Strings.create(ref),
			engine.context(standing.owner(), standing.issuer()));
		return engine.jobs().startToolOperation(Strings.create(ref), operation, input, engine.tokenContext(token.ref())).result();
	}

	private static ACell done(CompletableFuture<ACell> result) {
		try {
			return result.get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			return fail("expected the call to succeed", e);
		}
	}

	private static void refused(CompletableFuture<ACell> result) {
		assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS), "expected a refusal");
	}

	private boolean mayWrite(RequestContext ctx, AString owner, String path) {
		return engine.authorityCovers(ctx, Strings.create(owner + "/" + path), Capability.CRUD_WRITE);
	}

	private RequestContext agent(AString owner, String id, ACell caps) {
		complete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id,
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo", Fields.CAPS, caps)), RequestContext.of(owner)));
		return engine.context(owner, Principals.agentDID(owner, Strings.create(id)));
	}

	private void deleteToken(AString owner, String name) {
		complete(run("v/ops/mcp/delete-token", Maps.of(Fields.NAME, name), RequestContext.of(owner)));
	}

	@Test
	public void whatATokenMadeHoldsNothingOnceTheTokenIsGone(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		Issued maker = issue(RequestContext.of(alice), "maker", Vectors.of("v/ops/agent/create"), Caps.UNRESTRICTED);

		// An agent the token's request creates acts with the token's authority, not Alice's own
		done(tool(maker, "v/ops/agent/create", Maps.of(Fields.AGENT_ID, "made",
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo"))));
		assertEquals(maker.ref(), engine.getAgent(alice, Strings.create("made")).getControllerToken());
		RequestContext harness = engine.harnessContext(alice, Strings.create("made"));
		assertTrue(mayWrite(harness, alice, "w/private/x"), "an unrestricted token carries its issuer's authority");

		// A job or schedule keeps the token, and acts with it again
		AMap<AString, ACell> kept = Engine.keptAuthority(engine.tokenContext(maker.ref()));
		assertEquals(maker.ref(), kept.get(Fields.MCP_TOKEN));
		RequestContext resumed = engine.context(alice, alice, null, kept);
		assertTrue(mayWrite(resumed, alice, "w/private/x"));

		deleteToken(alice, "maker");
		assertFalse(mayWrite(harness, alice, "w/private/x"), "a context already built reads the token at each check");
		assertFalse(mayWrite(engine.harnessContext(alice, Strings.create("made")), alice, "w/private/x"));
		assertFalse(mayWrite(resumed, alice, "w/private/x"));
		assertFalse(mayWrite(engine.context(alice, alice, null, kept), alice, "w/private/x"));
		assertThrows(AuthException.class, () -> engine.tokenContext(maker.ref()));

		// A new token of the same name is another token
		issue(RequestContext.of(alice), "maker", Vectors.of("v/ops/agent/create"), Caps.UNRESTRICTED);
		assertFalse(mayWrite(engine.harnessContext(alice, Strings.create("made")), alice, "w/private/x"));
		assertFalse(mayWrite(engine.context(alice, alice, null, kept), alice, "w/private/x"));
		assertThrows(AuthException.class, () -> engine.tokenContext(maker.ref()));
	}

	@Test
	public void aTokenFollowsItsIssuersAuthority(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		ACell invoke = cap("v/ops/", Abilities.INVOKE);
		ACell tokens = cap("x/mcp/tokens/", Abilities.MCP_TOKEN);
		RequestContext builder = agent(alice, "builder", Vectors.of(invoke, tokens,
			cap("w/shared/", Capability.CRUD), cap("w/other/", Capability.CRUD)));
		Issued wide = issue(builder, "wide", Vectors.of("v/ops/covia/write"), Caps.UNRESTRICTED);

		RequestContext through = engine.tokenContext(wide.ref());
		assertEquals(builder.getCallerDID(), through.getCallerDID(), "a token's request is its issuer's");
		assertTrue(mayWrite(through, alice, "w/shared/x"));
		assertTrue(mayWrite(through, alice, "w/other/x"));
		assertFalse(mayWrite(through, alice, "w/private/x"), "unrestricted caps are the issuer's own");

		// The issuer's authority changes underneath, and the token's with it
		complete(run("v/ops/agent/update", Maps.of(Fields.AGENT_ID, "builder",
			Fields.CONFIG, Maps.of(Fields.CAPS, Vectors.of(invoke, tokens, cap("w/other/", Capability.CRUD)))), RequestContext.of(alice)));
		assertFalse(mayWrite(through, alice, "w/shared/x"));
		assertTrue(mayWrite(through, alice, "w/other/x"));

		// An issuer deleted takes its tokens with it, and one recreated in its place has none of them
		complete(run("v/ops/agent/delete", Maps.of(Fields.AGENT_ID, "builder", Fields.REMOVE, true), RequestContext.of(alice)));
		assertNull(engine.mcpTokens().lookup(wide.bearer()));
		assertFalse(mayWrite(through, alice, "w/other/x"));
		agent(alice, "builder", Caps.UNRESTRICTED);
		assertNull(engine.mcpTokens().lookup(wide.bearer()));
		assertFalse(mayWrite(through, alice, "w/other/x"));
	}

	@Test
	public void aTokenNeitherWidensItselfNorMakesAWiderOne(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		AVector<ACell> manage = Vectors.of("v/ops/mcp/create-token", "v/ops/mcp/update-token");
		Issued narrow = issue(RequestContext.of(alice), "narrow", manage,
			Vectors.of(cap("x/mcp/tokens/", Abilities.MCP_TOKEN), cap("w/shared/", Capability.CRUD)));

		refused(tool(narrow, "v/ops/mcp/update-token", Maps.of(Fields.NAME, "narrow", Fields.CAPS, Caps.UNRESTRICTED)));
		refused(tool(narrow, "v/ops/mcp/update-token", Maps.of(Fields.NAME, "narrow", Fields.ROTATE, true)));

		// A token it makes is narrowed by it too, whatever its own caps say
		String bearer = RT.getIn(done(tool(narrow, "v/ops/mcp/create-token", Maps.of(Fields.NAME, "child",
			Fields.TOOLS, Vectors.of("v/ops/covia/write"), Fields.CAPS, Caps.UNRESTRICTED))), Fields.TOKEN).toString();
		Issued child = new Issued(bearer, engine.mcpTokens().lookup(bearer));
		RequestContext throughChild = engine.tokenContext(child.ref());
		assertTrue(mayWrite(throughChild, alice, "w/shared/x"));
		assertFalse(mayWrite(throughChild, alice, "w/private/x"));

		// It may change what it made, never beyond itself
		done(tool(narrow, "v/ops/mcp/update-token", Maps.of(Fields.NAME, "child", Fields.CAPS, Vectors.of(cap("w/", Capability.CRUD)))));
		assertFalse(mayWrite(engine.tokenContext(child.ref()), alice, "w/private/x"));

		// And what it made dies with it
		deleteToken(alice, "narrow");
		assertFalse(mayWrite(throughChild, alice, "w/shared/x"));
		assertFalse(mayWrite(engine.tokenContext(child.ref()), alice, "w/shared/x"));
	}

	@Test
	public void aRestrictedTokenNeverActsWithItsIssuersWholeAuthority(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		engine.getVenueState().users().ensure(bob);
		AVector<ACell> tools = Vectors.of("v/ops/user/info", "v/ops/user/authentication-list");
		Issued restricted = issue(RequestContext.of(alice), "restricted", tools, Vectors.of(cap("w/", Capability.CRUD)));
		Issued unrestricted = issue(RequestContext.of(alice), "unrestricted", tools, Caps.UNRESTRICTED);
		RequestContext narrowed = engine.tokenContext(restricted.ref());
		RequestContext whole = engine.tokenContext(unrestricted.ref());

		assertFalse(engine.holdsAuthorityOf(narrowed, alice));
		assertTrue(engine.holdsAuthorityOf(whole, alice), "an unrestricted token is its issuer's authority");
		assertFalse(narrowed.actsWithNamespaceAuthority());
		assertFalse(whole.actsWithNamespaceAuthority(), "state held for a namespace is never a token's");

		// Reading or managing the user itself needs all of the user's authority
		for (String op : new String[] {"v/ops/user/info", "v/ops/user/authentication-list"}) {
			refused(tool(restricted, op, Maps.of(Fields.DID, alice)));
			done(tool(unrestricted, op, Maps.of(Fields.DID, alice)));
		}

		// Acting for another user, or as an agent, starts from the user's own authority
		assertThrows(AuthException.class, () -> engine.sudoContext(whole, bob));
		agent(alice, "helper", Caps.UNRESTRICTED);
		assertThrows(AuthException.class, () -> engine.stepContext(narrowed, Strings.create("helper")));
	}
}
