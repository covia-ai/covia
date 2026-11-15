package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.hitl.Hitl;
import covia.lattice.Caps;

/**
 * Acts only the user may do in person ({@link Engine#inPersonDenial}): a
 * request passes only when the principal whose authority applies is a user in
 * its own account and the request authenticated as that user. An agent, the
 * public principal, a delegate acting for the user, a callable's call and work
 * carried on from what a job kept are each refused, and the user's own request
 * is the control that passes.
 */
class InPersonTest {

	private static final Engine engine = TestEngine.ENGINE;

	private static final long HOUR = 3600;

	/** A user with keys of its own, so it can sign grants. */
	private record Keyed(AKeyPair keys, AString did) {
		static Keyed generate() {
			AKeyPair keys = AKeyPair.generate();
			AString did = UCAN.toDIDKey(keys.getAccountKey());
			engine.getVenueState().users().ensure(did);
			return new Keyed(keys, did);
		}
	}

	private static Job await(Job job) {
		TestEngine.awaitJob(job, Job::isFinished, 10_000, () -> "job never finished: " + job.getData());
		return job;
	}

	/** {@code user}'s open approval request, asked of itself. */
	private static Job ask(AString user) {
		Job asked = engine.jobs().invokeOperation("v/ops/hitl/request",
			Hitl.request("Pay invoice").ask(Hitl.approval("pay", "Approve payment?").required()).build(),
			RequestContext.of(user));
		assertEquals(Status.INPUT_REQUIRED, asked.getStatus(), asked::getErrorMessage);
		return asked;
	}

	private static AMap<AString, ACell> approval(Job asked) {
		return Hitl.answer(asked.getID().toHexString()).answer("pay", true).build();
	}

	/** {@code delegate} acting for {@code owner} under {@code admin/sudo}, with {@code invoke} on {@code op}. */
	private static RequestContext sudo(Keyed owner, Keyed delegate, String op) {
		AString grant = UCAN.create(owner.keys(), delegate.keys().getAccountKey(), System.currentTimeMillis() / 1000 + HOUR,
			Vectors.of(Capability.create(owner.did(), Abilities.ADMIN_SUDO),
				Capability.create(Strings.create(owner.did() + "/" + op), Abilities.INVOKE)),
			Vectors.empty()).toJWT(owner.keys());
		return engine.sudoContext(engine.withTransportGrants(RequestContext.of(delegate.did()), Vectors.of(grant)), owner.did());
	}

	@Test void aUsersOwnRequestIsInPerson() {
		AString alice = Keyed.generate().did();
		assertNull(engine.inPersonDenial(RequestContext.of(alice)));
		assertNull(engine.inPersonDenial(engine.requestContext(alice, null)));
		RequestContext carried = engine.requestContext(alice, null).withOp(Strings.create("v/ops/covia/read")).withProofs(Vectors.empty());
		assertEquals(alice, carried.getAuthenticatedDID(), "every context made for the request keeps who it authenticated as");
		assertNull(engine.inPersonDenial(carried));
	}

	@Test void aContextTheVenueMakesForTheUserIsNot() {
		AString alice = Keyed.generate().did();
		for (RequestContext made : new RequestContext[] {
				engine.context(alice), engine.context(alice, alice), engine.context(alice, alice, Vectors.empty()) }) {
			assertTrue(engine.holdsAuthorityOf(made, alice), "it has all of the user's authority");
			assertNull(made.getAuthenticatedDID(), "but nobody authenticated as the user");
			assertEquals(Engine.NOT_IN_PERSON, engine.inPersonDenial(made));
		}
		assertEquals(Engine.NOT_IN_PERSON, engine.inPersonDenial(engine.venueContext()),
			"nor is the venue's own work anyone present");
	}

	@Test void aRequestThroughAnUnrestrictedTokenIsItsIssuerInPerson() {
		AString alice = Keyed.generate().did();
		RequestContext own = engine.requestContext(alice, null);
		Job asked = ask(alice);
		try {
			RequestContext restricted = engine.requestContext(alice, token(own, "in-person-restricted",
				Vectors.of(Capability.create(Strings.create("v/ops/hitl/respond"), Abilities.INVOKE))));
			assertEquals(alice, restricted.getAuthenticatedDID());
			Job refused = await(engine.jobs().invokeOperation("v/ops/hitl/respond", approval(asked), restricted));
			assertEquals(Status.FAILED, refused.getStatus(), "a restricted token lacks the user's whole authority");
			assertEquals(covia.adapter.HITLAdapter.ANSWER_NEEDS_WHOLE_AUTHORITY, refused.getErrorMessage());
			assertEquals(Status.INPUT_REQUIRED, asked.getStatus(), "the request stays open");

			// An unrestricted token is its issuer's whole authority, as ACCESS_TOKENS.md says
			RequestContext whole = engine.requestContext(alice, token(own, "in-person-whole", Caps.UNRESTRICTED));
			assertNull(engine.inPersonDenial(whole));
			await(engine.jobs().invokeOperation("v/ops/hitl/respond", approval(asked), whole));
			assertEquals(Status.COMPLETE, asked.getStatus(), "the issuer answers through it");
		} finally {
			asked.cancel();
		}
	}

	/** A test token of {@code ctx}'s, accepted at the test access point, as its reference. */
	private static AMap<AString, ACell> token(RequestContext ctx, String name, ACell caps) {
		Job made = await(engine.jobs().invokeOperation("v/ops/token/create", Maps.of(Fields.NAME, name,
			Fields.ACCESS, Maps.of("test", Maps.empty()), Fields.CAPS, caps), ctx));
		assertEquals(Status.COMPLETE, made.getStatus(), made::getErrorMessage);
		return engine.tokens().lookup(convex.core.lang.RT.getIn(made.getOutput(), Fields.TOKEN).toString());
	}

