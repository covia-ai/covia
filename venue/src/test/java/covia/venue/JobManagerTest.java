package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.parallel.Isolated;

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
import convex.core.lang.RT;
import covia.api.Fields;
import covia.adapter.AAdapter;
import covia.adapter.TestAdapter;
import covia.exception.AuthException;
import covia.exception.CoviaException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.exception.NotSupportedException;
import covia.exception.UnavailableException;
import covia.grid.Asset;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.CoviaPath;
import covia.test.Rendezvous;

/**
 * Unit tests for {@link JobManager#invokeInternal}. Covers the transient-Job
 * internal dispatch path used by adapters (transitions, LLM sub-invocations,
 * tool calls) per issue #85.
 *
	 * <p>Scenarios: happy path (ref + meta overloads), transient persistence policy,
 * null/unresolvable/non-op refs, capability denial, strict-mode schema
 * validation, and missing authentication.</p>
 *
 * <p>Remote-venue fallback (where {@code invokeInternal} delegates to the
 * Job-creating path) is not covered here — it requires cross-venue
 * infrastructure. Real two-venue exercises live in
	 * {@code covia.venue.grid.CrossVenueTest}; the loopback dispatch path
	 * inside one venue is covered by {@code GridAdapterTest}.</p>
 *
 * <p>Isolated because a test provoking a venue fault silences the loggers
 * that report it.</p>
 */
@Isolated("silences the AAdapter and JobManager loggers")
public class JobManagerTest {
	/**
	 * Every route that reads a job leaves it as it was: neither its record nor
	 * the user's activity stamp changes, and a failed transaction writes
	 * nothing.
	 */
	@Test
	public void jobReadsArePure() {
		User user = engine.getVenueState().users().ensure(did);
		Blob id = Blob.fromHex("61106110611061106110611061106110");
		AMap<AString, ACell> output = Maps.of(Strings.create("answer"), 42L);
		AMap<AString, ACell> record = Maps.of(Fields.ID, id, Fields.CALLER, did,
			Fields.STATUS, Status.COMPLETE, Fields.OUTPUT, output, Fields.UPDATED, 123L,
			Fields.HISTORY, Vectors.of(Maps.of(Fields.MESSAGE_ID, "existing")));
		user.cursor().path(covia.lattice.Namespace.J).set(convex.core.data.Index.<Blob, ACell>none().assoc(id, record));
		var storage = user.cursor().path(covia.lattice.Namespace.J, id);
		ACell before = user.get();
		String path = "j/" + id.toHexString();
		assertEquals(record, user.jobs().getRecord(id));
		assertEquals(record, engine.jobs().getJobData(id, ctx));
		assertEquals(record, engine.jobs().getJobs(ctx).get(id));
		assertEquals(1, engine.jobs().countJobs(ctx));
		assertEquals(output, engine.jobs().getJob(id, ctx).awaitResult());
		assertEquals(record, engine.resolvePath(Strings.create(path), ctx));
		assertEquals(record, RT.getIn(engine.resolvePath(Strings.create("j/"), ctx), id));
		assertEquals(record, RT.getIn(user.cursor().getOrDefault(null), covia.lattice.Namespace.J, id));
		var adapter = (covia.adapter.CoviaAdapter) engine.getAdapter("covia");
		assertEquals(record, RT.getIn(adapter.handleRead(ctx, Maps.of(Fields.PATH, path)), "value"));
		// Equal, not identical: the shared engine's background sync may swap in
		// persisted references for the same value (LatticeHost.sync).
		assertEquals(before, user.get(), "reads must neither rewrite records nor stamp user activity");

		assertThrows(IllegalStateException.class, () -> user.execute(draft -> {
			draft.jobs().job(id).appendHistory(Maps.of(Fields.MESSAGE_ID, "rollback"));
			throw new IllegalStateException("rollback");
		}));
		assertEquals(record, storage.get());
		AMap<AString, ACell> message = Maps.of(Fields.MESSAGE_ID, "new");
		assertTrue(user.jobs().job(id).appendHistory(message));
		assertEquals(Vectors.of(Maps.of(Fields.MESSAGE_ID, "existing"), message), RT.getIn(storage.get(), Fields.HISTORY));
		ACell after = storage.get();
		assertFalse(user.jobs().job(id).appendHistory(message));
		assertEquals(after, storage.get(), "repeat edits remain idempotent");
	}

	/**
	 * Jobs a venue before 0.10.0 left unfinished, their earlier states chained
	 * under prev, recover at start without the chain, which the venue's
	 * migration drops: an interrupted job fails, and a paused job is restored
	 * live.
	 */
	@Test
	public void legacyUnfinishedJobsRecoverInTheirCurrentShape() throws Exception {
		convex.core.crypto.AKeyPair key = convex.core.crypto.AKeyPair.generate();
		CoviaApplication application = TestEngine.application();
		Engine venue = application.mount(key, Maps.empty());
		try {
			AString caller = Strings.create("did:test:legacy-unfinished");
			AString prev = Strings.intern("prev");
			Blob started = Blob.fromHex("61136113611361136113611361136113");
			Blob paused = Blob.fromHex("61146114611461146114611461146114");
			AMap<AString, ACell> chain = Maps.of(Fields.STATUS, Status.PENDING);
			application.venue(key.getAccountKey()).users().ensure(caller).cursor().path(covia.lattice.Namespace.J)
				.set(convex.core.data.Index.<Blob, ACell>none()
					.assoc(started, Maps.of(Fields.ID, started, Fields.CALLER, caller, Fields.STATUS, Status.STARTED, prev, chain))
					.assoc(paused, Maps.of(Fields.ID, paused, Fields.CALLER, caller, Fields.STATUS, Status.PAUSED, prev, chain)));

			venue.launch();
			RequestContext ctx = RequestContext.of(caller);
			User user = venue.getVenueState().users().user(caller);
			assertEquals(AAdapter.RESTARTED_DURING_EXECUTION, venue.jobs().getJob(started, ctx).getErrorMessage());
			assertNull(RT.getIn(user.cursor().path(covia.lattice.Namespace.J, started).get(), prev));

			Job restored = venue.jobs().getJob(paused);
			assertEquals(Status.PAUSED, restored.getStatus(), "restored live");
			assertNull(restored.getData().get(prev));
			venue.jobs().cancelJob(paused, ctx);
			AMap<AString, ACell> stored = RT.ensureMap(user.cursor().path(covia.lattice.Namespace.J, paused).get());
			assertEquals(Status.CANCELLED, stored.get(Fields.STATUS));
			assertNull(stored.get(prev));
		} finally {
			venue.close();
		}
	}

	@Test
	public void testSynchronousAdapterErrorsSettleDurableJobs() throws Exception {
		// A linkage error is the adapter's fault, logged at ERROR where its Job reports it
		TestLogs.quiet(covia.exception.FaultException.class, this::synchronousAdapterErrorsSettleDurableJobs);
	}

