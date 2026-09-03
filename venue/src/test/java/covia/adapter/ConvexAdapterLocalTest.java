package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/** Local contract tests for Convex key operations. Network tests remain in
 * {@link ConvexAdapterTest}; these run in the default suite without a peer. */
class ConvexAdapterLocalTest {

	private final Engine engine = TestEngine.ENGINE;
	private RequestContext ctx;

	@BeforeEach
	void setup(TestInfo info) {
		ctx = RequestContext.of(TestEngine.uniqueDID(info));
	}

	private ACell run(String operation, ACell input) {
		return engine.jobs().invokeOperation(operation, input, ctx).awaitResult(5000);
	}

	@Test
	void generateKeyStoresSeedButReturnsOnlyPublicMaterial() {
		ACell result = run("v/ops/convex/generate-key", Maps.of(Fields.NAME, "signer"));

		assertEquals("s/signer", RT.getIn(result, "secret").toString());
		String storedSeed = engine.resolveSecret("s/signer", ctx);
		assertNotNull(storedSeed);
		AKeyPair stored = AKeyPair.create(Blob.fromHex(storedSeed));
		assertEquals(stored.getAccountKey().toHexString(),
			RT.getIn(result, "accountKey").toString());
		assertFalse(result.toString().contains(storedSeed),
			"the generated seed must never be returned");
	}

	@Test
	void generateKeyRequiresExplicitOverwrite() {
		ACell first = run("v/ops/convex/generate-key", Maps.of(Fields.NAME, "protected"));
		String firstSeed = engine.resolveSecret("s/protected", ctx);

		Job refused = engine.jobs().invokeOperation("v/ops/convex/generate-key",
			Maps.of(Fields.NAME, "protected"), ctx);
		assertThrows(Exception.class, () -> refused.awaitResult(5000));
		assertTrue(refused.getErrorMessage().contains("overwrite:true"));
		assertEquals(firstSeed, engine.resolveSecret("s/protected", ctx));

		ACell replacement = run("v/ops/convex/generate-key", Maps.of(
			Fields.NAME, "protected", Fields.OVERWRITE, CVMBool.TRUE));
		assertNotEquals(firstSeed, engine.resolveSecret("s/protected", ctx));
		assertNotEquals(RT.getIn(first, "accountKey"), RT.getIn(replacement, "accountKey"));
	}

	@Test
	void signUsesStoredSeedAndProducesVerifiableUtf8Signature() {
		run("v/ops/convex/generate-key", Maps.of(Fields.NAME, "utf8-signer"));
		String message = "Covia signs exact UTF-8 \uD83C\uDF10";
		ACell result = run("v/ops/convex/sign", Maps.of(
			Fields.SEED, "s/utf8-signer", Fields.MESSAGE, message));

		AKeyPair stored = AKeyPair.create(Blob.fromHex(
			engine.resolveSecret("s/utf8-signer", ctx)));
		ASignature signature = ASignature.fromHex(RT.getIn(result, "signature").toString());
		assertTrue(signature.verify(
			Blob.wrap(message.getBytes(StandardCharsets.UTF_8)), stored.getAccountKey()));
		assertEquals(stored.getAccountKey().toHexString(),
			RT.getIn(result, "accountKey").toString());
	}

	@Test
	void signSupportsExactHexBytes() {
		run("v/ops/convex/generate-key", Maps.of(Fields.NAME, "hex-signer"));
		ACell result = run("v/ops/convex/sign", Maps.of(
			Fields.SEED, "/s/hex-signer",
			Fields.MESSAGE, "0x000102ff",
			"encoding", "hex"));

		AKeyPair stored = AKeyPair.create(Blob.fromHex(
			engine.resolveSecret("s/hex-signer", ctx)));
		ASignature signature = ASignature.fromHex(RT.getIn(result, "signature").toString());
		assertTrue(signature.verify(Blob.fromHex("000102ff"), stored.getAccountKey()));
	}

	@Test
	void signRejectsLiteralSeedsAndMissingSecrets() {
		AKeyPair keyPair = AKeyPair.generate();
		Job literal = engine.jobs().invokeOperation("v/ops/convex/sign", Maps.of(
			Fields.SEED, keyPair.getSeed().toHexString(), Fields.MESSAGE, "message"), ctx);
		assertThrows(Exception.class, () -> literal.awaitResult(5000));
		assertTrue(literal.getErrorMessage().contains("secret reference"));

		Job missing = engine.jobs().invokeOperation("v/ops/convex/sign", Maps.of(
			Fields.SEED, "s/missing", Fields.MESSAGE, "message"), ctx);
		assertThrows(Exception.class, () -> missing.awaitResult(5000));
		assertTrue(missing.getErrorMessage().contains("not found"));
	}

	@Test
	void transactDeclaresSeedRedactionAndResolvesSecretsBeforeNetworkUse() {
		ACell operation = engine.resolvePath(
			Strings.create("v/ops/convex/transact"), ctx);
		AVector<ACell> secretFields = RT.ensureVector(
			RT.getIn(operation, Fields.OPERATION, "secretFields"));
		assertNotNull(secretFields);
		assertTrue(secretFields.contains(Fields.SEED));

		Job missing = engine.jobs().invokeOperation("v/ops/convex/transact", Maps.of(
			Fields.PEER, "localhost:1",
			Fields.ADDRESS, "#13",
			Fields.SOURCE, "(def foo 1)",
			Fields.SEED, "s/missing"), ctx);
		assertThrows(Exception.class, () -> missing.awaitResult(5000));
		assertTrue(missing.getErrorMessage().contains("not found"),
			"secret resolution must fail before attempting a peer connection");
	}
}
