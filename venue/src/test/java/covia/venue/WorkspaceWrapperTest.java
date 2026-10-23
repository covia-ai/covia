package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import covia.api.Fields;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.lattice.WorkspaceRoot;

/**
 * The {@code w}/{@code o}/{@code h} namespaces are stored as a
 * {@code {updated, data}} container presented through a {@link Covia#WORKSPACE_RECORD}
 * view boundary. Deletion durability comes from the venue-value whole-value
 * LWW; the wrapper owns navigation and write-time metadata only. These tests
 * lock in that the wrapper is
 * <b>transparent</b>: callers address keys inside {@code data}, never see the
 * wrapper fields, and can use keys named {@code data}/{@code updated} without
 * colliding with the container. {@code updated} is maintained as per-namespace
 * last-modified metadata.
 */
public class WorkspaceWrapperTest {

	private final Engine engine = TestEngine.ENGINE;

	private AString DID;
	private RequestContext CTX;

	@BeforeEach
	public void setup(TestInfo info) {
		DID = TestEngine.uniqueDID(info);
		CTX = RequestContext.of(DID);
	}

	private void write(String path, ACell value) {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, path, Fields.VALUE, value), CTX).awaitResult(5000);
	}

	private void delete(String path) {
		engine.jobs().invokeOperation("v/ops/covia/delete",
			Maps.of(Fields.PATH, path), CTX).awaitResult(5000);
	}

	private ACell read(String path) {
		return engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, path), CTX).awaitResult(5000);
	}

	/** Raw stored value of a namespace, for inspecting the storage shape. */
	private ACell storedNamespace(String ns) {
		ACell userValue = engine.getVenueState().users().ensure(DID).cursor().get();
		return RT.getIn(userValue, ns);
	}

	@Test
	public void testVenueCatalogStored() {
		// materialiseVOps ran during TestEngine init; the covia:write catalog
		// entry must be physically stored under the venue user's w/data/global/...
		AString venueDID = engine.getDIDString();
		ACell venueUser = engine.getVenueState().users().ensure(venueDID).cursor().get();
		ACell entry = RT.getIn(venueUser, "w", "data", "global", "ops", "covia", "write");
		assertNotNull(entry, "catalog entry must be stored at w/data/global/ops/covia/write");
	}

	@Test
	public void testVenueOpsCatalogResolvable() {
		assertNotNull(
			engine.resolveAsset(convex.core.data.Strings.create("v/ops/covia/write"), engine.venueContext()),
			"v/ops/covia/write must resolve after materialisation");
	}

	@Test
	public void testWrapperInvisibleToReaders() {
		write("w/visible", CVMLong.create(1));

		// Reading the namespace yields the data, not the {updated, data} wrapper.
		ACell data = RT.getIn(read("w"), "value");
		assertEquals(CVMLong.create(1), RT.getIn(data, "visible"));
		assertNull(RT.getIn(data, "data"), "wrapper 'data' field must not leak to readers");
		assertNull(RT.getIn(data, "updated"), "wrapper 'updated' field must not leak to readers");

		// ...but the stored value IS the wrapper (storage shape, not API shape).
		ACell stored = storedNamespace("w");
		assertNotNull(RT.getIn(stored, "data"), "stored namespace is the {updated, data} wrapper");
		assertTrue(RT.getIn(stored, "updated") instanceof CVMLong, "wrapper carries an 'updated' timestamp");
	}

	@Test
	public void testUserKeysNamedLikeWrapperFieldsDoNotCollide() {
		write("w/data/inner", CVMLong.create(2));
		write("w/updated", CVMLong.create(7));

		// User keys named exactly like the wrapper fields live inside data.
		assertEquals(CVMLong.create(2), RT.getIn(read("w/data/inner"), "value"));
		assertEquals(CVMLong.create(7), RT.getIn(read("w/updated"), "value"));

		// Deleting one leaves the other intact (no collision with the container).
		delete("w/data/inner");
		assertEquals(CVMBool.FALSE, RT.getIn(read("w/data/inner"), "exists"));
		assertEquals(CVMLong.create(7), RT.getIn(read("w/updated"), "value"));
	}

	@Test
	public void testUpdatedMetadataAdvancesOnWrite() {
		write("w/a", CVMLong.create(1));
		long t1 = ((CVMLong) RT.getIn(storedNamespace("w"), "updated")).longValue();

		// A later write re-stamps the namespace's updated metadata (never regresses).
		write("w/b", CVMLong.create(2));
		long t2 = ((CVMLong) RT.getIn(storedNamespace("w"), "updated")).longValue();
		assertTrue(t2 >= t1, "updated metadata must not go backwards across writes");
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testWrapperPreservesMetadataAndRatchetsContextClock() {
		var userRoot = Cursors.<ACell>root(convex.core.data.Maps.empty());
		var policy = new java.util.concurrent.atomic.AtomicLong(200);
		LatticeContext context = new LatticeContext() {
			@Override public CVMLong currentTimestamp() { return CVMLong.create(policy.get()); }
		};
		AForkableCursor<ACell> data = new WorkspaceRoot(userRoot, context, true).child(Namespace.W);
		AForkableCursor<ACell> container = userRoot.path(Namespace.W);
		data.set(Maps.of("a", CVMLong.ONE));

		// Metadata alongside data belongs to the physical envelope and must
		// survive a caller-visible data update.
		AString marker = Strings.intern("marker");
		container.updateAndGet(raw -> ((AMap<ACell, ACell>) raw)
			.assoc(marker, Strings.create("keep")));

		// A fixed/overridden context may be older than persisted state. The
		// metadata stamp follows the same one-way rule as the venue stamp.
		policy.set(100);
		data.updateAndGet(current -> ((AMap<AString, ACell>) current)
			.assoc(Strings.intern("b"), CVMLong.create(2)));

		ACell stored = container.get();
		assertEquals(CVMLong.create(200), RT.getIn(stored, Fields.UPDATED));
		assertEquals(Strings.create("keep"), RT.getIn(stored, marker));
		assertEquals(CVMLong.create(2), RT.getIn(stored,
			Covia.WORKSPACE_DATA, Strings.intern("b")));
	}

	@Test
	public void testCoviaChildReturnsTheCallerVisibleWrappedValue() {
		User user = engine.getVenueState().users().ensure(DID);
		AForkableCursor<ACell> workspace = user.workspaceRoot().child(Namespace.W);
		workspace.withDefault(Maps.empty()).path(Strings.intern("through-child")).set(CVMLong.create(9));

		assertEquals(CVMLong.create(9), workspace.path(Strings.intern("through-child")).get());
		assertEquals(CVMLong.create(9), RT.getIn(user.cursor().get(),
			"w", Covia.WORKSPACE_DATA, "through-child"));
		assertTrue(RT.getIn(user.cursor().get(), "w", Fields.UPDATED) instanceof CVMLong,
			"the public child accessor must cross the wrapper's stamping boundary");
	}

	@Test
	public void testWrapperBoundaryAppliesOnlyAtNamespaceRoot() {
		var userRoot = Cursors.<ACell>root(convex.core.data.Maps.empty());
		var data = new WorkspaceRoot(userRoot, LatticeContext.create(CVMLong.create(300), null), true).child(Namespace.W);
		AForkableCursor<ACell> container = userRoot.path(Namespace.W);

		AForkableCursor<ACell> nested = data.withDefault(convex.core.data.Maps.empty()).path(Strings.intern("nested"))
			.withDefault(convex.core.data.Maps.empty()).path(Strings.intern("value"));
		nested.set(CVMLong.ONE);

		assertEquals(CVMLong.ONE,
			RT.getIn(container.get(), Covia.WORKSPACE_DATA, "nested", "value"));
		assertEquals(CVMLong.create(300),
			RT.getIn(container.get(), Fields.UPDATED));
	}

	@Test
	public void testOperationsNamespaceIsAlsoWrapped() {
		write("o/my-op", Maps.of("kind", "custom"));

		assertEquals(Maps.of("kind", "custom"), RT.getIn(read("o/my-op"), "value"));
		ACell stored = storedNamespace("o");
		assertNotNull(RT.getIn(stored, "data"), "o/ is a wrapped namespace too");
	}
}
