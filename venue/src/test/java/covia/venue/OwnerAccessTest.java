package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.adapter.CoviaAdapter;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.Access;
import covia.lattice.CoviaPath;

/**
 * Who may use each owner's data through a request's {@link Access}: the owner,
 * one of its agents under caps, a principal it granted, another user and the
 * public principal, for a user's workspace, an agent's {@code n/}, a session's
 * {@code c/}, a job's record and {@code temp}, and the venue's {@code v/}. The
 * owner names the place, so every way of writing a path to the same data needs
 * the same authority.
 */
public class OwnerAccessTest {

	final Engine engine = TestEngine.ENGINE;
	final CoviaAdapter covia = (CoviaAdapter) engine.getAdapter("covia");

	static final AString AGENT = Strings.create("helper");
	static final Blob SESSION = Blob.fromHex("5e55100001");
	static final Blob OTHER_SESSION = Blob.fromHex("5e55100002");

	AKeyPair aliceKP, bobKP;
	AString alice, bob;
	RequestContext ALICE, BOB, PUBLIC;
	Blob job;

	@BeforeEach
	void setup() {
		aliceKP = AKeyPair.generate();
		bobKP = AKeyPair.generate();
		alice = UCAN.toDIDKey(aliceKP.getAccountKey());
		bob = UCAN.toDIDKey(bobKP.getAccountKey());
		ALICE = RequestContext.of(alice);
		BOB = RequestContext.of(bob);
		PUBLIC = RequestContext.of(Strings.create(engine.getDIDString() + ":public"), null, Vectors.empty());

		User user = engine.getVenueState().users().ensure(alice);
		user.workspace().createPath(CoviaPath.parse("shared"));
		user.workspace().write(CoviaPath.parse("shared/note"), Strings.create("w-note"));
		user.workspace().write(CoviaPath.parse("private"), Strings.create("w-private"));
		AgentState agent = user.ensureAgent(AGENT, null, null);
		agent.workspace().write(CoviaPath.parse("note"), Strings.create("n-note"));
		agent.ensureSession(SESSION, alice);
		agent.ensureSession(OTHER_SESSION, alice);
		agent.session(SESSION).workspace().write(CoviaPath.parse("draft"), Strings.create("c-draft"));
		agent.session(OTHER_SESSION).workspace().write(CoviaPath.parse("draft"), Strings.create("c-other"));
		Job echo = engine.jobs().invokeOperation("v/test/ops/echo", Maps.of("message", "scope"), ALICE);
		echo.awaitResult(5000);
		job = echo.getID();
		user.jobs().scratch(job).write(CoviaPath.parse("scratch"), Strings.create("t-scratch"));
	}

	// ========== principals ==========

	/** One of Alice's agents, in session {@link #SESSION}, under {@code caps}. */
	private RequestContext agent(AVector<ACell> caps) {
		return RequestContext.ofAgent(alice, AGENT).withSessionId(SESSION).withCaps(caps);
	}

	/** Bob presenting Alice's grant of {@code can} on {@code with}. */
	private RequestContext bobGranted(String with, AString can) {
		long exp = System.currentTimeMillis() / 1000 + 3600;
		return BOB.withProofs(Vectors.of(UCAN.create(aliceKP, bobKP.getAccountKey(), exp,
			Vectors.of(Capability.create(Strings.create(with), can)), Vectors.empty()).toMap()));
	}

	private static AVector<ACell> caps(String with, AString can) {
		return Vectors.of(Capability.create(Strings.create(with), can));
	}

	private static final AVector<ACell> NONE = Vectors.empty();

	// ========== checks ==========

	private ACell read(RequestContext ctx, String path) {
		return covia.resolve(ctx, Strings.create(path)).read();
	}

	private void assertReads(String expected, RequestContext ctx, String path) {
		assertEquals(Strings.create(expected), read(ctx, path), path);
	}

	private void assertDenied(RequestContext ctx, String path) {
		assertThrows(AuthException.class, () -> read(ctx, path), path);
	}

	// ========== a user's data ==========

	@Test
	public void testTheOwnerReadsItsDataHoweverItIsWritten() {
		assertReads("w-note", ALICE, "w/shared/note");
		assertReads("w-note", ALICE, "/w/shared/note");
		assertReads("w-note", ALICE, alice + "/w/shared/note");
	}

	@Test
	public void testAnAgentReadsItsOwnersDataWithinItsCaps() {
		for (String with : new String[] {"w/shared/", alice + "/w/shared/"}) {
			RequestContext scoped = agent(caps(with, Capability.CRUD_READ));
			assertReads("w-note", scoped, "w/shared/note");
			assertReads("w-note", scoped, alice + "/w/shared/note");
			assertDenied(scoped, "w/private");
			assertDenied(scoped, alice + "/w/private");
		}
		assertDenied(agent(NONE), "w/shared/note");
		assertReads("w-private", agent(null), "w/private");
	}

	@Test
	public void testAnotherUserReadsOnlyWhatItWasGranted() {
		assertDenied(BOB, alice + "/w/shared/note");
		assertDenied(PUBLIC, alice + "/w/shared/note");
		RequestContext granted = bobGranted(alice + "/w/shared/", Capability.CRUD_READ);
		assertReads("w-note", granted, alice + "/w/shared/note");
		assertDenied(granted, alice + "/w/private");
		assertNull(read(granted, "w/shared/note"), "a bare path is Bob's own");
	}

	@Test
	public void testAChangeNeedsWriteAuthority() {
		Access mine = covia.resolve(ALICE, Strings.create("w/fresh"));
		mine.entry(Capability.CRUD_WRITE).write(Strings.create("new"));
		assertReads("new", ALICE, "w/fresh");
		Access theirs = covia.resolve(bobGranted(alice + "/w/", Capability.CRUD_READ), Strings.create(alice + "/w/fresh"));
		assertThrows(AuthException.class, () -> theirs.entry(Capability.CRUD_WRITE));
	}

