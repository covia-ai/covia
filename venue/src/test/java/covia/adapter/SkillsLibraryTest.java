package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.agent.ContextBuilder;
import covia.adapter.agent.Skills;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * The venue skill library (SKILLS.md §9): every shipped skill materialises at
 * {@code v/skills/<name>}, resolves with a real body, declares only tools that
 * exist on the venue (the drift guard — a library skill must never ship a
 * dangling op ref), renders into a compact index, and reaches out-of-the-box
 * agents via the standard templates' {@code config.skills}.
 */
public class SkillsLibraryTest {

	private final Engine engine = TestEngine.ENGINE;
	private RequestContext ctx;

	@BeforeEach
	public void setup(TestInfo info) {
		ctx = RequestContext.of(TestEngine.uniqueDID(info));
	}

	@Test
	public void testLibraryMaterialised() {
		for (String name : SkillsAdapter.LIBRARY) {
			ACell value = engine.resolvePath(Strings.create("v/skills/" + name), ctx);
			assertTrue(value instanceof AMap, "v/skills/" + name + " should materialise: " + value);
		}
	}

	@Test
	public void testEverySkillResolvesWithBody() {
		for (String name : SkillsAdapter.LIBRARY) {
			Skills.ResolvedSkill s = Skills.resolveRef(engine, ctx, Strings.create("v/skills/" + name));
			assertEquals(name, s.name());
			assertNotNull(s.description());
			assertFalse(s.description().isBlank(), name);
			assertNotNull(s.body(), name + " must ship a body (content.inline)");
			assertTrue(s.body().length() > 200, name + " body should be substantive");
			assertTrue(s.toolOps().count() > 0, name + " should declare tools");
		}
	}

	@Test
	public void testEveryDeclaredToolResolves() {
		// The drift guard: renaming or removing a catalog op must fail this
		// test, not silently strip a tool from a shipped skill.
		for (String name : SkillsAdapter.LIBRARY) {
			Skills.ResolvedSkill s = Skills.resolveRef(engine, ctx, Strings.create("v/skills/" + name));
			for (long i = 0; i < s.toolOps().count(); i++) {
				AString op = RT.ensureString(s.toolOps().get(i));
				assertNotNull(engine.resolveAsset(op, ctx),
					"skill '" + name + "' declares unresolvable tool: " + op);
			}
		}
	}

	@Test
	public void testIndexRendersAllAndCompact() {
		String index = Skills.renderIndex(engine, ctx,
			Vectors.of((ACell) Strings.create("v/skills")), null, true);
		assertNotNull(index);
		for (String name : SkillsAdapter.LIBRARY) {
			assertTrue(index.contains("- " + name + " — "), "index missing " + name + ":\n" + index);
		}
		assertFalse(index.contains("INVALID"), index);
		assertFalse(index.contains("unavailable"), index);

		// Context-efficiency guard, computed over the LIBRARY resources alone —
		// deterministic under the shared engine (other tests may legitimately
		// write extra venue skills; their lines must not fail the library's
		// budget, nor mask real description creep).
		StringBuilder libIndex = new StringBuilder();
		for (String name : SkillsAdapter.LIBRARY) {
			ACell meta = convex.core.util.JSON.parse(readResource("/skills/" + name + ".json"));
			libIndex.append("- ").append(name).append(" — ")
				.append(RT.ensureString(RT.getIn(meta, "description"))).append('\n');
		}
		// Per-skill budget: the bound scales with deliberate library growth
		// while still catching description creep on individual skills.
		int budget = SkillsAdapter.LIBRARY.length * 170;
		assertTrue(libIndex.length() < budget,
			"library index should stay compact (" + libIndex.length() + "/" + budget
				+ " chars):\n" + libIndex);
	}

	private static String readResource(String path) {
		try (java.io.InputStream is = SkillsLibraryTest.class.getResourceAsStream(path)) {
			return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new RuntimeException("cannot read " + path, e);
		}
	}

	@Test
	public void testTemplatesDeclareRoleAppropriateSkillSources() {
		// Every template keeps operator-authored skills first. General templates
		// see the whole venue library; specialists use stable single-skill paths
		// so they do not pay for an irrelevant 22-line index on every inference.
		for (String t : new String[] {"minimal", "skilled", "reader", "worker", "analyst",
				"manager", "goaltree", "full"}) {
			ACell asset = engine.resolvePath(Strings.create("v/agents/templates/" + t), ctx);
			ACell config = RT.getIn(asset, "agent", "config");
			AVector<ACell> sources = RT.ensureVector(RT.getIn(config, "skills"));
			assertNotNull(sources, t + " template should declare skills sources");
			assertEquals("w/skills", sources.get(0).toString());   // user skills shadow venue skills
			if (java.util.Set.of("minimal", "skilled", "goaltree", "full").contains(t)) {
				assertEquals(2, sources.count(), t);
				assertEquals("v/skills", sources.get(1).toString());
			} else {
				assertTrue(sources.count() > 2, t + " should have a curated venue subset");
				for (long i = 1; i < sources.count(); i++) {
					assertTrue(sources.get(i).toString().startsWith("v/skills/"),
						t + " source should be one named venue skill: " + sources.get(i));
				}
			}
		}
	}

