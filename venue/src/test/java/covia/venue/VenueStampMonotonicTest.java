package covia.venue;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.LatticeContext;

/**
 * The venue value's {@code :timestamp} is the whole-value-LWW <b>merge key</b>:
 * two DISTINCT venue values carrying the SAME stamp are unorderable, the LWW
 * tie-break is positional ("own wins"), and different merge sites then pick
 * different winners — committed writes get merged away and the state
 * flip-flops (covia#214: a crash-resume repair was written, then discarded).
 *
 * <p>This locks in the stamp policy that prevents the tie from ever existing:
 * every distinct write strictly increases the stamp, even when the write clock
 * has not advanced (same-millisecond writes, the clock-refresh throttle, a
 * long-lived cursor's older context). Deterministic: the write clock is
 * frozen explicitly, so without the strict ratchet the two stamps are equal
 * and this test fails.</p>
 */
public class VenueStampMonotonicTest {

	private static final Keyword K_TIMESTAMP = convex.lattice.generic.LWWLattice.KEY_TIMESTAMP;
	private static final convex.core.data.AString ALICE =
		Strings.create("did:key:z6MkStampMonotonicAlice");

	private static long venueStamp(Engine engine) {
		ACell v = engine.getVenueState().get();
		ACell ts = ((convex.core.data.Index<Keyword, ACell>) v).get(K_TIMESTAMP);
		return (ts instanceof CVMLong l) ? l.longValue() : -1;
	}

	@Test
	public void testDistinctWritesNeverShareTheLwwStamp() {
		Engine engine = Engine.createTemp(Maps.of(
			Config.DID, Strings.create("did:key:zStampMonotonicVenue")));
		try {
			// Freeze the write clock: a fixed context time BELOW the boot stamp,
			// the worst case (long-lived cursor with an old clock; also covers
			// same-millisecond writes under the refresh throttle).
			engine.getVenueState().cursor().withContext(
				LatticeContext.create(CVMLong.create(1_000_000), AKeyPair.generate()));

			User user = engine.getVenueState().users().ensure(ALICE);
			user.persistJob(Blob.fromHex("ee112233445566778899aabbccddee01"),
				(AMap<convex.core.data.AString, ACell>) (AMap<?, ?>) Maps.of(
					Strings.intern("n"), CVMLong.create(1)));
			long t1 = venueStamp(engine);

			// Fresh wrapper derivation, same frozen clock — as real traffic
			// derives per access within one throttle window.
			User again = engine.getVenueState().users().get(ALICE);
			again.persistJob(Blob.fromHex("ee112233445566778899aabbccddee02"),
				(AMap<convex.core.data.AString, ACell>) (AMap<?, ?>) Maps.of(
					Strings.intern("n"), CVMLong.create(2)));
			long t2 = venueStamp(engine);

			assertTrue(t1 > 0, "venue value carries an LWW stamp");
			assertTrue(t2 > t1,
				"distinct writes must strictly increase the LWW merge key even under a"
				+ " frozen write clock — equal stamps make the whole-value LWW merge"
				+ " positional and non-convergent (#214): t1=" + t1 + " t2=" + t2);
		} finally {
			engine.close();
		}
	}
}
