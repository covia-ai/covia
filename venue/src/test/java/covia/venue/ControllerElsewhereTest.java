package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;
import covia.grid.hitl.Hitl;

/**
 * An agent whose controller is in another account acts with that controller's
 * authority narrowed by its caps: its names resolve in its own account and its
 * context names only the agent, while each check works out the controller's
 * authority from the agent's record ({@link Engine#authorityFrame}).
 */
class ControllerElsewhereTest {

	private static final Engine engine = TestEngine.ENGINE;
	private static final AString READ = Capability.CRUD_READ;
	private static final AString HELPER = Strings.intern("helper");

	private static AString path(String path) {
		return Strings.create(path);
	}

	private static ACell cap(String with, AString can) {
		return Capability.create(Strings.create(with), can);
	}

	private static void run(String op, ACell input, RequestContext ctx) {
		engine.jobs().invokeOperation(op, input, ctx).awaitResult(10_000);
	}

	/** {@code owner}'s agent {@code id} under {@code controller}, with {@code caps}, as it acts now. */
	private static RequestContext agent(AString owner, AString id, AString controller, AVector<ACell> caps) {
		if (!Principals.isAgentDID(controller)) engine.getVenueState().users().ensure(controller);
		AMap<AString, ACell> config = (caps == null) ? null : Maps.of(Config.CAPS, caps);
		engine.getVenueState().users().ensure(owner).agents().create(id, config, null, null, controller);
		return engine.context(owner, Principals.agentDID(owner, id));
	}

	/** {@code alice}'s agent "helper" under {@code controller}, with no caps of its own. */
	private static RequestContext helper(AString alice, AString controller) {
		return agent(alice, HELPER, controller, null);
	}

	@Test void namesResolveHereButAccessIsTheControllers() {
		AString alice = TestEngine.uniqueDID("ElsewhereAlice");
		AString bob = TestEngine.uniqueDID("ElsewhereBob");
		run("v/ops/covia/write", Maps.of(Fields.PATH, "w/notes", Fields.VALUE, "Alice's"), RequestContext.of(alice));
		RequestContext ctx = helper(alice, bob);
		assertEquals(Principals.agentDID(alice, HELPER), ctx.getAuthorityDID(), "the context names only the agent");
		assertEquals(alice, ctx.getUserDID());
		assertEquals(bob, engine.authorityFrame(ctx).getUserDID());

		assertFalse(engine.authorityCovers(ctx, path("w/notes"), READ), "a bare path names Alice's");
		assertFalse(engine.authorityCovers(ctx, path(alice + "/w/notes"), READ));
		assertThrows(AuthException.class, () -> engine.requireLocalAccess(ctx, path("w/notes"), READ));
		assertTrue(engine.authorityCovers(ctx, path(bob + "/w/notes"), READ), "Bob's own");
		assertEquals(bob, engine.requireLocalAccess(ctx, path(bob + "/w/notes"), READ));
	}

	@Test void itsCapsNarrowItsControllersAuthority() {
		AString alice = TestEngine.uniqueDID("ElsewhereCapsAlice");
		AString bob = TestEngine.uniqueDID("ElsewhereCapsBob");
		RequestContext ctx = agent(alice, HELPER, bob, Vectors.of(cap(bob + "/w/notes/", READ)));
		assertTrue(engine.authorityCovers(ctx, path(bob + "/w/notes/a"), READ));
		assertFalse(engine.authorityCovers(ctx, path(bob + "/w/other"), READ));
		assertFalse(engine.authorityCovers(ctx, path("w/notes/a"), READ));
	}

	@Test void anAgentOnTheWayNarrowsItToo() {
		AString alice = TestEngine.uniqueDID("ElsewhereChainAlice");
		AString bob = TestEngine.uniqueDID("ElsewhereChainBob");
		AString middle = Strings.intern("middle");
		agent(alice, middle, bob, Vectors.of(cap(bob + "/w/shared/", READ)));
		RequestContext ctx = agent(alice, HELPER, Principals.agentDID(alice, middle), null);
		assertEquals(bob, engine.authorityFrame(ctx).getUserDID(), "the first controller outside the account");
		assertTrue(engine.authorityCovers(ctx, path(bob + "/w/shared/doc"), READ));
		assertFalse(engine.authorityCovers(ctx, path(bob + "/w/private"), READ), "within the caps of the agent on the way");
		assertFalse(engine.authorityCovers(ctx, path("w/shared/doc"), READ));
	}

	@Test void theOwnersAuthorityReachesTheOwnersNames() {
		AString alice = TestEngine.uniqueDID("ElsewhereOwner");
		RequestContext ctx = helper(alice, alice);
		assertSame(ctx, engine.authorityFrame(ctx));
		assertTrue(engine.authorityCovers(ctx, path("w/notes"), READ));
		assertEquals(alice, engine.requireLocalAccess(ctx, path("w/notes"), READ));
	}

	@Test void invokeIsCheckedAgainstItsAuthority() {
		AString alice = TestEngine.uniqueDID("ElsewhereInvokeAlice");
		AString bob = TestEngine.uniqueDID("ElsewhereInvokeBob");
		RequestContext ctx = agent(alice, HELPER, bob, Vectors.of(cap("v/test/ops/echo", Abilities.INVOKE)));
		engine.requireInvoke(ctx, path("v/test/ops/echo"));
		assertThrows(AuthException.class, () -> engine.requireInvoke(ctx, path("v/test/ops/llm")));
	}

