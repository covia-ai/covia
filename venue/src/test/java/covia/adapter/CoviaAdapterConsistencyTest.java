package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Targeted tests for the four defect claims in issue GetMine-ai/demo#108.
 *
 * <p>The issue alleges that the embedded Covia venue's {@code covia/list},
 * {@code covia/delete}, and immediate read-after-write are not strongly
 * consistent — same path returning different counts, deletes not sticking,
 * fresh writes invisible to the next read. Reading {@link CoviaAdapter}
 * source shows every handler is invoked via
 * {@code CompletableFuture.completedFuture(handle...)} — the cursor mutation
 * is synchronous and complete before the future resolves. So with a stable
 * caller DID and no concurrent writers, the claimed wobble cannot happen at
 * the venue layer.</p>
 *
 * <p>These tests replicate the exact patterns the issue describes and assert
 * the venue is strongly consistent. If any of these fail, the defect is real
 * at the venue layer. If they all pass, the defects observed in #108 must be
 * coming from somewhere else in the GetMine stack — most likely caller-DID
 * instability across the venue restart described in #92 (writes land in one
 * user namespace, subsequent reads come back to a different one). The final
 * test demonstrates that cross-DID call sequences DO produce exactly the
 * wobble #108 describes — proving #108's symptoms are downstream of #92.</p>
 */
public class CoviaAdapterConsistencyTest {

	private final Engine engine = TestEngine.ENGINE;
	private RequestContext ALICE;

	@BeforeEach
	public void setup(TestInfo info) {
		AString aliceDID = TestEngine.uniqueDID(info);
		ALICE = RequestContext.of(aliceDID);
	}

	// ========== Helpers ==========

