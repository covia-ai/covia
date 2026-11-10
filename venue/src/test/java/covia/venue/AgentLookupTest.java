package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import covia.exception.NotSupportedException;
import covia.lattice.CoviaPath;

/**
 * An agent looks up the references in its config through its user and venue,
 * and refuses tasks when the model they name cannot call tools.
 */
public class AgentLookupTest {

	private static final Engine ENGINE = TestEngine.ENGINE;

	/** A model definition, held in its owner's {@code o/}, whose model cannot call tools. */
	private static final AMap<AString, ACell> TINY = Maps.of(
		"name", "Tiny",
		"operation", Maps.of("adapter", "test:llm"),
		"model", Maps.of("id", "tiny", "provider", "v/test/ops/llm",
			"options", Maps.of("toolCalling", CVMBool.FALSE)));

	private static User user(AString did) {
		User user = ENGINE.getVenueState().users().ensure(did);
		user.operations().write(CoviaPath.parse("tiny"), TINY);
		return user;
	}

	private static Agent agent(User user, String id, AMap<AString, ACell> config) {
		return user.createAgent(Strings.create(id), config, null);
	}

	private static AString ref(Object... parts) {
		StringBuilder sb = new StringBuilder();
		for (Object part : parts) sb.append(part);
		return Strings.create(sb.toString());
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
			assertNull(agent.taskRefusal(), junk);
			assertNull(Agent.taskRefusal(agent.getConfig(), ref -> {
				covia.grid.Asset asset = ENGINE.resolveAsset(ref, RequestContext.ofAgent(user.getDID(), agent.getAgentId()));
				return (asset == null) ? null : asset.meta();
			}), junk);
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
		assertEquals(Agent.MODEL_CANNOT_CALL_TOOLS, agent.taskRefusal());

		NotSupportedException refused = assertThrows(NotSupportedException.class,
			() -> agent.addTaskInSession(Blob.fromHex("5e55"), user.getDID(), null,
				Blob.fromHex("7a5c"), Maps.of("input", "work")));
		assertEquals(Agent.CANNOT_TAKE_TASKS.formatted("tiny-worker", Agent.MODEL_CANNOT_CALL_TOOLS),
			refused.getMessage());
		assertTrue(agent.getTasks().isEmpty(), "nothing is queued");
		assertTrue(agent.getSessions().isEmpty(), "no session is made for a refused task");
	}

	@Test
	public void testAgentTakesTasksUnlessItsModelCannotCallTools(TestInfo info) {
		User user = user(TestEngine.uniqueDID(info));
		assertNull(agent(user, "default", Maps.empty()).taskRefusal(), "the default model calls tools");
		assertNull(agent(user, "provider", Maps.of(Models.K_LLM_OPERATION, "v/test/ops/llm")).taskRefusal());
		assertNull(agent(user, "other-model", Maps.of(Models.K_LLM_OPERATION, "o/tiny", Models.K_MODEL, "large"))
			.taskRefusal(), "the definition speaks only for its own model");
		assertNull(agent(user, "unresolved", Maps.of(Models.K_LLM_OPERATION, "o/missing")).taskRefusal(),
			"a model it cannot look up is not decided here");
		Agent ollama = agent(user, "ollama", Maps.of(Models.K_LLM_OPERATION, "v/ops/langchain/ollama"));
		assertNotNull(ollama.lookup(ref("v/ops/langchain/ollama")));
		assertNull(ollama.taskRefusal(), "tool support that must be probed is not declared off");
		assertEquals(Agent.MODEL_CANNOT_CALL_TOOLS, agent(user, "own-profile", Maps.of(
			Models.K_LLM_OPERATION, "v/test/ops/llm",
			Models.K_MODEL_PROFILE, Maps.of("options", Maps.of("toolCalling", CVMBool.FALSE)))).taskRefusal(),
			"the agent's own profile");
	}
}
