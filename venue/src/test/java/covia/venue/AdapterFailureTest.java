package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.adapter.VenueAdapter;
import covia.api.Fields;
import covia.exception.ConfigurationException;
import covia.exception.NotFoundException;
import covia.exception.NotSupportedException;
import covia.exception.StillStartingException;
import covia.exception.UnavailableException;
import covia.grid.Job;
import covia.grid.Status;

/**
 * An enabled adapter that is not running: its start failed at launch, enable
 * or restart. The venue runs without it, refuses its operations, lists why
 * for operators and starts it again on {@code venue/adapter/restart}.
 */
public class AdapterFailureTest {

	private static final String NAME = "flaky";
	private static final String OP = "v/ops/flaky/echo";
	private static final String HOLD = "v/ops/flaky/hold";
	private static final Blob RECOVERED_JOB = Blob.fromHex("0a11");
	private static final String START_FAILURE = "flaky backend unreachable";
	private static final String REFUSED = "flaky refuses this setting";
	private static final String DELETION_FAILURE = "flaky cannot reach its store of user data";
	private static final AString K_COLOUR = Strings.intern("colour");

	/** Fails to start while {@code failStart}; records its lifecycle calls. */
	private static final class FlakyAdapter extends AAdapter implements AutoCloseable {
		volatile boolean failStart;
		volatile boolean refuseConfig;
		/** A colour it refuses while it accepts others. */
		volatile String refuseColour;
		volatile boolean closed;
		volatile boolean failDeletion;
		volatile AMap<AString, ACell> configured;
		final List<String> events = new CopyOnWriteArrayList<>();

