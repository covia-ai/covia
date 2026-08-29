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
import convex.core.util.JSON;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.RequestContext;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The harness tools every runtime provides, and the one rule for offering
 * them: opt-in by name in {@code config.tools}, plus what the situation
 * itself implies — {@code skill_load} when the agent declares skills, and
 * {@code context_unload} when skills make persistent loads relevant. The
 * manifest stays fixed while the writable tier changes. A runtime adds only
 * its specialised tools to the registry (goaltree: {@code subgoal},
 * {@code complete}, {@code fail}); the stable task tools are
 * {@link TaskTools}. The handlers here are parameterised by the writable loads
 * tier, which is the only thing the runtimes differ in.
 */
final class HarnessTools {
	private HarnessTools() {}

	static final String CONTEXT_LOAD   = "context_load";
	static final String CONTEXT_UNLOAD = "context_unload";
	static final String SKILL_LOAD     = "skill_load";
	static final String MORE_TOOLS     = "more_tools";
	static final String INVOKE_TOOL    = "invoke_tool";
	static final String COMPACT        = "compact";

	private static final AString K_OPERATIONS = Strings.intern("operations");
	private static final AString K_ADDED      = Strings.intern("added");
	private static final AString K_NOTE       = Strings.intern("note");
	private static final AString K_ERRORS     = Strings.intern("errors");
	private static final AString K_NAME       = Strings.intern("name");
	private static final AString K_INPUT      = Strings.intern("input");
	private static final AString K_SUMMARY    = Strings.intern("summary");
	static final AString K_TOOL_ADDITION      = Strings.intern("toolAddition");
	static final AString K_TOOL_REMOVAL       = Strings.intern("toolRemoval");

	static final AMap<AString, ACell> DEF_CONTEXT_LOAD = Maps.of(
		AbstractLLMAdapter.K_NAME, Strings.create(CONTEXT_LOAD),
		AbstractLLMAdapter.K_DESCRIPTION, Strings.create(
			"Keep something visible in your context across turns until you remove it: a lattice "
			+ "path loaded now, a note you write (text), a read-only operation whose fresh result you "
			+ "want each turn (op), or a finished job's result (job). "
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
			"Deliberately remove agent-managed persistent context. Accepts only exact map keys from a "
			+ "loaded_context result; use paths to remove several together. Never pass a display label, "
			+ "a source or operation path from pinned_context, or an argument from an ordinary tool call. "
			+ "Ordinary results such as covia_read and covia_inspect belong to conversation history, are "
			+ "not persistent loads, and need no cleanup. Operator/caller pinned_context cannot be removed."),
		AbstractLLMAdapter.K_PARAMETERS, AbstractLLMAdapter.CONTEXT_UNLOAD_PARAMS);

