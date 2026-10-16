package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.json.schema.JsonSchema;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.SecretStore;

/** Real dispatch and adversarial metadata tests for the secret disclosure boundary. */
@Execution(ExecutionMode.SAME_THREAD)
class SecretAdapterTest {

	private static final String VALUE = "test-only-plaintext-never-in-history";
	private static Engine engine;
	private RequestContext owner;
	private AKeyPair identity;
	@BeforeAll static void start() {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
	}
	@AfterAll static void stop() { engine.close(); }
	@BeforeEach void caller() {
		identity = AKeyPair.generate();
		owner = RequestContext.of(UCAN.toDIDKey(identity.getAccountKey()));
		put("KEY", VALUE);
	}
	private void put(String name, String value) {
		engine.getVenueState().users().ensure(owner.getUserDID()).secrets()
			.store(name, value, SecretStore.keys(engine.getKeyPair()));
	}
	private AMap<AString, ACell> meta(String op) {
		return engine.getAdapter("secret").getOperations().get(Strings.create(op));
	}
	private CompletableFuture<ACell> run(String op, ACell input, RequestContext ctx) {
		return engine.jobs().runOperation("v/ops/secret/" + op, input, ctx);
	}
	private ACell run(String op, ACell input) {
		ACell result = run(op, input, owner).join();
		assertNull(JsonSchema.validate(RT.ensureMap(RT.getIn(meta(op), "operation", "output")), result));
		return result;
	}
	private RequestContext scope(String resource, AString ability) {
		return owner.withCaps(Vectors.of(Capability.create(Strings.create(resource), ability),
			Capability.create(null, Abilities.ASSET_READ)));
	}
	private static Throwable failure(CompletableFuture<?> future) {
		return assertThrows(CompletionException.class, future::join).getCause();
	}
	private static void denied(CompletableFuture<?> future) {
		assertInstanceOf(AuthException.class, failure(future));
	}

	@Test void listReturnsOnlyOwnNamesWithFilteringAndPagination() {
		put("APP_A", "a"); put("APP_B", "b"); put("OTHER", "c");
		ACell first = run("list", Maps.of("prefix", "APP_", "limit", 1));
		assertEquals(CVMLong.create(2), RT.getIn(first, "total"));
		assertEquals(CVMLong.ONE, RT.getIn(first, "nextOffset"));
		AVector<ACell> a = RT.getIn(first, "names");
		ACell second = run("list", Maps.of("prefix", "APP_", "offset", 1, "limit", 1));
		AVector<ACell> b = RT.getIn(second, "names");
		assertEquals(1, a.count()); assertEquals(1, b.count());
		assertNotEquals(a.get(0), b.get(0));
		assertNull(RT.getIn(second, "nextOffset"));
		assertEquals(Vectors.empty(), RT.getIn(run("list", Maps.of("offset", Long.MAX_VALUE)), "names"));
		assertFalse(run("list", Maps.empty()).toString().contains(VALUE));
		RequestContext other = RequestContext.of(UCAN.toDIDKey(AKeyPair.generate().getAccountKey()));
		engine.getVenueState().users().ensure(other.getUserDID());
		assertEquals(Vectors.empty(), RT.getIn(run("list", Maps.empty(), other).join(), "names"));
	}

	@Test void listAndDeleteUseWorkspacePermissions() {
		assertEquals(CVMLong.ONE, RT.getIn(run("list", Maps.empty(), scope("s/", Capability.CRUD_READ)).join(), "total"));
		for (AString ability : new AString[] { Abilities.SECRET_USE, Abilities.SECRET_WRITE, Strings.create("invoke") }) {
			denied(run("list", Maps.empty(), scope("s/", ability)));
			denied(run("delete", Maps.of("name", "KEY"), scope("s/", ability)));
		}
		denied(run("delete", Maps.of("name", "KEY"), scope("s/KEY", Capability.CRUD_READ)));
		denied(run("delete", Maps.of("name", "KEY"), scope("s/OTHER", Capability.CRUD_DELETE)));
		assertEquals(CVMBool.TRUE, RT.getIn(run("delete", Maps.of("name", "KEY"), scope("s/KEY", Capability.CRUD_DELETE)).join(), "deleted"));
		assertEquals(CVMBool.FALSE, RT.getIn(run("delete", Maps.of("name", "KEY")), "deleted"));
	}

