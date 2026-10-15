package covia.adapter.messaging;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import covia.adapter.webhook.WebhookInbox;
import covia.api.Fields;
import covia.venue.Engine;

class DeliveryRetryTest {
	private static final String FAILURE = "injected delivery failure";
	private static final DeliveryRetry.Guard OPEN = write -> write.get();

	private static AMap<AString, ACell> tried(long attempts, long first, long last) {
		return Maps.of(WebhookInbox.ATTEMPTS, CVMLong.create(attempts),
			WebhookInbox.FIRST_ATTEMPT_AT, CVMLong.create(first), WebhookInbox.ATTEMPTED_AT, CVMLong.create(last));
	}

	@Test void attemptsBackOffFromTheFirstAttemptAndStopAtTheWindow() {
		var retry = new DeliveryRetry(10_000, 1_000);
		assertEquals(0, retry.due(Maps.empty(), 5_000), "a receipt never attempted is due now");
		assertEquals(900, retry.due(tried(1, 0, 0), 100));
		assertEquals(0, retry.due(tried(1, 0, 0), 1_000));
		assertEquals(2_000, retry.due(tried(2, 0, 1_000), 1_000), "the backoff doubles");
		assertEquals(4_000, retry.due(tried(3, 0, 3_000), 3_000));
		assertEquals(-1, retry.due(tried(4, 0, 7_000), 7_000), "the next retry would start outside the window");
		assertEquals(-1, retry.due(tried(1, 0, 0), 10_000), "the window runs from the first attempt");
		// The window is measured from the persisted first attempt, whoever reads it.
		assertEquals(-1, new DeliveryRetry(10_000, 1_000).due(tried(1, 0, 0), 60_000));
	}