	private void synchronousAdapterErrorsSettleDurableJobs() {
		Engine eng = ownAdapters;
		var invoked = new java.util.concurrent.atomic.AtomicReference<Job>();
		eng.registerAdapter(new AAdapter() {
			@Override public String getName() { return "broken-module"; }
			@Override public String getDescription() { return "test"; }
			@Override public CompletableFuture<ACell> invokeFuture(RequestContext c,
					AMap<AString, ACell> m, ACell in) {
				invoked.set(c.getJob());
				throw new NoClassDefFoundError("missing/library/Class");
			}
			@Override public void invoke(Job job, RequestContext c, AMap<AString, ACell> m, ACell in) {
				if (Strings.create("custom").equals(in)) {
					invoked.set(job);
					throw new NoClassDefFoundError("custom/adapter/Class");
				}
				super.invoke(job, c, m, in);
			}
		});
		AMap<AString, ACell> meta = Maps.of(Fields.OPERATION,
			Maps.of(Fields.ADAPTER, Strings.create("broken-module:run")));
		for (ACell input : new ACell[] { null, Strings.create("custom") }) {
			// The caller receives the Job's report: the adapter's fault, the error chained
			covia.exception.FaultException thrown = assertThrows(covia.exception.FaultException.class,
				() -> eng.jobs().invokeOperation(meta, input, eng.venueContext()));
			assertTrue(thrown.getCause() instanceof NoClassDefFoundError, String.valueOf(thrown.getCause()));
			Job job = invoked.get();
			assertEquals(Status.FAILED, job.getStatus());
			assertEquals(thrown.getMessage(), job.getErrorMessage());
			assertTrue(job.future().isCompletedExceptionally());
			assertEquals(Status.FAILED, eng.jobs().getJobData(job.getID(), eng.venueContext()).get(Fields.STATUS));
		}
	}

	@Test
	public void testCancellationDuringInvokeFutureCancelsReturnedFuture() {
		Engine eng = ownAdapters;
		CompletableFuture<ACell> processing = new CompletableFuture<>();
		eng.registerAdapter(new AAdapter() {
			@Override public String getName() { return "cancel-during-invoke"; }
			@Override public String getDescription() { return "test"; }
			@Override public CompletableFuture<ACell> invokeFuture(RequestContext c,
					AMap<AString, ACell> m, ACell in) {
				c.getJob().cancel();
				return processing;
			}
		});
		AMap<AString, ACell> meta = Maps.of(Fields.OPERATION,
			Maps.of(Fields.ADAPTER, Strings.create("cancel-during-invoke:run")));
		Job job = eng.jobs().invokeOperation(meta, null, eng.venueContext());
		assertEquals(Status.CANCELLED, job.getStatus());
		assertTrue(processing.isCancelled());
		assertTrue(job.future().isCompletedExceptionally());
	}