	@Test void concurrentDeletionHasOneWinnerAndKeepsSibling() {
		put("SIBLING", "keep");
		List<CompletableFuture<ACell>> calls = new ArrayList<>();
		for (int i = 0; i < 12; i++) calls.add(CompletableFuture.supplyAsync(() -> run("delete", Maps.of("name", "KEY"))));
		assertEquals(1, calls.stream().map(CompletableFuture::join)
			.filter(r -> CVMBool.TRUE.equals(RT.getIn(r, "deleted"))).count());
		assertEquals("keep", engine.resolveSecret("s/SIBLING", owner));
	}

	@Test void malformedInputsAreRejectedEvenWithoutSchemas() {
		for (String op : new String[] { "set", "delete", "extract" }) {
			for (String name : new String[] { ".", "..", "a/b", "a\\b", "a\nb" }) {
				Throwable error = failure(engine.getAdapter("secret").invokeFuture(owner, meta(op), Maps.of("name", name, "value", "x")));
				assertEquals(SecretAdapter.INVALID_NAME, error.getMessage());
			}
		}
		for (ACell input : new ACell[] { Maps.of("limit", 0), Maps.of("limit", 1001),
			Maps.of("offset", -1), Maps.of("offset", "0"), Maps.of("limit", 1.5) }) {
			assertEquals(SecretAdapter.INVALID_PAGE, failure(engine.getAdapter("secret").invokeFuture(owner, meta("list"), input)).getMessage());
		}
		assertEquals(SecretAdapter.INVALID_PREFIX, failure(engine.getAdapter("secret").invokeFuture(owner, meta("list"), Maps.of("prefix", 1))).getMessage());
		assertEquals(SecretAdapter.AUTH_REQUIRED, failure(engine.getAdapter("secret").invokeFuture(RequestContext.ANONYMOUS, meta("extract"), Maps.of("name", "KEY"))).getMessage());
	}

	@Test void extractionReturnsPlaintextOnlyToDirectOwnerWithoutHistoryOrEvents() {
		ACell before = engine.jobs().getJobs(owner);
		List<Job> events = new ArrayList<>();
		java.util.function.Consumer<Job> listener = events::add;
		engine.jobs().addJobUpdateListener(listener);
		try {
			assertEquals(Strings.create(VALUE), RT.getIn(run("extract", Maps.of("name", "KEY")), "value"));
			assertEquals(Strings.create(VALUE), RT.getIn(run("extract", Maps.of("name", "KEY"), scope("s/KEY", Abilities.SECRET_EXTRACT)).join(), "value"));
			assertEquals(before, engine.jobs().getJobs(owner));
			assertTrue(events.isEmpty());
			assertFalse(engine.getVenueState().users().get(owner.getUserDID()).get().toString().contains(VALUE));
		} finally { engine.jobs().removeJobUpdateListener(listener); }
	}

	@Test void extractionRequiresItsOwnPreciseCapability() {
		for (AString ability : new AString[] { Capability.CRUD_READ, Abilities.SECRET_USE,
			Abilities.SECRET_WRITE, Strings.create("secret/decrypt"), Strings.create("invoke") }) {
			denied(run("extract", Maps.of("name", "KEY"), scope("s/KEY", ability)));
		}
		denied(run("extract", Maps.of("name", "KEY"), scope("s/OTHER", Abilities.SECRET_EXTRACT)));
		denied(run("extract", Maps.of("name", "KEY"), owner.withCaps(Vectors.empty())));
		RequestContext caveated = owner.withCaps(Vectors.of(Capability.create(Strings.create("s/KEY"),
			Abilities.SECRET_EXTRACT, Maps.of("url", "https://example.com")), Capability.create(null, Abilities.ASSET_READ)));
		denied(run("extract", Maps.of("name", "KEY"), caveated));
	}

