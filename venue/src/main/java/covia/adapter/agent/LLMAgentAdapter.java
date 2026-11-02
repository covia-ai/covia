package covia.adapter.agent;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.AgentAdapter;
import covia.adapter.agent.ContextInspectable.Inspection;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.venue.AgentState;
import covia.venue.RequestContext;

/**
 * LLM-backed transition function for agents (level 2).
 *
 * <p>Invoked by the agent run loop as a transition operation ({@code llmagent:chat}).
 * Uses the same durable frame store as the goal-tree runtime, with one root
 * frame, and delegates LLM calls to a level 3 grid operation (for example
 * {@code langchain:openai}).</p>
 *
 * <p>This adapter has no LLM library dependencies — it works entirely with
 * structured message maps and invokes level 3 via the grid operation dispatch.</p>
 *
 * <h3>Tool palette</h3>
 * <p>Agents are advertised exactly the tools they declare in config
 * {@code tools} (strict allowlist, #92). {@code defaultTools: true} adds the
 * deliberately minimal read-only pack in
 * {@link ToolPalette#DEFAULT_TOOL_OPS}; capability tools arrive via skills
 * ({@code skill_load}). The task tools ({@code complete_task},
 * {@code fail_task}) are always offered; which harness tools are offered,
 * and that only those run, is AGENT_LOOP.md §3.6.</p>
 *
 * <p>Additional tools can be configured via {@code tools} in the agent's config.
 * Each entry is a string (operation name) or map with {@code operation} plus optional
 * {@code name} and {@code description} overrides. Config tools are resolved from
 * adapter functions or grid operations and flattened as direct tools for the LLM.</p>
 *
 * <h3>Message format</h3>
 * <p>All messages in history use a common map format:</p>
 * <ul>
 *   <li>{@code {role: "system"|"user", content: "..."}}</li>
 *   <li>{@code {role: "assistant", content: "...", toolCalls?: [{id, name, arguments: {...}}]}}</li>
 *   <li>{@code {role: "tool", id: "...", name: "...", content: "..."}}</li>
 * </ul>
 *
 * <h3>Tool call loop</h3>
 * <p>When level 3 returns an assistant message with {@code toolCalls}, level 2
 * executes each tool as a grid operation, appends tool result messages, and
 * calls level 3 again. This loops until the LLM returns a text response
 * (no tool calls) or the tool-call iteration limit is reached (venue
 * config {@code maxToolIterations}, default 30, overridable per agent via
 * {@code config.maxToolIterations}). The shared {@code compact} harness tool
 * replaces the visible prefix with assistant memory while retaining the exact
 * old vector through a backward history reference.</p>
 *
 * <h3>Conversation structure</h3>
 * <pre>
 * { "frames": [{
 *   "description": "Process incoming work",
 *   "historyVersion": 1, "contextStart": 0,
 *   "history": [
 *     { "role": "user",      "content": "Hello" },
 *     { "role": "assistant", "content": "Hi there!" }
 *   ]
 * }]}
 * </pre>
 */
public class LLMAgentAdapter extends AbstractLLMAdapter implements FramesOwning {

	private static final Logger log = LoggerFactory.getLogger(LLMAgentAdapter.class);

	/** A cycle that lost its session claim: what it was doing. */
	static final String SESSION_SUPERSEDED = "Session cycle was superseded while %s";

	// ========== Default tool definitions ==========
	// MCP-style: {name, description, parameters: {type: "object", properties: {...}, required: [...]}}

	/** Harness pseudo-tools this runtime provides — intercepted by the adapter,
	 *  never dispatched as operations (see {@link AbstractLLMAdapter#dispatchTool}):
	 *  the shared registry plus the task tools. */
	static final java.util.Set<String> HARNESS_TOOL_NAMES;
	static {
		java.util.Set<String> names = new java.util.HashSet<>(HarnessTools.SHARED.keySet());
		names.addAll(TaskTools.NAMES);
		HARNESS_TOOL_NAMES = java.util.Set.copyOf(names);
	}

	@Override
	protected java.util.Set<String> harnessToolNames() {
		return HARNESS_TOOL_NAMES;
	}

	@Override
	public String getName() {
		return "llmagent";
	}

