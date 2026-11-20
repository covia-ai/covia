package covia.adapter.webhook;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.crypto.AKeyPair;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.store.MemoryStore;
import convex.lattice.LatticeHost;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.TestEngine;

class WebhookInboxTest {

	/**
	 * One venue for the class, not the shared one: adapter state under a name no
	 * registered adapter has would leave every later account deletion there pending.
	 */
	private static Engine venue;

	@BeforeAll static void start() { venue = Engine.createTemp(Maps.empty()); }
	@AfterAll static void stop() { venue.close(); }

	/** A name for this test's own adapter state: adapter names are lower-case, so not a DID as it stands. */
	private static String stateName(TestInfo info) {
		return TestEngine.uniqueDID(info).toString().toLowerCase().replaceAll("[^a-z0-9-]", "-");
	}

	@Test void deduplicatesConcurrentDeliveriesAndClaimsAcrossHandles(TestInfo info) throws Exception {
		Engine engine = venue;
		String name = stateName(info);
		var inbox = new WebhookInbox(engine, engine.adapterState(name), "config/bot/inbox");
		var otherHandle = new WebhookInbox(engine, engine.adapterState(name), "config/bot/inbox");
		var tasks = new ArrayList<CompletableFuture<Boolean>>();
		for (int i = 0; i < 24; i++) {
			WebhookInbox handle = i % 2 == 0 ? inbox : otherHandle;
			tasks.add(CompletableFuture.supplyAsync(() -> {
				handle.accept("../../event-1", Maps.of("text", "hello"));
				return handle.claim("../../event-1");
			}, AAdapter.VIRTUAL_EXECUTOR));
		}
		CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(10, java.util.concurrent.TimeUnit.SECONDS);
		assertEquals(1, tasks.stream().filter(CompletableFuture::join).count());
		assertTrue(inbox.records(WebhookInbox.PENDING).isEmpty());
		assertEquals(1, inbox.records(WebhookInbox.STARTED).size());
		otherHandle.complete("../../event-1");
		assertEquals(WebhookInbox.COMPLETE, inbox.accept("../../event-1", Maps.of("text", "duplicate")).get(Fields.STATUS));
		assertEquals(Maps.of("text", "hello"), inbox.get("../../event-1").get(Fields.INPUT));
		assertFalse(inbox.claim("../../event-1"));
		assertNull(engine.adapterState(name).read("event-1"));
		assertNull(new WebhookInbox(engine, engine.adapterState(name), "config/other/inbox").get("../../event-1"));
		inbox.accept("keep-pending", Maps.empty());
		inbox.accept("keep-uncertain", Maps.empty());
		inbox.claim("keep-uncertain");
		assertEquals(1, inbox.pruneCompleted(Long.MAX_VALUE));
		assertNotNull(inbox.get("keep-pending"));
		assertNotNull(inbox.get("keep-uncertain"));
		assertEquals(WebhookInbox.NOT_STARTED, assertThrows(IllegalStateException.class, () -> inbox.complete("keep-pending")).getMessage());
	}

	@Test void retriesDurabilityBarrierEvenForDuplicateReceipts() throws Exception {
		FlushStore store = new FlushStore();
		Engine engine = TestEngine.application(LatticeHost.create(Index.none(), store))
			.mount(AKeyPair.generate(), Maps.empty());
		engine.start();
		try {
			WebhookInbox inbox = new WebhookInbox(engine, engine.adapterState("webhook-test"), "inbox");
			store.failFlush = true;
			assertThrows(IllegalStateException.class, () -> inbox.accept("event", Maps.empty()));
			assertNotNull(inbox.get("event"));
			store.failFlush = false;
			int before = store.flushes.get();
			inbox.accept("event", Maps.empty());
			assertEquals(before + 1, store.flushes.get());
			store.failFlush = true;
			assertThrows(IllegalStateException.class, () -> inbox.claim("event"));
			store.failFlush = false;
			assertFalse(inbox.claim("event"), "uncertain work is never automatically replayed");
		} finally { store.failFlush = false; engine.close(); }
	}

	@Test void malformedReceiptCannotBeAcknowledgedOrOverwrittenAsANewEvent(TestInfo info) {
		Engine engine = venue;
		var state = engine.adapterState(stateName(info));
		var inbox = new WebhookInbox(engine, state, "inbox");
		String path = "inbox/" + Strings.create("event").getHash().toHexString();
		state.write(path, Maps.empty());
		assertEquals(WebhookInbox.INVALID_RECEIPT,
			assertThrows(IllegalStateException.class, () -> inbox.accept("event", Maps.empty())).getMessage());
		assertEquals(Maps.empty(), state.read(path));
	}

	/** A store whose durability barrier counts its calls and fails on request. */
	private static final class FlushStore extends MemoryStore {
		volatile boolean failFlush;
		final AtomicInteger flushes = new AtomicInteger();
		@Override public void flush() {
			flushes.incrementAndGet();
			if (failFlush) throw new IllegalStateException("test durability barrier failure");
			super.flush();
		}
	}
}
