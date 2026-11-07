package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import covia.exception.CoviaException;
import covia.exception.FaultException;
import covia.exception.InvalidInputException;
import covia.exception.JobFailedException;
import covia.exception.NotSupportedException;
import covia.venue.RequestContext;
import covia.venue.TestLogs;

/**
 * Failure text callers read: a Covia exception's reason, and anything else as
 * a fault of the scope it escaped, naming its owner and its type, never its
 * text. Isolated because the fault cases silence the ERROR line that reports
 * them.
 */
@Isolated
public class DescribeFailureTest {

	private static final AAdapter CROWN = new AAdapter() {
		@Override public String getName() { return "crown"; }
		@Override public String getDescription() { return "A test adapter"; }
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			return CompletableFuture.completedFuture(null);
		}
	};

	private static final String OWNER = FaultException.ADAPTER.formatted("crown");

	@Test
	public void testExpectedFailuresAreTheirReason() {
		assertEquals("no such db", CROWN.describeFailure(new JobFailedException("no such db")));
		assertEquals("chat_id is required", CROWN.describeFailure(new InvalidInputException("chat_id is required")));
		assertEquals("wrapped", CROWN.describeFailure(new CompletionException(new NotSupportedException("wrapped"))));
		InvalidInputException refused = new InvalidInputException("bad");
		assertSame(refused, FaultException.within("Operation x", OWNER, new CompletionException(refused)),
			"a reported failure passes as it is");
	}

	@Test
	public void testUnexpectedFailuresAreTheAdaptersFault() throws Exception {
		TestLogs.quiet(FaultException.class, () -> {
			String ncdfe = CROWN.describeFailure(new NoClassDefFoundError("com/pengrad/telegrambot/model/request/ChatAction"));
			assertEquals(FaultException.UNEXPECTED.formatted("NoClassDefFoundError", OWNER) + AAdapter.CLASS_NOT_LOADED, ncdfe);
			assertFalse(ncdfe.contains("pengrad"), "never the message: " + ncdfe);
			assertEquals(FaultException.UNEXPECTED.formatted("IllegalArgumentException", OWNER),
				CROWN.describeFailure(new IllegalArgumentException("chat_id is required")));
			assertEquals(FaultException.UNEXPECTED.formatted("RuntimeException", OWNER),
				CROWN.describeFailure(new CompletionException(new RuntimeException("token=private"))));
			assertEquals(FaultException.UNEXPECTED.formatted("InvalidInputException", OWNER),
				CROWN.describeFailure(new InvalidInputException((String) null)), "a reason is what makes it reported");

			IllegalArgumentException thrown = new IllegalArgumentException("crown requires x");
			CoviaException reported = FaultException.within("Operation crown:query", OWNER, thrown);
			FaultException fault = assertInstanceOf(FaultException.class, reported);
			assertEquals(FaultException.FAILED.formatted("Operation crown:query", "IllegalArgumentException", OWNER),
				fault.getMessage());
			assertSame(thrown, fault.getCause(), "the cause is kept for the log");
			assertTrue(FaultException.isFault(new CompletionException(fault)));
			assertSame(fault, FaultException.within("Request", FaultException.VENUE, fault),
				"an outer scope passes a reported fault as it is");
		});
		assertEquals("Operation failed without an error detail", CROWN.describeFailure(null));
	}

	@Test
	public void testOriginNamesAUrlWithoutWhatMayCarryACredential() {
		assertEquals("https://mcp.example.com",
			AAdapter.origin("https://user:secret@mcp.example.com/api/mcp/s/tok3n/mcp?api_key=k"));
		assertEquals("http://localhost:11434", AAdapter.origin("HTTP://localhost:11434/"));
		assertEquals(null, AAdapter.origin("did:web:venue.example"));
		assertEquals(null, AAdapter.origin("not a url"));
		assertEquals(null, AAdapter.origin(null));
	}
}
