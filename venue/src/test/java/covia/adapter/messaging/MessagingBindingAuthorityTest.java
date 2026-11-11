package covia.adapter.messaging;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.SecretStore;
import covia.venue.TestEngine;

/**
 * A bot acts on its own account with its creator's authority, as its
 * conversations do: a bot an agent created uses its user's credentials, and
 * runs a relay's hooks, only as far as that agent may
 * ({@link AMessagingAdapter#bindingContext}).
 */
class MessagingBindingAuthorityTest {
	private static Engine engine;

	@BeforeAll static void start() {
		engine = Engine.createTemp(Maps.of(Config.USERS, Maps.of(Config.AUTO_CREATE, true)));
		Engine.addDemoAssets(engine);
	}
	@AfterAll static void stop() { engine.close(); }

	private void createAgent(AString owner, String id, ACell caps) {
		Job job = engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id,
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo", Fields.CAPS, caps)), RequestContext.of(owner));
		TestEngine.awaitJob(job, Job::isFinished, 10000, () -> "agent:create never finished");
		assertEquals(Status.COMPLETE, job.getStatus(), job::getErrorMessage);
	}

	@Test void aBotAnAgentCreatedUsesOnlyWhatTheAgentMay(TestInfo info) {
		AString owner = TestEngine.uniqueDID(info);
		engine.getVenueState().users().ensure(owner).secrets().store("token", "bot-credential", SecretStore.keys(engine.getKeyPair()));
		TestMessagingAdapter adapter = new TestMessagingAdapter("binding-authority", owner);
		engine.registerAdapter(adapter);
		ACell manage = Capability.create(Strings.create(adapter.getName() + "/"), Strings.create(adapter.getName() + "/manage"));
		createAgent(owner, "maker", Vectors.of(manage));
		AString maker = Principals.agentDID(owner, Strings.create("maker"));

		adapter.handleCreate(engine.context(owner, maker), Maps.of(Fields.NAME, "made", Fields.TOKEN, "s/token"));
		TestMessagingAdapter.Bot made = adapter.runner(owner, "made");
		RequestContext acting = adapter.bindingContext(made);
		assertEquals(maker, acting.getCallerDID());
		assertFalse(engine.authorityCovers(acting, Strings.create(owner + "/w/x"), Capability.CRUD_WRITE),
			"the bot holds the agent's authority, not its user's");
		assertThrows(AuthException.class, () -> adapter.resolveCredential("s/token", made));

		// The same bot made by the user itself, and an agent allowed the secret, use it
		adapter.create(owner);
		assertEquals("bot-credential", adapter.resolveCredential("s/token", adapter.runner(owner, "runtime")));
		createAgent(owner, "trusted", Vectors.of(manage, Capability.create(Strings.create("s/token"), Abilities.SECRET_USE)));
		adapter.handleCreate(engine.context(owner, Principals.agentDID(owner, Strings.create("trusted"))),
			Maps.of(Fields.NAME, "trusted", Fields.TOKEN, "s/token"));
		assertEquals("bot-credential", adapter.resolveCredential("s/token", adapter.runner(owner, "trusted")));
	}
}
