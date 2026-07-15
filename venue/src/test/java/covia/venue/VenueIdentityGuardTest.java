package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	@Test
	public void testWrongKeyOnExistingStoreFails() throws Exception {
		EtchStore store = EtchStore.createTemp();
		AKeyPair kpA = AKeyPair.generate();
		AKeyPair kpB = AKeyPair.generate();

		// Stage 1: create the venue under key A and persist it.
		{
			NodeServer<Index<Keyword, ACell>> ns = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			ns.launch();
			Engine engine = new Engine(configFor(kpA), ns.getCursor(), kpA);
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
					() -> new Engine(configFor(kpB), ns.getCursor(), kpB));
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
			Engine engine = new Engine(configFor(kpA), ns.getCursor(), kpA);
			assertEquals(didFor(kpA), engine.getDIDString().toString());
			engine.close();
			ns.close();
		}
	}
}
