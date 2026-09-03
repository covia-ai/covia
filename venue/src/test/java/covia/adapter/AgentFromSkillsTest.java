package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * {@code agent:from-skills} (covia#484) — the one-call migration wedge that
 * composes {@code skills:import} and {@code agent:create}: import an existing
 * agent's SKILL.md skills and stand up a native agent from them plus a system
 * prompt, in a single operation. Uses a bespoke engine for a host file root,
 * mirroring {@link SkillsImportTest}.
 */
public class AgentFromSkillsTest {

	@TempDir static Path work;
	private static Engine engine;
	private AString did;
	private RequestContext ctx;

	private static final AString K_AGENT_ID = Strings.intern("agentId");
	private static final AString K_SKILLS   = Strings.intern("skills");
	private static final AString K_SYSTEM_PROMPT = Strings.intern("systemPrompt");

	private static final String REFUND_SKILL = """
		---
		name: refund-policy
		description: How to handle customer refund requests within the 30-day window.
		tools:
		  - v/ops/covia/read
		---

		# Refund policy

		Refunds are allowed within 30 days of purchase.
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
			return engine.jobs().invokeInternal(op, input, ctx).get(15, TimeUnit.SECONDS);
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

	/** One call imports the skill, creates the agent, and reports both. */
	@Test
	public void testOneCallImportsSkillsAndCreatesAgent() {
		ACell out = call("v/ops/agent/from-skills", Maps.of(
			K_AGENT_ID, Strings.create("refund-bot"),
			K_SYSTEM_PROMPT, Strings.create(SYSTEM_PROMPT),
			K_SKILLS, Vectors.of(Strings.create("file://work/refund-policy/SKILL.md"))));

		// The result carries the created agent plus what was migrated in.
		assertEquals("refund-bot", str(out, "agentId"));
		assertNotNull(RT.getIn(out, "address"));
		assertEquals("w/skills", str(out, "skillset"));
		AVector<ACell> imported = RT.ensureVector(RT.getIn(out, "importedSkills"));
		assertNotNull(imported, "importedSkills must be reported");
		assertTrue(imported.contains(Strings.create("w/skills/refund-policy")),
			"the imported skill path is reported: " + imported);

		// The agent exists, carries the prompt, and indexes the skillset.
		ACell info = call("v/ops/agent/info", Maps.of(K_AGENT_ID, Strings.create("refund-bot")));
		assertEquals(SYSTEM_PROMPT, str(info, "config", "systemPrompt"));
		AVector<ACell> agentSkills = RT.ensureVector(RT.getIn(info, "config", "skills"));
		assertTrue(agentSkills.contains(Strings.create("w/skills")), "agent indexes the skillset: " + agentSkills);

		// And the migrated skill resolves in the agent's live index.
		List<Skills.SkillIndexEntry> index = Skills.listSkills(engine, ctx,
			Skills.SkillSources.ofSkillsets(agentSkills));
		assertTrue(index.stream().anyMatch(e -> "refund-policy".equals(e.name()) && e.error() == null),
			"the migrated skill resolves for the agent: " + index);
	}

	/** A skills entry may be an inline SKILL.md map, so a UI can port a pasted skill in one call. */
	@Test
	public void testAcceptsInlineSkillText() {
		String skillMd = "---\nname: greeting\ndescription: Say hello warmly.\n---\nAlways greet by name.\n";
		ACell out = call("v/ops/agent/from-skills", Maps.of(
			K_AGENT_ID, Strings.create("greeter"),
			K_SKILLS, Vectors.of(Maps.of(Strings.intern("text"), Strings.create(skillMd)))));
		AVector<ACell> imported = RT.ensureVector(RT.getIn(out, "importedSkills"));
		assertTrue(imported.contains(Strings.create("w/skills/greeting")),
			"the inline-text skill is imported: " + imported);

		ACell info = call("v/ops/agent/info", Maps.of(K_AGENT_ID, Strings.create("greeter")));
		List<Skills.SkillIndexEntry> index = Skills.listSkills(engine, ctx,
			Skills.SkillSources.ofSkillsets(RT.ensureVector(RT.getIn(info, "config", "skills"))));
		assertTrue(index.stream().anyMatch(e -> "greeting".equals(e.name()) && e.error() == null),
			"the inline-text skill resolves for the agent: " + index);
	}

	/** The composed op inherits agent:create's guard: an existing name is an error. */
	@Test
	public void testFailsWhenAgentNameAlreadyExists() {
		call("v/ops/agent/create", Maps.of(K_AGENT_ID, Strings.create("taken"),
			Fields.CONFIG, Maps.empty()));
		assertThrows(RuntimeException.class, () -> call("v/ops/agent/from-skills", Maps.of(
			K_AGENT_ID, Strings.create("taken"),
			K_SKILLS, Vectors.of(Strings.create("file://work/refund-policy/SKILL.md")))));
	}
}
