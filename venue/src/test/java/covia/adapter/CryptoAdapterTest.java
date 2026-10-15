package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.auth.jwt.JWT;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.BIP39;
import convex.core.crypto.ECIES;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.Blobs;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.json.schema.JsonSchema;
import convex.core.util.JSON;
import covia.api.Abilities;
import covia.grid.Job;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.SecretStore;

/** Known-answer, interoperability and authority tests through real Jobs. */
@Execution(ExecutionMode.SAME_THREAD) // Lifecycle test temporarily disables this class's shared adapter.
class CryptoAdapterTest {

	private static final String PHRASE = "abandon ".repeat(11) + "about";
	private static Engine engine;
	private RequestContext ctx;
	private AKeyPair identity;

	@BeforeAll static void start() {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
	}
	@AfterAll static void stop() { if (engine != null) engine.close(); }
	@BeforeEach void caller() {
		identity = AKeyPair.generate();
		ctx = RequestContext.of(UCAN.toDIDKey(identity.getAccountKey()));
		engine.getVenueState().users().ensure(ctx.getUserDID());
	}

	private Job job(String op, ACell input) {
		return engine.jobs().invokeOperation("v/ops/crypto/" + op, input, ctx);
	}
	private ACell run(String op, ACell input) {
		ACell result = job(op, input).awaitResult(5000);
		ACell metadata = engine.getAdapter("crypto").getOperations().get(Strings.create(op));
		// Schemas describe JSON/MCP values; native binary values project to 0x strings.
		assertNull(JsonSchema.validate(RT.ensureMap(RT.getIn(metadata, "operation", "input")),
			JSON.parse(JSON.toString(input))), "JSON input schema: " + op);
		assertNull(JsonSchema.validate(RT.ensureMap(RT.getIn(metadata, "operation", "output")),
			JSON.parse(JSON.toString(result))), "JSON output schema: " + op);
		return result;
	}
	private static String text(ACell cell, String field) {
		return assertInstanceOf(AString.class, RT.getIn(cell, field)).toString();
	}
	private static String hex(ACell cell, String field) {
		return assertInstanceOf(ABlob.class, RT.getIn(cell, field)).toHexString();
	}
	private void put(String name, String value) {
		engine.getVenueState().users().ensure(ctx.getUserDID()).secrets()
			.store(name, value, SecretStore.deriveKey(engine.getKeyPair()));
	}
	private Job failure(String op, ACell input) {
		Job job = job(op, input);
		assertThrows(Exception.class, () -> job.awaitResult(5000));
		return job;
	}