	@Test
	public void testEveryTemplateOperationToolResolves() {
		java.util.Set<String> harness = java.util.Set.of(
			"subgoal", "complete", "fail", "compact", "context_load",
			"context_unload", "more_tools", "skill_load");
		for (String template : new String[] {"minimal", "skilled", "reader", "worker",
				"analyst", "manager", "goaltree", "full"}) {
			ACell asset = engine.resolvePath(
				Strings.create("v/agents/templates/" + template), ctx);
			AVector<ACell> tools = RT.ensureVector(
				RT.getIn(asset, "agent", "config", "tools"));
			if (tools == null) continue;
			for (long i = 0; i < tools.count(); i++) {
				AString op = RT.ensureString(tools.get(i));
				assertNotNull(op, template + " tool entry " + i);
				if (harness.contains(op.toString())) continue;
				assertNotNull(engine.resolveAsset(op, ctx),
					template + " declares unresolvable tool: " + op);
			}
		}
	}

	@Test
	public void testStandardSkillGuidanceMatchesCurrentAgentContract() {
		String agents = skillBody("agents");
		String tasks = skillBody("tasks");
		String models = skillBody("models");
		String skills = skillBody("skills");
		assertFalse(agents.contains("created: false"), agents);
		assertFalse(agents.contains("wait?"), agents);
		assertFalse(tasks.contains("wait?"), tasks);
		assertTrue(tasks.contains("timeout?"), tasks);
		assertTrue(models.contains("Templates are provider-neutral"), models);
		assertFalse(models.contains("templates default to openai"), models);
		assertTrue(skills.contains("Do not invent an undeclared tool name"), skills);

		ACell secretsMeta = convex.core.util.JSON.parse(readResource("/skills/secrets.json"));
		AVector<ACell> secretTools = RT.ensureVector(
			RT.getIn(secretsMeta, "skill", "tools"));
		assertEquals(Vectors.of((ACell) Strings.create("v/ops/secret/set")), secretTools,
			"ordinary secrets skill must not advertise plaintext extraction");
	}

	@Test
	public void testResidentGuidanceDoesNotEmbedProviderToolAliases() {
		// Provider-facing names are presentation aliases resolved with the tool
		// palette. Durable skill/template prose must not depend on them: an alias
		// may change through operation metadata, a config name override, or a
		// provider adapter. Canonical operation refs remain in skill.tools/config.tools.
		Set<String> aliases = new HashSet<>(Set.of(
			"context_load", "context_unload", "skill_load", "more_tools",
			"complete_task", "fail_task"));

		for (String name : SkillsAdapter.LIBRARY) {
			Skills.ResolvedSkill skill = Skills.resolveRef(
				engine, ctx, Strings.create("v/skills/" + name));
			for (long i = 0; i < skill.toolOps().count(); i++) {
				addProviderAlias(aliases, RT.ensureString(skill.toolOps().get(i)));
			}
		}

		String[] templates = {"minimal", "skilled", "reader", "worker",
			"analyst", "manager", "goaltree", "full"};
		for (String template : templates) {
			ACell meta = convex.core.util.JSON.parse(
				readResource("/agent-templates/" + template + ".json"));
			AVector<ACell> tools = RT.ensureVector(
				RT.getIn(meta, "agent", "config", "tools"));
			if (tools != null) {
				for (long i = 0; i < tools.count(); i++) {
					AString ref = RT.ensureString(tools.get(i));
					if (ref != null && ref.toString().startsWith("v/ops/")) {
						addProviderAlias(aliases, ref);
					}
				}
			}
		}

		for (String name : SkillsAdapter.LIBRARY) {
			assertNoProviderAlias("skill '" + name + "'", skillBody(name), aliases);
		}
		for (String template : templates) {
			ACell meta = convex.core.util.JSON.parse(
				readResource("/agent-templates/" + template + ".json"));
			AString prompt = RT.ensureString(
				RT.getIn(meta, "agent", "config", "systemPrompt"));
			if (prompt != null) {
				assertNoProviderAlias("template '" + template + "'", prompt.toString(), aliases);
			}
		}
	}

