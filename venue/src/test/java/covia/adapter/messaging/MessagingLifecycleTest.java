package covia.adapter.messaging;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.AString;
import convex.core.data.Maps;
import covia.exception.UnavailableException;
import covia.venue.Engine;
import covia.venue.TestEngine;

/**
 * A messaging adapter's bots stop while it is disabled: they stop with it,
 * nothing starts one until it is enabled again, and then they all start with
 * their current settings.
 */
class MessagingLifecycleTest {
	private static Engine engine;

	@BeforeAll static void start() { engine = Engine.createTemp(Maps.empty()); }
	@AfterAll static void stop() { engine.close(); }

	@Test void stoppedBotsStartOnlyWhenTheAdapterIsEnabledAgain(TestInfo info) {
		AString owner = TestEngine.uniqueDID(info);
		engine.getVenueState().users().create(owner);
		TestMessagingAdapter adapter = new TestMessagingAdapter("stopping", owner);
		adapter.configureOwner("configured");
		engine.registerAdapter(adapter);
		adapter.create(owner);
		TestMessagingAdapter.Bot configured = adapter.runner("configured"), runtime = adapter.runner(owner, "runtime");
		assertTrue(configured.running && runtime.running);

		engine.disableAdapter(adapter.getName());
		assertFalse(configured.running, "disabling stops operator bots");
		assertFalse(runtime.running, "and created ones");
		assertNull(adapter.runner("configured"));
		assertNull(adapter.runner(owner, "runtime"));

		// Nothing starts a bot meanwhile: configuration, re-arming, create or delete.
		adapter.configureOwner("configured", "added");
		adapter.rearmRuntimeBots();
		assertNull(adapter.runner("added"));
		assertNull(adapter.runner(owner, "runtime"));
		String notRunning = AMessagingAdapter.NOT_RUNNING.formatted(adapter.providerName());
		assertEquals(notRunning, assertThrows(UnavailableException.class, () -> adapter.create(owner)).getMessage());
		assertEquals(notRunning, assertThrows(UnavailableException.class, () -> adapter.delete(owner)).getMessage());
		assertNotNull(adapter.state().read(adapter.userStatePath(owner, "bots/runtime")), "a stopped bot keeps its record");

		engine.enableAdapter(adapter.getName());
		assertTrue(adapter.runner("configured").running);
		assertTrue(adapter.runner("added").running, "settings given while disabled apply when enabled");
		TestMessagingAdapter.Bot restarted = adapter.runner(owner, "runtime");
		assertTrue(restarted.running, "created bots start again from their records");
		assertNotSame(runtime, restarted);
		adapter.delete(owner);
		assertFalse(restarted.running);
		assertNull(adapter.state().read(adapter.userStatePath(owner, "bots/runtime")));
	}
}
