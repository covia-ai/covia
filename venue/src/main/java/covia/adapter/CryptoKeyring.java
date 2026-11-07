package covia.adapter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.BIP39;
import convex.core.crypto.SLIP10;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Principals;
import covia.venue.AdapterState;
import covia.venue.SecretStore;
import covia.venue.migration.MigrationStep;

/**
 * The crypto adapter's keyrings: the private keys and recovery phrases each
 * identity has the venue hold, sealed in the adapter's state at
 * {@code :adapters/crypto} (HELD_KEYS.md, "Store format"). Pure functions of
 * that state; who may use what is the adapter's concern.
 */
final class CryptoKeyring {

	private CryptoKeyring() {}

	/** The layout version this class reads and writes; also the state's {@code version}. */
	static final long LAYOUT_VERSION = 1;

	/** The HKDF label of the keyrings' sealing key, apart from the secret store's. */
	static final String SEALING_LABEL = "covia/crypto/keyring";

	public static final String UNKNOWN_STATE = "Crypto adapter state is in no layout this venue knows";
	public static final String RECORD_MISMATCH = "Held key record does not match where it is stored";

	private static final AString HOLDERS = Strings.intern("holders");
	private static final AString SEALED = Strings.intern("sealed");
	private static final AString HOLDER = Strings.intern("holder");
	private static final AString PUBLIC_KEY = Strings.intern("publicKey");
	private static final AString PRIVATE_KEY = Strings.intern("privateKey");
	private static final AString PHRASE = Strings.intern("mnemonic");
	private static final CVMLong LAYOUT = CVMLong.create(LAYOUT_VERSION);

	/** Multicodec prefix of an Ed25519 public key. */
	private static final byte[] ED25519_PUB = {(byte) 0xed, 0x01};

	/** What a keyring holds: keys, or the recovery phrases kept in place of secrets. */
	enum Kind {
		KEY(Fields.KEYS), MNEMONIC(Fields.MNEMONICS);
		final AString field;
		Kind(AString field) { this.field = field; }
	}

	/** The state's steps: step 0 takes version 0, before keyrings, to this layout. */
	static final List<MigrationStep> STATE_STEPS = List.of(new Keyrings());

	/**
	 * Version 1: keyrings. Version 0, the crypto adapter's state before
	 * keyrings, held nothing, so only an empty value converts; a value already
	 * in this layout comes back as it is, and anything else is refused.
	 */
	static final class Keyrings implements MigrationStep {
		@Override public ACell apply(ACell state) {
			if (state instanceof AMap<?, ?> map) {
				if (map.isEmpty()) return empty();
				if (LAYOUT.equals(map.get(Fields.VERSION)) && map.get(HOLDERS) instanceof Index<?, ?>) return state;
			}
			throw new IllegalStateException(UNKNOWN_STATE);
		}
	}

	/** The state at this layout, holding nothing. */
	static AMap<AString, ACell> empty() {
		return Maps.of(Fields.VERSION, LAYOUT, HOLDERS, Index.none());
	}

	/** The sealing keys for keyring records. */
	static SecretStore.Keys keys(AKeyPair venueKey) {
		return SecretStore.sealingKeys(venueKey, SEALING_LABEL);
	}

	// ========== Identifiers ==========

	/** A key's identifier: its multicodec-prefixed public key bytes. */
	static Blob id(AccountKey key) {
		byte[] bytes = new byte[ED25519_PUB.length + AccountKey.LENGTH];
		System.arraycopy(ED25519_PUB, 0, bytes, 0, ED25519_PUB.length);
		key.getBytes(bytes, ED25519_PUB.length);
		return Blob.wrap(bytes);
	}

	/** The Ed25519 account key an identifier names. */
	static AccountKey accountKey(Blob id) {
		return AccountKey.wrap(id.getBytes(), ED25519_PUB.length);
	}

	/** An identifier in multibase form, as a resource names it. */
	static AString multibase(Blob id) {
		return Multikey.encodePublicKey(accountKey(id));
	}

	/** The identifier of a recovery phrase: the public key of its SLIP-10 master node, with no passphrase. */
	static Blob mnemonicId(String phrase) {
		return id(SLIP10.deriveKeyPair(BIP39.getSeed(phrase, ""), new int[0]).getAccountKey());
	}

	// ========== Records ==========

	/** A record sealing {@code secret} (a private key's multikey, or a phrase) to its place. */
	static AMap<AString, ACell> record(SecretStore.Keys keys, AString holder, Kind kind, Blob id,
			AString secret, AString label, CVMLong addedAt) {
		AMap<AString, ACell> plain = Maps.of(HOLDER, holder, PUBLIC_KEY, multibase(id),
			kind == Kind.KEY ? PRIVATE_KEY : PHRASE, secret);
		AMap<AString, ACell> record = Maps.of(SEALED, SecretStore.seal(JSON.toAString(plain), keys), Fields.ADDED_AT, addedAt);
		return (label == null) ? record : record.assoc(Fields.LABEL, label);
	}

	/** A key record's key pair, for one use. */
	static AKeyPair openKey(SecretStore.Keys keys, AString holder, Blob id, AMap<AString, ACell> record) {
		AString secret = open(keys, holder, Kind.KEY, id, record);
		AKeyPair pair = AKeyPair.create(Multikey.decodeSecretKey(secret));
		if (!pair.getAccountKey().equals(accountKey(id))) throw new IllegalStateException(RECORD_MISMATCH);
		return pair;
	}

