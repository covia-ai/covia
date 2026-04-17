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
 * infrastructure and is exercised by {@code GridAdapterTest}.</p>
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
	 * A capability vector that doesn't cover the requested resource must
	 * deny before the adapter runs — same enforcement path as
	 * invokeOperation.
	 */
	@Test
	public void testInvokeInternalCapsDeny() {
		AVector<ACell> caps = Vectors.of(Maps.of(
			Strings.create("with"), Strings.create("w/allowed"),
			Strings.create("can"),  Strings.create("crud/read")));
		RequestContext capCtx = ctx.withCaps(caps);

		CompletableFuture<ACell> f = engine.jobs().invokeInternal(
			"v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/forbidden/x")),
			capCtx);

		ExecutionException ex = assertThrows(ExecutionException.class,
			() -> f.get(5, TimeUnit.SECONDS));
		assertInstanceOf(RuntimeException.class, ex.getCause());
		assertTrue(ex.getCause().getMessage().startsWith("Capability denied:"),
			"Expected capability-denied message, got: " + ex.getCause().getMessage());
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
	public void testInvokeInternalInternalContextAllowed() throws Exception {
		// INTERNAL context has no caller DID but internal=true → allowed
		CompletableFuture<ACell> f = engine.jobs().invokeInternal(
			"v/test/ops/echo",
			Maps.of(Strings.create("ping"), Strings.create("pong")),
			RequestContext.INTERNAL);
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
}
