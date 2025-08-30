package covia.grid.client;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.Collections;

import convex.core.crypto.Hashing;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.venue.TestServer;

public class Benchmark {
	
	private static final int TEST_NUMBER = 10000;
	private static final int TIMEOUT_SECONDS = 30;
	private static final boolean CHECK_LATENCIES=true;
	
	@FunctionalInterface
	private interface BenchmarkOperation<T> {
		void execute(int index, Consumer<T> onSuccess, Consumer<Throwable> onError);
	}
	
	private static HashMap<Integer,Hash> ASSET_IDS=new HashMap<>();
	
	public static void main(String[] args) {
		try {
		// Initialize the client
		VenueHTTP client = TestServer.COVIA;
		
		System.out.println("Starting benchmark with " + TEST_NUMBER + " operations...");
		
		// Benchmark 1: Put TEST_NUMBER assets
		runBenchmark("Put assets", TEST_NUMBER, 
			(index, onSuccess, onError) -> {
				ACell metadata = Maps.of(
					"name", Strings.create("Benchmark Asset " + index),
					"description", Strings.create("Asset created for benchmarking")
				);
				
				client.addAsset(metadata).thenAccept(assetId -> {
					synchronized (ASSET_IDS) {ASSET_IDS.put(index, assetId);}
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
		} finally {
			System.exit(0);
		}
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
		
		CountDownLatch latch = new CountDownLatch(operationCount);
		AtomicInteger successCount = new AtomicInteger(0);
		ArrayList<Long> latencies = new ArrayList<>();
		
		long startTime = System.nanoTime();

		// Execute all operations
		for (int i = 0; i < operationCount; i++) {
			final int index = i;
			final long operationStartTime = System.nanoTime();
			
			operation.execute(index, 
				result -> {
					long latency = System.nanoTime() - operationStartTime;
					if (CHECK_LATENCIES) synchronized (latencies) {latencies.add(latency);}
					successCount.incrementAndGet();
					latch.countDown();
				},  // onSuccess
				ex -> {
					long latency = System.nanoTime() - operationStartTime;
					if (CHECK_LATENCIES) synchronized (latencies) {latencies.add(latency);}
					latch.countDown();
				}  // onError - still track latency
			);
		}
		
		// Wait for completion
		try {
			latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		
		// Calculate and report results
		long totalTime = System.nanoTime() - startTime;
		double totalTimeMs = totalTime / 1_000_000.0; // Convert nanoseconds to milliseconds
		double opTimeMS=totalTimeMs/TEST_NUMBER;
		
		String latencyOutput="";
		if (CHECK_LATENCIES) {
			// Calculate latency statistics
			Collections.sort(latencies);
			long minLatency = latencies.isEmpty() ? 0 : latencies.get(0);
			long maxLatency = latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);
			long avgLatency = latencies.isEmpty() ? 0 : latencies.stream().mapToLong(Long::longValue).sum() / latencies.size();
			
			// Calculate percentiles
			long p50 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.5));
			long p95 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.95));
			long p99 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.99));
			
			// Convert latencies to milliseconds for display
			double minLatencyMs = minLatency / 1_000_000.0;
			double maxLatencyMs = maxLatency / 1_000_000.0;
			double avgLatencyMs = avgLatency / 1_000_000.0;
			double p50Ms = p50 / 1_000_000.0;
			double p95Ms = p95 / 1_000_000.0;
			double p99Ms = p99 / 1_000_000.0;
			latencyOutput=String.format("  Latency: [%.5fms avg %.5fms min, %.5fms max, %.5fms p50, %.5fms p95, %.5fms p99 ]\n", avgLatencyMs, minLatencyMs, maxLatencyMs, p50Ms, p95Ms, p99Ms);
		}
		
		System.out.printf("%s:\n  %.3fms total (%.5fms per op, %.2f%% successful)\n"+latencyOutput, 
			operationName, totalTimeMs, opTimeMS,successCount.get()*100.0/TEST_NUMBER);
	}
} 