	/** A mnemonic record's phrase. */
	static String openMnemonic(SecretStore.Keys keys, AString holder, Blob id, AMap<AString, ACell> record) {
		String phrase = open(keys, holder, Kind.MNEMONIC, id, record).toString();
		if (!mnemonicId(phrase).equals(id)) throw new IllegalStateException(RECORD_MISMATCH);
		return phrase;
	}

	/** Opens a record, checking it names the place it is stored. */
	private static AString open(SecretStore.Keys keys, AString holder, Kind kind, Blob id, AMap<AString, ACell> record) {
		AString json = SecretStore.unseal(record.get(SEALED), keys);
		AMap<AString, ACell> plain = (json == null) ? null : RT.ensureMap(JSON.parse(json));
		if (plain == null || !holder.equals(plain.get(HOLDER)) || !multibase(id).equals(plain.get(PUBLIC_KEY))) {
			throw new IllegalStateException(RECORD_MISMATCH);
		}
		AString secret = RT.ensureString(plain.get(kind == Kind.KEY ? PRIVATE_KEY : PHRASE));
		if (secret == null) throw new IllegalStateException(RECORD_MISMATCH);
		return secret;
	}

	// ========== State ==========

	/** One record, or null. */
	static AMap<AString, ACell> get(ACell state, AString holder, Kind kind, Blob id) {
		return RT.ensureMap(entries(state, holder, kind).get(id));
	}

	/** A holder's records of one kind, ordered by identifier. */
	@SuppressWarnings("unchecked")
	static Index<Blob, ACell> entries(ACell state, AString holder, Kind kind) {
		ACell ring = holders(state).get(holder);
		ACell entries = RT.getIn(ring, kind.field);
		return (entries instanceof Index<?, ?> index) ? (Index<Blob, ACell>) index : Index.none();
	}

	/**
	 * Stores a record. One already at its place is kept unless
	 * {@code relabel}, which keeps its key and gives it the new record's label.
	 *
	 * @return whether this wrote the record
	 */
	static boolean put(AdapterState state, AString holder, Kind kind, Blob id, AMap<AString, ACell> record,
			boolean relabel) {
		AtomicBoolean wrote = new AtomicBoolean();
		state.update(current -> {
			AMap<AString, ACell> existing = get(current, holder, kind, id);
			AMap<AString, ACell> stored;
			if (existing == null) stored = record;
			else if (!relabel) { wrote.set(false); return current; }
			else {
				AString label = RT.ensureString(record.get(Fields.LABEL));
				stored = (label == null) ? existing.dissoc(Fields.LABEL) : existing.assoc(Fields.LABEL, label);
			}
			wrote.set(true);
			return withEntries(current, holder, kind, entries(current, holder, kind).assoc(id, stored));
		});
		return wrote.get();
	}

	/** Removes a record; whether there was one. */
	static boolean remove(AdapterState state, AString holder, Kind kind, Blob id) {
		AtomicBoolean removed = new AtomicBoolean();
		state.update(current -> {
			Index<Blob, ACell> entries = entries(current, holder, kind);
			removed.set(entries.containsKey(id));
			return removed.get() ? withEntries(current, holder, kind, entries.dissoc(id)) : current;
		});
		return removed.get();
	}

	/**
	 * Removes a deleted user's keyring and its agents' ({@code <did>:g:<id>}),
	 * which go with the user. Another user nested below it ({@code <did>:u:bob})
	 * is a user of its own, deleted on its own.
	 */
	static void deleteHolder(AdapterState state, AString did) {
		state.update(current -> {
			Index<AString, ACell> holders = holders(current);
			AtomicReference<Index<AString, ACell>> rest = new AtomicReference<>(holders);
			holders.forEach((holder, ring) -> {
				boolean agent = Principals.isAgentDID(holder) && did.equals(Principals.userOf(holder));
				if (holder.equals(did) || agent) rest.set(rest.get().dissoc(holder));
			});
			return (rest.get() == holders) ? current : layout(current).assoc(HOLDERS, rest.get());
		});
	}

	@SuppressWarnings("unchecked")
	private static Index<AString, ACell> holders(ACell state) {
		ACell holders = RT.getIn(state, HOLDERS);
		return (holders instanceof Index<?, ?> index) ? (Index<AString, ACell>) index : Index.none();
	}

	/** The state in this layout: as it is, or empty when nothing is stored yet. */
	private static AMap<AString, ACell> layout(ACell state) {
		AMap<AString, ACell> map = RT.ensureMap(state);
		return (map == null || map.isEmpty()) ? empty() : map;
	}

	private static ACell withEntries(ACell state, AString holder, Kind kind, Index<Blob, ACell> entries) {
		Index<AString, ACell> holders = holders(state);
		AMap<AString, ACell> ring = RT.ensureMap(holders.get(holder));
		if (ring == null) ring = Maps.empty();
		ring = entries.isEmpty() ? ring.dissoc(kind.field) : ring.assoc(kind.field, entries);
		holders = ring.isEmpty() ? holders.dissoc(holder) : holders.assoc(holder, ring);
		return layout(state).assoc(HOLDERS, holders);
	}
}