	@Test void failingDeliveryRetriesOnlyWithinTheWindowThenFailsTerminally() throws Exception {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			var inbox = new WebhookInbox(engine, engine.adapterWorkspace("delivery-test"), "inbox");
			inbox.accept("1", Maps.of("text", "hello"));
			var retry = new DeliveryRetry(300, 20);
			var calls = new AtomicInteger();
			DeliveryRetry.Target failing = target(null, () -> {
				calls.incrementAndGet();
				throw new IllegalStateException(FAILURE);
			});
			DeliveryRetry.Result result = retry.attempt(inbox, "1", OPEN, failing);
			while (result.outcome() == DeliveryRetry.Outcome.WAITING) {
				assertEquals(FAILURE, result.error());
				Thread.sleep(result.waitMillis());
				result = retry.attempt(inbox, "1", OPEN, failing);
			}
			assertEquals(DeliveryRetry.Outcome.FAILED, result.outcome());
			assertEquals(DeliveryRetry.WINDOW_EXHAUSTED, result.reason());
			assertEquals(FAILURE, result.error());
			AMap<AString, ACell> failed = inbox.get("1");
			assertEquals(WebhookInbox.FAILED, failed.get(Fields.STATUS));
			long attempts = ((CVMLong) failed.get(WebhookInbox.ATTEMPTS)).longValue();
			assertEquals(calls.get(), attempts);
			// Attempts start at 0, 20, 60 and 140 ms; the next (300 ms) is outside the window.
			assertTrue(attempts >= 2 && attempts <= 4, "attempts: " + attempts);

			// FAILED is terminal: never attempted, claimed or pruned, and duplicates see it.
			assertEquals(DeliveryRetry.Outcome.SKIPPED, retry.attempt(inbox, "1", OPEN, failing).outcome());
			assertEquals(attempts, calls.get());
			assertFalse(inbox.claim("1"));
			assertEquals(WebhookInbox.FAILED, inbox.accept("1", Maps.of("text", "again")).get(Fields.STATUS));
			assertNull(inbox.fail("1", DeliveryRetry.UNDELIVERABLE, null, 0));
			assertEquals(0, inbox.pruneCompleted(Long.MAX_VALUE));
			assertEquals(1, inbox.records(WebhookInbox.FAILED).size());
			assertTrue(inbox.records(WebhookInbox.PENDING).isEmpty());
		} finally { engine.close(); }
	}

	@Test void transientFailureDeliversOnceWithinTheWindow() throws Exception {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			var inbox = new WebhookInbox(engine, engine.adapterWorkspace("delivery-test"), "inbox");
			inbox.accept("1", Maps.of("text", "hello"));
			var retry = new DeliveryRetry(10_000, 10);
			var calls = new AtomicInteger();
			DeliveryRetry.Target flaky = target(null, () -> {
				if (calls.incrementAndGet() <= 2) throw new IllegalStateException(FAILURE);
				assertTrue(inbox.claim("1"));
				inbox.complete("1");
			});
			DeliveryRetry.Result result = retry.attempt(inbox, "1", OPEN, flaky);
			while (result.outcome() == DeliveryRetry.Outcome.WAITING) {
				Thread.sleep(result.waitMillis());
				result = retry.attempt(inbox, "1", OPEN, flaky);
			}
			assertEquals(DeliveryRetry.Outcome.DELIVERED, result.outcome());
			assertEquals(3, calls.get());
			assertEquals(WebhookInbox.COMPLETE, inbox.get("1").get(Fields.STATUS));
			assertEquals(CVMLong.create(3), inbox.get("1").get(WebhookInbox.ATTEMPTS));
			assertEquals(DeliveryRetry.Outcome.SKIPPED, retry.attempt(inbox, "1", OPEN, flaky).outcome());
			assertEquals(3, calls.get());
		} finally { engine.close(); }
	}

	@Test void undeliverableMessagesFailOnceWithoutRetry() throws Exception {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			var inbox = new WebhookInbox(engine, engine.adapterWorkspace("delivery-test"), "inbox");
			var retry = new DeliveryRetry(60_000, 10);
			var calls = new AtomicInteger();

			inbox.accept("refused", Maps.of("text", "hello"));
			var refused = retry.attempt(inbox, "refused", OPEN, target("recipient is suspended", calls::incrementAndGet));
			assertEquals(DeliveryRetry.Outcome.FAILED, refused.outcome());
			assertEquals(DeliveryRetry.UNDELIVERABLE, refused.reason());
			assertEquals("recipient is suspended", refused.error());
			assertEquals(0, calls.get(), "a refused message is never handed over");

			inbox.accept("invalid", Maps.of("text", "hello"));
			var invalid = retry.attempt(inbox, "invalid", OPEN, target(null, () -> {
				calls.incrementAndGet();
				throw new IllegalArgumentException(FAILURE);
			}));
			assertEquals(DeliveryRetry.Outcome.FAILED, invalid.outcome());
			assertEquals(DeliveryRetry.UNDELIVERABLE, invalid.reason());
			assertEquals(CVMLong.ONE, inbox.get("invalid").get(WebhookInbox.ATTEMPTS));

			// A refusal observed after a failed attempt also ends it, e.g. an agent suspended mid-attempt.
			inbox.accept("suspended-later", Maps.of("text", "hello"));
			var refusal = new String[1];
			var late = retry.attempt(inbox, "suspended-later", OPEN, new DeliveryRetry.Target() {
				@Override public String refusal() { return refusal[0]; }
				@Override public void deliver() { refusal[0] = "recipient is suspended"; throw new IllegalStateException(FAILURE); }
			});
			assertEquals(DeliveryRetry.Outcome.FAILED, late.outcome());
			assertEquals("recipient is suspended", late.error());
		} finally { engine.close(); }
	}

	@Test void committedDeliveryIsNotRetriedWhenItsCallFailsAfterwards() throws Exception {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			var inbox = new WebhookInbox(engine, engine.adapterWorkspace("delivery-test"), "inbox");
			inbox.accept("1", Maps.of("text", "hello"));
			var result = new DeliveryRetry(60_000, 10).attempt(inbox, "1", OPEN, target(null, () -> {
				inbox.claim("1");
				inbox.complete("1");
				throw new IllegalStateException("durability barrier failed");
			}));
			assertEquals(DeliveryRetry.Outcome.DELIVERED, result.outcome());
			assertNull(inbox.get("1").get(WebhookInbox.LAST_ERROR));
		} finally { engine.close(); }
	}

	@Test void stoppedBindingWritesNothing() throws Exception {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			var inbox = new WebhookInbox(engine, engine.adapterWorkspace("delivery-test"), "inbox");
			inbox.accept("1", Maps.of("text", "hello"));
			var calls = new AtomicInteger();
			var result = new DeliveryRetry(60_000, 10).attempt(inbox, "1", write -> null, target(null, calls::incrementAndGet));
			assertEquals(DeliveryRetry.Outcome.SKIPPED, result.outcome());
			assertEquals(0, calls.get());
			assertNull(inbox.get("1").get(WebhookInbox.ATTEMPTS));
		} finally { engine.close(); }
	}

	private static DeliveryRetry.Target target(String refusal, Runnable deliver) {
		return new DeliveryRetry.Target() {
			@Override public String refusal() { return refusal; }
			@Override public void deliver() { deliver.run(); }
		};
	}
}
