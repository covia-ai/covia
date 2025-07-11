package covia.grid.client;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import convex.core.crypto.Hashing;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.venue.TestServer;

public class Benchmark {
	
	private static final int TEST_NUMBER = 10000;
	private static final int TIMEOUT_SECONDS = 30;
	
	@FunctionalInterface
	private interface BenchmarkOperation<T> {
		void execute(int index, Consumer<T> onSuccess, Consumer<Throwable> onError);
	}
	
	private static HashMap<Integer,Hash> ASSET_IDS=new HashMap<>();
	
	public static void main(String[] args) {
		// Initialize the client
		Covia client = TestServer.COVIA;
		
		System.out.println("Starting benchmark with " + TEST_NUMBER + " operations...");
		
		// Benchmark 1: Put TEST_NUMBER assets
		runBenchmark("Put assets", TEST_NUMBER, 
			(index, onSuccess, onError) -> {
				ACell metadata = Maps.of(
					"name", Strings.create("Benchmark Asset " + index),
					"description", Strings.create("Asset created for benchmarking")
				);
				
				client.addAsset(metadata).thenAccept(assetId -> {
					ASSET_IDS.put(index, assetId);
					onSuccess.accept(null);
				}).exceptionally(ex -> {
					System.err.println("Failed to put asset " + index + ": " + ex.getMessage());
					onError.accept(ex);
					return null;
				});
			});
		
		// Benchmark 2: Retrieve TEST_NUMBER assets
		runBenchmark("Retrieve assets", TEST_NUMBER,
			(index, onSuccess, onError) -> {
				Hash storedHash =ASSET_IDS.get(index);
				
				client.getMeta(storedHash.toHexString()).thenAccept(meta -> {
					if (meta==null) throw new IllegalStateException("Asset metadata not retreived");
					onSuccess.accept(null);
				}).exceptionally(ex -> {
					onError.accept(ex);
					return null;
				});
			});
		
		// Benchmark 3: Make TEST_NUMBER requests for non-existent assets
		runBenchmark("Non-existent asset requests", TEST_NUMBER,
			(index, onSuccess, onError) -> {
				Hash nonExistentHash = Hashing.sha256(("non-existent-" + index).getBytes());
				
				client.getContent(nonExistentHash.toHexString()).thenAccept(content -> {
					onSuccess.accept(null);
				}).exceptionally(ex -> {
					// Expected to fail for non-existent assets
					onError.accept(ex);
					return null;
				});
			});
		
		System.out.println("Benchmark completed.");
		System.exit(0);
	}
	
	/**
	 * Runs a benchmark with the given operation.
	 * 
	 * @param operationName Name of the operation for reporting
	 * @param operationCount Number of operations to perform
	 * @param operation The operation to benchmark
	 */
	private static void runBenchmark(String operationName, int operationCount, 
			BenchmarkOperation<Void> operation) {
		
		long startTime = System.currentTimeMillis();
		CountDownLatch latch = new CountDownLatch(operationCount);
		AtomicInteger successCount = new AtomicInteger(0);
		
		// Execute all operations
		for (int i = 0; i < operationCount; i++) {
			final int index = i;
			operation.execute(index, 
				result -> {
					successCount.incrementAndGet();
					latch.countDown();
				},  // onSuccess
				ex -> latch.countDown()  // onError - just count down
			);
		}
		
		// Wait for completion
		try {
			latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		
		// Calculate and report results
		long totalTime = System.currentTimeMillis() - startTime;
		double totalTimeMs = totalTime / 1000.0;
		double avgTimeMs = totalTimeMs / operationCount;
		
		System.out.printf("%s: %.3fms (%.5fms per operation, %d successful)%n", 
			operationName, totalTimeMs, avgTimeMs, successCount.get());
	}
} 