package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.Blob;
import convex.core.data.Strings;
import covia.adapter.CoviaAdapter;
import covia.exception.WrongScopeException;

/**
 * #175 — path navigation must not convert abnormal errors into phantom absence.
 *
 * <p>Only a {@link WrongScopeException} (a context-dependent namespace such as
 * {@code n/} used outside its agent, session or job) is a genuine absence for a
 * read. <b>Every other</b> exception during navigation — a broken request
 * context, a lower-level store fault, an auth failure, a malformed path —
 * propagates rather than being masked as "path not found".</p>
 */
public class PathResolutionErrorTest {

	private static final Engine engine = TestEngine.ENGINE;

	private RequestContext ctx;

	@BeforeEach
	public void setup(TestInfo info) {
		ctx = RequestContext.of(TestEngine.uniqueDID(info));
	}

	@Test
	public void testAbnormalNavigationPropagates() {
		// A task without its agent is a broken context, not a path that holds nothing.
		RequestContext broken = ctx.withTaskId(Blob.fromHex("0102030405060708"));
		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> engine.resolvePath(Strings.create("t/x"), broken));
		assertEquals(CoviaAdapter.TASK_WITHOUT_AGENT, failure.getMessage());
	}

	@Test
	public void testWrongScopeResolvesToAbsence() {
		assertNull(engine.resolvePath(Strings.create("c/x"), ctx), "c/ without a session reads as absence");
		assertNull(engine.resolvePath(Strings.create("t/x"), ctx), "t/ without a job reads as absence");
	}

	@Test
	public void testRealAgentNamespaceOutsideScopeIsAbsence() {
		assertNull(engine.resolvePath(Strings.create("n/foo"), ctx),
			"n/ without agent scope reads as absence");
	}

	@Test
	public void testGenuineAbsenceStillNull() {
		assertNull(engine.resolvePath(Strings.create("w/nothingHere"), ctx),
			"an unwritten workspace path is a genuine absence");
	}
}
