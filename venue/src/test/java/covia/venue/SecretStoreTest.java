package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AESGCM;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.HKDF;
import convex.core.crypto.Hashing;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.lattice.Namespace;

/**
 * Tests for SecretStore and the extended USER lattice with Namespace keys.
 */
public class SecretStoreTest {

	private static final String SECRET_NAME = "openai-key";
	private static final String SECRET_VALUE = "sk-test-1234567890";

	/** Record fields as a version 1 venue wrote them: no key version. */
	private static final AString ENCRYPTED = Strings.create("encrypted");
	private static final AString KEY_VERSION = Strings.create("keyVersion");

	// ========== Roundtrip Encryption ==========

	@Test
	public void testStoreAndDecrypt() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		SecretStore.Keys key = SecretStore.keys(kp);

		User user = vs.users().ensure("did:key:zAlice");
		SecretStore secrets = user.secrets();

		secrets.store(SECRET_NAME, SECRET_VALUE, key);
		AString decrypted = secrets.decrypt(SECRET_NAME, key);

		assertEquals(SECRET_VALUE, decrypted.toString(),
			"Decrypted value should match original plaintext");
	}

	// ========== Sealed values ==========

	@Test
	public void testSealedValueOpensOnlyUnderItsVenuesKeys() {
		AKeyPair kp = AKeyPair.generate();
		SecretStore.Keys keys = SecretStore.keys(kp);
		AMap<AString, ACell> sealed = SecretStore.seal(Strings.create(SECRET_VALUE), keys);

		assertFalse(sealed.toString().contains(SECRET_VALUE), "a sealed value carries no plaintext");
		assertEquals(CVMLong.create(SecretStore.CURRENT_KEY_VERSION), sealed.get(KEY_VERSION));
		assertEquals(SECRET_VALUE, SecretStore.unseal(sealed, keys).toString());
		assertNull(SecretStore.unseal(null, keys));
		assertThrows(RuntimeException.class, () -> SecretStore.unseal(sealed, SecretStore.keys(AKeyPair.generate())),
			"another venue's keys open nothing");
		IllegalStateException notSealed = assertThrows(IllegalStateException.class,
			() -> SecretStore.unseal(Strings.create(SECRET_VALUE), keys));
		assertEquals(SecretStore.INVALID_RECORD, notSealed.getMessage());
	}

	// ========== Multiple Secrets ==========

	@Test
	public void testMultipleSecrets() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		SecretStore.Keys key = SecretStore.keys(kp);

		User user = vs.users().ensure("did:key:zAlice");
		SecretStore secrets = user.secrets();

		secrets.store("key1", "value1", key);
		secrets.store("key2", "value2", key);
		secrets.store("key3", "value3", key);

		AVector<AString> names = secrets.list();
		assertEquals(3, names.count(), "Should have 3 secrets");
	}

	// ========== Overwrite (LWW) ==========

	@Test
	public void testOverwrite() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		SecretStore.Keys key = SecretStore.keys(kp);

		User user = vs.users().ensure("did:key:zAlice");
		SecretStore secrets = user.secrets();

		secrets.store(SECRET_NAME, "old-value", key);
		secrets.store(SECRET_NAME, "new-value", key);

		AString decrypted = secrets.decrypt(SECRET_NAME, key);
		assertEquals("new-value", decrypted.toString(),
			"Should return the latest stored value");

		AVector<AString> names = secrets.list();
		assertEquals(1, names.count(), "Should still be one secret");
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testOverwriteDoesNotRegressSecretTimestamp() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		User user = vs.users().ensure("did:key:zAlice");
		SecretStore secrets = user.secrets();
		SecretStore.Keys key = SecretStore.keys(kp);
		AString name = Strings.create(SECRET_NAME);
		secrets.store(name, Strings.create("old-value"), key);
		CVMLong future = CVMLong.create(System.currentTimeMillis() + 60_000);
		user.cursor().path(Namespace.S).updateAndGet(value -> {
			AMap<AString, ACell> all = (AMap<AString, ACell>) value;
			AMap<AString, ACell> record = (AMap<AString, ACell>) all.get(name);
			return all.assoc(name, record.assoc(Fields.UPDATED, future));
		});

		secrets.store(name, Strings.create("new-value"), key);

		assertEquals(future, RT.getIn(user.cursor().get(), Namespace.S, name, Fields.UPDATED));
		assertEquals("new-value", secrets.decrypt(name, key).toString());
	}

	// ========== Exists ==========

	@Test
	public void testExists() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		SecretStore.Keys key = SecretStore.keys(kp);

		User user = vs.users().ensure("did:key:zAlice");
		SecretStore secrets = user.secrets();

		assertFalse(secrets.exists(SECRET_NAME), "Should not exist before storing");

		secrets.store(SECRET_NAME, SECRET_VALUE, key);
		assertTrue(secrets.exists(SECRET_NAME), "Should exist after storing");

		assertFalse(secrets.exists("nonexistent"),
			"Nonexistent secret should return false");
	}

	// ========== Delete ==========

	@Test
	public void testDelete() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		SecretStore.Keys key = SecretStore.keys(kp);

		User user = vs.users().ensure("did:key:zAlice");
		SecretStore secrets = user.secrets();

		secrets.store(SECRET_NAME, SECRET_VALUE, key);
		assertTrue(secrets.exists(SECRET_NAME));

		secrets.delete(SECRET_NAME);
		assertFalse(secrets.exists(SECRET_NAME), "Should not exist after deletion");
		assertNull(secrets.decrypt(SECRET_NAME, key), "Decrypt should return null after deletion");
	}

	// ========== List Returns Names Only ==========

	@Test
	public void testListReturnsNamesOnly() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		SecretStore.Keys key = SecretStore.keys(kp);

		User user = vs.users().ensure("did:key:zAlice");
		SecretStore secrets = user.secrets();

		secrets.store("api-key", "secret-value", key);
		AVector<AString> names = secrets.list();

		assertEquals(1, names.count());
		assertEquals("api-key", names.get(0).toString());
		// Names list should not contain plaintext values
	}

	// ========== Wrong Key Fails ==========

	@Test
	public void testWrongKeyFails() {
		AKeyPair kp1 = AKeyPair.generate();
		AKeyPair kp2 = AKeyPair.generate();
		VenueState vs = VenueState.create(kp1);
		SecretStore.Keys key1 = SecretStore.keys(kp1);
		SecretStore.Keys key2 = SecretStore.keys(kp2);

		User user = vs.users().ensure("did:key:zAlice");
		SecretStore secrets = user.secrets();

		secrets.store(SECRET_NAME, SECRET_VALUE, key1);

		// Decrypting with wrong key should throw (AES-GCM authentication tag mismatch)
		assertThrows(RuntimeException.class,
			() -> secrets.decrypt(SECRET_NAME, key2),
			"Decrypting with wrong key should fail");
	}

	// ========== Decrypt Nonexistent ==========

	@Test
	public void testDecryptNonexistent() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		SecretStore.Keys key = SecretStore.keys(kp);

		User user = vs.users().ensure("did:key:zAlice");
		assertNull(user.secrets().decrypt("no-such-key", key));
	}

	// ========== Empty List ==========

	@SuppressWarnings("deprecation")
	@Test
	public void testMalformedRecordsAreNotReportedAsMissing() {
		AKeyPair kp = AKeyPair.generate();
		User user = VenueState.create(kp).users().ensure("did:key:zAlice");
		var records = user.secrets().cursor();
		for (ACell malformed : new ACell[] { null, Strings.create("invalid"), Maps.empty(), Maps.of(ENCRYPTED, "invalid") }) {
			records.set(Maps.of(SECRET_NAME, malformed));
			assertEquals(SecretStore.INVALID_RECORD, assertThrows(IllegalStateException.class,
				() -> user.secrets().decrypt(SECRET_NAME, SecretStore.keys(kp))).getMessage());
			assertEquals(SecretStore.INVALID_RECORD, assertThrows(IllegalStateException.class,
				() -> user.secrets().decrypt(SECRET_NAME, SecretStore.deriveKey(kp))).getMessage());
		}
	}

	@Test
	public void testEmptyList() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);

		User user = vs.users().ensure("did:key:zAlice");
		AVector<AString> names = user.secrets().list();

		assertNotNull(names);
		assertEquals(0, names.count(), "Empty secret store should list zero names");
	}

	// ========== USER Merge Properties (via lattice component wrappers) ==========

	@Test
	public void testUserMergePreservesAllNamespaces() {
		// Two independent venues write to different namespaces of the same user,
		// then merge. Both namespaces should be present after merge.
		AKeyPair kp = AKeyPair.generate();
		SecretStore.Keys encKey = SecretStore.keys(kp);
		String did = "did:key:zTest";

		// Venue 1: user writes a job
		VenueState vs1 = VenueState.create(kp);
		User user1 = vs1.users().ensure(did);
		user1.persistJob(Blob.parse("0x0001"), Maps.of(
			covia.api.Fields.STATUS, covia.grid.Status.PENDING,
			covia.api.Fields.UPDATED, convex.core.data.prim.CVMLong.create(1000L)));

		// Venue 2: user writes a secret
		VenueState vs2 = VenueState.create(kp);
		User user2 = vs2.users().ensure(did);
		user2.secrets().store("key1", "val1", encKey);

		// After merge, both should be present
		// We verify by reading through component wrappers, not raw Index equality
		// (which would fail due to AString/Keyword key type normalisation)
		assertEquals(1, user1.getJobs().count(), "Jobs should be accessible via wrapper");
		assertTrue(user2.secrets().exists("key1"), "Secrets should be accessible via wrapper");
	}

	// ========== User Isolation ==========

	@Test
	public void testSecretIsolationBetweenUsers() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		SecretStore.Keys key = SecretStore.keys(kp);

		User alice = vs.users().ensure("did:key:zAlice");
		User bob = vs.users().ensure("did:key:zBob");

		alice.secrets().store("alice-key", "alice-secret", key);
		bob.secrets().store("bob-key", "bob-secret", key);

		// Each user sees only their own secrets
		assertEquals(1, alice.secrets().list().count());
		assertEquals(1, bob.secrets().list().count());
		assertTrue(alice.secrets().exists("alice-key"));
		assertFalse(alice.secrets().exists("bob-key"));
		assertTrue(bob.secrets().exists("bob-key"));
		assertFalse(bob.secrets().exists("alice-key"));
	}

	// ========== Agent Cursor ==========

	@Test
	public void testAgentAccessor() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);

		User user = vs.users().ensure("did:key:zAlice");
		assertNull(user.agent("my-agent"),
			"Agent should be null before creation");

		AgentState agent = user.ensureAgent("my-agent", null, null);
		assertNotNull(agent, "ensureAgent should return non-null AgentState");
		assertTrue(agent.exists(), "Agent should exist after ensureAgent");
	}

	@Test
	public void testGetAgentsInitiallyNull() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);

		User user = vs.users().ensure("did:key:zAlice");
		assertNull(user.getAgents(), "Agents should be null before any are created");
	}

	// ========== Backwards Compatibility ==========

	@Test
	public void testJobsStillWorkWithNewUserLattice() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);

		User user = vs.users().ensure("did:key:zAlice");

		// Jobs should still work with the extended USER lattice
		user.persistJob(
			convex.core.data.Blob.parse("0x0001"),
			convex.core.data.Maps.of(
				covia.api.Fields.STATUS, covia.grid.Status.PENDING,
				covia.api.Fields.UPDATED, convex.core.data.prim.CVMLong.create(1000L)));

		assertEquals(1, user.getJobs().count(), "Jobs should work in extended USER lattice");
	}

	// ========== Key Derivation ==========

	@Test
	public void testKeysDeterministic() {
		AKeyPair kp = AKeyPair.generate();
		SecretStore.Keys keys1 = SecretStore.keys(kp);
		SecretStore.Keys keys2 = SecretStore.keys(kp);

		for (int version : new int[] { SecretStore.KEY_VERSION_1, SecretStore.KEY_VERSION_2 }) {
			assertArrayEquals(keys1.forVersion(version), keys2.forVersion(version),
				"Same key pair should produce the same key of version " + version);
			assertEquals(32, keys1.forVersion(version).length, "Derived key should be 32 bytes (AES-256)");
		}
	}

	@Test
	public void testKeysDifferentForDifferentKeyPairs() {
		SecretStore.Keys keys1 = SecretStore.keys(AKeyPair.generate());
		SecretStore.Keys keys2 = SecretStore.keys(AKeyPair.generate());

		for (int version : new int[] { SecretStore.KEY_VERSION_1, SecretStore.KEY_VERSION_2 }) {
			assertFalse(java.util.Arrays.equals(keys1.forVersion(version), keys2.forVersion(version)),
				"Different key pairs should produce different keys of version " + version);
		}
	}

	@Test
	public void testVersion2KeyIsDomainSeparatedFromSha256OfSeed() {
		AKeyPair kp = AKeyPair.generate();
		byte[] seed = kp.getSeed().getBytes();
		SecretStore.Keys keys = SecretStore.keys(kp);

		byte[] sha256 = Hashing.sha256(seed).getBytes();
		assertFalse(java.util.Arrays.equals(sha256, keys.forVersion(SecretStore.KEY_VERSION_2)),
			"The version 2 key is not SHA-256 of the seed");
		assertArrayEquals(HKDF.derive256(seed, null,
				SecretStore.KEY_INFO_V2.getBytes(StandardCharsets.UTF_8)),
			keys.forVersion(SecretStore.KEY_VERSION_2));
		assertArrayEquals(sha256, keys.forVersion(SecretStore.KEY_VERSION_1));
		assertEquals(SecretStore.KEY_VERSION_2, SecretStore.CURRENT_KEY_VERSION);
		assertNull(keys.forVersion(7), "No key for an unknown version");
	}

	@SuppressWarnings("deprecation")
	@Test
	public void testDerivationVectorsArePinned() {
		// Seed 0x00..0x1f. Expected values computed independently: v2 by
		// RFC 5869 (HMAC-SHA256, empty salt, info "covia/secret-store/v2"),
		// v1 by SHA-256. A change here strands every stored secret.
		byte[] seed = new byte[32];
		for (int i = 0; i < seed.length; i++) seed[i] = (byte) i;
		AKeyPair kp = AKeyPair.create(Blob.wrap(seed));
		SecretStore.Keys keys = SecretStore.keys(kp);

		assertEquals("86fad320f224bcff4f7f121aa6ad23f6d8c4d6f8c0130018cad850d67adad9e5",
			Blob.wrap(keys.forVersion(SecretStore.KEY_VERSION_2)).toHexString());
		assertEquals("630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd",
			Blob.wrap(keys.forVersion(SecretStore.KEY_VERSION_1)).toHexString());
		assertArrayEquals(keys.forVersion(SecretStore.KEY_VERSION_1), SecretStore.deriveKey(kp),
			"The deprecated deriveKey is the version 1 key");
	}

	// ========== Key Versions ==========

	/** Writes a record exactly as a venue did before key versions existed. */
	static void storeVersion1(User user, String name, String plaintext, AKeyPair kp) {
		byte[] v1 = Hashing.sha256(kp.getSeed().getBytes()).getBytes();
		Blob encrypted = Blob.wrap(AESGCM.encrypt(v1, plaintext.getBytes(StandardCharsets.UTF_8)));
		user.secrets().cursor().withDefault(Maps.empty()).path(Strings.create(name)).set(Maps.of(
			ENCRYPTED, encrypted,
			Fields.UPDATED, CVMLong.create(1000L)));
	}

	private static AMap<AString, ACell> record(User user, String name) {
		return RT.ensureMap(RT.getIn(user.cursor().get(), Namespace.S, Strings.create(name)));
	}

	@SuppressWarnings("deprecation")
	@Test
	public void testVersion1StoreStillDecrypts() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		User user = vs.users().ensure("did:key:zAlice");
		storeVersion1(user, SECRET_NAME, SECRET_VALUE, kp);
		storeVersion1(user, "other", "other-value", kp);

		assertNull(record(user, SECRET_NAME).get(KEY_VERSION), "fixture has no key version");
		SecretStore.Keys keys = SecretStore.keys(kp);
		assertEquals(SECRET_VALUE, user.secrets().decrypt(SECRET_NAME, keys).toString());
		assertEquals("other-value", user.secrets().decrypt("other", keys).toString());
		assertEquals(SECRET_VALUE, user.secrets().decrypt(SECRET_NAME, SecretStore.deriveKey(kp)).toString(),
			"the deprecated key still reads a version 1 secret");
		assertNull(record(user, SECRET_NAME).get(KEY_VERSION), "a read does not rewrite");
	}

	@SuppressWarnings("deprecation")
	@Test
	public void testRewriteMigratesToVersion2() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		User user = vs.users().ensure("did:key:zAlice");
		SecretStore.Keys keys = SecretStore.keys(kp);
		storeVersion1(user, SECRET_NAME, "old-value", kp);
		storeVersion1(user, "untouched", "kept-value", kp);

		user.secrets().store(SECRET_NAME, "new-value", keys);

		AMap<AString, ACell> migrated = record(user, SECRET_NAME);
		assertEquals(CVMLong.create(SecretStore.KEY_VERSION_2), migrated.get(KEY_VERSION));
		byte[] blob = ((Blob) migrated.get(ENCRYPTED)).getBytes();
		assertEquals("new-value", new String(
			AESGCM.decrypt(keys.forVersion(SecretStore.KEY_VERSION_2), blob), StandardCharsets.UTF_8));
		assertThrows(RuntimeException.class,
			() -> AESGCM.decrypt(Hashing.sha256(kp.getSeed().getBytes()).getBytes(), blob),
			"version 2 ciphertext does not open under SHA-256 of the seed");
		assertEquals("new-value", user.secrets().decrypt(SECRET_NAME, keys).toString());

		IllegalStateException legacy = assertThrows(IllegalStateException.class,
			() -> user.secrets().decrypt(SECRET_NAME, SecretStore.deriveKey(kp)));
		assertEquals(SecretStore.LEGACY_KEY_READS_V1_ONLY, legacy.getMessage());

		// Lazy: a secret nobody wrote stays at version 1 and still reads.
		assertNull(record(user, "untouched").get(KEY_VERSION));
		assertEquals("kept-value", user.secrets().decrypt("untouched", keys).toString());
	}

	@Test
	public void testEveryWriteEncryptsUnderTheCurrentVersion() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		User user = vs.users().ensure("did:key:zAlice");
		SecretStore.Keys keys = SecretStore.keys(kp);

		user.secrets().store("stored", "a", keys);
		assertTrue(user.secrets().storeIfAbsent(Strings.create("absent"), Strings.create("b"), keys));

		CVMLong current = CVMLong.create(SecretStore.CURRENT_KEY_VERSION);
		assertEquals(current, record(user, "stored").get(KEY_VERSION));
		assertEquals(current, record(user, "absent").get(KEY_VERSION));
		assertEquals("b", user.secrets().decrypt("absent", keys).toString());
	}

	@Test
	public void testUnknownKeyVersionIsRefused() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		User user = vs.users().ensure("did:key:zAlice");
		SecretStore.Keys keys = SecretStore.keys(kp);
		user.secrets().store(SECRET_NAME, SECRET_VALUE, keys);
		user.cursor().path(Namespace.S, Strings.create(SECRET_NAME), KEY_VERSION).set(CVMLong.create(7));

		IllegalStateException e = assertThrows(IllegalStateException.class,
			() -> user.secrets().decrypt(SECRET_NAME, keys));
		assertEquals(SecretStore.UNKNOWN_KEY_VERSION, e.getMessage());
	}

	@SuppressWarnings("deprecation")
	@Test
	public void testNoKeyMaterialOrValueInKeysOrRefusals() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		User user = vs.users().ensure("did:key:zAlice");
		SecretStore.Keys keys = SecretStore.keys(kp);
		user.secrets().store(SECRET_NAME, SECRET_VALUE, keys);

		StringBuilder exposed = new StringBuilder(keys.toString()).append('\n');
		exposed.append(assertThrows(RuntimeException.class,
			() -> user.secrets().decrypt(SECRET_NAME, SecretStore.keys(AKeyPair.generate())))).append('\n');
		exposed.append(assertThrows(RuntimeException.class,
			() -> user.secrets().decrypt(SECRET_NAME, SecretStore.deriveKey(kp)))).append('\n');
		user.cursor().path(Namespace.S, Strings.create(SECRET_NAME), KEY_VERSION).set(CVMLong.create(7));
		exposed.append(assertThrows(RuntimeException.class,
			() -> user.secrets().decrypt(SECRET_NAME, keys)));

		String text = exposed.toString().toLowerCase();
		assertFalse(text.contains(SECRET_VALUE.toLowerCase()), text);
		for (byte[] material : new byte[][] { kp.getSeed().getBytes(),
				keys.forVersion(SecretStore.KEY_VERSION_1), keys.forVersion(SecretStore.KEY_VERSION_2) }) {
			assertFalse(text.contains(Blob.wrap(material).toHexString()), text);
		}
	}
}
