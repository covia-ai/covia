package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import covia.adapter.AgentAdapter;
import covia.exception.CoviaException;
import covia.exception.NotSupportedException;
import covia.grid.Asset;
import covia.lattice.CoviaPath;

/**
 * An agent looks up the references in its config through its user and venue,
 * and refuses tasks when the model they name cannot call tools. A lookup only
 * resolves; the refusal reads only what the authority the agent runs under may
 * read, as its cycle does.
 */
public class AgentLookupTest {

	private static final Engine ENGINE = TestEngine.ENGINE;

	/** A model definition, held in its owner's {@code o/}, whose model cannot call tools. */
	private static final AMap<AString, ACell> TINY = Maps.of(
		"name", "Tiny",
		"operation", Maps.of("adapter", "test:llm"),
		"model", Maps.of("id", "tiny", "provider", "v/test/ops/llm",
			"options", Maps.of("toolCalling", CVMBool.FALSE)));

	private static final AMap<AString, ACell> NO_TOOL_CALLING =
		Maps.of("options", Maps.of("toolCalling", CVMBool.FALSE));

	private static User user(AString did) {
		User user = ENGINE.getVenueState().users().ensure(did);
		user.operations().write(CoviaPath.parse("tiny"), TINY);
		return user;
	}

	private static Agent agent(User user, String id, AMap<AString, ACell> config) {
		return user.createAgent(Strings.create(id), config, null);
	}

	/** {@code user}'s agent {@code id}, whose harness acts with {@code controller}'s authority. */
	private static Agent controlled(User user, String id, AMap<AString, ACell> config, AString controller) {
		return user.createAgent(Strings.create(id), config, null, null, controller);
	}

	private static AString ref(Object... parts) {
		StringBuilder sb = new StringBuilder();
		for (Object part : parts) sb.append(part);
		return Strings.create(sb.toString());
	}

	/** What {@code agent}'s harness may read, as a request's intake asks. */
	private static Predicate<AString> reads(Agent agent) {
		return AgentAdapter.harnessReads(ENGINE, agent.getOwner(), agent.getAgentId());
	}

	/** The refusal a request's intake reaches. */
	private static AString refusal(Agent agent) {
		return agent.taskRefusal(reads(agent));
	}

	/** The refusal the agent's cycle reaches, from what this venue holds, under the same harness. */
	private static AString cycleRefusal(Agent agent) {
		return Agent.taskRefusal(agent.getConfig(), ref -> {
			try {
				Asset asset = ENGINE.resolveLocalAsset(ref, ENGINE.harnessContext(agent.getOwner(), agent.getAgentId()));
				return (asset == null) ? null : asset.meta();
			} catch (CoviaException e) {
				return null;
			}
		});
	}

	@Test
	public void testAgentLooksUpThroughItsUserAndVenue(TestInfo info) {
		User user = user(TestEngine.uniqueDID(info));
		Agent agent = agent(user, "looker", Maps.empty());
		agent.workspace().write(CoviaPath.parse("note"), Strings.create("mine"));

		assertEquals(Strings.create("mine"), agent.lookup(ref("n/note")), "its own workspace");
		assertEquals(TINY, agent.lookup(ref("o/tiny")), "its user's namespaces");
		assertEquals(TINY, agent.lookup(ref(user.getDID(), "/o/tiny")), "by its user's DID");

		AMap<AString, ACell> llm = Models.operation(agent.lookup(ref("v/test/ops/llm")));
		assertNotNull(llm, "the venue's definitions");
		assertEquals(llm, agent.lookup(ref(ENGINE.getDIDString(), "/v/test/ops/llm")), "by the venue's DID");

		AMap<AString, ACell> asset = ENGINE.getVenueState().assets().meta(llm.getHash());
		assertNotNull(asset, "the venue's catalogue holds the definition as an asset");
		assertEquals(asset, agent.lookup(ref("a/", llm.getHash().toHexString())), "the venue's assets by hash");

		assertNotNull(agent.getRecord());
		assertNull(agent.lookup(ref("g/looker")), "only the namespaces that hold definitions and data");
		assertNull(agent.lookup(ref(user.getDID(), "/g/looker")));
		assertNull(agent.lookup(ref("o/missing")));
		assertNull(agent.lookup(ref("c/scratch")), "no session to look in");
		assertNull(agent.lookup(ref("v/test/ops/missing")));
	}

	/** A malformed reference looks up nothing, so it is left to whoever uses it to report. */
	@Test
	public void testMalformedReferencesLookUpNothing(TestInfo info) {
		User user = user(TestEngine.uniqueDID(info));
		for (String junk : new String[] {"", "/", "//", "did:", "did:web:", "did:key:/o/tiny", "a/zz", "a/", "zz", "v/", "n/"}) {
			Agent agent = agent(user, "junk-" + Integer.toHexString(junk.hashCode()),
				Maps.of(Models.K_LLM_OPERATION, junk));
			if (!junk.equals("v/") && !junk.equals("n/")) assertNull(agent.lookup(Strings.create(junk)), junk);
			assertNull(Models.operation(agent.lookup(Strings.create(junk))), junk);
			assertNull(refusal(agent), junk);
			assertNull(cycleRefusal(agent), junk);
		}
	}

	@Test
	public void testAgentLooksUpNothingOfAnotherUser(TestInfo info) {
		User other = user(TestEngine.uniqueDID(info.getDisplayName() + "-other"));
		Agent agent = agent(user(TestEngine.uniqueDID(info)), "nosy", Maps.empty());
		assertNull(agent.lookup(ref(other.getDID(), "/o/tiny")));
		assertNull(agent.lookup(ref("did:key:z6MkUnknown/o/tiny")));
	}

