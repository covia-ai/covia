package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import covia.venue.RequestContext;

/**
 * Projects — long-running objectives executed as a work breakdown tree.
 *
 * <p>A project is a tree of nodes at {@code w/projects/<pid>} (children under
 * {@code nodes/<nid>}) in the root principal's workspace. Every node has the
 * same shape — brief, deliverables with quality criteria, deadline, budget,
 * tolerances, an accountable principal and an assignee — and the assignee of
 * a node is the principal of its children. The full design, including the
 * record schema, status machine, rollup, escalation chain and the operations
 * this adapter will provide, is in {@code venue/docs/PROJECT.md}.</p>
 *
 * <p><b>Current scope.</b> The adapter publishes the {@code projects} skill
 * family — the instructions an agent needs to work with a project tree as
 * principal or assignee — and no operations yet. Until the {@code project:*}
 * operations land, a project tree is plain workspace data that agents read and
 * write with the lattice operations; the skills teach the invariants the
 * operations will later enforce. Operation dispatch therefore fails loudly:
 * there is nothing to dispatch to, and a caller that reaches this adapter has
 * an operation asset that names a sub-operation which does not exist.</p>
 */
public class ProjectAdapter extends AAdapter {

	/** Skillset the entry point opens: {@code v/skills/projects/}. */
	static final String SKILLSET = "projects";

	/**
	 * The skill family, in the order it is installed: the entry point first
	 * (also mirrored at {@code root/projects}), then one sub-skill per kind of
	 * project activity. Each lives at {@code v/skills/projects/<name>} from
	 * {@code /skills/<name>.json}.
	 */
	static final String[] SKILLS = {
		"projects",
		"project-briefing",
		"project-planning",
		"project-delegation",
		"project-reporting",
		"project-delivery",
		"project-monitoring",
	};

	@Override
	public String getName() {
		return "project";
	}

	@Override
	public String getDescription() {
		return "Projects: long-running objectives executed as a work breakdown tree at w/projects/<pid>. "
			+ "Every node carries a brief, deliverables with quality criteria, a deadline, a budget, "
			+ "tolerances, a principal and an assignee; the assignee of a node is the principal of its "
			+ "children. Publishes the projects skill family; operations are not yet provided.";
	}

	@Override
	protected void installAssets() {
		// The entry point is installed at its family path AND the root/ mirror
		// from the same resource, so both addresses hold identical metadata and
		// content-identity dedup treats them as one skill (SKILLS.md §4.2).
		installSkill("root/" + SKILLS[0], "/skills/" + SKILLS[0] + ".json");
		for (String skill : SKILLS) {
			installSkill(SKILLSET + "/" + skill, "/skills/" + skill + ".json");
		}
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String subOp = getSubOperation(meta);
		return CompletableFuture.failedFuture(new IllegalArgumentException(
			"Unknown project operation: " + subOp + " (the project adapter provides no operations yet; "
			+ "work with the project tree through the lattice operations — see the projects skill)"));
	}
}
