package covia.adapter;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import convex.auth.jwt.JWT;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.BIP39;
import convex.core.crypto.ECIES;
import convex.core.crypto.Hashing;
import convex.core.crypto.SLIP10;
import convex.core.crypto.util.Multikey;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.data.prim.ANumeric;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.ConflictException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.exception.NotSupportedException;
import covia.exception.UnavailableException;
import covia.venue.AdapterState;
import covia.venue.Audit;
import covia.venue.SecretStore;
import covia.venue.Engine;
import covia.venue.RequestContext;

/** Local cryptographic operations using Convex's wire formats and primitives.
 * Private seeds, mnemonics and passphrases are resolved only from secrets;
 * generated private material is stored without passing through a Job result. */
public class CryptoAdapter extends AAdapter {

	/** Convex's official SLIP-44 coin type is 864. This is a default only;
	 * callers may supply any valid hardened SLIP-10 path. */
	public static final AString DEFAULT_PATH = Strings.intern("m/44/864/0/0/0");
	public static final String INVALID_INPUT = "Expected an input object";
	public static final String REQUIRED_STRING = " must be a string";
	public static final String INVALID_ENCODING = "encoding must be utf8, hex or base64";
	public static final String INVALID_OUTPUT_ENCODING = "encoding must be blob, utf8, hex or base64";
	public static final String INVALID_BYTES = "Invalid byte encoding";
	public static final String TOO_LARGE = "Crypto input exceeds 1 MiB";
	public static final String INVALID_KEY = "Expected a 32-byte Ed25519 seed or account key";
	public static final String INVALID_SIGNATURE = "Expected a 64-byte Ed25519 signature";
	public static final String SECRET_REF_REQUIRED = "Expected an s/<name> or <did>/s/<name> secret reference";
	public static final String SECRET_MISSING = "Referenced crypto secret is not available";
	public static final String OWN_SECRETS_ONLY = "derive-key reads only the caller's own secrets: it stores the derived key where the caller can extract it";
	public static final String INVALID_WORDS = "words must be one of 12, 15, 18, 21 or 24";
	public static final String INVALID_MNEMONIC = "Invalid English BIP39 mnemonic: check word count, words and checksum";
	public static final String INVALID_PATH = "Expected m or m/<index>/... with at most 255 indexes in 0..2147483647; apostrophes are optional, all indexes are hardened";
	public static final String INVALID_OVERWRITE = "overwrite must be a boolean";
	public static final String INVALID_ALGORITHM = "algorithm must be sha256, sha512, sha3-256 or keccak256";
	public static final String DECRYPT_FAILED = "ECIES envelope is invalid or failed authentication";
	public static final String INVALID_UTF8 = "Decrypted bytes are not valid UTF-8; use hex or base64";
	public static final String UNKNOWN_OPERATION = "Unknown crypto operation";
	public static final String INVALID_JWT = "Expected a compact JWT with base64url header, claims and signature";
	public static final String INVALID_JWT_SOURCE = "Supply exactly one of token or tokenSecret";
	public static final String INVALID_CLAIMS = "claims must be an object with string keys";
	public static final String INVALID_REQUIRE_EXPIRATION = "requireExpiration must be a boolean";
	public static final String JWT_ALGORITHM = "JWT algorithm must be EdDSA (Ed25519)";
	public static final String JWT_EXTENSIONS = "JWT critical or unencoded-payload extensions are not supported";
	public static final String JWT_SIGNATURE = "JWT signature does not match the trusted accountKey";
	public static final String JWT_DATE = "JWT exp, nbf and iat must be finite NumericDates when present";
	public static final String JWT_STRING_CLAIM = "JWT iss, sub and jti must be strings when present";
	public static final String JWT_AUDIENCE_FORMAT = "JWT aud must be a string or array of strings when present";
	public static final String JWT_EXPIRATION_REQUIRED = "JWT exp is required";
	public static final String JWT_EXPIRED = "JWT has expired";
	public static final String JWT_NOT_YET_VALID = "JWT is not yet valid";
	public static final String JWT_ISSUER = "JWT issuer does not match expectedIssuer";
	public static final String JWT_AUDIENCE = "JWT audience does not match expectedAudience";
	public static final String KEY_OR_SEED = "Give either seed or key, not both";
	public static final String SEED_OR_MNEMONIC = "Give exactly one of seed or mnemonic";
	public static final String KEY_OR_MNEMONIC = "Give exactly one of key or mnemonic";
	public static final String INVALID_HELD_KEY = "Expected a held key: a did:key, a multibase public key, a 32-byte account key, or <did>/x/crypto/keys/<key>";
	public static final String INVALID_HELD_MNEMONIC = "Expected a held mnemonic: its ID as a did:key, multibase key or 32-byte account key, or <did>/x/crypto/mnemonics/<id>";
	public static final String KEY_NOT_HELD = "No such key is held in that keyring";
	public static final String MNEMONIC_NOT_HELD = "No such mnemonic is held in that keyring";
	public static final String NO_KEYRING = "Only an authenticated identity has a keyring";
	public static final String KEY_ALREADY_HELD = "That key or mnemonic is already held; set overwrite to replace its label";
	public static final String INVALID_KEYRING = "keyring must be a boolean";
	public static final String KEYRING_OR_NAME = "Give either name or keyring: true, not both";
	public static final String INVALID_LABEL = "label must be a string of at most 256 characters";
	public static final String ADD_OWN_SECRETS_ONLY = "add-key reads only the caller's own secrets: a lent secret is use, not possession";
	public static final String DERIVED_TO_KEYRING = "A key derived from a held mnemonic goes only into a keyring: set keyring: true";
	public static final String CRYPTO_UNAVAILABLE = "Held keys need the crypto adapter, which is not enabled";

