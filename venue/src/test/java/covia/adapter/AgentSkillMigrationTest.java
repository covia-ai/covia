package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.agent.Skills;
import covia.api.Fields;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * M1 migration wedge (covia#484, epic #481): a source agent's <b>system
 * prompt</b> plus its <b>SKILL.md skills</b> become a native Covia agent, using
 * only the primitives that already exist — {@code skills:import} then
 * {@code agent:create}, no new plumbing. The wedge's claim is that the created
 * agent then <em>resolves</em> the migrated skill in its live skills index, so
 * it thinks on Covia's own loop with the ported know-how in scope.
 *
 * <p>Uses a bespoke engine because the import source is a host {@code file://}
 * root, mirroring {@link SkillsImportTest}.</p>
 */
public class AgentSkillMigrationTest {

	@TempDir static Path work;
	private static Engine engine;
	private AString did;
	private RequestContext ctx;

	private static final AString K_SOURCE   = Fields.SOURCE;
	private static final AString K_AGENT_ID = Strings.intern("agentId");
	private static final AString K_CONFIG   = Strings.intern("config");
	private static final AString K_SKILLSETS = Skills.K_SKILLSETS;

	/** A realistic ported skill: Anthropic Agent Skills frontmatter + a tool the skill declares. */
	private static final String REFUND_SKILL = """
		---
		name: refund-policy
		description: How to handle customer refund requests within the 30-day window.
		tools:
		  - v/ops/covia/read
		---

		# Refund policy

		Refunds are allowed within 30 days of purchase. Inside the window, approve and
		tell the customer it takes 5-7 business days; outside it, offer store credit.
		""";

	private static final String SYSTEM_PROMPT =
		"You are Acme's support agent. Follow the refund policy skill exactly.";

	@BeforeAll
	static void setup() throws IOException {
		engine = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			"file", Maps.of("roots", Maps.of("work", work.toAbsolutePath().toString()))));
		Engine.addDemoAssets(engine);
		Files.createDirectories(work.resolve("refund-policy"));
		Files.writeString(work.resolve("refund-policy/SKILL.md"), REFUND_SKILL);
	}

	@AfterAll
	static void teardown() {
		engine.close();
	}

	@BeforeEach
	public void setupContext(TestInfo info) {
		did = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(did);
	}

	private ACell call(String op, AMap<AString, ACell> input) {
		try {
			return engine.jobs().invokeInternal(op, input, ctx).get(10, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			throw (e.getCause() instanceof RuntimeException re) ? re : new RuntimeException(e.getCause());
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static String str(ACell v, Object... path) {
		AString s = RT.ensureString(RT.getIn(v, path));
		return (s != null) ? s.toString() : null;
	}

	/**
	 * The whole wedge, end to end: import one SKILL.md, create a native agent
	 * from that skillset plus a system prompt, and confirm the agent both
	 * carries the config and resolves the migrated skill in its live index.
	 */
	@Test
	public void testMigrateSkillAndSystemPromptIntoNativeAgent() {
		// 1. Ingest the source agent's SKILL.md as a native Covia skill.
		ACell imported = call("v/ops/skills/import", Maps.of(K_SOURCE,
			Strings.create("file://work/refund-policy/SKILL.md")));
		assertEquals("w/skills/refund-policy", str(imported, "path"));
		assertEquals("refund-policy", str(imported, "name"));
		assertEquals(false, RT.bool(RT.getIn(imported, Strings.intern("existed"))));

		// 2. Create a native agent from a system prompt + the imported skillset —
		//    the two migration inputs, composed with no bespoke migration code.
		AMap<AString, ACell> config = Maps.of(
			Strings.intern("operation"), Strings.create("v/ops/llmagent/chat"),
			Strings.intern("systemPrompt"), Strings.create(SYSTEM_PROMPT),
			K_SKILLSETS, Vectors.of(Strings.create("w/skills")));
		ACell created = call("v/ops/agent/create", Maps.of(
			K_AGENT_ID, Strings.create("refund-bot"), K_CONFIG, config));
		assertEquals("refund-bot", str(created, "agentId"));
		assertNotNull(RT.getIn(created, "address"));

		// 3. The native agent carries exactly what was migrated.
		ACell info = call("v/ops/agent/info", Maps.of(K_AGENT_ID, Strings.create("refund-bot")));
		assertEquals(SYSTEM_PROMPT, str(info, "config", "systemPrompt"));
		assertEquals("v/ops/llmagent/chat", str(info, "config", "operation"));
		AMap<AString, ACell> agentConfig = RT.ensureMap(RT.getIn(info, "config"));
		AVector<ACell> agentSkillsets = RT.ensureVector(agentConfig.get(K_SKILLSETS));
		assertNotNull(agentSkillsets, "migrated agent must declare a skillset");
		assertTrue(agentSkillsets.contains(Strings.create("w/skills")),
			"agent config declares the skillset the skill was imported into: " + agentSkillsets);

		// 4. The wedge's real claim: through the discovery surface the runtime
		//    builds from the agent's own config, the migrated skill is in scope.
		List<Skills.SkillIndexEntry> index = Skills.listSkills(engine, ctx,
			Skills.sourcesOf(agentConfig));
		Skills.SkillIndexEntry refund = index.stream()
			.filter(e -> "refund-policy".equals(e.name())).findFirst().orElse(null);
		assertNotNull(refund, "the migrated skill must resolve in the agent's index: " + index);
		assertFalse(refund.error() != null && !refund.error().isEmpty(),
			"migrated skill resolves without error: " + refund.error());
	}
}
