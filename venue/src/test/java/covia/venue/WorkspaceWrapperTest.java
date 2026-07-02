package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.data.AString;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;

/**
 * The {@code w}/{@code o}/{@code h} namespaces are stored as a
 * {@code {updated, data}} container — a storage shape only (NOT a lattice; the
 * old {@code LWWWrapperLattice} is gone and deletion durability comes from the
 * venue-value whole-value LWW). These tests lock in that the wrapper is
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
	public void testOperationsNamespaceIsAlsoWrapped() {
		write("o/my-op", Maps.of("kind", "custom"));

		assertEquals(Maps.of("kind", "custom"), RT.getIn(read("o/my-op"), "value"));
		ACell stored = storedNamespace("o");
		assertNotNull(RT.getIn(stored, "data"), "o/ is a wrapped namespace too");
	}
}
