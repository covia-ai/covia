package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.p2p.P2PLattice;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * The venue's P2P node (covia#587). Off by default on the shared engine; two
 * joined venues on loopback for the network behaviour, since that is what is
 * under test.
 */
public class P2PAdapterTest {

	private static final AString PEERS = Strings.create("v/ops/p2p/peers");
	private static final AString CONNECT = Strings.create("v/ops/p2p/connect");
	private static final long WAIT_MS = 10_000;

	/** A venue that joins on loopback and polls fast, so changes show promptly; it listens only if asked. */
	private static Engine joined(String name, boolean listens) {
		AMap<AString, ACell> p2p = Maps.of("join", true, "pollMs", 50);
		if (listens) p2p = p2p.assoc(Strings.create("port"), RT.cvm(0))
			.assoc(Strings.create("url"), Strings.create("tcp://localhost:0"))
			.assoc(Strings.create("allowPrivateURL"), CVMBool.TRUE);
		Engine engine = Engine.createTemp(Maps.of(Config.NAME, name, Config.ADAPTERS, Maps.of("p2p", p2p)));
		Engine.addDemoAssets(engine);
		return engine;
	}

	private static ACell peers(Engine engine) throws Exception {
		return engine.jobs().runOperation(PEERS, Maps.empty(), engine.venueContext()).get(WAIT_MS, TimeUnit.MILLISECONDS);
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> entry(Engine engine, AString did) {
		try {
			AVector<ACell> list = RT.ensureVector(RT.getIn(peers(engine), P2PAdapter.K_PEERS));
			for (long i = 0; i < list.count(); i++) {
				AMap<AString, ACell> peer = (AMap<AString, ACell>) list.get(i);
				if (did.equals(peer.get(Fields.DID))) return peer;
			}
			return null;
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private static boolean connected(Engine engine, AString did) {
		AMap<AString, ACell> peer = entry(engine, did);
		return peer != null && CVMBool.TRUE.equals(peer.get(P2PAdapter.K_CONNECTED));
	}

	private static P2PAdapter.PeerEvent await(BlockingQueue<P2PAdapter.PeerEvent> events,
			Predicate<P2PAdapter.PeerEvent> wanted, String what) throws InterruptedException {
		long deadline = System.currentTimeMillis() + WAIT_MS;
		long left;
		while ((left = deadline - System.currentTimeMillis()) > 0) {
			P2PAdapter.PeerEvent event = events.poll(left, TimeUnit.MILLISECONDS);
			if (event != null && wanted.test(event)) return event;
		}
		throw new AssertionError("No event: " + what);
	}

	@Test
	public void testOffByDefault() throws Exception {
		ACell result = peers(TestEngine.ENGINE);
		assertEquals(CVMBool.FALSE, RT.getIn(result, P2PAdapter.K_JOINED));
		assertEquals(0, RT.ensureVector(RT.getIn(result, P2PAdapter.K_PEERS)).count());

		ExecutionException refused = assertThrows(ExecutionException.class, () -> TestEngine.ENGINE.jobs()
			.invokeInternal(CONNECT, Maps.of("did", UCAN.toDIDKey(AKeyPair.generate().getAccountKey()),
				"address", "localhost:18888"), TestEngine.ENGINE.venueContext())
			.get(WAIT_MS, TimeUnit.MILLISECONDS));
		assertEquals(P2PAdapter.NOT_JOINED, refused.getCause().getMessage());
	}

	@Test
	public void testRequiresVenueAuthority() throws Exception {
		Engine engine = TestEngine.ENGINE;
		AString user = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		engine.getVenueState().users().create(user);
		AString resource = Strings.create(engine.getDIDString() + "/" + P2PAdapter.RESOURCE);

		ExecutionException read = assertThrows(ExecutionException.class, () -> engine.jobs()
			.invokeInternal(PEERS, Maps.empty(), RequestContext.of(user)).get(WAIT_MS, TimeUnit.MILLISECONDS));
		assertInstanceOf(AuthException.class, read.getCause());
		assertTrue(read.getCause().getMessage().contains(Abilities.P2P_READ.toString()));
		assertTrue(read.getCause().getMessage().contains(resource.toString()));

		ExecutionException connect = assertThrows(ExecutionException.class, () -> engine.jobs()
			.invokeInternal(CONNECT, Maps.of("did", user, "address", "localhost:18888"), RequestContext.of(user))
			.get(WAIT_MS, TimeUnit.MILLISECONDS));
		assertInstanceOf(AuthException.class, connect.getCause());
		assertTrue(connect.getCause().getMessage().contains(Abilities.P2P_MANAGE.toString()));
	}

	@Test
	public void testConfiguration() {
		P2PAdapter.Settings off = P2PAdapter.parse(null, true);
		assertFalse(off.join());
		assertFalse(off.listens(), "no port means no listener");
		assertEquals(-1, off.node().getPort());

		P2PAdapter.Settings on = P2PAdapter.parse(Maps.of("join", true, "port", 0,
			"url", "tcp://localhost:0", "allowPrivateURL", true, "pollMs", 20,
			"peers", Vectors.of(Maps.of("did",
				UCAN.toDIDKey(AKeyPair.generate().getAccountKey()), "address", "tcp://peer.example:18888"))), true);
		assertTrue(on.join());
		assertTrue(on.listens());
		assertEquals(0, on.node().getPort());
		assertEquals(20, on.pollMs());
		assertEquals("peer.example", on.peers().get(0).host());
		assertEquals(18888, on.peers().get(0).port());

		assertThrows(IllegalArgumentException.class, () -> P2PAdapter.parse(Maps.of("listen", true), true),
			"strict mode rejects unknown settings");
		P2PAdapter.parse(Maps.of("listen", true), false);
		assertThrows(IllegalArgumentException.class, () -> P2PAdapter.parse(Maps.of("join", "yes"), false));
		assertThrows(IllegalArgumentException.class, () -> P2PAdapter.parse(Maps.of("port", 70000), false));
		assertThrows(IllegalArgumentException.class, () -> P2PAdapter.parse(Maps.of("url", "tcp://peer.example:1"), false),
			"a URL advertises a listener, so it needs a port");
		assertThrows(IllegalArgumentException.class, () -> P2PAdapter.parse(Maps.of("port", 0, "url", "tcp://localhost:0"), false),
			"loopback URLs need allowPrivateURL");
		assertThrows(IllegalArgumentException.class, () -> P2PAdapter.parse(Maps.of("pollMs", 1), false));
		assertThrows(IllegalArgumentException.class, () -> P2PAdapter.parse(Maps.of("peers", Vectors.of(
			Maps.of("did", "did:web:example.com", "address", "peer.example:1"))), false));
		assertThrows(IllegalArgumentException.class, () -> P2PAdapter.parse(Maps.of("peers", Vectors.of(
			Maps.of("did", UCAN.toDIDKey(AKeyPair.generate().getAccountKey()), "address", "peer.example"))), false));
	}

	/**
	 * Joining and leaving by reconfiguring the live adapter, as an embedder's
	 * settings screen does. Its own engine, so the venue's job count is this
	 * test's alone: listing peers leaves no job record. The timer is a minute,
	 * so a prompt event for a merged record shows the lattice change woke it.
	 */
	@Test
	public void testJoinAndLeaveAtRuntime() throws Exception {
		Engine engine = Engine.createTemp(null);
		try {
			Engine.addDemoAssets(engine);
			RequestContext venue = engine.venueContext();
			long jobs = engine.jobs().getJobs(venue).count();
			assertEquals(CVMBool.FALSE, RT.getIn(peers(engine), P2PAdapter.K_JOINED));

			engine.configureAdapter("p2p", Maps.of("join", true, "pollMs", 60_000));
			ACell joined = peers(engine);
			assertEquals(jobs, engine.jobs().getJobs(venue).count(), "p2p/peers is read-only");
			assertEquals(CVMBool.TRUE, RT.getIn(joined, P2PAdapter.K_JOINED));
			assertEquals(UCAN.toDIDKey(engine.getAccountKey()), RT.getIn(joined, P2PAdapter.K_SELF, Fields.DID));
			assertEquals(0, RT.ensureVector(RT.getIn(joined, P2PAdapter.K_SELF, P2PAdapter.K_TRANSPORTS)).count(),
				"without a port the node only dials out");

			P2PAdapter adapter = engine.findAdapter(P2PAdapter.class);
			BlockingQueue<P2PAdapter.PeerEvent> events = new LinkedBlockingQueue<>();
			adapter.subscribe(events::add);
			AKeyPair other = AKeyPair.generate();
			AString otherDid = UCAN.toDIDKey(other.getAccountKey());
			var cursor = adapter.node().getNodeServer().getCursor();
			cursor.path(P2PLattice.KEY_P2P, P2PLattice.KEY_NODES).merge(convex.lattice.P2PLattice.createSignedEntry(other,
				convex.lattice.P2PLattice.createNodeInfo(Vectors.empty(), Strings.create("test"), Strings.create("1"),
					Vectors.empty(), false, cursor.getContext().currentTimestampValue())));
			cursor.sync();
			assertEquals(P2PAdapter.APPEARED, await(events, e -> otherDid.equals(e.did()), "the merged record").type());

			engine.configureAdapter("p2p", Maps.empty());
			assertEquals(CVMBool.FALSE, RT.getIn(peers(engine), P2PAdapter.K_JOINED));
			await(events, e -> P2PAdapter.REMOVED.equals(e.type()) && otherDid.equals(e.did()), "leaving lists it removed");
		} finally {
			engine.close();
		}
	}

	/** A listens; B only dials out, as a venue behind NAT does, so A reaches B over B's own connection. */
	@Test
	public void testTwoVenuesSeeEachOtherAndNoticeAStop() throws Exception {
		Engine a = joined("Venue A", true);
		Engine b = null;
		try {
			b = joined("Venue B", false);
			Engine venueB = b;
			AString didA = UCAN.toDIDKey(a.getAccountKey());
			AString didB = UCAN.toDIDKey(b.getAccountKey());
			BlockingQueue<P2PAdapter.PeerEvent> events = new LinkedBlockingQueue<>();
			b.findAdapter(P2PAdapter.class).subscribe(events::add);

			ACell selfA = RT.getIn(peers(a), P2PAdapter.K_SELF);
			assertEquals(didA, RT.getIn(selfA, Fields.DID));
			assertEquals(Strings.create("Venue A"), RT.getIn(selfA, Fields.NAME));
			AString address = RT.ensureString(RT.ensureVector(RT.getIn(selfA, P2PAdapter.K_TRANSPORTS)).get(0));

			ACell result = b.jobs().invokeInternal(CONNECT, Maps.of("did", didA, "address", address), b.venueContext())
				.get(WAIT_MS, TimeUnit.MILLISECONDS);
			assertEquals(didA, RT.getIn(result, Fields.DID));

			TestEngine.awaitCondition(() -> connected(a, didB) && connected(venueB, didA), WAIT_MS,
				() -> "the venues never listed each other as connected");
			assertEquals(P2PAdapter.OUTBOUND, entry(b, didA).get(P2PAdapter.K_REACH));
			assertEquals(P2PAdapter.INBOUND, entry(a, didB).get(P2PAdapter.K_REACH));
			assertEquals(0, RT.ensureVector(entry(a, didB).get(P2PAdapter.K_TRANSPORTS)).count(),
				"B advertises no address");
			TestEngine.awaitCondition(() -> Strings.create("Venue A").equals(entry(venueB, didA).get(Fields.NAME))
					&& Strings.create("Venue B").equals(entry(a, didB).get(Fields.NAME)), WAIT_MS,
				() -> "the venues never learnt each other's names");
			await(events, e -> didA.equals(e.did()) && CVMBool.TRUE.equals(e.peer().get(P2PAdapter.K_CONNECTED)),
				"B told that A is connected");

			// Disabling A's adapter takes its node offline; B notices and says so
			a.disableAdapter("p2p");
			await(events, e -> P2PAdapter.DISCONNECTED.equals(e.type()) && didA.equals(e.did()),
				"B told that A disconnected");
			assertFalse(connected(b, didA));
			assertNotNull(entry(b, didA), "a stopped node stays known");
		} finally {
			if (b != null) b.close();
			a.close();
		}
	}
}
