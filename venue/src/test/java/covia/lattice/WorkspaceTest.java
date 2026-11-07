package covia.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Path;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.ReadOnlyCursorException;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;

/** A data area's operations, on areas outside a venue. */
public class WorkspaceTest {

	private static final AString AREA = Strings.intern("temp");

	private static Path path(String path) {
		return CoviaPath.parse(path);
	}

	private static Workspace empty() {
		return new Workspace(Cursors.<ACell>root(null));
	}

	@Test
	public void testWriteCreatesTheRootThenReplaces() {
		Workspace area = empty();
		assertFalse(area.write(path("a"), CVMLong.ONE), "the first write creates the absent root");
		assertEquals(Maps.of("a", CVMLong.ONE), area.read(CoviaPath.EMPTY));
		assertTrue(area.write(path("a"), CVMLong.create(2)));
		assertEquals(CVMLong.create(2), area.read(path("a")));
	}

	@Test
	public void testChangesBelowAMissingLevelAreRefused() {
		Workspace area = new Workspace(Cursors.<ACell>root(Maps.empty()));
		ACell before = area.read();
		String missing = String.format(ValuePath.NO_LEVEL, "/a");

		assertEquals(missing, assertThrows(NotFoundException.class,
			() -> area.write(path("a/b"), CVMLong.ONE)).getMessage());
		assertEquals(missing, assertThrows(NotFoundException.class,
			() -> area.append(path("a/b"), CVMLong.ONE)).getMessage());
		assertEquals(missing, assertThrows(NotFoundException.class,
			() -> area.update(path("a/b"), value -> CVMLong.ONE)).getMessage());
		assertSame(before, area.read(), "a refused change writes nothing");
	}

	@Test
	public void testCreatePathMakesOnlyTheMissingLevels() {
		Workspace area = empty();
		assertEquals(2, area.createPath(path("a/b")));
		assertEquals(Maps.of("a", Maps.of("b", Maps.empty())), area.read());
		area.write(path("a/b/c"), CVMLong.ONE);

		assertEquals(1, area.createPath(path("a/x")));
		assertEquals(0, area.createPath(path("a/b")));
		assertEquals(CVMLong.ONE, area.read(path("a/b/c")), "existing levels keep their values");
	}

	@Test
	public void testCreatePathRefusesALevelThatIsNotAContainer() {
		Workspace area = new Workspace(Cursors.<ACell>root(Maps.of("n", CVMLong.ONE,
			"list", Vectors.of(CVMLong.ONE))));
		ACell before = area.read();

		assertEquals(String.format(ValuePath.NOT_A_MAP, "x", "/n"), assertThrows(InvalidInputException.class,
			() -> area.createPath(path("n/x"))).getMessage());
		assertEquals(String.format(ValuePath.INDEX_OUT_OF_RANGE, "/list", 3L, 1L),
			assertThrows(InvalidInputException.class, () -> area.createPath(path("list/3"))).getMessage());
		assertEquals(String.format(ValuePath.NOT_AN_INDEX, "/list", 1L, "x"),
			assertThrows(InvalidInputException.class, () -> area.createPath(path("list/x"))).getMessage());
		assertSame(before, area.read());
	}

	@Test
	public void testListsAreAppendedAndIndexed() {
		Workspace area = empty();
		assertEquals(1, area.append(path("list"), CVMLong.ONE));
		assertEquals(2, area.append(path("list"), CVMLong.create(2)));
		assertTrue(area.write(path("list/0"), CVMLong.create(10)));
		assertEquals(Vectors.of(CVMLong.create(10), CVMLong.create(2)), area.read(path("list")));

		assertThrows(InvalidInputException.class, () -> area.write(path("list/2"), CVMLong.ONE));
		area.write(path("n"), CVMLong.ONE);
		assertEquals(String.format(ValuePath.NOT_A_LIST, CVMLong.ONE.getType()),
			assertThrows(InvalidInputException.class, () -> area.append(path("n"), CVMLong.ONE)).getMessage());
	}

	@Test
	public void testAStoredNullIsPresentUntilDeleted() {
		Workspace area = empty();
		area.write(path("a"), null);
		assertTrue(area.contains(path("a")));
		assertNull(area.read(path("a")));

		assertTrue(area.delete(path("a")));
		assertFalse(area.contains(path("a")));
		ACell before = area.read();
		assertFalse(area.delete(path("a")));
		assertSame(before, area.read(), "deleting an absent value writes nothing");
	}

	@Test
	public void testUpdateAppliesItsChangeAtThePath() {
		Workspace area = new Workspace(Cursors.<ACell>root(Maps.of("count", CVMLong.ONE)));
		assertEquals(CVMLong.create(2), area.update(path("count"),
			value -> CVMLong.create(((CVMLong) value).longValue() + 1)));
		assertEquals(CVMLong.create(2), area.read(path("count")));
	}

	@Test
	public void testSegmentsAreReadByTheValueTheyMeet() {
		Blob id = Blob.fromHex("0a0b");
		Workspace area = new Workspace(Cursors.<ACell>root(Maps.of(
			"list", Vectors.of(CVMLong.ONE, CVMLong.create(2)),
			"index", Index.of(id, Strings.create("found")))));
		assertEquals(CVMLong.create(2), area.read(path("list/1")));
		assertEquals(Strings.create("found"), area.read(path("index/0a0b")));
		assertNull(area.read(path("list/9")));
	}

