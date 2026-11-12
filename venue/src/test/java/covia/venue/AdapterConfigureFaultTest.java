package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import covia.adapter.AAdapter;
import covia.exception.FaultException;

/**
 * An adapter whose {@code configure} fails unexpectedly: on every route to it,
 * the failure is that adapter's fault, never the caller's invalid input or the
 * {@code venue} adapter's fault. Isolated because the fault is logged at ERROR,
 * which the test silences.
 */
@Isolated
public class AdapterConfigureFaultTest {

	private static final AString BAD = Strings.create("bad");

	/** Refuses a {@code bad} setting the way adapters used to, with an IllegalArgumentException. */
	private static final class FaultyAdapter extends AAdapter {
		private final String name;
		FaultyAdapter(String name) { this.name = name; }
		@Override public String getName() { return name; }
		@Override public String getDescription() { return "test"; }
		@Override public boolean configure(AMap<AString, ACell> config, boolean strict) {
			if (config.containsKey(BAD)) throw new IllegalArgumentException("bad is not a setting");
			return true;
		}
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			return CompletableFuture.completedFuture(input);
		}
	}

	private static String report(String adapter) {
		return FaultException.FAILED.formatted(Engine.CONFIGURING_ADAPTER.formatted(adapter),
			"IllegalArgumentException", FaultException.ADAPTER.formatted(adapter));
	}

	@Test
	public void testFaultInConfigureIsTheAdaptersOnEveryRoute() throws Exception {
		AMap<AString, ACell> bad = Maps.of(BAD, CVMBool.TRUE);
		Engine engine = Engine.createTemp(Maps.of(Config.ADAPTERS, Maps.of(
			Strings.create("faulty"), bad,
			Strings.create("parked"), bad.assoc(Config.ENABLED, CVMBool.FALSE))));
		try {
			Engine.addDemoAssets(engine);
			TestLogs.quiet(FaultException.class, () -> {
				// The venue config, as the adapter registers or is enabled
				FaultException atRegistration = assertThrows(FaultException.class,
					() -> engine.registerAdapter(new FaultyAdapter("faulty")));
				assertEquals(report("faulty"), atRegistration.getMessage());
				assertInstanceOf(IllegalArgumentException.class, atRegistration.getCause(), "the cause is kept for the log");
				assertNull(engine.getRegisteredAdapter("faulty"));

				engine.registerAdapter(new FaultyAdapter("parked"));
				assertEquals(report("parked"), assertThrows(FaultException.class,
					() -> engine.enableAdapter("parked")).getMessage());

				// A runtime reconfiguration, directly and through the operation
				assertEquals(report("parked"), assertThrows(FaultException.class,
					() -> engine.configureAdapter("parked", bad)).getMessage());
				ExecutionException viaOperation = assertThrows(ExecutionException.class,
					() -> engine.jobs().invokeInternal("v/ops/venue/adapter/configure",
						Maps.of(Strings.create("name"), Strings.create("parked"), Strings.create("config"), bad),
						engine.venueContext()).get(10, TimeUnit.SECONDS));
				assertEquals(report("parked"), viaOperation.getCause().getMessage(),
					"the configured adapter is named, not the venue adapter");
			});
			engine.configureAdapter("parked", Maps.empty());
			assertTrue(engine.enableAdapter("parked"), "it takes a configuration it does not refuse");
		} finally {
			engine.close();
		}
	}
}
