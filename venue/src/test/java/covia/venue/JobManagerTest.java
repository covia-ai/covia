package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;

/**
 * Unit tests for {@link JobManager#invokeInternal}. Covers the zero-Job
 * internal dispatch path used by adapters (transitions, LLM sub-invocations,
 * tool calls) per issue #85.
 *
 * <p>Scenarios: happy path (ref + meta overloads), zero-Job invariant,
 * null/unresolvable/non-op refs, capability denial, strict-mode schema
 * validation, and missing authentication.</p>
 *
 * <p>Remote-venue fallback (where {@code invokeInternal} delegates to the
 * Job-creating path) is not covered here — it requires cross-venue
 * infrastructure. Real two-venue exercises live in
	 * {@code covia.venue.grid.CrossVenueTest}; the loopback dispatch path
	 * inside one venue is covered by {@code GridAdapterTest}.</p>
 */
public class JobManagerTest {

	private final Engine engine = TestEngine.ENGINE;
	private AString did;
	private RequestContext ctx;

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

	// ========== Zero-Job invariant ==========

	/**
	 * Core contract of invokeInternal: no Job is created. Guards against
	 * regressions that accidentally route internal calls through the
	 * Job-creating {@code invokeOperation} path.
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
			"invokeInternal must not create a Job — the future is the only handle");
	}

	// ========== Bad refs ==========

	@Test
	public void testInvokeInternalNullRef() {
		CompletableFuture<ACell> f = engine.jobs().invokeInternal(
			(String) null, Maps.empty(), ctx);
		assertTrue(f.isCompletedExceptionally());
		ExecutionException ex = assertThrows(ExecutionException.class, f::get);
		assertInstanceOf(IllegalArgumentException.class, ex.getCause());
	}

	@Test
	public void testInvokeInternalUnresolvableRef() {
		CompletableFuture<ACell> f = engine.jobs().invokeInternal(
			"v/does/not/exist", Maps.empty(), ctx);
		ExecutionException ex = assertThrows(ExecutionException.class,
			() -> f.get(5, TimeUnit.SECONDS));
		assertInstanceOf(IllegalArgumentException.class, ex.getCause());
		assertTrue(ex.getCause().getMessage().contains("Cannot resolve")
			|| ex.getCause().getMessage().contains("not an operation"));
	}

	// ========== Capability enforcement ==========

	/**
	 * invokeInternal enforces the capability ceiling carried by the context —
	 * it differs from invokeOperation only in Job creation, never in trust.
	 * A read outside the ceiling is denied; a read within it proceeds; the
	 * ceiling is read, never stripped. "Framework-trusted" is expressed by an
	 * unrestricted (null-caps) context, not by choosing this dispatch path.
	 */
	@Test
	public void testInvokeInternalEnforcesContextCeiling() throws Exception {
		AVector<ACell> caps = Vectors.of(Maps.of(
			Strings.create("with"), Strings.create("w/allowed"),
			Strings.create("can"),  Strings.create("crud/read")));
		RequestContext capCtx = ctx.withCaps(caps);

		// Outside the ceiling — denied on the internal path too (no bypass).
		CompletableFuture<ACell> denied = engine.jobs().invokeInternal(
			"v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/forbidden/x")),
			capCtx);
		ExecutionException ex = assertThrows(ExecutionException.class,
			() -> denied.get(5, TimeUnit.SECONDS));
		assertTrue(ex.getCause().getMessage().contains("Capability denied"),
			"invokeInternal must enforce the context ceiling");

		// Within the ceiling — proceeds; absent path reads {exists: false}.
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
		assertTrue(String.valueOf(job.getErrorMessage()).contains("Capability denied"),
			"Expected capability-denied message, got: " + job.getErrorMessage());
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
		assertInstanceOf(IllegalArgumentException.class, ex.getCause());
		assertTrue(ex.getCause().getMessage().contains("schema"),
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
		Engine eng = Engine.createTemp(null);
		eng.jobs().validateOutput(metaWithObjectOutput(), Strings.create("not an object"));
	}

	@Test
	public void testOutputValidationStrictFailsBadResult() {
		Engine eng = Engine.createTemp(Maps.of(Config.OUTPUT_VALIDATION, Strings.create("strict")));
		assertThrows(IllegalArgumentException.class,
			() -> eng.jobs().validateOutput(metaWithObjectOutput(), Strings.create("not an object")));
		// A conforming result (an object) passes.
		eng.jobs().validateOutput(metaWithObjectOutput(), Maps.empty());
	}

	@Test
	public void testOutputValidationWarnDoesNotFail() {
		// warn logs a mismatch but completes — must not raise.
		Engine eng = Engine.createTemp(Maps.of(Config.OUTPUT_VALIDATION, Strings.create("warn")));
		eng.jobs().validateOutput(metaWithObjectOutput(), Strings.create("not an object"));
	}

	@Test
	public void testOutputValidationNoSchemaIsNoop() {
		// An operation with no output schema is never validated, even in strict.
		Engine eng = Engine.createTemp(Maps.of(Config.OUTPUT_VALIDATION, Strings.create("strict")));
		AMap<AString, ACell> meta = Maps.of(Fields.OPERATION,
			Maps.of(Fields.ADAPTER, Strings.create("test:out")));
		eng.jobs().validateOutput(meta, Strings.create("anything"));
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

		User user = engine.getVenueState().users().get(did);
		assertNotNull(user.getJob(job.getID()), "completed job record should be persisted");

		assertTrue(engine.jobs().deleteJob(job.getID(), ctx));
		assertNull(user.getJob(job.getID()), "durable record should be removed");
		assertNull(engine.jobs().getJobData(job.getID(), ctx),
			"deleted job must not reappear via the lattice fallback");
		assertFalse(engine.jobs().deleteJob(job.getID(), ctx),
			"second delete finds nothing");
	}
}