	@Override
	public String getDescription() {
		return "LLM-backed transition function for agents. Maintains conversation "
			+ "history in the shared durable frame format, processes inbox messages as user turns, and "
			+ "invokes a level 3 grid operation for LLM calls. Supports tool call "
			+ "loops: when the LLM requests tool calls, executes them as grid "
			+ "operations and feeds results back until a text response is produced. "
			+ "Built-in tools: complete_task and fail_task, which resolve the "
			+ "agent's task. All other tools dispatch via the grid.";
	}

	@Override
	protected void installAssets() {
		installAsset("llmagent/chat", "/adapters/llmagent/chat.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		return CompletableFuture.supplyAsync(() -> processChat(ctx, input), VIRTUAL_EXECUTOR);
	}

	/** What inspection and step share with a live cycle: the Spec, the root
	 *  frame's harness tools, and the cycle's fixed tools. */
	private record Preview(ContextAssembler.Spec spec, FrameTools frameTools, AVector<ACell> fixedTools,
			ContextAssembler.Diagnostics diagnostics) {}

	/**
	 * The transition a call with these inputs would start: the session's
	 * conversation, the inbox, pending results, and a task rendered exactly as
	 * the tool loop renders it — task tools included.
	 */
	@SuppressWarnings("unchecked")
	private Preview preview(Inspection in, RequestContext ctx) {
		AMap<AString, ACell> sourceConfig = in.config();
		ModelSetup model = modelSetup(sourceConfig, ctx);
		AMap<AString, ACell> config = model.config();
		// Same scope-chain view as processChat (agent tier + root frame), so
		// the inspected skills index carries the right (loaded) markers.
		AMap<AString, ACell> configLoads = ContextChain.operatorLoads(
			RT.getIn(config, Fields.LOADS), "config.loads");
		AMap<AString, ACell> frameLoads = ContextChain.sessionRootLoads(in.session());

		RequestContext capsCtx = capsContext(config, ctx);
		ModelProfile profile = model.profile();
		boolean cachePrefix = promptCaching(profile, config);
		AVector<ACell> sessionFrames = sessionFramesOf(in.session());
		if (sessionFrames != null && !sessionFrames.isEmpty()) {
			sessionFrames = GoalTreeContext.withRootLoads(sessionFrames, frameLoads);
		}
		FixedPalette fixed = fixedPalette(sourceConfig, ctx,
			sessionFrames, cachePrefix);
		AVector<ACell> harness = fixed.harness();
		AVector<ACell> fixedTools = fixed.tools();

		// The task renders through the tool loop's own renderer — a preview
		// job id stands in for the one a real task would carry.
		AVector<ACell> tasks = (in.task() != null)
			? Vectors.of((ACell) Maps.of(Fields.JOB_ID, TaskTools.PREVIEW_JOB_ID, Fields.INPUT, in.task()))
			: null;
		AVector<ACell> previewFrames = (sessionFrames != null) ? sessionFrames : Vectors.empty();
		if (previewFrames.isEmpty()) {
			previewFrames = Vectors.of((ACell) GoalTreeContext.createFrame("", frameLoads));
		}
		// The cycle's input as a live cycle's start writes it: the messages,
		// then the task's request turn.
		previewFrames = FrameStore.appendCycleInputTurns(previewFrames, in.messages(),
			(in.task() != null) ? Maps.of(Fields.NEW_INPUT, in.task()) : null,
			convex.core.util.Utils.getCurrentTimestamp());
		boolean hasInput = (in.messages() != null && in.messages().count() > 0)
			|| (in.pending() != null && in.pending().count() > 0)
			|| in.task() != null;
		ContextAssembler.Spec spec = new ContextAssembler.Spec(
			engine, ctx, capsCtx, config,
			ContextAssembler.sessionHex(RT.getIn(in.session(), Fields.ID)), null,
			profile.budget(), profile.labels(), profile.toolCalling(),
			fixedTools, null, null,
			previewFrames, in.pending(), null, hasInput, null, null,
			fixed.unavailable(), null, null)
			.withSourceConfig(sourceConfig)
			.withCachePrefix(cachePrefix);
		FrameTools frameTools = frameTools(spec, tasks, configLoads, fixed,
			new FrameStore.LocalFrameStore(previewFrames), true);

		// The loads ride in exactly as the tool loop sets them — stable elements
		// through withLoads and watched values through the frame observation — so an inspected
		// context matches a live inference by construction.
		Loads.Snapshot loads = frameTools.refreshLoadSnapshot();
		spec = spec.withLoads(loads, concatTools(fixedTools, loads.tools()))
			.withTask(frameTools.tasks.message());
		if (!frameTools.store.observe(0, ContextAssembler.observations(spec, loads),
				convex.core.util.Utils.getCurrentTimestamp())) {
			throw new IllegalStateException("Could not preview watched context observations");
		}
		spec = spec.withFrames(frameTools.reload());
		AVector<ACell> entries = Vectors.empty();
		if (profile.toolCalling()) {
			entries = (AVector<ACell>) ToolPalette.provenance(TaskTools.DEFINITIONS, "harness")
				.concat(ToolPalette.provenance(harness, "harness"))
				.concat(fixed.provenance())
				.concat(loads.toolProvenance());
		}
		ContextAssembler.Diagnostics diagnostics = new ContextAssembler.Diagnostics(
			entries, loads.diagnostics(), fixed.unavailable());
		return new Preview(spec, frameTools, fixedTools, diagnostics);
	}

	@Override
	protected InspectionContext inspectionContext(Inspection in, RequestContext ctx) {
		Preview p = preview(in, ctx);
		return new InspectionContext(p.spec(), p.diagnostics());
	}

	/**
	 * One iteration of the tool loop on the supplied reply: text-as-control
	 * recognised as live, the batch dispatched through the live registry —
	 * a task resolution judged and recorded but never reaching a job — and the next
	 * prompt rebuilt as the loop rebuilds it: appended loads reused, watched loads observed, a resolved task
	 * gone from the tail, this iteration's turns in band.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> stepContext(Inspection in, AMap<AString, ACell> assistant, RequestContext ctx) {
		Preview p = preview(in, ctx);
		FrameTools frameTools = p.frameTools();
		AMap<AString, ACell> reply = assistant;
		AVector<ACell> calls = RT.ensureVector(reply.get(K_TOOL_CALLS));
		if (calls == null && p.spec().task() != null) {
			AMap<AString, ACell> rewritten = recogniseTextualControlCall(reply, 0);
			if (rewritten != null) {
				reply = rewritten;
				calls = RT.ensureVector(reply.get(K_TOOL_CALLS));
			}
		}
		if (calls == null) return Step.done(reply, reply.get(K_CONTENT)).report();

		frameTools.turnText = RT.ensureString(reply.get(K_CONTENT));
		frameTools.appendTurn(reply);
		StepSink sink = new StepSink(frameTools);
		ToolCycleEngine.BatchResult batch = ToolCycleEngine.executeBatch(
			calls, 0, frameTools.registry(), frameTools, sink, log);
		AVector<ACell> turns = Vectors.of((ACell) reply).concat(sink.turns());

		ACell task = frameTools.tasks.message();
		ContextAssembler.Spec next;
		boolean compacted = frameTools.pendingCompactSummary != null;
		if (compacted) {
			frameTools.activeFrame = GoalTreeContext.compactFrame(
				frameTools.activeFrame, frameTools.pendingCompactSummary);
			next = p.spec().afterCompaction(frameTools.store.frames().assoc(0, frameTools.activeFrame));
		} else {
			next = p.spec().withToolLoop(turns);
		}
		Loads.Snapshot loads = frameTools.refreshLoadSnapshot();
		AVector<ACell> tools = concatTools(p.fixedTools(), loads.tools());
		next = next
			.withLoads(loads, tools)
			.withNotice(replyNotice(task, tools))
			.withTask(task);
		next = observeStep(next, loads, compacted);
		return new Step(reply, turns, sink, batch.terminalStatus(), batch.terminalValue(), null, next).report();
	}

	/**
	 * The inspection-only step path has no durable CAS. Reuse the same pure
	 * observation transform, then place newly appended observation messages
	 * after this step's tool results. A compacted preview already moved those
	 * results into its frame, so its observations stay there as live runtime
	 * observations do.
	 */
	@SuppressWarnings("unchecked")
	private static ContextAssembler.Spec observeStep(ContextAssembler.Spec spec,
			Loads.Snapshot loads, boolean compacted) {
		AVector<ACell> frames = spec.frames();
		if (frames.isEmpty()) return spec;
		AMap<AString, ACell> frame = (AMap<AString, ACell>) frames.get(0);
		AVector<ACell> before = GoalTreeContext.activeConversation(frame);
		if (before == null) before = Vectors.empty();
		AMap<AString, ACell> observed = GoalTreeContext.applyObservations(frame,
			ContextAssembler.observations(spec, loads),
			convex.core.util.Utils.getCurrentTimestamp());
		if (compacted) return spec.withFrames(frames.assoc(0, observed));

		AVector<ACell> after = GoalTreeContext.activeConversation(observed);
		AVector<ACell> appended = (after != null && after.count() > before.count())
			? (AVector<ACell>) after.slice(before.count(), after.count()) : Vectors.empty();
		AMap<AString, ACell> stateOnly = GoalTreeContext.observationState(frame, observed);
		return spec.withFrames(frames.assoc(0, stateOnly))
			.withToolLoop((AVector<ACell>) spec.toolLoop().concat(appended));
	}

	/**
	 * Core transition function logic.
	 *
	 * <p>Builds conversation history, invokes level 3 (with tool call loop),
	 * and returns the updated state.</p>
	 *
	 * @param ctx Request context (caller identity for level 3 invocation)
	 * @param input Transition input: { agentId, state, tasks, pending, messages, config, newInput, session? }
	 * @return Transition output: { state, response | error }
	 */
	ACell processChat(RequestContext ctx, ACell input) {
		// The cycle record (#392) collects every inference and tool call made
		// below — thread-confined, nothing threaded through the loop — and
		// rides out on the output, or on the failure that ends the cycle.
		CycleRecord.begin(ctx.getCycle());
		try {
			return chat(ctx, input);
		} catch (RuntimeException e) {
			throw CycleRecord.Failure.of(e);
		}
	}

	@SuppressWarnings("unchecked")
	private ACell chat(RequestContext ctx, ACell input) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		// A step runs as its agent, under the agent's authority as it stands now,
		// whoever invoked it and whatever its input's config names.
		RequestContext capsCtx = engine.stepContext(ctx, agentId);
		// The session's pending messages when a session is in scope, else the agent's.
		AVector<ACell> messages = AgentAdapter.effectiveMessages(input);
		AVector<ACell> tasks = (AVector<ACell>) RT.getIn(input, Fields.TASKS);
		AVector<ACell> pending = (AVector<ACell>) RT.getIn(input, Fields.PENDING);

		@SuppressWarnings("unchecked")
		AMap<AString, ACell> recordConfig = (RT.getIn(input, AgentState.KEY_CONFIG) instanceof AMap m) ? m : null;
		ModelSetup model = modelSetup(recordConfig, ctx);

		// Determine if there is real input for the agent
		boolean hasInput = (messages != null && messages.count() > 0)
			|| (tasks != null && tasks.count() > 0)
			|| (pending != null && pending.count() > 0);

		// LLMAgent is the one-frame form of the shared frame runtime: it works on
		// the session the cycle's start claimed and gave this cycle's input,
		// exactly as GoalTree does.
		FrameStore.Opened opened = FrameStore.open(engine, ctx, agentId, input, messages,
			GoalTreeContext.describeTransitionInput(messages, tasks, pending),
			convex.core.util.Utils.getCurrentTimestamp());
		if (opened.failed()) {
			return CycleRecord.endInto(Maps.of(AgentState.KEY_STATE, Maps.empty(), Fields.ERROR, opened.error()));
		}
		FrameStore store = opened.store();

		// Context scope chain (#142): agent config (operator-pinned) → root frame,
		// whose loads are the conversation's own.
		AMap<AString, ACell> configLoads = ContextChain.operatorLoads(
			RT.getIn(recordConfig, Fields.LOADS), "config.loads");

		AVector<ACell> sessionFrames = store.frames();
		AMap<AString, ACell> config = model.config();
		ModelProfile profile = model.profile();
		boolean cachePrefix = promptCaching(profile, config);
		FixedPalette fixed = fixedPalette(recordConfig, ctx,
			sessionFrames, cachePrefix);
		AVector<ACell> fixedTools = fixed.tools();

		// Everything the assembler needs for this cycle; the loop supplies what
		// changes per inference — loads, this cycle's turns, the outstanding task.
		ContextAssembler.Spec spec = new ContextAssembler.Spec(
			engine, ctx, capsCtx, config,
			ContextAssembler.sessionHex(RT.getIn(input, Fields.SESSION, Fields.ID)), null,
			profile.budget(), profile.labels(), profile.toolCalling(),
			fixedTools, null, null, sessionFrames, pending, null, hasInput, null, null,
			fixed.unavailable(), null, null)
			.withSourceConfig(recordConfig)
			.withCachePrefix(cachePrefix);
		FrameTools frameTools = frameTools(spec, tasks, configLoads, fixed, store, false);

		// ctx (uncapped) makes the provider call; the agent's authority flows
		// through frameTools for tool dispatch.
		AVector<ACell> newMessages = invokeWithToolLoop(model, spec, fixedTools, ctx, frameTools);

		// Filter out empty assistant messages (e.g. when LLM produces only <think> tags)
		// to avoid polluting the transcript with useless entries
		AVector<ACell> newMessagesFiltered = Vectors.empty();
		for (long i = 0; i < newMessages.count(); i++) {
			ACell msg = newMessages.get(i);
			if (ROLE_ASSISTANT.equals(RT.getIn(msg, K_ROLE))) {
				AString content = RT.ensureString(RT.getIn(msg, K_CONTENT));
				boolean hasContent = content != null && content.count() > 0;
				boolean hasToolCalls = RT.getIn(msg, K_TOOL_CALLS) instanceof AVector<?> v && v.count() > 0;
				if (!hasContent && !hasToolCalls) continue;
			}
			newMessagesFiltered = newMessagesFiltered.conj(msg);
		}

		// Extract text content from the final assistant message
		ACell lastMsg = newMessages.get(newMessages.count() - 1);
		AString contentText = RT.ensureString(RT.getIn(lastMsg, K_CONTENT));
		String responseText = (contentText != null) ? contentText.toString() : "";

		// The root frame is the sole conversation and dynamic-load record;
		// agent-level state carries nothing for this
		// runtime (config's single home is record.config, #144).
		AMap<AString, ACell> newState = Maps.empty();
		// Lean transition output: emit {state, response | error}. Task
		// completion (if any) is signalled to the framework by the venue op
		// invoked from the complete_task / fail_task tool wrappers, which
		// parks an envelope in deferredCompletions; the run loop drains
		// that map AFTER mergeRunResult to build the cycle's TASK_RESULTS.
		//
		// Default response is the assistant's chat text. If the LLM called
		// complete_task with structured output, that output overrides the
		// chat text in the timeline result (it's the authoritative task
		// answer). If the LLM called fail_task, the error replaces the
		// response entirely.
		AMap<AString, ACell> output = Maps.of(
			AgentState.KEY_STATE, newState,
			Fields.RESPONSE, Strings.create(responseText));
		// Preserve the conventional transition output for direct callers. Session
		// runs have already written these turns live through FrameStore, and the
		// FramesOwning marker prevents the framework from appending them again.
		if (newMessagesFiltered.count() > 1) {
			output = output.assoc(Fields.TURNS,
				newMessagesFiltered.slice(0, newMessagesFiltered.count() - 1));
		}
		if (store instanceof FrameStore.LocalFrameStore) {
			output = output.assoc(Fields.FRAMES, store.frames());
		}
		output = frameTools.tasks.promote(output);
		return CycleRecord.endInto(output);
	}

