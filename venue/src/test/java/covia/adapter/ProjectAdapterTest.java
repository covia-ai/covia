package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.adapter.agent.Skills;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * The project adapter's first increment: it registers, publishes the
 * {@code projects} skill family (entry point mirrored at {@code root/projects},
 * sub-skills under {@code v/skills/projects/}), and provides no operations yet
 * — so dispatch fails loudly rather than pretending. Library-wide invariants
 * (bodies, tool drift, index budget, alias hygiene) are covered by
 * {@link SkillsLibraryTest}; this test pins what is specific to this adapter.
 */
public class ProjectAdapterTest {

	private final Engine engine = TestEngine.ENGINE;
	private RequestContext ctx;

	@BeforeEach
	public void setup(TestInfo info) {
		ctx = RequestContext.of(TestEngine.uniqueDID(info));
	}

	@Test
	public void testAdapterRegisteredWithNoOperations() {
		AAdapter adapter = engine.getAdapter("project");
		assertNotNull(adapter, "project adapter should be registered");
		assertTrue(adapter instanceof ProjectAdapter);
		for (String path : adapter.pendingCatalogEntries.keySet()) {
			assertFalse(path.startsWith("v/ops/"), "no operations yet, but found " + path);
		}
		assertTrue(adapter.getDescription().contains("w/projects/<pid>"));
	}

	@Test
	public void testSkillFamilyMaterialisedUnderProjectsSkillset() {
		AAdapter adapter = engine.getAdapter("project");
		for (String skill : ProjectAdapter.SKILLS) {
			String path = "v/skills/projects/" + skill;
			assertTrue(adapter.pendingCatalogEntries.containsKey(path), "project owns " + path);
			ACell value = engine.resolvePath(Strings.create(path), ctx);
			assertTrue(value instanceof AMap, path + " should materialise: " + value);
			Skills.ResolvedSkill resolved = Skills.resolveRef(engine, ctx, Strings.create(path));
			assertEquals(skill, resolved.name());
			assertNotNull(resolved.body(), skill + " ships an inline body");
		}
	}

	@Test
	public void testEntryPointMirroredAtRootFromSameResource() {
		AAdapter adapter = engine.getAdapter("project");
		Hash family = adapter.pendingCatalogEntries.get("v/skills/projects/projects");
		Hash root = adapter.pendingCatalogEntries.get("v/skills/root/projects");
		assertNotNull(family);
		assertNotNull(root);
		assertEquals(family, root, "root mirror must be the identical asset, so it dedups as one skill");

		// The entry point opens the family: loading it reveals v/skills/projects.
		ACell meta = engine.resolvePath(Strings.create("v/skills/root/projects"), ctx);
		AVector<ACell> skillsets = RT.ensureVector(RT.getIn(meta, "skill", "skillsets"));
		assertNotNull(skillsets);
		assertEquals(Strings.create("v/skills/projects"), skillsets.get(0));

		// Sub-skills are not entry points: only the family path, no root mirror.
		for (String skill : ProjectAdapter.SKILLS) {
			if (skill.equals("projects")) continue;
			assertFalse(adapter.pendingCatalogEntries.containsKey("v/skills/root/" + skill),
				skill + " is reached through the entry point, not from root");
		}
	}

	@Test
	public void testSubSkillsCoverEachKindOfProjectActivity() {
		// The entry point body names every sub-skill's activity so an agent knows
		// what one more load will give it; each sub-skill declares tools an agent
		// needs for that activity without any project-specific operation, since
		// none exist yet.
		Skills.ResolvedSkill entry = Skills.resolveRef(engine, ctx, Strings.create("v/skills/projects/projects"));
		String body = entry.body();
		for (String activity : new String[] {"briefing", "planning", "delegation", "reporting", "delivery", "monitoring"}) {
			assertTrue(body.contains(activity), "entry point should point at " + activity);
			Skills.ResolvedSkill sub = Skills.resolveRef(engine, ctx,
				Strings.create("v/skills/projects/project-" + activity));
			assertTrue(sub.toolOps().count() > 0, activity + " declares tools");
			for (long i = 0; i < sub.toolOps().count(); i++) {
				String op = sub.toolOps().get(i).toString();
				assertFalse(op.startsWith("v/ops/project/"), activity + " must not reference unpublished ops: " + op);
			}
		}
	}

	@Test
	public void testDispatchFailsLoudlyUntilOperationsExist() {
		AMap<AString, ACell> meta = Maps.of(
			Fields.NAME, Strings.create("Phantom project op"),
			Fields.OPERATION, Maps.of(Fields.ADAPTER, Strings.create("project:create")));
		JobFailedException e = assertThrows(JobFailedException.class,
			() -> engine.jobs().invokeOperation(meta, Maps.empty(), ctx).awaitResult(5000));
		assertTrue(e.getMessage().contains("Unknown project operation: create"), e.getMessage());
	}
}
