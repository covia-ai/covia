package covia.adapter.agent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.util.CellExplorer;
import convex.core.lang.RT;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * The loads phase of assembly (AGENT_CONTEXT.md §4): the effective loads
 * chain resolved once per inference into the elements the model sees, the
 * tools those loads contribute, and the dispatch routes of those tools —
 * together, so a caller can never refresh one without the others.
 *
 * <p>The snapshot is ephemeral: resolved immediately before every provider
 * call under the agent's capability-narrowed authority, never persisted.</p>
 */
public final class Loads {

	private Loads() {}

	/** One inference's view of the effective loads. */
	public record Snapshot(AVector<ACell> elements, AVector<ACell> tools, Map<String, AString> routes) {
		public static final Snapshot EMPTY = new Snapshot(null, null, null);

		public Snapshot {
			elements = (elements != null) ? elements : Vectors.empty();
			tools = (tools != null) ? tools : Vectors.empty();
			routes = (routes != null) ? Map.copyOf(routes) : Map.of();
		}
	}

	/**
	 * Resolves the effective loads. Contributed tools are deduplicated against
	 * {@code fixedNames} — the harness and configured tools, which a load may
	 * never shadow.
	 */
	public static Snapshot resolve(Engine engine, RequestContext ctx,
			AMap<AString, ACell> effectiveLoads, Set<String> fixedNames, AString dialect) {
		if (effectiveLoads == null || effectiveLoads.count() == 0) return Snapshot.EMPTY;
		Map<String, AString> routes = new HashMap<>();
		AVector<ACell> tools = ToolPalette.loadsToolDefs(engine, ctx, effectiveLoads, fixedNames, routes);
		return new Snapshot(elements(engine, ctx, effectiveLoads, dialect), tools, routes);
	}

	/**
	 * Renders every loads entry to its context messages. This is the single
	 * place that knows about entry kinds:
	 * <ul>
	 *   <li><b>Skill entries</b> ({@code skill: true}): the skill re-resolves
	 *       from the entry key and renders as a skill element plus its body,
	 *       followed by the skill's own context entries. Failures are
	 *       <b>visible</b> — a skill the agent loaded must not silently
	 *       disappear. A skill reached under two addresses renders once
	 *       (content-identity dedup).</li>
	 *   <li><b>Everything else</b>: standard context-entry resolution of the
	 *       key — absent → skipped, error → a visible unavailable element.</li>
	 * </ul>
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> elements(Engine engine, RequestContext ctx,
			AMap<AString, ACell> loads, AString dialect) {
		AVector<ACell> out = Vectors.empty();
		if (loads == null || loads.count() == 0) return out;
		ContextLoader loader = new ContextLoader(engine, dialect);
		Set<convex.core.data.Hash> seenSkillIds = new HashSet<>();
		for (var entry : loads.entrySet()) {
			AString path = entry.getKey();
			AMap<AString, ACell> meta = (AMap<AString, ACell>) entry.getValue();
			int entryBudget = (int) AbstractLLMAdapter.clampLoadBudget(meta.get(AbstractLLMAdapter.K_BUDGET));
			loader.setCellExplorer(new CellExplorer(entryBudget));
			out = (AVector<ACell>) out.concat(element(engine, ctx, loader, path, meta, seenSkillIds, dialect));
		}
		return out;
	}

	private static AVector<ACell> element(Engine engine, RequestContext ctx, ContextLoader loader,
			AString path, AMap<AString, ACell> meta, Set<convex.core.data.Hash> seenSkillIds,
			AString dialect) {
		if (!Skills.isSkillEntry(meta)) {
			ACell msg = loader.resolveEntry(path, ctx);
			return (msg != null) ? Vectors.of(msg) : Vectors.empty();
		}
		try {
			Skills.ResolvedSkill skill = Skills.resolveRef(engine, ctx, path);
			if (skill.id() != null && !seenSkillIds.add(skill.id())) return Vectors.empty();
			AVector<ACell> msgs = Vectors.of(
				Skills.renderSkillMessage(dialect, skill.name(), path, skill.displayBody()));
			if (skill.contextEntries().count() > 0) {
				msgs = msgs.concat(loader.resolve(skill.contextEntries(), ctx));
			}
			return msgs;
		} catch (RuntimeException e) {
			AString label = RT.ensureString(meta.get(AbstractLLMAdapter.K_LABEL));
			return Vectors.of(Skills.skillErrorMessage(dialect,
				(label != null) ? label.toString() : path.toString(), path,
				ContextLoader.rootMessage(e)));
		}
	}
}
