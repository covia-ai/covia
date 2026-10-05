package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.etch.EtchStore;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.server.VenueServer;

/** Startup readiness, including work restored before embedder registration (#553). */
public class VenueStartupTest {

	private static final AString OP = Strings.create("v/ops/startup-test/echo");
	private static final Blob RECOVERED_JOB = Blob.fromHex("0553");
	private static final String START_FAILURE = "injected adapter startup failure";
	private static final String HOOK_FAILURE = "injected startup hook failure";

	private static AMap<AString, ACell> config(AKeyPair key, int port) {
		return Maps.of(Config.PORT, port, Config.BIND_ADDRESS, "127.0.0.1",
			Config.STORE, "temp", Config.SEED, key.getSeed().toHexString());
	}

	private static class StartupAdapter extends AAdapter implements AutoCloseable {
		final AtomicInteger starts = new AtomicInteger();
		final AtomicInteger calls = new AtomicInteger();
		final CountDownLatch closed = new CountDownLatch(1);
		final CompletableFuture<ACell> fired = new CompletableFuture<>();
		boolean recovered;
		boolean failStart;

		@Override public String getName() { return "startup-test"; }
		@Override public String getDescription() { return "Startup ordering fixture"; }
		@Override protected void installAssets() {
			installAsset("startup-test/echo", Maps.of(Fields.NAME, "Startup echo",
				Fields.OPERATION, Maps.of(Fields.ADAPTER, "startup-test:echo",
					Fields.READ_ONLY, false)));
		}
		@Override public void start() {
			starts.incrementAndGet();
			assertNotNull(engine.resolveAsset(OP, engine.venueContext()));
			if (failStart) throw new IllegalStateException(START_FAILURE);
		}
		@Override public void recoverJob(Job job) {
			recovered = true;
			assertEquals(0, starts.get(), "recovery precedes autonomous workers");
			job.completeWith(Strings.create("recovered"));
		}
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx,
				AMap<AString, ACell> metadata, ACell input) {
			requireInvoke(ctx);
			assertTrue(recovered, "embedder recovery must precede the overdue fire");
			assertEquals(1, starts.get());
			calls.incrementAndGet();
			fired.complete(input);
			return CompletableFuture.completedFuture(input);
		}
		@Override public void close() { closed.countDown(); }
	}

	@Test
	public void asyncHookCompletesBeforeRecoveryWorkersSchedulesAndListener() throws Exception {
		AKeyPair key = AKeyPair.generate();
		EtchStore store = EtchStore.createTemp("startup-order");
		CompletableFuture<Void> release = new CompletableFuture<>();
		CompletableFuture<Engine> prepared = new CompletableFuture<>();
		StartupAdapter adapter = new StartupAdapter();
		CompletableFuture<VenueServer> launch = null;
		VenueServer server = null;
		try (ServerSocket reserved = new ServerSocket(0)) {
			AMap<AString, ACell> config = config(key, reserved.getLocalPort());
			Engine seed = new Engine(config, CoviaApplication.open(store, key), key).prepare();
			try {
				seed.registerAdapter(new StartupAdapter());
				seed.materialiseBootstrapState();
				seed.getVenueState().users().ensure(seed.getDIDString()).persistJob(RECOVERED_JOB,
					Maps.of(Fields.ID, RECOVERED_JOB, Fields.OP, OP, Fields.STATUS, Status.STARTED,
						Fields.CALLER, seed.getDIDString(), Fields.INPUT, Maps.empty()));
				seed.gridScheduler().schedule(OP, Strings.create("due"), seed.venueContext(), 1L);
			} finally {
				seed.close();
			}
			launch = VenueServer.launchAsync(config, store, List.of(), engine -> {
				prepared.complete(engine);
				return release.thenRun(() -> engine.registerAdapter(adapter));
			});
			Engine engine = prepared.get(10, TimeUnit.SECONDS);
			assertFalse(engine.isStarted());
			assertFalse(launch.isDone());
			assertEquals(0, adapter.starts.get());
			assertEquals(0, adapter.calls.get());
			assertEquals(1, engine.gridScheduler().list(engine.venueContext()).count());
			assertEquals(Status.STARTED, engine.getVenueState().users().get(engine.getDIDString())
				.getJob(RECOVERED_JOB).get(Fields.STATUS));
			// The reserved port prevents accidental early HTTP publication.
			reserved.close();
			release.complete(null);
			server = launch.get(10, TimeUnit.SECONDS);
			assertTrue(server.port() > 0);
			assertTrue(engine.isStarted());
			assertTrue(adapter.recovered);
			assertEquals(Strings.create("due"), adapter.fired.get(5, TimeUnit.SECONDS));
			assertEquals(1, adapter.calls.get());
			assertEquals(0, engine.gridScheduler().list(engine.venueContext()).count());
			assertEquals(Status.COMPLETE, engine.getVenueState().users().get(engine.getDIDString())
				.getJob(RECOVERED_JOB).get(Fields.STATUS));
		} finally {
			if (server != null) server.close();
			else if (launch != null) launch.cancel(true);
			else store.close();
		}
	}

	@Test
	public void queuedAgentSeesAdapterRegisteredByAsyncHook() throws Exception {
		AKeyPair key = AKeyPair.generate();
		CompletableFuture<Void> release = new CompletableFuture<>();
		CompletableFuture<AgentState> queued = new CompletableFuture<>();
		StartupAdapter adapter = new StartupAdapter() {
			@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx,
					AMap<AString, ACell> metadata, ACell input) {
				fired.complete(input);
				return CompletableFuture.completedFuture(input);
			}
		};
		CompletableFuture<VenueServer> launch = VenueServer.launchAsync(config(key, 0),
			List.of(), engine -> {
				AString owner = engine.getDIDString();
				AgentState agent = engine.getVenueState().users().ensure(owner).ensureAgent(
					"startup-agent", Maps.of(Fields.OPERATION, OP), null);
				Blob sid = Blob.fromHex("05530001");
				agent.ensureSession(sid, owner);
				agent.appendSessionPending(sid, Maps.of(Fields.SESSION_ID, sid.toHexString(),
					Fields.MESSAGE, Maps.of("content", "queued before registration")));
				queued.complete(agent);
				return release.thenRun(() -> engine.registerAdapter(adapter));
			});
		VenueServer server = null;
		try {
			AgentState agent = queued.get(10, TimeUnit.SECONDS);
			assertTrue(agent.hasSessionPending());
			assertFalse(adapter.fired.isDone());
			release.complete(null);
			server = launch.get(10, TimeUnit.SECONDS);
			assertNotNull(adapter.fired.get(5, TimeUnit.SECONDS));
			TestEngine.awaitAgentIdle(agent, 5_000);
			assertNull(agent.getError());
		} finally {
			if (server != null) server.close();
			else launch.cancel(true);
		}
	}

	@Test
	public void failedHookPreservesCauseAndReleasesOwnedStore() throws Exception {
		AKeyPair key = AKeyPair.generate();
		EtchStore store = EtchStore.createTemp("startup-failure");
		java.io.File file = store.getFile();
		StartupAdapter adapter = new StartupAdapter();
		IllegalStateException failure = new IllegalStateException(HOOK_FAILURE);
		CompletableFuture<VenueServer> launch = VenueServer.launchAsync(config(key, 0), store,
			List.of(), engine -> {
				engine.registerAdapter(adapter);
				return CompletableFuture.failedFuture(failure);
			});
		assertSame(failure, assertThrows(ExecutionException.class,
			() -> launch.get(10, TimeUnit.SECONDS)).getCause());
		assertTrue(adapter.closed.await(5, TimeUnit.SECONDS));
		assertEquals(0, adapter.starts.get());
		try (EtchStore reopened = EtchStore.create(file)) {
			assertNotNull(reopened);
		}
	}

	@Test
	public void failedWorkerActivationClosesAdapterAndStore() throws Exception {
		AKeyPair key = AKeyPair.generate();
		EtchStore store = EtchStore.createTemp("startup-worker-failure");
		java.io.File file = store.getFile();
		StartupAdapter adapter = new StartupAdapter();
		adapter.failStart = true;
		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> VenueServer.launch(config(key, 0), store, List.of(), engine -> {
				engine.registerAdapter(adapter);
				return CompletableFuture.completedFuture(null);
			}));
		assertEquals(START_FAILURE, failure.getMessage());
		assertTrue(adapter.closed.await(5, TimeUnit.SECONDS));
		try (EtchStore reopened = EtchStore.create(file)) {
			assertNotNull(reopened);
		}
	}

	@Test
	public void cancellationDuringHookClosesResourcesWithoutStartingWorkers() throws Exception {
		AKeyPair key = AKeyPair.generate();
		EtchStore store = EtchStore.createTemp("startup-cancel");
		StartupAdapter adapter = new StartupAdapter();
		CompletableFuture<Void> hook = new CompletableFuture<>();
		CompletableFuture<Engine> prepared = new CompletableFuture<>();
		CompletableFuture<VenueServer> launch = VenueServer.launchAsync(config(key, 0), store,
			List.of(), engine -> {
				engine.registerAdapter(adapter);
				prepared.complete(engine);
				return hook;
			});
		try {
			Engine engine = prepared.get(10, TimeUnit.SECONDS);
			assertTrue(launch.cancel(true));
			assertTrue(adapter.closed.await(5, TimeUnit.SECONDS));
			TestEngine.awaitCondition(engine::isClosing, 5_000, () -> "Engine was not closed");
			assertEquals(0, adapter.starts.get());
			assertFalse(hook.isDone(), "the embedder owns the supplied stage");
		} finally {
			launch.cancel(true);
		}
	}

	@Test
	public void failedRuntimeWorkerStartRestoresPreviousAdapter() {
		Engine engine = Engine.createTemp(null);
		try {
			Engine.addDemoAssets(engine);
			StartupAdapter original = new StartupAdapter();
			engine.registerAdapter(original);
			StartupAdapter replacement = new StartupAdapter();
			replacement.failStart = true;
			assertEquals(START_FAILURE, assertThrows(IllegalStateException.class,
				() -> engine.registerAdapter(replacement)).getMessage());
			assertSame(original, engine.getAdapter(original.getName()));
			assertEquals(0, replacement.closed.getCount());
			assertEquals(1, original.closed.getCount());
			assertNotNull(engine.resolveAsset(OP, engine.venueContext()));
		} finally {
			engine.close();
		}
	}

	@Test
	public void runtimeRegistrationAndEnableStartWorkers() {
		Engine engine = Engine.createTemp(null);
		try {
			Engine.addDemoAssets(engine);
			StartupAdapter adapter = new StartupAdapter();
			engine.registerAdapter(adapter);
			assertEquals(1, adapter.starts.get());
			engine.disableAdapter(adapter.getName());
			engine.enableAdapter(adapter.getName());
			assertEquals(2, adapter.starts.get());
		} finally {
			engine.close();
		}
	}
}