	static final AMap<AString, ACell> DEF_SKILL_LOAD = Maps.of(
		AbstractLLMAdapter.K_NAME, Strings.create(SKILL_LOAD),
		AbstractLLMAdapter.K_DESCRIPTION, Strings.create(
			"Load a skill from the [Skills] index by name (or any skill by direct ref). "
			+ "The result acknowledges activation; the skill instructions are appended once and "
			+ "stay in your context across turns. Their loaded skill header names the exact unload "
			+ "key if you later need to remove one; routine cleanup is unnecessary. The skill's tools "
			+ "join your palette from your next step."),
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

	/** Stable provider fallback for tools introduced after the initial tools
	 * vector was materialised. Native provider edges may translate the same
	 * persisted state event to their tool-addition block instead. */
	static final AMap<AString, ACell> DEF_INVOKE_TOOL = Maps.of(
		AbstractLLMAdapter.K_NAME, Strings.create(INVOKE_TOOL),
		AbstractLLMAdapter.K_DESCRIPTION, Strings.create(
			"Invoke a tool that a later tool-addition system event made available. "
			+ "Use the exact added tool name and pass that tool's arguments in input. "
			+ "Do not use this for tools already offered directly."),
		AbstractLLMAdapter.K_PARAMETERS, Maps.of(
			AbstractLLMAdapter.K_TYPE, Strings.create("object"),
			AbstractLLMAdapter.K_PROPERTIES, Maps.of(
				K_NAME, Maps.of(
					AbstractLLMAdapter.K_TYPE, Strings.create("string"),
					AbstractLLMAdapter.K_DESCRIPTION, Strings.create("Exact name from a tool-addition event")),
				K_INPUT, Maps.of(
					AbstractLLMAdapter.K_TYPE, Strings.create("object"),
					AbstractLLMAdapter.K_DESCRIPTION, Strings.create("Arguments for the added tool"))),
			AbstractLLMAdapter.K_REQUIRED, Vectors.of((ACell) K_NAME)));

	/** Shared conversation compaction. The summary is agent-authored memory;
	 * the runtime archives the exact replaced conversation beneath it. */
	static final AMap<AString, ACell> DEF_COMPACT = Maps.of(
		AbstractLLMAdapter.K_NAME, Strings.create(COMPACT),
		AbstractLLMAdapter.K_DESCRIPTION, Strings.create(
			"Archive the conversation so far under a summary you write, freeing context "
			+ "space for future turns while retaining the exact archived history for audit. "
			+ "Use after a significant chunk of work when you still have more to do. "
			+ "Capture key findings, decisions, active constraints and what remains."),
		AbstractLLMAdapter.K_PARAMETERS, Maps.of(
			AbstractLLMAdapter.K_TYPE, Strings.create("object"),
			AbstractLLMAdapter.K_PROPERTIES, Maps.of(
				K_SUMMARY, Maps.of(
					AbstractLLMAdapter.K_TYPE, Strings.create("string"),
					AbstractLLMAdapter.K_DESCRIPTION, Strings.create(
						"Required summary of what future turns need to remember"))),
			AbstractLLMAdapter.K_REQUIRED, Vectors.of((ACell) K_SUMMARY)));

	/** The harness tools every runtime provides, by name. */
	static final Map<String, AMap<AString, ACell>> SHARED = Map.of(
		CONTEXT_LOAD, DEF_CONTEXT_LOAD,
		CONTEXT_UNLOAD, DEF_CONTEXT_UNLOAD,
		SKILL_LOAD, DEF_SKILL_LOAD,
		MORE_TOOLS, DEF_MORE_TOOLS,
		INVOKE_TOOL, DEF_INVOKE_TOOL,
		COMPACT, DEF_COMPACT);

	/** Validated request supplied to {@code compact}. */
	record Compaction(String summary, ACell error) {}

	static Compaction compaction(ACell input) {
		AString summary = RT.ensureString(RT.getIn(input, K_SUMMARY));
		if (summary == null || summary.toString().isBlank()) {
			return new Compaction(null, Strings.create(
				"Error: summary is required — compact must preserve the important work so far."));
		}
		return new Compaction(summary.toString(), null);
	}

	/**
	 * The harness tools a cycle offers from a runtime's registry: those the
	 * agent opted into by name in {@code config.tools}, in that order — and,
	 * when the agent declares skill sources, {@code skill_load} and
	 * {@code context_unload}: the skills index is rendered, so both definitions
	 * remain declared for the persisted context. Entries
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
		// The fallback must be in the immutable initial manifest whenever this
		// context can acquire definitions later. It remains unused otherwise.
		if ((names.contains(SKILL_LOAD) || names.contains(MORE_TOOLS))
				&& names.add(INVOKE_TOOL)) {
			out = out.conj(registry.get(INVOKE_TOOL));
		}
		return out;
	}

	/** Decoded request for the fixed dynamic-tool dispatcher. */
	record Invocation(String name, ACell input, ACell error) {}

	static Invocation invocation(ACell value) {
		AString name = RT.ensureString(RT.getIn(value, K_NAME));
		if (name == null || name.toString().isBlank()) {
			return new Invocation(null, null,
				Strings.create("Error: invoke_tool requires the exact added tool name"));
		}
		ACell input = RT.getIn(value, K_INPUT);
		return new Invocation(name.toString(), (input != null) ? input : Maps.empty(), null);
	}

	/** One trusted, append-only tool-state event. Exact definitions are retained
	 * so a provider edge can map them natively and the generic fallback can
	 * explain the same state to the model. */
	static AVector<ACell> toolStateEvent(AVector<ACell> additions, AVector<ACell> removals) {
		additions = (additions != null) ? additions : Vectors.empty();
		removals = (removals != null) ? removals : Vectors.empty();
		if (additions.isEmpty() && removals.isEmpty()) return Vectors.empty();
		StringBuilder text = new StringBuilder("Tool availability changed.");
		if (!additions.isEmpty()) {
			text.append(" The following tools are now available. On this provider call them through ")
				.append(INVOKE_TOOL).append(" using their exact name and arguments: ")
				.append(JSON.print(additions));
		}
		if (!removals.isEmpty()) {
			text.append(" These tools are no longer available: ").append(JSON.print(removals)).append('.');
		}
		AMap<AString, ACell> event = Maps.of(
			Strings.intern("role"), Strings.intern("system"),
			Strings.intern("content"), Strings.create(text.toString()));
		if (!additions.isEmpty()) event = event.assoc(K_TOOL_ADDITION, additions);
		if (!removals.isEmpty()) event = event.assoc(K_TOOL_REMOVAL, removals);
		return Vectors.of(event);
	}

	/** Minimal changed definitions/removals between two active-load snapshots. */
	static AVector<ACell> toolStateEvent(Loads.Snapshot before, Loads.Snapshot after) {
		return toolStateEvent(before, after, Set.of());
	}

	/** Tool-state delta excluding definitions already present in the immutable
	 * provider manifest. Their skill body announces activation; repeating full
	 * schemas as text would waste context and contradict native declarations. */
	static AVector<ACell> toolStateEvent(Loads.Snapshot before, Loads.Snapshot after,
			Set<String> alreadyDeclared) {
		Map<String, ACell> oldDefs = definitionsByName(before != null ? before.tools() : null);
		Map<String, ACell> newDefs = definitionsByName(after != null ? after.tools() : null);
		AVector<ACell> additions = Vectors.empty();
		for (long i = 0; after != null && i < after.tools().count(); i++) {
			ACell def = after.tools().get(i);
			AString name = RT.ensureString(RT.getIn(def, AbstractLLMAdapter.K_NAME));
			if (name != null && !alreadyDeclared.contains(name.toString())
					&& !def.equals(oldDefs.get(name.toString()))) additions = additions.conj(def);
		}
		AVector<ACell> removals = Vectors.empty();
		for (long i = 0; before != null && i < before.tools().count(); i++) {
			AString name = RT.ensureString(RT.getIn(before.tools().get(i), AbstractLLMAdapter.K_NAME));
			if (name != null && !newDefs.containsKey(name.toString())) removals = removals.conj(name);
		}
		return toolStateEvent(additions, removals);
	}

	private static Map<String, ACell> definitionsByName(AVector<ACell> defs) {
		Map<String, ACell> out = new HashMap<>();
		for (long i = 0; defs != null && i < defs.count(); i++) {
			AString name = RT.ensureString(RT.getIn(defs.get(i), AbstractLLMAdapter.K_NAME));
			if (name != null) out.put(name.toString(), defs.get(i));
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
			? "Loaded as agent-managed context. It re-renders at the end of every model call (never cached)."
			: "Loaded as agent-managed context across turns.";
		return Maps.of(
			AbstractLLMAdapter.K_PATH, key,
			Strings.create("loaded"), CVMBool.TRUE,
			AbstractLLMAdapter.K_BUDGET, CVMLong.create(budget),
			Strings.create("note"), Strings.create(note));
	}

	static ACell contextUnload(ACell input, LoadScope scope) {
		AString path = RT.ensureString(RT.getIn(input, AbstractLLMAdapter.K_PATH));
		AVector<ACell> paths = RT.ensureVector(RT.getIn(input, AbstractLLMAdapter.K_PATHS));
		if ((path == null) == (paths == null)) return Strings.create(
			"Error: give exactly one of path or paths — using exact keys from loaded_context.");
		if (!scope.writable) return Strings.create(scope.unavailableMessage);

		if (path != null) return unloadOne(path, scope);
		if (paths.isEmpty() || paths.count() > 50) {
			return Strings.create("Error: paths must contain 1 to 50 loaded_context keys.");
		}
		AVector<ACell> unloaded = Vectors.empty();
		AVector<ACell> errors = Vectors.empty();
		Set<AString> seen = new HashSet<>();
		for (long i = 0; i < paths.count(); i++) {
			AString key = RT.ensureString(paths.get(i));
			if (key == null) {
				errors = errors.conj(Maps.of(AbstractLLMAdapter.K_PATH, paths.get(i),
					Fields.ERROR, Strings.create("key must be a string")));
				continue;
			}
			if (!seen.add(key)) continue;
			ACell result = unloadOne(key, scope);
			if (CVMBool.TRUE.equals(RT.getIn(result, "unloaded"))) {
				unloaded = unloaded.conj(RT.getIn(result, AbstractLLMAdapter.K_PATH));
			} else {
				errors = errors.conj(Maps.of(AbstractLLMAdapter.K_PATH, key,
					Fields.ERROR, result));
			}
		}
		AMap<AString, ACell> result = Maps.of(Strings.create("unloaded"), unloaded);
		if (!errors.isEmpty()) result = result.assoc(K_ERRORS, errors);
		return result;
	}

	private static ACell unloadOne(AString path, LoadScope scope) {
		ACell local = scope.loads.get(path);
		if (local != null && Loads.isAgentManaged(local)) {
			// Removing a local shadow reveals any pinned outer value; unloading
			// must never manufacture a tombstone over operator context.
			scope.loads = scope.loads.dissoc(path);
			return Maps.of(AbstractLLMAdapter.K_PATH, path,
				Strings.create("unloaded"), CVMBool.TRUE);
		}
		if (local != null || (scope.outerLoads != null && scope.outerLoads.get(path) != null)) {
			return Strings.create("Error: key belongs to pinned_context and cannot be unloaded: " + path);
		}
		return Strings.create("Error: key not in loaded_context: " + path
			+ ". Ordinary tool results are conversation history, not persistent loads, and need no cleanup.");
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