	/**
	 * The root frame's harness tools for one cycle. The root's loads are the
	 * conversation's own; the agent tier rides along read-only so pinned
	 * ownership can be enforced.
	 *
	 * @param preview whether the tasks are only shown, never resolved
	 */
	private FrameTools frameTools(ContextAssembler.Spec spec, AVector<ACell> tasks,
			AMap<AString, ACell> configLoads, FixedPalette fixed, FrameStore store, boolean preview) {
		long timeoutMs = resolveToolCallTimeoutMs(spec.config());
		FrameTools frameTools = new FrameTools(this, spec,
			new TaskTools.Tasks(engine, spec.capsCtx(), tasks, timeoutMs, preview),
			fixed.toolIndex(), configLoads, timeoutMs, store, 0);
		frameTools.baseToolStart = Math.toIntExact(TaskTools.DEFINITIONS.count() + fixed.harness().count());
		frameTools.baseToolEnd = Math.toIntExact(frameTools.baseToolStart + fixed.baseTools().count());
		frameTools.offered = ToolPalette.names(fixed.tools());
		return frameTools;
	}

	private record FixedPalette(AVector<ACell> baseTools, AVector<ACell> harness,
			AMap<AString, ACell> toolIndex, AVector<ACell> unavailable) {
		@SuppressWarnings("unchecked")
		AVector<ACell> tools() {
			return (AVector<ACell>) TaskTools.DEFINITIONS.concat(harness).concat(baseTools);
		}

		AVector<ACell> provenance() {
			return new ToolPalette.Palette(null, toolIndex, null).provenance();
		}
	}

