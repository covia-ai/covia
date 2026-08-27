package covia.adapter.agent;

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
import covia.venue.Engine;
import covia.venue.RequestContext;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The harness tools every runtime provides, and the one rule for offering
 * them: opt-in by name in {@code config.tools}, plus what the situation
 * itself implies — {@code skill_load} and {@code context_unload} when the
 * agent declares skills. A runtime adds its own tools to the registry
 * (goaltree: {@code subgoal}, {@code compact}, {@code complete},
 * {@code fail}); the task tools are {@link TaskTools}, offered while a task
 * is outstanding. The handlers here are parameterised by the writable loads
 * tier, which is the only thing the runtimes differ in.
 */
final class HarnessTools {
	private HarnessTools() {}

	static final String CONTEXT_LOAD   = "context_load";
	static final String CONTEXT_UNLOAD = "context_unload";
	static final String SKILL_LOAD     = "skill_load";
	static final String MORE_TOOLS     = "more_tools";

	private static final AString K_OPERATIONS = Strings.intern("operations");
	private static final AString K_ADDED      = Strings.intern("added");
	private static final AString K_NOTE       = Strings.intern("note");

	static final AMap<AString, ACell> DEF_CONTEXT_LOAD = Maps.of(
		AbstractLLMAdapter.K_NAME, Strings.create(CONTEXT_LOAD),
		AbstractLLMAdapter.K_DESCRIPTION, Strings.create(
			"Keep something visible in your context across turns until you remove it: a lattice "
			+ "path (re-read and rendered on every model call), a note you write (text), a read-only "
			+ "operation whose fresh result you want each turn (op), or a finished job's result (job). "
			+ "Give exactly one of path, text, op or job; text, op and job also need an id — the key "
			+ "shown in the element's header and passed to context_unload. Use for rules, schemas or "
			+ "reference material you consult repeatedly; for data needed once, use an advertised "
			+ "inspection or read operation instead. An op entry re-runs every call and renders at "
			+ "the end of your context (never cached); other entries render in the working set. "
			+ "Scoped to this conversation (subgoals inherit it); other conversations are unaffected."),
		AbstractLLMAdapter.K_PARAMETERS, AbstractLLMAdapter.CONTEXT_LOAD_PARAMS);

	static final AMap<AString, ACell> DEF_CONTEXT_UNLOAD = Maps.of(
		AbstractLLMAdapter.K_NAME, Strings.create(CONTEXT_UNLOAD),
		AbstractLLMAdapter.K_DESCRIPTION, Strings.create(
			"Remove an entry from this conversation's loaded context, freeing its budget — pass the "
			+ "key shown in its header: the path you loaded, or the id you gave a text, op or job "
			+ "entry. Unload a volatile entry (one that renders at the end of your context, such as "
			+ "an op) as soon as you no longer need it: it is re-run and re-sent on every model call "
			+ "until you do. Also hides an operator-pinned load (from config.loads) for this "
			+ "conversation only; the pin itself is untouched and other conversations still see it."),
		AbstractLLMAdapter.K_PARAMETERS, AbstractLLMAdapter.CONTEXT_UNLOAD_PARAMS);

	static final AMap<AString, ACell> DEF_SKILL_LOAD = Maps.of(
		AbstractLLMAdapter.K_NAME, Strings.create(SKILL_LOAD),
		AbstractLLMAdapter.K_DESCRIPTION, Strings.create(
			"Load a skill from the [Skills] index by name (or any skill by direct ref). "
			+ "The result includes the skill's full instructions for immediate use; they "
			+ "also stay in your context each turn until you remove the skill's loaded "
			+ "path with context_unload. The skill's tools join your palette from your next step."),
		AbstractLLMAdapter.K_PARAMETERS, AbstractLLMAdapter.SKILL_LOAD_PARAMS);

	static final AMap<AString, ACell> DEF_MORE_TOOLS = Maps.of(
		AbstractLLMAdapter.K_NAME, Strings.create(MORE_TOOLS),
		AbstractLLMAdapter.K_DESCRIPTION, Strings.create(
			"Add operations to your tool set for the rest of this run. "
			+ "Use an advertised catalog-listing operation to discover available operations first "
			+ "(for example, list path=v/ops), then call this with the exact paths "
			+ "you need. Added tools appear on your next turn."),
		AbstractLLMAdapter.K_PARAMETERS, Maps.of(
			AbstractLLMAdapter.K_TYPE, Strings.create("object"),
			AbstractLLMAdapter.K_PROPERTIES, Maps.of(
				K_OPERATIONS, Maps.of(
					AbstractLLMAdapter.K_TYPE, Strings.create("array"),
					AbstractLLMAdapter.K_DESCRIPTION, Strings.create(
						"Operation paths to add as tools (e.g. [\"v/ops/agent/create\", \"v/ops/grid/run\"])"),
					Strings.create("items"), Maps.of(AbstractLLMAdapter.K_TYPE, Strings.create("string")))),
			AbstractLLMAdapter.K_REQUIRED, Vectors.of((ACell) K_OPERATIONS)));