	@Test void standardHashVectorsAndEncodings() {
		String[][] vectors = {
			{"sha256", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"},
			{"sha3-256", "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532"},
			{"keccak256", "4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45"},
			{"sha512", "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192"
				+ "992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"}
		};
		for (String[] vector : vectors) {
			for (String[] encoded : new String[][] {{"utf8", "abc"}, {"hex", "0x616263"}, {"base64", "YWJj"}}) {
				assertEquals(vector[1], hex(run("hash", Maps.of("algorithm", vector[0],
					"encoding", encoded[0], "data", encoded[1])), "digest"));
			}
		}
		assertEquals("a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a",
			hex(run("hash", Maps.of("data", "")), "digest"));
		assertEquals(hex(run("hash", Maps.of("data", "\uD83C\uDF10", "encoding", "utf8")), "digest"),
			hex(run("hash", Maps.of("data", "f09f8c90", "encoding", "hex")), "digest"));
	}

	@Test void rfc8032EmptyMessageSignatureAndMismatch() {
		// RFC 8032 section 7.1, test 1; public test key, never a production credential.
		put("key", "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
		String publicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
		String signature = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555f"
			+ "b8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";
		ACell signed = run("sign", Maps.of("seed", "s/key", "data", ""));
		assertEquals(signature, hex(signed, "signature"));
		assertEquals(publicKey, hex(signed, "accountKey"));
		assertEquals(CVMBool.TRUE, RT.getIn(run("verify", Maps.of("accountKey", publicKey,
			"signature", signature, "data", "")), "valid"));
		assertEquals(CVMBool.FALSE, RT.getIn(run("verify", Maps.of("accountKey", publicKey,
			"signature", signature, "data", "changed", "encoding", "utf8")), "valid"));
		ACell publicInfo = run("public-key", Maps.of("seed", "/s/key"));
		assertEquals(publicKey, hex(publicInfo, "accountKey"));
		assertEquals(UCAN.toDIDKey(AccountKey.fromHex(publicKey)).toString(), text(publicInfo, "did"));
		// Seeds also work with the existing Convex adapter, in both directions.
		ACell legacy = engine.jobs().invokeOperation("v/ops/convex/sign", Maps.of("seed", "s/key", "message", ""), ctx).awaitResult(5000);
		assertEquals(signature, text(legacy, "signature"));
	}

	@Test void eciesInteroperatesWithConvexBothDirections() throws Exception {
		AKeyPair key = AKeyPair.generate();
		put("recipient", key.getSeed().toHexString());
		Blob bytes = Blob.fromHex("0001ff80fe");
		AMap<AString, ACell> input = Maps.of("accountKey", key.getAccountKey().toHexString(),
			"data", Base64.getEncoder().encodeToString(bytes.getBytes()), "encoding", "base64");
		String ciphertext = hex(run("encrypt", input), "ciphertext");
		assertEquals(bytes, ECIES.decrypt(key, Blob.fromHex(ciphertext)));
		assertNotEquals(ciphertext, hex(run("encrypt", input), "ciphertext"));
		assertEquals(bytes.count() + ECIES.OVERHEAD, Blob.fromHex(ciphertext).count());
		String convexEnvelope = ECIES.encrypt(key.getAccountKey(), bytes).toHexString();
		ACell decrypted = run("decrypt", Maps.of("seed", "s/recipient", "ciphertext", convexEnvelope, "encoding", "hex"));
		assertEquals(bytes.toHexString(), text(decrypted, "data"));
		assertEquals(CryptoAdapter.INVALID_UTF8,
			failure("decrypt", Maps.of("seed", "s/recipient", "ciphertext", convexEnvelope, "encoding", "utf8")).getErrorMessage());
		String empty = ECIES.encrypt(key.getAccountKey(), Blob.EMPTY).toHexString();
		assertEquals(Blob.EMPTY, RT.getIn(run("decrypt", Maps.of("seed", "s/recipient", "ciphertext", empty)), "data"));
	}

	@Test void eciesTamperingAndWrongKeysNeverWriteSecrets() {
		AKeyPair key = AKeyPair.generate();
		put("recipient", key.getSeed().toHexString());
		String envelope = ECIES.encrypt(key.getAccountKey(), Blob.fromHex("ff")).toHexString();
		put("wrong", AKeyPair.generate().getSeed().toHexString());
		for (String bad : new String[] {"", "00", envelope.substring(0, envelope.length() - 2),
			(envelope.startsWith("00") ? "01" : "00") + envelope.substring(2)}) {
			assertEquals(CryptoAdapter.DECRYPT_FAILED, failure("decrypt", Maps.of("seed", "s/recipient",
				"ciphertext", bad, "name", "plaintext")).getErrorMessage());
		}
		assertEquals(CryptoAdapter.DECRYPT_FAILED, failure("decrypt", Maps.of("seed", "s/wrong",
			"ciphertext", envelope, "name", "plaintext")).getErrorMessage());
		assertNull(engine.resolveSecret("s/plaintext", ctx));
	}

	@Test void nativeBlobsComposeAndJsonRoundTripsPreserveBytes() {
		AKeyPair key = AKeyPair.generate();
		put("key", key.getSeed().toHexString());
		// A BlobTree exercises the ABlob interface, not just flat Blob inputs.
		ABlob message = Blobs.createRandom(new java.util.Random(1234), 5000);
		ACell hashed = run("hash", Maps.of("data", message));
		ABlob digest = assertInstanceOf(ABlob.class, RT.getIn(hashed, "digest"));
		ACell signed = run("sign", Maps.of("seed", "s/key", "data", digest));
		ABlob signature = assertInstanceOf(ABlob.class, RT.getIn(signed, "signature"));
		ABlob accountKey = assertInstanceOf(ABlob.class, RT.getIn(signed, "accountKey"));
		AMap<AString, ACell> verification = Maps.of("data", digest, "accountKey", accountKey, "signature", signature);
		assertEquals(CVMBool.TRUE, RT.getIn(run("verify", verification), "valid"));
		// JSON cannot carry an ABlob: Convex renders it as a 0x-prefixed string.
		ACell jsonVerification = JSON.parse(JSON.toString(verification));
		assertEquals(Strings.create("0x" + digest.toHexString()), RT.getIn(jsonVerification, "data"));
		assertEquals(CVMBool.TRUE, RT.getIn(run("verify", jsonVerification), "valid"));
		assertEquals(digest, RT.getIn(run("hash", JSON.parse(JSON.toString(Maps.of("data", message)))), "digest"));

		ACell encrypted = run("encrypt", Maps.of("accountKey", accountKey, "data", message));
		ABlob envelope = assertInstanceOf(ABlob.class, RT.getIn(encrypted, "ciphertext"));
		AMap<AString, ACell> decryption = Maps.of("seed", "s/key", "ciphertext", envelope);
		assertEquals(message, RT.getIn(run("decrypt", decryption), "data"));
		assertEquals(message, RT.getIn(run("decrypt", JSON.parse(JSON.toString(decryption))), "data"));
		ACell stored = run("decrypt", decryption.assoc(Strings.intern("name"), Strings.create("binary")));
		assertEquals(Strings.intern("hex"), RT.getIn(stored, "encoding"));
		assertNull(RT.getIn(stored, "data"));
		assertEquals(message.toHexString(), engine.resolveSecret("s/binary", ctx));
	}

	@Test void nativeBlobLimitsAndExplicitTextConveniences() {
		ABlob oversized = Blobs.createRandom(new java.util.Random(5678), 1024 * 1024 + 1);
		assertEquals(CryptoAdapter.TOO_LARGE, failure("hash", Maps.of("data", oversized)).getErrorMessage());
		assertEquals(CryptoAdapter.INVALID_BYTES, failure("hash", Maps.of("data", "hello")).getErrorMessage());
		assertEquals(RT.getIn(run("hash", Maps.of("data", "0x68656c6c6f")), "digest"),
			RT.getIn(run("hash", Maps.of("data", "hello", "encoding", "utf8")), "digest"));
		assertEquals(RT.getIn(run("hash", Maps.of("data", Blob.fromHex("ff"))), "digest"),
			RT.getIn(run("hash", Maps.of("data", "/w==", "encoding", "base64")), "digest"));
	}

	@Test void secureDecryptionDoesNotPutPlaintextInTheJob() {
		AKeyPair key = AKeyPair.generate();
		put("recipient", key.getSeed().toHexString());
		String plaintext = "sensitive recovery material";
		String envelope = ECIES.encrypt(key.getAccountKey(), Blob.wrap(plaintext.getBytes(StandardCharsets.UTF_8))).toHexString();
		Job job = job("decrypt", Maps.of("seed", "s/recipient", "ciphertext", envelope, "name", "recovered", "encoding", "utf8"));
		ACell out = job.awaitResult(5000);
		assertEquals("s/recovered", text(out, "secret"));
		assertNull(RT.getIn(out, "data"));
		assertEquals(plaintext, engine.resolveSecret("s/recovered", ctx));
		assertFalse(job.getData().toString().contains(plaintext));
		assertFalse(job.getData().toString().contains(key.getSeed().toHexString()));
	}

	@Test void generatedMaterialStaysInSecretStoreAndRequiresExplicitOverwrite() {
		Job job = job("generate-key", Maps.of("name", "key"));
		ACell out = job.awaitResult(5000);
		String seed = engine.resolveSecret("s/key", ctx);
		assertEquals(64, seed.length());
		assertEquals(hex(out, "accountKey"), AKeyPair.create(seed).getAccountKey().toHexString());
		assertFalse(job.getData().toString().contains(seed));
		failure("generate-key", Maps.of("name", "key"));
		assertEquals(seed, engine.resolveSecret("s/key", ctx));
		run("generate-key", Maps.of("name", "key", "overwrite", true));
		assertNotEquals(seed, engine.resolveSecret("s/key", ctx));
		for (int words : new int[] {12, 15, 18, 21, 24}) {
			Job generated = job("generate-mnemonic", Maps.of("name", "phrase" + words, "words", words));
			generated.awaitResult(5000);
			String phrase = engine.resolveSecret("s/phrase" + words, ctx);
			assertEquals(words, phrase.split(" ").length);
			assertNull(BIP39.checkMnemonic(phrase));
			assertFalse(generated.getData().toString().contains(phrase));
		}
		run("generate-mnemonic", Maps.of("name", "default"));
		assertEquals(12, engine.resolveSecret("s/default", ctx).split(" ").length);
		assertNotEquals(engine.resolveSecret("s/default", ctx), engine.resolveSecret("s/phrase12", ctx));
		for (Object words : new Object[] {3, 9, 13, 27, "12", 12.5}) {
			failure("generate-mnemonic", Maps.of("name", "invalid", "words", words));
		}
		assertNull(engine.resolveSecret("s/invalid", ctx));
	}

	@Test void sensitiveMessageInputsAreRedactedButReferencesRemain() {
		AKeyPair key = AKeyPair.generate();
		put("key", key.getSeed().toHexString());
		String message = "confidential signing and encryption input";
		Job signed = job("sign", Maps.of("seed", "s/key", "data", message, "encoding", "utf8"));
		signed.awaitResult(5000);
		assertFalse(signed.getData().toString().contains(message));
		assertEquals(Strings.create("s/key"), RT.getIn(signed.getData(), "input", "seed"));
		Job encrypted = job("encrypt", Maps.of("accountKey", key.getAccountKey().toHexString(), "data", message, "encoding", "utf8"));
		ACell envelope = encrypted.awaitResult(5000);
		assertFalse(encrypted.getData().toString().contains(message));
		Job invalidDestination = failure("decrypt", Maps.of("seed", "s/key",
			"ciphertext", hex(envelope, "ciphertext"), "name", null));
		assertEquals("name" + CryptoAdapter.REQUIRED_STRING, invalidDestination.getErrorMessage());
		assertFalse(invalidDestination.getData().toString().contains(message));
	}

	@Test void getMineAndCad025KnownDerivationVectors() {
		put("phrase", "  " + PHRASE.toUpperCase(java.util.Locale.ROOT).replace(" ", "\t ") + "  ");
		ACell derived = run("derive-key", Maps.of("mnemonic", "s/phrase", "name", "venue"));
		assertEquals("m/44'/864'/0'/0'/0'", text(derived, "path"));
		assertEquals("401fc829f23f2ed4e37ec18c90df9ed6a8a7de99072460762b0387d12bb7dfd2", engine.resolveSecret("s/venue", ctx));
		assertEquals("036598af84b625f6f36753eda9de6730f7d828cf14994dc55e87ecf3c22674e9", hex(derived, "accountKey"));
		run("derive-key", Maps.of("mnemonic", "s/phrase", "name", "did", "path", "m/44'/864'/1'/0'/0'"));
		assertEquals("14375e75e206f045649e4261b49bd6bf839b389f7fdd4c1f870cac5966e9a6e0", engine.resolveSecret("s/did", ctx));
		run("derive-key", Maps.of("mnemonic", "s/phrase", "name", "legacy", "path", "m"));
		assertEquals("560f9f3c94558b6551928bb781cf6092c6b8800b4fc544af2c9444ed126d51aa", engine.resolveSecret("s/legacy", ctx));
		put("cad", "hold round save brand meat deposit armed idea taste reunion silent pair estate ladder copper");
		put("pass", "test");
		run("derive-key", Maps.of("mnemonic", "s/cad", "passphraseSecret", "s/pass", "path", "m/44/864/1234/0/1", "name", "cadkey"));
		// Independently verified using Python hashlib.pbkdf2_hmac + hmac + struct.
		assertEquals("23a4270c0336913c75ce391e6ec69d50d0a1b87fb1760dfd6bbc789b51905ddd", engine.resolveSecret("s/cadkey", ctx));
		// CAD025's printed expected seed actually belongs to the historical 888
		// path in Convex's SLIP10Test, not the 864 path printed on the web page.
		run("derive-key", Maps.of("mnemonic", "s/cad", "passphraseSecret", "s/pass", "path", "m/44/888/1234/0/1", "name", "old-coin"));
		assertEquals("2172bb864deb4f978ad6360beefe205d38a6839c011dc4f37592769007c8321f", engine.resolveSecret("s/old-coin", ctx));
	}

	@Test void arbitraryCoinTypesAndPathShapesAreSupported() {
		put("phrase", PHRASE);
		// Independently derived with Python hashlib/HMAC, not Convex's implementation.
		String[][] vectors = {
			{"m/44/1234567/0/0/0", "m/44'/1234567'/0'/0'/0'",
				"dc6fcef57ca626e8b293957f48cfd451129a864af1b3a94a1b9e85989d323aab"},
			{"m/7'/1234567/2147483647'", "m/7'/1234567'/2147483647'",
				"1dbce27d4f928a3c1fc32ae8ff45dc065c854a67b554b92b72e0e6c098981b3e"}
		};
		for (int i = 0; i < vectors.length; i++) {
			String name = "custom" + i;
			ACell result = run("derive-key", Maps.of("mnemonic", "s/phrase",
				"name", name, "path", vectors[i][0]));
			assertEquals(vectors[i][1], text(result, "path"));
			assertEquals(vectors[i][2], engine.resolveSecret("s/" + name, ctx));
		}
	}

	@Test void passphraseNfkdAndPathValidation() {
		put("phrase", PHRASE);
		put("composed", "caf\u00e9");
		put("decomposed", "cafe\u0301");
		run("derive-key", Maps.of("mnemonic", "s/phrase", "passphraseSecret", "s/composed", "name", "a"));
		run("derive-key", Maps.of("mnemonic", "s/phrase", "passphraseSecret", "s/decomposed", "name", "b"));
		run("derive-key", Maps.of("mnemonic", "s/phrase", "name", "empty"));
		assertEquals(engine.resolveSecret("s/a", ctx), engine.resolveSecret("s/b", ctx));
		assertNotEquals(engine.resolveSecret("s/a", ctx), engine.resolveSecret("s/empty", ctx));
		for (String path : new String[] {"", "M/0", "m/", "m//0", "m/-1", "m/+1", "m/2147483648", "m/0h", "m" + "/0".repeat(256)}) {
			assertEquals(CryptoAdapter.INVALID_PATH, failure("derive-key", Maps.of("mnemonic", "s/phrase", "name", "bad", "path", path)).getErrorMessage());
		}
		assertNull(engine.resolveSecret("s/bad", ctx));
	}

	@Test void invalidMnemonicNeverLeaksAWordOrDerivesAKey() {
		for (String phrase : new String[] {"abandon ".repeat(12).strip(), "sensitiveword ".repeat(11) + "about", "abandon abandon abandon"}) {
			put("bad", phrase);
			assertEquals(CVMBool.FALSE, RT.getIn(run("validate-mnemonic", Maps.of("mnemonic", "s/bad")), "valid"));
			Job job = failure("derive-key", Maps.of("mnemonic", "s/bad", "name", "no-key"));
			assertEquals(CryptoAdapter.INVALID_MNEMONIC, job.getErrorMessage());
			assertFalse(job.getData().toString().contains("sensitiveword"));
		}
		assertNull(engine.resolveSecret("s/no-key", ctx));
		put("good", PHRASE);
		assertEquals(CVMBool.TRUE, RT.getIn(run("validate-mnemonic", Maps.of("mnemonic", "s/good")), "valid"));
		failure("derive-key", Maps.of("mnemonic", PHRASE, "name", "no-key"));
	}

	@Test void malformedBytesAndSecretReferencesFailSafely() {
		for (String bad : new String[] {"0", "0xz1", "00 01", "0x0x00"}) {
			assertEquals(CryptoAdapter.INVALID_BYTES, failure("hash", Maps.of("data", bad, "encoding", "hex")).getErrorMessage());
		}
		failure("hash", Maps.of("data", "!", "encoding", "base64"));
		failure("hash", Maps.of("data", "", "encoding", "guess"));
		failure("hash", Maps.of("data", "", "algorithm", "md5"));
		failure("hash", Maps.of("data", "x".repeat(1024 * 1024 + 1)));
		failure("verify", Maps.of("data", "", "accountKey", "00".repeat(32), "signature", "00"));
		assertEquals(CryptoAdapter.SECRET_REF_REQUIRED, failure("public-key", Maps.of("seed", "00".repeat(32))).getErrorMessage());
		assertEquals(CryptoAdapter.SECRET_MISSING, failure("public-key", Maps.of("seed", "s/missing")).getErrorMessage());
		put("malformed", "do-not-echo-this-private-value");
		Job bad = failure("public-key", Maps.of("seed", "s/malformed"));
		assertEquals(CryptoAdapter.INVALID_BYTES, bad.getErrorMessage());
		assertFalse(bad.getData().toString().contains("do-not-echo-this-private-value"));
		failure("generate-key", Maps.of("name", "path/child"));
		failure("generate-key", Maps.of("name", "bad", "overwrite", "true"));
	}

	@Test void invokeAndSecretWriteAreIndependentGates() {
		RequestContext original = ctx;
		ctx = original.withCaps(Vectors.empty());
		failure("hash", Maps.of("data", ""));
		failure("generate-key", Maps.of("name", "denied"));
		ctx = original.withCaps(Vectors.of(Capability.create(null, Strings.create("invoke"))));
		run("hash", Maps.of("data", ""));
		failure("generate-key", Maps.of("name", "denied"));
		assertNull(engine.resolveSecret("s/denied", original));
		ctx = original.withCaps(Vectors.of(Capability.create(null, Strings.create("invoke")),
			Capability.create(Strings.create("s/allowed"), Abilities.SECRET_WRITE)));
		run("generate-key", Maps.of("name", "allowed"));
		failure("generate-mnemonic", Maps.of("name", "denied"));
	}

	@Test void crossUserUseRequiresGrantAndCannotShedDestinationCaveats() {
		put("key", AKeyPair.generate().getSeed().toHexString());
		String ref = ctx.getUserDID() + "/s/key";
		AKeyPair bob = AKeyPair.generate();
		ctx = RequestContext.of(UCAN.toDIDKey(bob.getAccountKey()));
		engine.getVenueState().users().ensure(ctx.getUserDID());
		failure("sign", Maps.of("seed", ref, "data", "hello", "encoding", "utf8"));
		long exp = System.currentTimeMillis() / 1000 + 3600;
		UCAN bound = UCAN.create(identity, bob.getAccountKey(), exp,
			Vectors.of(Capability.create(Strings.create(ref), Abilities.SECRET_USE,
				Maps.of("url", "https://example.com/"))), Vectors.empty());
		ctx = ctx.withProofs(Vectors.of(bound.toMap()));
		failure("sign", Maps.of("seed", ref, "data", "hello", "encoding", "utf8"));
		UCAN unbound = UCAN.create(identity, bob.getAccountKey(), exp,
			Vectors.of(Capability.create(Strings.create(ref), Abilities.SECRET_USE)), Vectors.empty());
		ctx = ctx.withProofs(Vectors.of(unbound.toMap()));
		assertNotNull(RT.getIn(run("sign", Maps.of("seed", ref, "data", "hello", "encoding", "utf8")), "signature"));
	}

	@Test void concurrentGenerationHasOneWinner() throws Exception {
		Job one = job("generate-key", Maps.of("name", "race"));
		Job two = job("generate-key", Maps.of("name", "race"));
		CompletableFuture.allOf(one.future().handle((v, e) -> null), two.future().handle((v, e) -> null))
			.get(5, java.util.concurrent.TimeUnit.SECONDS);
		assertNotEquals(one.future().isCompletedExceptionally(), two.future().isCompletedExceptionally());
		Job winner = one.future().isCompletedExceptionally() ? two : one;
		assertEquals(AKeyPair.create(engine.resolveSecret("s/race", ctx)).getAccountKey().toHexString(), hex(winner.getOutput(), "accountKey"));
	}

	@Test void jwtSigningInteroperatesWithConvexAndJdk() throws Exception {
		put("jwt-key", identity.getSeed().toHexString());
		AMap<AString, ACell> claims = Maps.of("iss", "issuer", "sub", "subject",
			"aud", Vectors.of("another-service", "service"), "exp", 4000000000L,
			"nbf", 1, "iat", 2.5, "nested", Maps.of("bytes", Blob.fromHex("0123")));
		ACell signed = run("jwt-sign", Maps.of("seed", "s/jwt-key", "claims", claims));
		AString token = (AString) RT.getIn(signed, "token");
		assertEquals(JWT.signPublic(claims, identity), token);
		assertEquals(identity.getAccountKey(), RT.getIn(signed, "accountKey"));
		JWT parsed = JWT.parse(token);
		assertTrue(parsed.verifyEdDSA(identity.getAccountKey()));
		// Independent JDK Ed25519 verification, using the raw public key's SPKI.
		Signature verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
			Blob.fromHex("302a300506032b6570032100" + identity.getAccountKey().toHexString()).getBytes())));
		verifier.update(parsed.getSigningInput().getBytes(StandardCharsets.UTF_8));
		assertTrue(verifier.verify(parsed.getSignatureBytes()));
		for (ACell key : new ACell[] {identity.getAccountKey(), Strings.create(identity.getAccountKey().toHexString()),
			Strings.create("0x" + identity.getAccountKey().toHexString())}) {
			ACell verified = run("jwt-verify", Maps.of("token", token, "accountKey", key,
				"expectedIssuer", "issuer", "expectedAudience", "service", "requireExpiration", true));
			assertEquals(CVMBool.TRUE, RT.getIn(verified, "valid"));
			assertEquals(JSON.parse(JSON.toString(claims)), RT.getIn(verified, "claims"));
		}
		AString kid = Strings.create("did:web:example.com#key-1");
		ACell custom = run("jwt-sign", Maps.of("seed", "s/jwt-key", "claims", Maps.empty(), "kid", kid));
		assertEquals(kid, JWT.parse((AString) RT.getIn(custom, "token")).getHeader().get(JWT.KID));
	}

	/** Builds an inbound fixture independently of Convex's JWS signing implementation. */
	private AString jdkJwt(AMap<AString, ACell> header, AMap<AString, ACell> claims) throws Exception {
		Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
		String input = encoder.encodeToString(JSON.toAString(header).getBytes()) + "."
			+ encoder.encodeToString(JSON.toAString(claims).getBytes());
		Signature signer = Signature.getInstance("Ed25519");
		signer.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(
			Blob.fromHex("302e020100300506032b657004220420" + identity.getSeed().toHexString()).getBytes())));
		signer.update(input.getBytes(StandardCharsets.UTF_8));
		return Strings.create(input + "." + encoder.encodeToString(signer.sign()));
	}

	private void jwtRejected(AString token, String reason, AMap<AString, ACell> options) {
		ACell result = run("jwt-verify", options.assoc(Strings.create("token"), token)
			.assoc(Strings.create("accountKey"), identity.getAccountKey()));
		assertEquals(CVMBool.FALSE, RT.getIn(result, "valid"));
		assertEquals(reason, text(result, "reason"));
		assertNull(RT.getIn(result, "claims"));
		assertNull(RT.getIn(result, "header"));
	}

	@Test void jwtTrustIsPinnedAndHeadersCannotSelectAnAlgorithmOrKey() throws Exception {
		// No kid required: a JDK-created token verifies with the caller's key.
		AString token = jdkJwt(Maps.of("alg", "EdDSA"), Maps.of("sub", "subject"));
		assertEquals(CVMBool.TRUE, RT.getIn(run("jwt-verify",
			Maps.of("token", token, "accountKey", identity.getAccountKey())), "valid"));
		ACell wrongKey = run("jwt-verify", Maps.of("token", token, "accountKey", AKeyPair.generate().getAccountKey()));
		assertEquals(CryptoAdapter.JWT_SIGNATURE, text(wrongKey, "reason"));
		failure("jwt-verify", Maps.of("token", JWT.signPublic(Maps.empty(), identity)));
		AString attacker = JWT.signPublic(Maps.of("sub", ctx.getUserDID()), AKeyPair.generate());
		jwtRejected(attacker, CryptoAdapter.JWT_SIGNATURE, Maps.empty());
		for (String algorithm : new String[] {"none", "HS256", "RS256", "Ed25519"}) {
			jwtRejected(jdkJwt(Maps.of("alg", algorithm), Maps.empty()), CryptoAdapter.JWT_ALGORITHM, Maps.empty());
		}
		jwtRejected(JWT.signSymmetric(Maps.empty(), identity.getAccountKey()), CryptoAdapter.JWT_ALGORITHM, Maps.empty());
		for (AMap<AString, ACell> header : java.util.List.<AMap<AString, ACell>>of(
			Maps.of("alg", "EdDSA", "crit", Vectors.of("unknown"), "unknown", true),
			Maps.of("alg", "EdDSA", "b64", false))) {
			jwtRejected(jdkJwt(header, Maps.empty()), CryptoAdapter.JWT_EXTENSIONS, Maps.empty());
		}
		JWT original = JWT.parse(token);
		AString tampered = JWT.build(JSON.toAString(original.getHeader()), JSON.toAString(Maps.of("sub", "attacker")),
			Blob.wrap(original.getSignatureBytes()));
		jwtRejected(tampered, CryptoAdapter.JWT_SIGNATURE, Maps.empty());
	}

	@Test void jwtIssuerAudienceAndTimeChecks() {
		AMap<AString, ACell> claims = Maps.of("iss", "issuer", "aud", "service", "exp", 4000000000L, "nbf", 0);
		AString token = JWT.signPublic(claims, identity);
		assertEquals(CVMBool.TRUE, RT.getIn(run("jwt-verify", Maps.of("token", token,
			"accountKey", identity.getAccountKey(), "expectedIssuer", "issuer", "expectedAudience", "service")), "valid"));
		jwtRejected(token, CryptoAdapter.JWT_ISSUER, Maps.of("expectedIssuer", "Issuer"));
		jwtRejected(token, CryptoAdapter.JWT_AUDIENCE, Maps.of("expectedAudience", "Service"));
		jwtRejected(JWT.signPublic(Maps.empty(), identity), CryptoAdapter.JWT_ISSUER, Maps.of("expectedIssuer", "issuer"));
		jwtRejected(JWT.signPublic(Maps.empty(), identity), CryptoAdapter.JWT_AUDIENCE, Maps.of("expectedAudience", "service"));
		jwtRejected(JWT.signPublic(Maps.empty(), identity), CryptoAdapter.JWT_EXPIRATION_REQUIRED, Maps.of("requireExpiration", true));
		jwtRejected(JWT.signPublic(Maps.of("exp", 0), identity), CryptoAdapter.JWT_EXPIRED, Maps.empty());
		jwtRejected(JWT.signPublic(Maps.of("exp", 0.5), identity), CryptoAdapter.JWT_EXPIRED, Maps.empty());
		jwtRejected(JWT.signPublic(Maps.of("nbf", 4000000000.5), identity), CryptoAdapter.JWT_NOT_YET_VALID, Maps.empty());
		// iat is descriptive, not a second nbf; numeric future iat is permitted.
		assertEquals(CVMBool.TRUE, RT.getIn(run("jwt-verify", Maps.of("token",
			JWT.signPublic(Maps.of("iat", 4000000000L), identity), "accountKey", identity.getAccountKey())), "valid"));
	}

	@Test void jwtRejectsMalformedRegisteredClaims() {
		for (String field : new String[] {"exp", "nbf", "iat"}) {
			for (ACell value : new ACell[] {null, Strings.create("4000000000"), CVMBool.TRUE, Vectors.of(0)}) {
				jwtRejected(JWT.signPublic(Maps.of(field, value), identity), CryptoAdapter.JWT_DATE, Maps.empty());
			}
		}
		for (String field : new String[] {"iss", "sub", "jti"}) {
			jwtRejected(JWT.signPublic(Maps.of(field, null), identity), CryptoAdapter.JWT_STRING_CLAIM, Maps.empty());
		}
		for (ACell audience : new ACell[] {null, CVMBool.TRUE, Vectors.of("service", 1)}) {
			jwtRejected(JWT.signPublic(Maps.of("aud", audience), identity), CryptoAdapter.JWT_AUDIENCE_FORMAT, Maps.empty());
		}
	}

	@Test void jwtDecodeAlwaysMarksClaimsUnverified() {
		AString token = JWT.build(JSON.toAString(Maps.of("alg", "none")), JSON.toAString(Maps.of("admin", true)), Blob.EMPTY);
		ACell decoded = run("jwt-decode", Maps.of("token", token));
		assertEquals(CVMBool.FALSE, RT.getIn(decoded, "verified"));
		assertEquals(CVMBool.TRUE, RT.getIn(decoded, "claims", "admin"));
		assertEquals(Blob.EMPTY, RT.getIn(decoded, "signature"));
		jwtRejected(token, CryptoAdapter.JWT_ALGORITHM, Maps.empty());
		JWT signed = JWT.parse(JWT.signPublic(Maps.empty(), identity));
		ACell inspected = run("jwt-decode", Maps.of("token", signed.getRaw()));
		assertEquals(CVMBool.FALSE, RT.getIn(inspected, "verified"));
		assertEquals(Blob.wrap(signed.getSignatureBytes()), RT.getIn(inspected, "signature"));
	}

	@Test void jwtTokensCanStayInSecretStoreAndInputsAreRedacted() {
		put("jwt-key", identity.getSeed().toHexString());
		AMap<AString, ACell> input = Maps.of("seed", "s/jwt-key", "name", "jwt-token",
			"claims", Maps.of("sub", "private-jwt-claim"));
		Job signed = job("jwt-sign", input);
		ACell output = signed.awaitResult(5000);
		assertEquals("s/jwt-token", text(output, "secret"));
		assertNull(RT.getIn(output, "token"));
		String token = engine.resolveSecret("s/jwt-token", ctx);
		assertNotNull(JWT.parse(Strings.create(token)));
		assertFalse(signed.getData().toString().contains(token));
		assertFalse(signed.getData().toString().contains(identity.getSeed().toHexString()));
		assertFalse(signed.getData().toString().contains("private-jwt-claim"));
		ACell verified = run("jwt-verify", Maps.of("tokenSecret", "s/jwt-token", "accountKey", identity.getAccountKey()));
		assertEquals(CVMBool.TRUE, RT.getIn(verified, "valid"));
		run("jwt-decode", Maps.of("tokenSecret", "s/jwt-token"));
		Job inspected = job("jwt-decode", Maps.of("token", token));
		inspected.awaitResult(5000);
		assertFalse(inspected.getData().toString().contains(token));
		failure("jwt-sign", input);
		assertEquals(token, engine.resolveSecret("s/jwt-token", ctx));
		run("jwt-sign", input.assoc(Strings.create("overwrite"), CVMBool.TRUE));
		failure("jwt-sign", input.assoc(Strings.create("name"), null));
	}

	@Test void jwtMalformedInputsFailWithoutEchoingTokenData() {
		for (String bad : new String[] {"private-token-data", "e30.e30", "e30.e30.AA.extra", "e30=.e30.AA", "e30.e30. AA", "W10.e30.AA", "e30.W10.AA"}) {
			for (String op : new String[] {"jwt-decode", "jwt-verify"}) {
				Job rejected = failure(op, Maps.of("token", bad, "accountKey", identity.getAccountKey()));
				assertEquals(CryptoAdapter.INVALID_JWT, rejected.getErrorMessage());
				assertFalse(rejected.getData().toString().contains(bad));
			}
		}
		assertEquals(CryptoAdapter.TOO_LARGE, failure("jwt-decode", Maps.of("token", "a".repeat(1024 * 1024 + 1))).getErrorMessage());
		AString token = JWT.signPublic(Maps.empty(), identity);
		failure("jwt-decode", Maps.of("token", token, "tokenSecret", "s/token"));
		failure("jwt-decode", Maps.empty());
		failure("jwt-decode", Maps.of("tokenSecret", token));
		failure("jwt-decode", Maps.of("tokenSecret", "s/missing"));
		failure("jwt-decode", Maps.of("token", Blob.EMPTY));
		for (String option : new String[] {"expectedIssuer", "expectedAudience", "requireExpiration"}) {
			failure("jwt-verify", Maps.of("token", token, "accountKey", identity.getAccountKey(), option, null));
		}
		assertEquals(CryptoAdapter.SECRET_REF_REQUIRED, failure("jwt-sign",
			Maps.of("seed", identity.getSeed().toHexString(), "claims", Maps.empty())).getErrorMessage());
		assertEquals(CryptoAdapter.INVALID_CLAIMS, failure("jwt-sign", Maps.of("seed", "s/key", "claims", Vectors.empty())).getErrorMessage());
		assertEquals(CryptoAdapter.INVALID_CLAIMS, failure("jwt-sign", Maps.of("seed", "s/key", "claims", Maps.of(1, "value"))).getErrorMessage());
		assertEquals(CryptoAdapter.JWT_DATE, failure("jwt-sign", Maps.of("seed", "s/key", "claims", Maps.of("exp", "tomorrow"))).getErrorMessage());
	}

	@Test void jwtSigningAndTokenSecretsRespectCapabilities() {
		put("jwt-key", identity.getSeed().toHexString());
		put("jwt-token", JWT.signPublic(Maps.empty(), identity).toString());
		RequestContext owner = ctx;
		String seedRef = owner.getUserDID() + "/s/jwt-key";
		String tokenRef = owner.getUserDID() + "/s/jwt-token";
		ctx = owner.withCaps(Vectors.empty());
		failure("jwt-sign", Maps.of("seed", "s/jwt-key", "claims", Maps.empty()));
		failure("jwt-verify", Maps.of("tokenSecret", "s/jwt-token", "accountKey", identity.getAccountKey()));
		failure("jwt-decode", Maps.of("tokenSecret", "s/jwt-token"));
		ctx = owner.withCaps(Vectors.of(Capability.create(null, Strings.create("invoke"))));
		failure("jwt-sign", Maps.of("seed", "s/jwt-key", "claims", Maps.empty(), "name", "no-write"));
		assertNull(engine.resolveSecret("s/no-write", owner));
		AKeyPair bob = AKeyPair.generate();
		RequestContext other = RequestContext.of(UCAN.toDIDKey(bob.getAccountKey()));
		engine.getVenueState().users().ensure(other.getUserDID());
		ctx = other;
		for (String ref : new String[] {seedRef, tokenRef}) {
			String op = ref.equals(seedRef) ? "jwt-sign" : "jwt-decode";
			ACell args = ref.equals(seedRef) ? Maps.of("seed", ref, "claims", Maps.empty()) : Maps.of("tokenSecret", ref);
			ctx = other;
			failure(op, args);
			for (boolean bound : new boolean[] {true, false}) {
				AMap<AString, ACell> cap = bound ? Capability.create(Strings.create(ref), Abilities.SECRET_USE, Maps.of("url", "https://example.com/"))
					: Capability.create(Strings.create(ref), Abilities.SECRET_USE);
				UCAN grant = UCAN.create(identity, bob.getAccountKey(), System.currentTimeMillis() / 1000 + 3600,
					Vectors.of(cap), Vectors.empty());
				ctx = other.withProofs(Vectors.of(grant.toMap()));
				if (bound) failure(op, args); else run(op, args);
			}
		}
	}

	@Test void discoveryMirrorsAndLifecycle() {
		var adapter = engine.getAdapter("crypto");
		assertEquals(13, adapter.getOperations().count());
		for (AString op : adapter.getOperations().getKeys()) {
			ACell metadata = engine.jobs().invokeOperation("v/ops/covia/read", Maps.of("path", "v/ops/crypto/" + op), ctx).awaitResult(5000);
			ACell mirror = engine.jobs().invokeOperation("v/ops/covia/read", Maps.of("path", "v/adapters/crypto/ops/" + op), ctx).awaitResult(5000);
			assertEquals(metadata, mirror);
			assertEquals(adapter.getOperations().get(op), RT.getIn(metadata, "value"));
			assertEquals(Strings.create("object"), RT.getIn(metadata, "value", "operation", "input", "type"));
		}
		assertEquals(CVMBool.TRUE, RT.getIn(engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of("path", "v/skills/crypto/crypto"), ctx).awaitResult(5000), "exists"));
		run("generate-key", Maps.of("name", "survives"));
		String seed = engine.resolveSecret("s/survives", ctx);
		try {
			assertTrue(engine.disableAdapter("crypto"));
			assertThrows(Exception.class, () -> run("public-key", Maps.of("seed", "s/survives")));
		} finally { engine.enableAdapter("crypto"); }
		assertEquals(seed, engine.resolveSecret("s/survives", ctx));
		run("public-key", Maps.of("seed", "s/survives"));
	}
}