	/** The immutable tools for a session: task controls, declared harness tools
	 * and configured operations. Skill tools append only when their skill loads. */
	@SuppressWarnings("unchecked")
	private FixedPalette fixedPalette(AMap<AString, ACell> config, RequestContext catalogCtx,
			AVector<ACell> frames,
			boolean cachePrefix) {
		AVector<ACell> harness = HarnessTools.offered(config, HarnessTools.SHARED);
		ContextAssembler.Rendered rendered = ContextAssembler.rendered(
			frames, config, cachePrefix);
		if (rendered != null) {
			return new FixedPalette(rendered.baseTools(), harness,
				rendered.toolIndex(), Vectors.empty());
		}
		ToolPalette.Palette configured = ToolPalette.resolve(
			engine, catalogCtx, config, HARNESS_TOOL_NAMES);
		return new FixedPalette(configured.tools(), harness,
			configured.toolIndex(), configured.unavailable());
	}

	/**
	 * Invokes level 3 with a tool call loop.
	 *
	 * <p>Calls the LLM operation. If the response contains {@code toolCalls},
	 * executes each tool, appends tool result messages, and calls the LLM again.
	 * Repeats until a text-only response or the iteration limit.</p>
	 *
	 * <p>This runtime's harness tools (the task tools and the shared context
	 * tools) are handled here; every other tool name is dispatched as an
	 * operation.</p>
	 *
	 * @return Vector of new messages to append to history (includes any tool call
	 *         assistant messages, tool result messages, and the final text response)
	 */
	@SuppressWarnings("unchecked")
	private AVector<ACell> invokeWithToolLoop(
			ModelSetup model, ContextAssembler.Spec spec,
			AVector<ACell> fixedTools, RequestContext ctx, FrameTools frameTools) {
		AMap<AString, ACell> config = spec.config();
		AVector<ACell> messages = Vectors.empty();
		int maxToolIterations = resolveMaxToolIterations(config);
		ToolCycleEngine.Registry<FrameTools> registry = frameTools.registry();
		@SuppressWarnings("unchecked")
		final AVector<ACell>[] sinkMessages = new AVector[] { messages };
		ToolCycleEngine.BatchSink sink = new ToolCycleEngine.BatchSink() {
			@Override
			public void append(AMap<AString, ACell> message) {
				sinkMessages[0] = sinkMessages[0].conj(message);
				frameTools.appendTurn(normaliseRootTurn(message));
			}
		};
		boolean retriedAfterTruncation = false;

		for (int iteration = 0; iteration < maxToolIterations; iteration++) {
			if (frameTools.pendingCompactSummary != null) {
				spec = spec.afterCompaction(compactRoot(frameTools, frameTools.pendingCompactSummary));
				frameTools.pendingCompactSummary = null;
			}
			AVector<ACell> frames = frameTools.reload();
			// Existing messages stay intact; this cycle's turns and any changed
			// observations extend them.
			Loads.Snapshot loads = frameTools.refreshLoadSnapshot();
			ACell taskMessage = frameTools.tasks.message();
			AVector<ACell> tools = concatTools(fixedTools, loads.tools());
			ContextAssembler.Spec inference = spec
				.withLoads(loads, tools)
				.withFrames(frames)
				.withToolLoop(Vectors.empty())
				.withNotice(replyNotice(taskMessage, tools))
				.withTask(taskMessage);
			if (!frameTools.store.observe(0, ContextAssembler.observations(inference, loads),
					convex.core.util.Utils.getCurrentTimestamp())) {
				throw new JobFailedException(SESSION_SUPERSEDED.formatted("observing context"));
			}
			inference = inference.withFrames(frameTools.reload());
			inference = prepareRendering(inference, frameTools);
			ContextAssembler.Prompt prompt = ContextAssembler.assemble(inference);

			ACell assistant = invokeLevel3(model, prompt, ctx);
			if (isLengthLimited(assistant)) {
				if (retriedAfterTruncation) {
					log.warn("LLM response reached its output token limit again — failing the transition");
					throw new JobFailedException(TRUNCATION_FAILURE_MESSAGE);
				}
				retriedAfterTruncation = true;
				log.warn("LLM response reached its output token limit — retrying once without partial output");
				AMap<AString, ACell> retry = RT.ensureMap(stampTs(truncationRetryTurn()));
				messages = messages.conj(retry);
				appendRootTurn(frameTools, retry);
				iteration--; // truncation recovery is not a tool iteration
				continue;
			}
			AVector<ACell> calls = RT.ensureVector(RT.getIn(assistant, K_TOOL_CALLS));
			boolean hasCalls = calls != null && calls.count() > 0;
			if (!hasCalls && taskMessage != null) {
				AMap<AString, ACell> rewritten = ToolCycleEngine.recogniseTextualControlCall(
					assistant, iteration,
					TaskTools.NAMES);
				if (rewritten != null) {
					log.warn("Assistant emitted a harness control tool as text — honouring it (#215)");
					assistant = rewritten;
					calls = RT.ensureVector(RT.getIn(assistant, K_TOOL_CALLS));
					hasCalls = true;
				}
			}

			if (!hasCalls) {
				// responseFormat is a provider hint here, not a contract: the
				// same judgement as a typed completion, logged rather than enforced.
				AMap<AString, ACell> schema = responseFormatSchema(config);
				AString content = RT.ensureString(RT.getIn(assistant, K_CONTENT));
				if (schema != null && content != null) {
					Completion completion = Completion.of(content, null, schema, null);
					if (!completion.accepted()) log.warn("LLM response schema violation: {}", completion.rejection());
				}
				AMap<AString, ACell> finalTurn = RT.ensureMap(stampTs(assistant));
				appendRootTurn(frameTools, finalTurn);
				return messages.conj(finalTurn);
			}

			AMap<AString, ACell> callTurn = RT.ensureMap(stampTs(assistant));
			messages = messages.conj(callTurn);
			appendRootTurn(frameTools, callTurn);
			frameTools.turnText = RT.ensureString(RT.getIn(assistant, K_CONTENT));
			sinkMessages[0] = messages;
			ToolCycleEngine.executeBatch(calls, iteration, registry, frameTools, sink, log);
			persistRootFrame(frameTools);
			messages = sinkMessages[0];
		}

		log.warn("Tool call loop reached iteration limit ({}) — failing the transition", maxToolIterations);
		throw new JobFailedException("Agent reached the tool-call iteration limit ("
			+ maxToolIterations + ") without completing the task.");
	}

