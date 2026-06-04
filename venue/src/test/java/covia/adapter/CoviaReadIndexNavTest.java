package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;

/**
 * Regression for {@code covia_read} navigating into a Blob-keyed {@link Index}
 * by a hex path segment — the shape of {@code g/<agent>/sessions/<sid>/wakeTime}
 * (the sessions Index lives inside the agent's opaque LWW value, so the lattice
 * resolver never sees it; {@code deepGet}/{@code navigateInto} does the walk).
 *
 * <p>An {@code AString} key is tried as a blob-like key first (AString is itself
 * {@code ABlobLike}, covering a string-keyed index) and then parsed as hex into a
 * {@link Blob} (covering the common Blob-keyed case), with or without a {@code 0x}
 * prefix.</p>
 */
public class CoviaReadIndexNavTest {

	private static AString s(String x) { return Strings.create(x); }

	/** {sessions: Index{ <16-byte Blob> : {wakeTime: 1790000000000} }} */
	private static ACell fixture(Blob sid) {
		AMap<AString, ACell> sessionRec = Maps.of(s("wakeTime"), CVMLong.create(1790000000000L));
		Index<Blob, ACell> sessions = Index.of(sid, sessionRec);
		return Maps.of(s("sessions"), sessions);
	}

	@Test
	public void testHexSegmentResolvesBlobKeyedIndex() {
		Blob sid = Blob.fromHex("0000019e921a468d0000000000000000");
		ACell root = fixture(sid);
		String hex = sid.toHexString();   // bare hex, no 0x

		// sessions/<hex>/wakeTime
		ACell wt = CoviaAdapter.deepGet(root, new ACell[]{ s("sessions"), s(hex), s("wakeTime") }, 0);
		assertEquals(CVMLong.create(1790000000000L), wt, "hex segment must resolve the Blob key");

		// sessions/<hex> → whole session record
		ACell rec = CoviaAdapter.deepGet(root, new ACell[]{ s("sessions"), s(hex) }, 0);
		assertEquals(s("wakeTime"), ((AMap<?, ?>) rec).getKeys().get(0), "whole record resolves too");
	}

	@Test
	public void testZeroXPrefixAlsoResolves() {
		Blob sid = Blob.fromHex("0000019e921a468d0000000000000000");
		ACell root = fixture(sid);
		// keys render outward as "0x…" via ALattice.toJSONKey — accept that form
		ACell wt = CoviaAdapter.deepGet(root,
			new ACell[]{ s("sessions"), s("0x" + sid.toHexString()), s("wakeTime") }, 0);
		assertEquals(CVMLong.create(1790000000000L), wt, "0x-prefixed hex must resolve too");
	}

	@Test
	public void testUnknownKeyMisses() {
		Blob sid = Blob.fromHex("0000019e921a468d0000000000000000");
		ACell root = fixture(sid);
		ACell miss = CoviaAdapter.deepGet(root,
			new ACell[]{ s("sessions"), s("ffffffffffffffffffffffffffffffff"), s("wakeTime") }, 0);
		assertNull(miss, "a hex key not present in the index resolves to null, not a throw");
	}
}