	@Test
	public void testTheSegmentsAboveTheAreaNameItInRefusals() {
		Workspace area = empty();
		String missing = String.format(ValuePath.NO_LEVEL, "/w/a");
		assertEquals(missing, assertThrows(NotFoundException.class,
			() -> area.write(path("w/a/b").slice(1), CVMLong.ONE)).getMessage());
		area.write(path("w/a").slice(1), CVMLong.ONE);
		assertEquals(Maps.of("a", CVMLong.ONE), area.read(path("w").slice(1)));
	}

	@Test
	public void testAnAreaInARecordNeedsTheRecord() {
		var record = Cursors.<ACell>root(null);
		Workspace area = Workspace.inRecord(record, AREA);
		String missing = String.format(Workspace.NO_RECORD, AREA);

		assertNull(area.read(path("x")));
		assertEquals(missing, assertThrows(InvalidInputException.class,
			() -> area.write(path("x"), CVMLong.ONE)).getMessage());
		assertEquals(missing, assertThrows(InvalidInputException.class,
			() -> area.execute(draft -> draft.write(path("x"), CVMLong.ONE))).getMessage());
		assertNull(record.get(), "a refused change never creates the record");

		record.set(Maps.of("status", "STARTED"));
		area.write(path("x"), CVMLong.ONE);
		assertEquals(Maps.of("status", "STARTED", AREA, Maps.of("x", CVMLong.ONE)), record.get());
	}

	@Test
	public void testOnlyAWorkspaceIsChangedByPath() {
		var cursor = Cursors.<ACell>root(Maps.of("list", Vectors.of(CVMLong.ONE, CVMLong.create(2))));
		ACoviaComponent<ACell> component = new ACoviaComponent<>(cursor) {};
		ALatticeComponent<?> data = component.resolve(path("list/1"));
		assertEquals(CVMLong.create(2), data.cursor().get());
		assertEquals(ACoviaComponent.READ_ONLY,
			assertThrows(AuthException.class, () -> Entry.of(data)).getMessage());
		assertThrows(ReadOnlyCursorException.class, () -> data.cursor().set(CVMLong.ONE),
			"data below a component other than a workspace takes no change");

		Workspace area = new Workspace(cursor);
		assertTrue(Entry.of(area.resolve(path("list/1"))).write(CVMLong.create(3)));
		assertEquals(CVMLong.create(3), area.read(path("list/1")));
	}

	@Test
	public void testOnlyTheStretchOfThePathIsUsed() {
		// The area's own segments are a/b; x and c lie outside the stretch
		Path whole = path("x/a/b/c");
		Path own = whole.slice(1).slice(0, 2);
		Workspace area = new Workspace(Cursors.<ACell>root(Maps.of("a", Maps.empty())));

		assertFalse(area.write(own, CVMLong.ONE));
		assertEquals(Maps.of("a", Maps.of("b", CVMLong.ONE)), area.read());
		assertEquals(CVMLong.ONE, area.read(own));
		assertTrue(area.contains(own));
		assertFalse(area.contains(whole.slice(1)));
		assertEquals(1, area.entry(whole.slice(1)).missingLevels());

		assertEquals(1, area.createPath(path("x/a/d/e").slice(1).slice(0, 2)), "only levels inside the stretch are created");
		assertEquals(Maps.of("b", CVMLong.ONE, "d", Maps.empty()), area.read(path("a")));

		assertTrue(area.delete(own));
		assertEquals(Maps.of("d", Maps.empty()), area.read(path("a")));
	}

	@Test
	public void testARefusalInAStretchNamesThePathAsWritten() {
		Workspace area = new Workspace(Cursors.<ACell>root(Maps.empty()));
		assertEquals(String.format(ValuePath.NO_LEVEL, "/x/a"), assertThrows(NotFoundException.class,
			() -> area.write(path("x/a/b/c").slice(1).slice(0, 2), CVMLong.ONE)).getMessage());
	}

	@Test
	public void testDataBelowAComponentIsAddressedByCoviasRulesAtAnyDepth() {
		Blob id = Blob.fromHex("0a0b");
		var cursor = Cursors.<ACell>root(Maps.of("a", Maps.of("b", CVMLong.ONE),
			"list", Vectors.of(Strings.create("first")), "ids", Index.of(id, CVMLong.create(2))));
		ACoviaComponent<ACell> component = new ACoviaComponent<>(cursor) {};

		assertEquals(CVMLong.ONE, component.resolve(path("a/b")).cursor().get());
		assertEquals(CVMLong.ONE, component.resolve(path("a")).resolve(path("b")).cursor().get(),
			"resolving in steps reaches the same value");
		assertEquals(Strings.create("first"), component.resolve(path("list")).resolve(path("0")).cursor().get(),
			"a list takes a position");
		assertEquals(CVMLong.create(2), component.resolve(path("ids")).resolve(path(id.toHexString())).cursor().get(),
			"an Index takes its Blob key in hex");
		assertTrue(component.resolve(path("a")).exists());
		assertFalse(component.resolve(path("a/z")).exists());
		assertSame(component, component.resolve(CoviaPath.EMPTY), "an empty path is the component itself");
	}

	@Test
	public void testATransactionCreatesLevelsAndWritesTogether() {
		var root = Cursors.<ACell>root(Maps.empty());
		Workspace area = new Workspace(root);
		ACell before = root.get();

		assertThrows(IllegalStateException.class, () -> area.execute(draft -> {
			draft.createPath(path("a/b"));
			draft.write(path("a/b/c"), CVMLong.ONE);
			throw new IllegalStateException("rollback");
		}));
		assertSame(before, root.get(), "a failed transaction changes nothing");

		area.execute(draft -> {
			assertEquals(2, draft.createPath(path("a/b")));
			draft.write(path("a/b/c"), CVMLong.ONE);
		});
		assertEquals(CVMLong.ONE, area.read(path("a/b/c")));
	}
}
