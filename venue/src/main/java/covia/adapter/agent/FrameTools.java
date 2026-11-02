package covia.adapter.agent;

import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.venue.RequestContext;

/**
 * The harness tools every runtime shares, for one frame, and the state they
 * act on: the frame as this cycle is building it, its loads, the tools it is
 * offered, and a compaction it asked for (AGENT_LOOP.md §3.6). llmagent works
 * its root frame with one; goaltree extends it for each frame with the
 * hierarchy controls. The handlers change {@link #activeFrame}; the runtime's
 * tool loop persists it.
 */
class FrameTools {

	/** The reply to {@code compact}: how many turns it archived. */
	static final String COMPACTED = "Compacted %d turns into an archived summary. Context freed.";

	final AbstractLLMAdapter runtime;
	/** The agent's authority: every tool call and load runs under it. */
	final RequestContext ctx;
	final TaskTools.Tasks tasks;
	final long toolCallTimeoutMs;
	/** Effective loads of the agent tier ({@code config.loads}), read-only here. */
	final AMap<AString, ACell> outerLoads;
	/** The agent's config as stored: what its tools, skills and loads come from. */
	final AMap<AString, ACell> sourceConfig;
	final boolean cachePrefix;
	final AString labels;
	final Skills.SkillSources skillSources;
	final FrameStore store;
	final int frameIndex;

	/** Routes of the fixed tools; the manifest's once a projection is rendered. */
	AMap<AString, ACell> toolIndex;
	/** Names in the tool list this frame is sent. */
	Set<String> offered = Set.of();
	/** The frame as this cycle is building it. */
	AMap<AString, ACell> activeFrame;
	/** A summary {@code compact} asked for, applied before the next inference. */
	String pendingCompactSummary;
	/** Text of the assistant message whose tool batch is running: the payload
	 *  of an empty {@code complete_task} (the dual of #215). */
	AString turnText;
	int baseToolStart;
	int baseToolEnd;
	/** Routes the effective loads contribute, from the latest snapshot. */
	AMap<AString, ACell> loadToolIndex = Maps.empty();
	AMap<AString, ACell> pinnedToolIndex = Maps.empty();

	/**
	 * @param cycleSpec the cycle's Spec: the agent's authority, its labels,
	 *        cache policy and config as stored
	 * @param toolIndex routes of the configured tools
	 * @param outerLoads effective loads of the agent tier
	 */
	FrameTools(AbstractLLMAdapter runtime, ContextAssembler.Spec cycleSpec, TaskTools.Tasks tasks,
			AMap<AString, ACell> toolIndex, AMap<AString, ACell> outerLoads, long toolCallTimeoutMs,
			FrameStore store, int frameIndex) {
		this.runtime = runtime;
		this.ctx = cycleSpec.capsCtx();
		this.tasks = tasks;
		this.toolIndex = (toolIndex != null) ? toolIndex : Maps.empty();
		this.outerLoads = (outerLoads != null) ? outerLoads : Maps.empty();
		this.toolCallTimeoutMs = toolCallTimeoutMs;
		this.sourceConfig = cycleSpec.sourceConfig();
		this.cachePrefix = cycleSpec.cachePrefix();
		this.labels = cycleSpec.labels();
		this.skillSources = Skills.sourcesOf(sourceConfig);
		this.store = store;
		this.frameIndex = frameIndex;
		reload();
	}

	/** The task tools, the shared harness tools and, for every other name,
	 *  dispatch as an operation. */
	ToolCycleEngine.Registry<FrameTools> registry() {
		return new ToolCycleEngine.Registry<FrameTools>()
			.activityLabels((name, ignored) -> activityLabel(name))
			// The framework's task boundary (TaskTools): a task resolved here
			// reaches its job at tool time.
			.register(TaskTools.COMPLETE, (call, ignored) -> tasks.complete(call, turnText))
			.register(TaskTools.FAIL, (call, ignored) -> tasks.fail(call, turnText))
			.register(HarnessTools.CONTEXT_LOAD, (call, ignored) -> contextLoad(call))
			.register(HarnessTools.CONTEXT_UNLOAD, (call, ignored) -> contextUnload(call))
			.register(HarnessTools.SKILL_LOAD, (call, ignored) -> skillLoad(call))
			.register(HarnessTools.MORE_TOOLS, (call, ignored) -> moreTools(call))
			.register(HarnessTools.INVOKE_TOOL, (call, ignored) -> invokeTool(call))
			.register(HarnessTools.COMPACT, (call, ignored) -> compact(call))
			.fallback((call, ignored) -> ToolCycleEngine.ToolOutcome.result(
				dispatch(call.name(), call.input())));
	}