	/** Applies the model's rendering policy before the provider call. The
	 * session CAS replaces a stale cached projection or removes one for an
	 * uncached model, leaving append-only conversation untouched. */
	@SuppressWarnings("unchecked")
	private ContextAssembler.Spec prepareRendering(
			ContextAssembler.Spec spec, FrameTools frameTools) {
		if (frameTools.activeFrame == null) return spec;
		if (!ContextAssembler.renderingUpdateRequired(spec)) return spec;
		ContextAssembler.Rendered rendering = frameTools.rendering(spec);
		if (!frameTools.store.update(frames -> {
			if (frames.isEmpty()) return frames;
			AMap<AString, ACell> root = (AMap<AString, ACell>) frames.get(0);
			AMap<AString, ACell> prepared = ContextAssembler.applyRendering(
				root, spec, rendering);
			return prepared == root ? frames : frames.assoc(0, prepared);
		})) {
			throw new JobFailedException(SESSION_SUPERSEDED.formatted("preparing context"));
		}
		return spec.withFrames(frameTools.reload());
	}

	private static AMap<AString, ACell> normaliseRootTurn(AMap<AString, ACell> turn) {
		if (turn.get(AgentState.K_TURN_TS) == null) {
			turn = turn.assoc(AgentState.K_TURN_TS,
				CVMLong.create(convex.core.util.Utils.getCurrentTimestamp()));
		}
		if (turn.get(AgentState.K_SOURCE) == null) {
			AString role = RT.ensureString(turn.get(AgentState.K_ROLE));
			turn = turn.assoc(AgentState.K_SOURCE,
				ROLE_TOOL.equals(role)
					? AgentState.SOURCE_TOOL : AgentState.SOURCE_TRANSITION);
		}
		return turn;
	}

