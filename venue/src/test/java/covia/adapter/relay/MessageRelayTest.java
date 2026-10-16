package covia.adapter.relay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.venue.AdapterWorkspace;
import covia.venue.Engine;

/**
 * The relay core: links are the only durable state, at canonical paths in the
 * adapter's venue-owned workspace, and survive a new relay over the same workspace;
 * messages wait in memory for an open principal and are refused for a closed one
 * unless the hold allows; a reply belongs to the message's principal, once, inside
 * the window; codes are one-shot.
 */
class MessageRelayTest {

	private static final AString ALICE = Strings.create("did:test:relay:alice");
	private static final AString BOB = Strings.create("did:test:relay:bob");

	@Test void linksAreTheOnlyStateAndLiveAtCanonicalVenuePaths() {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			AdapterWorkspace ws = engine.adapterWorkspace("relay-test");
			MessageRelay relay = new MessageRelay(ws, "config/main", 60);
			relay.link("447700900001", ALICE);
			assertEquals(ALICE, relay.principalFor("447700900001"));
			assertEquals("447700900001", relay.senderFor(ALICE));

			String linkPath = "config/main/links/" + Strings.create("447700900001").getHash().toHexString();
			AMap<AString, ACell> record = RT.ensureMap(ws.read(linkPath));
			assertNotNull(record, "the link is at its canonical path");
			assertEquals(ALICE, record.get(Strings.create("principal")));
			ACell venueOwned = engine.resolvePath(Strings.create("w/adapters/relay-test/" + linkPath), engine.venueContext());
			assertEquals(record, venueOwned, "in the venue's own workspace");

			relay.poll(ALICE, 0);   // open, so the message is queued in memory
			assertEquals(MessageRelay.Offer.DELIVERED,
				relay.offer(ALICE, "447700900001", new MessageRelay.Inbound("m1", "secret health text", now()), null));
			assertFalse(String.valueOf(engine.resolvePath(Strings.create("w/adapters/relay-test"), engine.venueContext()))
				.contains("secret health text"), "no message reaches the store");

			MessageRelay restarted = new MessageRelay(ws, "config/main", 60);
			assertEquals(ALICE, restarted.principalFor("447700900001"), "links survive a restart");

			restarted.link("447700900001", BOB);
			assertNull(restarted.senderFor(ALICE), "relinking a sender releases its old principal");
			assertEquals(BOB, restarted.unlinkSender("447700900001"));
			assertNull(restarted.principalFor("447700900001"));
			assertNull(restarted.senderFor(BOB));

			restarted.link("447700900002", ALICE);
			restarted.deleteAll();
			assertNull(ws.read("config/main"), "deleting the relay removes its links");
		} finally {
			engine.close();
		}
	}

	@Test void aClosedPrincipalIsRefused_anOpenOneReceives_andALongHoldWaits() throws Exception {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			AdapterWorkspace ws = engine.adapterWorkspace("relay-test");
			MessageRelay shortHold = new MessageRelay(ws, "config/short", 60);
			assertEquals(MessageRelay.Offer.NOT_OPEN, shortHold.offer(ALICE, "s", new MessageRelay.Inbound("m1", "hi", now()), null));

			CompletableFuture<List<MessageRelay.Inbound>> waiting = shortHold.poll(ALICE, 5_000);
			assertFalse(waiting.isDone());
			assertEquals(MessageRelay.Offer.DELIVERED, shortHold.offer(ALICE, "s", new MessageRelay.Inbound("m2", "hello", now()), null));
			assertEquals("m2", waiting.get(2, TimeUnit.SECONDS).getFirst().id(), "a waiting poll is answered at once");
			assertEquals(MessageRelay.Offer.DELIVERED, shortHold.offer(ALICE, "s", new MessageRelay.Inbound("m3", "again", now()), null),
				"just polled, still open");
			assertEquals("m3", shortHold.poll(ALICE, 0).get().getFirst().id());

			MessageRelay longHold = new MessageRelay(ws, "config/long", 3_600);
			assertEquals(MessageRelay.Offer.HELD, longHold.offer(BOB, "s", new MessageRelay.Inbound("m4", "later", now()), null));
			assertEquals("m4", longHold.poll(BOB, 0).get().getFirst().id(), "held until collected");
		} finally {
			engine.close();
		}
	}

	@Test void aReplyBelongsToItsPrincipal_onceAndInsideTheWindow() {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			AtomicLong clock = new AtomicLong(1_800_000_000_000L);
			long window = 60 * 60 * 1000L;
			MessageRelay relay = new MessageRelay(engine.adapterWorkspace("relay-test"), "config/main", 60, window, clock::get);
			relay.poll(ALICE, 0);
			long sent = clock.get() / 1000;
			AMap<AString, ACell> address = Maps.of("channel", "C123", "thread", "1700000000.000100");
			relay.offer(ALICE, "U123", new MessageRelay.Inbound("m1", "hi", sent), address);
			MessageRelay.Target t = relay.target("m1");
			assertEquals(ALICE, t.principal());
			assertEquals("U123", t.sender());
			assertEquals(address, t.address(), "the provider's reply address comes back as it was given");
			assertTrue(relay.claimReply("m1"));
			assertFalse(relay.claimReply("m1"), "one answer per message");
			relay.releaseReply("m1");
			assertTrue(relay.claimReply("m1"), "a failed send may be retried");
			clock.addAndGet(window);
			assertNull(relay.target("m1"), "past the provider's window, nothing can be answered");
			assertNull(relay.target("never"));
		} finally {
			engine.close();
		}
	}

	@Test void codesAreOneShot_andSightingsDeduplicate() {
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			MessageRelay relay = new MessageRelay(engine.adapterWorkspace("relay-test"), "config/main", 60);
			String code = relay.newCode(ALICE);
			assertEquals(ALICE, relay.redeem(code.toLowerCase()), "codes are case-insensitive");
			assertNull(relay.redeem(code), "and spent once");
			assertTrue(relay.firstSighting("wamid.1"));
			assertFalse(relay.firstSighting("wamid.1"));
		} finally {
			engine.close();
		}
	}

	private static long now() {
		return System.currentTimeMillis() / 1000;
	}
}