	/** The harness tools every runtime provides, by name. */
	static final Map<String, AMap<AString, ACell>> SHARED = Map.of(
		CONTEXT_LOAD, DEF_CONTEXT_LOAD,
		CONTEXT_UNLOAD, DEF_CONTEXT_UNLOAD,
		SKILL_LOAD, DEF_SKILL_LOAD,
		MORE_TOOLS, DEF_MORE_TOOLS);

	/**
	 * The harness tools a cycle offers from a runtime's registry: those the
	 * agent opted into by name in {@code config.tools}, in that order — and,
	 * when the agent declares skill sources, {@code skill_load} and
	 * {@code context_unload}: the skills index is rendered, so the tool that
	 * loads from it and the tool that removes a load are implied. Entries
	 * that are not harness names are operations, resolved by the palette.
	 */
	static AVector<ACell> offered(AMap<AString, ACell> config, Map<String, AMap<AString, ACell>> registry) {
		AVector<ACell> out = Vectors.empty();
		Set<String> names = new HashSet<>();
		AVector<ACell> listed = RT.ensureVector(config != null ? config.get(AbstractLLMAdapter.K_TOOLS) : null);
		for (long i = 0; listed != null && i < listed.count(); i++) {
			if (!(listed.get(i) instanceof AString name)) continue;
			AMap<AString, ACell> def = registry.get(name.toString());
			if (def != null && names.add(name.toString())) out = out.conj(def);
		}
		if (config != null && !Skills.sourcesOf(config).isEmpty()) {
			for (String implied : new String[] {SKILL_LOAD, CONTEXT_UNLOAD}) {
				AMap<AString, ACell> def = registry.get(implied);
				if (def != null && names.add(implied)) out = out.conj(def);
			}
		}
		return out;
	}

	/** What {@code more_tools} produced: definitions to add to the run's palette, and the reply to the model. */
	record Added(AVector<ACell> tools, ACell result) {}

	/**
	 * {@code more_tools}: resolves operation paths to tool definitions under
	 * the agent's authority, skipping names already offered, and routes each
	 * new name to its operation. The additions last for the rest of the run
	 * — never persisted; a load or a skill is the durable way to acquire tools.
	 */
	@SuppressWarnings("unchecked")
	static Added moreTools(ACell input, Engine engine, RequestContext ctx,
			Set<String> existing, Map<String, AString> routes) {
		ACell opsCell = RT.getIn(input, K_OPERATIONS);
		if (!(opsCell instanceof AVector<?>)) {
			return new Added(Vectors.empty(), Strings.create("Error: operations must be an array of operation paths"));
		}
		Map<String, AString> fresh = new HashMap<>();
		AVector<ACell> resolved = ToolPalette.forOperations(engine, ctx, (AVector<ACell>) opsCell, fresh);
		AVector<ACell> added = Vectors.empty();
		AVector<ACell> names = Vectors.empty();
		for (long i = 0; i < resolved.count(); i++) {
			ACell tool = resolved.get(i);
			AString name = RT.ensureString(RT.getIn(tool, AbstractLLMAdapter.K_NAME));
			if (name == null || !existing.add(name.toString())) continue;
			added = added.conj(tool);
			routes.put(name.toString(), fresh.get(name.toString()));
			names = names.conj(name);
		}
		return new Added(added, Maps.of(
			K_ADDED, names,
			K_NOTE, Strings.create("Tools available on your next turn.")));
	}

	/** Mutable view of the innermost loads tier for a flat session or frame. */
	static final class LoadScope {
		final Engine engine;
		final RequestContext context;
		final AMap<AString, ACell> outerLoads;
		final boolean writable;
		final String unavailableMessage;
		final Skills.SkillSources skillSources;
		AMap<AString, ACell> loads;

		LoadScope(Engine engine, RequestContext context,
				AMap<AString, ACell> loads, AMap<AString, ACell> outerLoads,
				boolean writable, String unavailableMessage, Skills.SkillSources skillSources) {
			this.engine = engine;
			this.context = context;
			this.loads = (loads != null) ? loads : Maps.empty();
			this.outerLoads = (outerLoads != null) ? outerLoads : Maps.empty();
			this.writable = writable;
			this.unavailableMessage = unavailableMessage;
			this.skillSources = (skillSources != null) ? skillSources : Skills.SkillSources.EMPTY;
		}
	}

