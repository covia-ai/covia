package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import covia.exception.JobFailedException;

/** Failure text people read: self-describing messages as-is, everything else named by type. */
public class DescribeFailureTest {

	@Test
	public void testSelfDescribingMessagesPassThrough() {
		assertEquals("no such db", AAdapter.describeFailure(new JobFailedException("no such db")));
		assertEquals("chat_id is required", AAdapter.describeFailure(new IllegalArgumentException("chat_id is required")));
		assertEquals("plain carrier", AAdapter.describeFailure(new RuntimeException("plain carrier")));
		assertEquals("wrapped", AAdapter.describeFailure(new CompletionException(new IllegalStateException("wrapped"))));
	}

	@Test
	public void testOpaqueMessagesAreNamedByType() {
		String ncdfe = AAdapter.describeFailure(new NoClassDefFoundError("com/pengrad/telegrambot/model/request/ChatAction"));
		assertTrue(ncdfe.startsWith("NoClassDefFoundError: com/pengrad/telegrambot/model/request/ChatAction"), ncdfe);
		assertTrue(ncdfe.contains("restart the venue"), "linkage errors carry the actionable hint: " + ncdfe);
		assertEquals("NullPointerException (no detail)", AAdapter.describeFailure(new NullPointerException()));
		assertEquals("ClassCastException: x", AAdapter.describeFailure(new ClassCastException("x")));
		assertEquals("Operation failed without an error detail", AAdapter.describeFailure(null));
	}
}
