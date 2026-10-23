package covia.venue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

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
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.AForkableCursor;

/**
 * Per-user encrypted secret storage.
 *
 * <p>Wraps a lattice cursor at the user's {@code /s/} namespace path.
 * Secrets are encrypted with AES-256-GCM under a key derived from the
 * venue's Ed25519 seed, so only the venue that encrypted them can decrypt.</p>
 *
 * <p>Each secret is stored as a map with {@code "encrypted"} (Blob),
 * {@code "updated"} (CVMLong, last-modified millis from the lattice write
 * clock) and {@code "keyVersion"} (CVMLong) fields. The key version names the
 * derivation that encrypted the blob (see {@link Keys}); a record without one
	 * predates versioning and is version 1. It does not identify a particular
	 * venue seed or rotated key generation; {@link Keys} derives both versions
	 * from one seed. Every write encrypts under
 * {@link #CURRENT_KEY_VERSION}, so an older secret migrates on its next
 * write.</p>
 *
 * <p>Follows the same lattice app wrapper pattern as {@link AssetStore}.</p>
 */
public class SecretStore extends ALatticeComponent<AMap<AString, ACell>> {
	private final LatticeContext context;

	public LatticeContext getContext() { return context; }

	/** Key version 1: SHA-256 of the venue seed. Read only; nothing encrypts under it. */
	public static final int KEY_VERSION_1 = 1;

	/** Key version 2: HKDF-SHA256 of the venue seed under {@link #KEY_INFO_V2}. */
	public static final int KEY_VERSION_2 = 2;

	/** The key version every write encrypts under. */
	public static final int CURRENT_KEY_VERSION = KEY_VERSION_2;

	/** HKDF info label of the version 2 key, separating it from every other use of the seed. */
	public static final String KEY_INFO_V2 = "covia/secret-store/v2";

	/** Refusal for a record whose key version this venue cannot derive. */
	public static final String UNKNOWN_KEY_VERSION = "Secret is encrypted under an unknown key version";
	public static final String INVALID_RECORD = "Secret store entry in wrong format";

	/** Refusal for the deprecated single-key API meeting a record it cannot read. */
	public static final String LEGACY_KEY_READS_V1_ONLY =
		"A key from SecretStore.deriveKey reads key version 1 secrets only; use SecretStore.keys";

	private static final AString ENCRYPTED_KEY = Strings.intern("encrypted");
	private static final AString UPDATED_KEY = Strings.intern("updated");
	private static final AString KEY_VERSION_KEY = Strings.intern("keyVersion");
	private static final CVMLong CURRENT_KEY_VERSION_CELL = CVMLong.create(CURRENT_KEY_VERSION);

	SecretStore(User parent,
			AForkableCursor<AMap<AString, ACell>> cursor) {
		super(cursor);
		this.context = parent.getContext();
	}

	/**
	 * The secret store keys of one venue: a key for every version it can
	 * decrypt, each derived from its Ed25519 seed. Holds no seed, and its key
	 * bytes never leave this package; obtain one with {@link SecretStore#keys(AKeyPair)}.
	 */
	public static final class Keys {
		private final byte[] v1;
		private final byte[] v2;

		private Keys(byte[] seed) {
			this.v1 = sha256Key(seed);
			this.v2 = HKDF.derive256(seed, null, KEY_INFO_V2.getBytes(StandardCharsets.UTF_8));
		}

		/** The key of one version, or null when there is no such version. */
		byte[] forVersion(long version) {
			if (version == KEY_VERSION_1) return v1;
			if (version == KEY_VERSION_2) return v2;
			return null;
		}

		@Override
		public String toString() {
			return "SecretStore.Keys[redacted]";
		}
	}

	/**
	 * Derives the secret store keys from the venue's Ed25519 seed.
	 *
	 * <p>Version 2, the one every write uses, is HKDF-SHA256 (RFC 5869) with
	 * the seed as input keying material, no salt and the info label
	 * {@link #KEY_INFO_V2}: domain separated, so no other use of the seed can
	 * produce it. Version 1, SHA-256 of the seed, still decrypts secrets
	 * written before key versions existed.</p>
	 *
	 * @param venueKeyPair Venue's key pair
	 * @return The venue's secret store keys
	 */
	public static Keys keys(AKeyPair venueKeyPair) {
		return new Keys(venueKeyPair.getSeed().getBytes());
	}

