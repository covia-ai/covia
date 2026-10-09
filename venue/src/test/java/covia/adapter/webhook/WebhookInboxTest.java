package covia.adapter.webhook;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.venue.Config;
import covia.venue.CoviaApplication;
import covia.venue.Engine;
import covia.venue.server.VenueServer;

class WebhookInboxTest {
	@TempDir Path temp;

	@Test void deduplicatesConcurrentDeliveriesAndClaimsAcrossHandles() throws Exception {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			var inbox = new WebhookInbox(engine, engine.adapterWorkspace("webhook-test"), "config/bot/inbox");
			var otherHandle = new WebhookInbox(engine, engine.adapterWorkspace("webhook-test"), "config/bot/inbox");
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
			assertNull(engine.adapterWorkspace("webhook-test").read("event-1"));
			assertNull(new WebhookInbox(engine, engine.adapterWorkspace("webhook-test"), "config/other/inbox").get("../../event-1"));
			inbox.accept("keep-pending", Maps.empty());
			inbox.accept("keep-uncertain", Maps.empty());
			inbox.claim("keep-uncertain");
			assertEquals(1, inbox.pruneCompleted(Long.MAX_VALUE));
			assertNotNull(inbox.get("keep-pending"));
			assertNotNull(inbox.get("keep-uncertain"));
			assertEquals(WebhookInbox.NOT_STARTED, assertThrows(IllegalStateException.class, () -> inbox.complete("keep-pending")).getMessage());
		} finally { engine.close(); }
	}

	@Test void retriesDurabilityBarrierEvenForDuplicateReceipts() throws Exception {
		FlushEngine engine = new FlushEngine(AKeyPair.generate());
		engine.start();
		try {
			WebhookInbox inbox = new WebhookInbox(engine, engine.adapterWorkspace("webhook-test"), "inbox");
			engine.failFlush = true;
			assertThrows(IllegalStateException.class, () -> inbox.accept("event", Maps.empty()));
			assertNotNull(inbox.get("event"));
			engine.failFlush = false;
			int before = engine.flushes;
			inbox.accept("event", Maps.empty());
			assertEquals(before + 1, engine.flushes);
			engine.failFlush = true;
			assertThrows(IllegalStateException.class, () -> inbox.claim("event"));
			engine.failFlush = false;
			assertFalse(inbox.claim("event"), "uncertain work is never automatically replayed");
		} finally { engine.failFlush = false; engine.close(); }
	}

	@Test void malformedReceiptCannotBeAcknowledgedOrOverwrittenAsANewEvent() {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			var state = engine.adapterWorkspace("webhook-test");
			var inbox = new WebhookInbox(engine, state, "inbox");
			String path = "inbox/" + Strings.create("event").getHash().toHexString();
			state.write(path, Maps.empty());
			assertEquals(WebhookInbox.INVALID_RECEIPT,
				assertThrows(IllegalStateException.class, () -> inbox.accept("event", Maps.empty())).getMessage());
			assertEquals(Maps.empty(), state.read(path));
		} finally { engine.close(); }
	}

	@Test void receiptsSurviveVenueRestartAndKeepUncertainWorkSeparate() {
		AMap<AString, ACell> config = Maps.of(Config.PORT, 0, Config.BIND_ADDRESS, "127.0.0.1",
			Config.STORE, temp.resolve("webhooks.etch").toString(),
			Config.SEED, AKeyPair.createSeeded(1928).getSeed().toHexString());
		VenueServer first = VenueServer.launch(config);
		try {
			Engine engine = first.getEngine();
			var inbox = new WebhookInbox(engine, engine.adapterWorkspace("webhook-test"), "config/bot/inbox");
			inbox.accept("pending", Maps.of("text", "one"));
			assertEquals(WebhookInbox.PENDING, inbox.get("pending").get(Fields.STATUS), () -> inbox.get("pending").toString());
			inbox.accept("uncertain", Maps.of("text", "two"));
			inbox.claim("uncertain");
			inbox.accept("done", Maps.empty());
			inbox.claim("done");
			inbox.complete("done");
		} finally { first.close(); }
		VenueServer second = VenueServer.launch(config);
		try {
			Engine engine = second.getEngine();
			var inbox = new WebhookInbox(engine, engine.adapterWorkspace("webhook-test"), "config/bot/inbox");
			assertEquals(Strings.create("pending"), inbox.records(WebhookInbox.PENDING).getFirst().get(Fields.ID));
			assertEquals(Strings.create("uncertain"), inbox.records(WebhookInbox.STARTED).getFirst().get(Fields.ID));
			assertFalse(inbox.claim("uncertain"));
			assertFalse(inbox.claim("done"));
			assertTrue(inbox.claim("pending"));
		} finally { second.close(); }
	}

	private static final class FlushEngine extends Engine {
		boolean failFlush;
		int flushes;
		FlushEngine(AKeyPair key) throws IOException { super(Maps.empty(), CoviaApplication.create(key), key); }
		@Override public void flush() {
			flushes++;
			if (failFlush) throw new IllegalStateException("test durability barrier failure");
			super.flush();
		}
	}
}
