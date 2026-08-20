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

/** Shared harness-tool handlers parameterised by the writable loads tier. */
final class HarnessTools {

	private HarnessTools() {}

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

	static ACell contextLoad(ACell input, LoadScope scope) {
		AString path = RT.ensureString(RT.getIn(input, AbstractLLMAdapter.K_PATH));
		if (path == null) return Strings.create(
			"Error: path is required — context_load pins a lattice path into your context. "
			+ "Call with {\"path\": \"w/...\"} (optional: \"budget\" in bytes, \"label\").");
		if (!scope.writable) return Strings.create(scope.unavailableMessage);
		try {
			new ContextLoader(scope.engine).requireReadAccess(path, scope.context);
		} catch (RuntimeException e) {
			return Strings.create("Error: context_load denied: " + describe(e));
		}

		long budget = AbstractLLMAdapter.clampLoadBudget(
			RT.getIn(input, AbstractLLMAdapter.K_BUDGET));
		AString label = RT.ensureString(RT.getIn(input, AbstractLLMAdapter.K_LABEL));
		scope.loads = scope.loads.assoc(path,
			AbstractLLMAdapter.buildLoadEntryMeta(budget, label));
		return Maps.of(
			AbstractLLMAdapter.K_PATH, path,
			Strings.create("loaded"), CVMBool.TRUE,
			AbstractLLMAdapter.K_BUDGET, CVMLong.create(budget),
			Strings.create("note"), Strings.create(
				"Path is loaded and will be visible on the next model invocation."));
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