	/**
	 * {@code context_load}: pins one entry into the innermost loads tier.
	 * Exactly one source form — {@code path} (the key is the path, as always),
	 * or {@code text} / {@code op} / {@code job} under a caller-chosen
	 * {@code id} (the key). The entry grammar is AGENT_CONTEXT.md §6.2; the
	 * stored spec is what a declared load would carry, so the loads tiers
	 * hold one shape whoever wrote them. An op entry defaults to volatile —
	 * it re-runs every inference, so it belongs in the tail.
	 */
	static ACell contextLoad(ACell input, LoadScope scope) {
		AString path = RT.ensureString(RT.getIn(input, AbstractLLMAdapter.K_PATH));
		AString text = RT.ensureString(RT.getIn(input, Loads.K_TEXT));
		AString op   = RT.ensureString(RT.getIn(input, Loads.K_OP));
		AString job  = RT.ensureString(RT.getIn(input, Loads.K_JOB));
		int forms = (path != null ? 1 : 0) + (text != null ? 1 : 0) + (op != null ? 1 : 0) + (job != null ? 1 : 0);
		if (forms == 0) return Strings.create(
			"Error: path is required — or text, op or job with an id. context_load pins one entry "
			+ "into your context: {\"path\": \"w/...\"} keeps a lattice path visible; {\"id\", \"text\"} "
			+ "a note; {\"id\", \"op\", \"input\"?} a read-only operation re-run each turn; "
			+ "{\"id\", \"job\"} a finished job's result. Optional: budget in bytes, label.");
		if (forms > 1) return Strings.create(
			"Error: give exactly one of path, text, op or job — context_load pins one entry per call.");
		if (!scope.writable) return Strings.create(scope.unavailableMessage);
		long budget = AbstractLLMAdapter.clampLoadBudget(
			RT.getIn(input, AbstractLLMAdapter.K_BUDGET));
		AString label = RT.ensureString(RT.getIn(input, AbstractLLMAdapter.K_LABEL));
		AMap<AString, ACell> meta = AbstractLLMAdapter.buildLoadEntryMeta(budget, label);
		AString key = path;
		if (path != null) {
			try {
				new ContextLoader(scope.engine).requireReadAccess(path, scope.context);
			} catch (RuntimeException e) {
				return Strings.create("Error: context_load denied: " + describe(e));
			}
		} else {
			key = RT.ensureString(RT.getIn(input, AbstractLLMAdapter.K_ID));
			if (key == null) return Strings.create(
				"Error: id is required with text, op or job — it is the key shown in the "
				+ "element's header and the argument to context_unload.");
			if (text != null) {
				meta = meta.assoc(Loads.K_TEXT, text);
			} else if (op != null) {
				meta = meta.assoc(Loads.K_OP, op);
				ACell opInput = RT.getIn(input, Loads.K_INPUT);
				if (opInput != null) meta = meta.assoc(Loads.K_INPUT, opInput);
			} else {
				meta = meta.assoc(Loads.K_JOB, job);
			}
		}
		ACell vol = RT.getIn(input, Loads.K_VOLATILE);
		if (vol instanceof CVMBool) meta = meta.assoc(Loads.K_VOLATILE, vol);
		scope.loads = scope.loads.assoc(key, meta);
		String note = Loads.isVolatile(meta)
			? "Loaded. It re-renders at the end of your context on every model call (never cached)"
				+ " until you unload " + key + "."
			: "Loaded. It is visible on every model call until you unload " + key + ".";
		return Maps.of(
			AbstractLLMAdapter.K_PATH, key,
			Strings.create("loaded"), CVMBool.TRUE,
			AbstractLLMAdapter.K_BUDGET, CVMLong.create(budget),
			Strings.create("note"), Strings.create(note));
	}

	static ACell contextUnload(ACell input, LoadScope scope) {
		AString path = RT.ensureString(RT.getIn(input, AbstractLLMAdapter.K_PATH));
		if (path == null) return Strings.create(
			"Error: path is required — context_unload removes a loaded path from your context. "
			+ "Call with {\"path\": \"...\"} naming a currently loaded entry.");
		if (!scope.writable) return Strings.create(scope.unavailableMessage);

		AMap<AString, ACell> updated = ContextChain.unload(scope.loads, scope.outerLoads, path);
		if (updated == null) return Strings.create("Error: path not in context: " + path);
		scope.loads = updated;
		return Maps.of(
			AbstractLLMAdapter.K_PATH, path,
			Strings.create("unloaded"), CVMBool.TRUE);
	}

	static ACell skillLoad(ACell input, LoadScope scope) {
		if (!scope.writable) return Strings.create(scope.unavailableMessage);
		try {
			Skills.LoadOutcome outcome = Skills.load(scope.engine, scope.context,
				scope.skillSources, input,
				ContextChain.effective(scope.outerLoads, scope.loads));
			if (outcome.entryMeta() != null) {
				scope.loads = scope.loads.assoc(outcome.path(), outcome.entryMeta());
			}
			return outcome.result();
		} catch (RuntimeException e) {
			return Strings.create("Error: skill_load failed: " + describe(e));
		}
	}

	private static String describe(Throwable failure) {
		Throwable cause = AbstractLLMAdapter.unwrap(failure);
		String message = cause.getMessage();
		return (message == null || message.isBlank())
			? cause.getClass().getSimpleName() : message;
	}
}