	@Test void secretsAreTheControllers() {
		AString alice = TestEngine.uniqueDID("ElsewhereSecretAlice");
		AString bob = TestEngine.uniqueDID("ElsewhereSecretBob");
		run("v/ops/secret/set", Maps.of(Fields.NAME, "KEY", Fields.VALUE, "Alice's"), RequestContext.of(alice));
		run("v/ops/secret/set", Maps.of(Fields.NAME, "KEY", Fields.VALUE, "Bob's"), RequestContext.of(bob));
		RequestContext ctx = helper(alice, bob);

		assertFalse(engine.isOwnSecretRef("s/KEY", ctx));
		assertThrows(AuthException.class, () -> engine.resolveSecret("s/KEY", ctx));
		assertEquals("Bob's", engine.resolveSecret(bob + "/s/KEY", ctx));
	}

	@Test void askingHomeMeansTheControllersHome() {
		AString alice = TestEngine.uniqueDID("ElsewhereAskAlice");
		AString bob = TestEngine.uniqueDID("ElsewhereAskBob");
		AMap<AString, ACell> ask = Hitl.request("Ask").ask(Hitl.approval("ok", "OK?").required()).build();

		Job foreign = engine.jobs().invokeOperation("v/ops/hitl/request", ask, helper(alice, bob));
		try { foreign.awaitResult(10_000); } catch (Exception expected) {}
		assertEquals(Status.FAILED, foreign.getStatus(), "Alice's inbox is not Bob's family");

		AString carol = TestEngine.uniqueDID("ElsewhereAskCarol");
		Job home = engine.jobs().invokeOperation("v/ops/hitl/request", ask, agent(carol, HELPER, carol, null));
		assertEquals(Status.INPUT_REQUIRED, home.getStatus(), home.getErrorMessage());
		home.cancel();
	}

	@Test void keptWorkFollowsTheControllerAsItStands() {
		AString alice = TestEngine.uniqueDID("ElsewhereKeptAlice");
		AString bob = TestEngine.uniqueDID("ElsewhereKeptBob");
		AString carol = TestEngine.uniqueDID("ElsewhereKeptCarol");
		engine.getVenueState().users().ensure(carol);
		RequestContext ctx = helper(alice, bob);
		assertEquals(Maps.of(Fields.FRAME, Maps.of(Fields.AUTHORITY, ctx.getAuthorityDID())), Engine.stateOf(ctx),
			"nothing of its controller is kept");
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), ctx);
		try {
			RequestContext resumed = engine.jobs().callerContext(job);
			assertEquals(ctx.getAuthorityDID(), resumed.getAuthorityDID());
			assertEquals(alice, resumed.getUserDID());
			assertFalse(engine.authorityCovers(resumed, path("w/notes"), READ), "not its account's authority");
			assertTrue(engine.authorityCovers(resumed, path(bob + "/w/notes"), READ));

			engine.getVenueState().users().user(alice).agent(HELPER).update(null, carol, null, null);
			RequestContext handedOn = engine.jobs().callerContext(job);
			assertFalse(engine.authorityCovers(handedOn, path(bob + "/w/notes"), READ), "Bob controls it no longer");
			assertTrue(engine.authorityCovers(handedOn, path(carol + "/w/notes"), READ));
		} finally {
			job.cancel();
		}
	}

	@Test void aScheduledEventFiresWithItsControllersAuthority() {
		AString alice = TestEngine.uniqueDID("ElsewhereFireAlice");
		AString bob = TestEngine.uniqueDID("ElsewhereFireBob");
		Scheduler scheduler = engine.gridScheduler();
		Blob handle = scheduler.schedule(path("v/test/ops/echo"), Strings.create("x"),
			helper(alice, bob), System.currentTimeMillis() + 3_600_000);
		try {
			RequestContext fire = scheduler.fireContext(engine.getVenueState().schedule().find(handle).rec());
			assertEquals(alice, fire.getUserDID());
			assertEquals(bob, engine.authorityFrame(fire).getUserDID());
			assertTrue(engine.authorityCovers(fire, path(bob + "/w/notes"), READ));
		} finally {
			scheduler.cancel(handle, RequestContext.of(alice));
		}
	}

	@Test void controllersThatLeaveTheAccountAndLeadBackGiveNoAuthority() {
		AString alice = TestEngine.uniqueDID("ElsewhereLoopAlice");
		AString bob = TestEngine.uniqueDID("ElsewhereLoopBob");
		AString theirs = Strings.intern("theirs");
		agent(bob, theirs, Principals.agentDID(alice, HELPER), null);
		engine.getVenueState().users().ensure(alice).agents().create(HELPER, null, null, null, bob);
		RequestContext ctx = engine.context(alice, Principals.agentDID(alice, HELPER));
		engine.getVenueState().users().user(alice).agent(HELPER).update(null, Principals.agentDID(bob, theirs), null, null);
		AuthException refused = assertThrows(AuthException.class, () -> engine.authorityCovers(ctx, path(bob + "/w/notes"), READ));
		assertEquals(Engine.CONTROLLERS_LOOP.formatted(Principals.agentDID(alice, HELPER)), refused.getMessage());
	}

	@Test void aStepActsWithTheAgentsOwnAuthority() {
		AString alice = TestEngine.uniqueDID("ElsewhereStep");
		AString bob = TestEngine.uniqueDID("ElsewhereStepBob");
		agent(alice, HELPER, alice, Vectors.of(cap("w/agent/", READ)));
		RequestContext step = engine.stepContext(engine.harnessContext(alice, HELPER), HELPER);
		assertSame(step, engine.authorityFrame(step));
		assertTrue(engine.authorityCovers(step, path("w/agent/x"), READ));
		assertFalse(engine.authorityCovers(step, path(bob + "/w/x"), READ));
	}
}
