package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.api.Fields;

/**
 * stats.ops is the number of invocable operations in the venue catalogue
 * ({@code v/ops} and {@code v/test/ops}), the same population a client lists
 * by reading those sub-trees. Adapters also declare models, skills and agent
 * templates through the same mechanism; those are not operations, and counting
 * them made the venue card disagree with the Operations page (frontend#421).
 */
class StatsOpsCountTest {

	private static final Engine engine = TestEngine.ENGINE;

	@Test
	void statsOpsCountsOnlyCatalogOperations() throws Exception {
		long listed = countOperations(read("v/ops")) + countOperations(read("v/test/ops"));
		long stated = RT.ensureLong(engine.getStats().get(Strings.create("ops"))).longValue();
		assertEquals(listed, stated, "stats.ops matches the operations a client finds in the catalogue");
	}

	@Test
	void nonOperationDeclarationsExist() {
		// Guards the test above: it only proves something while adapters declare
		// catalog entries that are not operations.
		long declared = 0, operations = 0;
		for (AAdapter adapter : engine.adapters.values()) {
			declared += adapter.pendingCatalogEntries.size();
			operations += adapter.getOperationPaths().size();
		}
		assertTrue(declared > operations, "adapters declare skills, templates or models besides operations");
	}

	private static ACell read(String path) throws Exception {
		ACell result = engine.jobs().invokeInternal("v/ops/covia/read",
			Maps.of(Fields.PATH, path), engine.venueContext()).get(10, TimeUnit.SECONDS);
		return RT.getIn(result, Fields.VALUE);
	}

	/** Same rule as the frontend's catalogue walk: a node carrying {@code operation} is one operation. */
	private static long countOperations(ACell node) {
		if (!(node instanceof AMap<?, ?> map)) return 0;
		if (map.get(Fields.OPERATION) != null) return 1;
		long n = 0;
		for (long i = 0; i < map.count(); i++) n += countOperations(map.entryAt(i).getValue());
		return n;
	}
}