	/**
	 * Derives the key version 1 secret key: SHA-256 of the venue's Ed25519 seed.
	 *
	 * <p>It decrypts only secrets with no key version, through
	 * {@link #decrypt(AString, byte[])}; nothing encrypts under it any more.</p>
	 *
	 * @param venueKeyPair Venue's key pair
	 * @return 32-byte AES-256 key of key version 1
	 * @deprecated Use {@link #keys(AKeyPair)} inside the venue, or the
	 * {@code secret} operations from outside it.
	 */
	@Deprecated(since = "0.9.12")
	public static byte[] deriveKey(AKeyPair venueKeyPair) {
		return sha256Key(venueKeyPair.getSeed().getBytes());
	}

	private static byte[] sha256Key(byte[] seed) {
		return Hashing.sha256(seed).getBytes();
	}

	/**
	 * Stores a secret, encrypting the plaintext with AES-256-GCM under the
	 * current key version.
	 *
	 * @param name Secret name
	 * @param plaintext Secret value in plaintext
	 * @param keys The venue's secret store keys (from {@link #keys})
	 */
	public void store(String name, String plaintext, Keys keys) {
		store(Strings.create(name), Strings.create(plaintext), keys);
	}

	public void store(AString name, AString plaintext, Keys keys) {
		Blob encryptedBlob = encrypt(plaintext, keys);
		CVMLong timestamp = getContext().currentTimestamp();
		cursor().updateAndGet(current -> {
			AMap<AString, ACell> m = RT.castMap(current);
			if (m == null) m = Maps.empty();
			CVMLong effective = timestamp;
			ACell existing = m.get(name);
			if (existing instanceof AMap<?, ?> oldRecord
					&& oldRecord.get(UPDATED_KEY) instanceof CVMLong old
					&& old.longValue() > effective.longValue()) effective = old;
			return m.assoc(name, record(encryptedBlob, effective));
		});
	}

	/**
	 * Atomically stores a secret only if no entry with this name exists.
	 *
	 * <p>Uses cursor CAS — concurrent callers see a consistent winner. Does NOT
	 * call {@code sync()} on the cursor; propagation is the caller's concern.</p>
	 *
	 * @param name Secret name
	 * @param plaintext Secret value in plaintext
	 * @param keys The venue's secret store keys (from {@link #keys})
	 * @return true if this call wrote the entry; false if an entry already existed
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public boolean storeIfAbsent(AString name, AString plaintext, Keys keys) {
		AMap<AString, ACell> record = record(encrypt(plaintext, keys),
			getContext().currentTimestamp());
		AtomicBoolean wrote = new AtomicBoolean(false);
		cursor().updateAndGet(current -> {
			AMap m = RT.castMap(current);
			if (m == null) m = Maps.empty();
			if (m.containsKey(name)) {
				wrote.set(false);
				return m;
			}
			wrote.set(true);
			return m.assoc(name, record);
		});
		return wrote.get();
	}

	private static Blob encrypt(AString plaintext, Keys keys) {
		byte[] bytes = plaintext.toString().getBytes(StandardCharsets.UTF_8);
		return Blob.wrap(AESGCM.encrypt(keys.forVersion(CURRENT_KEY_VERSION), bytes));
	}

	private static AMap<AString, ACell> record(Blob encrypted, CVMLong updated) {
		return Maps.of(
			ENCRYPTED_KEY, encrypted,
			UPDATED_KEY, updated,
			KEY_VERSION_KEY, CURRENT_KEY_VERSION_CELL);
	}

	/**
	 * Decrypts and returns a secret value, under the key of the version that
	 * encrypted it.
	 *
	 * @param name Secret name
	 * @param keys The venue's secret store keys (from {@link #keys})
	 * @return Decrypted plaintext, or null if the secret does not exist
	 * @throws IllegalStateException with {@link #UNKNOWN_KEY_VERSION} for a key
	 * version this venue cannot derive
	 * @throws RuntimeException if decryption fails (wrong key or corrupted data)
	 */
	public AString decrypt(String name, Keys keys) {
		return decrypt(Strings.create(name), keys);
	}