	private void addProviderAlias(Set<String> aliases, AString operationRef) {
		assertNotNull(operationRef);
		var asset = engine.resolveAsset(operationRef, ctx);
		assertNotNull(asset, "cannot derive tool name for " + operationRef);
		AString explicitName = RT.ensureString(
			RT.getIn(asset.meta(), "operation", "toolName"));
		AString adapter = RT.ensureString(
			RT.getIn(asset.meta(), "operation", "adapter"));
		String alias = ContextBuilder.deriveToolName(null, explicitName,
			(adapter != null) ? adapter : operationRef);
		// Bare words such as "memory" are also ordinary prose. Generated
		// namespaced aliases are the brittle identifiers this guard targets.
		if (alias.contains("_")) aliases.add(alias);
	}

	private static void assertNoProviderAlias(
			String label, String prose, Set<String> aliases) {
		for (String alias : aliases) {
			Pattern token = Pattern.compile(
				"(?<![A-Za-z0-9_-])" + Pattern.quote(alias) + "(?![A-Za-z0-9_-])");
			assertFalse(token.matcher(prose).find(),
				label + " hard-codes provider-facing tool alias '" + alias + "':\n" + prose);
		}
	}

	private static String skillBody(String name) {
		ACell meta = convex.core.util.JSON.parse(readResource("/skills/" + name + ".json"));
		return RT.ensureString(RT.getIn(meta, "content", "inline")).toString();
	}

	@Test
	public void testNamedSkillPathIsAFirstClassSource() {
		AVector<ACell> sources = Vectors.of(
			(ACell) Strings.create("v/skills/models"),
			(ACell) Strings.create("v/skills/tasks"));
		String index = Skills.renderIndex(engine, ctx, sources, null, true);
		assertTrue(index.contains("- models — "), index);
		assertTrue(index.contains("- tasks — "), index);
		assertFalse(index.contains("- workspace — "), index);
		assertEquals("models", Skills.resolveByName(engine, ctx, sources, "models").name());
	}

	@Test
	public void testSpecialistTemplateIndexesStayCurated() {
		String fullIndex = Skills.renderIndex(engine, ctx,
			Vectors.of((ACell) Strings.create("v/skills")), null, true);
		java.util.Map<String, java.util.Set<String>> expected = java.util.Map.of(
			"reader", java.util.Set.of("discovery", "provenance", "assets", "skills"),
			"worker", java.util.Set.of("workspace", "files", "assets", "provenance"),
			"analyst", java.util.Set.of("workspace", "discovery", "provenance", "orchestration", "assets"),
			"manager", java.util.Set.of("agents", "tasks", "models", "grid",
				"scheduling", "hitl", "auth", "provenance"));
		for (var entry : expected.entrySet()) {
			ACell asset = engine.resolvePath(
				Strings.create("v/agents/templates/" + entry.getKey()), ctx);
			AVector<ACell> sources = RT.ensureVector(
				RT.getIn(asset, "agent", "config", "skills"));
			String index = Skills.renderIndex(engine, ctx, sources, null, true);
			for (String skill : entry.getValue()) {
				assertTrue(index.contains("- " + skill + " — "),
					entry.getKey() + " missing " + skill + ":\n" + index);
			}
			assertFalse(index.contains("- convex — "), entry.getKey() + ":\n" + index);
			assertTrue(index.length() < fullIndex.length() * 3 / 4,
				entry.getKey() + " index should be materially smaller than the full library ("
					+ index.length() + "/" + fullIndex.length() + ")");
		}
	}

	@Test
	public void testReaderTemplateIsCapabilityPinned() {
		// reader's read-only-ness is ENFORCED, not prompt fiction: the template
		// pins crud/read + asset/read, so a loaded write tool is denied at
		// invocation (verified live — skill loading grants no authority).
		ACell asset = engine.resolvePath(Strings.create("v/agents/templates/reader"), ctx);
		ACell config = RT.getIn(asset, "agent", "config");
		AVector<ACell> caps = RT.ensureVector(RT.getIn(config, "caps"));
		assertNotNull(caps, "reader must pin a capability");
		assertEquals(2, caps.count());
		assertEquals("crud/read", RT.getIn(caps.get(0), "can").toString());
		assertEquals("asset/read", RT.getIn(caps.get(1), "can").toString());
	}

	@Test
	public void testSkilledTemplateShape() {
		ACell skilledAsset = engine.resolvePath(Strings.create("v/agents/templates/skilled"), ctx);
		ACell config = RT.getIn(skilledAsset, "agent", "config");
		assertNotNull(config, "skilled template should materialise");
		AVector<ACell> tools = RT.ensureVector(RT.getIn(config, "tools"));
		assertEquals(3, tools.count(), "lean base: inspect + read + list");
		assertTrue(tools.contains(Strings.create("v/ops/covia/inspect")));
		// minimal holds NO op tools — skills discovery is its entire surface
		ACell minimalAsset = engine.resolvePath(Strings.create("v/agents/templates/minimal"), ctx);
		ACell minimal = RT.getIn(minimalAsset, "agent", "config");
		assertNull(RT.getIn(minimal, "tools"));
	}
}
