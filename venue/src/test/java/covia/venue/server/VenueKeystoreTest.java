package covia.venue.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.security.KeyStore;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;

/**
 * Tests for venue identity via a PKCS12 keystore (#208) — the Convex keystore
 * format, so venue keys can be managed with the Convex CLI. Every load failure
 * must be fatal with a message naming the missing piece: a venue must never
 * silently boot with a different identity than the operator configured.
 */
public class VenueKeystoreTest {

	static final String STOREPASS = "store-pass-208";
	static final String KEYPASS   = "key-pass-208";

	static AKeyPair KP;
	static File KEYSTORE_FILE;
	static String ALIAS;   // Convex convention: hex public key

	@BeforeAll
	static void createKeystore() throws Exception {
		KP = AKeyPair.generate();
		ALIAS = KP.getAccountKey().toHexString();
		KEYSTORE_FILE = File.createTempFile("venue-test-", ".pfx");
		KEYSTORE_FILE.delete();
		KEYSTORE_FILE.deleteOnExit();
		KeyStore ks = PFXTools.createStore(KEYSTORE_FILE, STOREPASS.toCharArray());
		PFXTools.setKeyPair(ks, KP, KEYPASS.toCharArray());
		PFXTools.saveStore(ks, KEYSTORE_FILE, STOREPASS.toCharArray());
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> ksConfig(String path, String alias,
			String storepass, String keypass) {
		AMap<AString, ACell> m = Maps.empty();
		if (path != null)      m = m.assoc(Strings.create("path"), Strings.create(path));
		if (alias != null)     m = m.assoc(Strings.create("alias"), Strings.create(alias));
		if (storepass != null) m = m.assoc(Strings.create("storepass"), Strings.create(storepass));
		if (keypass != null)   m = m.assoc(Strings.create("keypass"), Strings.create(keypass));
		return m;
	}

	@Test
	public void testLoadHappyPath() {
		AKeyPair kp = VenueServer.loadFromKeystore(
			ksConfig(KEYSTORE_FILE.getAbsolutePath(), ALIAS, STOREPASS, KEYPASS));
		assertEquals(KP.getAccountKey(), kp.getAccountKey(),
			"keystore must yield the stored venue identity");
	}

	@Test
	public void testMissingAliasFails() {
		IllegalStateException e = assertThrows(IllegalStateException.class, () ->
			VenueServer.loadFromKeystore(
				ksConfig(KEYSTORE_FILE.getAbsolutePath(), null, STOREPASS, KEYPASS)));
		assertTrue(e.getMessage().contains("alias"), e.getMessage());
	}

	@Test
	public void testUnknownAliasFails() {
		IllegalStateException e = assertThrows(IllegalStateException.class, () ->
			VenueServer.loadFromKeystore(
				ksConfig(KEYSTORE_FILE.getAbsolutePath(), "deadbeef", STOREPASS, KEYPASS)));
		assertTrue(e.getMessage().contains("deadbeef"), e.getMessage());
	}

	@Test
	public void testWrongStorePasswordFails() {
		assertThrows(IllegalStateException.class, () ->
			VenueServer.loadFromKeystore(
				ksConfig(KEYSTORE_FILE.getAbsolutePath(), ALIAS, "wrong", KEYPASS)));
	}

	@Test
	public void testMissingKeystoreFileFails() {
		IllegalStateException e = assertThrows(IllegalStateException.class, () ->
			VenueServer.loadFromKeystore(
				ksConfig(KEYSTORE_FILE.getAbsolutePath() + ".nope", ALIAS, STOREPASS, KEYPASS)));
		assertTrue(e.getMessage().contains("not found"), e.getMessage());
	}

	@Test
	public void testMissingStorePasswordFails() {
		// Env fallback would mask the failure — only meaningful when unset.
		assumeTrue(System.getenv("CONVEX_KEYSTORE_PASSWORD") == null);
		IllegalStateException e = assertThrows(IllegalStateException.class, () ->
			VenueServer.loadFromKeystore(
				ksConfig(KEYSTORE_FILE.getAbsolutePath(), ALIAS, null, KEYPASS)));
		assertTrue(e.getMessage().contains("CONVEX_KEYSTORE_PASSWORD"), e.getMessage());
	}

	@Test
	public void testMissingKeyPasswordFails() {
		assumeTrue(System.getenv("CONVEX_KEY_PASSWORD") == null);
		IllegalStateException e = assertThrows(IllegalStateException.class, () ->
			VenueServer.loadFromKeystore(
				ksConfig(KEYSTORE_FILE.getAbsolutePath(), ALIAS, STOREPASS, null)));
		assertTrue(e.getMessage().contains("CONVEX_KEY_PASSWORD"), e.getMessage());
	}
}
