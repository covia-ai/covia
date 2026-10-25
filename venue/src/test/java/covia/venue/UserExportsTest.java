package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.UserAdapter;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.lattice.WorkspaceRoot;

/** Export, import and clone of a user's whole stored state, through the user operations. */
class UserExportsTest {

	private static final Engine engine = TestEngine.ENGINE;
	private static final SecretStore.Keys KEYS = SecretStore.keys(engine.getKeyPair());
	private static final String SECRET = "test-only-api-key-never-in-an-export";
	private static final AString HELPER = Strings.create("helper");
	private static final AString EXPORT = Strings.create("export");
	private static final AString ACCOUNT_KEY = Strings.create("accountKey");
	private static final AString SECRETS = Strings.create("secrets");

	private final RequestContext venue = engine.venueContext();
	private AString source;
	private AString target;
	private String unique;

	@BeforeEach
	void users(TestInfo info) {
		source = TestEngine.uniqueDID(info);
		target = Strings.create(source + "-target");
		unique = info.getTestMethod().map(m -> m.getName()).orElseThrow();
	}

	/** A user with something in every namespace, all of it finished. */
	private User fill(AString did) {
		Users users = engine.getVenueState().users();
		users.create(did);
		User user = users.ensure(did);
		user.workspaceRoot().write(WorkspaceRoot.parseStringPath("w/notes/today"), Strings.create("buy milk"));
		user.workspaceRoot().write(WorkspaceRoot.parseStringPath("o/greet"), Maps.of("adapter", "test:echo"));
		user.putHitlRequest(Strings.create("req-1"), Maps.of(Fields.STATUS, "OPEN"));
		user.assets().store(Strings.create("{\"name\":\"note\"}"), Strings.create("asset content"));
		user.secrets().store("API_KEY", SECRET, KEYS);
		Blob jobID = Blob.fromHex("0a".repeat(16));
		user.persistJob(jobID, Maps.of(Fields.ID, jobID, Fields.STATUS, Status.COMPLETE, Fields.OUTPUT, "done"));
		AgentState agent = user.ensureAgent(HELPER, Maps.of("model", "test"), Strings.create("state"));
		agent.ensureSession(Blob.fromHex("aa"), did);
		return user;
	}

	private CompletableFuture<ACell> start(String op, ACell input, RequestContext ctx) {
		return engine.jobs().runOperation("v/ops/user/" + op, input, ctx);
	}

	private ACell run(String op, ACell input, RequestContext ctx) {
		return start(op, input, ctx).join();
	}

	private static String failure(CompletableFuture<?> future) {
		return assertThrows(CompletionException.class, future::join).getCause().getMessage();
	}

	private ABlob export(AString did, ACell... extra) {
		AMap<AString, ACell> input = Maps.of(Fields.DID, did);
		for (int i = 0; i < extra.length; i += 2) input = input.assoc((AString) extra[i], extra[i + 1]);
		return RT.getIn(run("export", input, venue), EXPORT);
	}

	/** Stores a seed in the operator's own secret store and returns its reference. */
	private AString seedRef(AKeyPair key) {
		String name = "export-seed-" + unique + "-" + key.getAccountKey().toHexString().substring(0, 8);
		engine.getVenueState().users().ensure(engine.getDIDString()).secrets()
			.store(name, key.getSeed().toHexString(), KEYS);
		return Strings.create("s/" + name);
	}

	@Test
	void exportAndImportCarryTheWholeState() throws Exception {
		fill(source);
		AKeyPair seal = AKeyPair.generate();
		ACell exported = run("export", Maps.of(Fields.DID, source, ACCOUNT_KEY, seal.getAccountKey()), venue);
		assertEquals(CVMLong.create(UserExports.VERSION), RT.getIn(exported, Fields.VERSION));
		ABlob blob = RT.getIn(exported, EXPORT);
		String plaintextHex = Blob.wrap(SECRET.getBytes(StandardCharsets.UTF_8)).toHexString();
		assertFalse(blob.toHexString().contains(plaintextHex), "secrets travel sealed");

		// As a JSON caller sends it: hex, through a recorded job.
		Job job = engine.jobs().invokeOperation("v/ops/user/import", Maps.of(Fields.DID, target,
			EXPORT, Strings.create("0x" + blob.toHexString()), Fields.SEED, seedRef(seal)), venue);
		assertEquals(CVMBool.TRUE, RT.getIn(job.awaitResult(10_000), Fields.CREATED));
		AMap<AString, ACell> record = engine.getVenueState().users().get(engine.getDIDString()).getJob(job.getID());
		assertEquals(Fields.HIDDEN, RT.getIn(record, Fields.INPUT, EXPORT), "the export never reaches a job record");

		Users users = engine.getVenueState().users();
		assertEquals(users.content(source).dissoc(Namespace.S), users.content(target).dissoc(Namespace.S));
		User imported = users.get(target);
		assertEquals(SECRET, imported.secrets().decrypt("API_KEY", KEYS).toString());
		assertNotNull(RT.getIn(imported.get(), Covia.K_META, Covia.K_CREATED), "the import is the account's first activity");
	}

