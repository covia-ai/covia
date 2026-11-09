package covia.adapter.relay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.venue.AdapterState;
import covia.venue.Engine;
import covia.venue.TestEngine;

/**
 * The relay core: links are the only durable state, at canonical paths in the
 * adapter's venue-owned workspace, and survive a new relay over the same workspace;
 * messages wait in memory for an open principal and are refused for a closed one
 * unless the hold allows; a reply belongs to the message's principal, once, inside
 * the window; codes are one-shot.
 */
class MessageRelayTest {

	/**
	 * One venue for the class, not the shared one: adapter state under a name no
	 * registered adapter has would leave every later account deletion there pending.
	 */
	private static Engine engine;

	private AString alice;
	private AString bob;
	/** This test's own adapter state on the venue. */
	private String relayState;

	@BeforeAll static void start() { engine = Engine.createTemp(Maps.empty()); }
	@AfterAll static void stop() { engine.close(); }

	@BeforeEach void identities(TestInfo info) {
		alice = TestEngine.uniqueDID(info);
		bob = Strings.create(alice + "-bob");
		// Adapter names are lower-case, so not the DID as it stands
		relayState = alice.toString().toLowerCase().replaceAll("[^a-z0-9-]", "-");
	}

	@Test void linksAreTheOnlyStateAndLiveAtCanonicalVenuePaths() {
		AdapterState ws = engine.adapterState(relayState);
		MessageRelay relay = new MessageRelay(ws, "config/main", 60);
		relay.link("447700900001", alice);
		assertEquals(alice, relay.principalFor("447700900001"));
		assertEquals("447700900001", relay.senderFor(alice));

		String linkPath = "config/main/links/" + Strings.create("447700900001").getHash().toHexString();
		AMap<AString, ACell> record = RT.ensureMap(ws.read(linkPath));
		assertNotNull(record, "the link is at its canonical path");
		assertEquals(alice, record.get(Strings.create("principal")));
		ACell venueOwned = engine.adapterState(relayState).read(linkPath);
		assertEquals(record, venueOwned, "in the adapter component");

		relay.poll(alice, 0);   // open, so the message is queued in memory
		assertEquals(MessageRelay.Offer.DELIVERED,
			relay.offer(alice, "447700900001", new MessageRelay.Inbound("m1", "secret health text", now()), null));
		assertFalse(String.valueOf(engine.adapterState(relayState).get())
			.contains("secret health text"), "no message reaches the store");

		MessageRelay restarted = new MessageRelay(ws, "config/main", 60);
		assertEquals(alice, restarted.principalFor("447700900001"), "links survive a restart");

		restarted.link("447700900001", bob);
		assertNull(restarted.senderFor(alice), "relinking a sender releases its old principal");
		assertEquals(bob, restarted.unlinkSender("447700900001"));
		assertNull(restarted.principalFor("447700900001"));
		assertNull(restarted.senderFor(bob));

		restarted.link("447700900002", alice);
		restarted.deleteAll();
		assertNull(ws.read("config/main"), "deleting the relay removes its links");
	}

	@Test void aClosedPrincipalIsRefused_anOpenOneReceives_andALongHoldWaits() throws Exception {
		AdapterState ws = engine.adapterState(relayState);
		MessageRelay shortHold = new MessageRelay(ws, "config/short", 60);
		assertEquals(MessageRelay.Offer.NOT_OPEN, shortHold.offer(alice, "s", new MessageRelay.Inbound("m1", "hi", now()), null));

		CompletableFuture<List<MessageRelay.Inbound>> waiting = shortHold.poll(alice, 5_000);
		assertFalse(waiting.isDone());
		assertEquals(MessageRelay.Offer.DELIVERED, shortHold.offer(alice, "s", new MessageRelay.Inbound("m2", "hello", now()), null));
		assertEquals("m2", waiting.get(2, TimeUnit.SECONDS).getFirst().id(), "a waiting poll is answered at once");
		assertEquals(MessageRelay.Offer.DELIVERED, shortHold.offer(alice, "s", new MessageRelay.Inbound("m3", "again", now()), null),
			"just polled, still open");
		assertEquals("m3", shortHold.poll(alice, 0).get().getFirst().id());

		MessageRelay longHold = new MessageRelay(ws, "config/long", 3_600);
		assertEquals(MessageRelay.Offer.HELD, longHold.offer(bob, "s", new MessageRelay.Inbound("m4", "later", now()), null));
		assertEquals("m4", longHold.poll(bob, 0).get().getFirst().id(), "held until collected");
	}

	@Test void aReplyBelongsToItsPrincipal_onceAndInsideTheWindow() {
		AtomicLong clock = new AtomicLong(1_800_000_000_000L);
		long window = 60 * 60 * 1000L;
		MessageRelay relay = new MessageRelay(engine.adapterState(relayState), "config/main", 60, window, clock::get);
		relay.poll(alice, 0);
		long sent = clock.get() / 1000;
		AMap<AString, ACell> address = Maps.of("channel", "C123", "thread", "1700000000.000100");
		relay.offer(alice, "U123", new MessageRelay.Inbound("m1", "hi", sent), address);
		MessageRelay.Target t = relay.target("m1");
		assertEquals(alice, t.principal());
		assertEquals("U123", t.sender());
		assertEquals(address, t.address(), "the provider's reply address comes back as it was given");
		assertTrue(relay.claimReply("m1"));
		assertFalse(relay.claimReply("m1"), "one answer per message");
		relay.releaseReply("m1");
		assertTrue(relay.claimReply("m1"), "a failed send may be retried");
		clock.addAndGet(window);
		assertNull(relay.target("m1"), "past the provider's window, nothing can be answered");
		assertNull(relay.target("never"));
	}

	@Test void codesAreOneShot_andSightingsDeduplicate() {
		MessageRelay relay = new MessageRelay(engine.adapterState(relayState), "config/main", 60);
		String code = relay.newCode(alice);
		assertEquals(alice, relay.redeem(code.toLowerCase()), "codes are case-insensitive");
		assertNull(relay.redeem(code), "and spent once");
		assertTrue(relay.firstSighting("wamid.1"));
		assertFalse(relay.firstSighting("wamid.1"));
	}

	private static long now() {
		return System.currentTimeMillis() / 1000;
	}
}
