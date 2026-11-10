package covia.venue.server;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.store.MemoryStore;
import covia.lattice.Covia;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.TestSignal;

/**
 * A venue server opened its host, so it runs the host's sync and flush
 * workers: state reaches the store without anyone calling flush.
 */
public class VenueServerHostWorkersTest {

	/** A memory store that signals each time the host retains a root. */
	private static final class SignallingStore extends MemoryStore {
		final TestSignal retained = new TestSignal();

		@Override
		public <T extends ACell> Ref<T> setRootData(T value) {
			Ref<T> ref = super.setRootData(value);
			retained.signal();
			return ref;
		}
	}

	private static AMap<AString, ACell> config() {
		return Maps.of(Config.PORT, 0, Config.BIND_ADDRESS, "127.0.0.1",
			Config.SEED, AKeyPair.generate().getSeed().toHexString(),
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, false)));
	}

	private static boolean retains(MemoryStore store, AccountKey venue, AString did) {
		ACell root;
		try {
			root = store.getRootData();
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		return RT.getIn(root, Covia.GRID, Covia.VENUES, venue, Keywords.VALUE, Covia.USER_DATA, did) != null;
	}

	@Test
	public void theVenueRunsItsHostsWorkers() {
		SignallingStore store = new SignallingStore();
		VenueServer server = VenueServer.launch(config(), store);
		try {
			var host = server.getHost();
			assertThrows(IllegalStateException.class, () -> host.autoSync(Duration.ofSeconds(1)),
				"the venue already runs the host's sync worker");
			assertThrows(IllegalStateException.class, () -> host.autoFlush(Duration.ofSeconds(1)),
				"the venue already runs the host's flush worker");

			Engine engine = server.getEngine();
			AString did = Strings.create("did:test:host-workers");
			engine.getVenueState().users().ensure(did);
			store.retained.await("the sync worker to publish a write nobody flushed",
				() -> retains(store, engine.getAccountKey(), did));
		} finally {
			server.close();
		}
	}
}
