package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.jwt.JWT;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.UcanTokens;
import covia.grid.hitl.Hitl;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.Config;

/**
 * HITL end-to-end (COG-16), driven through the {@link Hitl} builders — the
 * clean flow this adapter exists for:
 *
 * <pre>request (Job parks INPUT_REQUIRED) → respond → Job completion</pre>
 *
 * Includes the adversarial set: delivery without a hitl/request delegation,
 * non-target respond, echo of untriggered/unoffered grants, answer-shape
 * violations, respond-after-expiry, and cancel. Domain-rule edge cases are
 * unit-tested engine-free in {@code HitlValidationTest}.
 *
 * <p>Per-test key pairs isolate user namespaces on the shared engine.</p>
 */
public class HITLAdapterTest {

	private static final Engine engine;
	static {
		engine = Engine.createTemp(Maps.of(
			Config.HOSTNAME, Strings.create("hitl.test.covia.example"),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true)));
		Engine.addDemoAssets(engine);
	}

	private AKeyPair BOB_KP;
	private AString ALICE_DID;
	private AString BOB_DID;
	private RequestContext ALICE;
	private RequestContext BOB;

	private static final long HOUR = 3600;

	@BeforeEach
	public void setup(TestInfo info) {
		BOB_KP = AKeyPair.generate();
		String method = info.getTestMethod().map(m -> m.getName()).orElse("unknown");
		ALICE_DID = engine.managedUserDID(Strings.create("hitl-" + method));
		engine.getVenueState().users().ensure(ALICE_DID);
		BOB_DID = UCAN.toDIDKey(BOB_KP.getAccountKey());
		ALICE = RequestContext.of(ALICE_DID);
		BOB = RequestContext.of(BOB_DID);
	}

	@AfterAll
	static void closeEngine() {
		engine.close();
	}

	// ========== Helpers ==========

	private Job request(RequestContext ctx, AMap<AString, ACell> input) {
		return engine.jobs().invokeOperation("v/ops/hitl/request", input, ctx);
	}

	private ACell respond(RequestContext ctx, AMap<AString, ACell> input) {
		return engine.jobs().invokeOperation("v/ops/hitl/respond", input, ctx).awaitResult(5000);
	}

	private AMap<AString, ACell> readRecord(RequestContext ctx, String id) {
		ACell result = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, "h/" + id), ctx).awaitResult(5000);
		if (!CVMBool.TRUE.equals(RT.getIn(result, "exists"))) return null;
		return RT.castMap(RT.getIn(result, "value"));
	}

	// ========== The clean drive: request → respond → completion ==========

	@Test
	public void testAnswerLifecycle() {
		// 1. Request — the Job parks awaiting the human.
		Job job = request(ALICE, Hitl.request("Pay invoice")
			.description("Invoice INV-1 for testing")
			.ask(Hitl.approval("pay", "Approve payment?").required())
			.build());
		assertEquals(Status.INPUT_REQUIRED, job.getStatus(),
			"a delivered HITL request parks the job awaiting the human");
		String id = job.getID().toHexString();

		AMap<AString, ACell> record = readRecord(ALICE, id);
		assertNotNull(record, "the record must land in the target's h/ inbox");
		assertEquals(Hitl.OPEN, record.get(Hitl.STATUS));
		assertEquals(ALICE_DID, record.get(Hitl.FROM),
			"from is the VERIFIED caller identity, venue-set");

		// The inbox listing shows the open ask.
		ACell list = engine.jobs().invokeOperation("v/ops/hitl/list",
			Maps.of("status", "open"), ALICE).awaitResult(5000);
		assertTrue(RT.ensureLong(RT.getIn(list, "count")).longValue() >= 1);

		// 2. Respond — the inbox owner answers.
		respond(ALICE, Hitl.answer(id)
			.answer("pay", true)
			.comment("approved")
			.build());

		// 3. Completion — the requester's Job resolves with the response.
		assertEquals(Status.COMPLETE, job.getStatus());
		ACell output = job.getOutput();
		assertEquals(CVMBool.TRUE, RT.getIn(output, "answers", "pay"));
		assertEquals(Strings.create(id), RT.getIn(output, "id"));
		assertEquals(Hitl.ANSWERED, readRecord(ALICE, id).get(Hitl.STATUS));
	}

	// ========== Token transport (COG-19): self-sovereign cross-venue tokens ==========

	/** Builds a self-ask request carrying one token ask audienced to {@code aud}. */
	private AMap<AString, ACell> tokenRequest(AString aud) {
		return Maps.of(
			Hitl.TITLE, Strings.create("Cross-venue access"),
			Hitl.ASKS, Vectors.of(Maps.of(
				Hitl.ID,       Strings.create("b-access"),
				Hitl.TYPE,     Hitl.TOKEN_ASK,
				Hitl.PROMPT,   Strings.create("Grant the agent read on your invoices at venue B?"),
				Hitl.REQUIRED, CVMBool.TRUE,
				Hitl.TOKEN,    Maps.of(
					Hitl.CAPS,     Vectors.of(Maps.of(
						Hitl.WITH, Strings.create("did:web:b.example/w/invoices/"),
						Hitl.CAN,  Strings.create("crud/read"))),
					Hitl.AUDIENCE, aud,
					Hitl.VENUE,    Strings.create("did:web:b.example")))));
	}

	private AMap<AString, ACell> tokenAnswer(String id, String jwt) {
		return Maps.of(
			Hitl.ID, Strings.create(id),
			Hitl.OUTCOME, Hitl.ANSWER,
			Hitl.ANSWERS, Maps.of(Strings.create("b-access"), Strings.create(jwt)));
	}

	@Test
	public void testTokenAskTransportsSignedToken() {
		engine.getVenueState().users().ensure(BOB_DID);   // BOB is the self-sovereign responder
		AString agentDID = UCAN.toDIDKey(AKeyPair.generate().getAccountKey()); // requester/session audience

		Job job = request(BOB, tokenRequest(agentDID));
		assertEquals(Status.INPUT_REQUIRED, job.getStatus());
		String id = job.getID().toHexString();

		// BOB signs a self-sovereign UCAN with their OWN key (what the frontend does).
		String jwt = UcanTokens.grant(BOB_KP, agentDID.toString(),
			BOB_DID + "/w/invoices/", "crud/read", HOUR);

		respond(BOB, tokenAnswer(id, jwt));

		assertEquals(Status.COMPLETE, job.getStatus());
		ACell output = job.getOutput();
		// The signed token is delivered on the requester's job output, keyed by ask id.
		assertEquals(Strings.create(jwt), RT.getIn(output, Hitl.TOKENS, Strings.create("b-access")),
			"the transported token must reach the requester");
		// It is a secret: redacted from the job-output answers AND the durable record.
		assertNotEquals(Strings.create(jwt), RT.getIn(output, Hitl.ANSWERS, Strings.create("b-access")),
			"the raw token must not sit in the answers map");
		AMap<AString, ACell> rec = readRecord(BOB, id);
		assertNotEquals(Strings.create(jwt),
			RT.getIn(rec, Hitl.RESPONSE, Hitl.ANSWERS, Strings.create("b-access")),
			"the raw token must not be persisted in the durable inbox record");
	}

	@Test
	public void testTokenAskAcceptsLegacyTokenWithoutVersionOrProofs() {
		engine.getVenueState().users().ensure(BOB_DID);
		AString agentDID = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		Job job = request(BOB, tokenRequest(agentDID));
		String id = job.getID().toHexString();

		long exp = (System.currentTimeMillis() / 1000) + HOUR;
		AMap<AString, ACell> legacyClaims = Maps.of(
			UCAN.ISS, BOB_DID,
			UCAN.AUD, agentDID,
			UCAN.EXP, CVMLong.create(exp),
			UCAN.ATT, Vectors.of(Capability.create(
				Strings.create(BOB_DID + "/w/invoices/"), Capability.CRUD_READ)));
		AString jwt = JWT.signPublic(legacyClaims, BOB_KP);

		respond(BOB, tokenAnswer(id, jwt.toString()));
		assertEquals(Status.COMPLETE, job.getStatus());
		assertEquals(jwt, RT.getIn(job.getOutput(), Hitl.TOKENS, Strings.create("b-access")));
	}

	@Test
	public void testTokenAskRejectsWrongSigner() {
		engine.getVenueState().users().ensure(BOB_DID);
		AString agentDID = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		Job job = request(BOB, tokenRequest(agentDID));
		String id = job.getID().toHexString();

		// Signed by someone else's key — iss != the responder.
		String forged = UcanTokens.grant(AKeyPair.generate(), agentDID.toString(),
			BOB_DID + "/w/invoices/", "crud/read", HOUR);
		assertThrows(RuntimeException.class, () -> respond(BOB, tokenAnswer(id, forged)),
			"a token not signed by the responder must be refused");
		assertEquals(Status.INPUT_REQUIRED, job.getStatus(), "the request stays open");
	}

	@Test
	public void testTokenAskRejectsWrongAudience() {
		engine.getVenueState().users().ensure(BOB_DID);
		AString agentDID = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		AString otherDID = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		Job job = request(BOB, tokenRequest(agentDID));
		String id = job.getID().toHexString();

		// Correctly signed by BOB, but audienced to the wrong principal.
		String misAud = UcanTokens.grant(BOB_KP, otherDID.toString(),
			BOB_DID + "/w/invoices/", "crud/read", HOUR);
		assertThrows(RuntimeException.class, () -> respond(BOB, tokenAnswer(id, misAud)),
			"a token audienced to someone other than the requester must be refused");
	}

	@Test
	public void testRejectFailsJob() {
		Job job = request(ALICE, Hitl.request("Pay?")
			.ask(Hitl.approval("pay", "Pay?").required()).build());
		String id = job.getID().toHexString();

		respond(ALICE, Hitl.reject(id, "Wrong PO"));

		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains("rejected"));
		assertTrue(job.getErrorMessage().contains("Wrong PO"),
			"the rejection reason must travel in the job error — the requester cannot read the inbox");
		assertEquals(Hitl.REJECTED, readRecord(ALICE, id).get(Hitl.STATUS));
	}

	@Test
	public void testRespondAcceptsPrefixedJobId() {
		// REST renders job ids 0x-prefixed; records key on bare hex. A pasted
		// job id must work for respond (live smoke-test finding).
		Job job = request(ALICE, Hitl.request("x").ask(Hitl.approval("ok", "OK?")).build());
		respond(ALICE, Hitl.answer("0x" + job.getID().toHexString()).answer("ok", true).build());
		assertEquals(Status.COMPLETE, job.getStatus());
	}

	// ========== Validation at the adapter boundary (adversarial) ==========

	@Test
	public void testAnswerValidationAdversarial() {
		Job job = request(ALICE, Hitl.request("Setup")
			.ask(Hitl.approval("pay", "Pay?").required())
			.ask(Hitl.choice("tier", "Tier?").option("fast", "Fast").option("best", "Best"))
			.build());
		String id = job.getID().toHexString();

		// Missing required ask
		assertThrows(Exception.class, () -> respond(ALICE,
			Hitl.answer(id).answer("tier", "fast").build()));
		// Unknown option id
		assertThrows(Exception.class, () -> respond(ALICE,
			Hitl.answer(id).answer("pay", true).answer("tier", "zzz").build()));
		// Unknown ask id
		assertThrows(Exception.class, () -> respond(ALICE,
			Hitl.answer(id).answer("pay", true).answer("bogus", "x").build()));
		// Wrong answer shape for approval
		assertThrows(Exception.class, () -> respond(ALICE,
			Hitl.answer(id).answer("pay", "yes").build()));

		// Failed responses left the record open and the job unfinished...
		assertEquals(Hitl.OPEN, readRecord(ALICE, id).get(Hitl.STATUS));
		assertFalse(job.isFinished());
		// ...and a valid response still resolves it.
		respond(ALICE, Hitl.answer(id).answer("pay", true).answer("tier", "fast").build());
		assertEquals(Status.COMPLETE, job.getStatus());
	}

	@Test
	public void testRequestValidationAdversarial() {
		// Empty asks — rejected synchronously, no record, no parked job.
		assertThrows(Exception.class, () -> request(ALICE, Hitl.request("t").build()));
		// Unknown ask type
		assertThrows(Exception.class, () -> request(ALICE,
			Maps.of(Hitl.TITLE, Strings.create("t"), Hitl.ASKS, Vectors.of(
				(ACell) Maps.of(Hitl.ID, Strings.create("a"),
					Hitl.TYPE, Strings.create("essay"), Hitl.PROMPT, Strings.create("p"))))));
		// Grants on a TEXT ask — a grant must ride an explicit choice
		assertThrows(Exception.class, () -> request(ALICE,
			Maps.of(Hitl.TITLE, Strings.create("t"), Hitl.ASKS, Vectors.of(
				(ACell) Hitl.text("a", "p").build().assoc(Hitl.GRANTS,
					Vectors.of((ACell) Hitl.grant("w/x", "crud/read")))))));
	}

	// ========== Echo-consent grants ==========

	@Test
	public void testEchoConsentGrants() {
		// ADVERSARIAL: echoing the grant while DENYING the approval — not triggered.
		Job denied = request(ALICE, Hitl.request("g")
			.ask(Hitl.approval("access", "Grant?").grant("w/reports/", "crud/read")).build());
		assertThrows(Exception.class, () -> respond(ALICE,
			Hitl.answer(denied.getID().toHexString())
				.answer("access", false)
				.echo("w/reports/", "crud/read")
				.build()));
		assertFalse(denied.isFinished(), "a failed response must not resolve the job");

		// ADVERSARIAL: echoing a grant that was never offered.
		Job crafted = request(ALICE, Hitl.request("g")
			.ask(Hitl.approval("access", "Grant?").grant("w/reports/", "crud/read")).build());
		assertThrows(Exception.class, () -> respond(ALICE,
			Hitl.answer(crafted.getID().toHexString())
				.answer("access", true)
				.echo("w/private/", "crud")
				.build()));

		// Approving WITHOUT echoing confers nothing.
		Job silent = request(ALICE, Hitl.request("g")
			.ask(Hitl.approval("access", "Grant?").grant("w/reports/", "crud/read")).build());
		respond(ALICE, Hitl.answer(silent.getID().toHexString()).answer("access", true).build());
		assertEquals(Status.COMPLETE, silent.getStatus());
		assertNull(RT.getIn(silent.getOutput(), "token"), "no echo, no token");
		assertNull(RT.getIn(silent.getOutput(), "grants"));

		// Approve + echo → token issued, audienced to the requester, resource
		// canonicalised to the RESPONDER's namespace at issuance.
		Job granted = request(ALICE, Hitl.request("g")
			.ask(Hitl.approval("access", "Grant?").grant("w/reports/", "crud/read")).build());
		respond(ALICE, Hitl.answer(granted.getID().toHexString())
			.answer("access", true)
			.echo("w/reports/", "crud/read")
			.build());
		assertEquals(Status.COMPLETE, granted.getStatus());
		AString jwt = RT.ensureString(RT.getIn(granted.getOutput(), "token"));
		assertNotNull(jwt, "echoed-and-triggered grants must issue a token");
		UCAN token = UCAN.fromJWT(jwt);
		assertEquals(ALICE_DID, token.getAudience(), "audience is the requester");
		assertEquals(Strings.create(ALICE_DID + "/w/reports/"),
			RT.getIn(token.getCapabilities().get(0), Capability.WITH));
	}

	@Test
	public void testExplicitGrantExpiryIsNotSilentlyCapped() {
		long offeredExp = (System.currentTimeMillis() / 1000) + 14 * 24 * 3600L;
		Job job = request(ALICE, Hitl.request("g")
			.ask(Hitl.approval("access", "Grant?")
				.grant(Hitl.grant("w/reports/", "crud/read", offeredExp)))
			.build());
		respond(ALICE, Hitl.answer(job.getID().toHexString())
			.answer("access", true)
			.echo("w/reports/", "crud/read")
			.build());

		UCAN token = UCAN.fromJWT(RT.ensureString(RT.getIn(job.getOutput(), Hitl.TOKEN)));
		assertEquals(offeredExp, token.getExpiry(),
			"without an operator ceiling, the venue must honour the offered expiry exactly");
	}

	@Test
	public void testExplicitNullGrantExpiryMintsNonExpiringToken() {
		Job job = request(ALICE, Hitl.request("g")
			.ask(Hitl.approval("access", "Grant?")
				.grant("w/reports/", "crud/read", (Long) null))
			.build());
		respond(ALICE, Hitl.answer(job.getID().toHexString())
			.answer("access", true)
			.echo("w/reports/", "crud/read")
			.build());

		UCAN token = UCAN.fromJWT(RT.ensureString(RT.getIn(job.getOutput(), Hitl.TOKEN)));
		assertNull(token.getExpiry(),
			"an explicit no-expiry grant mints a genuinely non-expiring token (Convex #678)");
		assertNull(RT.getIn(job.getOutput(), Hitl.GRANTS, 0L, Hitl.EXP),
			"the consent record retains the caller's explicit no-expiry intent");
	}

	@Test
	public void testConfiguredGrantCeilingRejectsBeforeDelivery() {
		Engine limited = Engine.createTemp(Maps.of(
			Config.HOSTNAME, Strings.create("hitl-limit.test.covia.example"),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.ADAPTERS, Maps.of(Strings.create("hitl"), Maps.of(
				HITLAdapter.CONFIG_MAX_GRANT_LIFETIME_SECS, CVMLong.create(HOUR)))));
		try {
			Engine.addDemoAssets(limited);
			AString user = limited.managedUserDID(Strings.create("hitl-limit-user"));
			limited.getVenueState().users().ensure(user);
			RequestContext ctx = RequestContext.of(user);
			long now = System.currentTimeMillis() / 1000;

			Exception excessive = assertThrows(Exception.class, () -> limited.jobs().invokeOperation(
				"v/ops/hitl/request",
				Hitl.request("too long").ask(Hitl.approval("a", "Approve?")
					.grant(Hitl.grant("w/x", "crud/read", now + 2 * HOUR))).build(),
				ctx));
			assertTrue(excessive.getMessage().contains("3600-second HITL grant ceiling"));

			Exception permanent = assertThrows(Exception.class, () -> limited.jobs().invokeOperation(
				"v/ops/hitl/request",
				Hitl.request("permanent").ask(Hitl.approval("a", "Approve?")
					.grant("w/x", "crud/read", (Long) null)).build(),
				ctx));
			assertTrue(permanent.getMessage().contains("requests no expiry"));
			assertEquals(0, limited.getVenueState().users().get(user).getHitlRequests().count(),
				"invalid offers must be rejected before entering the human's inbox");
		} finally {
			limited.close();
		}
	}

	// ========== Cross-user delivery ==========

	@Test
	public void testCrossUserDelivery() {
		// ADVERSARIAL: no delegation → delivery denied, job FAILED, no record.
		Job blocked = request(BOB, Hitl.request("gimme").to(ALICE_DID.toString())
			.ask(Hitl.approval("ok", "OK?")).build());
		assertEquals(Status.FAILED, blocked.getStatus());
		assertTrue(blocked.getErrorMessage().contains("hitl/request"));
		assertNull(readRecord(ALICE, blocked.getID().toHexString()),
			"a denied delivery must leave no record in the target's inbox");

		// With a venue-signed delegation for managed Alice, delivery succeeds.
		long exp = (System.currentTimeMillis() / 1000) + HOUR;
		UCAN delegation = UCAN.create(engine.getKeyPair(), UCAN.fromDIDKey(BOB_DID), exp,
			Vectors.of(Capability.create(
				Strings.create(ALICE_DID + "/h/"), HITLAdapter.ABILITY_HITL_REQUEST)),
			Vectors.empty());
		Job job = request(BOB.withProofs(Vectors.of(delegation.toMap())),
			Hitl.request("Report access").to(ALICE_DID.toString())
				.ask(Hitl.approval("access", "Grant report access?")
					.grant("w/reports/", "crud/read"))
				.build());
		assertEquals(Status.INPUT_REQUIRED, job.getStatus());
		String id = job.getID().toHexString();

		AMap<AString, ACell> record = readRecord(ALICE, id);
		assertEquals(BOB_DID, record.get(Hitl.FROM),
			"from is Bob — the verified requester, not the inbox owner");

		// ADVERSARIAL: the requester cannot respond — the record is not in HIS inbox.
		assertThrows(Exception.class, () -> respond(BOB,
			Hitl.answer(id).answer("access", true).build()));
		assertEquals(Hitl.OPEN, readRecord(ALICE, id).get(Hitl.STATUS));

		// Alice answers, approving and echoing the grant: Bob's job completes
		// with a token audienced to BOB over ALICE's resource.
		respond(ALICE, Hitl.answer(id)
			.answer("access", true)
			.echo("w/reports/", "crud/read")
			.build());
		assertEquals(Status.COMPLETE, job.getStatus());
		UCAN token = UCAN.fromJWT(RT.ensureString(RT.getIn(job.getOutput(), "token")));
		assertEquals(BOB_DID, token.getAudience());
		assertEquals(Strings.create(ALICE_DID + "/w/reports/"),
			RT.getIn(token.getCapabilities().get(0), Capability.WITH));
	}

	// ========== Expiry and cancellation ==========

	@Test
	public void testExpiry() {
		Job job = request(ALICE, Hitl.request("quick")
			.ask(Hitl.approval("ok", "OK?")).timeout(1).build());
		String id = job.getID().toHexString();
		assertEquals(Status.INPUT_REQUIRED, job.getStatus());

		// The expiry timer fails the job; awaitResult surfaces it.
		assertThrows(JobFailedException.class, () -> job.awaitResult(15000));
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains("expired"));
		assertEquals(Hitl.EXPIRED, readRecord(ALICE, id).get(Hitl.STATUS));

		// ADVERSARIAL: responding to an expired request must fail.
		assertThrows(Exception.class, () -> respond(ALICE,
			Hitl.answer(id).answer("ok", true).build()));
	}

	@Test
	public void testCancelMarksRecordCancelled() {
		Job job = request(ALICE, Hitl.request("c")
			.ask(Hitl.approval("ok", "OK?")).build());
		String id = job.getID().toHexString();

		engine.jobs().cancelJob(job.getID(), ALICE);
		assertEquals(Status.CANCELLED, job.getStatus());
		assertEquals(Hitl.CANCELLED, readRecord(ALICE, id).get(Hitl.STATUS),
			"the cancel hook marks the inbox record cancelled");
		assertThrows(Exception.class, () -> respond(ALICE,
			Hitl.answer(id).answer("ok", true).build()));
	}
}
