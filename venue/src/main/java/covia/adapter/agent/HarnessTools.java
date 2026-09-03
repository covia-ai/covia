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
import convex.core.util.Utils;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Asset;
import covia.venue.Engine;
import covia.venue.RequestContext;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

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

	private static final String BASE = "/adapters/agent/harness/";

	static final AMap<AString, ACell> DEF_CONTEXT_LOAD = definition(BASE + "contextLoad.json");

	static final AMap<AString, ACell> DEF_CONTEXT_UNLOAD = definition(BASE + "contextUnload.json");

	static final AMap<AString, ACell> DEF_SKILL_LOAD = definition(BASE + "skillLoad.json");

	static final AMap<AString, ACell> DEF_MORE_TOOLS = definition(BASE + "moreTools.json");

	/** Stable provider fallback for tools introduced after the initial tools
	 * vector was materialised. Native provider edges may translate the same
	 * persisted state event to their tool-addition block instead. */
	static final AMap<AString, ACell> DEF_INVOKE_TOOL = definition(BASE + "invokeTool.json");

	/** Shared conversation compaction. The summary is agent-authored memory;
	 * the runtime archives the exact replaced conversation beneath it. */
	static final AMap<AString, ACell> DEF_COMPACT = definition(BASE + "compact.json");

	/** The harness tools every runtime provides, by name. */
	static final Map<String, AMap<AString, ACell>> SHARED = Map.of(
		CONTEXT_LOAD, DEF_CONTEXT_LOAD,
		CONTEXT_UNLOAD, DEF_CONTEXT_UNLOAD,
		SKILL_LOAD, DEF_SKILL_LOAD,
		MORE_TOOLS, DEF_MORE_TOOLS,
		INVOKE_TOOL, DEF_INVOKE_TOOL,
		COMPACT, DEF_COMPACT);

	/** Reads ordinary operation metadata without installing it in the venue
	 * catalog, then projects it through the same provider-tool path as a
	 * registered operation. A name override is only for a cycle-local alias. */
	static AMap<AString, ACell> definition(String resourcePath) {
		return definition(resourcePath, null);
	}

	static AMap<AString, ACell> definition(String resourcePath, String nameOverride) {
		try {
			AMap<AString, ACell> metadata = Asset.forString(
				Utils.readResourceAsAString(resourcePath)).meta();
			return ToolPalette.operationToolDefinition(metadata,
				(nameOverride != null) ? Strings.create(nameOverride) : null, null);
		} catch (Exception e) {
			throw new ExceptionInInitializerError(
				"Invalid harness tool resource " + resourcePath + ": " + e.getMessage());
		}
	}

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
	 * remain declared for the persisted context. {@code more_tools} likewise
	 * implies {@code context_unload}, because its result is a durable load. Entries
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
		AMap<AString, ACell> unload = registry.get(CONTEXT_UNLOAD);
		if (names.contains(MORE_TOOLS) && unload != null && names.add(CONTEXT_UNLOAD)) {
			out = out.conj(unload);
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
		Map<String, ACell> oldDefs = definitionsByName(before != null ? before.tools() : null);
		Map<String, ACell> newDefs = definitionsByName(after != null ? after.tools() : null);
		AVector<ACell> additions = Vectors.empty();
		for (long i = 0; after != null && i < after.tools().count(); i++) {
			ACell def = after.tools().get(i);
			AString name = RT.ensureString(RT.getIn(def, AbstractLLMAdapter.K_NAME));
			if (name != null && !def.equals(oldDefs.get(name.toString()))) {
				additions = additions.conj(def);
			}
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

	/**
	 * {@code more_tools}: a tool-only {@link LoadScope} update. Operation refs
	 * resolve once to exact {@code {operation, definition}} bindings, which are
	 * persisted on the load entry. Rendering and dispatch subsequently derive
	 * from that one value, exactly as they do for tools contributed by a skill.
	 */
	@SuppressWarnings("unchecked")
	static ACell moreTools(ACell input, LoadScope scope, Predicate<String> occupiedName) {
		ACell opsCell = RT.getIn(input, K_OPERATIONS);
		if (!(opsCell instanceof AVector<?>)) {
			return Strings.create("Error: operations must be an array of operation paths");
		}
		if (!scope.writable) return Strings.create(scope.unavailableMessage);
		AVector<ACell> requested = (AVector<ACell>) opsCell;
		try {
			// Authorise the whole batch before resolving or storing any binding.
			for (long i = 0; i < requested.count(); i++) {
				AString operation = RT.ensureString(requested.get(i));
				if (operation == null) {
					return Strings.create("Error: operations must contain only operation paths");
				}
				if (!operationPresent(scope, operation)) {
					scope.context.requireExplicitCapability(operation, Abilities.TOOL_LOAD);
				}
			}
		} catch (RuntimeException e) {
			return Strings.create("Error: more_tools denied: " + describe(e));
		}
		AVector<ACell> resolved = ToolPalette.bindingsForOperations(
			scope.engine, scope.context, requested);
		Set<String> addedNames = new HashSet<>();
		AVector<ACell> bindings = Vectors.empty();
		AVector<ACell> operations = Vectors.empty();
		AVector<ACell> names = Vectors.empty();
		for (long i = 0; i < resolved.count(); i++) {
			ACell binding = resolved.get(i);
			ACell definition = RT.getIn(binding, Fields.DEFINITION);
			AString name = RT.ensureString(RT.getIn(definition, AbstractLLMAdapter.K_NAME));
			AString operation = RT.ensureString(RT.getIn(binding, Fields.OPERATION));
			if (name == null || operation == null
					|| (occupiedName != null && occupiedName.test(name.toString()))
					|| !addedNames.add(name.toString())) continue;
			bindings = bindings.conj(binding);
			operations = operations.conj(operation);
			names = names.conj(name);
		}
		if (bindings.isEmpty()) return Maps.of(
			K_ADDED, names,
			K_NOTE, Strings.create("No new tool names were added."));

		AString key = Strings.create("tools/" + bindings.getHash().toHexString());
		AMap<AString, ACell> meta = AbstractLLMAdapter.buildLoadEntryMeta(
			AbstractLLMAdapter.CONTEXT_LOAD_MIN_BUDGET, Strings.create("Tools"))
			.assoc(Loads.K_KIND, Loads.KIND_TOOLS)
			.assoc(Fields.TOOLS, operations)
			.assoc(Loads.K_TOOL_BINDINGS, bindings);
		scope.loads = scope.loads.assoc(key, meta);
		return Maps.of(
			AbstractLLMAdapter.K_PATH, key,
			K_ADDED, names,
			K_NOTE, Strings.create(
				"Tools loaded for this conversation. Use context_unload with path to remove them."));
	}

	/** Whether an operation is already part of the agent's declarative or
	 * load-owned surface. This consults canonical source state only. */
	private static boolean operationPresent(LoadScope scope, AString operation) {
		if (ToolPalette.declaresOperation(scope.config, operation)) return true;
		AMap<AString, ACell> effective = ContextChain.effective(scope.outerLoads, scope.loads);
		for (var entry : effective.entrySet()) {
			AVector<ACell> operations = RT.ensureVector(RT.getIn(entry.getValue(), Fields.TOOLS));
			for (long i = 0; operations != null && i < operations.count(); i++) {
				if (operation.equals(operations.get(i))) return true;
			}
		}
		return Skills.advertisesOperation(scope.engine, scope.context,
			scope.skillSources, effective, operation);
	}

	/** Mutable view of the innermost loads tier for a flat session or frame. */
	static final class LoadScope {
		final Engine engine;
		final RequestContext context;
		final AMap<AString, ACell> outerLoads;
		final boolean writable;
		final String unavailableMessage;
		final Skills.SkillSources skillSources;
		final AMap<AString, ACell> config;
		AMap<AString, ACell> loads;

		LoadScope(Engine engine, RequestContext context,
				AMap<AString, ACell> loads, AMap<AString, ACell> outerLoads,
				boolean writable, String unavailableMessage, Skills.SkillSources skillSources,
				AMap<AString, ACell> config) {
			this.engine = engine;
			this.context = context;
			this.loads = (loads != null) ? loads : Maps.empty();
			this.outerLoads = (outerLoads != null) ? outerLoads : Maps.empty();
			this.writable = writable;
			this.unavailableMessage = unavailableMessage;
			this.skillSources = (skillSources != null) ? skillSources : Skills.SkillSources.EMPTY;
			this.config = config;
		}
	}

	/**
	 * {@code context_load}: pins one entry into the innermost loads tier.
	 * Exactly one source form — {@code path} (the key is the path, as always),
	 * or {@code text} / {@code op} / {@code job} under a caller-chosen
	 * {@code id} (the key). The entry grammar is AGENT_CONTEXT.md §6.2; the
	 * stored spec is what a declared load would carry, so the loads tiers
	 * hold one shape whoever wrote them. An op entry defaults to volatile —
	 * it is observed before every inference and appends only when changed.
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
			? "Loaded as agent-managed context. It is checked before every model call and appends only when changed."
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
			"Error: give exactly one of path or paths — using exact agent-managed load keys.");
		if (!scope.writable) return Strings.create(scope.unavailableMessage);

		if (path != null) return unloadOne(path, scope);
		if (paths.isEmpty() || paths.count() > 50) {
			return Strings.create("Error: paths must contain 1 to 50 agent-managed load keys.");
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
