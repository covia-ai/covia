package covia.grid.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.crypto.Hashing;
import covia.venue.TestServer;

public class Benchmark {
	
	private static final int TEST_NUMBER = 1000;
	
	public static void main(String[] args) {
		// Initialize the client
		Covia client = TestServer.COVIA;
		
		System.out.println("Starting benchmark with " + TEST_NUMBER + " operations...");
		
		// Benchmark 1: Put TEST_NUMBER assets
		long startTime = System.currentTimeMillis();
		CountDownLatch putLatch = new CountDownLatch(TEST_NUMBER);
		AtomicInteger putSuccessCount = new AtomicInteger(0);
		
		for (int i = 0; i < TEST_NUMBER; i++) {
			final int index = i;
			ACell metadata = Maps.of(
				"name", Strings.create("Benchmark Asset " + index),
				"description", Strings.create("Asset created for benchmarking")
			);
			
			client.addAsset(metadata).thenAccept(assetId -> {
				putSuccessCount.incrementAndGet();
				putLatch.countDown();
			}).exceptionally(ex -> {
				System.err.println("Failed to put asset " + index + ": " + ex.getMessage());
				putLatch.countDown();
				return null;
			});
		}
		
		try {
			putLatch.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		
		long putTime = System.currentTimeMillis() - startTime;
		double putTimeMs = putTime / 1000.0;
		System.out.printf("Put %d assets: %.3fms (%.3fms per operation)%n", 
			putSuccessCount.get(), putTimeMs, putTimeMs / putSuccessCount.get());
		
		// Benchmark 2: Retrieve TEST_NUMBER assets
		startTime = System.currentTimeMillis();
		CountDownLatch retrieveLatch = new CountDownLatch(TEST_NUMBER);
		AtomicInteger retrieveSuccessCount = new AtomicInteger(0);
		
		for (int i = 0; i < TEST_NUMBER; i++) {
			final int index = i;
			// Create a fake hash for retrieval (these will mostly fail, which is what we want to test)
			Hash fakeHash = Hashing.sha256(("benchmark-retrieve-" + index).getBytes());
			
			client.getMeta(fakeHash.toHexString()).thenAccept(meta -> {
				retrieveSuccessCount.incrementAndGet();
				retrieveLatch.countDown();
			}).exceptionally(ex -> {
				retrieveLatch.countDown();
				return null;
			});
		}
		
		try {
			retrieveLatch.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		
		long retrieveTime = System.currentTimeMillis() - startTime;
		double retrieveTimeMs = retrieveTime / 1000.0;
		System.out.printf("Retrieve %d assets: %.3fms (%.3fms per operation)%n", 
			TEST_NUMBER, retrieveTimeMs, retrieveTimeMs / TEST_NUMBER);
		
		// Benchmark 3: Make TEST_NUMBER requests for non-existent assets
		startTime = System.currentTimeMillis();
		CountDownLatch nonExistentLatch = new CountDownLatch(TEST_NUMBER);
		AtomicInteger nonExistentSuccessCount = new AtomicInteger(0);
		
		for (int i = 0; i < TEST_NUMBER; i++) {
			final int index = i;
			Hash nonExistentHash = Hashing.sha256(("non-existent-" + index).getBytes());
			
			client.getContent(nonExistentHash.toHexString()).thenAccept(content -> {
				nonExistentSuccessCount.incrementAndGet();
				nonExistentLatch.countDown();
			}).exceptionally(ex -> {
				// Expected to fail for non-existent assets
				nonExistentLatch.countDown();
				return null;
			});
		}
		
		try {
			nonExistentLatch.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		
		long nonExistentTime = System.currentTimeMillis() - startTime;
		double nonExistentTimeMs = nonExistentTime / 1000.0;
		System.out.printf("Non-existent asset requests: %.3fms (%.3fms per operation)%n", 
			nonExistentTimeMs, nonExistentTimeMs / TEST_NUMBER);
		
		System.out.println("Benchmark completed.");
	}
} 