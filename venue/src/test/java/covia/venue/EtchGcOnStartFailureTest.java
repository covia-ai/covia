package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Ref;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.etch.EtchStore;
import covia.venue.server.VenueServer;

/**
 * A startup collection that fails must boot the venue on the untouched
 * original store (covia#451). The product logs that failure at ERROR — right
 * for an operator, noise in a passing build — so this runs alone with the
 * logger silenced (see {@link TestLogs}).
 */
@Isolated
public class EtchGcOnStartFailureTest {

	@Test
	public void failedSweepBootsOnTheOriginalStore() throws Exception {
		// A root whose children were never written: the sweep meets missing
		// data, so the cycle must be cancelled and the original store kept.
		File f = new File(TestTemp.dir("etch-gc-broken").toFile(), "venue.etch");
		AVector<ACell> tree = Vectors.of(
			Strings.create("left-" + "x".repeat(200)), Strings.create("right-" + "x".repeat(200)));
		EtchStore broken = EtchStore.create(f);
		broken.storeTopRef(tree.getRef(), Ref.STORED, null); // top entry only
		broken.getEtch().setRootHash(tree.getHash());
		broken.flush();
		broken.close();

		EtchStore store = EtchStore.create(f);
		try {
			TestLogs.quiet(VenueServer.class, () -> {
				assertSame(store, VenueServer.collectAtStartup(store, f, null),
					"a failed cycle boots on the original store");
			});
			assertFalse(store.isGCInProgress(), "the failed cycle must be cancelled");
			assertEquals(tree.getHash(), store.getRootHash(), "the original root is untouched");
		} finally {
			store.close();
		}
	}
}
