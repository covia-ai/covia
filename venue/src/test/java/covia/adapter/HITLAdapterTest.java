package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
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
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * HITL lifecycle (COG-16): record in the target's h/ inbox + Job carrier +
 * response op. Includes the adversarial set: delivery without a hitl/request
 * delegation, non-target respond, echo of untriggered/unoffered grants,
 * answer-shape violations, respond-after-expiry, and cancel.
 *
 * <p>Per-test key pairs isolate user namespaces on the shared engine.</p>
 */
public class HITLAdapterTest {

	private final Engine engine = TestEngine.ENGINE;

	private AKeyPair ALICE_KP;
	private AKeyPair BOB_KP;
	private AString ALICE_DID;
	private AString BOB_DID;
	private RequestContext ALICE;
	private RequestContext BOB;

	private static final long HOUR = 3600;

	@BeforeEach
	public void setup() {
		ALICE_KP = AKeyPair.generate();
		BOB_KP = AKeyPair.generate();
		ALICE_DID = UCAN.toDIDKey(ALICE_KP.getAccountKey());
		BOB_DID = UCAN.toDIDKey(BOB_KP.getAccountKey());
		ALICE = RequestContext.of(ALICE_DID);
		BOB = RequestContext.of(BOB_DID);
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

	private static AVector<ACell> approvalAsk(String id, ACell grants) {
		AMap<AString, ACell> ask = Maps.of(
			"id", id, "type", "approval", "prompt", "Approve?", "required", CVMBool.TRUE);
		if (grants != null) ask = ask.assoc(HITLAdapter.K_GRANTS, grants);
		return Vectors.of((ACell) ask);
	}

	// ========== Lifecycle ==========

	@Test
	public void testAnswerLifecycle() {
		Job job = request(ALICE, Maps.of(
			"title", "Pay invoice",
			"description", "Invoice INV-1 for testing",
			"asks", approvalAsk("pay", null)));
		assertEquals(Status.INPUT_REQUIRED, job.getStatus(),
			"a delivered HITL request parks the job awaiting the human");
		String id = job.getID().toHexString();

		AMap<AString, ACell> record = readRecord(ALICE, id);
		assertNotNull(record, "the record must land in the target's h/ inbox");
		assertEquals(HITLAdapter.S_OPEN, record.get(HITLAdapter.K_STATUS));
		assertEquals(ALICE_DID, record.get(HITLAdapter.K_FROM),
			"from is the VERIFIED caller identity, venue-set");

		// The inbox listing shows the open ask.
		ACell list = engine.jobs().invokeOperation("v/ops/hitl/list",
			Maps.of("status", "open"), ALICE).awaitResult(5000);
		assertTrue(RT.ensureLong(RT.getIn(list, "count")).longValue() >= 1);

		respond(ALICE, Maps.of("id", id, "outcome", "answer",
			"answers", Maps.of("pay", CVMBool.TRUE),
			"comment", "approved"));

		assertEquals(Status.COMPLETE, job.getStatus());
		ACell output = job.getOutput();
		assertEquals(CVMBool.TRUE, RT.getIn(output, "answers", "pay"));
		assertEquals(Strings.create(id), RT.getIn(output, "id"));
		assertEquals(HITLAdapter.S_ANSWERED, readRecord(ALICE, id).get(HITLAdapter.K_STATUS));
	}

	@Test
	public void testRejectFailsJob() {
		Job job = request(ALICE, Maps.of("title", "Pay?", "asks", approvalAsk("pay", null)));
		String id = job.getID().toHexString();

		respond(ALICE, Maps.of("id", id, "outcome", "reject", "comment", "Wrong PO"));

		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains("rejected"));
		assertTrue(job.getErrorMessage().contains("Wrong PO"),
			"the rejection reason must travel in the job error — the requester cannot read the inbox");
		assertEquals(HITLAdapter.S_REJECTED, readRecord(ALICE, id).get(HITLAdapter.K_STATUS));
	}

	// ========== Validation (adversarial) ==========

