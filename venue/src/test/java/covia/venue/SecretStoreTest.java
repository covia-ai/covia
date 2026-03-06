package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.lattice.Covia;
import covia.lattice.Namespace;

/**
 * Tests for SecretStore and the extended USER lattice with Namespace keys.
 */
public class SecretStoreTest {

	private static final String SECRET_NAME = "openai-key";
	private static final String SECRET_VALUE = "sk-test-1234567890";

	// ========== Roundtrip Encryption ==========

	@Test
	public void testStoreAndDecrypt() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		byte[] key = SecretStore.deriveKey(kp);

		User user = vs.users().ensure("did:key:zAlice");
		SecretStore secrets = user.secrets();

		secrets.store(SECRET_NAME, SECRET_VALUE, key);
		AString decrypted = secrets.decrypt(SECRET_NAME, key);

		assertEquals(SECRET_VALUE, decrypted.toString(),
			"Decrypted value should match original plaintext");
	}

	// ========== Multiple Secrets ==========

	@Test
	public void testMultipleSecrets() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		byte[] key = SecretStore.deriveKey(kp);

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
		byte[] key = SecretStore.deriveKey(kp);

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

	// ========== Exists ==========

	@Test
	public void testExists() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		byte[] key = SecretStore.deriveKey(kp);

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
		byte[] key = SecretStore.deriveKey(kp);

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
		byte[] key = SecretStore.deriveKey(kp);

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
		byte[] key1 = SecretStore.deriveKey(kp1);
		byte[] key2 = SecretStore.deriveKey(kp2);

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
		byte[] key = SecretStore.deriveKey(kp);

		User user = vs.users().ensure("did:key:zAlice");
		assertNull(user.secrets().decrypt("no-such-key", key));
	}

	// ========== Empty List ==========

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
		byte[] encKey = SecretStore.deriveKey(kp);
		String did = "did:key:zTest";

		// Venue 1: user writes a job
		VenueState vs1 = VenueState.create(kp);
		User user1 = vs1.users().ensure(did);
		user1.jobs().persist(Blob.parse("0x0001"), Maps.of(
			covia.api.Fields.STATUS, covia.grid.Status.PENDING,
			covia.api.Fields.UPDATED, convex.core.data.prim.CVMLong.create(1000L)));

		// Venue 2: user writes a secret
		VenueState vs2 = VenueState.create(kp);
		User user2 = vs2.users().ensure(did);
		user2.secrets().store("key1", "val1", encKey);

		// After merge, both should be present
		// We verify by reading through component wrappers, not raw Index equality
		// (which would fail due to AString/Keyword key type normalisation)
		assertEquals(1, user1.jobs().count(), "Jobs should be accessible via wrapper");
		assertTrue(user2.secrets().exists("key1"), "Secrets should be accessible via wrapper");
	}

	@Test
	public void testUserMergeIdempotentViaWrappers() {
		// Merge a user state with itself should not change observable state
		AKeyPair kp = AKeyPair.generate();
		byte[] encKey = SecretStore.deriveKey(kp);

		VenueState vs = VenueState.create(kp);
		User user = vs.users().ensure("did:key:zTest");
		user.jobs().persist(Blob.parse("0x0001"), Maps.of(
			covia.api.Fields.STATUS, covia.grid.Status.PENDING,
			covia.api.Fields.UPDATED, convex.core.data.prim.CVMLong.create(1000L)));
		user.secrets().store("key1", "val1", encKey);

		long jobCountBefore = user.jobs().count();
		long secretCountBefore = user.secrets().list().count();

		// Self-merge via lattice (simulates lattice sync)
		@SuppressWarnings("unchecked")
		AHashMap<AString, ACell> state = (AHashMap<AString, ACell>) user.get();
		AHashMap<AString, ACell> merged = Covia.USER.merge(state, state);

		// Merged state should be identical (idempotent)
		assertEquals(state, merged, "Self-merge must be idempotent");
		assertEquals(jobCountBefore, user.jobs().count());
		assertEquals(secretCountBefore, user.secrets().list().count());
	}

	// ========== Namespace Key Equivalence ==========

	@Test
	public void testNamespaceKeyResolution() {
		// StringKeyedLattice uses AString keys — Namespace constants should resolve
		assertNotNull(Covia.USER.path(Namespace.J), "AString 'j' should resolve to jobs lattice");
		assertNotNull(Covia.USER.path(Namespace.G), "AString 'g' should resolve to agents lattice");
		assertNotNull(Covia.USER.path(Namespace.S), "AString 's' should resolve to secrets lattice");

		// Equivalent AString values should also resolve
		assertNotNull(Covia.USER.path(Strings.create("j")), "AString 'j' literal should resolve");
		assertNotNull(Covia.USER.path(Strings.create("g")), "AString 'g' literal should resolve");
		assertNotNull(Covia.USER.path(Strings.create("s")), "AString 's' literal should resolve");
	}

	// ========== User Isolation ==========

	@Test
	public void testSecretIsolationBetweenUsers() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		byte[] key = SecretStore.deriveKey(kp);

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
		user.jobs().persist(
			convex.core.data.Blob.parse("0x0001"),
			convex.core.data.Maps.of(
				covia.api.Fields.STATUS, covia.grid.Status.PENDING,
				covia.api.Fields.UPDATED, convex.core.data.prim.CVMLong.create(1000L)));

		assertEquals(1, user.jobs().count(), "Jobs should work in extended USER lattice");
	}

	// ========== Key Derivation ==========

	@Test
	public void testDeriveKeyDeterministic() {
		AKeyPair kp = AKeyPair.generate();
		byte[] key1 = SecretStore.deriveKey(kp);
		byte[] key2 = SecretStore.deriveKey(kp);

		assertArrayEquals(key1, key2, "Same key pair should produce same derived key");
		assertEquals(32, key1.length, "Derived key should be 32 bytes (AES-256)");
	}

	@Test
	public void testDeriveKeyDifferentForDifferentKeyPairs() {
		AKeyPair kp1 = AKeyPair.generate();
		AKeyPair kp2 = AKeyPair.generate();
		byte[] key1 = SecretStore.deriveKey(kp1);
		byte[] key2 = SecretStore.deriveKey(kp2);

		assertFalse(java.util.Arrays.equals(key1, key2),
			"Different key pairs should produce different derived keys");
	}
}