	private static final int MAX_BYTES = 1024 * 1024;
	private static final AString DATA = Strings.intern("data");
	private static final AString ENCODING = Strings.intern("encoding");
	private static final AString ACCOUNT_KEY = Strings.intern("accountKey");
	private static final AString SECRET = Strings.intern("secret");
	private static final AString SIGNATURE = Strings.intern("signature");
	private static final AString VALID = Strings.intern("valid");
	private static final AString PATH = Strings.intern("path");
	private static final AString WORDS = Strings.intern("words");
	private static final AString NAME = Strings.intern("name");
	private static final AString ALGORITHM = Strings.intern("algorithm");
	private static final AString DIGEST = Strings.intern("digest");
	private static final AString DID = Strings.intern("did");
	private static final AString CIPHERTEXT = Strings.intern("ciphertext");
	private static final AString SEED = Strings.intern("seed");
	private static final AString MNEMONIC = Strings.intern("mnemonic");
	private static final AString PASSPHRASE_SECRET = Strings.intern("passphraseSecret");
	private static final AString OVERWRITE = Strings.intern("overwrite");
	private static final AString HEX = Strings.intern("hex");
	private static final AString UTF8 = Strings.intern("utf8");
	private static final AString BASE64 = Strings.intern("base64");
	private static final AString BLOB = Strings.intern("blob");
	private static final AString SHA256 = Strings.intern("sha256");
	private static final AString SHA512 = Strings.intern("sha512");
	private static final AString SHA3_256 = Strings.intern("sha3-256");
	private static final AString KECCAK256 = Strings.intern("keccak256");
	private static final AString SECRET_PREFIX = Strings.intern("s/");
	private static final AString TOKEN = Strings.intern("token");
	private static final AString TOKEN_SECRET = Strings.intern("tokenSecret");
	private static final AString CLAIMS = Strings.intern("claims");
	private static final AString HEADER = Strings.intern("header");
	private static final AString VERIFIED = Strings.intern("verified");
	private static final AString REASON = Strings.intern("reason");
	private static final AString EXPECTED_ISSUER = Strings.intern("expectedIssuer");
	private static final AString EXPECTED_AUDIENCE = Strings.intern("expectedAudience");
	private static final AString REQUIRE_EXPIRATION = Strings.intern("requireExpiration");
	private static final AString EDDSA = Strings.intern("EdDSA");
	private static final AString CRIT = Strings.intern("crit");
	private static final AString B64 = Strings.intern("b64");

	@Override public String getName() { return "crypto"; }
	@Override public String getDescription() {
		return "Hash bytes, sign and verify Ed25519 and JWTs, encrypt and decrypt Convex ECIES, and generate or derive secret-backed BIP39 keys";
	}

	/** Keyrings are this adapter's state, versioned by {@link CryptoKeyring#STATE_STEPS}. */
	@Override public java.util.List<covia.venue.migration.MigrationStep> stateSteps() {
		return CryptoKeyring.STATE_STEPS;
	}

	/** A deleted user's keyring goes with it, and those of the identities nested below it. */
	@Override public void deleteUserState(AString did, AdapterState state) {
		CryptoKeyring.deleteHolder(state, did);
	}

	@Override protected void installAssets() {
		for (String op : new String[] {"hash", "generate-key", "public-key", "sign", "verify",
				"encrypt", "decrypt", "generate-mnemonic", "validate-mnemonic", "derive-key",
				"jwt-sign", "jwt-verify", "jwt-decode", "add-key", "remove-key", "list-keys"}) {
			installAsset("crypto/" + op, "/adapters/crypto/" + op + ".json");
		}
		installSkill("crypto/crypto", "/skills/crypto.json");
	}