	@Test void extractionCannotReadAnotherOwnersOrPublicSecrets() {
		RequestContext other = RequestContext.of(UCAN.toDIDKey(AKeyPair.generate().getAccountKey()));
		engine.getVenueState().users().ensure(other.getUserDID());
		assertEquals(SecretAdapter.SECRET_UNAVAILABLE, failure(run("extract", Maps.of("name", "KEY"), other)).getMessage());
		assertThrows(CompletionException.class, () -> run("extract", Maps.of("name", owner.getUserDID() + "/s/KEY"), other).join());
		assertThrows(CompletionException.class, () -> run("extract", Maps.of("name", "KEY", "owner", owner.getUserDID()), other).join());
	}

	@Test void publicPrincipalNeverExtractsEvenWithUnrestrictedAccess() {
		AString publicDID = Strings.create(engine.getDIDString() + ":public");
		engine.getVenueState().users().ensure(publicDID).secrets()
			.store("SHARED_KEY", VALUE, SecretStore.keys(engine.getKeyPair()));
		// No caps is the unrestricted scope (auth.public.caps: "unrestricted"), and the
		// public caller is its own owner, so only the public-principal rule refuses this.
		RequestContext anonymous = RequestContext.of(publicDID);
		assertDoesNotThrow(() -> engine.requireResourceAccess(anonymous, Strings.create("s/SHARED_KEY"), Abilities.SECRET_EXTRACT));
		assertEquals(SecretAdapter.EXTRACT_NOT_PUBLIC,
			failure(run("extract", Maps.of("name", "SHARED_KEY"), anonymous)).getMessage());
	}

	@Test void extractionCannotBeDelegatedEvenWithAValidOwnerGrant() {
		AKeyPair other = AKeyPair.generate();
		AString did = UCAN.toDIDKey(other.getAccountKey());
		engine.getVenueState().users().ensure(did);
		UCAN proof = UCAN.create(identity, other.getAccountKey(), System.currentTimeMillis() / 1000 + 300,
			Vectors.of(Capability.create(Strings.create(owner.getUserDID() + "/s/KEY"), Abilities.SECRET_EXTRACT)), Vectors.empty());
		RequestContext delegated = RequestContext.of(did).withProofs(Vectors.of(proof.toMap())).onBehalfOf(owner.getUserDID());
		// Establish that the capability is valid; the additional owner-only boundary must still refuse.
		assertDoesNotThrow(() -> engine.requireResourceAccess(delegated, Strings.create("s/KEY"), Abilities.SECRET_EXTRACT));
		assertEquals(SecretAdapter.EXTRACT_DIRECT_ONLY, failure(engine.jobs().runOperation(
			meta("extract"), Maps.of("name", "KEY"), delegated)).getMessage());
	}

	@Test void extractionRefusesAgentsSessionsTasksAndMcpTools() {
		RequestContext agent = RequestContext.ofAgent(owner.getUserDID(), Strings.create("mina"));
		for (RequestContext ctx : new RequestContext[] { agent, RequestContext.of(agent.getCallerDID()),
			agent.withCaps(Vectors.of(Capability.create(null, Strings.create("*")))),
			owner.withSessionId(Blob.fromHex("01")), owner.withTaskId(Blob.fromHex("02")) }) {
			assertEquals(SecretAdapter.EXTRACT_DIRECT_ONLY, failure(run("extract", Maps.of("name", "KEY"), ctx)).getMessage());
		}
		assertEquals(SecretAdapter.EXTRACT_DIRECT_ONLY, failure(engine.jobs().runToolOperation(
			Strings.create("v/ops/secret/extract"), Maps.of("name", "KEY"), owner)).getMessage());
	}

	@Test void extractionRefusesRecordedJobsAndInternalComposition() {
		Job job = engine.jobs().invokeOperation("v/ops/secret/extract", Maps.of("name", "KEY"), owner);
		assertThrows(Exception.class, () -> job.awaitResult(5000));
		assertEquals(SecretAdapter.EXTRACT_DIRECT_ONLY, job.getErrorMessage());
		assertFalse(engine.jobs().getJobs(owner).toString().contains(VALUE));
		assertEquals(SecretAdapter.EXTRACT_DIRECT_ONLY, failure(engine.jobs().invokeInternal("v/ops/secret/extract", Maps.of("name", "KEY"), owner)).getMessage());
	}

