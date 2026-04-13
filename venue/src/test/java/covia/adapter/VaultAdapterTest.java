package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.lang.RT;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

public class VaultAdapterTest {

	private final Engine engine = TestEngine.ENGINE;
	// Per-method DID isolation: avoids cross-test interference within the
	// shared TestEngine.ENGINE (parallel @Execution at method level).
	private AString aliceDID;

	@BeforeEach
	public void setup(TestInfo info) {
		aliceDID = TestEngine.uniqueDID(info);
	}

	private ACell run(String op, ACell input) {
		return engine.jobs().invokeOperation(
			op, input, RequestContext.of(aliceDID)
		).awaitResult(5000);
	}

	@Test
	public void testWriteAndRead() {
		// Write to vault — no drive parameter needed
		ACell result = run("v/ops/vault/write", Maps.of(
			"path", "profile.json",
			"content", "{\"name\": \"Sarah Smith\", \"nhsNumber\": \"485 777 3456\"}"
		));
		assertTrue(RT.bool(RT.getIn(result, "created")));

		// Read back
		result = run("v/ops/vault/read", Maps.of("path", "profile.json"));
		String content = RT.ensureString(RT.getIn(result, "content")).toString();
		assertTrue(content.contains("Sarah Smith"));
		assertEquals("utf-8", RT.ensureString(RT.getIn(result, "encoding")).toString());
	}

	@Test
	public void testMkdirAndList() {
		run("v/ops/vault/mkdir", Maps.of("path", "lab-results"));
		run("v/ops/vault/write", Maps.of("path", "lab-results/panel-q4.json", "content", "{\"tsh\": 2.8}"));

		// List lab-results dir
		ACell result = run("v/ops/vault/list", Maps.of("path", "lab-results"));
		AVector<?> entries = RT.ensureVector(RT.getIn(result, "entries"));
		assertNotNull(entries);
		assertEquals(1, entries.count());
		assertEquals("panel-q4.json", RT.getIn(entries.get(0), "name").toString());
	}

	@Test
	public void testDelete() {
		run("v/ops/vault/mkdir", Maps.of("path", "tmp"));
		run("v/ops/vault/write", Maps.of("path", "tmp/deleteme.txt", "content", "delete me"));
		ACell result = run("v/ops/vault/delete", Maps.of("path", "tmp/deleteme.txt"));
		assertTrue(RT.bool(RT.getIn(result, "deleted")));
	}

	@Test
	public void testNoAuthFails() {
		// Anonymous context should fail
		assertThrows(Exception.class, () ->
			engine.jobs().invokeOperation(
				"v/ops/vault/list", Maps.empty(), RequestContext.ANONYMOUS
			).awaitResult(5000)
		);
	}

	/**
	 * Concurrent writes under a SINGLE DID. Regression test for a race in
	 * DLFSAdapter.ensureUserKeyPair where parallel callers each generated
	 * their own keypair, signing under different AccountKeys and producing
	 * conflicting OwnerLattice slots that lost siblings on merge.
	 *
	 * <p>All disjoint paths must survive the concurrent writes.</p>
	 */
	@Test
	public void testConcurrentOpsSameDID() throws Exception {
		String[] dirs = { "alpha", "beta", "gamma", "delta" };
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(dirs.length);
		List<Throwable> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

		for (String d : dirs) {
			new Thread(() -> {
				try {
					start.await();
					run("v/ops/vault/mkdir", Maps.of("path", d));
					run("v/ops/vault/write", Maps.of(
						"path", d + "/file.txt", "content", "content of " + d));
				} catch (Throwable t) {
					errors.add(t);
				} finally {
					done.countDown();
				}
			}, "vault-writer-" + d).start();
		}
		start.countDown();
		assertTrue(done.await(10, TimeUnit.SECONDS), "writers did not finish in time");
		assertTrue(errors.isEmpty(), "writer errors: " + errors);

		// All siblings must survive concurrent writes
		ACell listResult = run("v/ops/vault/list", Maps.of("path", ""));
		AVector<?> entries = RT.ensureVector(RT.getIn(listResult, "entries"));
		assertNotNull(entries, "list should return entries");

		Set<String> survivors = new HashSet<>();
		for (long i = 0; i < entries.count(); i++) {
			survivors.add(RT.getIn(entries.get(i), "name").toString());
		}
		assertEquals(Set.of(dirs), survivors,
			"all disjoint sibling dirs should survive concurrent writes under the same DID");

		// And the contents should be intact
		for (String d : dirs) {
			ACell read = run("v/ops/vault/read", Maps.of("path", d + "/file.txt"));
			assertEquals("content of " + d,
				RT.ensureString(RT.getIn(read, "content")).toString(),
				"file under " + d + " should round-trip");
		}
	}
}