	@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx,
			AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		return CompletableFuture.supplyAsync(() -> {
			if (!(input instanceof AMap<?, ?>)) throw new InvalidInputException(INVALID_INPUT);
			return switch (getSubOperation(meta)) {
				case "hash" -> hash(input);
				case "generate-key" -> storeKey(ctx, input, AKeyPair.generate());
				case "public-key" -> publicKey(keyPair(ctx, input, null));
				case "sign" -> sign(ctx, input);
				case "verify" -> verify(input);
				case "encrypt" -> encrypt(input);
				case "decrypt" -> decrypt(ctx, input);
				case "generate-mnemonic" -> generateMnemonic(ctx, input);
				case "validate-mnemonic" -> Maps.of(VALID, validateMnemonic(ctx, input));
				case "add-key" -> addKey(ctx, input);
				case "remove-key" -> removeKey(ctx, input);
				case "list-keys" -> listKeys(ctx, input);
				case "derive-key" -> deriveKey(ctx, input);
				case "jwt-sign" -> jwtSign(ctx, input);
				case "jwt-verify" -> jwtVerify(ctx, input);
				case "jwt-decode" -> jwtDecode(ctx, input);
				default -> throw new NotSupportedException(UNKNOWN_OPERATION);
			};
		}, VIRTUAL_EXECUTOR);
	}

	private ACell hash(ACell input) {
		byte[] bytes = data(input).getBytes();
		AString algorithm = text(input, ALGORITHM, SHA3_256);
		ABlob digest;
		if (SHA256.equals(algorithm)) digest = Hashing.sha256(bytes);
		else if (SHA3_256.equals(algorithm)) digest = Hashing.sha3(bytes);
		else if (KECCAK256.equals(algorithm)) digest = Hashing.keccak256(bytes);
		else if (SHA512.equals(algorithm)) {
			try { digest = Blob.wrap(MessageDigest.getInstance("SHA-512").digest(bytes)); }
			catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
		} else throw new InvalidInputException(INVALID_ALGORITHM);
		return Maps.of(ALGORITHM, algorithm, DIGEST, digest);
	}

	private AMap<AString, ACell> publicKey(AKeyPair key) {
		return Maps.of(ACCOUNT_KEY, key.getAccountKey(), DID, UCAN.toDIDKey(key.getAccountKey()));
	}

	/** A new key into a secret ({@code name}), or into the caller's keyring ({@code keyring: true}). */
	private AMap<AString, ACell> storeKey(RequestContext ctx, ACell input, AKeyPair key) {
		if (toKeyring(input)) return holdKey(ctx, ownHolder(ctx), key, input, Abilities.KEY_ADD);
		return publicKey(key).assoc(SECRET, store(ctx, input, Strings.create(key.getSeed().toHexString())));
	}

	private ACell sign(RequestContext ctx, ACell input) {
		Blob bytes = data(input);
		AKeyPair key = keyPair(ctx, input, Abilities.KEY_SIGN_RAW);
		return publicKey(key).assoc(SIGNATURE, key.sign(bytes));
	}

	private ACell verify(ACell input) {
		Blob signature = blob(RT.getIn(input, SIGNATURE), MAX_BYTES);
		if (signature.count() != 64) throw new InvalidInputException(INVALID_SIGNATURE);
		boolean valid = ASignature.fromBlob(signature).verify(data(input), accountKey(input));
		return Maps.of(VALID, valid);
	}

	private ACell encrypt(ACell input) {
		Blob envelope = ECIES.encrypt(accountKey(input), data(input));
		return Maps.of(CIPHERTEXT, envelope, ENCODING, BLOB);
	}

	private ACell jwtSign(RequestContext ctx, ACell input) {
		if (!(RT.getIn(input, CLAIMS) instanceof AMap<?, ?> supplied)) throw new InvalidInputException(INVALID_CLAIMS);
		for (ACell key : supplied.getKeys()) {
			if (!(key instanceof AString)) throw new InvalidInputException(INVALID_CLAIMS);
		}
		AString json = JSON.toAString(supplied);
		if (json.count() > MAX_BYTES) throw new InvalidInputException(TOO_LARGE);
		// Use the JSON projection consistently, including for native blob claim values.
		AMap<AString, ACell> claims = RT.castMap(JSON.parse(json));
		String reason = jwtClaimShape(claims);
		if (reason != null) throw new InvalidInputException(reason);
		AString kid = optionalText(input, JWT.KID);
		if (kid != null && kid.count() > MAX_BYTES) throw new InvalidInputException(TOO_LARGE);
		boolean storeOutput = ((AMap<?, ?>) input).containsKey(NAME);
		if (storeOutput) text(input, NAME, null);
		AKeyPair key = keyPair(ctx, input, Abilities.KEY_SIGN_RAW);
		AString token = kid == null ? JWT.signPublic(claims, key) : JWT.signPublic(claims, key, kid);
		if (token.count() > MAX_BYTES) throw new InvalidInputException(TOO_LARGE);
		return storeOutput ? publicKey(key).assoc(SECRET, store(ctx, input, token))
			: publicKey(key).assoc(TOKEN, token);
	}

	private ACell jwtDecode(RequestContext ctx, ACell input) {
		JWT jwt = JWT.parse(jwtToken(ctx, input));
		if (jwt == null) throw new InvalidInputException(INVALID_JWT);
		// Decoding proves nothing about the signature, claims, or signing authority.
		AMap<AString, ACell> decoded = Maps.of(VERIFIED, false, HEADER, jwt.getHeader(), CLAIMS, jwt.getClaims());
		// A stored token's signature is its bearer credential: the token could be
		// rebuilt from it, so a use of the secret never returns it.
		if (((AMap<?, ?>) input).containsKey(TOKEN_SECRET)) return decoded;
		return decoded.assoc(SIGNATURE, Blob.wrap(jwt.getSignatureBytes()));
	}

	private ACell jwtVerify(RequestContext ctx, ACell input) {
		AccountKey trustedKey = accountKey(input);
		AString issuer = optionalText(input, EXPECTED_ISSUER);
		AString audience = optionalText(input, EXPECTED_AUDIENCE);
		ACell required = RT.getIn(input, REQUIRE_EXPIRATION);
		if (((AMap<?, ?>) input).containsKey(REQUIRE_EXPIRATION) && !(required instanceof CVMBool)) {
			throw new InvalidInputException(INVALID_REQUIRE_EXPIRATION);
		}
		JWT jwt = JWT.parse(jwtToken(ctx, input));
		if (jwt == null) throw new InvalidInputException(INVALID_JWT);
		String reason = jwtVerificationReason(jwt, trustedKey, issuer, audience, CVMBool.TRUE.equals(required));
		// Only successful verification releases claims. Reasons never echo token data.
		if (reason != null) return Maps.of(VALID, false, REASON, reason);
		return Maps.of(VALID, true, HEADER, jwt.getHeader(), CLAIMS, jwt.getClaims());
	}

	private AString jwtToken(RequestContext ctx, ACell input) {
		AMap<?, ?> map = (AMap<?, ?>) input;
		if (map.containsKey(TOKEN) == map.containsKey(TOKEN_SECRET)) throw new InvalidInputException(INVALID_JWT_SOURCE);
		AString token = map.containsKey(TOKEN_SECRET) ? secret(ctx, input, TOKEN_SECRET) : text(input, TOKEN, null);
		if (token.count() > MAX_BYTES) throw new InvalidInputException(TOO_LARGE);
		// Compact JWS uses unpadded base64url; reject extra segments and whitespace.
		// Empty signatures may be decoded, but never pass Ed25519 verification.
		if (!token.toString().matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*")) {
			throw new InvalidInputException(INVALID_JWT);
		}
		return token;
	}

	private static String jwtVerificationReason(JWT jwt, AccountKey trustedKey,
			AString issuer, AString audience, boolean requireExpiration) {
		AMap<AString, ACell> header = jwt.getHeader();
		if (!EDDSA.equals(header.get(JWT.ALG))) return JWT_ALGORITHM;
		if (header.containsKey(CRIT) || header.containsKey(B64)) return JWT_EXTENSIONS;
		// No key discovery from untrusted kid, jwk, jku or x5u headers.
		if (!jwt.verifyEdDSA(trustedKey)) return JWT_SIGNATURE;
		AMap<AString, ACell> claims = jwt.getClaims();
		String reason = jwtClaimShape(claims);
		if (reason != null) return reason;
		if (requireExpiration && !claims.containsKey(JWT.EXP)) return JWT_EXPIRATION_REQUIRED;
		double now = System.currentTimeMillis() / 1000.0;
		if (claims.get(JWT.EXP) instanceof ANumeric exp && now >= exp.doubleValue()) return JWT_EXPIRED;
		if (claims.get(JWT.NBF) instanceof ANumeric nbf && now < nbf.doubleValue()) return JWT_NOT_YET_VALID;
		if (issuer != null && !issuer.equals(claims.get(JWT.ISS))) return JWT_ISSUER;
		if (audience != null) {
			ACell aud = claims.get(JWT.AUD);
			boolean matches = audience.equals(aud);
			if (aud instanceof AVector<?> audiences) {
				for (ACell candidate : audiences) matches |= audience.equals(candidate);
			}
			if (!matches) return JWT_AUDIENCE;
		}
		return null;
	}

	private static String jwtClaimShape(AMap<AString, ACell> claims) {
		for (AString field : new AString[] {JWT.EXP, JWT.NBF, JWT.IAT}) {
			if (claims.containsKey(field) && (!(claims.get(field) instanceof ANumeric n) || !Double.isFinite(n.doubleValue()))) return JWT_DATE;
		}
		for (AString field : new AString[] {JWT.ISS, JWT.SUB, JWT.JTI}) {
			if (claims.containsKey(field) && !(claims.get(field) instanceof AString)) return JWT_STRING_CLAIM;
		}
		if (claims.containsKey(JWT.AUD)) {
			ACell aud = claims.get(JWT.AUD);
			if (aud instanceof AVector<?> audiences) {
				for (ACell candidate : audiences) if (!(candidate instanceof AString)) return JWT_AUDIENCE_FORMAT;
			} else if (!(aud instanceof AString)) return JWT_AUDIENCE_FORMAT;
		}
		return null;
	}

	private static AString optionalText(ACell input, AString field) {
		return ((AMap<?, ?>) input).containsKey(field) ? text(input, field, null) : null;
	}

	private ACell decrypt(RequestContext ctx, ACell input) {
		boolean storeOutput = ((AMap<?, ?>) input).containsKey(NAME);
		// A supplied but invalid destination must not silently fall back to
		// returning plaintext (in particular, name:null).
		if (storeOutput) text(input, NAME, null);
		AString encoding = text(input, ENCODING, BLOB);
		if (!BLOB.equals(encoding) && !isTextEncoding(encoding)) throw new InvalidInputException(INVALID_OUTPUT_ENCODING);
		Blob envelope = blob(RT.getIn(input, CIPHERTEXT), MAX_BYTES + ECIES.OVERHEAD);
		Blob plaintext;
		try { plaintext = ECIES.decrypt(keyPair(ctx, input, Abilities.KEY_DECRYPT), envelope); }
		catch (BadFormatException e) {
			// No provider exception or decrypted material enters the Job error.
			throw new InvalidInputException(DECRYPT_FAILED);
		}
		if (storeOutput) {
			// SecretStore is currently string-valued. Its binary representation
			// remains hex, compatible with existing secret-backed operations.
			AString storedEncoding = BLOB.equals(encoding) ? HEX : encoding;
			return Maps.of(SECRET, store(ctx, input, encode(plaintext, storedEncoding)), ENCODING, storedEncoding);
		}
		return Maps.of(DATA, BLOB.equals(encoding) ? plaintext : encode(plaintext, encoding), ENCODING, encoding);
	}

	private ACell generateMnemonic(RequestContext ctx, ACell input) {
		ACell supplied = RT.getIn(input, WORDS);
		long words = supplied == null ? 12 : supplied instanceof CVMLong n ? n.longValue() : -1;
		if (!validWordCount(words)) throw new InvalidInputException(INVALID_WORDS);
		String phrase = BIP39.createSecureMnemonic((int) words);
		if (toKeyring(input)) return holdMnemonic(ctx, phrase, input).assoc(WORDS, CVMLong.create(words));
		return Maps.of(SECRET, store(ctx, input, Strings.create(phrase)), WORDS, words);
	}

	private ACell deriveKey(RequestContext ctx, ACell input) {
		Held held = heldMnemonicRef(ctx, RT.getIn(input, MNEMONIC));
		// The derived key lands in the caller's store, where the caller may
		// extract it. Another principal's mnemonic would yield its key material,
		// and its passphrase could be guessed offline from the derived key.
		if (held == null) requireOwnSecret(ctx, input, MNEMONIC, OWN_SECRETS_ONLY);
		if (RT.getIn(input, PASSPHRASE_SECRET) != null) requireOwnSecret(ctx, input, PASSPHRASE_SECRET, OWN_SECRETS_ONLY);
		AString path = text(input, PATH, DEFAULT_PATH);
		int[] indexes = parsePath(path);
		// A held phrase is never readable, so neither is a key derived from it:
		// it goes only into the phrase holder's keyring.
		if (held != null && !toKeyring(input)) throw new InvalidInputException(DERIVED_TO_KEYRING);
		String phrase = (held != null) ? openMnemonic(ctx, held, Abilities.KEY_DERIVE) : mnemonic(ctx, input);
		if (!validMnemonic(phrase)) throw new InvalidInputException(INVALID_MNEMONIC);
		// BIP39's normalisation/KDF API requires Java strings.
		String passphrase = RT.getIn(input, PASSPHRASE_SECRET) == null ? "" : secret(ctx, input, PASSPHRASE_SECRET).toString();
		AKeyPair key = SLIP10.deriveKeyPair(BIP39.getSeed(phrase, passphrase), indexes);
		StringBuilder canonical = new StringBuilder("m");
		for (int index : indexes) canonical.append('/').append(index).append('\'');
		AMap<AString, ACell> stored = (held != null)
			? holdKey(ctx, held.holder(), key, input, null) // key/derive covers it
			: storeKey(ctx, input, key);
		return stored.assoc(PATH, Strings.create(canonical.toString()));
	}

	private boolean validateMnemonic(RequestContext ctx, ACell input) {
		Held held = heldMnemonicRef(ctx, RT.getIn(input, MNEMONIC));
		// A held phrase was valid when added; opening it shows it is still there.
		if (held != null) return validMnemonic(openMnemonic(ctx, held, Capability.CRUD_READ));
		return validMnemonic(mnemonic(ctx, input));
	}

	// ========== Keyrings (HELD_KEYS.md) ==========

	/** A held key or mnemonic: whose keyring, and its identifier. */
	record Held(AString holder, Blob id) {}

	/** Keyrings are resources in the adapter's own {@code x/} space: {@code <did>/x/crypto/keys/<key>}. */
	private static final AString KEYS_SEGMENT = Strings.intern("/x/crypto/keys/");
	private static final AString MNEMONICS_SEGMENT = Strings.intern("/x/crypto/mnemonics/");
	private static final AString NEXT_OFFSET = Strings.intern("nextOffset");

	/**
	 * The private key a held key names, for one use. Checks {@code ability}
	 * on the key by the standard rules (HELD_KEYS.md, "Abilities") and audits
	 * the use; the key pair must not outlive the call it serves.
	 *
	 * @param key a held key: a did:key, multibase key or account key in the
	 *            keyring of the namespace the request acts in, or {@code <did>/x/crypto/keys/<key>}
	 * @throws AuthException when the request's authority does not cover the use
	 * @throws NotFoundException when no such key is held
	 */
	public AKeyPair heldKeyPair(RequestContext ctx, ACell key, AString ability) {
		Held held = heldRef(ctx, key, KEYS_SEGMENT, INVALID_HELD_KEY);
		if (held == null) throw new InvalidInputException(INVALID_HELD_KEY);
		engine.requireLocalAccess(ctx, resource(held, KEYS_SEGMENT), ability);
		AMap<AString, ACell> record = CryptoKeyring.get(adapterState().get(), held.holder(), CryptoKeyring.Kind.KEY, held.id());
		if (record == null) throw new NotFoundException(KEY_NOT_HELD, held.holder().toString());
		AKeyPair pair = CryptoKeyring.openKey(sealing(), held.holder(), held.id(), record);
		audit(Audit.KEYRING_USE, ctx, held, ability);
		return pair;
	}

	/**
	 * The public key a held key reference names, without using it: what a
	 * caller needs to know before it asks {@link #heldKeyPair} to sign.
	 *
	 * @throws InvalidInputException when {@code key} names no key
	 */
	public AccountKey heldPublicKey(RequestContext ctx, ACell key) {
		Held held = heldRef(ctx, key, KEYS_SEGMENT, INVALID_HELD_KEY);
		if (held == null) throw new InvalidInputException(INVALID_HELD_KEY);
		return CryptoKeyring.accountKey(held.id());
	}

	/**
	 * The key a root identity holds for itself, to sign identity tokens for it
	 * and the identities nested below it (HELD_KEYS.md, principle 4): its own
	 * did:key's private key, in its own keyring. Null when it holds none.
	 */
	AKeyPair identityKeyPair(RequestContext ctx, AString root) {
		AccountKey key = UCAN.fromDIDKey(root);
		if (key == null) return null;
		Held held = new Held(root, CryptoKeyring.id(key));
		AMap<AString, ACell> record = CryptoKeyring.get(adapterState().get(), root, CryptoKeyring.Kind.KEY, held.id());
		if (record == null) return null;
		AKeyPair pair = CryptoKeyring.openKey(sealing(), root, held.id(), record);
		audit(Audit.KEYRING_USE, ctx, held, null);
		return pair;
	}

	/** The crypto adapter of {@code engine}, through which other adapters use held keys. */
	static CryptoAdapter of(Engine engine) {
		if (engine.getAdapter("crypto") instanceof CryptoAdapter crypto) return crypto;
		throw new UnavailableException(CRYPTO_UNAVAILABLE);
	}

	private ACell addKey(RequestContext ctx, ACell input) {
		AMap<?, ?> map = (AMap<?, ?>) input;
		if (map.containsKey(SEED) == map.containsKey(MNEMONIC)) throw new InvalidInputException(SEED_OR_MNEMONIC);
		AString field = map.containsKey(SEED) ? SEED : MNEMONIC;
		requireOwnSecret(ctx, input, field, ADD_OWN_SECRETS_ONLY);
		if (field == SEED) return holdKey(ctx, ownHolder(ctx), seedKeyPair(ctx, input), input, Abilities.KEY_ADD);
		String phrase = mnemonic(ctx, input);
		if (!validMnemonic(phrase)) throw new InvalidInputException(INVALID_MNEMONIC);
		return holdMnemonic(ctx, phrase, input).assoc(WORDS, CVMLong.create(phrase.split(" ").length));
	}

	private ACell removeKey(RequestContext ctx, ACell input) {
		AMap<?, ?> map = (AMap<?, ?>) input;
		if (map.containsKey(Fields.KEY) == map.containsKey(MNEMONIC)) throw new InvalidInputException(KEY_OR_MNEMONIC);
		boolean key = map.containsKey(Fields.KEY);
		AString segment = key ? KEYS_SEGMENT : MNEMONICS_SEGMENT;
		Held held = key ? heldRef(ctx, map.get(Fields.KEY), segment, INVALID_HELD_KEY) : heldMnemonicRef(ctx, map.get(MNEMONIC));
		if (held == null) throw new InvalidInputException(key ? INVALID_HELD_KEY : INVALID_HELD_MNEMONIC);
		engine.requireLocalAccess(ctx, resource(held, segment), Abilities.KEY_REMOVE);
		boolean removed = CryptoKeyring.remove(adapterState(), held.holder(),
			key ? CryptoKeyring.Kind.KEY : CryptoKeyring.Kind.MNEMONIC, held.id());
		if (removed) audit(Audit.KEYRING_REMOVE, ctx, held, Abilities.KEY_REMOVE);
		return Maps.of(Fields.REMOVED, CVMBool.create(removed));
	}

	private ACell listKeys(RequestContext ctx, ACell input) {
		ACell did = RT.getIn(input, Fields.DID);
		if (did != null && !(did instanceof AString s && s.toString().startsWith("did:") && s.toString().indexOf('/') < 0)) {
			throw new InvalidInputException(Fields.DID + REQUIRED_STRING);
		}
		AString holder = (did != null) ? (AString) did : ownHolder(ctx);
		requireKeyring(holder);
		engine.requireLocalAccess(ctx, holder.append(KEYS_SEGMENT), Capability.CRUD_READ);
		engine.requireLocalAccess(ctx, holder.append(MNEMONICS_SEGMENT), Capability.CRUD_READ);
		long offset = SecretAdapter.pageNumber(input, Fields.OFFSET, 0, Long.MAX_VALUE);
		long limit = SecretAdapter.pageNumber(input, Fields.LIMIT, 100, 1000);
		if (limit == 0) throw new InvalidInputException(SecretAdapter.INVALID_PAGE);
		ACell state = adapterState().get();
		Index<Blob, ACell> keys = CryptoKeyring.entries(state, holder, CryptoKeyring.Kind.KEY);
		Index<Blob, ACell> mnemonics = CryptoKeyring.entries(state, holder, CryptoKeyring.Kind.MNEMONIC);
		long total = keys.count() + mnemonics.count();
		AVector<ACell> keyPage = Vectors.empty(), mnemonicPage = Vectors.empty();
		for (long i = offset; i < total && i < offset + limit; i++) {
			boolean isKey = i < keys.count();
			var entry = isKey ? keys.entryAt(i) : mnemonics.entryAt(i - keys.count());
			AccountKey ak = CryptoKeyring.accountKey(entry.getKey());
			AMap<AString, ACell> record = RT.ensureMap(entry.getValue());
			AMap<AString, ACell> item = Maps.of(DID, UCAN.toDIDKey(ak), Fields.ADDED_AT, record.get(Fields.ADDED_AT));
			if (isKey) item = item.assoc(ACCOUNT_KEY, ak);
			if (record.get(Fields.LABEL) != null) item = item.assoc(Fields.LABEL, record.get(Fields.LABEL));
			if (isKey) keyPage = keyPage.conj(item); else mnemonicPage = mnemonicPage.conj(item);
		}
		long shown = keyPage.count() + mnemonicPage.count();
		AMap<AString, ACell> result = Maps.of(Fields.KEYS, keyPage, Fields.MNEMONICS, mnemonicPage,
			Fields.TOTAL, CVMLong.create(total), Fields.OFFSET, CVMLong.create(offset));
		if (offset < total && shown < total - offset) result = result.assoc(NEXT_OFFSET, CVMLong.create(offset + shown));
		return result;
	}

	/** Puts a key into {@code holder}'s keyring after checking {@code ability} on it (null: already checked). */
	private AMap<AString, ACell> holdKey(RequestContext ctx, AString holder, AKeyPair key, ACell input, AString ability) {
		Held held = new Held(holder, CryptoKeyring.id(key.getAccountKey()));
		AString secret = Multikey.encodeSecretKeey(key.getSeed());
		hold(ctx, held, CryptoKeyring.Kind.KEY, KEYS_SEGMENT, secret, input, ability);
		return publicKey(key);
	}

	/** Puts a phrase into the caller's keyring; the result names it by its master node's did:key. */
	private AMap<AString, ACell> holdMnemonic(RequestContext ctx, String phrase, ACell input) {
		Held held = new Held(ownHolder(ctx), CryptoKeyring.mnemonicId(phrase));
		hold(ctx, held, CryptoKeyring.Kind.MNEMONIC, MNEMONICS_SEGMENT, Strings.create(phrase), input, Abilities.KEY_ADD);
		return Maps.of(DID, UCAN.toDIDKey(CryptoKeyring.accountKey(held.id())));
	}

	private void hold(RequestContext ctx, Held held, CryptoKeyring.Kind kind, AString segment, AString secret,
			ACell input, AString ability) {
		requireKeyring(held.holder());
		if (ability != null) engine.requireLocalAccess(ctx, resource(held, segment), ability);
		AString label = label(input);
		ACell overwrite = RT.getIn(input, OVERWRITE);
		if (overwrite != null && !(overwrite instanceof CVMBool)) throw new InvalidInputException(INVALID_OVERWRITE);
		AdapterState state = adapterState();
		AMap<AString, ACell> record = CryptoKeyring.record(sealing(), held.holder(), kind, held.id(), secret, label,
			state.getContext().currentTimestamp());
		if (!CryptoKeyring.put(state, held.holder(), kind, held.id(), record, CVMBool.TRUE.equals(overwrite))) {
			throw new ConflictException(KEY_ALREADY_HELD);
		}
		audit(Audit.KEYRING_ADD, ctx, held, Abilities.KEY_ADD);
	}

	private String openMnemonic(RequestContext ctx, Held held, AString ability) {
		engine.requireLocalAccess(ctx, resource(held, MNEMONICS_SEGMENT), ability);
		AMap<AString, ACell> record = CryptoKeyring.get(adapterState().get(), held.holder(), CryptoKeyring.Kind.MNEMONIC, held.id());
		if (record == null) throw new NotFoundException(MNEMONIC_NOT_HELD, held.holder().toString());
		String phrase = CryptoKeyring.openMnemonic(sealing(), held.holder(), held.id(), record);
		audit(Audit.KEYRING_USE, ctx, held, ability);
		return phrase;
	}

	/** Whether a new key or phrase goes into the keyring instead of a secret. */
	private static boolean toKeyring(ACell input) {
		ACell value = RT.getIn(input, Fields.KEYRING);
		if (value == null) return false;
		if (!(value instanceof CVMBool b)) throw new InvalidInputException(INVALID_KEYRING);
		if (b.booleanValue() && ((AMap<?, ?>) input).containsKey(NAME)) throw new InvalidInputException(KEYRING_OR_NAME);
		return b.booleanValue();
	}

	private static AString label(ACell input) {
		ACell value = RT.getIn(input, Fields.LABEL);
		if (value == null) return null;
		if (!(value instanceof AString s) || s.count() > 256) throw new InvalidInputException(INVALID_LABEL);
		return s;
	}

	/** The keyring of the namespace the request acts in, as a bare {@code s/NAME} names a secret there. */
	private AString ownHolder(RequestContext ctx) {
		AString holder = (ctx == null) ? null : ctx.getUserDID();
		requireKeyring(holder);
		return holder;
	}

	private void requireKeyring(AString holder) {
		if (holder == null || engine.isPublicPrincipal(holder)) throw new AuthException(NO_KEYRING);
	}

	private static AString resource(Held held, AString segment) {
		return held.holder().append(segment).append(CryptoKeyring.multibase(held.id()));
	}

	private SecretStore.Keys sealing() {
		return CryptoKeyring.keys(engine.getKeyPair());
	}

	private void audit(String event, RequestContext ctx, Held held, AString ability) {
		engine.audit().event(event, Audit.K_DID, ctx == null ? null : ctx.getCallerDID(), Audit.K_HOLDER, held.holder(),
			Audit.K_KEY, CryptoKeyring.multibase(held.id()), Audit.K_ABILITY, ability);
	}

	private Held heldMnemonicRef(RequestContext ctx, ACell value) {
		return heldRef(ctx, value, MNEMONICS_SEGMENT, INVALID_HELD_MNEMONIC);
	}

	/**
	 * Reads a held key or mnemonic reference: {@code <did><segment><key>}, or
	 * a bare key in the namespace the request acts in. Null for a value that is
	 * no such reference, such as a secret reference or a phrase.
	 */
	private Held heldRef(RequestContext ctx, ACell value, AString segment, String invalid) {
		if (value instanceof ABlob blob) {
			if (blob.count() != AccountKey.LENGTH) throw new InvalidInputException(invalid);
			return new Held(ownHolder(ctx), CryptoKeyring.id(AccountKey.wrap(blob.getBytes())));
		}
		if (!(value instanceof AString text)) return null;
		String s = text.toString();
		AString holder = null;
		int at = s.indexOf(segment.toString());
		if (s.startsWith("did:") && at > 0) {
			holder = Strings.create(s.substring(0, at));
			if (holder.toString().indexOf('/') >= 0) throw new InvalidInputException(invalid);
			s = s.substring(at + segment.toString().length());
		}
		AccountKey key = publicKeyOf(s);
		if (key == null) {
			if (holder != null) throw new InvalidInputException(invalid);
			return null;
		}
		return new Held(holder != null ? holder : ownHolder(ctx), CryptoKeyring.id(key));
	}

	/** A public key from a did:key, multibase or hex string; null for anything else. */
	private static AccountKey publicKeyOf(String s) {
		try {
			if (s.matches("did:key:z[1-9A-HJ-NP-Za-km-z]+")) return UCAN.fromDIDKey(Strings.create(s));
			if (s.matches("z[1-9A-HJ-NP-Za-km-z]+")) return Multikey.decodePublicKey(s);
			if (s.startsWith("0x") || s.matches("[0-9a-fA-F]{64}")) {
				ABlob blob = Blobs.parse(s);
				return (blob != null && blob.count() == AccountKey.LENGTH) ? AccountKey.wrap(blob.getBytes()) : null;
			}
		} catch (IllegalArgumentException e) {
			return null;
		}
		return null;
	}

	private static int[] parsePath(AString value) {
		if (value.count() > 3061) throw new InvalidInputException(INVALID_PATH);
		String path = value.toString(); // Java split and integer parsing implement path syntax.
		String[] parts = path.split("/", -1);
		if (!parts[0].equals("m") || parts.length > 256) throw new InvalidInputException(INVALID_PATH);
		int[] indexes = new int[parts.length - 1];
		for (int i = 1; i < parts.length; i++) {
			String part = parts[i];
			if (part.endsWith("'")) part = part.substring(0, part.length() - 1);
			if (!part.matches("[0-9]{1,10}")) throw new InvalidInputException(INVALID_PATH);
			try { indexes[i - 1] = Integer.parseInt(part); }
			catch (NumberFormatException e) { throw new InvalidInputException(INVALID_PATH); }
		}
		return indexes;
	}

	private String mnemonic(RequestContext ctx, ACell input) {
		// Match English recovery input without silently completing abbreviated words.
		return Normalizer.normalize(secret(ctx, input, MNEMONIC).toString(), Normalizer.Form.NFKD)
			.toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+", " ").strip();
	}

	private static boolean validMnemonic(String phrase) {
		if (!validWordCount(phrase.split(" ", -1).length)) return false;
		// Convex's reason can contain a mnemonic word; never return it to a Job.
		return BIP39.checkMnemonic(phrase) == null;
	}

	private static boolean validWordCount(long words) {
		return words >= 12 && words <= 24 && words % 3 == 0;
	}

	private AString store(RequestContext ctx, ACell input, AString value) {
		AString name = text(input, NAME, null);
		ACell overwrite = RT.getIn(input, OVERWRITE);
		if (overwrite != null && !(overwrite instanceof CVMBool)) throw new InvalidInputException(INVALID_OVERWRITE);
		SecretAdapter.storeSecret(engine, ctx, name, value, CVMBool.TRUE.equals(overwrite));
		return SECRET_PREFIX.append(name);
	}

	private AString secret(RequestContext ctx, ACell input, AString field) {
		AString ref = text(input, field, null);
		// The existing Engine secret-resolution boundary is String-based.
		if (!Engine.isSecretRef(ref.toString())) throw new InvalidInputException(SECRET_REF_REQUIRED);
		String value = engine.resolveSecret(ref.toString(), ctx);
		if (value == null) throw new NotFoundException(SECRET_MISSING, ref.toString());
		if (value.length() > MAX_BYTES) throw new InvalidInputException(TOO_LARGE);
		return Strings.create(value);
	}

	private void requireOwnSecret(RequestContext ctx, ACell input, AString field, String refusal) {
		AString ref = text(input, field, null);
		// A literal is left for secret() to refuse with its own reason.
		if (Engine.isSecretRef(ref.toString()) && !engine.isOwnSecretRef(ref.toString(), ctx)) {
			throw new AuthException(refusal);
		}
	}

	/** The key an operation uses: a held {@code key}, used under {@code ability}, or a {@code seed} secret. */
	private AKeyPair keyPair(RequestContext ctx, ACell input, AString ability) {
		AMap<?, ?> map = (AMap<?, ?>) input;
		if (ability != null && map.containsKey(Fields.KEY)) {
			if (map.containsKey(SEED)) throw new InvalidInputException(KEY_OR_SEED);
			return heldKeyPair(ctx, map.get(Fields.KEY), ability);
		}
		return seedKeyPair(ctx, input);
	}

	private AKeyPair seedKeyPair(RequestContext ctx, ACell input) {
		Blob seed = blob(secret(ctx, input, SEED), MAX_BYTES);
		if (seed.count() != AKeyPair.SEED_LENGTH) throw new InvalidInputException(INVALID_KEY);
		return AKeyPair.create(seed);
	}

	private static AccountKey accountKey(ACell input) {
		Blob key = blob(RT.getIn(input, ACCOUNT_KEY), MAX_BYTES);
		if (key.count() != 32) throw new InvalidInputException(INVALID_KEY);
		return AccountKey.wrap(key.getBytes());
	}

	private static Blob data(ACell input) {
		AString encoding = text(input, ENCODING, HEX);
		if (!isTextEncoding(encoding)) throw new InvalidInputException(INVALID_ENCODING);
		ACell supplied = RT.getIn(input, DATA);
		// Encoding describes string input only; native blobs already ARE bytes.
		if (supplied instanceof ABlob || HEX.equals(encoding)) return blob(supplied, MAX_BYTES);
		AString value = text(input, DATA, null);
		if (value.count() > MAX_BYTES * 2L) throw new InvalidInputException(TOO_LARGE);
		byte[] bytes;
		try { bytes = UTF8.equals(encoding) ? value.toBlob().getBytes() : Base64.getDecoder().decode(value.toString()); }
		catch (IllegalArgumentException e) { throw new InvalidInputException(INVALID_BYTES); }
		if (bytes.length > MAX_BYTES) throw new InvalidInputException(TOO_LARGE);
		return Blob.wrap(bytes);
	}

	private static Blob blob(ACell value, int maxBytes) {
		if (value instanceof AString s && s.count() > maxBytes * 2L + 2) throw new InvalidInputException(TOO_LARGE);
		ABlob parsed = Blobs.parse(value);
		if (parsed == null) throw new InvalidInputException(INVALID_BYTES);
		// Check before flattening a BlobTree or allocating a copy.
		if (parsed.count() > maxBytes) throw new InvalidInputException(TOO_LARGE);
		return parsed.toFlatBlob();
	}

	private static AString encode(Blob bytes, AString encoding) {
		if (HEX.equals(encoding)) return Strings.create(bytes.toHexString());
		if (BASE64.equals(encoding)) return Strings.create(Base64.getEncoder().encodeToString(bytes.getBytes()));
		try {
			// Validate strictly before constructing a UTF-8 string from the blob.
			StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.getBytes()));
			return Strings.create(bytes);
		} catch (CharacterCodingException e) { throw new InvalidInputException(INVALID_UTF8); }
	}

	private static boolean isTextEncoding(AString encoding) {
		return UTF8.equals(encoding) || HEX.equals(encoding) || BASE64.equals(encoding);
	}

	private static AString text(ACell input, AString field, AString fallback) {
		ACell value = RT.getIn(input, field);
		if (value == null && fallback != null) return fallback;
		if (!(value instanceof AString s)) throw new InvalidInputException(field + REQUIRED_STRING);
		return s;
	}
}