	public AString decrypt(AString name, Keys keys) {
		// One read: the blob and its version must come from the same record.
		AMap<AString, ACell> record = getRecord(name);
		Blob blob = encrypted(record);
		if (blob == null) return null;
		byte[] key = keys.forVersion(keyVersion(record));
		if (key == null) throw new IllegalStateException(UNKNOWN_KEY_VERSION);
		return plaintext(AESGCM.decrypt(key, blob.getBytes()));
	}

	/**
	 * Decrypts a key version 1 secret with a key from {@link #deriveKey}.
	 *
	 * @param name Secret name
	 * @param encryptionKey 32-byte AES-256 key of key version 1
	 * @return Decrypted plaintext, or null if the secret does not exist
	 * @throws IllegalStateException with {@link #LEGACY_KEY_READS_V1_ONLY} for
	 * a secret under any other key version
	 * @deprecated Use {@link #decrypt(String, Keys)}, which reads every key version.
	 */
	@Deprecated(since = "0.9.12")
	public AString decrypt(String name, byte[] encryptionKey) {
		return decrypt(Strings.create(name), encryptionKey);
	}

	/**
	 * @deprecated Use {@link #decrypt(AString, Keys)}, which reads every key version.
	 */
	@Deprecated(since = "0.9.12")
	public AString decrypt(AString name, byte[] encryptionKey) {
		AMap<AString, ACell> record = getRecord(name);
		Blob blob = encrypted(record);
		if (blob == null) return null;
		if (keyVersion(record) != KEY_VERSION_1) {
			throw new IllegalStateException(LEGACY_KEY_READS_V1_ONLY);
		}
		return plaintext(AESGCM.decrypt(encryptionKey, blob.getBytes()));
	}

	/** The ciphertext of a secret record, or null when there is none. */
	private static Blob encrypted(AMap<AString, ACell> record) {
		if (record == null) return null;
		if (record.get(ENCRYPTED_KEY) instanceof Blob blob) return blob;
		throw new IllegalStateException(INVALID_RECORD);
	}

	/** A record's key version: absent predates versioning (version 1); malformed is -1, which no key matches. */
	private static long keyVersion(AMap<AString, ACell> record) {
		ACell version = record.get(KEY_VERSION_KEY);
		if (version == null) return KEY_VERSION_1;
		return (version instanceof CVMLong v) ? v.longValue() : -1;
	}

	private static AString plaintext(byte[] decrypted) {
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
		AMap<AString, ACell> all = cursor().get();
		if (all == null || all.isEmpty()) return Vectors.empty();
		return all.getKeys();

	}

	/**
	 * Deletes a secret by name.
	 *
	 * @param name Secret name to delete
	 */
	public void delete(String name) {
		delete(Strings.create(name));
	}

	public void delete(AString name) {
		deleteIfPresent(name);
	}

	/** Atomically removes an entry and reports whether it existed. */
	public boolean deleteIfPresent(AString name) {
		AMap<AString, ACell> previous = cursor().getAndUpdate(current -> {
			AMap<AString,ACell> m = RT.ensureMap(current);
			if (m == null) return null;
			return m.dissoc(name);
		});
		return previous != null && previous.containsKey(name);
	}

	/**
	 * Gets the raw record for a secret (encrypted blob, updated timestamp, key version).
	 */
	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> getRecord(AString name) {
		AMap<AString, ACell> all = cursor().get();
		if (all == null) return null;
		ACell value = all.get(name);
		if (!(value instanceof AMap)) {
			if (!all.containsKey(name)) return null;
			throw new IllegalStateException(INVALID_RECORD);
		}
		return (AMap<AString, ACell>) value;
	}
}
