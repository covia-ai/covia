package covia.adapter.agent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
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

	private static final AString K_KIND         = Strings.intern("kind");
	private static final AString K_STATUS       = Strings.intern("status");
	private static final AString K_TRUNCATED    = Strings.intern("truncated");
	private static final AString K_DEDUPLICATED = Strings.intern("deduplicated");

	/** One inference's view of the effective loads. */
	public record Snapshot(AVector<ACell> elements, AVector<ACell> tools, Map<String, AString> routes,
			AVector<ACell> diagnostics, AVector<ACell> toolProvenance) {
		public static final Snapshot EMPTY = new Snapshot(null, null, null, null, null);

		public Snapshot {
			elements = (elements != null) ? elements : Vectors.empty();
			tools = (tools != null) ? tools : Vectors.empty();
			routes = (routes != null) ? Map.copyOf(routes) : Map.of();
			diagnostics = (diagnostics != null) ? diagnostics : Vectors.empty();
			toolProvenance = (toolProvenance != null) ? toolProvenance : Vectors.empty();
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
		java.util.List<AMap<AString, ACell>> toolEntries = new java.util.ArrayList<>();
		AMap<AString, ACell> toolLoads = Skills.resolveLoadTools(engine, ctx, effectiveLoads);
		AVector<ACell> tools = ToolPalette.loadsToolDefs(
			engine, ctx, toolLoads, fixedNames, routes, toolEntries);
		ResolvedElements resolved = resolveElements(engine, ctx, effectiveLoads, dialect);
		return new Snapshot(resolved.elements(), tools, routes, resolved.diagnostics(), vector(toolEntries));
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
		return resolveElements(engine, ctx, loads, dialect).elements();
	}

	private record ResolvedElements(AVector<ACell> elements, AVector<ACell> diagnostics) {}
	private record Element(AVector<ACell> messages, String status, boolean deduplicated) {}

	@SuppressWarnings("unchecked")
	private static ResolvedElements resolveElements(Engine engine, RequestContext ctx,
			AMap<AString, ACell> loads, AString dialect) {
		AVector<ACell> out = Vectors.empty();
		AVector<ACell> diagnostics = Vectors.empty();
		if (loads == null || loads.count() == 0) return new ResolvedElements(out, diagnostics);
		ContextLoader loader = new ContextLoader(engine, dialect);
		Set<convex.core.data.Hash> seenSkillIds = new HashSet<>();
		for (var entry : loads.entrySet()) {
			AString path = entry.getKey();
			AMap<AString, ACell> meta = (AMap<AString, ACell>) entry.getValue();
			long entryBudget = AbstractLLMAdapter.clampLoadBudget(meta.get(AbstractLLMAdapter.K_BUDGET));
			loader.beginTrace(entryBudget);
			boolean skill = Skills.isSkillEntry(meta);
			Element resolved = element(engine, ctx, loader, path, meta, seenSkillIds, dialect);
			out = (AVector<ACell>) out.concat(resolved.messages());
			long bytes = 0;
			for (long i = 0; i < resolved.messages().count(); i++) {
				bytes += ContextAssembler.bytes(resolved.messages().get(i));
			}
			diagnostics = diagnostics.conj(Maps.of(
				Fields.REF, path,
				K_KIND, Strings.create(skill ? "skill" : "load"),
				Fields.BYTES, CVMLong.create(bytes),
				AbstractLLMAdapter.K_BUDGET, CVMLong.create(entryBudget),
				K_STATUS, Strings.create(resolved.status()),
				K_TRUNCATED, CVMBool.create(loader.wasTruncated()),
				K_DEDUPLICATED, CVMBool.create(resolved.deduplicated())));
		}
		return new ResolvedElements(out, diagnostics);
	}

	private static Element element(Engine engine, RequestContext ctx, ContextLoader loader,
			AString path, AMap<AString, ACell> meta, Set<convex.core.data.Hash> seenSkillIds,
			AString dialect) {
		if (!Skills.isSkillEntry(meta)) {
			ACell msg = loader.resolveEntry(path, ctx);
			AVector<ACell> messages = (msg != null) ? Vectors.of(msg) : Vectors.empty();
			return new Element(messages, loader.resolution().name().toLowerCase(), false);
		}
		try {
			Skills.ResolvedSkill skill = Skills.resolveRef(engine, ctx, path);
			if (skill.id() != null && !seenSkillIds.add(skill.id())) {
				return new Element(Vectors.empty(), "deduplicated", true);
			}
			AVector<ACell> msgs = Vectors.of(
				Skills.renderSkillMessage(dialect, skill.name(), path, skill.displayBody()));
			if (skill.contextEntries().count() > 0) {
				msgs = msgs.concat(loader.resolve(skill.contextEntries(), ctx));
			}
			return new Element(msgs, "resolved", false);
		} catch (RuntimeException e) {
			AString label = RT.ensureString(meta.get(AbstractLLMAdapter.K_LABEL));
			return new Element(Vectors.of(Skills.skillErrorMessage(dialect,
				(label != null) ? label.toString() : path.toString(), path,
				ContextLoader.rootMessage(e))), "unavailable", false);
		}
	}

	private static AVector<ACell> vector(java.util.List<? extends ACell> cells) {
		AVector<ACell> out = Vectors.empty();
		for (ACell cell : cells) out = out.conj(cell);
		return out;
	}
}
