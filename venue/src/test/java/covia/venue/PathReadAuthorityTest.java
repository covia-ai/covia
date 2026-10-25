package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Status;

/**
 * Adversarial reads of another principal's paths. {@link Engine#resolvePath} is
 * the checked read every supplied reference goes through: naming a path in a
 * config layer, an agent definition, an operation reference or an A2A binding
 * never makes it readable, and no path shape escapes a granted prefix.
 */
public class PathReadAuthorityTest {

	private static final String SECRET = "ALICE-PRIVATE";

	final Engine engine = TestEngine.ENGINE;
	AKeyPair aliceKP, bobKP;
	AString alice, bob;
	RequestContext ALICE, BOB;

	@BeforeEach
	void setup() {
		aliceKP = AKeyPair.generate();
		bobKP = AKeyPair.generate();
		alice = UCAN.toDIDKey(aliceKP.getAccountKey());
		bob = UCAN.toDIDKey(bobKP.getAccountKey());
		ALICE = RequestContext.of(alice);
		BOB = RequestContext.of(bob);
		write("w/cfg", Maps.of("systemPrompt", SECRET), ALICE);
		write("w/public/note", Maps.of("systemPrompt", "PUBLIC"), ALICE);
		write("o/priv", Maps.of("name", "priv", "description", SECRET,
			"operation", Maps.of("adapter", "test:echo")), ALICE);
		write("w/scratch", Strings.create("bob exists"), BOB);
	}

	private void write(String path, ACell value, RequestContext ctx) {
		assertEquals(Status.COMPLETE, run("v/ops/covia/write",
			Maps.of(Fields.PATH, path, Fields.VALUE, value), ctx).getStatus());
	}

	/** Runs an operation; a refusal before any Job exists becomes a failed Job. */
	private Job run(String op, ACell input, RequestContext ctx) {
		Job job;
		try {
			job = engine.jobs().invokeOperation(op, input, ctx);
		} catch (RuntimeException refused) {
			return Job.failure(String.valueOf(refused.getMessage()));
		}
		try { job.awaitResult(10000); } catch (Exception ignored) {}
		return job;
	}

	private RequestContext bobWith(String with, AString can) {
		long exp = System.currentTimeMillis() / 1000 + 3600;
		return BOB.withProofs(Vectors.of(UCAN.create(aliceKP, bobKP.getAccountKey(), exp,
			Vectors.of(Capability.create(Strings.create(with), can)), Vectors.empty()).toMap()));
	}

	private static void assertReadDenied(Job job) {
		assertEquals(Status.FAILED, job.getStatus(), "expected a refusal");
		assertTrue(job.getErrorMessage().contains(Engine.READ_DENIED), job.getErrorMessage());
	}

	private ACell agentPrompt(AString owner, String id) {
		var user = engine.getVenueState().users().get(owner);
		AgentState agent = (user == null) ? null : user.agent(id);
		return (agent == null) ? null : RT.getIn(agent.getConfig(), "systemPrompt");
	}