	@Test
	public void testAnswerValidationAdversarial() {
		Job job = request(ALICE, Maps.of("title", "Setup",
			"asks", Vectors.of(
				(ACell) Maps.of("id", "pay", "type", "approval", "prompt", "Pay?", "required", CVMBool.TRUE),
				(ACell) Maps.of("id", "tier", "type", "choice", "prompt", "Tier?",
					"options", Vectors.of(
						(ACell) Maps.of("id", "fast", "label", "Fast"),
						(ACell) Maps.of("id", "best", "label", "Best"))))));
		String id = job.getID().toHexString();

		// Missing required ask
		assertThrows(Exception.class, () -> respond(ALICE,
			Maps.of("id", id, "outcome", "answer", "answers", Maps.of("tier", "fast"))));
		// Unknown option id
		assertThrows(Exception.class, () -> respond(ALICE,
			Maps.of("id", id, "outcome", "answer",
				"answers", Maps.of("pay", CVMBool.TRUE, "tier", "zzz"))));
		// Unknown ask id
		assertThrows(Exception.class, () -> respond(ALICE,
			Maps.of("id", id, "outcome", "answer",
				"answers", Maps.of("pay", CVMBool.TRUE, "bogus", "x"))));
		// Wrong answer shape for approval
		assertThrows(Exception.class, () -> respond(ALICE,
			Maps.of("id", id, "outcome", "answer", "answers", Maps.of("pay", "yes"))));

		// Failed responses left the record open and the job unfinished...
		assertEquals(HITLAdapter.S_OPEN, readRecord(ALICE, id).get(HITLAdapter.K_STATUS));
		assertFalse(job.isFinished());
		// ...and a valid response still resolves it.
		respond(ALICE, Maps.of("id", id, "outcome", "answer",
			"answers", Maps.of("pay", CVMBool.TRUE, "tier", "fast")));
		assertEquals(Status.COMPLETE, job.getStatus());
	}

	@Test
	public void testRequestValidationAdversarial() {
		// Empty asks
		assertThrows(Exception.class, () -> request(ALICE,
			Maps.of("title", "t", "asks", Vectors.empty())));
		// Unknown ask type
		assertThrows(Exception.class, () -> request(ALICE,
			Maps.of("title", "t", "asks", Vectors.of(
				(ACell) Maps.of("id", "a", "type", "essay", "prompt", "p")))));
		// choice without options
		assertThrows(Exception.class, () -> request(ALICE,
			Maps.of("title", "t", "asks", Vectors.of(
				(ACell) Maps.of("id", "a", "type", "choice", "prompt", "p")))));
		// Grants on a TEXT ask — a grant must ride an explicit choice
		assertThrows(Exception.class, () -> request(ALICE,
			Maps.of("title", "t", "asks", Vectors.of(
				(ACell) Maps.of("id", "a", "type", "text", "prompt", "p",
					"grants", Vectors.of((ACell) Capability.create(
						Strings.create("w/x"), Capability.CRUD_READ)))))));
	}

	// ========== Echo-consent grants ==========

	@Test
	public void testEchoConsentGrants() {
		ACell offered = Vectors.of((ACell) Capability.create(
			Strings.create("w/reports/"), Capability.CRUD_READ));

		// ADVERSARIAL: echoing the grant while DENYING the approval — not triggered.
		Job denied = request(ALICE, Maps.of("title", "g", "asks", approvalAsk("access", offered)));
		assertThrows(Exception.class, () -> respond(ALICE,
			Maps.of("id", denied.getID().toHexString(), "outcome", "answer",
				"answers", Maps.of("access", CVMBool.FALSE),
				"grants", offered)));
		assertFalse(denied.isFinished(), "a failed response must not resolve the job");

		// ADVERSARIAL: echoing a grant that was never offered.
		Job crafted = request(ALICE, Maps.of("title", "g", "asks", approvalAsk("access", offered)));
		assertThrows(Exception.class, () -> respond(ALICE,
			Maps.of("id", crafted.getID().toHexString(), "outcome", "answer",
				"answers", Maps.of("access", CVMBool.TRUE),
				"grants", Vectors.of((ACell) Capability.create(
					Strings.create("w/private/"), Strings.create("crud"))))));

		// Approving WITHOUT echoing confers nothing.
		Job silent = request(ALICE, Maps.of("title", "g", "asks", approvalAsk("access", offered)));
		respond(ALICE, Maps.of("id", silent.getID().toHexString(), "outcome", "answer",
			"answers", Maps.of("access", CVMBool.TRUE)));
		assertEquals(Status.COMPLETE, silent.getStatus());
		assertNull(RT.getIn(silent.getOutput(), "token"), "no echo, no token");
		assertNull(RT.getIn(silent.getOutput(), "grants"));

		// Approve + echo → token issued, audienced to the requester, resource
		// canonicalised to the RESPONDER's namespace.
		Job granted = request(ALICE, Maps.of("title", "g", "asks", approvalAsk("access", offered)));
		respond(ALICE, Maps.of("id", granted.getID().toHexString(), "outcome", "answer",
			"answers", Maps.of("access", CVMBool.TRUE),
			"grants", offered));
		assertEquals(Status.COMPLETE, granted.getStatus());
		AString jwt = RT.ensureString(RT.getIn(granted.getOutput(), "token"));
		assertNotNull(jwt, "echoed-and-triggered grants must issue a token");
		UCAN token = UCAN.fromJWT(jwt);
		assertEquals(ALICE_DID, token.getAudience(), "audience is the requester");
		assertEquals(Strings.create(ALICE_DID + "/w/reports/"),
			RT.getIn(token.getCapabilities().get(0), Capability.WITH),
			"bare offered resources canonicalise to the responder's namespace at issuance");
	}

