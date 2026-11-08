package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Principals;

/**
 * A request can act with another principal's authority
 * ({@link RequestContext#withAuthorityOf}): its names resolve in its own
 * namespace and it is attributed to its own caller, but what it may do is
 * decided by that other authority.
 */
class SeparateAuthorityTest {

	private static final Engine engine = TestEngine.ENGINE;
	private static final AString READ = Capability.CRUD_READ;
	private static final AString HELPER = Strings.intern("helper");

	private static AString path(String path) {
		return Strings.create(path);
	}

	private static ACell cap(String with, AString can) {
		return Capability.create(Strings.create(with), can);
	}

	/** {@code alice}'s agent "helper", acting with {@code authority}'s authority. */
	private static RequestContext helper(AString alice, RequestContext authority) {
		return RequestContext.ofAgent(alice, HELPER).withAuthorityOf(authority);
	}

	private static void run(String op, ACell input, RequestContext ctx) {
		engine.jobs().invokeOperation(op, input, ctx).awaitResult(10_000);
	}

	/** {@code owner}'s agent {@code id} with {@code caps}, as it acts now. */
	private static RequestContext agent(AString owner, String id, AVector<ACell> caps) {
		run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id, Fields.CONFIG, Maps.of(Config.CAPS, caps)),
			RequestContext.of(owner));
		return engine.context(owner, Principals.agentDID(owner, Strings.create(id)));
	}

	@Test void namesResolveHereButAccessIsTheAuthoritys() {
		AString alice = TestEngine.uniqueDID("SeparateAlice");
		AString bob = TestEngine.uniqueDID("SeparateBob");
		run("v/ops/covia/write", Maps.of(Fields.PATH, "w/notes", Fields.VALUE, "Alice's"), RequestContext.of(alice));
		RequestContext ctx = helper(alice, RequestContext.of(bob));
		assertEquals(Principals.agentDID(alice, HELPER), ctx.getCallerDID());
		assertEquals(alice, ctx.getUserDID());

		assertFalse(engine.authorityCovers(ctx, path("w/notes"), READ), "a bare path names Alice's");
		assertFalse(engine.authorityCovers(ctx, path(alice + "/w/notes"), READ));
		assertThrows(AuthException.class, () -> engine.requireLocalAccess(ctx, path("w/notes"), READ));
		assertTrue(engine.authorityCovers(ctx, path(bob + "/w/notes"), READ), "Bob's own");
		assertEquals(bob, engine.requireLocalAccess(ctx, path(bob + "/w/notes"), READ));
	}

	@Test void theOwnersAuthorityReachesTheOwnersNames() {
		AString alice = TestEngine.uniqueDID("SeparateOwner");
		RequestContext ctx = helper(alice, RequestContext.of(alice));
		assertTrue(engine.authorityCovers(ctx, path("w/notes"), READ));
		assertEquals(alice, engine.requireLocalAccess(ctx, path("w/notes"), READ));
	}

	@Test void anAgentsAuthorityIsBoundByItsCaps() {
		AString alice = TestEngine.uniqueDID("SeparateCapped");
		RequestContext controller = agent(alice, "controller", Vectors.of(cap("w/shared/", READ)));
		RequestContext ctx = helper(alice, controller);
		assertTrue(engine.authorityCovers(ctx, path("w/shared/doc"), READ));
		assertFalse(engine.authorityCovers(ctx, path("w/private"), READ));
	}

	@Test void invokeIsCheckedAgainstTheAuthoritysScope() {
		AString alice = TestEngine.uniqueDID("SeparateInvoke");
		RequestContext controller = agent(alice, "invoker", Vectors.of(cap("v/test/ops/echo", Abilities.INVOKE)));
		RequestContext ctx = helper(alice, controller);
		engine.requireInvoke(ctx, path("v/test/ops/echo"));
		assertThrows(AuthException.class, () -> engine.requireInvoke(ctx, path("v/test/ops/llm")));
	}

	@Test void secretsAreTheAuthoritys() {
		AString alice = TestEngine.uniqueDID("SeparateSecretAlice");
		AString bob = TestEngine.uniqueDID("SeparateSecretBob");
		run("v/ops/secret/set", Maps.of(Fields.NAME, "KEY", Fields.VALUE, "Alice's"), RequestContext.of(alice));
		run("v/ops/secret/set", Maps.of(Fields.NAME, "KEY", Fields.VALUE, "Bob's"), RequestContext.of(bob));
		RequestContext ctx = helper(alice, RequestContext.of(bob));

		assertFalse(engine.isOwnSecretRef("s/KEY", ctx));
		assertThrows(AuthException.class, () -> engine.resolveSecret("s/KEY", ctx));
		assertEquals("Bob's", engine.resolveSecret(bob + "/s/KEY", ctx));
	}

	@Test void itsOwnAuthorityHoldsNothing() {
		AString alice = TestEngine.uniqueDID("SeparateEmpty");
		AString bob = TestEngine.uniqueDID("SeparateEmptyBob");
		RequestContext ctx = helper(alice, RequestContext.of(bob));
		assertEquals(Vectors.empty(), ctx.getCaps());
		assertNull(ctx.getProofs());
		assertNotNull(ctx.grantsDenial(path("w/notes"), READ), "a scope-only check refuses Alice's names");

		// A scope given to the context is its caller's own again.
		RequestContext own = ctx.withCaps(Vectors.of(cap("w/", READ)));
		assertFalse(own.hasSeparateAuthority());
		assertTrue(engine.authorityCovers(own, path("w/notes"), READ));
		assertFalse(engine.authorityCovers(own, path(bob + "/w/notes"), READ));
	}

	@Test void aStepActsWithTheAgentsOwnAuthority() {
		AString alice = TestEngine.uniqueDID("SeparateStep");
		AString bob = TestEngine.uniqueDID("SeparateStepBob");
		agent(alice, HELPER.toString(), Vectors.of(cap("w/agent/", READ)));
		RequestContext step = engine.stepContext(helper(alice, RequestContext.of(bob)), HELPER);
		assertFalse(step.hasSeparateAuthority());
		assertTrue(engine.authorityCovers(step, path("w/agent/x"), READ));
		assertFalse(engine.authorityCovers(step, path(bob + "/w/x"), READ));
	}
}
