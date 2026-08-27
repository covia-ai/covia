package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * A venue that fails to start says what failed and why on one line, before
 * the stack trace: the failure and every distinct cause beneath it.
 */
public class MainVenueTest {

	@Test
	public void testStartupFailureNamesEveryCause() {
		Throwable parse = new IllegalArgumentException("Unexpected character '}' at line 7");
		Throwable install = new IllegalStateException(
			"Failed to install adapter asset from /skills/http.json: Unexpected character '}' at line 7", parse);
		Throwable init = new ExceptionInInitializerError(install);
		Throwable launch = new RuntimeException("Venue launch failed", init);

		String line = MainVenue.describeStartupFailure(launch);
		// The install message already carries the parse detail, so the parse
		// exception is not repeated; the message-less initializer error is
		// named by type.
		assertEquals("Venue launch failed — caused by: ExceptionInInitializerError"
			+ " — caused by: Failed to install adapter asset from /skills/http.json:"
			+ " Unexpected character '}' at line 7", line);

		assertEquals("boom", MainVenue.describeStartupFailure(new RuntimeException("boom")));
		assertEquals("NullPointerException", MainVenue.describeStartupFailure(new NullPointerException()));
	}
}
