package covia.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import covia.exception.InvalidInputException;

/** A Covia path: how one is made, its stretches, and the address it names. */
public class CoviaPathTest {

	private static AString s(String value) {
		return Strings.create(value);
	}

	@Test
	public void testParseKeepsEachSegmentAsWritten() {
		CoviaPath path = CoviaPath.parse("/g/my-agent/timeline/3/");
		assertEquals(4, path.length());
		assertEquals(s("g"), path.first());
		assertEquals(s("my-agent"), path.get(1));
		// Every segment is a string; the component it reaches decides what it means
		assertEquals(s("3"), path.get(3));
		assertEquals("/g/my-agent/timeline/3", path.toString());

		for (String none : new String[] {null, "", "/"}) {
			assertSame(CoviaPath.EMPTY, CoviaPath.parse(none), String.valueOf(none));
		}
		assertEquals("/", CoviaPath.EMPTY.toString());
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
		CoviaPath path = CoviaPath.segments(pieces);
		pieces[1] = s("changed");
		assertEquals("/w/notes", path.toString(), "the path keeps its own segments");

		assertEquals(String.format(CoviaPath.SEGMENT_HAS_SLASH, 0),
			assertThrows(InvalidInputException.class, () -> CoviaPath.segments(s("a/b"))).getMessage());
		assertEquals(String.format(CoviaPath.SEGMENT_NOT_STRING, 1, "null"),
			assertThrows(InvalidInputException.class, () -> CoviaPath.segments(s("w"), null)).getMessage());
	}

	@Test
	public void testAStretchNamesThePlaceAtItsEnd() {
		CoviaPath path = CoviaPath.parse("w/a/b/c");
		CoviaPath rest = path.rest();
		assertEquals(3, rest.length());
		assertEquals(s("a"), rest.first());
		assertEquals("/w/a/b/c", rest.toString(), "a stretch formats as the whole address");

		CoviaPath prefix = rest.prefix(2);
		assertEquals(2, prefix.length());
		assertEquals("/w/a/b", prefix.toString());
		assertEquals("/w/a", prefix.parent().toString());
		assertTrue(rest.rest(3).isEmpty());
		assertEquals("/w/a/b/c", rest.rest(3).toString());

		CoviaPath none = path.rest(4);
		assertSame(none, none.parent(), "an empty stretch is its own parent");
		assertThrows(IndexOutOfBoundsException.class, () -> path.rest(5));
		assertThrows(IndexOutOfBoundsException.class, () -> rest.prefix(4));
		assertThrows(IndexOutOfBoundsException.class, () -> rest.get(3));
		assertThrows(IndexOutOfBoundsException.class, () -> none.first());
	}

	@Test
	public void testAppendingStretchesMakesAWholePath() {
		CoviaPath alias = CoviaPath.parse("v/ops/echo");
		CoviaPath joined = CoviaPath.parse("w/global").append(alias.rest());
		assertEquals(CoviaPath.parse("w/global/ops/echo"), joined);
		assertEquals("/w/global/ops/echo", joined.toString(), "the joined path is whole, not a stretch of either");
		assertEquals("/w/global", joined.prefix(2).toString());

		assertEquals(alias, alias.append(CoviaPath.EMPTY));
		assertEquals("/ops/echo", CoviaPath.EMPTY.append(alias.rest()).toString());
	}

	@Test
	public void testPathsAreEqualWhenTheyNameTheSamePlaceTheSameWay() {
		CoviaPath path = CoviaPath.of(Vectors.of("g", "my-agent", "", "timeline", "3"));
		assertEquals(path, CoviaPath.parse(path.toString()));
		assertEquals(path.hashCode(), CoviaPath.parse(path.toString()).hashCode());

		CoviaPath other = CoviaPath.parse("x/w/a");
		assertEquals(CoviaPath.parse("w/a").rest(), CoviaPath.parse("w/a/b").rest().prefix(1));
		assertNotEquals(CoviaPath.parse("w/a"), CoviaPath.parse("w/a").rest(), "a stretch starting later differs");
		assertNotEquals(CoviaPath.parse("w/a"), other.rest(), "the same segments below another place differ");
	}
}
