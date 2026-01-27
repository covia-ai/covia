package covia.adapter;

import java.util.Objects;
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
import convex.core.data.Blobs;
import convex.core.data.Hash;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import covia.api.Fields;
import covia.exception.JobFailedException;

/**
 * Adapter for interacting with the Convex network.
 *
 * Currently supports the {@code convex:query} operation, with a structure that
 * makes it easy to add further Convex operations in future.
 */
public class ConvexAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(ConvexAdapter.class);

	private Hash QUERY_OPERATION;
	private Hash TRANSACT_OPERATION;

	@Override
	public String getName() {
		return "convex";
	}

	@Override
	public String getDescription() {
		return "Enables interactions with the Convex network, including on-chain CVM queries and transactions";
	}

	@Override
	protected void installAssets() {
		QUERY_OPERATION = installAsset("/adapters/convex/query.json");
		TRANSACT_OPERATION = installAsset("/adapters/convex/transact.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input) {
		Objects.requireNonNull(operation, "Operation must not be null");

		String[] parts = operation.split(":");
		if (parts.length < 2) {
			return CompletableFuture.failedFuture(
					new IllegalArgumentException("Invalid Convex operation format: " + operation));
		}

		String op = parts[1];
		return switch (op) {
			case "query" -> invokeQuery(meta, input);
			case "transact" -> invokeTransact(meta, input);
			default -> CompletableFuture.failedFuture(
					new UnsupportedOperationException("Unsupported Convex operation: " + operation));
		};
	}

	private CompletableFuture<ACell> invokeQuery(ACell meta, ACell input) {
		return withConvexClient(meta, input, convex -> executeQuery(convex, meta, input));
	}

	private CompletableFuture<ACell> invokeTransact(ACell meta, ACell input) {
		return withConvexClient(meta, input, convex -> executeTransact(convex, meta, input));
	}

	/**
	 * Hook for the actual query implementation. 
	 */
	protected CompletableFuture<ACell> executeQuery(Convex convex, ACell meta, ACell input) {
		// Get the address from the input. If not specified
		Address address = Address.parse(RT.getIn(input, Fields.ADDRESS));

		AString source=RT.ensureString(RT.getIn(input, Fields.SOURCE));
		if (source==null) {
			return CompletableFuture.failedFuture(new JobFailedException("No query source provided"));
		}

		ACell code=Reader.read(source.toString());

		CompletableFuture<Result> resultFuture = convex.query(code, address);

		return resultFuture.thenApply(result -> {
			return RT.cvm(result.toJSON());
		});
	}

	protected CompletableFuture<ACell> executeTransact(Convex convex, ACell meta, ACell input) {
		// Get the address from the input. If not specified
		Address address = Address.parse(RT.getIn(input, Fields.ADDRESS));
		if (address==null) {
			return CompletableFuture.failedFuture(new JobFailedException("No address provided"));
		}
		
		// Get the address from the input. If not specified
		ABlob seedBlob = Blobs.parse(RT.getIn(input, Fields.SEED));
		if (seedBlob==null) {
			return CompletableFuture.failedFuture(new JobFailedException("No signing key provided provided as a Ed25519 seed"));
		}
		

		AString source=RT.ensureString(RT.getIn(input, Fields.SOURCE));
		if (source==null) {
			return CompletableFuture.failedFuture(new JobFailedException("No query source provided"));
		}

		ACell code=Reader.read(source.toString());

		convex.setAddress(address);
		convex.setKeyPair(AKeyPair.create(seedBlob.getBytes()));

		CompletableFuture<Result> resultFuture = convex.transact(code);

		return resultFuture.thenApply(result -> {
			return RT.cvm(result.toJSON());
		});
	}

	private CompletableFuture<ACell> withConvexClient(ACell meta, ACell input,
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
		});
	}

	private Convex openConvexClient(ACell meta, ACell input) throws Exception {
		AString endpoint = locateEndpoint(meta, input);
		if (endpoint == null) {
			throw new JobFailedException("No Convex endpoint provided (expected in input or metadata)");
		}
		return Convex.connect(endpoint.toString());
	}

	private AString locateEndpoint(ACell meta, ACell input) {
		// Check input first
		AString endpoint = RT.ensureString(RT.getIn(input, Fields.PEER));
		if (endpoint != null) return endpoint;

		// Then check metadata for default peer
		if (meta instanceof AMap<?, ?> map) {
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> metaMap = (AMap<AString, ACell>) map;
			endpoint = RT.ensureString(RT.getIn(metaMap, Fields.OPERATION, Fields.PEER));
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
}
