package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.CoviaAdapter;
import covia.api.Fields;
import covia.exception.WrongScopeException;
import covia.grid.Job;
import covia.lattice.ACoviaComponent;

/**
 * #175 — path navigation must not convert abnormal errors into phantom absence.
 *
 * <p>Only a {@link WrongScopeException} (a context-rooted namespace such as
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

	@Test
	public void testAnInvalidPathIsNotAReferenceButIsAnErrorToRead() {
		assertNull(engine.resolvePath(Strings.create("wrok/notes"), ctx), "a reference to nothing resolves to nothing");
		assertNull(engine.resolvePath(Strings.create("meta"), ctx));

		Job read = engine.jobs().invokeOperation("v/ops/covia/read", Maps.of(Fields.PATH, "wrok/notes"), ctx);
		assertThrows(Exception.class, () -> read.awaitResult(5000));
		assertEquals(String.format(ACoviaComponent.NO_SUCH_PATH, "/wrok/notes", "/wrok"), read.getErrorMessage());

		ACell missing = engine.jobs().invokeOperation("v/ops/covia/read", Maps.of(Fields.PATH, "w/nothingHere"), ctx)
			.awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(missing, "exists"), "a missing value is no error");

		ACell hash = engine.jobs().invokeOperation("v/ops/covia/read", Maps.of(Fields.PATH, "ab".repeat(32)), ctx)
			.awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(hash, "exists"), "a hash that names no asset is no error");
	}

	@Test
	public void testAChangeToANameAUserHasNotIsRefused() {
		AString own = ctx.getUserDID();
		Job write = engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, own + "/n/notes", Fields.VALUE, CVMLong.ONE), ctx);
		assertThrows(Exception.class, () -> write.awaitResult(5000));
		assertEquals(String.format(ACoviaComponent.NO_SUCH_PATH, "/" + own + "/n/notes", "/" + own + "/n"), write.getErrorMessage());
		assertNull(RT.getIn(engine.getVenueState().users().get(own).get(), "n"), "no stray field is written");
	}

	@Test
	public void testAPathWithoutAUserIsAbsence() {
		assertNull(engine.resolvePathUnchecked(Strings.create("w/notes"), RequestContext.ANONYMOUS));
		assertNull(engine.resolvePathUnchecked(Strings.create(""), ctx), "an empty path names no namespace");
	}
}
