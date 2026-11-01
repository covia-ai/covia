package covia.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.venue.RequestContext;
import covia.venue.VenueContext;

/** Data operations, their checks and their edits, on components outside a venue. */
public class DataComponentTest {

	private static final RequestContext CALLER = RequestContext.of(Strings.create("did:key:caller"));
	private static final VenueContext CLOCK = VenueContext.create(() -> CVMLong.create(100));

	private static ACell[] path(String path) {
		return WorkspaceRoot.parseStringPath(path);
	}

	/** Caller data in a user's workspace, addressed as the caller names it. */
	private static DataComponent data(VenueContext context, AForkableCursor<ACell> user, String path) {
		return new WorkspaceRoot(user, context, true).data(Strings.create(path), path(path), false);
	}

	@Test
	public void testCreateAccessIsAskedAtTheNearestExistingLevel() {
		var user = Cursors.<ACell>root(Maps.empty());
		List<AString> asked = new ArrayList<>();
		var context = CLOCK.withAccess((ctx, resource, ability) -> {
			assertEquals(Capability.CRUD_WRITE, ability);
			asked.add(resource);
		});

		data(context, user, "w/x").write(CALLER, Maps.empty());
		AMap<AString, ACell> created = data(context, user, "w/x/y/z").write(CALLER, CVMLong.ONE);
		assertEquals(List.of(Strings.create("w"), Strings.create("w/x")), asked);
		assertEquals(CVMBool.FALSE, created.get(Fields.EXISTED));
		assertEquals(CVMBool.TRUE, created.get(Fields.PATH_CREATED));

		AMap<AString, ACell> replaced = data(context, user, "w/x/y/z").write(CALLER, CVMLong.create(2));
		assertEquals(2, asked.size(), "replacing a value creates nothing, so asks nothing");
		assertEquals(CVMBool.TRUE, replaced.get(Fields.EXISTED));
		assertNull(replaced.get(Fields.PATH_CREATED));
		assertEquals(CVMLong.create(2), data(context, user, "w/x/y/z").read());
	}

	@Test
	public void testRefusedCreateAccessChangesNothing() {
		var user = Cursors.<ACell>root(Maps.empty());
		var context = CLOCK.withAccess((ctx, resource, ability) -> {
			throw new AuthException("refused");
		});
		ACell before = user.get();
		assertThrows(AuthException.class, () -> data(context, user, "w/a").write(CALLER, CVMLong.ONE));
		assertEquals(before, user.get());
	}

	@Test
	public void testUninstalledAccessRefusesCreation() {
		var user = Cursors.<ACell>root(Maps.empty());
		var e = assertThrows(AuthException.class,
			() -> data(VenueContext.SYSTEM, user, "w/a").write(CALLER, CVMLong.ONE));
		assertEquals(VenueContext.NO_ACCESS_POLICY, e.getMessage());
	}

	@Test
	public void testDeletingAnAbsentValueWritesNothing() {
		var user = Cursors.<ACell>root(Maps.empty());
		ACell before = user.get();
		AMap<AString, ACell> result = data(CLOCK, user, "w/none").delete();
		assertEquals(CVMBool.FALSE, result.get(Fields.DELETED));
		assertSame(before, user.get(), "no namespace envelope is created");
	}

	@Test
	public void testDeleteRemovesAPresentValue() {
		var user = Cursors.<ACell>root(Maps.empty());
		var context = CLOCK.withAccess((ctx, resource, ability) -> {});
		data(context, user, "w/a").write(CALLER, CVMLong.ONE);
		assertEquals(CVMBool.TRUE, data(context, user, "w/a").delete().get(Fields.DELETED));
		assertFalse(data(context, user, "w/a").contains());
	}

	@Test
	public void testAppendCreatesThenExtendsAVector() {
		var user = Cursors.<ACell>root(Maps.empty());
		var context = CLOCK.withAccess((ctx, resource, ability) -> {});
		AMap<AString, ACell> first = data(context, user, "w/list").append(CALLER, CVMLong.ONE);
		assertEquals(CVMBool.FALSE, first.get(Fields.EXISTED));
		assertEquals(CVMLong.create(0), first.get(Fields.INDEX));
		assertEquals(CVMLong.create(1), first.get(Fields.NEW_SIZE));
		AMap<AString, ACell> second = data(context, user, "w/list").append(CALLER, CVMLong.create(2));
		assertEquals(CVMBool.TRUE, second.get(Fields.EXISTED));
		assertEquals(CVMLong.create(1), second.get(Fields.INDEX));
		assertEquals(Vectors.of(CVMLong.ONE, CVMLong.create(2)), data(context, user, "w/list").read());
	}

	@Test
	public void testScopedDataNeedsItsRecord() {
		var record = Cursors.<ACell>root(null);
		var context = CLOCK.withAccess((ctx, resource, ability) -> {});
		var scoped = new WorkspaceRoot(record, context, false);
		String missing = String.format(WorkspaceRoot.NO_PARENT_RECORD, "n");

		var write = assertThrows(InvalidInputException.class,
			() -> scoped.data(Strings.create("n/x"), path("n/x"), true).write(CALLER, CVMLong.ONE));
		assertEquals(missing, write.getMessage());
		var delete = assertThrows(InvalidInputException.class,
			() -> scoped.data(Strings.create("n/x"), path("n/x"), true).delete());
		assertEquals(missing, delete.getMessage());
		assertNull(record.get(), "a refused scoped edit never creates the record");

		record.set(Maps.of("status", "SLEEPING"));
		scoped.data(Strings.create("n/x"), path("n/x"), true).write(CALLER, CVMLong.ONE);
		assertEquals(CVMLong.ONE, scoped.data(Strings.create("n/x"), path("n/x"), true).read());
		assertEquals(Strings.create("SLEEPING"), scoped.read(path("status")));
	}

	@Test
	public void testUnclaimedPositionsAreReadOnlyData() {
		var cursor = Cursors.<ACell>root(Maps.of("list", Vectors.of(CVMLong.ONE, CVMLong.create(2))));
		ACoviaComponent<ACell> component = new ACoviaComponent<>(cursor) {};
		ACell[] keys = path("list/1");

		assertSame(component, component.resolve(keys, keys.length));
		ACoviaComponent<?> data = component.resolve(keys, 0);
		assertEquals(CVMLong.create(2), data.read());
		var e = assertThrows(AuthException.class,
			() -> ((DataComponent) data).write(CALLER, CVMLong.ONE));
		assertEquals(DataComponent.READ_ONLY, e.getMessage());
	}

	@Test
	public void testResolvingBelowDataExtendsItsAddress() {
		var user = Cursors.<ACell>root(Maps.empty());
		List<AString> asked = new ArrayList<>();
		var context = CLOCK.withAccess((ctx, resource, ability) -> asked.add(resource));
		data(context, user, "w/a").write(CALLER, Maps.empty());
		asked.clear();

		var below = (DataComponent) data(context, user, "w/a").resolve(path("b/c"), 0);
		below.write(CALLER, CVMLong.ONE);
		assertEquals(List.of(Strings.create("w/a")), asked);
		assertEquals(CVMLong.ONE, data(context, user, "w/a/b/c").read());
	}
}
