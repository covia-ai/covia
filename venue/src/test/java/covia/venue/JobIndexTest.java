package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Path;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.CoviaPath;

/**
 * The venue's entries for unfinished jobs ({@link JobIndex}): each is written
 * with its job, goes with the update that finishes it, with the job or with
 * its owner, and keeps what the job's operation declared when it was admitted
 * (JOBS.md, The venue's job entries).
 */
class JobIndexTest {
	private static final Engine ENGINE = TestEngine.ENGINE;
	private static final AString TOKEN = Strings.create("token");
	private static final Path OP =CoviaPath.parse("o/declared");
	/** A job that waits until it is finished from outside, with a secret field and an output schema. */
	private static final AMap<AString, ACell> DECLARED = Maps.of(Fields.NAME, "declared", Fields.OPERATION, Maps.of(
		Fields.ADAPTER, "test:never", Strings.create("secretFields"), Vectors.of(TOKEN),
		Fields.OUTPUT, Maps.of("type", "object")));

	@Test void anEntryLastsWhileItsJobIsUnfinished(TestInfo info) {
		RequestContext ctx = RequestContext.of(TestEngine.uniqueDID(info));
		JobIndex index = ENGINE.getVenueState().jobIndex();
		Job job = ENGINE.jobs().invokeOperation(DECLARED, Maps.empty(), ctx);
		AMap<AString, ACell> entry = index.entry(job.getID());
		assertEquals(ctx.getUserDID(), entry.get(JobIndex.OWNER));
		assertEquals(OutputRules.of(DECLARED), OutputRules.fromCell(entry.get(JobIndex.RULES)));
		job.cancel();
		assertNull(index.entry(job.getID()), "the update that finishes the job removes it");

		Job deleted = ENGINE.jobs().invokeOperation(DECLARED, Maps.empty(), ctx);
		assertNotNull(index.entry(deleted.getID()));
		assertTrue(ENGINE.jobs().deleteJob(deleted.getID(), ctx));
		assertNull(index.entry(deleted.getID()), "deleting the job removes it");
	}

	@Test void deletingTheOwnerRemovesTheEntriesOfItsJobs() {
		AString did = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		ENGINE.admitUser(did);
		Job job = ENGINE.jobs().invokeOperation(DECLARED, Maps.empty(), RequestContext.of(did));
		assertNotNull(ENGINE.getVenueState().jobIndex().entry(job.getID()));
		ENGINE.deleteUser(ENGINE.venueContext(), did);
		assertNull(ENGINE.getVenueState().jobIndex().entry(job.getID()));
	}

	@Test void aRecoveredJobKeepsTheRulesItWasAdmittedWith() throws Exception {
		AMap<AString, ACell> config = Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0),
			Config.OUTPUT_VALIDATION, Strings.create("strict"));
		Root<Index<Keyword, ACell>> root = Cursors.root(Index.<Keyword, ACell>none());
		AKeyPair key = AKeyPair.generate();
		RequestContext alice = RequestContext.of(Strings.create("did:key:zAliceJobIndexRules"));

		Engine first = new Engine(config, CoviaApplication.connect(new LatticeHost<>(root, null), key), key).start();
		Job secret, shaped;
		try {
			Engine.addDemoAssets(first);
			first.admitUser(alice.getUserDID()).writeInternalPath(OP, DECLARED);
			secret = first.jobs().invokeOperation("o/declared", Maps.empty(), alice);
			shaped = first.jobs().invokeOperation("o/declared", Maps.empty(), alice);
			first.syncState();
		} finally {
			first.close();
		}

		Engine second = new Engine(config, CoviaApplication.connect(new LatticeHost<>(root, null), key), key).start();
		try {
			Engine.addDemoAssets(second);
			// The path now names an operation that declares neither.
			second.getVenueState().users().get(alice.getUserDID()).writeInternalPath(OP,
				Maps.of(Fields.OPERATION, Maps.of(Fields.ADAPTER, "test:never")));
			second.jobs().recoverJobs();

			second.jobs().getJob(secret.getID()).completeWith(Maps.of(TOKEN, "kept private"));
			AMap<AString, ACell> completed = second.jobs().getJobData(secret.getID(), alice);
			assertEquals(Status.COMPLETE, completed.get(Fields.STATUS));
			assertEquals(Fields.HIDDEN, RT.getIn(completed, Fields.OUTPUT, TOKEN), "its secret field stays hidden");

			second.jobs().getJob(shaped.getID()).completeWith(Strings.create("not an object"));
			AMap<AString, ACell> failed = second.jobs().getJobData(shaped.getID(), alice);
			assertEquals(Status.FAILED, failed.get(Fields.STATUS), "its output is still checked against its schema");
		} finally {
			second.close();
		}
	}
}
