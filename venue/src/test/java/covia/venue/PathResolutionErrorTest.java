package covia.venue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.data.ACell;
import convex.core.data.Strings;
import covia.adapter.CoviaAdapter;
import covia.exception.WrongScopeException;
import covia.lattice.NamespaceResolver;

/**
 * #175 — path navigation must not convert abnormal errors into phantom absence.
 *
 * <p>Only a {@link WrongScopeException} (a scope-bound prefix such as {@code n/}
 * used outside its agent/session scope) is a genuine absence for a read.
 * <b>Every other</b> exception during navigation — an abnormal resolver bug, a
 * lower-level store fault, an auth failure, a malformed path — propagates rather
 * than being masked as "path not found". Uses its own temp Engine so the
 * throwing resolver stubs don't pollute the shared {@link TestEngine}.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
public class PathResolutionErrorTest {

	private Engine engine;
	private RequestContext ctx;

	@BeforeAll
	public void setup() {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
		ctx = RequestContext.of(Strings.create("did:key:z6Mk-test-PathResolutionError"));

		CoviaAdapter covia = (CoviaAdapter) engine.getAdapter("covia");
		// A resolver whose navigation throws an ORDINARY internal error (the class
		// of bug #175 was masking as absence).
		covia.registerResolver("zz", new NamespaceResolver() {
			@Override public NamespaceResolver.ResolvedNamespace resolve(RequestContext c, CoviaAdapter a, ACell[] keys) {
				throw new RuntimeException("simulated internal navigation failure");
			}
			@Override public boolean isWritable() { return false; }
		});
		// A resolver that reports a wrong-scope condition — a genuine absence for reads.
		covia.registerResolver("wz", new NamespaceResolver() {
			@Override public NamespaceResolver.ResolvedNamespace resolve(RequestContext c, CoviaAdapter a, ACell[] keys) {
				throw new WrongScopeException("simulated wrong scope");
			}
			@Override public boolean isWritable() { return false; }
		});
	}

	@AfterAll
	public void teardown() {
		engine.close();
	}

	@Test
	public void testAbnormalNavigationPropagates() {
		// The #175 bug: a resolver threw an internal error and it was swallowed to
		// null (phantom absence). It must now propagate.
		RuntimeException ex = assertThrows(RuntimeException.class,
			() -> engine.resolvePath(Strings.create("zz/x"), ctx));
		assertFalse(ex instanceof WrongScopeException,
			"an internal navigation failure must not be treated as a wrong-scope absence");
		assertTrue(String.valueOf(ex.getMessage()).contains("simulated"),
			"the real cause surfaces, got: " + ex.getMessage());
	}

	@Test
	public void testWrongScopeResolvesToAbsence() {
		// The one benign case: a scope-bound prefix outside its scope reads as a
		// genuine absence — resolvePath returns null, does not throw.
		assertNull(engine.resolvePath(Strings.create("wz/x"), ctx),
			"a wrong-scope prefix reads as absence");
	}

	@Test
	public void testRealAgentNamespaceOutsideScopeIsAbsence() {
		// The real n/ resolver with no agent scope on ctx → WrongScopeException →
		// absence (null), not an error.
		assertNull(engine.resolvePath(Strings.create("n/foo"), ctx),
			"n/ without agent scope reads as absence");
	}

	@Test
	public void testGenuineAbsenceStillNull() {
		assertNull(engine.resolvePath(Strings.create("w/nothingHere"), ctx),
			"an unwritten workspace path is a genuine absence");
	}
}
