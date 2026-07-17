package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
			Vectors.of((ACell) Strings.create("v/skills")), null);
		assertNotNull(index);
		for (String name : SkillsAdapter.LIBRARY) {
			assertTrue(index.contains("- " + name + " — "), "index missing " + name + ":\n" + index);
		}
		assertFalse(index.contains("INVALID"), index);
		assertFalse(index.contains("unavailable"), index);
		// Context efficiency: the always-in-context index stays compact.
		assertTrue(index.length() < 2500,
			"index should stay compact (" + index.length() + " chars):\n" + index);
	}

	@Test
	public void testTemplatesDeclareSkillSources() {
		// Every template is skills-capable: minimal STARTS with only skills
		// discovery; skilled is the lean recommended default; full is the
		// context-heavy frontier setup with skills on top of the default pack.
		for (String t : new String[] {"minimal", "skilled", "reader", "worker", "analyst",
				"manager", "goaltree", "full"}) {
			ACell config = engine.resolvePath(Strings.create("v/agents/templates/" + t), ctx);
			AVector<ACell> sources = RT.ensureVector(RT.getIn(config, "skills"));
			assertNotNull(sources, t + " template should declare skills sources");
			assertEquals(2, sources.count(), t);
			assertEquals("w/skills", sources.get(0).toString());   // user skills shadow venue skills
			assertEquals("v/skills", sources.get(1).toString());
		}
	}

	@Test
	public void testReaderTemplateIsCapabilityPinned() {
		// reader's read-only-ness is ENFORCED, not prompt fiction: the template
		// pins crud/read + asset/read, so a loaded write tool is denied at
		// invocation (verified live — skill loading grants no authority).
		ACell config = engine.resolvePath(Strings.create("v/agents/templates/reader"), ctx);
		AVector<ACell> caps = RT.ensureVector(RT.getIn(config, "caps"));
		assertNotNull(caps, "reader must pin a capability ceiling");
		assertEquals(2, caps.count());
		assertEquals("crud/read", RT.getIn(caps.get(0), "can").toString());
		assertEquals("asset/read", RT.getIn(caps.get(1), "can").toString());
	}

	@Test
	public void testSkilledTemplateShape() {
		ACell config = engine.resolvePath(Strings.create("v/agents/templates/skilled"), ctx);
		assertNotNull(config, "skilled template should materialise");
		AVector<ACell> tools = RT.ensureVector(RT.getIn(config, "tools"));
		assertEquals(2, tools.count(), "lean base: read + list only");
		// minimal holds NO op tools — skills discovery is its entire surface
		ACell minimal = engine.resolvePath(Strings.create("v/agents/templates/minimal"), ctx);
		assertNull(RT.getIn(minimal, "tools"));
	}
}
