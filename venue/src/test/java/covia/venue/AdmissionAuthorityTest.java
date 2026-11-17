package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Principals;

/**
 * An {@code accepts} naming a principal admits whatever acts with its
 * authority: the principal itself and the agents acting for it up their
 * controller chain, each only where the caps of every agent on its chain
 * cover the act (AUTHORITY.md, Admission). The venue's public principal has no
 * caps, so nothing bounds what {@code "anyone"} admits it to.
 */
class AdmissionAuthorityTest {

	private static final Engine engine = TestEngine.ENGINE;
	private static final AString REQUEST = Abilities.AGENT_REQUEST;

	private static ACell cap(String with, AString can) {
		return Capability.create(Strings.create(with), can);
	}

	/** {@code owner}'s agent {@code id} under {@code controller}, with {@code config}. */
	private static void create(AString owner, String id, AString controller, AMap<AString, ACell> config) {
		if (!Principals.isAgentDID(controller)) engine.getVenueState().users().ensure(controller);
		engine.getVenueState().users().ensure(owner).agents().create(Strings.create(id), config, null, null, controller);
	}

	/** {@code owner}'s agent {@code id} under {@code controller}, with {@code caps} (none: no limit), as it acts now. */
	private static RequestContext agent(AString owner, String id, AString controller, AVector<ACell> caps) {
		create(owner, id, controller, (caps == null) ? null : Maps.of(Config.CAPS, caps));
		return engine.context(owner, Principals.agentDID(owner, Strings.create(id)));
	}

	/** Carol's agent "desk", admitting whom {@code accepts} says; returns its resource. */
	private static AString desk(AString carol, ACell accepts) {
		return desk(carol, "desk", accepts);
	}

	/** Carol's agent {@code id}, admitting whom {@code accepts} says; returns its resource. */
	private static AString desk(AString carol, String id, ACell accepts) {
		create(carol, id, carol, Maps.of(Fields.ACCEPTS, accepts));
		return Strings.create(carol + "/g/" + id);
	}

	private static boolean mayAsk(RequestContext ctx, AString desk) {
		return engine.authorityCovers(ctx, desk, REQUEST);
	}

	@Test void namingAUserAdmitsItsAgentsWithinTheirCaps() {
		AString carol = TestEngine.uniqueDID("AdmitUserCarol");
		AString bob = TestEngine.uniqueDID("AdmitUserBob");
		AString desk = desk(carol, Vectors.of(bob));

		assertTrue(mayAsk(RequestContext.of(bob), desk));
		assertTrue(mayAsk(agent(bob, "free", bob, null), desk), "an agent with no caps acts with all of Bob's authority");
		assertTrue(mayAsk(agent(bob, "talker", bob, Vectors.of(cap(desk.toString(), REQUEST))), desk));
		RequestContext narrow = agent(bob, "narrow", bob, Vectors.of(cap("w/", Capability.CRUD)));
		assertFalse(mayAsk(narrow, desk), "its caps do not cover asking Carol's agent");
		assertTrue(refusal(narrow, desk).contains(Engine.ACCEPTS_BEYOND_CAPS), "and it is told so");
		RequestContext dan = RequestContext.of(TestEngine.uniqueDID("AdmitUserDan"));
		assertFalse(mayAsk(dan, desk));
		assertTrue(refusal(dan, desk).contains(Engine.ACCEPTS_NOT_ADMITTED));
	}

	/** Why {@code ctx} may not ask the agent at {@code desk}, as it is told. */
	private static String refusal(RequestContext ctx, AString desk) {
		return assertThrows(covia.exception.AuthException.class, () -> engine.requireAuthority(ctx, desk, REQUEST)).getMessage();
	}

	@Test void anAgentOnTheChainBoundsItToo() {
		AString carol = TestEngine.uniqueDID("AdmitChainCarol");
		AString bob = TestEngine.uniqueDID("AdmitChainBob");
		AString desk = desk(carol, Vectors.of(bob));
		agent(bob, "planner", bob, Vectors.of(cap("w/", Capability.CRUD)));
		RequestContext worker = agent(bob, "worker", Principals.agentDID(bob, Strings.create("planner")), null);
		assertFalse(mayAsk(worker, desk), "its controller's caps do not cover it");

		agent(bob, "lead", bob, Vectors.of(cap(desk.toString(), REQUEST)));
		assertTrue(mayAsk(agent(bob, "member", Principals.agentDID(bob, Strings.create("lead")), null), desk));
	}

	@Test void anAgentControlledFromElsewhereIsAdmittedAsItsControllerWithinItsCaps() {
		AString carol = TestEngine.uniqueDID("AdmitElsewhereCarol");
		AString bob = TestEngine.uniqueDID("AdmitElsewhereBob");
		AString alice = TestEngine.uniqueDID("AdmitElsewhereAlice");
		AString desk = desk(carol, Vectors.of(bob));

		assertFalse(mayAsk(agent(alice, "narrow", bob, Vectors.of(cap(bob + "/w/", Capability.CRUD))), desk),
			"Bob's admission reaches it only as far as its caps");
		assertTrue(mayAsk(agent(alice, "talker", bob, Vectors.of(cap(desk.toString(), REQUEST))), desk));

		// Every check of it is its controller's (Engine.authorityFrame), so naming it admits nothing yet
		AString counter = Strings.create(carol + "/g/counter");
		RequestContext named = agent(alice, "named", bob, Vectors.of(cap(counter.toString(), REQUEST)));
		desk(carol, "counter", Vectors.of(named.getAuthorityDID()));
		assertFalse(mayAsk(named, counter));
	}

