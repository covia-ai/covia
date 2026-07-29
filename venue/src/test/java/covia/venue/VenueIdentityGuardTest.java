package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.etch.EtchStore;
import convex.node.NodeConfig;
import convex.node.NodeServer;
import covia.lattice.Covia;
import covia.venue.server.VenueServer;

/**
 * Tests for the venue identity guard (#208): booting an existing store with a
 * key that owns none of its venue state must fail loudly. Venues are keyed by
 * AccountKey, so without the guard a wrong key would silently create a fresh
 * empty venue entry alongside the real one, orphaning all existing data.
 */
public class VenueIdentityGuardTest {

	private static String didFor(AKeyPair kp) {
		return "did:key:" + Multikey.encodePublicKey(kp.getAccountKey());
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> configFor(AKeyPair kp) {
		return (AMap<AString, ACell>) (AMap<?, ?>) Maps.of(Config.DID, didFor(kp));
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> serverConfig(Path storePath, AKeyPair kp) {
		return (AMap<AString, ACell>) (AMap<?, ?>) Maps.of(
			Config.PORT, 0,
			Config.STORE, storePath.toString().replace('\\', '/'),
			Config.SEED, kp.getSeed().toHexString());
	}

	@Test
	public void testWrongKeyOnExistingStoreFails() throws Exception {
		EtchStore store = EtchStore.createTemp();
		AKeyPair kpA = AKeyPair.generate();
		AKeyPair kpB = AKeyPair.generate();

		// Stage 1: create the venue under key A and persist it.
		{
			NodeServer<Index<Keyword, ACell>> ns = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			ns.launch();
			Engine engine = new Engine(configFor(kpA), ns.getCursor(), kpA).start();
			assertEquals(didFor(kpA), engine.getDIDString().toString());
			engine.flush();
			engine.close();
			ns.close();
		}

		// Stage 2: reopening with key B must fail, naming the store's real owner.
		{
			NodeServer<Index<Keyword, ACell>> ns = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			ns.launch();
			try {
				IllegalStateException e = assertThrows(IllegalStateException.class,
					() -> new Engine(configFor(kpB), ns.getCursor(), kpB).start());
				assertTrue(e.getMessage().contains(didFor(kpA)),
					"error names the owning identity: " + e.getMessage());
				assertTrue(e.getMessage().contains(didFor(kpB)),
					"error names the configured identity: " + e.getMessage());
			} finally {
				ns.close();
			}
		}

		// Stage 3: the owning key still boots normally (guard passes on match).
		{
			NodeServer<Index<Keyword, ACell>> ns = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			ns.launch();
			Engine engine = new Engine(configFor(kpA), ns.getCursor(), kpA).start();
			assertEquals(didFor(kpA), engine.getDIDString().toString());
			engine.close();
			ns.close();
		}
	}

	@Test
	public void failedVenueServerIdentityGuardReleasesEtchFileLock() throws Exception {
		Path dir = Files.createTempDirectory("venue-identity-rollback-");
		Path storePath = dir.resolve("venue.etch");
		dir.toFile().deleteOnExit();
		storePath.toFile().deleteOnExit();
		AKeyPair owner = AKeyPair.createSeeded(20801);
		AKeyPair wrong = AKeyPair.createSeeded(20802);

		VenueServer initial = VenueServer.launch(serverConfig(storePath, owner));
		initial.close();

		RuntimeException failure = assertThrows(RuntimeException.class,
				() -> VenueServer.launch(serverConfig(storePath, wrong)));
		assertTrue(String.valueOf(failure.getCause()).contains("Venue key mismatch"),
			"identity guard diagnostic must survive constructor rollback: " + failure);

		EtchStore reopened = EtchStore.create(storePath.toFile());
		try {
			assertNotNull(reopened,
				"the failed constructor must release the Etch file for immediate reuse");
		} finally {
			reopened.close();
		}
	}

	@Test
	public void peekKeysThenLaunchWithAdoptedStore() throws Exception {
		Path dir = Files.createTempDirectory("venue-adopt-store-");
		Path storePath = dir.resolve("venue.etch");
		dir.toFile().deleteOnExit();
		storePath.toFile().deleteOnExit();
		AKeyPair owner = AKeyPair.createSeeded(30701);

		VenueServer initial = VenueServer.launch(serverConfig(storePath, owner));
		initial.close();

		EtchStore adopted = EtchStore.create(storePath.toFile());
		assertEquals(java.util.Set.of(owner.getAccountKey()), VenueState.peekVenueKeys(adopted));

		VenueServer relaunched = VenueServer.launch(
			serverConfig(storePath, owner), adopted, java.util.List.of());
		assertSame(adopted, relaunched.getStore(), "launch must use the caller's store instance");
		relaunched.close();

		EtchStore reopened = EtchStore.create(storePath.toFile());
		reopened.close(); // successful venue close owns and releases the adopted store
	}

	@Test
	public void failedLaunchClosesAdoptedStore() throws Exception {
		Path dir = Files.createTempDirectory("venue-adopt-failure-");
		Path storePath = dir.resolve("venue.etch");
		dir.toFile().deleteOnExit();
		storePath.toFile().deleteOnExit();
		AKeyPair owner = AKeyPair.createSeeded(30702);
		AKeyPair wrong = AKeyPair.createSeeded(30703);

		VenueServer initial = VenueServer.launch(serverConfig(storePath, owner));
		initial.close();

		EtchStore adopted = EtchStore.create(storePath.toFile());
		RuntimeException failure = assertThrows(RuntimeException.class,
			() -> VenueServer.launch(serverConfig(storePath, wrong), adopted));
		assertTrue(String.valueOf(failure.getCause()).contains("Venue key mismatch"));

		EtchStore reopened = EtchStore.create(storePath.toFile());
		reopened.close(); // failed venue launch also owns and releases the store
	}
}
