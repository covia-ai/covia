package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.TestAdapter;
import covia.exception.FaultException;
import covia.exception.IntegrityException;
import covia.grid.Job;
import covia.venue.TestLogs;
import covia.venue.TestServer;

/**
 * An exception escaping an adapter is that adapter's fault, not the caller's
 * error: {@code /run} answers 500 with the operation's report, naming the
 * adapter and the exception's type, never its text (ERRORS.md, Scopes).
 * Isolated because the venue logs the fault at ERROR, as it must, and the
 * tests silence or capture that line.
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

	/**
	 * An integrity failure is the venue's own, not the caller's: {@code /run}
	 * answers 500 with its message, which says what failed, and the venue logs
	 * it once at ERROR, though no code before the job's report logged it.
	 */
	@Test
	public void anIntegrityFailureIsTheVenuesLoggedOnce() throws Exception {
		Logger covia = (Logger) LoggerFactory.getLogger("covia");
		ListAppender<ILoggingEvent> captured = new ListAppender<>();
		Level level = covia.getLevel();
		boolean additive = covia.isAdditive();
		captured.start();
		covia.setLevel(Level.ERROR);
		covia.setAdditive(false);
		covia.addAppender(captured);
		HttpResponse<String> resp;
		try {
			resp = run("{\"operation\":\"" + OP + "\",\"input\":{\"model\":\"integrity\"}}");
		} finally {
			covia.detachAppender(captured);
			covia.setLevel(level);
			covia.setAdditive(additive);
			captured.stop();
		}
		assertEquals(500, resp.statusCode(), resp.body());
		assertEquals(TestAdapter.FAILLLM_REASON.formatted("integrity"),
			RT.getIn(JSON.parse(resp.body()), "detail").toString());
		List<ILoggingEvent> errors = List.copyOf(captured.list);
		assertEquals(1, errors.size(), errors.toString());
		assertEquals(IntegrityException.class.getName(), errors.get(0).getLoggerName());
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