	@Test
	public void testDelayedPersistenceCannotOverwriteCancellation() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try {
			AMap<AString, ACell> meta = Maps.of(Fields.OPERATION,
				Maps.of(Fields.ADAPTER, Strings.create("test:never")));
			Job original = engine.jobs().invokeOperation(meta, null, ctx);
			JobRecord record = engine.getVenueState().users().user(did).jobs().job(original.getID());
			Job job = new VenueJob(record, original.getData(), OutputRules.of(meta), did, engine.jobs(), null) {
				@Override public void onUpdate(AMap<AString, ACell> next) {
					if (Status.PAUSED.equals(next.get(Fields.STATUS))) {
						entered.countDown();
						try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
						catch (InterruptedException e) { throw new AssertionError(e); }
					}
					super.onUpdate(next);
				}
			};
			var delayed = CompletableFuture.runAsync(() -> job.setStatus(Status.PAUSED));
			try {
				assertTrue(entered.await(5, TimeUnit.SECONDS));
				job.cancel();
			} finally { release.countDown(); }
			delayed.get(5, TimeUnit.SECONDS);
			assertEquals(Status.CANCELLED,
				engine.jobs().getJobData(job.getID(), ctx).get(Fields.STATUS));
		} finally { release.countDown(); }
	}

	private final Engine engine = TestEngine.ENGINE;
	private AString did;
	private RequestContext ctx;

	/** For the tests that register an adapter, which every test on the shared venue would see. */
	private static Engine ownAdapters;

	/** Output validation in strict mode, for the tests that need it. */
	private static Engine strict;

	@BeforeAll
	static void startVenues() {
		ownAdapters = Engine.createTemp(null);
		strict = Engine.createTemp(Maps.of(Config.OUTPUT_VALIDATION, Strings.create("strict")));
	}

	@AfterAll
	static void closeVenues() {
		ownAdapters.close();
		strict.close();
	}

	@BeforeEach
	public void setup(TestInfo info) {
		did = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(did);
	}

	// ========== Happy path ==========

	@Test
	public void testInvokeInternalByRef() throws Exception {
		CompletableFuture<ACell> f = engine.jobs().invokeInternal(
			"v/test/ops/echo",
			Maps.of(Strings.create("hello"), Strings.create("world")),
			ctx);
		ACell result = f.get(5, TimeUnit.SECONDS);
		assertNotNull(result);
		assertEquals(Strings.create("world"),
			RT.getIn(result, Strings.create("hello")));
	}

	@Test
	public void testInvokeInternalByMeta() throws Exception {
		AMap<AString, ACell> meta = Maps.of(
			Fields.OPERATION, Maps.of(Fields.ADAPTER, Strings.create("test:echo")));
		CompletableFuture<ACell> f = engine.jobs().invokeInternal(
			meta, Strings.create("just-a-string"), ctx);
		ACell result = f.get(5, TimeUnit.SECONDS);
		assertEquals(Strings.create("just-a-string"), result);
	}

	// ========== Persistence policy ==========

	/**
	 * Default internal execution uses a Job wrapper but creates no durable record.
	 */
	@Test
	public void testInvokeInternalCreatesNoJobs() throws Exception {
		long before = engine.jobs().getJobs(ctx).count();

		engine.jobs().invokeInternal(
			"v/test/ops/echo",
			Maps.of(Strings.create("k"), Strings.create("v")),
			ctx).get(5, TimeUnit.SECONDS);

		long after = engine.jobs().getJobs(ctx).count();
		assertEquals(0, after - before,
			"invokeInternal must not persist its transient Job wrapper");
	}

	@Test
	public void testReadOnlyRunCreatesNoDurableJob() throws Exception {
		long before = engine.jobs().getJobs(ctx).count();
		ACell result = engine.jobs().runOperation(readOnlyEchoMeta(),
			Maps.of("mode", Strings.create("run")), ctx).get(5, TimeUnit.SECONDS);
		assertEquals(Strings.create("run"), RT.getIn(result, "mode"));
		assertEquals(before, engine.jobs().getJobs(ctx).count());
	}

	@Test
	public void testRunRecordsUnclassifiedOperation() throws Exception {
		long before = engine.jobs().getJobs(ctx).count();
		engine.jobs().runOperation(echoMeta(), Maps.empty(), ctx)
			.get(5, TimeUnit.SECONDS);
		assertEquals(before + 1, engine.jobs().getJobs(ctx).count(),
			"run must record operations that are not explicitly read-only");
	}

	@Test
	public void testInvokeAlwaysRecordsReadOnlyOperation() throws Exception {
		long before = engine.jobs().getJobs(ctx).count();
		Job job = engine.jobs().invokeOperation(readOnlyEchoMeta(), Maps.empty(), ctx);
		job.awaitResult(5000);
		assertEquals(before + 1, engine.jobs().getJobs(ctx).count(),
			"invoke always means a durable Job, including for read-only operations");
	}

	@Test
	public void testInternalFalseForcesDurableJob() throws Exception {
		long before = engine.jobs().getJobs(ctx).count();
		engine.jobs().invokeInternal(internalFalseEchoMeta(), Maps.empty(), ctx)
			.get(5, TimeUnit.SECONDS);
		assertEquals(before + 1, engine.jobs().getJobs(ctx).count(),
			"operation.internal=false must force recording on invokeInternal");
	}

	@Test
	public void testTransientInvocationIsPresentOnExecutionContext() throws Exception {
		TestAdapter.CAPTURED_CTX.remove(did);
		engine.jobs().invokeInternal(captureContextMeta(true, null), Maps.empty(), ctx)
			.get(5, TimeUnit.SECONDS);

		RequestContext execution = TestAdapter.CAPTURED_CTX.get(did);
		assertNotNull(execution);
		assertNotNull(execution.getJob(), "adapter context must carry its Job wrapper");
		assertFalse(execution.getJob().isRecorded());
		assertNull(execution.getJobId(),
			"a transient wrapper must not masquerade as a durable t/ scope");
	}

	@Test
	public void testRecordedInvocationEstablishesJobScope() throws Exception {
		TestAdapter.CAPTURED_CTX.remove(did);
		engine.jobs().runOperation(captureContextMeta(false, null), Maps.empty(), ctx)
			.get(5, TimeUnit.SECONDS);

		RequestContext execution = TestAdapter.CAPTURED_CTX.get(did);
		assertNotNull(execution);
		assertTrue(execution.getJob().isRecorded());
		assertEquals(execution.getJob().getID(), execution.getJobId());
	}

	@Test
	public void testInternalInvocationPreservesParentJobScope() throws Exception {
		Job parent = engine.jobs().invokeOperation(echoMeta(), Maps.empty(), ctx);
		parent.awaitResult(5000);
		TestAdapter.CAPTURED_CTX.remove(did);

		engine.jobs().invokeInternal(captureContextMeta(true, null), Maps.empty(),
			ctx.withJobId(parent.getID())).get(5, TimeUnit.SECONDS);

		RequestContext execution = TestAdapter.CAPTURED_CTX.get(did);
		assertNotNull(execution);
		assertFalse(execution.getJob().isRecorded());
		assertNotEquals(parent.getID(), execution.getJob().getID(),
			"each invocation still gets its own Job wrapper");
		assertEquals(parent.getID(), execution.getJobId(),
			"durable parent temp scope passes through internal composition");
	}

	private static AMap<AString, ACell> echoMeta() {
		return Maps.of(Fields.OPERATION,
			Maps.of(Fields.ADAPTER, Strings.create("test:echo")));
	}

	private static AMap<AString, ACell> readOnlyEchoMeta() {
		return Maps.of(Fields.OPERATION, Maps.of(
			Fields.ADAPTER, Strings.create("test:echo"),
			Fields.READ_ONLY, CVMBool.TRUE));
	}

	private static AMap<AString, ACell> internalFalseEchoMeta() {
		return Maps.of(Fields.OPERATION, Maps.of(
			Fields.ADAPTER, Strings.create("test:echo"),
			Fields.READ_ONLY, CVMBool.TRUE,
			Fields.INTERNAL, CVMBool.FALSE));
	}

	private static AMap<AString, ACell> captureContextMeta(boolean readOnly,
			Boolean internal) {
		AMap<AString, ACell> operation = Maps.of(
			Fields.ADAPTER, Strings.create("test:capturectx"),
			Fields.READ_ONLY, CVMBool.create(readOnly));
		if (internal != null) {
			operation = operation.assoc(Fields.INTERNAL, CVMBool.create(internal));
		}
		return Maps.of(Fields.OPERATION, operation);
	}

	// ========== Bad refs ==========

	@Test
	public void testInvokeInternalNullRef() {
		CompletableFuture<ACell> f = engine.jobs().invokeInternal(
			(String) null, Maps.empty(), ctx);
		assertTrue(f.isCompletedExceptionally());
		ExecutionException ex = assertThrows(ExecutionException.class, f::get);
		assertInstanceOf(InvalidInputException.class, ex.getCause());
	}

	@Test
	public void testInvokeInternalUnresolvableRef() {
		CompletableFuture<ACell> f = engine.jobs().invokeInternal(
			"v/does/not/exist", Maps.empty(), ctx);
		ExecutionException ex = assertThrows(ExecutionException.class,
			() -> f.get(5, TimeUnit.SECONDS));
		NotFoundException missing = assertInstanceOf(NotFoundException.class, ex.getCause());
		assertEquals("v/does/not/exist", missing.getRef());
	}

	// ========== Capability enforcement ==========

	/**
	 * invokeInternal enforces the capability scope carried by the context —
	 * it differs from invokeOperation only in Job creation, never in trust.
	 * A read outside the scope is denied; a read within it proceeds; the
	 * scope is read, never stripped. "Framework-trusted" is expressed by an
	 * unrestricted (null-caps) context, not by choosing this dispatch path.
	 */
	@Test
	public void testInvokeInternalEnforcesContextScope() throws Exception {
		AVector<ACell> caps = Vectors.of(Maps.of(
			Strings.create("with"), Strings.create("w/allowed"),
			Strings.create("can"),  Strings.create("crud/read")));
		RequestContext capCtx = ctx.withCaps(caps);

		// Outside the scope — denied on the internal path too (no bypass).
		CompletableFuture<ACell> denied = engine.jobs().invokeInternal(
			"v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/forbidden/x")),
			capCtx);
		ExecutionException ex = assertThrows(ExecutionException.class,
			() -> denied.get(5, TimeUnit.SECONDS));
		assertFalse(ex.getCause().getMessage().isBlank(),
			"invokeInternal must enforce the context scope");

		// Within the scope — proceeds; absent path reads {exists: false}.
		ACell result = engine.jobs().invokeInternal(
			"v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/allowed")),
			capCtx).get(5, TimeUnit.SECONDS);
		assertNotNull(result);
		assertEquals(Boolean.FALSE,
			convex.core.lang.RT.bool(convex.core.lang.RT.getIn(result, "exists")));

		// Caps remain on the ctx — enforcement reads them, never strips them.
		assertEquals(caps, capCtx.getCaps());
	}

	/**
	 * The user-facing path (invokeOperation) still enforces caps. Same
	 * caps + same op as the test above, but going through the user-facing
	 * entry — must be denied.
	 */
	@Test
	public void testInvokeOperationEnforcesCaps() {
		AVector<ACell> caps = Vectors.of(Maps.of(
			Strings.create("with"), Strings.create("w/allowed"),
			Strings.create("can"),  Strings.create("crud/read")));
		RequestContext capCtx = ctx.withCaps(caps);

		// Enforcement is at the adapter's point now: a denial fails the Job
		// (surfaced at awaitResult), not a synchronous throw from invokeOperation.
		Job job = engine.jobs().invokeOperation(
			"v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/forbidden/x")),
			capCtx);
		assertThrows(Exception.class, () -> job.awaitResult(5000));
		assertEquals(Status.FAILED, job.getStatus());
		assertFalse(String.valueOf(job.getErrorMessage()).isBlank(),
			"Expected a capability-denied message");
	}

	@Test
	public void testInvokeInternalCapsAllow() throws Exception {
		AVector<ACell> caps = Vectors.of(Maps.of(
			Strings.create("with"), Strings.create("w/allowed"),
			Strings.create("can"),  Strings.create("crud/read")));
		RequestContext capCtx = ctx.withCaps(caps);

		// Granted path — read should proceed (and return exists:false since nothing written)
		CompletableFuture<ACell> f = engine.jobs().invokeInternal(
			"v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/allowed/nothing")),
			capCtx);

		ACell result = f.get(5, TimeUnit.SECONDS);
		assertNotNull(result, "Caps-covered read must produce a result, not a denial");
	}

	// ========== Schema validation (strict mode) ==========

	@Test
	public void testInvokeInternalSchemaViolation() {
		AMap<AString, ACell> meta = Maps.of(Fields.OPERATION, Maps.of(
			Fields.ADAPTER,             Strings.create("test:echo"),
			Strings.intern("strict"),   CVMBool.TRUE,
			Fields.INPUT, Maps.of(
				Strings.create("type"),     Strings.create("object"),
				Strings.create("required"), Vectors.of(Strings.create("name")),
				Strings.create("properties"), Maps.of(
					Strings.create("name"), Maps.of(
						Strings.create("type"), Strings.create("string"))))));

		CompletableFuture<ACell> f = engine.jobs().invokeInternal(
			meta, Maps.empty(), ctx);

		ExecutionException ex = assertThrows(ExecutionException.class,
			() -> f.get(5, TimeUnit.SECONDS));
		assertInstanceOf(InvalidInputException.class, ex.getCause());
		assertTrue(ex.getCause().getMessage().startsWith(JobManager.INPUT_SCHEMA_VIOLATION),
			"Expected schema-violation message, got: " + ex.getCause().getMessage());
	}

	@Test
	public void testInvokeInternalSchemaAllowedWhenNotStrict() throws Exception {
		// Same bad input — but strict flag absent → schema not enforced
		AMap<AString, ACell> meta = Maps.of(Fields.OPERATION, Maps.of(
			Fields.ADAPTER, Strings.create("test:echo"),
			Fields.INPUT, Maps.of(
				Strings.create("type"),     Strings.create("object"),
				Strings.create("required"), Vectors.of(Strings.create("name")))));

		CompletableFuture<ACell> f = engine.jobs().invokeInternal(
			meta, Maps.empty(), ctx);
		ACell result = f.get(5, TimeUnit.SECONDS);
		assertNotNull(result);
	}

	// ========== Authentication ==========

	/**
	 * Non-internal context with null caller DID must fail fast via
	 * AuthException — same rule as invokeOperation.
	 */
	@Test
	public void testInvokeInternalRequiresAuth() {
		CompletableFuture<ACell> f = engine.jobs().invokeInternal(
			"v/test/ops/echo", Maps.empty(), RequestContext.ANONYMOUS);

		ExecutionException ex = assertThrows(ExecutionException.class,
			() -> f.get(5, TimeUnit.SECONDS));
		assertInstanceOf(AuthException.class, ex.getCause());
	}

	@Test
	public void testInvokeInternalVenueContextAllowed() throws Exception {
		// venueContext() carries the venue's own DID — engine-startup /
		// recovery / framework calls use this where they need the venue
		// itself as the caller.
		CompletableFuture<ACell> f = engine.jobs().invokeInternal(
			"v/test/ops/echo",
			Maps.of(Strings.create("ping"), Strings.create("pong")),
			engine.venueContext());
		ACell result = f.get(5, TimeUnit.SECONDS);
		assertEquals(Strings.create("pong"), RT.getIn(result, Strings.create("ping")));
	}

	// ========== Cooperative cancellation ==========

	/**
	 * {@code test:never} never completes — cancelling the returned future
	 * returns control to the caller immediately. Interrupting the adapter
	 * itself is best-effort (not asserted here).
	 */
	@Test
	public void testInvokeInternalCancelReturnsToCaller() {
		CompletableFuture<ACell> f = engine.jobs().invokeInternal(
			"v/test/ops/never", Maps.empty(), ctx);

		assertThrows(TimeoutException.class, () -> f.get(100, TimeUnit.MILLISECONDS));
		assertTrue(f.cancel(true));
		assertTrue(f.isCancelled());
	}

	// ========== Output-schema validation (operator-gated, default off) ==========

	/** Operation meta declaring an {@code object} output schema. */
	private static AMap<AString, ACell> metaWithObjectOutput() {
		return Maps.of(Fields.OPERATION, Maps.of(
			Fields.ADAPTER, Strings.create("test:out"),
			Fields.OUTPUT, Maps.of(Strings.create("type"), Strings.create("object"))));
	}

	@Test
	public void testOutputValidationOffIgnoresBadResult() {
		// Default config → outputValidation off → a non-object result (which
		// violates the schema) must NOT raise. No validation, no logging.
		engine.jobs().validateOutput(OutputRules.of(metaWithObjectOutput()), Strings.create("not an object"));
	}

	@Test
	public void testOutputValidationStrictFailsBadResult() {
		assertThrows(CoviaException.class,
			() -> strict.jobs().validateOutput(OutputRules.of(metaWithObjectOutput()), Strings.create("not an object")));
		// A conforming result (an object) passes.
		strict.jobs().validateOutput(OutputRules.of(metaWithObjectOutput()), Maps.empty());
	}

	@Test
	public void testOutputValidationWarnDoesNotFail() {
		// warn logs a mismatch but completes — must not raise.
		Engine eng = Engine.createTemp(Maps.of(Config.OUTPUT_VALIDATION, Strings.create("warn")));
		try {
			eng.jobs().validateOutput(OutputRules.of(metaWithObjectOutput()), Strings.create("not an object"));
		} finally {
			eng.close();
		}
	}

	@Test
	public void testOutputValidationNoSchemaIsNoop() {
		// An operation with no output schema is never validated, even in strict.
		AMap<AString, ACell> meta = Maps.of(Fields.OPERATION,
			Maps.of(Fields.ADAPTER, Strings.create("test:out")));
		strict.jobs().validateOutput(OutputRules.of(meta), Strings.create("anything"));
	}

	@Test
	public void testStrictOutputValidationCoversJobAwareAdapterOverride() {
		Engine eng = strict;
		eng.registerAdapter(new AAdapter() {
			@Override public String getName() { return "custom-output"; }
			@Override public String getDescription() { return "test"; }
			@Override public CompletableFuture<ACell> invokeFuture(RequestContext c,
					AMap<AString, ACell> m, ACell in) {
				return CompletableFuture.completedFuture(Strings.create("bad"));
			}
			@Override public void invoke(Job job, RequestContext c,
					AMap<AString, ACell> m, ACell in) {
				job.setStatus(Status.STARTED);
				job.completeWith(Strings.create("not an object"));
			}
		});
		AMap<AString, ACell> meta = Maps.of(Fields.OPERATION, Maps.of(
			Fields.ADAPTER, Strings.create("custom-output:run"),
			Fields.OUTPUT, Maps.of(Strings.create("type"), Strings.create("object"))));
		Job job = eng.jobs().invokeOperation(meta, Maps.empty(), eng.venueContext());
		assertThrows(Exception.class, () -> job.awaitResult(1000));
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains(JobManager.OUTPUT_SCHEMA_VIOLATION));
	}

	@Test
	public void testInputSecretsRemainRedactedOnLaterJobUpdates() {
		Engine eng = ownAdapters;
		eng.registerAdapter(new AAdapter() {
			@Override public String getName() { return "secret-update"; }
			@Override public String getDescription() { return "test"; }
			@Override public CompletableFuture<ACell> invokeFuture(RequestContext c,
					AMap<AString, ACell> m, ACell in) {
				return CompletableFuture.completedFuture(in);
			}
			@Override public void invoke(Job job, RequestContext c,
					AMap<AString, ACell> m, ACell in) {
				job.setStatus(Status.STARTED);
				job.update(data -> data.assoc(Fields.INPUT, in));
				job.completeWith(Maps.empty());
			}
		});
		AString token = Strings.create("token");
		AMap<AString, ACell> meta = Maps.of(Fields.OPERATION, Maps.of(
			Fields.ADAPTER, Strings.create("secret-update:run"),
			Strings.create("secretFields"), Vectors.of(token)));
		Job job = eng.jobs().invokeOperation(meta,
			Maps.of(token, Strings.create("plaintext")), eng.venueContext());
		job.awaitResult(1000);
		AMap<AString, ACell> durable = eng.jobs().getJobData(job.getID(), eng.venueContext());
		assertEquals(Fields.HIDDEN, RT.getIn(durable, Fields.INPUT, token));
	}

	// ========== deleteJob ==========

	/**
	 * deleteJob must delete permanently: the durable record leaves the
	 * owner's job index, not just the active cache. (Regression — the
	 * cache-only implementation left "deleted" jobs readable via the
	 * lattice fallback of getJobData.)
	 */
	@Test
	public void testDeleteJobRemovesLatticeRecord() {
		Job job = engine.jobs().invokeOperation(
			"v/test/ops/echo",
			Maps.of(Strings.create("hello"), Strings.create("world")),
			ctx);
		job.awaitResult(5000);

		User user = engine.getVenueState().users().user(did);
		assertNotNull(user.jobs().getRecord(job.getID()), "completed job record should be persisted");

		assertTrue(engine.jobs().deleteJob(job.getID(), ctx));
		assertNull(user.jobs().getRecord(job.getID()), "durable record should be removed");
		assertNull(engine.jobs().getJobData(job.getID(), ctx),
			"deleted job must not reappear via the lattice fallback");
		assertFalse(engine.jobs().deleteJob(job.getID(), ctx),
			"second delete finds nothing");
	}

	@Test
	public void testLiveJobEditsPreservePrimaryRecordChanges() {
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), ctx);
		User user = engine.getVenueState().users().user(did);
		AMap<AString, ACell> message = Maps.of(Fields.MESSAGE_ID, "component-edit");
		user.execute(draft -> {
			draft.jobs().job(job.getID()).appendHistory(message);
			draft.jobs().job(job.getID()).update(data -> data.assoc(Strings.intern("temp"), Maps.of("kept", true)));
		});
		assertEquals(user.jobs().getRecord(job.getID()), job.getData());
		job.setStatus(Status.INPUT_REQUIRED);
		assertEquals(Vectors.of(message), job.getData().get(Fields.HISTORY));
		assertEquals(Maps.of("kept", true), job.getData().get(Strings.intern("temp")));
		AuthException rejection = new AuthException("test updater rejection");
		assertSame(rejection, assertThrows(AuthException.class, () -> job.update(data -> { throw rejection; })));
		job.cancel();
	}

	@Test
	public void testPrimaryRecordRedactsSecretsButAwaiterReceivesOriginalOutput() {
		AString token = Strings.create("token");
		AMap<AString, ACell> meta = Maps.of(Fields.OPERATION, Maps.of(
			Fields.ADAPTER, "test:echo", Strings.create("secretFields"), Vectors.of(token)));
		AMap<AString, ACell> input = Maps.of(token, "private-test-value");
		Job job = engine.jobs().invokeOperation(meta, input, ctx);
		assertEquals(input, job.awaitResult(5000));
		assertEquals(input, job.getOutput());
		AMap<AString, ACell> primary = engine.getVenueState().users().user(did).jobs().getRecord(job.getID());
		assertEquals(primary, job.getData());
		assertEquals(Fields.HIDDEN, RT.getIn(primary, Fields.INPUT, token));
		assertEquals(Fields.HIDDEN, RT.getIn(primary, Fields.OUTPUT, token));
		assertFalse(primary.toString().contains("private-test-value"));
	}

	@Test
	public void testTransactionalCompletionPublishesOnlyAfterCommitAndRedactsSecrets() throws Exception {
		AString token = Strings.create("token");
		AMap<AString, ACell> meta = Maps.of(Fields.OPERATION, Maps.of(
			Fields.ADAPTER, "test:never", Strings.create("secretFields"), Vectors.of(token)));
		VenueJob job = (VenueJob) engine.jobs().invokeOperation(meta, Maps.empty(), ctx);
		var notifications = new java.util.concurrent.atomic.AtomicInteger();
		job.subscribe(ignored -> notifications.incrementAndGet());
		var future = job.future();
		var output = Maps.of(token, "private-test-value");
		var venue = engine.getVenueState();
		assertThrows(IllegalStateException.class, () -> venue.execute(draft -> {
			assertNotNull(job.updateIn(draft, data -> data.assoc(Fields.STATUS, Status.COMPLETE).assoc(Fields.OUTPUT, output)));
			assertNull(draft.jobIndex().entry(job.getID()), "the update that finishes the job removes its entry");
			assertFalse(job.isFinished());
			throw new IllegalStateException("rollback");
		}));
		assertFalse(job.isFinished());
		assertFalse(future.isDone());
		assertEquals(0, notifications.get());
		assertNotNull(venue.jobIndex().entry(job.getID()), "a rolled back finish keeps the entry");
		Runnable notification = venue.transact(draft -> job.updateIn(draft,
			data -> data.assoc(Fields.STATUS, Status.COMPLETE).assoc(Fields.OUTPUT, output)));
		assertEquals(Status.COMPLETE, job.getStatus());
		assertNull(venue.jobIndex().entry(job.getID()));
		assertEquals(Fields.HIDDEN, RT.getIn(venue.users().user(did).jobs().getRecord(job.getID()), Fields.OUTPUT, token));
		assertFalse(future.isDone(), "draft edits cannot notify live waiters");
		notification.run();
		notification.run();
		assertEquals(output, future.get(5, TimeUnit.SECONDS));
		assertEquals(1, notifications.get(), "terminal lifecycle notifications run once");
		venue.execute(draft -> assertNull(job.updateIn(draft, data -> data.assoc(Fields.STATUS, Status.FAILED))));
	}

	/** Settling jobs in a venue transaction: waiters wake only on the notification, and a refused result fails its job there. */
	@Test
	public void testCompleteInAndFailInSettleInATransaction() throws Exception {
		Engine eng = strict;
		eng.registerAdapter(new AAdapter() {
			@Override public String getName() { return "held"; }
			@Override public String getDescription() { return "test"; }
			@Override public CompletableFuture<ACell> invokeFuture(RequestContext c,
					AMap<AString, ACell> m, ACell in) {
				return new CompletableFuture<>();
			}
			@Override public void invoke(Job job, RequestContext c, AMap<AString, ACell> m, ACell in) {
				job.setStatus(Status.STARTED);
			}
		});
		AMap<AString, ACell> meta = Maps.of(Fields.OPERATION, Maps.of(Fields.ADAPTER, "held:run",
			Fields.OUTPUT, Maps.of(Strings.create("type"), Strings.create("object"))));
		VenueJob completed = (VenueJob) eng.jobs().invokeOperation(meta, Maps.empty(), eng.venueContext());
		VenueJob refused = (VenueJob) eng.jobs().invokeOperation(meta, Maps.empty(), eng.venueContext());
		VenueJob failed = (VenueJob) eng.jobs().invokeOperation(meta, Maps.empty(), eng.venueContext());
		java.util.List<Runnable> after = eng.getVenueState().transact(draft -> java.util.List.of(
			completed.completeIn(draft, Maps.of("ok", "yes")),
			refused.completeIn(draft, Strings.create("not an object")),
			failed.failIn(draft, "gave up")));
		assertEquals(Status.COMPLETE, completed.getStatus());
		assertFalse(completed.future().isDone(), "waiters wake only when the notification runs");
		after.forEach(Runnable::run);
		assertEquals(Maps.of("ok", "yes"), completed.future().get(5, TimeUnit.SECONDS));
		assertEquals(Status.FAILED, refused.getStatus(), "a refused result fails its job instead of aborting the transaction");
		assertTrue(refused.getErrorMessage().startsWith(JobManager.OUTPUT_SCHEMA_VIOLATION));
		assertEquals(Status.FAILED, failed.getStatus());
		assertEquals("gave up", failed.getErrorMessage());
		eng.getVenueState().execute(draft -> assertNull(completed.failIn(draft, "late"), "a finished job is left as it is"));
	}

	@Test
	public void testHistoryAppendDuringTerminalNotificationIsRetained() {
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), ctx);
		AMap<AString, ACell> message = Maps.of(Fields.MESSAGE_ID, "during-completion");
		job.subscribe(updated -> {
			if (updated.isFinished()) engine.jobs().appendToHistory(job.getID(), message, ctx);
		});
		job.cancel();
		assertEquals(Vectors.of(message), engine.jobs().getJobData(job.getID(), ctx).get(Fields.HISTORY));
	}

	@Test
	public void testLateCancellationEvidencePreservesDurableHistory() {
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), ctx);
		job.cancel();
		AMap<AString, ACell> message = Maps.of(Fields.MESSAGE_ID, "after-cancellation");
		engine.jobs().appendToHistory(job.getID(), message, ctx);
		job.recordCancellation(Maps.of("backend", "stopped"));
		AMap<AString, ACell> persisted = engine.jobs().getJobData(job.getID(), ctx);
		assertEquals(Vectors.of(message), persisted.get(Fields.HISTORY));
		assertEquals(Maps.of("backend", "stopped"), persisted.get(Fields.CANCELLATION));
		assertEquals(persisted, job.getData(), "the live handle reads the same primary record");
	}

	@Test
	public void testConcurrentTerminalHistoryAppendCannotResurrectDeletedJob() throws Exception {
		Job job = engine.jobs().invokeOperation(
			"v/test/ops/echo", Maps.of("hello", "history"), ctx);
		job.awaitResult(5000);
		AMap<AString, ACell> message = Maps.of(
			Fields.MESSAGE_ID, Strings.create("concurrent-history"),
			Fields.MESSAGE, Strings.create("hello"));
		// either linearisation order is valid for the append; the delete must win
		List<Boolean> raced = Rendezvous.all(
			() -> {
				try {
					engine.jobs().appendToHistory(job.getID(), message, ctx);
					return true;
				} catch (NotFoundException deletedFirst) {
					return false;
				}
			},
			() -> engine.jobs().deleteJob(job.getID(), ctx));
		assertTrue(raced.get(1));
		assertNull(engine.getVenueState().users().user(did).jobs().getRecord(job.getID()),
			"the durable-history path must update-if-present, never recreate after delete");
	}

	@Test
	public void testDeleteFencesLateActiveJobPersistence() throws Exception {
		Job job = engine.jobs().invokeOperation(
			"v/test/ops/never", Maps.empty(), ctx);
		VenueJob venueJob = assertInstanceOf(VenueJob.class, job);
		AString marker = Strings.create("late-update");
		Thread updater;
		synchronized (venueJob) {
			updater = Thread.ofVirtual().start(() ->
				job.update(data -> data.assoc(marker, CVMLong.ONE)));
			TestEngine.awaitJob(job, j -> CVMLong.ONE.equals(j.getData().get(marker)), 10_000,
				() -> "the late update did not commit");
			assertTrue(engine.jobs().deleteJob(job.getID(), ctx));
		}
		updater.join(TimeUnit.SECONDS.toMillis(5));
		assertFalse(updater.isAlive());
		assertNull(engine.getVenueState().users().user(did).jobs().getRecord(job.getID()),
			"an update committed before deletion must not persist after deletion");
		job.cancel("test cleanup");
	}

	@Test
	public void testConcurrentActiveDeleteHasExactlyOneWinner() throws Exception {
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), ctx);
		Callable<Boolean> delete = () -> engine.jobs().deleteJob(job.getID(), ctx);
		assertEquals(1, Rendezvous.all(delete, delete).stream()
			.filter(Boolean::booleanValue).count());
		assertNull(engine.getVenueState().users().user(did).jobs().getRecord(job.getID()));
		job.cancel("test cleanup");
	}

	// ========== Argument defaults (operation.default) ==========

	private static AMap<AString, ACell> echoWithDefaults(AMap<AString, ACell> defaults) {
		return Maps.of(Fields.OPERATION, Maps.of(
			Fields.ADAPTER, Strings.create("test:echo"),
			Fields.DEFAULT, defaults));
	}

	@Test
	public void testDefaultsFillMissingArgs() throws Exception {
		// Defaults may be ANY value type: string, number, boolean, vector, map
		AMap<AString, ACell> defaults = Maps.of(
			Strings.create("s"), Strings.create("dflt"),
			Strings.create("n"), RT.cvm(42L),
			Strings.create("b"), CVMBool.TRUE,
			Strings.create("v"), Vectors.of(Strings.create("x")),
			Strings.create("m"), Maps.of(Strings.create("k"), Strings.create("v")));
		ACell result = engine.jobs().invokeInternal(echoWithDefaults(defaults),
			Maps.of(Strings.create("caller"), Strings.create("arg")), ctx)
			.get(5, TimeUnit.SECONDS);
		assertEquals(Strings.create("arg"), RT.getIn(result, "caller"));
		assertEquals(Strings.create("dflt"), RT.getIn(result, "s"));
		assertEquals(RT.cvm(42L), RT.getIn(result, "n"));
		assertEquals(CVMBool.TRUE, RT.getIn(result, "b"));
		assertEquals(1, RT.ensureVector(RT.getIn(result, "v")).count());
		assertEquals(Strings.create("v"), RT.getIn(result, "m", "k"));
	}

	@Test
	public void testCallerOverridesDefault() throws Exception {
		// Purpose-shaping, not policy: the caller always wins
		AMap<AString, ACell> defaults = Maps.of(Strings.create("a"), Strings.create("dflt"));
		ACell result = engine.jobs().invokeInternal(echoWithDefaults(defaults),
			Maps.of(Strings.create("a"), Strings.create("mine")), ctx)
			.get(5, TimeUnit.SECONDS);
		assertEquals(Strings.create("mine"), RT.getIn(result, "a"));
	}

	@Test
	public void testNullInputBecomesDefaults() throws Exception {
		AMap<AString, ACell> defaults = Maps.of(Strings.create("a"), RT.cvm(1L));
		ACell result = engine.jobs().invokeInternal(echoWithDefaults(defaults), null, ctx)
			.get(5, TimeUnit.SECONDS);
		assertEquals(RT.cvm(1L), RT.getIn(result, "a"));
	}

	@Test
	public void testNonMapInputUntouchedByDefaults() throws Exception {
		// Defaults only make sense for named arguments — scalar inputs pass through
		AMap<AString, ACell> defaults = Maps.of(Strings.create("a"), RT.cvm(1L));
		ACell result = engine.jobs().invokeInternal(echoWithDefaults(defaults),
			Strings.create("scalar"), ctx).get(5, TimeUnit.SECONDS);
		assertEquals(Strings.create("scalar"), result);
	}

	@Test
	public void testDefaultsApplyOnJobPath() {
		// invokeOperation (Job path) behaves identically to invokeInternal,
		// and the job record stores the EFFECTIVE input
		AMap<AString, ACell> defaults = Maps.of(Strings.create("a"), Strings.create("dflt"));
		Job job = engine.jobs().invokeOperation(echoWithDefaults(defaults),
			Maps.of(Strings.create("b"), Strings.create("mine")), ctx);
		ACell result = job.awaitResult(5000);
		assertEquals(Strings.create("dflt"), RT.getIn(result, "a"));
		assertEquals(Strings.create("mine"), RT.getIn(result, "b"));
		ACell recorded = RT.getIn(engine.jobs().getJobData(job.getID(), ctx), Fields.INPUT);
		assertEquals(Strings.create("dflt"), RT.getIn(recorded, "a"),
			"the job record must show what actually ran");
	}

	// ========== Job record: op reference and parent link (#499, #500) ==========

	@Test
	public void testJobRecordOpIsTheInvokedReference() {
		Job job = engine.jobs().invokeOperation("v/test/ops/echo", Maps.empty(), ctx);
		job.awaitResult(5000);
		AMap<AString, ACell> record = engine.jobs().getJobData(job.getID(), ctx);
		assertEquals(Strings.create("v/test/ops/echo"), record.get(Fields.OP),
			"op is the reference that was invoked, not the resolved hash");
		assertNull(record.get(Fields.PARENT), "a top-level job has no parent");
	}

	@Test
	public void testJobRecordOpIsTheHashWhenInvokedByHash() {
		Asset echo = engine.resolveAsset(Strings.create("v/test/ops/echo"), ctx);
		String hash = echo.getID().toHexString();
		Job job = engine.jobs().invokeOperation(hash, Maps.empty(), ctx);
		job.awaitResult(5000);
		AMap<AString, ACell> record = engine.jobs().getJobData(job.getID(), ctx);
		assertEquals(Strings.create(hash), record.get(Fields.OP),
			"an explicit pinned invocation records the hash it pinned");
	}

	@Test
	public void testJobRecordCarriesDispatchedAdapter() {
		Asset echo = engine.resolveAsset(Strings.create("v/test/ops/echo"), ctx);
		Job job = engine.jobs().invokeOperation(echo.getID().toHexString(), Maps.empty(), ctx);
		job.awaitResult(5000);
		AMap<AString, ACell> record = engine.jobs().getJobData(job.getID(), ctx);
		assertEquals(Strings.create("test"), record.get(Fields.ADAPTER),
			"the adapter the venue dispatched to is on the record even when op is a bare hash (#520)");
	}

	// ========== Message delivery to an evicted job (#506) ==========

	@Test
	public void testDeliverMessageToEvictedTerminalJobReportsTerminalState() {
		Job job = engine.jobs().invokeOperation("v/test/ops/echo", Maps.empty(), ctx);
		job.awaitResult(5000);
		engine.jobs().evictActive(job.getID());
		assertNull(engine.jobs().getJobData(job.getID()), "gone from the active cache");
		assertNotNull(engine.jobs().getJobData(job.getID(), ctx), "still readable from the lattice");

		NotSupportedException ex = assertThrows(NotSupportedException.class, () ->
			engine.jobs().deliverMessage(job.getID(),
				Maps.of(Strings.create("content"), Strings.create("more")), ctx));
		assertTrue(ex.getMessage().startsWith(JobManager.JOB_TERMINAL), ex.getMessage());

		assertThrows(NotFoundException.class, () ->
			engine.jobs().deliverMessage(Blob.parse("0x00112233445566778899aabbccddeeff"),
				Maps.empty(), ctx),
			"an id nobody has ever seen is still not found");
	}

	@Test
	public void testControlOfEvictedTerminalJob() {
		Job job = engine.jobs().invokeOperation("v/test/ops/echo", Maps.empty(), ctx);
		job.awaitResult(5000);
		engine.jobs().evictActive(job.getID());

		AMap<AString, ACell> cancelled = engine.jobs().cancelJob(job.getID(), ctx);
		assertNotNull(cancelled, "cancelling a finished job answers its record (COG-8)");
		assertEquals(JobManager.acknowledgement(job.getID()), cancelled);
		assertEquals(Status.COMPLETE, job.getStatus());

		NotSupportedException pause = assertThrows(NotSupportedException.class,
			() -> engine.jobs().pauseJob(job.getID(), ctx));
		assertTrue(pause.getMessage().startsWith(JobManager.JOB_TERMINAL), pause.getMessage());
		NotSupportedException resume = assertThrows(NotSupportedException.class,
			() -> engine.jobs().resumeJob(job.getID(), ctx));
		assertTrue(resume.getMessage().startsWith(JobManager.JOB_TERMINAL), resume.getMessage());
	}

	@Test
	public void testDeliverMessageToSingleTurnJobIsRefused() {
		engine.registerAdapter(new AAdapter() {
			@Override public String getName() { return "single-turn-test"; }
			@Override public String getDescription() { return "test"; }
			@Override public CompletableFuture<ACell> invokeFuture(RequestContext c,
					AMap<AString, ACell> m, ACell in) {
				return new CompletableFuture<>();
			}
		});
		AMap<AString, ACell> meta = Maps.of(Fields.OPERATION,
			Maps.of(Fields.ADAPTER, Strings.create("single-turn-test:run")));
		Job job = engine.jobs().invokeOperation(meta, null, ctx);
		try {
			assertFalse(job.isFinished());
			NotSupportedException ex = assertThrows(NotSupportedException.class, () ->
				engine.jobs().deliverMessage(job.getID(),
					Maps.of(Strings.create("content"), Strings.create("more")), ctx),
				"a message the job's operation cannot take is refused, not dropped");
			assertTrue(ex.getMessage().startsWith(JobManager.JOB_NO_MESSAGES), ex.getMessage());
		} finally {
			job.cancel();
		}
	}

	@Test
	public void testDeliverMessageWithDisabledAdapterIsUnavailable() {
		engine.registerAdapter(new AAdapter() {
			@Override public String getName() { return "disabled-multiturn-test"; }
			@Override public String getDescription() { return "test"; }
			@Override public boolean supportsMultiTurn() { return true; }
			@Override public CompletableFuture<ACell> invokeFuture(RequestContext c,
					AMap<AString, ACell> m, ACell in) {
				return new CompletableFuture<>();
			}
		});
		AMap<AString, ACell> meta = Maps.of(Fields.OPERATION,
			Maps.of(Fields.ADAPTER, Strings.create("disabled-multiturn-test:run")));
		Job job = engine.jobs().invokeOperation(meta, null, ctx);
		engine.disableAdapter("disabled-multiturn-test");
		try {
			UnavailableException ex = assertThrows(UnavailableException.class, () ->
				engine.jobs().deliverMessage(job.getID(),
					Maps.of(Strings.create("content"), Strings.create("more")), ctx),
				"the job takes messages, but its backend is not enabled now");
			assertTrue(ex.getMessage().startsWith(JobManager.ADAPTER_NOT_AVAILABLE), ex.getMessage());
		} finally {
			job.cancel();
		}
	}

	@Test
	public void testJobRecordOpFallsBackToHashForInlineDefinition() {
		AMap<AString, ACell> meta = echoMeta();
		Job job = engine.jobs().invokeOperation(meta, Maps.empty(), ctx);
		job.awaitResult(5000);
		AMap<AString, ACell> record = engine.jobs().getJobData(job.getID(), ctx);
		assertEquals(Strings.create(meta.getHash().toHexString()), record.get(Fields.OP),
			"an inline definition has no reference, so its own hash stands in");
	}

	@Test
	public void testSubJobRecordsRecordedParent() throws Exception {
		Job parent = engine.jobs().invokeOperation("v/test/ops/echo", Maps.empty(), ctx);
		parent.awaitResult(5000);

		Job child = engine.jobs().invokeOperation("v/test/ops/echo", Maps.empty(),
			ctx.withJob(parent));
		child.awaitResult(5000);
		AMap<AString, ACell> record = engine.jobs().getJobData(child.getID(), ctx);
		assertEquals(parent.getID(), Job.parseID(record.get(Fields.PARENT)),
			"a job dispatched inside a recorded job links to it");
	}

	@Test
	public void testSubJobThroughTransientLayerLinksToNearestRecordedAncestor() throws Exception {
		Job root = engine.jobs().invokeOperation("v/test/ops/echo", Maps.empty(), ctx);
		root.awaitResult(5000);

		// A transient wrapper dispatched inside the recorded job...
		TestAdapter.CAPTURED_CTX.remove(did);
		engine.jobs().invokeInternal(captureContextMeta(true, null), Maps.empty(),
			ctx.withJob(root)).get(5, TimeUnit.SECONDS);
		RequestContext transientCtx = TestAdapter.CAPTURED_CTX.get(did);
		assertFalse(transientCtx.getJob().isRecorded());

		// ...forwards the recorded ancestor to a recorded grandchild.
		Job grandchild = engine.jobs().invokeOperation("v/test/ops/echo", Maps.empty(),
			transientCtx);
		grandchild.awaitResult(5000);
		AMap<AString, ACell> record = engine.jobs().getJobData(grandchild.getID(), ctx);
		assertEquals(root.getID(), Job.parseID(record.get(Fields.PARENT)),
			"a transient parent never appears in a record; the link skips to the recorded ancestor");
	}

	@Test
	public void testInheritedJobScopeIsTheParentWhenNoImmediateJob() {
		Job parent = engine.jobs().invokeOperation("v/test/ops/echo", Maps.empty(), ctx);
		parent.awaitResult(5000);

		Job child = engine.jobs().invokeOperation("v/test/ops/echo", Maps.empty(),
			ctx.withJobId(parent.getID()));
		child.awaitResult(5000);
		AMap<AString, ACell> record = engine.jobs().getJobData(child.getID(), ctx);
		assertEquals(parent.getID(), Job.parseID(record.get(Fields.PARENT)));
	}
}
