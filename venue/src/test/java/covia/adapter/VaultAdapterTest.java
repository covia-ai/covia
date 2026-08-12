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
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.lang.RT;
import covia.venue.Config;
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
			"content", "{\"name\": \"Example User\", \"theme\": \"dark\"}"
		));
		assertTrue(RT.bool(RT.getIn(result, "created")));

		// Read back
		result = run("v/ops/vault/read", Maps.of("path", "profile.json"));
		String content = RT.ensureString(RT.getIn(result, "content")).toString();
		assertTrue(content.contains("Example User"));
		assertEquals("utf-8", RT.ensureString(RT.getIn(result, "encoding")).toString());

		// The convenience adapter's neutral default is the DLFS drive "vault".
		result = run("v/ops/dlfs/read", Maps.of("drive", "vault", "path", "profile.json"));
		assertTrue(RT.ensureString(RT.getIn(result, "content")).toString()
			.contains("Example User"));
	}

	@Test
	public void testMkdirAndList() {
		run("v/ops/vault/mkdir", Maps.of("path", "reports"));
		run("v/ops/vault/write", Maps.of("path", "reports/q4.json", "content", "{\"score\": 2.8}"));

		ACell result = run("v/ops/vault/list", Maps.of("path", "reports"));
		AVector<?> entries = RT.ensureVector(RT.getIn(result, "entries"));
		assertNotNull(entries);
		assertEquals(1, entries.count());
		assertEquals("q4.json", RT.getIn(entries.get(0), "name").toString());
	}

	@Test
	public void testConfiguredDriveBinding() {
		AMap<AString, ACell> config = Maps.of(
			Config.ADAPTERS, Maps.of("vault", Maps.of("drive", "documents")),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true));
		Engine configured = Engine.createTemp(config);
		try {
			Engine.addDemoAssets(configured);
			AString did = TestEngine.uniqueDID("configured-vault");
			RequestContext ctx = RequestContext.of(did);
			configured.jobs().invokeOperation("v/ops/vault/write",
				Maps.of("path", "note.txt", "content", "configured"), ctx)
				.awaitResult(5000);
			ACell result = configured.jobs().invokeOperation("v/ops/dlfs/read",
				Maps.of("drive", "documents", "path", "note.txt"), ctx)
				.awaitResult(5000);
			assertEquals("configured", RT.getIn(result, "content").toString());
		} finally {
			configured.close();
		}
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
