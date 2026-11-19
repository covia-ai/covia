package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import convex.auth.ucan.Capability;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.TestServer;

/**
 * The venue's refusal of a model call's outbound request is final. Isolated
 * because it watches langchain4j's retry logger, which other model tests may
 * write to.
 */
@Isolated
class ModelRefusalTest {

	private final Engine engine = TestServer.ENGINE;

	@Test void aRefusedModelCallIsTriedOnce(TestInfo info) throws Exception {
		AString owner = TestEngine.uniqueDID(info);
		engine.getVenueState().users().ensure(owner);
		AString id = Strings.create("unconnected");
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id, Fields.CONFIG,
			Maps.of(Config.CAPS, Vectors.of(Maps.of(Capability.CAN, Abilities.INVOKE)))), engine.context(owner))
			.awaitResult(10_000);
		RequestContext agent = engine.context(owner, Principals.agentDID(owner, id));

		Logger retries = (Logger) LoggerFactory.getLogger(dev.langchain4j.internal.RetryUtils.class);
		ListAppender<ILoggingEvent> captured = new ListAppender<>();
		captured.start();
		Level level = retries.getLevel();
		boolean additive = retries.isAdditive();
		retries.setLevel(Level.WARN);
		retries.setAdditive(false);
		retries.addAppender(captured);
		Job refused;
		try {
			refused = engine.jobs().invokeOperation("v/ops/langchain/openai", Maps.of("url", "http://localhost:1/v1",
				"apiKey", "test-key", "model", "test-model", "prompt", "hello"), agent);
			try { refused.awaitResult(15_000); } catch (Exception expected) { /* the refusal */ }
		} finally {
			retries.detachAppender(captured);
			retries.setLevel(level);
			retries.setAdditive(additive);
		}
		assertEquals(Status.FAILED, refused.getStatus());
		assertTrue(refused.getErrorMessage().contains(Abilities.OUTBOUND_HTTP_POST.toString()), refused.getErrorMessage());
		assertEquals(List.of(), captured.list, "the refusal is final, so langchain4j does not retry it");
	}
}
