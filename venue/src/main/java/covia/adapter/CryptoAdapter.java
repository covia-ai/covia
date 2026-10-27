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
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.BIP39;
import convex.core.crypto.ECIES;
import convex.core.crypto.Hashing;
import convex.core.crypto.SLIP10;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.data.prim.ANumeric;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.exception.AuthException;
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

	@Override protected void installAssets() {
		for (String op : new String[] {"hash", "generate-key", "public-key", "sign", "verify",
				"encrypt", "decrypt", "generate-mnemonic", "validate-mnemonic", "derive-key",
				"jwt-sign", "jwt-verify", "jwt-decode"}) {
			installAsset("crypto/" + op, "/adapters/crypto/" + op + ".json");
		}
		installSkill("crypto/crypto", "/skills/crypto.json");
	}

	@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx,
			AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		return CompletableFuture.supplyAsync(() -> {
			if (!(input instanceof AMap<?, ?>)) throw new IllegalArgumentException(INVALID_INPUT);
			return switch (getSubOperation(meta)) {
				case "hash" -> hash(input);
				case "generate-key" -> storeKey(ctx, input, AKeyPair.generate());
				case "public-key" -> publicKey(keyPair(ctx, input));
				case "sign" -> sign(ctx, input);
				case "verify" -> verify(input);
				case "encrypt" -> encrypt(input);
				case "decrypt" -> decrypt(ctx, input);
				case "generate-mnemonic" -> generateMnemonic(ctx, input);
				case "validate-mnemonic" -> Maps.of(VALID, validMnemonic(mnemonic(ctx, input)));
				case "derive-key" -> deriveKey(ctx, input);
				case "jwt-sign" -> jwtSign(ctx, input);
				case "jwt-verify" -> jwtVerify(ctx, input);
				case "jwt-decode" -> jwtDecode(ctx, input);
				default -> throw new IllegalArgumentException(UNKNOWN_OPERATION);
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
		} else throw new IllegalArgumentException(INVALID_ALGORITHM);
		return Maps.of(ALGORITHM, algorithm, DIGEST, digest);
	}

	private AMap<AString, ACell> publicKey(AKeyPair key) {
		return Maps.of(ACCOUNT_KEY, key.getAccountKey(), DID, UCAN.toDIDKey(key.getAccountKey()));
	}

	private AMap<AString, ACell> storeKey(RequestContext ctx, ACell input, AKeyPair key) {
		return publicKey(key).assoc(SECRET, store(ctx, input, Strings.create(key.getSeed().toHexString())));
	}

	private ACell sign(RequestContext ctx, ACell input) {
		Blob bytes = data(input);
		AKeyPair key = keyPair(ctx, input);
		return publicKey(key).assoc(SIGNATURE, key.sign(bytes));
	}

	private ACell verify(ACell input) {
		Blob signature = blob(RT.getIn(input, SIGNATURE), MAX_BYTES);
		if (signature.count() != 64) throw new IllegalArgumentException(INVALID_SIGNATURE);
		boolean valid = ASignature.fromBlob(signature).verify(data(input), accountKey(input));
		return Maps.of(VALID, valid);
	}

	private ACell encrypt(ACell input) {
		Blob envelope = ECIES.encrypt(accountKey(input), data(input));
		return Maps.of(CIPHERTEXT, envelope, ENCODING, BLOB);
	}

	private ACell jwtSign(RequestContext ctx, ACell input) {
		if (!(RT.getIn(input, CLAIMS) instanceof AMap<?, ?> supplied)) throw new IllegalArgumentException(INVALID_CLAIMS);
		for (ACell key : supplied.getKeys()) {
			if (!(key instanceof AString)) throw new IllegalArgumentException(INVALID_CLAIMS);
		}
		AString json = JSON.toAString(supplied);
		if (json.count() > MAX_BYTES) throw new IllegalArgumentException(TOO_LARGE);
		// Use the JSON projection consistently, including for native blob claim values.
		AMap<AString, ACell> claims = RT.castMap(JSON.parse(json));
		String reason = jwtClaimShape(claims);
		if (reason != null) throw new IllegalArgumentException(reason);
		AString kid = optionalText(input, JWT.KID);
		if (kid != null && kid.count() > MAX_BYTES) throw new IllegalArgumentException(TOO_LARGE);
		boolean storeOutput = ((AMap<?, ?>) input).containsKey(NAME);
		if (storeOutput) text(input, NAME, null);
		AKeyPair key = keyPair(ctx, input);
		AString token = kid == null ? JWT.signPublic(claims, key) : JWT.signPublic(claims, key, kid);
		if (token.count() > MAX_BYTES) throw new IllegalArgumentException(TOO_LARGE);
		return storeOutput ? publicKey(key).assoc(SECRET, store(ctx, input, token))
			: publicKey(key).assoc(TOKEN, token);
	}

	private ACell jwtDecode(RequestContext ctx, ACell input) {
		JWT jwt = JWT.parse(jwtToken(ctx, input));
		if (jwt == null) throw new IllegalArgumentException(INVALID_JWT);
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
			throw new IllegalArgumentException(INVALID_REQUIRE_EXPIRATION);
		}
		JWT jwt = JWT.parse(jwtToken(ctx, input));
		if (jwt == null) throw new IllegalArgumentException(INVALID_JWT);
		String reason = jwtVerificationReason(jwt, trustedKey, issuer, audience, CVMBool.TRUE.equals(required));
		// Only successful verification releases claims. Reasons never echo token data.
		if (reason != null) return Maps.of(VALID, false, REASON, reason);
		return Maps.of(VALID, true, HEADER, jwt.getHeader(), CLAIMS, jwt.getClaims());
	}

	private AString jwtToken(RequestContext ctx, ACell input) {
		AMap<?, ?> map = (AMap<?, ?>) input;
		if (map.containsKey(TOKEN) == map.containsKey(TOKEN_SECRET)) throw new IllegalArgumentException(INVALID_JWT_SOURCE);
		AString token = map.containsKey(TOKEN_SECRET) ? secret(ctx, input, TOKEN_SECRET) : text(input, TOKEN, null);
		if (token.count() > MAX_BYTES) throw new IllegalArgumentException(TOO_LARGE);
		// Compact JWS uses unpadded base64url; reject extra segments and whitespace.
		// Empty signatures may be decoded, but never pass Ed25519 verification.
		if (!token.toString().matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*")) {
			throw new IllegalArgumentException(INVALID_JWT);
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
		if (!BLOB.equals(encoding) && !isTextEncoding(encoding)) throw new IllegalArgumentException(INVALID_OUTPUT_ENCODING);
		Blob envelope = blob(RT.getIn(input, CIPHERTEXT), MAX_BYTES + ECIES.OVERHEAD);
		Blob plaintext;
		try { plaintext = ECIES.decrypt(keyPair(ctx, input), envelope); }
		catch (BadFormatException e) {
			// No provider exception or decrypted material enters the Job error.
			throw new IllegalArgumentException(DECRYPT_FAILED);
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
		if (!validWordCount(words)) throw new IllegalArgumentException(INVALID_WORDS);
		AString phrase = Strings.create(BIP39.createSecureMnemonic((int) words));
		return Maps.of(SECRET, store(ctx, input, phrase), WORDS, words);
	}

	private ACell deriveKey(RequestContext ctx, ACell input) {
		// The derived key lands in the caller's store, where the caller may
		// extract it. Another principal's mnemonic would yield its key material,
		// and its passphrase could be guessed offline from the derived key.
		requireOwnSecret(ctx, input, MNEMONIC);
		if (RT.getIn(input, PASSPHRASE_SECRET) != null) requireOwnSecret(ctx, input, PASSPHRASE_SECRET);
		AString path = text(input, PATH, DEFAULT_PATH);
		int[] indexes = parsePath(path);
		String phrase = mnemonic(ctx, input);
		if (!validMnemonic(phrase)) throw new IllegalArgumentException(INVALID_MNEMONIC);
		// BIP39's normalisation/KDF API requires Java strings.
		String passphrase = RT.getIn(input, PASSPHRASE_SECRET) == null ? "" : secret(ctx, input, PASSPHRASE_SECRET).toString();
		AKeyPair key = SLIP10.deriveKeyPair(BIP39.getSeed(phrase, passphrase), indexes);
		StringBuilder canonical = new StringBuilder("m");
		for (int index : indexes) canonical.append('/').append(index).append('\'');
		return storeKey(ctx, input, key).assoc(PATH, Strings.create(canonical.toString()));
	}

	private static int[] parsePath(AString value) {
		if (value.count() > 3061) throw new IllegalArgumentException(INVALID_PATH);
		String path = value.toString(); // Java split and integer parsing implement path syntax.
		String[] parts = path.split("/", -1);
		if (!parts[0].equals("m") || parts.length > 256) throw new IllegalArgumentException(INVALID_PATH);
		int[] indexes = new int[parts.length - 1];
		for (int i = 1; i < parts.length; i++) {
			String part = parts[i];
			if (part.endsWith("'")) part = part.substring(0, part.length() - 1);
			if (!part.matches("[0-9]{1,10}")) throw new IllegalArgumentException(INVALID_PATH);
			try { indexes[i - 1] = Integer.parseInt(part); }
			catch (NumberFormatException e) { throw new IllegalArgumentException(INVALID_PATH); }
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
		if (overwrite != null && !(overwrite instanceof CVMBool)) throw new IllegalArgumentException(INVALID_OVERWRITE);
		SecretAdapter.storeSecret(engine, ctx, name, value, CVMBool.TRUE.equals(overwrite));
		return SECRET_PREFIX.append(name);
	}

	private AString secret(RequestContext ctx, ACell input, AString field) {
		AString ref = text(input, field, null);
		// The existing Engine secret-resolution boundary is String-based.
		if (!Engine.isSecretRef(ref.toString())) throw new IllegalArgumentException(SECRET_REF_REQUIRED);
		String value = engine.resolveSecret(ref.toString(), ctx);
		if (value == null) throw new IllegalArgumentException(SECRET_MISSING);
		if (value.length() > MAX_BYTES) throw new IllegalArgumentException(TOO_LARGE);
		return Strings.create(value);
	}

	private void requireOwnSecret(RequestContext ctx, ACell input, AString field) {
		AString ref = text(input, field, null);
		// A literal is left for secret() to refuse with its own reason.
		if (Engine.isSecretRef(ref.toString()) && !engine.isOwnSecretRef(ref.toString(), ctx)) {
			throw new AuthException(OWN_SECRETS_ONLY);
		}
	}

	private AKeyPair keyPair(RequestContext ctx, ACell input) {
		Blob seed = blob(secret(ctx, input, SEED), MAX_BYTES);
		if (seed.count() != AKeyPair.SEED_LENGTH) throw new IllegalArgumentException(INVALID_KEY);
		return AKeyPair.create(seed);
	}

	private static AccountKey accountKey(ACell input) {
		Blob key = blob(RT.getIn(input, ACCOUNT_KEY), MAX_BYTES);
		if (key.count() != 32) throw new IllegalArgumentException(INVALID_KEY);
		return AccountKey.wrap(key.getBytes());
	}

	private static Blob data(ACell input) {
		AString encoding = text(input, ENCODING, HEX);
		if (!isTextEncoding(encoding)) throw new IllegalArgumentException(INVALID_ENCODING);
		ACell supplied = RT.getIn(input, DATA);
		// Encoding describes string input only; native blobs already ARE bytes.
		if (supplied instanceof ABlob || HEX.equals(encoding)) return blob(supplied, MAX_BYTES);
		AString value = text(input, DATA, null);
		if (value.count() > MAX_BYTES * 2L) throw new IllegalArgumentException(TOO_LARGE);
		byte[] bytes;
		try { bytes = UTF8.equals(encoding) ? value.toBlob().getBytes() : Base64.getDecoder().decode(value.toString()); }
		catch (IllegalArgumentException e) { throw new IllegalArgumentException(INVALID_BYTES); }
		if (bytes.length > MAX_BYTES) throw new IllegalArgumentException(TOO_LARGE);
		return Blob.wrap(bytes);
	}

	private static Blob blob(ACell value, int maxBytes) {
		if (value instanceof AString s && s.count() > maxBytes * 2L + 2) throw new IllegalArgumentException(TOO_LARGE);
		ABlob parsed = Blobs.parse(value);
		if (parsed == null) throw new IllegalArgumentException(INVALID_BYTES);
		// Check before flattening a BlobTree or allocating a copy.
		if (parsed.count() > maxBytes) throw new IllegalArgumentException(TOO_LARGE);
		return parsed.toFlatBlob();
	}

	private static AString encode(Blob bytes, AString encoding) {
		if (HEX.equals(encoding)) return Strings.create(bytes.toHexString());
		if (BASE64.equals(encoding)) return Strings.create(Base64.getEncoder().encodeToString(bytes.getBytes()));
		try {
			// Validate strictly before constructing a UTF-8 string from the blob.
			StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.getBytes()));
			return Strings.create(bytes);
		} catch (CharacterCodingException e) { throw new IllegalArgumentException(INVALID_UTF8); }
	}

	private static boolean isTextEncoding(AString encoding) {
		return UTF8.equals(encoding) || HEX.equals(encoding) || BASE64.equals(encoding);
	}

	private static AString text(ACell input, AString field, AString fallback) {
		ACell value = RT.getIn(input, field);
		if (value == null && fallback != null) return fallback;
		if (!(value instanceof AString s)) throw new IllegalArgumentException(field + REQUIRED_STRING);
		return s;
	}
}
