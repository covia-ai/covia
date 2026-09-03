package covia.adapter;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.cvm.Address;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.venue.RequestContext;

/**
 * Adapter for interacting with the Convex network.
 *
 * Provides network queries and transactions plus local Ed25519 key generation
 * and signing. Private seeds remain in the caller's encrypted secret store.
 */
public class ConvexAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(ConvexAdapter.class);

	/**
	 * Backstop deadline for a Convex peer call (connect + query/transact). The
	 * Convex client configures no request timeout, so without this a connected-
	 * but-non-responsive peer leaves the future — and the Job — pending forever.
	 */
	private static final long CONVEX_CALL_TIMEOUT_MS = 60_000L;

	private static final AString K_ACCOUNT_KEY = Strings.intern("accountKey");
	private static final AString K_ENCODING = Strings.intern("encoding");
	private static final AString K_SECRET = Strings.intern("secret");
	private static final AString K_SIGNATURE = Strings.intern("signature");

	private Hash QUERY_OPERATION;
	private Hash TRANSACT_OPERATION;
	private Hash GENERATE_KEY_OPERATION;
	private Hash SIGN_OPERATION;

	@Override
	public String getName() {
		return "convex";
	}

	@Override
	public String getDescription() {
		return "Convex network queries, transactions, and secret-backed Ed25519 signing";
	}

	@Override
	protected void installAssets() {
		// The adapter's own skill: v/skills/convex lives and dies with this adapter.
		installSkill("convex/convex", "/skills/convex.json");
		QUERY_OPERATION        = installAsset("convex/query",        "/adapters/convex/query.json");
		TRANSACT_OPERATION     = installAsset("convex/transact",     "/adapters/convex/transact.json");
		GENERATE_KEY_OPERATION = installAsset("convex/generate-key", "/adapters/convex/generateKey.json");
		SIGN_OPERATION         = installAsset("convex/sign",         "/adapters/convex/sign.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String op = getSubOperation(meta);
		return switch (op) {
			case "query" -> invokeQuery(meta, input);
			case "transact" -> invokeTransact(ctx, meta, input);
			case "generate-key" -> CompletableFuture.supplyAsync(
				() -> generateKey(ctx, input), VIRTUAL_EXECUTOR);
			case "sign" -> CompletableFuture.supplyAsync(
				() -> sign(ctx, input), VIRTUAL_EXECUTOR);
			default -> CompletableFuture.failedFuture(
					new UnsupportedOperationException("Unsupported Convex operation: " + op));
		};
	}

	private CompletableFuture<ACell> invokeQuery(AMap<AString, ACell> meta, ACell input) {
		final Query request;
		try {
			request = query(input);
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
		return withConvexClient(meta, input, convex -> executeQuery(convex, request));
	}

	private CompletableFuture<ACell> invokeTransact(RequestContext ctx,
			AMap<AString, ACell> meta, ACell input) {
		final Transaction request;
		try {
			request = transaction(ctx, input);
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
		return withConvexClient(meta, input, convex -> executeTransact(convex, request));
	}

	private record Query(Address address, ACell code) {}
	private record Transaction(Address address, AKeyPair keyPair, ACell code) {}

	private Query query(ACell input) {
		Address address = Address.parse(RT.getIn(input, Fields.ADDRESS));
		AString source = RT.ensureString(RT.getIn(input, Fields.SOURCE));
		if (source == null) {
			throw new JobFailedException("No query source provided");
		}
		ACell code = Reader.read(source.toString());
		if (code == null) throw new JobFailedException("Query source is empty");
		return new Query(address, code);
	}

	private Transaction transaction(RequestContext ctx, ACell input) {
		Address address = Address.parse(RT.getIn(input, Fields.ADDRESS));
		if (address == null) throw new JobFailedException("No address provided");
		AString source = RT.ensureString(RT.getIn(input, Fields.SOURCE));
		if (source == null) throw new JobFailedException("No transaction source provided");
		ACell code = Reader.read(source.toString());
		if (code == null) throw new JobFailedException("Transaction source is empty");
		return new Transaction(address, resolveKeyPair(ctx, input, false), code);
	}

	private CompletableFuture<ACell> executeQuery(Convex convex, Query request) {
		return convex.query(request.code(), request.address())
			.thenApply(ConvexAdapter::resultCell);
	}

	private CompletableFuture<ACell> executeTransact(Convex convex, Transaction request) {
		convex.setAddress(request.address());
		convex.setKeyPair(request.keyPair());
		return convex.transact(request.code()).thenApply(ConvexAdapter::resultCell);
	}

	private static ACell resultCell(Result result) {
		return RT.cvm(result.toJSON());
	}

	private ACell generateKey(RequestContext ctx, ACell input) {
		AString name = RT.ensureString(RT.getIn(input, Fields.NAME));
		if (name == null || name.toString().isBlank()) {
			throw new IllegalArgumentException("name is required");
		}
		ACell overwriteValue = RT.getIn(input, Fields.OVERWRITE);
		if (overwriteValue != null && !(overwriteValue instanceof CVMBool)) {
			throw new IllegalArgumentException("overwrite must be a boolean");
		}

		AKeyPair keyPair = AKeyPair.generate();
		SecretAdapter.storeSecret(engine, ctx, name,
			Strings.create(keyPair.getSeed().toHexString()),
			CVMBool.TRUE.equals(overwriteValue));
		return Maps.of(
			K_SECRET, Strings.create("s/" + name),
			K_ACCOUNT_KEY, Strings.create(keyPair.getAccountKey().toHexString()));
	}

	private ACell sign(RequestContext ctx, ACell input) {
		AString message = RT.ensureString(RT.getIn(input, Fields.MESSAGE));
		if (message == null) throw new IllegalArgumentException("message is required");
		AString encodingValue = RT.ensureString(RT.getIn(input, K_ENCODING));
		String encoding = (encodingValue != null) ? encodingValue.toString() : "utf8";
		Blob bytes = switch (encoding) {
			case "utf8" -> Blob.wrap(message.toString().getBytes(StandardCharsets.UTF_8));
			case "hex" -> parseBlob(message, "message");
			default -> throw new IllegalArgumentException("encoding must be 'utf8' or 'hex'");
		};
		AKeyPair keyPair = resolveKeyPair(ctx, input, true);
		ASignature signature = keyPair.sign(bytes);
		return Maps.of(
			K_ACCOUNT_KEY, Strings.create(keyPair.getAccountKey().toHexString()),
			K_SIGNATURE, Strings.create(signature.toHexString()));
	}

	/** Resolves a transaction/signing seed. Transactions retain literal-seed
	 * compatibility, but agent-facing signing accepts only a secret reference so
	 * raw private material never needs to enter its job input. */
	private AKeyPair resolveKeyPair(RequestContext ctx, ACell input, boolean requireSecretRef) {
		AString supplied = RT.ensureString(RT.getIn(input, Fields.SEED));
		if (supplied == null) throw new JobFailedException("No Ed25519 signing seed provided");
		String value = supplied.toString();
		boolean secretRef = value.startsWith("s/") || value.startsWith("/s/");
		if (requireSecretRef && !secretRef) {
			throw new IllegalArgumentException("seed must be an s/<name> secret reference");
		}
		if (secretRef) {
			String resolved = engine.resolveSecret(value, ctx);
			if (resolved == null) throw new JobFailedException(
				"Convex signing seed not found in the caller's secret store: " + value);
			value = resolved;
		}
		Blob seed = parseBlob(Strings.create(value), "seed");
		if (seed.count() != AKeyPair.SEED_LENGTH) {
			throw new IllegalArgumentException("Ed25519 seed must be exactly "
				+ AKeyPair.SEED_LENGTH + " bytes");
		}
		return AKeyPair.create(seed);
	}

	private static Blob parseBlob(AString value, String field) {
		ABlob parsed = Blobs.parse(value);
		if (parsed == null) throw new IllegalArgumentException(
			field + " must be a hexadecimal blob");
		return parsed.toFlatBlob();
	}

	private CompletableFuture<ACell> withConvexClient(AMap<AString, ACell> meta, ACell input,
			Function<Convex, CompletableFuture<ACell>> action) {

		return CompletableFuture.supplyAsync(() -> {
			try {
				return openConvexClient(meta, input);
			} catch (Exception e) {
				throw new CompletionException(
						new JobFailedException("Failed to connect to Convex peer: " + e.getMessage()));
			}
		}, VIRTUAL_EXECUTOR).thenCompose(convex -> {
			CompletableFuture<ACell> resultFuture;
			try {
				resultFuture = action.apply(convex);
			} catch (Exception e) {
				resultFuture = CompletableFuture.failedFuture(e);
			}

			return resultFuture.whenComplete((result, error) -> closeQuietly(convex));
		})
		// Backstop so a non-responsive Convex peer cannot leave the Job pending
		// forever (the Convex client sets no request timeout of its own).
		.orTimeout(CONVEX_CALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
	}

	private Convex openConvexClient(AMap<AString, ACell> meta, ACell input) throws Exception {
		AString endpoint = locateEndpoint(meta, input);
		if (endpoint == null) {
			throw new JobFailedException("No Convex endpoint provided (expected in input or metadata)");
		}
		return Convex.connect(endpoint.toString());
	}

	private AString locateEndpoint(AMap<AString, ACell> meta, ACell input) {
		// Check input first
		AString endpoint = RT.ensureString(RT.getIn(input, Fields.PEER));
		if (endpoint != null) return endpoint;

		// Then check metadata for default peer
		if (meta != null) {
			endpoint = RT.ensureString(RT.getIn(meta, Fields.OPERATION, Fields.PEER));
			if (endpoint != null) return endpoint;
		}

		return null;
	}

	private void closeQuietly(AutoCloseable closeable) {
		if (closeable == null) return;
		try {
			closeable.close();
		} catch (Exception e) {
			log.warn("Failed to close Convex client: {}", e.getMessage());
		}
	}

	public Hash getQueryOperation() {
		return QUERY_OPERATION;
	}

	public Hash getTransactOperation() {
		return TRANSACT_OPERATION;
	}

	public Hash getGenerateKeyOperation() {
		return GENERATE_KEY_OPERATION;
	}

	public Hash getSignOperation() {
		return SIGN_OPERATION;
	}
}