	private void write(String path, ACell value, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, path, Fields.VALUE, value), ctx);
		ACell r = job.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(r, "written"),
			"write must report written:true for " + path);
	}

	private ACell read(String path, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, path), ctx);
		return job.awaitResult(5000);
	}

	private ACell list(String path, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation("v/ops/covia/list",
			Maps.of(Fields.PATH, path), ctx);
		return job.awaitResult(5000);
	}

	private void delete(String path, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation("v/ops/covia/delete",
			Maps.of(Fields.PATH, path), ctx);
		ACell r = job.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(r, "deleted"));
	}

	private long listCount(String path, RequestContext ctx) {
		ACell r = list(path, ctx);
		CVMLong c = RT.getIn(r, "count");
		return (c == null) ? -1 : c.longValue();
	}

	// ========== #108 obs 1: covia/list counts wobble across reads ==========

	/**
	 * Issue text: {@code covia/list} counts fluctuate — same {@code w/health/letters},
	 * no writes between, returned 43 then 91 seconds later.
	 *
	 * <p>Test: write a fixed set of entries once, then call {@code covia/list}
	 * many times in a row with no intervening writes. Every count must be
	 * identical. If the venue had a lazy/async path index this would wobble.</p>
	 */
	@Test
	public void testListCountStableAcrossRepeatedReads() {
		for (int i = 0; i < 43; i++) {
			write("w/letters/" + i, Strings.create("letter-" + i), ALICE);
		}

		long first = listCount("w/letters", ALICE);
		assertEquals(43, first, "initial list count");

		// Read the count 50 times in a row with no writes between.
		// In #108's "43 then 91" scenario, anything other than a constant
		// 43 would be the bug.
		for (int i = 0; i < 50; i++) {
			assertEquals(first, listCount("w/letters", ALICE),
				"list count must be stable across repeated reads (iteration " + i + ")");
		}
	}

	// ========== #108 obs 2: covia/delete doesn't reliably persist ==========

	/**
	 * Issue text: wrote {@code __deltest__}, called delete; one run it was
	 * still readable + present afterward, another run it was gone.
	 *
	 * <p>Test: write a marker, delete it, read it back many times. Every
	 * read must report {@code exists: false}. If delete were async or
	 * tombstone-eventual, repeated reads would flake.</p>
	 */
	@Test
	public void testDeleteIsImmediatelyAndPermanentlyVisible() {
		write("w/__deltest__", Strings.create("present"), ALICE);

		// Confirm write landed
		assertEquals(CVMBool.TRUE, RT.getIn(read("w/__deltest__", ALICE), "exists"));

		delete("w/__deltest__", ALICE);

		// Repeated reads must all see the delete.
		for (int i = 0; i < 50; i++) {
			ACell r = read("w/__deltest__", ALICE);
			assertEquals(CVMBool.FALSE, RT.getIn(r, "exists"),
				"deleted key must remain deleted on read " + i);
			assertNull(RT.getIn(r, "value"),
				"deleted key must return null value on read " + i);
		}

		// And it must be absent from list output too.
		ACell listResult = list("w", ALICE);
		// The whole "w" namespace may have other keys from prior tests in this
		// shared engine; what we assert is that __deltest__ isn't among them.
		// The simplest assertion: a direct list of "w/__deltest__" reports
		// exists:false.
		ACell direct = list("w/__deltest__", ALICE);
		assertEquals(CVMBool.FALSE, RT.getIn(direct, "exists"),
			"list of deleted path must report exists:false");
	}

	// ========== #108 obs 3: read-after-write misses ==========

	/**
	 * Issue text: an entry written in one request was not visible to a read
	 * in the very next request (broke the first snap-dedup attempt).
	 *
	 * <p>Test: write, immediately read, in a tight loop. Every read must
	 * see the value that was just written. If covia/write were async this
	 * would flake.</p>
	 */
	@Test
	public void testReadAfterWriteIsStronglyConsistent() {
		for (int i = 0; i < 100; i++) {
			String path = "w/raw-probe";
			AString value = Strings.create("value-" + i);
			write(path, value, ALICE);

			ACell r = read(path, ALICE);
			assertEquals(CVMBool.TRUE, RT.getIn(r, "exists"),
				"write iteration " + i + " must be visible to immediate read");
			assertEquals(value, RT.getIn(r, "value"),
				"read after write iteration " + i + " must see latest value");
		}
	}

	/**
	 * Issue text again, with the dedup-style pattern that prompted #107's
	 * workaround: write a key, list the parent, check the count rose.
	 */
	@Test
	public void testReadAfterWriteVisibleInListImmediately() {
		long initial = listCount("w/dedup-probe", ALICE);
		// Empty path returns -1 for count; treat as 0
		long base = (initial < 0) ? 0 : initial;

		for (int i = 0; i < 30; i++) {
			write("w/dedup-probe/key-" + i, Strings.create("v"), ALICE);
			long now = listCount("w/dedup-probe", ALICE);
			assertEquals(base + i + 1, now,
				"list count must reflect each write immediately (i=" + i + ")");
		}
	}

	// ========== #108 obs 4: nested listings under-report ==========

	/**
	 * Issue text: source-index per-item count read 0 while 17 batch entries
	 * existed; NHS workspace counted 508 via list vs 621 on the dashboard.
	 *
	 * <p>Test: write 17 entries under a nested path, list the parent, count
	 * must be 17. If a lazily-built nested index were the source of truth
	 * for list this would mis-report.</p>
	 */
	@Test
	public void testNestedListingCountMatchesEntries() {
		// Build a nested workspace: w/sources/<source-id>/items/<item-id>
		for (int i = 0; i < 17; i++) {
			write("w/sources/email/items/" + i,
				Maps.of(Strings.create("body"), Strings.create("item " + i)),
				ALICE);
		}

		assertEquals(17, listCount("w/sources/email/items", ALICE),
			"nested list count must match the number of writes");

		// Also assert the parent levels report the structure correctly.
		assertEquals(1, listCount("w/sources/email", ALICE),
			"parent of nested set should list one key ('items')");
		assertEquals(1, listCount("w/sources", ALICE),
			"grandparent should list one key ('email')");
	}

	// ========== Concurrent writes from a single caller ==========

	/**
	 * If there is any asynchrony hiding inside the cursor/lattice path (e.g.
	 * concurrent updates lost), concurrent writes to distinct keys from the
	 * same caller would lose entries. After all writes complete, list must
	 * report exactly N keys.
	 */
	@Test
	public void testConcurrentWritesPreserveAllEntriesUnderSameCaller() throws Exception {
		int N = 64;
		List<CompletableFuture<Void>> futures = new ArrayList<>();
		for (int i = 0; i < N; i++) {
			final int idx = i;
			futures.add(CompletableFuture.runAsync(() ->
				write("w/concurrent/key-" + idx, Strings.create("v-" + idx), ALICE)));
		}
		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();

		assertEquals(N, listCount("w/concurrent", ALICE),
			"all concurrent writes must be visible after futures complete");

		// And every key must read back its value (no lost updates).
		for (int i = 0; i < N; i++) {
			ACell r = read("w/concurrent/key-" + i, ALICE);
			assertEquals(CVMBool.TRUE, RT.getIn(r, "exists"),
				"concurrent write " + i + " must be readable");
			assertEquals(Strings.create("v-" + i), RT.getIn(r, "value"),
				"concurrent write " + i + " must have correct value");
		}
	}

	// ========== The actual root cause hypothesis ==========

	/**
	 * Demonstrates that the wobble #108 describes IS reproducible — but only
	 * across distinct caller DIDs. Each user's workspace is a separate
	 * namespace (see {@code CoviaAdapter#getUserCursor}); a write as DID_A
	 * is correctly invisible to a read as DID_B.
	 *
	 * <p>This is the suspected root cause of #108's symptoms in production:
	 * the venue's DID is rotating across restart (#92), so the GetMine app's
	 * cached session/identity points at one user namespace pre-restart and
	 * a different one post-restart. Counts, deletes, and reads all "wobble"
	 * because they're hitting two different stores.</p>
	 */
	@Test
	public void testCrossDIDCallsExplainTheApparentWobble() {
		RequestContext ALICE_PRE = ALICE;
		RequestContext ALICE_POST = RequestContext.of(
			Strings.create(ALICE.getCallerDID().toString() + "-post-restart"));

		// "Before restart" — Alice writes 43 letters.
		for (int i = 0; i < 43; i++) {
			write("w/letters/" + i, Strings.create("letter-" + i), ALICE_PRE);
		}
		assertEquals(43, listCount("w/letters", ALICE_PRE));

		// "After restart" — caller DID changed. Same path, different namespace.
		// This is the exact scenario #108 observed as "43 then 91 (or 0)".
		long postCount = listCount("w/letters", ALICE_POST);
		assertNotEquals(43, postCount,
			"if the caller DID changed, the workspace listing must differ");
		// Specifically, the post-rotation user has no data yet.
		assertEquals(-1, postCount,
			"new caller sees nothing at the path (exists:false → no count field)");

		// A delete as the post-rotation caller doesn't touch the pre-rotation
		// caller's data — exactly the "delete didn't stick" symptom.
		delete("w/letters/0", ALICE_POST);
		ACell preRead = read("w/letters/0", ALICE_PRE);
		assertEquals(CVMBool.TRUE, RT.getIn(preRead, "exists"),
			"pre-rotation data must survive a post-rotation delete attempt");
		assertEquals(Strings.create("letter-0"), RT.getIn(preRead, "value"));
	}
}
