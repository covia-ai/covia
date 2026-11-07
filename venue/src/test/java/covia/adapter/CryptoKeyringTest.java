package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import covia.adapter.CryptoKeyring.Kind;
import covia.venue.AdapterState;
import covia.venue.Engine;
import covia.venue.SecretStore;

/** The keyring store: its layout and migration, sealing and deletion. */
class CryptoKeyringTest {

	private static Engine engine;

	@BeforeAll static void start() { engine = Engine.createTemp(null); }
	@AfterAll static void stop() { if (engine != null) engine.close(); }

	private static final CVMLong NOW = CVMLong.create(1);

	private static AdapterState state() {
		AdapterState state = engine.adapterState("crypto");
		state.set(Maps.empty());
		return state;
	}

	private static AMap<AString, ACell> keyRecord(SecretStore.Keys keys, AString holder, AKeyPair pair) {
		return CryptoKeyring.record(keys, holder, Kind.KEY, CryptoKeyring.id(pair.getAccountKey()),
			Multikey.encodeSecretKeey(pair.getSeed()), null, NOW);
	}

	@Test void versionZeroHeldNothingAndConvertsToKeyrings() {
		CryptoKeyring.Keyrings step = new CryptoKeyring.Keyrings();
		assertEquals(CryptoKeyring.empty(), step.apply(Maps.empty()));
		ACell current = CryptoKeyring.empty();
		assertSame(current, step.apply(current));
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> step.apply(Maps.of("other", 1)));
		assertEquals(CryptoKeyring.UNKNOWN_STATE, e.getMessage());
		assertEquals(1, new CryptoAdapter().stateVersion());
	}

	@Test void sealingKeysOpenNothingOfTheSecretStore() {
		AKeyPair venue = AKeyPair.generate();
		SecretStore.Keys secrets = SecretStore.keys(venue);
		SecretStore.Keys keyring = CryptoKeyring.keys(venue);
		AMap<AString, ACell> sealed = SecretStore.seal(Strings.create("x"), secrets);
		assertThrows(RuntimeException.class, () -> SecretStore.unseal(sealed, keyring));
		// Nor a record claiming the secret store's legacy version 1.
		AMap<AString, ACell> legacy = sealed.assoc(Strings.create("keyVersion"), CVMLong.create(SecretStore.KEY_VERSION_1));
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> SecretStore.unseal(legacy, keyring));
		assertEquals(SecretStore.UNKNOWN_KEY_VERSION, e.getMessage());
		assertThrows(IllegalArgumentException.class, () -> SecretStore.sealingKeys(venue, SecretStore.KEY_INFO_V2));
	}

	@Test void aRecordOpensOnlyWhereItWasWritten() {
		SecretStore.Keys keys = CryptoKeyring.keys(engine.getKeyPair());
		AKeyPair pair = AKeyPair.generate();
		AKeyPair other = AKeyPair.generate();
		AString alice = Strings.create("did:key:zAlice");
		Blob id = CryptoKeyring.id(pair.getAccountKey());
		AMap<AString, ACell> record = keyRecord(keys, alice, pair);
		assertEquals(pair.getAccountKey(), CryptoKeyring.openKey(keys, alice, id, record).getAccountKey());
		// Moved to another holder or another key's place, it refuses to open.
		assertThrows(IllegalStateException.class, () -> CryptoKeyring.openKey(keys, Strings.create("did:key:zBob"), id, record));
		assertThrows(IllegalStateException.class,
			() -> CryptoKeyring.openKey(keys, alice, CryptoKeyring.id(other.getAccountKey()), record));
	}

	@Test void putKeepsAHeldKeyUnlessRelabelled() {
		AdapterState state = state();
		SecretStore.Keys keys = CryptoKeyring.keys(engine.getKeyPair());
		AString alice = Strings.create("did:key:zAlice");
		AKeyPair pair = AKeyPair.generate();
		Blob id = CryptoKeyring.id(pair.getAccountKey());
		assertTrue(CryptoKeyring.put(state, alice, Kind.KEY, id, keyRecord(keys, alice, pair), false));
		assertEquals(CVMLong.create(CryptoKeyring.LAYOUT_VERSION), state.read("version"));
		AMap<AString, ACell> labelled = keyRecord(keys, alice, pair).assoc(covia.api.Fields.LABEL, Strings.create("hot"));
		assertFalse(CryptoKeyring.put(state, alice, Kind.KEY, id, labelled, false));
		assertNull(CryptoKeyring.get(state.get(), alice, Kind.KEY, id).get(covia.api.Fields.LABEL));
		assertTrue(CryptoKeyring.put(state, alice, Kind.KEY, id, labelled, true));
		assertEquals(Strings.create("hot"), CryptoKeyring.get(state.get(), alice, Kind.KEY, id).get(covia.api.Fields.LABEL));
		assertTrue(CryptoKeyring.remove(state, alice, Kind.KEY, id));
		assertFalse(CryptoKeyring.remove(state, alice, Kind.KEY, id));
		assertEquals(0, CryptoKeyring.entries(state.get(), alice, Kind.KEY).count());
	}

	@Test void deletingAHolderTakesItsNestedIdentitiesNotItsNeighbours() {
		AdapterState state = state();
		SecretStore.Keys keys = CryptoKeyring.keys(engine.getKeyPair());
		String[] holders = {"did:key:zA", "did:key:zA:u:alice", "did:key:zA0", "did:key:zAb"};
		for (String h : holders) {
			AKeyPair pair = AKeyPair.generate();
			AString holder = Strings.create(h);
			CryptoKeyring.put(state, holder, Kind.KEY, CryptoKeyring.id(pair.getAccountKey()), keyRecord(keys, holder, pair), false);
		}
		new CryptoAdapter().deleteUserState(Strings.create("did:key:zA"), state);
		assertEquals(0, CryptoKeyring.entries(state.get(), Strings.create("did:key:zA"), Kind.KEY).count());
		assertEquals(0, CryptoKeyring.entries(state.get(), Strings.create("did:key:zA:u:alice"), Kind.KEY).count());
		assertEquals(1, CryptoKeyring.entries(state.get(), Strings.create("did:key:zA0"), Kind.KEY).count());
		assertEquals(1, CryptoKeyring.entries(state.get(), Strings.create("did:key:zAb"), Kind.KEY).count());
	}
}