	@Test
	public void testCreatingLevelsNeedsWriteWhereTheyAreCreated() {
		RequestContext deep = agent(caps("w/shared/deep", Capability.CRUD_WRITE));
		Access below = covia.resolve(deep, Strings.create("w/shared/deep/a/b"));
		assertThrows(AuthException.class, () -> below.changeCreatingLevels(Capability.CRUD_WRITE,
			entry -> entry.write(Strings.create("x"))), "w/shared/deep would be created");

		covia.resolve(ALICE, Strings.create("w/shared/deep")).entry(Capability.CRUD_WRITE).write(Maps.empty());
		assertTrue(covia.resolve(deep, Strings.create("w/shared/deep/a/b")).changeCreatingLevels(Capability.CRUD_WRITE,
			entry -> entry.write(Strings.create("x"))));
		assertReads("x", ALICE, "w/shared/deep/a/b");
	}

	// ========== an agent's workspace ==========

	@Test
	public void testAnAgentsWorkspaceIsCheckedWhereItLives() {
		String place = alice + "/g/" + AGENT + "/n/note";
		assertReads("n-note", agent(null), "n/note");
		assertReads("n-note", ALICE, "g/" + AGENT + "/n/note");
		assertReads("n-note", ALICE, place);

		assertReads("n-note", agent(caps("g/" + AGENT + "/n/", Capability.CRUD_READ)), "n/note");
		assertDenied(agent(caps("n/", Capability.CRUD_READ)), "n/note");
		assertDenied(agent(NONE), "n/note");

		assertDenied(BOB, place);
		assertDenied(PUBLIC, place);
		assertReads("n-note", bobGranted(alice + "/g/" + AGENT + "/n/", Capability.CRUD_READ), place);
	}

	// ========== a session's workspace ==========

	@Test
	public void testTheServingAgentReachesItsSessionOfRight() {
		RequestContext serving = agent(NONE);
		assertReads("c-draft", serving, "c/draft");
		covia.resolve(serving, Strings.create("c/next")).entry(Capability.CRUD_WRITE).write(Strings.create("c-next"));
		assertReads("c-next", agent(null), "c/next");
		assertReads("c-draft", serving, "g/" + AGENT + "/sessions/" + SESSION.toHexString() + "/c/draft");

		String other = "g/" + AGENT + "/sessions/" + OTHER_SESSION.toHexString() + "/c/draft";
		assertDenied(serving, other);
		assertReads("c-other", ALICE, other);
		assertReads("c-other", ALICE, alice + "/" + other);
		assertDenied(BOB, alice + "/" + other);
	}

	// ========== a job's record and scratch ==========

	@Test
	public void testWorkInAJobReachesItsScratchOfRight() {
		RequestContext working = ALICE.withJobId(job).withCaps(NONE);
		assertReads("t-scratch", working, "t/scratch");
		String record = "j/" + job.toHexString();
		assertReads("t-scratch", working, record + "/temp/scratch");
		assertDenied(working, record);
		assertReads("t-scratch", ALICE, record + "/temp/scratch");
	}

	@Test
	public void testAJobRecordNeedsItsOwnerOrAGrant() {
		String record = alice + "/j/" + job.toHexString();
		assertNotNull(read(ALICE, record));
		assertDenied(agent(NONE), record);
		assertDenied(BOB, record);
		assertNotNull(read(bobGranted(record, Capability.CRUD_READ), record));
	}

	// ========== through the covia operations ==========

	private Job run(String op, ACell input, RequestContext ctx) {
		Job job;
		try {
			job = engine.jobs().invokeOperation(op, input, ctx);
		} catch (RuntimeException refused) {
			return Job.failure(String.valueOf(refused.getMessage()));
		}
		try { job.awaitResult(10000); } catch (Exception ignored) {}
		return job;
	}

	@Test
	public void testTheCoviaOperationsAskTheOwner() {
		RequestContext serving = agent(NONE);
		Job wrote = run("v/ops/covia/write", Maps.of("path", "c/op", "value", "c-op"), serving);
		assertEquals(Status.COMPLETE, wrote.getStatus(), wrote.getErrorMessage());
		assertReads("c-op", agent(null), "c/op");

		String place = alice + "/g/" + AGENT + "/n/note";
		assertEquals(Status.FAILED, run("v/ops/covia/read", Maps.of("path", "n/note"),
			agent(caps("n/", Capability.CRUD_READ))).getStatus(), "n/ is checked where it lives");
		Job granted = run("v/ops/covia/read", Maps.of("path", place),
			bobGranted(alice + "/g/" + AGENT + "/n/", Capability.CRUD_READ));
		assertEquals(Status.COMPLETE, granted.getStatus(), granted.getErrorMessage());

		Job copied = run("v/ops/covia/copy", Maps.of("from", place, "to", "w/copy"), BOB);
		assertEquals(Status.FAILED, copied.getStatus(), "copy reads its source as covia:read does");
	}

	// ========== the venue's view ==========

	@Test
	public void testEveryPrincipalReadsTheVenueViewInItsOwnScope() {
		assertNotNull(read(ALICE, "v/ops"));
		assertNotNull(read(BOB, "v/ops"));
		assertNotNull(read(agent(caps("v/", Capability.CRUD_READ)), "v/ops"));
		assertDenied(agent(NONE), "v/ops");
		Access view = covia.resolve(BOB, Strings.create("v/ops/mine"));
		assertThrows(AuthException.class, () -> view.entry(Capability.CRUD_WRITE));
	}
}