	@Test void anExactlyNamedAgentIsBoundedByItsCaps() {
		AString carol = TestEngine.uniqueDID("AdmitExactCarol");
		AString bob = TestEngine.uniqueDID("AdmitExactBob");
		RequestContext narrow = agent(bob, "narrow", bob, Vectors.of(cap("w/", Capability.CRUD)));
		AString desk = desk(carol, Vectors.of(narrow.getAuthorityDID()));
		assertFalse(mayAsk(narrow, desk));
		assertFalse(mayAsk(RequestContext.of(bob), desk), "naming an agent admits nothing above it");

		AString counter = Strings.create(carol + "/g/counter");
		RequestContext talker = agent(bob, "talker", bob, Vectors.of(cap(counter.toString(), REQUEST)));
		assertEquals(counter, desk(carol, "counter", Vectors.of(talker.getAuthorityDID())));
		assertTrue(mayAsk(talker, counter));
	}

	@Test void anyoneAdmitsACappedAgentOnlyWithinItsCaps() {
		AString carol = TestEngine.uniqueDID("AdmitAnyoneCarol");
		AString bob = TestEngine.uniqueDID("AdmitAnyoneBob");
		AString desk = desk(carol, Admission.ANYONE);

		assertTrue(mayAsk(RequestContext.of(bob), desk));
		assertTrue(mayAsk(agent(bob, "free", bob, null), desk));
		assertFalse(mayAsk(agent(bob, "narrow", bob, Vectors.of(cap("w/", Capability.CRUD))), desk));

		// The public principal's scope is its whole authority, not caps that narrow it
		AString anonymous = engine.publicPrincipal();
		RequestContext readOnly = RequestContext.of(anonymous, null, Vectors.of(cap(anonymous + "/", Capability.CRUD_READ)));
		assertTrue(engine.crossUserAllows(readOnly, desk, REQUEST), "anonymous callers keep what anyone admits them to");
	}

	@Test void aSiblingIsBoundedByItsCapsToo() {
		AString alice = TestEngine.uniqueDID("AdmitSiblingAlice");
		create(alice, "desk", alice, Maps.of(Fields.ACCEPTS, Admission.ANYONE));
		AString desk = Strings.create(alice + "/g/desk");
		assertFalse(mayAsk(agent(alice, "narrow", alice, Vectors.of(cap("w/", Capability.CRUD))), desk));
		assertTrue(mayAsk(agent(alice, "talker", alice, Vectors.of(cap("g/desk", REQUEST))), desk));
	}

	@Test void theVenueClassAdmitsWhatActsForTheVenue() {
		AString carol = TestEngine.uniqueDID("AdmitVenueCarol");
		AString bob = TestEngine.uniqueDID("AdmitVenueBob");
		AString venue = engine.getDIDString();
		AString desk = desk(carol, Admission.VENUE);
		String unique = carol.toString().substring(carol.toString().lastIndexOf('-') + 1);

		assertTrue(mayAsk(engine.venueContext(), desk));
		assertTrue(mayAsk(agent(venue, "own-" + unique, venue, null), desk));
		assertFalse(mayAsk(agent(venue, "lent-" + unique, bob, null), desk), "an agent of the venue's that Bob controls acts for Bob");
		assertFalse(mayAsk(RequestContext.of(bob), desk));
	}

	@Test void actsForFollowsTheControllerChainByRecords() {
		AString bob = TestEngine.uniqueDID("ActsForBob");
		AString alice = TestEngine.uniqueDID("ActsForAlice");
		AString planner = Principals.agentDID(bob, Strings.create("planner"));
		AString worker = Principals.agentDID(bob, Strings.create("worker"));
		create(bob, "planner", bob, null);
		create(bob, "worker", planner, null);

		assertTrue(engine.actsFor(bob, bob));
		assertTrue(engine.actsFor(worker, bob), "through the agent on the way");
		assertTrue(engine.actsFor(worker, planner));
		assertFalse(engine.actsFor(planner, worker), "never down the chain");
		assertFalse(engine.actsFor(bob, worker));
		assertFalse(engine.actsFor(Principals.agentDID(bob, Strings.create("absent")), bob), "an agent with no record acts for no one");

		// A controller gone ends the chain
		engine.jobs().invokeOperation("v/ops/agent/delete", Maps.of(Fields.AGENT_ID, "planner", Fields.REMOVE, true),
			RequestContext.of(bob)).awaitResult(10_000);
		assertNull(engine.getAgent(bob, Strings.create("planner")));
		assertFalse(engine.actsFor(worker, bob));

		// Loops act for no one outside them, within an account and across two
		AString first = Principals.agentDID(bob, Strings.create("first"));
		AString second = Principals.agentDID(bob, Strings.create("second"));
		create(bob, "first", second, null);
		create(bob, "second", first, null);
		assertFalse(engine.actsFor(first, bob));
		AString away = Principals.agentDID(alice, Strings.create("away"));
		create(bob, "home", away, null);
		create(alice, "away", Principals.agentDID(bob, Strings.create("home")), null);
		assertFalse(engine.actsFor(away, alice));
		assertFalse(engine.actsFor(away, bob));
	}
}
