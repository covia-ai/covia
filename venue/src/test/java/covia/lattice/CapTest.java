package covia.lattice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;

/**
 * One capability: what it names, when it is well formed, what it covers and
 * how its caveats combine.
 */
public class CapTest {

	private static AString s(String v) {
		return Strings.create(v);
	}

	private static Cap cap(String with, String can) {
		return Cap.of(Cap.create(s(with), s(can)));
	}

	@Test
	public void fieldsReadAsStored() {
		Cap cap = Cap.of(Cap.create(s("w/a"), s("crud/read"),
			Maps.of(Cap.GATE, s("o/gate"), Cap.URL, s("https://x.example/"))));
		assertEquals(s("w/a"), cap.with());
		assertEquals(s("crud/read"), cap.can());
		assertEquals(s("o/gate"), cap.gate());
		assertEquals(s("https://x.example/"), cap.url());
		assertTrue(cap.conditional());
		assertFalse(cap("w/a", "crud").conditional());
		assertTrue(Cap.of(Cap.create(s("w/a"), s("crud"), Maps.empty())).nb().isEmpty());
	}

	@Test
	public void malformedCapsSayWhy() {
		assertNull(cap("w/a", "crud").invalid());
		assertNull(Cap.of(Maps.of(Capability.CAN, s("invoke"))).invalid(), "with may be left out");
		assertEquals(Cap.NOT_AN_OBJECT, Cap.of(null).invalid());
		assertEquals(Cap.WITH_NOT_STRING, Cap.of(Maps.of(Capability.WITH, CVMLong.ONE, Capability.CAN, s("crud"))).invalid());
		assertEquals(Cap.NO_ABILITY, Cap.of(Maps.of(Capability.WITH, s("w/a"))).invalid());
		assertEquals(Cap.NO_ABILITY, cap("w/a", "").invalid());
		assertEquals(Cap.NB_NOT_AN_OBJECT, Cap.of(Cap.create(s("w/a"), s("crud")).assoc(Capability.NB, s("x"))).invalid());
		String unknown = Cap.of(Cap.create(s("w/a"), s("crud"), Maps.of(s("maxSize"), s("10")))).invalid();
		assertTrue(unknown.startsWith(Cap.UNKNOWN_CAVEAT), unknown);
		String empty = Cap.of(Cap.create(s("w/a"), s("crud"), Maps.of(Cap.GATE, s("")))).invalid();
		assertTrue(empty.startsWith(Cap.CAVEAT_NOT_STRING), empty);
	}

	@Test
	public void coversOnWholeSegments() {
		Cap cap = cap("w/notes", "crud");
		assertTrue(cap.covers(s("w/notes"), s("crud/read")));
		assertTrue(cap.covers(s("w/notes/x"), s("crud")));
		assertFalse(cap.covers(s("w/notesx"), s("crud/read")), "a sibling is not below it");
		assertFalse(cap.covers(s("w/notes"), s("crudx")));
		assertFalse(cap.covers(s("w/other"), s("crud")));
	}

	@Test
	public void anEmptyWithCoversAnyResource() {
		assertTrue(cap("", "asset/read").covers(s("did:key:z1/a/x"), s("asset/read")));
		assertTrue(Cap.of(Maps.of(Capability.CAN, s("invoke"))).covers(s("v/ops/x"), s("invoke")));
		assertFalse(cap("", "asset/read").covers(s("a/x"), s("crud/write")));
	}

	@Test
	public void urlCaveatCoversItsPrefix() {
		Cap cap = Cap.of(Cap.create(s("s/KEY"), s("secret/use"), Maps.of(Cap.URL, s("https://api.example/v1"))));
		assertNull(cap.urlDenial(s("https://api.example/v1/chat")));
		assertNotNull(cap.urlDenial(s("https://api.example/v2")));
		assertNotNull(cap.urlDenial(null), "a use with no destination never satisfies a url caveat");
		assertNull(cap("s/KEY", "secret/use").urlDenial(null), "no caveat, nothing to satisfy");
	}

	@Test
	public void caveatsCombineUnlessTheyConflict() {
		AMap<AString, ACell> gate = Maps.of(Cap.GATE, s("o/g"));
		AMap<AString, ACell> url = Maps.of(Cap.URL, s("https://x.example/"));
		assertEquals(Maps.of(Cap.GATE, s("o/g"), Cap.URL, s("https://x.example/")), Cap.combinedCaveats(gate, url));
		assertEquals(gate, Cap.combinedCaveats(gate, gate));
		assertNull(Cap.combinedCaveats(gate, Maps.of(Cap.GATE, s("o/other"))));
	}

	@Test
	public void theNarrowerAbilityCoversWhatBothDo() {
		assertEquals(s("crud/read"), Cap.narrowerAbility(s("crud"), s("crud/read")));
		assertEquals(s("crud/read"), Cap.narrowerAbility(s("crud/read"), s("*")));
		assertNull(Cap.narrowerAbility(s("crud/read"), s("crud/write")));
		assertNull(Cap.narrowerAbility(null, s("crud")));
	}
}
