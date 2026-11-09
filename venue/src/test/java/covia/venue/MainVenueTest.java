package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.store.MemoryStore;
import covia.venue.server.VenueServer;

/**
 * How the process starts and stops its venues. A venue that fails to start says
 * what failed and why on one line, before the stack trace: the failure and every
 * distinct cause beneath it. A process that is stopping runs each venue's full
 * close and waits for one already under way.
 */
public class MainVenueTest {

	private static AMap<AString, ACell> memoryVenue() {
		return Maps.of(Strings.create("port"), 0, Config.STORE, "memory");
	}

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

	@Test
	public void stoppingRunsEachVenuesFullClose() {
		VenueServer server = VenueServer.launch(memoryVenue());

		MainVenue.closeAll(List.of(server));

		assertNull(server.getHost(), "the venue's host should be closed");
		assertNull(server.getStore(), "the venue's store should be closed");
	}

	@Test
	@Timeout(60)
	public void stoppingWaitsForACloseAlreadyUnderWay() throws Exception {
		CountDownLatch closing = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		VenueServer server = VenueServer.launch(memoryVenue(), new MemoryStore() {
			@Override
			public void close() {
				closing.countDown();
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				super.close();
			}
		});

		Thread first = Thread.ofPlatform().start(server::close);
		assertTrue(closing.await(30, TimeUnit.SECONDS), "the first close should reach the store");

		Thread stopping = Thread.ofPlatform().start(() -> MainVenue.closeAll(List.of(server)));
		try {
			// A stop that returned early ends here instead of parking on the close.
			while (stopping.isAlive() && stopping.getState() != Thread.State.WAITING) stopping.join(1);
			assertTrue(stopping.isAlive(), "stopping should wait for the close under way");
		} finally {
			release.countDown();
		}
		first.join();
		stopping.join();
	}
}