	/**
	 * Reads this frame afresh from the store: null once the store no longer
	 * holds it.
	 *
	 * @return the stack it was read from
	 */
	AVector<ACell> reload() {
		AVector<ACell> frames = store.frames();
		activeFrame = (frameIndex < frames.count()) ? RT.ensureMap(frames.get(frameIndex)) : null;
		return frames;
	}

	void appendTurn(AMap<AString, ACell> turn) {
		if (activeFrame != null) activeFrame = GoalTreeContext.appendTurn(activeFrame, turn);
	}

	AMap<AString, ACell> loads() {
		return (activeFrame != null) ? GoalTreeContext.getLoads(activeFrame) : Maps.empty();
	}

	void setLoads(AMap<AString, ACell> loads) {
		if (activeFrame != null) activeFrame = GoalTreeContext.withLoads(activeFrame, loads);
	}

	// ========== Tools the loads contribute ==========

	/**
	 * Resolves the effective loads for the next inference and adopts the tools
	 * they contribute. The route set is replaced whole, so unloading a source
	 * retracts both its advertised definition and its dispatch route.
	 */
	Loads.Snapshot refreshLoadSnapshot() {
		Loads.Snapshot snapshot = Loads.resolveForInference(runtime.engine, ctx,
			ContextChain.effective(outerLoads, loads()), this::excludesLoadName, labels,
			!renderingCurrent());
		adoptLoadSnapshot(snapshot);
		return snapshot;
	}

	void adoptLoadSnapshot(Loads.Snapshot snapshot) {
		loadToolIndex = snapshot.toolIndex();
		pinnedToolIndex = snapshot.pinnedToolIndex();
	}

	/**
	 * The cached projection of the Spec's initial render, with the manifest's
	 * routes, which dispatch then uses too. Null for an uncached model.
	 */
	ContextAssembler.Rendered rendering(ContextAssembler.Spec spec) {
		if (!spec.cachePrefix()) return null;
		AMap<AString, ACell> fixedIndex = ToolPalette.mergeIndex(toolIndex, pinnedToolIndex);
		fixedIndex = ToolPalette.mergeIndex(fixedIndex, ToolPalette.loadOwners(loadToolIndex));
		AMap<AString, ACell> manifestIndex = new ToolPalette.Palette(
			null, fixedIndex, null).forManifest(spec.tools()).toolIndex();
		int baseStart = spec.toolCalling() ? baseToolStart : 0;
		int baseEnd = spec.toolCalling() ? baseToolEnd : 0;
		toolIndex = manifestIndex;
		return ContextAssembler.initialise(spec).withToolIndex(manifestIndex, baseStart, baseEnd);
	}

	/** Whether the frame holds a projection rendered from the agent's current
	 *  config, so pinned context need not be resolved again. */
	private boolean renderingCurrent() {
		return activeFrame != null && ContextAssembler.rendered(
			Vectors.of((ACell) activeFrame), sourceConfig, cachePrefix) != null;
	}

	// ========== Routes ==========

	AString operation(String name) {
		AString operation = ToolPalette.operation(loadToolIndex, name);
		return (operation != null) ? operation : ToolPalette.operation(toolIndex, name);
	}

	String activityLabel(String name) {
		return (ToolPalette.operation(loadToolIndex, name) != null)
			? ToolPalette.labelFor(loadToolIndex, name).toString()
			: ToolPalette.labelFor(toolIndex, name).toString();
	}

	/** Every tool name offered: the fixed tools and those the loads added. */
	private Set<String> callableNames() {
		Set<String> names = ToolPalette.names(loadToolIndex);
		names.addAll(offered);
		return names;
	}

	private boolean excludesLoadName(String name, AString owner) {
		return ToolPalette.excludesLoadName(toolIndex, runtime.harnessToolNames(), name, owner);
	}

	private boolean containsFixedName(String name) {
		return runtime.harnessToolNames().contains(name) || toolIndex.containsKey(Strings.create(name));
	}

	/** Runs a tool the palette routes; any other name is answered by the
	 *  runtime's rules for unbound names. */
	ACell dispatch(String name, ACell input) {
		AString operation = operation(name);
		if (operation != null) return runtime.dispatchTool(name, input, operation, ctx, toolCallTimeoutMs);
		return runtime.dispatchUnbound(name, input, ctx,
			new HarnessTools.ToolSurface(loadScope(), callableNames()), toolCallTimeoutMs);
	}

	// ========== The shared harness tools ==========

	private HarnessTools.LoadScope loadScope() {
		return new HarnessTools.LoadScope(runtime.engine, ctx, loads(), outerLoads,
			skillSources, sourceConfig);
	}

