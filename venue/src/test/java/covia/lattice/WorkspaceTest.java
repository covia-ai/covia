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
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Cursors;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;

/** A data area's operations, on areas outside a venue. */
public class WorkspaceTest {

	private static final AString AREA = Strings.intern("temp");

	private static AString[] path(String path) {
		return ValuePath.parse(path);
	}

	private static Workspace empty() {
		return new Workspace(Cursors.<ACell>root(null));
	}

	@Test
	public void testWriteCreatesTheRootThenReplaces() {
		Workspace area = empty();
		assertFalse(area.write(path("a"), 0, CVMLong.ONE), "the first write creates the absent root");
		assertEquals(Maps.of("a", CVMLong.ONE), area.read(path(""), 0));
		assertTrue(area.write(path("a"), 0, CVMLong.create(2)));
		assertEquals(CVMLong.create(2), area.read(path("a"), 0));
	}

	@Test
	public void testChangesBelowAMissingLevelAreRefused() {
		Workspace area = new Workspace(Cursors.<ACell>root(Maps.empty()));
		ACell before = area.read(path(""), 0);
		String missing = String.format(ValuePath.NO_LEVEL, "/a");

		assertEquals(missing, assertThrows(NotFoundException.class,
			() -> area.write(path("a/b"), 0, CVMLong.ONE)).getMessage());
		assertEquals(missing, assertThrows(NotFoundException.class,
			() -> area.append(path("a/b"), 0, CVMLong.ONE)).getMessage());
		assertEquals(missing, assertThrows(NotFoundException.class,
			() -> area.update(path("a/b"), 0, value -> CVMLong.ONE)).getMessage());
		assertSame(before, area.read(path(""), 0), "a refused change writes nothing");
	}

	@Test
	public void testCreatePathMakesOnlyTheMissingLevels() {
		Workspace area = empty();
		assertEquals(2, area.createPath(path("a/b"), 0));
		assertEquals(Maps.of("a", Maps.of("b", Maps.empty())), area.read(path(""), 0));
		area.write(path("a/b/c"), 0, CVMLong.ONE);

		assertEquals(1, area.createPath(path("a/x"), 0));
		assertEquals(0, area.createPath(path("a/b"), 0));
		assertEquals(CVMLong.ONE, area.read(path("a/b/c"), 0), "existing levels keep their values");
	}

	@Test
	public void testCreatePathRefusesALevelThatIsNotAContainer() {
		Workspace area = new Workspace(Cursors.<ACell>root(Maps.of("n", CVMLong.ONE,
			"list", Vectors.of(CVMLong.ONE))));
		ACell before = area.read(path(""), 0);

		assertEquals(String.format(ValuePath.NOT_A_MAP, "x", "/n"), assertThrows(InvalidInputException.class,
			() -> area.createPath(path("n/x"), 0)).getMessage());
		assertEquals(String.format(ValuePath.INDEX_OUT_OF_RANGE, "/list", 3L, 1L),
			assertThrows(InvalidInputException.class, () -> area.createPath(path("list/3"), 0)).getMessage());
		assertEquals(String.format(ValuePath.NOT_AN_INDEX, "/list", 1L, "x"),
			assertThrows(InvalidInputException.class, () -> area.createPath(path("list/x"), 0)).getMessage());
		assertSame(before, area.read(path(""), 0));
	}

	@Test
	public void testListsAreAppendedAndIndexed() {
		Workspace area = empty();
		assertEquals(1, area.append(path("list"), 0, CVMLong.ONE));
		assertEquals(2, area.append(path("list"), 0, CVMLong.create(2)));
		assertTrue(area.write(path("list/0"), 0, CVMLong.create(10)));
		assertEquals(Vectors.of(CVMLong.create(10), CVMLong.create(2)), area.read(path("list"), 0));

		assertThrows(InvalidInputException.class, () -> area.write(path("list/2"), 0, CVMLong.ONE));
		area.write(path("n"), 0, CVMLong.ONE);
		assertEquals(String.format(ValuePath.NOT_A_LIST, CVMLong.ONE.getType()),
			assertThrows(InvalidInputException.class, () -> area.append(path("n"), 0, CVMLong.ONE)).getMessage());
	}

	@Test
	public void testAStoredNullIsPresentUntilDeleted() {
		Workspace area = empty();
		area.write(path("a"), 0, null);
		assertTrue(area.contains(path("a"), 0));
		assertNull(area.read(path("a"), 0));

		assertTrue(area.delete(path("a"), 0));
		assertFalse(area.contains(path("a"), 0));
		ACell before = area.read(path(""), 0);
		assertFalse(area.delete(path("a"), 0));
		assertSame(before, area.read(path(""), 0), "deleting an absent value writes nothing");
	}

	@Test
	public void testUpdateAppliesItsChangeAtThePath() {
		Workspace area = new Workspace(Cursors.<ACell>root(Maps.of("count", CVMLong.ONE)));
		assertEquals(CVMLong.create(2), area.update(path("count"), 0,
			value -> CVMLong.create(((CVMLong) value).longValue() + 1)));
		assertEquals(CVMLong.create(2), area.read(path("count"), 0));
	}

	@Test
	public void testSegmentsAreReadByTheValueTheyMeet() {
		Blob id = Blob.fromHex("0a0b");
		Workspace area = new Workspace(Cursors.<ACell>root(Maps.of(
			"list", Vectors.of(CVMLong.ONE, CVMLong.create(2)),
			"index", Index.of(id, Strings.create("found")))));
		assertEquals(CVMLong.create(2), area.read(path("list/1"), 0));
		assertEquals(Strings.create("found"), area.read(path("index/0a0b"), 0));
		assertNull(area.read(path("list/9"), 0));
	}

