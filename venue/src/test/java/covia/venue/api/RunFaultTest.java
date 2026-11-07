package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.TestAdapter;
import covia.exception.FaultException;
import covia.grid.Job;
import covia.venue.TestLogs;
import covia.venue.TestServer;

/**
 * An exception escaping an adapter is that adapter's fault, not the caller's
 * error: {@code /run} answers 500 with the operation's report, naming the
 * adapter and the exception's type, never its text (ERRORS.md, Scopes).
 * Isolated because the venue logs the fault at ERROR, as it must, and the
 * test silences that line.
 */
@Isolated
public class RunFaultTest {

	private static final String OP = "v/test/ops/failllm";

	@Test
	public void anAdaptersExceptionIsItsOperationsFault() throws Exception {
		AtomicReference<HttpResponse<String>> response = new AtomicReference<>();
		TestLogs.quiet(FaultException.class, () -> response.set(run(
			"{\"operation\":\"" + OP + "\",\"input\":{\"model\":\"bug\"}}")));
		HttpResponse<String> resp = response.get();
		assertEquals(500, resp.statusCode(), resp.body());
		ACell body = JSON.parse(resp.body());
		assertEquals(FaultException.FAILED.formatted(Job.OPERATION.formatted(OP), "IllegalStateException",
			FaultException.ADAPTER.formatted("test")), RT.getIn(body, "detail").toString());
		assertFalse(resp.body().contains(TestAdapter.FAILLLM_REASON.formatted("bug")),
			"the exception's text never reaches the caller: " + resp.body());
	}

	private static HttpResponse<String> run(String body) throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
			.uri(URI.create(TestServer.BASE_URL + "/api/v1/run"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		return covia.venue.TestHTTP.CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
	}
}