	private ToolCycleEngine.ToolOutcome contextLoad(ToolCycleEngine.ToolCall call) {
		HarnessTools.LoadScope scope = loadScope();
		AMap<AString, ACell> before = scope.loads;
		ACell result = HarnessTools.contextLoad(call.input(), scope);
		return loadedOutcome(call, scope, before, result);
	}

	/**
	 * {@code skill_load}: {@link Skills#load} owns what a skill is; the entry
	 * it returns is written into the loads as {@code context_load} writes one,
	 * and rendering and tool activation follow from it.
	 */
	private ToolCycleEngine.ToolOutcome skillLoad(ToolCycleEngine.ToolCall call) {
		HarnessTools.LoadScope scope = loadScope();
		AMap<AString, ACell> before = scope.loads;
		ACell result = HarnessTools.skillLoad(call.input(), scope);
		return loadedOutcome(call, scope, before, result);
	}

	/** {@code more_tools}: a durable tool-only load. */
	private ToolCycleEngine.ToolOutcome moreTools(ToolCycleEngine.ToolCall call) {
		HarnessTools.LoadScope scope = loadScope();
		AMap<AString, ACell> before = scope.loads;
		Loads.Snapshot active = loadSnapshot(before);
		ACell result = HarnessTools.moreTools(call.input(), scope,
			name -> containsFixedName(name)
				|| active.toolIndex().containsKey(Strings.create(name)));
		return loadedOutcome(call, scope, before, result);
	}

	/** A load the call changed is appended to the conversation as it reads now,
	 *  with any change to the tools it contributes. */
	@SuppressWarnings("unchecked")
	private ToolCycleEngine.ToolOutcome loadedOutcome(ToolCycleEngine.ToolCall call,
			HarnessTools.LoadScope scope, AMap<AString, ACell> before, ACell result) {
		AString key = RT.ensureString(RT.getIn(result, AbstractLLMAdapter.K_PATH));
		if (key == null || before.equals(scope.loads)) {
			setLoads(scope.loads);
			return ToolCycleEngine.ToolOutcome.result(result);
		}
		AString eventId = ContextAssembler.contextEventId(call.id(), call.iteration(), key);
		Loads.Snapshot beforeSnapshot = loadSnapshot(before);
		Loads.Append appended = Loads.append(runtime.engine, ctx, scope.loads, key, labels, eventId);
		setLoads(appended.loads());
		Loads.Snapshot afterSnapshot = loadSnapshot(appended.loads());
		adoptLoadSnapshot(afterSnapshot);
		AVector<ACell> events = (AVector<ACell>) appended.messages().concat(
			HarnessTools.toolStateEvent(beforeSnapshot, afterSnapshot));
		return ToolCycleEngine.ToolOutcome.result(result, events);
	}

	private ToolCycleEngine.ToolOutcome contextUnload(ToolCycleEngine.ToolCall call) {
		HarnessTools.LoadScope scope = loadScope();
		Loads.Snapshot before = loadSnapshot(scope.loads);
		ACell result = HarnessTools.contextUnload(call.input(), scope);
		setLoads(scope.loads);
		Loads.Snapshot after = loadSnapshot(scope.loads);
		adoptLoadSnapshot(after);
		return ToolCycleEngine.ToolOutcome.result(result, HarnessTools.toolStateEvent(before, after));
	}

	private ToolCycleEngine.ToolOutcome invokeTool(ToolCycleEngine.ToolCall call) {
		HarnessTools.Invocation invocation = HarnessTools.invocation(call.input());
		if (invocation.error() != null) return ToolCycleEngine.ToolOutcome.result(invocation.error());
		return ToolCycleEngine.ToolOutcome.result(dispatch(invocation.name(), invocation.input()));
	}

	/** {@code compact}: the runtime applies the summary before the next inference. */
	private ToolCycleEngine.ToolOutcome compact(ToolCycleEngine.ToolCall call) {
		HarnessTools.Compaction compact = HarnessTools.compaction(call.input());
		if (compact.error() != null) return ToolCycleEngine.ToolOutcome.result(compact.error());
		long turns = (activeFrame != null) ? GoalTreeContext.countLiveTurns(activeFrame) : 0;
		pendingCompactSummary = compact.summary();
		return ToolCycleEngine.ToolOutcome.result(Strings.create(COMPACTED.formatted(turns)));
	}

	private Loads.Snapshot loadSnapshot(AMap<AString, ACell> frameLoads) {
		return Loads.describe(runtime.engine, ctx, ContextChain.effective(outerLoads, frameLoads),
			this::excludesLoadName, !renderingCurrent());
	}
}