	@Test void anAgentIsNot() {
		AString alice = Keyed.generate().did();
		RequestContext agent = RequestContext.ofAgent(alice, Strings.create("helper"));
		assertNull(agent.getAuthenticatedDID(), "an agent's own work authenticated as no one");
		assertEquals(Engine.NOT_IN_PERSON, engine.inPersonDenial(agent));
	}

	@Test void thePublicPrincipalIsNot() {
		assertEquals(Engine.NOT_IN_PERSON, engine.inPersonDenial(engine.context(engine.publicPrincipal())));
	}

	@Test void aDelegateActingForTheUserIsNot() {
		Keyed alice = Keyed.generate();
		Keyed bob = Keyed.generate();
		RequestContext delegate = sudo(alice, bob, "v/ops/hitl/respond");
		assertEquals(bob.did(), delegate.getAuthenticatedDID(), "the delegate is who is present");
		assertEquals(Engine.NOT_IN_PERSON, engine.inPersonDenial(delegate));
	}

	@Test void workCarriedOnFromWhatAJobKeptIsNot() {
		AString alice = Keyed.generate().did();
		RequestContext own = RequestContext.of(alice);
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), own);
		engine.jobs().keepCallerContext(job, own);
		try {
			RequestContext resumed = engine.jobs().callerContext(job);
			assertEquals(alice, resumed.getAuthorityDID(), "it acts with the user's authority");
			assertNull(resumed.getAuthenticatedDID(), "but nobody is present");
			assertEquals(Engine.NOT_IN_PERSON, engine.inPersonDenial(resumed));
		} finally {
			job.cancel();
		}
	}

	@Test void workCarriedOnCannotAnswerTheUsersApprovalRequests() {
		AString alice = Keyed.generate().did();
		RequestContext own = RequestContext.of(alice);
		Job asked = ask(alice);
		Job held = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), own);
		engine.jobs().keepCallerContext(held, own);
		try {
			Job answer = await(engine.jobs().invokeOperation("v/ops/hitl/respond", approval(asked),
				engine.jobs().callerContext(held)));
			assertEquals(Status.FAILED, answer.getStatus(), "work resumed as the user may not answer for them");
			assertEquals(Engine.NOT_IN_PERSON, answer.getErrorMessage());
			assertEquals(Status.INPUT_REQUIRED, asked.getStatus(), "the request stays open");

			await(engine.jobs().invokeOperation("v/ops/hitl/respond", approval(asked), own));
			assertEquals(Status.COMPLETE, asked.getStatus(), "the user in person answers it");
		} finally {
			held.cancel();
			asked.cancel();
		}
	}

	@Test void aDelegateCannotAnswerTheUsersApprovalRequests() {
		Keyed alice = Keyed.generate();
		Keyed bob = Keyed.generate();
		Job asked = ask(alice.did());
		try {
			Job answer = await(engine.jobs().invokeOperation("v/ops/hitl/respond", approval(asked),
				sudo(alice, bob, "v/ops/hitl/respond")));
			assertEquals(Status.FAILED, answer.getStatus(), "a delegate may not answer for the user");
			assertEquals(Status.INPUT_REQUIRED, asked.getStatus(), "the request stays open");
		} finally {
			asked.cancel();
		}
	}

	/** The issuer calling its own callable is present, and the callable holds
	 * all of its authority, as an unrestricted token does. */
	@Test void anIssuerCallingItsOwnUnrestrictedCallableIsInPerson() {
		AString alice = Keyed.generate().did();
		RequestContext own = engine.requestContext(alice, null);
		Job made = await(engine.jobs().invokeOperation("v/ops/callable/create", Maps.of(Fields.NAME, "answer",
			Fields.OPERATION, "v/ops/hitl/respond", Fields.CAPS, Caps.UNRESTRICTED,
			Fields.GATE, "v/ops/callable/gates/anyone"), own));
		assertEquals(Status.COMPLETE, made.getStatus(), made::getErrorMessage);
		Job asked = ask(alice);
		try {
			Job answer = await(engine.jobs().invokeOperation("v/ops/callable/call", Maps.of(
				Fields.CALLABLE, alice + "/x/callable/answer", Fields.INPUT, approval(asked)), own));
			assertEquals(Status.COMPLETE, answer.getStatus(), answer::getErrorMessage);
			assertEquals(Status.COMPLETE, asked.getStatus(), "the issuer answered through its own callable");
		} finally {
			asked.cancel();
		}
	}

	@Test void aCallableCannotAnswerItsIssuersApprovalRequests() {
		AString alice = Keyed.generate().did();
		AString bob = Keyed.generate().did();
		RequestContext own = RequestContext.of(alice);
		Job made = await(engine.jobs().invokeOperation("v/ops/callable/create", Maps.of(Fields.NAME, "answer",
			Fields.OPERATION, "v/ops/hitl/respond", Fields.CAPS, Caps.UNRESTRICTED,
			Fields.GATE, "v/ops/callable/gates/anyone"), own));
		assertEquals(Status.COMPLETE, made.getStatus(), made::getErrorMessage);
		Job asked = ask(alice);
		try {
			Job answer = await(engine.jobs().invokeOperation("v/ops/callable/call", Maps.of(
				Fields.CALLABLE, alice + "/x/callable/answer", Fields.INPUT, approval(asked)), RequestContext.of(bob)));
			assertEquals(Status.FAILED, answer.getStatus(),
				"a call with all of its issuer's authority is still not the issuer in person");
			assertEquals(Status.INPUT_REQUIRED, asked.getStatus(), "the request stays open");
		} finally {
			asked.cancel();
		}
	}
}