	@Test
	public void testKeysBeforeFromNameTheAreaInRefusals() {
		Workspace area = empty();
		String missing = String.format(ValuePath.NO_LEVEL, "/w/a");
		assertEquals(missing, assertThrows(NotFoundException.class,
			() -> area.write(path("w/a/b"), 1, CVMLong.ONE)).getMessage());
		area.write(path("w/a"), 1, CVMLong.ONE);
		assertEquals(Maps.of("a", CVMLong.ONE), area.read(path("w"), 1));
	}

	@Test
	public void testAnAreaInARecordNeedsTheRecord() {
		var record = Cursors.<ACell>root(null);
		Workspace area = Workspace.inRecord(record, AREA);
		String missing = String.format(Workspace.NO_RECORD, AREA);

		assertNull(area.read(path("x"), 0));
		assertEquals(missing, assertThrows(InvalidInputException.class,
			() -> area.write(path("x"), 0, CVMLong.ONE)).getMessage());
		assertEquals(missing, assertThrows(InvalidInputException.class,
			() -> area.transact(draft -> draft.write(path("x"), 0, CVMLong.ONE))).getMessage());
		assertNull(record.get(), "a refused change never creates the record");

		record.set(Maps.of("status", "STARTED"));
		area.write(path("x"), 0, CVMLong.ONE);
		assertEquals(Maps.of("status", "STARTED", AREA, Maps.of("x", CVMLong.ONE)), record.get());
	}

	@Test
	public void testOnlyAWorkspaceIsChangedByPath() {
		var cursor = Cursors.<ACell>root(Maps.of("list", Vectors.of(CVMLong.ONE, CVMLong.create(2))));
		ACoviaComponent<ACell> component = new ACoviaComponent<>(cursor) {};
		Position position = component.resolve(path("list/1"), 0);
		assertSame(component, position.component());
		assertEquals(CVMLong.create(2), position.read());
		assertEquals(ACoviaComponent.READ_ONLY,
			assertThrows(AuthException.class, position::workspace).getMessage());

		Workspace area = new Workspace(cursor);
		assertSame(area, area.resolve(path("list/1"), 0).workspace());
	}

	@Test
	public void testOnlyTheRangeOfTheKeysIsUsed() {
		AString[] keys = path("x/a/b/c");   // the area's own segments are a/b; x and c lie outside the range
		Workspace area = new Workspace(Cursors.<ACell>root(Maps.of("a", Maps.empty())));

		assertFalse(area.write(keys, 1, 3, CVMLong.ONE));
		assertEquals(Maps.of("a", Maps.of("b", CVMLong.ONE)), area.read(path(""), 0));
		assertEquals(CVMLong.ONE, area.read(keys, 1, 3));
		assertTrue(area.contains(keys, 1, 3));
		assertFalse(area.contains(keys, 1, 4));
		assertEquals(1, ValuePath.missingLevels(area.read(), keys, 1, 4));
		assertEquals("/a/b", ValuePath.format(keys, 1, 3));

		assertEquals(1, area.createPath(path("x/a/d/e"), 1, 3), "only levels inside the range are created");
		assertEquals(Maps.of("b", CVMLong.ONE, "d", Maps.empty()), area.read(path("a"), 0));

		assertTrue(area.delete(keys, 1, 3));
		assertEquals(Maps.of("d", Maps.empty()), area.read(path("a"), 0));
	}

	@Test
	public void testARefusalInARangeNamesThePathAsWritten() {
		Workspace area = new Workspace(Cursors.<ACell>root(Maps.empty()));
		assertEquals(String.format(ValuePath.NO_LEVEL, "/x/a"), assertThrows(NotFoundException.class,
			() -> area.write(path("x/a/b/c"), 1, 3, CVMLong.ONE)).getMessage());
	}

	@Test
	public void testAPositionCoversItsRange() {
		var cursor = Cursors.<ACell>root(Maps.of("a", Maps.of("b", CVMLong.ONE)));
		ACoviaComponent<ACell> component = new ACoviaComponent<>(cursor) {};
		AString[] keys = path("a/b");

		Position whole = component.resolve(keys, 0);
		assertEquals(keys.length, whole.to());
		assertEquals(CVMLong.ONE, whole.read());

		Position parent = component.resolve(keys, 0, 1);
		assertEquals(Maps.of("b", CVMLong.ONE), parent.read());
		assertTrue(parent.contains());
		assertThrows(IndexOutOfBoundsException.class, () -> new Position(component, keys, 1, 3));
	}

	@Test
	public void testATransactionCreatesLevelsAndWritesTogether() {
		var root = Cursors.<ACell>root(Maps.empty());
		Workspace area = new Workspace(root);
		ACell before = root.get();

		assertThrows(IllegalStateException.class, () -> area.transact(draft -> {
			draft.createPath(path("a/b"), 0);
			draft.write(path("a/b/c"), 0, CVMLong.ONE);
			throw new IllegalStateException("rollback");
		}));
		assertSame(before, root.get(), "a failed transaction changes nothing");

		area.transact(draft -> {
			assertEquals(2, draft.createPath(path("a/b"), 0));
			draft.write(path("a/b/c"), 0, CVMLong.ONE);
		});
		assertEquals(CVMLong.ONE, area.read(path("a/b/c"), 0));
	}
}