	@Test void extractionRefusesNestedPublicRunsIncludingTransientParents() {
		engine.registerAdapter(new AAdapter() {
			@Override public String getName() { return "secret-wrapper"; }
			@Override public String getDescription() { return "test"; }
			@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> m, ACell input) {
				return run("extract", input, ctx);
			}
		});
		for (boolean readOnly : new boolean[] { true, false }) {
			AMap<AString, ACell> wrapper = Maps.of("operation", Maps.of("adapter", "secret-wrapper:run", "readOnly", readOnly));
			assertEquals(SecretAdapter.EXTRACT_DIRECT_ONLY, failure(engine.jobs().runOperation(wrapper, Maps.of("name", "KEY"), owner)).getMessage());
		}
	}

	@Test void changedMetadataCannotRemoveProtectionsOrLogPlaintextThroughValidation() {
		AMap<AString, ACell> op = RT.getIn(meta("extract"), "operation");
		for (AMap<AString, ACell> altered : List.of(op.dissoc(Strings.create("secretFields")),
			op.assoc(Fields.OUTPUT, Maps.of("enum", Vectors.of("impossible"))),
			op.assoc(Strings.create("strict"), CVMBool.FALSE))) {
			assertEquals(SecretAdapter.EXTRACT_DEFINITION_REQUIRED, failure(engine.jobs().runOperation(
				meta("extract").assoc(Fields.OPERATION, altered), Maps.of("name", "KEY"), owner)).getMessage());
		}
	}

	@Test void missingAndCorruptSecretsHaveTheSameSanitizedFailure() {
		engine.getVenueState().users().get(owner.getUserDID()).secrets()
			.store("CORRUPT", VALUE, SecretStore.keys(AKeyPair.generate()));
		for (String name : new String[] { "MISSING", "CORRUPT" }) {
			Throwable error = failure(run("extract", Maps.of("name", name), owner));
			assertEquals(SecretAdapter.SECRET_UNAVAILABLE, error.getMessage());
			assertNull(error.getCause());
			denied(run("extract", Maps.of("name", name), scope("s/", Capability.CRUD_READ)));
		}
	}

	@Test void recordingPolicyFailsClosed() {
		Engine recorded = Engine.createTemp(Maps.of(Config.RECORD_READ_ONLY_OPERATIONS, true));
		try {
			Engine.addDemoAssets(recorded);
			recorded.getVenueState().users().ensure(owner.getUserDID()).secrets()
				.store("KEY", VALUE, SecretStore.keys(recorded.getKeyPair()));
			assertEquals(SecretAdapter.EXTRACT_DIRECT_ONLY, failure(recorded.jobs().runOperation(
				"v/ops/secret/extract", Maps.of("name", "KEY"), owner)).getMessage());
			assertFalse(recorded.jobs().getJobs(owner).toString().contains(VALUE));
		} finally { recorded.close(); }
	}

	@Test void transientResultsAreNotQueryableAsJobsEvenBeforeCompletion() {
		AtomicReference<Job> active = new AtomicReference<>();
		CompletableFuture<ACell> result = new CompletableFuture<>();
		engine.registerAdapter(new AAdapter() {
			@Override public String getName() { return "private-result"; }
			@Override public String getDescription() { return "test"; }
			@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> m, ACell input) {
				active.set(ctx.getJob());
				return result;
			}
		});
		CompletableFuture<ACell> future = engine.jobs().runOperation(Maps.of("operation",
			Maps.of("adapter", "private-result:run", "readOnly", true)), Maps.empty(), owner);
		try {
			assertNotNull(active.get());
			assertNull(engine.jobs().getJobData(active.get().getID(), owner));
			assertNull(engine.jobs().getJobData(active.get().getID(), owner.withCaps(Vectors.empty())));
		} finally { result.complete(Strings.create(VALUE)); }
		assertEquals(Strings.create(VALUE), future.join());
	}
}
