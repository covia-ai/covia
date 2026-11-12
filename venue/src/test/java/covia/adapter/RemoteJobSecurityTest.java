package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpResponse;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

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
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.JobFailedException;
import covia.exception.ResponseException;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.UcanTokens;
import covia.test.IntegrationTest;
import covia.venue.Engine;
import covia.venue.JobManager;
import covia.venue.RequestContext;
import covia.venue.TwoVenueTestServer;

/** Adversarial calls through the real relay and destination, with positive controls. */
@IntegrationTest
class RemoteJobSecurityTest {
	private static final AString AUTHENTICATE_AS = Strings.create("authenticateAs");
	private final Engine source = TwoVenueTestServer.ENGINE_A;
	private final Engine destination = TwoVenueTestServer.ENGINE_B;
	private final AKeyPair alice = AKeyPair.generate(), bob = AKeyPair.generate();
	private final AString aliceDID = UCAN.toDIDKey(alice.getAccountKey());
	private final AString bobDID = UCAN.toDIDKey(bob.getAccountKey());
	private final String identity = UcanTokens.identityToken(bob, TwoVenueTestServer.DID_B, 300);

	private Job target(String operation) {
		return destination.jobs().invokeOperation("v/test/ops/" + operation,
			Maps.of("private", "owner-only payload"), RequestContext.of(aliceDID));
	}

	private RequestContext delegate(Job target, AString ability) {
		AString grant = UCAN.createJWT(alice, bob.getAccountKey(), System.currentTimeMillis() / 1000 + 300,
			Vectors.of(Capability.create(JobManager.resource(aliceDID, target.getID()), ability)), Vectors.empty());
		return RequestContext.of(bobDID).withCaps(Vectors.of(Capability.create(Strings.create("v/ops/"), Abilities.INVOKE)))
			.withRawUcans(Vectors.of(Strings.create(identity), grant));
	}

	private AMap<AString, ACell> input(Job target) {
		return Maps.of(Fields.VENUE, TwoVenueTestServer.BASE_URL_B, Fields.ID, target.getID().toHexString(),
			Fields.OWNER, aliceDID, AUTHENTICATE_AS, "caller", Fields.MESSAGE, Maps.of(Fields.CONTENT, "done"));
	}

	private ACell call(String operation, ACell input, RequestContext ctx) {
		return source.jobs().runToolOperation(Strings.create("v/ops/" + operation), input, ctx).join();
	}

	private void forbidden(String operation, ACell input, RequestContext ctx) {
		CompletionException failure = assertThrows(CompletionException.class, () -> call(operation, input, ctx));
		Throwable cause = failure.getCause();
		while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
		ResponseException response = assertInstanceOf(ResponseException.class, cause);
		assertEquals(403, assertInstanceOf(HttpResponse.class, response.response).statusCode(),
			"a transport failure or missing operation is not an authorization refusal");
		assertFalse(failure.toString().contains("owner-only payload"));
		assertFalse(failure.toString().contains(identity));
	}

	@Test void remoteRightsAreIndependentAndCannotBeRetargetedToASibling() {
		Job target = target("never"), sibling = target("never"), chat = target("chat");
		try {
			RequestContext reader = delegate(target, Abilities.JOB_READ);
			assertEquals(Status.STARTED, RT.getIn(call("job/status", input(target), reader), Fields.STATUS));
			for (String action : new String[]{"cancel", "pause", "resume", "message"})
				forbidden("job/" + action, input(target), reader);
			forbidden("job/status", input(sibling), reader);
			assertEquals(Status.STARTED, target.getStatus());

			RequestContext manager = delegate(target, Abilities.JOB_MANAGE);
			forbidden("job/status", input(target), manager);
			forbidden("grid/job-status", input(target), manager);
			forbidden("job/message", input(target), manager);
			forbidden("job/cancel", input(sibling), manager);
			for (String action : new String[]{"pause", "resume", "cancel"}) {
				ACell ack = call("job/" + action, input(target), manager);
				assertEquals(JobManager.acknowledgement(target.getID()), ack,
					"manage-only calls must not disclose snapshots, input or output");
			}
			assertEquals(Status.CANCELLED, target.getStatus());
			assertEquals(Status.STARTED, sibling.getStatus());

			RequestContext messenger = delegate(chat, Abilities.JOB_MESSAGE);
			forbidden("job/status", input(chat), messenger);
			forbidden("job/cancel", input(chat), messenger);
			forbidden("job/message", input(sibling), messenger);
			assertNotNull(call("job/message", input(chat), messenger));
			assertEquals(Status.COMPLETE, chat.getStatus());
			assertEquals(Status.STARTED, sibling.getStatus());
		} finally { target.cancel(); sibling.cancel(); chat.cancel(); }
	}

	@Test void genericAndNestedRemoteCallsCannotLaunderAReadGrantIntoCancellation() throws Exception {
		Job target = target("never");
		try {
			RequestContext reader = delegate(target, Abilities.JOB_READ);
			for (boolean nested : new boolean[]{false, true}) {
				ACell arguments = Maps.of(Fields.ID, target.getID().toHexString(), Fields.OWNER, aliceDID);
				String operation = "v/ops/job/cancel";
				if (nested) {
					arguments = Maps.of(Fields.OPERATION, operation, Fields.INPUT, arguments);
					operation = "v/ops/grid/run";
				}
				AMap<AString, ACell> relay = Maps.of(Fields.VENUE, TwoVenueTestServer.BASE_URL_B,
					AUTHENTICATE_AS, "caller", Fields.OPERATION, operation, Fields.INPUT, arguments);
				CompletionException failed = assertThrows(CompletionException.class, () -> call("grid/run", relay, reader));
				assertInstanceOf(JobFailedException.class, failed.getCause());
				ACell receipt = call("grid/invoke", relay, reader);
				Job attempt = destination.jobs().getJob(Job.parseID(RT.getIn(receipt, Fields.ID)), RequestContext.of(bobDID));
				assertNotNull(attempt);
				assertThrows(java.util.concurrent.ExecutionException.class, () -> attempt.future().get(5, TimeUnit.SECONDS));
				assertEquals(Status.FAILED, attempt.getStatus());
				assertTrue(attempt.getErrorMessage().contains(JobManager.JOB_ACCESS_DENIED), attempt.getErrorMessage());
				assertEquals(Status.STARTED, target.getStatus());
			}
		} finally { target.cancel(); }
	}

	@Test void authenticationModeAndLookupOwnerCannotBorrowAnotherIdentity() {
		Job target = target("never");
		try {
			RequestContext reader = delegate(target, Abilities.JOB_READ);
			// Having a credential does not select it, and knowing an owner grants no access.
			forbidden("job/status", input(target).dissoc(AUTHENTICATE_AS), reader);
			forbidden("job/status", input(target).assoc(AUTHENTICATE_AS, Strings.create("anonymous")), reader);
			forbidden("job/status", input(target), reader.withRawUcans(Vectors.of(Strings.create(identity))));
			CompletionException failure = assertThrows(CompletionException.class,
				() -> call("job/cancel", input(target).assoc(AUTHENTICATE_AS, Strings.create("venue")), reader));
			assertInstanceOf(AuthException.class, failure.getCause());
			assertEquals(Status.STARTED, target.getStatus());
			ACell record = call("job/status", input(target), reader);
			assertEquals(target.getID(), Job.parseID(RT.getIn(record, Fields.ID)));
			for (ACell credential : reader.getRawUcans())
				assertFalse(JSON.toString(record).contains(credential.toString()), "transport credentials must not be public Job data");
		} finally { target.cancel(); }
	}
}
