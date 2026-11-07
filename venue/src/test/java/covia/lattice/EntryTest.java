package covia.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Path;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Cursors;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;

/** An entry's changes, at keys fixed when its path was resolved, in areas outside a venue. */
public class EntryTest {

	private static final CVMLong ONE = CVMLong.ONE;
	private static final CVMLong TWO = CVMLong.create(2);

	private static Path path(String path) {
		return CoviaPath.parse(path);
	}

	private static Workspace area(ACell value) {
		return new Workspace(Cursors.<ACell>root(value));
	}

	@Test
	public void testChangesLandInTheWorkspace() {
		Workspace area = area(Maps.of("a", Maps.empty()));
		Entry entry = area.entry(path("a/b"));

		assertFalse(entry.exists());
		assertFalse(entry.write(ONE), "the first write creates the entry");
		assertEquals(Maps.of("a", Maps.of("b", ONE)), area.read());
		assertTrue(entry.write(TWO));
		assertEquals(CVMLong.create(3), entry.update(value -> CVMLong.create(((CVMLong) value).longValue() + 1)));
		assertEquals(CVMLong.create(3), area.read(path("a/b")));

		assertTrue(entry.delete());
		assertEquals(Maps.of("a", Maps.empty()), area.read());
		assertFalse(entry.delete(), "deleting an absent entry writes nothing");
	}

	@Test
	public void testAStoredNullIsPresent() {
		Workspace area = area(Maps.empty());
		Entry entry = area.entry(path("a"));

		assertFalse(entry.write(null));
		assertTrue(entry.exists());
		assertTrue(area.resolve(path("a")).exists());
		assertNull(entry.read());
		assertTrue(entry.write(null), "a stored null counts as present");

		assertTrue(entry.delete());
		assertFalse(entry.exists());
		assertEquals(Maps.empty(), area.read());
	}

	@Test
	public void testAListElementIsDeletedInPlace() {
		Workspace area = area(Maps.of("list", Vectors.of(1, 2, 3)));
		assertTrue(area.entry(path("list/1")).delete());
		assertEquals(Vectors.of(ONE, null, CVMLong.create(3)), area.read(path("list")), "later positions keep theirs");
		assertEquals(4, area.entry(path("list")).append(TWO));
	}

	@Test
	public void testALevelRemovedAfterResolutionIsRefused() {
		Workspace area = area(Maps.of("a", Maps.of("b", ONE)));
		Entry entry = area.entry(path("a/b"));
		area.delete(path("a"));

		String missing = String.format(ValuePath.NO_LEVEL, "/a");
		assertEquals(missing, assertThrows(NotFoundException.class, () -> entry.write(TWO)).getMessage());
		assertEquals(missing, assertThrows(NotFoundException.class, () -> entry.append(TWO)).getMessage());
		assertEquals(Maps.empty(), area.read(), "a refused change writes nothing");
	}

	@Test
	public void testAListThatShrankAfterResolutionIsKept() {
		Workspace area = area(Maps.of("list", Vectors.of(1, 2, 3)));
		Entry entry = area.entry(path("list/2"));
		area.write(path("list"), Vectors.of(1));

		assertEquals(String.format(ValuePath.INDEX_OUT_OF_RANGE, "/list", 2, 1),
			assertThrows(InvalidInputException.class, () -> entry.write(TWO)).getMessage());
		assertEquals(Maps.of("list", Vectors.of(1)), area.read());
	}

	@Test
	public void testALevelThatChangedKindIsRefused() {
		// Resolved as a list position: the key is not written into the map now there
		Workspace area = area(Maps.of("x", Vectors.of(1, 2)));
		Entry entry = area.entry(path("x/1"));
		area.write(path("x"), Maps.empty());

		assertEquals(String.format(Entry.CHANGED, "/x"),
			assertThrows(InvalidInputException.class, () -> entry.write(TWO)).getMessage());
		assertEquals(Maps.of("x", Maps.empty()), area.read());
	}

	@Test
	public void testASegmentItsLevelCannotHoldIsRefused() {
		ACell before = Maps.of("list", Vectors.of(1), "text", "hello");
		Workspace area = area(before);
		Entry inList = area.entry(path("list/x"));

		assertNull(inList.read());
		assertFalse(inList.exists());
		assertFalse(inList.delete());
		assertEquals(String.format(ValuePath.NOT_AN_INDEX, "/list", 1, "x"),
			assertThrows(InvalidInputException.class, () -> inList.write(ONE)).getMessage());
		assertEquals(String.format(ValuePath.NOT_A_MAP, "b", "/text"),
			assertThrows(InvalidInputException.class, () -> area.entry(path("text/b")).write(ONE)).getMessage());
		assertEquals(before, area.read());
	}

	@Test
	public void testLevelsAreCreatedOnlyWhenAsked() {
		Workspace area = area(null);
		Entry entry = area.entry(path("a/b/c"));

		assertEquals(3, entry.missingLevels());
		assertEquals(String.format(ValuePath.NO_LEVEL, "/a"),
			assertThrows(NotFoundException.class, () -> entry.write(ONE)).getMessage());
		assertNull(area.read());

		entry.execute(draft -> {
			assertEquals(2, draft.createLevels());
			draft.write(ONE);
		});
		assertEquals(Maps.of("a", Maps.of("b", Maps.of("c", ONE))), area.read());
		assertEquals(0, entry.missingLevels());
		assertEquals(0, entry.createLevels(), "existing levels are kept");
	}

	@Test
	public void testAFailedTransactionWritesNothing() {
		Workspace area = area(Maps.empty());
		Entry entry = area.entry(path("a/b"));
		assertThrows(IllegalStateException.class, () -> entry.execute(draft -> {
			draft.createLevels();
			draft.write(ONE);
			throw new IllegalStateException("rollback");
		}));
		assertEquals(Maps.empty(), area.read());
	}

	@Test
	public void testAnAbsentRootCountsAsEmpty() {
		Workspace area = area(null);
		assertFalse(area.entry(path("a")).write(ONE));
		assertEquals(Maps.of("a", ONE), area.read());
	}

	@Test
	public void testAnIndexEntryKeepsItsBlobKey() {
		Blob id = Blob.fromHex("0a0b");
		Workspace area = area(Maps.of("jobs", Index.of(id, ONE)));
		Entry entry = area.entry(path("jobs/" + id.toHexString()));

		assertEquals(ONE, entry.read());
		assertTrue(entry.write(TWO));
		assertEquals(Index.of(id, TWO), area.read(path("jobs")));
	}

	@Test
	public void testTheRootEntryIsTheWholeArea() {
		Workspace area = area(null);
		Entry root = area.entry(CoviaPath.EMPTY);

		assertFalse(root.exists());
		assertFalse(root.write(Maps.of("a", ONE)));
		assertTrue(root.exists());
		assertEquals(Maps.of("a", ONE), area.read());
		assertTrue(root.delete());
		assertNull(area.read());
	}
}