	// ========== Cross-user delivery ==========

	@Test
	public void testCrossUserDelivery() {
		// ADVERSARIAL: no delegation → delivery denied, job FAILED, no record.
		Job blocked = request(BOB, Maps.of("user", ALICE_DID,
			"title", "gimme", "asks", approvalAsk("ok", null)));
		assertEquals(Status.FAILED, blocked.getStatus());
		assertTrue(blocked.getErrorMessage().contains("hitl/request"));
		assertNull(readRecord(ALICE, blocked.getID().toHexString()),
			"a denied delivery must leave no record in the target's inbox");

		// With an Alice-signed hitl/request delegation, delivery succeeds.
		long exp = (System.currentTimeMillis() / 1000) + HOUR;
		UCAN delegation = UCAN.create(ALICE_KP, UCAN.fromDIDKey(BOB_DID), exp,
			Vectors.of(Capability.create(
				Strings.create(ALICE_DID + "/h/"), HITLAdapter.ABILITY_HITL_REQUEST)),
			Vectors.empty());
		ACell offered = Vectors.of((ACell) Capability.create(
			Strings.create("w/reports/"), Capability.CRUD_READ));
		Job job = request(BOB.withProofs(Vectors.of(delegation.toMap())),
			Maps.of("user", ALICE_DID, "title", "Report access",
				"asks", approvalAsk("access", offered)));
		assertEquals(Status.INPUT_REQUIRED, job.getStatus());
		String id = job.getID().toHexString();

		AMap<AString, ACell> record = readRecord(ALICE, id);
		assertEquals(BOB_DID, record.get(HITLAdapter.K_FROM),
			"from is Bob — the verified requester, not the inbox owner");

		// ADVERSARIAL: the requester cannot respond — the record is not in HIS inbox.
		assertThrows(Exception.class, () -> respond(BOB,
			Maps.of("id", id, "outcome", "answer", "answers", Maps.of("access", CVMBool.TRUE))));
		assertEquals(HITLAdapter.S_OPEN, readRecord(ALICE, id).get(HITLAdapter.K_STATUS));

		// Alice answers, approving and echoing the grant: Bob's job completes
		// with a token audienced to BOB over ALICE's resource.
		respond(ALICE, Maps.of("id", id, "outcome", "answer",
			"answers", Maps.of("access", CVMBool.TRUE),
			"grants", offered));
		assertEquals(Status.COMPLETE, job.getStatus());
		UCAN token = UCAN.fromJWT(RT.ensureString(RT.getIn(job.getOutput(), "token")));
		assertEquals(BOB_DID, token.getAudience());
		assertEquals(Strings.create(ALICE_DID + "/w/reports/"),
			RT.getIn(token.getCapabilities().get(0), Capability.WITH));
	}

	// ========== Expiry and cancellation ==========

	@Test
	public void testExpiry() {
		Job job = request(ALICE, Maps.of("title", "quick",
			"asks", approvalAsk("ok", null),
			"timeout", CVMLong.create(1)));
		String id = job.getID().toHexString();
		assertEquals(Status.INPUT_REQUIRED, job.getStatus());

		// The expiry timer fails the job; awaitResult surfaces it.
		assertThrows(JobFailedException.class, () -> job.awaitResult(15000));
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains("expired"));
		assertEquals(HITLAdapter.S_EXPIRED, readRecord(ALICE, id).get(HITLAdapter.K_STATUS));

		// ADVERSARIAL: responding to an expired request must fail.
		assertThrows(Exception.class, () -> respond(ALICE,
			Maps.of("id", id, "outcome", "answer", "answers", Maps.of("ok", CVMBool.TRUE))));
	}

	@Test
	public void testCancelMarksRecordCancelled() {
		Job job = request(ALICE, Maps.of("title", "c", "asks", approvalAsk("ok", null)));
		String id = job.getID().toHexString();

		engine.jobs().cancelJob(job.getID(), ALICE);
		assertEquals(Status.CANCELLED, job.getStatus());
		assertEquals(HITLAdapter.S_CANCELLED, readRecord(ALICE, id).get(HITLAdapter.K_STATUS),
			"the cancel hook marks the inbox record cancelled");
		assertThrows(Exception.class, () -> respond(ALICE,
			Maps.of("id", id, "outcome", "answer", "answers", Maps.of("ok", CVMBool.TRUE))));
	}
}
