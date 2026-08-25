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
import covia.adapter.agent.ToolPalette;
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

	/**
	 * Every skill shipped in covia.jar: the platform skills
	 * ({@link SkillsAdapter#LIBRARY}) plus each adapter's own, found by what
	 * the active adapters declared under {@code v/skills/}. Adapter-owned so
	 * that a skill lives and dies with its adapter — this enumeration is what
	 * keeps that honest.
	 */
	private java.util.List<String> shippedSkills() {
		java.util.List<String> names = new java.util.ArrayList<>();
		for (String adapterName : engine.getAdapterNames()) {
			AAdapter adapter = engine.getAdapter(adapterName);
			if (adapter == null) continue;
			for (String path : adapter.pendingCatalogEntries.keySet()) {
				if (path.startsWith("v/skills/")) names.add(path.substring("v/skills/".length()));
			}
		}
		java.util.Collections.sort(names);
		return names;
	}

	/** The skill's own name — the last segment of its {@code <skillset>/<name>} path. */
	private static String skillName(String relPath) {
		return relPath.substring(relPath.lastIndexOf('/') + 1);
	}

	/**
	 * Every shipped skill once, keyed by name. An entry-point skill is
	 * installed twice (its family and the {@code root/} mirror) from the same
	 * resource, so both addresses hold the same content — deduping by name
	 * here mirrors what the index itself does.
	 */
	private java.util.List<String> shippedSkillNames() {
		java.util.Set<String> unique = new java.util.TreeSet<>();
		for (String rel : shippedSkills()) unique.add(skillName(rel));
		return new java.util.ArrayList<>(unique);
	}

	/** Every skillset the active adapters declare. */
	private java.util.List<String> shippedSkillsets() {
		java.util.Set<String> sets = new java.util.TreeSet<>();
		for (String rel : shippedSkills()) {
			sets.add("v/skills/" + rel.substring(0, rel.lastIndexOf('/')));
		}
		return new java.util.ArrayList<>(sets);
	}

	/** A source over the whole shipped library — every skillset at once. */
	private Skills.SkillSources everySkillset() {
		AVector<ACell> sets = Vectors.empty();
		for (String set : shippedSkillsets()) sets = sets.conj(Strings.create(set));
		return Skills.SkillSources.ofSkillsets(sets);
	}

	@BeforeEach
	public void setup(TestInfo info) {
		ctx = RequestContext.of(TestEngine.uniqueDID(info));
	}

	@Test
	public void testEveryAdapterSkillIsOwnedByItsAdapterAndPlatformSetIsSmall() {
		java.util.List<String> all = shippedSkills();
		assertTrue(all.size() >= 24, "expected the full shipped set, got " + all);
		// Adapter skills belong to their adapters, not to SkillsAdapter
		AAdapter skills = engine.getAdapter("skills");
		java.util.Set<String> platformNames = new java.util.HashSet<>();
		for (String path : skills.pendingCatalogEntries.keySet()) {
			if (path.startsWith("v/skills/")) platformNames.add(skillName(path));
		}
		for (String platform : SkillsAdapter.LIBRARY) {
			assertTrue(platformNames.contains(platform), platform + " is a platform skill");
		}
		assertTrue(engine.getAdapter("grid").pendingCatalogEntries.containsKey("v/skills/grid/grid"),
			"grid owns its skill, inside its skillset");
		assertTrue(engine.getAdapter("hitl").pendingCatalogEntries.containsKey("v/skills/agents/hitl"),
			"hitl owns its skill");
		assertFalse(platformNames.contains("grid"), "SkillsAdapter no longer carries adapter skills");
	}

	@Test
	public void testLibraryMaterialised() {
		for (String rel : shippedSkills()) {
			ACell value = engine.resolvePath(Strings.create("v/skills/" + rel), ctx);
			assertTrue(value instanceof AMap, "v/skills/" + rel + " should materialise: " + value);
		}
	}

	@Test
	public void testEverySkillResolvesWithBody() {
		for (String rel : shippedSkills()) {
			Skills.ResolvedSkill s = Skills.resolveRef(engine, ctx, Strings.create("v/skills/" + rel));
			String name = skillName(rel);
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
		for (String rel : shippedSkills()) {
			Skills.ResolvedSkill s = Skills.resolveRef(engine, ctx, Strings.create("v/skills/" + rel));
			for (long i = 0; i < s.toolOps().count(); i++) {
				AString op = RT.ensureString(s.toolOps().get(i));
				assertNotNull(engine.resolveAsset(op, ctx),
					"skill '" + rel + "' declares unresolvable tool: " + op);
			}
		}
	}

	@Test
	public void testIndexRendersAllAndCompact() {
		String index = Skills.renderIndex(engine, ctx, everySkillset(), null, true);
		assertNotNull(index);
		for (String name : shippedSkillNames()) {
			assertTrue(index.contains("- " + name + " — "), "index missing " + name + ":\n" + index);
		}
		assertFalse(index.contains("INVALID"), index);
		assertFalse(index.contains("unavailable"), index);

		// Context-efficiency guard, computed over the LIBRARY resources alone —
		// deterministic under the shared engine (other tests may legitimately
		// write extra venue skills; their lines must not fail the library's
		// budget, nor mask real description creep).
		StringBuilder libIndex = new StringBuilder();
		for (String name : shippedSkillNames()) {
			ACell meta = convex.core.util.JSON.parse(readResource("/skills/" + name + ".json"));
			libIndex.append("- ").append(name).append(" — ")
				.append(RT.ensureString(RT.getIn(meta, "description"))).append('\n');
		}
		// Per-skill budget: the bound scales with deliberate library growth
		// while still catching description creep on individual skills.
		int budget = shippedSkillNames().size() * 170;
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
			AVector<ACell> sets = RT.ensureVector(RT.getIn(config, "skillsets"));
			assertNotNull(sets, t + " template should declare skillsets");
			assertEquals("w/skills", sets.get(0).toString());   // user skills shadow venue skills
			AVector<ACell> named = RT.ensureVector(RT.getIn(config, "skills"));
			if (java.util.Set.of("minimal", "skilled", "goaltree", "full").contains(t)) {
				assertEquals(2, sets.count(), t);
				assertEquals("v/skills/root", sets.get(1).toString());
				assertNull(named, t + " general template curates nothing individually");
			} else {
				assertEquals(1, sets.count(), t + " specialists take only user skills wholesale");
				assertNotNull(named, t + " should curate individual venue skills");
				assertTrue(named.count() >= 4, t + " should have a curated venue subset");
				for (long i = 0; i < named.count(); i++) {
					assertTrue(named.get(i).toString().startsWith("v/skills/"),
						t + " should name one venue skill: " + named.get(i));
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
		String capabilities = skillBody("capabilities");
		String auth = skillBody("auth");
		assertFalse(agents.contains("created: false"), agents);
		assertFalse(agents.contains("wait?"), agents);
		assertFalse(tasks.contains("wait?"), tasks);
		assertTrue(tasks.contains("timeout?"), tasks);
		assertTrue(models.contains("Templates are provider-neutral"), models);
		assertFalse(models.contains("templates default to openai"), models);
		assertTrue(skills.contains("Do not invent an undeclared tool name"), skills);
		for (String guidance : new String[] {capabilities, auth}) {
			assertTrue(guidance.contains("inherent capabilities are its `config.caps`"), guidance);
			assertTrue(guidance.contains("OR semantics"), guidance);
			assertFalse(guidance.contains("ceiling"), guidance);
		}

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

		for (String name : shippedSkills()) {
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

		for (String name : shippedSkillNames()) {
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
		String alias = ToolPalette.deriveToolName(null, explicitName,
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
		Skills.SkillSources sources = Skills.SkillSources.ofSkills(Vectors.of(
			(ACell) Strings.create("v/skills/ops-tools/models"),
			(ACell) Strings.create("v/skills/agents/tasks")));
		String index = Skills.renderIndex(engine, ctx, sources, null, true);
		assertTrue(index.contains("- models — "), index);
		assertTrue(index.contains("- tasks — "), index);
		assertFalse(index.contains("- workspace — "), index);
		assertEquals("models", Skills.resolveByName(engine, ctx, sources, "models").name());
	}

	@Test
	public void testSpecialistTemplateIndexesStayCurated() {
		String fullIndex = Skills.renderIndex(engine, ctx, everySkillset(), null, true);
		java.util.Map<String, java.util.Set<String>> expected = java.util.Map.of(
			"reader", java.util.Set.of("discovery", "provenance", "assets", "skills"),
			"worker", java.util.Set.of("workspace", "files", "assets", "provenance"),
			"analyst", java.util.Set.of("workspace", "discovery", "provenance", "orchestration", "assets"),
			"manager", java.util.Set.of("agents", "tasks", "models", "grid",
				"scheduling", "hitl", "auth", "provenance"));
		for (var entry : expected.entrySet()) {
			ACell asset = engine.resolvePath(
				Strings.create("v/agents/templates/" + entry.getKey()), ctx);
			Skills.SkillSources sources = new Skills.SkillSources(
				RT.ensureVector(RT.getIn(asset, "agent", "config", "skills")),
				RT.ensureVector(RT.getIn(asset, "agent", "config", "skillsets")));
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
		AVector<ACell> context = RT.ensureVector(RT.getIn(config, "context"));
		assertEquals(1, context.count(), "default agent should pin one agent-local memory source");
		ACell memory = context.get(0);
		assertEquals("v/ops/memory", RT.getIn(memory, "op").toString());
		assertEquals("n/memory", RT.getIn(memory, "input", "path").toString());
		assertEquals("Agent memory (edit using path n/memory)", RT.getIn(memory, "label").toString());
		// minimal holds NO op tools — skills discovery is its entire surface
		ACell minimalAsset = engine.resolvePath(Strings.create("v/agents/templates/minimal"), ctx);
		ACell minimal = RT.getIn(minimalAsset, "agent", "config");
		assertNull(RT.getIn(minimal, "tools"));
	}

	/**
	 * The venue's own library must validate clean at boot: {@code v/skills}
	 * holds only skillsets, every installed skill resolves, and every declared
	 * child ref resolves and matches its declared kind. This is the guard that
	 * a packaging mistake in the shipped skills is caught here rather than in
	 * an operator's log.
	 *
	 * <p>Deliberately runs against the SHARED engine, so it also catches any
	 * test that leaves a stray skill directly under {@code v/skills} — the
	 * invariant holds repo-wide, not just for the shipped resources.</p>
	 */
	@Test
	public void testVenueSkillLibraryValidatesClean() {
		assertEquals(0, Skills.validateVenueLibrary(engine),
			"the shipped venue skill library should report no problems");
	}

	/** Every skillset a root skill opens must exist and hold skills. */
	@Test
	public void testRootSkillsOpenRealSkillsets() {
		ACell root = engine.resolvePath(Strings.create("v/skills/root"), ctx);
		assertNotNull(root, "v/skills/root should be the default entry skillset");
		java.util.Set<String> opened = new java.util.HashSet<>();
		for (var e : RT.ensureMap(root).entrySet()) {
			Skills.ResolvedSkill s = Skills.resolveRef(engine, ctx,
				Strings.create("v/skills/root/" + e.getKey()));
			for (long i = 0; i < s.skillsets().count(); i++) {
				String set = s.skillsets().get(i).toString();
				opened.add(set);
				ACell value = engine.resolvePath(Strings.create(set), ctx);
				assertNotNull(value, s.name() + " opens missing skillset " + set);
				assertTrue(RT.ensureMap(value).count() > 0, set + " should not be empty");
			}
		}
		assertTrue(opened.size() >= 8, "root should reach the whole taxonomy: " + opened);
	}

	/** Mirroring a skill into root must not double it in an agent's context. */
	@Test
	public void testRootMirrorDedupsAgainstItsFamily() {
		Skills.ResolvedSkill viaRoot =
			Skills.resolveRef(engine, ctx, Strings.create("v/skills/root/workspace"));
		Skills.ResolvedSkill viaFamily =
			Skills.resolveRef(engine, ctx, Strings.create("v/skills/data/workspace"));
		assertEquals(viaRoot.id(), viaFamily.id(),
			"a mirrored skill must be the SAME content at both addresses");

		// Both sources in scope → the index lists it once.
		Skills.SkillSources both = Skills.SkillSources.ofSkillsets(Vectors.of(
			(ACell) Strings.create("v/skills/root"), (ACell) Strings.create("v/skills/data")));
		String index = Skills.renderIndex(engine, ctx, both, null, true);
		int first = index.indexOf("- workspace — ");
		assertTrue(first >= 0, index);
		assertEquals(first, index.lastIndexOf("- workspace — "), "workspace listed twice: " + index);
	}

	/** Templates with lattice tools pin the lattice skill; the tool-less one does not. */
	@Test
	public void testToolTemplatesPinTheLatticeSkill() {
		for (String t : new String[] {"skilled", "reader", "worker", "analyst", "manager", "goaltree", "full"}) {
			ACell asset = engine.resolvePath(Strings.create("v/agents/templates/" + t), ctx);
			ACell pin = RT.getIn(asset, "agent", "config", "loads", "v/skills/data/lattice");
			assertTrue(pin instanceof AMap, t + " should pin the lattice skill: " + pin);
			assertTrue(Skills.isSkillEntry(pin), t + " pins it as a skill entry");
		}
		ACell minimal = engine.resolvePath(Strings.create("v/agents/templates/minimal"), ctx);
		assertNull(RT.getIn(minimal, "agent", "config", "loads"), "no tools, no lattice reference");
	}
}