	private static void appendRootTurn(FrameTools frameTools, AMap<AString, ACell> turn) {
		turn = normaliseRootTurn(turn);
		if (!frameTools.store.appendRoot(turn)) {
			throw new JobFailedException(SESSION_SUPERSEDED.formatted("appending conversation"));
		}
		frameTools.reload();
	}

	/** Commits a completed tool batch's results, events and load changes together. */
	private static void persistRootFrame(FrameTools frameTools) {
		AMap<AString, ACell> root = frameTools.activeFrame;
		if (root == null || !frameTools.store.replace(0, root)) {
			throw new JobFailedException(SESSION_SUPERSEDED.formatted("committing tool results"));
		}
		frameTools.reload();
	}

	/** Applies a requested compaction to the root frame as this cycle last saw it.
	 *  @return the stack after it */
	@SuppressWarnings("unchecked")
	private static AVector<ACell> compactRoot(FrameTools frameTools, String summary) {
		AMap<AString, ACell> expected = frameTools.activeFrame;
		if (!frameTools.store.update(frames -> {
			if (frames.isEmpty()) return frames;
			AMap<AString, ACell> root = (AMap<AString, ACell>) frames.get(0);
			if (!root.equals(expected)) return null;
			return frames.assoc(0, GoalTreeContext.compactFrame(root, summary));
		})) {
			throw new JobFailedException(SESSION_SUPERSEDED.formatted("compacting conversation"));
		}
		return frameTools.reload();
	}

