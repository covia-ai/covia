package covia.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Path;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import covia.exception.InvalidInputException;

/** Covia's path syntax: how a path is made from what a caller writes, and the address a path names. */
public class CoviaPathTest {

	private static AString s(String value) {
		return Strings.create(value);
	}

	@Test
	public void testParseKeepsEachSegmentAsWritten() {
		Path path = CoviaPath.parse("/g/my-agent/timeline/3/");
		assertEquals(4, path.count());
		assertEquals(s("g"), path.get(0));
		assertEquals(s("my-agent"), path.get(1));
		// Every segment is a string; the component it reaches decides what it means
		assertEquals(s("3"), path.get(3));
		assertEquals("/g/my-agent/timeline/3", CoviaPath.address(path));

		for (String none : new String[] {null, "", "/"}) {
			assertSame(CoviaPath.EMPTY, CoviaPath.parse(none), String.valueOf(none));
		}
		assertEquals("/", CoviaPath.address(CoviaPath.EMPTY));
	}

	@Test
	public void testACallersArrayIsSegmentsWithNoSlash() {
		assertEquals(CoviaPath.parse("w/a//b"), CoviaPath.of(Vectors.of("w", "a", "", "b")));
		assertEquals(CoviaPath.parse("w/notes"), CoviaPath.of(s("w/notes")));
		assertSame(CoviaPath.EMPTY, CoviaPath.of(CVMLong.ONE));
		assertSame(CoviaPath.EMPTY, CoviaPath.of(null));

		assertEquals(String.format(CoviaPath.SEGMENT_NOT_STRING, 2, CVMLong.ONE.getType()),
			assertThrows(InvalidInputException.class,
				() -> CoviaPath.of(Vectors.of("w", "items", CVMLong.ONE))).getMessage());
		assertEquals(String.format(CoviaPath.SEGMENT_HAS_SLASH, 1),
			assertThrows(InvalidInputException.class,
				() -> CoviaPath.of(Vectors.of("w", "items/0"))).getMessage());
		assertEquals(String.format(CoviaPath.SEGMENT_NOT_STRING, 0, Maps.empty().getType()),
			assertThrows(InvalidInputException.class,
				() -> CoviaPath.of(Vectors.of(Maps.empty()))).getMessage());
	}

	@Test
	public void testSegmentsAreCheckedAndCopied() {
		AString[] pieces = {s("w"), s("notes")};
		Path path = CoviaPath.segments(pieces);
		pieces[1] = s("changed");
		assertEquals("/w/notes", CoviaPath.address(path), "the path keeps its own segments");

		assertEquals(String.format(CoviaPath.SEGMENT_HAS_SLASH, 0),
			assertThrows(InvalidInputException.class, () -> CoviaPath.segments(s("a/b"))).getMessage());
		assertEquals(String.format(CoviaPath.SEGMENT_NOT_STRING, 1, "null"),
			assertThrows(InvalidInputException.class, () -> CoviaPath.segments(s("w"), null)).getMessage());
	}

	@Test
	public void testASliceNamesThePlaceAtItsEnd() {
		Path path = CoviaPath.parse("w/a/b/c");
		Path rest = path.slice(1);
		assertEquals("/w/a/b/c", CoviaPath.address(rest), "a slice formats as the address up to its end");

		Path prefix = rest.slice(0, 2);
		assertEquals("/w/a/b", CoviaPath.address(prefix));
		assertEquals("/w/a", CoviaPath.address(CoviaPath.parent(prefix)));
		assertTrue(rest.slice(3).isEmpty());
		assertEquals("/w/a/b/c", CoviaPath.address(rest.slice(3)), "an empty slice still names the place at its end");

		Path none = path.slice(4);
		assertSame(none, CoviaPath.parent(none), "an empty path is its own parent");
	}

	@Test
	public void testAJoinedPathIsAWholeAddress() {
		Path alias = CoviaPath.parse("v/ops/echo");
		Path joined = CoviaPath.parse("w/global").concat(alias.slice(1));
		assertEquals(CoviaPath.parse("w/global/ops/echo"), joined);
		assertEquals("/w/global/ops/echo", CoviaPath.address(joined), "the joined path is whole, not a slice of either");
		assertEquals("/w/global", CoviaPath.address(joined.slice(0, 2)));
		assertEquals("/ops/echo", CoviaPath.address(CoviaPath.EMPTY.concat(alias.slice(1))));
	}

	@Test
	public void testAnAddressParsesBackToItsPath() {
		Path path = CoviaPath.of(Vectors.of("g", "my-agent", "", "timeline", "3"));
		assertEquals(path, CoviaPath.parse(CoviaPath.address(path)));
	}
}
