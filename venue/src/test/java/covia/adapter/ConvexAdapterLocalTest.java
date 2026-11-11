package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/** Local contract tests for Convex data and key operations. Network tests
 * remain in {@link ConvexAdapterTest}; these run in the default suite without
 * a peer. */
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

	/** Why a call failed, asserting that it did. */
	private String refusal(String operation, ACell input) {
		Job job = engine.jobs().invokeOperation(operation, input, ctx);
		assertThrows(Exception.class, () -> job.awaitResult(5000));
		assertEquals(Status.FAILED, job.getStatus());
		return job.getErrorMessage();
	}

	private static final String QUERY = "v/ops/convex/query";
	private static final String TRANSACT = "v/ops/convex/transact";
	/** A literal seed; these calls are refused before it is used. */
	private static final String SEED = "0xB5232CF710Aaa222F2C898105d06d58283f91173D668C313b72dD90f0175E622";

	// A refused source or address fails the call before any peer is dialled

	@Test
	void queryAndTransactRequireASource() {
		assertEquals(ConvexAdapter.NO_SOURCE, refusal(QUERY, Maps.of(Fields.ADDRESS, "#13")));
		assertEquals(ConvexAdapter.NO_SOURCE, refusal(QUERY, Maps.of(Fields.SOURCE, 42L)));
		assertEquals(ConvexAdapter.NO_SOURCE, refusal(TRANSACT, Maps.of(
			Fields.ADDRESS, "#13", Fields.SEED, SEED)));
	}

	@Test
	void sourceThatIsNotOneFormIsInvalidInput() {
		for (String source : new String[] {"", "  \n", ";; only a comment", "(+ 1 2", "1 2", ")"}) {
			String query = refusal(QUERY, Maps.of(Fields.SOURCE, source));
			assertTrue(query.startsWith(ConvexAdapter.UNREADABLE_SOURCE), source + ": " + query);
			assertTrue(query.length() > ConvexAdapter.UNREADABLE_SOURCE.length(), "the reader's reason follows: " + query);

			String transact = refusal(TRANSACT, Maps.of(
				Fields.ADDRESS, "#13", Fields.SOURCE, source, Fields.SEED, SEED));
			assertTrue(transact.startsWith(ConvexAdapter.UNREADABLE_SOURCE), source + ": " + transact);
		}
	}

	@Test
	void transactRequiresAnAddress() {
		assertEquals(ConvexAdapter.NO_ADDRESS, refusal(TRANSACT, Maps.of(
			Fields.SOURCE, "(def foo 1)", Fields.SEED, SEED)));
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
		assertFalse(literal.getErrorMessage().isBlank());

		Job missing = engine.jobs().invokeOperation("v/ops/convex/sign", Maps.of(
			Fields.SEED, "s/missing", Fields.MESSAGE, "message"), ctx);
		assertThrows(Exception.class, () -> missing.awaitResult(5000));
		assertTrue(missing.getErrorMessage().contains("s/missing"));
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
		assertTrue(missing.getErrorMessage().contains("s/missing"),
			"secret resolution must fail before attempting a peer connection");
	}

	@Test
	void cad3RoundTripsACompleteMultiCellDataStructure() {
		ACell value = Maps.of(
			"kind", "multi-cell",
			"payload", Strings.create("abcdef".repeat(2_000)));

		ACell encoded = run("v/ops/convex/encode-cad3", Maps.of(Fields.VALUE, value));
		AString encoding = RT.ensureString(RT.getIn(encoded, "encoding"));
		assertNotNull(encoding);
		assertTrue(encoding.toString().startsWith("0x"));
		Blob bytes = Blob.parse(encoding.toString());
		assertTrue(bytes.count() > Cells.getEncodingLength(value),
			"test value should require child cells beyond its top-level encoding");

		ACell decoded = run("v/ops/convex/decode-cad3",
			Maps.of("encoding", encoding));
		assertEquals(value, decoded);
	}

	@Test
	void decodeCad3AcceptsSingleCellEncodingWithoutPrefix() {
		ACell value = Maps.of("answer", 42L);
		String encoding = Format.encodeMultiCell(value, true).toHexString();

		assertEquals(value, run("v/ops/convex/decode-cad3",
			Maps.of("encoding", encoding)));
	}

	@Test
	void decodeCad3RejectsMalformedAndIncompleteMessages() {
		Job malformed = engine.jobs().invokeOperation("v/ops/convex/decode-cad3",
			Maps.of("encoding", "0xzz"), ctx);
		assertThrows(Exception.class, () -> malformed.awaitResult(5000));
		assertFalse(malformed.getErrorMessage().isBlank());

		ACell value = Maps.of("payload", Strings.create("abcdef".repeat(2_000)));
		String topCellOnly = Cells.getEncoding(value).toString();
		Job incomplete = engine.jobs().invokeOperation("v/ops/convex/decode-cad3",
			Maps.of("encoding", topCellOnly), ctx);
		assertThrows(Exception.class, () -> incomplete.awaitResult(5000));
		assertFalse(incomplete.getErrorMessage().isBlank());
	}

	@Test
	void encodeCad3DistinguishesMissingValueFromExplicitNull() {
		Job missing = engine.jobs().invokeOperation("v/ops/convex/encode-cad3",
			Maps.empty(), ctx);
		assertThrows(Exception.class, () -> missing.awaitResult(5000));
		assertFalse(missing.getErrorMessage().isBlank());

		ACell encodedNull = run("v/ops/convex/encode-cad3",
			Maps.of(Fields.VALUE, null));
		assertEquals("0x00", RT.getIn(encodedNull, "encoding").toString());
		assertNull(run("v/ops/convex/decode-cad3",
			Maps.of("encoding", "0x00")));
	}
}
