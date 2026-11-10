package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;

/**
 * Agents as releases stored them, each configured as agents really were: the
 * templates and demo agents 0.10.0 shipped and the forms a capability took
 * ({@code migrations/released-agents.json}, which also says what each may do).
 * {@link VenueMigrationEndToEndTest} seeds them at version 0. An upgrade must
 * leave their records as they were, and each must then act as it was
 * configured to.
 */
final class ReleasedAgents {

	/** The other user the agents' configurations name. */
	private static final AString OTHER = Strings.create("did:test:migration-e2e-other");
	private static final AString SYSTEM_PROMPT = Strings.create("systemPrompt");
	private static final AString CHANGED = Strings.create("changed");
	private static final String UPDATE = "v/ops/agent/update";
	private static final AString NOTE = Strings.create("kept");
	private static final AString ALICE = Strings.create("released-ap-alice");
	private static final AString BOB = Strings.create("released-ap-bob");
	private static final AString NOTES = Strings.create("released-notes");
	private static final AString PUBLIC = Strings.create("released-public");
	private static final AString MANAGER = Strings.create("released-manager");
	private static final AString CHILD = Strings.create("released-child");

	private ReleasedAgents() {}

	private static AVector<ACell> cases() {
		try (InputStream in = ReleasedAgents.class.getResourceAsStream("/migrations/released-agents.json")) {
			return RT.ensureVector(JSON.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static AString id(ACell agentCase) {
		return RT.ensureString(RT.getIn(agentCase, Fields.ID));
	}

	/** Stores every agent as its release did; returns their records, by id, to compare after the upgrade. */
	@SuppressWarnings("unchecked")
	static Map<AString, ACell> seed(VenueState venue, AString owner) {
		venue.users().ensure(OTHER);
		User user = venue.users().ensure(owner);
		Map<AString, ACell> records = new HashMap<>();
		for (ACell agentCase : cases()) {
			Agent agent = user.ensureAgent(id(agentCase), (AMap<AString, ACell>) RT.getIn(agentCase, Fields.CONFIG), null);
			AString status = RT.ensureString(RT.getIn(agentCase, Fields.STATUS));
			if (status != null) agent.putRecord(agent.getRecord().assoc(Agent.KEY_STATUS, status));
			assertNull(agent.getRecord().get(Agent.KEY_CONTROLLER), "no release wrote a controller");
			records.put(id(agentCase), agent.getRecord());
		}
		return records;
	}

	static void assertUntouched(VenueState venue, AString owner, Map<AString, ACell> seeded) {
		User user = venue.users().user(owner);
		seeded.forEach((id, record) -> assertEquals(record, user.agent(id).getRecord(), id.toString()));
	}

	/** Each agent reaches what its configuration says, and the behaviour that depends on more than its caps holds. */
	static void assertActAsConfigured(Engine engine, AString owner) {
		for (ACell agentCase : cases()) {
			RequestContext agent = engine.context(owner, Principals.agentDID(owner, id(agentCase)));
			for (ACell reach : RT.ensureVector(RT.getIn(agentCase, "may"))) {
				assertTrue(covers(engine, agent, reach), id(agentCase) + " may " + reach);
			}
			for (ACell reach : RT.ensureVector(RT.getIn(agentCase, "mayNot"))) {
				assertFalse(covers(engine, agent, reach), id(agentCase) + " may not " + reach);
			}
		}
		RequestContext ownerContext = RequestContext.of(owner);
		assertModelRunsUnderEmptyCaps(engine, ownerContext);
		assertBareNotesAreTheAgentsOwn(engine, ownerContext);
		assertConfigUpdateKeepsTheRest(engine, owner, ownerContext);
		assertOlderPublicAgentAcceptsAnyone(engine, owner);
		assertManagerNeedsItsChildHandedOver(engine, owner, ownerContext);
	}

	/** {@code reach} is an ability, a resource and, for a grant limited to one, a destination. */
	private static boolean covers(Engine engine, RequestContext agent, ACell reach) {
		AVector<ACell> parts = RT.ensureVector(reach);
		AString destination = (parts.count() > 2) ? RT.ensureString(parts.get(2)) : null;
		return engine.authorityCovers(agent, RT.ensureString(parts.get(1)), RT.ensureString(parts.get(0)), destination);
	}

	/** Caps restrict an agent's tool calls, not its own model call. */
	private static void assertModelRunsUnderEmptyCaps(Engine engine, RequestContext owner) {
		Job chat = run(engine, "v/ops/agent/chat", Maps.of(Fields.AGENT_ID, ALICE, Fields.MESSAGE, "scan this"), owner);
		assertComplete(chat);
		assertTrue(RT.getIn(chat.getOutput(), Fields.RESPONSE).toString().contains("scan this"));
	}

	/** A tool's {@code n/} path is the agent's own notes: its caps over {@code n/} reach them, and caps without it do not. */
	private static void assertBareNotesAreTheAgentsOwn(Engine engine, RequestContext owner) {
		ACell write = Maps.of(Fields.PATH, "n/note", Fields.VALUE, NOTE);
		assertComplete(run(engine, "v/ops/covia/write", write, engine.stepContext(owner, NOTES)));
		Job read = run(engine, "v/ops/covia/read", Maps.of(Fields.PATH, "n/note"), engine.stepContext(owner, NOTES));
		assertComplete(read);
		assertEquals(NOTE, RT.getIn(read.getOutput(), Fields.VALUE));
		assertEquals(Status.FAILED, run(engine, "v/ops/covia/write", write, engine.stepContext(owner, BOB)).getStatus());
	}

	/** A partial update composes with the release's whole config, and passes this build's validation. */
	private static void assertConfigUpdateKeepsTheRest(Engine engine, AString owner, RequestContext ownerContext) {
		Agent bob = engine.getAgent(owner, BOB);
		AMap<AString, ACell> before = bob.getConfig();
		assertComplete(run(engine, UPDATE,
			Maps.of(Fields.AGENT_ID, BOB, Fields.CONFIG, Maps.of(SYSTEM_PROMPT, CHANGED)), ownerContext));
		assertEquals(before.assoc(SYSTEM_PROMPT, CHANGED), bob.getConfig());
		assertEquals(owner, bob.getController());
	}

	private static void assertOlderPublicAgentAcceptsAnyone(Engine engine, AString owner) {
		assertEquals(Admission.ANYONE, Admission.policy(engine.getAgent(owner, PUBLIC).getConfig()));
		assertTrue(engine.crossUserAllows(RequestContext.of(OTHER), Strings.create(owner + "/g/" + PUBLIC),
			Abilities.AGENT_REQUEST));
	}

	/**
	 * No release recorded who made an agent, so a capped manager does not
	 * control a child it made until the owner hands it over. Then the manager
	 * may configure it, and it acts within the manager's authority.
	 */
	private static void assertManagerNeedsItsChildHandedOver(Engine engine, AString owner, RequestContext ownerContext) {
		AString manager = Principals.agentDID(owner, MANAGER);
		AString child = Principals.agentDID(owner, CHILD);
		ACell change = Maps.of(Fields.AGENT_ID, CHILD, Fields.CONFIG, Maps.of(SYSTEM_PROMPT, CHANGED));

		Job refused = run(engine, UPDATE, change, engine.stepContext(ownerContext, MANAGER));
		assertEquals(Status.FAILED, refused.getStatus());
		assertTrue(refused.getErrorMessage().contains(Engine.NOT_CONTROLLERS.formatted(owner + "/g/" + CHILD, owner)),
			refused.getErrorMessage());

		assertComplete(run(engine, UPDATE, Maps.of(Fields.AGENT_ID, CHILD, Fields.CONTROLLER, manager), ownerContext));
		assertEquals(manager, engine.getAgent(owner, CHILD).getController());
		assertComplete(run(engine, UPDATE, change, engine.stepContext(ownerContext, MANAGER)));
		assertFalse(engine.authorityCovers(engine.context(owner, child), Strings.create("w/report"),
			convex.auth.ucan.Capability.CRUD_READ), "its manager holds no read of w/, so neither does it now");
	}

	private static Job run(Engine engine, String operation, ACell input, RequestContext ctx) {
		Job job = engine.jobs().callOperation(operation, input, ctx);
		try {
			job.awaitResult(20_000);
		} catch (Exception refused) {
			// The job's status and error say what happened.
		}
		return job;
	}

	private static void assertComplete(Job job) {
		assertEquals(Status.COMPLETE, job.getStatus(), job.getErrorMessage());
	}
}