	@Test
	void importIntoADeletedDIDStartsANewLifecycle() {
		fill(source);
		ABlob blob = export(source, SECRETS, CVMBool.FALSE);
		Users users = engine.getVenueState().users();
		users.create(target);
		users.ensure(target).workspaceRoot().write(WorkspaceRoot.parseStringPath("w/old"), Strings.create("gone"));
		User old = users.get(target);
		engine.deleteUser(venue, target);
		assertEquals(CVMBool.TRUE, RT.getIn(run("import", Maps.of(Fields.DID, target, EXPORT, blob), venue), Fields.CREATED));
		assertEquals(users.content(source).dissoc(Namespace.S), users.content(target));
		assertThrows(AuthException.class, old::get, "a cursor from the deleted lifecycle stays fenced off");
	}

	@Test
	void exportIsADirectRunOnly() throws Exception {
		fill(source);
		ACell input = Maps.of(SECRETS, CVMBool.FALSE);
		RequestContext self = RequestContext.of(source);
		Job recorded = engine.jobs().invokeOperation("v/ops/user/export", input, self);
		assertThrows(Exception.class, () -> recorded.awaitResult(10_000));
		assertEquals(UserAdapter.EXPORT_DIRECT_ONLY, recorded.getErrorMessage());
		assertEquals(UserAdapter.EXPORT_DIRECT_ONLY,
			failure(engine.jobs().invokeInternal("v/ops/user/export", input, self)));
		assertEquals(UserAdapter.EXPORT_DIRECT_ONLY,
			failure(start("export", input, RequestContext.ofAgent(source, HELPER))));

		// A user may export themselves; another ordinary user may not.
		assertNotNull(RT.getIn(run("export", input, self), EXPORT));
		engine.getVenueState().users().create(target);
		assertInstanceOf(AuthException.class, assertThrows(CompletionException.class,
			() -> run("export", Maps.of(Fields.DID, source, SECRETS, CVMBool.FALSE), RequestContext.of(target))).getCause());
	}

	@Test
	void secretsAreSealedOrLeftOutNeverDropped() {
		fill(source);
		assertEquals(UserExports.SECRETS_NEED_KEY, failure(start("export", Maps.of(Fields.DID, source), venue)));
		ABlob blob = export(source, SECRETS, CVMBool.FALSE);
		run("import", Maps.of(Fields.DID, target, EXPORT, blob), venue);
		assertTrue(engine.getVenueState().users().get(target).secrets().list().isEmpty());
		assertNotNull(engine.getVenueState().users().content(target).get(Namespace.W));
	}

	@Test
	void sealedSecretsOpenOnlyWithTheirSeed() {
		fill(source);
		AKeyPair seal = AKeyPair.generate();
		ABlob blob = export(source, ACCOUNT_KEY, seal.getAccountKey());
		assertEquals(UserExports.SEED_REQUIRED,
			failure(start("import", Maps.of(Fields.DID, target, EXPORT, blob), venue)));
		assertEquals(UserExports.SEED_REJECTED, failure(start("import",
			Maps.of(Fields.DID, target, EXPORT, blob, Fields.SEED, seedRef(AKeyPair.generate())), venue)));
		assertEquals(UserAdapter.SEED_REF_REQUIRED, failure(start("import",
			Maps.of(Fields.DID, target, EXPORT, blob, Fields.SEED, seal.getSeed().toHexString()), venue)));
		assertNull(engine.getVenueState().users().get(target), "a refused import registers nothing");
	}

	@Test
	void importFillsOnlyAnEmptyOrdinaryAccount() {
		fill(source);
		ABlob blob = export(source, SECRETS, CVMBool.FALSE);
		Users users = engine.getVenueState().users();
		users.ensure(target).workspaceRoot().write(WorkspaceRoot.parseStringPath("w/x"), Strings.create("y"));
		assertEquals(Users.NOT_EMPTY, failure(start("import", Maps.of(Fields.DID, target, EXPORT, blob), venue)));

		AString registered = Strings.create(source + "-registered");
		users.create(registered);
		assertEquals(CVMBool.FALSE, RT.getIn(run("import", Maps.of(Fields.DID, registered, EXPORT, blob), venue), Fields.CREATED));
		assertEquals(Users.NOT_EMPTY, failure(start("import", Maps.of(Fields.DID, registered, EXPORT, blob), venue)));

		AString venueDID = engine.getDIDString();
		for (AString principal : new AString[] { venueDID, Strings.create(venueDID + ":public") }) {
			assertEquals(UserExports.PROTECTED, failure(start("import", Maps.of(Fields.DID, principal, EXPORT, blob), venue)));
			assertEquals(UserExports.PROTECTED, failure(start("export", Maps.of(Fields.DID, principal, SECRETS, CVMBool.FALSE), venue)));
			assertEquals(UserExports.PROTECTED, failure(start("clone", Maps.of(Fields.FROM, source, Fields.TO, principal), venue)));
		}
		// Importing is the operator's: a user's own import job would already fill their account.
		assertInstanceOf(AuthException.class, assertThrows(CompletionException.class,
			() -> run("import", Maps.of(Fields.DID, source, EXPORT, blob), RequestContext.of(source))).getCause());
	}

