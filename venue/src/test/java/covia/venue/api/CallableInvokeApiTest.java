package covia.venue.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.venue.Engine;
import covia.venue.JobManager;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.TestHTTP;
import covia.venue.TestServer;

/**
 * A callable invoked by its path over REST (CALLABLES.md): Bob's client
 * invokes Alice's guestbook at {@code POST /api/v1/invoke} as it would any
 * operation, with the target's input, and gets a job of Bob's; a callable Bob
 * may not call answers exactly as one that does not exist.
 */
public class CallableInvokeApiTest {

	private static final String INVOKE_URL = TestServer.BASE_URL + "/api/v1/invoke?wait=true";
	private final Engine engine = TestServer.ENGINE;
	private final HttpClient http = TestHTTP.CLIENT;

	private Job run(String op, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		TestEngine.awaitJob(job, Job::isFinished, 10000, () -> op + " never finished");
		assertEquals(Status.COMPLETE, job.getStatus(), job::getErrorMessage);
		return job;
	}

	/** Invokes {@code operation} with {@code input} over REST with {@code bearer}, waiting for its job. */
	private HttpResponse<String> invoke(String bearer, String operation, ACell input) throws Exception {
		String body = JSON.toString(Maps.of(Fields.OPERATION, operation, Fields.INPUT, input));
		return http.send(HttpRequest.newBuilder(URI.create(INVOKE_URL))
			.header("Content-Type", "application/json").header("Authorization", "Bearer " + bearer)
			.timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
			HttpResponse.BodyHandlers.ofString());
	}

	private ACell held(AString owner, String path) {
		return RT.getIn(run("v/ops/covia/read", Maps.of(Fields.PATH, path), RequestContext.of(owner)).getOutput(), Fields.VALUE);
	}

	@Test
	public void aClientInvokesACallableByItsPath(TestInfo info) throws Exception {
		AString alice = TestEngine.uniqueDID(info);
		VenueAuth bobsKeys = VenueAuth.keyPair(AKeyPair.generate());
		AString bob = Strings.create(bobsKeys.getDID());
		String bearer = bobsKeys.mintToken(engine.getDIDString().toString());
		RequestContext owner = RequestContext.of(alice);
		run("v/ops/covia/write", Maps.of(Fields.PATH, "w/guestbook", Fields.VALUE, Vectors.empty()), owner);
		ACell caps = Vectors.of(Capability.create(Strings.create("w/guestbook"), Capability.CRUD_WRITE));
		run("v/ops/callable/create", Maps.of(Fields.NAME, "guestbook", Fields.OPERATION, "v/ops/covia/append",
			Fields.CAPS, caps, Fields.GATE, "v/ops/callable/gates/anyone"), owner);
		run("v/ops/callable/create", Maps.of(Fields.NAME, "private", Fields.OPERATION, "v/ops/covia/append",
			Fields.CAPS, caps), owner);
		String guestbook = alice + "/x/callable/guestbook";
		String hidden = alice + "/x/callable/private";
		String missing = alice + "/x/callable/missing";
		AMap<AString, ACell> signing = Maps.of(Fields.PATH, "w/guestbook", Fields.VALUE, "Hello from Bob");

		// The job is Bob's, takes the target's input, and the target writes Alice's guestbook
		HttpResponse<String> invoked = invoke(bearer, guestbook, signing);
		assertEquals(200, invoked.statusCode(), invoked.body());
		AMap<AString, ACell> record = RT.ensureMap(JSON.parse(invoked.body()));
		assertEquals(Status.COMPLETE, record.get(Fields.STATUS), record::toString);
		assertEquals(bob, record.get(Fields.CALLER));
		assertEquals(Strings.create(guestbook), record.get(Fields.OP));
		assertEquals(signing, record.get(Fields.INPUT));
		assertEquals(Vectors.of("Hello from Bob"), held(alice, "w/guestbook"));

		// A callable Bob may not call answers as one that does not exist, and makes no job
		HttpResponse<String> refused = invoke(bearer, hidden, signing);
		HttpResponse<String> absent = invoke(bearer, missing, signing);
		assertEquals(absent.statusCode(), refused.statusCode());
		assertEquals(absent.body().replace(missing, "<path>"), refused.body().replace(hidden, "<path>"));
		assertTrue(refused.body().contains(JobManager.CANNOT_RESOLVE_OPERATION + hidden), refused::body);
		assertEquals(Vectors.of("Hello from Bob"), held(alice, "w/guestbook"));
	}
}