	private Job createAgent(String id, ACell config, RequestContext ctx) {
		return run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id, Fields.CONFIG, config), ctx);
	}

	// ========== naming a path does not make it readable ==========

	@Test
	public void testConfigReferenceNeedsRead() {
		assertReadDenied(createAgent("copy", Strings.create(alice + "/w/cfg"), BOB));
		assertNull(agentPrompt(bob, "copy"));

		RequestContext granted = bobWith(alice + "/w/cfg", Capability.CRUD_READ);
		assertEquals(Status.COMPLETE, createAgent("copy", Strings.create(alice + "/w/cfg"), granted).getStatus());
		assertEquals(Strings.create(SECRET), agentPrompt(bob, "copy"), "a read grant admits the reference");
	}

	@Test
	public void testEveryConfigLayerIsChecked() {
		// A layer in an ordered array, and one reached through Bob's own layer
		assertReadDenied(createAgent("layered", Vectors.of(
			Strings.create("v/agents/templates/minimal"), Strings.create(alice + "/w/cfg")), BOB));
		write("w/layer", Strings.create(alice + "/w/cfg"), BOB);
		assertReadDenied(createAgent("indirect", Strings.create("w/layer"), BOB));
		assertNull(agentPrompt(bob, "layered"));
		assertNull(agentPrompt(bob, "indirect"));
	}

	@Test
	public void testDefinitionReferenceNeedsRead() {
		assertReadDenied(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "defined",
			Fields.DEFINITION, Strings.create(alice + "/w/cfg")), BOB));
		assertNull(agentPrompt(bob, "defined"));
	}

	@Test
	public void testInvokingAPrivateOperationNeedsRead() {
		String op = alice + "/o/priv";
		assertReadDenied(run(op, Maps.of("echo", "x"), BOB));
		Job granted = run(op, Maps.of("echo", "x"), bobWith(op, Capability.CRUD_READ));
		assertEquals(Status.COMPLETE, granted.getStatus(), granted.getErrorMessage());
	}

	@Test
	public void testDIDQualifiedAssetNeedsRead() {
		Job stored = run("v/ops/asset/store", Maps.of("metadata",
			Maps.of("name", "private asset", "description", SECRET)), ALICE);
		AString id = RT.ensureString(RT.getIn(stored.getOutput(), "id"));
		assertNotNull(id);
		AString hash = Strings.create(covia.adapter.AssetAdapter.parseAssetId(id).toHexString());

		AString qualified = Strings.create(alice + "/a/" + hash);
		AuthException denied = assertThrows(AuthException.class, () -> engine.resolvePath(qualified, BOB));
		assertTrue(denied.getMessage().contains(Engine.READ_DENIED), denied.getMessage());
		assertReadDenied(createAgent("from-asset", qualified, BOB));
		// A bare hash names Bob's own store, then the venue catalog: not Alice's
		assertNull(engine.resolvePath(hash, BOB));
		assertNotNull(engine.resolvePath(qualified, bobWith(alice + "/a/", Abilities.ASSET_READ)),
			"asset/read admits the content-addressed reference");
	}

	@Test
	public void testA2ABindingReferenceNeedsRead() {
		write("w/a2a/agents/x", Maps.of("name", "x", "url", "https://agent.invalid/a2a"), ALICE);
		assertReadDenied(run("v/ops/a2a/agent-card",
			Maps.of("agent", Strings.create(alice + "/w/a2a/agents/x")), BOB));
	}

	@Test
	public void testContextEntryNeedsRead() {
		Job created = createAgent("ctx-probe", Maps.of(
			Fields.OPERATION, "v/ops/llmagent/chat", "llmOperation", "v/test/ops/toolllm",
			"context", Vectors.of(Maps.of("ref", Strings.create(alice + "/w/cfg")))), BOB);
		assertEquals(Status.COMPLETE, created.getStatus(), created.getErrorMessage());
		Job context = run("v/ops/agent/context", Maps.of(Fields.AGENT_ID, "ctx-probe"), BOB);
		assertFalse(String.valueOf(context.getOutput()).contains(SECRET),
			"a context entry must not render a path its agent cannot read");
	}

	// ========== path shapes ==========

	@Test
	public void testDotSegmentsDoNotEscapeAGrantedPrefix() {
		RequestContext publicOnly = bobWith(alice + "/w/public/", Capability.CRUD_READ);
		assertNotNull(engine.resolvePath(Strings.create(alice + "/w/public/note"), publicOnly));
		for (String escape : new String[] {
				"/w/public/../cfg", "/w/public/./../cfg", "/w/public//../cfg",
				"/w/public/%2e%2e/cfg", "/w/public/..%2fcfg"}) {
			AString ref = Strings.create(alice + escape);
			ACell value;
			try {
				value = engine.resolvePath(ref, publicOnly);
			} catch (RuntimeException refused) {
				continue;   // refusal is as good as absence
			}
			assertFalse(String.valueOf(value).contains(SECRET), escape + " escaped the grant: " + value);
			Job copy = createAgent("escape", ref, publicOnly);
			assertFalse(String.valueOf(agentPrompt(bob, "escape")).contains(SECRET), escape + " " + copy.getErrorMessage());
		}
	}

	@Test
	public void testOddShapesStayInTheirNamespace() {
		for (String odd : new String[] {
				alice + "//w/cfg", alice + "/w//cfg", alice + "/./w/cfg", "/" + alice + "/w/cfg", alice + "/W/cfg"}) {
			ACell value;
			try {
				value = engine.resolvePath(Strings.create(odd), BOB);
			} catch (RuntimeException refused) {
				continue;
			}
			assertFalse(String.valueOf(value).contains(SECRET), odd + " read Alice's data: " + value);
		}
	}

	// ========== a capability scope bounds the agent's own namespace too ==========

	@Test
	public void testScopedAgentCannotReadOwnPathsOutsideItsCaps() {
		write("w/data/cfg", Maps.of("systemPrompt", "DATA",
			"caps", Vectors.of(Capability.create(Strings.create("w/data/"), Capability.CRUD_READ))), ALICE);
		RequestContext parent = RequestContext.ofAgent(alice, Strings.create("parent")).withCaps(Vectors.of(
			Capability.create(Strings.create("g/"), Abilities.AGENT_CREATE),
			Capability.create(Strings.create("w/data/"), Capability.CRUD),
			Maps.of(Capability.CAN, Strings.create("invoke"))));
		assertReadDenied(createAgent("peek", Strings.create("w/cfg"), parent));
		assertNull(agentPrompt(alice, "peek"));
		Job within = createAgent("within", Strings.create("w/data/cfg"), parent);
		assertEquals(Status.COMPLETE, within.getStatus(), within.getErrorMessage());
		assertEquals(Strings.create("DATA"), agentPrompt(alice, "within"));
	}

	@Test
	public void testOwnerReadsFreely() {
		Job own = createAgent("mine", Strings.create("w/cfg"), ALICE);
		assertEquals(Status.COMPLETE, own.getStatus(), own.getErrorMessage());
		assertEquals(Strings.create(SECRET), agentPrompt(alice, "mine"));
		AMap<AString, ACell> meta = RT.ensureMap(engine.resolvePath(Strings.create(alice + "/o/priv"), ALICE));
		assertNotNull(meta);
	}
}