	@Test
	void refusesNewerAndForeignExports() {
		ABlob newer = Format.encodeMultiCell(Maps.of(UserExports.K_FORMAT, UserExports.FORMAT,
			Fields.VERSION, UserExports.VERSION + 1, UserExports.K_USER, Maps.empty()), true);
		assertEquals(UserExports.NEWER_VERSION, failure(start("import", Maps.of(Fields.DID, target, EXPORT, newer), venue)));
		ABlob foreign = Format.encodeMultiCell(Maps.of(UserExports.K_FORMAT, "something-else"), true);
		assertEquals(UserExports.NOT_AN_EXPORT, failure(start("import", Maps.of(Fields.DID, target, EXPORT, foreign), venue)));
		assertEquals(UserExports.NOT_AN_EXPORT, failure(start("import", Maps.of(Fields.DID, target, EXPORT, "0x00ff"), venue)));
		assertNull(engine.getVenueState().users().get(target));
	}

	@Test
	void refusesUnfinishedWork() {
		User user = fill(source);
		Blob running = Blob.fromHex("0b".repeat(16));
		user.persistJob(running, Maps.of(Fields.ID, running, Fields.STATUS, Status.STARTED));
		try {
			assertEquals(UserExports.BUSY, failure(start("export", Maps.of(Fields.DID, source, SECRETS, CVMBool.FALSE), venue)));
			assertEquals(UserExports.BUSY, failure(start("clone", Maps.of(Fields.FROM, source, Fields.TO, target), venue)));
			assertNull(engine.getVenueState().users().get(target));
		} finally {
			user.persistJob(running, Maps.of(Fields.ID, running, Fields.STATUS, Status.CANCELLED));
		}
		// An outstanding agent task is unfinished work, wherever its job is recorded.
		assertEquals(UserExports.BUSY, UserExports.unfinishedWork(Maps.of(Namespace.G,
			Maps.of(HELPER, Maps.of(AgentState.KEY_TASKS, Maps.of("task", Maps.empty()))))));
		assertNull(UserExports.unfinishedWork(Maps.of(Namespace.G,
			Maps.of(HELPER, Maps.of(AgentState.KEY_TASKS, Maps.empty())))));
	}

	@Test
	void cloneCopiesAsStoredAndRearmsWakes() {
		User user = fill(source);
		Blob sid = Blob.fromHex("aa");
		engine.getVenueState().setAgentThreadWakeTime(source, HELPER, AgentState.ThreadKind.SESSION, sid,
			System.currentTimeMillis() + 86_400_000L);
		Blob sourceWake = user.agent(HELPER).wakeHandle();
		assertNotNull(sourceWake);

		ACell result = run("clone", Maps.of(Fields.FROM, source, Fields.TO, target), venue);
		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.CREATED));
		Users users = engine.getVenueState().users();
		AMap<AString, ACell> original = users.content(source);
		AMap<AString, ACell> copy = users.content(target);
		assertEquals(original.dissoc(Namespace.G), copy.dissoc(Namespace.G));
		User clone = users.get(target);
		assertEquals(SECRET, clone.secrets().decrypt("API_KEY", KEYS).toString());
		AgentState agent = clone.agent(HELPER);
		assertEquals(user.agent(HELPER).getSession(sid), agent.getSession(sid));

		Blob cloneWake = agent.wakeHandle();
		assertNotNull(cloneWake);
		assertNotEquals(sourceWake, cloneWake);
		ScheduleState schedule = engine.getVenueState().schedule();
		assertEquals(target, schedule.find(cloneWake).rec().get(ScheduleState.K_OWNER));
		assertNotNull(schedule.find(sourceWake), "the source keeps its own wake");

		assertEquals(UserExports.SAME_USER, failure(start("clone", Maps.of(Fields.FROM, source, Fields.TO, source), venue)));
		assertEquals(Users.NOT_EMPTY, failure(start("clone", Maps.of(Fields.FROM, source, Fields.TO, target), venue)));
	}
}