	@Test
	public void testAgentWhoseModelCannotCallToolsTakesNoTask(TestInfo info) {
		User user = user(TestEngine.uniqueDID(info));
		Agent agent = agent(user, "tiny-worker", Maps.of(Models.K_LLM_OPERATION, "o/tiny"));
		assertEquals(Agent.MODEL_CANNOT_CALL_TOOLS, refusal(agent));

		NotSupportedException refused = assertThrows(NotSupportedException.class,
			() -> agent.addTaskInSession(Blob.fromHex("5e55"), user.getDID(), null,
				Blob.fromHex("7a5c"), Maps.of("input", "work"), reads(agent)));
		assertEquals(Agent.CANNOT_TAKE_TASKS.formatted("tiny-worker", Agent.MODEL_CANNOT_CALL_TOOLS),
			refused.getMessage());
		assertTrue(agent.getTasks().isEmpty(), "nothing is queued");
		assertTrue(agent.getSessions().isEmpty(), "no session is made for a refused task");
	}

	@Test
	public void testAgentTakesTasksUnlessItsModelCannotCallTools(TestInfo info) {
		User user = user(TestEngine.uniqueDID(info));
		assertNull(refusal(agent(user, "default", Maps.empty())), "the default model calls tools");
		assertNull(refusal(agent(user, "provider", Maps.of(Models.K_LLM_OPERATION, "v/test/ops/llm"))));
		assertNull(refusal(agent(user, "other-model", Maps.of(Models.K_LLM_OPERATION, "o/tiny", Models.K_MODEL, "large"))),
			"the definition speaks only for its own model");
		assertNull(refusal(agent(user, "unresolved", Maps.of(Models.K_LLM_OPERATION, "o/missing"))),
			"a model it cannot look up is not decided here");
		Agent ollama = agent(user, "ollama", Maps.of(Models.K_LLM_OPERATION, "v/ops/langchain/ollama"));
		assertNotNull(ollama.lookup(ref("v/ops/langchain/ollama")));
		assertNull(refusal(ollama), "tool support that must be probed is not declared off");
		assertEquals(Agent.MODEL_CANNOT_CALL_TOOLS, refusal(agent(user, "own-profile", Maps.of(
			Models.K_LLM_OPERATION, "v/test/ops/llm", Models.K_MODEL_PROFILE, NO_TOOL_CALLING))),
			"the agent's own profile");
	}

	/**
	 * The agent reads what the authority it runs under may read: an agent
	 * another principal controls acts with that controller's authority, so its
	 * owner's definitions are not read for it, though they still resolve.
	 */
	@Test
	public void testAnAgentReadsAsItsController(TestInfo info) {
		User alice = user(TestEngine.uniqueDID(info));
		User bob = user(TestEngine.uniqueDID(info.getDisplayName() + "-controller"));
		AMap<AString, ACell> owners = Maps.of(Models.K_LLM_OPERATION, "o/tiny");

		assertEquals(Agent.MODEL_CANNOT_CALL_TOOLS, refusal(agent(alice, "alices", owners)));
		Agent bobs = controlled(alice, "bobs", owners, bob.getDID());
		assertEquals(TINY, bobs.lookup(ref("o/tiny")), "the name resolves in its owner's namespace");
		assertFalse(reads(bobs).test(ref("o/tiny")), "its controller may not read the owner's definition");
		assertNull(refusal(bobs), "so it is not read for the agent");

		assertEquals(Agent.MODEL_CANNOT_CALL_TOOLS, refusal(controlled(alice, "venue-model", Maps.of(
			Models.K_LLM_OPERATION, "v/test/ops/llm", Models.K_MODEL_PROFILE, NO_TOOL_CALLING), bob.getDID())),
			"the venue's definitions are read for any controller");

		Agent controllers = controlled(alice, "controllers-model",
			Maps.of(Models.K_LLM_OPERATION, ref(bob.getDID(), "/o/tiny")), bob.getDID());
		assertNull(refusal(controllers), "its controller's own definition is not one the agent looks up");
		assertEquals(Agent.MODEL_CANNOT_CALL_TOOLS, cycleRefusal(controllers), "so its cycle decides it");
	}

	/** Intake never refuses a task its cycle, reading as the same harness, would not. */
	@Test
	public void testIntakeRefusesOnlyWhatItsCycleWould(TestInfo info) {
		User alice = user(TestEngine.uniqueDID(info));
		User bob = user(TestEngine.uniqueDID(info.getDisplayName() + "-controller"));
		AMap<AString, ACell> llm = Models.operation(ENGINE.resolveAsset(ref("v/test/ops/llm")).meta());
		String hash = llm.getHash().toHexString();
		String[] refs = {"o/tiny", alice.getDID() + "/o/tiny", bob.getDID() + "/o/tiny", "o/missing",
			"n/def", "v/test/ops/llm", ENGINE.getDIDString() + "/v/test/ops/llm", "a/" + hash, hash,
			"g/x", "w/x", "", "/", "//", "did:", "did:web:", "did:key:/o/tiny", "a/zz", "a/", "zz", "v/", "n/"};
		int n = 0;
		for (String r : refs) {
			AMap<AString, ACell> config = Maps.of(Models.K_LLM_OPERATION, r, Models.K_MODEL_PROFILE, NO_TOOL_CALLING);
			for (AString controller : new AString[] {null, bob.getDID()}) {
				Agent agent = controlled(alice, "ref-" + n++, config, controller);
				agent.workspace().write(CoviaPath.parse("def"), TINY);
				AString intake = refusal(agent);
				if (intake != null) assertEquals(intake, cycleRefusal(agent), r + " under " + controller);
			}
		}
	}
}
