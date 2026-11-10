package covia.venue.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.adapter.AAdapter;
import covia.exception.ConfigurationException;
import covia.grid.Venue;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * The operator's list of venue proof mechanisms: a mechanism an adapter
 * contributes is consulted while its adapter is enabled and withdrawn while it
 * is disabled; a proof is reused for its mechanism's period, and a failure
 * never is.
 */
@Execution(ExecutionMode.SAME_THREAD) // Tests share one probe, and one disables its adapter.
public class VenueProofsTest {

	private static final String TARGET = "https://venue.example";
	private static final String DID = "did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
	private static final long REUSE_SECONDS = 10;

	/** A mechanism that proves or refuses as it is told, counting the times it is asked. */
	private static final class Probe extends AAdapter implements VenueProofMethods {
		final AtomicInteger asked = new AtomicInteger();
		volatile String reason;
		private final Map<AString, Method> methods = Map.of(Strings.create("probe"), new Method() {
			@Override
			public String prove(String target, String did, Supplier<Venue> connection) {
				asked.incrementAndGet();
				return reason;
			}

			@Override
			public long reuseSeconds() { return REUSE_SECONDS; }
		});

		@Override public String getName() { return "proof-probe"; }
		@Override public String getDescription() { return "Venue proof test mechanism"; }
		@Override public Map<AString, Method> venueProofMethods() { return methods; }
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			return CompletableFuture.failedFuture(new UnsupportedOperationException());
		}
	}

	private static Engine engine;
	private static Probe probe;

	private static final Supplier<Venue> NO_DIAL = () -> { throw new AssertionError("the probe dials nothing"); };

	private final AtomicLong now = new AtomicLong(1_000_000);
	private VenueProofs proofs;

	@BeforeAll
	static void startEngine() {
		engine = Engine.createTemp(Maps.of(Config.AUTH, Maps.of(VenueProofs.VENUE_PROOF,
			Vectors.of(Maps.of(AuthMethods.ADAPTER, "proof-probe", AuthMethods.IMPLEMENTATION, "probe")))));
		probe = new Probe();
		engine.registerAdapter(probe);
	}

	@AfterAll
	static void closeEngine() { engine.close(); }

	@BeforeEach
	void reset() {
		probe.asked.set(0);
		probe.reason = null;
		proofs = new VenueProofs(engine, now::get);
	}

	@Test
	void anAdapterContributedMechanismIsWithdrawnWhileItsAdapterIsDisabled() {
		assertNull(proofs.prove(TARGET, DID, NO_DIAL));
		assertEquals(1, probe.asked.get());
		assertTrue(engine.disableAdapter(probe.getName()));
		try {
			String reason = proofs.prove(TARGET, DID, NO_DIAL);
			assertTrue(reason != null && reason.contains(VenueProofs.UNAVAILABLE), reason);
			assertEquals(1, probe.asked.get(), "a withdrawn mechanism is not asked, nor its earlier proof reused");
			assertThrows(ConfigurationException.class, proofs::validate, "a listed mechanism must be available at startup");
		} finally {
			engine.enableAdapter(probe.getName());
		}
		assertDoesNotThrow(proofs::validate);
	}

	@Test
	void aProofIsReusedForItsMechanismsPeriod() {
		assertNull(proofs.prove(TARGET, DID, NO_DIAL));
		now.addAndGet(REUSE_SECONDS * 1000 - 1);
		assertNull(proofs.prove(TARGET, DID, NO_DIAL));
		assertEquals(1, probe.asked.get());
		now.addAndGet(1);
		assertNull(proofs.prove(TARGET, DID, NO_DIAL));
		assertEquals(2, probe.asked.get());
		assertNull(proofs.prove("https://other.example", DID, NO_DIAL));
		assertEquals(3, probe.asked.get(), "a proof is reused only for the same target");
	}

	@Test
	void aFailureIsNeverReused() {
		probe.reason = "not proven";
		assertEquals("proof-probe/probe: not proven", proofs.prove(TARGET, DID, NO_DIAL));
		assertEquals("proof-probe/probe: not proven", proofs.prove(TARGET, DID, NO_DIAL));
		assertEquals(2, probe.asked.get());
	}

	@Test
	void theListIsAnArrayOfAdapterImplementationPairs() {
		assertDoesNotThrow(() -> VenueProofs.validateConfig(Maps.empty()));
		assertDoesNotThrow(() -> VenueProofs.validateConfig(Maps.of(VenueProofs.VENUE_PROOF, Vectors.empty())));
		for (ACell invalid : new ACell[] {
				Strings.create("address"),
				Vectors.of("address"),
				Vectors.of(Maps.of(AuthMethods.ADAPTER, "auth")),
				Vectors.of(Maps.of(AuthMethods.ADAPTER, "auth", AuthMethods.IMPLEMENTATION, "")),
				Vectors.of(Maps.of(AuthMethods.ADAPTER, "auth", AuthMethods.IMPLEMENTATION, "address", "order", 1))}) {
			ConfigurationException refused = assertThrows(ConfigurationException.class,
				() -> VenueProofs.validateConfig(Maps.of(VenueProofs.VENUE_PROOF, invalid)), invalid.toString());
			assertTrue(refused.getMessage().startsWith(VenueProofs.INVALID_CONFIG), refused.getMessage());
		}
	}
}
