package covia.lattice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Cursors;
import covia.exception.InvalidInputException;

/**
 * A set of capabilities: the storage convention, parsing what arrives from
 * outside, coverage, and changes through the component.
 */
public class CapsTest {

	private static AString s(String v) {
		return Strings.create(v);
	}

	private static final AVector<ACell> READ_NOTES = Vectors.of(Cap.create(s("w/notes"), s("crud/read")));

	@Test
	public void noValueIsUnrestrictedAndEmptyIsNone() {
		Caps unrestricted = Caps.of(null);
		assertTrue(unrestricted.isUnrestricted());
		assertNull(unrestricted.scope());
		assertTrue(unrestricted.covers(s("w/anything"), s("crud/write")));

		Caps none = Caps.of(Vectors.empty());
		assertFalse(none.isUnrestricted());
		assertEquals(0, none.count());
		assertFalse(none.covers(s("w/anything"), s("crud/read")));
	}

	@Test
	public void aMissingEntryIsUnrestricted() {
		Caps caps = new Caps(Cursors.create(Maps.empty()).path(s("caps")));
		assertTrue(caps.isUnrestricted());
	}

	@Test
	public void aStoredValueThatIsNoListGrantsNothing() {
		Caps caps = new Caps(Cursors.create(Maps.of(s("caps"), s("unrestricted"))).path(s("caps")));
		assertFalse(caps.isUnrestricted(), "only a missing value is unrestricted in the store");
		assertEquals(Vectors.empty(), caps.scope());
	}

	@Test
	public void parseTakesUnrestrictedOrWellFormedCaps() {
		assertNull(Caps.parse(Caps.UNRESTRICTED));
		assertEquals(READ_NOTES, Caps.parse(READ_NOTES));
		assertEquals(Vectors.empty(), Caps.parse(Vectors.empty()));

		InvalidInputException notList = assertThrows(InvalidInputException.class, () -> Caps.parse(s("everything")));
		assertEquals(Caps.NOT_A_LIST, notList.getMessage());
		assertThrows(InvalidInputException.class, () -> Caps.parse(null));

		AVector<ACell> malformed = READ_NOTES.conj(Maps.of(s("with"), s("w/x")));
		InvalidInputException entry = assertThrows(InvalidInputException.class, () -> Caps.parse(malformed));
		assertEquals(Caps.MALFORMED_ENTRY.formatted(1, Cap.NO_ABILITY), entry.getMessage());
	}

	@Test
	public void coversWithUnconditionalCapsOnly() {
		AVector<ACell> scope = Vectors.of(
			Cap.create(s("w/notes"), s("crud/read")),
			Cap.create(s("s/KEY"), s("secret/use"), Maps.of(Cap.URL, s("https://api.example/"))),
			Maps.of(s("with"), CVMLong.ONE, s("can"), s("crud")));
		Caps caps = Caps.of(scope);
		assertTrue(caps.covers(s("w/notes/a"), s("crud/read")));
		assertFalse(caps.covers(s("w/notes/a"), s("crud/write")));
		assertFalse(caps.covers(s("s/KEY"), s("secret/use")), "a caveated cap covers only through its caveat");
		assertEquals(1, caps.conditionallyCovering(s("s/KEY"), s("secret/use")).size());
		assertEquals(0, caps.conditionallyCovering(s("w/notes"), s("crud/read")).size());
		assertEquals(3, caps.count());
		assertEquals(s("s/KEY"), caps.cap(1).with());
		assertFalse(caps.cap(2).valid(), "a malformed entry grants nothing");
	}

	@Test
	public void changesThroughTheComponent() {
		Caps caps = new Caps(Cursors.create(Maps.empty()).path(s("caps")));
		caps.set(READ_NOTES);
		assertEquals(READ_NOTES, caps.scope());
		caps.clear();
		assertEquals(Vectors.empty(), caps.scope());
		caps.set(null);
		assertTrue(caps.isUnrestricted());

		Caps inTransaction = Caps.of(READ_NOTES);
		inTransaction.execute(draft -> draft.clear());
		assertEquals(0, inTransaction.count());
	}
}
