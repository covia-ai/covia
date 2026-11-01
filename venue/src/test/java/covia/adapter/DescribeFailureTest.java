package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import covia.exception.CoviaException;
import covia.exception.InvalidInputException;
import covia.exception.JobFailedException;
import covia.exception.NotSupportedException;

/** Failure text callers read (#644): an expected failure's reason, anything else only its type. */
public class DescribeFailureTest {

	@Test
	public void testExpectedFailuresAreTheirReason() {
		assertEquals("no such db", AAdapter.describeFailure(new JobFailedException("no such db")));
		assertEquals("chat_id is required", AAdapter.describeFailure(new InvalidInputException("chat_id is required")));
		assertEquals("wrapped", AAdapter.describeFailure(new CompletionException(new NotSupportedException("wrapped"))));
	}

	@Test
	public void testUnexpectedFailuresShowOnlyTheirType() {
		String ncdfe = AAdapter.describeFailure(new NoClassDefFoundError("com/pengrad/telegrambot/model/request/ChatAction"));
		assertTrue(ncdfe.startsWith(CoviaException.UNEXPECTED.formatted("NoClassDefFoundError")), ncdfe);
		assertTrue(ncdfe.contains("restart the venue"), "linkage errors carry the actionable hint: " + ncdfe);
		assertFalse(ncdfe.contains("pengrad"), "never the message: " + ncdfe);
		assertEquals(CoviaException.UNEXPECTED.formatted("IllegalArgumentException"),
			AAdapter.describeFailure(new IllegalArgumentException("chat_id is required")));
		assertEquals(CoviaException.UNEXPECTED.formatted("RuntimeException"),
			AAdapter.describeFailure(new CompletionException(new RuntimeException("token=private"))));
		assertEquals(CoviaException.UNEXPECTED.formatted("NullPointerException"),
			AAdapter.describeFailure(new NullPointerException()));
		assertEquals(CoviaException.UNEXPECTED.formatted("InvalidInputException"),
			AAdapter.describeFailure(new InvalidInputException((String) null)), "a reason is what makes it expected");
		assertEquals("Operation failed without an error detail", AAdapter.describeFailure(null));
	}
}