		@Override public String getName() { return NAME; }
		@Override public String getDescription() { return "Fails to start on demand"; }
		@Override protected void installAssets() {
			installAsset("flaky/echo", Maps.of(Fields.NAME, "Flaky echo",
				Fields.OPERATION, Maps.of(Fields.ADAPTER, "flaky:echo")));
			installAsset("flaky/hold", Maps.of(Fields.NAME, "Flaky hold",
				Fields.OPERATION, Maps.of(Fields.ADAPTER, "flaky:hold")));
		}
		@Override public boolean supportsMultiTurn() { return true; }
		@Override public boolean configure(AMap<AString, ACell> config, boolean strict) {
			events.add("configure");
			boolean refused = refuseConfig
				|| (refuseColour != null && Strings.create(refuseColour).equals(config.get(K_COLOUR)));
			if (refused) throw new ConfigurationException(REFUSED);
			configured = config;
			return true;
		}
		@Override public void start() {
			events.add("start");
			if (failStart) throw new UnavailableException(START_FAILURE);
		}
		@Override public void stop() { events.add("stop"); }
		@Override public void onUserDeleted(AString did, AMap<AString, ACell> previousUser) {
			if (failDeletion) throw new UnavailableException(DELETION_FAILURE);
		}
		@Override public void close() { closed = true; }
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx,
				AMap<AString, ACell> meta, ACell input) {
			if ("hold".equals(getSubOperation(meta))) return new CompletableFuture<>();
			return CompletableFuture.completedFuture(input);
		}
	}

	@Test
	public void adapterThatFailsToStartLeavesTheVenueRunningUntilRestarted() throws Exception {
		Engine engine = TestEngine.application().mount(AKeyPair.generate(), Maps.empty());
		FlakyAdapter flaky = new FlakyAdapter();
		flaky.failStart = true;
		try {
			engine.launch(prepared -> {
				prepared.registerAdapter(flaky);
				leaveStarted(prepared);
				assertThrows(StillStartingException.class, () -> prepared.restartAdapter(NAME));
				return CompletableFuture.completedFuture(null);
			});
			assertTrue(engine.isStarted(), "the venue runs without the adapter");
			assertEquals(START_FAILURE, engine.adapterFailure(NAME));
			assertEquals(List.of("configure", "start", "stop"), flaky.events, "what a failed start began is stopped");
			// Recovery came first: the adapter's unfinished job ended as recovery ends any.
			assertEquals(Status.FAILED, engine.getVenueState().users().user(engine.getDIDString())
				.jobs().getRecord(RECOVERED_JOB).get(Fields.STATUS));
			UnavailableException refused = assertThrows(UnavailableException.class,
				() -> engine.jobs().invokeOperation(OP, Maps.empty(), engine.venueContext()));
			assertEquals(Engine.ADAPTER_NOT_RUNNING.formatted(NAME), refused.getMessage());
			assertEquals(Strings.create("x"), RT.getIn(asVenue(engine, "v/test/ops/echo", Maps.of("value", "x")), "value"));

			AMap<AString, ACell> entry = listed(engine, NAME);
			assertEquals(VenueAdapter.ADAPTER_FAILED, entry.get(Fields.STATUS));
			assertEquals(Strings.create(START_FAILURE), entry.get(Fields.REASON));
			assertEquals(VenueAdapter.ADAPTER_RUNNING, listed(engine, "test").get(Fields.STATUS));

			// Still failing: the restart fails with the reason and the adapter stays failed.
			ExecutionException again = assertThrows(ExecutionException.class,
				() -> asVenue(engine, "v/ops/venue/adapter/restart", Maps.of("name", NAME)));
			assertEquals(START_FAILURE, assertInstanceOf(UnavailableException.class, again.getCause()).getMessage());
			assertEquals(START_FAILURE, engine.adapterFailure(NAME));

			// Fixed: a restart offers the effective settings again, then starts it.
			flaky.failStart = false;
			engine.configureAdapter(NAME, Maps.of(K_COLOUR, "blue"));
			flaky.events.clear();
			ACell restarted = asVenue(engine, "v/ops/venue/adapter/restart", Maps.of("name", NAME));
			assertEquals(VenueAdapter.ADAPTER_RUNNING, RT.getIn(restarted, Fields.STATUS));
			assertEquals(List.of("stop", "configure", "start"), flaky.events);
			assertEquals(Maps.of(K_COLOUR, "blue"), flaky.configured);
			assertNull(engine.adapterFailure(NAME));
			assertNull(listed(engine, NAME).get(Fields.REASON));
			assertEquals(Maps.of("v", 1), engine.jobs().invokeOperation(OP, Maps.of("v", 1),
				engine.venueContext()).awaitResult(5000));

			// A running adapter is stopped first; settings it now refuses leave it failed, unstarted.
			flaky.refuseConfig = true;
			flaky.events.clear();
			assertEquals(REFUSED, assertThrows(ConfigurationException.class,
				() -> engine.restartAdapter(NAME)).getMessage());
			assertEquals(List.of("stop", "configure"), flaky.events);
			assertEquals(REFUSED, engine.adapterFailure(NAME));
		} finally {
			engine.close();
		}
	}

	@Test
	public void enableWhoseStartFailsKeepsTheAdapterEnabledButFailed() throws Exception {
		Engine engine = boot();
		try {
			FlakyAdapter flaky = new FlakyAdapter();
			engine.registerAdapter(flaky);
			engine.disableAdapter(NAME);
			assertEquals(Engine.ADAPTER_IS_DISABLED.formatted(NAME),
				assertThrows(NotSupportedException.class, () -> engine.restartAdapter(NAME)).getMessage());
			assertThrows(NotFoundException.class, () -> engine.restartAdapter("no-such-adapter"));

			flaky.failStart = true;
			flaky.events.clear();
			assertEquals(START_FAILURE, assertThrows(UnavailableException.class,
				() -> engine.enableAdapter(NAME)).getMessage());
			assertTrue(engine.hasAdapter(NAME), "the adapter stays enabled");
			assertFalse(flaky.closed, "and keeps its instance");
			assertEquals(START_FAILURE, engine.adapterFailure(NAME));
			assertEquals(List.of("start", "stop"), flaky.events);

			assertTrue(engine.disableAdapter(NAME));
			assertNull(engine.adapterFailure(NAME), "disabling forgets the failure");
			AMap<AString, ACell> disabled = listed(engine, NAME);
			assertEquals(VenueAdapter.ADAPTER_DISABLED, disabled.get(Fields.STATUS));
			assertNull(disabled.get(Fields.REASON));

			assertThrows(UnavailableException.class, () -> engine.enableAdapter(NAME));
			engine.removeAdapter(NAME);
			assertNull(engine.adapterFailure(NAME), "removal forgets the failure");
			assertTrue(flaky.closed);
		} finally {
			engine.close();
		}
	}

	@Test
	public void enableThatCannotFinishAUserDeletionLeavesTheAdapterDisabled() {
		Engine engine = boot();
		try {
			FlakyAdapter flaky = new FlakyAdapter();
			engine.registerAdapter(flaky);
			AString user = TestEngine.uniqueDID("flaky-user");
			engine.getVenueState().users().create(user);
			flaky.failDeletion = true;
			assertEquals(DELETION_FAILURE, assertThrows(UnavailableException.class,
				() -> engine.deleteUser(engine.venueContext(), user)).getMessage());
			engine.disableAdapter(NAME);

			// Enabling resumes the deletion first; when that fails, the adapter is disabled as before.
			assertEquals(DELETION_FAILURE, assertThrows(UnavailableException.class,
				() -> engine.enableAdapter(NAME)).getMessage());
			assertFalse(engine.hasAdapter(NAME));
			assertFalse(flaky.closed, "the instance is kept for the next enable");
			flaky.failDeletion = false;
			assertTrue(engine.enableAdapter(NAME));
			assertNull(engine.adapterFailure(NAME));
		} finally {
			engine.close();
		}
	}

	@Test
	public void refusedChangeIsRevertedWithThePreviousSettings() {
		Engine engine = boot();
		try {
			FlakyAdapter flaky = new FlakyAdapter();
			engine.registerAdapter(flaky);
			AMap<AString, ACell> blue = Maps.of(K_COLOUR, "blue");
			engine.configureAdapter(NAME, blue);
			flaky.refuseColour = "red";
			flaky.events.clear();
			assertEquals(REFUSED, assertThrows(ConfigurationException.class,
				() -> engine.configureAdapter(NAME, Maps.of(K_COLOUR, "red"))).getMessage());
			assertEquals(List.of("configure", "configure"), flaky.events, "offered the change, then its previous settings");
			assertEquals(blue, flaky.configured);
			assertEquals(blue, engine.adapterConfig(NAME));
			assertNull(engine.adapterFailure(NAME), "it carries on running");

			// Refusing its previous settings too takes it out of service.
			flaky.refuseConfig = true;
			flaky.events.clear();
			assertEquals(REFUSED, assertThrows(ConfigurationException.class,
				() -> engine.configureAdapter(NAME, Maps.of(K_COLOUR, "green"))).getMessage());
			assertEquals(List.of("configure", "configure", "stop"), flaky.events);
			assertEquals(REFUSED, engine.adapterFailure(NAME));
			assertEquals(blue, engine.adapterConfig(NAME), "the refused change is not recorded");

			// Disabled meanwhile, it is offered its settings again when enabled, and only then.
			engine.disableAdapter(NAME);
			flaky.refuseConfig = false;
			flaky.configured = null;
			flaky.events.clear();
			engine.enableAdapter(NAME);
			assertEquals(List.of("configure", "start"), flaky.events);
			assertEquals(blue, flaky.configured);
			assertNull(engine.adapterFailure(NAME));
			engine.disableAdapter(NAME);
			flaky.events.clear();
			engine.enableAdapter(NAME);
			assertEquals(List.of("start"), flaky.events);

			// Unsettled while disabled: nothing is stopped, and enabling waits for settings it takes.
			engine.disableAdapter(NAME);
			flaky.refuseConfig = true;
			flaky.events.clear();
			assertThrows(ConfigurationException.class, () -> engine.configureAdapter(NAME, Maps.of(K_COLOUR, "green")));
			assertEquals(List.of("configure", "configure"), flaky.events);
			assertEquals(REFUSED, assertThrows(ConfigurationException.class, () -> engine.enableAdapter(NAME)).getMessage());
			assertFalse(engine.hasAdapter(NAME), "it stays disabled");
			flaky.refuseConfig = false;
			assertTrue(engine.enableAdapter(NAME));
			assertNull(engine.adapterFailure(NAME));
		} finally {
			engine.close();
		}
	}

	@Test
	public void failedAdapterRefusesWorkUntilReplaced() throws Exception {
		Engine engine = boot();
		Job held = null;
		try {
			FlakyAdapter failed = new FlakyAdapter();
			engine.registerAdapter(failed);
			held = engine.jobs().invokeOperation(HOLD, Maps.empty(), engine.venueContext());
			failed.failStart = true;
			assertThrows(UnavailableException.class, () -> engine.restartAdapter(NAME));

			// Messages to its live jobs and internal calls (tools, orchestrations) are refused alike.
			String notRunning = Engine.ADAPTER_NOT_RUNNING.formatted(NAME);
			Blob heldID = held.getID();
			assertEquals(notRunning, assertThrows(UnavailableException.class, () -> engine.jobs().deliverMessage(
				heldID, Maps.of("content", "more"), engine.venueContext())).getMessage());
			ExecutionException internal = assertThrows(ExecutionException.class, () -> asVenue(engine, OP, Maps.empty()));
			assertEquals(notRunning, assertInstanceOf(UnavailableException.class, internal.getCause()).getMessage());

			// A replacement that fails to start is rolled back; the failed instance is not started again.
			failed.events.clear();
			FlakyAdapter broken = new FlakyAdapter();
			broken.failStart = true;
			assertEquals(START_FAILURE, assertThrows(UnavailableException.class,
				() -> engine.registerAdapter(broken)).getMessage());
			assertSame(failed, engine.getAdapter(NAME));
			assertEquals(START_FAILURE, engine.adapterFailure(NAME));
			assertEquals(List.of("stop"), failed.events);
			assertTrue(broken.closed);

			FlakyAdapter replacement = new FlakyAdapter();
			engine.registerAdapter(replacement);
			assertSame(replacement, engine.getAdapter(NAME));
			assertNull(engine.adapterFailure(NAME), "the running replacement clears the failure");
			assertTrue(failed.closed);
		} finally {
			if (held != null) held.cancel();
			engine.close();
		}
	}

	/** A job a previous run left STARTED: its record and the venue's entry for it, as admission writes them. */
	private static void leaveStarted(Engine engine) {
		AString owner = engine.getDIDString();
		engine.getVenueState().execute(draft -> {
			draft.users().ensure(owner).jobs().create(RECOVERED_JOB, Maps.of(Fields.ID, RECOVERED_JOB,
				Fields.OP, Strings.create(OP), Fields.STATUS, Status.STARTED, Fields.CALLER, owner, Fields.INPUT, Maps.empty()));
			draft.jobIndex().put(RECOVERED_JOB, Maps.of(JobIndex.OWNER, owner));
		});
	}

	private static Engine boot() {
		Engine engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
		return engine;
	}

	private static ACell asVenue(Engine engine, String op, AMap<AString, ACell> input) throws Exception {
		return engine.jobs().invokeInternal(op, input, engine.venueContext()).get(10, TimeUnit.SECONDS);
	}

	/** An adapter's entry in the operator listing. */
	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> listed(Engine engine, String name) throws Exception {
		AVector<ACell> adapters = RT.ensureVector(RT.getIn(asVenue(engine, "v/ops/venue/adapters", Maps.empty()), "adapters"));
		for (ACell entry : adapters) {
			if (Strings.create(name).equals(RT.getIn(entry, Fields.NAME))) return (AMap<AString, ACell>) entry;
		}
		return fail("not listed: " + name);
	}
}
