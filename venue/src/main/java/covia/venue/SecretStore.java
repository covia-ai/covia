package covia.venue;

import java.nio.charset.StandardCharsets;

import convex.core.crypto.AESGCM;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.Hashing;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;

/**
 * Per-user encrypted secret storage.
 *
 * <p>Wraps a lattice cursor at the user's {@code /s/} namespace path.
 * Secrets are encrypted with AES-256-GCM using a key derived from the
 * venue's Ed25519 seed, so only the venue that encrypted them can decrypt.</p>
 *
 * <p>Each secret is stored as a map with {@code "encrypted"} (Blob) and
 * {@code "updated"} (CVMLong timestamp) fields. The {@code "updated"} field
 * enables LWW merge semantics.</p>
 *
 * <p>Follows the same lattice app wrapper pattern as {@link AssetStore}
 * and {@link JobStore}.</p>
 */
public class SecretStore extends ALatticeComponent<AMap<AString, ACell>> {

	private static final AString ENCRYPTED_KEY = Strings.intern("encrypted");
	private static final AString UPDATED_KEY = Strings.intern("updated");

	SecretStore(ALatticeCursor<AMap<AString, ACell>> cursor) {
		super(cursor);
	}

	/**
	 * Derives an AES-256 encryption key from the venue's Ed25519 seed.
	 *
	 * <p>Uses SHA-256 of the seed bytes, producing a 32-byte key suitable
	 * for AES-256-GCM. This binds secrets to the venue identity — only the
	 * venue that encrypted them can decrypt.</p>
	 *
	 * @param venueKeyPair Venue's key pair
	 * @return 32-byte AES-256 key
	 */
	public static byte[] deriveKey(AKeyPair venueKeyPair) {
		return Hashing.sha256(venueKeyPair.getSeed().getBytes()).getBytes();
	}

	/**
	 * Stores a secret, encrypting the plaintext with AES-256-GCM.
	 *
	 * @param name Secret name
	 * @param plaintext Secret value in plaintext
	 * @param encryptionKey 32-byte AES-256 key (from {@link #deriveKey})
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void store(String name, String plaintext, byte[] encryptionKey) {
		store(Strings.create(name), Strings.create(plaintext), encryptionKey);
	}

	public void store(AString name, AString plaintext, byte[] encryptionKey) {
		byte[] encrypted = AESGCM.encrypt(encryptionKey, plaintext.toString().getBytes(StandardCharsets.UTF_8));
		AMap<AString, ACell> record = Maps.of(
			ENCRYPTED_KEY, Blob.wrap(encrypted),
			UPDATED_KEY, CVMLong.create(System.currentTimeMillis())
		);
		cursor.updateAndGet(current -> {
			AMap m = RT.ensureMap(current);
			if (m == null) m = Maps.empty();
			return m.assoc(name, record);
		});
	}

	/**
	 * Decrypts and returns a secret value.
	 *
	 * @param name Secret name
	 * @param encryptionKey 32-byte AES-256 key (from {@link #deriveKey})
	 * @return Decrypted plaintext, or null if the secret does not exist
	 * @throws RuntimeException if decryption fails (wrong key or corrupted data)
	 */
	public AString decrypt(String name, byte[] encryptionKey) {
		return decrypt(Strings.create(name), encryptionKey);
	}

	public AString decrypt(AString name, byte[] encryptionKey) {
		AMap<AString, ACell> record = getRecord(name);
		if (record == null) return null;

		ACell enc = record.get(ENCRYPTED_KEY);
		if (!(enc instanceof Blob blob)) return null;

		byte[] decrypted = AESGCM.decrypt(encryptionKey, blob.getBytes());
		return Strings.create(new String(decrypted, StandardCharsets.UTF_8));
	}

	/**
	 * Checks whether a secret with the given name exists.
	 *
	 * @param name Secret name
	 * @return true if the secret exists
	 */
	public boolean exists(String name) {
		return exists(Strings.create(name));
	}

	public boolean exists(AString name) {
		return getRecord(name) != null;
	}

	/**
	 * Lists all secret names (not values).
	 *
	 * @return Vector of secret names, or empty vector if none
	 */
	public AVector<AString> list() {
		AMap<AString, ACell> all = cursor.get();
		if (all == null || all.isEmpty()) return Vectors.empty();
		return all.getKeys();

	}

	/**
	 * Deletes a secret by name.
	 *
	 * @param name Secret name to delete
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void delete(String name) {
		delete(Strings.create(name));
	}

	public void delete(AString name) {
		cursor.updateAndGet(current -> {
			AMap m = RT.ensureMap(current);
			if (m == null) return null;
			return m.dissoc(name);
		});
	}

	/**
	 * Gets the raw record for a secret (encrypted blob + updated timestamp).
	 */
	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> getRecord(AString name) {
		AMap<AString, ACell> all = cursor.get();
		if (all == null) return null;
		ACell value = all.get(name);
		return (value instanceof AMap) ? (AMap<AString, ACell>) value : null;
	}
}
