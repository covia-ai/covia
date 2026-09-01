package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.adapter.agent.Skills;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.SecretStore;
import covia.venue.TestEngine;

/** Connection catalog, status secrecy, and standard skill integration. */
public class ConnectionsAdapterTest {

	private final Engine engine = TestEngine.ENGINE;
	private AString userDID;
	private RequestContext ctx;

	@BeforeEach
	public void setup(TestInfo info) {
		userDID = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(userDID);
	}

	private ACell invoke(String op, AMap<AString, ACell> input) throws Exception {
		return engine.jobs().invokeInternal(op, input, ctx).get(10, TimeUnit.SECONDS);
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> named(ACell result, String vectorKey, String provider) {
		AVector<ACell> values = RT.ensureVector(RT.getIn(result, vectorKey));
		assertNotNull(values, result.toString());
		for (long i = 0; i < values.count(); i++) {
			AMap<AString, ACell> value = (AMap<AString, ACell>) values.get(i);
			if (Strings.create(provider).equals(value.get(Strings.intern("provider")))) return value;
		}
		throw new AssertionError("No provider " + provider + " in " + result);
	}

	@Test
	public void testListIsDerivedFromProviderSkills() throws Exception {
		ACell result = invoke("v/ops/connections/list", Maps.empty());
		AVector<ACell> providers = RT.ensureVector(RT.getIn(result, "providers"));
		assertEquals(3, providers.count());
		assertEquals(Strings.create("hubspot"), RT.getIn(providers.get(0), "provider"));
		assertEquals(Strings.create("notion"), RT.getIn(providers.get(1), "provider"));
		assertEquals(Strings.create("slack"), RT.getIn(providers.get(2), "provider"));

		AMap<AString, ACell> notion = named(result, "providers", "notion");
		assertEquals(Strings.create("v/skills/connections/notion"), notion.get(Strings.intern("skill")));
		assertEquals(Strings.create("https://api.notion.com/v1"), RT.getIn(notion, "connection", "baseUrl"));
		assertEquals(Strings.create("s/NOTION_TOKEN"), RT.getIn(notion, "connection", "auth", "alternatives", 0L));
		assertEquals(CVMBool.TRUE, notion.get(Strings.intern("available")));
		assertTrue(RT.ensureVector(notion.get(Strings.intern("unavailableTools"))).isEmpty());
		assertTrue(RT.ensureVector(notion.get(Strings.intern("tools"))).contains(
			Strings.create("v/ops/connections/status")));
	}

	@Test
	public void testStatusChecksPresenceWithoutReturningSecret() throws Exception {
		ACell before = invoke("v/ops/connections/status", Maps.of("provider", "notion"));
		assertEquals(CVMBool.FALSE, named(before, "connections", "notion").get(Strings.intern("configured")));

		String plaintext = "never-return-this-notion-value";
		engine.getVenueState().users().ensure(userDID).secrets().store(
			"NOTION_TOKEN", plaintext, SecretStore.deriveKey(engine.getKeyPair()));

		ACell after = invoke("v/ops/connections/status", Maps.of("provider", "notion"));
		AMap<AString, ACell> notion = named(after, "connections", "notion");
		assertEquals(CVMBool.TRUE, notion.get(Strings.intern("configured")));
		assertEquals(Strings.create("s/NOTION_TOKEN"), RT.getIn(notion, "credentials", 0L, "ref"));
		assertEquals(CVMBool.TRUE, RT.getIn(notion, "credentials", 0L, "configured"));
		assertEquals(Strings.create("user"), RT.getIn(notion, "credentials", 0L, "source"));
		assertFalse(after.toString().contains(plaintext), "status must never materialise a secret value");
	}

	@Test
	public void testSlackCredentialRefsAreAlternatives() throws Exception {
		engine.getVenueState().users().ensure(userDID).secrets().store(
			"SLACK_USER_TOKEN", "xoxp-never-return", SecretStore.deriveKey(engine.getKeyPair()));
		ACell result = invoke("v/ops/connections/status", Maps.of("provider", "slack"));
		AMap<AString, ACell> slack = named(result, "connections", "slack");
		assertEquals(CVMBool.TRUE, slack.get(Strings.intern("configured")),
			"either documented Slack token configures the connection");
		AVector<ACell> credentials = RT.ensureVector(slack.get(Strings.intern("credentials")));
		assertEquals(2, credentials.count());
		assertEquals(CVMBool.FALSE, RT.getIn(credentials.get(0), "configured"));
		assertEquals(CVMBool.TRUE, RT.getIn(credentials.get(1), "configured"));
	}

	@Test
	public void testUnknownProviderAndAnonymousStatusFail() {
		assertThrows(ExecutionException.class,
			() -> invoke("v/ops/connections/status", Maps.of("provider", "missing")));
		assertThrows(ExecutionException.class, () -> engine.jobs().invokeInternal(
			"v/ops/connections/status", Maps.empty(), RequestContext.ANONYMOUS)
			.get(10, TimeUnit.SECONDS));
	}

	@Test
	public void testConnectionsUseOrdinarySkillSourcesAndTools() {
		Skills.ResolvedSkill entry = Skills.resolveRef(
			engine, ctx, Strings.create("v/skills/root/connections"));
		assertTrue(entry.skillsets().contains(Strings.create("v/skills/connections")));
		assertTrue(entry.toolOps().contains(Strings.create("v/ops/connections/list")));

		Skills.ResolvedSkill notion = Skills.resolveRef(
			engine, ctx, Strings.create("v/skills/connections/notion"));
		assertTrue(notion.toolOps().contains(Strings.create("v/ops/http/get")));
		assertTrue(notion.toolOps().contains(Strings.create("v/ops/connections/status")));
		assertNotNull(RT.getIn(engine.resolvePath(
			Strings.create("v/skills/connections/notion"), ctx), "connection"));
	}

	@Test
	public void testListReportsDisabledTransportWithoutChangingSkills() throws Exception {
		Engine isolated = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true)));
		try {
			Engine.addDemoAssets(isolated);
			assertTrue(isolated.disableAdapter("http"));
			RequestContext isolatedCtx = RequestContext.of(Strings.create("did:test:connections-disabled-http"));
			ACell result = isolated.jobs().invokeInternal(
				"v/ops/connections/list", Maps.empty(), isolatedCtx).get(10, TimeUnit.SECONDS);
			AMap<AString, ACell> notion = named(result, "providers", "notion");
			assertEquals(CVMBool.FALSE, notion.get(Strings.intern("available")));
			assertEquals(2, RT.ensureVector(notion.get(Strings.intern("unavailableTools"))).count());
			assertNotNull(isolated.resolvePath(Strings.create("v/skills/connections/notion"), isolatedCtx),
				"the canonical connection skill remains stable while liveness changes");
		} finally {
			isolated.close();
		}
	}
}