	/** @see TaskTools#renderTaskText */
	static String renderTaskText(ACell input) {
		return TaskTools.renderTaskText(input);
	}

	/**
	 * Fallback recognition of control tools emitted as plain TEXT (#215):
	 * smaller models frequently write {@code complete_task {"result": ...}} /
	 * {@code fail_task {"error": ...}} as assistant text even when the tools
	 * are offered structurally. Without recognition each such turn burns a
	 * loop iteration and the task never resolves.
	 *
	 * <p>Recognised only when the text starts with the tool name and the
	 * remainder is blank or a parseable JSON object — prose that merely
	 * mentions a tool name is left alone. A bare tool name gets empty
	 * arguments so the tool's own validation surfaces what's missing.</p>
	 *
	 * @return an assistant message carrying a synthetic toolCall (original
	 *         text preserved as content), or null when the text is not a
	 *         recognisable control-tool emission
	 */
	static AMap<AString, ACell> recogniseTextualControlCall(ACell assistantMsg, int iteration) {
		return ToolCycleEngine.recogniseTextualControlCall(assistantMsg, iteration,
			TaskTools.NAMES);
	}

	static AString getConfigValue(AMap<AString, ACell> config, AString key, AString defaultValue) {
		if (config == null) return defaultValue;
		AString val = RT.ensureString(config.get(key));
		return (val != null) ? val : defaultValue;
	}

	/**
	 * The reply notice when this turn is answered by its final message: a
	 * conversation, not a task (whose message says to resolve it with
	 * complete_task), and only where there are tools to write text alongside.
	 */
	private static String replyNotice(ACell task, AVector<ACell> tools) {
		return (task == null && tools != null && !tools.isEmpty()) ? ContextAssembler.REPLY_NOTICE : null;
	}

	@SuppressWarnings("unchecked")
	private static AVector<ACell> concatTools(AVector<ACell> fixed, AVector<ACell> loads) {
		return (AVector<ACell>) fixed.concat(loads);
	}
}
